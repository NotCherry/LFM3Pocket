package com.notcherry.lfm3pocket;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Android SAF ({@link DocumentsContract}) implementation of {@link WorkspaceGateway}.
 * Operates strictly within the user-picked persisted document tree URI (e.g. an Obsidian vault,
 * Syncthing folder, or local Notes folder) — 100% offline with zero MANAGE_EXTERNAL_STORAGE.
 */
public final class AndroidWorkspaceGateway implements WorkspaceGateway {
    private static final long MAX_READ_BYTES = 512L * 1024L;
    private static final int MAX_READ_CHARS = 12_000;
    private static final int MAX_SEARCH_FILES_SCANNED = 200;

    private static final String[] PROJECTION = new String[] {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
    };

    private static final class DocNode {
        final String docId;
        final String name;
        final String mime;
        final long size;

        DocNode(String docId, String name, String mime, long size) {
            this.docId = docId;
            this.name = name == null ? "" : name;
            this.mime = mime == null ? "" : mime;
            this.size = Math.max(0L, size);
        }

        boolean isDir() {
            return DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
        }
    }

    private final ContentResolver resolver;
    private final Uri treeUri;
    private final String rootDocId;

    public AndroidWorkspaceGateway(Context context, Uri treeUri) {
        if (context == null || treeUri == null) {
            throw new IllegalArgumentException("Missing context or workspace tree URI");
        }
        this.resolver = context.getApplicationContext().getContentResolver();
        this.treeUri = treeUri;
        this.rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
    }

    /** Resolves a human-readable display label for a SAF tree URI. */
    public static String queryTreeLabel(Context context, Uri treeUri) {
        if (context == null || treeUri == null) return "";
        try {
            String rootId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId);
            try (Cursor cursor = context.getContentResolver().query(
                    docUri, new String[] { DocumentsContract.Document.COLUMN_DISPLAY_NAME },
                    null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    String name = cursor.getString(0);
                    if (name != null && !name.trim().isEmpty()) return name.trim();
                }
            }
            if (rootId != null && !rootId.isEmpty()) {
                int slash = rootId.lastIndexOf('/');
                int colon = rootId.lastIndexOf(':');
                int cut = Math.max(slash, colon);
                if (cut >= 0 && cut + 1 < rootId.length()) return rootId.substring(cut + 1);
                return rootId;
            }
        } catch (RuntimeException ignored) {}
        return treeUri.getLastPathSegment() == null ? "Workspace" : treeUri.getLastPathSegment();
    }

    @Override public List<Entry> list(String relativePath, int maxDepth, int maxEntries) throws IOException {
        List<String> segments = WorkspacePath.parseSegments(relativePath, true);
        String startDocId = resolveDirectoryDocId(segments);
        if (startDocId == null) {
            throw new IOException("Folder not found: " + relativePath);
        }
        String prefix = WorkspacePath.normalize(relativePath, true);
        List<Entry> results = new ArrayList<>();
        listRecursive(startDocId, prefix, 1, Math.max(1, maxDepth), Math.max(1, maxEntries), results);
        return results;
    }

    private void listRecursive(String dirDocId, String prefix, int depth, int maxDepth,
                               int maxEntries, List<Entry> out) {
        if (out.size() >= maxEntries) return;
        List<DocNode> children = listChildren(dirDocId);
        for (DocNode child : children) {
            if (out.size() >= maxEntries) return;
            if (WorkspacePath.TRASH_DIR.equals(child.name)) continue;
            String rel = prefix.isEmpty() ? child.name : prefix + "/" + child.name;
            out.add(new Entry(rel, child.isDir(), child.size));
            if (child.isDir() && depth < maxDepth && out.size() < maxEntries) {
                listRecursive(child.docId, rel, depth + 1, maxDepth, maxEntries, out);
            }
        }
    }

    @Override public List<SearchMatch> search(String query, String relativePath, int maxMatches) throws IOException {
        List<String> segments = WorkspacePath.parseSegments(relativePath, true);
        String startDocId = resolveDirectoryDocId(segments);
        if (startDocId == null) {
            throw new IOException("Folder not found: " + relativePath);
        }
        String needle = query.toLowerCase(Locale.ROOT);
        String prefix = WorkspacePath.normalize(relativePath, true);
        List<SearchMatch> matches = new ArrayList<>();
        int[] scanned = new int[] { 0 };
        searchRecursive(startDocId, prefix, needle, Math.max(1, maxMatches), scanned, matches);
        return matches;
    }

    private void searchRecursive(String dirDocId, String prefix, String needle, int maxMatches,
                                 int[] scanned, List<SearchMatch> matches) {
        if (matches.size() >= maxMatches || scanned[0] >= MAX_SEARCH_FILES_SCANNED) return;
        for (DocNode child : listChildren(dirDocId)) {
            if (matches.size() >= maxMatches || scanned[0] >= MAX_SEARCH_FILES_SCANNED) return;
            if (WorkspacePath.TRASH_DIR.equals(child.name) || ".git".equals(child.name)) continue;
            String rel = prefix.isEmpty() ? child.name : prefix + "/" + child.name;
            if (child.isDir()) {
                searchRecursive(child.docId, rel, needle, maxMatches, scanned, matches);
                continue;
            }
            scanned[0]++;
            if (rel.toLowerCase(Locale.ROOT).contains(needle)) {
                matches.add(new SearchMatch(rel, 0, "[filename match]"));
                if (matches.size() >= maxMatches) return;
            }
            if (child.size > MAX_READ_BYTES || !isSearchableText(child)) continue;
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.docId);
            try (InputStream in = resolver.openInputStream(docUri)) {
                if (in == null) continue;
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                int lineNo = 0;
                int fileHits = 0;
                while ((line = reader.readLine()) != null && lineNo < 5000) {
                    lineNo++;
                    if (line.toLowerCase(Locale.ROOT).contains(needle)) {
                        String preview = line.trim();
                        if (preview.length() > 140) preview = preview.substring(0, 137) + "…";
                        matches.add(new SearchMatch(rel, lineNo, preview));
                        fileHits++;
                        if (matches.size() >= maxMatches || fileHits >= 4) break;
                    }
                }
            } catch (IOException | SecurityException ignored) {}
        }
    }

    @Override public ReadResult read(String relativePath, int startLine, int maxLines) throws IOException {
        List<String> segments = WorkspacePath.parseSegments(relativePath, false);
        DocNode node = resolveNode(segments);
        if (node == null || node.isDir()) {
            throw new IOException("File not found: " + relativePath);
        }
        if (node.size > MAX_READ_BYTES) {
            throw new IOException("File exceeds 512 KB read limit (" + node.size + " bytes)");
        }
        String text = readDocumentText(node.docId);
        return formatReadResult(WorkspacePath.normalize(relativePath, false), text, startLine, maxLines);
    }

    @Override public String createFile(String relativePath, String content) throws IOException {
        List<String> segments = WorkspacePath.parseSegments(relativePath, false);
        if (resolveNode(segments) != null) {
            throw new IOException("File already exists: " + relativePath + " (use fs_append or fs_edit instead)");
        }
        String parentDocId = ensureParentDirectories(segments);
        String filename = segments.get(segments.size() - 1);
        String mime = filename.toLowerCase(Locale.ROOT).endsWith(".md") ? "text/markdown" : "text/plain";
        Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId);
        Uri createdUri = DocumentsContract.createDocument(resolver, parentUri, mime, filename);
        if (createdUri == null) {
            throw new IOException("Android SAF refused to create file: " + relativePath);
        }
        writeDocumentText(createdUri, content);
        return WorkspacePath.normalize(relativePath, false);
    }

    @Override public String appendFile(String relativePath, String content) throws IOException {
        List<String> segments = WorkspacePath.parseSegments(relativePath, false);
        DocNode node = resolveNode(segments);
        if (node == null || node.isDir()) {
            throw new IOException("File not found: " + relativePath);
        }
        if (node.size > MAX_READ_BYTES) {
            throw new IOException("File exceeds 512 KB edit limit");
        }
        String existing = readDocumentText(node.docId);
        StringBuilder updated = new StringBuilder(existing);
        if (updated.length() > 0 && updated.charAt(updated.length() - 1) != '\n') {
            updated.append('\n');
        }
        updated.append(content);
        if (!content.endsWith("\n")) updated.append('\n');
        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, node.docId);
        writeDocumentText(docUri, updated.toString());
        return WorkspacePath.normalize(relativePath, false);
    }

    @Override public EditResult editFile(String relativePath, String oldText, String newText) throws IOException {
        List<String> segments = WorkspacePath.parseSegments(relativePath, false);
        DocNode node = resolveNode(segments);
        if (node == null || node.isDir()) {
            throw new IOException("File not found: " + relativePath);
        }
        if (node.size > MAX_READ_BYTES) {
            throw new IOException("File exceeds 512 KB edit limit");
        }
        String existing = readDocumentText(node.docId);
        int first = existing.indexOf(oldText);
        if (first < 0) {
            throw new IOException("old_text was not found in " + relativePath);
        }
        int second = existing.indexOf(oldText, first + oldText.length());
        if (second >= 0) {
            throw new IOException("old_text matched multiple places in " + relativePath
                    + "; provide more surrounding context so the edit is unique");
        }
        String updated = existing.substring(0, first) + newText + existing.substring(first + oldText.length());
        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, node.docId);
        writeDocumentText(docUri, updated);
        return new EditResult(WorkspacePath.normalize(relativePath, false), 1);
    }

    @Override public String deleteFile(String relativePath) throws IOException {
        List<String> segments = WorkspacePath.parseSegments(relativePath, false);
        DocNode node = resolveNode(segments);
        if (node == null || node.isDir()) {
            throw new IOException("File not found: " + relativePath);
        }
        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, node.docId);
        boolean deleted = DocumentsContract.deleteDocument(resolver, docUri);
        if (!deleted) {
            throw new IOException("Android SAF could not delete " + relativePath);
        }
        return "Deleted " + WorkspacePath.normalize(relativePath, false);
    }

    private String resolveDirectoryDocId(List<String> segments) {
        if (segments.isEmpty()) return rootDocId;
        DocNode node = resolveNode(segments);
        return (node != null && node.isDir()) ? node.docId : null;
    }

    private DocNode resolveNode(List<String> segments) {
        String currentId = rootDocId;
        DocNode current = null;
        for (int i = 0; i < segments.size(); i++) {
            String target = segments.get(i);
            DocNode found = findChild(currentId, target);
            if (found == null) return null;
            current = found;
            currentId = found.docId;
            if (i + 1 < segments.size() && !found.isDir()) return null;
        }
        return current;
    }

    private String ensureParentDirectories(List<String> segments) throws IOException {
        String currentId = rootDocId;
        for (int i = 0; i < segments.size() - 1; i++) {
            String folderName = segments.get(i);
            DocNode existing = findChild(currentId, folderName);
            if (existing != null) {
                if (!existing.isDir()) {
                    throw new IOException("Path component is a file, not a folder: " + folderName);
                }
                currentId = existing.docId;
            } else {
                Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, currentId);
                Uri createdDir = DocumentsContract.createDocument(
                        resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, folderName);
                if (createdDir == null) {
                    throw new IOException("Could not create folder: " + folderName);
                }
                currentId = DocumentsContract.getDocumentId(createdDir);
            }
        }
        return currentId;
    }

    private DocNode findChild(String dirDocId, String exactName) {
        for (DocNode child : listChildren(dirDocId)) {
            if (exactName.equals(child.name)) return child;
        }
        return null;
    }

    private List<DocNode> listChildren(String dirDocId) {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId);
        List<DocNode> result = new ArrayList<>();
        try (Cursor cursor = resolver.query(childrenUri, PROJECTION, null, null, null)) {
            if (cursor == null) return result;
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                long size = cursor.isNull(3) ? 0L : cursor.getLong(3);
                if (id != null && name != null) {
                    result.add(new DocNode(id, name, mime, size));
                }
            }
        } catch (RuntimeException ignored) {}
        result.sort(Comparator.comparing((DocNode d) -> !d.isDir()).thenComparing(d -> d.name.toLowerCase(Locale.ROOT)));
        return result;
    }

    private String readDocumentText(String docId) throws IOException {
        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
        try (InputStream in = resolver.openInputStream(docUri)) {
            if (in == null) throw new IOException("Could not open input stream");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int total = 0;
            int n;
            while ((n = in.read(chunk)) != -1) {
                if (total + n > MAX_READ_BYTES) {
                    throw new IOException("File exceeds 512 KB read limit");
                }
                buf.write(chunk, 0, n);
                total += n;
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void writeDocumentText(Uri docUri, String content) throws IOException {
        // "wt" truncates cleanly across Storage Access Framework providers.
        try (OutputStream out = resolver.openOutputStream(docUri, "wt")) {
            if (out == null) throw new IOException("Could not open output stream for writing");
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private static boolean isSearchableText(DocNode child) {
        if (child.mime.startsWith("text/")) return true;
        return WorkspacePath.isReadableTextExtension(child.name);
    }

    static ReadResult formatReadResult(String normalizedPath, String fullText, int startLine, int maxLines) {
        String[] lines = fullText.isEmpty() ? new String[0] : fullText.split("\\r?\\n", -1);
        int total = lines.length;
        int start = Math.max(1, startLine);
        int count = Math.max(1, Math.min(maxLines, 300));
        if (total == 0 || start > total) {
            return new ReadResult(normalizedPath, start, Math.max(start, total), total, false, "");
        }
        int end = Math.min(total, start + count - 1);
        StringBuilder out = new StringBuilder();
        boolean truncated = end < total;
        int actualEnd = start;
        for (int line = start; line <= end; line++) {
            String row = line + ": " + lines[line - 1];
            if (out.length() + row.length() + 1 > MAX_READ_CHARS && out.length() > 0) {
                truncated = true;
                break;
            }
            if (out.length() > 0) out.append('\n');
            out.append(row);
            actualEnd = line;
        }
        return new ReadResult(normalizedPath, start, actualEnd, total, truncated, out.toString());
    }
}

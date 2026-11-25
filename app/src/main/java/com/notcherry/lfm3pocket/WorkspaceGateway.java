package com.notcherry.lfm3pocket;

import java.io.IOException;
import java.util.List;

/**
 * Offline local workspace abstraction backed by Android SAF ({@link AndroidWorkspaceGateway})
 * in production and a temp directory ({@link FileWorkspaceGateway}) in JVM unit tests.
 */
public interface WorkspaceGateway {
    final class Entry {
        public final String relativePath;
        public final boolean directory;
        public final long sizeBytes;

        public Entry(String relativePath, boolean directory, long sizeBytes) {
            this.relativePath = relativePath == null ? "" : relativePath;
            this.directory = directory;
            this.sizeBytes = Math.max(0L, sizeBytes);
        }
    }

    final class SearchMatch {
        public final String relativePath;
        public final int lineNumber;
        public final String preview;

        public SearchMatch(String relativePath, int lineNumber, String preview) {
            this.relativePath = relativePath == null ? "" : relativePath;
            this.lineNumber = lineNumber;
            this.preview = preview == null ? "" : preview;
        }
    }

    final class ReadResult {
        public final String relativePath;
        public final int startLine;
        public final int endLine;
        public final int totalLines;
        public final boolean truncated;
        public final String numberedContent;

        public ReadResult(String relativePath, int startLine, int endLine,
                          int totalLines, boolean truncated, String numberedContent) {
            this.relativePath = relativePath;
            this.startLine = startLine;
            this.endLine = endLine;
            this.totalLines = totalLines;
            this.truncated = truncated;
            this.numberedContent = numberedContent == null ? "" : numberedContent;
        }
    }

    final class EditResult {
        public final String relativePath;
        public final int replacedCount;

        public EditResult(String relativePath, int replacedCount) {
            this.relativePath = relativePath;
            this.replacedCount = replacedCount;
        }
    }

    List<Entry> list(String relativePath, int maxDepth, int maxEntries) throws IOException;

    List<SearchMatch> search(String query, String relativePath, int maxMatches) throws IOException;

    ReadResult read(String relativePath, int startLine, int maxLines) throws IOException;

    String createFile(String relativePath, String content) throws IOException;

    String appendFile(String relativePath, String content) throws IOException;

    EditResult editFile(String relativePath, String oldText, String newText) throws IOException;

    String deleteFile(String relativePath) throws IOException;
}

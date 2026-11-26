package com.notcherry.lfm3pocket;

import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Executes coding-harness style local workspace tools ({@code fs_list}, {@code fs_search},
 * {@code fs_read}, {@code note_create}, {@code fs_append}, {@code fs_edit}, {@code fs_delete})
 * strictly inside the user's SAF-mounted folder.
 */
public final class WorkspaceActionExecutor implements AndroidActionExecutor {
    private static final int MAX_LIST_ENTRIES = 120;
    private static final int MAX_SEARCH_MATCHES = 30;

    private final BooleanSupplier workspaceConfigured;
    private final Supplier<WorkspaceGateway> gatewaySupplier;
    private final Supplier<ZonedDateTime> clock;
    private final Supplier<String> workspaceLabelSupplier;

    public WorkspaceActionExecutor(BooleanSupplier workspaceConfigured,
                                   Supplier<WorkspaceGateway> gatewaySupplier) {
        this(workspaceConfigured, gatewaySupplier, ZonedDateTime::now, () -> "");
    }

    public WorkspaceActionExecutor(BooleanSupplier workspaceConfigured,
                                   Supplier<WorkspaceGateway> gatewaySupplier,
                                   Supplier<ZonedDateTime> clock) {
        this(workspaceConfigured, gatewaySupplier, clock, () -> "");
    }

    public WorkspaceActionExecutor(BooleanSupplier workspaceConfigured,
                                   Supplier<WorkspaceGateway> gatewaySupplier,
                                   Supplier<ZonedDateTime> clock,
                                   Supplier<String> workspaceLabelSupplier) {
        if (workspaceConfigured == null || gatewaySupplier == null) {
            throw new IllegalArgumentException("Missing workspace executor dependencies");
        }
        this.workspaceConfigured = workspaceConfigured;
        this.gatewaySupplier = gatewaySupplier;
        this.clock = clock == null ? ZonedDateTime::now : clock;
        this.workspaceLabelSupplier = workspaceLabelSupplier == null ? () -> "" : workspaceLabelSupplier;
    }

    @Override public ActionResult execute(String approvedToolName, Map<String, String> arguments) {
        if (!workspaceConfigured.getAsBoolean()) {
            return new ActionResult(false, "WORKSPACE_NOT_CONFIGURED",
                    "No local workspace folder is selected. Pick a folder in Settings → Local Workspace.");
        }
        WorkspaceGateway gateway;
        try {
            gateway = gatewaySupplier.get();
        } catch (RuntimeException e) {
            return new ActionResult(false, "WORKSPACE_UNAVAILABLE",
                    "Local workspace is unavailable: " + safeMessage(e));
        }
        if (gateway == null) {
            return new ActionResult(false, "WORKSPACE_NOT_CONFIGURED",
                    "No local workspace folder is selected. Pick a folder in Settings → Local Workspace.");
        }
        if ("fs_list".equals(approvedToolName)) return fsList(gateway, arguments);
        if ("fs_search".equals(approvedToolName)) return fsSearch(gateway, arguments);
        if ("fs_read".equals(approvedToolName)) return fsRead(gateway, arguments);
        if ("note_create".equals(approvedToolName)) return noteCreate(gateway, arguments);
        if ("fs_append".equals(approvedToolName)) return fsAppend(gateway, arguments);
        if ("fs_edit".equals(approvedToolName)) return fsEdit(gateway, arguments);
        if ("fs_delete".equals(approvedToolName)) return fsDelete(gateway, arguments);
        return new ActionResult(false, "UNKNOWN_TOOL", "This workspace tool is not enabled.");
    }

    private ActionResult fsList(WorkspaceGateway gateway, Map<String, String> args) {
        String path = args == null ? "" : nonNull(args.get("path"));
        int maxDepth = parseIntOrDefault(args == null ? null : args.get("max_depth"), 2, 1, 4);
        try {
            String normalized = WorkspacePath.normalize(path, true);
            List<WorkspaceGateway.Entry> entries;
            String effectivePath = normalized;
            try {
                entries = gateway.list(normalized, maxDepth, MAX_LIST_ENTRIES);
            } catch (IOException primaryError) {
                String fallback = fallbackStrippingRootLabel(normalized, true);
                if (fallback == null) throw primaryError;
                entries = gateway.list(fallback, maxDepth, MAX_LIST_ENTRIES);
                effectivePath = fallback;
            }
            String location = formatLocation(effectivePath);
            if (entries.isEmpty()) {
                return new ActionResult(true, "FS_LIST_OK",
                        "UNTRUSTED_WORKSPACE Folder is empty: " + location);
            }
            StringBuilder out = new StringBuilder("UNTRUSTED_WORKSPACE Listing ");
            out.append(location).append(" (").append(entries.size()).append(" entries)");
            for (WorkspaceGateway.Entry entry : entries) {
                out.append('\n').append(entry.directory ? "[dir] " : "[file] ").append(entry.relativePath);
                if (!entry.directory) out.append(" (").append(entry.sizeBytes).append(" B)");
            }
            return new ActionResult(true, "FS_LIST_OK", out.toString());
        } catch (IllegalArgumentException e) {
            return new ActionResult(false, "INVALID_ARGUMENTS", e.getMessage());
        } catch (SecurityException e) {
            return new ActionResult(false, "FS_LIST_FAILED",
                    "Workspace folder permission expired (" + safeMessage(e)
                            + "). Re-pick the folder in Settings → Local Workspace.");
        } catch (IOException e) {
            return new ActionResult(false, "FS_LIST_FAILED", "Could not list workspace folder: " + safeMessage(e));
        }
    }

    private ActionResult fsSearch(WorkspaceGateway gateway, Map<String, String> args) {
        String query = args == null ? "" : nonNull(args.get("query")).trim();
        String path = args == null ? "" : nonNull(args.get("path"));
        if (query.isEmpty()) {
            return new ActionResult(false, "INVALID_ARGUMENTS", "query is required");
        }
        // Small models sometimes call fs_search(query="*") or query="." when trying to list files.
        if ("*".equals(query) || "*.*".equals(query) || ".".equals(query) || "/".equals(query)) {
            return fsList(gateway, args);
        }
        try {
            String normalized = WorkspacePath.normalize(path, true);
            List<WorkspaceGateway.SearchMatch> matches;
            try {
                matches = gateway.search(query, normalized, MAX_SEARCH_MATCHES);
            } catch (IOException primaryError) {
                String fallback = fallbackStrippingRootLabel(normalized, true);
                if (fallback == null) throw primaryError;
                matches = gateway.search(query, fallback, MAX_SEARCH_MATCHES);
            }
            if (matches.isEmpty()) {
                return new ActionResult(true, "FS_SEARCH_OK",
                        "UNTRUSTED_WORKSPACE No files or lines matched \"" + query + "\" in " + formatLocation(normalized));
            }
            StringBuilder out = new StringBuilder("UNTRUSTED_WORKSPACE Found ");
            out.append(matches.size()).append(" match(es) for \"").append(query).append('"');
            for (WorkspaceGateway.SearchMatch match : matches) {
                out.append('\n').append(match.relativePath);
                if (match.lineNumber > 0) out.append(':').append(match.lineNumber);
                if (!match.preview.isEmpty()) out.append(" — ").append(match.preview);
            }
            return new ActionResult(true, "FS_SEARCH_OK", out.toString());
        } catch (IllegalArgumentException e) {
            return new ActionResult(false, "INVALID_ARGUMENTS", e.getMessage());
        } catch (SecurityException e) {
            return new ActionResult(false, "FS_SEARCH_FAILED",
                    "Workspace folder permission expired (" + safeMessage(e)
                            + "). Re-pick the folder in Settings → Local Workspace.");
        } catch (IOException e) {
            return new ActionResult(false, "FS_SEARCH_FAILED", "Workspace search failed: " + safeMessage(e));
        }
    }

    private ActionResult fsRead(WorkspaceGateway gateway, Map<String, String> args) {
        String path = args == null ? "" : nonNull(args.get("path"));
        int startLine = parseIntOrDefault(args == null ? null : args.get("start_line"), 1, 1, 100000);
        int maxLines = parseIntOrDefault(args == null ? null : args.get("max_lines"), 120, 1, 300);
        try {
            String normalized = WorkspacePath.normalize(path, false);
            WorkspaceGateway.ReadResult read;
            try {
                read = gateway.read(normalized, startLine, maxLines);
            } catch (IOException primaryError) {
                String fallback = fallbackStrippingRootLabel(normalized, false);
                if (fallback == null) throw primaryError;
                read = gateway.read(fallback, startLine, maxLines);
            }
            StringBuilder out = new StringBuilder("UNTRUSTED_WORKSPACE File ");
            out.append(read.relativePath)
                    .append(" (lines ").append(read.startLine).append('-').append(read.endLine)
                    .append(" of ").append(read.totalLines).append(")");
            if (read.truncated) out.append(" [truncated]");
            out.append('\n').append(read.numberedContent);
            return new ActionResult(true, "FS_READ_OK", out.toString());
        } catch (IllegalArgumentException e) {
            return new ActionResult(false, "INVALID_ARGUMENTS", e.getMessage());
        } catch (SecurityException e) {
            return new ActionResult(false, "FS_READ_FAILED",
                    "Workspace folder permission expired (" + safeMessage(e)
                            + "). Re-pick the folder in Settings → Local Workspace.");
        } catch (IOException e) {
            return new ActionResult(false, "FS_READ_FAILED", "Could not read file: " + safeMessage(e));
        }
    }

    private ActionResult noteCreate(WorkspaceGateway gateway, Map<String, String> args) {
        String title = args == null ? "" : nonNull(args.get("title")).trim();
        String content = args == null ? "" : nonNull(args.get("content"));
        String folder = args == null ? "" : nonNull(args.get("folder")).trim();
        String tagsRaw = args == null ? "" : nonNull(args.get("tags"));
        if (title.isEmpty()) return new ActionResult(false, "INVALID_ARGUMENTS", "title is required");
        if (content.trim().isEmpty()) return new ActionResult(false, "INVALID_ARGUMENTS", "content is required");
        try {
            String normalizedFolder = WorkspacePath.normalize(folder, true);
            String strippedFolder = fallbackStrippingRootLabel(normalizedFolder, true);
            if (strippedFolder != null) normalizedFolder = strippedFolder;
            String filename = WorkspacePath.noteFilename(title);
            String targetPath = normalizedFolder.isEmpty() ? filename : normalizedFolder + "/" + filename;
            String markdown = WorkspacePath.formatMarkdownNote(
                    title, content, WorkspacePath.parseTags(tagsRaw), clock.get());
            String created = gateway.createFile(targetPath, markdown);
            return new ActionResult(true, "NOTE_CREATED", "UNTRUSTED_WORKSPACE Created note " + created);
        } catch (IllegalArgumentException e) {
            return new ActionResult(false, "INVALID_ARGUMENTS", e.getMessage());
        } catch (IOException | SecurityException e) {
            return new ActionResult(false, "NOTE_CREATE_FAILED", "Could not create note: " + safeMessage(e));
        }
    }

    private ActionResult fsAppend(WorkspaceGateway gateway, Map<String, String> args) {
        String path = args == null ? "" : nonNull(args.get("path"));
        String content = args == null ? "" : nonNull(args.get("content"));
        if (content.isEmpty()) return new ActionResult(false, "INVALID_ARGUMENTS", "content is required");
        try {
            String normalized = WorkspacePath.normalize(path, false);
            String updated;
            try {
                updated = gateway.appendFile(normalized, content);
            } catch (IOException primaryError) {
                String fallback = fallbackStrippingRootLabel(normalized, false);
                if (fallback == null) throw primaryError;
                updated = gateway.appendFile(fallback, content);
            }
            return new ActionResult(true, "FS_APPENDED", "UNTRUSTED_WORKSPACE Appended to " + updated);
        } catch (IllegalArgumentException e) {
            return new ActionResult(false, "INVALID_ARGUMENTS", e.getMessage());
        } catch (IOException | SecurityException e) {
            return new ActionResult(false, "FS_APPEND_FAILED", "Could not append to file: " + safeMessage(e));
        }
    }

    private ActionResult fsEdit(WorkspaceGateway gateway, Map<String, String> args) {
        String path = args == null ? "" : nonNull(args.get("path"));
        String oldText = args == null ? "" : nonNull(args.get("old_text"));
        String newText = args == null ? "" : nonNull(args.get("new_text"));
        if (oldText.isEmpty()) return new ActionResult(false, "INVALID_ARGUMENTS", "old_text is required");
        try {
            String normalized = WorkspacePath.normalize(path, false);
            WorkspaceGateway.EditResult res;
            try {
                res = gateway.editFile(normalized, oldText, newText);
            } catch (IOException primaryError) {
                String fallback = fallbackStrippingRootLabel(normalized, false);
                if (fallback == null) throw primaryError;
                res = gateway.editFile(fallback, oldText, newText);
            }
            return new ActionResult(true, "FS_EDITED",
                    "UNTRUSTED_WORKSPACE Updated " + res.relativePath + " (" + res.replacedCount + " replacement)");
        } catch (IllegalArgumentException e) {
            return new ActionResult(false, "INVALID_ARGUMENTS", e.getMessage());
        } catch (IOException | SecurityException e) {
            return new ActionResult(false, "FS_EDIT_FAILED", "Could not edit file: " + safeMessage(e));
        }
    }

    private ActionResult fsDelete(WorkspaceGateway gateway, Map<String, String> args) {
        String path = args == null ? "" : nonNull(args.get("path"));
        try {
            String normalized = WorkspacePath.normalize(path, false);
            String detail;
            try {
                detail = gateway.deleteFile(normalized);
            } catch (IOException primaryError) {
                String fallback = fallbackStrippingRootLabel(normalized, false);
                if (fallback == null) throw primaryError;
                detail = gateway.deleteFile(fallback);
            }
            return new ActionResult(true, "FS_DELETED", "UNTRUSTED_WORKSPACE " + detail);
        } catch (IllegalArgumentException e) {
            return new ActionResult(false, "INVALID_ARGUMENTS", e.getMessage());
        } catch (IOException | SecurityException e) {
            return new ActionResult(false, "FS_DELETE_FAILED", "Could not delete file: " + safeMessage(e));
        }
    }

    private String workspaceLabel() {
        try {
            String label = workspaceLabelSupplier.get();
            return label == null ? "" : label.trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private String fallbackStrippingRootLabel(String normalized, boolean allowEmptyResult) {
        String stripped = WorkspacePath.stripLeadingRootLabel(normalized, workspaceLabel());
        if (stripped == null) return null;
        if (!allowEmptyResult && stripped.isEmpty()) return null;
        return stripped;
    }

    private String formatLocation(String normalized) {
        String label = workspaceLabel();
        if (normalized == null || normalized.isEmpty()) {
            return label.isEmpty() ? "/" : label + " (/)";
        }
        return label.isEmpty() ? normalized : label + "/" + normalized;
    }

    private static int parseIntOrDefault(String raw, int fallback, int min, int max) {
        if (raw == null || raw.trim().isEmpty()) return fallback;
        try {
            int value = Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    private static String safeMessage(Throwable e) {
        String msg = e.getMessage();
        return msg == null || msg.trim().isEmpty() ? e.getClass().getSimpleName() : msg.trim();
    }
}

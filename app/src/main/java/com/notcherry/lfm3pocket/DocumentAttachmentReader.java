package com.notcherry.lfm3pocket;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Reads SAF-selected document bytes into bounded text for prompt context. */
final class DocumentAttachmentReader {
    static final long MAX_TEXT_BYTES = 512L * 1024L;
    static final long MAX_PDF_BYTES = 8L * 1024L * 1024L;
    static final int MAX_EXTRACTED_CHARS = 16000;
    static final int MAX_FILES = 4;

    static final class Result {
        final String displayName;
        final String text;
        final String error;

        private Result(String displayName, String text, String error) {
            this.displayName = displayName == null ? "document" : displayName;
            this.text = text;
            this.error = error;
        }

        static Result ok(String displayName, String text) {
            return new Result(displayName, text, null);
        }

        static Result error(String displayName, String error) {
            return new Result(displayName, null, error);
        }

        boolean isOk() { return error == null; }
    }

    private DocumentAttachmentReader() {}

    static Result read(File file, String displayName, String mimeType) {
        String name = displayName == null || displayName.trim().isEmpty() ? file.getName() : displayName.trim();
        if (file == null || !file.isFile()) {
            return Result.error(name, "Selected file could not be read.");
        }
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        String lowerName = name.toLowerCase(Locale.ROOT);

        if (isPdf(mime, lowerName)) {
            if (file.length() > MAX_PDF_BYTES) {
                return Result.error(name, "This PDF is too large to attach.");
            }
            try {
                String extracted = PdfTextExtractor.extract(file, MAX_EXTRACTED_CHARS);
                if (extracted == null || extracted.trim().isEmpty()) {
                    return Result.error(name,
                            "No extractable text found in this PDF (it may be scanned, image-only, or encrypted). Attach a text file or screenshot instead.");
                }
                return Result.ok(name, extracted.trim());
            } catch (IOException e) {
                return Result.error(name, "Could not read this PDF file.");
            } catch (OutOfMemoryError e) {
                return Result.error(name, "This PDF is too large to attach.");
            }
        }
        if (!isSupportedText(mime, lowerName)) {
            return Result.error(name, "Unsupported file type. Attach a plain text file.");
        }
        if (file.length() > MAX_TEXT_BYTES) {
            return Result.error(name, "This file is too large to attach.");
        }
        try {
            String text = readText(file, MAX_EXTRACTED_CHARS + 1);
            if (text == null || text.trim().isEmpty()) {
                return Result.error(name, "This file does not contain readable text.");
            }
            String trimmed = text.trim();
            if (trimmed.length() > MAX_EXTRACTED_CHARS) {
                trimmed = trimmed.substring(0, MAX_EXTRACTED_CHARS).trim();
            }
            if (looksBinary(trimmed)) {
                return Result.error(name, "Unsupported file type. Attach a plain text file.");
            }
            return Result.ok(name, trimmed);
        } catch (IOException e) {
            return Result.error(name, "Could not read this file.");
        } catch (OutOfMemoryError e) {
            return Result.error(name, "This file is too large to attach.");
        }
    }

    static boolean isPdfMimeOrName(String mimeType, String name) {
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        String lowerName = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return "application/pdf".equals(mime) || lowerName.endsWith(".pdf");
    }

    private static boolean isPdf(String mime, String lowerName) {
        return "application/pdf".equals(mime) || lowerName.endsWith(".pdf");
    }

    private static boolean isSupportedText(String mime, String lowerName) {
        if (mime.startsWith("text/")) return true;
        if ("application/json".equals(mime) || "application/xml".equals(mime)
                || "application/xhtml+xml".equals(mime) || "application/javascript".equals(mime)
                || "application/x-javascript".equals(mime) || "application/yaml".equals(mime)
                || "application/x-yaml".equals(mime) || "application/csv".equals(mime)
                || "application/sql".equals(mime)) {
            return true;
        }
        return lowerName.endsWith(".txt") || lowerName.endsWith(".md") || lowerName.endsWith(".markdown")
                || lowerName.endsWith(".csv") || lowerName.endsWith(".json") || lowerName.endsWith(".xml")
                || lowerName.endsWith(".html") || lowerName.endsWith(".htm") || lowerName.endsWith(".log")
                || lowerName.endsWith(".yaml") || lowerName.endsWith(".yml") || lowerName.endsWith(".ini")
                || lowerName.endsWith(".cfg") || lowerName.endsWith(".conf") || lowerName.endsWith(".properties")
                || lowerName.endsWith(".java") || lowerName.endsWith(".kt") || lowerName.endsWith(".js")
                || lowerName.endsWith(".ts") || lowerName.endsWith(".py") || lowerName.endsWith(".sh")
                || lowerName.endsWith(".c") || lowerName.endsWith(".cpp") || lowerName.endsWith(".h")
                || lowerName.endsWith(".rs") || lowerName.endsWith(".go");
    }

    private static String readText(File file, int maxChars) throws IOException {
        Charset charset = StandardCharsets.UTF_8;
        try (InputStream input = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, charset))) {
            StringBuilder out = new StringBuilder(Math.min(maxChars, 8192));
            char[] buffer = new char[4096];
            int n;
            while ((n = reader.read(buffer)) != -1) {
                int remaining = maxChars - out.length();
                if (remaining <= 0) break;
                out.append(buffer, 0, Math.min(n, remaining));
                if (out.length() >= maxChars) break;
            }
            return out.toString();
        }
    }

    private static boolean looksBinary(String text) {
        int controls = 0;
        int checked = Math.min(text.length(), 512);
        for (int i = 0; i < checked; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') continue;
            if (c < 0x20 || c == 0x7f) controls++;
        }
        return checked > 0 && controls * 10 > checked;
    }
}

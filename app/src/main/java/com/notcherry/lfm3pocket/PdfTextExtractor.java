package com.notcherry.lfm3pocket;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Lightweight pure-Java offline PDF text extractor (zero external dependencies, F-Droid friendly).
 * Supports uncompressed and {@code /FlateDecode} content streams, literal strings {@code (...)},
 * hex strings {@code <...>}, {@code Tj}/{@code TJ}/{@code '}/{@code "} text operators, and basic
 * {@code /ToUnicode} CMap tables with strict memory caps against zip bombs.
 */
public final class PdfTextExtractor {
    private static final int MAX_STREAM_INFLATED_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TOTAL_INFLATED_BYTES = 8 * 1024 * 1024;
    private static final int MAX_STREAMS = 512;

    private PdfTextExtractor() {}

    /**
     * Extracts up to {@code maxChars} of readable text from a PDF file.
     * Returns an empty string if the PDF is encrypted, image-only (scanned), or has no text streams.
     */
    public static String extract(File file, int maxChars) throws IOException {
        if (file == null || !file.isFile() || maxChars <= 0) return "";
        long len = file.length();
        if (len < 16 || len > DocumentAttachmentReader.MAX_PDF_BYTES) return "";
        byte[] data = readAllBytes(file, (int) len);
        return extract(data, maxChars);
    }

    public static String extract(byte[] data, int maxChars) {
        if (data == null || data.length < 16 || maxChars <= 0) return "";
        String latin = new String(data, StandardCharsets.ISO_8859_1);
        if (!latin.startsWith("%PDF-")) return "";
        if (latin.contains("/Encrypt ")) return "";

        Map<Integer, String> toUnicode = new HashMap<>();
        StringBuilder extracted = new StringBuilder(Math.min(maxChars, 4096));
        int totalInflated = 0;
        int streamCount = 0;

        // First pass: collect any ToUnicode CMaps and extract text from content streams.
        // We scan streams twice if CMaps appear later than content streams, storing decoded streams
        // only up to our memory budget.
        java.util.List<String> contentStreams = new java.util.ArrayList<>();
        int cursor = 0;
        while (cursor < data.length && streamCount < MAX_STREAMS && totalInflated < MAX_TOTAL_INFLATED_BYTES) {
            int streamIdx = indexOfToken(latin, "stream", cursor);
            if (streamIdx < 0) break;
            int dictStart = Math.max(0, latin.lastIndexOf("<<", streamIdx));
            String dict = latin.substring(dictStart, streamIdx);
            int bodyStart = streamIdx + 6;
            if (bodyStart < data.length && data[bodyStart] == '\r') bodyStart++;
            if (bodyStart < data.length && data[bodyStart] == '\n') bodyStart++;

            int endIdx = latin.indexOf("endstream", bodyStart);
            if (endIdx < 0) break;
            int bodyEnd = endIdx;
            while (bodyEnd > bodyStart && (data[bodyEnd - 1] == '\n' || data[bodyEnd - 1] == '\r')) {
                bodyEnd--;
            }
            cursor = endIdx + 9;
            streamCount++;

            boolean isFlate = dict.contains("/FlateDecode");
            if (dict.contains("/DCTDecode") || dict.contains("/JPXDecode")
                    || dict.contains("/CCITTFaxDecode") || dict.contains("/Subtype /Image")
                    || dict.contains("/Subtype/Image")) {
                continue;
            }
            byte[] decoded;
            if (isFlate) {
                int remaining = Math.min(MAX_STREAM_INFLATED_BYTES, MAX_TOTAL_INFLATED_BYTES - totalInflated);
                decoded = inflateBounded(data, bodyStart, bodyEnd - bodyStart, remaining);
                if (decoded == null) continue;
                totalInflated += decoded.length;
            } else {
                int rawLen = bodyEnd - bodyStart;
                if (rawLen <= 0 || rawLen > MAX_STREAM_INFLATED_BYTES) continue;
                decoded = new byte[rawLen];
                System.arraycopy(data, bodyStart, decoded, 0, rawLen);
                totalInflated += rawLen;
            }

            String streamText = new String(decoded, StandardCharsets.ISO_8859_1);
            if (streamText.contains("begincmap") || streamText.contains("beginbfchar") || streamText.contains("beginbfrange")) {
                parseCMap(streamText, toUnicode);
            } else if (streamText.contains("BT") && streamText.contains("ET")) {
                contentStreams.add(streamText);
            }
        }

        for (String content : contentStreams) {
            if (extracted.length() >= maxChars) break;
            extractTextOperators(content, toUnicode, extracted, maxChars);
            if (extracted.length() > 0 && extracted.charAt(extracted.length() - 1) != '\n') {
                extracted.append('\n');
            }
        }

        return normalizeWhitespace(extracted.toString(), maxChars);
    }

    private static void parseCMap(String cmap, Map<Integer, String> map) {
        int pos = 0;
        while (pos < cmap.length()) {
            int bfchar = cmap.indexOf("beginbfchar", pos);
            if (bfchar < 0) break;
            int end = cmap.indexOf("endbfchar", bfchar);
            if (end < 0) break;
            String block = cmap.substring(bfchar + 11, end);
            int i = 0;
            while (i < block.length()) {
                int aStart = block.indexOf('<', i);
                if (aStart < 0) break;
                int aEnd = block.indexOf('>', aStart + 1);
                if (aEnd < 0) break;
                int bStart = block.indexOf('<', aEnd + 1);
                if (bStart < 0) break;
                int bEnd = block.indexOf('>', bStart + 1);
                if (bEnd < 0) break;
                i = bEnd + 1;
                Integer src = parseHexInt(block.substring(aStart + 1, aEnd));
                String dst = decodeUnicodeHex(block.substring(bStart + 1, bEnd));
                if (src != null && !dst.isEmpty() && map.size() < 4096) {
                    map.put(src, dst);
                }
            }
            pos = end + 9;
        }
        pos = 0;
        while (pos < cmap.length()) {
            int bfrange = cmap.indexOf("beginbfrange", pos);
            if (bfrange < 0) break;
            int end = cmap.indexOf("endbfrange", bfrange);
            if (end < 0) break;
            String block = cmap.substring(bfrange + 12, end);
            int i = 0;
            while (i < block.length()) {
                int aStart = block.indexOf('<', i);
                if (aStart < 0) break;
                int aEnd = block.indexOf('>', aStart + 1);
                if (aEnd < 0) break;
                int bStart = block.indexOf('<', aEnd + 1);
                if (bStart < 0) break;
                int bEnd = block.indexOf('>', bStart + 1);
                if (bEnd < 0) break;
                int cStart = block.indexOf('<', bEnd + 1);
                if (cStart < 0) break;
                int cEnd = block.indexOf('>', cStart + 1);
                if (cEnd < 0) break;
                i = cEnd + 1;
                Integer lo = parseHexInt(block.substring(aStart + 1, aEnd));
                Integer hi = parseHexInt(block.substring(bStart + 1, bEnd));
                Integer dstStart = parseHexInt(block.substring(cStart + 1, cEnd));
                if (lo != null && hi != null && dstStart != null && hi >= lo && (hi - lo) <= 256) {
                    for (int code = lo; code <= hi && map.size() < 4096; code++) {
                        int cp = dstStart + (code - lo);
                        if (Character.isValidCodePoint(cp)) {
                            map.put(code, new String(Character.toChars(cp)));
                        }
                    }
                }
            }
            pos = end + 10;
        }
    }

    private static void extractTextOperators(String stream, Map<Integer, String> toUnicode,
                                             StringBuilder out, int maxChars) {
        int n = stream.length();
        int i = 0;
        boolean inText = false;
        StringBuilder pendingToken = new StringBuilder();
        while (i < n && out.length() < maxChars) {
            char c = stream.charAt(i);
            if (c == '%') {
                while (i < n && stream.charAt(i) != '\n' && stream.charAt(i) != '\r') i++;
                continue;
            }
            if (c == '(' && inText) {
                StringBuilder lit = new StringBuilder();
                i = readLiteralString(stream, i + 1, lit);
                pendingToken.setLength(0);
                pendingToken.append(lit);
                continue;
            }
            if (c == '<' && inText) {
                if (i + 1 < n && stream.charAt(i + 1) == '<') {
                    i += 2;
                    continue;
                }
                int close = stream.indexOf('>', i + 1);
                if (close > i) {
                    String hex = stream.substring(i + 1, close);
                    pendingToken.setLength(0);
                    pendingToken.append(decodePdfHex(hex, toUnicode));
                    i = close + 1;
                    continue;
                }
            }
            if (c == '[' && inText) {
                StringBuilder arr = new StringBuilder();
                i = readTextArray(stream, i + 1, toUnicode, arr);
                pendingToken.setLength(0);
                pendingToken.append(arr);
                continue;
            }
            if (Character.isWhitespace(c) || c == ']' || c == '>' || c == '/') {
                if (c == '/') {
                    i++;
                    while (i < n && !isDelimiter(stream.charAt(i))) i++;
                } else {
                    i++;
                }
                continue;
            }
            int start = i;
            while (i < n && !isDelimiter(stream.charAt(i))) i++;
            String op = stream.substring(start, i);
            if ("BT".equals(op)) {
                inText = true;
                pendingToken.setLength(0);
            } else if ("ET".equals(op)) {
                inText = false;
                pendingToken.setLength(0);
                appendNewlineIfNeeded(out);
            } else if (inText) {
                if ("Tj".equals(op) || "TJ".equals(op)) {
                    if (pendingToken.length() > 0) {
                        out.append(pendingToken);
                        pendingToken.setLength(0);
                    }
                } else if ("'".equals(op) || "\"".equals(op)) {
                    appendNewlineIfNeeded(out);
                    if (pendingToken.length() > 0) {
                        out.append(pendingToken);
                        pendingToken.setLength(0);
                    }
                } else if ("T*".equals(op) || "Td".equals(op) || "TD".equals(op) || "Tm".equals(op)) {
                    appendNewlineIfNeeded(out);
                }
            }
        }
    }

    private static int readTextArray(String s, int i, Map<Integer, String> toUnicode, StringBuilder out) {
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == ']') return i + 1;
            if (c == '(') {
                i = readLiteralString(s, i + 1, out);
            } else if (c == '<') {
                int close = s.indexOf('>', i + 1);
                if (close < 0) return n;
                out.append(decodePdfHex(s.substring(i + 1, close), toUnicode));
                i = close + 1;
            } else if (c == '-' || (c >= '0' && c <= '9')) {
                int numStart = i;
                i++;
                while (i < n && ((s.charAt(i) >= '0' && s.charAt(i) <= '9') || s.charAt(i) == '.')) i++;
                try {
                    double adjust = Double.parseDouble(s.substring(numStart, i));
                    if (adjust <= -180.0 && out.length() > 0 && out.charAt(out.length() - 1) != ' ') {
                        out.append(' ');
                    }
                } catch (NumberFormatException ignored) {}
            } else {
                i++;
            }
        }
        return n;
    }

    private static int readLiteralString(String s, int i, StringBuilder out) {
        int n = s.length();
        int depth = 1;
        while (i < n && depth > 0) {
            char c = s.charAt(i++);
            if (c == '\\' && i < n) {
                char esc = s.charAt(i++);
                switch (esc) {
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case '(': out.append('('); break;
                    case ')': out.append(')'); break;
                    case '\\': out.append('\\'); break;
                    case '\r':
                        if (i < n && s.charAt(i) == '\n') i++;
                        break;
                    case '\n':
                        break;
                    default:
                        if (esc >= '0' && esc <= '7') {
                            int val = esc - '0';
                            for (int d = 0; d < 2 && i < n; d++) {
                                char oct = s.charAt(i);
                                if (oct >= '0' && oct <= '7') {
                                    val = (val << 3) + (oct - '0');
                                    i++;
                                } else {
                                    break;
                                }
                            }
                            out.append((char) (val & 0xFF));
                        } else {
                            out.append(esc);
                        }
                        break;
                }
            } else if (c == '(') {
                depth++;
                out.append('(');
            } else if (c == ')') {
                depth--;
                if (depth > 0) out.append(')');
            } else {
                out.append(c);
            }
        }
        return i;
    }

    private static String decodePdfHex(String rawHex, Map<Integer, String> toUnicode) {
        StringBuilder clean = new StringBuilder(rawHex.length());
        for (int i = 0; i < rawHex.length(); i++) {
            char c = rawHex.charAt(i);
            if (!Character.isWhitespace(c)) clean.append(c);
        }
        if (clean.length() == 0) return "";
        if ((clean.length() & 1) == 1) clean.append('0');
        String hex = clean.toString();
        if (hex.length() >= 4 && hex.regionMatches(true, 0, "FEFF", 0, 4)) {
            return decodeUnicodeHex(hex.substring(4));
        }
        if (toUnicode != null && !toUnicode.isEmpty()) {
            // Try 2-byte codes first if length is even number of 4-hex chunks and matches map
            if (hex.length() % 4 == 0) {
                StringBuilder mapped2 = new StringBuilder();
                boolean anyHit = false;
                for (int i = 0; i + 4 <= hex.length(); i += 4) {
                    Integer code = parseHexInt(hex.substring(i, i + 4));
                    if (code != null && toUnicode.containsKey(code)) {
                        mapped2.append(toUnicode.get(code));
                        anyHit = true;
                    } else if (code != null && code >= 0x20 && code <= 0x7E) {
                        mapped2.append((char) code.intValue());
                    }
                }
                if (anyHit) return mapped2.toString();
            }
            StringBuilder mapped1 = new StringBuilder();
            boolean anyHit = false;
            for (int i = 0; i + 2 <= hex.length(); i += 2) {
                Integer code = parseHexInt(hex.substring(i, i + 2));
                if (code != null && toUnicode.containsKey(code)) {
                    mapped1.append(toUnicode.get(code));
                    anyHit = true;
                } else if (code != null && code >= 0x20 && code <= 0x7E) {
                    mapped1.append((char) code.intValue());
                }
            }
            if (anyHit) return mapped1.toString();
        }
        StringBuilder ascii = new StringBuilder(hex.length() / 2);
        for (int i = 0; i + 2 <= hex.length(); i += 2) {
            Integer b = parseHexInt(hex.substring(i, i + 2));
            if (b != null && (b >= 0x20 || b == '\n' || b == '\r' || b == '\t')) {
                ascii.append((char) b.intValue());
            }
        }
        return ascii.toString();
    }

    private static String decodeUnicodeHex(String hex) {
        StringBuilder out = new StringBuilder();
        if (hex.length() >= 4 && hex.length() % 4 == 0) {
            for (int i = 0; i + 4 <= hex.length(); i += 4) {
                Integer cp = parseHexInt(hex.substring(i, i + 4));
                if (cp != null && cp > 0) out.append((char) cp.intValue());
            }
        } else if (hex.length() >= 2 && hex.length() % 2 == 0) {
            for (int i = 0; i + 2 <= hex.length(); i += 2) {
                Integer b = parseHexInt(hex.substring(i, i + 2));
                if (b != null && b > 0) out.append((char) b.intValue());
            }
        }
        return out.toString();
    }

    private static Integer parseHexInt(String hex) {
        try {
            return Integer.parseInt(hex.trim(), 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static byte[] inflateBounded(byte[] data, int offset, int length, int maxOutput) {
        if (length <= 0 || maxOutput <= 0) return null;
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data, offset, length);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(length * 3, 16384));
            byte[] buf = new byte[4096];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break;
                }
                if (out.size() + n > maxOutput) {
                    out.write(buf, 0, Math.max(0, maxOutput - out.size()));
                    break;
                }
                out.write(buf, 0, n);
            }
            return out.size() == 0 ? null : out.toByteArray();
        } catch (DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
    }

    private static int indexOfToken(String text, String token, int fromIndex) {
        int idx = fromIndex;
        int len = token.length();
        while ((idx = text.indexOf(token, idx)) >= 0) {
            boolean leftOk = idx == 0 || isDelimiter(text.charAt(idx - 1));
            boolean rightOk = idx + len >= text.length()
                    || text.charAt(idx + len) == '\r' || text.charAt(idx + len) == '\n';
            if (leftOk && rightOk) return idx;
            idx += len;
        }
        return -1;
    }

    private static boolean isDelimiter(char c) {
        return Character.isWhitespace(c) || c == '(' || c == ')' || c == '<' || c == '>'
                || c == '[' || c == ']' || c == '{' || c == '}' || c == '/' || c == '%';
    }

    private static void appendNewlineIfNeeded(StringBuilder out) {
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
    }

    private static String normalizeWhitespace(String raw, int maxChars) {
        StringBuilder out = new StringBuilder(Math.min(raw.length(), maxChars));
        int newlines = 0;
        boolean lastSpace = false;
        for (int i = 0; i < raw.length() && out.length() < maxChars; i++) {
            char c = raw.charAt(i);
            if (c == '\r') continue;
            if (c == '\n') {
                if (newlines < 2 && out.length() > 0) {
                    out.append('\n');
                    newlines++;
                }
                lastSpace = false;
            } else if (c == '\t' || c == ' ') {
                if (!lastSpace && out.length() > 0 && newlines == 0) {
                    out.append(' ');
                    lastSpace = true;
                }
            } else if (c >= 0x20 && c != 0x7f) {
                out.append(c);
                newlines = 0;
                lastSpace = false;
            }
        }
        return out.toString().trim();
    }

    private static byte[] readAllBytes(File file, int expectedLen) throws IOException {
        byte[] out = new byte[expectedLen];
        try (FileInputStream in = new FileInputStream(file)) {
            int offset = 0;
            while (offset < expectedLen) {
                int n = in.read(out, offset, expectedLen - offset);
                if (n < 0) break;
                offset += n;
            }
            if (offset == expectedLen) return out;
            byte[] trimmed = new byte[offset];
            System.arraycopy(out, 0, trimmed, 0, offset);
            return trimmed;
        }
    }
}

package com.notcherry.lfm3pocket;

import java.net.URI;
import java.util.Locale;

/**
 * Offline-first endpoint validation for MCP servers:
 * <ul>
 *   <li>Loopback ({@code 127.0.0.1}, {@code localhost}, {@code ::1}) is allowed over HTTP or HTTPS
 *       for 100% offline local Termux / on-device MCP servers.</li>
 *   <li>Private RFC1918 LAN addresses ({@code 10.x}, {@code 192.168.x}, {@code 172.16-31.x},
 *       {@code *.local}) are allowed over HTTP only when explicitly opted-in via {@code allowLanHttp}.</li>
 *   <li>All public hosts (including remote hubs like Composio MCP, GitHub MCP, Notion MCP)
 *       strictly require {@code https://}.</li>
 * </ul>
 */
public final class McpEndpointPolicy {
    private McpEndpointPolicy() {}

    public static String validationError(String rawUrl, boolean allowLanHttp) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return "Enter an MCP server URL.";
        }
        String trimmed = rawUrl.trim();
        if (trimmed.length() > 512) return "MCP server URL is too long.";
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            return "Invalid MCP server URL.";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) return "MCP server URL must include a host.";
        if ("https".equals(scheme)) return null;
        if (!"http".equals(scheme)) {
            return "MCP server URL must use https:// (or http:// for localhost/Termux).";
        }
        if (isLoopback(host)) return null;
        if (allowLanHttp && isPrivateLan(host)) return null;
        if (isPrivateLan(host)) {
            return "Enable 'Allow local LAN HTTP' or use HTTPS for LAN MCP servers.";
        }
        return "Public MCP servers must use HTTPS.";
    }

    public static boolean isAllowed(String rawUrl, boolean allowLanHttp) {
        return validationError(rawUrl, allowLanHttp) == null;
    }

    public static boolean isLoopback(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return "127.0.0.1".equals(h) || "localhost".equals(h) || "::1".equals(h) || "[::1]".equals(h);
    }

    public static boolean isPrivateLan(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".local") || h.endsWith(".lan")) return true;
        String[] parts = h.split("\\.");
        if (parts.length != 4) return false;
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            int c = Integer.parseInt(parts[2]);
            int d = Integer.parseInt(parts[3]);
            if (a < 0 || a > 255 || b < 0 || b > 255 || c < 0 || c > 255 || d < 0 || d > 255) return false;
            if (a == 10) return true;
            if (a == 192 && b == 168) return true;
            return a == 172 && b >= 16 && b <= 31;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}

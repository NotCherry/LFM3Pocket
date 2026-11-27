package com.notcherry.lfm3pocket;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers SAF document URIs for GGUF files and opens them as readable file
 * descriptors so llama.cpp can mmap via {@code /proc/self/fd/N} without copying
 * into private storage.
 */
public final class ExternalModelStore {
    public static final class Entry {
        public final String uri;
        public final String displayName;
        public final boolean projector;

        public Entry(String uri, String displayName, boolean projector) {
            this.uri = uri;
            this.displayName = displayName;
            this.projector = projector;
        }
    }

    public static final class Opened {
        public final String path;
        public final String displayName;
        public final Uri uri;
        private final ParcelFileDescriptor descriptor;

        Opened(String path, String displayName, Uri uri, ParcelFileDescriptor descriptor) {
            this.path = path;
            this.displayName = displayName;
            this.uri = uri;
            this.descriptor = descriptor;
        }
    }

    interface PermissionGrants {
        void take(String uriString) throws IOException;
        void release(String uriString);
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final PermissionGrants grants;
    private final Map<String, ParcelFileDescriptor> openDescriptors = new LinkedHashMap<>();

    public ExternalModelStore(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences("external_models", Context.MODE_PRIVATE);
        this.grants = androidGrants(this.context);
    }

    /** Test seam that avoids Android framework APIs. */
    ExternalModelStore(SharedPreferences prefs) {
        this(prefs, noOpGrants());
    }

    ExternalModelStore(SharedPreferences prefs, PermissionGrants grants) {
        this.context = null;
        this.prefs = prefs;
        this.grants = grants == null ? noOpGrants() : grants;
    }

    private static PermissionGrants noOpGrants() {
        return new PermissionGrants() {
            @Override public void take(String uriString) { }
            @Override public void release(String uriString) { }
        };
    }

    private static PermissionGrants androidGrants(final Context context) {
        return new PermissionGrants() {
            @Override public void take(String uriString) throws IOException {
                try {
                    context.getContentResolver().takePersistableUriPermission(Uri.parse(uriString),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException e) {
                    throw new IOException("Could not retain access to the selected document", e);
                }
            }

            @Override public void release(String uriString) {
                try {
                    context.getContentResolver().releasePersistableUriPermission(Uri.parse(uriString),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                    // Providers may not support persistable permissions or the grant may already be gone.
                }
            }
        };
    }

    public synchronized List<Entry> list() {
        ArrayList<Entry> entries = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString("entries", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                entries.add(new Entry(item.getString("uri"), item.optString("name", "GGUF"),
                        item.optBoolean("projector", false)));
            }
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(entries);
    }

    public synchronized void remember(Uri uri, String displayName, boolean projector) throws IOException {
        remember(uri == null ? null : uri.toString(), displayName, projector, java.util.Collections.emptySet());
    }

    public synchronized void remember(Uri uri, String displayName, boolean projector,
                                      java.util.Set<String> protectedUris) throws IOException {
        remember(uri == null ? null : uri.toString(), displayName, projector, protectedUris);
    }

    synchronized void remember(String uriString, String displayName, boolean projector,
                               java.util.Set<String> protectedUris) throws IOException {
        if (uriString == null || uriString.isEmpty()) throw new IOException("Missing document URI");
        grants.take(uriString);
        String name = displayName == null || displayName.trim().isEmpty() ? "GGUF" : displayName.trim();
        List<Entry> previous = list();
        ArrayList<Entry> next = new ArrayList<>();
        for (Entry entry : previous) {
            if (!entry.uri.equals(uriString)) next.add(entry);
        }
        next.add(0, new Entry(uriString, name, projector));
        while (next.size() > 16) next.remove(next.size() - 1);
        save(next);
        revokeEvicted(previous, next, protectedUris);
    }

    private void revokeEvicted(List<Entry> previous, List<Entry> next, java.util.Set<String> protectedUris) {
        java.util.HashSet<String> kept = new java.util.HashSet<>();
        for (Entry entry : next) kept.add(entry.uri);
        for (Entry entry : previous) {
            if (kept.contains(entry.uri)) continue;
            if (protectedUris != null && protectedUris.contains(entry.uri)) continue;
            releasePersistablePermission(entry.uri);
        }
    }

    private void releasePersistablePermission(String uriString) {
        if (uriString == null || uriString.isEmpty()) return;
        grants.release(uriString);
    }

    public synchronized Opened open(Uri uri) throws IOException {
        if (uri == null) throw new IOException("Missing document URI");
        if (context == null) throw new IOException("Android content resolver is unavailable");
        ParcelFileDescriptor previous = openDescriptors.remove(uri.toString());
        if (previous != null) {
            try { previous.close(); } catch (Exception ignored) { }
        }
        ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "r");
        if (descriptor == null) throw new IOException("Could not open the selected document");
        String path = "/proc/self/fd/" + descriptor.getFd();
        if (!new File(path).exists()) {
            descriptor.close();
            throw new IOException("File descriptor path is unavailable on this device");
        }
        openDescriptors.put(uri.toString(), descriptor);
        String name = displayName(uri);
        return new Opened(path, name, uri, descriptor);
    }

    public synchronized void release(String uri) {
        if (uri == null) return;
        ParcelFileDescriptor descriptor = openDescriptors.remove(uri);
        if (descriptor != null) {
            try { descriptor.close(); } catch (Exception ignored) { }
        }
    }

    /** Releases every open descriptor whose URI is not in {@code keepUris}. */
    public synchronized void releaseExcept(java.util.Set<String> keepUris) {
        java.util.Set<String> keep = keepUris == null ? java.util.Collections.emptySet() : keepUris;
        java.util.Iterator<java.util.Map.Entry<String, ParcelFileDescriptor>> it =
                openDescriptors.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, ParcelFileDescriptor> entry = it.next();
            if (keep.contains(entry.getKey())) continue;
            it.remove();
            try { entry.getValue().close(); } catch (Exception ignored) { }
        }
    }

    public synchronized void releaseAll() {
        for (ParcelFileDescriptor descriptor : openDescriptors.values()) {
            try { descriptor.close(); } catch (Exception ignored) { }
        }
        openDescriptors.clear();
    }

    /** Pure JSON helper used by unit tests. */
    public static String serialize(List<Entry> entries) {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            JSONObject item = new JSONObject();
            try {
                item.put("uri", entry.uri);
                item.put("name", entry.displayName);
                item.put("projector", entry.projector);
            } catch (Exception ignored) { }
            array.put(item);
        }
        return array.toString();
    }

    public static List<Entry> deserialize(String json) {
        ArrayList<Entry> entries = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return entries;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                entries.add(new Entry(item.getString("uri"), item.optString("name", "GGUF"),
                        item.optBoolean("projector", false)));
            }
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
        return entries;
    }

    private void save(List<Entry> entries) {
        prefs.edit().putString("entries", serialize(entries)).apply();
    }

    private String displayName(Uri uri) {
        if (context == null) return "GGUF";
        try (android.database.Cursor cursor = context.getContentResolver().query(uri,
                new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (Exception ignored) { }
        String last = uri.getLastPathSegment();
        return last == null || last.isEmpty() ? "GGUF" : last;
    }
}

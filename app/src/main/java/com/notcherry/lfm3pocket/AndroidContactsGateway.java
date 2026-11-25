package com.notcherry.lfm3pocket;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Queries local Android ContactsContract offline for name, phone, and email matches. */
public final class AndroidContactsGateway implements ContactsGateway {
    private final Context context;

    public AndroidContactsGateway(Context context) {
        if (context == null) throw new IllegalArgumentException("Missing context");
        Context app = null;
        try { app = context.getApplicationContext(); } catch (RuntimeException ignored) {}
        this.context = app != null ? app : context;
    }

    @Override public List<ContactMatch> search(String query, int limit) {
        if (query == null || query.trim().isEmpty() || limit <= 0) {
            return java.util.Collections.emptyList();
        }
        ContentResolver resolver = null;
        try { resolver = context.getContentResolver(); } catch (RuntimeException ignored) {}
        if (resolver == null) {
            return java.util.Collections.emptyList();
        }
        String needle = "%" + query.trim() + "%";
        int cap = Math.min(limit, 10);
        Map<String, String[]> byKey = new LinkedHashMap<>();

        // Query phones matching name or number
        try (Cursor c = resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[] {
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                },
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ? OR "
                        + ContactsContract.CommonDataKinds.Phone.NUMBER + " LIKE ?",
                new String[] { needle, needle },
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
            if (c != null) {
                while (c.moveToNext() && byKey.size() < cap) {
                    String name = nonNull(c.getString(0)).trim();
                    String phone = nonNull(c.getString(1)).trim();
                    if (name.isEmpty() && phone.isEmpty()) continue;
                    String key = (name + "|" + normalizePhone(phone)).toLowerCase(Locale.ROOT);
                    byKey.putIfAbsent(key, new String[] { name, phone, "" });
                }
            }
        } catch (RuntimeException ignored) {}

        // Fill email matches up to cap (or attach email to matched names)
        try (Cursor c = resolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                new String[] {
                        ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Email.ADDRESS
                },
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME + " LIKE ? OR "
                        + ContactsContract.CommonDataKinds.Email.ADDRESS + " LIKE ?",
                new String[] { needle, needle },
                ContactsContract.CommonDataKinds.Email.DISPLAY_NAME + " ASC")) {
            if (c != null) {
                while (c.moveToNext()) {
                    String name = nonNull(c.getString(0)).trim();
                    String email = nonNull(c.getString(1)).trim();
                    if (email.isEmpty()) continue;
                    boolean attached = false;
                    for (String[] existing : byKey.values()) {
                        if (!name.isEmpty() && name.equalsIgnoreCase(existing[0]) && existing[2].isEmpty()) {
                            existing[2] = email;
                            attached = true;
                            break;
                        }
                    }
                    if (!attached && byKey.size() < cap) {
                        String key = (name + "|email|" + email).toLowerCase(Locale.ROOT);
                        byKey.putIfAbsent(key, new String[] { name, "", email });
                    }
                }
            }
        } catch (RuntimeException ignored) {}

        List<ContactMatch> out = new ArrayList<>(byKey.size());
        for (String[] row : byKey.values()) {
            out.add(new ContactMatch(row[0], row[1], row[2]));
        }
        return out;
    }

    private static String normalizePhone(String raw) {
        return raw.replaceAll("[\\s\\-().]", "");
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}

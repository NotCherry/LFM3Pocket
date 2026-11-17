package com.notcherry.lfm3pocket;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/** Persists pending reminders for reschedule, cancel, and duplicate detection. */
public final class ReminderStore implements ReminderBook {
  static final String PREFS = "hanami_reminders";
  static final String KEY_ENTRIES = "entries";

  private final SharedPreferences preferences;

  public ReminderStore(Context context) {
    preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  public synchronized List<Entry> all() {
    List<Entry> entries = new ArrayList<>();
    try {
      JSONArray array = new JSONArray(preferences.getString(KEY_ENTRIES, "[]"));
      for (int i = 0; i < array.length(); i++) {
        Entry entry = Entry.fromJson(array.getJSONObject(i));
        if (entry != null) entries.add(entry);
      }
    } catch (Exception ignored) {
    }
    return entries;
  }

  public synchronized boolean hasDuplicate(String title, long whenEpochSec) {
    for (Entry entry : all()) {
      if (entry.whenEpochSec == whenEpochSec && Objects.equals(entry.title, title)) return true;
    }
    return false;
  }

  public synchronized List<Entry> findByTitle(String title) {
    List<Entry> matches = new ArrayList<>();
    if (title == null) return matches;
    for (Entry entry : all()) {
      if (title.equals(entry.title)) matches.add(entry);
    }
    return matches;
  }

  public synchronized Entry findById(String id) {
    if (id == null) return null;
    for (Entry entry : all()) {
      if (id.equals(entry.id)) return entry;
    }
    return null;
  }

  public synchronized void put(Entry entry) {
    if (entry == null || entry.id == null) return;
    List<Entry> entries = new ArrayList<>(all());
    entries.removeIf(e -> entry.id.equals(e.id));
    entries.add(entry);
    write(entries);
  }

  public synchronized void remove(String id) {
    if (id == null) return;
    List<Entry> entries = new ArrayList<>(all());
    for (Iterator<Entry> iterator = entries.iterator(); iterator.hasNext();) {
      if (id.equals(iterator.next().id)) iterator.remove();
    }
    write(entries);
  }

  private void write(List<Entry> entries) {
    JSONArray array = new JSONArray();
    for (Entry entry : entries) array.put(entry.toJson());
    preferences.edit().putString(KEY_ENTRIES, array.toString()).apply();
  }

  public static final class Entry {
    public final String id;
    public final long whenEpochSec;
    public final String title;
    public final String body;
    public final int notificationId;
    public final boolean exact;

    public Entry(String id, long whenEpochSec, String title, String body, int notificationId, boolean exact) {
      this.id = id;
      this.whenEpochSec = whenEpochSec;
      this.title = title == null ? "" : title;
      this.body = body == null ? "" : body;
      this.notificationId = notificationId;
      this.exact = exact;
    }

    JSONObject toJson() {
      JSONObject object = new JSONObject();
      try {
        object.put("id", id);
        object.put("when", whenEpochSec);
        object.put("title", title);
        object.put("body", body);
        object.put("notification_id", notificationId);
        object.put("exact", exact);
      } catch (Exception ignored) {
      }
      return object;
    }

    static Entry fromJson(JSONObject object) {
      try {
        return new Entry(
                object.getString("id"),
                object.getLong("when"),
                object.getString("title"),
                object.optString("body", ""),
                object.getInt("notification_id"),
                object.optBoolean("exact", false));
      } catch (Exception e) {
        return null;
      }
    }
  }
}
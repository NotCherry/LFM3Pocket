package com.notcherry.lfm3pocket;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.CalendarContract;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Queries device calendars via {@link CalendarContract} and shows the write-target picker
 * dialog outside {@link MainActivity} (#322).
 */
final class CalendarPickerHelper {
    static final class Deps {
        final Activity activity;
        final Runnable requestReadCalendarPermission;
        final Consumer<CharSequence> setChatStatus;
        final Runnable refreshCalendarPickerStatus;

        Deps(Activity activity, Runnable requestReadCalendarPermission,
                Consumer<CharSequence> setChatStatus, Runnable refreshCalendarPickerStatus) {
            this.activity = activity;
            this.requestReadCalendarPermission = requestReadCalendarPermission;
            this.setChatStatus = setChatStatus;
            this.refreshCalendarPickerStatus = refreshCalendarPickerStatus;
        }
    }

    private final Deps deps;

    CalendarPickerHelper(Deps deps) {
        this.deps = deps;
    }

    void pickCalendarTarget() {
        if (!hasCalendarPermissions()) {
            deps.requestReadCalendarPermission.run();
            return;
        }
        showCalendarPickerDialog();
    }

    void onPermissionResult(boolean granted) {
        if (granted && hasCalendarPermissions()) {
            showCalendarPickerDialog();
        } else {
            deps.setChatStatus.accept(deps.activity.getString(R.string.calendar_permission_needed));
        }
        deps.refreshCalendarPickerStatus.run();
    }

    private boolean hasCalendarPermissions() {
        return deps.activity.checkSelfPermission(Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED
                && deps.activity.checkSelfPermission(Manifest.permission.WRITE_CALENDAR)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void showCalendarPickerDialog() {
        if (deps.activity.checkSelfPermission(Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) {
            deps.setChatStatus.accept(deps.activity.getString(R.string.calendar_permission_needed));
            return;
        }
        final ArrayList<String> ids = new ArrayList<>();
        final ArrayList<String> labels = new ArrayList<>();
        String[] projection = {
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.VISIBLE
        };
        String writableSelection = CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + " >= ? AND "
                + CalendarContract.Calendars.VISIBLE + " = 1";
        String[] writableArgs = { String.valueOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) };
        try (Cursor cursor = deps.activity.getContentResolver().query(
                CalendarContract.Calendars.CONTENT_URI,
                projection, writableSelection, writableArgs,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " ASC")) {
            collectCalendars(cursor, ids, labels);
        } catch (SecurityException e) {
            deps.setChatStatus.accept(deps.activity.getString(R.string.calendar_permission_needed));
            deps.refreshCalendarPickerStatus.run();
            return;
        } catch (Exception ignored) {
            // Fall through to empty-state handling.
        }
        if (ids.isEmpty()) {
            deps.setChatStatus.accept(deps.activity.getString(R.string.calendar_no_writable_calendars));
            return;
        }
        CharSequence[] items = labels.toArray(new CharSequence[0]);
        StillwaterUi.showDialog(new AlertDialog.Builder(deps.activity)
                .setTitle(R.string.calendar_pick_title)
                .setItems(items, (dialog, which) -> {
                    if (which < 0 || which >= ids.size()) return;
                    new RuntimePreferences(deps.activity).setCalendarWriteTarget(ids.get(which), labels.get(which));
                    deps.refreshCalendarPickerStatus.run();
                })
                .setNegativeButton(R.string.cancel, null));
    }

    private static void collectCalendars(Cursor cursor, ArrayList<String> ids, ArrayList<String> labels) {
        if (cursor == null) return;
        int idIdx = cursor.getColumnIndex(CalendarContract.Calendars._ID);
        int nameIdx = cursor.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME);
        int accountIdx = cursor.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME);
        while (cursor.moveToNext()) {
            String id = idIdx >= 0 ? cursor.getString(idIdx) : null;
            if (id == null || id.trim().isEmpty()) continue;
            String name = nameIdx >= 0 ? cursor.getString(nameIdx) : null;
            String account = accountIdx >= 0 ? cursor.getString(accountIdx) : null;
            String label = (name == null || name.trim().isEmpty()) ? id : name.trim();
            if (account != null && !account.trim().isEmpty() && !account.equals(name)) {
                label = label + " (" + account.trim() + ")";
            }
            ids.add(id.trim());
            labels.add(label);
        }
    }
}

package com.notcherry.lfm3pocket;

import java.util.List;

/** Pending reminder index for duplicate detection and cancel. */
public interface ReminderBook {
    boolean hasDuplicate(String title, long whenEpochSec);

    List<ReminderStore.Entry> findByTitle(String title);

    ReminderStore.Entry findById(String id);

    void put(ReminderStore.Entry entry);

    void remove(String id);
}
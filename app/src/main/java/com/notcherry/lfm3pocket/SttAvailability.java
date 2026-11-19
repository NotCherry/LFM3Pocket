package com.notcherry.lfm3pocket;

/** Whether an STT provider can run locally on this device right now. */
public final class SttAvailability {
    public final boolean available;
    public final String message;

    public SttAvailability(boolean available, String message) {
        this.available = available;
        this.message = message == null ? "" : message;
    }

    public static SttAvailability ready() {
        return new SttAvailability(true, "");
    }

    public static SttAvailability unavailable(String message) {
        return new SttAvailability(false, message);
    }
}

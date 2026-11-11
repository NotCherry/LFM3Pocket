package com.notcherry.lfm3pocket;

/** Range and platform-policy checks for the four allowlisted Android volume streams. */
public final class VolumeController {
    public enum Stream { MUSIC, ALARM, RING, NOTIFICATION }

    public interface Audio {
        boolean isVolumeFixed();
        int minVolume(Stream stream);
        int maxVolume(Stream stream);
        int currentVolume(Stream stream);
        void setVolume(Stream stream, int index);
    }

    private final Audio audio;

    public VolumeController(Audio audio) {
        if (audio == null) throw new IllegalArgumentException("Missing audio dependency");
        this.audio = audio;
    }

    public AndroidActionExecutor.ActionResult setVolume(String streamName, int percent) {
        Stream stream = stream(streamName);
        if (stream == null) return result(false, "UNSUPPORTED_STREAM", "This audio stream is not allowlisted.");
        if (percent < 0 || percent > 100) {
            return result(false, "INVALID_ARGUMENTS", "Volume percent must be in 0..100.");
        }

        try {
            if (audio.isVolumeFixed()) {
                return result(false, "FIXED_VOLUME", "This device does not allow app-controlled volume changes.");
            }
            int min = audio.minVolume(stream);
            int max = audio.maxVolume(stream);
            if (min < 0 || max < min) return unavailable();
            int target = min + Math.round((max - min) * (percent / 100.0f));
            audio.setVolume(stream, target);
            if (audio.currentVolume(stream) != target) {
                return result(false, "POLICY_RESTRICTED", "Android did not apply the requested volume level.");
            }
            return result(true, "VOLUME_SET", label(stream) + " volume set to " + percent + "%.");
        } catch (SecurityException e) {
            return result(false, "POLICY_DENIED",
                    "Android denied this volume change because it would change Do Not Disturb policy.");
        } catch (RuntimeException e) {
            return unavailable();
        }
    }

    private static Stream stream(String name) {
        if ("music".equals(name)) return Stream.MUSIC;
        if ("alarm".equals(name)) return Stream.ALARM;
        if ("ring".equals(name)) return Stream.RING;
        if ("notification".equals(name)) return Stream.NOTIFICATION;
        return null;
    }

    private static String label(Stream stream) {
        switch (stream) {
            case MUSIC: return "Music";
            case ALARM: return "Alarm";
            case RING: return "Ring";
            case NOTIFICATION: return "Notification";
            default: return "Audio";
        }
    }

    private static AndroidActionExecutor.ActionResult unavailable() {
        return result(false, "AUDIO_UNAVAILABLE", "Android audio volume is unavailable right now.");
    }

    private static AndroidActionExecutor.ActionResult result(boolean success, String status, String message) {
        return new AndroidActionExecutor.ActionResult(success, status, message);
    }
}

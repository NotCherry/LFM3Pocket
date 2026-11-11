package com.notcherry.lfm3pocket;

/** Opens one supported Android settings page without changing system settings itself. */
public final class SettingsPageController {
    public enum Page {
        WIFI("wifi", "Wi-Fi"),
        BLUETOOTH("bluetooth", "Bluetooth"),
        SOUND("sound", "sound"),
        DISPLAY("display", "display"),
        APPLICATION("application", "application");

        public final String wireName;
        public final String label;
        Page(String wireName, String label) { this.wireName = wireName; this.label = label; }

        static Page fromWireName(String value) {
            for (Page page : values()) if (page.wireName.equals(value)) return page;
            return null;
        }
    }

    public interface Launcher { void open(Page page); }

    public static final class NoSettingsPageException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private final Launcher launcher;

    public SettingsPageController(Launcher launcher) {
        if (launcher == null) throw new IllegalArgumentException("Missing settings launcher");
        this.launcher = launcher;
    }

    public AndroidActionExecutor.ActionResult open(String requestedPage) {
        Page page = Page.fromWireName(requestedPage);
        if (page == null) return result(false, "UNSUPPORTED_SETTINGS_PAGE", "That Android settings page is not supported.");
        try {
            launcher.open(page);
            return result(true, "SETTINGS_OPENED", "Opened the " + page.label
                    + " settings page. Review and apply any changes yourself.");
        } catch (NoSettingsPageException e) {
            return result(false, "SETTINGS_PAGE_UNAVAILABLE", "Android has no handler for the " + page.label + " settings page.");
        } catch (SecurityException e) {
            return result(false, "SETTINGS_OPEN_DENIED", "Android denied opening the " + page.label + " settings page.");
        } catch (RuntimeException e) {
            return result(false, "SETTINGS_OPEN_FAILED", "Android could not open the " + page.label + " settings page.");
        }
    }

    private static AndroidActionExecutor.ActionResult result(boolean success, String status, String message) {
        return new AndroidActionExecutor.ActionResult(success, status, message);
    }
}

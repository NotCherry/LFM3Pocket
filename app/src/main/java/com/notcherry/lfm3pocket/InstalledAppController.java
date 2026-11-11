package com.notcherry.lfm3pocket;

import java.util.List;
import java.util.regex.Pattern;

/** Resolves an explicitly named, visible launcher target before requesting its launch. */
public final class InstalledAppController {
    private static final Pattern PACKAGE = Pattern.compile("(?:[A-Za-z][A-Za-z0-9_]*\\.)+[A-Za-z][A-Za-z0-9_]*");

    public interface Launcher {
        List<LaunchTarget> findLaunchActivities(String packageName);
        void launch(LaunchTarget target);
    }

    public static final class LaunchTarget {
        public final String packageName;
        public final String className;
        public final String label;

        public LaunchTarget(String packageName, String className, String label) {
            this.packageName = packageName;
            this.className = className;
            this.label = label;
        }
    }

    public static final class NoLaunchActivityException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private final Launcher launcher;

    public InstalledAppController(Launcher launcher) {
        if (launcher == null) throw new IllegalArgumentException("Missing installed-app launcher");
        this.launcher = launcher;
    }

    public AndroidActionExecutor.ActionResult open(String packageName) {
        if (packageName == null || packageName.length() > 255 || !PACKAGE.matcher(packageName).matches()) {
            return result(false, "INVALID_ARGUMENTS", "Expected one installed app package identifier.");
        }
        final List<LaunchTarget> matches;
        try {
            matches = launcher.findLaunchActivities(packageName);
        } catch (SecurityException e) {
            return result(false, "APP_QUERY_DENIED", "Android denied access to this installed-app launch target.");
        } catch (RuntimeException e) {
            return result(false, "APP_QUERY_FAILED", "Android could not inspect this installed-app launch target.");
        }
        if (matches == null || matches.isEmpty()) {
            return result(false, "APP_NOT_FOUND", "No visible launchable app matches package " + packageName + ".");
        }
        if (matches.size() != 1) {
            return result(false, "APP_AMBIGUOUS", "More than one launch activity matches package " + packageName + ". No app was opened.");
        }
        LaunchTarget target = matches.get(0);
        if (target == null || !packageName.equals(target.packageName) || target.className == null
                || target.className.isEmpty()) {
            return result(false, "APP_NOT_FOUND", "No visible launchable app matches package " + packageName + ".");
        }
        try {
            launcher.launch(target);
            String label = safeLabel(target.label);
            return result(true, "APP_OPENED", "Launch request sent for "
                    + (label.isEmpty() ? packageName : label + " (" + packageName + ")") + ".");
        } catch (NoLaunchActivityException e) {
            return result(false, "APP_NOT_FOUND", "The selected app no longer has a launchable activity.");
        } catch (SecurityException e) {
            return result(false, "APP_LAUNCH_DENIED", "Android denied the request to open " + packageName + ".");
        } catch (RuntimeException e) {
            return result(false, "APP_LAUNCH_FAILED", "Android could not open " + packageName + ".");
        }
    }

    private static String safeLabel(String label) {
        if (label == null || label.isEmpty()) return "";
        StringBuilder safe = new StringBuilder(Math.min(label.length(), 100));
        for (int i = 0; i < label.length() && safe.length() < 100; i++) {
            char c = label.charAt(i);
            safe.append(Character.isISOControl(c) ? ' ' : c);
        }
        return safe.toString().trim();
    }

    private static AndroidActionExecutor.ActionResult result(boolean success, String status, String message) {
        return new AndroidActionExecutor.ActionResult(success, status, message);
    }
}

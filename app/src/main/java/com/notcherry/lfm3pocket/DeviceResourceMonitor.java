package com.notcherry.lfm3pocket;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;

/**
 * Lightweight on-device RAM, app RSS, and thermal snapshot helper for live processing status
 * and sanitized diagnostic reports. Best-effort: never throws on restricted/test environments.
 */
public final class DeviceResourceMonitor {
    public static final class Snapshot {
        public final long totalRamMb;
        public final long availRamMb;
        public final long appRssMb;
        public final boolean lowMemory;
        public final String thermalState;

        public Snapshot(long totalRamMb, long availRamMb, long appRssMb, boolean lowMemory, String thermalState) {
            this.totalRamMb = Math.max(0L, totalRamMb);
            this.availRamMb = Math.max(0L, availRamMb);
            this.appRssMb = Math.max(0L, appRssMb);
            this.lowMemory = lowMemory;
            this.thermalState = thermalState == null ? "unknown" : thermalState;
        }

        public String compactBadge() {
            StringBuilder sb = new StringBuilder();
            if (availRamMb > 0L) {
                sb.append(String.format(Locale.US, "RAM %.1f GB free", availRamMb / 1024.0));
            }
            if (appRssMb > 0L) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append("app ").append(appRssMb).append(" MB");
            }
            if (lowMemory) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append("LOW RAM");
            }
            if (!"none".equals(thermalState) && !"unknown".equals(thermalState) && !"light".equals(thermalState)) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append("thermal=").append(thermalState);
            }
            return sb.toString();
        }
    }

    private DeviceResourceMonitor() {}

    public static Snapshot sample(Context context) {
        long totalMb = 0L;
        long availMb = 0L;
        boolean lowMem = false;
        String thermal = "unknown";
        if (context != null) {
            try {
                ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                    am.getMemoryInfo(mi);
                    totalMb = mi.totalMem / (1024L * 1024L);
                    availMb = mi.availMem / (1024L * 1024L);
                    lowMem = mi.lowMemory;
                }
            } catch (Throwable ignored) {}
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        thermal = thermalLabel(pm.getCurrentThermalStatus());
                    }
                } catch (Throwable ignored) {}
            }
        }
        long rssMb = readProcVmRssMb();
        return new Snapshot(totalMb, availMb, rssMb, lowMem, thermal);
    }

    private static long readProcVmRssMb() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    long kb = 0L;
                    boolean foundDigit = false;
                    for (int i = 6; i < line.length(); i++) {
                        char c = line.charAt(i);
                        if (c >= '0' && c <= '9') {
                            kb = kb * 10L + (c - '0');
                            foundDigit = true;
                        } else if (foundDigit) {
                            break;
                        }
                    }
                    if (foundDigit) return kb / 1024L;
                }
            }
        } catch (Throwable ignored) {}
        return 0L;
    }

    private static String thermalLabel(int status) {
        switch (status) {
            case PowerManager.THERMAL_STATUS_NONE: return "none";
            case PowerManager.THERMAL_STATUS_LIGHT: return "light";
            case PowerManager.THERMAL_STATUS_MODERATE: return "moderate";
            case PowerManager.THERMAL_STATUS_SEVERE: return "severe";
            case PowerManager.THERMAL_STATUS_CRITICAL: return "critical";
            case PowerManager.THERMAL_STATUS_EMERGENCY: return "emergency";
            case PowerManager.THERMAL_STATUS_SHUTDOWN: return "shutdown";
            default: return "code_" + status;
        }
    }
}

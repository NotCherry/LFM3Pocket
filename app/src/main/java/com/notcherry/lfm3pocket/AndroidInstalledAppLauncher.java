package com.notcherry.lfm3pocket;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.ArrayList;
import java.util.List;

/** Android adapter limited to apps with visible MAIN/LAUNCHER activities. */
final class AndroidInstalledAppLauncher implements InstalledAppController.Launcher {
    private final Context context;
    private final PackageManager packageManager;

    AndroidInstalledAppLauncher(Context context) {
        Context resolvedContext = context;
        PackageManager resolvedPackageManager = null;
        if (context != null) {
            try {
                Context applicationContext = context.getApplicationContext();
                resolvedContext = applicationContext == null ? context : applicationContext;
                resolvedPackageManager = resolvedContext.getPackageManager();
            } catch (RuntimeException ignored) {
                // Keep dependency-factory contract tests and unattached previews fail-closed.
            }
        }
        this.context = resolvedContext;
        this.packageManager = resolvedPackageManager;
    }

    @Override public List<InstalledAppController.LaunchTarget> findLaunchActivities(String packageName) {
        if (packageManager == null) throw new IllegalStateException("Android package manager is unavailable");
        Intent query = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(packageName);
        List<ResolveInfo> resolved = packageManager.queryIntentActivities(query, 0);
        List<InstalledAppController.LaunchTarget> targets = new ArrayList<>();
        if (resolved == null) return targets;
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null || !packageName.equals(info.activityInfo.packageName)
                    || info.activityInfo.name == null || info.activityInfo.name.isEmpty()) continue;
            CharSequence loadedLabel = info.loadLabel(packageManager);
            targets.add(new InstalledAppController.LaunchTarget(info.activityInfo.packageName,
                    info.activityInfo.name, loadedLabel == null ? "" : loadedLabel.toString()));
        }
        return targets;
    }

    @Override public void launch(InstalledAppController.LaunchTarget target) {
        if (context == null) throw new IllegalStateException("Android context is unavailable");
        try {
            context.startActivity(createIntent(target.packageName, target.className));
        } catch (ActivityNotFoundException e) {
            throw new InstalledAppController.NoLaunchActivityException();
        }
    }

    static Intent createIntent(String packageName, String className) {
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(packageName, className))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}

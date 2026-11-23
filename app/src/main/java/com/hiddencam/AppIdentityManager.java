package com.hiddencam;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/** Switches between launcher aliases whose labels and vector icons are declared in the manifest. */
final class AppIdentityManager {
    static final String[] LABELS = {"HiddenCam", "Calculator", "Notes"};
    private static final String[] COMPONENTS = {
            "com.hiddencam.LauncherHiddenCam",
            "com.hiddencam.LauncherCalculator",
            "com.hiddencam.LauncherNotes"
    };

    private AppIdentityManager() { }

    static void apply(Context context, int identityIndex) {
        if (identityIndex < 0 || identityIndex >= COMPONENTS.length) {
            throw new IllegalArgumentException("Unknown launcher identity.");
        }
        PackageManager manager = context.getPackageManager();
        // Enable the new launcher entry before disabling the previous one to avoid losing the icon.
        ComponentName selected = new ComponentName(context, COMPONENTS[identityIndex]);
        manager.setComponentEnabledSetting(selected,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        for (int index = 0; index < COMPONENTS.length; index++) {
            if (index == identityIndex) continue;
            manager.setComponentEnabledSetting(new ComponentName(context, COMPONENTS[index]),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
    }
}

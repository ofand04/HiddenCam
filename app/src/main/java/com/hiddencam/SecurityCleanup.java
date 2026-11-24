package com.hiddencam;

import android.content.Context;

import java.io.File;

/** Centralized sensitive-data cleanup used by manual lock, emergency lock and process recovery. */
final class SecurityCleanup {
    private SecurityCleanup() { }

    static void performLockCleanup(Context context) {
        SessionManager.lockEverything();
        VaultManager.clearPlaybackCache(context);
        clearStaleTemporaryRecordings(context, false);
    }

    /** Full cryptographic erasure of HiddenCam-controlled private data; used only by Duress Code. */
    static void performDuressWipe(Context context) {
        SessionManager.lockEverything();
        deleteRecursive(new File(context.getFilesDir(), "protected_folders"));
        deleteRecursive(new File(context.getFilesDir(), "vault"));
        deleteRecursive(new File(context.getCacheDir(), "playback"));
        deleteRecursive(new File(context.getCacheDir(), "recording_tmp"));
        deleteRecursive(new File(context.getFilesDir(), "recovery"));
        context.getSharedPreferences("private_settings", Context.MODE_PRIVATE).edit().clear().apply();
        File filesDir = context.getFilesDir();
        if (filesDir != null) {
            File[] children = filesDir.listFiles();
            if (children != null) {
                for (File child : children) if (!"lib".equals(child.getName())) deleteRecursive(child);
            }
        }
    }

    static void clearPlaybackCaches(Context context) {
        deleteRecursive(new File(context.getCacheDir(), "playback"));
    }

    static void clearStaleTemporaryRecordings(Context context, boolean force) {
        File tmp = new File(context.getCacheDir(), "recording_tmp");
        if (!tmp.isDirectory()) return;
        File[] files = tmp.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis();
        for (File file : files) {
            if (force || now - file.lastModified() > 30 * 60 * 1000L) {
                if (file.getName().startsWith("rec_") || file.getName().endsWith(".tmp") || force) file.delete();
            }
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        file.delete();
    }
}

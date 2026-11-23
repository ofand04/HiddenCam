package com.hiddencam;

import android.content.Context;

import java.io.File;

/**
 * Centralized sensitive data cleanup used by manual lock, emergency lock,
 * and process-restart recovery. Never deletes the only recoverable copy of a recording.
 */
final class SecurityCleanup {
    private SecurityCleanup() {
    }

    /**
     * Clears in-memory keys, temporary plaintext files, and playback caches.
     * Encrypted recordings and recovery files are preserved.
     */
    static void performLockCleanup(Context context) {
        SessionManager.lockEverything();
        clearPlaybackCaches(context);
        clearStaleTemporaryRecordings(context, false);
    }

    /**
     * Full cryptographic erasure of all HiddenCam-controlled private data.
     * Used exclusively by the Duress Code path.
     */
    static void performDuressWipe(Context context) {
        SessionManager.lockEverything();

        // 1. Clear in-memory material
        // (already done by lockEverything)

        // 2. Delete protected folders (encrypted content becomes inaccessible)
        deleteRecursive(new File(context.getFilesDir(), "protected_folders"));

        // 3. Delete legacy vault
        deleteRecursive(new File(context.getFilesDir(), "vault"));

        // 4. Delete recovery / temporary areas
        deleteRecursive(new File(context.getCacheDir(), "playback"));
        deleteRecursive(new File(context.getCacheDir(), "recording_tmp"));
        deleteRecursive(new File(context.getFilesDir(), "recovery"));

        // 5. Clear preferences that hold verifiers and metadata
        context.getSharedPreferences("private_settings", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();

        // 6. Best-effort removal of any remaining app-private files under filesDir
        File filesDir = context.getFilesDir();
        if (filesDir != null) {
            File[] children = filesDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!"lib".equals(child.getName())) {
                        deleteRecursive(child);
                    }
                }
            }
        }
    }

    static void clearPlaybackCaches(Context context) {
        deleteRecursive(new File(context.getCacheDir(), "playback"));
    }

    /**
     * @param force when true, removes temporary files more aggressively
     *              (still never touches encrypted vault content).
     */
    static void clearStaleTemporaryRecordings(Context context, boolean force) {
        File tmp = new File(context.getCacheDir(), "recording_tmp");
        if (!tmp.isDirectory()) {
            return;
        }
        File[] files = tmp.listFiles();
        if (files == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (File file : files) {
            if (force || now - file.lastModified() > 30 * 60 * 1000L) {
                // Only delete if it looks like a leftover temp; never delete known encrypted artifacts.
                if (file.getName().startsWith("rec_") || file.getName().endsWith(".tmp") || force) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}

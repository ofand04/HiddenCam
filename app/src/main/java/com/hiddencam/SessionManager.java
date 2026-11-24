package com.hiddencam;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.SecretKey;

/** In-memory only session state. Keys are never persisted and are cleared on explicit lock. */
final class SessionManager {
    private static volatile boolean applicationUnlocked = false;
    private static final Map<String, SecretKey> unlockedFolderKeys = new ConcurrentHashMap<>();
    private static volatile String activeRecordingFolderId = null;

    private SessionManager() { }

    static void setApplicationUnlocked(boolean unlocked) {
        applicationUnlocked = unlocked;
        if (!unlocked) {
            clearAllFolderKeys();
            activeRecordingFolderId = null;
        }
    }

    static boolean isApplicationUnlocked() { return applicationUnlocked; }

    static void unlockFolder(String folderId, SecretKey key) {
        if (folderId != null && key != null) unlockedFolderKeys.put(folderId, key);
    }

    static SecretKey getFolderKey(String folderId) {
        return folderId == null ? null : unlockedFolderKeys.get(folderId);
    }

    static boolean isFolderUnlocked(String folderId) {
        return folderId != null && unlockedFolderKeys.containsKey(folderId);
    }

    static void lockFolder(String folderId) {
        SecretKey key = unlockedFolderKeys.remove(folderId);
        if (key != null) {
            try {
                byte[] encoded = key.getEncoded();
                if (encoded != null) java.util.Arrays.fill(encoded, (byte) 0);
            } catch (Exception ignored) { }
        }
        if (folderId != null && folderId.equals(activeRecordingFolderId)) activeRecordingFolderId = null;
    }

    static void clearAllFolderKeys() {
        for (String id : unlockedFolderKeys.keySet()) lockFolder(id);
        unlockedFolderKeys.clear();
        activeRecordingFolderId = null;
    }

    static void setActiveRecordingFolder(String folderId) { activeRecordingFolderId = folderId; }
    static String getActiveRecordingFolder() { return activeRecordingFolderId; }

    static void lockEverything() {
        applicationUnlocked = false;
        clearAllFolderKeys();
    }
}

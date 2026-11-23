package com.hiddencam;

import android.content.SharedPreferences;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class PinManager {
    private static final String SALT_KEY = "pin_salt";
    private static final String HASH_KEY = "pin_hash";
    private static final int ITERATIONS = 250_000;
    private static final int KEY_LENGTH_BITS = 256;

    private PinManager() {
    }

    static boolean isConfigured(SharedPreferences preferences) {
        return preferences.contains(SALT_KEY) && preferences.contains(HASH_KEY);
    }

    static void setPin(SharedPreferences preferences, char[] pin) throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = null;
        try {
            hash = derive(pin, salt);
            preferences.edit()
                    .putString(SALT_KEY, Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString(HASH_KEY, Base64.encodeToString(hash, Base64.NO_WRAP))
                    .putInt("pin_failed_attempts", 0)
                    .putLong("pin_lock_until", 0L)
                    .apply();
        } finally {
            Arrays.fill(salt, (byte) 0);
            if (hash != null) {
                Arrays.fill(hash, (byte) 0);
            }
        }
    }

    static boolean verify(SharedPreferences preferences, char[] pin) throws Exception {
        String saltValue = preferences.getString(SALT_KEY, null);
        String hashValue = preferences.getString(HASH_KEY, null);
        if (saltValue == null || hashValue == null) {
            return false;
        }

        byte[] salt = Base64.decode(saltValue, Base64.NO_WRAP);
        byte[] expected = Base64.decode(hashValue, Base64.NO_WRAP);
        byte[] actual = null;
        try {
            actual = derive(pin, salt);
            return MessageDigest.isEqual(expected, actual);
        } finally {
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(expected, (byte) 0);
            if (actual != null) {
                Arrays.fill(actual, (byte) 0);
            }
        }
    }

    private static byte[] derive(char[] pin, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pin, salt, ITERATIONS, KEY_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } finally {
            spec.clearPassword();
        }
    }
}

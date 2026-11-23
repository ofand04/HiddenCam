package com.hiddencam;

import android.content.SharedPreferences;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Secure PIN and optional Duress Code management.
 * Stores only salted PBKDF2-HMAC-SHA256 verifiers. Never stores plaintext codes.
 */
final class PinManager {
    private static final String SALT_KEY = "pin_salt";
    private static final String HASH_KEY = "pin_hash";
    private static final String DURESS_SALT_KEY = "duress_salt";
    private static final String DURESS_HASH_KEY = "duress_hash";
    private static final int ITERATIONS = 310_000;
    private static final int KEY_LENGTH_BITS = 256;

    private PinManager() {
    }

    static boolean isConfigured(SharedPreferences preferences) {
        return preferences.contains(SALT_KEY) && preferences.contains(HASH_KEY);
    }

    static boolean isDuressConfigured(SharedPreferences preferences) {
        return preferences.contains(DURESS_SALT_KEY) && preferences.contains(DURESS_HASH_KEY);
    }

    static void setPin(SharedPreferences preferences, char[] pin) throws Exception {
        storeVerifier(preferences, pin, SALT_KEY, HASH_KEY);
        preferences.edit()
                .putInt("pin_failed_attempts", 0)
                .putLong("pin_lock_until", 0L)
                .apply();
    }

    static void setDuressCode(SharedPreferences preferences, char[] duress) throws Exception {
        if (duress == null || duress.length < 4 || duress.length > 12) {
            throw new IllegalArgumentException("Duress code must be 4–12 digits.");
        }
        // Ensure it is not identical to the normal PIN.
        if (isConfigured(preferences) && verify(preferences, duress)) {
            throw new IllegalArgumentException("Duress code must differ from the normal PIN.");
        }
        storeVerifier(preferences, duress, DURESS_SALT_KEY, DURESS_HASH_KEY);
    }

    static void clearDuressCode(SharedPreferences preferences) {
        preferences.edit()
                .remove(DURESS_SALT_KEY)
                .remove(DURESS_HASH_KEY)
                .apply();
    }

    static boolean verify(SharedPreferences preferences, char[] pin) throws Exception {
        return verifyAgainst(preferences, pin, SALT_KEY, HASH_KEY);
    }

    static boolean verifyDuress(SharedPreferences preferences, char[] code) throws Exception {
        if (!isDuressConfigured(preferences)) {
            return false;
        }
        return verifyAgainst(preferences, code, DURESS_SALT_KEY, DURESS_HASH_KEY);
    }

    private static void storeVerifier(SharedPreferences preferences, char[] secret,
                                      String saltKey, String hashKey) throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = null;
        try {
            hash = derive(secret, salt);
            preferences.edit()
                    .putString(saltKey, Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString(hashKey, Base64.encodeToString(hash, Base64.NO_WRAP))
                    .apply();
        } finally {
            Arrays.fill(salt, (byte) 0);
            if (hash != null) {
                Arrays.fill(hash, (byte) 0);
            }
        }
    }

    private static boolean verifyAgainst(SharedPreferences preferences, char[] secret,
                                         String saltKey, String hashKey) throws Exception {
        String saltValue = preferences.getString(saltKey, null);
        String hashValue = preferences.getString(hashKey, null);
        if (saltValue == null || hashValue == null) {
            return false;
        }
        byte[] salt = Base64.decode(saltValue, Base64.NO_WRAP);
        byte[] expected = Base64.decode(hashValue, Base64.NO_WRAP);
        byte[] actual = null;
        try {
            actual = derive(secret, salt);
            return MessageDigest.isEqual(expected, actual);
        } finally {
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(expected, (byte) 0);
            if (actual != null) {
                Arrays.fill(actual, (byte) 0);
            }
        }
    }

    private static byte[] derive(char[] secret, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(secret, salt, ITERATIONS, KEY_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } finally {
            spec.clearPassword();
        }
    }
}

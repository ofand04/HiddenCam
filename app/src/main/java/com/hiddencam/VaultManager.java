package com.hiddencam;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class VaultManager {
    private static final byte[] MAGIC = new byte[]{'H', 'C', 'A', 'M', 1};
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String KEY_ALIAS_PREFIX = "hiddencam_file_";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LENGTH = 12;
    private static final int BUFFER_SIZE = 64 * 1024;

    private VaultManager() {
    }

    static File encryptRecording(Context context, File plainFile) throws Exception {
        if (plainFile == null || !plainFile.isFile() || plainFile.length() == 0) {
            throw new IOException("Recording file is empty");
        }

        String id = UUID.randomUUID().toString().replace("-", "");
        String alias = KEY_ALIAS_PREFIX + id;
        File vaultDirectory = new File(context.getFilesDir(), "vault");
        if (!vaultDirectory.exists() && !vaultDirectory.mkdirs()) {
            throw new IOException("Unable to create the encrypted vault");
        }
        File encryptedFile = new File(vaultDirectory, id + ".hcam");
        byte[] buffer = new byte[BUFFER_SIZE];

        try {
            SecretKey key = createKey(alias);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != IV_LENGTH) {
                throw new IOException("Unexpected encryption IV length");
            }

            try (InputStream input = new BufferedInputStream(new FileInputStream(plainFile));
                 FileOutputStream fileOutput = new FileOutputStream(encryptedFile);
                 OutputStream output = new BufferedOutputStream(fileOutput)) {
                output.write(MAGIC);
                output.write(iv.length);
                output.write(iv);

                int count;
                while ((count = input.read(buffer)) != -1) {
                    byte[] encrypted = cipher.update(buffer, 0, count);
                    if (encrypted != null && encrypted.length > 0) {
                        output.write(encrypted);
                        Arrays.fill(encrypted, (byte) 0);
                    }
                }

                byte[] finalBytes = cipher.doFinal();
                if (finalBytes != null && finalBytes.length > 0) {
                    output.write(finalBytes);
                    Arrays.fill(finalBytes, (byte) 0);
                }
                output.flush();
                fileOutput.getFD().sync();
            }
            return encryptedFile;
        } catch (Exception exception) {
            encryptedFile.delete();
            deleteKey(alias);
            throw exception;
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
    }

    static File decryptToCache(Context context, File encryptedFile) throws Exception {
        String id = encryptedFile.getName().replaceFirst("\\.hcam$", "");
        if (!id.matches("[0-9a-fA-F]{32}")) {
            throw new IOException("Invalid vault entry");
        }

        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);
        String alias = KEY_ALIAS_PREFIX + id;
        if (!keyStore.containsAlias(alias)) {
            throw new IOException("The decryption key is unavailable");
        }
        SecretKey key = (SecretKey) keyStore.getKey(alias, null);

        File temporaryFile = new File(context.getCacheDir(), "playback-" + id + ".mp4");
        byte[] buffer = new byte[BUFFER_SIZE];
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(encryptedFile)))) {
            byte[] magic = new byte[MAGIC.length];
            input.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new IOException("Unsupported encrypted file format");
            }

            int ivLength = input.readUnsignedByte();
            if (ivLength != IV_LENGTH) {
                throw new IOException("Invalid encrypted file header");
            }
            byte[] iv = new byte[ivLength];
            input.readFully(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            try (OutputStream output = new BufferedOutputStream(new FileOutputStream(temporaryFile))) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    byte[] plaintext = cipher.update(buffer, 0, count);
                    if (plaintext != null && plaintext.length > 0) {
                        output.write(plaintext);
                        Arrays.fill(plaintext, (byte) 0);
                    }
                }
                byte[] finalPlaintext = cipher.doFinal();
                if (finalPlaintext != null && finalPlaintext.length > 0) {
                    output.write(finalPlaintext);
                    Arrays.fill(finalPlaintext, (byte) 0);
                }
                output.flush();
            }
            Arrays.fill(iv, (byte) 0);
            return temporaryFile;
        } catch (Exception exception) {
            temporaryFile.delete();
            throw exception;
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
    }

    static File[] listEncryptedFiles(Context context) {
        File vaultDirectory = new File(context.getFilesDir(), "vault");
        File[] files = vaultDirectory.listFiles((directory, name) -> name.endsWith(".hcam"));
        if (files == null) {
            return new File[0];
        }
        Arrays.sort(files, (first, second) -> Long.compare(second.lastModified(), first.lastModified()));
        return files;
    }

    static boolean deleteEncrypted(Context context, File encryptedFile) throws Exception {
        String id = encryptedFile.getName().replaceFirst("\\.hcam$", "");
        if (!id.matches("[0-9a-fA-F]{32}")) {
            throw new IOException("Invalid vault entry");
        }
        boolean removed = !encryptedFile.exists() || encryptedFile.delete();
        // Keep the key if file deletion fails; deleting it first can strand undeleted ciphertext.
        if (removed) deleteKey(KEY_ALIAS_PREFIX + id);
        return removed;
    }

    static void cleanupStaleTemporaryFiles(Context context) {
        File[] files = context.getCacheDir().listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith("recording-")) {
                // A previous process may have died before finalization. Never silently delete this source.
                if (!RecordingService.isActive && !RecordingService.isRecording) retainStaleRecording(context, file);
            } else if (name.startsWith("playback-") || name.startsWith("folder-")) {
                deleteRecursively(file);
            }
        }
    }

    static File[] listRecoveryFiles(Context context) {
        File recovery = new File(context.getFilesDir(), "recovery");
        File[] files = recovery.listFiles((directory, name) -> name.endsWith(".mp4"));
        if (files == null) return new File[0];
        Arrays.sort(files, (first, second) -> Long.compare(second.lastModified(), first.lastModified()));
        return files;
    }

    private static void retainStaleRecording(Context context, File source) {
        if (source == null || !source.isFile()) return;
        File recovery = new File(context.getFilesDir(), "recovery");
        if (!recovery.exists() && !recovery.mkdirs()) return;
        File destination = new File(recovery, "recovered-after-restart-" + System.currentTimeMillis() + ".mp4");
        if (source.renameTo(destination)) return;
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush(); output.getFD().sync();
            Arrays.fill(buffer, (byte) 0);
            if (destination.length() == source.length()) source.delete();
            else destination.delete();
        } catch (Exception ignored) {
            destination.delete();
            // Deliberately leave source untouched if recovery move/copy fails.
        }
    }


    static void clearPlaybackCache(Context context) {
        File[] files = context.getCacheDir().listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith("playback-") || name.startsWith("folder-") || name.endsWith(".pending")) {
                deleteRecursively(file);
            }
        }
    }

    static void clearCache(Context context) {
        File[] files = context.getCacheDir().listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            deleteRecursively(file);
        }
    }

    private static SecretKey createKey(String alias) throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
        KeyGenParameterSpec specification = new KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build();
        generator.init(specification);
        return generator.generateKey();
    }

    private static void deleteKey(String alias) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias);
        }
    }

    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}

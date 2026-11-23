package com.hiddencam;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Per-folder PBKDF2-derived AES-256-GCM encryption. Passwords are never persisted. */
final class FolderVaultManager {
    private static final byte[] MAGIC = new byte[]{'H', 'C', 'F', 'V', 1};
    private static final int ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String CHECK_TEXT = "HiddenCam folder key check v1";

    static final class Folder {
        final String id;
        final String name;
        Folder(String id, String name) { this.id = id; this.name = name; }
    }

    static final class Entry {
        final String id;
        final long size;
        final long modified;
        Entry(String id, long size, long modified) {
            this.id = id; this.size = size; this.modified = modified;
        }
    }

    private FolderVaultManager() { }

    private static File root(Context context) {
        return new File(context.getFilesDir(), "protected_folders");
    }

    private static File folderDirectory(Context context, String folderId) throws IOException {
        if (folderId == null || !folderId.matches("[0-9a-f]{32}")) throw new IOException("Invalid folder.");
        return new File(root(context), folderId);
    }

    static List<Folder> listFolders(Context context) {
        ArrayList<Folder> result = new ArrayList<>();
        File directory = root(context);
        File[] children = directory.listFiles(File::isDirectory);
        if (children == null) return result;
        for (File child : children) {
            Properties properties = readProperties(new File(child, "folder.meta"));
            String name = properties.getProperty("name");
            if (name != null && child.getName().matches("[0-9a-f]{32}")) {
                result.add(new Folder(child.getName(), name));
            }
        }
        result.sort(Comparator.comparing(folder -> folder.name.toLowerCase(java.util.Locale.ROOT)));
        return result;
    }

    static Folder createFolder(Context context, String name, char[] password) throws Exception {
        String safeName = name == null ? "" : name.trim();
        if (safeName.isEmpty() || safeName.length() > 48 || safeName.contains("/")) {
            throw new IllegalArgumentException("Folder name must be 1–48 characters.");
        }
        requirePassword(password);
        File parent = root(context);
        if (!parent.exists() && !parent.mkdirs()) throw new IOException("Could not create protected folder storage.");
        String id = UUID.randomUUID().toString().replace("-", "");
        File folder = new File(parent, id);
        if (!folder.mkdirs()) throw new IOException("Could not create protected folder.");
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        SecretKey key = null;
        try {
            key = deriveKey(password, salt);
            Properties properties = new Properties();
            properties.setProperty("version", "1");
            properties.setProperty("name", safeName);
            properties.setProperty("salt", Base64.encodeToString(salt, Base64.NO_WRAP));
            File meta = new File(folder, "folder.meta");
            try (FileOutputStream output = new FileOutputStream(meta)) {
                properties.store(output, "HiddenCam protected folder metadata");
                output.getFD().sync();
            }
            encryptBytes(new File(folder, "key.check"), CHECK_TEXT.getBytes(StandardCharsets.UTF_8), key);
            return new Folder(id, safeName);
        } catch (Exception exception) {
            deleteRecursively(folder);
            throw exception;
        } finally {
            Arrays.fill(salt, (byte) 0);
            if (key != null) wipeKey(key);
        }
    }

    static SecretKey unlock(Context context, String folderId, char[] password) throws Exception {
        requirePassword(password);
        File directory = folderDirectory(context, folderId);
        Properties metadata = readProperties(new File(directory, "folder.meta"));
        String encodedSalt = metadata.getProperty("salt");
        if (!"1".equals(metadata.getProperty("version")) || encodedSalt == null) {
            throw new IOException("Unsupported or damaged protected folder.");
        }
        byte[] salt = Base64.decode(encodedSalt, Base64.NO_WRAP);
        SecretKey key = null;
        try {
            if (salt.length != SALT_BYTES) throw new IOException("Invalid folder salt.");
            key = deriveKey(password, salt);
            byte[] check = decryptBytes(new File(directory, "key.check"), key);
            boolean valid = Arrays.equals(check, CHECK_TEXT.getBytes(StandardCharsets.UTF_8));
            Arrays.fill(check, (byte) 0);
            if (!valid) {
                wipeKey(key);
                throw new SecurityException("Incorrect folder password.");
            }
            // Keep the key only in memory for the current authenticated session.
            SessionManager.unlockFolder(folderId, key);
            return key;
        } finally {
            Arrays.fill(salt, (byte) 0);
        }
    }

    static List<Entry> listEntries(Context context, String folderId) throws IOException {
        File directory = folderDirectory(context, folderId);
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".hcv"));
        ArrayList<Entry> entries = new ArrayList<>();
        if (files != null) {
            for (File file : files) {
                String id = file.getName().substring(0, file.getName().length() - 4);
                if (id.matches("[0-9a-f]{32}")) entries.add(new Entry(id, file.length(), file.lastModified()));
            }
        }
        entries.sort((a, b) -> Long.compare(b.modified, a.modified));
        return entries;
    }

    static Entry importUri(Context context, String folderId, Uri uri, SecretKey key) throws Exception {
        String displayName = "imported-file";
        try (android.database.Cursor cursor = context.getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (column >= 0) displayName = cursor.getString(column);
            }
        }
        displayName = sanitizeName(displayName);
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Could not read the selected file.");
            return encryptStream(context, folderId, displayName, input, key);
        }
    }

    /** Public entry point used by RecordingService for new captures. */
    static Entry encryptStreamPublic(Context context, String folderId, String displayName,
                                     InputStream input, SecretKey key) throws Exception {
        return encryptStream(context, folderId, displayName, input, key);
    }

    private static Entry encryptStream(Context context, String folderId, String displayName,
                                       InputStream input, SecretKey key) throws Exception {
        File directory = folderDirectory(context, folderId);
        String id = UUID.randomUUID().toString().replace("-", "");
        File target = new File(directory, id + ".hcv");
        File pending = new File(directory, id + ".pending");
        byte[] buffer = new byte[BUFFER_SIZE];
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != IV_BYTES) throw new IOException("Invalid GCM IV.");
            try (FileOutputStream file = new FileOutputStream(pending);
                 BufferedOutputStream output = new BufferedOutputStream(file)) {
                output.write(MAGIC);
                output.write(iv.length);
                output.write(iv);
                byte[] nameBytes = displayName.getBytes(StandardCharsets.UTF_8);
                if (nameBytes.length > 1024) throw new IOException("File name is too long.");
                byte[] prefix = new byte[2 + nameBytes.length];
                prefix[0] = (byte) ((nameBytes.length >>> 8) & 0xff);
                prefix[1] = (byte) (nameBytes.length & 0xff);
                System.arraycopy(nameBytes, 0, prefix, 2, nameBytes.length);
                writeCipherBytes(output, cipher.update(prefix));
                Arrays.fill(prefix, (byte) 0);
                Arrays.fill(nameBytes, (byte) 0);
                int count;
                while ((count = input.read(buffer)) != -1) {
                    writeCipherBytes(output, cipher.update(buffer, 0, count));
                }
                writeCipherBytes(output, cipher.doFinal());
                output.flush();
                file.getFD().sync();
            }
            if (!pending.renameTo(target)) throw new IOException("Could not finalize encrypted folder entry.");
            return new Entry(id, target.length(), target.lastModified());
        } catch (Exception exception) {
            pending.delete();
            target.delete();
            throw exception;
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
    }

    static void exportToUri(Context context, String folderId, String entryId,
                            SecretKey key, Uri destination) throws Exception {
        if (entryId == null || !entryId.matches("[0-9a-f]{32}")) throw new IOException("Invalid entry.");
        File encrypted = new File(folderDirectory(context, folderId), entryId + ".hcv");
        if (!encrypted.isFile()) throw new IOException("Protected entry not found.");
        // Authenticate the complete GCM stream in private cache before exporting any plaintext.
        File verified = new File(context.getCacheDir(), "folder-verified-" + UUID.randomUUID() + ".tmp");
        try {
            try (FileOutputStream privateOutput = new FileOutputStream(verified)) {
                decryptStream(encrypted, privateOutput, key);
                privateOutput.flush();
                privateOutput.getFD().sync();
            }
            try (InputStream input = new BufferedInputStream(new FileInputStream(verified));
                 OutputStream output = context.getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) throw new IOException("Could not open destination.");
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.flush();
                Arrays.fill(buffer, (byte) 0);
            }
        } finally {
            verified.delete();
        }
    }

    static boolean deleteEntry(Context context, String folderId, String entryId) throws IOException {
        if (entryId == null || !entryId.matches("[0-9a-f]{32}")) throw new IOException("Invalid entry.");
        File entry = new File(folderDirectory(context, folderId), entryId + ".hcv");
        return !entry.exists() || entry.delete();
    }

    /** Copies legacy .hcam entries; originals are intentionally retained until the user verifies the copies. */
    static int copyLegacyRecordings(Context context, String folderId, SecretKey key) throws Exception {
        int copied = 0;
        for (File legacy : VaultManager.listEncryptedFiles(context)) {
            File plain = null;
            try {
                plain = VaultManager.decryptToCache(context, legacy);
                try (InputStream input = new FileInputStream(plain)) {
                    encryptStream(context, folderId, "legacy-" + legacy.getName().replace(".hcam", ".mp4"), input, key);
                }
                copied++;
            } finally {
                if (plain != null) plain.delete();
            }
        }
        return copied;
    }

    private static void encryptBytes(File file, byte[] data, SecretKey key) throws Exception {
        byte[] iv = new byte[IV_BYTES];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        byte[] encrypted = cipher.doFinal(data);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(MAGIC); output.write(iv.length); output.write(iv); output.write(encrypted);
            output.getFD().sync();
        } finally {
            Arrays.fill(iv, (byte) 0);
            Arrays.fill(encrypted, (byte) 0);
        }
    }

    private static byte[] decryptBytes(File file, SecretKey key) throws Exception {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            readHeader(input);
            byte[] iv = readIv(input);
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
                int count;
                while ((count = input.read(buffer)) != -1) {
                    byte[] clear = cipher.update(buffer, 0, count);
                    if (clear != null) { bytes.write(clear); Arrays.fill(clear, (byte) 0); }
                }
                byte[] last = cipher.doFinal();
                if (last != null) { bytes.write(last); Arrays.fill(last, (byte) 0); }
                Arrays.fill(buffer, (byte) 0);
                return bytes.toByteArray();
            } finally { Arrays.fill(iv, (byte) 0); }
        }
    }

    private static void decryptStream(File encrypted, OutputStream destination, SecretKey key) throws Exception {
        byte[] buffer = new byte[BUFFER_SIZE];
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(encrypted)))) {
            readHeader(input);
            byte[] iv = readIv(input);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
                ByteArrayOutputStream prefix = new ByteArrayOutputStream();
                boolean nameRead = false;
                int nameLength = -1;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    byte[] clear = cipher.update(buffer, 0, count);
                    if (clear != null && clear.length > 0) {
                        if (!nameRead) {
                            prefix.write(clear);
                            byte[] p = prefix.toByteArray();
                            if (p.length >= 2) {
                                nameLength = ((p[0] & 0xff) << 8) | (p[1] & 0xff);
                                if (nameLength > 1024) throw new IOException("Invalid encrypted filename.");
                                if (p.length >= nameLength + 2) {
                                    destination.write(p, nameLength + 2, p.length - nameLength - 2);
                                    nameRead = true;
                                    prefix.reset();
                                }
                            }
                            Arrays.fill(p, (byte) 0);
                        } else {
                            destination.write(clear);
                        }
                        Arrays.fill(clear, (byte) 0);
                    }
                }
                byte[] last = cipher.doFinal();
                if (last != null && last.length > 0) {
                    if (!nameRead) {
                        prefix.write(last);
                        byte[] p = prefix.toByteArray();
                        if (p.length < 2) throw new IOException("Encrypted entry is truncated.");
                        nameLength = ((p[0] & 0xff) << 8) | (p[1] & 0xff);
                        if (p.length < nameLength + 2) throw new IOException("Encrypted entry is truncated.");
                        destination.write(p, nameLength + 2, p.length - nameLength - 2);
                        Arrays.fill(p, (byte) 0);
                    } else destination.write(last);
                    Arrays.fill(last, (byte) 0);
                }
                if (!nameRead && prefix.size() == 0) throw new IOException("Encrypted entry is empty.");
            } finally { Arrays.fill(iv, (byte) 0); }
        } finally { Arrays.fill(buffer, (byte) 0); }
    }

    private static void readHeader(DataInputStream input) throws IOException {
        byte[] magic = new byte[MAGIC.length];
        input.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) throw new IOException("Unsupported protected-folder entry.");
        int ivLength = input.readUnsignedByte();
        if (ivLength != IV_BYTES) throw new IOException("Invalid protected-folder entry header.");
    }

    private static byte[] readIv(DataInputStream input) throws IOException {
        byte[] iv = new byte[IV_BYTES]; input.readFully(iv); return iv;
    }

    private static void writeCipherBytes(OutputStream output, byte[] bytes) throws IOException {
        if (bytes != null && bytes.length > 0) { output.write(bytes); Arrays.fill(bytes, (byte) 0); }
    }

    private static SecretKey deriveKey(char[] password, byte[] salt) throws Exception {
        PBEKeySpec specification = new PBEKeySpec(password, salt, ITERATIONS, 256);
        try {
            byte[] encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(specification).getEncoded();
            try { return new SecretKeySpec(encoded, "AES"); }
            finally { Arrays.fill(encoded, (byte) 0); }
        } finally { specification.clearPassword(); }
    }

    private static void requirePassword(char[] password) {
        if (password == null || password.length < 8 || password.length > 128) {
            throw new IllegalArgumentException("Use a folder password of 8–128 characters.");
        }
    }

    private static String sanitizeName(String value) {
        String name = value == null ? "imported-file" : value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (name.isEmpty()) name = "imported-file";
        return name.length() > 120 ? name.substring(name.length() - 120) : name;
    }

    private static Properties readProperties(File file) {
        Properties properties = new Properties();
        if (file.isFile()) {
            try (FileInputStream input = new FileInputStream(file)) { properties.load(input); }
            catch (Exception ignored) { }
        }
        return properties;
    }

    private static void wipeKey(SecretKey key) {
        byte[] bytes = key.getEncoded();
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
    }

    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}

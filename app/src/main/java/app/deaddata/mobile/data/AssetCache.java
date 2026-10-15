package app.deaddata.mobile.data;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Tiny on-disk cache for API asset payloads (the hero list).
 *
 * Keys are sanitised and hashed into file names, so an arbitrary URL is safe to
 * use as a key and two different keys can never collide. Writes go through a
 * temporary file and are moved into place, so a killed process cannot leave a
 * half-written payload behind.
 */
public final class AssetCache {
    /** java.nio.file is available from API 26, which matches this app's minSdk. */
    private static final int MAX_PREFIX_LENGTH = 40;

    private final File directory;

    public AssetCache(File directory) {
        this.directory = directory;
    }

    public File directory() {
        return directory;
    }

    /** Target file for a key, or {@code null} when the key is unusable. */
    public File fileFor(String key) {
        if (directory == null || key == null || key.trim().isEmpty()) return null;
        String safe = key.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() > MAX_PREFIX_LENGTH) safe = safe.substring(0, MAX_PREFIX_LENGTH);
        return new File(directory, safe + "-" + digest(key) + ".cache");
    }

    /**
     * Cached text, or {@code null} when it is missing or older than
     * {@code maxAgeMillis}. A non-positive age disables the freshness check,
     * which is how the offline fallback accepts arbitrarily old assets.
     */
    public String read(String key, long maxAgeMillis) {
        File file = fileFor(key);
        if (file == null || !file.isFile()) return null;
        if (maxAgeMillis > 0
                && System.currentTimeMillis() - file.lastModified() > maxAgeMillis) {
            return null;
        }
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException | OutOfMemoryError error) {
            return null;
        }
    }

    /** Stores {@code content}; returns false when the payload could not be written. */
    public boolean write(String key, String content) {
        File file = fileFor(key);
        if (file == null || content == null) return false;
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
        File temporary = new File(file.getPath() + ".tmp");
        try {
            Files.write(temporary.toPath(), content.getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException error) {
            deleteQuietly(temporary);
            return false;
        }
    }

    public void clear() {
        File[] files = directory == null ? null : directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile()) deleteQuietly(file);
        }
    }

    private static void deleteQuietly(File file) {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException ignored) {
            // A cache miss is never fatal.
        }
    }

    private static String digest(String value) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            byte[] bytes = sha.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6 && i < bytes.length; i++) {
                hex.append(String.format("%02x", bytes[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            return Integer.toHexString(value.hashCode());
        }
    }
}

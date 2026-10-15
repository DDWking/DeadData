package app.deaddata.mobile.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Covers the on-disk asset cache used to render heroes without a network. */
public class AssetCacheTest {
    private File directory;

    @After
    public void cleanUp() {
        deleteRecursively(directory);
    }

    @Test
    public void writesAndReadsBackUtf8Payloads() throws IOException {
        AssetCache cache = new AssetCache(temporaryDirectory());

        assertTrue(cache.write("heroes", "{\"name\":\"英雄\"}"));

        assertEquals("{\"name\":\"英雄\"}", cache.read("heroes", 0L));
    }

    @Test
    public void createsTheDirectoryOnFirstWrite() throws IOException {
        File target = new File(temporaryDirectory(), "api-assets");
        AssetCache cache = new AssetCache(target);

        assertFalse(target.exists());
        assertTrue(cache.write("heroes", "[]"));

        assertTrue(target.isDirectory());
        assertEquals("[]", cache.read("heroes", 0L));
    }

    @Test
    public void rewritingReplacesThePreviousPayload() throws IOException {
        AssetCache cache = new AssetCache(temporaryDirectory());

        assertTrue(cache.write("heroes", "first"));
        assertTrue(cache.write("heroes", "second"));

        assertEquals("second", cache.read("heroes", 0L));
    }

    @Test
    public void staleEntriesAreRejectedUnlessTheAgeCheckIsDisabled() throws IOException {
        AssetCache cache = new AssetCache(temporaryDirectory());
        cache.write("heroes", "[{\"id\":1}]");
        File file = cache.fileFor("heroes");
        assertNotNull(file);
        file.setLastModified(System.currentTimeMillis() - 60_000L);

        assertNull(cache.read("heroes", 30_000L));
        assertEquals("[{\"id\":1}]", cache.read("heroes", 0L));
    }

    @Test
    public void missingEntriesAndUnusableKeysAreEmpty() throws IOException {
        AssetCache cache = new AssetCache(temporaryDirectory());

        assertNull(cache.read("heroes", 0L));
        assertNull(cache.fileFor(null));
        assertNull(cache.fileFor("   "));
        assertFalse(cache.write(null, "[]"));
        assertFalse(cache.write("heroes", null));
    }

    @Test
    public void aUrlKeyKeepsTheCacheFileInsideItsDirectory() throws IOException {
        File root = temporaryDirectory();
        AssetCache cache = new AssetCache(root);

        File file = cache.fileFor("https://api.deadlock-api.com/v1/assets/heroes?x=1");

        assertNotNull(file);
        assertEquals(root.getCanonicalPath(), file.getParentFile().getCanonicalPath());
        assertFalse(file.getName().contains("/"));
        assertFalse(file.getName().contains("?"));
        assertFalse(file.getName().contains(":"));
    }

    @Test
    public void distinctKeysNeverShareAFileAndIdenticalKeysAreStable() throws IOException {
        AssetCache cache = new AssetCache(temporaryDirectory());

        assertEquals(cache.fileFor("heroes"), cache.fileFor("heroes"));
        assertNotEquals(cache.fileFor("heroes"), cache.fileFor("heroes-v2"));
        // Keys that sanitise to the same prefix still differ thanks to the digest.
        assertNotEquals(cache.fileFor("a/b"), cache.fileFor("a:b"));
    }

    @Test
    public void clearRemovesEveryCachedPayload() throws IOException {
        AssetCache cache = new AssetCache(temporaryDirectory());
        cache.write("heroes", "[]");
        cache.write("items", "[]");

        cache.clear();

        assertNull(cache.read("heroes", 0L));
        assertNull(cache.read("items", 0L));
    }

    @Test
    public void unusableDirectoriesReportFailureInsteadOfThrowing() throws IOException {
        File blocker = new File(temporaryDirectory(), "not-a-directory");
        assertTrue(blocker.createNewFile());
        AssetCache cache = new AssetCache(blocker);

        assertFalse(cache.write("heroes", "[]"));
        assertNull(cache.read("heroes", 0L));
        cache.clear();
    }

    @Test
    public void payloadWrittenByAnotherProcessIsReadable() throws IOException {
        AssetCache cache = new AssetCache(temporaryDirectory());
        File file = cache.fileFor("heroes");
        assertNotNull(file);
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        Files.write(file.toPath(), "external".getBytes(StandardCharsets.UTF_8));

        assertEquals("external", cache.read("heroes", 0L));
    }

    private File temporaryDirectory() throws IOException {
        directory = Files.createTempDirectory("deaddata-asset-cache").toFile();
        return directory;
    }

    private static void deleteRecursively(File file) {
        if (file == null) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        // A failure here only leaves a temp directory behind.
        file.delete();
    }
}

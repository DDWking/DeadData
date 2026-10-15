package app.deaddata.mobile.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

/**
 * Covers the cache policy wired into {@link DeadlockApi}: how long each endpoint
 * is cached and that the disk-cache hooks tolerate missing directories.
 */
public class DeadlockApiCacheTest {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void heroAssetsAreCachedMuchLongerThanPlayerData() {
        long assetTtl = DeadlockApi.cacheTtlMillis("/v1/assets/heroes");
        long playerTtl = DeadlockApi.cacheTtlMillis("/v1/players/1234/match-history");

        assertEquals(12L * 60L * 60L * 1000L, assetTtl);
        assertEquals(60L * 1000L, playerTtl);
        assertTrue(assetTtl > playerTtl);
    }

    @Test
    public void unknownAndNullPathsFallBackToTheShortTtl() {
        assertEquals(60L * 1000L, DeadlockApi.cacheTtlMillis("/v1/matches/123/metadata"));
        assertEquals(60L * 1000L, DeadlockApi.cacheTtlMillis(null));
    }

    @Test
    public void cacheDirectoryHooksTolerateMissingDirectories() {
        DeadlockApi.setCacheDirectory(null);
        DeadlockApi.clearResponseCache();

        DeadlockApi.setCacheDirectory(folder.getRoot());
        DeadlockApi.setCacheDirectory(new File(folder.getRoot(), "nested"));
        DeadlockApi.clearResponseCache();
    }

    @Test
    public void freshHeroesAreReturnedAndPersistedForOfflineStarts() {
        AssetCache cache = new AssetCache(folder.getRoot());

        assertEquals("[{\"id\":1}]", DeadlockApi.heroesPayload(cache, "[{\"id\":1}]"));
        assertEquals("[{\"id\":1}]", cache.read("heroes", 0L));
    }

    @Test
    public void offlineStartsReuseTheLastSuccessfulHeroPayload() {
        AssetCache cache = new AssetCache(folder.getRoot());
        DeadlockApi.heroesPayload(cache, "[{\"id\":7}]");
        File stored = cache.fileFor("heroes");
        assertNotNull(stored);
        // Even a very old payload is served when there is nothing fresher.
        stored.setLastModified(System.currentTimeMillis() - 90L * 24L * 60L * 60L * 1000L);

        assertEquals("[{\"id\":7}]", DeadlockApi.heroesPayload(cache, null));
        assertEquals("[{\"id\":7}]", DeadlockApi.heroesPayload(cache, "   "));
    }

    @Test
    public void anEmptyCacheAndAFailedFetchYieldNothingInsteadOfThrowing() {
        AssetCache cache = new AssetCache(folder.getRoot());

        assertNull(DeadlockApi.heroesPayload(cache, null));
        assertNull(DeadlockApi.heroesPayload(cache, ""));
        assertNull(DeadlockApi.heroesPayload(null, null));
    }

    @Test
    public void aFreshPayloadIsStillReturnedWhenNoCacheDirectoryIsConfigured() {
        assertEquals("[]", DeadlockApi.heroesPayload(null, "[]"));
    }
}

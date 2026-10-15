package app.deaddata.mobile.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.deaddata.mobile.data.TestSharedPreferences;

import org.junit.Test;

/** Covers the rate limit that guards the automatic update check. */
public class UpdateThrottleTest {
    private static final long HOUR = 60L * 60L * 1000L;

    private final TestSharedPreferences preferences = new TestSharedPreferences();

    @Test
    public void theFirstLaunchAlwaysChecks() {
        assertTrue(UpdateThrottle.shouldCheck(preferences, 1_000_000L, HOUR));
    }

    @Test
    public void aRecentCheckBlocksTheNextAutomaticOne() {
        UpdateThrottle.recordCheck(preferences, 1_000_000L);

        assertFalse(UpdateThrottle.shouldCheck(preferences, 1_000_000L, HOUR));
        assertFalse(UpdateThrottle.shouldCheck(preferences, 1_000_000L + HOUR - 1L, HOUR));
    }

    @Test
    public void theCheckRunsAgainOnceTheIntervalHasElapsed() {
        UpdateThrottle.recordCheck(preferences, 1_000_000L);

        assertTrue(UpdateThrottle.shouldCheck(preferences, 1_000_000L + HOUR, HOUR));
        assertTrue(UpdateThrottle.shouldCheck(preferences, 1_000_000L + HOUR + 5L, HOUR));
    }

    @Test
    public void aClockThatMovedBackwardsDoesNotBlockForever() {
        UpdateThrottle.recordCheck(preferences, 5_000_000L);

        assertTrue(UpdateThrottle.shouldCheck(preferences, 1_000L, HOUR));
    }

    @Test
    public void missingPreferencesNeverBlockAndAreSafeToRecord() {
        assertTrue(UpdateThrottle.shouldCheck(null, 1_000L, HOUR));
        UpdateThrottle.recordCheck(null, 1_000L);
    }

    @Test
    public void recordingTwiceKeepsTheLatestTimestamp() {
        UpdateThrottle.recordCheck(preferences, 1_000_000L);
        UpdateThrottle.recordCheck(preferences, 2_000_000L);

        assertEquals(2_000_000L, preferences.getLong(UpdateThrottle.PREF_LAST_CHECK, 0L));
        assertFalse(UpdateThrottle.shouldCheck(preferences, 2_000_000L + HOUR - 1L, HOUR));
    }

    @Test
    public void theDefaultIntervalIsSixHours() {
        assertEquals(6L * HOUR, UpdateThrottle.DEFAULT_INTERVAL_MS);
    }
}

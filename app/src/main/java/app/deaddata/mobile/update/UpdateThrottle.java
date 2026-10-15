package app.deaddata.mobile.update;

import android.content.SharedPreferences;

/**
 * Rate-limits the automatic update check.
 *
 * The manifest is tiny, but the app used to fetch it on every launch, which is
 * pointless for a release cadence measured in weeks and wasteful on mobile
 * data. A manual "check now" from the user always bypasses this.
 */
public final class UpdateThrottle {
    static final String PREF_LAST_CHECK = "update_last_check_ms";

    /** Automatic checks run at most this often. */
    public static final long DEFAULT_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    private UpdateThrottle() {
    }

    public static boolean shouldCheck(SharedPreferences preferences) {
        return shouldCheck(preferences, System.currentTimeMillis(), DEFAULT_INTERVAL_MS);
    }

    public static boolean shouldCheck(SharedPreferences preferences, long nowMillis,
                                      long intervalMillis) {
        if (preferences == null) return true;
        long lastCheck = preferences.getLong(PREF_LAST_CHECK, 0L);
        if (lastCheck <= 0L) return true;
        // A device clock that moved backwards must not disable update checks.
        if (nowMillis < lastCheck) return true;
        return nowMillis - lastCheck >= Math.max(0L, intervalMillis);
    }

    /** Records a completed check. Failures deliberately do not update this. */
    public static void recordCheck(SharedPreferences preferences, long nowMillis) {
        if (preferences == null) return;
        preferences.edit().putLong(PREF_LAST_CHECK, nowMillis).apply();
    }
}

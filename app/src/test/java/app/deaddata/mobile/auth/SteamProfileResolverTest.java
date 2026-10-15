package app.deaddata.mobile.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

/**
 * Covers the identifier conversion paths that never touch the network, so the
 * SteamID64 to Deadlock account ID mapping stays correct.
 */
public class SteamProfileResolverTest {
    /** SteamID64 of Deadlock account ID 0; every account ID is relative to it. */
    private static final long ACCOUNT_BASE = 76561197960265728L;

    @Test
    public void steamId64IsConvertedToAccountId() throws Exception {
        long steamId = 76561198000000000L;
        SteamProfileResolver.ResolvedAccount resolved =
                SteamProfileResolver.resolve(Long.toString(steamId));

        assertEquals(Long.toString(steamId), resolved.steamId);
        assertEquals("39734272", resolved.accountId);
        assertEquals("https://steamcommunity.com/profiles/" + steamId, resolved.profileUrl);
    }

    @Test
    public void knownSteamIdMatchesTheDefaultAccountId() throws Exception {
        // 1507445138 is the account the app ships as its default account.
        SteamProfileResolver.ResolvedAccount resolved =
                SteamProfileResolver.resolve("76561199467710866");

        assertEquals("1507445138", resolved.accountId);
    }

    @Test
    public void plainAccountIdIsPassedThrough() throws Exception {
        SteamProfileResolver.ResolvedAccount resolved =
                SteamProfileResolver.resolve("1507445138");

        assertEquals("", resolved.steamId);
        assertEquals("1507445138", resolved.accountId);
        assertEquals("", resolved.profileUrl);
    }

    @Test
    public void emptyAndNullInputAreRejected() {
        IOException empty = assertThrows(IOException.class,
                () -> SteamProfileResolver.resolve(""));
        assertTrue(empty.getMessage().contains("请输入"));

        IOException blank = assertThrows(IOException.class,
                () -> SteamProfileResolver.resolve("   "));
        assertTrue(blank.getMessage().contains("请输入"));

        assertThrows(IOException.class, () -> SteamProfileResolver.resolve(null));
    }

    @Test
    public void baseSteamIdIsRejectedBecauseAccountIdWouldBeZero() {
        IOException error = assertThrows(IOException.class,
                () -> SteamProfileResolver.resolve(Long.toString(ACCOUNT_BASE)));

        assertTrue(error.getMessage().contains("无法转换"));
    }

    @Test
    public void steamIdAboveTheAccountIdRangeIsRejected() {
        IOException error = assertThrows(IOException.class,
                () -> SteamProfileResolver.resolve("99999999999999999"));

        assertTrue(error.getMessage().contains("无法转换"));
    }

    @Test
    public void resolvedAccountNeverHoldsNullFields() {
        SteamProfileResolver.ResolvedAccount resolved =
                new SteamProfileResolver.ResolvedAccount(null, null, null);

        assertEquals("", resolved.steamId);
        assertEquals("", resolved.accountId);
        assertEquals("", resolved.profileUrl);
    }
}

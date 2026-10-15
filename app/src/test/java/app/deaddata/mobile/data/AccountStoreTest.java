package app.deaddata.mobile.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;

/**
 * Locks down the on-device account directory: parsing, de-duplication, default
 * account handling and the legacy preference migration.
 */
public class AccountStoreTest {
    private static final String STEAM_A = "76561198000000001";
    private static final String STEAM_B = "76561198000000002";

    private TestSharedPreferences prefs = new TestSharedPreferences();

    @Test
    public void saveAndLoadRoundTripKeepsOrderAndFields() {
        ArrayList<AccountStore.Account> accounts = new ArrayList<>();
        accounts.add(new AccountStore.Account(STEAM_A, "111", "Alice", "https://a.png",
                "好友", true, 1000L));
        accounts.add(new AccountStore.Account("", "222", "Bob", "", "", false, 2000L));

        AccountStore.save(prefs, accounts);
        ArrayList<AccountStore.Account> loaded = AccountStore.load(prefs);

        assertEquals(2, loaded.size());
        assertEquals("111", loaded.get(0).accountId);
        assertEquals(STEAM_A, loaded.get(0).steamId);
        assertEquals("Alice", loaded.get(0).displayName);
        assertEquals("https://a.png", loaded.get(0).avatarUrl);
        assertEquals("好友", loaded.get(0).note);
        assertTrue(loaded.get(0).defaultAccount);
        assertEquals(1000L, loaded.get(0).lastUsedAt);
        assertEquals("222", loaded.get(1).accountId);
        assertEquals(2000L, loaded.get(1).lastUsedAt);
    }

    @Test
    public void saveSkipsAccountsWithoutAValidAccountId() {
        ArrayList<AccountStore.Account> accounts = new ArrayList<>();
        accounts.add(new AccountStore.Account(STEAM_A, "111", "", "", "", false, 0L));
        accounts.add(new AccountStore.Account(STEAM_B, "not-an-id", "", "", "", false, 0L));

        AccountStore.save(prefs, accounts);

        assertEquals(1, AccountStore.load(prefs).size());
        assertEquals("111", AccountStore.load(prefs).get(0).accountId);
    }

    @Test
    public void loadRejectsCorruptJsonInsteadOfThrowing() {
        prefs.edit().putString(AccountStore.PREF_ACCOUNTS, "{not valid json").apply();

        assertEquals(0, AccountStore.load(prefs).size());
    }

    @Test
    public void loadDropsInvalidAndDuplicateEntries() {
        prefs.edit().putString(AccountStore.PREF_ACCOUNTS,
                "[{\"accountId\":\"111\",\"displayName\":\"First\"},"
                        + "{\"accountId\":\"111\",\"displayName\":\"Duplicate\"},"
                        + "{\"accountId\":\"abc\"},"
                        + "{\"steamId\":\"" + STEAM_A + "\",\"displayName\":\"NoAccountId\"},"
                        + "{\"accountId\":\"222\"}]").apply();

        ArrayList<AccountStore.Account> loaded = AccountStore.load(prefs);

        assertEquals(2, loaded.size());
        assertEquals("111", loaded.get(0).accountId);
        assertEquals("First", loaded.get(0).displayName);
        assertEquals("222", loaded.get(1).accountId);
    }

    @Test
    public void loadIgnoresMissingOrBlankPreference() {
        assertEquals(0, AccountStore.load(prefs).size());
        prefs.edit().putString(AccountStore.PREF_ACCOUNTS, "   ").apply();
        assertEquals(0, AccountStore.load(prefs).size());
    }

    @Test
    public void firstUpsertBecomesTheDefaultAccount() {
        AccountStore.Account account = AccountStore.upsert(
                prefs, STEAM_A, "111", "Alice", "", "好友", false);

        assertNotNull(account);
        assertTrue(account.defaultAccount);
        assertEquals("111", prefs.getString(AccountStore.PREF_DEFAULT_ACCOUNT_ID, ""));
        assertEquals(1, AccountStore.load(prefs).size());
    }

    @Test
    public void secondUpsertDoesNotStealTheDefaultFlag() {
        AccountStore.upsert(prefs, STEAM_A, "111", "Alice", "", "", false);
        AccountStore.upsert(prefs, STEAM_B, "222", "Bob", "", "", false);

        ArrayList<AccountStore.Account> loaded = AccountStore.load(prefs);
        assertEquals(2, loaded.size());
        assertTrue(AccountStore.findByAccountId(loaded, "111").defaultAccount);
        assertFalse(AccountStore.findByAccountId(loaded, "222").defaultAccount);
    }

    @Test
    public void upsertWithMakeDefaultSwitchesTheDefaultAccount() {
        AccountStore.upsert(prefs, STEAM_A, "111", "Alice", "", "", false);
        AccountStore.upsert(prefs, STEAM_B, "222", "Bob", "", "", true);

        ArrayList<AccountStore.Account> loaded = AccountStore.load(prefs);
        assertFalse(AccountStore.findByAccountId(loaded, "111").defaultAccount);
        assertTrue(AccountStore.findByAccountId(loaded, "222").defaultAccount);
        assertEquals("222", prefs.getString(AccountStore.PREF_DEFAULT_ACCOUNT_ID, ""));
    }

    @Test
    public void upsertMergesBySteamIdAndKeepsExistingDetails() {
        AccountStore.upsert(prefs, STEAM_A, "111", "Alice", "https://a.png", "好友", false);

        AccountStore.Account merged = AccountStore.upsert(
                prefs, STEAM_A, "", "", "", "", false);

        assertEquals(1, AccountStore.load(prefs).size());
        assertEquals("111", merged.accountId);
        assertEquals("Alice", merged.displayName);
        assertEquals("https://a.png", merged.avatarUrl);
        assertEquals("好友", merged.note);
    }

    @Test
    public void updateReplacesFieldsOfAnExistingAccount() {
        AccountStore.upsert(prefs, "", "111", "", "", "", false);

        AccountStore.update(prefs, new AccountStore.Account(
                STEAM_A, "111", "New Name", "https://new.png", "note", true, 42L));

        AccountStore.Account stored = AccountStore.load(prefs).get(0);
        assertEquals(STEAM_A, stored.steamId);
        assertEquals("New Name", stored.displayName);
        assertEquals("https://new.png", stored.avatarUrl);
        assertEquals("note", stored.note);
        assertEquals(42L, stored.lastUsedAt);
    }

    @Test
    public void updateIgnoresUnusableAccounts() {
        AccountStore.upsert(prefs, "", "111", "Alice", "", "", false);

        AccountStore.update(prefs, new AccountStore.Account("", "bad", "X", "", "", false, 1L));
        AccountStore.update(prefs, null);

        assertEquals(1, AccountStore.load(prefs).size());
        assertEquals("Alice", AccountStore.load(prefs).get(0).displayName);
    }

    @Test
    public void clearSteamIdUnlinksTheSteamProfile() {
        AccountStore.upsert(prefs, STEAM_A, "111", "Alice", "", "", false);

        AccountStore.clearSteamId(prefs, "111");

        assertEquals("", AccountStore.load(prefs).get(0).steamId);
    }

    @Test
    public void markSelectedAndSetDefaultIgnoreUnknownAccounts() {
        AccountStore.markSelected(prefs, "999");
        AccountStore.setDefault(prefs, "999");
        AccountStore.markSelected(prefs, "not-an-id");

        assertFalse(prefs.contains(AccountStore.PREF_SELECTED_ACCOUNT_ID));
        assertFalse(prefs.contains(AccountStore.PREF_DEFAULT_ACCOUNT_ID));
    }

    @Test
    public void markSelectedPersistsTheSelectionAndTouchesLastUsed() {
        AccountStore.upsert(prefs, STEAM_A, "111", "Alice", "", "", false);

        AccountStore.markSelected(prefs, "111");

        assertEquals("111", prefs.getString(AccountStore.PREF_SELECTED_ACCOUNT_ID, ""));
        assertEquals("111", AccountStore.selected(prefs).accountId);
        assertTrue(AccountStore.load(prefs).get(0).lastUsedAt > 0);
    }

    @Test
    public void selectedFallsBackToTheDefaultAccount() {
        AccountStore.upsert(prefs, STEAM_A, "111", "Alice", "", "", false);
        AccountStore.upsert(prefs, STEAM_B, "222", "Bob", "", "", true);

        assertEquals("222", AccountStore.selected(prefs).accountId);
        assertEquals("222", AccountStore.defaultAccount(prefs).accountId);
    }

    @Test
    public void emptyDirectoryHasNoDefaultAccount() {
        assertNull(AccountStore.defaultAccount(prefs));
        assertNull(AccountStore.selected(prefs));
    }

    @Test
    public void removeReassignsDefaultAndSelection() {
        AccountStore.upsert(prefs, STEAM_A, "111", "Alice", "", "", true);
        AccountStore.upsert(prefs, STEAM_B, "222", "Bob", "", "", false);
        AccountStore.markSelected(prefs, "111");

        assertTrue(AccountStore.remove(prefs, "111"));

        ArrayList<AccountStore.Account> loaded = AccountStore.load(prefs);
        assertEquals(1, loaded.size());
        assertTrue(loaded.get(0).defaultAccount);
        assertEquals("222", prefs.getString(AccountStore.PREF_DEFAULT_ACCOUNT_ID, ""));
        assertEquals("222", prefs.getString(AccountStore.PREF_SELECTED_ACCOUNT_ID, ""));
    }

    @Test
    public void removeOfMissingAccountChangesNothing() {
        AccountStore.upsert(prefs, STEAM_A, "111", "Alice", "", "", true);

        assertFalse(AccountStore.remove(prefs, "999"));

        assertEquals(1, AccountStore.load(prefs).size());
        assertEquals("111", prefs.getString(AccountStore.PREF_DEFAULT_ACCOUNT_ID, ""));
    }

    @Test
    public void removingTheLastAccountClearsTheSelectionPreferences() {
        AccountStore.upsert(prefs, STEAM_A, "111", "Alice", "", "", true);
        AccountStore.markSelected(prefs, "111");

        assertTrue(AccountStore.remove(prefs, "111"));

        assertEquals(0, AccountStore.load(prefs).size());
        assertFalse(prefs.contains(AccountStore.PREF_DEFAULT_ACCOUNT_ID));
        assertFalse(prefs.contains(AccountStore.PREF_SELECTED_ACCOUNT_ID));
    }

    @Test
    public void migrateLegacyImportsBoundAccountOnceOnly() {
        prefs.edit()
                .putString("bound_steam_id", STEAM_A)
                .putString("bound_account_id", "111")
                .putString("last_account_id", "222")
                .apply();

        AccountStore.migrateLegacy(prefs);

        ArrayList<AccountStore.Account> loaded = AccountStore.load(prefs);
        assertEquals(2, loaded.size());
        AccountStore.Account bound = AccountStore.findByAccountId(loaded, "111");
        assertEquals(STEAM_A, bound.steamId);
        assertEquals("我的账号", bound.note);
        assertTrue(bound.defaultAccount);
        assertEquals("默认账号", AccountStore.findByAccountId(loaded, "222").note);
        assertEquals("111", prefs.getString(AccountStore.PREF_DEFAULT_ACCOUNT_ID, ""));
        assertEquals("111", prefs.getString(AccountStore.PREF_SELECTED_ACCOUNT_ID, ""));

        // The migration flag must make a second run a no-op.
        AccountStore.remove(prefs, "222");
        AccountStore.migrateLegacy(prefs);

        assertEquals(1, AccountStore.load(prefs).size());
    }

    @Test
    public void migrateLegacyIgnoresInvalidLegacyValues() {
        prefs.edit()
                .putString("bound_steam_id", "not-a-steam-id")
                .putString("bound_account_id", "abc")
                .putString("last_account_id", "")
                .apply();

        AccountStore.migrateLegacy(prefs);

        assertEquals(0, AccountStore.load(prefs).size());
    }

    @Test
    public void migrateLegacyRepairsDanglingDefaultAndSelection() {
        AccountStore.upsert(prefs, STEAM_A, "111", "Alice", "", "", false);
        prefs.edit()
                .putString(AccountStore.PREF_DEFAULT_ACCOUNT_ID, "999")
                .putString(AccountStore.PREF_SELECTED_ACCOUNT_ID, "999")
                .apply();

        AccountStore.migrateLegacy(prefs);

        assertEquals("111", prefs.getString(AccountStore.PREF_DEFAULT_ACCOUNT_ID, ""));
        assertEquals("111", prefs.getString(AccountStore.PREF_SELECTED_ACCOUNT_ID, ""));
    }

    @Test
    public void identifierValidationMatchesTheApiContract() {
        assertTrue(AccountStore.isSteamId("76561198000000001"));
        assertFalse(AccountStore.isSteamId("7656119800000000"));
        assertFalse(AccountStore.isSteamId("765611980000000012"));
        assertFalse(AccountStore.isSteamId(""));
        assertFalse(AccountStore.isSteamId(null));

        assertTrue(AccountStore.isAccountId("1"));
        assertTrue(AccountStore.isAccountId("12345678901234567890"));
        assertFalse(AccountStore.isAccountId("123456789012345678901"));
        assertFalse(AccountStore.isAccountId("abc"));
        assertFalse(AccountStore.isAccountId(""));
        assertFalse(AccountStore.isAccountId(null));
    }

    @Test
    public void findBySteamIdRequiresASeventeenDigitId() {
        AccountStore.upsert(prefs, STEAM_A, "111", "Alice", "", "", false);

        assertNotNull(AccountStore.findBySteamId(AccountStore.load(prefs), STEAM_A));
        assertNull(AccountStore.findBySteamId(AccountStore.load(prefs), ""));
        assertNull(AccountStore.findBySteamId(AccountStore.load(prefs), "123"));
    }

    @Test
    public void labelAndDetailPreferTheUserNoteThenDisplayName() {
        // With a note, the note becomes the primary line and the nickname moves to the
        // secondary line together with the account identifier.
        AccountStore.Account noted = new AccountStore.Account(
                STEAM_A, "111", "Alice", "", "好友", true, 0L);
        assertEquals("好友 · 默认", AccountStore.label(noted));
        assertEquals("Alice · " + STEAM_A, AccountStore.detail(noted));

        // Without a note the nickname is the primary line and the detail line is the id.
        AccountStore.Account named = new AccountStore.Account(
                STEAM_A, "111", "Alice", "", "", false, 0L);
        assertEquals("Alice", AccountStore.label(named));
        assertEquals(STEAM_A, AccountStore.detail(named));

        AccountStore.Account bare = new AccountStore.Account("", "222", "", "", "", false, 0L);
        assertEquals("账号 222", AccountStore.label(bare));
        assertEquals("Deadlock ID 222", AccountStore.detail(bare));

        assertEquals("未选择账号", AccountStore.label(null));
        assertEquals("输入 SteamID64 或个人主页链接添加", AccountStore.detail(null));
    }
}

package app.deaddata.mobile.data;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Stores the small local account directory used by the app.
 *
 * Account records intentionally stay on the device. No Steam credential or
 * remote account list is involved.
 */
public final class AccountStore {
    public static final String PREF_ACCOUNTS = "saved_accounts_v1";
    public static final String PREF_DEFAULT_ACCOUNT_ID = "default_account_id";
    public static final String PREF_SELECTED_ACCOUNT_ID = "selected_account_id";
    private static final String PREF_MIGRATED = "saved_accounts_migrated_v1";

    private AccountStore() {
    }

    public static ArrayList<Account> load(SharedPreferences preferences) {
        ArrayList<Account> accounts = new ArrayList<>();
        String raw = preferences.getString(PREF_ACCOUNTS, "");
        if (raw == null || raw.trim().isEmpty()) return accounts;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                Account account = Account.fromJson(array.optJSONObject(i));
                if (account != null && !containsAccount(accounts, account)) {
                    accounts.add(account);
                }
            }
        } catch (JSONException ignored) {
        }
        return accounts;
    }

    public static void save(SharedPreferences preferences, ArrayList<Account> accounts) {
        JSONArray array = new JSONArray();
        for (Account account : accounts) {
            if (account == null || !account.isUsable()) continue;
            array.put(account.toJson());
        }
        preferences.edit().putString(PREF_ACCOUNTS, array.toString()).apply();
    }

    public static void migrateLegacy(SharedPreferences preferences) {
        if (preferences.getBoolean(PREF_MIGRATED, false)) return;

        ArrayList<Account> accounts = load(preferences);
        String boundSteamId = clean(preferences.getString("bound_steam_id", ""));
        String boundAccountId = clean(preferences.getString("bound_account_id", ""));
        String lastAccountId = clean(preferences.getString("last_account_id", ""));

        if (isSteamId(boundSteamId) && isAccountId(boundAccountId)) {
            upsert(preferences, accounts, boundSteamId, boundAccountId,
                    "", "", "我的账号", true);
        }
        accounts = load(preferences);
        if (isAccountId(lastAccountId) && findByAccountId(accounts, lastAccountId) == null) {
            upsert(preferences, accounts, "", lastAccountId,
                    "", "", "默认账号", accounts.isEmpty());
        }

        accounts = load(preferences);
        if (!accounts.isEmpty()) {
            String defaultId = clean(preferences.getString(PREF_DEFAULT_ACCOUNT_ID, ""));
            if (findByAccountId(accounts, defaultId) == null) {
                setDefault(preferences, accounts.get(0).accountId);
            }
            String selectedId = clean(
                    preferences.getString(PREF_SELECTED_ACCOUNT_ID, ""));
            if (findByAccountId(accounts, selectedId) == null) {
                markSelected(preferences, accounts.get(0).accountId);
            }
        }
        preferences.edit().putBoolean(PREF_MIGRATED, true).apply();
    }

    public static Account selected(SharedPreferences preferences) {
        ArrayList<Account> accounts = load(preferences);
        String selectedId = clean(preferences.getString(PREF_SELECTED_ACCOUNT_ID, ""));
        Account selected = findByAccountId(accounts, selectedId);
        if (selected != null) return selected;

        return defaultAccount(preferences);
    }

    public static Account defaultAccount(SharedPreferences preferences) {
        ArrayList<Account> accounts = load(preferences);
        String defaultId = clean(preferences.getString(PREF_DEFAULT_ACCOUNT_ID, ""));
        Account defaultAccount = findByAccountId(accounts, defaultId);
        if (defaultAccount != null) return defaultAccount;
        for (Account account : accounts) {
            if (account.defaultAccount) return account;
        }
        return accounts.isEmpty() ? null : accounts.get(0);
    }

    public static Account findByAccountId(ArrayList<Account> accounts, String accountId) {
        if (!isAccountId(accountId)) return null;
        for (Account account : accounts) {
            if (accountId.equals(account.accountId)) return account;
        }
        return null;
    }

    public static Account findBySteamId(ArrayList<Account> accounts, String steamId) {
        if (!isSteamId(steamId)) return null;
        for (Account account : accounts) {
            if (steamId.equals(account.steamId)) return account;
        }
        return null;
    }

    public static Account upsert(
            SharedPreferences preferences,
            String steamId,
            String accountId,
            String displayName,
            String avatarUrl,
            String note,
            boolean makeDefault
    ) {
        ArrayList<Account> accounts = load(preferences);
        Account account = upsert(preferences, accounts, steamId, accountId,
                displayName, avatarUrl, note, makeDefault);
        return account;
    }

    private static Account upsert(
            SharedPreferences preferences,
            ArrayList<Account> accounts,
            String steamId,
            String accountId,
            String displayName,
            String avatarUrl,
            String note,
            boolean makeDefault
    ) {
        steamId = clean(steamId);
        accountId = clean(accountId);
        displayName = clean(displayName);
        avatarUrl = clean(avatarUrl);
        note = clean(note);
        Account account = findByAccountId(accounts, accountId);
        if (account == null) account = findBySteamId(accounts, steamId);
        boolean newAccount = account == null;
        if (newAccount) {
            account = new Account(steamId, accountId, displayName, avatarUrl, note,
                    false, System.currentTimeMillis());
            accounts.add(account);
        } else {
            if (isSteamId(steamId)) account.steamId = steamId;
            if (!displayName.isEmpty()) account.displayName = displayName;
            if (!avatarUrl.isEmpty()) account.avatarUrl = avatarUrl;
            if (!note.isEmpty()) account.note = note;
            account.lastUsedAt = System.currentTimeMillis();
        }
        if (makeDefault || !hasDefault(preferences, accounts)) {
            for (Account item : accounts) item.defaultAccount = false;
            account.defaultAccount = true;
            preferences.edit().putString(PREF_DEFAULT_ACCOUNT_ID, account.accountId).apply();
        }
        save(preferences, accounts);
        return account;
    }

    public static void markSelected(SharedPreferences preferences, String accountId) {
        if (!isAccountId(accountId)) return;
        ArrayList<Account> accounts = load(preferences);
        Account account = findByAccountId(accounts, accountId);
        if (account == null) return;
        account.lastUsedAt = System.currentTimeMillis();
        save(preferences, accounts);
        preferences.edit().putString(PREF_SELECTED_ACCOUNT_ID, accountId).apply();
    }

    public static void setDefault(SharedPreferences preferences, String accountId) {
        if (!isAccountId(accountId)) return;
        ArrayList<Account> accounts = load(preferences);
        Account selected = findByAccountId(accounts, accountId);
        if (selected == null) return;
        for (Account account : accounts) account.defaultAccount = false;
        selected.defaultAccount = true;
        save(preferences, accounts);
        preferences.edit()
                .putString(PREF_DEFAULT_ACCOUNT_ID, accountId)
                .apply();
    }

    public static void update(SharedPreferences preferences, Account updated) {
        if (updated == null || !updated.isUsable()) return;
        ArrayList<Account> accounts = load(preferences);
        Account existing = findByAccountId(accounts, updated.accountId);
        if (existing == null) {
            accounts.add(updated);
        } else {
            existing.steamId = updated.steamId;
            existing.displayName = updated.displayName;
            existing.avatarUrl = updated.avatarUrl;
            existing.note = updated.note;
            existing.defaultAccount = updated.defaultAccount;
            existing.lastUsedAt = updated.lastUsedAt;
        }
        save(preferences, accounts);
    }

    public static void clearSteamId(SharedPreferences preferences, String accountId) {
        ArrayList<Account> accounts = load(preferences);
        Account account = findByAccountId(accounts, accountId);
        if (account == null) return;
        account.steamId = "";
        save(preferences, accounts);
    }

    public static boolean remove(SharedPreferences preferences, String accountId) {
        ArrayList<Account> accounts = load(preferences);
        Account removed = findByAccountId(accounts, accountId);
        if (removed == null) return false;
        String selectedId = clean(preferences.getString(PREF_SELECTED_ACCOUNT_ID, ""));
        String defaultId = clean(preferences.getString(PREF_DEFAULT_ACCOUNT_ID, ""));
        accounts.remove(removed);
        if (accounts.isEmpty()) {
            preferences.edit()
                    .remove(PREF_DEFAULT_ACCOUNT_ID)
                    .remove(PREF_SELECTED_ACCOUNT_ID)
                    .apply();
        } else {
            Account nextDefault = findByAccountId(accounts, defaultId);
            if (nextDefault == null) {
                nextDefault = accounts.get(0);
                for (Account account : accounts) account.defaultAccount = false;
                nextDefault.defaultAccount = true;
                defaultId = nextDefault.accountId;
            }
            Account nextSelected = findByAccountId(accounts, selectedId);
            if (nextSelected == null) nextSelected = nextDefault;
            preferences.edit()
                    .putString(PREF_DEFAULT_ACCOUNT_ID, defaultId)
                    .putString(PREF_SELECTED_ACCOUNT_ID, nextSelected.accountId)
                    .apply();
        }
        save(preferences, accounts);
        return true;
    }

    public static String label(Account account) {
        if (account == null) return "未选择账号";
        String title = !account.note.isEmpty() ? account.note
                : !account.displayName.isEmpty() ? account.displayName
                : "账号 " + account.accountId;
        return account.defaultAccount ? title + " · 默认" : title;
    }

    public static String detail(Account account) {
        if (account == null) return "输入 SteamID64 或个人主页链接添加";
        if (!account.displayName.isEmpty() && !account.note.isEmpty()) {
            return account.displayName + " · " + displayId(account);
        }
        return displayId(account);
    }

    public static boolean isSteamId(String value) {
        return value != null && value.matches("[0-9]{17}");
    }

    public static boolean isAccountId(String value) {
        return value != null && value.matches("[0-9]{1,20}");
    }

    private static String displayId(Account account) {
        return isSteamId(account.steamId) ? account.steamId
                : "Deadlock ID " + account.accountId;
    }

    private static boolean hasDefault(
            SharedPreferences preferences,
            ArrayList<Account> accounts
    ) {
        String defaultId = clean(preferences.getString(PREF_DEFAULT_ACCOUNT_ID, ""));
        if (findByAccountId(accounts, defaultId) != null) return true;
        for (Account account : accounts) {
            if (account.defaultAccount) return true;
        }
        return false;
    }

    private static boolean containsAccount(ArrayList<Account> accounts, Account candidate) {
        return findByAccountId(accounts, candidate.accountId) != null
                || findBySteamId(accounts, candidate.steamId) != null;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class Account {
        public String steamId;
        public final String accountId;
        public String displayName;
        public String avatarUrl;
        public String note;
        public boolean defaultAccount;
        public long lastUsedAt;

        public Account(
                String steamId,
                String accountId,
                String displayName,
                String avatarUrl,
                String note,
                boolean defaultAccount,
                long lastUsedAt
        ) {
            this.steamId = clean(steamId);
            this.accountId = clean(accountId);
            this.displayName = clean(displayName);
            this.avatarUrl = clean(avatarUrl);
            this.note = clean(note);
            this.defaultAccount = defaultAccount;
            this.lastUsedAt = lastUsedAt;
        }

        public boolean isUsable() {
            return isAccountId(accountId);
        }

        public JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("steamId", steamId);
                object.put("accountId", accountId);
                object.put("displayName", displayName);
                object.put("avatarUrl", avatarUrl);
                object.put("note", note);
                object.put("defaultAccount", defaultAccount);
                object.put("lastUsedAt", lastUsedAt);
            } catch (JSONException ignored) {
            }
            return object;
        }

        private static Account fromJson(JSONObject object) {
            if (object == null) return null;
            String accountId = clean(object.optString("accountId", ""));
            if (!isAccountId(accountId)) return null;
            return new Account(
                    object.optString("steamId", ""),
                    accountId,
                    object.optString("displayName", ""),
                    object.optString("avatarUrl", ""),
                    object.optString("note", ""),
                    object.optBoolean("defaultAccount", false),
                    object.optLong("lastUsedAt", 0)
            );
        }
    }
}

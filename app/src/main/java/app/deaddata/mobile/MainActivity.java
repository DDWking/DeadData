package app.deaddata.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import app.deaddata.mobile.data.DeadlockApi;
import app.deaddata.mobile.data.AccountStore;
import app.deaddata.mobile.auth.SteamProfileResolver;
import app.deaddata.mobile.update.ApkContentProvider;
import app.deaddata.mobile.update.UpdateClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(16, 19, 17);
    private static final int SURFACE = Color.rgb(27, 32, 28);
    private static final int SURFACE_ALT = Color.rgb(35, 41, 36);
    private static final int BORDER = Color.rgb(51, 60, 53);
    private static final int TEXT = Color.rgb(243, 243, 235);
    private static final int MUTED = Color.rgb(161, 170, 160);
    private static final int CORAL = Color.rgb(231, 123, 93);
    private static final int LIME = Color.rgb(213, 242, 119);
    private static final String DEFAULT_ACCOUNT_ID = "1507445138";
    private static final String PREF_ACCOUNT_DIRECTORY_INITIALIZED =
            "account_directory_initialized_v1";
    private static final int MAX_VISIBLE_MATCHES = 20;

    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final AtomicBoolean updateCheckRunning = new AtomicBoolean(false);
    private SharedPreferences preferences;

    private EditText accountInput;
    private TextView searchButton;
    private TextView accountSelector;
    private TextView accountSelectorDetail;
    private TextView statusView;
    private TextView profileName;
    private TextView profileId;
    private ImageView avatarView;
    private TextView updateStamp;
    private LinearLayout metricGrid;
    private LinearLayout matchList;
    private LinearLayout heroList;
    private ProgressBar loadingBar;
    private File pendingApk;
    private String currentAccountId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(UpdateClient.PREFS, MODE_PRIVATE);
        AccountStore.migrateLegacy(preferences);
        clearLegacyProfileSnapshots();
        if (AccountStore.load(preferences).isEmpty()
                && !preferences.getBoolean(PREF_ACCOUNT_DIRECTORY_INITIALIZED, false)) {
            AccountStore.upsert(
                    preferences,
                    "",
                    DEFAULT_ACCOUNT_ID,
                    "",
                    "",
                    "默认账号",
                    true
            );
        }
        preferences.edit()
                .putBoolean(PREF_ACCOUNT_DIRECTORY_INITIALIZED, true)
                .apply();
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        buildUi();
        AccountStore.Account selected = AccountStore.defaultAccount(preferences);
        if (selected == null) {
            renderAccountSelector();
            renderEmpty();
        } else {
            currentAccountId = selected.accountId;
            AccountStore.markSelected(preferences, selected.accountId);
            setAccountInput(selected);
            renderAccountSelector();
            loadProfile(selected.accountId);
        }
        checkForUpdate(false);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 301 && pendingApk != null) {
            if (getPackageManager().canRequestPackageInstalls()) {
                launchInstaller(pendingApk);
            } else {
                Toast.makeText(this, "需要允许 DeadData 安装更新包", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void buildUi() {
        LinearLayout root = column(BG);
        root.setFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(0, top, 0, bottom);
            return insets;
        });

        LinearLayout toolbar = row();
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(20), dp(10), dp(16), dp(8));
        TextView mark = text("DD", 14, BG, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(rounded(LIME, 12, 0));
        toolbar.addView(mark, new LinearLayout.LayoutParams(dp(38), dp(38)));

        LinearLayout wordmark = column(0);
        wordmark.setPadding(dp(11), 0, 0, 0);
        wordmark.addView(text("DEADDATA", 16, TEXT, true));
        TextView subbrand = text("MATCH TRACKER", 9, MUTED, true);
        subbrand.setLetterSpacing(0.12f);
        wordmark.addView(subbrand);
        toolbar.addView(wordmark, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton updateButton = new ImageButton(this);
        updateButton.setImageResource(android.R.drawable.stat_notify_sync_noanim);
        updateButton.setColorFilter(LIME);
        updateButton.setBackground(rounded(SURFACE_ALT, 14, 0));
        updateButton.setContentDescription(getString(R.string.check_updates));
        updateButton.setTooltipText(getString(R.string.check_updates));
        updateButton.setOnClickListener(view -> openUpdateSettingsOrCheck());
        toolbar.addView(updateButton, iconButtonParams());

        ImageButton refreshButton = new ImageButton(this);
        refreshButton.setImageResource(android.R.drawable.ic_popup_sync);
        refreshButton.setColorFilter(CORAL);
        refreshButton.setBackground(rounded(SURFACE_ALT, 14, 0));
        refreshButton.setContentDescription(getString(R.string.refresh_data));
        refreshButton.setTooltipText(getString(R.string.refresh_data));
        refreshButton.setOnClickListener(view -> refreshCurrentProfile());
        LinearLayout.LayoutParams refreshParams = iconButtonParams();
        refreshParams.leftMargin = dp(8);
        toolbar.addView(refreshButton, refreshParams);
        root.addView(toolbar);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout content = column(BG);
        content.setPadding(dp(20), dp(14), dp(20), dp(28));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView eyebrow = text("PLAYER PROFILE", 10, CORAL, true);
        eyebrow.setLetterSpacing(0.12f);
        content.addView(eyebrow);
        TextView title = text("玩家战绩", 25, TEXT, true);
        LinearLayout.LayoutParams titleParams = params(-2, -2);
        titleParams.topMargin = dp(4);
        content.addView(title, titleParams);

        LinearLayout accountHeader = row();
        accountHeader.setGravity(Gravity.CENTER_VERTICAL);
        accountHeader.setPadding(dp(14), dp(10), dp(10), dp(10));
        accountHeader.setBackground(rounded(SURFACE, 14, BORDER));
        LinearLayout accountHeaderText = column(0);
        accountHeaderText.setGravity(Gravity.CENTER_VERTICAL);
        accountSelector = text("未选择账号", 15, TEXT, true);
        accountSelector.setSingleLine(true);
        accountSelector.setEllipsize(android.text.TextUtils.TruncateAt.END);
        accountSelectorDetail = text("输入 SteamID64 或个人主页链接添加", 11, MUTED, false);
        accountSelectorDetail.setSingleLine(true);
        accountSelectorDetail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        accountHeaderText.addView(accountSelector);
        LinearLayout.LayoutParams selectorDetailParams = params(-1, -2);
        selectorDetailParams.topMargin = dp(4);
        accountHeaderText.addView(accountSelectorDetail, selectorDetailParams);
        accountHeader.addView(accountHeaderText, new LinearLayout.LayoutParams(0, -2, 1));
        TextView switchAccount = text("切换", 13, BG, true);
        switchAccount.setGravity(Gravity.CENTER);
        switchAccount.setBackground(rounded(LIME, 10, 0));
        switchAccount.setOnClickListener(view -> showAccountPicker());
        accountHeader.addView(switchAccount, params(dp(58), dp(40)));
        accountHeader.setOnClickListener(view -> showAccountPicker());
        LinearLayout.LayoutParams accountHeaderParams = params(-1, dp(66));
        accountHeaderParams.topMargin = dp(18);
        content.addView(accountHeader, accountHeaderParams);

        LinearLayout search = row();
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setPadding(dp(14), 0, dp(5), 0);
        search.setBackground(rounded(SURFACE, 14, BORDER));
        LinearLayout.LayoutParams searchParams = params(-1, dp(54));
        searchParams.topMargin = dp(10);
        accountInput = new EditText(this);
        accountInput.setSingleLine(true);
        accountInput.setTextSize(15);
        accountInput.setTextColor(TEXT);
        accountInput.setHintTextColor(MUTED);
        accountInput.setHint("SteamID64 或 Steam 主页链接");
        accountInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        accountInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        accountInput.setBackgroundColor(Color.TRANSPARENT);
        accountInput.setPadding(0, 0, dp(8), 0);
        accountInput.setSelectAllOnFocus(false);
        accountInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch();
                return true;
            }
            return false;
        });
        search.addView(accountInput, new LinearLayout.LayoutParams(0, -1, 1));
        searchButton = text("查询", 14, BG, true);
        searchButton.setGravity(Gravity.CENTER);
        searchButton.setBackground(rounded(LIME, 11, 0));
        searchButton.setOnClickListener(view -> submitSearch());
        search.addView(searchButton, new LinearLayout.LayoutParams(dp(62), dp(42)));
        content.addView(search, searchParams);

        LinearLayout profile = row();
        profile.setGravity(Gravity.CENTER_VERTICAL);
        profile.setPadding(dp(15), dp(14), dp(15), dp(14));
        profile.setBackground(rounded(SURFACE, 16, BORDER));
        LinearLayout.LayoutParams profileParams = params(-1, dp(86));
        profileParams.topMargin = dp(16);
        avatarView = new ImageView(this);
        avatarView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatarView.setBackground(rounded(CORAL, 99, 0));
        avatarView.setClipToOutline(true);
        profile.addView(avatarView, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout profileInfo = column(0);
        profileInfo.setGravity(Gravity.CENTER_VERTICAL);
        profileInfo.setPadding(dp(13), 0, 0, 0);
        profileName = text("Deadlock 玩家", 16, TEXT, true);
        profileName.setSingleLine(true);
        profileName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        profileId = text("账号 " + DEFAULT_ACCOUNT_ID, 12, MUTED, false);
        LinearLayout.LayoutParams idParams = params(-2, -2);
        idParams.topMargin = dp(4);
        profileInfo.addView(profileName);
        profileInfo.addView(profileId, idParams);
        profile.addView(profileInfo, new LinearLayout.LayoutParams(0, -2, 1));
        updateStamp = text("等待战绩", 10, MUTED, false);
        updateStamp.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        profile.addView(updateStamp, params(-2, -2));
        content.addView(profile, profileParams);

        LinearLayout overviewHeading = row();
        overviewHeading.setGravity(Gravity.CENTER_VERTICAL);
        TextView overviewTitle = text("近期概览", 16, TEXT, true);
        overviewHeading.addView(overviewTitle, new LinearLayout.LayoutParams(0, -2, 1));
        loadingBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loadingBar.setIndeterminate(true);
        loadingBar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(LIME));
        loadingBar.setVisibility(View.GONE);
        overviewHeading.addView(loadingBar, new LinearLayout.LayoutParams(dp(42), dp(3)));
        LinearLayout.LayoutParams overviewParams = params(-1, -2);
        overviewParams.topMargin = dp(23);
        content.addView(overviewHeading, overviewParams);

        metricGrid = column(0);
        LinearLayout.LayoutParams gridParams = params(-1, -2);
        gridParams.topMargin = dp(11);
        content.addView(metricGrid, gridParams);

        LinearLayout matchHeading = sectionHeading("最近比赛", "MATCH HISTORY");
        LinearLayout.LayoutParams matchHeadingParams = params(-1, -2);
        matchHeadingParams.topMargin = dp(25);
        content.addView(matchHeading, matchHeadingParams);
        matchList = column(0);
        LinearLayout.LayoutParams matchParams = params(-1, -2);
        matchParams.topMargin = dp(10);
        content.addView(matchList, matchParams);

        LinearLayout heroHeading = sectionHeading("英雄统计", "IN-GAME STATS");
        LinearLayout.LayoutParams heroHeadingParams = params(-1, -2);
        heroHeadingParams.topMargin = dp(25);
        content.addView(heroHeading, heroHeadingParams);
        heroList = column(0);
        LinearLayout.LayoutParams heroesParams = params(-1, -2);
        heroesParams.topMargin = dp(10);
        content.addView(heroList, heroesParams);

        statusView = text("数据来源  ·  Deadlock API", 10, MUTED, false);
        LinearLayout.LayoutParams statusParams = params(-1, -2);
        statusParams.topMargin = dp(24);
        statusView.setGravity(Gravity.CENTER);
        content.addView(statusView, statusParams);
        setContentView(root);
        root.requestApplyInsets();

        renderEmpty();
    }

    private LinearLayout sectionHeading(String title, String tag) {
        LinearLayout line = row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(text(title, 16, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView marker = text(tag, 9, MUTED, true);
        marker.setLetterSpacing(0.1f);
        line.addView(marker);
        return line;
    }

    private void renderEmpty() {
        setAvatarPlaceholder();
        renderMetricCards("—", "—", "—", "—", "—", "—");
        matchList.removeAllViews();
        heroList.removeAllViews();
        addEmptyLine(matchList, "输入 SteamID64 或 Steam 主页链接查看近期比赛");
        addEmptyLine(heroList, "暂无英雄统计");
    }

    private void clearLegacyProfileSnapshots() {
        SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith("profile:")) {
                editor.remove(key);
                changed = true;
            }
        }
        if (changed) editor.apply();
    }

    private String countHeroStats(DeadlockApi.PlayerData data) {
        int count = 0;
        for (int i = 0; i < data.heroStats.length(); i++) {
            JSONObject item = data.heroStats.optJSONObject(i);
            if (item != null && DeadlockApi.firstInt(item, 0, "matches_played") > 0) count++;
        }
        return String.valueOf(count);
    }

    private String rankLabel(JSONObject rank) {
        if (rank == null) return "未定级";
        int badge = DeadlockApi.firstInt(rank, 0, "badge", "rank");
        int subrank = DeadlockApi.firstInt(rank, 0, "subrank");
        if (badge <= 0) return "未定级";
        return subrank > 0 ? badge + "-" + subrank : String.valueOf(badge);
    }

    private String formatCompact(double value) {
        if (value < 1000) return String.format(Locale.US, "%.0f", value);
        if (value < 1000000) return String.format(Locale.US, "%.1fK", value / 1000.0);
        return String.format(Locale.US, "%.1fM", value / 1000000.0);
    }

    private void renderAccountSelector() {
        if (accountSelector == null || accountSelectorDetail == null) return;
        AccountStore.Account account = AccountStore.selected(preferences);
        accountSelector.setText(AccountStore.label(account));
        accountSelectorDetail.setText(AccountStore.detail(account));
    }

    private void addEmptyLine(LinearLayout parent, String message) {
        TextView empty = text(message, 13, MUTED, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(12), dp(20), dp(12), dp(20));
        parent.addView(empty);
    }

    private void showAccountPicker() {
        ArrayList<AccountStore.Account> accounts = AccountStore.load(preferences);
        if (accounts.isEmpty()) {
            focusAccountInput();
            return;
        }
        CharSequence[] labels = new CharSequence[accounts.size()];
        for (int i = 0; i < accounts.size(); i++) {
            AccountStore.Account account = accounts.get(i);
            labels[i] = AccountStore.label(account) + "\n" + AccountStore.detail(account);
        }
        new AlertDialog.Builder(this)
                .setTitle("切换查询账号")
                .setItems(labels, (dialog, which) -> selectAccount(accounts.get(which)))
                .setNegativeButton("关闭", null)
                .setNeutralButton("管理账号", (dialog, which) -> showAccountManager())
                .setPositiveButton("添加账号", (dialog, which) -> focusAccountInput())
                .show();
    }

    private void selectAccount(AccountStore.Account account) {
        if (account == null) return;
        AccountStore.markSelected(preferences, account.accountId);
        currentAccountId = account.accountId;
        setAccountInput(account);
        renderAccountSelector();
        loadProfile(account.accountId);
    }

    private void setAccountInput(AccountStore.Account account) {
        if (accountInput == null || account == null) return;
        String input = AccountStore.isSteamId(account.steamId)
                ? account.steamId : account.accountId;
        accountInput.setText(input);
        accountInput.setSelection(accountInput.length());
    }

    private void showAccountManager() {
        LinearLayout list = column(0);
        list.setPadding(dp(2), dp(4), dp(2), dp(2));
        ArrayList<AccountStore.Account> accounts = AccountStore.load(preferences);
        for (AccountStore.Account account : accounts) {
            LinearLayout item = row();
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(10), dp(10), dp(6), dp(10));
            item.setBackground(rounded(SURFACE, 12, BORDER));
            LinearLayout labels = column(0);
            TextView title = text(AccountStore.label(account), 14, TEXT, true);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView detail = text(AccountStore.detail(account), 10, MUTED, false);
            detail.setSingleLine(true);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(title);
            LinearLayout.LayoutParams detailParams = params(-1, -2);
            detailParams.topMargin = dp(4);
            labels.addView(detail, detailParams);
            item.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            TextView edit = text("编辑", 12, LIME, true);
            edit.setGravity(Gravity.CENTER);
            edit.setPadding(dp(8), 0, dp(8), 0);
            edit.setOnClickListener(view -> showEditAccountDialog(account));
            item.addView(edit, params(dp(48), dp(36)));
            item.setOnClickListener(view -> showEditAccountDialog(account));
            list.addView(item);
            if (account != accounts.get(accounts.size() - 1)) {
                View divider = new View(this);
                divider.setBackgroundColor(BORDER);
                list.addView(divider, params(-1, dp(1)));
            }
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(list);
        new AlertDialog.Builder(this)
                .setTitle("已保存账号")
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .setPositiveButton("添加账号", (dialog, which) -> focusAccountInput())
                .show();
    }

    private void showEditAccountDialog(AccountStore.Account account) {
        if (account == null) return;
        EditText noteInput = new EditText(this);
        noteInput.setSingleLine(true);
        noteInput.setText(account.note);
        noteInput.setHint("例如：我自己、朋友小明");
        noteInput.setSelectAllOnFocus(false);
        CheckBox defaultBox = new CheckBox(this);
        defaultBox.setText("设为默认账号");
        defaultBox.setTextColor(TEXT);
        defaultBox.setChecked(account.defaultAccount);
        LinearLayout form = column(0);
        form.setPadding(dp(24), dp(4), dp(24), 0);
        form.addView(noteInput, params(-1, dp(52)));
        form.addView(defaultBox, params(-1, dp(48)));

        new AlertDialog.Builder(this)
                .setTitle("编辑账号")
                .setMessage(AccountStore.detail(account))
                .setView(form)
                .setNegativeButton("取消", null)
                .setNeutralButton("删除", (dialog, which) -> confirmRemoveAccount(account))
                .setPositiveButton("保存", (dialog, which) -> {
                    account.note = noteInput.getText().toString().trim();
                    if (defaultBox.isChecked()) {
                        AccountStore.update(preferences, account);
                        AccountStore.setDefault(preferences, account.accountId);
                    } else {
                        AccountStore.update(preferences, account);
                    }
                    renderAccountSelector();
                    Toast.makeText(this, "账号信息已保存", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void confirmRemoveAccount(AccountStore.Account account) {
        new AlertDialog.Builder(this)
                .setTitle("删除账号")
                .setMessage("删除后只会移除本机保存的账号记录，不会影响 Steam 账号。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    boolean wasCurrent = account.accountId.equals(currentAccountId);
                    AccountStore.remove(preferences, account.accountId);
                    if (wasCurrent) {
                        AccountStore.Account next = AccountStore.defaultAccount(preferences);
                        currentAccountId = next == null ? "" : next.accountId;
                        renderAccountSelector();
                        if (next == null) {
                            renderEmpty();
                        } else {
                            selectAccount(next);
                        }
                    } else {
                        renderAccountSelector();
                    }
                    Toast.makeText(this, "账号已删除", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void focusAccountInput() {
        if (accountInput == null) return;
        accountInput.setText("");
        accountInput.requestFocus();
        InputMethodManager imm =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(accountInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void submitSearch() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(accountInput.getWindowToken(), 0);
        String input = accountInput.getText().toString().trim();
        if (input.isEmpty()) {
            Toast.makeText(this, "请输入 SteamID64 或 Steam 主页链接", Toast.LENGTH_SHORT).show();
            return;
        }
        resolveAndLoadProfile(input);
    }

    private void refreshCurrentProfile() {
        String accountId = currentAccountId;
        if (accountId == null || accountId.isEmpty()) {
            AccountStore.Account account = AccountStore.selected(preferences);
            accountId = account == null ? "" : account.accountId;
        }
        loadProfile(accountId);
    }

    private void resolveAndLoadProfile(String input) {
        setLoading(true);
        statusView.setText("正在解析 Steam 账号…");
        io.execute(() -> {
            try {
                SteamProfileResolver.ResolvedAccount resolved =
                        SteamProfileResolver.resolve(input);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    AccountStore.Account existing = AccountStore.findByAccountId(
                            AccountStore.load(preferences), resolved.accountId);
                    String note = existing == null ? "" : existing.note;
                    AccountStore.upsert(
                            preferences,
                            resolved.steamId,
                            resolved.accountId,
                            "",
                            "",
                            note,
                            false
                    );
                    AccountStore.markSelected(preferences, resolved.accountId);
                    accountInput.setText(resolved.steamId.isEmpty()
                            ? resolved.accountId : resolved.steamId);
                    accountInput.setSelection(accountInput.length());
                    renderAccountSelector();
                    loadProfile(resolved.accountId);
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    setLoading(false);
                    String message = exception.getMessage();
                    if (message == null || message.trim().isEmpty()) {
                        message = "无法解析 Steam 账号";
                    }
                    statusView.setText(message);
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void loadProfile(String accountId) {
        if (accountId == null || !accountId.matches("\\d{1,20}")) {
            Toast.makeText(this, "请输入有效的 SteamID64 或 Steam 主页链接",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        currentAccountId = accountId;
        AccountStore.markSelected(preferences, accountId);
        preferences.edit().putString("last_account_id", accountId).apply();
        renderAccountSelector();
        setLoading(true);
        statusView.setText(R.string.loading_player);
        io.execute(() -> {
            try {
                DeadlockApi.PlayerData data = DeadlockApi.loadPlayer(accountId);
                runOnUiThread(() -> {
                    if (accountId.equals(currentAccountId)) {
                        updateSavedAccountProfile(data);
                        renderProfile(data, System.currentTimeMillis());
                        setLoading(false);
                    }
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    if (!accountId.equals(currentAccountId)) return;
                    setLoading(false);
                    String message = exception.getMessage();
                    if (message == null || message.trim().isEmpty()) message = "连接战绩服务失败";
                    statusView.setText(message);
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void updateSavedAccountProfile(DeadlockApi.PlayerData data) {
        if (data == null) return;
        AccountStore.Account account = AccountStore.findByAccountId(
                AccountStore.load(preferences), data.accountId);
        if (account == null) return;
        JSONObject profile = data.steamProfile;
        if (profile != null) {
            String name = DeadlockApi.firstString(profile,
                    "personaname", "persona_name", "name");
            String avatar = DeadlockApi.firstString(profile,
                    "avatarfull", "avatar_full", "avatar");
            if (!name.isEmpty()) account.displayName = name;
            if (!avatar.isEmpty()) account.avatarUrl = avatar;
        }
        account.lastUsedAt = System.currentTimeMillis();
        AccountStore.update(preferences, account);
        renderAccountSelector();
    }

    private void setLoading(boolean loading) {
        loadingBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        searchButton.setEnabled(!loading);
        searchButton.setAlpha(loading ? 0.55f : 1f);
        accountInput.setEnabled(!loading);
    }

    private void renderProfile(DeadlockApi.PlayerData data, long loadedAt) {
        currentAccountId = data.accountId;
        JSONObject profile = data.steamProfile;
        String name = profile == null ? "" : DeadlockApi.firstString(profile,
                "personaname", "persona_name", "name");
        if (name.isEmpty()) name = "玩家 " + data.accountId;
        profileName.setText(name);
        profileId.setText(getString(R.string.account_id_value, data.accountId));
        String avatar = profile == null ? "" : DeadlockApi.firstString(profile,
                "avatarfull", "avatar_full", "avatar");
        if (!avatar.isEmpty()) loadAvatar(avatar);
        else setAvatarPlaceholder();
        updateStamp.setText(getString(R.string.profile_updated, timeLabel(loadedAt)));

        int historyCount = data.matches.length();
        int count = Math.min(historyCount, MAX_VISIBLE_MATCHES);
        int wins = 0;
        long kills = 0;
        long deaths = 0;
        long assists = 0;
        for (int i = 0; i < count; i++) {
            JSONObject match = data.matches.optJSONObject(i);
            if (match == null) continue;
            if (isWin(match)) wins++;
            JSONObject stats = statsObject(match);
            kills += DeadlockApi.firstLong(stats, 0, "kills", "player_kills");
            deaths += DeadlockApi.firstLong(stats, 0, "deaths", "player_deaths");
            assists += DeadlockApi.firstLong(stats, 0, "assists", "player_assists");
        }
        double winRate = count == 0 ? 0 : (wins * 100.0 / count);
        String kda = count == 0 ? "—" : String.format(Locale.US, "%.1f / %.1f / %.1f",
                kills / (double) count, deaths / (double) count, assists / (double) count);
        long totalDamage = 0;
        int totalHeroMatches = 0;
        for (int i = 0; i < data.heroStats.length(); i++) {
            JSONObject heroStat = data.heroStats.optJSONObject(i);
            if (heroStat == null) continue;
            totalDamage += DeadlockApi.firstLong(heroStat, 0, "total_player_damage");
            totalHeroMatches += DeadlockApi.firstInt(heroStat, 0, "matches_played");
        }
        String averageDamage = totalHeroMatches == 0
                ? "—" : formatCompact(totalDamage / (double) totalHeroMatches);
        String rank = rankLabel(data.rank);
        String heroCount = countHeroStats(data);
        renderMetricCards(historyCount == 0 ? "0" : String.format(Locale.US, "%.0f%%", winRate),
                kda,
                averageDamage,
                String.valueOf(historyCount),
                rank,
                heroCount);
        renderMatches(data);
        renderHeroes(data);
        statusView.setText(R.string.api_data_source);
    }

    private void renderMetricCards(String winRate, String kda, String averageDamage,
                                   String matches, String rank, String heroCount) {
        metricGrid.removeAllViews();
        LinearLayout first = row();
        LinearLayout second = row();
        LinearLayout third = row();
        first.addView(metricCard(getString(R.string.recent_win_rate), winRate, CORAL),
                new LinearLayout.LayoutParams(0, dp(80), 1));
        LinearLayout.LayoutParams secondCell = new LinearLayout.LayoutParams(0, dp(80), 1);
        secondCell.leftMargin = dp(10);
        first.addView(metricCard("场均 K / D / A", kda, LIME), secondCell);
        second.addView(metricCard("场均英雄伤害", averageDamage, LIME),
                new LinearLayout.LayoutParams(0, dp(80), 1));
        LinearLayout.LayoutParams matchCell = new LinearLayout.LayoutParams(0, dp(80), 1);
        matchCell.leftMargin = dp(10);
        second.addView(metricCard("比赛数量", matches, CORAL), matchCell);
        third.addView(metricCard("当前段位", rank, CORAL),
                new LinearLayout.LayoutParams(0, dp(80), 1));
        LinearLayout.LayoutParams heroCell = new LinearLayout.LayoutParams(0, dp(80), 1);
        heroCell.leftMargin = dp(10);
        third.addView(metricCard("使用英雄", heroCount, LIME), heroCell);
        metricGrid.addView(first);
        LinearLayout.LayoutParams secondRow = params(-1, -2);
        secondRow.topMargin = dp(10);
        metricGrid.addView(second, secondRow);
        LinearLayout.LayoutParams thirdRow = params(-1, -2);
        thirdRow.topMargin = dp(10);
        metricGrid.addView(third, thirdRow);
    }

    private View metricCard(String label, String value, int accent) {
        LinearLayout card = column(SURFACE);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(13), dp(10), dp(13), dp(10));
        card.setBackground(rounded(SURFACE, 12, BORDER));
        TextView number = text(value, value.length() > 12 ? 14 : 20, accent, true);
        number.setSingleLine(true);
        number.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView caption = text(label, 11, MUTED, false);
        LinearLayout.LayoutParams captionParams = params(-1, -2);
        captionParams.topMargin = dp(4);
        card.addView(number);
        card.addView(caption, captionParams);
        return card;
    }

    private void renderMatches(DeadlockApi.PlayerData data) {
        matchList.removeAllViews();
        if (data.matches.length() == 0) {
            addEmptyLine(matchList, "当前没有可展示的比赛");
            return;
        }
        int visible = Math.min(data.matches.length(), MAX_VISIBLE_MATCHES);
        for (int i = 0; i < visible; i++) {
            JSONObject match = data.matches.optJSONObject(i);
            if (match == null) continue;
            matchList.addView(matchRow(match, data.heroes));
            if (i < visible - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(BORDER);
                LinearLayout.LayoutParams dividerParams = params(-1, dp(1));
                dividerParams.leftMargin = dp(56);
                matchList.addView(divider, dividerParams);
            }
        }
    }

    private View matchRow(JSONObject match, Map<Integer, DeadlockApi.Hero> heroes) {
        boolean won = isWin(match);
        int heroId = DeadlockApi.firstInt(match, -1, "hero_id", "hero");
        DeadlockApi.Hero hero = heroes.get(heroId);
        String heroName = hero == null ? (heroId < 0 ? "未知英雄" : "英雄 #" + heroId) : hero.name;
        JSONObject stats = statsObject(match);
        int kills = DeadlockApi.firstInt(stats, 0, "kills", "player_kills");
        int deaths = DeadlockApi.firstInt(stats, 0, "deaths", "player_deaths");
        int assists = DeadlockApi.firstInt(stats, 0, "assists", "player_assists");
        String idLabel = match.opt("match_id") == null ? "Deadlock" :
                "比赛 #" + match.optString("match_id");

        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(9), dp(8), dp(9));
        row.setBackground(rounded(SURFACE, 12, BORDER));
        row.setOnClickListener(view -> showMatchDetails(
                match, heroName, won, kills, deaths, assists, heroes));
        row.addView(resultBadge(won), params(dp(52), dp(32)));
        ImageView portrait = new ImageView(this);
        portrait.setScaleType(ImageView.ScaleType.CENTER_CROP);
        portrait.setBackground(rounded(won ? LIME : CORAL, 10, 0));
        portrait.setClipToOutline(true);
        portrait.setContentDescription(heroName);
        LinearLayout.LayoutParams portraitParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        portraitParams.leftMargin = dp(7);
        row.addView(portrait, portraitParams);
        if (hero != null && !hero.imageUrl.isEmpty()) loadImage(hero.imageUrl, portrait);
        LinearLayout details = column(0);
        details.setPadding(dp(10), 0, dp(5), 0);
        TextView heroText = text(heroName, 14, TEXT, true);
        heroText.setSingleLine(true);
        heroText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.addView(heroText);
        TextView meta = text(idLabel + "  ·  " + modeLabel(match)
                + "  ·  " + durationLabel(match) + "  ·  " + matchDate(match),
                10, MUTED, false);
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metaParams = params(-1, -2);
        metaParams.topMargin = dp(3);
        details.addView(meta, metaParams);
        row.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        TextView kda = text(kills + " / " + deaths + " / " + assists, 12,
                won ? LIME : TEXT, true);
        kda.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(kda, params(-2, -2));
        return row;
    }

    private TextView resultBadge(boolean won) {
        TextView result = text(won ? "胜利" : "失败", 12, BG, true);
        result.setGravity(Gravity.CENTER);
        result.setBackground(rounded(won ? LIME : CORAL, 9, 0));
        return result;
    }

    private String modeLabel(JSONObject match) {
        int mode = DeadlockApi.firstInt(match, -1, "match_mode", "game_mode");
        if (mode == 1) return "标准";
        if (mode == 2) return "排位";
        if (mode < 0) return "模式未知";
        return "模式 " + mode;
    }

    private void showMatchDetails(JSONObject match, String heroName, boolean won,
                                  int kills, int deaths, int assists,
                                  Map<Integer, DeadlockApi.Hero> heroes) {
        String matchId = match.optString("match_id", "").trim();
        if (matchId.isEmpty()) {
            showBasicMatchDetails(match, heroName, won, kills, deaths, assists);
            return;
        }

        AlertDialog loading = new AlertDialog.Builder(this)
                .setTitle(R.string.match_details)
                .setMessage("正在加载完整阵容和对局数据…")
                .setNegativeButton("取消", null)
                .create();
        loading.show();
        io.execute(() -> {
            try {
                JSONObject metadata = DeadlockApi.loadMatchMetadata(matchId);
                JSONObject info = metadata.optJSONObject("match_info");
                JSONArray players = info == null ? null : info.optJSONArray("players");
                ArrayList<String> accountIds = new ArrayList<>();
                if (players != null) {
                    for (int i = 0; i < players.length(); i++) {
                        JSONObject player = players.optJSONObject(i);
                        if (player == null) continue;
                        String accountId = String.valueOf(player.opt("account_id"));
                        if (accountId.matches("[0-9]{1,20}")) accountIds.add(accountId);
                    }
                }
                Map<String, JSONObject> profiles =
                        DeadlockApi.loadSteamProfiles(accountIds);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    loading.dismiss();
                    renderMatchDetails(match, heroName, won, kills, deaths, assists,
                            metadata, profiles, heroes);
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    loading.dismiss();
                    showBasicMatchDetails(match, heroName, won, kills, deaths, assists);
                    Toast.makeText(this, "完整比赛数据暂时不可用", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showBasicMatchDetails(JSONObject match, String heroName, boolean won,
                                       int kills, int deaths, int assists) {
        StringBuilder message = new StringBuilder();
        message.append(won ? "胜利" : "失败").append("\n");
        message.append("英雄  ").append(heroName).append("\n");
        message.append("比赛  #").append(match.optString("match_id", "未知")).append("\n");
        message.append("模式  ").append(modeLabel(match)).append("\n");
        message.append("时间  ").append(matchDate(match)).append("\n");
        message.append("时长  ").append(durationLabel(match)).append("\n");
        message.append("K / D / A  ").append(kills).append(" / ")
                .append(deaths).append(" / ").append(assists).append("\n");
        message.append("经济  ").append(formatNumber(
                DeadlockApi.firstLong(match, 0, "net_worth"))).append("\n");
        message.append("补刀  ").append(DeadlockApi.firstInt(match, 0, "last_hits"))
                .append("    否决  ").append(DeadlockApi.firstInt(match, 0, "denies"));
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.match_details))
                .setMessage(message.toString().trim())
                .setPositiveButton("关闭", null)
                .show();
    }

    private void renderMatchDetails(JSONObject match, String heroName, boolean won,
                                    int kills, int deaths, int assists,
                                    JSONObject metadata,
                                    Map<String, JSONObject> profiles,
                                    Map<Integer, DeadlockApi.Hero> heroes) {
        JSONObject info = metadata.optJSONObject("match_info");
        if (info == null) {
            showBasicMatchDetails(match, heroName, won, kills, deaths, assists);
            return;
        }

        LinearLayout content = column(0);
        content.setPadding(dp(4), dp(2), dp(4), dp(8));
        LinearLayout summary = row();
        summary.setGravity(Gravity.CENTER_VERTICAL);
        summary.addView(resultBadge(won), params(dp(52), dp(32)));
        LinearLayout summaryText = column(0);
        summaryText.setPadding(dp(10), 0, 0, 0);
        summaryText.addView(text(heroName + "  ·  " + modeLabel(match),
                15, TEXT, true));
        summaryText.addView(text("#" + match.optString("match_id", "未知")
                        + "  ·  " + durationLabel(info)
                        + "  ·  " + matchDate(match), 11, MUTED, false),
                topMargin(params(-1, -2), 3));
        summary.addView(summaryText, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(summary);

        addDetailSection(content, "比赛概览");
        addDetailPair(content, "最终结果",
                winningTeamLabel(info, DeadlockApi.firstInt(info, -1, "winning_team")));
        addDetailPair(content, "比赛模式", modeLabel(match));
        addDetailPair(content, "比赛时长", durationLabel(info));

        JSONArray players = info.optJSONArray("players");
        if (players == null || players.length() == 0) {
            addEmptyLine(content, "没有可用的阵容数据");
        } else {
            int winningTeam = DeadlockApi.firstInt(info, -1, "winning_team");
            for (int team = 0; team <= 1; team++) {
                addDetailSection(content, winningTeamLabel(info, team)
                        + "  ·  队伍 " + team);
                for (int i = 0; i < players.length(); i++) {
                    JSONObject player = players.optJSONObject(i);
                    if (player == null || DeadlockApi.firstInt(player, -1, "team") != team) {
                        continue;
                    }
                    content.addView(matchPlayerRow(player, profiles, heroes,
                            String.valueOf(player.opt("account_id"))
                                    .equals(currentAccountId)));
                }
            }
            JSONObject currentPlayer = findPlayer(players, currentAccountId);
            if (currentPlayer != null) {
                addDetailSection(content, "我的对局数据");
                addPlayerStats(content, currentPlayer);
            }
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        new AlertDialog.Builder(this)
                .setTitle("比赛 #" + match.optString("match_id", "未知"))
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .show();
    }

    private View matchPlayerRow(JSONObject player, Map<String, JSONObject> profiles,
                                Map<Integer, DeadlockApi.Hero> heroes, boolean current) {
        int heroId = DeadlockApi.firstInt(player, -1, "hero_id");
        DeadlockApi.Hero hero = heroes.get(heroId);
        String heroName = hero == null ? "英雄 #" + heroId : hero.name;
        String accountId = String.valueOf(player.opt("account_id"));
        JSONObject profile = profiles.get(accountId);
        String name = profile == null ? accountId : DeadlockApi.firstString(
                profile, "personaname", "persona_name", "name");
        if (name.isEmpty()) name = accountId;

        LinearLayout row = row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(8), dp(6), dp(8));
        row.setBackground(rounded(current ? SURFACE_ALT : SURFACE, 10,
                current ? LIME : BORDER));
        ImageView portrait = new ImageView(this);
        portrait.setScaleType(ImageView.ScaleType.CENTER_CROP);
        portrait.setBackground(rounded(CORAL, 8, 0));
        portrait.setClipToOutline(true);
        row.addView(portrait, params(dp(34), dp(34)));
        if (hero != null && !hero.imageUrl.isEmpty()) loadImage(hero.imageUrl, portrait);

        LinearLayout labels = column(0);
        labels.setPadding(dp(9), 0, dp(6), 0);
        TextView playerName = text(name + (current ? "  ·  我" : ""),
                13, current ? LIME : TEXT, true);
        playerName.setSingleLine(true);
        playerName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(playerName);
        labels.addView(text(heroName + "  ·  Lv." + DeadlockApi.firstInt(
                player, 0, "level"), 10, MUTED, false),
                topMargin(params(-1, -2), 3));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout values = column(0);
        values.setGravity(Gravity.END);
        values.addView(text(DeadlockApi.firstInt(player, 0, "kills") + " / "
                        + DeadlockApi.firstInt(player, 0, "deaths") + " / "
                        + DeadlockApi.firstInt(player, 0, "assists"),
                12, current ? LIME : TEXT, true));
        values.addView(text(formatCompact(DeadlockApi.firstLong(player, 0, "net_worth"))
                        + " 经济", 10, MUTED, false),
                topMargin(params(-2, -2), 3));
        row.addView(values, params(-2, -2));
        return row;
    }

    private void addPlayerStats(LinearLayout content, JSONObject player) {
        JSONObject finalStats = lastObject(player.optJSONArray("stats"));
        addDetailPair(content, "K / D / A",
                DeadlockApi.firstInt(player, 0, "kills") + " / "
                        + DeadlockApi.firstInt(player, 0, "deaths") + " / "
                        + DeadlockApi.firstInt(player, 0, "assists"));
        addDetailPair(content, "等级 / 经济",
                DeadlockApi.firstInt(player, 0, "level") + "  /  "
                        + formatNumber(DeadlockApi.firstLong(player, 0, "net_worth")));
        addDetailPair(content, "补刀 / 否决",
                DeadlockApi.firstInt(player, 0, "last_hits") + "  /  "
                        + DeadlockApi.firstInt(player, 0, "denies"));
        if (finalStats != null) {
            addDetailPair(content, "玩家伤害 / 承伤",
                    formatNumber(DeadlockApi.firstLong(finalStats, 0, "player_damage"))
                            + "  /  "
                            + formatNumber(DeadlockApi.firstLong(
                            finalStats, 0, "player_damage_taken")));
            addDetailPair(content, "治疗 / 技能击杀",
                    formatNumber(DeadlockApi.firstLong(finalStats, 0, "player_healing"))
                            + "  /  "
                            + DeadlockApi.firstInt(finalStats, 0, "ability_kills"));
            addDetailPair(content, "小兵 / 中立 / Boss 伤害",
                    formatNumber(DeadlockApi.firstLong(finalStats, 0, "creep_damage"))
                            + "  /  "
                            + formatNumber(DeadlockApi.firstLong(finalStats, 0, "neutral_damage"))
                            + "  /  "
                            + formatNumber(DeadlockApi.firstLong(finalStats, 0, "boss_damage")));
            addDetailPair(content, "命中 / 暴击",
                    formatNumber(DeadlockApi.firstLong(finalStats, 0, "shots_hit"))
                            + "  /  "
                            + formatNumber(DeadlockApi.firstLong(
                            finalStats, 0, "hero_bullets_hit_crit")));
        }
        int mvp = DeadlockApi.firstInt(player, 0, "mvp_rank");
        if (mvp > 0) addDetailPair(content, "MVP 排名", "#" + mvp);
    }

    private JSONObject findPlayer(JSONArray players, String accountId) {
        for (int i = 0; i < players.length(); i++) {
            JSONObject player = players.optJSONObject(i);
            if (player != null && String.valueOf(player.opt("account_id"))
                    .equals(accountId)) return player;
        }
        return null;
    }

    private JSONObject lastObject(JSONArray array) {
        if (array == null) return null;
        for (int i = array.length() - 1; i >= 0; i--) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) return item;
        }
        return null;
    }

    private String winningTeamLabel(JSONObject info, int team) {
        if (team < 0) return "结果未知";
        int winning = DeadlockApi.firstInt(info, -1, "winning_team");
        if (winning == team) return "胜方";
        return "败方";
    }

    private void addDetailSection(LinearLayout content, String title) {
        TextView section = text(title, 13, CORAL, true);
        LinearLayout.LayoutParams sectionParams = params(-1, -2);
        sectionParams.topMargin = dp(15);
        sectionParams.bottomMargin = dp(6);
        content.addView(section, sectionParams);
    }

    private void addDetailPair(LinearLayout content, String label, String value) {
        LinearLayout line = row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(8), dp(7), dp(8), dp(7));
        line.setBackground(rounded(SURFACE, 8, BORDER));
        line.addView(text(label, 11, MUTED, false),
                new LinearLayout.LayoutParams(0, -2, 1));
        TextView valueView = text(value, 11, TEXT, true);
        valueView.setGravity(Gravity.END);
        line.addView(valueView, params(-2, -2));
        LinearLayout.LayoutParams lineParams = params(-1, -2);
        lineParams.bottomMargin = dp(4);
        content.addView(line, lineParams);
    }

    private String durationLabel(JSONObject match) {
        long seconds = DeadlockApi.firstLong(match, 0, "match_duration_s", "duration_s");
        if (seconds <= 0) return "未知";
        long minutes = seconds / 60;
        long remainder = seconds % 60;
        return String.format(Locale.getDefault(), "%d:%02d", minutes, remainder);
    }

    private String formatNumber(long value) {
        return String.format(Locale.getDefault(), "%,d", value);
    }

    private void renderHeroes(DeadlockApi.PlayerData data) {
        heroList.removeAllViews();
        ArrayList<JSONObject> stats = new ArrayList<>();
        for (int i = 0; i < data.heroStats.length(); i++) {
            JSONObject item = data.heroStats.optJSONObject(i);
            if (item != null) stats.add(item);
        }
        if (stats.isEmpty()) stats = aggregateHeroStats(data.matches);
        Collections.sort(stats, Comparator.comparingInt(
                item -> -DeadlockApi.firstInt(item, 0,
                        "matches_played", "match_count", "matches", "games_played")));
        if (stats.isEmpty()) {
            addEmptyLine(heroList, "暂无英雄统计");
            return;
        }
        int size = Math.min(stats.size(), 6);
        for (int i = 0; i < size; i++) {
            JSONObject item = stats.get(i);
            int heroId = DeadlockApi.firstInt(item, -1, "hero_id", "hero");
            int played = DeadlockApi.firstInt(item, 0,
                    "matches_played", "match_count", "matches", "games_played");
            int won = DeadlockApi.firstInt(item, 0, "wins", "won_matches", "wins_count");
            if (played == 0) played = 1;
            int reportedRate = DeadlockApi.firstInt(item, -1, "win_rate", "winrate");
            double rate = reportedRate >= 0
                    ? (reportedRate <= 1 ? reportedRate * 100.0 : reportedRate)
                    : (won * 100.0 / played);
            DeadlockApi.Hero hero = data.heroes.get(heroId);
            String name = hero == null
                    ? (heroId < 0 ? "未知英雄" : "英雄 #" + heroId) : hero.name;
            heroList.addView(heroRow(item, name, rate, hero));
            if (i < size - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(BORDER);
                LinearLayout.LayoutParams dividerParams = params(-1, dp(1));
                dividerParams.leftMargin = dp(12);
                heroList.addView(divider, dividerParams);
            }
        }
    }

    private View heroRow(JSONObject stats, String name, double winRate,
                         DeadlockApi.Hero hero) {
        int matches = DeadlockApi.firstInt(stats, 0, "matches_played");
        long kills = DeadlockApi.firstLong(stats, 0, "kills");
        long deaths = DeadlockApi.firstLong(stats, 0, "deaths");
        long assists = DeadlockApi.firstLong(stats, 0, "assists");
        long damage = DeadlockApi.firstLong(stats, 0, "total_player_damage");
        long damageTaken = DeadlockApi.firstLong(stats, 0, "total_player_damage_taken");
        LinearLayout row = row();
        row.setGravity(Gravity.TOP);
        row.setPadding(dp(4), dp(10), dp(4), dp(10));
        row.setBackground(rounded(SURFACE, 10, BORDER));
        row.setOnClickListener(view -> showHeroStats(stats, name));
        ImageView portrait = new ImageView(this);
        portrait.setScaleType(ImageView.ScaleType.CENTER_CROP);
        portrait.setBackground(rounded(CORAL, 11, 0));
        portrait.setClipToOutline(true);
        portrait.setContentDescription(name);
        row.addView(portrait, new LinearLayout.LayoutParams(dp(36), dp(36)));
        if (hero != null && !hero.imageUrl.isEmpty()) loadImage(hero.imageUrl, portrait);
        TextView heroName = text(name, 13, TEXT, true);
        heroName.setSingleLine(true);
        heroName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout details = column(0);
        LinearLayout.LayoutParams heroNameParams = params(-1, -2);
        heroNameParams.leftMargin = dp(11);
        details.addView(heroName, heroNameParams);
        details.addView(text(String.format(Locale.US, "胜率 %.0f%%  ·  %d 场",
                winRate, matches), 10, LIME, false),
                topMargin(params(-1, -2), 4));
        details.addView(text("KDA  " + averageLabel(kills, matches) + " / "
                        + averageLabel(deaths, matches) + " / "
                        + averageLabel(assists, matches), 10, MUTED, false),
                topMargin(params(-1, -2), 3));
        details.addView(text("伤害  " + formatCompact(damage)
                        + "  ·  承伤  " + formatCompact(damageTaken), 10, MUTED, false),
                topMargin(params(-1, -2), 3));
        row.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = text("›", 20, MUTED, false);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, params(dp(18), dp(42)));
        return row;
    }

    private String averageLabel(long total, int matches) {
        return matches <= 0 ? "—" : String.format(Locale.US, "%.1f",
                total / (double) matches);
    }

    private void showHeroStats(JSONObject stats, String heroName) {
        int matches = DeadlockApi.firstInt(stats, 0, "matches_played");
        int wins = DeadlockApi.firstInt(stats, 0, "wins");
        double rate = matches <= 0 ? 0 : wins * 100.0 / matches;
        LinearLayout content = column(0);
        content.setPadding(dp(4), dp(2), dp(4), dp(8));
        addDetailSection(content, heroName + "  ·  对局统计");
        addDetailPair(content, "场次 / 胜场 / 胜率",
                matches + "  /  " + wins + "  /  "
                        + String.format(Locale.US, "%.1f%%", rate));
        addDetailPair(content, "平均 K / D / A",
                averageLabel(DeadlockApi.firstLong(stats, 0, "kills"), matches)
                        + "  /  "
                        + averageLabel(DeadlockApi.firstLong(stats, 0, "deaths"), matches)
                        + "  /  "
                        + averageLabel(DeadlockApi.firstLong(stats, 0, "assists"), matches));
        addDetailPair(content, "总玩家伤害 / 承伤",
                formatNumber(DeadlockApi.firstLong(stats, 0, "total_player_damage"))
                        + "  /  "
                        + formatNumber(DeadlockApi.firstLong(
                        stats, 0, "total_player_damage_taken")));
        addDetailPair(content, "Boss / 小兵 / 中立伤害",
                formatNumber(DeadlockApi.firstLong(stats, 0, "total_boss_damage"))
                        + "  /  "
                        + formatNumber(DeadlockApi.firstLong(
                        stats, 0, "total_creep_damage"))
                        + "  /  "
                        + formatNumber(DeadlockApi.firstLong(
                        stats, 0, "total_neutral_damage")));
        addDetailPair(content, "每分钟伤害 / 承伤",
                formatCompact(DeadlockApi.firstDouble(stats, 0, "damage_per_min"))
                        + "  /  "
                        + formatCompact(DeadlockApi.firstDouble(
                        stats, 0, "damage_taken_per_min")));
        addDetailPair(content, "每分钟补刀 / 经济",
                String.format(Locale.US, "%.2f", DeadlockApi.firstDouble(
                        stats, 0, "last_hits_per_min"))
                        + "  /  "
                        + formatCompact(DeadlockApi.firstDouble(
                        stats, 0, "networth_per_min")));
        addDetailPair(content, "命中率 / 暴击率",
                percentLabel(DeadlockApi.firstDouble(stats, 0, "accuracy"))
                        + "  /  "
                        + percentLabel(DeadlockApi.firstDouble(
                        stats, 0, "crit_shot_rate")));
        JSONArray mvpCounts = stats.optJSONArray("mvp_rank_counts");
        if (mvpCounts != null) {
            addDetailPair(content, "MVP 排名次数",
                    "第1 " + mvpCounts.optInt(0, 0)
                            + "  ·  第2 " + mvpCounts.optInt(1, 0)
                            + "  ·  第3 " + mvpCounts.optInt(2, 0));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        new AlertDialog.Builder(this)
                .setTitle("英雄统计")
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .show();
    }

    private String percentLabel(double value) {
        double percent = value <= 1.0 ? value * 100.0 : value;
        return String.format(Locale.US, "%.1f%%", percent);
    }

    private ArrayList<JSONObject> aggregateHeroStats(JSONArray matches) {
        HashMap<Integer, JSONObject> totals = new HashMap<>();
        for (int i = 0; i < matches.length(); i++) {
            JSONObject match = matches.optJSONObject(i);
            if (match == null) continue;
            int heroId = DeadlockApi.firstInt(match, -1, "hero_id", "hero");
            if (heroId < 0) continue;
            JSONObject stat = totals.get(heroId);
            if (stat == null) {
                stat = new JSONObject();
                try {
                    stat.put("hero_id", heroId);
                    stat.put("matches_played", 0);
                    stat.put("wins", 0);
                } catch (JSONException ignored) {
                }
                totals.put(heroId, stat);
            }
            try {
                stat.put("matches_played", stat.optInt("matches_played") + 1);
                if (isWin(match)) stat.put("wins", stat.optInt("wins") + 1);
            } catch (JSONException ignored) {
            }
        }
        return new ArrayList<>(totals.values());
    }

    private boolean isWin(JSONObject match) {
        JSONObject stats = statsObject(match);
        int playerTeam = DeadlockApi.firstInt(match, -1, "player_team", "team");
        int matchResult = DeadlockApi.firstInt(match, -1, "match_result",
                "winning_team", "winning_team_id");
        if (playerTeam >= 0 && matchResult >= 0) return playerTeam == matchResult;
        int outcome = DeadlockApi.firstInt(match, -1,
                "player_match_outcome", "won", "player_won");
        if (outcome == 1) return true;
        if (outcome == 2 || outcome == 0) return false;
        String outcomeText = DeadlockApi.firstString(match, "outcome", "result").toLowerCase(Locale.ROOT);
        if (outcomeText.contains("win") || outcomeText.contains("victory")) return true;
        if (outcomeText.contains("loss") || outcomeText.contains("defeat")) return false;
        return stats.optBoolean("won", false);
    }

    private JSONObject statsObject(JSONObject match) {
        JSONObject stats = match.optJSONObject("player_match_stats");
        if (stats == null) stats = match.optJSONObject("player_stats");
        if (stats == null) stats = match.optJSONObject("player");
        return stats == null ? match : stats;
    }

    private String matchDate(JSONObject match) {
        long seconds = DeadlockApi.firstLong(match, 0, "start_time", "start_timestamp");
        if (seconds == 0) return "时间未知";
        long millis = seconds > 100000000000L ? seconds : seconds * 1000L;
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    private String timeLabel(long millis) {
        if (millis <= 0) return "刚刚";
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    private void loadAvatar(String url) {
        loadImage(url, avatarView);
    }

    private void loadImage(String url, ImageView target) {
        target.setTag(url);
        io.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(10000);
                connection.setInstanceFollowRedirects(true);
                try (InputStream stream = connection.getInputStream()) {
                    Bitmap bitmap = BitmapFactory.decodeStream(stream);
                    if (bitmap != null) runOnUiThread(() -> {
                        if (url.equals(target.getTag())) target.setImageBitmap(bitmap);
                    });
                }
            } catch (Exception ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private void setAvatarPlaceholder() {
        avatarView.setImageDrawable(null);
        avatarView.setBackground(rounded(CORAL, 99, 0));
        avatarView.setContentDescription("玩家头像");
    }

    private void openUpdateSettingsOrCheck() {
        checkForUpdate(true);
    }

    private void checkForUpdate(boolean manual) {
        if (!updateCheckRunning.compareAndSet(false, true)) return;
        io.execute(() -> {
            try {
                UpdateClient.UpdateInfo update = UpdateClient.check();
                runOnUiThread(() -> {
                    if (update == null) {
                        if (manual) Toast.makeText(this, "当前已是最新版本", Toast.LENGTH_SHORT).show();
                    } else {
                        showUpdateDialog(update);
                    }
                });
            } catch (Exception exception) {
                if (manual) runOnUiThread(() -> Toast.makeText(this,
                        exception.getMessage() == null ? "检查更新失败" : exception.getMessage(),
                        Toast.LENGTH_LONG).show());
            } finally {
                updateCheckRunning.set(false);
            }
        });
    }

    private void showUpdateDialog(UpdateClient.UpdateInfo update) {
        String message = "发现新版本  " + update.versionName;
        if (!update.releaseNotes.isEmpty()) message += "\n\n" + update.releaseNotes;
        new AlertDialog.Builder(this)
                .setTitle("DeadData 更新")
                .setMessage(message)
                .setNegativeButton("稍后", null)
                .setPositiveButton("下载并安装", (dialog, which) -> downloadUpdate(update))
                .show();
    }

    private void downloadUpdate(UpdateClient.UpdateInfo update) {
        statusView.setText(getString(R.string.downloading_version, update.versionName));
        io.execute(() -> {
            try {
                File apk = UpdateClient.download(this, update, (downloaded, total) -> {
                    if (total > 0) {
                        int percent = (int) Math.min(100, downloaded * 100L / total);
                        runOnUiThread(() -> statusView.setText(
                                getString(R.string.download_percent, percent)));
                    }
                });
                runOnUiThread(() -> {
                    pendingApk = apk;
                    beginInstall(apk);
                });
            } catch (Exception exception) {
                runOnUiThread(() -> {
                    String message = exception.getMessage() == null ? "更新下载失败" : exception.getMessage();
                    statusView.setText(message);
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void beginInstall(File apk) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle("允许安装更新")
                    .setMessage("Android 需要你允许 DeadData 安装自己下载的更新包。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("前往设置", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName()));
                        startActivityForResult(intent, 301);
                    })
                    .show();
            return;
        }
        launchInstaller(apk);
    }

    private void launchInstaller(File apk) {
        try {
            Uri uri = ApkContentProvider.uriFor(this, apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception exception) {
            Toast.makeText(this, "无法打开系统安装器: " + exception.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void setAvatarFallbackText() {
        avatarView.setImageDrawable(null);
    }

    private LinearLayout column(int background) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        if (background != 0) layout.setBackgroundColor(background);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private LinearLayout.LayoutParams iconButtonParams() {
        return new LinearLayout.LayoutParams(dp(42), dp(42));
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setIncludeFontPadding(false);
        return view;
    }

    private GradientDrawable rounded(int color, int radiusDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private LinearLayout.LayoutParams params(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private LinearLayout.LayoutParams topMargin(LinearLayout.LayoutParams layoutParams,
                                                int marginDp) {
        layoutParams.topMargin = dp(marginDp);
        return layoutParams;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

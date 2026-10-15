package app.deaddata.mobile.auth;

import android.net.Uri;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts user-facing Steam identifiers into the numeric identity required by
 * the Deadlock API. No DeadData server is involved in this process.
 */
public final class SteamProfileResolver {
    private static final String STEAM_HOST = "steamcommunity.com";
    private static final long ACCOUNT_BASE = 76561197960265728L;
    private static final int CONNECT_TIMEOUT_MS = 12000;
    private static final int READ_TIMEOUT_MS = 18000;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final Pattern STEAM_ID_PATTERN = Pattern.compile("[0-9]{17}");
    private static final Pattern XML_STEAM_ID_PATTERN = Pattern.compile(
            "<steamID64[^>]*>\\s*([0-9]{17})\\s*</steamID64>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_STEAM_ID_PATTERN = Pattern.compile(
            "\"steamid\"\\s*:\\s*\"([0-9]{17})\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DATA_STEAM_ID_PATTERN = Pattern.compile(
            "(?:data-steamid|data-steam-id)\\s*=\\s*[\"']([0-9]{17})[\"']",
            Pattern.CASE_INSENSITIVE);

    private SteamProfileResolver() {
    }

    public static ResolvedAccount resolve(String input) throws IOException {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) throw new IOException("请输入 SteamID64 或 Steam 个人主页链接");
        if (STEAM_ID_PATTERN.matcher(value).matches()) return fromSteamId(value);
        if (value.matches("[0-9]{1,20}")) return new ResolvedAccount("", value, "");

        Uri uri = parseUri(value);
        List<String> segments = uri.getPathSegments();
        if (segments.size() < 2) throw new IOException("Steam 主页链接格式无效");
        String type = segments.get(0).toLowerCase(Locale.ROOT);
        String identifier = segments.get(1);
        if ("profiles".equals(type) && STEAM_ID_PATTERN.matcher(identifier).matches()) {
            return fromSteamId(identifier);
        }
        if ("id".equals(type) && !identifier.isEmpty()) {
            if (STEAM_ID_PATTERN.matcher(identifier).matches()) return fromSteamId(identifier);
            if (!identifier.matches("[A-Za-z0-9_.-]{1,128}")) {
                throw new IOException("Steam 自定义主页名称无效");
            }
            return resolveVanityDirectly(identifier);
        }
        throw new IOException("只支持 Steam /profiles/数字ID 或 /id/自定义名称 链接");
    }

    private static Uri parseUri(String input) throws IOException {
        String value = input;
        if (!value.matches("(?i)^https?://.*")) value = "https://" + value;
        Uri uri = Uri.parse(value);
        String host = uri.getHost();
        if (host == null || (!STEAM_HOST.equalsIgnoreCase(host)
                && !("www." + STEAM_HOST).equalsIgnoreCase(host))) {
            throw new IOException("链接不是 Steam 个人主页");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getFragment() != null
                || uri.getUserInfo() != null
                || uri.getPort() != -1) {
            throw new IOException("Steam 主页链接必须使用 HTTPS");
        }
        return uri;
    }

    private static ResolvedAccount resolveVanityDirectly(String vanity) throws IOException {
        Uri uri = new Uri.Builder()
                .scheme("https")
                .authority(STEAM_HOST)
                .appendPath("id")
                .appendPath(vanity)
                .appendQueryParameter("xml", "1")
                .build();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/xml,text/html");
            connection.setRequestProperty("User-Agent", "DeadData-Android/1.4");
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = readFully(stream);
            if (status < 200 || status >= 300) {
                throw new IOException("Steam 主页暂时无法访问 (" + status + ")");
            }

            String steamId = extractSteamId(body);
            if (steamId.isEmpty()) steamId = extractSteamId(connection.getURL().toString());
            if (steamId.isEmpty()) {
                throw new IOException("无法从该 Steam 主页解析 SteamID64");
            }
            return fromSteamId(steamId);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String extractSteamId(String value) {
        if (value == null || value.isEmpty()) return "";
        for (Pattern pattern : new Pattern[]{
                XML_STEAM_ID_PATTERN, JSON_STEAM_ID_PATTERN, DATA_STEAM_ID_PATTERN
        }) {
            Matcher matcher = pattern.matcher(value);
            if (matcher.find()) return matcher.group(1);
        }
        Matcher profile = Pattern.compile(
                "/profiles/([0-9]{17})(?:[/\"?]|$)", Pattern.CASE_INSENSITIVE)
                .matcher(value);
        return profile.find() ? profile.group(1) : "";
    }

    private static String readFully(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (body.length() + line.length() + 1 > MAX_RESPONSE_BYTES) {
                    throw new IOException("Steam 主页响应过大");
                }
                body.append(line).append('\n');
            }
        }
        return body.toString();
    }

    private static ResolvedAccount fromSteamId(String steamId) throws IOException {
        return new ResolvedAccount(
                steamId,
                toAccountId(steamId),
                "https://steamcommunity.com/profiles/" + steamId
        );
    }

    private static String toAccountId(String steamId) throws IOException {
        if (steamId == null || !STEAM_ID_PATTERN.matcher(steamId).matches()) {
            throw new IOException("Steam64 ID 无效");
        }
        long accountId = Long.parseLong(steamId) - ACCOUNT_BASE;
        if (accountId <= 0 || accountId > 0xFFFFFFFFL) {
            throw new IOException("Steam64 ID 无法转换为 Deadlock account ID");
        }
        return Long.toString(accountId);
    }

    public static final class ResolvedAccount {
        public final String steamId;
        public final String accountId;
        public final String profileUrl;

        public ResolvedAccount(String steamId, String accountId, String profileUrl) {
            this.steamId = steamId == null ? "" : steamId;
            this.accountId = accountId == null ? "" : accountId;
            this.profileUrl = profileUrl == null ? "" : profileUrl;
        }
    }
}

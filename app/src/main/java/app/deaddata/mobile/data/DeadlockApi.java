package app.deaddata.mobile.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DeadlockApi {
    private static final String BASE_URL = "https://api.deadlock-api.com";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15000;

    private DeadlockApi() {
    }

    public static PlayerData loadPlayer(String accountId) throws IOException, JSONException {
        String historyBody = get("/v1/players/" + accountId + "/match-history");
        JSONArray matches = arrayFrom(new JSONTokener(historyBody).nextValue(),
                "matches", "data", "result");
        if (matches == null) {
            throw new IOException("战绩接口返回了无法识别的数据");
        }

        JSONArray heroStats = optionalArray("/v1/players/hero-stats?account_ids=" + accountId,
                "players", "hero_stats", "data", "result");
        JSONObject steamProfile = optionalObject("/v1/players/steam?account_ids=" + accountId,
                accountId, "players", "data", "result");
        JSONObject rank = optionalDirectObject("/v1/players/" + accountId + "/rank");
        Map<Integer, Hero> heroes = loadHeroes();

        return new PlayerData(accountId, matches, heroStats, steamProfile, rank, heroes);
    }

    public static JSONObject loadMatchMetadata(String matchId) throws IOException, JSONException {
        Object value = new JSONTokener(get("/v1/matches/" + matchId + "/metadata")).nextValue();
        if (!(value instanceof JSONObject)) {
            throw new IOException("比赛详情接口返回了无法识别的数据");
        }
        return (JSONObject) value;
    }

    public static Map<String, JSONObject> loadSteamProfiles(List<String> accountIds)
            throws IOException, JSONException {
        Map<String, JSONObject> result = new HashMap<>();
        if (accountIds == null || accountIds.isEmpty()) return result;
        StringBuilder query = new StringBuilder();
        for (String accountId : accountIds) {
            if (accountId == null || !accountId.matches("[0-9]{1,20}")) continue;
            if (query.length() > 0) query.append(',');
            query.append(accountId);
        }
        if (query.length() == 0) return result;
        Object value = new JSONTokener(
                get("/v1/players/steam?account_ids=" + query)
        ).nextValue();
        JSONArray array = value instanceof JSONArray
                ? (JSONArray) value
                : arrayFrom(value, "players", "data", "result");
        if (array == null && value instanceof JSONObject) {
            array = new JSONArray().put(value);
        }
        if (array == null) return result;
        for (int i = 0; i < array.length(); i++) {
            JSONObject profile = array.optJSONObject(i);
            if (profile == null) continue;
            Object rawAccountId = profile.opt("account_id");
            String accountId = rawAccountId == null
                    ? "" : String.valueOf(rawAccountId);
            if (accountId.matches("[0-9]{1,20}")) result.put(accountId, profile);
        }
        return result;
    }

    private static JSONObject optionalObject(String path, String accountId, String... keys) {
        try {
            Object value = new JSONTokener(get(path)).nextValue();
            return objectFrom(value, accountId, keys);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JSONObject optionalDirectObject(String path) {
        try {
            Object value = new JSONTokener(get(path)).nextValue();
            if (value instanceof JSONObject) return (JSONObject) value;
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JSONArray optionalArray(String path, String... keys) {
        try {
            Object value = new JSONTokener(get(path)).nextValue();
            JSONArray array = arrayFrom(value, keys);
            return array == null ? new JSONArray() : array;
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static Map<Integer, Hero> loadHeroes() {
        HashMap<Integer, Hero> result = new HashMap<>();
        try {
            Object value = new JSONTokener(get("/v1/assets/heroes")).nextValue();
            JSONArray array = arrayFrom(value, "heroes", "data", "result");
            if (array == null) return result;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                int id = firstInt(item, -1, "id", "hero_id");
                if (id < 0) continue;
                String name = firstString(item, "name", "localized_name", "class_name");
                if (name.isEmpty()) name = "英雄 #" + id;
                String image = imageUrl(item);
                result.put(id, new Hero(id, name, image));
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private static String imageUrl(JSONObject item) {
        String[] directKeys = {
                "icon_image_small", "icon_image_small_webp", "hero_image",
                "portrait", "image", "icon"
        };
        String direct = firstString(item, directKeys);
        if (!direct.isEmpty() && direct.startsWith("https://")) return direct;

        JSONObject images = item.optJSONObject("images");
        if (images != null) {
            String nested = firstString(images, directKeys);
            if (!nested.isEmpty() && nested.startsWith("https://")) return nested;
        }
        return "";
    }

    private static JSONObject objectFrom(Object value, String accountId, String... keys) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (String.valueOf(object.optLong("account_id", -1)).equals(accountId)) {
                return object;
            }
            for (String key : keys) {
                Object nested = object.opt(key);
                if (nested instanceof JSONObject) {
                    JSONObject result = objectFrom(nested, accountId, keys);
                    if (result != null) return result;
                } else if (nested instanceof JSONArray) {
                    JSONObject result = objectFrom(nested, accountId, keys);
                    if (result != null) return result;
                }
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null && String.valueOf(item.optLong("account_id", -1))
                        .equals(accountId)) return item;
            }
        }
        return null;
    }

    private static JSONArray arrayFrom(Object value, String... keys) {
        if (value instanceof JSONArray) return (JSONArray) value;
        if (!(value instanceof JSONObject)) return null;
        JSONObject object = (JSONObject) value;
        for (String key : keys) {
            Object nested = object.opt(key);
            if (nested instanceof JSONArray) return (JSONArray) nested;
            if (nested instanceof JSONObject) {
                JSONArray deeper = arrayFrom(nested, keys);
                if (deeper != null) return deeper;
            }
        }
        return null;
    }

    private static String get(String path) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "DeadData-Android/1.0");
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = readFully(stream);
            if (status < 200 || status >= 300) {
                if (status == 404) throw new IOException("没有找到该玩家的战绩");
                if (status == 429 || status == 503) {
                    throw new IOException("战绩服务繁忙，请稍后再试");
                }
                throw new IOException("战绩服务返回错误 (" + status + ")");
            }
            return body;
        } finally {
            connection.disconnect();
        }
    }

    private static String readFully(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
        }
        return body.toString();
    }

    public static String firstString(JSONObject object, String... keys) {
        for (String key : keys) {
            Object value = object.opt(key);
            if (value instanceof String && !((String) value).trim().isEmpty()) {
                return ((String) value).trim();
            }
        }
        return "";
    }

    public static int firstInt(JSONObject object, int fallback, String... keys) {
        for (String key : keys) {
            Object value = object.opt(key);
            if (value instanceof Number) return ((Number) value).intValue();
            if (value instanceof String) {
                try {
                    return (int) Double.parseDouble((String) value);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return fallback;
    }

    public static long firstLong(JSONObject object, long fallback, String... keys) {
        for (String key : keys) {
            Object value = object.opt(key);
            if (value instanceof Number) return ((Number) value).longValue();
            if (value instanceof String) {
                try {
                    return (long) Double.parseDouble((String) value);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return fallback;
    }

    public static double firstDouble(JSONObject object, double fallback, String... keys) {
        for (String key : keys) {
            Object value = object.opt(key);
            if (value instanceof Number) return ((Number) value).doubleValue();
            if (value instanceof String) {
                try {
                    return Double.parseDouble((String) value);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return fallback;
    }

    public static final class PlayerData {
        public final String accountId;
        public final JSONArray matches;
        public final JSONArray heroStats;
        public final JSONObject steamProfile;
        public final JSONObject rank;
        public final Map<Integer, Hero> heroes;

        public PlayerData(String accountId, JSONArray matches, JSONArray heroStats,
                          JSONObject steamProfile, JSONObject rank, Map<Integer, Hero> heroes) {
            this.accountId = accountId;
            this.matches = matches;
            this.heroStats = heroStats;
            this.steamProfile = steamProfile;
            this.rank = rank;
            this.heroes = heroes;
        }

    }

    public static final class Hero {
        public final int id;
        public final String name;
        public final String imageUrl;

        public Hero(int id, String name, String imageUrl) {
            this.id = id;
            this.name = name;
            this.imageUrl = imageUrl;
        }
    }
}

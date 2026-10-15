package app.deaddata.mobile.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

/**
 * Match derived values that used to live inside {@code MainActivity}.
 *
 * The Deadlock API exposes the same information under several key names depending
 * on the endpoint version, so the lookups here are deliberately tolerant: unknown
 * shapes must degrade to a sensible default instead of crashing the match list.
 */
public final class MatchStats {
    private MatchStats() {
    }

    /** Returns the object that carries per-player statistics for a match. */
    public static JSONObject statsObject(JSONObject match) {
        JSONObject stats = match.optJSONObject("player_match_stats");
        if (stats == null) stats = match.optJSONObject("player_stats");
        if (stats == null) stats = match.optJSONObject("player");
        return stats == null ? match : stats;
    }

    /**
     * Decides whether the tracked player won a match.
     *
     * Team comparison wins when both sides are present; otherwise the player level
     * outcome fields are used, then free text, then the {@code won} flag.
     */
    public static boolean isWin(JSONObject match) {
        JSONObject stats = statsObject(match);
        int playerTeam = DeadlockApi.firstInt(match, -1, "player_team", "team");
        int matchResult = DeadlockApi.firstInt(match, -1, "match_result",
                "winning_team", "winning_team_id");
        if (playerTeam >= 0 && matchResult >= 0) return playerTeam == matchResult;
        int outcome = DeadlockApi.firstInt(match, -1,
                "player_match_outcome", "won", "player_won");
        if (outcome == 1) return true;
        if (outcome == 2 || outcome == 0) return false;
        String outcomeText = DeadlockApi.firstString(match, "outcome", "result")
                .toLowerCase(Locale.ROOT);
        if (outcomeText.contains("win") || outcomeText.contains("victory")) return true;
        if (outcomeText.contains("loss") || outcomeText.contains("defeat")) return false;
        return stats.optBoolean("won", false);
    }

    /** Human readable match mode label. */
    public static String modeLabel(JSONObject match) {
        int mode = DeadlockApi.firstInt(match, -1, "match_mode", "game_mode");
        if (mode == 1) return "标准";
        if (mode == 2) return "排位";
        if (mode < 0) return "模式未知";
        return "模式 " + mode;
    }

    /**
     * Folds a match history into one row per hero with the number of games played
     * and won.
     */
    public static ArrayList<JSONObject> aggregateHeroStats(JSONArray matches) {
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
}

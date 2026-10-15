package app.deaddata.mobile.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;

/**
 * Win detection has to survive several Deadlock API response shapes, and the hero
 * summary feeds the statistics screen, so both are pinned down here.
 */
public class MatchStatsTest {
    private static JSONObject json(String text) {
        try {
            return new JSONObject(text);
        } catch (Exception error) {
            throw new AssertionError("invalid test payload: " + text, error);
        }
    }

    private static JSONObject findByHero(ArrayList<JSONObject> stats, int heroId) {
        for (JSONObject stat : stats) {
            if (stat.optInt("hero_id") == heroId) return stat;
        }
        throw new AssertionError("no stats row for hero " + heroId);
    }

    @Test
    public void statsObjectPrefersTheNestedStatsContainer() {
        JSONObject match = json("{\"player_match_stats\":{\"kills\":7},"
                + "\"player_stats\":{\"kills\":1},\"player\":{\"kills\":2}}");

        assertEquals(7, MatchStats.statsObject(match).optInt("kills"));
    }

    @Test
    public void statsObjectFallsBackThroughKnownContainers() {
        assertEquals(1, MatchStats.statsObject(
                json("{\"player_stats\":{\"kills\":1},\"player\":{\"kills\":2}}")).optInt("kills"));
        assertEquals(2, MatchStats.statsObject(
                json("{\"player\":{\"kills\":2}}")).optInt("kills"));
    }

    @Test
    public void statsObjectFallsBackToTheMatchItself() {
        JSONObject match = json("{\"won\":true}");

        assertSame(match, MatchStats.statsObject(match));
    }

    @Test
    public void teamComparisonDecidesTheResult() {
        assertTrue(MatchStats.isWin(json("{\"player_team\":1,\"match_result\":1}")));
        assertFalse(MatchStats.isWin(json("{\"player_team\":0,\"match_result\":1}")));
        assertTrue(MatchStats.isWin(json("{\"team\":2,\"winning_team\":2}")));
        assertFalse(MatchStats.isWin(json("{\"team\":2,\"winning_team_id\":3}")));
    }

    @Test
    public void teamComparisonWinsOverTheOutcomeField() {
        assertTrue(MatchStats.isWin(json(
                "{\"player_team\":1,\"match_result\":1,\"player_match_outcome\":2}")));
    }

    @Test
    public void playerOutcomeFieldsAreUsedWhenTeamsAreUnknown() {
        assertTrue(MatchStats.isWin(json("{\"player_match_outcome\":1}")));
        assertFalse(MatchStats.isWin(json("{\"player_match_outcome\":2}")));
        assertFalse(MatchStats.isWin(json("{\"player_won\":0}")));
    }

    @Test
    public void freeTextOutcomeIsUnderstood() {
        assertTrue(MatchStats.isWin(json("{\"outcome\":\"Victory\"}")));
        assertTrue(MatchStats.isWin(json("{\"result\":\"WIN\"}")));
        assertFalse(MatchStats.isWin(json("{\"outcome\":\"Defeat\"}")));
        assertFalse(MatchStats.isWin(json("{\"result\":\"loss\"}")));
    }

    @Test
    public void wonFlagIsTheLastResortAndUnknownMatchesCountAsLosses() {
        assertTrue(MatchStats.isWin(json("{\"won\":true}")));
        assertTrue(MatchStats.isWin(json("{\"player\":{\"won\":true}}")));
        assertFalse(MatchStats.isWin(json("{}")));
        assertFalse(MatchStats.isWin(json("{\"won\":false}")));
    }

    @Test
    public void modeLabelsCoverKnownAndUnknownModes() {
        assertEquals("标准", MatchStats.modeLabel(json("{\"match_mode\":1}")));
        assertEquals("排位", MatchStats.modeLabel(json("{\"game_mode\":2}")));
        assertEquals("模式 3", MatchStats.modeLabel(json("{\"match_mode\":3}")));
        assertEquals("模式未知", MatchStats.modeLabel(json("{}")));
    }

    @Test
    public void aggregateCountsGamesAndWinsPerHero() {
        JSONArray matches = new JSONArray();
        matches.put(json("{\"hero_id\":1,\"player_team\":1,\"match_result\":1}"));
        matches.put(json("{\"hero_id\":1,\"player_team\":0,\"match_result\":1}"));
        matches.put(json("{\"hero\":2,\"won\":true}"));

        ArrayList<JSONObject> stats = MatchStats.aggregateHeroStats(matches);

        assertEquals(2, stats.size());
        assertEquals(2, findByHero(stats, 1).optInt("matches_played"));
        assertEquals(1, findByHero(stats, 1).optInt("wins"));
        assertEquals(1, findByHero(stats, 2).optInt("matches_played"));
        assertEquals(1, findByHero(stats, 2).optInt("wins"));
    }

    @Test
    public void aggregateSkipsEntriesWithoutAHeroId() {
        JSONArray matches = new JSONArray();
        matches.put(json("{\"match_id\":1}"));
        matches.put(JSONObject.NULL);

        assertEquals(0, MatchStats.aggregateHeroStats(matches).size());
    }

    @Test
    public void aggregateHandlesAnEmptyHistory() {
        assertEquals(0, MatchStats.aggregateHeroStats(new JSONArray()).size());
    }
}

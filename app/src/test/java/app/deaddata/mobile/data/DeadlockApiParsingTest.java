package app.deaddata.mobile.data;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

/**
 * The Deadlock API spells the same field differently across endpoints, so these
 * key-chain helpers decide how tolerant the app is with unknown payloads.
 */
public class DeadlockApiParsingTest {
    private static JSONObject json(String text) {
        try {
            return new JSONObject(text);
        } catch (Exception error) {
            throw new AssertionError("invalid test payload: " + text, error);
        }
    }

    @Test
    public void firstStringTrimsAndSkipsBlankValues() {
        assertEquals("value", DeadlockApi.firstString(
                json("{\"a\":\"   \",\"b\":\" value \"}"), "a", "b"));
        assertEquals("", DeadlockApi.firstString(json("{}"), "a"));
        assertEquals("", DeadlockApi.firstString(json("{\"a\":null}"), "a"));
    }

    @Test
    public void firstStringIgnoresNonStringValues() {
        assertEquals("ok", DeadlockApi.firstString(
                json("{\"a\":123,\"b\":true,\"c\":\"ok\"}"), "a", "b", "c"));
        assertEquals("", DeadlockApi.firstString(json("{\"a\":123}"), "a"));
    }

    @Test
    public void firstIntPrefersNumbersThenParsesTextAndTruncates() {
        assertEquals(5, DeadlockApi.firstInt(json("{\"a\":5,\"b\":6}"), -1, "a", "b"));
        assertEquals(5, DeadlockApi.firstInt(json("{\"a\":5.7}"), -1, "a"));
        assertEquals(3, DeadlockApi.firstInt(json("{\"a\":\"3.9\"}"), -1, "a"));
        assertEquals(7, DeadlockApi.firstInt(
                json("{\"a\":\"\",\"b\":\"x\",\"c\":7}"), -1, "a", "b", "c"));
        assertEquals(-1, DeadlockApi.firstInt(json("{}"), -1, "a"));
    }

    @Test
    public void firstIntIgnoresBooleansAndKeepsTheFallback() {
        assertEquals(-2, DeadlockApi.firstInt(json("{\"a\":false}"), -2, "a"));
        assertEquals(-2, DeadlockApi.firstInt(json("{\"a\":null}"), -2, "a"));
    }

    @Test
    public void firstLongFollowsTheSameChain() {
        assertEquals(42L, DeadlockApi.firstLong(json("{\"a\":\"42\"}"), -1L, "a"));
        assertEquals(1700000000000L,
                DeadlockApi.firstLong(json("{\"a\":1700000000000}"), 0L, "a"));
        assertEquals(1700000000000L,
                DeadlockApi.firstLong(json("{\"b\":1700000000000}"), 0L, "a", "b"));
        assertEquals(-1L, DeadlockApi.firstLong(json("{}"), -1L, "a"));
    }

    @Test
    public void firstDoubleRejectsUnparseableText() {
        assertEquals(1.5, DeadlockApi.firstDouble(json("{\"a\":\"1.5\"}"), -1.0, "a"), 0.0001);
        assertEquals(0.65, DeadlockApi.firstDouble(json("{\"a\":0.65}"), -1.0, "a"), 0.0001);
        assertEquals(-1.0, DeadlockApi.firstDouble(json("{\"a\":\"x\"}"), -1.0, "a"), 0.0001);
    }
}

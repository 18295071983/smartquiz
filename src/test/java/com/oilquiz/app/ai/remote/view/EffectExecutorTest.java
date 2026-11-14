package com.oilquiz.app.ai.remote.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * C2 (2026-11-14): effect whitelist, 3-second window and field validation.
 *
 * <p>These are the guardrails that keep a plugin from doing anything the app did not
 * whitelist, so they deserve direct tests (no Android runtime needed).</p>
 */
public class EffectExecutorTest {

    private static JSONArray effects(String... json) throws Exception {
        JSONArray arr = new JSONArray();
        for (String s : json) arr.put(new JSONObject(s));
        return arr;
    }

    @Test
    public void whitelistCoversNineTypes() {
        assertEquals(9, EffectExecutor.ALLOWED.length);
        assertTrue(EffectExecutor.allowed("open_file"));
        assertFalse(EffectExecutor.allowed("approve_tool"));   // never allowed
        assertFalse(EffectExecutor.allowed(""));
        assertFalse(EffectExecutor.allowed(null));
    }

    @Test
    public void lateEffectsAreDropped() throws Exception {
        JSONArray raw = effects("{\"type\":\"toast\",\"text\":\"hi\"}");
        long now = System.currentTimeMillis();
        assertEquals(0, EffectExecutor.check(raw, now - 5000, now).size());
        assertEquals(1, EffectExecutor.check(raw, now - 1000, now).size());
    }

    @Test
    public void atMostFourEffects() throws Exception {
        JSONArray raw = effects(
                "{\"type\":\"toast\",\"text\":\"1\"}",
                "{\"type\":\"toast\",\"text\":\"2\"}",
                "{\"type\":\"toast\",\"text\":\"3\"}",
                "{\"type\":\"toast\",\"text\":\"4\"}",
                "{\"type\":\"toast\",\"text\":\"5\"}");
        assertEquals(4, EffectExecutor.check(raw, 0, System.currentTimeMillis()).size());
    }

    @Test
    public void invalidPayloadsRejected() throws Exception {
        JSONArray raw = effects(
                "{\"type\":\"link\",\"url\":\"http://x.test\"}",          // http not https
                "{\"type\":\"link\",\"url\":\"https://ok.test\"}",
                "{\"type\":\"insert\",\"text\":\"\"}",                    // empty
                "{\"type\":\"copy\",\"text\":\"abc\"}");
        assertEquals(2, EffectExecutor.check(raw, 0, System.currentTimeMillis()).size());
    }

    @Test
    public void toastIsTruncatedToFortyChars() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 80; i += 1) sb.append('x');
        JSONArray raw = effects("{\"type\":\"toast\",\"text\":\"" + sb + "\"}");
        JSONObject e = EffectExecutor.check(raw, 0, System.currentTimeMillis()).get(0);
        assertEquals(40, e.optString("text").length());
    }

    @Test
    public void commandNameStripsLeadingSlash() throws Exception {
        JSONArray raw = effects("{\"type\":\"command\",\"name\":\"/compact\"}");
        JSONObject e = EffectExecutor.check(raw, 0, System.currentTimeMillis()).get(0);
        assertEquals("compact", e.optString("name"));
    }
}

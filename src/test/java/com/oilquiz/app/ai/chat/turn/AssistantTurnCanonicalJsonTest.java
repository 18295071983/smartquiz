package com.oilquiz.app.ai.chat.turn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 指纹的规范化 JSON（2026-11-13，B3）。
 *
 * <p>`JSONObject.toString()` 保留插入顺序，而 props 可能来自 HashMap 或不同的构造路径：
 * 同样的内容会算出不同的指纹 → 每次重投影都被判成"变了" → 整卡重绑（闪烁、丢展开态）。
 * 这几条用例把"键序不影响指纹、数组顺序仍影响指纹"钉住。</p>
 */
public class AssistantTurnCanonicalJsonTest {

    @Test
    public void keyOrderDoesNotAffectDigest() throws Exception {
        JSONObject a = new JSONObject();
        a.put("toolName", "write");
        a.put("status", "success");
        a.put("args", "{}");

        JSONObject b = new JSONObject();
        b.put("args", "{}");
        b.put("status", "success");
        b.put("toolName", "write");

        assertEquals("键序不同但内容相同，指纹必须一致",
                AssistantTurn.canonicalJson(a), AssistantTurn.canonicalJson(b));
    }

    @Test
    public void arrayOrderStillMatters() throws Exception {
        JSONObject a = new JSONObject();
        a.put("items", new JSONArray().put("x").put("y"));
        JSONObject b = new JSONObject();
        b.put("items", new JSONArray().put("y").put("x"));

        assertNotEquals("数组是顺序语义，不能排序",
                AssistantTurn.canonicalJson(a), AssistantTurn.canonicalJson(b));
    }

    @Test
    public void nestedObjectsAreCanonicalizedToo() throws Exception {
        JSONObject innerA = new JSONObject();
        innerA.put("path", "a.txt");
        innerA.put("hunks", 3);
        JSONObject outerA = new JSONObject();
        outerA.put("diff", innerA);

        JSONObject innerB = new JSONObject();
        innerB.put("hunks", 3);
        innerB.put("path", "a.txt");
        JSONObject outerB = new JSONObject();
        outerB.put("diff", innerB);

        assertEquals("嵌套对象也要按键排序",
                AssistantTurn.canonicalJson(outerA), AssistantTurn.canonicalJson(outerB));
    }

    @Test
    public void valueChangeChangesDigest() throws Exception {
        JSONObject a = new JSONObject();
        a.put("status", "success");
        JSONObject b = new JSONObject();
        b.put("status", "failed");

        assertNotEquals(AssistantTurn.canonicalJson(a), AssistantTurn.canonicalJson(b));
    }
}

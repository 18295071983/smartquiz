package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 2026-10-23 新增四张卡（playlist / agenda / location / diff）与题库卡的填空/简答分支：
 * "目录教给模型的形状，照着填必须能渲染"。
 *
 * <p>这是 {@code ComponentFieldIntegrityTest} 的手工补充：那条测试只检查必填字段名，
 * 这里按文档里真实会出现的完整形状各给一份，避免"字段名对、形状不对"又掉进原始 JSON 兜底。</p>
 */
public class NewCardShapeTest {

    @Test
    public void taughtShapesPassCanRender() throws Exception {
        // playlist：多音频产物的真实形状
        assertTrue(new PlaylistCardView().canRender(ComponentData.of("playlist",
                obj("title", "播放列表 · 2 首", "items", arr(
                        obj("name", "a.mp3", "url", "/tmp/a.mp3", "size", "1 MB"),
                        obj("name", "b.mp3", "url", "/tmp/b.mp3"))))));

        // agenda：reminder/task 的典型形状
        assertTrue(new AgendaCardView().canRender(ComponentData.of("agenda",
                obj("title", "本周日程", "items", arr(
                        obj("date", "2026-10-23", "time", "09:00", "title", "晨会", "status", "done"),
                        obj("date", "2026-10-24", "title", "交作业"))))));

        // location：location 工具产出的形状
        assertTrue(new LocationCardView().canRender(ComponentData.of("location",
                obj("address", "上海市黄浦区人民广场", "city", "上海",
                        "latitude", 31.2304, "longitude", 121.4737,
                        "accuracy", "20", "provider", "gps"))));

        // diff：hunks 与 before/after 两种
        assertTrue(new DiffCardView().canRender(ComponentData.of("diff",
                obj("hunks", arr(obj("type", "add", "text", "new line"))))));
        assertTrue(new DiffCardView().canRender(ComponentData.of("diff",
                obj("before", "a\nb", "after", "a\nc"))));
    }

    @Test
    public void quizTextTypesRender() throws Exception {
        assertTrue(new QuizCardView().canRender(ComponentData.of("quiz_card",
                obj("type", "fill", "question", "中国的首都是？", "answer", "北京|首都北京"))));
        assertTrue(new QuizCardView().canRender(ComponentData.of("quiz_card",
                obj("type", "short", "question", "简述光合作用", "answer", "参考答案……"))));
    }

    @Test
    public void newTypesAreRegisteredAndInCatalog() {
        ComponentRegistry registry = ComponentRegistry.getInstance();
        for (String type : new String[]{"playlist", "agenda", "location", "diff"}) {
            assertNotNull("没有注册渲染实现: " + type, registry.componentFor(type));
            assertNotNull("目录里没有: " + type, catalogEntry(type));
        }
        // 题库卡仍是原实现，只是多认两种 type
        assertNotNull(registry.componentFor("quiz_card"));
    }

    private static JSONObject catalogEntry(String type) {
        JSONArray catalog = ComponentTypes.catalogJson();
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject o = catalog.optJSONObject(i);
            if (o != null && type.equals(o.optString("type"))) return o;
        }
        return null;
    }

    private static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            try {
                o.put(String.valueOf(kv[i]), kv[i + 1]);
            } catch (org.json.JSONException e) {
                throw new RuntimeException(e);
            }
        }
        return o;
    }

    private static JSONArray arr(Object... v) {
        JSONArray a = new JSONArray();
        for (Object o : v) a.put(o);
        return a;
    }
}

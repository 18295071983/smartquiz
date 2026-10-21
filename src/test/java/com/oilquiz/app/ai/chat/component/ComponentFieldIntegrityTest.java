package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.ai.tool.SystemUIComponentTool;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.HashMap;
import java.util.Map;

/**
 * 目录字段 ↔ 卡片真实读取 的一致性（2026-10-21）。
 *
 * <p>{@link ComponentNameIntegrityTest} 只管"名字能不能命中实现"，名字对了但<b>字段名教错</b>
 * 一样会坏：卡片 {@code canRender} 返回 false 时 {@code ComponentRegistry#render} 静默落到
 * {@code DynamicCardView} 通配兜底（标题 + 键值行 + 折叠原始 JSON），既不报错也没日志，
 * 用户看到的就是"贴上去的 JSON 原文"。本次查实的三条就长期活在这里：
 * <ol>
 *   <li>{@code progress} 目录教必填 {@code percent}，卡片真闸门是 {@code props.has("progress")}；</li>
 *   <li>{@code metric} 目录教 {@code items[]}，卡片真读 {@code metrics[]}；</li>
 *   <li>{@code contact} 目录教 {@code name}/{@code phone}，卡片真读 {@code value}。</li>
 * </ol>
 *
 * <p>本测试用"目录教出去的必填字段"构造 props 并要求 {@code canRender==true}，
 * 再对这三条反向锁死（旧错字段必须仍然渲染不出），字段名再漂移就会红。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class ComponentFieldIntegrityTest {

    /** 目录教出去的必填字段 → 卡片必须能渲染。 */
    @Test
    public void catalogRequiredFieldsSatisfyCardGates() {
        Map<String, JSONObject> taughtByCatalog = new HashMap<>();
        taughtByCatalog.put("info", obj("items", arr()));
        taughtByCatalog.put("list", obj("items", arr()));
        taughtByCatalog.put("steps", obj("steps", arr()));
        taughtByCatalog.put("alert", obj("content", "已完成"));
        taughtByCatalog.put("markdown", obj("content", "**加粗**"));
        taughtByCatalog.put("code", obj("code", "int a=1;"));
        taughtByCatalog.put("html", obj("html", "<h3>t</h3>"));
        taughtByCatalog.put("table", obj("headers", arr("题目"), "rows", arr()));
        taughtByCatalog.put("chart", obj("chartType", "bar", "categories", arr("A"),
                "series", arr(obj("name", "n", "data", arr(1)))));
        taughtByCatalog.put("metric", obj("metrics", arr()));
        taughtByCatalog.put("progress", obj("progress", 68));
        taughtByCatalog.put("json", obj("data", obj("k", "v")));
        taughtByCatalog.put("image", obj("images", arr("http://a/b.png")));
        taughtByCatalog.put("video", obj("url", "http://a/b.mp4"));
        taughtByCatalog.put("audio", obj("url", "http://a/b.mp3"));
        taughtByCatalog.put("file", obj("name", "q.xlsx"));
        taughtByCatalog.put("link", obj("url", "https://a.b"));
        taughtByCatalog.put("quiz", obj("question", "1+1?", "options", arr("1", "2")));
        taughtByCatalog.put("weather", obj("temp", "25", "city", "北京"));
        taughtByCatalog.put("contact", obj("value", "13800000000", "type", "phone"));
        taughtByCatalog.put("todo", obj("items", arr()));
        taughtByCatalog.put("file_list", obj("files", arr()));

        for (Map.Entry<String, JSONObject> e : taughtByCatalog.entrySet()) {
            assertNotNull("目录里没有该 canonical 类型: " + e.getKey(),
                    schemaEntry(e.getKey()));
            ChatComponent c = implFor(e.getKey());
            assertNotNull("canonical 名没有渲染实现: " + e.getKey(), c);
            assertTrue("目录教的字段渲染不出卡（会掉原始 JSON 兜底）: " + e.getKey(),
                    c.canRender(ComponentData.of(e.getKey(), e.getValue())));
        }
    }

    /** 反向锁死三条历史错字段：只给旧字段必须仍然渲染不出，证明本测试真的能抓到漂移。 */
    @Test
    public void previouslyTaughtWrongFieldsStillFailGate() {
        assertFalse("progress 卡不读 percent", renderableWithOnly("progress", "percent", 68));
        assertFalse("metric 卡不读 items", renderableWithOnly("metric", "items", arr()));
        assertFalse("contact 卡不读 name", renderableWithOnly("contact", "name", "张三"));
        assertFalse("weather 卡不读 data", renderableWithOnly("weather", "data", obj("temp", "1")));
    }

    /** file_list 是场景卡但以前不进目录，模型只能猜字段。 */
    @Test
    public void sceneCardsAreExposedInCatalog() {
        for (String scene : new String[]{"weather", "contact", "todo", "file_list"}) {
            assertNotNull("场景卡未进目录: " + scene, schemaEntry(scene));
        }
    }

    /** 已删实现（note_card/grid_card）不许再出现在任何模型可见文案里。 */
    @Test
    public void deletedImplementationsAreNotAdvertised() {
        Map<String, String> descs = new SystemUIComponentTool(
                ApplicationProvider.getApplicationContext()).getParameterDescriptions();
        String typeParam = String.valueOf(descs.get("component_type"));
        String propsParam = String.valueOf(descs.get("props"));
        assertFalse("参数说明缺失", typeParam.isEmpty() || propsParam.isEmpty());
        for (String dead : new String[]{"note_card", "grid_card"}) {
            assertFalse("工具仍向模型售卖已删除实现: component_type 含 " + dead,
                    typeParam.contains(dead));
            // props 说明里 "替代已删除的 note_card" 这类解释性提法是允许的，只禁"名字:字段结构"式售卖
            assertFalse("工具仍给出已删除实现的字段结构: " + dead,
                    propsParam.contains(dead + ":{"));
        }
        // 但历史数据仍必须能渲染（alias 不许删）
        assertTrue(ComponentRegistry.getInstance()
                .componentFor("note_card").canRender(ComponentData.of("note_card", obj("items", arr()))));
        assertTrue(ComponentRegistry.getInstance()
                .componentFor("grid_card").canRender(ComponentData.of("grid_card", obj("items", arr()))));
    }

    // ---- helpers ----

    /**
     * 走真渲染解析：{@code ComponentRegistry#componentFor} 内部按 canonical 归一后再直取原名。
     * 场景短名（weather/contact/todo/file_list）现也由 {@code SCENE_TARGET} 占位，模型照目录用短名即可命中。
     */
    private static ChatComponent implFor(String name) {
        return ComponentRegistry.getInstance().componentFor(name);
    }

    private static boolean renderableWithOnly(String canonical, String key, Object value) {
        ChatComponent c = implFor(canonical);
        return c != null && c.canRender(ComponentData.of(canonical, obj(key, value)));
    }

    private static JSONObject schemaEntry(String canonical) {
        JSONArray catalog = ComponentTypes.catalogJson();
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject o = catalog.optJSONObject(i);
            if (o != null && canonical.equals(o.optString("type"))) {
                return o;
            }
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
        for (Object o : v) {
            a.put(o);
        }
        return a;
    }
}

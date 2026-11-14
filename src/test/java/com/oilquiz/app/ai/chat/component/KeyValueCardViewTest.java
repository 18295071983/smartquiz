package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * key_value / compare 的 props 解析（纯 JVM，不创建 Android View）。
 *
 * <p>钉住三件事：三种 items 形态都能解析、24 项/4 项截断计数正确、空/坏数据不崩且
 * 目录教给模型的形状真的能过 canRender（否则会掉 DynamicCardView 原始 JSON 兜底）。</p>
 */
public class KeyValueCardViewTest {

    @Test
    public void keyValueParsesThreeTaughtShapes() throws Exception {
        // 1) [["名称","值"], …]
        CardKeyValues.Parsed pairs = CardKeyValues.parse(
                obj("items", arr(arr("余额", "128.50"), arr("状态", "正常"))));
        assertEquals(2, pairs.items.size());
        assertEquals("余额", pairs.items.get(0).label);
        assertEquals("128.50", pairs.items.get(0).value);
        assertEquals("正常", pairs.items.get(1).value);

        // 2) [{label,value,tone}]
        CardKeyValues.Parsed objects = CardKeyValues.parse(obj("items", arr(
                obj("label", "余额", "value", "128.50", "tone", "good"),
                obj("key", "状态", "val", "正常"))));
        assertEquals(2, objects.items.size());
        assertEquals("余额", objects.items.get(0).label);
        assertEquals("128.50", objects.items.get(0).value);
        assertEquals("good", objects.items.get(0).tone);
        assertEquals("状态", objects.items.get(1).label);
        assertEquals("正常", objects.items.get(1).value);

        // 3) {"名称":"值"}；数字/布尔按文本展示
        CardKeyValues.Parsed map = CardKeyValues.parse(obj("items",
                obj("余额", 128.5, "状态", "正常", "已启用", true)));
        assertEquals(3, map.items.size());
        assertEquals("128.5", find(map, "余额").value);
        assertEquals("true", find(map, "已启用").value);
    }

    @Test
    public void keyValueCapsAtTwentyFourAndReportsOverflow() throws Exception {
        JSONArray many = new JSONArray();
        for (int i = 0; i < 30; i++) many.put(arr("键" + i, String.valueOf(i)));
        CardKeyValues.Parsed parsed = CardKeyValues.parse(obj("items", many));
        assertEquals(24, parsed.items.size());
        assertEquals(30, parsed.total);
        assertEquals(6, parsed.overflow());
        assertEquals("键23", parsed.items.get(23).label);
    }

    @Test
    public void keyValueCopyTextIsReadable() throws Exception {
        CardKeyValues.Parsed parsed = CardKeyValues.parse(obj("items",
                arr(arr("余额", "128.50"), arr("状态", "正常"))));
        assertEquals("余额：128.50\n状态：正常", CardKeyValues.toCopyText(parsed.items));
    }

    @Test
    public void numericLookingValuesUseMonospace() {
        assertTrue(CardKeyValues.looksNumeric("128"));
        assertTrue(CardKeyValues.looksNumeric("-3.14"));
        assertTrue(CardKeyValues.looksNumeric("1,280.00"));
        assertTrue(CardKeyValues.looksNumeric("88%"));
        assertTrue(CardKeyValues.looksNumeric("12.5 MB"));
        assertTrue(CardKeyValues.looksNumeric("3 人"));
        assertFalse(CardKeyValues.looksNumeric("正常"));
        assertFalse(CardKeyValues.looksNumeric("约 12 个"));
        assertFalse(CardKeyValues.looksNumeric("2026-11-15"));
        assertFalse(CardKeyValues.looksNumeric(""));
    }

    @Test
    public void compareParsesRowsInBothShapes() throws Exception {
        JSONObject props = obj("title", "套餐对比", "items", arr(
                obj("title", "基础版", "badge", "¥19",
                        "rows", arr(arr("存储", "10 GB"), arr("成员", "3 人"))),
                obj("title", "专业版", "badge", "¥49",
                        "rows", obj("存储", "100 GB", "成员", "20 人"))));
        CompareCardView.Parsed parsed = CompareCardView.parse(props);
        assertEquals(2, parsed.cards.size());
        assertEquals("基础版", parsed.cards.get(0).title);
        assertEquals("¥19", parsed.cards.get(0).badge);
        assertEquals(2, parsed.cards.get(0).rows.size());
        assertEquals("¥49", parsed.cards.get(1).badge);
        assertEquals("100 GB", find(parsed.cards.get(1).rows, "存储").value);
    }

    @Test
    public void compareCapsAtFourAndReportsDropped() throws Exception {
        JSONArray items = new JSONArray();
        for (int i = 0; i < 7; i++) {
            items.put(obj("title", "方案" + i, "rows", arr(arr("价格", i + " 元"))));
        }
        CompareCardView.Parsed parsed = CompareCardView.parse(obj("items", items));
        assertEquals(4, parsed.cards.size());
        assertEquals(7, parsed.total);
        assertEquals(3, parsed.dropped());
        assertEquals("方案3", parsed.cards.get(3).title);
    }

    @Test
    public void compareSkipsBadEntriesButKeepsGoodOnes() throws Exception {
        JSONObject props = obj("items", arr(
                "坏条目", arr("也不是对象"), obj(),
                obj("title", "只有标题"),
                obj("rows", arr(arr("价格", "1 元")))));
        CompareCardView.Parsed parsed = CompareCardView.parse(props);
        assertEquals(2, parsed.cards.size());
        assertEquals("只有标题", parsed.cards.get(0).title);
        assertEquals("价格", parsed.cards.get(1).rows.get(0).label);
    }

    @Test
    public void emptyOrBadDataDoesNotRenderButTaughtShapeDoes() throws Exception {
        KeyValueCardView keyValue = new KeyValueCardView();
        CompareCardView compare = new CompareCardView();

        assertFalse(keyValue.canRender(ComponentData.of("key_value", obj())));
        assertFalse(keyValue.canRender(ComponentData.of("key_value", obj("items", arr()))));
        assertFalse(keyValue.canRender(ComponentData.of("key_value", obj("items", "x"))));
        assertFalse(keyValue.canRender(ComponentData.of("key_value", null)));
        assertFalse(compare.canRender(ComponentData.of("compare", obj())));
        assertFalse(compare.canRender(ComponentData.of("compare", obj("items", obj()))));

        // 目录派生测试会按 "items[{...}]" 构造 [{}]：必须过闸门（createView 显示空态）
        assertTrue(keyValue.canRender(ComponentData.of("key_value", obj("items", arr(obj())))));
        assertTrue(compare.canRender(ComponentData.of("compare", obj("items", arr(obj())))));
    }

    @Test
    public void registeredAliasesResolveAndAreInCatalog() {
        ComponentRegistry registry = ComponentRegistry.getInstance();
        assertEquals("key_value", ComponentTypes.resolve("keyValue"));
        assertEquals("key_value", ComponentTypes.resolve("dsh_keyvalue"));
        assertEquals("key_value", ComponentTypes.resolve("key_value"));
        assertEquals("compare", ComponentTypes.resolve("compare"));
        assertNotNull(registry.componentFor("key_value"));
        assertNotNull(registry.componentFor("keyValue"));
        assertNotNull(registry.componentFor("compare"));
        assertNotNull(catalogEntry("key_value"));
        assertNotNull(catalogEntry("compare"));
    }

    // ---- helpers ----

    private static CardKeyValues.Item find(CardKeyValues.Parsed parsed, String label) {
        for (CardKeyValues.Item item : parsed.items) {
            if (label.equals(item.label)) return item;
        }
        return null;
    }

    private static CardKeyValues.Item find(java.util.List<CardKeyValues.Item> items, String label) {
        for (CardKeyValues.Item item : items) {
            if (label.equals(item.label)) return item;
        }
        return null;
    }

    private static JSONObject catalogEntry(String type) {
        JSONArray catalog = ComponentTypes.catalogJson();
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject entry = catalog.optJSONObject(i);
            if (entry != null && type.equals(entry.optString("type"))) return entry;
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

    private static JSONArray arr(Object... values) {
        JSONArray a = new JSONArray();
        for (Object value : values) a.put(value);
        return a;
    }
}

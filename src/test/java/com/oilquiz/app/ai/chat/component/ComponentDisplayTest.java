package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

/**
 * 工具结果卡片的展示档位口径（2026-10-20）。
 *
 * <p>2026-10-21 起，模型不能通过 {@code display}/{@code collapsed} 控制显隐；
 * 这张表只钉住 UI 口径：
 * <ol>
 *   <li>模型的 {@code display}/{@code collapsed} 一律忽略，只认用户默认档；</li>
 *   <li>交互/正文类底线仍然压过用户默认档，避免按钮被收起；</li>
 *   <li>{@link ComponentDisplay#countMounting} 与渲染端"挂不挂"同口径；</li>
 *   <li>摘要行文案（{@link ComponentDisplay#peekLabel}）在 RecyclerView 绑定路径上跑，
 *       任何 props 形状都不能抛。</li>
 * </ol>
 */
public class ComponentDisplayTest {

    private static ComponentData comp(String type, String json) {
        try {
            return new ComponentData(type, new JSONObject(json));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== 档位优先级 ====================

    @Test
    public void modelDeclarationIsIgnored() {
        ComponentData table = comp("table_card",
                "{\"title\":\"成绩\",\"headers\":[\"姓名\",\"分数\"],\"rows\":[[\"甲\",90]]}");
        assertEquals(ComponentDisplay.Mode.PEEK,
                ComponentDisplay.resolveMode(table, ComponentDisplay.Mode.PEEK));
        assertEquals(ComponentDisplay.Mode.INLINE,
                ComponentDisplay.resolveMode(table, ComponentDisplay.Mode.INLINE));
        // 模型写 display 也不能改变档位：只认用户默认档
        ComponentData forcedInline = comp("table_card", "{\"display\":\"inline\",\"rows\":[]}");
        assertEquals(ComponentDisplay.Mode.HIDDEN,
                ComponentDisplay.resolveMode(forcedInline, ComponentDisplay.Mode.HIDDEN));
        ComponentData forcedHidden = comp("table_card", "{\"display\":\"hidden\"}");
        assertEquals(ComponentDisplay.Mode.INLINE,
                ComponentDisplay.resolveMode(forcedHidden, ComponentDisplay.Mode.INLINE));
    }

    @Test
    public void interactiveAndContentTypesAreNeverCollapsed() {
        // 带按钮：模型想 hidden 也 hidden 不掉（收起后用户点不到，回传链直接断）
        ComponentData withActions = comp("info_card",
                "{\"display\":\"hidden\",\"title\":\"确认\",\"actions\":[{\"label\":\"好\",\"value\":\"ok\"}]}");
        assertEquals(ComponentDisplay.Mode.INLINE,
                ComponentDisplay.resolveMode(withActions, ComponentDisplay.Mode.HIDDEN));
        assertTrue(ComponentDisplay.isAlwaysInline(withActions));

        // 交互/正文类型同理
        assertEquals(ComponentDisplay.Mode.INLINE, ComponentDisplay.resolveMode(
                comp("dialog", "{\"display\":\"peek\"}"), ComponentDisplay.Mode.HIDDEN));
        assertEquals(ComponentDisplay.Mode.INLINE, ComponentDisplay.resolveMode(
                comp("quiz_card", "{\"display\":\"hidden\"}"), ComponentDisplay.Mode.PEEK));
        assertEquals(ComponentDisplay.Mode.INLINE, ComponentDisplay.resolveMode(
                comp("markdown_card", "{\"display\":\"peek\",\"content\":\"正文\"}"),
                ComponentDisplay.Mode.PEEK));
        assertEquals(ComponentDisplay.Mode.INLINE, ComponentDisplay.resolveMode(
                comp("progress_card", "{\"display\":\"hidden\",\"percent\":40}"),
                ComponentDisplay.Mode.PEEK));
        // Layout 原语里的输入/选择控件 + 状态/点赞条：本体就是给用户操作的，不许收起
        assertEquals(ComponentDisplay.Mode.INLINE, ComponentDisplay.resolveMode(
                comp("tag_input", "{\"display\":\"peek\"}"), ComponentDisplay.Mode.PEEK));
        assertEquals(ComponentDisplay.Mode.INLINE, ComponentDisplay.resolveMode(
                comp("feedback_bar", "{\"display\":\"hidden\"}"), ComponentDisplay.Mode.PEEK));
        assertEquals(ComponentDisplay.Mode.INLINE, ComponentDisplay.resolveMode(
                comp("search_bar", "{\"display\":\"peek\"}"), ComponentDisplay.Mode.PEEK));
        assertFalse(ComponentDisplay.isAlwaysInline(
                comp("table_card", "{\"rows\":[]}")));
    }

    @Test
    public void legacyCollapsedFlagIsIgnored() {
        ComponentData collapsed = comp("json_viewer", "{\"title\":\"数据\",\"collapsed\":true}");
        assertEquals(ComponentDisplay.Mode.INLINE,
                ComponentDisplay.resolveMode(collapsed, ComponentDisplay.Mode.INLINE));
        ComponentData open = comp("json_viewer", "{\"title\":\"数据\",\"collapsed\":false}");
        assertEquals(ComponentDisplay.Mode.PEEK,
                ComponentDisplay.resolveMode(open, ComponentDisplay.Mode.PEEK));
    }

    @Test
    public void garbageDeclarationFallsBackToTier() {
        ComponentData odd = comp("list_card", "{\"display\":\"whatever\",\"items\":[]}");
        assertEquals(ComponentDisplay.Mode.PEEK,
                ComponentDisplay.resolveMode(odd, ComponentDisplay.Mode.PEEK));
        assertEquals(ComponentDisplay.Mode.HIDDEN,
                ComponentDisplay.resolveMode(odd, ComponentDisplay.Mode.HIDDEN));
        assertNullMode();
    }

    private void assertNullMode() {
        assertEquals(ComponentDisplay.Mode.PEEK,
                ComponentDisplay.resolveMode(null, ComponentDisplay.Mode.PEEK));
        assertEquals(ComponentDisplay.Mode.INLINE,
                ComponentDisplay.resolveMode(comp("list_card", "{}"), ComponentDisplay.Mode.INLINE));
    }

    // ==================== 挂不挂：与渲染端同口径 ====================

    @Test
    public void countMountingUsesUserTierNotModelParams() {
        ComponentData peek = comp("table_card", "{\"rows\":[]}");
        ComponentData modelHidden = comp("list_card", "{\"display\":\"hidden\",\"items\":[]}");
        ComponentData interactive = comp("quiz_card", "{\"display\":\"hidden\"}");
        java.util.List<ComponentData> list =
                Arrays.asList(peek, modelHidden, interactive, null);
        // 模型 display:hidden 被忽略：数据卡按默认档 PEEK 挂，交互卡 INLINE 挂，null 不算 → 3 个
        assertEquals(3, ComponentDisplay.countMounting(list, null));
        assertFalse(ComponentDisplay.mounts(ComponentDisplay.Mode.HIDDEN));
        assertTrue(ComponentDisplay.mounts(ComponentDisplay.Mode.PEEK));
    }

    @Test
    public void defaultTierWithoutContextIsPeek() {
        // 读不到 prefs（单测/异常路径）都按 peek——与"新能力默认最小副作用"一致
        assertEquals(ComponentDisplay.Mode.PEEK, ComponentDisplay.defaultTier(null));
        assertTrue(ComponentDisplay.mounts(ComponentDisplay.defaultTier(null)));
    }

    // ==================== 摘要行文案 ====================

    @Test
    public void peekLabelCarriesScalePerType() {
        // props 里没有 title 时，摘要行是"类型中文名 · 关键值"
        assertEquals("天气 · 北京 18℃ 晴", ComponentDisplay.peekLabel(
                comp("weather_card", "{\"city\":\"北京\",\"temp\":\"18\",\"text\":\"晴\"}")));
        // 工具侧的 temp 自带单位（"26°C"），摘要不能再补一个 ℃
        assertEquals("天气 · 北京 26°C 晴", ComponentDisplay.peekLabel(
                comp("weather_card", "{\"city\":\"北京\",\"temp\":\"26°C\",\"text\":\"晴\"}")));
        assertEquals("天气 · 北京 晴", ComponentDisplay.peekLabel(
                comp("weather_card", "{\"city\":\"北京\",\"temp\":\"\",\"text\":\"晴\"}")));
        assertEquals("成绩 · 2 行 × 2 列", ComponentDisplay.peekLabel(
                comp("table_card", "{\"title\":\"成绩\",\"headers\":[\"a\",\"b\"],"
                        + "\"rows\":[[1,2],[3,4]]}")));
        assertEquals("图集 · 3 张图", ComponentDisplay.peekLabel(
                comp("image_grid", "{\"images\":[\"a\",\"b\",\"c\"]}")));
        assertEquals("文件 · 报告.pdf", ComponentDisplay.peekLabel(
                comp("file_card", "{\"path\":\"/sdcard/Download/报告.pdf\"}")));
        assertEquals("数据 · 2 个字段", ComponentDisplay.peekLabel(
                comp("json_viewer", "{\"data\":{\"a\":1,\"b\":2}}")));
        assertEquals("待办 · 4 项", ComponentDisplay.peekLabel(
                comp("todo_card", "{\"items\":[{},{},{},{}]}")));
        assertEquals("链接 · example.com", ComponentDisplay.peekLabel(
                comp("link_card", "{\"url\":\"https://example.com/a/b\"}")));
        // 正文类兜底：取内容前缀并裁短，不整段摊开（绑定路径上一行摘要必须只有一行）
        String label = ComponentDisplay.peekLabel(
                comp("info_card", "{\"content\":\"这是一段很长很长很长很长很长很长很长的说明文字，"
                        + "用来验证摘要行会把正文裁短而不是整段铺开\"}"));
        assertTrue("摘要必须带上正文开头: " + label, label.contains("这是一段很长"));
        assertTrue("摘要应当被裁短而不是整段铺开: " + label.length(), label.length() <= 50);
        assertTrue("裁短要留省略号: " + label, label.endsWith("…"));
    }

    @Test
    public void peekLabelNeverThrowsOnOddProps() {
        String[] samples = {
                "{}",
                "{\"rows\":null}",
                "{\"rows\":{}}",                       // 该是数组却给了对象
                "{\"data\":\"纯字符串\"}",
                "{\"temp\":38.5,\"city\":\"\"}",
                "{\"images\":[null]}",
                "{\"url\":\"不是链接\"}",
                "{\"content\":123}",
        };
        String[] types = {"weather_card", "table_card", "table_card", "json_viewer",
                "weather_card", "image_grid", "link_card", "info_card"};
        for (int i = 0; i < samples.length; i++) {
            String out = ComponentDisplay.peekLabel(comp(types[i], samples[i]));
            assertNotNull(types[i] + " 的摘要不能为 null", out);
            assertFalse(types[i] + " 的摘要不该是空串", out.trim().isEmpty());
        }
    }

    @Test
    public void peekLabelFallsBackToTypeChineseNameWithoutTitle() {
        // props 没 title、类型又不是已知名 → 给一个能读的兜底
        assertEquals("数据表 · 1 行", ComponentDisplay.peekLabel(
                comp("table_card", "{\"rows\":[[1]]}")));
        assertTrue(ComponentDisplay.peekLabel(
                comp("mystery_type", "{\"whatever\":1}")).length() > 0);
    }

    // ==================== 展开状态锚点 ====================

    @Test
    public void stateKeyPrefersComponentId() {
        ComponentData withId = comp("table_card", "{\"rows\":[]}");
        withId.id = "T1-C3";
        assertEquals("T1-C3", ComponentDisplay.stateKey(withId, 7));
        ComponentData noId = comp("table_card", "{\"rows\":[]}");
        assertEquals("idx:table_card#7", ComponentDisplay.stateKey(noId, 7));
    }

    // ==================== 档位解析与 pref 名 ====================

    @Test
    public void parseAcceptsAliasesAndIsCaseInsensitive() {
        assertEquals(ComponentDisplay.Mode.INLINE, ComponentDisplay.parse(" Inline ", ComponentDisplay.Mode.PEEK));
        assertEquals(ComponentDisplay.Mode.INLINE, ComponentDisplay.parse("show", ComponentDisplay.Mode.PEEK));
        assertEquals(ComponentDisplay.Mode.PEEK, ComponentDisplay.parse("COLLAPSED", ComponentDisplay.Mode.INLINE));
        assertEquals(ComponentDisplay.Mode.PEEK, ComponentDisplay.parse("summary", ComponentDisplay.Mode.INLINE));
        assertEquals(ComponentDisplay.Mode.HIDDEN, ComponentDisplay.parse("hide", ComponentDisplay.Mode.INLINE));
        assertEquals(ComponentDisplay.Mode.INLINE, ComponentDisplay.parse(null, ComponentDisplay.Mode.INLINE));
        assertEquals(ComponentDisplay.Mode.INLINE, ComponentDisplay.parse("", ComponentDisplay.Mode.INLINE));
        assertEquals("peek", ComponentDisplay.name(ComponentDisplay.Mode.PEEK));
        assertEquals("inline", ComponentDisplay.name(ComponentDisplay.Mode.INLINE));
        assertEquals("hidden", ComponentDisplay.name(ComponentDisplay.Mode.HIDDEN));
    }

    @Test
    public void catalogDoesNotAdvertiseModelControlledDisplay() {
        // 模型目录里不能再出现 display/collapsed 的显隐口径
        JSONArray catalog = ComponentTypes.catalogJson();
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject o = catalog.optJSONObject(i);
            if (o == null) continue;
            String text = o.optString("optional") + o.optString("note");
            assertFalse("目录仍在教模型控制显隐: " + o.optString("type"),
                    text.contains("display=") || text.contains("collapsed=true"));
        }
    }
}

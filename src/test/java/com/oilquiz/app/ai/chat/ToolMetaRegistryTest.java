package com.oilquiz.app.ai.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link ToolMetaRegistry} 的工具名口径（C0-T4）。
 *
 * <p>此前只有 9 个 case，其余工具把 {@code python_execute} 这种内部名直接甩到界面上。
 * 现在要求：名单内的给中文名；名单外的也必须人性化，不能露出下划线原始名。
 */
public class ToolMetaRegistryTest {

    @Test
    public void knownToolsGetChineseNames() {
        assertEquals("Python 执行", ToolMetaRegistry.displayName("python_execute"));
        assertEquals("OCR 识别", ToolMetaRegistry.displayName("ocr_recognize"));
        assertEquals("网络搜索", ToolMetaRegistry.displayName("network_search"));
        assertEquals("UI 组件", ToolMetaRegistry.displayName("ui_component"));
        assertEquals("Excel 处理", ToolMetaRegistry.displayName("excel_tool"));
        assertEquals("数据库查询", ToolMetaRegistry.displayName("database"));
    }

    @Test
    public void weatherAliasesShareOneLabel() {
        // 注册名与历史别名并存（ai_weather 是工具实现里的 getName，get_weather 是旧数据里的名字），
        // 界面上必须显示同一个中文名
        assertEquals(ToolMetaRegistry.displayName("ai_weather"),
                ToolMetaRegistry.displayName("get_weather"));
        assertEquals("天气查询", ToolMetaRegistry.displayName("get_weather"));
    }

    @Test
    public void unknownToolsGetPolishedEnglishNames() {
        // 2026-10-25：用户"也不是必须中文名，英文名也行，别设计太死板"——
        // 表外工具保留润色后的英文名：去尾部技术后缀、缩写大写、下划线转空格
        assertEquals("Foo Bar", ToolMetaRegistry.displayName("foo_bar"));
        assertEquals("Newthing", ToolMetaRegistry.displayName("newthing"));
        assertEquals("Custom Search", ToolMetaRegistry.displayName("custom_search_tool"));
        assertEquals("My Read File", ToolMetaRegistry.displayName("my_read_file_tool"));
        assertEquals("Internal DOCX Writer", ToolMetaRegistry.displayName("internal_docx_writer"));
        String fallback = ToolMetaRegistry.displayName("some_brand_new_tool");
        assertFalse("不能把内部名直接给用户看", fallback.contains("_"));
        assertEquals("Some Brand New", fallback);
        assertFalse(ToolMetaRegistry.isKnown("some_brand_new_tool"));
    }

    @Test
    public void nullAndEmptyAreSafe() {
        assertEquals("", ToolMetaRegistry.displayName(null));
        assertEquals("", ToolMetaRegistry.displayName(""));
        assertFalse(ToolMetaRegistry.isKnown(null));
    }

    @Test
    public void weirdNamesNeverThrowAndNeverExposeRaw() {
        // 2026-10-25 "表外工具不用强求，照样让用，不要造成新 bug"：显示名必须永远安全
        assertEquals("", ToolMetaRegistry.displayName(null));
        assertEquals("", ToolMetaRegistry.displayName(""));
        assertEquals("Tool", ToolMetaRegistry.displayName("_"));
        assertEquals("Tool", ToolMetaRegistry.displayName("___"));
        assertEquals("Tool", ToolMetaRegistry.displayName("   "));
        assertEquals("Foo Bar", ToolMetaRegistry.displayName("foo__bar"));
        assertEquals("Foo Bar", ToolMetaRegistry.displayName("foo\nbar"));
        // 超长内部名截断，避免把工具卡/状态行撑爆
        String longName = "very_long_internal_tool_name_" + "x".repeat(200);
        String label = ToolMetaRegistry.displayName(longName);
        assertTrue("超长名要截断", label.length() <= 49);
        assertTrue(label.endsWith("…"));
        // 非 ASCII / 中文工具名原样可见、不抛异常
        assertEquals("天气查询", ToolMetaRegistry.displayName("天气查询"));
        // 全部分支都不允许抛异常
        for (String weird : new String[]{"a", "-", "_-_", "UPPER_TOOL", "mcp/v2",
                "tool.with.dots", "emoji_😀_tool"}) {
            String l = ToolMetaRegistry.displayName(weird);
            assertTrue("displayName 必须是非 null 字符串: " + weird, l != null);
            assertFalse("不能回吐下划线内部名: " + weird, l.contains("_"));
        }
    }
}

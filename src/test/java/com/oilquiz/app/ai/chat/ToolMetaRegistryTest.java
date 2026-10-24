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
    public void unknownToolsFallBackToChineseCategoryLabel() {
        // 2026-10-25：用户反馈"一堆洋文感到困惑"——未知工具不再 Foo Bar，而是按活动分类给中文兜底
        assertEquals("自定义工具", ToolMetaRegistry.displayName("foo_bar"));
        assertEquals("自定义工具", ToolMetaRegistry.displayName("newthing"));
        assertEquals("搜索工具", ToolMetaRegistry.displayName("custom_search_tool"));
        assertEquals("文件读取工具", ToolMetaRegistry.displayName("my_read_file_tool"));
        assertEquals("文件生成工具", ToolMetaRegistry.displayName("internal_docx_writer"));
        String fallback = ToolMetaRegistry.displayName("some_brand_new_tool");
        assertFalse("不能把内部名直接给用户看", fallback.contains("_"));
        assertFalse("兜底也不该是洋文", fallback.matches(".*[A-Za-z].*"));
        assertFalse(ToolMetaRegistry.isKnown("some_brand_new_tool"));
    }

    @Test
    public void nullAndEmptyAreSafe() {
        assertEquals("", ToolMetaRegistry.displayName(null));
        assertEquals("", ToolMetaRegistry.displayName(""));
        assertFalse(ToolMetaRegistry.isKnown(null));
    }

    @Test
    public void everyMappedNameHasChineseLabel() {
        // 抽查：新增工具时若只登记名字不写中文名，这里会先失败
        String[] mustHave = {"app_operation", "chat_history", "memory", "task", "reminder",
                "file_reader", "file_generator", "file_analyzer", "image_gen", "speech_synthesis",
                "voice_input", "permission_manager", "tool_registry", "knowledge_base",
                "import_start", "export_apk", "linux_shell", "calculator", "unit_converter"};
        for (String name : mustHave) {
            assertTrue(name + " 必须登记中文名", ToolMetaRegistry.isKnown(name));
            String label = ToolMetaRegistry.displayName(name);
            assertFalse(name + " 的中文名不能还是原始名", label.contains("_"));
            assertTrue(name + " 的中文名需含非 ASCII 字符", !label.equals(name));
        }
    }
}

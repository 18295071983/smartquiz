package com.oilquiz.app.ai.tool.openai;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 参数型校验：只查"声明必填却没给"与"给了未声明参数"。
 *
 * <p>要钉住的是<b>不误伤</b>：类型归一由 AIToolManager 负责（数字串、字符串布尔都合法），
 * 未知参数只提示不拒绝；只有"定义里明确 required 且确实缺失"才拒绝执行。</p>
 */
public class ToolArgsValidatorTest {

    private static ToolDefinition def() {
        return ToolDefinition.builder("demo", "演示")
                .addParameter("file_path", "string", "文件路径", true)
                .addParameter("lines", "integer", "行数", false)
                .build();
    }

    @Test
    public void missingRequiredIsRejectedWithNames() {
        ToolArgsValidator.Result r = ToolArgsValidator.validate(def(), new HashMap<>());
        assertFalse(r.ok());
        assertEquals(1, r.missingRequired.size());
        assertEquals("file_path", r.missingRequired.get(0));
        String msg = r.missingMessage();
        assertNotNull(msg);
        assertTrue(msg, msg.contains("file_path"));
        assertTrue("提示要告诉模型补齐后重试", msg.contains("重试"));
    }

    @Test
    public void blankStringCountsAsMissing() {
        Map<String, Object> args = new HashMap<>();
        args.put("file_path", "   ");
        ToolArgsValidator.Result r = ToolArgsValidator.validate(def(), args);
        assertFalse("空白串等同于没给", r.ok());
        assertTrue(r.missingRequired.contains("file_path"));
    }

    @Test
    public void unknownParamsAreReportedButNotBlocking() {
        Map<String, Object> args = new HashMap<>();
        args.put("file_path", "a.txt");
        args.put("unexpected", 1);
        ToolArgsValidator.Result r = ToolArgsValidator.validate(def(), args);
        assertTrue("未知参数不拒绝执行", r.ok());
        assertNull(r.missingMessage());
        assertEquals(1, r.unknown.size());
        assertEquals("unexpected", r.unknown.get(0));
    }

    @Test
    public void nullDefinitionOrOptionalOnlyPasses() {
        assertTrue(ToolArgsValidator.validate(null, new HashMap<>()).ok());
        ToolDefinition optionalOnly = ToolDefinition.builder("demo2", "全可选")
                .addParameter("q", "string", "查询", false)
                .build();
        assertTrue("可选参数缺失不该拒绝", ToolArgsValidator.validate(optionalOnly, null).ok());
    }
}

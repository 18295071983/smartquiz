package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 动态工具子系统的三处硬约束：命名权威唯一、注销顺序正确、半成品工具不假装成功。
 *
 * <p>为什么这些值得钉成测试：动态工具是模型自己写的，它的名字会<b>持久化并且每轮进
 * tools 数组</b>——一个非法名字不是"这个工具不能用"，而是"这轮所有工具都不能用"（服务商
 * 按 function 名规则可能直接拒整个 tools 参数）。同理，注销顺序错了会留下一个能被
 * {@code tool_registry} 搜到并调用的幽灵工具；空逻辑返回成功，则模型会把回显当答案。</p>
 *
 * <p>后两条只能靠源码断言（构造 DynamicAITool/AIToolManager 需要 Context 与脚本引擎），
 * 命名那条是真行为断言。</p>
 */
public class DynamicToolNamingTest {

    // ==================== 命名权威（真行为） ====================

    @Test
    public void legalNamesPass() {
        assertNull(AIToolManager.toolNameError("my_query"));
        assertNull(AIToolManager.toolNameError("A1"));
        assertNull(AIToolManager.toolNameError("tool_2_x"));
        StringBuilder sixty = new StringBuilder("a");
        for (int i = 1; i < 64; i++) sixty.append('b');
        assertNull("64 字符是上限，不是超限", AIToolManager.toolNameError(sixty.toString()));
        assertTrue(AIToolManager.isValidToolName("calc_sum"));
    }

    @Test
    public void illegalNamesAreRejectedWithActionableReasons() {
        assertNotNull(AIToolManager.toolNameError(null));
        assertNotNull(AIToolManager.toolNameError("   "));
        assertNotNull("点号会破坏 function 名规则", AIToolManager.toolNameError("my.tool"));
        assertNotNull(AIToolManager.toolNameError("my tool"));
        assertNotNull(AIToolManager.toolNameError("my-tool"));
        assertNotNull("中文名进 tools 数组会被服务商拒", AIToolManager.toolNameError("查天气"));
        assertNotNull(AIToolManager.toolNameError("1st_tool"));
        assertNotNull("首字符不能是下划线（易与内部前缀混淆）", AIToolManager.toolNameError("_priv"));
        assertNotNull(AIToolManager.toolNameError("system_ui_control.v2"));

        StringBuilder longName = new StringBuilder("a");
        for (int i = 1; i < 80; i++) longName.append('b');
        String tooLong = AIToolManager.toolNameError(longName.toString());
        assertNotNull(tooLong);
        assertTrue("超长要给出上限数字，模型才能自己改短：" + tooLong, tooLong.contains("64"));
    }

    @Test
    public void mcpNamespaceIsReserved() {
        String err = AIToolManager.toolNameError("mcp__amap-maps__maps_geo");
        assertNotNull("动态工具不能占 MCP 命名空间", err);
        assertTrue(err.contains("mcp__"));
        // 带前缀但大小写不同也一样挡（前缀判定不靠巧合）
        assertNotNull(AIToolManager.toolNameError("mcp__anything"));
    }

    // ==================== 两个创建入口共用一份口径（源码断言） ====================

    @Test
    public void bothCreatePathsValidateNames() throws Exception {
        String manager = read("ai/tool/DynamicToolManagerTool.java");
        String creator = read("ai/python/AIToolCreatorTool.java");
        assertTrue("create_dynamic_tool 要用统一权威",
                manager.contains("AIToolManager.toolNameError("));
        assertTrue("ai_create_tool 也要用同一份（以前只有 create_dynamic_tool 校验）",
                creator.contains("AIToolManager.toolNameError("));
        assertFalse("不留第二份正则口径——两份校验迟早漂开",
                manager.contains("matches(\"^[a-zA-Z0-9_]+$\")"));
    }

    @Test
    public void registrationAndRestoreBothGuardTheName() throws Exception {
        String manager = read("ai/tool/AIToolManager.java");
        assertTrue("注册入口必须拒绝非法名（以前是静默 put）",
                manager.contains("拒绝注册非法命名的动态工具"));
        assertTrue("从 dynamic_tools.json 恢复时也要挡（存量里可能已有非法名）",
                manager.contains("跳过非法命名的存量动态工具"));
    }

    // ==================== 注销顺序（源码断言） ====================

    @Test
    public void unregisterRemovesBeforeNotifying() throws Exception {
        String manager = read("ai/tool/AIToolManager.java");
        // 返回 boolean（注册表里到底有没有）是 2026-11-04 补的，锚点不锁返回类型，避免改签名就把行为断言弄丢
        int start = manager.indexOf("unregisterDynamicTool(String name) {");
        assertTrue("找不到注销方法", start > 0);
        assertTrue("注销要把结果返回给调用方（否则删不掉也报已删除）",
                manager.substring(manager.lastIndexOf("public", start), start).contains("boolean"));
        String body = manager.substring(start, manager.indexOf("\n    }", start));
        int removed = body.indexOf("dynamicTools.remove(name)");
        int notified = body.indexOf("notifyDynamicToolChange()");
        assertTrue("注销必须真删", removed >= 0);
        assertTrue("注销必须通知注册表", notified >= 0);
        assertTrue("顺序：先删再通知，反过来注册表会把已删的工具重新写回索引（幽灵工具）",
                removed < notified);
    }

    // ==================== 半成品工具不假装成功 ====================

    @Test
    public void toolWithoutLogicFailsInsteadOfEchoing() throws Exception {
        String tool = read("ai/tool/DynamicAITool.java");
        int start = tool.indexOf("private AIToolResult executeDefaultLogic");
        assertTrue("找不到空逻辑出口", start > 0);
        String body = tool.substring(start, tool.indexOf("\n    }", start));
        assertTrue("必须按失败返回：" + body, body.contains("AIToolResult.fail("));
        assertFalse("不能再把参数回显当结果", body.contains("entry.getValue()"));
        assertTrue("要指下一步（update 补逻辑）", body.contains("action=update"));
    }

    @Test
    public void dynamicToolLogsGoToAiLogsAndSkipValues() throws Exception {
        String tool = read("ai/tool/DynamicAITool.java");
        assertTrue("执行日志走 AILogger（进 ai_logs，可事后回查）", tool.contains("AILogger.i(TAG"));
        assertFalse("不再用裸 logcat", tool.contains("Log.i(TAG") || tool.contains("Log.d(TAG"));
        assertTrue("参数只记键名与个数，不打印值", tool.contains("keyNames(parameters)"));
        assertFalse("不许整包打参数值", tool.contains("\"Parameters: \" + parameters"));
    }

    private static String read(String rel) throws Exception {
        Path p = Paths.get("src/main/java/com/oilquiz/app").resolve(rel);
        assertTrue("文件不存在：" + p, Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}

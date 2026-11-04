package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 动态工具执行层的行为测试（2026-11-04）。
 *
 * <p>覆盖三件以前"看代码看不出错、跑一次才知道"的事：
 * <ol>
 *   <li>失败必须报成失败（{@link DynamicToolExecutor.Outcome#success}），不能再是
 *       "一段写着错误信息的成功输出"；</li>
 *   <li>DSL 的变量表是每次调用的局部状态，不能跨调用残留；</li>
 *   <li>参数注入（{@link ScriptArgs}）的布尔与转义：两个创建入口以前一份对一份错，
 *       同一个"传个布尔参数"的动作在 create_dynamic_tool 里必崩、在 ai_create_tool 里能跑。</li>
 * </ol>
 *
 * <p>为什么 DSL 能用纯 JVM 测：执行器不依赖 Android 运行时（只用 {@code android.util.Log}，
 * 单测里 {@code returnDefaultValues=true} 等价于无副作用）；一旦涉及 Python/JS 引擎
 * 或需要 Context 的注册表就要走下面的源码级断言。
 */
public class DynamicToolExecutionTest {

    private static DynamicToolExecutor.Outcome runDsl(String logic, Map<String, Object> params) {
        return new DynamicToolExecutor(null).run(logic, params);
    }

    // ==================== 成败标志 ====================

    @Test
    public void normalDslLineIsSuccess() {
        DynamicToolExecutor.Outcome ok = runDsl("echo 你好", null);
        assertTrue("正常脚本必须报成功", ok.success);
        assertEquals("你好", ok.output);
        assertEquals("dsl", ok.engine);
    }

    @Test
    public void emptyLogicIsFailureNotText() {
        DynamicToolExecutor.Outcome r = runDsl("   ", null);
        assertFalse("空逻辑不能当成功", r.success);
        assertTrue(r.error.contains("未提供执行逻辑"));
    }

    @Test
    public void badDslLineFailsAndStops() {
        // "set =abc" 等号左边没有变量名 → 旧实现把 "语法错误: set 变量名=值" 当这一行的
        // 输出继续往下跑，最后整次执行报成功
        DynamicToolExecutor.Outcome r = runDsl("set =abc", null);
        assertFalse("坏行必须报失败", r.success);
        assertTrue("要说清是哪一行: " + r.error, r.error.contains("第 1 行失败"));
        assertTrue(r.error.contains("语法错误"));
    }

    @Test
    public void partialOutputSurvivesFailure() {
        DynamicToolExecutor.Outcome r = runDsl("echo 第一段\nif 没有then结构\n", null);
        assertFalse(r.success);
        assertNotNull("已跑出的前几行要还给模型，不然它无从判断错在哪一步", r.output);
        assertTrue(r.output.contains("第一段"));
        assertTrue(r.error.contains("第 2 行失败"));
    }

    @Test
    public void metaToolCallIsRefusedBeforeTouchingRegistry() {
        // 这条断言同时证明"拒绝发生在取注册表之前"——本测试传的是 null Context，
        // 若先取 AIToolManager 就会 NPE 而不是给出安全限制
        DynamicToolExecutor.Outcome r = runDsl("call_tool create_dynamic_tool", null);
        assertFalse(r.success);
        assertTrue(r.error.contains("安全限制"));
        assertTrue(r.error.contains("create_dynamic_tool"));
    }

    // ==================== 变量表作用域 ====================

    @Test
    public void dslVariablesDoNotLeakAcrossCalls() {
        DynamicToolExecutor executor = new DynamicToolExecutor(null);
        assertTrue(executor.run("set city=宁夏", null).success);
        DynamicToolExecutor.Outcome second = executor.run("echo ${city}", null);
        assertTrue(second.success);
        assertFalse("上一次调用 set 的值不能残留到这一次（跨轮/跨会话残留＝行为不可复现）",
                second.output != null && second.output.contains("宁夏"));
    }

    @Test
    public void substitutionKeepsLiteralDollarAndBackslash() {
        // 旧实现直接把值塞进 appendReplacement：值里含 $ 会抛 IllegalArgumentException
        // （Illegal group reference），含反斜杠会被当转义吃掉
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("path", "C:\\Temp\\a$1b");
        DynamicToolExecutor.Outcome r = new DynamicToolExecutor(null).run("echo ${path}", p);
        assertTrue(r.success);
        assertEquals("C:\\Temp\\a$1b", r.output);
    }

    // ==================== 参数注入 ====================

    @Test
    public void pythonBooleansAreTrueFalse() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("flag", Boolean.TRUE);
        p.put("off", Boolean.FALSE);
        String dict = ScriptArgs.pythonDict(p);
        // create_dynamic_tool 那份旧代码注进去的是 true/false —— Python 里是未定义名，
        // 脚本第一行就 NameError，而模型只看到"Python 执行失败"
        assertTrue(dict, dict.contains("\"flag\": True"));
        assertTrue(dict, dict.contains("\"off\": False"));
    }

    @Test
    public void pythonStringsAreEscapedNotBroken() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("path", "C:\\a\\'b");
        p.put("multi", "第一行\n第二行");
        String dict = ScriptArgs.pythonDict(p);
        assertFalse("换行不能真的落进字面量（会把 script_args 拆成两条语句）",
                dict.contains("\n"));
        assertTrue(dict, dict.contains("\\\\'"));
        assertTrue(dict, dict.contains("\\n"));
    }

    @Test
    public void jsonContainersBecomePythonContainers() {
        JSONObject nested = new JSONObject();
        try {
            nested.put("k", "v");
            nested.put("n", 3);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        JSONArray arr = new JSONArray();
        arr.put(nested);
        arr.put(true);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("obj", nested);
        p.put("list", arr);
        String dict = ScriptArgs.pythonDict(p);
        // org.json 的键顺序不保证，所以只断言"每个成员都在且形状是容器"，不断言先后
        assertFalse("org.json 类型不能被 toString 成带引号的 JSON 文本",
                dict.contains("\"obj\": \"{"));
        assertTrue(dict, dict.contains("\"obj\": {"));
        assertTrue(dict, dict.contains("\"k\": \"v\""));
        assertTrue(dict, dict.contains("\"n\": 3"));
        assertTrue(dict, dict.contains("\"list\": ["));
        assertTrue(dict, dict.contains(", True]"));
    }

    @Test
    public void fullPythonScriptHasPreludeAndBody() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("q", "1");
        String script = ScriptArgs.fullPythonScript("print(script_args['q'])", p);
        assertTrue(script.startsWith("# -*- coding: utf-8 -*-"));
        assertTrue(script, script.contains("script_args = {\"q\": \"1\"}"));
        assertTrue(script, script.endsWith("print(script_args['q'])"));
    }

    @Test
    public void jsPreludeAlwaysGivesAnObject() {
        assertTrue(ScriptArgs.jsPrelude(null), ScriptArgs.jsPrelude(null).contains("= {};"));
        String withArgs = ScriptArgs.jsPrelude(oneParam("x", "y"));
        assertTrue(withArgs, withArgs.contains("script_args"));
        assertTrue(withArgs, withArgs.contains("\"x\":\"y\""));
    }

    @Test
    public void keyNamesNeverContainValues() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("phone", "13800000000");
        String summary = ScriptArgs.keyNames(p);
        assertTrue(summary, summary.contains("phone"));
        assertFalse("日志摘要只能有键名", summary.contains("13800000000"));
    }

    // ==================== 预算 ====================

    @Test
    public void budgetClampsEngineTimeouts() {
        assertEquals("剩余为 0 也要给 1 秒（0 会被引擎解读成\"用默认\"，绕开预算）",
                1, DynamicToolBudget.engineTimeoutSeconds(0, 30));
        assertEquals(1, DynamicToolBudget.engineTimeoutSeconds(500, 30));
        assertEquals(7, DynamicToolBudget.engineTimeoutSeconds(7_500, 30));
        assertEquals("不能超过引擎上限", 30, DynamicToolBudget.engineTimeoutSeconds(60_000, 30));
        assertTrue(DynamicToolBudget.callerWaitMs() > DynamicToolBudget.SCRIPT_BUDGET_MS);
    }

    // ==================== 接线（源码级） ====================

    @Test
    public void outcomeIsPropagatedToTheToolResult() throws Exception {
        String dyn = read("ai/tool/DynamicAITool.java");
        assertTrue("必须读 Outcome 而不是把字符串当成功",
                dyn.contains("DynamicToolExecutor.Outcome outcome = executor.run("));
        assertTrue("脚本失败要报 AIToolResult.fail", dyn.contains("if (!outcome.success)"));
        assertTrue("不能再有无条件 success(result)", !dyn.contains("AIToolResult.success(result"));

        String py = read("ai/python/PythonDynamicTool.java");
        assertTrue("异常分支也必须报失败（两参构造会把失败报成成功）",
                py.contains("additionalInfo, false)"));

        String manager = read("ai/tool/DynamicToolManagerTool.java");
        assertTrue("试运行的成败要透传，不能永远绿", manager.contains("AIToolResult.fail(\"🧪 试运行失败"));
    }

    @Test
    public void bothKindsOfDynamicToolDeclareTheirWait() throws Exception {
        // 不申报＝调用方按默认 30s 等，而脚本可能合法跑更久：外层先放弃，副作用却已经发生
        assertTrue(read("ai/tool/DynamicAITool.java").contains(
                "public long executionTimeoutMs(Map<String, Object> args) {"));
        assertTrue(read("ai/python/PythonDynamicTool.java").contains(
                "public long executionTimeoutMs(Map<String, Object> args) {"));
        assertTrue("两边同源一个预算，不会各自漂移",
                read("ai/tool/DynamicAITool.java").contains("DynamicToolBudget.callerWaitMs()")
                        && read("ai/python/PythonDynamicTool.java")
                                .contains("DynamicToolBudget.callerWaitMs()"));
        String exec = read("ai/tool/DynamicToolExecutor.java");
        assertTrue("JS 超时不能再硬编码 10s", !exec.contains("p.put(\"timeout\", 10)"));
        assertTrue("Python 要显式拿预算而不是吃引擎默认", exec.contains("ptm.executeCode(fullCode, null, timeoutSeconds)"));
    }

    @Test
    public void registrationResultIsHonouredByAllThreeCallers() throws Exception {
        String manager = read("ai/tool/AIToolManager.java");
        assertTrue("注册要返回成不成", manager.contains("public boolean registerDynamicTool(AITool tool)"));
        assertTrue("创建入口拿不到工具时必须返回 null",
                manager.contains("return registerDynamicTool(tool) ? tool : null;"));

        String creator = read("ai/python/AIToolCreatorTool.java");
        assertTrue("ai_create_tool 不能在被拒后还说\"已注册并可以使用\"",
                creator.contains("boolean registered = registerDynamicTool(creationResult")
                        && creator.contains("if (!registered) {"));

        String plugin = read("ai/plugin/PluginContext.java");
        assertTrue("插件工具被拒时不能只注册到\"模型可见\"那一半",
                plugin.contains("boolean registered = toolManager == null")
                        && plugin.contains("throw new IllegalStateException"));
    }

    @Test
    public void halfBuiltToolIsNotAnnouncedAsUsable() throws Exception {
        String mgr = read("ai/tool/DynamicToolManagerTool.java");
        assertTrue("没有逻辑的创建结果必须给下一步，而不是\"现在可以使用\"",
                mgr.contains("这个工具还不能调用"));
        assertFalse("旧文案（未定义也说能用）不能回来", mgr.contains("将使用默认逻辑"));
    }

    @Test
    public void toolFileIsWrittenAtomicallyAndFailuresAreAudible() throws Exception {
        String manager = read("ai/tool/AIToolManager.java");
        assertTrue("先写 .tmp 再 rename，进程中途被杀不能留下半截 JSON",
                manager.contains(".tmp\")") && manager.contains("tmp.renameTo(target)"));
        assertTrue("整份读不进来要能在 ai_logs 查到",
                manager.contains("动态工具清单读取失败"));
        assertTrue("单条坏数据要计数并说明", manager.contains("skipped++"));
    }

    @Test
    public void dslNestedCallCannotOutliveTheInvocation() throws Exception {
        String exec = read("ai/tool/DynamicToolExecutor.java");
        assertTrue("嵌套子调用的预算要被本次调用的剩余时间夹住",
                exec.contains("final long remainingMs) throws DslLineError")
                        && exec.contains("remainingMs));"));
        assertTrue("子调用失败必须抛，不能再当这一行的输出",
                exec.contains("throw new DslLineError(\"嵌套工具 \" + toolName + \" 失败: \""));
    }

    @Test
    public void deleteAndUpdateReportWhatTheRegistryActuallyDid() throws Exception {
        String manager = read("ai/tool/AIToolManager.java");
        assertTrue("注销要回答注册表里到底有没有这个东西",
                manager.contains("public boolean unregisterDynamicTool(String name) {"));

        String mgr = read("ai/tool/DynamicToolManagerTool.java");
        assertTrue("删除结果要按注册表真实回答，不能永远报已删除",
                mgr.contains("boolean removed = toolManager.unregisterDynamicTool(toolName)")
                        && mgr.contains("动态工具未删除"));
        assertTrue("更新后仍没逻辑时必须说清它调用会失败",
                mgr.contains("它仍然没有执行逻辑"));
    }

    // ==================== 辅助 ====================

    private static Map<String, Object> oneParam(String k, String v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    private static String read(String rel) throws Exception {
        java.nio.file.Path p = java.nio.file.Paths.get("src/main/java/com/oilquiz/app").resolve(rel);
        assertTrue("找不到源文件 " + rel + " (" + p + ")", Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}

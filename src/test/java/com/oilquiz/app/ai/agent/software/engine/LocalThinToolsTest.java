package com.oilquiz.app.ai.agent.software.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 本地「薄工具」适配层不变量（2026-10-25）。
 *
 * <p>薄工具（read_file/write_file…）只在 schema 里出现、执行时翻译成真实工具。三个必守的口径：
 * ① 每个薄工具名都必须在 {@code DEFAULT_CORE_TOOLS} 里（否则永远不会被注入、模型点不到）；
 * ② FC 提示词不得点名工具数量，也不得提及未注入的工具（2026-11-01 起改成"不写数量"，
 *    历史上的"说 4 实际 6/9"漂移从此在结构上不可能发生）；
 * ③ 工具用途只写在 schema description，提示词不复述（复述是本地提示词的主要体积开销）；
 * ④ system 正文与日期无关（DATE-TAIL 2026-11-01）：日期改为本轮 user 末尾的块，
 *    跨零点不再让 system 前缀失效，见 systemPromptsContainNoDateVaryingText。
 */
public class LocalThinToolsTest {

    private static Object readStatic(String field) throws Exception {
        Field f = AgentLoopEngine.class.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(null);
    }

    @Test
    public void everyThinToolIsInDefaultCoreTools() throws Exception {
        String[] core = (String[]) readStatic("DEFAULT_CORE_TOOLS");
        @SuppressWarnings("unchecked")
        Map<String, ?> thin = (Map<String, ?>) readStatic("LOCAL_THIN_TOOLS");
        Set<String> coreSet = new HashSet<>(Arrays.asList(core));
        assertTrue("薄工具表不应为空", !thin.isEmpty());
        for (String name : thin.keySet()) {
            assertTrue("薄工具 " + name + " 未进 DEFAULT_CORE_TOOLS，模型永远看不到",
                    coreSet.contains(name));
        }
    }

    @Test
    public void fcPromptToolCountMatchesTheInjectedSet() throws Exception {
        // 直接调用真实方法（原实现按源码文本扫方法体：修饰符一改——比如把这两个 prompt
        // 方法声明成 static——indexOf 就返回 -1，整条不变量指数组越界崩溃，属于仪器坏了
        // 而不是被测物变了）。方法体只有字面量拼接，运行时返回值与代码行文本等价。
        String fc = invokePrompt("buildFcSystemPrompt");
        String[] core = (String[]) readStatic("DEFAULT_CORE_TOOLS");
        Set<String> coreSet = new HashSet<>(Arrays.asList(core));

        // PROMPT-ALIGN(2026-11-01)：提示词不再写死工具数量——原来"说 4 实际 6/9"的漂移，
        // 根因就是数量写在正文里；改成不点名数量后，这类漂移在结构上不可能发生。
        // TOOL-DISCOVERY(2026-11-01)：工具集不再是"有限闭集"，所以措辞从"只能调用这些"
        // 改成"直接调用这些；未注入的走 tool_registry"，模型才知道发现那条路存在。
        assertTrue("FC 提示词应说明『直接调用本次注入的工具 + 未注入的走 tool_registry』",
                fc.contains("直接调用的是本次注入的这些工具") && fc.contains("tool_registry"));
        assertFalse("FC 提示词不应再写死『下列九个工具』", fc.contains("下列九个工具"));
        assertFalse("FC 提示词不应再写死『下列四个工具』", fc.contains("下列四个工具"));

        // 提示词里点名的工具必须真的在注入集里（native PEG 逐字枚举，点名未注入的工具
        // 会让整轮 tool_call 退化成正文——这是这条测试存在的原始理由）。
        // 逐一反查已知工具名，而不是写死"提示词该提哪几个"：新增/删工具时不必回来改断言。
        String[] knownTools = {"time_date", "location", "ai_weather", "network_search", "read_file",
                "write_file", "calculate", "search_questions", "recall_history", "ui_component",
                "workspace", "tool_registry", "knowledge_base", "memory", "task", "image_gen"};        int named = 0;
        for (String t : knownTools) {
            if (!fc.contains(t)) continue;
            named++;
            assertTrue("FC 提示词点名了未注入的工具：" + t
                    + "（native 只能生成注入过的工具名，模型会整轮退化成正文）", coreSet.contains(t));
        }
        assertTrue("FC 提示词应至少点名一个工具（天气/搜索的跨工具规则依赖它），实际点名数=" + named,
                named >= 1);

        // 反向不变量：工具用途只写在 schema description 里，提示词不复述。
        // （复述是本地这份的主要体积开销，2B 还要在两段重复文本之间自行对齐。）
        // TOOL-DISCOVERY(2026-11-01)：tool_registry / memory / workspace 不在此列——它们必须被
        // 提示词各点名一句（发现入口、记忆触发、工作区归属），否则模型不会主动想到用；
        // 其余薄工具仍不得复述用法。
        for (String thin : new String[]{"read_file", "write_file", "calculate",
                "search_questions", "task"}) {
            assertFalse("薄工具 " + thin + " 的用法应由 schema 承担，不该再抄进 FC 提示词",
                    fc.contains(thin + " "));
        }
    }

    /**
     * WORKSPACE/MEMORY(2026-11-01)：这两个工具必须在核心集里可调，并且在提示词里有触发场景——
     * 只写 schema 的话模型不会主动想到（工作区会被绕过成 read_file 猜路径，偏好当闲聊丢掉）。
     */
    @Test
    public void workspaceAndMemoryAreCallableAndMentionedInPrompt() throws Exception {
        String[] core = (String[]) readStatic("DEFAULT_CORE_TOOLS");
        Set<String> coreSet = new HashSet<>(Arrays.asList(core));
        assertTrue("workspace 必须在本地核心集里（提示词点名了它，PEG 只能调用注入过的工具）",
                coreSet.contains("workspace"));
        assertTrue("memory 必须在本地核心集里", coreSet.contains("memory"));

        @SuppressWarnings("unchecked")
        Map<String, ?> thin = (Map<String, ?>) readStatic("LOCAL_THIN_TOOLS");
        Object ws = thin.get("workspace");
        assertTrue("workspace 必须有薄 schema（否则退化为完整定义，吃前缀 token）", ws != null);
        java.lang.reflect.Field paramsF = ws.getClass().getDeclaredField("params");
        paramsF.setAccessible(true);
        String[][] params = (String[][]) paramsF.get(ws);
        Set<String> names = new HashSet<>();
        for (String[] p : params) names.add(p[0]);
        assertTrue("workspace 需要 fileName（read/versions/restore/delete 的定位键）",
                names.contains("fileName"));
        assertTrue("workspace 需要 action", names.contains("action"));

        // CONFIRM-PROTOCOL(2026-11-01)：restore/delete 会被工具以 confirm 拦住，协议必须写在
        // 模型可见的描述里——真实执行侧 boolParam 只认 true/1，漏传就是"操作被拒绝"。
        java.lang.reflect.Field descF = ws.getClass().getDeclaredField("desc");
        descF.setAccessible(true);
        String wsDesc = String.valueOf(descF.get(ws));
        assertTrue("workspace 描述必须写明 restore/delete 需要 confirm",
                wsDesc.contains("confirm"));
        String confirmDesc = null;
        String actionDesc = null;
        for (String[] p : params) {
            if ("confirm".equals(p[0])) confirmDesc = p[2];
            if ("action".equals(p[0])) actionDesc = p[2];
        }
        assertTrue("confirm 参数描述必须说明必填 true", confirmDesc != null && confirmDesc.contains("true"));
        assertTrue("action 描述必须标出 restore/delete 需 confirm",
                actionDesc != null && actionDesc.contains("restore") && actionDesc.contains("confirm"));

        String fc = invokePrompt("buildFcSystemPrompt");
        assertTrue("提示词应给出工作区的使用场景", fc.contains("workspace"));
        assertTrue("提示词应给出记忆的触发场景", fc.contains("memory"));
    }

    /**
     * TOOL-DISCOVERY(2026-11-01)：本地工具名由 native PEG 逐字枚举，中途改集会毁 tools 前缀，
     * 所以"发现"必须能直接落地为执行——tool_registry 必须常驻核心，且它的薄 schema 里
     * 必须带 tool 与 args 两个参数，否则 call 动作无法表达目标与参数（发现出来也调不动）。
     */
    @Test
    public void toolRegistryIsInCoreAndExposesCallParams() throws Exception {
        String[] core = (String[]) readStatic("DEFAULT_CORE_TOOLS");
        assertTrue("tool_registry 必须在本地核心集里（否则发现入口不存在）",
                new HashSet<>(Arrays.asList(core)).contains("tool_registry"));

        @SuppressWarnings("unchecked")
        Map<String, ?> thin = (Map<String, ?>) readStatic("LOCAL_THIN_TOOLS");
        Object entry = thin.get("tool_registry");
        assertTrue("tool_registry 必须有薄 schema（否则会退化为完整定义，吃前缀 token）",
                entry != null);
        java.lang.reflect.Field paramsF = entry.getClass().getDeclaredField("params");
        paramsF.setAccessible(true);
        String[][] params = (String[][]) paramsF.get(entry);
        Set<String> names = new HashSet<>();
        for (String[] p : params) names.add(p[0]);
        assertTrue("call 动作需要 tool 参数", names.contains("tool"));
        assertTrue("call 动作需要 args 参数", names.contains("args"));
    }

    /**
     * 调用 system 提示词构建方法（buildSystemPrompt / buildFcSystemPrompt 都是无实例状态的
     * 纯函数，声明为 private static）→ 返回值就是模型看到的 system 正文，不用解析源码。
     * 参数是用户人设：null = 未设置（默认行为）。
     */
    private static String invokePrompt(String method) throws Exception {
        return invokePrompt(method, null);
    }

    private static String invokePrompt(String method, String persona) throws Exception {
        java.lang.reflect.Method m = AgentLoopEngine.class.getDeclaredMethod(method, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, persona);
    }

    /** 调用 buildCurrentTimeLine(boolean)：本轮末尾的日期块。 */
    private static String invokeDateBlock(boolean fcMode) throws Exception {
        java.lang.reflect.Method m = AgentLoopEngine.class.getDeclaredMethod(
                "buildCurrentTimeLine", boolean.class);
        m.setAccessible(true);
        return (String) m.invoke(null, fcMode);
    }

    /** 调用 appendTailBlock(String, String)：D 块的安全拼接。 */
    private static String invokeAppendTailBlock(String base, String block) throws Exception {
        java.lang.reflect.Method m = AgentLoopEngine.class.getDeclaredMethod(
                "appendTailBlock", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, base, block);
    }

    /**
     * SCHEMA-SLIM(2026-11-01)：本地「模型可见面」裁剪表的不变量。
     *
     * <p>裁剪表按名字保留参数，而名字的来源是另一份文件（AIToolManager 的静态定义）。
     * 两边一旦错开——参数改名、删掉、或工具根本没进核心集——{@code buildToolsJson} 是
     * {@code byName.get()} 拿不到就跳过，**静默少一个参数**：模型连唯一的必填参数都看不到，
     * 表现为"天气工具调用不带城市"这类很难归因的行为退化。本测试把这条静默路径变成红。</p>
     */
    @Test
    public void slimmedSchemaParamsMustExistInTheRealDefinition() throws Exception {
        Map<String, ?> slim = (Map<String, ?>) readStatic("LOCAL_SCHEMA_SLIM");
        assertFalse("裁剪表不应为空", slim.isEmpty());
        Set<String> coreSet = new HashSet<>(Arrays.asList((String[]) readStatic("DEFAULT_CORE_TOOLS")));

        String mgr = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/oilquiz/app/ai/tool/AIToolManager.java")), StandardCharsets.UTF_8);

        for (Map.Entry<String, ?> e : slim.entrySet()) {
            String tool = e.getKey();
            assertTrue("裁剪表点了未注入的工具：" + tool + "（模型永远看不到它，白写一张表）",
                    coreSet.contains(tool));

            int at = mgr.indexOf("ToolDefinition.builder(\"" + tool + "\"");
            assertTrue("AIToolManager 里找不到 " + tool + " 的静态定义，裁剪表无从对账", at >= 0);
            int end = mgr.indexOf(".build();", at);
            String def = mgr.substring(at, end < 0 ? mgr.length() : end);

            Object rule = e.getValue();
            String desc = (String) readInstance(rule, "desc");
            assertTrue(tool + " 的裁剪表应提供本地专用短描述", desc != null && !desc.trim().isEmpty());

            String[][] keep = (String[][]) readInstance(rule, "keepParams");
            assertTrue(tool + " 至少应保留一个参数", keep.length > 0);
            for (String[] k : keep) {
                assertTrue(tool + " 的裁剪表保留的参数 " + k[0]
                        + " 在真实定义里不存在（buildToolsJson 会静默跳过它）",
                        def.contains("addParameter(\"" + k[0] + "\""));
                assertFalse("引擎自己填的参数不应回到模型可见面：" + tool + "." + k[0],
                        "lat".equals(k[0]) || "lon".equals(k[0])
                                || "latitude".equals(k[0]) || "longitude".equals(k[0]));
            }
        }
    }

    /** 参数面裁掉 lat/lon 后，提示词与回给模型的观察文本都不该再提它们。 */
    @Test
    public void promptAndToolHintsNoLongerReferenceHiddenLocationParams() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/oilquiz/app/ai/agent/software/engine/AgentLoopEngine.java")),
                StandardCharsets.UTF_8);
        String fc = invokePrompt("buildFcSystemPrompt");
        assertFalse("FC 提示词不应再让模型回避未注入的 lat/lon", fc.contains("lat/lon"));
        assertFalse("FC 提示词不应出现 latitude/longitude 别名", fc.contains("latitude"));

        // getAlternativeTool 的返回值是**回给模型的观察文本**，只扫方法体内的代码行（跳过注释）。
        StringBuilder alt = new StringBuilder();
        boolean inMethod = false;
        for (String line : src.split("\r?\n")) {
            String s = line.trim();
            if (!inMethod) {
                if (s.startsWith("private static String getAlternativeTool(")) inMethod = true;
                continue;
            }
            if (s.equals("}")) break;
            if (s.isEmpty() || s.startsWith("//") || s.startsWith("*")) continue;
            alt.append(s).append('\n');
        }
        assertTrue("没找到 getAlternativeTool 方法体（改名了要同步改这条不变量）",
                alt.length() > 0);
        assertFalse("工具失败建议不应让模型去掉它看不见的参数",
                alt.toString().contains("lat/lon"));
    }

    /**
     * DATE-TAIL(2026-11-01)：system 前缀必须逐字节静态，跨零点也不许失效。
     *
     * <p>日期原先写在 system 第一段：每天第一条消息把那行换掉，头部之后的会话序列虽然
     * 逐字节可回放，回放到的却是已经变头的 prompt → 前缀匹配长度归零 → 本地实测一次
     * ~20s 的全量 re-prefill。挪到本轮 user 末尾后，system 与更早轮次都不含日期。
     * 本条不变量的作用是让"把日期塞回头部"这个动作当场变红。</p>
     */
    @Test
    public void systemPromptsContainNoDateVaryingText() throws Exception {
        for (String method : new String[]{"buildSystemPrompt", "buildFcSystemPrompt"}) {
            String prompt = invokePrompt(method);
            assertTrue(method + " 不该是空提示词（扫描口径错了要当场发现）", !prompt.isEmpty());
            assertFalse(method + " 里不许出现日期块：跨天会让整个 system 前缀失效",
                    prompt.contains("【当前真实日期】"));
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\d{4}年|\\d{1,2}月|星期[一二三四五六日天]|周[一二三四五六日]")
                    .matcher(prompt);
            boolean hit = m.find();
            assertFalse(method + " 里不许出现任何随日期变化的文本，实际命中："
                    + (hit ? "『" + m.group() + "』" : "无"), hit);
        }
    }

    /** 日期块自身：尾巴形状 + 跨天后的新旧冲突必须由文本压掉。 */
    @Test
    public void dateBlockIsTailShapedAndSupersedesHistory() throws Exception {
        for (boolean fc : new boolean[]{false, true}) {
            String block = invokeDateBlock(fc);
            assertTrue("日期块必须以空行开头（它是拼在已有文本之后的尾巴，不是段首）",
                    block.startsWith("\n\n"));
            assertFalse("日期块结尾不要留空行：后面还会接【本次任务】/【已存记忆】等块",
                    block.endsWith("\n\n"));
            assertTrue("跨天回放时同一 prompt 里会同时存在两轮日期块，必须声明以本轮为准",
                    block.contains("更早轮次的快照"));
            if (fc) {
                assertTrue("FC 档要把具体时刻指给 time_date 工具", block.contains("time_date"));
            } else {
                assertFalse("普通对话档不注入工具，不许让模型去调 time_date",
                        block.contains("time_date"));
            }
        }
    }

    /** D 块拼接的 null 安全：字符串相加会把 null 变字面量 "null" 发给模型。 */
    @Test
    public void appendTailBlockNeverProducesLiteralNull() throws Exception {
        assertEquals("hi\n\nX", invokeAppendTailBlock("hi", "\n\nX"));
        assertEquals("X", invokeAppendTailBlock(null, "\n\nX"));
        assertEquals("X", invokeAppendTailBlock("   ", "\n\nX"));
        assertEquals("hi", invokeAppendTailBlock("hi", ""));
        assertEquals("hi", invokeAppendTailBlock("hi", null));
        assertNull(invokeAppendTailBlock(null, ""));
    }

    /**
     * PERSONA(2026-11-01)：人设注入。
     * 未设置时必须与旧文本一致（默认行为不变）；设置后用用户人设替换内置身份句，
     * 但行为/工具要求必须保留——它们属于工具协议，不能因为换了人设就丢。
     */
    @Test
    public void personaReplacesIdentityButKeepsBehaviorRules() throws Exception {
        String fcNone = invokePrompt("buildFcSystemPrompt", null);
        assertTrue("未设置人设时应保留内置身份句",
                fcNone.contains("你是手机App里帮用户解决问题的助手"));

        String sample = "你是一位化工厂的工艺工程师助手，回答要带单位与量程。";
        String fcWith = invokePrompt("buildFcSystemPrompt", sample);
        assertTrue("用户人设必须出现在 system 里", fcWith.contains(sample));
        assertFalse("有人设时不应再出现内置身份句",
                fcWith.contains("你是手机App里帮用户解决问题的助手"));
        assertTrue("行为规则不能随人设一起丢",
                fcWith.contains("实时信息必须用工具查") && fcWith.contains("先征得用户确认"));

        String plainWith = invokePrompt("buildSystemPrompt", sample);
        assertTrue("普通对话也要注入人设", plainWith.contains(sample));
        assertFalse(plainWith.contains("你是答题宝App中的AI聊天助手"));
        assertTrue("普通对话仍要保留不编造约束", plainWith.contains("不确定时如实说明，不编造"));
    }

    private static Object readInstance(Object target, String field) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }
}

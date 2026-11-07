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
     * DATABASE-THIN(2026-11-07)：本地核心集必须能取题库数据（题数/分页/只读 SQL），
     * 且薄 schema 只暴露只读动作；execute_sql 必须挡掉多语句与非 SELECT。
     * 真机 2026-11-07：用户说“你不是有数据库题目吗”，本地 Agent 只有 search_questions，
     * 结果把“题库/导出 APK”当题干关键词搜出 0 条，然后去翻应用目录空转。
     */
    @Test
    public void databaseThinToolIsInCoreAndReadOnly() throws Exception {
        String[] core = (String[]) readStatic("DEFAULT_CORE_TOOLS");
        assertTrue("database 必须在本地核心集里（否则模型无取数入口）",
                new HashSet<>(Arrays.asList(core)).contains("database"));

        @SuppressWarnings("unchecked")
        Map<String, ?> thin = (Map<String, ?>) readStatic("LOCAL_THIN_TOOLS");
        Object entry = thin.get("database");
        assertTrue("database 必须有薄 schema（避免注入完整 20+ action 定义）", entry != null);
        java.lang.reflect.Field paramsF = entry.getClass().getDeclaredField("params");
        paramsF.setAccessible(true);
        String[][] params = (String[][]) paramsF.get(entry);
        Set<String> names = new HashSet<>();
        for (String[] p : params) names.add(p[0]);
        assertTrue("database 需要 action", names.contains("action"));
        assertTrue("database 需要 sql（execute_sql 用）", names.contains("sql"));
        assertTrue("database 需要 page/page_size（分页取题）",
                names.contains("page") && names.contains("page_size"));

        assertTrue(AgentLoopEngine.isLocalDatabaseReadAction("get_question_count"));
        assertTrue(AgentLoopEngine.isLocalDatabaseReadAction("get_questions"));
        assertTrue(AgentLoopEngine.isLocalDatabaseReadAction("execute_sql"));
        assertFalse("写动作不得进入薄 database",
                AgentLoopEngine.isLocalDatabaseReadAction("delete_question"));

        assertTrue(AgentLoopEngine.isReadOnlySelectSql("SELECT id FROM question LIMIT 1"));
        assertTrue("尾部一个分号应放行", AgentLoopEngine.isReadOnlySelectSql("select 1;"));
        assertFalse("非 SELECT 必须拒绝", AgentLoopEngine.isReadOnlySelectSql("DELETE FROM question"));
        assertFalse("分号多语句必须拒绝",
                AgentLoopEngine.isReadOnlySelectSql("select 1; delete from question"));
        assertFalse("空 SQL 必须拒绝", AgentLoopEngine.isReadOnlySelectSql("  "));
    }

    /**
     * WHERE-AM-I(2026-11-07)：本地核心集必须能回答“我在哪”。
     * 真机 2026-11-07：location 不在注入集，模型拿 ai_weather（空 city 自动定位）和
     * network_search 代查，最后答“无法得知你具体在哪里”。
     */
    @Test
    public void locationIsInCoreAndWhereAmIIntentIsNarrow() throws Exception {
        String[] core = (String[]) readStatic("DEFAULT_CORE_TOOLS");
        assertTrue("location 必须在本地核心集里（否则“我在哪”没有可调用的定位工具）",
                new HashSet<>(Arrays.asList(core)).contains("location"));
        String fc = invokePrompt("buildFcSystemPrompt");
        assertTrue("提示词应给出位置问题的工具选用规则", fc.contains("location"));

        assertTrue(AgentLoopEngine.isWhereAmIQuery("我在哪"));
        assertTrue(AgentLoopEngine.isWhereAmIQuery("我现在在哪里？"));
        assertTrue(AgentLoopEngine.isWhereAmIQuery("我在哪个城市"));
        assertTrue(AgentLoopEngine.isWhereAmIQuery("这是哪"));
        assertTrue(AgentLoopEngine.isWhereAmIQuery("当前位置"));
        assertFalse("“在哪买票”不是位置查询",
                AgentLoopEngine.isWhereAmIQuery("我在哪买票"));
        assertFalse(AgentLoopEngine.isWhereAmIQuery("今天天气怎么样"));
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

    /**
     * CLEAN-UNCLOSED-TOOLCALL(2026-11-01)：收尾清理不得把"标签之后的正文"一起吃掉。
     * 旧实现是正则删到文本结尾（真机实测某轮 1508 字只剩 577），现在只吞标签+紧随的 JSON。
     */
    @Test
    public void unclosedToolCallStripsOnlyTagAndJson() throws Exception {
        java.lang.reflect.Method m = AgentLoopEngine.class.getDeclaredMethod(
                "stripUnclosedToolCalls", String.class);
        m.setAccessible(true);

        String in = "前文\n<tool_call>{\"name\":\"workspace\",\"arguments\":{\"action\":\"list\"}}\n后文正文";
        String out = (String) m.invoke(null, in);
        assertFalse("标签应被剥掉", out.contains("<tool_call"));
        assertFalse("紧随的 JSON 应被吞掉", out.contains("\"name\""));
        assertTrue("标签之后的正文必须保留", out.contains("后文正文"));
        assertTrue("标签之前的正文必须保留", out.contains("前文"));

        String noJson = (String) m.invoke(null, "A<tool_call>B");
        assertTrue("没有 JSON 时只删标签，前后正文都在",
                noJson.contains("A") && noJson.contains("B"));
        assertFalse(noJson.contains("<tool_call"));

        String truncated = (String) m.invoke(null, "A<tool_call>{\"name\":\"x");
        assertTrue("前半段正文保留", truncated.contains("A"));
        assertFalse("JSON 被截断时不能残留半截", truncated.contains("\"name\""));
    }

    /**
     * REPEAT-WHITELIST(2026-11-02)：异步轮询类调用不参与"同参去重"，其余照旧。
     * 判据必须按 (工具, action) 精确匹配——按工具名一刀切会把 exec/写操作一起放过。
     */
    @Test
    public void repeatablePollingWhitelistMatchesToolAndAction() throws Exception {
        java.lang.reflect.Method m = AgentLoopEngine.class.getDeclaredMethod(
                "isRepeatablePollingCall", String.class, String.class);
        m.setAccessible(true);
        java.util.function.BiFunction<String, String, Boolean> allow = (tool, action) -> {
            try {
                return (Boolean) m.invoke(null, tool, action);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        assertTrue("import_status 按 taskId 轮询，整个工具豁免", allow.apply("import_status", ""));
        assertTrue(allow.apply("dashscope_media", "query"));
        assertFalse("提交动作不能豁免（重复提交=重复计费）", allow.apply("dashscope_media", "video"));
        assertTrue(allow.apply("screen_watch", "status"));
        assertFalse(allow.apply("screen_watch", "start"));
        assertTrue(allow.apply("linux_shell", "termux_status"));
        assertTrue(allow.apply("linux_shell", "termux_wake"));
        assertFalse("同一命令反复执行要拦", allow.apply("linux_shell", "exec"));
        assertTrue(allow.apply("remote_dsh", "get_status"));
        assertFalse(allow.apply("remote_dsh", "run"));
        assertFalse("普通搜索不受影响", allow.apply("network_search", "search"));
        assertFalse(allow.apply(null, "query"));
    }

    /**
     * READ-PAGE(2026-11-02)：大文件必须能续读。真实工具 file_reader 有 read_lines(startLine,endLine)，
     * 但薄工具曾把 action 钉死成 read —— 模型一次看不完就再也读不到后半段（只能猜）。
     * 本测试锁三件事：① 薄 schema 暴露 start_line/end_line；② 带 start_line 时 translate 改走
     * read_lines；③ 不带时仍是 read（普通读取行为不变）。
     */
    @Test
    public void readFileThinToolPagesViaReadLines() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, ?> thin = (Map<String, ?>) readStatic("LOCAL_THIN_TOOLS");
        Object rf = thin.get("read_file");
        assertTrue("read_file 薄工具必须存在", rf != null);

        java.lang.reflect.Field paramsF = rf.getClass().getDeclaredField("params");
        paramsF.setAccessible(true);
        String[][] params = (String[][]) paramsF.get(rf);
        Set<String> names = new HashSet<>();
        for (String[] p : params) names.add(p[0]);
        assertTrue("必须暴露 start_line（分页续读入口）", names.contains("start_line"));
        assertTrue("必须暴露 end_line", names.contains("end_line"));

        java.lang.reflect.Method tr = rf.getClass().getDeclaredMethod("translate", Map.class);
        tr.setAccessible(true);

        java.util.Map<String, Object> pageIn = new java.util.HashMap<>();
        pageIn.put("path", "/sdcard/a.txt");
        pageIn.put("start_line", "201");
        pageIn.put("end_line", "400");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> pageOut = (java.util.Map<String, Object>) tr.invoke(rf, pageIn);
        assertEquals("带 start_line 必须改走 read_lines", "read_lines", pageOut.get("action"));
        assertEquals("参数名要映射成真实工具的 camelCase", "201", pageOut.get("startLine"));
        assertEquals("路径要映射成 file_path", "/sdcard/a.txt", pageOut.get("file_path"));

        java.util.Map<String, Object> plainIn = new java.util.HashMap<>();
        plainIn.put("path", "/sdcard/a.txt");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> plainOut = (java.util.Map<String, Object>) tr.invoke(rf, plainIn);
        assertEquals("不带分页参数时仍是普通 read", "read", plainOut.get("action"));
    }

    /**
     * ALT-ACTION(2026-11-02)：失败建议必须按 (工具, action) 给。
     * 真机反例：读网页失败却提示"用 ai_weather 查天气"——建议本身把模型带偏，
     * 而且不能点名可能没注入的工具（PEG 逐字枚举，模型写不出来）。
     */
    @Test
    public void alternativeHintIsActionAwareForNetworkSearch() throws Exception {
        java.lang.reflect.Method m = AgentLoopEngine.class.getDeclaredMethod(
                "getAlternativeTool", String.class, String.class);
        m.setAccessible(true);

        String read = (String) m.invoke(null, "network_search", "read_url");
        assertTrue("读网页失败应指向网页抓取通道", read.contains("webpage_reader"));
        assertTrue("webpage_reader 可能未注入，必须经 tool_registry 代执行", read.contains("tool_registry"));
        assertFalse("读网页失败不该提天气", read.contains("天气"));

        String search = (String) m.invoke(null, "network_search", "search");
        assertTrue("搜索失败应指向关键词/limit", search.contains("limit"));
        assertFalse("搜索失败不该提天气", search.contains("天气"));

        assertNull("未知工具不给建议", m.invoke(null, "no_such_tool", "x"));
    }

    /**
     * GEN-ERROR(2026-11-02)：生成失败的兜底描述必须用**真实原因**，
     * 不能把所有失败都说成"模型没有输出"（真机：上下文被并发卸载 → 报"回答为空"）。
     */
    @Test
    public void generationErrorTranslatedToAccurateReason() throws Exception {
        java.lang.reflect.Method m = AgentLoopEngine.class.getDeclaredMethod(
                "describeGenerationError", String.class, String.class);
        m.setAccessible(true);

        assertEquals("没有原始错误时用调用方给的默认说法",
                "fallback", m.invoke(null, null, "fallback"));
        assertEquals("空白错误同样回落默认说法",
                "fallback", m.invoke(null, "  ", "fallback"));

        String ctx = (String) m.invoke(null, "Helper context not initialized", "fallback");
        assertTrue("应指出上下文未就绪", ctx.contains("未就绪"));

        String timeout = (String) m.invoke(null, "chatJson timeout after 120000ms", "fallback");
        assertTrue("应指出超时", timeout.contains("超时"));

        String unknown = (String) m.invoke(null, "boom-xyz", "fallback");
        assertTrue("未知错误保留原文供排查", unknown.contains("boom-xyz"));
    }

    /**
     * PROMPT-DOOR(2026-11-08)：预算与 native 门同源的两条纯算术。
     *
     * <p>真机 11-07 的故障链是"窗口 4096 → 门容量 3584 → 预算却回退写死 4000"，
     * 于是请求必然被门拒收。这两条函数就是把"预算恒不超过门"变成可执行断言：
     * 任何容量下（包括小于输出预留的极小窗口）预算都不得 ≥ 门容量。</p>
     */
    @Test
    public void promptBudgetIsAlwaysInsideTheDoor() {
        // 正常窗口：预算 = 门容量 − 输出预留
        assertEquals(6000, AgentLoopEngine.promptBudgetFor(10000, 4000));

        // 小窗口：输出预留装不下时按容量 1/3 缩，至少 256，仍严格小于门
        int tiny = AgentLoopEngine.promptBudgetFor(1000, 4000);
        assertEquals(667, tiny);
        assertTrue("缩减后的预算必须仍小于门容量", tiny < 1000);

        int tinyCtx = AgentLoopEngine.promptBudgetFor(512, 4000);
        assertEquals(256, tinyCtx);

        // 容量未知：沿用历史兜底值（调用方 computePromptBudget 会先判 <=0 再回退）
        assertEquals(4000, AgentLoopEngine.promptBudgetFor(0, 4000));
        assertEquals(4000, AgentLoopEngine.promptBudgetFor(-1, 4000));

        // 不变量：只要容量 > 0，预算就严格小于门容量（否则请求必被 native 拒）
        for (int capacity : new int[]{512, 1024, 3584, 4096, 8192, 12288, 32256, 32768}) {
            int budget = AgentLoopEngine.promptBudgetFor(capacity, 4000);
            assertTrue("capacity=" + capacity + " 时预算 " + budget + " 不应 >= 门容量",
                    budget > 0 && budget < capacity);
        }
    }

    @Test
    public void promptFitsDoorUsesTheSameStrictBoundaryAsNative() {
        // 门是 promptTokens >= capacity 即拒；两者相等必须判"装不下"
        assertTrue(AgentLoopEngine.promptFitsDoor(3583, 3584));
        assertFalse("等于门容量必须拒绝（与 LlamaHelper 的 >= 同口径）",
                AgentLoopEngine.promptFitsDoor(3584, 3584));
        assertFalse(AgentLoopEngine.promptFitsDoor(4000, 3584));

        // 容量未知（<=0）时放行，交给门自己兜底，不拿猜测值拦请求
        assertTrue(AgentLoopEngine.promptFitsDoor(999999, 0));
        assertTrue(AgentLoopEngine.promptFitsDoor(999999, -1));
    }

    private static Object readInstance(Object target, String field) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }
}

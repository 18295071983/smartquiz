package com.oilquiz.app.ai.agent.software.engine;

import android.content.Context;
import com.oilquiz.app.ai.engine.NpuEngineRouter;

import com.oilquiz.app.ai.agent.software.model.AgentResponse;
import com.oilquiz.app.ai.agent.software.model.AgentStats;
import com.oilquiz.app.ai.chat.parser.ThinkingTagConfig;
import com.oilquiz.app.ai.chat.parser.ToolCallTagConfig;
import com.oilquiz.app.ai.config.UserModelParamsManager;
import com.oilquiz.app.ai.inference.InferenceGateway;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.refactor.AIConfig;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.service.OnlineInferenceService;
import com.oilquiz.app.ai.tool.AITool;
import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.ai.tool.openai.ParamDefinition;
import com.oilquiz.app.ai.tool.openai.ToolDefinition;
import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AgentLoopEngine - 原生 Function Calling Agent 单循环引擎
 *
 * 架构：
 * 1. 使用 llama.cpp common_chat_templates_apply 传入 tools JSON，由模型按原生格式（Qwen Hermes / Llama3 / Mistral 等）生成 tool_call
 * 2. 每轮一次 generate → 解析工具调用 → 执行工具 → 追加结果 → 继续
 * 3. 最终回复用 generateStream() 流式输出，UI 通过 onToken 接收
 * 4. 不支持原生 FC 的模型自动降级为 prompt 模式
 *
 * 路径 A（Qwen 原生格式对齐）：
 * - FC 循环由 AIConfig.local_fc_enabled 开关控制；开启后"意图未命中"的请求进入本循环，
 *   由模型自主决定是否调用工具（Qwen 官方 demo 行为一致）
 * - <tool_call> 解析兼容多形态：单条 / {"tool_calls":[...]} 数组（一轮并行多个工具）/
 *   arguments 为 JSON 字符串 / parameters 别名 / function.name 等（Qwen-Agent fncall 约定）
 * - FC 模式 system 提示词带工具使用规则（buildFcSystemPrompt，nous_fncall_prompt 风格）
 * - 思考链：C++ onReasoning / onJson reasoning 事件优先，回退 <think>/<thought> 标签提取
 *
 * 线程模型：所有回调在调用线程执行（AgentSoftwareLayer 的单线程 executor）
 */
public class AgentLoopEngine {

    private static final String TAG = "AgentLoopEngine";
    /** Agent 总轮次上限（与上下文容量联动：32K 上下文预算≈26K，放宽到 16 轮，
     *  复杂多步任务（搜索→读→分析→生成）不因轮次中途卡死；工具轮仍由
     *  MAX_TOOL_ROUNDS=6 单独保护，防止小模型空转；旧历史由 trim+历史要点保留） */
    private static final int MAX_ITERATIONS_BASE = 16;
    /** 循环保护：实际工具调用轮次上限（两跳检索+多工具链≈6轮够用，
     *  提到 8 给"搜索→读→分析→生成"类多步任务余量；去重/重复检测/时间预算仍兜底） */
    private static final int MAX_TOOL_ROUNDS = 8;
    /**
     * REPEAT-WHITELIST(2026-11-02)：异步轮询类调用同一个 key 的单轮次数上限。
     * 这类调用的"同参重复"是正常语义（导入进度/文生图 task_id/盯梢状态/Termux 自检），
     * 不能参与去重；但仍要有上限，防止模型原地刷进度把轮次和时间预算耗光。
     */
    private static final int MAX_REPEATABLE_CALLS = 30;
    /** 循环保护：整个 Agent 执行的总时长上限（含工具执行与推理）。
     *  180s→300s：Qwen3VL-4B-Thinking 思考链 + 工具调用 + 最终整合在内存紧张
     *  的真机上实测需 4-6 分钟，180s 会在思考中途被强制结束（虽走 unified exit
     *  优雅降级，但回答完整性打折）。用户已确认"速度可以慢、功能正常优先"，
     *  故放宽到 5 分钟；思考链冗长问题由规则 1 强约束抑制。 */
    private static final long TOTAL_TIME_BUDGET_MS = 300000;
    /** 单次推理的 prompt token 预算系数（占上下文容量的比例，下限 0.15）
     *  ⚠ 2026-10-07 已废弃：固定比例只约束 prompt，把生成空间压到 < maxTokens，
     *  长回答必然触发 native ctx_full 硬截断。预算改由 computePromptBudget 的
     *  「n_ctx − OUTPUT_RESERVE_TOKENS − NATIVE_GENERATION_RESERVE」公式计算 */
    /** 工具结果最大字符数（超长时截断，节省上下文；放宽到 6000：天气/搜索等
     *  结果不再因 1200 截断丢失关键数值——上一轮天气问题的根因。trim 阶段会把
     *  更早的工具结果压缩到 2500，最近的保持完整，平衡信息完整性与上下文占用） */
    private static final int MAX_TOOL_RESULT_LENGTH = 6000;
    /**
     * TOOL-RESULT-SHAPE(2026-11-02)：工具结果的**结构化**裁剪上限。
     * 旧实现只按总字符数截，搜索/天气这类多字段结果会被拦腰砍断（关键字段只剩半截），
     * 模型只能猜。现在：数组保留前 K 条并标注 omitted，单字段限长，最后才按总长兜底。
     */
    /** 给输出预留的空间（不是上限！）。NO_OUTPUT_CAP(2026-11-01) 之后，这个数只剩两个用途：
     *  ① 算 prompt 预算时从 n_ctx 里扣掉的那份"留给回答的位置"（computePromptBudget /
     *     trimHistoryToFit），防止 prompt 把窗口占满、模型没地方答；
     *  ② n_ctx 取不到时（老 so / 模型未加载）的回落上限。
     *  实际生成上限现在是"剩余上下文"本身，见 buildRequestJson 与 resolveGenerationCap。
     *  原名 FINAL_RESPONSE_MAX_TOKENS，改名是为了不把"预留"和"上限"两个语义混在一起 */
    private static final int OUTPUT_RESERVE_TOKENS = 4000;
    /** native 生成预留（native-lib.cpp GENERATION_RESERVE=512）：prompt 上限 n_ctx−512 的最后防线 */
    private static final int NATIVE_GENERATION_RESERVE = 512;
    /** 模板渲染 overhead 预留：Java token 计数基于简化文本（serializeHistory），
     *  实际模板含 <|im_start|> 等特殊 token 与工具格式包装，需预留余量防低估 */
    private static final int TEMPLATE_OVERHEAD_RESERVE = 512;
    /** 工具执行超时（毫秒） */
    private static final long TOOL_TIMEOUT_MS = 15000;
    /** 工具失败重试次数 */
    private static final int MAX_RETRIES = 1;
    /** 同步调用超时（毫秒）：必须大于 C++ 生成超时(400s)，否则 Java 先放弃导致回答被掐断 */
    private static final long SYNC_TIMEOUT_MS = 420000;
    /** 用户问题长度上限（字符） */
    private static final int MAX_USER_MESSAGE_CHARS = 2000;
    /** UI 交互等待时长（毫秒）：弹窗问用户，超时未操作则回退文本追问 */
    /**
     * 决策轮温度（模型产出 tool_call 的迭代轮）：结构化输出比"敢不敢试"更重要，
     * 保持 Qwen3 官方默认 0.7。默认值只是**兜底**——实际取值走 resolveDecisionTemperature()，
     * 优先用户参数（USER-PARAMS 2026-10-11）；过高会让 chatJson 的 tool_call JSON 出错，
     * 过低（实测 0.6）会让小模型过度保守、只敢填默认 action/参数。
     */
    private static final float DEFAULT_DECISION_TEMPERATURE =
            UserModelParamsManager.DEFAULT_DECISION_TEMPERATURE;
    /**
     * 回答轮温度（最终总结 / 普通对话）：纯自然语言，无结构约束，可高于决策轮换取表达自然度。
     * 默认与决策轮一致（0.7）——**拆分本身不改变既有行为**，要放宽由用户参数显式设置。
     */
    private static final float DEFAULT_ANSWER_TEMPERATURE =
            UserModelParamsManager.DEFAULT_ANSWER_TEMPERATURE;

    /** 工具 → 中文标签（程序化总结/toast 用）。
     *  只保留**当前核心集里可达**的工具：不可达的名字留着会让后来者误判"本引擎支持这些工具"。
     *  将来若恢复某工具的能力，在此补一条即可（toolLabel 缺省回退原名）。 */
    private static final java.util.Map<String, String> TOOL_LABELS = new java.util.HashMap<>();
    static {
        TOOL_LABELS.put("time_date", "时间日期");
        TOOL_LABELS.put("location", "位置");
        TOOL_LABELS.put("ai_weather", "天气");
        TOOL_LABELS.put("network_search", "搜索");
        TOOL_LABELS.put("read_file", "读文件");
        TOOL_LABELS.put("write_file", "写文件");
        TOOL_LABELS.put("calculate", "计算");
        TOOL_LABELS.put("search_questions", "搜题");
        TOOL_LABELS.put("database", "题库");
        TOOL_LABELS.put("workspace", "工作区");
        TOOL_LABELS.put("tool_registry", "工具发现");
        TOOL_LABELS.put("memory", "记忆");
        TOOL_LABELS.put("task", "任务");
    }

    /** 程序化执行结果：text 为程序直接输出的最终回复（不调模型） */
    private static class ProgResult {
        final boolean success;
        final String text;
        ProgResult(boolean success, String text) {
            this.success = success;
            this.text = text;
        }
    }

    /**
     * 常驻核心工具（会话开始<b>静态</b>注入，中途改集会毁 tools 前缀）。
     *
     * <p>当前名单以本数组为唯一权威：时间/定位/天气/联网搜索 + 薄工具
     * read_file/write_file/calculate/search_questions/database + 发现与记忆
     * tool_registry/memory/task + 工作区 workspace。UI 与提示词要报数量或清单，
     * 一律走 {@link #localCoreToolNames()}，不要再抄一份字面量。
     * <b>注入集有多大，prompt 前缀就有多大</b>：小窗口（内存降档到 4096）下这份 schema
     * 本身就可能装不下，见 {@link #describePromptOverflow}。</p>
     *
     * <p><b>为什么"注入集就是能力上限"（重要，别照搬在线路径的经验）</b>：
     * native 的 PEG 语法按本次请求的 tools 数组**逐字枚举**工具名 ——
     * {@code atomic(literal(name))} + 该工具自己的参数 schema
     * （llama.cpp/common/chat-peg-parser.cpp standard_json_tools）。
     * 所以未注入 schema 的工具名，模型**根本生成不出来**：解析失败 → 整段 tool_call 退化成正文。
     * 这与在线路径（OpenAI tools，模型可自由点名）有本质区别 ——
     * 在线的"模型自主发现工具"策略在本地路径上不可行，会形成死锁：
     * 要调用某工具必须先有它的 schema，而拿 schema 的唯一途径是先调用它。</p>
     *
     * <p>因此 <b>不要</b>把 {@code resolveToolNameFuzzy} 当作补救手段：
     * 它只能归一化**已经在 tools 数组里的名字**（如别名 ui_control → ui_component），
     * 无法让未注入的工具变得可调用。注入集之外的能力只有两条真通路：
     * ① 加进本数组（静态、会话开始生效）；② 经 {@code tool_registry} 的 get/call 发现并代执行
     * （2026-11-01 起常驻，代执行不需要该工具的 schema 进 tools 数组）。
     * 单靠改 prompt 或让模型猜名字都无效。</p>
     *
     * <p><b>薄工具适配层（2026-10-25，见 {@link #LOCAL_THIN_TOOLS}）</b>：
     * read_file/write_file/calculate/search_questions/database 都是**单用途薄工具**
     * （schema 里无 action enum、参数只留必要一两个、描述一句话），执行时由
     * {@code executeToolSafely} 翻译成真实工具（如 read_file→file_reader(action=read)、
     * calculate→calculator、search_questions→database(action=search_questions)）。
     * 之所以"薄"而不是直接注入 file_reader：大 schema 吃前缀、action enum 是 4B 选错的高发点；
     * 薄工具既省 token 又少可选值。</p>
     *
     * <p>历史沿革（保留以便回退判断，<b>以下都不是当前状态</b>）：
     * 2026-10-05 补 ai_weather/smart_research/memory —— 原先只有
     * time_date/location/tool_registry/file_reader/workspace，而 sessionTools 只在会话首条消息
     * 播种一次 → 模型整轮看不到天气工具（"我无法获取天气信息"的根因）；
     * 2026-10-09 补 network_search —— 实测"查油价"这类非天气实时查询被错派给 ai_weather，
     * 因为核心集里唯一带"实时查询"语义的就是 ai_weather；
     * 2026-10-11 曾收窄到 4 个（time_date/location/ai_weather/network_search），同时移出
     * tool_registry/smart_research/memory/file_reader/workspace（当时写的"只做这四个"已失效）；
     * 2026-10-25 起以薄工具逐个恢复读文件/写文件/计算/搜题；
     * 2026-11-01 恢复 tool_registry（发现+代执行）、memory、task、workspace；
     * 2026-11-07 补只读 database，并把 location 的选用规则写进提示词（问"我在哪"不再拿天气代查）。</p>
     */
    private static final String[] DEFAULT_CORE_TOOLS = {
            "time_date", "location", "ai_weather", "network_search",
            "read_file", "write_file", "calculate", "search_questions",
            // DATABASE-THIN(2026-11-07)：题库查询曾经只能走 search_questions（按题干搜），
            // 用户问“题库里有多少题/把题库导出”时模型没有可调用的取数入口，只能去翻应用目录
            // （真机 2026-11-07 两个对话就是这样空转的）。database 以薄 schema 常驻核心集，
            // 只暴露 count/questions/只读 SQL 三个动作；完整 database 定义仍留在 tool_registry。
            "database",
            // TOOL-DISCOVERY(2026-11-01)：本地工具名由 native PEG 逐字枚举，会话中途改集会毁
            // tools 前缀（全量 re-prefill ~20s）。所以"能力广度"不能靠堆工具，而是：
            // 核心集只留高频，其余一律经 tool_registry 发现，并由它的 call 动作代执行。
            "tool_registry", "memory", "task",
            // WORKSPACE-TOOL(2026-11-01)：工作区是一等公民——生成/改过的文件都在这儿，
            // 列目录、看历史版本、回退都只能靠它（read_file 猜不到工作区相对路径）。
            "workspace"
    };

    /**
     * 本地常驻核心工具名（只读副本，顺序与注入顺序一致）。
     *
     * <p><b>WHY（2026-11-02，GUIDE-DYNAMIC-TOOLS）</b>：聊天页的「使用说明」弹框此前把本地
     * 能力写死成"5 个核心工具（天气/时间/定位/搜索/记忆）"，而本数组早已不是那个数、当时还
     * <b>没有 location</b> —— 用户在弹框里读到"定位"，实际模型永远点不到它（location 已于
     * 2026-11-07 补进核心集）。这与"提示词核心清单硬编码≠CORE_TOOLS"是同一个坑，只是残留在
     * UI 侧。UI 要报数量/名单一律从这里取，不要再抄一份字面量。</p>
     */
    public static java.util.List<String> localCoreToolNames() {
        return java.util.Collections.unmodifiableList(
                java.util.Arrays.asList(DEFAULT_CORE_TOOLS));
    }

    // ==================== 本地专用「薄工具」适配层（2026-10-25）====================
    // 动机：在线工具（file_reader/file_generator…）是**多 action + 大 description**的完整 schema，
    // 对 4B + ~7.7K prompt 预算太贵；且 action enum 是 4B 选错参数的高发点（见 buildToolsJson 注释）。
    // 薄工具 = 单用途：schema 里**没有 action 参数**（action 在适配层钉死）、参数只留必要的一两个、描述一句话
    // → 前缀更短、可选值更少（对 4B 反而更稳），底层仍复用 executeTool(真实工具, {action:钉死值,...})。
    // 关键约束：这些名字必须**会话开始就静态注入**（DEFAULT_CORE_TOOLS 首轮 seed），
    // 中途加会破坏 Qwen3 tools 前缀的字节稳定 → 全量 re-prefill（~20s 首字）。故只走静态扩集。
    private static final java.util.Map<String, ThinTool> LOCAL_THIN_TOOLS = new java.util.LinkedHashMap<>();
    static {
        LOCAL_THIN_TOOLS.put("read_file", new ThinTool(
                // READ-PAGE(2026-11-02)：文件一次读不完就分页续读——真实工具本来就有
                // read_lines(startLine,endLine)，但薄工具把 action 钉死成 read，模型永远读不到后半段。
                // 这里加 start_line/end_line：一旦给出就改走 read_lines（建议每段 ≤200 行）。
                "读取本地/工作区文本文件（编码自动检测 UTF-8/GB18030/UTF-16）。path 支持绝对路径或工作区相对文件名；"
                        + "内容长时用 start_line/end_line 分页读（从 1 开始，建议每段≤200 行），读完一段再续读下一段，不要凭猜测补全。",
                new String[][]{
                        {"path", "string", "文件路径（绝对路径或工作区相对名）", "1"},
                        {"start_line", "string", "可选：起始行号（从 1 开始；给了就走按行分页读）", "0"},
                        {"end_line", "string", "可选：结束行号（配合 start_line；不给则读到末尾）", "0"},
                },
                "file_reader", "read",
                new String[][]{
                        {"path", "file_path"},
                        {"start_line", "startLine"},
                        {"end_line", "endLine"},
                },
                "read_lines", "start_line"));
        LOCAL_THIN_TOOLS.put("write_file", new ThinTool(
                "把文本内容写入工作区文件（默认保存到 files/ 长期区，返回完整绝对路径）。文件名一律用英文/数字/下划线，不要用中文文件名。",
                new String[][]{
                        {"file_name", "string", "文件名/路径（英文，勿中文）", "1"},
                        {"content", "string", "要写入的文本内容", "1"},
                },
                "file_generator", "create",
                new String[][]{}));
        LOCAL_THIN_TOOLS.put("calculate", new ThinTool(
                "计算算术表达式并返回结果，支持 + - * / % ^ 括号、小数与负数（如 2^10、3.5*(2+4)/7）。凡是要精确算数就用它，别自己心算。",
                new String[][]{{"expression", "string", "算术表达式，如 2^10 或 (15-3)%4", "1"}},
                "calculator", null,
                new String[][]{}));
        LOCAL_THIN_TOOLS.put("search_questions", new ThinTool(
                "从本地题库按题干关键词搜索题目，返回匹配的题目（含选项/答案/解析等）。keyword 为题干里的关键词或知识点；"
                        + "不要用“题库/导出/统计”这类词搜它（题干里没有这种词）。要题数、全部题目或按字段筛选，用 database。",
                new String[][]{{"keyword", "string", "题干关键词/知识点", "1"}},
                "database", "search_questions",
                new String[][]{}));
        // DATABASE-THIN(2026-11-07)：本地核心集原先没有数据库取数入口，用户说“你不是有数据库题目吗”
        // 时模型只能去翻应用目录。这里只暴露三件只读操作（题数/分页题目/一条 SELECT），
        // 既让“题库有多少题、题目内容是什么、导出前取数”可落地，又不把 20 个 action 全塞给 4B。
        // action 用真实值透传（ThinTool.action=null），执行前有只读白名单/只读 SQL 校验。
        LOCAL_THIN_TOOLS.put("database", new ThinTool(
                "本地题库数据库（SQLite，只读）。action 只能是 get_question_count（总题数）、"
                        + "get_questions（分页题目，page 从 1 开始、page_size 最多 50）、"
                        + "execute_sql（执行一条 SELECT）。question 表字段：questionText/optionA~D/"
                        + "correctAnswer/questionType/explanation 等。用户问题库统计、题目内容、导出前取数时用。",
                new String[][]{
                        {"action", "string", "get_question_count / get_questions / execute_sql（只读）", "1"},
                        {"page", "integer", "get_questions 用：页码，从 1 开始", "0"},
                        {"page_size", "integer", "get_questions 用：每页条数（默认 20，最多 50）", "0"},
                        {"sql", "string", "execute_sql 用：一条 SELECT 语句", "0"},
                },
                "database", null,
                new String[][]{}));
        LOCAL_THIN_TOOLS.put("tool_registry", new ThinTool(
                // TOOL-CHOICE(2026-11-02)：真机出现过 action=read_url（编的）——把它能接受的动作
                // 写死在这句里，并明说"只能是这四个"，减少模型自创参数值。
                "工具发现与代执行（工具清单只在这里）：action 只能是 list/search/get/call 四个之一。"
                        + "list 列出全部工具，search 按关键词找，get 取某工具参数说明，call 执行某工具（args 传 JSON 对象）。"
                        + "想知道有哪些工具或怎么填参数就用 get，不要去联网搜。",
                new String[][]{
                        {"action", "string", "只能是 list / search / get / call（其他值无效）", "1"},
                        {"tool", "string", "get/call 的工具名", "0"},
                        {"keyword", "string", "list/search 的关键词", "0"},
                        {"args", "string", "call 的参数 JSON，如 {\"path\":\"/sdcard/a.txt\"}", "0"},
                },
                "tool_registry", null,
                new String[][]{}));
        LOCAL_THIN_TOOLS.put("memory", new ThinTool(
                "长期记忆（跨会话）：save 保存用户的稳定信息（key/value/category=fact|preference|context），recall 读取，list 列出，delete 删除。用户说\u201c记住…\u201d或告知姓名/住址/偏好时 save。",
                new String[][]{
                        {"action", "string", "save/recall/list/delete", "1"},
                        {"key", "string", "记忆键，如 user_name", "0"},
                        {"value", "string", "记忆值（save 用）", "0"},
                        {"category", "string", "fact/preference/context，默认 fact", "0"},
                },
                "memory", null,
                new String[][]{}));
        LOCAL_THIN_TOOLS.put("task", new ThinTool(
                "多步任务清单：add 新建（description），update 更新状态/进度（id/progress），complete 完成，fail 失败，list 查看。只用于需要多轮完成的任务，一次性问答不要建。",
                new String[][]{
                        {"action", "string", "add/update/complete/fail/list", "1"},
                        {"id", "string", "任务ID（update/complete/fail 用）", "0"},
                        {"description", "string", "任务描述（add 用）", "0"},
                        {"progress", "string", "进度 0-100（update 用）", "0"},
                },
                "task", null,
                new String[][]{}));
        LOCAL_THIN_TOOLS.put("workspace", new ThinTool(
                "工作区：生成或改过的文件都在这里。list/path/read/versions 只读（读文件内容用 action=read，"
                        + "没有 read_file 这个动作）；restore 与 delete 会改/删文件，"
                        + "必须同时传 confirm=\"true\"（字符串 true 或 1 均可识别），否则工具拒绝执行并提示；"
                        + "restore 前先 versions 拿到版本号。",
                new String[][]{
                        {"action", "string", "list/path/read/versions 只读；restore（需 confirm=true）、delete（需 confirm=true）", "1"},
                        {"fileName", "string", "工作区内相对文件名（read/versions/restore/delete 用）", "0"},
                        {"version", "string", "restore 用：版本号（1=最近一版，默认 1），从 versions 结果取", "0"},
                        {"confirm", "string", "restore/delete 必填 \"true\"；不传或非真值 = 拒绝执行", "0"},
                },
                "workspace", null,
                new String[][]{}));
    }

    /** 一个本地薄工具：极简 schema（无 action enum）+ 到真实工具的参数映射。 */
    private static final class ThinTool {
        final String desc;
        final String[][] params;      // {name, type, description, required("1"/"0")}
        final String realTool;        // 底层真实工具名
        final String action;          // 钉死的 action（null 表示不注入）
        final String[][] rename;      // {localParam -> realParam}；未列出的参数原样透传
        /** READ-PAGE(2026-11-02)：出现 pageTriggerParam 时改用的 action（如 read_file+start_line→read_lines）。 */
        final String pageAction;
        final String pageTriggerParam;

        ThinTool(String desc, String[][] params, String realTool, String action, String[][] rename) {
            this(desc, params, realTool, action, rename, null, null);
        }

        ThinTool(String desc, String[][] params, String realTool, String action, String[][] rename,
                 String pageAction, String pageTriggerParam) {
            this.desc = desc;
            this.params = params;
            this.realTool = realTool;
            this.action = action;
            this.rename = rename;
            this.pageAction = pageAction;
            this.pageTriggerParam = pageTriggerParam;
        }

        /** 把模型给的薄参数翻译成真实工具的参数（重命名 + 钉死 action）。 */
        Map<String, Object> translate(Map<String, Object> in) {
            Map<String, Object> out = new java.util.HashMap<>();
            java.util.Set<String> renamed = new java.util.HashSet<>();
            for (String[] m : rename) {
                renamed.add(m[0]);
                if (in.containsKey(m[0])) out.put(m[1], in.get(m[0]));
            }
            for (Map.Entry<String, Object> e : in.entrySet()) {
                if (!renamed.contains(e.getKey())) out.put(e.getKey(), e.getValue());
            }
            if (action != null) out.put("action", action);
            // 分页参数出现 → 改走按行读，让"一次看不完"能续读（read_file → read_lines）
            if (pageAction != null && pageTriggerParam != null && in.containsKey(pageTriggerParam)) {
                out.put("action", pageAction);
            }
            return out;
        }
    }

    // ==================== 本地 FC 路径「模型可见面」裁剪（2026-11-01 SCHEMA-SLIM）====================
    // 动机：AIToolManager 的 ToolDefinition 是**三条路径共用**的完整定义（在线 OpenAI tools、
    // 本地 FC、工具中心展示），里面写了只有引擎层才需要的东西——lat/lon 及其别名 latitude/longitude、
    // "city 与 lat/lon 二选一，同传报冲突"、各 action 返回哪些字段。本地路径的 native PEG 语法
    // 按注入的 schema **逐字枚举**参数名（见上方 PEG 注释），模型只能生成这里出现过的名字，
    // 所以"反正由引擎自己填"的参数留在可见面上只有两个效果：吃前缀 token、给 2B 多一个选错的入口。
    // 本表把这类参数从**本地 schema** 里摘掉，位置参数执行前由 fillWeatherLocation 补齐（那里本来就补）。
    //
    // 只裁模型可见面，不动 AIToolManager 的定义：在线路径与工具中心仍看完整 schema，
    // 真实工具照常接受被摘掉的参数（fillWeatherLocation 填的就是 lat/lon）。
    //
    // 为什么 **city 留在可见面上**（与"把 city/lat/lon 一起摘掉"的直觉相反）：
    // 引擎侧唯一能认出城市的是 extractCityFromMessage，而它只做 QWeather CSV 的 nameZh 子串匹配
    // （QWeatherCityManager.findCityNamesInMessage），CSV 是**县市级**表；用户说"花山镇天气"这类
    // 小于县市的地名，引擎认不出来 → 按现有兜底顺序会填**手机定位的经纬度** → 返回的是用户所在城市的
    // 天气，且**不报错**。模型传 city="花山镇" 时走的是 AIWeatherManager.resolveCityEntry 的后缀剥离
    // + 上级地名打分 + 系统 Geocoder 三层，比引擎的精确子串匹配强。摘掉 city 换来的那点 token，
    // 代价是一类静默错答，不划算。
    private static final java.util.Map<String, LocalSchemaSlim> LOCAL_SCHEMA_SLIM =
            new java.util.LinkedHashMap<>();
    static {
        LOCAL_SCHEMA_SLIM.put("ai_weather", new LocalSchemaSlim(
                "查询某地的天气。",
                new String[][]{
                        {"action", "查什么：current 实时天气（默认）/forecast 未来几天预报/"
                                + "hourly 逐小时/air_quality 空气质量/alerts 预警/indices 生活指数/all 全部"},
                        {"city", "城市名或和风城市编码，如\u201c北京\u201d或 101010100。"
                                + "用户没说城市时不要传，系统会自动用手机定位"},
                }));
    }

    /** 一个工具的本地模型可见面：顶层描述 + 保留哪些参数（数组顺序即注入顺序，保证前缀字节稳定）。 */
    private static final class LocalSchemaSlim {
        final String desc;
        final String[][] keepParams;   // {paramName, 覆盖后的描述；空串表示沿用原定义里的描述}

        LocalSchemaSlim(String desc, String[][] keepParams) {
            this.desc = desc;
            this.keepParams = keepParams;
        }
    }

    private final AIService aiService;
    private final AIToolManager toolManager;
    private final AIConfig aiConfig;
    private final android.content.Context appContext;
    /** UI 交互辅助：程序直接用 ui_component 组件与用户交互（追问/确认/进度/卡片） */
    private final UiInteractor uiInteractor;
    private LoopCallback callback;
    /** 最近一次 chatJson 生成是否已有正文 token 流式输出到 UI（防重复回复，见 run()） */
    private volatile boolean lastStreamed = false;
    /**
     * 当前模型的思考标签（来自 chat template，由 native meta 事件下发）。
     * 未收到 meta 时为 empty，此时思考提取回退到旧的正则/字面量路径。
     */
    private volatile ThinkingTagConfig thinkingTags = ThinkingTagConfig.empty();

    /**
     * 工具调用标签（模板下发）—— 流式阶段据此判断 token 是正文还是工具调用语法。
     * 空配置表示模板未声明工具语法，此时**不做任何吞除**（按纯正文处理）。
     */
    private volatile ToolCallTagConfig toolCallTags = ToolCallTagConfig.empty();

    /** 最近一次用户消息原文：供 ai_weather 缺城市参数时提取城市名兜底（如"五台县的天气"→city=五台县） */
    private volatile String lastUserMessage = "";

    /**
     * 本轮最近一次生成失败的**原始**原因（chatJson error / 超时 / 异常）。
     *
     * <p>GEN-ERROR(2026-11-02)：generateWithChatJsonSync 失败时只返回 null，统一退出路径
     * 于是把任何失败都说成"模型没有输出"——真机实例："Helper context not initialized"
     * （模型被并发切换卸载）被报成"回答为空或被过滤"，用户据此无法判断该重试还是该换模型。
     * 这里保留原始错误，退出时翻成人话。</p>
     */
    private volatile String lastGenerationError = null;

    /**
     * 用户取消标志（2026-10-25 停止链路修复）。
     *
     * <p><b>为什么必须有 Java 侧标志</b>：此前取消只能靠 native {@code stopGeneration()}
     * 让 {@code chatJson} 返回 null，循环在 {@code genResult == null} 那个分支 break ——
     * 那是**间接**感知，且 break 之后
     * 统一退出路径只要 {@code toolCallCount > 0} 就仍然调用 {@code generateFinalAnswer()}
     * 再解码一轮。表现即"点了停止，手机反而更卖力地跑"。工具等待循环（单工具/并行）同样
     * 不知道取消，最坏要多等一个 {@code TOOL_TIMEOUT_MS}。</p>
     *
     * <p>生命周期：每次 {@code run()} 入口复位（否则上一轮的取消会永久粘住实例——引擎实例
     * 在 {@code AgentChatHandler} 里是常驻的，见该文件 :117），{@link #cancel()} 置位。</p>
     */
    private final java.util.concurrent.atomic.AtomicBoolean cancelRequested =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // ==================== KV 前缀稳定性（2026-09-23）====================
    // 本地 Agent 每轮 prompt = system + 会话序列 + 本轮用户消息。
    // 会话序列保存"上一轮实际发送给 native 的请求消息（去 system）+ 模型最终输出"，
    // 跨轮逐字节回放，使本轮 prompt 成为上一轮 KV 缓存的严格超集 → KV INCREMENTAL，
    // 避免工具轮之后每轮 PARTIAL→FULL 全量 prefill（真机实测 ~4K tokens / ~20s 首字）。
    // 旧实现每轮从 bridge 干净历史重建 prompt，与 KV 里模型原始生成序列字节对不上，
    // matchedLen_==cachedNPast_ 永不成立 → AgentKvCache PARTIAL → 混合记忆 seq_rm
    // 深截断失败（recurrent 只支持尾部窗口回滚）→ 全量 prefill。
    private final java.util.List<ChatMessage> sessionMessages = new java.util.ArrayList<>();
    /** 最近一次实际发送的请求消息列表（含 system 头），供轮末持久化会话序列 */
    private volatile java.util.List<ChatMessage> lastRequestHistory = null;
    /** 会话级工具集：首轮=核心4工具（见 DEFAULT_CORE_TOOLS），动态注入结果跨轮保留。
     *  Qwen3 模板把 tools 渲染在 system 前缀，跨轮集合必须逐字节一致，
     *  否则 system 漂移 → KV 前缀失配 → 全量 prefill。 */
    private final java.util.Set<String> sessionTools = new java.util.LinkedHashSet<>();

    /** P2(2026-10-07)：历史裁剪要点缓存。trimHistoryToFit 超预算时一次性生成，
     *  之后同一次 agent 运行内逐字节复用，保证"已入历史的消息永不改写"的
     *  前缀稳定性质（要点文本若每轮重生成，位置 1 的 token 序列每轮都变）。 */
    private String compactionSummary = null;

    public interface LoopCallback {
        void onIterationStart(int iteration, String promptSummary);
        void onIterationEnd(int iteration, String response);
        /** 可选：该轮模型生成的完整正文快照（清理后的 fullText）。 */
        default void onRoundComplete(int iteration, String fullText) {}
        void onThinkingUpdate(String thought);
        void onToolCall(String toolName, String args);
        void onToolResult(String toolName, boolean success, String result);

        /** 可选：带稳定 toolCallId 的重载；默认退回旧方法，兼容已有实现。 */
        default void onToolCall(String toolCallId, String toolName, String args) {
            onToolCall(toolName, args);
        }

        default void onToolResult(String toolCallId, String toolName,
                                  boolean success, String result) {
            onToolResult(toolName, success, result);
        }
        /**
         * 可选：工具声明化卡片意图（dsh presentCall/presentResult 对齐，2026-09-23）。
         * 引擎在 onToolCall/onToolResult 前调用；card 为 null 表示工具无声明，UI 走通用呈现。
         */
        default void onToolPresent(String toolName, Map<String, Object> card) {}
        void onToken(String token);
        void onComplete(String finalText);
        void onError(String error);
        void onInferenceProgress(int tokenCount, float tokensPerSecond);
        /** 可选：本轮生成被上下文上限截断（native stop_reason=ctx_full），UI 可提示"回答不完整" */
        default void onTruncated(String detail) {}
    }

    public AgentLoopEngine(Context context, AIService aiService) {
        this.aiService = aiService;
        this.appContext = context != null ? context.getApplicationContext() : null;
        this.toolManager = AIToolManager.getInstance(context);
        this.aiConfig = new AIConfig(context);
        this.uiInteractor = new UiInteractor(toolManager);
    }

    public void setCallback(LoopCallback callback) {
        this.callback = callback;
    }

    /**
     * 请求取消本次 Agent 运行。幂等，可从任意线程调用（UI 点"停止"即走这里）。
     *
     * <p>置位后：循环每轮开头 break、工具等待循环立即结束、统一退出路径**不再**生成最终答，
     * 也不注入兜底话术；已流式到 UI 的部分正文由调用方收尾。下一轮 {@code run()} 入口自动复位。</p>
     */
    public void cancel() {
        cancelRequested.set(true);
    }

    /** 当前是否已被请求取消（供调用方日志/测试断言）。 */
    public boolean isCancelled() {
        return cancelRequested.get();
    }

    /** UI 运行态提示（toast）：本地 Agent 的实验性可见性——工具调用/结果/意图执行等
     *  关键事件直接弹给用户，无需翻日志。失败静默（无 Context/异常不崩）。 */
    private void showToast(String msg) {
        try {
            if (appContext != null && msg != null && !msg.isEmpty()) {
                android.widget.Toast.makeText(appContext, msg, android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 工具友好名（TOOL_LABELS 缺省用原名） */
    private String toolLabel(String toolName) {
        String label = TOOL_LABELS.get(toolName);
        return label != null ? label : toolName;
    }

    // ==================== 主循环 ====================

    /** 历史对话条目：供多轮上下文传入（role 仅 user/assistant） */
    public static class HistoryEntry {
        public final String role;
        public final String content;
        public HistoryEntry(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    public AgentResponse run(String userMessage, boolean enableThinking) {
        return run(userMessage, enableThinking, null);
    }

    /**
     * 主循环入口（带多轮上下文）。
     * @param priorHistory 最近若干轮对话历史（role: user/assistant），可为 null；当前消息单独传入
     */
    public AgentResponse run(String userMessage, boolean enableThinking,
                             List<HistoryEntry> priorHistory) {
        long startTime = System.currentTimeMillis();
        int totalTokens = 0;
        int toolCallCount = 0;
        // 新一轮开始：清除上一轮的取消标志。引擎实例是常驻的（AgentChatHandler 只 new 一次），
        // 不复位就会出现"上一轮被用户停止 → 这一轮刚进循环就被判为取消"。
        cancelRequested.set(false);
        // 同理清掉上一轮的生成错误，避免把旧原因带进本轮兜底描述
        lastGenerationError = null;

        // 超长问题先截断，避免单条消息撞穿预算
        if (userMessage != null && userMessage.length() > MAX_USER_MESSAGE_CHARS) {
            AILogger.w(TAG, "User message too long, truncating to " + MAX_USER_MESSAGE_CHARS);
            userMessage = userMessage.substring(0, MAX_USER_MESSAGE_CHARS) + "…";
        }
        // 记录最近用户消息：ai_weather 缺城市参数时程序从原文提取城市名兜底
        this.lastUserMessage = userMessage != null ? userMessage : "";

        // 常用工具全量注入首轮（天气/时间/定位/搜索/记忆/工具注册表 + 高频工具），
        // 避免模型动态注入时 system（tools 前缀）变化触发 FULL
        List<String> selectedTools = new ArrayList<>();
        for (String name : DEFAULT_CORE_TOOLS) {
            if (!selectedTools.contains(name)) {
                selectedTools.add(name);
            }
        }
        // 不注入大 schema 的 UI 工程工具（保留为护栏：核心集若被改回含 ui_component 也不会注入）
        selectedTools.removeIf(n -> "ui_component".equals(n) || "ui_component_plugin".equals(n));
        // 会话级工具集：首轮=常用全量；动态注入结果跨轮保留。
        // tools 渲染在 system 前缀，跨轮集合一致 → system 字节稳定 → KV 前缀可增量。
        if (sessionTools.isEmpty()) {
            sessionTools.addAll(selectedTools);
        }
        // 注入集：初始 = 会话级工具集（= DEFAULT_CORE_TOOLS 全量，名单以该数组为权威）。
        // 集外能力不再靠"猜名字调用成功后注入"，而是走常驻的 tool_registry(get/call) 代执行；
        // 下面 FC 循环里的"动态注入"分支因此基本休眠（见该处注释）。
        final java.util.Set<String> activeTools = new java.util.LinkedHashSet<>(sessionTools);
        String toolsJson = buildToolsJson(new ArrayList<>(activeTools));
        byte[] toolsJsonBytes = toolsJson.getBytes(StandardCharsets.UTF_8);
        AILogger.i(TAG, "Initial tools: " + activeTools.size() + " tools, schema len: " + toolsJson.length());
        // 必须能一眼看出实际生效的核心工具名单
        // ——尤其 time_date 走 Python 注册块，注册失败时它会静默消失（buildToolsJson 对 null 定义
        // 直接 continue），只靠数量无法定位问题。
        AILogger.i(TAG, "Core tools: " + selectedTools + " (active initial: " + activeTools + ")");
        // SCHEMA-DUMP(2026-10-11)：核对"模型实际看到的工具定义"。核心工具走两条不同路径 ——
        // ai_weather / network_search 用 AIToolManager.getToolDefinition 的 switch 定义（有 enum、
        // 参数类型正确）；time_date / location 无 switch case，走 createToolDefinitionFromAITool
        // 注解路径（参数一律 string、required=false，枚举靠 PARAM_ENUM_OVERRIDES 补）。
        // 只在首轮打一次（截断 2500 字符），避免每轮刷屏：
        //   adb logcat -s AgentLoopEngine | grep "tools schema dump"
        AILogger.i(TAG, "tools schema dump (" + activeTools.size() + " tools, " + toolsJson.length()
                + " chars): " + truncate(toolsJson, 2500));
        logEffectiveTemperature(); // USER-PARAMS：进入循环前打一次生效温度与来源，便于核对用户参数
        if (aiConfig != null && aiConfig.isFcEnabled()) {
            // 就绪弹窗：只列核心能力名（全量 20+ 工具太长，能力明细模型可从 schema 读取）
            StringBuilder readySb = new StringBuilder("🤖 本地Agent就绪：");
            boolean first = true;
            for (String t : activeTools) {
                if (!first) readySb.append("、");
                readySb.append(toolLabel(t));
                first = false;
                if (readySb.length() > 60) break; // 截断，避免 toast 超长
            }
            showToast(readySb.toString());
        }

        // 单次推理的 prompt token 预算（结合配置上下文容量）
        final int promptBudget = computePromptBudget();
        AILogger.i(TAG, "Prompt budget: " + promptBudget + " tokens");
        // P2：每次 agent 运行重置历史要点缓存（跨用户消息不复用旧要点）
        compactionSummary = null;

        // FC 模式（local_fc_enabled）：system 提示词带 Qwen 原生工具调用规则（<tool_call> 格式）
        boolean modelFcMode = aiConfig != null && aiConfig.isFcEnabled();

        List<ChatMessage> history = new ArrayList<>();
        // PERSONA(2026-11-01)：用户设置的人设（AIConfig.system_prompt）。在线路径一直用它，
        // 本地两条路径此前都没读——设了人设一开"智能助手"就被静默丢弃。
        String persona = resolvePersona();
        if (persona != null) {
            AILogger.i(TAG, "persona injected into system prompt: " + persona.length() + " chars");
        }
        String sysPrompt = modelFcMode ? buildFcSystemPrompt(persona) : buildSystemPrompt(persona);
        history.add(new ChatMessage("system", sysPrompt));
        // SCHEMA-DUMP(2026-10-11)：把**实际下发**的 system prompt 打出来（tools schema 在循环内
        // 每轮重建，另有 "Round tools schema" 日志给长度/hash，下面那行给首轮内容）。
        // 动机：4 个核心工具的 schema 来自两条不同路径 —— ai_weather/network_search 走
        // AIToolManager.getToolDefinition 的 switch 定义，time_date/location 走
        // createToolDefinitionFromAITool 注解路径（参数无类型/无 required，枚举靠
        // PARAM_ENUM_OVERRIDES 补）。仅读代码难以确认模型最终看到什么，adb 一次即可核对：
        //   adb logcat -s AgentLoopEngine | grep -E "system prompt dump|schema dump"
        AILogger.i(TAG, "system prompt dump (" + sysPrompt.length() + " chars): " + sysPrompt);
        // 多轮上下文：把最近几轮对话注入历史（system → 历史 → 当前问题），
        // 让模型能理解"那明天呢？"之类的指代；超预算由 trimHistoryToFit 裁剪
        // ---- KV 前缀稳定性：会话级原始消息序列（2026-09-23）----
        // 旧实现每轮从 bridge 干净历史重建 prompt（干净 user/assistant 文本 vs KV 缓存里
        // 模型原始生成序列，含 <tool_call>/tool_calls/思考标签），字节对不上，
        // matchedLen_==cachedNPast_ 永不成立 → PARTIAL → 混合记忆 seq_rm 深截断失败
        // （recurrent 只支持尾部窗口回滚）→ 每轮全量 prefill（真机实测 ~4K tok / ~20s 首字）。
        // 新实现：会话序列保存"上一轮实际发送的请求 + 模型输出"，逐字节回放 →
        // 本轮 prompt = system + 会话序列 + 当前问题 = 上一轮 KV 严格超集 → KV INCREMENTAL。
        resetSessionIfHistoryChanged(priorHistory);
        if (sessionMessages.isEmpty() && priorHistory != null) {
            for (HistoryEntry h : priorHistory) {
                if (h == null || h.content == null || h.content.trim().isEmpty()) continue;
                sessionMessages.add(new ChatMessage(h.role, h.content));
            }
        }
        for (ChatMessage m : sessionMessages) {
            // TOOL-HISTORY-CONTAMINATION(2026-11-08)：普通对话模式系统提示明确“不调用任何工具”，
            // 但 sessionMessages 会回放上一轮 FC 模式的 tool_call/tool 消息。真机 T133 因此
            // 看到上一轮 calculate 结果，思考里声称“我已经用 calculate 得到结果 4”，实际本轮
            // toolCalls=0。工具不可用的轮次必须把工具消息从 prompt 历史里剔除，避免把
            // “历史工具结果”误导成“本轮工具结果”。
            if (!modelFcMode && ("tool".equals(m.role) || m.toolCalls != null)) {
                continue;
            }
            history.add(m);
        }
        // 本轮动态任务指令（缓存命中优化）：天气直给指令从 system 移到本轮 user 消息末尾。
        // system 前缀保持静态（DATE-TAIL 后连日期都不在头部，跨天也不变，见
        // buildCurrentTimeLine 注释），本地推理引擎的 KV 缓存才能命中 system+历史前缀；
        // 否则每条带"天气"的消息都改写 system → 前缀抖动 → 每轮全量重算前缀。
        // 动态指令放 prompt 末尾（D 块），模型仍能读到并执行，行为不变。
        String effectiveUserMessage = userMessage;
        // 日期块（DATE-TAIL 2026-11-01）：本轮 user 末尾的第一段。放这里而不是 system 头部，
        // 是为了让 system 与会话序列的字节都不随跨天变化 → KV 前缀跨天继续命中。
        // 它必须是第一个追加项：appendTailBlock 的返回值一定非 null，后面的【已存记忆】/
        // 【当前任务】因此不会在 userMessage==null 时把字面量 "null" 拼进 prompt。
        effectiveUserMessage = appendTailBlock(effectiveUserMessage, buildCurrentTimeLine(modelFcMode));
        // CHIP-ASK(2026-11-02)：用户点了工具抽屉里的「🔍 搜索」（一键发问）→ 本轮强制先联网。
        // 与在线侧共用同一个一次性信号（LiveSearchHint）：谁先跑谁消费，不会两边都生效。
        // MODE-GATE(2026-11-02)：d730ca1b 之后本地「普通模式」和「agent 模式」共用本引擎，差别只在
        // 是否注入工具（modelFcMode）。普通模式没有 network_search，却照样追加"请先用它查"，
        // 等于让模型去调一个不存在的工具 —— 所以必须绑在 modelFcMode 上；不消费时信号会自己过期。
        if (modelFcMode && com.oilquiz.app.ai.chat.LiveSearchHint.consume()) {
            effectiveUserMessage = appendTailBlock(effectiveUserMessage,
                    "【本轮联网】用户点了「搜索」快捷入口：请先用 network_search 查一次再回答"
                    + "（默认 action=ask；需要一批来源/链接才用 search，别为多看几条反复搜）。"
                    + "不要把训练记忆里的旧信息当成本次结果；查不到就如实说。");
        }
        if (modelFcMode && selectedTools.contains("ai_weather") && isWeatherQuery(userMessage)) {
            String city = extractCityFromMessage(userMessage);
            if (city != null && !city.isEmpty()) {
                String cityId = getCityLocationId(city); // 优先用和风城市编码查询，最精确
                String locParam = cityId != null ? cityId : city;
                // 直给 city 参数并附编码，省掉模型"先查编码再查询"的一轮；编码不可用时退回城市名
                // （AIWeatherManager 内部还有本地 CSV + Geocoder 两层换算，不再依赖 geo API）。
                // action 枚举模型能从 schema 读到，此处不重复列举，只保留"按问法选"这一判断。
                // 与 system prompt 的 ACTION-LOCATION 规则一致：换算不出位置就如实告知，不要用别的城市顶上。
                effectiveUserMessage = userMessage + "\n\n【本次任务】用户要查 " + city + " 的天气，"
                        + "直接调用 ai_weather 工具并传 city=" + locParam
                        + "（优先用此值，不要改用手机定位结果）；action 按用户问法选：当前→current，"
                        + "预报→forecast，空气质量→air_quality，默认 current。"
                        + "若该城市换算不出位置，如实告知查不到，不要用其他城市的数据顶上。";
            } else {
                // 无具体城市（查"这里/附近/现在天气"）：**不要**让模型先调 location ——
                // executeToolSafely 的 fillWeatherLocation 在模型"完全没给位置参数"时会自动补
                // 手机定位的经纬度（优先经纬度缓存、其次用户消息城市、最后当前位置），
                // 比让模型先跑一轮 location 再自己把经纬度填回去少一轮推理。
                // 该文案必须与 system prompt"工具选择"行的位置参数规则保持一致，否则两句互相打架。
                // SCHEMA-SLIM(2026-11-01)：lat/lon 已不在本地 schema 的模型可见面上，
                // 模型无从传它们，文案再提"不要带 lat/lon"就是在教模型回避一个不存在的参数。
                effectiveUserMessage = userMessage + "\n\n【本次任务】用户要查当前位置/附近的天气，直接调用 ai_weather 工具、不要带 city 参数（系统会自动使用手机定位的城市）；action 按问法选 current(实时)/forecast(预报)/air_quality(空气质量) 等。";
            }
        }
        // WHERE-AM-I(2026-11-07)：定位工具已注入核心集，但真机 2026-11-07 “我在哪”仍被模型
        // 拿 ai_weather（空 city 自动定位）和 network_search 代查，最后答“无法得知你具体在哪里”。
        // 与天气直给指令同一策略：把本轮该用哪个工具写进 prompt 末尾，避免 4B 在工具之间绕路。
        if (modelFcMode && selectedTools.contains("location") && isWhereAmIQuery(userMessage)
                && !isWeatherQuery(userMessage)) {
            effectiveUserMessage = appendTailBlock(effectiveUserMessage,
                    "【本次任务】用户问的是当前位置：直接调用 location（action=get_current 获取经纬度/城市/地址；"
                    + "只要城市名用 get_city），不要用 ai_weather 或 network_search 代查。"
                    + "定位失败或无权限时如实说明，并引导用户开启定位权限。");
        }
        // 动态状态（长期记忆摘要 + 活跃任务清单）从 system 移出，改为本轮 user 消息末尾 D 块注入：
        // system 前缀保持静态 → 本地 KV 缓存命中（与上方天气指令同款策略，见 buildCurrentTimeLine 注释）；
        // 动态内容放 prompt 末尾，模型每轮仍能读到并执行，行为不变。
        if (modelFcMode && appContext != null) {
            try {
                String memorySummary = com.oilquiz.app.ai.agent.online.AgentMemoryStore
                        .getInstance(appContext).buildMemorySummary();
                if (memorySummary != null && !memorySummary.isEmpty()) {
                    if (memorySummary.length() > 800) {
                        memorySummary = memorySummary.substring(0, 800) + "…";
                    }
                    effectiveUserMessage = effectiveUserMessage + "\n\n【已存记忆】" + memorySummary;
                }
            } catch (Throwable t) {
                AILogger.w(TAG, "Memory summary injection failed: " + t.getMessage());
            }
            try {
                String taskSummary = com.oilquiz.app.ai.tool.TaskStateTracker
                        .getInstance(appContext).buildTaskSummary();
                if (taskSummary != null && !taskSummary.isEmpty()) {
                    effectiveUserMessage = effectiveUserMessage + "\n\n【当前任务】\n" + taskSummary
                            + "\n任务清单为跨轮状态，多步任务持续用 task 工具维护（update 进度/complete 完成/fail 失败/delete 取消），已完成任务无需再提。";
                }
            } catch (Throwable t) {
                AILogger.w(TAG, "Task summary injection failed: " + t.getMessage());
            }
        }
        history.add(new ChatMessage("user", effectiveUserMessage));

        // ===== 本地 Agent：模型自主处理（意图编排已移除）=====
        // 用户确认模型可自主：不再用 IntentEngine 确定性编排（简化设计），
        // FC 开启 → 模型自主调工具（Qwen 原生 <tool_call>）；FC 关闭 → 普通对话。
        boolean handled = false;
        String finalAnswer = null;
        String persistRaw = null;  // KV 前缀稳定：本轮的模型原始输出（persist 用，clean 会剥尾部字节）

        if (aiConfig != null && aiConfig.isFcEnabled()) {
            // 交给下方模型自主 FC 循环（handled 保持 false）
            AILogger.i(TAG, "Local agent FC mode (no intent orchestration)");
        } else {
            // ===== 普通对话 =====
            // 模型直接回答（不注入工具、不进入 FC 工具循环），行为与普通聊天一致。
            AILogger.i(TAG, "Plain chat mode");
            GenerateResult plain = generatePlainChat(history, enableThinking);
            if (plain != null && plain.content != null && !plain.content.trim().isEmpty()) {
                String clean = cleanResponse(plain.content);
                logAnswerReconcile("plain", plain.content, clean);
                totalTokens += plain.content.length();
                if (!clean.isEmpty() && !isPromptLeakage(clean)) {
                    AILogger.i(TAG, "Plain chat answer: " + truncate(clean, 80));
                    finalAnswer = clean;
                    persistRaw = plain.content; // KV 前缀稳定：用原始输出，勿用 clean
                    handled = true;
                }
            }
            if (cancelRequested.get()) {
                // 取消发生在单次普通生成期间（用户点了停止）：不注入兜底话术——
                // buildSimpleFallback 是给"模型没答上来"用的，给"用户不要了"用会莫名其妙。
                // 也**故意不**调 persistSessionMessages：普通对话路径不设 lastRequestHistory，
                // 此时持久化会把**上一轮**的请求当成本轮序列写进去，污染下一轮前缀。
                AILogger.i(TAG, "Plain chat run cancelled, returning without fallback");
                AgentResponse cancelledResponse = buildResponse("", totalTokens,
                        System.currentTimeMillis() - startTime, toolCallCount, 1);
                cancelledResponse.cancelled = true;
                return cancelledResponse;
            }
            if (!handled) {
                // ENGINE-ASK(2026-11-02)：普通对话失败同样先问用户（用已有结果/补充/停止），
                // 拿不到答复时用**单次推理**解释原因，固定话术只当最后一道保险。
                int ask = askUserOnEngineTrouble("这次回答没有成功生成。");
                if (ask == 1) {
                    finalAnswer = "好的，你补充后我继续。";
                } else if (ask == 2) {
                    finalAnswer = "已停止本次任务。";
                } else {
                    String plainReason = describeGenerationError(lastGenerationError,
                            "这次普通对话没有成功生成（推理调用失败或输出为空）");
                    String explained = explainFailureOnce(history, plainReason);
                    finalAnswer = explained != null ? explained : buildSimpleFallback(plainReason);
                }
                handled = true;
            }
        }

        if (handled) {
            AILogger.i(TAG, "Answer ready, len=" + finalAnswer.length());
            streamDirectAnswer(finalAnswer);
            persistSessionMessages(persistRaw != null ? persistRaw : finalAnswer, activeTools);
            if (callback != null) callback.onComplete(finalAnswer);
            return buildResponse(finalAnswer, totalTokens,
                    System.currentTimeMillis() - startTime, toolCallCount, 1);
        }

        // ===== 模型自主 FC 循环（仅 local_fc_enabled 开启时可达）=====
        // 意图命中 → 程序化输出；意图未命中 + FC 关闭 → 普通对话；仅当两者皆非时进入此处。
        // 与 Qwen-Agent 对齐：模型按 <tool_call> 原生格式自主发起工具调用（多工具并行/多轮）。
        boolean modelAutonomyEnabled = aiConfig != null && aiConfig.isFcEnabled();
        if (modelAutonomyEnabled) {

        // 循环保护状态：已执行调用去重、工具轮次计数、最近一次有效回复
        java.util.Set<String> executedCallKeys = new java.util.HashSet<>();
        // REPEAT-WHITELIST：轮询类调用的按 key 计数（单轮超过 MAX_REPEATABLE_CALLS 即拒绝）
        java.util.Map<String, Integer> repeatableCallCounts = new java.util.HashMap<>();
        int toolRounds = 0;
        int consecutiveDuplicates = 0;
        String lastMeaningfulResponse = null;
        boolean forcedByLoopGuard = false;
        // EXIT-REASON(2026-11-02)：统一退出的**具体**原因。兜底/弹框/单次解释都用它，
        // 不再用"可能被截断、或工具太多"这种含糊说法——每一类退出都在源头赋准确文案。
        String lastExitReason = null;
        // OVERSIZE-SAY(2026-11-02)：本轮最大一条工具结果的字符数与工具名。
        // 数据不裁，但**装不下时必须说清是谁撑爆的**，否则会退化成含糊的"上下文已满"。
        int biggestToolResultChars = 0;
        String biggestToolResultName = null;

        // 最近一次 chatJson 生成是否已有正文 token 流式输出到 UI：
        // - 迭代内直接回答路径：token 已流式 → 迭代结束不重复 streamDirectAnswer
        // - 统一退出路径：generateFinalAnswer 也走 chatJson 流式 → 同样不重复推
        // 每轮迭代开始重置，退出路径读最后一次状态
        lastStreamed = false;

        // TOOL-CALL-ID-UNIQUE(2026-11-07)：工具调用 id 必须在**整个回合内**唯一，不能只在本轮唯一。
        // 旧实现把 callSeq 声明在循环体内，每轮重置 → 每轮第一个调用都叫 call_1。下游多处按
        // toolCallId 做关联（AssistantTurn.fillToolPartsFromComponents 的 byId、groupTools 的
        // callById、synthesizeAbortedToolResults 的配对集合），重复 id 会让后一轮的结果覆盖前一轮：
        // 真机 2026-11-07 的两个对话里，同一张消息的 8 个 read_file 卡全部显示成最后一次读的文件。
        // 前缀用纳秒时间戳：同一进程里不同回合也不会和已落盘的旧 id 撞车，模板只要求 assistant
        // tool_calls 与紧随其后的 tool 消息 id 相等，不限制格式。
        final String toolCallIdPrefix = "call_" + Long.toString(System.nanoTime(), 36) + "_";
        int toolCallSeq = 0;
        java.util.Set<String> usedToolCallIds = new java.util.HashSet<>();

        for (int iteration = 1; iteration <= getAgentMaxIterations(); iteration++) {
            // 取消（2026-10-25）：用户已点停止 → 立即收尾，不再发起新一轮解码
            if (cancelRequested.get()) {
                AILogger.i(TAG, "Cancelled by user at iteration " + iteration + ", exiting loop");
                break;
            }
            // 总时长预算：超时直接收尾，避免长时间卡死
            if (System.currentTimeMillis() - startTime > TOTAL_TIME_BUDGET_MS) {
                AILogger.w(TAG, "Total time budget exceeded, forcing final response");
                forcedByLoopGuard = true;
                lastExitReason = "总时长达到上限（" + (TOTAL_TIME_BUDGET_MS / 1000) + " 秒），已强制收尾";
                break;
            }

            if (callback != null) {
                callback.onIterationStart(iteration, "第 " + iteration + " 轮推理");
            }

            // 本轮是否已有正文 token 流式输出到 UI：
            // 若 chatJson 的 onToken 已推送过正文（模型直接回答路径），
            // 迭代结束就不再 streamDirectAnswer 重推一遍（防重复回复）
            final boolean[] streamedThisIteration = {false};
            lastStreamed = false;

            // 动态注入：每轮从 activeTools 重建 schema——模型已调用过的工具
            // 按需加入注入集（需要哪个注入哪个，不一股脑全量）；
            // CORE-TOOLS-4(2026-10-11)：tool_registry 已移出核心集，"检索后注入"这条路径不存在了，
            // 初始集外的工具只能靠模型自己猜对名字调用成功后才被注入。
            toolsJson = buildToolsJson(new ArrayList<>(activeTools));
            toolsJsonBytes = toolsJson.getBytes(StandardCharsets.UTF_8);
            // schema 指纹：跨轮比对即可确认 tools 前缀是否稳定（不稳定则 KV 前缀必失配）
            AILogger.i(TAG, "Round tools schema: " + activeTools.size() + " tools, len=" + toolsJson.length()
                    + ", hash=" + Integer.toHexString(toolsJson.hashCode()));

            // 每轮推理前裁剪历史，确保单次推理 prompt 不超预算（防截断/decode崩溃）
            history = trimHistoryToFit(history, toolsJson, promptBudget);

            // PROMPT-DOOR(2026-11-07)：裁完仍装不下就**别发请求**。native 那道门只回英文
            // "Prompt too long: N tokens, aborting"，上层再把它喂给解释推理，模型就自己编原因
            // （真机 11-07 说成"生成的回复内容太长"，实际超长的是 prompt 不是回复）。
            // 这里提前给出带数字与下一步的准确原因，走统一退出路径（弹框/解释/兜底共用同一句）。
            String overflow = describePromptOverflow(history, toolsJson, activeTools.size(), null);
            if (overflow != null) {
                AILogger.w(TAG, "Prompt overflow at iteration " + iteration + ", not sending: " + overflow);
                lastExitReason = overflow;
                break;
            }

            // 构建请求 JSON（spec §7.2.1 step b）
            // tool_choice 恒为 auto：实测 tool_choice=required 会让 Qwen3-4B 退化
            // （输出模板标签/重复文本而非 tool_call，FcTest 实证 tool_calls=0 + 乱码），
            // auto 让模型按提示词与工具定义自行判断，需要时自然输出原生 tool_call
            String toolChoice = "auto";
            // 不限制思考量：统一用 OUTPUT_RESERVE_TOKENS（原第一轮 800 会让 enableThinking
            // 模型思考就被截断，触发"使用更强大的模型"兜底；具体安全值由 buildRequestJson 按上下文钳制）
            int iterMaxTokens = OUTPUT_RESERVE_TOKENS;

            long genStart = System.currentTimeMillis();
            GenerateResult genResult = null;

            // 决策轮温度：本处产出 tool_call，用决策轮温度（默认 0.7）
            lastRequestHistory = new ArrayList<>(history); // 记录实际发送的请求（轮末持久化会话序列）
            float iterTemperature = resolveDecisionTemperature();
            String requestJson = buildRequestJson(history, toolsJson, toolChoice, iterMaxTokens,
                    enableThinking, iterTemperature);
            if (requestJson == null) {
                AILogger.e(TAG, "buildRequestJson returned null at iteration " + iteration + ", breaking");
                break;
            }

            try {
                if (aiConfig != null && aiConfig.isUseJsonProtocol()) {
                    // 新协议：chatJson → 统一 onJson 事件
                    genResult = generateWithChatJsonSync(requestJson, streamedThisIteration);
                } else {
                    // 回退开关：旧 generateWithTools 路径（不限制思考量）
                    genResult = generateWithToolsSync(history, toolsJsonBytes, OUTPUT_RESERVE_TOKENS, iterTemperature, enableThinking);
                }
            } catch (UnsatisfiedLinkError e) {
                // §10.2：chatJson 不可用 → 自动切回旧路径；仍失败则本轮失败（模型原生 FC，无标签兜底）
                AILogger.w(TAG, "chatJson unavailable (" + e.getMessage() + "), fallback to generateWithToolsSync");
                try {
                    genResult = generateWithToolsSync(history, toolsJsonBytes, OUTPUT_RESERVE_TOKENS, iterTemperature, enableThinking);
                } catch (UnsatisfiedLinkError e2) {
                    AILogger.w(TAG, "nativeGenerateWithTools unavailable, generation failed");
                    lastGenerationError = "native 生成接口不可用: " + e2.getMessage();
                }
            } catch (Exception e) {
                AILogger.e(TAG, "Generate failed at iter " + iteration + ": " + e.getMessage());
                // GEN-ERROR(2026-11-02)：留下真实原因，兜底描述不再一律说成"模型没输出"
                lastGenerationError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }

            // genResult 为 null（chatJson error/cancelled/timeout，或生成失败）：
            // 本轮视为失败，break 走统一退出路径（lastMeaningfulResponse/兜底回答），防 NPE
            if (genResult == null) {
                AILogger.w(TAG, "genResult null at iteration " + iteration + " (error/cancelled/timeout), breaking");
                break;
            }

            String response = genResult.content;
            if (callback != null && response != null) {
                // 每轮独立 fullText 快照：不是 delta，不是最后拼接
                callback.onRoundComplete(iteration, cleanResponse(response));
            }
            int iterTokens = response != null ? response.length() : 0;
            totalTokens += iterTokens;

            if (callback != null) {
                long elapsed = System.currentTimeMillis() - genStart;
                float tps = elapsed > 0 ? (iterTokens * 1000.0f) / elapsed : 0;
                callback.onInferenceProgress(totalTokens, tps);
                callback.onIterationEnd(iteration, response);
            }

            // P0：native 上下文满截断信号（stop_reason=ctx_full）→ 记录日志并通知 UI。
            // 截断回复不再被当作完整回答静默交付（原实现仅 native 日志可见）。
            if (genResult.stopReason != null && "ctx_full".equals(genResult.stopReason)) {
                lastExitReason = "上下文已满，回答被截断";
                AILogger.w(TAG, "iter " + iteration + " generation truncated by context full, "
                        + "response=" + (response != null ? response.length() : 0) + " chars");
                if (callback != null) callback.onTruncated("第 " + iteration + " 轮回答因上下文已满被截断");
            }

            // 提取思考过程（C++ 层通过 onReasoning / onJson reasoning 事件传递；
            // 未走回调时回退从文本 <think>/<thought> 标签提取，reasoning_content 对齐）
            String reasoning = genResult.reasoning;
            if (reasoning == null || reasoning.isEmpty()) {
                reasoning = extractThought(response);
            }
            // THINK-DUP-FIX(2026-11-08)：思考已在 generateWithChatJsonSync 的 thinking/reasoning
            // 事件里逐 token 推给 UI；这里**不能**再补一条
            //   "第 N 轮思考: " + truncate(reasoning, 120)
            // ——UI 会把它追加进同一个 thinkingContent，真机 T129/T130/T131 都出现
            // “原文 + 第 N 轮思考 + 截断副本”（12k 字符思考文件 76KB）。
            // 轮次标题由 thinkingRounds/UI 负责，原始 reasoning 由 genResult 负责，二者不要混写。

            // 工具调用解析：**唯一权威来源是 native 层**。
            // native 用模板的 PEG parser（common_chat_parse）解析并下发 tool_call 事件，
            // 标签/语法全部由模板动态推导，不需要 Java 侧再猜格式。
            // LOCAL-REGEX-REMOVED(2026-10-09)：原先此处有"native 解析不出就回退正则"的兜底，
            // 那是 makeChatParserParams 未传 parser 那个 bug 的产物 —— 该 bug 已修，
            // 正则兜底会让 native 的退化被静默掩盖（宽松正则能把畸形输出"猜"成工具调用）。
            // 现在改为：native 没给出工具调用，就是真的没有，直接进入无工具分支。
            List<ToolCall> toolCalls = genResult.toolCalls != null
                    ? genResult.toolCalls : new ArrayList<>();

            // 动态注入（基本休眠）：把本轮实际调用的工具名并入 activeTools，下轮起注入其 schema。
            // native PEG 语法只允许生成 activeTools 里的工具名（见 DEFAULT_CORE_TOOLS 注释），
            // 薄工具名又跳过模糊解析，所以下面 !contains 分支实际很难成立；集外能力的真通路是
            // tool_registry(get/call) 代执行，不需要把那份大 schema 中途塞进来（塞了会毁 KV 前缀）。
            // 保留本分支是为将来恢复"引擎侧注入"时复用。名字归一（别名/模糊匹配）在 resolveToolNameFuzzy。
            for (ToolCall tc : toolCalls) {
                if (tc.toolName != null && !tc.toolName.isEmpty()) {
                    // 薄工具名（read_file/write_file）不能被模糊解析成真实工具名（如 file_reader），
                    // 否则会把那份大 schema 中途注入 activeTools → 上下文暴涨 + KV 前缀破坏。
                    // 命中薄工具就原样保留（它已在 activeTools 里，下面 !contains 恒不成立、不注入任何东西）。
                    String resolved = LOCAL_THIN_TOOLS.containsKey(tc.toolName)
                            ? null : toolManager.resolveToolNameFuzzy(tc.toolName);
                    String real = resolved != null ? resolved : tc.toolName;
                    if (!activeTools.contains(real)) {
                        activeTools.add(real);
                        AILogger.i(TAG, "Dynamic injection: added tool " + real
                                + (resolved != null && !resolved.equals(tc.toolName)
                                   ? " (resolved from '" + tc.toolName + "')" : "")
                                + " to active set");
                    }
                }
            }

            // R4-1：执行工具前补齐 tool_call id（assistant 消息与 tool 消息共用同一 id）。
            // TOOL-CALL-ID-UNIQUE(2026-11-07)：id 在**整个回合**内唯一（见循环外 toolCallIdPrefix 注释），
            // 不再每轮从 call_1 重新编号；native 若给了重复 id 也改写，保证下游按 id 关联不串轮。
            for (ToolCall tc : toolCalls) {
                if (tc.id == null || tc.id.isEmpty() || !usedToolCallIds.add(tc.id)) {
                    tc.id = toolCallIdPrefix + (++toolCallSeq);
                    usedToolCallIds.add(tc.id);
                }
            }

            // 空回复检查：工具轮的 complete.content 通常为空（纯 tool_call 输出），
            // 仅"无工具调用且内容为空"才处理；模型原生 FC，无标签重试，直接 break 走统一退出
            if ((response == null || response.trim().isEmpty()) && toolCalls.isEmpty()) {
                AILogger.w(TAG, "Empty response at iteration " + iteration + " with no tool calls, breaking");
                break;
            }
            if (response != null && !response.trim().isEmpty()) {
                // 存清理版（剥掉 tool_call 标签/思考标签），防止工具循环 break 后
                // 回退 lastMeaningfulResponse 时把未清理的 tool_call 残留带进最终消息
                String meaningful = cleanResponse(response);
                if (!meaningful.isEmpty()) {
                    lastMeaningfulResponse = meaningful;   // 工具轮空内容不覆盖最后有效回复
                }
            }

            if (toolCalls.isEmpty()) {
                AILogger.i(TAG, "No tool call at iteration " + iteration + ", using model answer directly");
                String cleanResponse = cleanResponse(response);

                if (cleanResponse.isEmpty()) {
                    // 清理后为空（可能全是 </think> 标签），break 走统一退出路径
                    AILogger.w(TAG, "Clean response empty after stripping tags, breaking");
                    break;
                }

                // ===== 降级重试结束 =====

                // 提示词泄漏检测：模型把系统规则当回答输出时，break 走统一退出路径
                if (isPromptLeakage(cleanResponse)) {
                    AILogger.w(TAG, "Prompt leakage detected, breaking");
                    break;
                }

                // 模型的回答即最终答案：直接流式输出，不再二次生成
                // 防重复：若本轮 token 已流式输出到 UI（onToken 推送过正文），
                // 只发 onComplete 收尾，不重复 streamDirectAnswer
                if (!streamedThisIteration[0]) {
                    streamDirectAnswer(cleanResponse);
                } else {
                    AILogger.i(TAG, "Answer already streamed via tokens, skipping re-stream");
                }
                if (callback != null) callback.onComplete(cleanResponse);
                persistSessionMessages(response, activeTools); // KV 前缀稳定：原始输出，勿用 cleanResponse
                return buildResponse(cleanResponse, totalTokens, System.currentTimeMillis() - startTime, toolCallCount, iteration);
            }

            // 将模型回复追加到历史（content 用纯净正文 + 结构化 tool_calls，A2/R4-1）
            ChatMessage assistantTurnMsg = new ChatMessage("assistant", cleanResponse(response), toolCalls);
            // REASONING-REPLAY(2026-11-01)：本轮思考随历史一起回放。模板对"最后一条 user 之后的
            // assistant 轮"会渲染 <think>{reasoning_content}</think>；不传就只能渲染空块，
            // 多轮工具调用后模型看不到自己上一轮的推理。
            assistantTurnMsg.reasoningContent = reasoning;
            history.add(assistantTurnMsg);

            // 循环保护①：去重。同一 tool+args 已执行过则不再执行；连续两轮全是重复调用 → 强制收尾。
            // REPEAT-WHITELIST(2026-11-02)：异步任务轮询类调用除外——它们的**同参重复本来就是语义**
            // （导入进度、文生图 task_id、盯梢状态、Termux 就绪自检）。旧实现在这里会把这些调用
            // 当成"重复"跳过并回一句"已执行过"，等于把轮询打死（真机：导入卡住不再查进度）。
            List<ToolCall> freshCalls = new ArrayList<>();
            for (ToolCall tc : toolCalls) {
                String key = tc.toolName + "|" + tc.args.toString();
                boolean repeatable = isRepeatablePollingCall(tc.toolName, tc.args);
                if (!repeatable && executedCallKeys.contains(key)) {
                    AILogger.w(TAG, "Duplicate tool call skipped: " + key);
                } else {
                    if (!repeatable) {
                        executedCallKeys.add(key);   // 轮询类不入表：每次都要真执行
                    } else {
                        // 轮询也要有上限：同一 key 单轮超过 MAX_REPEATABLE_CALLS 次视为卡死，
                        // 由循环保护②的轮次上限兜底之前先给模型一句提示，避免白烧 token。
                        int n = repeatableCallCounts.merge(key, 1, Integer::sum);
                        if (n > MAX_REPEATABLE_CALLS) {
                            AILogger.w(TAG, "Repeatable call exceeded " + MAX_REPEATABLE_CALLS
                                    + " times, skipping: " + key);
                            history.add(new ChatMessage("tool",
                                    "[系统提示] 该轮询调用已重复 " + n + " 次，请基于已有结果作答或换用其他方式。",
                                    "call_hint_" + System.nanoTime(), true));
                            continue;
                        }
                    }
                    freshCalls.add(tc);
                }
            }
            if (freshCalls.isEmpty()) {
                consecutiveDuplicates++;
                // R4-1：tool 消息带唯一合成 id（非真实调用，防模板 call_order 匹配错乱）
                history.add(new ChatMessage("tool",
                        "[系统提示] 该工具调用已执行过且结果已在上方给出，请勿重复调用。请基于已有结果直接给出最终回答。",
                        "call_hint_" + System.nanoTime(), true));
                if (consecutiveDuplicates >= 2) {
                    AILogger.w(TAG, "Repeated duplicate tool calls, forcing final answer");
                    forcedByLoopGuard = true;
                    lastExitReason = "连续两轮都是重复的工具调用，已停止重复执行";
                    break;
                }
                continue;
            }
            consecutiveDuplicates = 0;

            // 循环保护②：工具轮次上限
            toolRounds++;
            if (toolRounds > MAX_TOOL_ROUNDS) {
                AILogger.w(TAG, "Tool rounds exceeded " + MAX_TOOL_ROUNDS + ", forcing final answer");
                forcedByLoopGuard = true;
                lastExitReason = "工具调用轮次达到上限（" + MAX_TOOL_ROUNDS + " 轮）";
                break;
            }

            // ===== 并行执行所有新工具调用（结果按调用顺序回填，保证 tool_call_id 配对）=====
            // 每个工具一个线程执行（executeToolSafely 内部已带工具级超时），
            // 全部完成后按 freshCalls 原顺序逐个追加到历史——
            // Qwen 模板按 assistant.tool_calls 的 id 与 tool 消息的 tool_call_id 配对，
            // 回填顺序必须与调用顺序一致，否则模板解析错乱。
            // 单工具场景退化为等价串行（多一层线程，无行为差异）。
            int callCount = freshCalls.size();
            final Object[] parResults = new Object[callCount]; // AIToolResult 或 Throwable
            final boolean[] parDone = new boolean[callCount];
            Thread[] parWorkers = new Thread[callCount];
            for (int i = 0; i < callCount; i++) {
                final ToolCall tc = freshCalls.get(i);
                final int idx = i;
                Thread t = new Thread(() -> {
                    try {
                        parResults[idx] = executeToolSafely(tc.toolName, tc.args);
                    } catch (Throwable th) {
                        parResults[idx] = th;
                    } finally {
                        synchronized (parDone) {
                            parDone[idx] = true;
                            parDone.notifyAll();
                        }
                    }
                }, "agent-tool-par-" + idx);
                t.setDaemon(true);
                parWorkers[idx] = t;
                t.start();
            }
            // 等待全部完成。ASK-SLOW(2026-11-02)：这一批的等待上限取"各调用应等时长"的最大值
            // （基线 15s 与各工具自声明的较大者，见 toolWaitMs），秘塔 ask 申报 180s，
            // 不再被 15 秒一刀切判成"工具失败"进而反复重试白烧按次额度。
            long parBudget = TOOL_TIMEOUT_MS;
            for (ToolCall tc0 : freshCalls) {
                if (tc0 == null) continue;
                long w = toolWaitMs(tc0.toolName, tc0.args != null ? jsonToMap(tc0.args) : null);
                if (w > parBudget) parBudget = w;
            }
            long parDeadline = System.currentTimeMillis() + parBudget;
            synchronized (parDone) {
                // 取消（2026-10-25）：用户点停止后不白等一个 TOOL_TIMEOUT_MS。worker 是 daemon
                // 线程，这里只结束等待，让它们自己跑完；未回填的调用在下面按"已取消"标注。
                while (System.currentTimeMillis() < parDeadline && !cancelRequested.get()) {
                    boolean allDone = true;
                    for (boolean f : parDone) {
                        if (!f) {
                            allDone = false;
                            break;
                        }
                    }
                    if (allDone) break;
                    try {
                        parDone.wait(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            // 按原顺序回填（onToolCall/onToolResult 回调保持有序，UI 展示不乱）
            for (int i = 0; i < callCount; i++) {
                ToolCall tc = freshCalls.get(i);
                toolCallCount++;
                String tLabel = toolLabel(tc.toolName);
                showToast("🔧 调用: " + tLabel);
                // dsh 对齐：工具声明化 pending 卡片（presentCall，纯函数可重放，2026-09-23）
                Map<String, Object> callCard = null;
                AITool presentTool = null;
                try { presentTool = toolManager.getTool(tc.toolName); } catch (Throwable ignored) {}
                if (presentTool != null) {
                    try { callCard = presentTool.presentCall(jsonToMap(tc.args)); } catch (Throwable ignored) {}
                }
                if (callback != null) callback.onToolCall(tc.id, tc.toolName, tc.args.toString());
                if (callback != null) callback.onToolPresent(tc.toolName, callCard);
                AIToolResult result;
                if (parResults[i] instanceof AIToolResult) {
                    result = (AIToolResult) parResults[i];
                } else if (parResults[i] instanceof Throwable) {
                    result = AIToolResult.fail("工具异常: " + ((Throwable) parResults[i]).getMessage());
                } else if (cancelRequested.get()) {
                    // 取消时结束等待、该调用没跑完：不能说"超时"（用户会以为工具坏了）
                    result = AIToolResult.fail("已取消，该工具未执行完成");
                } else {
                    // 说清楚"这次等了多久"：秘塔 ask 的 180 秒与别的工具的 15 秒含义完全不同，
                    // 只写"工具超时"会让模型以为工具坏了于是重试（按次计费，重试就是再扣一次）。
                    result = AIToolResult.fail("工具超时("
                            + (toolWaitMs(tc.toolName, tc.args != null ? jsonToMap(tc.args) : null) / 1000)
                            + "秒)");
                }
                // 成功判定统一走 isToolSuccess：AIWeatherManager 等工具会把
                // "查询失败: 无法定位城市"包装成 success 结果，须识别失败语义并降级为失败，
                // 触发替代方案提示，让模型继续多轮补充信息而非直接收尾。
                boolean success = isToolSuccess(result);
                String resultStr = success ? String.valueOf(result.getResult()) : result.getErrorMessage();
                if (!success && resultStr == null) {
                    resultStr = extractResultText(result);
                }
                // 工具失败时附加替代工具提示：引导模型换工具而非放弃（4B 模型需要明确指引）
                String altHint = null;
                if (!success) {
                    // ALT-ACTION(2026-11-02)：同一工具不同 action 的补救不同——read_url 失败该换
                    // webpage_reader，search 失败该换关键词/调大 limit。以前只按工具名给建议，
                    // 读网页失败却提示"用 ai_weather 查天气"，把模型带偏。
                    String action = tc.args != null ? tc.args.optString("action", null) : null;
                    String alt = getAlternativeTool(tc.toolName, action);
                    if (alt != null) {
                        altHint = alt;
                        resultStr = resultStr + "\n[替代方案] 可尝试工具: " + alt;
                        AILogger.i(TAG, "Tool " + tc.toolName + " failed, suggesting alternative: " + alt);
                    }
                }
                showToast(success ? "✅ " + tLabel + " 完成" : "❌ " + tLabel + " 失败: " + truncate(resultStr, 40));
                // dsh 对齐：工具声明化完成卡片（presentResult，纯函数可重放，2026-09-23）
                Map<String, Object> resultCard = null;
                if (presentTool != null) {
                    try { resultCard = presentTool.presentResult(jsonToMap(tc.args), result); } catch (Throwable ignored) {}
                }
                if (callback != null) callback.onToolResult(tc.id, tc.toolName, success, resultStr);
                if (callback != null) callback.onToolPresent(tc.toolName, resultCard);
                // TOOL-RESULT-JSON(2026-11-02)：回给模型的工具结果一律**格式化成 JSON**。
                // 旧实现是 String.valueOf(Map) → `{code=200, data=[{title=...}]}`：既不是 JSON、
                // 键值又用 '='，2B 只能靠猜——这是搜索类回答幻觉的主要来源之一。
                // AUTO-CONTINUE(2026-11-02)：read_file 一次读不完时，引擎自动分页续读并合并
                //（只影响回给模型的文本；UI 卡片仍用原始结果）。
                AIToolResult modelResult = success ? autoContinueReadIfLarge(tc, result) : result;
                String modelJson = formatToolResultForModel(success, modelResult, altHint);
                // OVERSIZE-SAY(2026-11-02)：数据照原样塞（不裁），但**记下是谁把预算撑爆的**，
                // 供下面安全网如实报给模型/用户 —— "超了就说"，不是"超了就连数据一起裁"。
                if (modelJson != null && modelJson.length() > biggestToolResultChars) {
                    biggestToolResultChars = modelJson.length();
                    biggestToolResultName = tc.toolName;
                }
                history.add(new ChatMessage("tool", modelJson, tc.id, true));
                AILogger.i(TAG, "Tool " + tc.toolName + (success ? " OK" : " FAIL")
                        + ": " + truncate(resultStr, 800));
            }

            // 上下文长度兜底校验（trim 后理论上不会触发，作为安全网）
            // TOOL-RESULT-REFIT(2026-11-07)：每轮开头 trim 到预算内，但**追加完本轮工具结果后**
            // 可能刚好把窗口顶破（真机：轮首裁到 7511/7776，一条 1642 字符的 tool_registry 结果
            // 就超了 → 第 3 轮直接收尾，模型拿着“没查到题库”的残缺上下文编结论）。
            // 这里先按新历史再裁一次（仍从最旧消息丢起，保留当前工具轮），只有裁不动才收尾。
            if (countTokensSafe(serializeHistory(history)) + countTokensSafe(toolsJson) > promptBudget) {
                history = trimHistoryToFit(history, toolsJson, promptBudget);
            }
            if (countTokensSafe(serializeHistory(history)) + countTokensSafe(toolsJson) > promptBudget) {
                // 挤掉更早的历史之后仍然超预算：说明**本轮这条工具结果本身就装不下**。
                // 数据不做静默裁剪，改成把数字说出来，让模型/用户决定下一步（缩小 limit、分段读、或换问题）。
                String who = biggestToolResultName != null ? biggestToolResultName : "工具";
                String detail = who + " 返回了 " + biggestToolResultChars + " 字符（未截断）";
                // PROMPT-DOOR(2026-11-07)：连门都装不下时用带窗口数字与下一步的说法；
                // 只是挤掉了输出预留（门还装得下）时说清是预算不足，别谎报成"窗口满了"。
                String door = describePromptOverflow(history, toolsJson, activeTools.size(), detail);
                lastExitReason = door != null ? door
                        : detail + "，已超出本轮 prompt 预算 " + promptBudget
                        + " tokens（留给回答的空间不足），装不下更早的历史";
                AILogger.w(TAG, "Context still too large after trim, ending loop. 最大一条工具结果: "
                        + who + " " + biggestToolResultChars + " 字符，promptBudget=" + promptBudget + " tokens");
                break;
            }
        }

        // ===== 统一退出路径 =====
        // 所有退出原因（超时/最大迭代/循环保护/上下文溢出/空回复/泄漏）统一走这里
        AILogger.w(TAG, "Loop ended, using unified exit path");

        // dsh 对齐：中止合成错误（2026-09-23）——统一退出前扫描 history，
        // 对无配对 tool 结果的 assistant.tool_calls 补"工具调用已中止"合成结果，
        // 保证 tool_call/tool 配对完整，模型下次知道该工具被取消了。
        synthesizeAbortedToolResults(history);

        // 取消优先（2026-10-25 停止链路修复）：用户点停止后**不得**再发起任何解码。
        // 旧行为是 break 之后照旧走下面的"有工具调用就补一次总结"，于是"点了停止
        // 手机反而再解一轮"；也不注入兜底话术（buildSimpleFallback），那是
        // "模型没答上来"的补救，不是"用户不要了"的补救，混在一起用户会看到莫名其妙的句子。
        final boolean cancelled = cancelRequested.get();

        // 最终回答生成：执行过工具调用但循环被强制收尾、尚未产出完整回答时，
        // 基于全部工具结果生成一次总结回复。单次调用、不注入工具（tool_choice=none），
        // 模型无法再发起工具调用 → 不会重新进入循环，也不会反复总结。
        String clean = null;
        String finalGenRaw = null;  // KV 前缀稳定：最终总结的原始输出
        if (cancelled) {
            // 只回收循环里已产出的正文；已流式过的正文不再重推（见下面 lastStreamed 判定）
            clean = cleanResponse(lastMeaningfulResponse);
            AILogger.i(TAG, "Run cancelled: skipping final-answer generation, partial len="
                    + clean.length());
        } else if (toolCallCount > 0) {
            GenerateResult finalGen = generateFinalAnswer(history, enableThinking, toolsJson);
            if (finalGen != null && finalGen.content != null && !finalGen.content.trim().isEmpty()) {
                clean = cleanResponse(finalGen.content);
                logAnswerReconcile("final", finalGen.content, clean);
                finalGenRaw = finalGen.content;
                totalTokens += finalGen.content.length();
                AILogger.i(TAG, "Final summary generated: " + truncate(clean, 120));
            } else {
                AILogger.w(TAG, "Final summary generation failed, falling back to last meaningful response");
            }
        }
        if (!cancelled && (clean == null || clean.isEmpty())) {
            clean = cleanResponse(lastMeaningfulResponse);
        }
        if (cancelled) {
            // 取消收尾：有正文且此前没流式过 → 补一次流式，保证气泡里不是空白；
            // 已流式过（正常停止大多如此）就什么都不推，正文已在界面上。
            if (!clean.isEmpty() && !isPromptLeakage(clean) && !lastStreamed) {
                streamDirectAnswer(clean);
            }
            // 会话序列只保存"本轮实际发送的请求"，不伪造 assistant 输出
            // （与错误兜底同一口径：persistSessionMessages(null, …)）
            persistSessionMessages(null, activeTools);
            AgentResponse cancelledResponse = buildResponse(clean, totalTokens,
                    System.currentTimeMillis() - startTime, toolCallCount, getAgentMaxIterations());
            cancelledResponse.cancelled = true;
            return cancelledResponse;
        }
        if (!clean.isEmpty() && !isPromptLeakage(clean)) {
            AILogger.i(TAG, "Using final answer: " + truncate(clean, 80));
            // 防重复：generateFinalAnswer 已通过 chatJson onToken 流式输出正文时，
            // 不重复 streamDirectAnswer（lastStreamed 记录最近一次生成是否流式过）
            if (!lastStreamed) {
                streamDirectAnswer(clean);
            } else {
                AILogger.i(TAG, "Final answer already streamed via tokens, skipping re-stream");
            }
            if (callback != null) callback.onComplete(clean);
            persistSessionMessages(finalGenRaw != null ? finalGenRaw : clean, activeTools);
            return buildResponse(clean, totalTokens, System.currentTimeMillis() - startTime, toolCallCount, getAgentMaxIterations());
        }
        // ENGINE-ASK(2026-11-02)：完全没有有效回答时，**先弹选择框问用户**，不要默认回一句"抱歉"。
        // 用仓库现成的 ui_component(choice) + get_result（阻塞 60s）收集选择；只有拿不到答复
        // （无界面/超时/工具失败）才退回文字兜底——引擎不替用户做决定。
        // 泄漏优先于"空输出"：两者都走到这里，但原因不同，得分开说。
        if (lastExitReason == null && clean != null && !clean.isEmpty() && isPromptLeakage(clean)) {
            lastExitReason = "模型输出与系统提示词高度重合，被判为提示词泄漏";
        }
        // EXIT-REASON(2026-11-02)：用循环里记下的**具体**原因；没记（例如模型直接空输出
        // 或被泄漏过滤）时补一条准确说法。弹框、解释、静态兜底三处共用同一句。
        String exitReason = lastExitReason != null ? lastExitReason
                : describeGenerationError(lastGenerationError,
                        "模型这一轮没有输出任何可用内容（回答为空或被过滤）");
        int askChoice = askUserOnEngineTrouble("本轮没有产出可用回答：" + exitReason + "。");
        String fb = null;
        if (askChoice == 0) {
            String partial = lastMeaningfulResponse != null ? cleanResponse(lastMeaningfulResponse) : "";
            fb = !partial.isEmpty() ? partial : null;   // 没有部分结果 → 交给单次推理解释
        } else if (askChoice == 1) {
            fb = "好的，你补充后我继续。";
        } else if (askChoice == 2) {
            fb = "已停止本次任务。";
        }
        if (fb == null) {
            // FALLBACK-EXPLAIN(2026-11-02)：固定话术只当最后一道保险；正常情况下用单次推理
            // 结合已有工具结果说明原因与下一步。
            String explained = explainFailureOnce(history, exitReason);
            fb = explained != null ? explained : buildSimpleFallback(exitReason);
        }
        streamDirectAnswer(fb);
        if (callback != null) callback.onComplete(fb);
        persistSessionMessages(null, activeTools);
        return buildResponse(fb, totalTokens, System.currentTimeMillis() - startTime, toolCallCount, getAgentMaxIterations());

        } // end modelAutonomyEnabled (Qwen-native model FC loop)
        // 理论不可达（前方已全部 return），仅满足编译器
        return buildResponse("", totalTokens, System.currentTimeMillis() - startTime, toolCallCount, 1);
    }

    // ==================== KV 前缀稳定性：会话序列维护（2026-09-23）====================

    /**
     * 会话身份变更检测：首条历史消息变了（切换会话/清空/换模型）→ 重建会话序列。
     * 同会话内 bridge 历史只增不减（首条不变），序列保留 → 前缀字节稳定。
     */
    /**
     * 历史清空判据（2026-11-08）：
     * <ul>
     *   <li>{@code null} = 调用方未提供历史（调试桥单轮注入）——<b>不是</b>清空，必须保留会话；</li>
     *   <li>非 null 但首条非空消息不存在（空列表/全空内容）= 显式清空。</li>
     * </ul>
     * 旧实现把 null 也当清空，导致同 session 第二轮仍重置、每轮 19.5s 全量 prefill。
     */
    static boolean isHistoryExplicitlyCleared(List<HistoryEntry> priorHistory) {
        if (priorHistory == null) return false;
        for (HistoryEntry h : priorHistory) {
            if (h != null && h.content != null && !h.content.trim().isEmpty()) return false;
        }
        return true;
    }

    static String firstHistoryContent(List<HistoryEntry> priorHistory) {
        if (priorHistory == null) return null;
        for (HistoryEntry h : priorHistory) {
            if (h != null && h.content != null && !h.content.trim().isEmpty()) {
                return h.content;
            }
        }
        return null;
    }

    private void resetSessionIfHistoryChanged(List<HistoryEntry> priorHistory) {
        if (sessionMessages.isEmpty()) return; // 尚无会话状态，稍后首次种子化
        // HISTORY-NULL(2026-11-08)：null = 调用方没提供历史（如调试桥单轮注入），
        // 不等于"历史被清空"。之前这里把 null 也当清空，导致同 session 第二轮仍重置，
        // 每轮 prompt 都从 4292 token 全量 prefill（真机 19.5s），多轮记忆也丢。
        // 显式清空必须由调用方传空列表或 resetSessionSequence()。
        if (priorHistory == null) return;
        if (isHistoryExplicitlyCleared(priorHistory)) {
            // 对话已清空（调用方显式传空历史）
            if (!sessionMessages.isEmpty()) {
                AILogger.i(TAG, "Session history cleared, resetting KV-stable sequence");
                sessionMessages.clear();
            }
            sessionTools.clear(); // 工具集随会话重置（下次 run 重新填充核心4工具，见 DEFAULT_CORE_TOOLS）
            return;
        }
        String firstContent = firstHistoryContent(priorHistory);
        ChatMessage first = sessionMessages.get(0);
        String stored = first != null ? first.content : null;
        // startsWith 而非 equals：会话首条消息若走 Agent 路径，序列里存的是带 D 块
        // （【本次任务】/【已存记忆】）的有效消息，bridge 里是原文——首条须按"原文前缀"判定，
        // 否则 fresh 会话第 2 轮起每轮被误判"会话变更"→ 重置 → 全量 prefill。
        if (stored == null || !stored.startsWith(firstContent)) {
            AILogger.i(TAG, "Session changed (first history message differs), resetting KV-stable sequence");
            sessionMessages.clear();
            sessionTools.clear();
        }
    }

    /**
     * RETRY-RESET(2026-11-01)：重试/重生成前清空会话序列与工具集缓存。
     *
     * <p>重试在 UI 上删掉旧回答、把同一个问题重发一次；若引擎还留着"旧问题 + 旧回答"的
     * 逐字节序列，重试的 prompt 就会变成 system + 旧轮 + 同一个问题，模型对着自己的旧答案
     * 续写——真机表现是"点重试后答非所问"（曾只回一句错误的当前日期）。</p>
     *
     * <p>只清序列/工具集缓存，不动模型与 native 上下文：下一次请求会按新序列自然重建前缀。</p>
     */
    public void resetSessionSequence() {
        synchronized (sessionMessages) {
            sessionMessages.clear();
        }
        synchronized (sessionTools) {
            sessionTools.clear();
        }
        AILogger.i(TAG, "Session sequence reset (retry)");
    }

    /**
     * REPEAT-WHITELIST(2026-11-02)：异步任务轮询类调用——同参重复是正常语义，不参与去重。
     *
     * <p>按 (工具名, action) 判定，**不能按工具名一刀切**：`linux_shell(exec)`、
     * `screen_watch` 的其他动作、`dashscope_media(image/video)` 重复仍然要拦（那是死循环）。
     * 白名单依据是"返回异步句柄、结果随时间变化"：</p>
     * <ul>
     *   <li>{@code import_status}：题库导入按 taskId 轮询（没有 action 参数，整个工具豁免）</li>
     *   <li>{@code dashscope_media(action=query)}：文生图/视频未完成时按 task_id 再查</li>
     *   <li>{@code screen_watch(action=status)}：盯梢状态查询</li>
     *   <li>{@code linux_shell(action=termux_wake|termux_status)}：等 Termux 服务起来</li>
     *   <li>{@code remote_dsh(action=get_status)}：桥接/dsh 在线自检</li>
     * </ul>
     */
    static boolean isRepeatablePollingCall(String toolName, JSONObject args) {
        return isRepeatablePollingCall(toolName, args != null ? args.optString("action", "") : "");
    }

    /** 同上，纯字符串重载（便于 JVM 单测，不依赖 org.json 运行时）。 */
    static boolean isRepeatablePollingCall(String toolName, String action) {
        if (toolName == null) return false;
        String a = action != null ? action : "";
        switch (toolName) {
            case "import_status":
                return true;
            case "dashscope_media":
                return "query".equals(a);
            case "screen_watch":
                return "status".equals(a);
            case "linux_shell":
                return "termux_wake".equals(a) || "termux_status".equals(a);
            case "remote_dsh":
                return "get_status".equals(a);
            default:
                return false;
        }
    }

    /** 本地薄 database 的只读动作白名单（完整 database 定义仍在 tool_registry，不受此限）。 */
    static boolean isLocalDatabaseReadAction(String action) {
        return "get_question_count".equals(action)
                || "get_questions".equals(action)
                || "execute_sql".equals(action);
    }

    /**
     * WHERE-AM-I(2026-11-07)：识别“我在哪/当前位置”这类问法，用于本轮直给 location 指令。
     *
     * <p>不用 contains("在哪") 一刀切：那会把“我在哪买票/在哪下载”也判成位置查询。
     * 这里只认整句问位置（“我在哪/我在哪里/我现在在哪/我在哪个城市/这是哪/当前位置”）。</p>
     */
    static boolean isWhereAmIQuery(String message) {
        if (message == null) return false;
        String s = message.trim().replace(" ", "");
        if (s.isEmpty()) return false;
        if (s.contains("当前位置") || s.contains("我的位置")
                || s.contains("这是哪") || s.contains("这里是哪")) {
            return true;
        }
        return s.matches("^我(现在)?在(哪|哪里|什么地方|啥地方)(个)?(城市|地方)?[？?。!！]?$");
    }

    /** 天气问法判据（天气直给指令与“我在哪”直给指令共用，避免“当前位置的天气”同时命中两套指令）。 */
    static boolean isWeatherQuery(String message) {
        return message != null && (message.contains("天气") || message.contains("气温")
                || message.contains("温度") || message.contains("下雨")
                || message.contains("预报") || message.contains("湿度"));
    }

    /**
     * DATABASE-READONLY(2026-11-07)：本地薄 database 的 SQL 只读校验。
     *
     * <p>真实 database 的 execute_sql 支持分号多语句；薄工具给的是 4B 模型，一句"只读"约束
     * 挡不住它把 "select ...; delete from question" 一起发下去。这里只放行单条 SELECT：
     * 去掉尾部一个分号后不允许再出现分号，且首词必须是 select。</p>
     */
    static boolean isReadOnlySelectSql(String sql) {
        if (sql == null) return false;
        String s = sql.trim();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        if (s.isEmpty() || s.indexOf(';') >= 0) return false;
        return s.regionMatches(true, 0, "select", 0, 6);
    }

    /**
     * 轮末持久化会话序列：上一轮实际发送的请求（去 system）+ 助手最终输出。
     * 下一轮 prompt = system + 该序列 + 新用户消息 = 上一轮 KV 缓存的严格超集
     * → AgentKvCache INCREMENTAL（增量 prefill），跳过每轮全量 prefill。
     * 异常/失败兜底时 assistantContent 传 null（不伪造模型输出，仅保存请求）。
     */
    private void persistSessionMessages(String assistantContent, java.util.Set<String> finalTools) {
        try {
            List<ChatMessage> next = new ArrayList<>();
            List<ChatMessage> req = lastRequestHistory;
            if (req != null) {
                for (int i = 1; i < req.size(); i++) { // 去掉 system 头
                    next.add(req.get(i));
                }
            }
            if (assistantContent != null && !assistantContent.trim().isEmpty()) {
                next.add(new ChatMessage("assistant", assistantContent));
            }
            synchronized (sessionMessages) {
                sessionMessages.clear();
                sessionMessages.addAll(next);
            }
            // 工具集持久化：FC 循环内动态注入的结果跨轮保留，
            // 下一轮初始 tools == 本轮最终 tools → system 前缀字节稳定
            if (finalTools != null) {
                synchronized (sessionTools) {
                    sessionTools.clear();
                    sessionTools.addAll(finalTools);
                }
            }
            AILogger.i(TAG, "Session sequence persisted: " + next.size()
                    + " msgs (request=" + (req != null ? req.size() : 0)
                    + ", tools=" + (finalTools != null ? finalTools.size() : 0) + ")");
        } catch (Throwable t) {
            AILogger.w(TAG, "persistSessionMessages failed: " + t.getMessage());
        }
    }

    /**
     * 最终回答生成：循环结束后，基于全部历史（含工具结果）生成一次总结回复。
     *
     * 防循环设计：
     * 1. 在迭代循环之外调用，至多执行一次；
     * 2. 不注入任何工具（tools 为空 + tool_choice=none），模型无法输出 tool_call，
     *    结果不会触发工具执行或循环重入；
     * 3. 失败/超时/空输出直接返回 null 回退，绝不重试；
     * 4. 末尾追加"请基于以上结果给出最终回答"指令，引导小模型做总结而非续写工具调用。
     */
    private GenerateResult generateFinalAnswer(List<ChatMessage> history, boolean enableThinking, String toolsJson) {
        AILogger.i(TAG, "Generating final summary answer based on all results...");
        try {
            // 留出输出空间：把历史裁剪到 budget - 1000，避免总结输出时上下文溢出
            int budget = computePromptBudget();
            List<ChatMessage> trimmed = trimHistoryToFit(history, "",
                    Math.max(1000, budget - OUTPUT_RESERVE_TOKENS));

            // 追加总结指令（作为最后一轮 user 消息，引导模型整合结果给结论）
            List<ChatMessage> summaryHistory = new ArrayList<>(trimmed);
            summaryHistory.add(new ChatMessage("user",
                    "请基于以上对话和工具返回的结果，用中文给出最终回答。\n"
                    + "要求：只提取与用户问题直接相关的关键信息，用自然语言简洁总结；"
                    + "严禁照抄/复述工具返回的原始数据、JSON、字段列表或完整详情"
                    + "（如\"湿度: 93% 降水量: 0.0mm\"这类罗列）；"
                    + "工具返回的次要细节只在用户问到时才提及。"));

            lastRequestHistory = new ArrayList<>(summaryHistory); // 记录实际发送的请求（轮末持久化会话序列）
            // 回答轮温度：tool_choice=none，纯自然语言总结，用回答轮温度
            String requestJson = buildRequestJson(summaryHistory, toolsJson, "none",
                    OUTPUT_RESERVE_TOKENS, enableThinking, resolveAnswerTemperature());
            if (requestJson == null) return null;

            if (aiConfig != null && aiConfig.isUseJsonProtocol()) {
                // 传 null：不重置本轮标志；onToken 内部仍会更新 lastStreamed 记录流式状态
                return generateWithChatJsonSync(requestJson, null);
            }
            // 旧路径：tools 与迭代同字节（tool_choice=none 阻止模型发起调用），
            // 保证总结轮 prompt 前缀与迭代一致 → KV 增量，而非 tools=空 触发全量
            byte[] fbTools = toolsJson != null && !toolsJson.isEmpty()
                    ? toolsJson.getBytes(StandardCharsets.UTF_8) : new byte[0];
            return generateWithToolsSync(summaryHistory, fbTools,
                    OUTPUT_RESERVE_TOKENS, resolveAnswerTemperature(), enableThinking);
        } catch (UnsatisfiedLinkError e) {
            AILogger.w(TAG, "Final answer generation: native unavailable: " + e.getMessage());
            return null;
        } catch (Exception e) {
            AILogger.e(TAG, "Final answer generation failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 普通对话生成（意图未命中时）：模型直接回答，不注入工具、不进 FC 循环，
     * 行为与普通聊天一致。失败返回 null。
     */
    private GenerateResult generatePlainChat(List<ChatMessage> history, boolean enableThinking) {
        try {
            int budget = computePromptBudget();
            List<ChatMessage> trimmed = trimHistoryToFit(history, "",
                    Math.max(1000, budget - OUTPUT_RESERVE_TOKENS));
            lastRequestHistory = new ArrayList<>(trimmed); // 记录实际发送的请求（轮末持久化会话序列）
            String requestJson = buildRequestJson(trimmed, "", "none",
                    OUTPUT_RESERVE_TOKENS, enableThinking, resolveAnswerTemperature());
            if (requestJson == null) return null;
            if (aiConfig != null && aiConfig.isUseJsonProtocol()) {
                return generateWithChatJsonSync(requestJson, null);
            }
            return generateWithToolsSync(trimmed, new byte[0],
                    OUTPUT_RESERVE_TOKENS, resolveAnswerTemperature(), enableThinking);
        } catch (Exception e) {
            AILogger.e(TAG, "Plain chat generation failed: " + e.getMessage());
            lastGenerationError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return null;
        }
    }

    private AgentResponse buildResponse(String answer, int tokens, long time, int toolCalls, int steps) {        AgentResponse r = new AgentResponse(answer);
        r.stats = new AgentStats(tokens, time, toolCalls, steps);
        return r;
    }

    // ==================== 原生 FC 同步调用 ====================

    /**
     * NO_OUTPUT_CAP(2026-11-01)：本地路径这一轮能生成多少 = 剩余上下文，而不是固定数字。
     *
     * <p>与 {@code buildRequestJson} 里同一口径（那里已有注释说明为什么不能再钉死）。
     * 走原生 FC 的调用链以前直接把调用方传来的 2000/4000 交给 native，等于绕开了
     * JSON 那条的钳制，两条路的实际上限一直不一致；现在统一到"剩余窗口"。
     * ctx 取不到时退回请求值，避免算出 0/负数把回答掐死。</p>
     *
     * @param requested 调用方原本想给的上限（仅在 n_ctx 未知时作为回落值）
     */
    private int resolveGenerationCap(List<ChatMessage> history, byte[] toolsJson, int requested) {
        // PROMPT-DOOR(2026-11-07)：与 buildRequestJson 同口径，按门容量（已扣 native guard）算
        final int capacity = getSafePromptCapacity();
        if (capacity <= 0) return Math.max(256, requested);
        String tools = (toolsJson == null || toolsJson.length == 0)
                ? "" : new String(toolsJson, StandardCharsets.UTF_8);
        int promptTokens = countTokensSafe(serializeHistory(history))
                + countTokensSafe(tools) + TEMPLATE_OVERHEAD_RESERVE;
        return Math.max(256, capacity - promptTokens);
    }

    /**
     * 同步调用 llama.cpp 原生 function calling。
     * C++ 层通过 common_chat_parse 解析模型原生 FC 输出，通过 JNI 回调直接传递
     * onToolCalls/onReasoning，与在线 Agent 使用相同的 ToolCallInfo 格式。
     * 模型想输出什么就输出什么，不强制思考、不强制工具调用格式。
     */
    private GenerateResult generateWithToolsSync(List<ChatMessage> history, byte[] toolsJson,
                                         int maxTokens, float temperature, boolean thinking) {
        CountDownLatch latch = new CountDownLatch(1);
        final List<OnlineInferenceService.ToolCallInfo> toolCallsHolder = new ArrayList<>();
        final StringBuilder reasoningBuf = new StringBuilder();
        final StringBuilder fullTextBuf = new StringBuilder();
        final String[] errorHolder = {null};

        // 将 history 拆分为 roles + contents 数组
        int size = history.size();
        String[] roles = new String[size];
        byte[][] contents = new byte[size][];
        for (int i = 0; i < size; i++) {
            roles[i] = history.get(i).role;
            contents[i] = history.get(i).content.getBytes(StandardCharsets.UTF_8);
        }

        NpuEngineRouter.generateWithTools(roles, contents, toolsJson,
                resolveGenerationCap(history, toolsJson, maxTokens), temperature,
                0.9f, 40, thinking, new LlamaHelper.TokenCallback() {
                    @Override
                    public void onToken(String token) {
                        if (token == null || token.isEmpty()) return;
                        fullTextBuf.append(token);
                        if (callback != null) {
                            callback.onToken(token);
                        }
                    }

                    @Override
                    public void onToolCalls(List<OnlineInferenceService.ToolCallInfo> toolCalls) {
                        if (toolCalls != null && !toolCalls.isEmpty()) {
                            toolCallsHolder.addAll(toolCalls);
                            AILogger.i(TAG, "Received " + toolCalls.size() + " tool calls from C++ layer");
                        }
                    }

                    @Override
                    public void onReasoning(String reasoning) {
                        if (reasoning != null && !reasoning.isEmpty()) {
                            reasoningBuf.append(reasoning);
                            AILogger.i(TAG, "Received reasoning (" + reasoning.length() + " chars) from C++ layer");
                            if (callback != null) {
                                callback.onThinkingUpdate(reasoning);
                            }
                        }
                    }

                    @Override
                    public void onComplete(String fullText) {
                        if (fullText != null && !fullText.isEmpty()) {
                            synchronized (fullTextBuf) {
                                fullTextBuf.setLength(0);
                                fullTextBuf.append(fullText);
                            }
                        }
                        latch.countDown();
                    }

                    @Override
                    public void onError(String error) {
                        errorHolder[0] = error;
                        latch.countDown();
                    }
                });

        try {
            boolean done = latch.await(SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!done) {
                AILogger.e(TAG, "generateWithTools timeout after " + SYNC_TIMEOUT_MS + "ms");
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        if (errorHolder[0] != null) {
            AILogger.e(TAG, "generateWithTools error: " + errorHolder[0]);
            return null;
        }

        // 直接使用 C++ 层解析出的工具调用和推理内容，不再从 onComplete 文本中解析
        String content = fullTextBuf.toString().trim();
        String reasoning = reasoningBuf.toString();

        // 将 ToolCallInfo 转换为内部 ToolCall
        List<ToolCall> nativeToolCalls = new ArrayList<>();
        for (OnlineInferenceService.ToolCallInfo tc : toolCallsHolder) {
            String argsStr = tc.arguments != null ? tc.arguments : "{}";
            JSONObject argsJson;
            try {
                argsJson = new JSONObject(argsStr);
            } catch (Exception e) {
                argsJson = new JSONObject();
            }
            nativeToolCalls.add(new ToolCall(tc.id, tc.name, argsJson));
        }

        AILogger.i(TAG, "generateWithToolsSync result: contentLen=" + content.length()
                + " reasoningLen=" + reasoning.length()
                + " toolCalls=" + nativeToolCalls.size());

        return new GenerateResult(content, reasoning, nativeToolCalls);
    }

    /**
     * 新协议同步调用（spec §7.2.1 step c/d）：
     * LlamaHelper.chatJson → C++ chatJson → 统一 onJson 事件。
     * onJson 状态机：token(流式) / tool_call(收集) / reasoning(思考) / complete(本轮结束) / error(终止)
     */
    private GenerateResult generateWithChatJsonSync(String requestJson, final boolean[] streamedFlag) {
        CountDownLatch latch = new CountDownLatch(1);
        final List<ToolCall> toolCallsHolder = new ArrayList<>();
        final StringBuilder reasoningBuf = new StringBuilder();
        final StringBuilder fullContentBuf = new StringBuilder();  // 收集所有 token，兜底解析工具调用
        final String[] contentHolder = {null};
        final String[] errorHolder = {null};
        final String[] stopReasonHolder = {"normal"};   // P0：complete 事件回传的 native 停止原因
        // Java 侧流式 tool_call 兜底状态：C++ is_tool_call 对 <tool_call> 标签格式失效时，
        // 用跨 token 缓冲识别并吞掉标签片段（避免 tool_call JSON 流式透传到 UI）
        final StringBuilder streamFilterBuf = new StringBuilder();
        final boolean[] streamSwallowing = {false};
        final boolean[] streamJsonDone = {false};
        final StringBuilder streamCloseBuf = new StringBuilder();
        final boolean[] done = {false};   // F10：幂等标志，error/complete 后忽略迟到事件
        final boolean[] thinkingStreamedAgent = {false};   // 思考已实时累积（thinking 事件），reasoning 全文跳过防重复

        // 工具调用标签兜底：正常由 meta 事件覆盖；若本轮没收到 meta（旧路径/异常），
        // 这里主动向引擎要一次模板标签，避免退化成"不吞除"而泄漏标签。
        // LlamaHelper.getToolCallTags() 内部有缓存，重复调用开销可忽略。
        try {
            if (!toolCallTags.isAvailable()) {
                ToolCallTagConfig fromEngine = ToolCallTagConfig.forCurrentEngine();
                if (fromEngine.isAvailable()) toolCallTags = fromEngine;
            }
        } catch (Throwable ignored) {
            // 取不到就保持空配置（按纯正文处理，不吞除）
        }

        InferenceGateway.get().chatJson(requestJson, new LlamaHelper.JsonCallback() {
            @Override
            public void onJson(String json) {
                if (done[0]) return;
                try {
                    JSONObject event = new JSONObject(json);
                    String type = event.optString("type", "");
                    switch (type) {
                        case "meta":
                            // 思考标签由 native 从 chat template 推导后下发，首个 token 前到达。
                            // 每次生成都会覆盖，换模型/换模板时自动更新，无需 Java 侧硬编码。
                            thinkingTags = ThinkingTagConfig.fromJson(event);
                            toolCallTags = ToolCallTagConfig.fromJson(event);
                            break;
                        case "token":
                            // is_tool_call=true 的 token（tool_call JSON 片段）吞掉不渲染（§5.2）
                            if (!event.optBoolean("is_tool_call", false)) {
                                String token = event.optString("content", "");
                                if (!token.isEmpty()) {
                                    fullContentBuf.append(token);  // 收集完整内容，兜底解析工具调用
                                    // Java 侧兜底：C++ is_tool_call 对 <tool_call> 标签格式失效时，
                                    // 流式吞掉标签格式的 tool_call 片段（支持跨 token 拆分/漏闭合补壳）
                                    String emit = filterStreamToken(token, streamFilterBuf, streamSwallowing, streamJsonDone, streamCloseBuf);
                                    if (!emit.isEmpty()) {
                                        if (streamedFlag != null) {
                                            streamedFlag[0] = true;      // 正文已流式输出（本轮）
                                        }
                                        lastStreamed = true;             // 最近一次生成有正文流式输出
                                        if (callback != null) {
                                            callback.onToken(emit);
                                        }
                                    }
                                }
                            }
                            break;
                        case "tool_call":
                            // 流式增量事件（native §5.3 已 diff 去重，此处双保险）：
                            // 同 name+arguments 视为同一次工具调用，重复跳过，避免工具重复执行
                            {
                                ToolCall tcEvent = parseToolCallEvent(event);
                                boolean dup = false;
                                for (ToolCall exist : toolCallsHolder) {
                                    if (exist.toolName.equals(tcEvent.toolName)
                                            && exist.args.toString().equals(tcEvent.args.toString())) {
                                        dup = true;
                                        break;
                                    }
                                }
                                if (!dup) toolCallsHolder.add(tcEvent);
                            }
                            break;
                        case "thinking":
                            // 实时思考增量事件：native 思考段每累积一段下发，思考区实时显示
                            {
                                String tk = event.optString("content", "");
                                if (!tk.isEmpty()) {
                                    thinkingStreamedAgent[0] = true;
                                    reasoningBuf.append(tk);
                                    if (callback != null) callback.onThinkingUpdate(tk);
                                }
                            }
                            break;
                        case "reasoning":
                            // 思考已实时累积（thinkingStreamedAgent）则全文跳过防重复
                            if (thinkingStreamedAgent[0]) break;
                            String reasoning = event.optString("content", "");
                            if (!reasoning.isEmpty()) {
                                reasoningBuf.append(reasoning);
                                if (callback != null) callback.onThinkingUpdate(reasoning);
                            }
                            break;
                        case "complete":
                            contentHolder[0] = event.optString("content", "");
                            stopReasonHolder[0] = event.optString("stop_reason", "normal");

                            done[0] = true;
                            latch.countDown();
                            break;
                        case "error":
                            errorHolder[0] = event.optString("message", "Unknown error");
                            done[0] = true;
                            latch.countDown();
                            break;
                        default:
                            AILogger.w(TAG, "Unknown onJson event type: " + type);
                            break;
                    }
                } catch (Exception e) {
                    AILogger.e(TAG, "onJson parse error: " + e.getMessage());
                    if (!done[0]) {
                        errorHolder[0] = "onJson parse error: " + e.getMessage();
                        done[0] = true;
                        latch.countDown();
                    }
                }
            }
        }, "AgentLoopEngine");

        try {
            boolean completed = latch.await(SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!completed) {
                AILogger.e(TAG, "chatJson timeout after " + SYNC_TIMEOUT_MS + "ms");
                lastGenerationError = "chatJson timeout after " + SYNC_TIMEOUT_MS + "ms";
                // 超时可能等不到 complete/error：显式释放闸门，否则下一条消息永久 busy
                InferenceGateway.get().forceEnd("AgentLoopEngine");
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastGenerationError = "interrupted";
            InferenceGateway.get().forceEnd("AgentLoopEngine");
            return null;
        }

        if (errorHolder[0] != null) {
            // cancelled（R3-2）与真实错误统一视为本轮失败，走 run() 统一退出路径
            AILogger.e(TAG, "chatJson error: " + errorHolder[0]);
            lastGenerationError = errorHolder[0];
            // 解析异常/非常规 error 事件可能不是标准 {"type":"error"}，闸门必须显式释放
            InferenceGateway.get().forceEnd("AgentLoopEngine");
            return null;
        }

        String content = contentHolder[0] != null ? contentHolder[0].trim() : "";

        // LOCAL-REGEX-REMOVED(2026-10-09)：此处原有两个正则兜底
        // （native 未下发 tool_call 时，从流式全文/complete 正文里用正则捞工具调用）。
        // 它们与 AgentService 的 11 套正则重复，且会把 native 的解析退化静默掩盖。
        // native 现在通过模板 PEG parser 解析并下发 tool_call 事件，是唯一权威来源；
        // 若确实没有下发，那就是该轮没有工具调用，交由后续无工具分支处理。
        // 说明：在线路径不经过本函数（它走 OpenAI 原生 tool_calls → ToolCallInfo），
        // 因此删除本地兜底不影响在线模型。

        AILogger.i(TAG, "generateWithChatJsonSync result: contentLen=" + content.length()
                + " reasoningLen=" + reasoningBuf.length()
                + " toolCalls=" + toolCallsHolder.size()
                + " stopReason=" + stopReasonHolder[0]
                + " fullContentLen=" + fullContentBuf.length());

        return new GenerateResult(content, reasoningBuf.toString(), toolCallsHolder, stopReasonHolder[0]);
    }

    /**
     * 流式过滤工具调用标签片段（Java 侧兜底）。
     *
     * <p><b>标签来源动态化（2026-10-08）</b>：本方法原先只硬编码识别 Qwen 的
     * {@code <tool_call>}，导致 MiniCPM5 的 {@code <function name=".."><param name="..">}
     * 被判为普通正文推给 UI 并被 TTS 朗读。现改为读取模板下发的
     * {@link ToolCallTagConfig}（native 从 {@code preserved_tokens} 推导），
     * 换模型无需改本方法。标签集为空时**直接原样输出**，不做任何吞除。</p>
     *
     * <p>状态机：
     * - 开标签跨 token 拆分时（如 tool 与 _call）前缀滞留探测，识别后进入吞状态；
     * - 吞状态下持续累积直到闭合标签；若模型漏输出闭合标签，则内容 {} 配平后进入
     *   "观望"状态：继续吞可选的闭合标签残留（含跨 token 拆分），一旦收到非
     *   闭合标签内容即补壳退出，把正常正文还回，保证不泄露标签也不吞掉正常回复；
     * - 超长保护：累积超过上限仍无闭合时强制结束并交回当前 token。
     * tool_calls 复数标签同样覆盖（其以 tool_call 为前缀）。
     * @return 需要推送给 UI 的正文片段（正在吞 tool_call 时返回 ""）
     */
    private String filterStreamToken(String token, StringBuilder buf, boolean[] swallowing,
                                     boolean[] jsonDone, StringBuilder closeBuf) {
        if (swallowing[0]) {
            // 超长保护：累积超过上限仍未闭合 → 强制补壳退出，当前 token 交回正常路径
            if (buf.length() > 8192) {
                swallowing[0] = false;
                jsonDone[0] = false;
                buf.setLength(0);
                return filterStreamToken(token, buf, swallowing, jsonDone, closeBuf);
            }
            // 正在吞工具调用：持续累积（闭合标签可能跨 token 拆分）
            buf.append(token);
            String acc = buf.toString();
            int[] closeLen = new int[1];
            int close = findCloseTag(acc, closeLen);
            if (close >= 0) {
                // 有闭合标签 → 正常结束；闭标签长度按模板标签取（不再假设单字符 '>'）
                swallowing[0] = false;
                jsonDone[0] = false;
                String tail = acc.substring(close + closeLen[0]);
                buf.setLength(0);
                if (!tail.isEmpty()) return filterStreamToken(tail, buf, swallowing, jsonDone, closeBuf);
                return "";
            }
            if (jsonDone[0]) {
                // 观望：JSON 已配平，吞掉可选的闭合标签残留
                closeBuf.append(token);
                String cb = closeBuf.toString();
                int[] cbLen = new int[1];
                int cClose = findCloseTag(cb, cbLen);
                if (cClose >= 0) {
                    // 闭合标签补全（含跨 token 拆分）→ 结束，处理其后的正文
                    swallowing[0] = false;
                    jsonDone[0] = false;
                    closeBuf.setLength(0);
                    buf.setLength(0);
                    String tail = cb.substring(cClose + cbLen[0]);
                    if (!tail.isEmpty()) return filterStreamToken(tail, buf, swallowing, jsonDone, closeBuf);
                    return "";
                }
                if (isClosingTagIncomplete(cb)) {
                    // 闭合标签前缀（跨 token 拆分中）→ 继续吞
                    return "";
                }
                // 不是闭合标签 → 模型确实漏闭合：closeBuf 内容视为正文输出（不吞正常回复）
                swallowing[0] = false;
                jsonDone[0] = false;
                closeBuf.setLength(0);
                buf.setLength(0);
                return cb;
            }
            // JSON 对象已完整（org.json 解析成功）→ 进入观望状态（等可选闭合标签 / 判定漏闭合）
            if (isCompleteJsonObject(acc)) {
                jsonDone[0] = true;
                return "";
            }
            return "";
        }
        // 不在吞状态：把 token 追加到探测缓冲
        buf.append(token);
        String probe = buf.toString();

        // 查找工具调用开标签（标签集来自模板：MiniCPM5 的 <function/<param、
        // Qwen 的 <tool_call 都在其中，不再硬编码单一写法）
        int open = findOpenTag(probe);
        if (open >= 0) {
            String before = probe.substring(0, open);
            buf.setLength(0);
            buf.append(probe.substring(open));
            String after = buf.toString();
            int[] aLen = new int[1];
            int aClose = findCloseTag(after, aLen);
            if (aClose >= 0) {
                // 同一缓冲内已闭合：处理闭合标签之后的正文
                buf.setLength(0);
                String tail = after.substring(aClose + aLen[0]);
                if (!tail.isEmpty()) return before + filterStreamToken(tail, buf, swallowing, jsonDone, closeBuf);
                return before;
            }
            // 闭合标签正在跨 token 流入 → 进入吞状态继续等
            if (isClosingTagIncomplete(after)) {
                closeBuf.setLength(0);
                swallowing[0] = true;
                return before;
            }
            // 模型漏闭合但内容已在同一缓冲内形成完整 JSON → 进入观望状态
            if (isCompleteJsonObject(after)) {
                closeBuf.setLength(0);
                swallowing[0] = true;
                jsonDone[0] = true;
                return before;
            }
            closeBuf.setLength(0);
            swallowing[0] = true;
            return before;
        }

        // 无完整开标签：末尾是否存在跨 token 拆分的开标签前缀（如 "<fun" / "<tool_c"）
        int lastLt = findIncomingOpenTagPrefix(probe);
        if (lastLt >= 0) {
            String suffix = probe.substring(lastLt);
            String head = probe.substring(0, lastLt);
            buf.setLength(0);
            buf.append(suffix);
            // 恰好等于某个完整开标签 → 直接进入吞状态
            if (toolCallTags.getOpenTags().contains(suffix)) {
                closeBuf.setLength(0);
                swallowing[0] = true;
            }
            return head;
        }
        // 无任何可疑前缀 → 缓冲全部输出
        buf.setLength(0);
        return probe;
    }

    /** 判断 s 中从第一个 { 起是否已形成完整 JSON 对象：用 org.json 解析器判定（正确处理字符串内花括号/嵌套/转义） */
    private boolean isCompleteJsonObject(String s) {
        int start = s.indexOf('{');
        if (start < 0) return false;
        if (s.indexOf('}') < start) return false;   // 尚无闭合括号，未形成对象
        try {
            new JSONObject(s.substring(start));
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    /**
     * 判断 s 末尾是否存在正在跨 token 流入的**闭合标签前缀**（如 {@code </} / {@code </f}），
     * 用于避免提前补壳。闭合标签取自模板（{@link ToolCallTagConfig}），不再硬编码。
     */
    private boolean isClosingTagIncomplete(String s) {
        int lt = s.lastIndexOf('<');
        if (lt < 0) return false;
        String suffix = s.substring(lt);
        for (String close : toolCallTags.getCloseTags()) {
            if (close.startsWith(suffix) && suffix.length() < close.length()) return true;
        }
        return false;
    }

    /**
     * 在当前缓冲末尾找出"正在跨 token 流入的开标签前缀"，返回其起始下标；无则返回 -1。
     * 开标签取自模板，因此 MiniCPM5 的 {@code <function}/{@code <param} 与
     * Qwen 的 {@code <tool_call} 都走同一套逻辑。
     */
    private int findIncomingOpenTagPrefix(String s) {
        int lt = s.lastIndexOf('<');
        if (lt < 0) return -1;
        String suffix = s.substring(lt);
        for (String open : toolCallTags.getOpenTags()) {
            if (open.startsWith(suffix) && suffix.length() < open.length()) return lt;
        }
        return -1;
    }

    /**
     * 在当前缓冲中查找任一工具调用开标签，返回最早出现的位置；无则返回 -1。
     */
    private int findOpenTag(String s) {
        int best = -1;
        for (String open : toolCallTags.getOpenTags()) {
            int p = s.indexOf(open);
            if (p >= 0 && (best < 0 || p < best)) best = p;
        }
        return best;
    }

    /**
     * 在当前缓冲中查找任一工具调用闭标签，返回最早出现的位置与其长度；无则返回 -1。
     * 长度通过长度为 1 的 int[] 回传。
     */
    private int findCloseTag(String s, int[] lenOut) {
        int best = -1;
        int bestLen = 0;
        for (String close : toolCallTags.getCloseTags()) {
            int p = s.indexOf(close);
            if (p >= 0 && (best < 0 || p < best)) {
                best = p;
                bestLen = close.length();
            }
        }
        if (lenOut != null && lenOut.length > 0) lenOut[0] = bestLen;
        return best;
    }

    /** 解析 tool_call 事件（§4.2）：id/name/arguments(JSON 字符串) */
    private ToolCall parseToolCallEvent(JSONObject event) {
        String idStr = event.optString("id", "");
        String id = (idStr == null || idStr.isEmpty()) ? null : idStr;
        String name = event.optString("name", "");
        String argsStr = event.optString("arguments", "{}");
        JSONObject argsJson;
        try {
            argsJson = new JSONObject(argsStr);
        } catch (Exception e) {
            argsJson = new JSONObject();
        }
        return new ToolCall(id, name, argsJson);
    }

    /**
     * 决策轮温度：用户参数优先，未配置回落到 {@link #DEFAULT_DECISION_TEMPERATURE}。
     * 取值顺序：UserModelParamsManager（per-run 覆盖 > 持久化手动值 > 自动模式默认）。
     * 温度不进 KV 前缀缓存，因此运行期调整无需重载模型，可随时回退。
     */
    private float resolveDecisionTemperature() {
        try {
            if (appContext != null) {
                return UserModelParamsManager.getInstance(appContext).getDecisionTemperature();
            }
        } catch (Throwable t) {
            AILogger.w(TAG, "resolveDecisionTemperature failed, using default: " + t.getMessage());
        }
        return DEFAULT_DECISION_TEMPERATURE;
    }

    /** 回答轮温度：同上，用户参数优先。 */
    private float resolveAnswerTemperature() {
        try {
            if (appContext != null) {
                return UserModelParamsManager.getInstance(appContext).getAnswerTemperature();
            }
        } catch (Throwable t) {
            AILogger.w(TAG, "resolveAnswerTemperature failed, using default: " + t.getMessage());
        }
        return DEFAULT_ANSWER_TEMPERATURE;
    }

    /** 打印当前生效温度与来源（决策轮/回答轮），便于 adb logcat 核对用户参数是否生效 */
    private void logEffectiveTemperature() {
        try {
            if (appContext == null) return;
            AILogger.i(TAG, "effective sampling: "
                    + UserModelParamsManager.getInstance(appContext).describeEffectiveSampling());
        } catch (Throwable ignored) {
        }
    }

    /**
     * 构建 OpenAI 格式请求 JSON（spec §4.1 / §7.2.3）
     * 支持 assistant.tool_calls（结构化）与 tool.tool_call_id（R4-1）
     * 失败返回 null（org.json 的 put 抛受检 JSONException，内部消化）
     *
     * @param temperature 采样温度：决策轮传 {@link #resolveDecisionTemperature()}，
     *                    回答轮（tool_choice=none）传 {@link #resolveAnswerTemperature()}。
     *                    USER-PARAMS(2026-10-11)：原先硬编码 0.7f，用户改设置无效
     */
    private String buildRequestJson(List<ChatMessage> history, String toolsJson,
                                     String toolChoice, int maxTokens, boolean enableThinking,
                                     float temperature) {
        try {
            JSONObject req = new JSONObject();
            req.put("action", "chat");

            JSONArray msgs = new JSONArray();
            for (ChatMessage m : history) {
                JSONObject msg = new JSONObject();
                msg.put("role", m.role);
                msg.put("content", m.content != null ? m.content : "");
                if (m.toolCalls != null && !m.toolCalls.isEmpty()) {
                    JSONArray tcs = new JSONArray();
                    for (ToolCall tc : m.toolCalls) {
                        // A3：id 必须来自执行工具前的补齐（R4-1），这里不临时生成
                        if (tc.id == null || tc.id.isEmpty()) continue;
                        JSONObject call = new JSONObject();
                        call.put("id", tc.id);
                        call.put("type", "function");
                        JSONObject fn = new JSONObject();
                        fn.put("name", tc.toolName);
                        fn.put("arguments", tc.args.toString());
                        call.put("function", fn);
                        tcs.put(call);
                    }
                    if (tcs.length() > 0) msg.put("tool_calls", tcs);
                }
                if (m.toolCallId != null) {
                    msg.put("tool_call_id", m.toolCallId);
                }
                // REASONING-REPLAY(2026-11-01)：历史 assistant 轮带上原始思考，
                // 模板据此渲染 <think>…</think>（否则只有空块）。
                if (m.reasoningContent != null && !m.reasoningContent.isEmpty()) {
                    msg.put("reasoning_content", m.reasoningContent);
                }
                msgs.put(msg);
            }
            req.put("messages", msgs);

            if (toolsJson != null && !toolsJson.isEmpty()) {
                req.put("tools", new JSONArray(toolsJson));
            }
            req.put("tool_choice", toolChoice);
            req.put("enable_thinking", enableThinking);   // R8-1：调用方传入（Agent 模式默认 false）
            // P0(2026-10-07)：max_tokens 按上下文钳制——生成空间 = n_ctx − prompt实际token − native guard − 模板overhead。
            // 原实现恒发 OUTPUT_RESERVE_TOKENS=4000，而旧 80% 预算下生成空间仅 2454，
            // 长回答必然触发 native "Context full, stopping generation"（native-lib.cpp:3115）
            // 且上层无感知（截断回复被当完整回答交付）。钳制后生成在 max_tokens 处自然停止，
            // 永不撞 ctx_full；即使 Java token 计数低估模板开销，256 下限也能给出短回答而非硬截断。
            int promptTokens = countTokensSafe(serializeHistory(history))
                    + countTokensSafe(toolsJson != null ? toolsJson : "")
                    + TEMPLATE_OVERHEAD_RESERVE;
            // NO_OUTPUT_CAP(2026-11-01)：不再设固定输出上限。原来这里是
            // min(maxTokens, safeMax)，把回答+思考钉死在 2000/4000——思考一写长正文就是空的，
            // 上层看到的是"抱歉，我暂时无法完整回答这个问题"，而不是"答到一半断了"。
            // 现在能给的生成空间就是本次剩余的整个上下文窗口，什么时候收口由模型自己的 EOS
            // 或 native 的 ctx_full/max_tokens 兜底决定（stop cause 会进引擎日志）。
            // ctx 取不到（老 so / 模型未加载）时退回调用方给的数，别算出 0 把回答掐死。
            // PROMPT-DOOR(2026-11-07)：改按**门容量**算（min(nCtxTrain, runtime) − 512），
            // 与 computePromptBudget/promptFitsDoor 同源。只看 runtime 会在训练窗口更小的模型上
            // 承诺门根本不允许的生成量；容量里已扣掉 native guard，不再重复减。
            final int capacity = getSafePromptCapacity();
            int clamped = (capacity > 0)
                    ? Math.max(256, capacity - promptTokens)
                    : Math.max(256, maxTokens);
            req.put("max_tokens", clamped);
            req.put("temperature", temperature);   // R8-2 / USER-PARAMS：由调用方按"决策轮 vs 回答轮"传入（默认均 0.7）。过低(0.6)会让 2B 在工具调用时过度保守，只敢用默认 action/参数；过高则破坏 chatJson 结构化输出稳定性
            // SAMPLING-PREFS(2026-11-01)：top_p/top_k 原先写死 0.9/40，改成走用户参数层
            // （默认值与硬编码逐值相同，未设置时行为不变）。这两个值真正进请求，属于偏好。
            UserModelParamsManager sampling = appContext != null
                    ? UserModelParamsManager.getInstance(appContext) : null;
            req.put("top_p", sampling != null ? sampling.getTopP() : UserModelParamsManager.DEFAULT_TOP_P);
            req.put("top_k", sampling != null ? sampling.getTopK() : UserModelParamsManager.DEFAULT_TOP_K);

            return req.toString();
        } catch (org.json.JSONException e) {
            AILogger.e(TAG, "buildRequestJson failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 从完整文本中分离思考）和回答内容。
     */
    private String[] splitThinkingAndContent(String fullText) {
        if (fullText == null) return new String[]{"", ""};

        // 优先用模板标签（native meta 事件下发），换模型无需改代码；
        // 未收到 meta 事件（老路径）时回退到 <think> 字面量
        // THINKING-TEMPLATE-ONLY(2026-10-09)：思考标签只认 chat template
        // （ThinkingTagConfig，native 从模板推导）。模板未声明思考段时不做切分，
        // 而不是回退到 <think> 这类模型特有字面量 - 那会让换模型时漏检或误切。
        ThinkingTagConfig tags = thinkingTags;
        if (!tags.isAvailable()) {
            return new String[]{fullText, ""};
        }
        final String startTag = tags.getStartTag();
        final List<String> endTags = tags.getEndTags();

        String thinking = "";
        String content = fullText;

        int thinkStart = fullText.indexOf(startTag);
        if (thinkStart < 0) return new String[]{content, thinking};

        int contentStart = thinkStart + startTag.length();
        // 多个结束标签取最早出现者
        int close = -1;
        int closeLen = 0;
        for (String tag : endTags) {
            int p = fullText.indexOf(tag, contentStart);
            if (p >= 0 && (close < 0 || p < close)) {
                close = p;
                closeLen = tag.length();
            }
        }

        if (close >= 0) {
            thinking = fullText.substring(contentStart, close).trim();
            content = (fullText.substring(0, thinkStart) + fullText.substring(close + closeLen)).trim();
        } else {
            // 未闭合：开始标签之后全部视为思考残留
            thinking = fullText.substring(contentStart).trim();
            content = fullText.substring(0, thinkStart).trim();
        }

        return new String[]{content, thinking};
    }

    /** 生成结果：包含正文、思考内容和工具调用 */
    private static class GenerateResult {
        final String content;
        final String reasoning;
        final List<ToolCall> toolCalls;
        final String stopReason;   // P0：native 停止原因（normal/eos/ctx_full/...），chatJson complete 事件回传
        GenerateResult(String content, String reasoning, List<ToolCall> toolCalls) {
            this(content, reasoning, toolCalls, "normal");
        }
        GenerateResult(String content, String reasoning, List<ToolCall> toolCalls, String stopReason) {
            this.content = content;
            this.reasoning = reasoning;
            this.toolCalls = toolCalls;
            this.stopReason = stopReason != null ? stopReason : "normal";
        }
    }

    // ==================== 工具注入与预算守卫 ====================
    // 工具注入策略（2026-09-23 起）：不做意图识别预判注入，完全由 agent 自行注入——
    // 首轮注入常驻核心工具（CORE-TOOLS-4，2026-10-11 起为 time_date/location/ai_weather/
    // network_search，已不含 tool_registry 发现通道）；模型在 FC 循环内输出
    // tool_call 后，被调工具自动加入注入集、下轮起注入其 schema（见 run() 按需动态注入）。
    // 该策略同时保证 tools 前缀字节跨轮稳定（会话级工具集 sessionTools 跨轮保留）。

    /**
     * 计算单次推理的 prompt token 预算。
     *
     * <p>P1(2026-10-07)：预算 = 窗口 − 输出预留 − native guard，取代原来的固定 80% 比例
     * （比例制只约束 prompt，把生成空间压到 2454 &lt; OUTPUT_RESERVE_TOKENS=4000，
     * 长回答必然触发 native "Context full, stopping generation" 且上层无信号）。</p>
     *
     * <p>PROMPT-DOOR(2026-11-07)：预算还必须与 {@code LlamaHelper.chatJson} 的"最后一扇门"
     * <b>同源</b>——那道门按 {@code getSafeContextReference(n_ctx) - 512} 拒收超长 prompt。
     * 原实现在 {@code n_ctx - 4000 - 512 <= 0}（窗口 ≤4512）时回退写死的 4000，
     * <b>比门允许的 3584 还大</b>：裁剪一步都不做，请求发出去必被拒。真机 11-07 16:58
     * 就是这条链——可用内存 1399MB / 模型 1158MB → CONTEXT-DYNAMIC 把 12288 降到 floor 4096
     * → 预算回退 4000 → 门报 "Prompt过长: 3634 >= 3584"。现在小窗口按容量缩输出预留，
     * 预算恒不超过门；真装不下由 {@link #promptFitsDoor} 在发请求前拦下并说清原因。</p>
     */
    private int computePromptBudget() {
        int capacity = getSafePromptCapacity();
        if (capacity <= 0) {
            AILogger.w(TAG, "computePromptBudget: 窗口未知，回退 4000 tokens");
            return 4000;
        }
        return promptBudgetFor(capacity, OUTPUT_RESERVE_TOKENS);
    }

    /**
     * 门允许的最大 prompt token 数：与 {@code LlamaHelper} 各入口的
     * {@code getSafeContextReference(ctx) - GENERATION_RESERVE} 逐值同口径。
     *
     * <p>为什么不能只用 {@link #getEffectiveContextSize()}：门取的是
     * {@code min(nCtxTrain, runtimeCtx)}，训练窗口比运行窗口小的模型上只看 runtime 会高估容量，
     * 照样撞门。NPU 路径不走 llama.cpp 那道门，按规划窗口算。
     * 取不到窗口时返回 0，调用方据此<b>跳过</b>预检，而不是拿猜测值拦请求。</p>
     */
    private int getSafePromptCapacity() {
        try {
            int ctx = getEffectiveContextSize();
            if (ctx <= 0) return 0;
            if (!com.oilquiz.app.ai.engine.NpuAwareText.isNpu()) {
                ctx = LlamaHelper.getSafeContextReference(ctx);
            }
            return Math.max(0, ctx - NATIVE_GENERATION_RESERVE);
        } catch (Throwable t) {
            AILogger.w(TAG, "getSafePromptCapacity failed: " + t.getMessage());
            return 0;
        }
    }

    /**
     * 纯算术（可单测）：预算 = 门容量 − 输出预留。
     * 预留装不下时按容量的 1/3 缩（至少 256），<b>绝不回退到比门还大的固定值</b>——
     * 那等于放弃裁剪，把必然失败的请求发给 native。
     */
    static int promptBudgetFor(int capacity, int outputReserve) {
        if (capacity <= 0) return 4000;
        int budget = capacity - outputReserve;
        if (budget > 0) return budget;
        int reserve = Math.max(256, capacity / 3);
        return Math.max(256, capacity - reserve);
    }

    /**
     * 纯判据（可单测）：prompt 是否装得进门。与门一致用 {@code >=} 判拒
     * （{@code LlamaHelper}: {@code promptTokens >= safeRef - 512} 即拒），
     * 容量未知（≤0）时放行，由门自己兜底。
     */
    static boolean promptFitsDoor(int promptTokens, int capacity) {
        return capacity <= 0 || promptTokens < capacity;
    }

    /**
     * PROMPT-DOOR(2026-11-07)：按门口径核算本轮 prompt，装不下时给出<b>带数字与下一步</b>的原因。
     *
     * <p>分两种装不下，下一步完全不同，不能混成一句"上下文已满"：</p>
     * <ul>
     * <li><b>工具定义本身就超</b>：窗口被内存降档到 floor（真机 4096），而核心工具 schema
     *     加模板开销已逼近门容量——裁历史无济于事，出路是释放内存/换大窗口模型；</li>
     * <li><b>历史+工具定义超</b>：裁剪已到地板（{@code trimHistoryToFit} 保底 4 条不动，
     *     再删会破坏 KV 前缀），出路是清空对话历史或换大窗口模型。</li>
     * </ul>
     *
     * @param detail 附加归因（如撑爆预算的那条工具结果），可为 null
     * @return 装得下返回 null；装不下返回可直接给用户看的原因
     */
    private String describePromptOverflow(List<ChatMessage> history, String toolsJson,
                                          int toolCount, String detail) {
        int capacity = getSafePromptCapacity();
        if (capacity <= 0) return null; // 窗口未知：不拿猜测值拦请求，交给门兜底
        String schema = toolsJson != null ? toolsJson : "";
        int schemaTokens = countTokensSafe(schema) + TEMPLATE_OVERHEAD_RESERVE;
        int promptTokens = countTokensSafe(serializeHistory(history)) + schemaTokens;
        if (promptFitsDoor(promptTokens, capacity)) return null;
        int window = capacity + NATIVE_GENERATION_RESERVE;
        String tail = (detail != null && !detail.isEmpty()) ? detail + "。" : "";
        if (schemaTokens >= capacity) {
            return tail + "上下文窗口只有 " + window + " tokens（可用内存不足时会自动降档），"
                    + "光 " + toolCount + " 个工具定义加模板开销就要约 " + schemaTokens
                    + " tokens，本轮 prompt 共约 " + promptTokens + "，超出安全上限 " + capacity
                    + "。请关掉后台应用释放内存后重进对话，或改用窗口更大的模型/在线模型";
        }
        return tail + "本轮 prompt（历史+工具定义）约 " + promptTokens + " tokens，超出窗口 "
                + window + " 的安全上限 " + capacity + "，历史已裁到最少仍装不下。"
                + "可以清空当前对话历史后重试，或改用窗口更大的模型";
    }

    /**
     * 实际生效的上下文窗口：以"实际加载的 native 上下文"为准（它就是真实 KV 缓存容量，
     * chatCreate 优先取模型 n_ctx，如 Qwen3-4B=32768），预算按它 ×80% 已留足输出余量。
     * 优化模式预设（4096~16384）只是资源旋钮，可能小于真实窗口（预设 8k vs 实际 32k），
     * 若取两者较小者会把预算压到 6554，schema 吃掉 4150 后多轮历史被裁剪过度。
     * 预设仅在实际值不可用时兜底。
     */
    private int getEffectiveContextSize() {
        int preset = aiConfig != null ? aiConfig.getContextSize() : 0;
        // NPU-AWARE(2026-10-07)：原实现只读 LlamaHelper.getContextSize()，NPU 模式下恒为 0
        // → 回退预设或写死的 8192，而 NPU 实际规划出的 nCtx（实测 8192）才是真窗口。
        // 走 NpuAwareText 按引擎取，并用同一份日志保留原有的可观测性。
        int real = 0;
        try {
            if (com.oilquiz.app.ai.engine.NpuAwareText.isNpu()) {
                // NPU-CONTRACT：plannedNCtxValue() 未规划时返回 -1（未知），**不再给猜测值**。
                // 未知时在这里显式触发一次内存规划（幂等，同模型不重复解析 GGUF），
                // 这样拿到的就是真实窗口，而不是把 4096/8192 这类兜底值当成窗口用
                // —— 那会把 Agent 的预算压到装不下工具定义+多轮结果。
                real = com.oilquiz.app.ai.engine.NpuLlmChat.plannedNCtxValue();
                if (real <= 0 && appContext != null) {
                    com.oilquiz.app.ai.engine.NpuLlmChat.planForCurrentModel(appContext);
                    real = com.oilquiz.app.ai.engine.NpuLlmChat.plannedNCtxValue();
                }
            } else {
                real = LlamaHelper.getContextSize();
            }
        } catch (Throwable t) {
            AILogger.w(TAG, "getContextSize failed: " + t.getMessage());
        }
        int ctxSize = real > 0 ? real : preset;
        if (ctxSize <= 0) {
            ctxSize = 8192; // 兜底
        }
        AILogger.i(TAG, "Effective context: preset=" + preset + ", native=" + real + ", using " + ctxSize
                + " [isNpu=" + com.oilquiz.app.ai.engine.NpuAwareText.isNpu()
                + ", npuPlanned=" + com.oilquiz.app.ai.engine.NpuLlmChat.plannedNCtxValue() + "]");
        return ctxSize;
    }

    /**
     * 获取 Agent 最大迭代轮次，与实际生效上下文容量联动。
     */
    private int getAgentMaxIterations() {
        try {
            int ctxSize = getEffectiveContextSize();
            if (ctxSize >= 65536) return 24;
            if (ctxSize >= 32768) return 20;
        } catch (Throwable t) {
            AILogger.w(TAG, "getAgentMaxIterations failed: " + t.getMessage());
        }
        return MAX_ITERATIONS_BASE;
    }

    /**
     * 每轮推理前裁剪历史，使 历史+schema 的 token 总量不超过预算。
     *
     * <p><b>KV-CACHE-STABLE（2026-10-07 实测修复）：本方法必须保持"只增不改"性质。</b>
     * 增量 KV 缓存的前提是"新 prompt 是上一轮 token 序列的前缀超集"——逐 token 比较，
     * 一旦某个位置变了，该位置之后的全部 token 作废（本机 Qwen3.5 属混合架构 SSM，
     * 部分前缀截断 seq_rm 必然失败 → 直接退化成全量重算）。
     *
     * <p>原先这里还有一步"渐进压缩工具结果"：每轮按预算压力把同一条工具结果重裁成
     * 4000/2500/1200。实测后果：同一条旧工具结果随对话增长被反复改短 → 文本变 → token 序列变 →
     * 前缀每轮都断 → <b>每轮全量 prefill</b>，日志表现为
     * {@code Prefill: 4729 tokens in 16.249s}，越聊越慢。
     * 工具结果在入库时已由 {@code MAX_TOOL_RESULT_LENGTH} 一次性定稿，故该步已移除。
     *
     * <p>现在超预算时**只从头部丢弃最旧消息**（丢弃让窗口滑动，剩下的序列仍然稳定且连续），
     * 绝不原地改写已进入历史的消息。
     */
    private List<ChatMessage> trimHistoryToFit(List<ChatMessage> history, String toolsJson, int budgetTokens) {
        int schemaTokens = countTokensSafe(toolsJson);
        int total = countTokensSafe(serializeHistory(history)) + schemaTokens;
        if (total <= budgetTokens) return history;

        AILogger.w(TAG, "Trimming history: " + total + " > " + budgetTokens + " tokens");
        List<ChatMessage> trimmed = new ArrayList<>(history);

        // 超预算：从最旧消息开始，把要丢弃的非工具对话压缩成一条"历史要点"
        // （保留多轮指代上下文，如"那明天呢/多少钱"），而非直接丢弃丢光——
        // 在线引擎用模型摘要历史，本地用轻量要点拼接兜底，保留关键事实。
        // 保留 system + 最新 N 轮（注意不能用 remove(size-2)：那会从中间删，
        // 留下"最旧+最新"两条、丢掉中间较新的上下文）
        List<ChatMessage> evicted = new ArrayList<>();
        while (trimmed.size() > 4 && total > budgetTokens) {
            ChatMessage old = trimmed.remove(1);
            if (old != null) evicted.add(old);
            total = countTokensSafe(serializeHistory(trimmed)) + schemaTokens;
        }
        if (!evicted.isEmpty()) {
            // P2(2026-10-07)：要点一次性生成后缓存复用（compactionSummary 字段，run() 开头重置）。
            // 原实现每轮 trim 都基于当前 evicted 集合重生成要点 → 同一批被裁历史的要点文本
            // 每轮不同（且要点在 index 1 被 remove(1) 删掉后会被递归压缩）→ 前缀稳定性质被破坏。
            // 缓存后：同一批被裁历史逐字节稳定，后续轮次 trim 直接复用同一要点文本。
            if (compactionSummary == null) {
                compactionSummary = buildCompactionSummary(evicted);
            }
            if (compactionSummary != null) {
                trimmed.add(1, new ChatMessage("user", compactionSummary));
                total = countTokensSafe(serializeHistory(trimmed)) + schemaTokens;
            }
        }

        AILogger.i(TAG, "History trimmed to " + trimmed.size() + " messages, " + total + " tokens");
        return trimmed;
    }

    /**
     * P2(2026-10-07)：从被裁消息生成"历史要点"（最新优先，最多 4 条非工具，每条 90 字符）。
     * 结果由 trimHistoryToFit 缓存复用，保证同一批被裁历史的要点文本逐字节稳定。
     * 全是工具/空消息时返回 null（无可压缩的对话内容，调用方不插入）。
     */
    private String buildCompactionSummary(List<ChatMessage> evicted) {
        StringBuilder summary = new StringBuilder("【历史对话要点】(较早对话已压缩)");
        int kept = 0;
        for (int i = evicted.size() - 1; i >= 0 && kept < 4; i--) {
            ChatMessage m = evicted.get(i);
            if ("tool".equals(m.role) || m.content == null || m.content.trim().isEmpty()) continue;
            String c = truncate(m.content, 90);
            summary.append("\n").append("user".equals(m.role) ? "用户" : "助手").append(": ").append(c);
            kept++;
        }
        return kept > 0 ? summary.toString() : null;
    }

    // ==================== Prompt 构建 ====================

    /**
     * 安全拼接本轮 user 末尾的 D 块（日期块、天气直给块、记忆块、任务块）。
     *
     * <p>base 为 null/空时返回去掉前导空行的块本身，而不是 {@code "null\n\n..."}——
     * 字符串相加会把 null 变成字面量 "null" 发给模型，2B 会照抄。</p>
     */
    private static String appendTailBlock(String base, String block) {
        if (block == null || block.isEmpty()) return base;
        if (base == null || base.trim().isEmpty()) {
            return block.startsWith("\n\n") ? block.substring(2) : block;
        }
        return base + block;
    }

    /**
     * PERSONA(2026-11-01)：读取用户人设（{@code AIConfig.system_prompt}）。
     *
     * <p>返回 null 表示"不注入"：未设置、空白，或仍是默认档 {@code 请用中文回答。}——
     * 默认档与角色行里的"用中文"重复，注入只会白吃前缀 token。</p>
     *
     * <p>为什么要有这一层：在线路径（桥接的 buildNativeSystemPrompt）一直用这份设置，
     * 本地 Agent/普通路径此前没读，表现为"设置里写了人设、一开智能助手就失效"。
     * 人设属于稳定段，同一会话内不变，跨轮仍能命中 KV；改了人设才会全量重建前缀。</p>
     */
    private String resolvePersona() {
        return resolvePersonaFor(appContext);
    }

    /**
     * PERSONA(2026-11-01)：静态版本，供桥接的本地普通对话复用同一口径。
     * 返回 null = 不注入（未设置 / 空白 / 默认档"请用中文回答。"）。
     */
    public static String resolvePersonaFor(android.content.Context ctx) {
        try {
            if (ctx == null) return null;
            String p = new com.oilquiz.app.ai.refactor.AIConfig(ctx).getSystemPrompt();
            if (p == null) return null;
            p = p.trim();
            if (p.isEmpty() || "请用中文回答。".equals(p)) return null;
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * TEMPLATE-COMPAT(2026-11-01)：本地普通对话的 system 正文（身份 + 行为规则）。
     * 与本地 Agent 路径同一份文案；桥接路径此前根本没发 system，导致角色/规则/人设全缺。
     */
    public static String buildPlainSystemPrompt(String persona) {
        return buildSystemPrompt(persona);
    }

    /**
     * TEMPLATE-COMPAT：带工具的本地请求要用的 system 正文（与本地 Agent 路径同一份：【角色】+【工具】规则）。
     * 不能拿 {@link #buildPlainSystemPrompt} 顶替——那份明确写"不调用任何工具"。
     */
    public static String buildAgentSystemPrompt(String persona) {
        return buildFcSystemPrompt(persona);
    }

    /**
     * TEMPLATE-COMPAT：本轮末尾的日期块（与引擎路径同一份文案）。
     * 桥接路径此前完全没有日期注入，模型只能靠训练数据猜"今天"。
     */
    public static String buildDateTailBlock(boolean fcMode) {
        return buildCurrentTimeLine(fcMode);
    }

    /**
     * 本轮的日期提醒块（注入在<b>本轮 user 消息末尾</b>，不放 system 头部）。
     *
     * <p>DATE-TAIL(2026-11-01)：这里原来只保证"同一天内 system 字节不变"，跨零点时第一行就变
     * → system 前缀整个失效 → 会话序列虽然逐字节可回放，回放到的也是变了头的 prompt，
     * 真机口径下是一次 ~20s 的全量 re-prefill。挪到本轮末尾后，system 与更早轮次的字节
     * 都跟日期无关，跨天只改最新那条消息的尾巴，前缀继续命中。</p>
     *
     * <p>代价（必须写进模型可见文本里解决）：会话序列会原样回放上一轮的尾巴，于是跨天的
     * prompt 里同时存在两个"【当前真实日期】"，历史里那条是旧快照。所以本块显式声明
     * "以本块为准、历史日期行不作数"，否则 2B 会把上一轮的旧日期当成今天。</p>
     *
     * <p>只到天、不带时分：带分钟就每轮都变，等于把这个块又变成纯动态内容；具体时刻由
     * time_date 工具提供（fcMode）或如实说明（普通对话）。</p>
     *
     * @return 以 "\\n\\n" 开头的块，可直接拼在已有尾巴之前
     */
    private static String buildCurrentTimeLine(boolean fcMode) {
        String today = new SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA).format(new Date());
        String timeHint = fcMode
                ? "问具体时刻时调用 time_date(action=now) 工具获取"
                : "问具体时刻时如实说明只能提供日期，无法给出准确时刻";
        return "\n\n【当前真实日期】" + today + "\n"
                + "用户问日期/星期时直接采用以上日期；" + timeHint + "。不要使用训练数据中的日期。\n"
                + "这是本轮唯一有效日期；对话历史里的【当前真实日期】都是更早轮次的快照，一律不采用。"
                + "本条只用于日期判断，不要复述、解释或反复确认这条规则；思考也用中文，简洁，不要循环自我确认。";
    }

    /**
     * 普通对话模式的 system 提示词。
     *
     * <p>DATE-TAIL(2026-11-01)：日期行原在本方法的第一段，现已从此移到本轮 user 末尾，
     * 本方法不再含任何随时间变化的文本。声明 static 是因为两个 prompt 构建方法本来就是
     * 纯函数（只拼字面量），同包单测因此能在不构造引擎实例、不放宽可见性的前提下
     * 断言"system 里逐字节静态"。</p>
     */
    private static String buildSystemPrompt(String persona) {
        // 极简提示词：意图命中走程序化输出（不经模型），意图未命中走普通对话
        // （模型直接回答、不注入工具），因此提示词只保留身份，
        // 工具调用规则/回答格式等指令段全部移除（模型已不再调用工具）。
        StringBuilder sb = new StringBuilder();
        // ---- 本地普通对话模式：纯聊天答疑角色 ----
        sb.append("【角色】\n");
        // PERSONA(2026-11-01)：有用户人设就用它替换内置身份句；工作/方式/风格三条行为规则保留，
        // 避免"只改身份"把不编造、口语化、先结论这些约束一起丢掉。
        sb.append(persona != null ? persona + "\n"
                : "你是答题宝App中的AI聊天助手，是App内\"AI对话\"功能模块的助手。\n");
        sb.append("你的工作：与正在使用答题宝的用户进行自然对话，解答学习、生活、技术等各类问题，给出清晰有用的回答。\n");
        sb.append("你的方式：不调用任何工具，完全依靠自身知识回答；不确定时如实说明，不编造。\n");
        // TOOL-HISTORY-HONESTY(2026-11-08)：历史里可能还留着早前工具模式的 tool_call/tool 结果。
        // 本轮工具不可用，模型不能把历史结果当成“我刚调用过”，否则 T133 会编造 calculate 调用。
        sb.append("历史里可能还有更早轮次的工具记录；本轮工具不可用，不要声称调用过任何工具，"
                + "也不要复述工具过程；只依据历史结论回答。\n");
        sb.append("你的风格：用中文，简洁自然、口语化，像熟悉的朋友聊天，先给结论再补细节。\n\n");

        return sb.toString();
    }

    /**
     * FC 模式的 system 提示词。
     *
     * <p>设计目标：prompt 尽量小（首轮 FULL 更小、KV 更省），工具的能力与参数以 schema
     * （{@code buildToolsJson} 注入完整 description + enum）为准，system 只补 schema 表达不了的
     * <b>跨工具选用规则</b>。</p>
     *
     * <p>与 2026-10-07 版的差别：当时工具集是 9 个且依赖模型自主发现，故删掉了【规则】段
     * （见文末 RULES-REMOVED）。CORE-TOOLS-4（2026-10-11）把工具集定为 4 个之后，规则段重新
     * 变得必要：schema 只能描述"单个工具怎么用"，无法表达"四个工具之间怎么选"、
     * "只准用这四个"、"能力边界外的请求怎么办"。因此当前版本有 4 行规则，
     * <b>不再是"无规则段的最小版"</b>。</p>
     */
    private static String buildFcSystemPrompt(String persona) {
        StringBuilder sb = new StringBuilder();
        // DATE-TAIL(2026-11-01)：日期行原在此处第一段，已移到本轮 user 末尾（见
        // buildCurrentTimeLine 注释）——留在头部时跨零点会让整个 system 前缀失效。
        // PROMPT-ALIGN(2026-11-01)：段落骨架与在线提示词（golden/assist.txt 前 6 行）逐段对齐，
        // 但只写本地真有的能力；正文从 1012 字符压到 ~460 字符。原稿三处互斥一并修掉：
        //  ① "实时信息必须用工具"与"日期不必调工具"与 time_date 仍列在工具清单里 —— 同一件事
        //     三句相邻指令，2B 在思考阶段来回解释；现在收成一句确定句（日期永远用上面那行）。
        //  ② "你的边界"写着"这些工具都是只读查询、无副作用"，可清单里明明有 write_file ——
        //     在线那句"覆盖/删除先征得确认"对本地是适用的（早先按 4 工具时代删掉了）。
        //  ③ "可建议用户换用在线模式"：本地模型没有切模式的手，只会产出推诿，删掉。
        // 另外原来"工具选择"整段（~320 字）在复述 9 个工具的 schema description，而 schema
        // 本来就在同一请求里，删掉；只保留 schema 表达不了的跨工具规则（天气给法、上级地名、
        // 失败重调）。至此方法内全部是静态字面量：不随天、不随会话、不随工具集变化，
        // system 前缀因此跨天也逐字节不变（日期行改由本轮末尾注入，见 buildCurrentTimeLine）。
        sb.append("【角色】\n");
        // PERSONA(2026-11-01)：用户人设替换内置身份句，行为要求（实时信息必须用工具、写前确认、
        // 不编造、先结论）保留——这些是工具协议的一部分，不能因为换了人设就丢。
        if (persona != null) {
            sb.append(persona).append('\n');
        } else {
            sb.append("你是手机App里帮用户解决问题的助手：");
        }
        sb.append("能直接答就直接答，天气/新闻/时刻这类实时信息必须用工具查，不要凭记忆答；"
                + "写入或覆盖文件前先征得用户确认；工具没返回的数据不要编。用中文，先结论后细节。\n");
        // TOOL-HISTORY-HONESTY(2026-11-08)：历史回放里有更早轮次的 tool_call/tool 结果，
        // 模型会把它当成“本轮已经调用过”。明确区分“历史工具结果”和“本轮工具结果”，
        // 本轮没有 tool 结果就必须重新调用或如实说没查到，不能声称已调用。
        sb.append("历史里出现过的工具调用/结果只属于更早轮次，不代表本轮；"
                + "本轮没有工具结果时，不要声称已经调用过工具，需要就重新调用。\n");
        sb.append("【工具】直接调用的是本次注入的这些工具；需要本次没注入的能力时，先用 tool_registry"
                + "（get 看参数、call 代执行，call 的参数里写工具名是允许的）。"
                // TOOL-CHOICE(2026-11-02)：真机思考日志实锤——2B 把"探索工具"理解成"上网搜工具"，
                // 连续 7 次 network_search(action=search, keyword="tool registry …")，把搜到的
                // 无关网页（Fusion 360 MCP）当成工具清单，还编出 tool_registry(action=read_url)。
                // 原因：原文只写"缺能力时用 discovery"，没禁止用搜索找工具，也没给"了解工具"的出口。
                // 补一句否定 + 给出正确出口，别让模型去猜参数名。
                + "想了解有哪些工具、或某个工具怎么填参数，直接用 tool_registry(list/get)，"
                + "不要用 network_search 搜工具名或工具文档——工具只来自本次注入的清单。"
                // MCP(2026-11-04)：本地不改静态工具集，统一经 tool_registry 发现 mcp_toolbox；
                // 工具箱内部再 search/get/call 外部 MCP 服务，避免中途改 tools 前缀触发 re-prefill。
                + "需要外部服务（MCP）能力时，用 tool_registry 找 mcp_toolbox（search/get/call），不要联网搜 MCP。"
                // WORKSPACE/MEMORY(2026-11-01)：这两个工具此前只在 schema 里，模型不会主动想到用。
                // 一句规则各给一次触发场景，避免它绕道 read_file 猜工作区路径、或把偏好当闲聊丢掉。
                + "生成或改过的文件都在工作区：列文件/读内容/看历史版本并回退用 workspace（工作区相对路径别拿别的读取工具瞎猜）。"
                // READ-PAGE(2026-11-02)：给"一次看不完"的续读出口——模型以前只会拿到被截断的结果，
                // 然后用训练知识补全（幻觉）。现在明确告诉它分页参数。
                + "内容一次看不完就分页续读：read_file（带 start_line/end_line，从 1 开始、每段≤200 行），"
                + "读完一段再读下一段，不要凭猜测补齐。"
                + "搜索：默认用 ask 拿结论（一般提问它就够），只有要一批来源/链接/原文时才用 search 等其它 action；"
                + "找论文/文库/图片/播客要带 scope（scholar/document/image/video/podcast，默认只搜网页）。"
                + "秘塔按次计费（一次调用一次额度，与返回条数无关）：一次就把 limit 给够、把问题问清，不要连续换词重搜。"
                + "只有 ask/search/read_url（含 search_and_read、smart_search 的搜索那一步）扣额度，"
                + "其余抓网页类的 action 是本地解析、不花钱；"
                + "summarize/smart_read 要带 results（上一步 search 的数组或它的 JSON 字符串都行），光填 query 没用。"
                + "ask 出答案要几十秒是正常的，慢不是失败，别因为等得久就重试。"
                // SEARCH-PAGE-HOLD(2026-11-02)：真机实测 search 接口对 page 参数
                // 返回 {"errCode":1000,"errMsg":"请求参数错误"}（连 page=0 也被拒），不带才 200，
                // 所以**没有翻页能力**，schema 里的 page 参数已摘掉；这里不再教模型翻页，
                // 只给它唯一的出口：一次多要几条（上限 50）。
                + "结果不够就调大 limit（最多 50）再搜一次，这个接口没有翻页，别找第 2 页。"
                // READ-PAGE：网页正文同理按字符续读（read_url 与 webpage_reader 都支持），
                // 服务端读不了的页面（返回服务错误码）就换一个来源或改用 webpage_reader。
                + "网页正文太长就用 start_char/max_chars 分段续读，读完一段再读下一段。"
                + "用户说\u201c记住\u201d或告知姓名、住址、偏好等稳定信息时用 memory 保存，需要回忆时用 memory 读。"
                // WHERE-AM-I(2026-11-07)：定位工具此前不在本地核心集，"我在哪"被模型拿
                // ai_weather（空 city 会走系统定位）和 network_search 代查，最后答"无法得知你
                // 具体在哪里"。location 现已注入，这里补一句跨工具选用规则，避免 4B 绕回天气/搜索。
                + "问\u201c我在哪/当前位置/这是哪\u201d用 location：action=get_current 拿经纬度+城市+地址"
                + "（只要城市名用 get_city），不要用 ai_weather 或 network_search 代查，也不要用天气结果猜位置。"
                + "问天气用 ai_weather：用户说了城市就传 city，没说就不传（系统自动用手机定位）。"
                + "地名换算不出位置时先换成它所属的县市重查，不知道上级是什么就用 network_search"
                + "搜\u201c<地名>属于哪个县市\u201d；还不行就如实说查不到，"
                + "不要拿别的城市的数据顶上，也不要改用自己的记忆编一个结果。\n\n");
        return sb.toString();
    }

    // ==================== 位置缓存（供 ai_weather 缺位置时兜底） ====================

    /** 位置缓存有效期（毫秒）：10 分钟内不重复定位 */
    private static final long LOCATION_CACHE_TTL_MS = 10 * 60 * 1000L;
    /** 位置获取超时（毫秒）：定位慢不能拖慢每条消息 */
    private static final long LOCATION_FETCH_TIMEOUT_MS = 3000;

    private volatile String cachedLocation;
    private volatile long cachedLocationTime;
    /** 缓存坐标（与 cachedLocation 同 TTL）：ai_weather 缺 city/lat/lon 时用真实经纬度
     *  直接查询，绕开和风 geo/city lookup（该端点无 JWT 权限时 403，此前错误兜底北京） */
    private volatile double cachedLat = 0;
    private volatile double cachedLon = 0;

    /** 获取位置（缓存+超时+权限检查）：无权限/失败/超时返回 null 静默跳过。
     *  同时缓存经纬度（cachedLat/cachedLon），供 ai_weather 缺坐标时直接按经纬度查询 */
    private String getCachedLocation() {
        long now = System.currentTimeMillis();
        if (cachedLocation != null && now - cachedLocationTime < LOCATION_CACHE_TTL_MS) {
            return cachedLocation;
        }
        // 先读主界面天气组件的定位缓存（weather_location_cache.xml，同源共享）：
        // 主界面已定位（银川 38.42,106.26），Agent 直接复用，避免重复定位/无权限兜底失败。
        try {
            android.content.SharedPreferences locPrefs = appContext.getSharedPreferences(
                    "weather_location_cache", android.content.Context.MODE_PRIVATE);
            long ts = locPrefs.getLong("cached_timestamp", 0);
            long age = ts > 0 ? (now - ts) : Long.MAX_VALUE;
            if (age < 30 * 60 * 1000L) {
                double lat = Double.longBitsToDouble(locPrefs.getLong("cached_lat", 0));
                double lon = Double.longBitsToDouble(locPrefs.getLong("cached_lon", 0));
                String city = locPrefs.getString("cached_city", "");
                if (lat != 0 || lon != 0) {
                    cachedLat = lat;
                    cachedLon = lon;
                    AILogger.i(TAG, "ai_weather: using banner location cache lat/lon=" + lat + "," + lon
                            + (city != null && !city.isEmpty() ? " city=" + city : ""));
                    if (city != null && !city.isEmpty() && !"当前位置".equals(city)) {
                        cachedLocation = city;
                        cachedLocationTime = now;
                        return city;
                    }
                    return "当前位置";
                }
            }
        } catch (Throwable t) {
            AILogger.w(TAG, "Banner location cache read failed: " + t.getMessage());
        }
        // 无权限时不尝试（避免触发权限请求打断对话）
        try {
            if (appContext == null) return null;
            boolean fine = appContext.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            boolean coarse = appContext.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (!fine && !coarse) return null;
        } catch (Throwable t) {
            return null;
        }
        final java.util.concurrent.atomic.AtomicReference<String> holder = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread t = new Thread(() -> {
            try {
                // get_current 返回完整位置（city + latitude + longitude），一次获取全缓存
                java.util.Map<String, Object> params = new java.util.HashMap<>();
                params.put("action", "get_current");
                // 内部环境感知取位：不该往聊天流挂卡片
                AIToolResult r;
                com.oilquiz.app.ai.agent.ToolResultCardBinder.BridgeScope uiScope =
                        com.oilquiz.app.ai.agent.ToolResultCardBinder.suppress();
                try {
                    r = toolManager.executeTool("location", params);
                } finally {
                    uiScope.close();
                }
                if (r != null && r.isSuccess() && r.getResult() != null) {
                    String resultStr = String.valueOf(r.getResult());
                    holder.set(resultStr);
                    // 从结果中解析 city 与坐标（LocationTool 返回 JSON 格式）
                    try {
                        org.json.JSONObject loc = new org.json.JSONObject(resultStr);
                        if (loc.has("city")) {
                            holder.set(loc.optString("city", ""));
                        }
                        if (loc.has("latitude") && loc.has("longitude")) {
                            cachedLat = loc.optDouble("latitude", 0);
                            cachedLon = loc.optDouble("longitude", 0);
                            AILogger.i(TAG, "Cached location lat/lon: " + cachedLat + "," + cachedLon);
                        }
                    } catch (Exception ignored) {
                        // 非 JSON（兼容旧格式），仅缓存原串
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                synchronized (done) { done.set(true); done.notifyAll(); }
            }
        }, "agent-loc-fetch");
        t.setDaemon(true);
        t.start();
        synchronized (done) {
            try {
                long waitMs = LOCATION_FETCH_TIMEOUT_MS;
                while (!done.get() && waitMs > 0) {
                    long start = System.currentTimeMillis();
                    done.wait(Math.min(waitMs, 500));
                    waitMs -= (System.currentTimeMillis() - start);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String loc = holder.get();
        if (loc != null && !loc.isEmpty()) {
            cachedLocation = loc;
            cachedLocationTime = now;
        }
        return loc;
    }

    /**
     * 按工具名构建 tools schema（喂给模型，渲染在 prompt 前缀）。
     *
     * <p><b>TOOL-SCHEMA-STABLE（2026-10-07）：工具名必须先排序再构建。</b>
     * 该 JSON 渲染在 prompt 前缀（见本类"tools 渲染在 system 前缀，跨轮集合一致 → system
     * 字节稳定 → KV 前缀可增量"的设计约定）。只要顺序变了，前缀即失效，增量 KV 退化成全量
     * prefill。上游 {@code selectedTools} / {@code activeTools} 来自意图识别、工具选择与
     * 动态注入，顺序没有契约保证，故在此显式排序兜底——排序后无论上游如何重排，输出一致。</p>
     */
    private String buildToolsJson(List<String> toolNames) {
        JSONArray tools = new JSONArray();
        int schemaTokens = 0;
        java.util.List<String> ordered = new java.util.ArrayList<>(toolNames);
        java.util.Collections.sort(ordered);
        for (String name : ordered) {
            // 本地薄工具：单用途极简 schema（无 action enum，只留必要参数），
            // 前缀更短 + 4B 无可选项可选错。底层调用在 executeToolSafely 翻译回真实工具。
            ThinTool thin = LOCAL_THIN_TOOLS.get(name);
            if (thin != null) {
                try {
                    JSONObject tool = new JSONObject();
                    tool.put("type", "function");
                    JSONObject function = new JSONObject();
                    function.put("name", name);
                    function.put("description", thin.desc);
                    JSONObject params = new JSONObject();
                    params.put("type", "object");
                    JSONObject props = new JSONObject();
                    JSONArray required = new JSONArray();
                    for (String[] p : thin.params) {
                        JSONObject prop = new JSONObject();
                        prop.put("type", p[1]);
                        prop.put("description", p[2]);
                        props.put(p[0], prop);
                        if ("1".equals(p[3])) required.put(p[0]);
                    }
                    params.put("properties", props);
                    if (required.length() > 0) params.put("required", required);
                    function.put("parameters", params);
                    tool.put("function", function);
                    schemaTokens += countTokensSafe(tool.toString());
                    tools.put(tool);
                } catch (Exception e) {
                    AILogger.w(TAG, "Build thin tool JSON failed: " + name);
                }
                continue;
            }
            // resolveToolDefinition 支持静态定义 + 动态工具反射生成
            ToolDefinition def = toolManager.resolveToolDefinition(name);
            if (def == null) continue;
            LocalSchemaSlim slim = LOCAL_SCHEMA_SLIM.get(name);
            try {
                JSONObject tool = new JSONObject();
                tool.put("type", "function");
                JSONObject function = new JSONObject();
                function.put("name", def.getName());
                // 完整描述注入：不截断，让本地模型完整理解每个工具的能力边界。
                // SCHEMA-SLIM：命中裁剪表的工具用本地专用短描述，去掉只有引擎层需要的内容
                // （lat/lon 别名、二选一冲突、逐 action 的返回字段清单）。
                function.put("description", slim != null ? slim.desc : def.getDescription());
                JSONObject params = new JSONObject();
                params.put("type", "object");
                JSONObject props = new JSONObject();
                JSONArray required = new JSONArray();
                if (slim != null) {
                    // 按裁剪表声明的顺序注入（不是定义的顺序）：前缀字节稳定依赖这里确定性。
                    java.util.Map<String, ParamDefinition> byName = new java.util.LinkedHashMap<>();
                    for (ParamDefinition p : def.getParameters()) byName.put(p.getName(), p);
                    for (String[] keep : slim.keepParams) {
                        ParamDefinition p = byName.get(keep[0]);
                        if (p == null) continue; // 定义里已不存在：跳过，不报错（裁剪表不是权威清单）
                        JSONObject prop = new JSONObject();
                        prop.put("type", p.getType());
                        prop.put("description", keep[1].isEmpty() ? p.getDescription() : keep[1]);
                        if (p.getEnumValues() != null && !p.getEnumValues().isEmpty()) {
                            prop.put("enum", new JSONArray(p.getEnumValues()));
                        }
                        props.put(p.getName(), prop);
                        if (p.isRequired()) required.put(p.getName());
                    }
                } else {
                    for (ParamDefinition p : def.getParameters()) {
                        JSONObject prop = new JSONObject();
                        prop.put("type", p.getType());
                        prop.put("description", p.getDescription());
                        // 不输出 default：避免引导模型只用默认参数（如 action 固定为 current）
                        // 枚举值必须写入 schema：否则模型只能猜 action/type 等枚举参数，
                        // 2B 模型容易只用第一个值（如 ai_weather 只用 current）
                        if (p.getEnumValues() != null && !p.getEnumValues().isEmpty()) {
                            prop.put("enum", new JSONArray(p.getEnumValues()));
                        }
                        props.put(p.getName(), prop);
                        if (p.isRequired()) required.put(p.getName());
                    }
                }
                params.put("properties", props);
                if (required.length() > 0) params.put("required", required);
                function.put("parameters", params);
                tool.put("function", function);

                // schema token 统计（仅日志，不再因预算裁掉工具——保证注入的工具数量完整）
                schemaTokens += countTokensSafe(tool.toString());
                tools.put(tool);
            } catch (Exception e) {
                AILogger.w(TAG, "Build tool JSON failed: " + name);
            }
        }
        return tools.toString();
    }

    /**
     * 供 token 计数用的历史文本（<b>不进请求字节</b>，请求由 buildRequestJson 另行构造，
     * 所以这里补字段不会动 KV 前缀）。
     *
     * <p>PROMPT-DOOR(2026-11-07)：必须数<b>门会数的东西</b>。原实现只拼 {@code role: content}，
     * 而 {@code LlamaHelper.estimateChatJsonPromptTokens} 还数 assistant 的
     * {@code tool_calls[].function.arguments}，模板另外渲染 {@code reasoning_content}。
     * 多轮工具对话里这两项能占几百到上千 token，漏数就会出现"引擎说装得下、门拒收"：
     * 真机 11-07 16:58 引擎一步没裁，门报 3634 &gt;= 3584。</p>
     */
    private String serializeHistory(List<ChatMessage> history) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : history) {
            sb.append(m.role).append(": ").append(m.content).append("\n");
            if (m.toolCalls != null) {
                for (ToolCall tc : m.toolCalls) {
                    if (tc == null) continue;
                    if (tc.toolName != null) sb.append(tc.toolName).append(' ');
                    if (tc.args != null) sb.append(tc.args.toString()).append('\n');
                }
            }
            if (m.reasoningContent != null && !m.reasoningContent.isEmpty()) {
                sb.append(m.reasoningContent).append('\n');
            }
        }
        return sb.toString();
    }

    // ==================== 提示词泄漏防护 ====================

    /**
     * 系统提示词的特征片段。LEAK-FILTER(2026-11-01)：这里**只保留确实会被注入模型的片段**——
     * `【回答】`、"系统会自动执行你发起的工具调用"、"严禁自问自答、重复已说过的内容" 三条全仓库
     * 只存在于本数组里（没有任何 prompt 注入它们），已删；留着等于放三条永不生效的判据，
     * 还会让下一个读代码的人以为提示词里有这些话。
     */
    private static final String[] PROMPT_LEAK_MARKERS = {
            "【规则】", "【环境上下文】",
            "你是答题宝AI助手", "你是一个智能AI助手，可以使用工具", "你是一个智能AI助手，拥有多种工具",
    };

    /** 判定为"正文基本就是提示词本身"的命中占比阈值 */
    private static final int PROMPT_LEAK_RATIO_PCT = 30;

    /**
     * 检测模型是否把 system prompt 当回答吐出来（泄漏）。
     *
     * <p>旧实现是"正文任意位置含一个标题就算泄漏"，代价很严重：{@code 【环境上下文】} 这类标题
     * 是**我们自己注入**的（{@code ModelExecutionBridge}/{@code AIService}/{@code AIChatActivity}），
     * 小模型在思考或收尾时复述一次标题、或回答开头带上这行，整条回答就被丢掉，用户看到的是
     * "抱歉，我暂时无法完整回答这个问题"。现在只在两种情况下判泄漏：①正文**以**标记开头；
     * ②命中标记的字符总量占正文 ≥{@link #PROMPT_LEAK_RATIO_PCT}%。两种情况都打 WARN，
     * 带命中的标记、占比与正文前 40 字——不记原因的话，线上就只能看到那句兜底话。</p>
     */
    private boolean isPromptLeakage(String text) {
        if (text == null || text.isEmpty()) return false;
        final String head = text.trim();
        int hitChars = 0;
        String worst = null;
        for (String marker : PROMPT_LEAK_MARKERS) {
            if (head.startsWith(marker)) {
                AILogger.w(TAG, "PROMPT-LEAK：正文以提示词片段开头 marker=" + marker
                        + " 正文前40字=" + truncate(text, 40));
                return true;
            }
            int idx = 0, n = 0;
            while ((idx = text.indexOf(marker, idx)) >= 0) { n++; idx += marker.length(); }
            hitChars += n * marker.length();
            if (n > 0 && (worst == null || n * marker.length() > worst.length())) worst = marker;
        }
        int pct = text.length() == 0 ? 0 : hitChars * 100 / text.length();
        if (hitChars == 0 || pct < PROMPT_LEAK_RATIO_PCT) return false;
        AILogger.w(TAG, "PROMPT-LEAK：提示词片段占正文 " + pct + "%（阈值 "
                + PROMPT_LEAK_RATIO_PCT + "%）marker=" + worst
                + " 正文前40字=" + truncate(text, 40));
        return true;
    }

    // ==================== 响应清理 ====================

    private String cleanResponse(String response) {
        if (response == null) return "";
        // 思考段已由 stripThinkingSections 按模板标签剥离，此处不再按
        // <think>/<thought> 字面量二次清理（标签一律取自模板）。
        String cleaned = stripThinkingSections(response);
        // ② 未闭合的 tool_call：只吞"开标签 + 紧随的 JSON"，标签之后的正文保留。
        //    旧实现是正则删到文本结尾——模型把工具调用写法写进正文（示例/文档）或漏闭标签时，
        //    后面的整段回答会被一起吃掉（真机实测某轮 native 产出 1508 字，落屏只剩 577）。
        cleaned = stripUnclosedToolCalls(cleaned);
        cleaned = cleaned
                .replaceAll("(?s)<tool_response>.*?</tool_response>", "")
                // ① 剥闭合的 tool_call / tool_calls 标签（含内容）
                .replaceAll("(?s)<tool_calls?>.*?</tool_calls?[^>]*>", "")
                // ③ 兜底：只删标签壳（历史/嵌套场景残留）
                .replaceAll("(?s)<tool_calls?[^>]*>", "")
                .replaceAll("(?s)</tool_calls?[^>]*>", "")
                // ④ 剥无标签的裸 tool_call JSON（模型把 {"name":"..","arguments":{..}} 直接当正文输出时）
                .replaceAll("(?s)\\{\\\"name\\\"\\s*:\\s*\\\"[^\\\"]+\\\",\\s*\\\"arguments\\\"\\s*:\\s*\\{.*?\\}\\s*\\}", "")
                // 剥 ChatML 标记（模型偶尔输出模板前缀 <|im_start|>assistant / <|im_end|>）
                .replaceAll("<\\|im_start\\|>\\s*assistant", "")
                .replaceAll("<\\|im_start\\|>", "")
                .replaceAll("<\\|im_end\\|>", "")
                .trim();
        // 去掉模型可能重复输出的"用户:"/"assistant:"等对话角色前缀，防止自问自答式续写
        cleaned = cleaned.replaceAll("(?i)^(user|assistant|system|用户|助手)\\s*[:：]\\s*", "");
        return cleaned.trim();
    }

    /**
     * 正文对账：native 原文 vs 清理后落屏文本。只在**掉字**时打一条 W，
     * 用来定位"吃正文"发生在哪一步（清理规则吞掉多少、是不是 tool_call/tool_response 段）。
     */
    private void logAnswerReconcile(String stage, String raw, String clean) {
        if (raw == null) return;
        int c = clean == null ? 0 : clean.length();
        if (raw.length() <= c) return;
        AILogger.w(TAG, "answer reconcile[" + stage + "]: raw=" + raw.length()
                + ", clean=" + c + ", dropped=" + (raw.length() - c)
                + (raw.contains("<tool_call") ? " (含 <tool_call>)" : "")
                + (raw.contains("<tool_response>") ? " (含 <tool_response>)" : ""));
    }

    /**
     * 剥掉**未闭合**的 tool_call 段：只吞"开标签 + 紧随的 JSON 对象"，标签之后的正文保留。
     *
     * <p>旧实现用正则把开标签之后删到文本结束：模型在正文里写工具调用示例（说明文档里写出
     * 调用格式）或漏了闭标签时，标签后面的整段回答会被一起吃掉——真机实测某轮 native 产出
     * 1508 字，最终消息只剩 577 字。</p>
     *
     * <p>闭合段仍交给后续正则；这里只处理找不到闭标签的开标签。JSON 用花括号配平消费：
     * 配平成功就只删"标签 + JSON"；JSON 被截断（配不平）才删到结尾，避免留半截 JSON；
     * 标签后面根本没有 JSON 时只删标签，正文一字不动。</p>
     */
    private static String stripUnclosedToolCalls(String text) {
        if (text == null || text.indexOf("<tool_call") < 0) return text;
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int open = text.indexOf("<tool_call", i);
            if (open < 0) {
                out.append(text, i, text.length());
                break;
            }
            out.append(text, i, open);
            int tagEnd = text.indexOf('>', open);
            if (tagEnd < 0) {
                out.append(text, open, text.length());
                break;
            }
            // 有闭合标签的段交给闭合规则处理，原样保留
            if (text.indexOf("</tool_call", tagEnd) >= 0) {
                out.append(text, open, tagEnd + 1);
                i = tagEnd + 1;
                continue;
            }
            int j = tagEnd + 1;
            while (j < text.length() && Character.isWhitespace(text.charAt(j))) j++;
            if (j < text.length() && text.charAt(j) == '{') {
                int depth = 0, end = -1;
                boolean inStr = false, esc = false;
                for (int k = j; k < text.length(); k++) {
                    char c = text.charAt(k);
                    if (inStr) {
                        if (esc) esc = false;
                        else if (c == '\\') esc = true;
                        else if (c == '"') inStr = false;
                        continue;
                    }
                    if (c == '"') { inStr = true; continue; }
                    if (c == '{') depth++;
                    else if (c == '}') {
                        depth--;
                        if (depth == 0) { end = k + 1; break; }
                    }
                }
                // 配平成功：只吞标签+JSON；配平失败（JSON 被截断）：删到结尾
                i = end > 0 ? end : text.length();
            } else {
                // 标签后没有 JSON：只删标签本身，正文保留
                i = tagEnd + 1;
            }
        }
        return out.toString();
    }

    /**
     * 循环剥离思考段（含未闭合、空格分隔的 Qwen  thinking... response 格式）。
     * 复用 splitThinkingAndContent 的模板标签逻辑；多段思考循环剥离，最多 8 段。
     */
    private String stripThinkingSections(String text) {
        if (text == null || text.isEmpty()) return "";
        String s = text;
        for (int guard = 0; guard < 8; guard++) {
            String[] parts = splitThinkingAndContent(s);
            String content = parts[0];
            if (content.equals(s)) return content;   // 无思考段，剥离完成
            s = content;
            if (content.trim().isEmpty()) break;
        }
        return s;
    }

    /**
     * 将已生成的最终答案分块推送给 UI，模拟打字机效果。
     * 不发起第二次 LLM 生成，避免"基于上下文生成最终回复"式元提示诱发自问自答。
     */
    private void streamDirectAnswer(String answer) {
        if (callback == null || answer == null || answer.isEmpty()) return;
        int chunkSize = 4;
        for (int i = 0; i < answer.length(); i += chunkSize) {
            int end = Math.min(i + chunkSize, answer.length());
            callback.onToken(answer.substring(i, end));
        }
    }

    // ==================== 思考提取 ====================

    private String extractThought(String response) {
        if (response == null) return null;
        // THINKING-TEMPLATE-ONLY(2026-10-09)：只认 chat template 的思考标签。
        // 模板未声明思考段 -> 认为模型无思考段，返回 null（不回退到 <think>/<thought>）。
        ThinkingTagConfig tags = thinkingTags;
        if (!tags.isAvailable()) return null;
        return extractBetweenTags(response, tags.getStartTag(), tags.getEndTags());
    }

    /**
     * 按模板标签提取首个思考段内容（不含标签本身）。
     * 多个结束标签取最早出现者；未闭合时取开始标签到结尾。
     */
    private String extractBetweenTags(String text, String startTag, List<String> endTags) {
        if (text == null || text.isEmpty() || startTag.isEmpty()) return null;
        int start = text.indexOf(startTag);
        if (start < 0) return null;
        int contentStart = start + startTag.length();
        int close = -1;
        for (String tag : endTags) {
            if (tag.isEmpty()) continue;
            int p = text.indexOf(tag, contentStart);
            if (p >= 0 && (close < 0 || p < close)) close = p;
        }
        String thought = (close < 0)
                ? text.substring(contentStart)
                : text.substring(contentStart, close);
        thought = thought.trim();
        return thought.isEmpty() ? null : thought;
    }

    // ==================== 工具调用解析（已移交 native） ====================
    //
    // LOCAL-REGEX-REMOVED(2026-10-09)：本段原先是一整套「从模型文本里用正则猜工具调用」的
    // 解析器（4 套形态：<tool_call>{JSON}、<function=X>+<parameter=Y>、裸 JSON、思考标签），
    // 与 AgentService 的 11 套正则功能重复。
    //
    // 为什么删除：它是 makeChatParserParams 未把模板 PEG parser 传给 common_chat_parse
    // 那个 bug 的产物 —— native 当时解析不出任何工具调用，才必须在 Java 侧用正则补救。
    // 该 bug 已修，native 现在按各模型自己的模板语法解析并下发 tool_call 事件，是唯一权威来源。
    // 保留宽松正则兜底的害处：native 一旦退化，兜底会把畸形输出「猜」成工具调用，静默掩盖故障。
    //
    // 在线模型不经过本类（走 OpenAI 原生 tool_calls → OnlineInferenceService.ToolCallInfo），
    // 故本段删除不影响在线路径。

    /** 工具调用 id（可能缺失，缺失时上层执行前补齐 R4-1） */
    /** 兼容 arguments(JSON 对象/JSON 字符串) / parameters / args 三种取值 */
    /** strftime 格式（%Y/%m/%d/%A 等）→ Java SimpleDateFormat 格式 */
    /** 程序拼当前时间文本（format 支持 strftime 或 Java 格式，null 用默认） */
    /** 工具失败时的替代工具映射：引导模型换工具而非放弃（4B 模型需要明确指引）
     *
     *  <p>CORE-TOOLS-4(2026-10-11)：备选**只能给核心 4 个之内**的工具
     *  （time_date / location / ai_weather / network_search）。
     *  原因：native PEG 语法把工具名按本次请求的 tools 数组逐字枚举
     *  （chat-peg-parser.cpp atomic(literal(name))），没注入 schema 的名字模型生成不出来；
     *  建议一个调不动的工具，结果是模型把工具调用写成正文、整轮退化 —— 比不给建议更糟。
     *  因此 file_reader / python_* / excel_tool / image_gen / smart_research 等原备选已删除。</p> */
    private static String getAlternativeTool(String toolName) {
        return getAlternativeTool(toolName, null);
    }

    /**
     * ALT-ACTION(2026-11-02)：按 action 细化的失败建议。
     *
     * <p>同一个工具的失败模式不同，补救也不同：{@code network_search} 的 search 失败
     * 应换关键词/调大 limit，而 read_url 失败是"这一页秘塔读不了"，应改用
     * {@code webpage_reader}（Jsoup 直抓）或换来源。只按工具名给建议会给出无关指引
     * （真机：读网页失败 → 提示"用 ai_weather 查天气"）。</p>
     */
    private static String getAlternativeTool(String toolName, String action) {
        if (toolName == null) return null;
        switch (toolName) {
            // 措辞与 system prompt / ai_weather.whenToUse 的"位置参数"规则保持一致：
            // 用户所在城市 = 不带 city（由 fillWeatherLocation 自动补手机定位经纬度）；
            // 外地城市 = 必须显式传 city。不要在这里出现"先调 location"这类多烧一轮的说法。
            // SCHEMA-SLIM(2026-11-01)：lat/lon 已从本地 schema 摘掉，这里不能再提——
            // 这是**回给模型的观察文本**，提一个模型看不见的参数等于让它去改一个不存在的字段。
            case "ai_weather": return "去掉 city 重新调用（改用手机定位城市）、或 network_search 搜天气";
            // 不给"再试一次 network_search"这类建议：它刚失败，重试同一工具只会白烧一轮。
            case "network_search": {
                String a = action == null ? "" : action;
                if ("read_url".equals(a) || "get_webpage".equals(a) || "get_dynamic_content".equals(a)) {
                    // 不要直接点名 webpage_reader：它可能不在本轮注入的 tools 里
                    //（native PEG 按 tools 数组逐字枚举工具名），模型会写不出来。
                    // tool_registry 始终在核心集里，用它代执行才一定可达。
                    return "换一个来源重试；或 tool_registry 的 call 调用 webpage_reader 直接抓取";
                }
                if ("ask".equals(a)) {
                    // COST(2026-11-02)：秘塔按次计费（每次 3 积分，与条数无关），而 ask 出答案本来就要几十秒——
                    // 把"慢"当"失败"再重试一次就是双倍扣费。
                    return "别急着重试（每次调用都按次扣额度）：可改用 action=search 拿一批来源自行汇总，或把问题拆得更具体";
                }
                return "换更具体的关键词再搜一次（按次扣额度，一次就把 limit 给够，最多 50）；仍为空就如实说没查到，不要连续重跑同一条查询";
            }
            case "location": return "如实告知定位失败/无权限，并引导用户打开定位权限；"
                    + "不要用 network_search 或天气结果代替当前位置";
            case "time_date": return "network_search（联网查日期时间）";
            default: return null;
        }
    }

    /** dsh 对齐：中止合成错误（2026-09-23）。
     *  扫描 history：assistant 消息带 tool_calls 但后续无配对 tool 结果（含取消/break 未执行）
     *  → 在 assistant 消息后补一条"工具调用已中止"合成错误 tool 消息，保持配对完整。
     *  与 dsh 的 TOOL_ABORTED_BEFORE_DISPATCH 合成错误同语义（replay 后模型可见）。 */
    private void synthesizeAbortedToolResults(List<ChatMessage> history) {
        java.util.Set<String> paired = new java.util.HashSet<>();
        for (ChatMessage m : history) {
            if ("tool".equals(m.role) && m.toolCallId != null && !m.toolCallId.isEmpty()) {
                paired.add(m.toolCallId);
            }
        }
        boolean changed = false;
        for (int i = 0; i < history.size(); i++) {
            ChatMessage m = history.get(i);
            if (m == null || !"assistant".equals(m.role) || m.toolCalls == null) continue;
            for (ToolCall tc : m.toolCalls) {
                if (tc == null || tc.id == null || tc.id.isEmpty() || paired.contains(tc.id)) continue;
                history.add(i + 1, new ChatMessage("tool",
                        "工具调用已中止（未完成执行）：" + tc.toolName + "，参数：" + tc.args,
                        tc.id, true));
                paired.add(tc.id);
                changed = true;
                i++; // 跳过刚插入的 tool 消息
            }
        }
        if (changed) AILogger.w(TAG, "Synthesized aborted tool results for unpaired tool_calls");
    }

    private AIToolResult executeToolSafely(String toolName, JSONObject args) {
        Map<String, Object> params = jsonToMap(args);
        if (params == null) return AIToolResult.fail("参数解析失败");

        // 本地薄工具：翻译成真实工具 + 钉死 action + 重命名参数，后续按真实工具执行/重试。
        ThinTool thin = LOCAL_THIN_TOOLS.get(toolName);
        if (thin != null) {
            params = thin.translate(params);
            // DATABASE-READONLY(2026-11-07)：本地薄 database 是给 4B 的取数入口，只允许
            // 题数/分页题目/单条 SELECT。白名单与 SQL 校验必须在翻译后、执行前做——否则模型
            // 只要写 action=delete_question 或 "select ...; delete ..." 就能越过薄 schema 的说明。
            // 注意判据是**薄工具名**是 database：search_questions 也映射到真实 database
            //（action=search_questions），不能被这份只读白名单误伤。
            if ("database".equals(toolName)) {
                String action = params.get("action") != null
                        ? String.valueOf(params.get("action")).trim() : "";
                if (!isLocalDatabaseReadAction(action)) {
                    return AIToolResult.fail("本地题库只读：action 只能是 get_question_count / "
                            + "get_questions / execute_sql；要改题库请在题库页操作");
                }
                if ("execute_sql".equals(action)) {
                    String sql = params.get("sql") != null ? String.valueOf(params.get("sql")) : "";
                    if (!isReadOnlySelectSql(sql)) {
                        return AIToolResult.fail("本地题库只读：execute_sql 只接受一条 SELECT 语句"
                                + "（不能包含第二条 SQL；分页取题请用 get_questions）");
                    }
                }
            }
            toolName = thin.realTool;
        }

        // 程序硬编码：天气与位置强关联——ai_weather 缺位置参数时自动补当前位置。
        // 优先填经纬度（绕开和风 geo/city lookup）；坐标无缓存时，优先从用户消息
        // 提取城市名（用户明确问的城市 > 当前位置），避免"五台县"场景首轮无 city 报错。
        //
        // EMPTY-AS-ABSENT(2026-10-11)：原先只判 containsKey —— 模型传了**空值**
        // （city="" 或 lat=null）时 containsKey 为 true 反而不注入，而工具侧把空值
        // 视为"未提供"并报"缺少城市参数"，两边语义不一致导致"不传参必失败"
        // （真机模型自测发现）。这里改为按**有效值**判断，与 AIWeatherManager 的
        // hasCity/useLocation 语义对齐；若本层仍填不出（无缓存且消息里无城市），
        // 则由 AIWeatherManager 的 tryAutoLocate() 兜底真正取一次设备定位。
        if ("ai_weather".equals(toolName) && !hasMeaningfulLocationParam(params)) {
            fillWeatherLocation(params);
        }

        AIToolResult result = executeWithTimeout(toolName, params);
        if (isToolSuccess(result)) return result;

        // NO-CITY-SUBSTITUTE(2026-10-11)：此处原有「失败感知重试」——
        // 模型传了 city 但查不到时，丢弃该 city、改走 fillWeatherLocation 用**设备定位**重试。
        // 该行为已移除，原因：用户明确问"镇江天气"而城市查不到时，它会静默返回**设备所在城市**
        // 的天气 —— 属"错误数据冒充正确数据"，比查询失败更糟（用户无法察觉），
        // 且与 system prompt 的规则直接冲突（prompt 明确要求"如实告知查不到，不要用别的城市顶上"）。
        //
        // 保留的行为区分（两者必须一致，改一处要同时改 system prompt 的 ACTION-LOCATION 段）：
        //   ① 用户**未指定城市**（问"今天天气"）→ 由上方 !containsKey 分支的 fillWeatherLocation
        //      自动补手机定位经纬度，这是**正确**的（问的就是用户所在地）；
        //   ② 用户**已指定城市** → 城市解析失败就如实报错，不再替换为设备所在城市。
        // 原注释担心的"模型把非城市词当 city 传入"（实测"查询下今天的天气"→city=今天）仍然会发生，
        // 但现在会明确报"无法定位城市: 今天"，模型可据此改带正确城市名或改用 network_search 搜索。

        // 通用重试：同参数重跑，只对**瞬时故障**有意义（网络抖动、服务端偶发 5xx）。
        // NO-RETRY-TOOLS(2026-10-11)：network_search 的重试是纯浪费 ——
        // 它的"无结果"是确定性结果，重试只会用同一 query 再打两次 HTTP（白烧 2 轮推理，还可能触发
        // 服务端限流）；而真正的瞬时故障由模型换关键词重搜比引擎原样重跑更有效。
        // 该工具的失败已改成 fail() 上报（见 NetworkSearchTool 的 FAIL-SEMANTICS 注释），
        // 上层据此给模型"换更具体的关键词重搜"的替代提示。
        // BILLED(2026-11-02)：判据从"写死 network_search 一个名字"改成 ToolSideEffects 的口径 ——
        // smart_research 内部也是按次计费的秘塔调用，以前它照样会被原样重跑一次（多扣一次积分）。
        // 现在凡是"按次付费"或"有副作用"的工具都不自动重跑，只有只读且免费的才重试。
        if (com.oilquiz.app.ai.tool.ToolSideEffects.mayRetryOnTimeout(toolName)) {
            for (int r = 0; r < MAX_RETRIES; r++) {
                AILogger.w(TAG, "Retrying " + toolName);
                result = executeWithTimeout(toolName, params);
                if (isToolSuccess(result)) return result;
            }
        }
        return result;
    }

    /** 工具结果是否视为成功：isSuccess() 且结果文本不含失败语义。
     *  解决 AIWeatherManager 等把"查询失败/无法定位"包装成 success 的问题。 */
    private boolean isToolSuccess(AIToolResult result) {
        if (result == null || !result.isSuccess()) return false;
        String text = extractResultText(result);
        return !containsToolFailure(text);
    }

    /** 提取结果文本（成功取 result，失败取 errorMessage） */
    private String extractResultText(AIToolResult result) {
        if (result == null) return null;
        if (result.isSuccess()) {
            Object r = result.getResult();
            return r != null ? String.valueOf(r) : null;
        }
        return result.getErrorMessage();
    }

    /** 结果文本是否含失败语义（工具常把失败文本包装进成功结果） */
    private boolean containsToolFailure(String text) {
        if (text == null || text.isEmpty()) return false;
        return text.contains("查询失败") || text.contains("无法定位")
                || text.contains("查询异常") || text.contains("获取失败")
                || text.contains("缺少城市") || text.contains("缺少坐标")
                || text.contains("请提供城市") || text.contains("请提供坐标")
                || text.contains("无法获取") || text.contains("无结果")
                || text.contains("超时");
    }

    /** ai_weather 位置兜底：优先经纬度缓存，其次用户消息城市，最后当前位置。 */
    /**
     * 判断 ai_weather 的位置参数是否存在**有效值**（空串/null 视为未提供）。
     * 与 AIWeatherManager 的 hasCity/useLocation 判定保持一致，避免引擎"以为模型给了位置"
     * 而工具"认为没给"的语义错位（EMPTY-AS-ABSENT）。
     */
    private static boolean hasMeaningfulLocationParam(Map<String, Object> params) {
        if (params == null) return false;
        Object c = params.get("city");
        if (c != null && !String.valueOf(c).trim().isEmpty()) return true;
        for (String k : new String[]{"lat", "latitude"}) {
            Object v = params.get(k);
            if (v != null && !String.valueOf(v).trim().isEmpty() && !"null".equals(String.valueOf(v).trim())) {
                return true;
            }
        }
        return false;
    }

    private void fillWeatherLocation(Map<String, Object> params) {
        // 确保坐标从主界面缓存加载（getCachedLocation 内部会先读 weather_location_cache）
        if (cachedLat == 0 && cachedLon == 0) {
            getCachedLocation();
        }
        if (cachedLat != 0 || cachedLon != 0) {
            params.put("lat", cachedLat);
            params.put("lon", cachedLon);
            AILogger.i(TAG, "ai_weather: auto-filled lat/lon=" + cachedLat + "," + cachedLon);
        } else {
            // 无坐标：优先用户消息里明确说的城市名（如"五台县的天气"→五台县）
            String city = extractCityFromMessage(lastUserMessage);
            if (city == null || city.isEmpty()) {
                city = getCachedLocation();
                if ("当前位置".equals(city)) city = null;
            }
            if (city != null && !city.isEmpty()) {
                params.put("city", city);
                AILogger.i(TAG, "ai_weather: auto-filled city=" + city);
            }
        }
    }

    private AIToolResult executeWithTimeout(String toolName, Map<String, Object> params) {
        // ASK-SLOW(2026-11-02)：等待时长不再一刀切 15 秒——按"基线与工具自声明取较大者"算，
        // 与在线 OnlineToolManager / 对话页 AgentService 同一口径（ToolTimeouts 是单一写者）。
        // 秘塔 ask 出答案要几十秒且**按次计费**：15 秒就判超时 = 把"慢"当"失败"，
        // 模型接着重试，每重试一次都是真扣积分。
        final int waitMs = toolWaitMs(toolName, params);
        final AtomicReference<AIToolResult> holder = new AtomicReference<>(null);
        final boolean[] done = {false};

        Thread t = new Thread(() -> {
            try {
                holder.set(toolManager.executeTool(toolName, params));
            } catch (Exception e) {
                holder.set(AIToolResult.fail("工具异常: " + e.getMessage()));
            } finally {
                synchronized (done) { done[0] = true; done.notifyAll(); }
            }
        }, "agent-tool-" + toolName);
        t.setDaemon(true);
        t.start();

        try {
            synchronized (done) {
                long start = System.currentTimeMillis();
                // 取消（2026-10-25）：与并行等待同一口径，点停止不白等满工具超时
                while (!done[0] && !cancelRequested.get()) {
                    long rem = waitMs - (System.currentTimeMillis() - start);
                    if (rem <= 0) break;
                    done.wait(Math.min(rem, 500));
                }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        AIToolResult result = holder.get();
        if (result != null) return result;
        if (cancelRequested.get()) {
            return AIToolResult.fail("已取消，该工具未执行完成");
        }
        return AIToolResult.fail("工具超时(" + (waitMs / 1000) + "秒)");
    }

    /**
     * 本工具这次调用该等多久：基线 {@code TOOL_TIMEOUT_MS} 与工具自声明取较大者
     * （{@link com.oilquiz.app.ai.agent.ToolTimeouts}，在线/对话页/本地三条路径共用同一判据）。
     * 读不到工具或申报异常时退回基线，**绝不**因为申报本身把一次正常调用判成失败。
     */
    private int toolWaitMs(String toolName, Map<String, Object> args) {
        try {
            return com.oilquiz.app.ai.tool.ToolTimeouts.effectiveMs(
                    toolManager.getTool(toolName), args, (int) TOOL_TIMEOUT_MS);
        } catch (Throwable t) {
            return (int) TOOL_TIMEOUT_MS;
        }
    }

    /**
     * 从用户消息中提取城市名（ai_weather 兜底 + 天气场景直给用）。
     * 优先匹配"XX市/县/区/旗/盟/自治州/地区"等行政区后缀（如"五台县"），
     * 其次匹配"XX的天气"结构；排除"今天/这边"等指代词，不误伤无城市问题。
     *
     * @param message 用户消息原文（可为空）
     * @return 城市名（含行政区后缀），无则返回 null
     */
    /**
     * 天气意图提取城市名：只用内置城市表做"消息内最长匹配"。
     * 表上无该城市时返回 null（不猜测），由模型自行从用户消息提取城市名后调用 ai_weather。
     */
    private String extractCityFromMessage(String message) {
        if (message == null || message.isEmpty()) return null;
        try {
            // 城市表匹配（最长优先）：直接与 CSV 全量城市比对，不依赖正则，
            // 避免"查询下/看一下/今天"等动词·时间词被吞进地名（"查询下五台县的天气"→"五台县"）
            // CITY-TABLE-ONLY(2026-10-11)：原「② 内置常用城市表兜底」已随 DEFAULT_CITY_IDS 删除。
            // 该表自称"覆盖 CSV 可能缺失的港澳台"，但实测 CSV 已含香港(101320101)/澳门(101330101)
            // 与台湾地区 363 条 —— 兜底前提不成立，且该表本身有错编码。现在唯一来源是 CSV，
            // 其加载已由 QWeatherCityManager.ensureLoaded() 保证确定性（见 LOAD-RACE 修复）。
            if (appContext != null) {
                com.oilquiz.app.weather.QWeatherCityManager cityMgr =
                        com.oilquiz.app.weather.QWeatherCityManager.getInstance(appContext);
                java.util.List<String> hits = cityMgr.findCityNamesInMessage(message);
                if (hits != null && !hits.isEmpty()) {
                    return hits.get(0); // 最长 = 最精确
                }
            }
        } catch (Throwable t) {
            AILogger.w(TAG, "extractCityFromMessage failed: " + t.getMessage());
        }
        return null; // 表上无此城市 → 不猜，交由模型自行提取
    }

    /**
     * 取城市名对应的和风城市编码（locationId），供 ai_weather 按编码查询（最精确，
     * 避免"银川市/银川"等名称不匹配）。查 CSV 全量表；无则 null
     * （调用方在 null 时会退回直接传城市名，工具内部仍有本地 CSV + Geocoder 兜底）。
     */
    private String getCityLocationId(String cityName) {
        if (cityName == null || cityName.isEmpty()) return null;
        try {
            if (appContext != null) {
                com.oilquiz.app.weather.QWeatherCityManager cityMgr =
                        com.oilquiz.app.weather.QWeatherCityManager.getInstance(appContext);
                com.oilquiz.app.weather.QWeatherCityManager.CityEntry entry = cityMgr.getCityByName(cityName);
                if (entry != null && entry.locationId != null && !entry.locationId.isEmpty()) {
                    return entry.locationId;
                }
            }
        } catch (Throwable t) {
            AILogger.w(TAG, "getCityLocationId failed for " + cityName + ": " + t.getMessage());
        }
        return null;
    }

    private Map<String, Object> jsonToMap(JSONObject args) {
        Map<String, Object> map = new HashMap<>();
        try {
            JSONArray keys = args.names();
            if (keys != null) {
                for (int i = 0; i < keys.length(); i++) {
                    String k = keys.getString(i);
                    Object v = args.get(k);
                    if (v instanceof JSONObject) map.put(k, jsonObjToMap((JSONObject) v));
                    else if (v instanceof JSONArray) {
                        List<Object> list = new ArrayList<>();
                        for (int j = 0; j < ((JSONArray) v).length(); j++) {
                            Object item = ((JSONArray) v).get(j);
                            list.add(item instanceof JSONObject ? jsonObjToMap((JSONObject) item) : item);
                        }
                        map.put(k, list);
                    } else map.put(k, v);
                }
            }
        } catch (Exception e) { return null; }
        return map;
    }

    private Map<String, Object> jsonObjToMap(JSONObject obj) {
        Map<String, Object> map = new HashMap<>();
        try {
            JSONArray keys = obj.names();
            if (keys != null) {
                for (int i = 0; i < keys.length(); i++) {
                    String k = keys.getString(i);
                    Object v = obj.get(k);
                    if (v instanceof JSONObject) map.put(k, jsonObjToMap((JSONObject) v));
                    else if (v instanceof JSONArray) {
                        List<Object> list = new ArrayList<>();
                        for (int j = 0; j < ((JSONArray) v).length(); j++) {
                            Object item = ((JSONArray) v).get(j);
                            list.add(item instanceof JSONObject ? jsonObjToMap((JSONObject) item) : item);
                        }
                        map.put(k, list);
                    } else map.put(k, v);
                }
            }
        } catch (Exception ignored) {}
        return map;
    }

    // ==================== 兜底回答 ====================

    /**
     * TOOL-RESULT-JSON(2026-11-02)：把工具结果规范化成 JSON 再交给模型。
     *
     * <p>问题：工具结果多数是 Map/List，旧实现是 {@code String.valueOf(...)}，得到的是
     * Java 的 {@code {code=200, data=[{title=…}]}}——不是合法 JSON、键值用 '='、字符串无引号，
     * 2B 只能靠猜结构去对字段与数值，搜索/天气这类多字段结果尤其容易读错（幻觉来源之一）。</p>
     *
     * <p>统一信封 {@code {"ok":true,"data":…}}；失败带 {@code "error"}，有替代方案带 {@code "hint"}。
     * 已经是 JSON 的字符串会解析后嵌入（而不是转义成一个大字符串）。</p>
     *
     * <p>NO-CUT(2026-11-02，用户口径)：**API 返回的数据一律原样给模型，不做任何裁剪**。
     * 之前这里按"数组前 8 条 / 单字段 400 字 / 总长 6000 字"三档裁过，看着像省 token，
     * 实际是把秘塔已经花钱买回来的条目和摘要丢掉：条数本该由模型的 {@code limit} 决定（1..50），
     * 不是由引擎替它决定；丢掉的那几条模型看不见，只会用自己的知识补（幻觉）。
     * 现在只在超预算时**如实标注** {@code size_chars} 与 {@code note}，让它知道这条结果很大；
     * 真装不下由 {@link #trimHistoryToFit} 挤掉更早的历史（丢的是旧对话，不是本次数据），
     * 并在退出归因里说清楚是谁撑爆的，不做静默截断。</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String formatToolResultForModel(boolean success, AIToolResult result, String hint) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", success);
            Object data = result != null ? result.getResult() : null;
            if (data instanceof Map) {
                o.put("data", new JSONObject((Map) data));
            } else if (data instanceof List) {
                o.put("data", new JSONArray((List) data));
            } else if (data instanceof String) {
                String s = ((String) data).trim();
                if (s.startsWith("{") || s.startsWith("[")) {
                    try {
                        o.put("data", new JSONTokener(s).nextValue());
                    } catch (Throwable notJson) {
                        o.put("data", data);
                    }
                } else {
                    o.put("data", data);
                }
            } else if (data != null) {
                o.put("data", String.valueOf(data));
            }
            if (!success && result != null && result.getErrorMessage() != null) {
                o.put("error", result.getErrorMessage());
            }
            if (hint != null && !hint.isEmpty()) {
                o.put("hint", "可能需要改用其他工具: " + hint);
            }
            String out = o.toString();
            if (out.length() > MAX_TOOL_RESULT_LENGTH) {
                // 只标注、不裁：让模型知道这条很大（它自己决定要不要换更小的 limit/分段读）
                try {
                    o.put("size_chars", out.length());
                    o.put("note", "本结果完整给出，未截断（" + out.length() + " 字符）。"
                            + "条数由你自己在参数里决定；若这轮上下文装不下，被挤掉的是更早的历史，不是本条数据。");
                    AILogger.w(TAG, "工具结果未截断但偏大: " + out.length() + " 字符（阈值提示线 "
                            + MAX_TOOL_RESULT_LENGTH + "）");
                } catch (Throwable ignored) {
                    // 加标注失败就退回原样，绝不因此改成裁剪
                }
                return o.toString();
            }
            return out;
        } catch (Throwable t) {
            try {
                JSONObject f = new JSONObject();
                f.put("ok", success);
                f.put("data", String.valueOf(result != null ? result.getResult() : ""));
                return f.toString();
            } catch (Throwable ignored) {
                return "{\"ok\":" + success + "}";
            }
        }
    }

    private static String clipString(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…(截断)";
    }

    /**
     * FALLBACK-TEXT(2026-11-02)：最后一道静态兜底——只在"单次解释推理也失败"时使用。
     * 不再说"抱歉，我暂时无法完整回答这个问题，请换一种方式描述"（既不含原因、也不是
     * 用户能执行的建议），而是带上**具体退出原因** + 两句可执行的下一步。
     */
    private String buildSimpleFallback(String reason) {
        String r = (reason != null && !reason.trim().isEmpty()) ? "（" + reason.trim() + "）" : "";
        return "这轮没能给出回答" + r + "。可以换个说法、缩小范围，或者让我分两步做。";
    }

    /**
     * GEN-ERROR(2026-11-02)：把 native/Java 侧的原始错误翻成用户能判断下一步的说法。
     *
     * <p>之前所有"生成失败"都落到同一句"回答为空或被过滤"：真机上模型被并发切换卸载时
     * native 明确报 "Helper context not initialized"，用户看到的却是"模型没输出"，
     * 于是会去改提示词而不是重试或等加载。这里按已知错误分类，未知错误保留原文（截断）。</p>
     */
    private static String describeGenerationError(String raw, String defaultText) {
        if (raw == null || raw.trim().isEmpty()) return defaultText;
        String e = raw.trim();
        String lower = e.toLowerCase();
        if (lower.contains("helper context not initialized")
                || lower.contains("context not initialized")) {
            return "本地推理上下文未就绪（模型正在加载或被切换释放），这次推理没有真正开始";
        }
        if (lower.contains("cancelled") || lower.contains("canceled")) {
            return "推理被取消，没有产出完整回答";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "推理超时，没有在限定时间内产出回答";
        }
        if (lower.contains("out of memory") || lower.contains("memory allocation")
                || lower.contains("failed to allocate")) {
            return "内存不足，推理被中断";
        }
        if (lower.contains("prompt too long") || e.contains("Prompt过长")) {
            // PROMPT-DOOR(2026-11-07)：超长的是**输入**（历史+工具定义），不是回复。
            // 原来没有这条分支，英文原文落到"推理引擎报错：Prompt too long: 3634 tokens, aborting"
            // 再被喂给解释推理，模型自己编成"生成的回复内容太长（超过模型限制）"——
            // 用户照着去缩短问题完全没用，真正该做的是清历史/释放内存/换大窗口模型。
            String n = null;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(e);
            if (m.find()) n = m.group();
            return "输入超长被推理引擎拒收" + (n != null ? "（本轮 prompt 约 " + n + " tokens）" : "")
                    + "：装不下的是历史+工具定义，不是回复内容。"
                    + "请清空当前对话历史或释放手机内存后重试，也可改用窗口更大的模型/在线模型";
        }
        return "推理引擎报错：" + clipString(e, 120);
    }

    /**
     * FALLBACK-EXPLAIN(2026-11-02)：兜底不再回一句固定话术，而是**单次推理**说明这轮为什么没成。
     *
     * <p>与 {@link #buildSimpleFallback} 的区别：那句对任何失败都一样、也不解释原因；
     * 这里把真实原因（上下文截断 / 工具轮超限 / 模型没吐内容）作为约束喂给模型，
     * 让它结合已经拿到的工具结果说明"发生了什么 + 下一步怎么走"。约束：
     * 单次调用、tool_choice=none（不会引发新工具轮）、限长 512、按回答轮温度、
     * 明确禁止编造没拿到的数据；失败才落回最短静态文案。</p>
     *
     * @return 解释文本；推理失败或为空时返回 null（调用方走静态兜底）
     */
    private String explainFailureOnce(List<ChatMessage> history, String reason) {
        try {
            int budget = computePromptBudget();
            List<ChatMessage> trimmed = trimHistoryToFit(history, "",
                    Math.max(1000, budget - OUTPUT_RESERVE_TOKENS));
            List<ChatMessage> h = new ArrayList<>(trimmed);
            h.add(new ChatMessage("user",
                    "【系统】本轮没有成功产出回答。原因：" + reason + "\n"
                    + "请用两三句中文向用户说明发生了什么（结合上面的工具结果与对话，"
                    + "不要编造没有拿到的数据），并给出一个具体的下一步建议"
                    + "（例如换个说法、缩小范围、分步执行）。"));
            lastRequestHistory = new ArrayList<>(h);
            String requestJson = buildRequestJson(h, null, "none",
                    Math.min(OUTPUT_RESERVE_TOKENS, 512), false, resolveAnswerTemperature());
            if (requestJson == null) return null;
            GenerateResult r = generateWithChatJsonSync(requestJson, null);
            if (r != null && r.content != null) {
                String c = cleanResponse(r.content);
                if (!c.isEmpty() && !isPromptLeakage(c)) {
                    AILogger.i(TAG, "Failure explanation generated: " + truncate(c, 80));
                    return c;
                }
            }
        } catch (Throwable t) {
            AILogger.w(TAG, "explainFailureOnce failed: " + t.getMessage());
        }
        return null;
    }

    /**
     * ENGINE-ASK(2026-11-02)：引擎自己出问题时，先弹选择框问用户，而不是默认回兜底话术
     * 或者静默结束。
     *
     * <p>用仓库现成的 {@code ui_component(action=create, component_type=choice)} +
     * {@code get_result}（阻塞 60s）收集选择——这套交互本来就是给模型用的，引擎也可以用。
     * 任何一步失败（无前台界面、组件工具不可用、超时、返回格式不符）都返回 -1，
     * 由调用方退回文字兜底；**绝不**因为"没拿到答复"就替用户选一个。</p>
     *
     * @return 0=用已有结果回答 / 1=用户补充说明 / 2=停止本次任务 / -1=没拿到答复
     */
    private int askUserOnEngineTrouble(String reason) {
        try {
            if (appContext == null) return -1;
            com.oilquiz.app.ai.tool.AIToolManager mgr =
                    com.oilquiz.app.ai.tool.AIToolManager.getInstance(appContext);
            if (mgr == null) return -1;

            java.util.Map<String, Object> create = new java.util.LinkedHashMap<>();
            create.put("action", "create");
            create.put("component_type", "choice");
            create.put("title", "本地引擎需要你决定");
            create.put("message", reason + "\n\n你想怎么继续？");
            create.put("options", java.util.Arrays.asList("用已有结果回答", "我再补充说明", "停止本次任务"));
            com.oilquiz.app.ai.tool.AIToolResult cr = mgr.executeTool("ui_component", create);
            if (cr == null || !cr.isSuccess()) {
                AILogger.w(TAG, "ENGINE-ASK: create component failed, fallback to text");
                return -1;
            }
            String cid = mapField(cr.getResult(), "component_id");
            if (cid == null || cid.isEmpty()) {
                AILogger.w(TAG, "ENGINE-ASK: no component_id in result, fallback to text");
                return -1;
            }

            java.util.Map<String, Object> get = new java.util.LinkedHashMap<>();
            get.put("action", "get_result");
            get.put("component_id", cid);
            get.put("wait_seconds", 60);
            com.oilquiz.app.ai.tool.AIToolResult gr = mgr.executeTool("ui_component", get);
            if (gr == null) return -1;
            String chosen = mapField(gr.getResult(), "result");
            if (chosen == null) {
                AILogger.w(TAG, "ENGINE-ASK: no user choice (timeout?), fallback to text");
                return -1;
            }
            AILogger.i(TAG, "ENGINE-ASK: user chose " + chosen);
            if (chosen.contains("补充")) return 1;
            if (chosen.contains("停止")) return 2;
            if (chosen.contains("已有结果")) return 0;
            return -1;
        } catch (Throwable t) {
            AILogger.w(TAG, "ENGINE-ASK failed: " + t.getMessage());
            return -1;
        }
    }

    /**
     * AUTO-CONTINUE(2026-11-02)：read_file 一次读不完时，引擎自动按行分页续读并合并。
     *
     * <p>触发：read_file 且未带 start_line、结果文本超过单条上限。分页从第 1 行按
     * 120 行/页重新取（不拼接截断产生的半行），最多 3 页、合并总长 ≤ 2×MAX_TOOL_RESULT_LENGTH；
     * 到上限时在结果里注明"可用 start_line=N 继续"——是否继续由模型决定，引擎不无限翻页。</p>
     */
    private AIToolResult autoContinueReadIfLarge(ToolCall tc, AIToolResult first) {
        try {
            if (tc == null || !"read_file".equals(tc.toolName)) return first;
            Object d = first != null ? first.getResult() : null;
            if (!(d instanceof String)) return first;
            if (((String) d).length() <= MAX_TOOL_RESULT_LENGTH) return first;
            String path = tc.args != null ? tc.args.optString("path", "") : "";
            if (path.isEmpty()) return first;
            com.oilquiz.app.ai.tool.AIToolManager mgr = appContext != null
                    ? com.oilquiz.app.ai.tool.AIToolManager.getInstance(appContext) : null;
            if (mgr == null) return first;
            final int pageLines = 120;
            StringBuilder sb = new StringBuilder();
            int line = 1;
            int pages = 0;
            while (pages < 3 && sb.length() < MAX_TOOL_RESULT_LENGTH * 2) {
                java.util.Map<String, Object> p = new java.util.HashMap<>();
                p.put("action", "read_lines");
                p.put("file_path", path);
                p.put("startLine", line);
                p.put("endLine", line + pageLines - 1);
                AIToolResult r = mgr.executeTool("file_reader", p);
                if (r == null || !r.isSuccess()) break;
                String chunk = extractResultText(r);
                if (chunk == null || chunk.trim().isEmpty()) break;
                if (sb.length() > 0) sb.append("\n");
                sb.append(chunk);
                line += pageLines;
                pages++;
                if (chunk.length() < 100) break;   // 已到文件末尾
            }
            if (sb.length() == 0) return first;
            JSONObject env = new JSONObject();
            env.put("ok", true);
            env.put("auto_paged", true);
            env.put("file", path);
            env.put("pages", pages);
            env.put("lines_read", line - 1);
            if (sb.length() >= MAX_TOOL_RESULT_LENGTH * 2) {
                env.put("truncated", true);
                env.put("note", "已达自动续读上限，可用同一工具带 start_line=" + line + " 继续");
            }
            env.put("text", sb.toString());
            AILogger.i(TAG, "AUTO-CONTINUE: read_file auto-paged " + pages
                    + " pages, lines=" + (line - 1));
            return new AIToolResult(env.toString(), jsonToMap(tc.args));
        } catch (Throwable t) {
            AILogger.w(TAG, "autoContinueReadIfLarge failed: " + t.getMessage());
            return first;
        }
    }

    /** 从工具结果（Map 或 JSON 字符串）里取一个字符串字段；取不到返回 null。 */
    private static String mapField(Object obj, String key) {
        try {
            if (obj instanceof java.util.Map) {
                Object v = ((java.util.Map<?, ?>) obj).get(key);
                return v != null ? String.valueOf(v) : null;
            }
            if (obj != null) {
                String s = String.valueOf(obj);
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
                        .matcher(s);
                if (m.find()) return m.group(1);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ==================== 工具方法 ====================

    private int countTokensSafe(String text) {
        if (text == null || text.isEmpty()) return 0;
        // NPU-AWARE：NPU 模式下 llama.cpp tokenizer 未加载，countTokens 恒 0 →
        // Agent 的单轮 prompt 预算与历史裁剪会全部失真。按引擎选数据源。
        return com.oilquiz.app.ai.engine.NpuAwareText.countTokens(text);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }

    // ==================== 内部类 ====================

    /**
     * 对话消息（spec §7.2.4）
     * toolCalls：assistant 消息携带的工具调用（OpenAI 格式结构化，R4-1）
     * toolCallId：tool 消息对应的调用 ID（必须与 assistant tool_calls 的 id 一致，R4-1）
     */
    private static class ChatMessage {
        final String role;
        final String content;
        final List<ToolCall> toolCalls;
        final String toolCallId;
        /**
         * REASONING-REPLAY(2026-11-01)：该轮 assistant 的原始思考，随请求回放给模板。
         * Qwen3.5 模板对"最后一条 user 之后的 assistant 轮"渲染 {@code <think>…</think>}，
         * 取自 {@code message.reasoning_content}；不传就只剩空思考块。非 final：构造后补充。
         */
        String reasoningContent;
        ChatMessage(String role, String content) { this(role, content, null, null); }
        ChatMessage(String role, String content, List<ToolCall> toolCalls) { this(role, content, toolCalls, null); }
        ChatMessage(String role, String content, String toolCallId, boolean isToolResult) { this(role, content, null, toolCallId); }
        private ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
            this.role = role;
            this.content = content;
            this.toolCalls = toolCalls;
            this.toolCallId = toolCallId;
        }
    }

    private static class ToolCall {
        String id;          // 非 final：执行工具前补齐（R4-1）
        final String toolName;
        final JSONObject args;
        ToolCall(String id, String name, JSONObject args) { this.id = id; this.toolName = name; this.args = args; }
        ToolCall(String name, JSONObject args) { this.id = null; this.toolName = name; this.args = args; }
    }
}

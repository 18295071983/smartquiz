package com.oilquiz.app.ai.agent.software.engine;

import android.content.Context;
import com.oilquiz.app.ai.engine.NpuEngineRouter;

import com.oilquiz.app.ai.agent.software.model.AgentResponse;
import com.oilquiz.app.ai.agent.software.model.AgentStats;
import com.oilquiz.app.ai.chat.parser.ThinkingTagConfig;
import com.oilquiz.app.ai.chat.parser.ToolCallTagConfig;
import com.oilquiz.app.ai.config.UserModelParamsManager;
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
    /** 循环保护：整个 Agent 执行的总时长上限（含工具执行与推理）。
     *  180s→300s：Qwen3VL-4B-Thinking 思考链 + 工具调用 + 最终整合在内存紧张
     *  的真机上实测需 4-6 分钟，180s 会在思考中途被强制结束（虽走 unified exit
     *  优雅降级，但回答完整性打折）。用户已确认"速度可以慢、功能正常优先"，
     *  故放宽到 5 分钟；思考链冗长问题由规则 1 强约束抑制。 */
    private static final long TOTAL_TIME_BUDGET_MS = 300000;
    /** 单次推理的 prompt token 预算系数（占上下文容量的比例，下限 0.15）
     *  ⚠ 2026-10-07 已废弃：固定比例只约束 prompt，把生成空间压到 < maxTokens，
     *  长回答必然触发 native ctx_full 硬截断。预算改由 computePromptBudget 的
     *  「n_ctx − FINAL_RESPONSE_MAX_TOKENS − NATIVE_GENERATION_RESERVE」公式计算 */
    private static final double PROMPT_BUDGET_RATIO = 0.8; // 保留引用说明，勿再使用
    /** 工具结果最大字符数（超长时截断，节省上下文；放宽到 6000：天气/搜索等
     *  结果不再因 1200 截断丢失关键数值——上一轮天气问题的根因。trim 阶段会把
     *  更早的工具结果压缩到 2500，最近的保持完整，平衡信息完整性与上下文占用） */
    private static final int MAX_TOOL_RESULT_LENGTH = 6000;
    /** 最终回复最大生成 token：放宽到 4000，长回答/长篇输出不被截断。
     *  注意：生成空间 = n_ctx − prompt 预算，必须由 computePromptBudget / buildRequestJson
     *  联动保证（P1/P0，2026-10-07），否则 4000 会超出实际空间触发 native ctx_full 硬截断 */
    private static final int FINAL_RESPONSE_MAX_TOKENS = 4000;
    /** native 生成预留（native-lib.cpp GENERATION_RESERVE=512）：prompt 上限 n_ctx−512 的最后防线 */
    private static final int NATIVE_GENERATION_RESERVE = 512;
    /** 模板渲染 overhead 预留：Java token 计数基于简化文本（serializeHistory），
     *  实际模板含 <|im_start|> 等特殊 token 与工具格式包装，需预留余量防低估 */
    private static final int TEMPLATE_OVERHEAD_RESERVE = 512;
    /** 普通对话（意图未命中）最大生成 token：放宽到 2000 */
    private static final int PLAIN_CHAT_MAX_TOKENS = 2000;
    /** 工具执行超时（毫秒） */
    private static final long TOOL_TIMEOUT_MS = 15000;
    /** 工具失败重试次数 */
    private static final int MAX_RETRIES = 1;
    /** 同步调用超时（毫秒）：必须大于 C++ 生成超时(400s)，否则 Java 先放弃导致回答被掐断 */
    private static final long SYNC_TIMEOUT_MS = 420000;
    /** 用户问题长度上限（字符） */
    private static final int MAX_USER_MESSAGE_CHARS = 2000;
    /** UI 交互等待时长（毫秒）：弹窗问用户，超时未操作则回退文本追问 */
    private static final long UI_WAIT_MS = 30000;
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
     *  CORE-TOOLS-4(2026-10-11)：本地 Agent 只做这 4 个工具调用，故只保留可达的四条映射。
     *  原先 30 条覆盖全量注册表，其中 26 条永远查不到（不可达）—— 留着会让后来者误判
     *  "本引擎支持这些工具"。将来若恢复某工具的能力，在此补一条即可（toolLabel 缺省回退原名）。 */
    private static final java.util.Map<String, String> TOOL_LABELS = new java.util.HashMap<>();
    static {
        TOOL_LABELS.put("time_date", "时间日期");
        TOOL_LABELS.put("location", "位置");
        TOOL_LABELS.put("ai_weather", "天气");
        TOOL_LABELS.put("network_search", "搜索");
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
     * 常驻核心工具（2026-10-11 定为产品范围）：时间(time_date)、定位(location)、
     * 天气(ai_weather)、联网搜索(network_search)。本地 Agent **只做这四个工具调用**。
     *
     * <p><b>为什么这四个就是能力上限（重要，别照搬在线路径的经验）</b>：
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
     * 无法让未注入的工具变得可调用。将来若要恢复 4 个之外的能力，需要新增一条
     * "引擎侧注入"通道（意图匹配或发现工具返回工具名后由 Java 加入 activeTools），
     * 单靠改 prompt 或让模型猜名字都无效。</p>
     *
     * <p>本次移出的 5 个（如需恢复，加回下面的数组即可，schema 由 buildToolsJson 自动处理）：
     * {@code tool_registry}（原 list/search/get 发现通道）、
     * {@code smart_research}（搜索→阅读→摘要多步链，现由 network_search 覆盖）、
     * {@code memory}（跨会话记忆）、{@code file_reader}（读本地文件）、
     * {@code workspace}（工作区文件浏览）。</p>
     *
     * <p>历史沿革（保留以便回退判断）：
     * 2026-10-05 补 ai_weather/smart_research/memory —— 原先只有
     * time_date/location/tool_registry/file_reader/workspace，而 sessionTools 只在会话首条消息
     * 播种一次 → 模型整轮看不到天气工具（"我无法获取天气信息"的根因）；
     * 2026-10-09 补 network_search —— 实测"查油价"这类非天气实时查询被错派给 ai_weather，
     * 因为核心集里唯一带"实时查询"语义的就是 ai_weather。</p>
     */
    private static final String[] DEFAULT_CORE_TOOLS = {
            "time_date", "location", "ai_weather", "network_search"
    };

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
        // 注入集：初始 = 会话级工具集（= DEFAULT_CORE_TOOLS 的 4 个）。
        // CORE-TOOLS-4(2026-10-11)：本地 Agent 只做这 4 个工具调用，故此处不再有其它扩展途径；
        // 下面 FC 循环里的"动态注入"分支因此恒不触发（见该处注释）。
        final java.util.Set<String> activeTools = new java.util.LinkedHashSet<>(sessionTools);
        String toolsJson = buildToolsJson(new ArrayList<>(activeTools));
        byte[] toolsJsonBytes = toolsJson.getBytes(StandardCharsets.UTF_8);
        AILogger.i(TAG, "Initial tools: " + activeTools.size() + " tools, schema len: " + toolsJson.length());
        // CORE-TOOLS-4(2026-10-11)：核心集收窄到 4 个后，必须能一眼看出实际生效的核心工具
        // ——尤其 time_date 走 Python 注册块，注册失败时它会静默消失（buildToolsJson 对 null 定义
        // 直接 continue），只靠数量无法定位问题。
        AILogger.i(TAG, "Core tools: " + selectedTools + " (active initial: " + activeTools + ")");
        // SCHEMA-DUMP(2026-10-11)：核对"模型实际看到的工具定义"。四个核心工具走两条不同路径 ——
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
        String sysPrompt = modelFcMode ? buildFcSystemPrompt() : buildSystemPrompt();
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
            history.add(m);
        }
        // 本轮动态任务指令（缓存命中优化）：天气直给指令从 system 移到本轮 user 消息末尾。
        // system 前缀保持静态（同一天内字节不变，见 buildCurrentTimeLine 注释），
        // 本地推理引擎的 KV 缓存才能命中 system+历史前缀；否则每条带"天气"的消息都改写
        // system → 前缀抖动 → 每轮全量重算前缀。动态指令放 prompt 末尾（D 块），
        // 模型仍能读到并执行，行为不变。
        String effectiveUserMessage = userMessage;
        if (modelFcMode && selectedTools.contains("ai_weather") && userMessage != null
                && (userMessage.contains("天气") || userMessage.contains("气温") || userMessage.contains("温度")
                    || userMessage.contains("下雨") || userMessage.contains("预报") || userMessage.contains("湿度"))) {
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
                effectiveUserMessage = userMessage + "\n\n【本次任务】用户要查当前位置/附近的天气，直接调用 ai_weather 工具、不要带 city/lat/lon 参数（系统会自动使用手机定位的城市）；action 按问法选 current(实时)/forecast(预报)/air_quality(空气质量) 等。";
            }
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
                totalTokens += plain.content.length();
                if (!clean.isEmpty() && !isPromptLeakage(clean)) {
                    AILogger.i(TAG, "Plain chat answer: " + truncate(clean, 80));
                    finalAnswer = clean;
                    persistRaw = plain.content; // KV 前缀稳定：用原始输出，勿用 clean
                    handled = true;
                }
            }
            if (!handled) {
                // 普通生成失败 → 简单兜底
                finalAnswer = buildSimpleFallback(userMessage);
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
        int toolRounds = 0;
        int consecutiveDuplicates = 0;
        String lastMeaningfulResponse = null;
        boolean forcedByLoopGuard = false;

        // 最近一次 chatJson 生成是否已有正文 token 流式输出到 UI：
        // - 迭代内直接回答路径：token 已流式 → 迭代结束不重复 streamDirectAnswer
        // - 统一退出路径：generateFinalAnswer 也走 chatJson 流式 → 同样不重复推
        // 每轮迭代开始重置，退出路径读最后一次状态
        lastStreamed = false;

        for (int iteration = 1; iteration <= getAgentMaxIterations(); iteration++) {
            // 总时长预算：超时直接收尾，避免长时间卡死
            if (System.currentTimeMillis() - startTime > TOTAL_TIME_BUDGET_MS) {
                AILogger.w(TAG, "Total time budget exceeded, forcing final response");
                forcedByLoopGuard = true;
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

            // 构建请求 JSON（spec §7.2.1 step b）
            // tool_choice 恒为 auto：实测 tool_choice=required 会让 Qwen3-4B 退化
            // （输出模板标签/重复文本而非 tool_call，FcTest 实证 tool_calls=0 + 乱码），
            // auto 让模型按提示词与工具定义自行判断，需要时自然输出原生 tool_call
            String toolChoice = "auto";
            // 不限制思考量：统一用 FINAL_RESPONSE_MAX_TOKENS（原第一轮 800 会让 enableThinking
            // 模型思考就被截断，触发"使用更强大的模型"兜底；具体安全值由 buildRequestJson 按上下文钳制）
            int iterMaxTokens = FINAL_RESPONSE_MAX_TOKENS;

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
                    genResult = generateWithToolsSync(history, toolsJsonBytes, FINAL_RESPONSE_MAX_TOKENS, iterTemperature, enableThinking);
                }
            } catch (UnsatisfiedLinkError e) {
                // §10.2：chatJson 不可用 → 自动切回旧路径；仍失败则本轮失败（模型原生 FC，无标签兜底）
                AILogger.w(TAG, "chatJson unavailable (" + e.getMessage() + "), fallback to generateWithToolsSync");
                try {
                    genResult = generateWithToolsSync(history, toolsJsonBytes, FINAL_RESPONSE_MAX_TOKENS, iterTemperature, enableThinking);
                } catch (UnsatisfiedLinkError e2) {
                    AILogger.w(TAG, "nativeGenerateWithTools unavailable, generation failed");
                }
            } catch (Exception e) {
                AILogger.e(TAG, "Generate failed at iter " + iteration + ": " + e.getMessage());
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
            if (reasoning != null && !reasoning.isEmpty() && callback != null) {
                callback.onThinkingUpdate("第 " + iteration + " 轮思考: " + truncate(reasoning, 120));
            }

            // 工具调用解析：**唯一权威来源是 native 层**。
            // native 用模板的 PEG parser（common_chat_parse）解析并下发 tool_call 事件，
            // 标签/语法全部由模板动态推导，不需要 Java 侧再猜格式。
            // LOCAL-REGEX-REMOVED(2026-10-09)：原先此处有"native 解析不出就回退正则"的兜底，
            // 那是 makeChatParserParams 未传 parser 那个 bug 的产物 —— 该 bug 已修，
            // 正则兜底会让 native 的退化被静默掩盖（宽松正则能把畸形输出"猜"成工具调用）。
            // 现在改为：native 没给出工具调用，就是真的没有，直接进入无工具分支。
            List<ToolCall> toolCalls = genResult.toolCalls != null
                    ? genResult.toolCalls : new ArrayList<>();

            // 动态注入（CORE-TOOLS-4 后为休眠路径）：
            // 把本轮实际调用的工具名并入 activeTools，下轮起注入其 schema。
            // 当前核心集固定 4 个、且没有任何其它途径扩展 activeTools，而 native PEG 语法
            // 只允许生成 activeTools 里的工具名（见 DEFAULT_CORE_TOOLS 注释），
            // 因此本循环的 !contains 分支恒不成立 —— 保留它是为将来恢复发现通道时复用，
            // 不承担任何实际功能。真正的名字归一（别名/模糊匹配）在 resolveToolNameFuzzy。
            for (ToolCall tc : toolCalls) {
                if (tc.toolName != null && !tc.toolName.isEmpty()) {
                    String resolved = toolManager.resolveToolNameFuzzy(tc.toolName);
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

            // R4-1：执行工具前补齐 tool_call id（assistant 消息与 tool 消息共用同一 id）
            int callSeq = 0;
            for (ToolCall tc : toolCalls) {
                if (tc.id == null || tc.id.isEmpty()) tc.id = "call_" + (++callSeq);
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
            history.add(new ChatMessage("assistant", cleanResponse(response), toolCalls));

            // 循环保护①：去重。同一 tool+args 已执行过则不再执行；连续两轮全是重复调用 → 强制收尾
            List<ToolCall> freshCalls = new ArrayList<>();
            for (ToolCall tc : toolCalls) {
                String key = tc.toolName + "|" + tc.args.toString();
                if (executedCallKeys.contains(key)) {
                    AILogger.w(TAG, "Duplicate tool call skipped: " + key);
                } else {
                    executedCallKeys.add(key);
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
            // 等待全部完成（总超时 = TOOL_TIMEOUT_MS，单个工具卡死不拖住整轮）
            long parDeadline = System.currentTimeMillis() + TOOL_TIMEOUT_MS;
            synchronized (parDone) {
                while (System.currentTimeMillis() < parDeadline) {
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
                } else {
                    result = AIToolResult.fail("工具超时(" + (TOOL_TIMEOUT_MS / 1000) + "秒)");
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
                if (!success) {
                    String alt = getAlternativeTool(tc.toolName);
                    if (alt != null) {
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
                history.add(new ChatMessage("tool", truncate(resultStr, MAX_TOOL_RESULT_LENGTH), tc.id, true));
                AILogger.i(TAG, "Tool " + tc.toolName + (success ? " OK" : " FAIL")
                        + ": " + truncate(resultStr, 800));
            }

            // 上下文长度兜底校验（trim 后理论上不会触发，作为安全网）
            if (countTokensSafe(serializeHistory(history)) + countTokensSafe(toolsJson) > promptBudget) {
                AILogger.w(TAG, "Context still too large after trim, ending loop");
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

        // 最终回答生成：执行过工具调用但循环被强制收尾、尚未产出完整回答时，
        // 基于全部工具结果生成一次总结回复。单次调用、不注入工具（tool_choice=none），
        // 模型无法再发起工具调用 → 不会重新进入循环，也不会反复总结。
        String clean = null;
        String finalGenRaw = null;  // KV 前缀稳定：最终总结的原始输出
        if (toolCallCount > 0) {
            GenerateResult finalGen = generateFinalAnswer(history, enableThinking, toolsJson);
            if (finalGen != null && finalGen.content != null && !finalGen.content.trim().isEmpty()) {
                clean = cleanResponse(finalGen.content);
                finalGenRaw = finalGen.content;
                totalTokens += finalGen.content.length();
                AILogger.i(TAG, "Final summary generated: " + truncate(clean, 120));
            } else {
                AILogger.w(TAG, "Final summary generation failed, falling back to last meaningful response");
            }
        }
        if (clean == null || clean.isEmpty()) {
            clean = cleanResponse(lastMeaningfulResponse);
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
        // 完全没有有效回答，用简单兜底
        String fb = buildSimpleFallback(userMessage);
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
    private void resetSessionIfHistoryChanged(List<HistoryEntry> priorHistory) {
        if (sessionMessages.isEmpty()) return; // 尚无会话状态，稍后首次种子化
        String firstContent = null;
        if (priorHistory != null) {
            for (HistoryEntry h : priorHistory) {
                if (h != null && h.content != null && !h.content.trim().isEmpty()) {
                    firstContent = h.content;
                    break;
                }
            }
        }
        if (firstContent == null) {
            // 对话已清空（bridge 历史空）
            if (!sessionMessages.isEmpty()) {
                AILogger.i(TAG, "Session history cleared, resetting KV-stable sequence");
                sessionMessages.clear();
            }
            sessionTools.clear(); // 工具集随会话重置（下次 run 重新填充核心4工具，见 DEFAULT_CORE_TOOLS）
            return;
        }
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
                    Math.max(1000, budget - FINAL_RESPONSE_MAX_TOKENS));

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
                    FINAL_RESPONSE_MAX_TOKENS, enableThinking, resolveAnswerTemperature());
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
                    FINAL_RESPONSE_MAX_TOKENS, resolveAnswerTemperature(), enableThinking);
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
                    Math.max(1000, budget - FINAL_RESPONSE_MAX_TOKENS));
            lastRequestHistory = new ArrayList<>(trimmed); // 记录实际发送的请求（轮末持久化会话序列）
            String requestJson = buildRequestJson(trimmed, "", "none",
                    PLAIN_CHAT_MAX_TOKENS, enableThinking, resolveAnswerTemperature());
            if (requestJson == null) return null;
            if (aiConfig != null && aiConfig.isUseJsonProtocol()) {
                return generateWithChatJsonSync(requestJson, null);
            }
            return generateWithToolsSync(trimmed, new byte[0],
                    PLAIN_CHAT_MAX_TOKENS, resolveAnswerTemperature(), enableThinking);
        } catch (Exception e) {
            AILogger.e(TAG, "Plain chat generation failed: " + e.getMessage());
            return null;
        }
    }

    private AgentResponse buildResponse(String answer, int tokens, long time, int toolCalls, int steps) {        AgentResponse r = new AgentResponse(answer);
        r.stats = new AgentStats(tokens, time, toolCalls, steps);
        return r;
    }

    // ==================== 原生 FC 同步调用 ====================

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

        NpuEngineRouter.generateWithTools(roles, contents, toolsJson, maxTokens, temperature,
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

        NpuEngineRouter.chatJson(requestJson, new LlamaHelper.JsonCallback() {
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
        });

        try {
            boolean completed = latch.await(SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!completed) {
                AILogger.e(TAG, "chatJson timeout after " + SYNC_TIMEOUT_MS + "ms");
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        if (errorHolder[0] != null) {
            // cancelled（R3-2）与真实错误统一视为本轮失败，走 run() 统一退出路径
            AILogger.e(TAG, "chatJson error: " + errorHolder[0]);
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
            AILogger.i(TAG, "effective temperature: "
                    + UserModelParamsManager.getInstance(appContext).describeEffectiveTemperature());
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
                msgs.put(msg);
            }
            req.put("messages", msgs);

            if (toolsJson != null && !toolsJson.isEmpty()) {
                req.put("tools", new JSONArray(toolsJson));
            }
            req.put("tool_choice", toolChoice);
            req.put("enable_thinking", enableThinking);   // R8-1：调用方传入（Agent 模式默认 false）
            // P0(2026-10-07)：max_tokens 按上下文钳制——生成空间 = n_ctx − prompt实际token − native guard − 模板overhead。
            // 原实现恒发 FINAL_RESPONSE_MAX_TOKENS=4000，而旧 80% 预算下生成空间仅 2454，
            // 长回答必然触发 native "Context full, stopping generation"（native-lib.cpp:3115）
            // 且上层无感知（截断回复被当完整回答交付）。钳制后生成在 max_tokens 处自然停止，
            // 永不撞 ctx_full；即使 Java token 计数低估模板开销，256 下限也能给出短回答而非硬截断。
            int promptTokens = countTokensSafe(serializeHistory(history))
                    + countTokensSafe(toolsJson != null ? toolsJson : "")
                    + TEMPLATE_OVERHEAD_RESERVE;
            int safeMax = getEffectiveContextSize() - promptTokens - NATIVE_GENERATION_RESERVE;
            int clamped = Math.max(256, Math.min(maxTokens, safeMax));
            req.put("max_tokens", clamped);
            req.put("temperature", temperature);   // R8-2 / USER-PARAMS：由调用方按"决策轮 vs 回答轮"传入（默认均 0.7）。过低(0.6)会让 2B 在工具调用时过度保守，只敢用默认 action/参数；过高则破坏 chatJson 结构化输出稳定性
            req.put("top_p", 0.9f);
            req.put("top_k", 40);

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
     * 计算单次推理的 prompt token 预算：
     * 按实际生效上下文的 80% 计算（80% prompt + 剩余留作输出），上限留出足够推理空间。
     */
    private int computePromptBudget() {
        try {
            int ctxSize = getEffectiveContextSize();
            if (ctxSize > 0) {
                // P1(2026-10-07)：预算 = n_ctx − 输出预留 − native guard。
                // 原固定 80% 比例只约束 prompt，把生成空间压到 2454（n_ctx=12288 时）
                // < FINAL_RESPONSE_MAX_TOKENS=4000 → 长回答必然触发 native
                // "Context full, stopping generation"（native-lib.cpp:3115）且无上层信号。
                // 输出预留按真实最大生成上限计：n_ctx=12288 → 预算 7776，生成空间 4512 ≥ 4000。
                int budget = ctxSize - FINAL_RESPONSE_MAX_TOKENS - NATIVE_GENERATION_RESERVE;
                if (budget > 0) return budget;
            }
        } catch (Throwable t) {
            AILogger.w(TAG, "computePromptBudget failed: " + t.getMessage());
        }
        return 4000;
    }

    /**
     * 工具结果注入总结请求的最大字符数（token 感知，防超 n_ctx）。
     * 预算 = prompt 预算 - 输出预留(1000) - 固定预留(512：system+用户问题+总结指令)；
     * 中文≈1 token/字符，故字符数直接取可用 token 数（保守上限）；
     * 至少保留 128 token 给工具结果，失败回退 MAX_TOOL_RESULT_LENGTH。
     * 注：注入的是 user 消息（绕过 trim 对 tool 消息的 2500 字符压缩），
     * 因此必须在注入时就按预算截断，否则 4K/8K 上下文下会撞预算。
     */
    private int toolResultInjectionLimitChars() {
        try {
            int budget = computePromptBudget();
            int availableTokens = budget - FINAL_RESPONSE_MAX_TOKENS - 512;
            if (availableTokens < 128) availableTokens = 128;
            return availableTokens;
        } catch (Throwable t) {
            return MAX_TOOL_RESULT_LENGTH;
        }
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
     * 注入当前真实日期（只到天，不带时分）：保证同一天内 prompt 内容不变，
     * KV 缓存可命中；具体时刻由 time_date 工具提供（fcMode）或如实说明（普通对话）。
     */
    private static String buildCurrentTimeLine(boolean fcMode) {
        String today = new SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA).format(new Date());
        String timeHint = fcMode
                ? "问具体时刻时调用 time_date(action=now) 工具获取"
                : "问具体时刻时如实说明只能提供日期，无法给出准确时刻";
        return "【当前真实日期】" + today + "\n"
                + "用户问日期/星期时直接采用以上日期；" + timeHint + "。不要使用训练数据中的日期。\n\n";
    }

    private String buildSystemPrompt() {
        // 极简提示词：意图命中走程序化输出（不经模型），意图未命中走普通对话
        // （模型直接回答、不注入工具），因此提示词只保留身份，
        // 工具调用规则/回答格式等指令段全部移除（模型已不再调用工具）。
        StringBuilder sb = new StringBuilder();
        // ---- 本地普通对话模式：纯聊天答疑角色 ----
        sb.append(buildCurrentTimeLine(false));
        sb.append("【角色】\n");
        sb.append("你是答题宝App中的AI聊天助手，是App内\"AI对话\"功能模块的助手。\n");
        sb.append("你的工作：与正在使用答题宝的用户进行自然对话，解答学习、生活、技术等各类问题，给出清晰有用的回答。\n");
        sb.append("你的方式：不调用任何工具，完全依靠自身知识回答；不确定时如实说明，不编造。\n");
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
    private String buildFcSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append(buildCurrentTimeLine(true));
        sb.append("【角色】\n");
        // PERSONA-ALIGN(2026-10-07)：角色段与在线 Agent（OnlinePromptBuilder.buildPersonaSection）
        // 逐字对齐——同一产品身份（答题宝App的AI聊天助手/AI对话功能模块）。
        // 两处按本地能力适配：①"（在线Agent模式）"→"（Agent模式）"（本地不能自称在线）；
        // ②"结构化信息用UI组件展示"→"清晰的分段与列表"（本地 ui_component 大 schema 不注入，
        // 照搬会诱导模型调用不存在的工具）。
        sb.append("你是答题宝App中的AI聊天助手，是App内\"AI对话\"功能模块的助手（Agent模式）。\n");
        // CAPABILITY-HONEST(2026-10-11)：本地 Agent 只有 4 个工具（时间/定位/天气/联网搜索），
        // 原文案"调用多种工具完成查询、搜索、生成、处理等任务"会让模型承诺做不到的事
        // （生成文件/读本地文件/画图/跑代码），用户要了却办不到 —— 属于 persona 与能力不符。
        // TOOL-SELECTION-EXACT(2026-10-11)：工具集从 9 个收到 4 个后，选用规则可以钉死到
        // 每一步，因此把工具清单与选用规则合并为一节（原"你的工作"与"工具选择"两处重复列举
        // 工具名，属冗余）。全部用**确定句**（必须/一律/直接），不用"可以/建议"这类可选项语气 ——
        // 4B 模型对可选语气倾向于忽略。五条硬约束按实测痛点排序：
        // ① 只准这四个 —— 未注入 schema 的工具名 native 根本生成不出来（PEG 逐字枚举），
        //    模型凭训练记忆乱点名会让整轮 tool_call 退化成正文；
        // ② 天气位置参数的精确写法（按 action 语义核对，不是"天气整体"一句话）——
        //    见下方 ACTION-LOCATION 注释：七个 action 都必须有位置，两种给法（不带参数走自动定位 /
        //    传 city 走内部换算）在实现上有实质差别。必须与 executeToolSafely、fillWeatherLocation、
        //    AIWeatherManager 的七个 action 分支保持一致，否则模型会选错给法；
        // ③ 静态知识别调工具 —— 原"实时/动态信息必须用工具获取"边界太粗，把可直答的
        //    静态知识也推去调工具（浪费轮次）；
        // ④ 办不到要如实说 + 不许拿别的工具凑 —— 4 个工具覆盖不了生成文件/读文件/画图/
        //    跑代码，不写这条模型会硬用 network_search / ai_weather 去凑无关的答案。
        // ⑤ 工具失败必须改参重调 —— 真机实测的行为缺陷，见下方 RETRY-ON-TOOL-FAILURE 注释。
        // 注：四工具全是只读查询，无外部副作用，故删掉了在线路径照抄来的"删除/覆盖文件先确认"
        // 死话（本地连该能力都没有），换成对四工具真实存在的风险：不许补充工具未返回的数据。
        // 全部为静态文本，同一天内字节不变，不破坏 buildCurrentTimeLine 的 KV 前缀复用设计。
        sb.append("你的工作：与用户对话答疑；需要实时信息时只能调用下列四个工具，"
                + "不要调用这四个之外的任何工具。\n");
        sb.append("你的方式：实时/时效性信息必须用工具查证，禁止用训练数据猜测；"
                + "静态知识直接回答，不必调用工具；结构化信息用清晰的分段与列表展示。\n");
        // ACTION-LOCATION(2026-10-11)：ai_weather 的每个 action 都**必须有位置**
        // （current/forecast/hourly/air_quality/alerts/indices/all 七个分支在缺位置时统一
        // 返回"请提供城市名称或经纬度"，且已确认**不再静默回退"北京"**，见 AIWeatherManager:2820）。
        // 两种给法在实现上有实质差别，必须写明，否则模型会选错：
        //   ① 不带任何位置参数 → executeToolSafely 的 fillWeatherLocation 自动补手机定位的**精确经纬度**
        //      （走坐标分支，最准、且零解析开销）；
        //   ② 传 city → air_quality/alerts 这类 URL 把经纬度拼进**路径**，内部必须先经
        //      getHefengLatLon(city) 把城市名转坐标，多一步解析且受解析范围限制
        //      （本地 CSV + Android Geocoder，非中文地名可能解析不出 → 返回"无法获取城市位置信息"）。
        // 所以"查用户所在城市"用 ①，"查指定城市"必须用 ②；两者都不要额外调 location 工具。
        sb.append("工具选择：time_date 查具体时刻；location 只用于用户明确要求定位、导航、"
                + "\"我在哪\"这类问题时；ai_weather 查天气——它每个 action 都必须有位置，二选一："
                + "（1）用户没指定城市（问\"今天天气\"\"这边天气\"）时，调用 ai_weather 不要带 city/lat/lon 参数，"
                + "系统会自动使用手机 GPS 定位的经纬度，最准；"
                + "（2）用户已指定城市（问\"北京天气\"）时传 city 参数，工具内部会自行换算坐标，"
                + "不要改用手机定位结果、也不要编造该城市的数据；若城市名换算不出位置，"
                + "如实告知查不到，不要用别的城市顶上。"
                + "network_search 查其他实时信息（油价、股价、新闻、赛事等）。"
                + "禁止用天气工具回答非天气问题；日期与星期直接采用上面给出的【当前真实日期】，不必调工具。\n");
        sb.append("你的边界：生成文件、读取本地文件、画图、运行代码、操作手机等超出上述四个工具的能力，"
                + "如实说明做不到，可建议用户换用在线模式，不要硬用不相关的工具凑答案；"
                + "工具返回什么就说什么，禁止补充工具未返回的数据。\n");
        // RETRY-ON-TOOL-FAILURE(2026-10-11)：真机实测（问"五台县的空气质量"）暴露的行为缺陷 ——
        // 上一轮工具失败后，模型把失败**写进回答交付给用户**并顺带念了一遍"应该改传经纬度"，
        // 整轮 toolCalls=0，没有重试。日志证据：
        //   [15:11:53] generateWithChatJsonSync result: ... toolCalls=0 stopReason=eos
        //   [15:11:53] No tool call at iteration 1, using model answer directly
        // 而引擎循环本身是支持重调的（失败结果回填后 continue，见 executeToolSafely 与循环骨架）。
        // 故用确定句要求"改参数重调"，并补第二句堵住"转而用训练数据回答"这条更糟的退路
        // （本工具的天气类数据只能来自和风接口，编造风险最高）。
        sb.append("你的做法：工具返回失败时，用修正后的参数重新调用该工具，不要把\"建议怎么改\""
                + "当作回答交付给用户，也不要在工具失败后改用训练数据给出结果。\n");
        // PARENT-REGION-RETRY(2026-10-11)：城市表只到市/县级，用户可能说乡镇/街道等更小单位
        // （如"阳白乡"）。工具查不到时会返回带提示的失败信息，明确要求模型先用地理知识判断
        // 其上级县市、再用上级地名重查；不确定时用 network_search 查归属。这条必须写进 prompt，
        // 因为**工具是代码、无法自己推理**，模型才是能做"阳白乡属于五台县"这一步的主体。
        // 同时用最后半句堵住"随便挑个城市顶上"这条歧路（那正是 NO-CITY-SUBSTITUTE 要防的）。
        sb.append("遇到乡镇、街道等小于城市的地名（城市表只到市/县级）：先用你的地理知识判断"
                + "它属于哪个县或市，然后用那个上级地名重新调用 ai_weather"
                + "（例：\"阳白乡\"属于\"五台县\"，就传 city=五台县）；"
                + "拿不准就先用 network_search 搜\"<地名>属于哪个县市\"再查。"
                + "绝不为了凑出结果而随便挑一个城市、也不要编造该地天气。\n");
        sb.append("你的风格：用中文，口语化、简洁有条理，先结论后细节。\n\n");
        // RULES-REMOVED(2026-10-07) 已被 CORE-TOOLS-4(2026-10-11) 部分回退：
        // 当时的理由是"与在线 Agent 的最小提示词一致、靠原生 FC + tools schema 引导"，前提是
        // 工具集 9 个且靠模型自主发现。现在工具集固定 4 个、且 schema 无法表达跨工具选用规则，
        // 故上面恢复了 4 行规则（工作/方式/工具选择/边界）。若将来再删规则段，需先确认
        // "只准这四个"与"能力边界外怎么说"这两条约束能从别处得到保证，否则模型会：
        // ① 点名未注入的工具导致整轮退化；② 承诺生成文件/读文件等做不到的事。
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
            // resolveToolDefinition 支持静态定义 + 动态工具反射生成
            ToolDefinition def = toolManager.resolveToolDefinition(name);
            if (def == null) continue;
            try {
                JSONObject tool = new JSONObject();
                tool.put("type", "function");
                JSONObject function = new JSONObject();
                function.put("name", def.getName());
                // 完整描述注入：不截断，让本地模型完整理解每个工具的能力边界。
                function.put("description", def.getDescription());
                JSONObject params = new JSONObject();
                params.put("type", "object");
                JSONObject props = new JSONObject();
                JSONArray required = new JSONArray();
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

    private String serializeHistory(List<ChatMessage> history) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : history) {
            sb.append(m.role).append(": ").append(m.content).append("\n");
        }
        return sb.toString();
    }

    // ==================== 提示词泄漏防护 ====================

    /** 系统提示词的特征片段：命中即判定为泄漏 */
    private static final String[] PROMPT_LEAK_MARKERS = {
            "【规则】", "【回答】", "【环境上下文】",
            "你是答题宝AI助手", "你是一个智能AI助手，可以使用工具", "你是一个智能AI助手，拥有多种工具",
            "系统会自动执行你发起的工具调用", "严禁自问自答、重复已说过的内容"
    };

    /**
     * 检测模型回复是否为提示词规则泄漏（把 system prompt 原文当回答输出）。
     */
    private boolean isPromptLeakage(String text) {
        if (text == null || text.isEmpty()) return false;
        for (String marker : PROMPT_LEAK_MARKERS) {
            if (text.contains(marker)) return true;
        }
        return false;
    }

    // ==================== 响应清理 ====================

    private String cleanResponse(String response) {
        if (response == null) return "";
        // 思考段已由 stripThinkingSections 按模板标签剥离，此处不再按
        // <think>/<thought> 字面量二次清理（标签一律取自模板）。
        String cleaned = stripThinkingSections(response);
        cleaned = cleaned
                .replaceAll("(?s)<tool_response>.*?</tool_response>", "")
                // ① 剥闭合的 tool_call / tool_calls 标签（含内容）
                .replaceAll("(?s)<tool_calls?>.*?</tool_calls?[^>]*>", "")
                // ② 剥未闭合的 tool_call 开始标签及后续残留：模型常见漏输出 </tool_call>，
                //    开始标签之后的 JSON 会被一并吞掉，避免 {"name":..} 残留进最终消息
                .replaceAll("(?s)<tool_calls?>[^>]*>(?:(?!</?tool_calls?[^>]*>).)*$", "")
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

    /**
     * 判断当前加载的模型是否支持思考链（thinking / chain-of-thought）。
     * 规则：模型名包含 qwen3、qwq、deepseek-r1、thinking 等关键词即视为思考模型。
     */
    private boolean isThinkingModel() {
        try {
            String name = aiService.getCurrentModelName();
            if (name == null) return false;
            String lower = name.toLowerCase();
            return lower.contains("qwen3") || lower.contains("qwq")
                    || lower.contains("deepseek-r1") || lower.contains("thinking")
                    || lower.contains("deepthink");
        } catch (Exception e) {
            return false;
        }
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
    private String resolveCallId(JSONObject json) {
        String id = json.isNull("id") ? null : json.optString("id", null);
        return (id == null || id.isEmpty()) ? null : id;
    }

    /** 兼容 arguments(JSON 对象/JSON 字符串) / parameters / args 三种取值 */
    private JSONObject resolveArgsObject(JSONObject json) {
        Object args = json.opt("arguments");
        if (args == null) args = json.opt("parameters");
        if (args == null) args = json.opt("args");
        if (args instanceof JSONObject) return (JSONObject) args;
        if (args instanceof String) {
            String s = ((String) args).trim();
            if (!s.isEmpty()) {
                try {
                    return new JSONObject(s);
                } catch (Exception ignored) {
                    AILogger.w(TAG, "Tool call arguments string not JSON: " + truncate(s, 80));
                }
            }
        }
        return new JSONObject();
    }

    /** strftime 格式（%Y/%m/%d/%A 等）→ Java SimpleDateFormat 格式 */
    private static String convertStrftime(String fmt) {
        if (fmt == null || fmt.isEmpty()) return null;
        return fmt.replace("%Y", "yyyy").replace("%m", "MM").replace("%d", "dd")
                .replace("%H", "HH").replace("%M", "mm").replace("%S", "ss")
                .replace("%A", "EEEE").replace("%a", "EEE")
                .replace("%B", "MMMM").replace("%b", "MMM");
    }

    /** 程序拼当前时间文本（format 支持 strftime 或 Java 格式，null 用默认） */
    private String formatCurrentTime(String format) {
        try {
            String javaFmt = convertStrftime(format);
            if (javaFmt == null || javaFmt.isEmpty()) {
                javaFmt = "yyyy年M月d日 EEEE HH:mm";
            }
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(javaFmt, java.util.Locale.CHINA);
            return sdf.format(new java.util.Date());
        } catch (Throwable t) {
            AILogger.w(TAG, "Format time failed: " + t.getMessage());
            try {
                return new java.text.SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", java.util.Locale.CHINA)
                        .format(new java.util.Date());
            } catch (Throwable ignored) {
                return String.valueOf(System.currentTimeMillis());
            }
        }
    }

    /** 工具失败时的替代工具映射：引导模型换工具而非放弃（4B 模型需要明确指引）
     *
     *  <p>CORE-TOOLS-4(2026-10-11)：备选**只能给核心 4 个之内**的工具
     *  （time_date / location / ai_weather / network_search）。
     *  原因：native PEG 语法把工具名按本次请求的 tools 数组逐字枚举
     *  （chat-peg-parser.cpp atomic(literal(name))），没注入 schema 的名字模型生成不出来；
     *  建议一个调不动的工具，结果是模型把工具调用写成正文、整轮退化 —— 比不给建议更糟。
     *  因此 file_reader / python_* / excel_tool / image_gen / smart_research 等原备选已删除。</p> */
    private static String getAlternativeTool(String toolName) {
        if (toolName == null) return null;
        switch (toolName) {
            // 措辞与 system prompt / ai_weather.whenToUse 的"位置参数"规则保持一致：
            // 用户所在城市 = 不带任何位置参数（由 fillWeatherLocation 自动补手机定位经纬度）；
            // 外地城市 = 必须显式传 city。不要在这里出现"先调 location"这类多烧一轮的说法。
            case "ai_weather": return "去掉 city/lat/lon 重新调用（改用手机定位城市）、或 network_search 搜天气";
            // 不给"再试一次 network_search"这类建议：它刚失败，重试同一工具只会白烧一轮。
            case "network_search": return "换更具体的关键词重搜、或用 ai_weather 查天气";
            case "location": return "网络搜索该城市的信息；用户问本地天气时不必先定位，直接调 ai_weather";
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
        if (!"network_search".equals(toolName)) {
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
                while (!done[0]) {
                    long rem = TOOL_TIMEOUT_MS - (System.currentTimeMillis() - start);
                    if (rem <= 0) break;
                    done.wait(Math.min(rem, 500));
                }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        AIToolResult result = holder.get();
        if (result != null) return result;
        return AIToolResult.fail("工具超时(" + (TOOL_TIMEOUT_MS / 1000) + "秒)");
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

    private String buildSimpleFallback(String userMessage) {
        return "抱歉，我暂时无法完整回答这个问题，请换一种方式描述。";
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

package com.oilquiz.app.ai.agent.software.engine;

import android.content.Context;

import com.oilquiz.app.ai.agent.software.model.AgentResponse;
import com.oilquiz.app.ai.agent.software.model.AgentStats;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.refactor.AIConfig;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.service.OnlineInferenceService;
import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.ai.tool.openai.ParamDefinition;
import com.oilquiz.app.ai.tool.openai.ToolDefinition;
import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;

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
    /** Agent 总轮次上限（与上下文容量联动：8K 历史容量≈8-10轮完整保留，
     *  12 轮为平衡点——再多则旧历史被裁剪、模型"忘前面"，轮次白给） */
    private static final int MAX_ITERATIONS_BASE = 12;
    /** 循环保护：实际工具调用轮次上限（两跳检索+多工具链≈6轮够用，
     *  6 覆盖 95% 任务且防小模型空转；去重/重复检测/时间预算仍兜底） */
    private static final int MAX_TOOL_ROUNDS = 6;
    /** 循环保护：整个 Agent 执行的总时长上限（含工具执行与推理） */
    private static final long TOTAL_TIME_BUDGET_MS = 180000;
    /** 单次推理的 prompt token 预算系数（占上下文容量的比例，下限 0.15） */
    private static final double PROMPT_BUDGET_RATIO = 0.8;
    /** 工具结果最大字符数（超长时截断，节省上下文） */
    private static final int MAX_TOOL_RESULT_LENGTH = 2000;
    /** 最终回复最大生成 token（与预算计算保持一致） */
    private static final int FINAL_RESPONSE_MAX_TOKENS = 1000;
    /** 普通对话（意图未命中）最大生成 token：小模型生成慢，500 足够普通回答 */
    private static final int PLAIN_CHAT_MAX_TOKENS = 500;
    /** 工具执行超时（毫秒） */
    private static final long TOOL_TIMEOUT_MS = 15000;
    /** 工具失败重试次数 */
    private static final int MAX_RETRIES = 1;
    /** 同步调用超时（毫秒） */
    private static final long SYNC_TIMEOUT_MS = 60000;
    /** 单轮推理最大生成 token（与预算计算保持一致） */
    private static final int ITER_MAX_TOKENS = 500;
    /** 单次执行最多注入的工具数（常驻 3 + 关键词命中，保证组合工具能力） */
    private static final int MAX_TOOLS_PER_RUN = 5;
    /** 工具 schema 的 token 预算：描述已截断为精简版（≤150 字符/工具），
     *  2000 token 可容纳全部 ~40 个工具注入——模型一眼看全工具池，
     *  减少 tool_registry 检索跳数；关键词命中工具仍排最前优先注入 */
    private static final int MAX_SCHEMA_TOKENS = 2000;
    /** 注入的工具描述最大字符数：超过则截断为精简版（完整描述经
     *  tool_registry(get=工具名) 按需获取），缩小注入 schema 让更多工具进预算 */
    private static final int MAX_TOOL_DESC_CHARS = 150;
    /** 用户问题长度上限（字符） */
    private static final int MAX_USER_MESSAGE_CHARS = 2000;
    /** UI 交互等待时长（毫秒）：弹窗问用户，超时未操作则回退文本追问 */
    private static final long UI_WAIT_MS = 30000;

    /** 工具 → 中文标签（程序化总结时使用，覆盖全部常用注册工具） */
    private static final java.util.Map<String, String> TOOL_LABELS = new java.util.HashMap<>();
    static {
        TOOL_LABELS.put("ai_weather", "天气");
        TOOL_LABELS.put("location", "位置");
        TOOL_LABELS.put("network_search", "搜索");
        TOOL_LABELS.put("webpage_reader", "网页");
        TOOL_LABELS.put("smart_research", "调研");
        TOOL_LABELS.put("database", "题库");
        TOOL_LABELS.put("system_resource", "系统资源");
        TOOL_LABELS.put("dynamic_clock", "时间");
        TOOL_LABELS.put("calculator", "计算");
        TOOL_LABELS.put("python_analyze_data", "数据分析");
        TOOL_LABELS.put("file_reader", "文件");
        TOOL_LABELS.put("file_analyzer", "文件分析");
        TOOL_LABELS.put("file_generator", "文件生成");
        TOOL_LABELS.put("excel_tool", "Excel");
        TOOL_LABELS.put("workspace", "工作区");
        TOOL_LABELS.put("image_gen", "图片生成");
        TOOL_LABELS.put("dashscope_media", "百炼文生图/视频");
        TOOL_LABELS.put("python_execute", "代码");
        TOOL_LABELS.put("app_operation", "应用");
        TOOL_LABELS.put("ui_component", "系统UI");
        TOOL_LABELS.put("system_ui_control", "系统UI"); // 遗留名，保留标签兼容
        TOOL_LABELS.put("memory", "记忆");
        TOOL_LABELS.put("deepseek_balance", "余额");
        TOOL_LABELS.put("deepseek_usage_calc", "用量");
        TOOL_LABELS.put("clean_import_files", "模型清理");
        TOOL_LABELS.put("speech_synthesis", "朗读");
        TOOL_LABELS.put("voice_input", "语音输入");
        TOOL_LABELS.put("tool_registry", "工具");
        TOOL_LABELS.put("get_models_profile", "模型");
        TOOL_LABELS.put("permission_manager", "权限");
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

    /** 关键词路由表：3 个工具（时间已由环境上下文注入，无需工具） */
    private static final String[][] TOOL_ROUTES = {
            {"ai_weather", "天气,气温,温度,下雨,下雪,刮风,湿度,空气质量,紫外线,预报,雾霾,台风"},
            {"location", "位置,定位,我在哪,附近,周边,坐标,经纬度,地址,城市"},
            {"time_date", "时间,日期,几点,今天几号,星期几,现在几点,当前时间,几月几号"},
            {"network_search", "搜索,搜一下,查一下,新闻,资讯,热点,最新,油价"},
            {"text_tools", "json格式化,json校验,base64,url编码,url解码,正则提取,转大写,转小写,去空白,字数统计,文本处理,编码解码"},
            {"unit_converter", "换算,单位转换,单位换算,厘米,公斤,磅,华氏,摄氏,千米,英里,英寸,英尺,加仑,公顷"},
            {"dashscope_media", "文生视频,生成视频,视频生成,ai视频,ai生成视频,生成一个视频,生成一段视频"},
    };
    /** 常驻基础工具：关键词命中后补入（时间不再需要，环境上下文已注入） */
    private static final String[] DEFAULT_CORE_TOOLS = {"ai_weather", "network_search"};

    /** 动态工具关键词路由表：程序硬编码，消息命中关键词即注入对应动态工具
     *  （不依赖模型猜工具名；工具需已注册，未注册自动跳过） */
    private static final String[][] DYNAMIC_TOOL_ROUTES = {
            {"deepseek_usage_calc", "余额,用量,费用,deepseek,花费,计费,花了"},
            {"deepseek_balance", "余额,deepseek"},
            {"dynamic_clock", "时间,日期,现在几点,时钟,星期几"},
            {"clean_import_files", "清理模型,删除模型,模型清理,清理文件"},
            {"show_progress", "进度,进度条,汇报进度"},
    };

    private final AIService aiService;
    private final AIToolManager toolManager;
    private final AIConfig aiConfig;
    private final android.content.Context appContext;
    /** UI 交互辅助：程序直接用 ui_component 组件与用户交互（追问/确认/进度/卡片） */
    private final UiInteractor uiInteractor;
    private LoopCallback callback;

    public interface LoopCallback {
        void onIterationStart(int iteration, String promptSummary);
        void onIterationEnd(int iteration, String response);
        void onThinkingUpdate(String thought);
        void onToolCall(String toolName, String args);
        void onToolResult(String toolName, boolean success, String result);
        void onToken(String token);
        void onComplete(String finalText);
        void onError(String error);
        void onInferenceProgress(int tokenCount, float tokensPerSecond);
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

        // 按需动态注入：初始只注入与本次问题相关（关键词命中）的工具 + 检索入口，
        // 不一股脑全量注入。FC 循环中模型实际调用/检索到的工具，会按需加入注入集
        // （见 FC 循环内 activeTools 增长逻辑），需要哪个工具注入哪个。
        List<String> selectedTools = selectRelevantTools(userMessage);
        // 检索型工具始终注入（逃生口）：
        // - tool_registry：工具发现（list/search/get），模型主动查询需要的工具
        // - control_lookup：低频 UI 控件参数查询
        for (String metaTool : new String[]{"tool_registry", "control_lookup"}) {
            if (!selectedTools.contains(metaTool)) {
                selectedTools.add(0, metaTool);
            }
        }
        // 不注入大 schema 的 UI 工具：执行不受影响（toolManager.executeTool 直接可用），
        // 模型需要时经 tool_registry(get=ui_component) 或 control_lookup 按需获取参数定义。
        selectedTools.removeIf(n -> "ui_component".equals(n) || "ui_component_plugin".equals(n));
        // 动态注入集合：初始 = 关键词命中 + 检索入口；FC 循环按模型实际使用增长
        final java.util.Set<String> activeTools = new java.util.LinkedHashSet<>(selectedTools);
        String toolsJson = buildToolsJson(new ArrayList<>(activeTools));
        byte[] toolsJsonBytes = toolsJson.getBytes(StandardCharsets.UTF_8);
        AILogger.i(TAG, "Initial tools: " + activeTools.size() + " tools, schema len: " + toolsJson.length());
        if (aiConfig != null && aiConfig.isFcEnabled()) {
            showToast("🤖 本地Agent就绪: " + activeTools.size() + " 个核心工具（按需可查询更多）");
        }

        // 单次推理的 prompt token 预算（结合配置上下文容量）
        final int promptBudget = computePromptBudget();
        AILogger.i(TAG, "Prompt budget: " + promptBudget + " tokens");

        // FC 模式（local_fc_enabled）：system 提示词带 Qwen 原生工具调用规则（<tool_call> 格式）
        boolean modelFcMode = aiConfig != null && aiConfig.isFcEnabled();

        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage("system", modelFcMode ? buildFcSystemPrompt() : buildSystemPrompt()));
        // 多轮上下文：把最近几轮对话注入历史（system → 历史 → 当前问题），
        // 让模型能理解"那明天呢？"之类的指代；超预算由 trimHistoryToFit 裁剪
        if (priorHistory != null) {
            for (HistoryEntry h : priorHistory) {
                if (h == null || h.content == null || h.content.trim().isEmpty()) continue;
                history.add(new ChatMessage(h.role, h.content));
            }
        }
        history.add(new ChatMessage("user", userMessage));

        // ===== 本地 Agent：模型自主处理（意图编排已移除）=====
        // 用户确认模型可自主：不再用 IntentEngine 确定性编排（简化设计），
        // FC 开启 → 模型自主调工具（Qwen 原生 <tool_call>）；FC 关闭 → 普通对话。
        boolean handled = false;
        String finalAnswer = null;

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

            // 动态注入：每轮从 activeTools 重建 schema——模型已调用/检索过的工具
            // 按需加入注入集（需要哪个注入哪个，不一股脑全量），
            // 初始集外的工具经 tool_registry 查询后由模型调用时自动注入。
            toolsJson = buildToolsJson(new ArrayList<>(activeTools));
            toolsJsonBytes = toolsJson.getBytes(StandardCharsets.UTF_8);

            // 每轮推理前裁剪历史，确保单次推理 prompt 不超预算（防截断/decode崩溃）
            history = trimHistoryToFit(history, toolsJson, promptBudget);

            long genStart = System.currentTimeMillis();
            GenerateResult genResult = null;

            // 构建请求 JSON（spec §7.2.1 step b）
            // tool_choice 恒为 auto：实测 tool_choice=required 会让 Qwen3-4B 退化
            // （输出模板标签/重复文本而非 tool_call，FcTest 实证 tool_calls=0 + 乱码），
            // auto 让模型按提示词与工具定义自行判断，需要时自然输出原生 tool_call
            String toolChoice = "auto";
            int iterMaxTokens = iteration == 1 ? ITER_MAX_TOKENS : FINAL_RESPONSE_MAX_TOKENS;
            String requestJson = buildRequestJson(history, toolsJson, toolChoice, iterMaxTokens, enableThinking);
            if (requestJson == null) {
                AILogger.e(TAG, "buildRequestJson returned null at iteration " + iteration + ", breaking");
                break;
            }

            try {
                if (aiConfig != null && aiConfig.isUseJsonProtocol()) {
                    // 新协议：chatJson → 统一 onJson 事件
                    genResult = generateWithChatJsonSync(requestJson);
                } else {
                    // 回退开关：旧 generateWithTools 路径
                    genResult = generateWithToolsSync(history, toolsJsonBytes, 1500, 0.6f, enableThinking);
                }
            } catch (UnsatisfiedLinkError e) {
                // §10.2：chatJson 不可用 → 自动切回旧路径；仍失败则本轮失败（模型原生 FC，无标签兜底）
                AILogger.w(TAG, "chatJson unavailable (" + e.getMessage() + "), fallback to generateWithToolsSync");
                try {
                    genResult = generateWithToolsSync(history, toolsJsonBytes, 1500, 0.6f, enableThinking);
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
            int iterTokens = response != null ? response.length() : 0;
            totalTokens += iterTokens;

            if (callback != null) {
                long elapsed = System.currentTimeMillis() - genStart;
                float tps = elapsed > 0 ? (iterTokens * 1000.0f) / elapsed : 0;
                callback.onInferenceProgress(totalTokens, tps);
                callback.onIterationEnd(iteration, response);
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

            // 工具调用解析：优先 C++ 层 common_chat_parse（原生 JSON tool_call）；
            // 实测 Qwen3-4B 输出的是 <tool_call>{...}</tool_call> 标签格式（FcTest 实证），
            // common_chat_parse 解析不出，需回退标签解析才能拿到工具调用
            List<ToolCall> toolCalls = genResult.toolCalls != null
                    ? genResult.toolCalls : new ArrayList<>();
            if (toolCalls.isEmpty() && response != null) {
                toolCalls = parseToolCallsTag(response);
            }

            // 按需动态注入：模型本轮调用/检索到的工具加入注入集，下轮起注入其 schema
            // （tool_registry 查到的工具由模型实际调用后自动注入，无需手动全量）
            for (ToolCall tc : toolCalls) {
                if (tc.toolName != null && !tc.toolName.isEmpty() && !activeTools.contains(tc.toolName)) {
                    activeTools.add(tc.toolName);
                    AILogger.i(TAG, "Dynamic injection: added tool " + tc.toolName + " to active set");
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
                lastMeaningfulResponse = response;   // 工具轮空内容不覆盖最后有效回复
            }

            if (toolCalls.isEmpty()) {
                AILogger.i(TAG, "No tool call at iteration " + iteration + ", using model answer directly");
                String cleanResponse = cleanResponse(response);

                if (cleanResponse.isEmpty()) {
                    // 清理后为空（可能全是 </think> 标签），break 走统一退出路径
                    AILogger.w(TAG, "Clean response empty after stripping tags, breaking");
                    break;
                }

                // 提示词泄漏检测：模型把系统规则当回答输出时，break 走统一退出路径
                if (isPromptLeakage(cleanResponse)) {
                    AILogger.w(TAG, "Prompt leakage detected, breaking");
                    break;
                }
                // ===== 降级重试结束 =====

                // 模型的回答即最终答案：直接流式输出，不再二次生成
                streamDirectAnswer(cleanResponse);
                if (callback != null) callback.onComplete(cleanResponse);
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
                if (callback != null) callback.onToolCall(tc.toolName, tc.args.toString());
                AIToolResult result;
                if (parResults[i] instanceof AIToolResult) {
                    result = (AIToolResult) parResults[i];
                } else if (parResults[i] instanceof Throwable) {
                    result = AIToolResult.fail("工具异常: " + ((Throwable) parResults[i]).getMessage());
                } else {
                    result = AIToolResult.fail("工具超时(" + (TOOL_TIMEOUT_MS / 1000) + "秒)");
                }
                boolean success = result.isSuccess();
                String resultStr = success ? String.valueOf(result.getResult()) : result.getErrorMessage();
                showToast(success ? "✅ " + tLabel + " 完成" : "❌ " + tLabel + " 失败: " + truncate(resultStr, 40));
                if (callback != null) callback.onToolResult(tc.toolName, success, resultStr);
                history.add(new ChatMessage("tool", truncate(resultStr, MAX_TOOL_RESULT_LENGTH), tc.id, true));
                AILogger.i(TAG, "Tool " + tc.toolName + (success ? " OK" : " FAIL")
                        + ": " + truncate(resultStr, 100));
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

        // 最终回答生成：执行过工具调用但循环被强制收尾、尚未产出完整回答时，
        // 基于全部工具结果生成一次总结回复。单次调用、不注入工具（tool_choice=none），
        // 模型无法再发起工具调用 → 不会重新进入循环，也不会反复总结。
        String clean = null;
        if (toolCallCount > 0) {
            GenerateResult finalGen = generateFinalAnswer(history, enableThinking);
            if (finalGen != null && finalGen.content != null && !finalGen.content.trim().isEmpty()) {
                clean = cleanResponse(finalGen.content);
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
            streamDirectAnswer(clean);
            if (callback != null) callback.onComplete(clean);
            return buildResponse(clean, totalTokens, System.currentTimeMillis() - startTime, toolCallCount, getAgentMaxIterations());
        }
        // 完全没有有效回答，用简单兜底
        String fb = buildSimpleFallback(userMessage);
        streamDirectAnswer(fb);
        if (callback != null) callback.onComplete(fb);
        return buildResponse(fb, totalTokens, System.currentTimeMillis() - startTime, toolCallCount, getAgentMaxIterations());

        } // end modelAutonomyEnabled (Qwen-native model FC loop)
        // 理论不可达（前方已全部 return），仅满足编译器
        return buildResponse("", totalTokens, System.currentTimeMillis() - startTime, toolCallCount, 1);
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
    private GenerateResult generateFinalAnswer(List<ChatMessage> history, boolean enableThinking) {
        AILogger.i(TAG, "Generating final summary answer based on all results...");
        try {
            // 留出输出空间：把历史裁剪到 budget - 1000，避免总结输出时上下文溢出
            int budget = computePromptBudget();
            List<ChatMessage> trimmed = trimHistoryToFit(history, "",
                    Math.max(1000, budget - FINAL_RESPONSE_MAX_TOKENS));

            // 追加总结指令（作为最后一轮 user 消息，引导模型整合结果给结论）
            List<ChatMessage> summaryHistory = new ArrayList<>(trimmed);
            summaryHistory.add(new ChatMessage("user",
                    "请基于以上对话和工具返回的结果，用中文给出最终回答。"));

            String requestJson = buildRequestJson(summaryHistory, "", "none",
                    FINAL_RESPONSE_MAX_TOKENS, enableThinking);
            if (requestJson == null) return null;

            if (aiConfig != null && aiConfig.isUseJsonProtocol()) {
                return generateWithChatJsonSync(requestJson);
            }
            // 旧路径：不带工具生成（native 层 tools 为空 → tool_choice=NONE）
            return generateWithToolsSync(summaryHistory, new byte[0],
                    FINAL_RESPONSE_MAX_TOKENS, 0.6f, enableThinking);
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
            String requestJson = buildRequestJson(trimmed, "", "none",
                    PLAIN_CHAT_MAX_TOKENS, enableThinking);
            if (requestJson == null) return null;
            if (aiConfig != null && aiConfig.isUseJsonProtocol()) {
                return generateWithChatJsonSync(requestJson);
            }
            return generateWithToolsSync(trimmed, new byte[0],
                    PLAIN_CHAT_MAX_TOKENS, 0.6f, enableThinking);
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

        LlamaHelper.generateWithTools(roles, contents, toolsJson, maxTokens, temperature,
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
    private GenerateResult generateWithChatJsonSync(String requestJson) {
        CountDownLatch latch = new CountDownLatch(1);
        final List<ToolCall> toolCallsHolder = new ArrayList<>();
        final StringBuilder reasoningBuf = new StringBuilder();
        final String[] contentHolder = {null};
        final String[] errorHolder = {null};
        final boolean[] done = {false};   // F10：幂等标志，error/complete 后忽略迟到事件

        LlamaHelper.chatJson(requestJson, new LlamaHelper.JsonCallback() {
            @Override
            public void onJson(String json) {
                if (done[0]) return;
                try {
                    JSONObject event = new JSONObject(json);
                    String type = event.optString("type", "");
                    switch (type) {
                        case "token":
                            // is_tool_call=true 的 token（tool_call JSON 片段）吞掉不渲染（§5.2）
                            if (!event.optBoolean("is_tool_call", false)) {
                                String token = event.optString("content", "");
                                if (!token.isEmpty() && callback != null) {
                                    callback.onToken(token);
                                }
                            }
                            break;
                        case "tool_call":
                            toolCallsHolder.add(parseToolCallEvent(event));
                            break;
                        case "reasoning":
                            String reasoning = event.optString("content", "");
                            if (!reasoning.isEmpty()) {
                                reasoningBuf.append(reasoning);
                                if (callback != null) callback.onThinkingUpdate(reasoning);
                            }
                            break;
                        case "complete":
                            contentHolder[0] = event.optString("content", "");
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
        AILogger.i(TAG, "generateWithChatJsonSync result: contentLen=" + content.length()
                + " reasoningLen=" + reasoningBuf.length()
                + " toolCalls=" + toolCallsHolder.size());

        return new GenerateResult(content, reasoningBuf.toString(), toolCallsHolder);
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
     * 构建 OpenAI 格式请求 JSON（spec §4.1 / §7.2.3）
     * 支持 assistant.tool_calls（结构化）与 tool.tool_call_id（R4-1）
     * 失败返回 null（org.json 的 put 抛受检 JSONException，内部消化）
     */
    private String buildRequestJson(List<ChatMessage> history, String toolsJson,
                                     String toolChoice, int maxTokens, boolean enableThinking) {
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
            req.put("max_tokens", maxTokens);
            req.put("temperature", 0.6f);
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
        String thinking = "";
        String content = fullText;

        String thinkStartTag = "<think>";
        String thinkEndTag = "</think>";
        int thinkStart = fullText.indexOf(thinkStartTag);
        int thinkEnd = fullText.indexOf(thinkEndTag);

        if (thinkStart >= 0 && thinkEnd > thinkStart) {
            thinking = fullText.substring(thinkStart + thinkStartTag.length(), thinkEnd).trim();
            content = fullText.substring(0, thinkStart) + fullText.substring(thinkEnd + thinkEndTag.length());
            content = content.trim();
        } else if (thinkStart >= 0 && thinkEnd < 0) {
            thinking = fullText.substring(thinkStart + thinkStartTag.length()).trim();
            content = fullText.substring(0, thinkStart).trim();
        }

        return new String[]{content, thinking};
    }

    /** 生成结果：包含正文、思考内容和工具调用 */
    private static class GenerateResult {
        final String content;
        final String reasoning;
        final List<ToolCall> toolCalls;
        GenerateResult(String content, String reasoning, List<ToolCall> toolCalls) {
            this.content = content;
            this.reasoning = reasoning;
            this.toolCalls = toolCalls;
        }
    }

    // ==================== 智能工具选择与预算守卫 ====================

    /**
     * 用户消息是否命中工具关键词路由（不含常驻默认工具）。
     * 决定首轮 tool_choice：命中 → required（强制调工具）；闲聊 → auto（直接回答）。
     */
    private boolean hasKeywordToolMatch(String userMessage) {
        if (userMessage == null) return false;
        String msg = userMessage.toLowerCase();
        for (String[] route : TOOL_ROUTES) {
            for (String keyword : route[1].split(",")) {
                if (!keyword.isEmpty() && msg.contains(keyword.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 根据用户消息关键词智能选择相关工具（不再全量注入，避免塞满上下文）。
     * 规则：关键词路由命中 → 取命中工具；无命中 → 不注入工具（闲聊提速）；
     * 最后按 schema token 预算与数量上限继续裁剪。
     */
    private List<String> selectRelevantTools(String userMessage) {
        // 轻量查询已注册工具名，不触发全部工具实例初始化
        List<String> registered = toolManager.getRegisteredToolNames();
        List<String> selected = new ArrayList<>();
        String msg = userMessage != null ? userMessage.toLowerCase() : "";

        for (String[] route : TOOL_ROUTES) {
            if (selected.size() >= MAX_TOOLS_PER_RUN) break;
            String toolName = route[0];
            if (!registered.contains(toolName) || selected.contains(toolName)) continue;
            for (String keyword : route[1].split(",")) {
                if (!keyword.isEmpty() && msg.contains(keyword.toLowerCase())) {
                    selected.add(toolName);
                    break;
                }
            }
        }

        // 关键词命中后补入常驻基础工具（天气/搜索/时间，保证组合能力）；
        // 无任何关键词命中（纯闲聊如"你好"）→ 不注入工具。CPU 设备上工具 schema
        // 占 ~1700 token（ui_component 最大），去掉后闲聊 prompt 从 2172 降到 ~400，
        // 首轮全量解码快 5 倍，避免"处理中"久等
        if (!selected.isEmpty()) {
            for (String name : DEFAULT_CORE_TOOLS) {
                if (selected.size() >= MAX_TOOLS_PER_RUN) break;
                if (registered.contains(name) && !selected.contains(name)) {
                    selected.add(name);
                }
            }
        }

        // 工具注册表异常（为空）时退化为限量全集，保证可用性
        if (selected.isEmpty() && registered.isEmpty()) {
            for (String name : registered) {
                if (selected.size() >= 4) break;
                selected.add(name);
            }
        }

        // 动态工具：①按硬编码关键词路由注入（输入特定关键词→对应动态工具）；
        // ②消息里直接提到工具名也选中（兜底）
        try {
            List<String> dynamicNames = toolManager.getDynamicToolNames();
            java.util.Set<String> dynSet = new java.util.HashSet<>(dynamicNames);
            // ① 关键词路由
            for (String[] route : DYNAMIC_TOOL_ROUTES) {
                if (selected.size() >= MAX_TOOLS_PER_RUN) break;
                String toolName = route[0];
                if (!dynSet.contains(toolName) || selected.contains(toolName)) continue;
                for (String keyword : route[1].split(",")) {
                    if (!keyword.isEmpty() && msg.contains(keyword.toLowerCase())) {
                        selected.add(toolName);
                        break;
                    }
                }
            }
            // ② 提工具名兜底
            for (String dynName : dynamicNames) {
                if (selected.size() >= MAX_TOOLS_PER_RUN) break;
                if (!selected.contains(dynName)
                        && dynName != null && !dynName.isEmpty()
                        && msg.contains(dynName.toLowerCase())) {
                    selected.add(dynName);
                }
            }
        } catch (Throwable t) {
            AILogger.w(TAG, "Dynamic tool selection failed: " + t.getMessage());
        }
        return selected;
    }

    /**
     * 计算单次推理的 prompt token 预算：
     * 按实际生效上下文的 80% 计算（80% prompt + 剩余留作输出），上限留出足够推理空间。
     */
    private int computePromptBudget() {
        try {
            int ctxSize = getEffectiveContextSize();
            if (ctxSize > 0) {
                return (int) (ctxSize * PROMPT_BUDGET_RATIO);
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
     * 注：注入的是 user 消息（绕过 trim 对 tool 消息的 400 字符压缩），
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
        int real = 0;
        try {
            real = LlamaHelper.getContextSize();
        } catch (Throwable t) {
            AILogger.w(TAG, "getContextSize failed: " + t.getMessage());
        }
        int ctxSize = real > 0 ? real : preset;
        if (ctxSize <= 0) {
            ctxSize = 8192; // 兜底
        }
        if (real > 0 && preset > 0 && preset != real) {
            AILogger.i(TAG, "Effective context: preset=" + preset + ", native=" + real + ", using " + ctxSize);
        }
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
     * 每轮推理前裁剪历史，使 历史+schema 的 token 总量不超过预算：
     * 1) 从早到晚压缩工具结果；2) 仍超则从头部截断最旧的消息，保留 system+最新 N 轮。
     */
    private List<ChatMessage> trimHistoryToFit(List<ChatMessage> history, String toolsJson, int budgetTokens) {
        int schemaTokens = countTokensSafe(toolsJson);
        int total = countTokensSafe(serializeHistory(history)) + schemaTokens;
        if (total <= budgetTokens) return history;

        AILogger.w(TAG, "Trimming history: " + total + " > " + budgetTokens + " tokens");
        List<ChatMessage> trimmed = new ArrayList<>(history);

        // 1) 压缩工具结果（最占空间），从早到晚
        for (int i = 0; i < trimmed.size() && total > budgetTokens; i++) {
            ChatMessage m = trimmed.get(i);
            if ("tool".equals(m.role) && m.content != null && m.content.length() > 400) {
                trimmed.set(i, new ChatMessage(m.role, truncate(m.content, 400)));
                total = countTokensSafe(serializeHistory(trimmed)) + schemaTokens;
            }
        }

        // 2) 仍超：从最旧消息开始丢弃（移除 index 1，system 之后第一个），
        //    保留 system + 最新 N 轮。注意不能用 remove(size-2)：那会从中间删，
        //    留下"最旧+最新"两条、丢掉中间较新的上下文（与注释宣称的保留最新 N 轮相反）
        while (trimmed.size() > 3 && total > budgetTokens) {
            trimmed.remove(1);
            total = countTokensSafe(serializeHistory(trimmed)) + schemaTokens;
        }

        AILogger.i(TAG, "History trimmed to " + trimmed.size() + " messages, " + total + " tokens");
        return trimmed;
    }

    // ==================== Prompt 构建 ====================

    private String buildSystemPrompt() {
        // 极简提示词：意图命中走程序化输出（不经模型），意图未命中走普通对话
        // （模型直接回答、不注入工具），因此提示词只保留身份 + 环境上下文，
        // 工具调用规则/回答格式等指令段全部移除（模型已不再调用工具）。
        StringBuilder sb = new StringBuilder();
        sb.append("你是答题宝AI助手，用中文简洁自然地与用户对话。\n\n");

        // 环境上下文注入（当前时间/位置）：模型可直接回答"今天几号/现在几点/附近"等，无需调工具
        try {
            sb.append(buildEnvironmentContext()).append("\n");
        } catch (Throwable t) {
            AILogger.w(TAG, "Environment context injection failed: " + t.getMessage());
        }

        return sb.toString();
    }

    /**
     * FC 模式的 system 提示词（精简版，控制 prompt 体积→推理速度）。
     * 要点：llama.cpp 的 Qwen chat 模板注入 tools 后自带工具调用指令与格式，
     * 此处只保留模板不覆盖的关键行为（检索/UI/时间/展示/记忆），
     * 删掉重复的格式长文与示例（省 ~1200 token/轮）。
     */
    private String buildFcSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是答题宝AI助手，可用工具完成任务，中文简洁回答。\n\n");
        // FC 模式不注入环境上下文：时间/位置经工具获取（见规则 3）

        sb.append("【规则】\n");
        sb.append("1. 需要工具时按模板输出 tool_call（一轮可多个并行）；收到结果后继续推理，信息齐备即直接回答，不再输出 tool_call。\n");
        sb.append("2. 工具不在列表→tool_registry(list/search/get)检索；工具描述被精简时，需要完整参数/用法也用它（tool_registry(get=工具名)）；UI控件参数→control_lookup；建UI→ui_component；图片/图表生成后必须用 ui_component(component_type=image) 展示。\n");
        sb.append("3. 时间/日期/位置先调 time_date/dynamic_clock/location，禁止编造。\n");
        sb.append("4. 结构信息优先 ui_component 卡片展示；先结论后细节；说明工具来源；工具失败给替代建议。\n");
        sb.append("5. 可多轮推理，每轮判断是否完成：完成→结论，未完成→继续，勿重复已执行调用；需用户输入时用 choice/input 组件询问。\n");
        sb.append("\n");
        sb.append("【记忆】\n");
        sb.append("跨会话记忆（memory 工具）：用户要求记住或主动告知个人信息/偏好时 save（key 用英文短词）；回忆用 recall/list；忘记用 delete。\n");
        if (appContext != null) {
            try {
                String memorySummary = com.oilquiz.app.ai.agent.online.AgentMemoryStore
                        .getInstance(appContext).buildMemorySummary();
                if (memorySummary != null && !memorySummary.isEmpty()) {
                    // 摘要超 800 字符截断，控制 prompt 体积
                    if (memorySummary.length() > 800) {
                        memorySummary = memorySummary.substring(0, 800) + "…";
                    }
                    sb.append("【已存记忆】").append(memorySummary).append("\n");
                }
            } catch (Throwable t) {
                AILogger.w(TAG, "Memory summary injection failed: " + t.getMessage());
            }
        }
        return sb.toString();
    }

    // ==================== 环境上下文注入 ====================

    /** 位置缓存有效期（毫秒）：10 分钟内不重复定位 */
    private static final long LOCATION_CACHE_TTL_MS = 10 * 60 * 1000L;
    /** 位置获取超时（毫秒）：定位慢不能拖慢每条消息 */
    private static final long LOCATION_FETCH_TIMEOUT_MS = 3000;

    private volatile String cachedLocation;
    private volatile long cachedLocationTime;

    /**
     * 构建环境上下文：当前日期时间（必含）+ 位置（缓存+短超时+权限检查，失败静默跳过）。
     * 与在线引擎一致（OnlineAgentEngine.buildEnvironmentContext）。
     */
    private String buildEnvironmentContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("【环境上下文】\n");
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                    "yyyy年M月d日 EEEE HH:mm", java.util.Locale.CHINA);
            sb.append("当前日期：").append(sdf.format(new java.util.Date())).append("\n");
        } catch (Throwable t) {
            AILogger.w(TAG, "Format time failed: " + t.getMessage());
        }
        String location = getCachedLocation();
        if (location != null && !location.isEmpty()) {
            sb.append("当前位置：").append(location).append("\n");
        }
        sb.append("（以上环境信息已自动获取，回答时可据此理解\"今天\"、\"附近\"等指代；时间/日期无需再调工具）");
        return sb.toString();
    }

    /** 获取位置（缓存+超时+权限检查）：无权限/失败/超时返回 null 静默跳过 */
    private String getCachedLocation() {
        long now = System.currentTimeMillis();
        if (cachedLocation != null && now - cachedLocationTime < LOCATION_CACHE_TTL_MS) {
            return cachedLocation;
        }
        // 无定位权限时不尝试（避免触发权限请求打断对话）
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
                java.util.Map<String, Object> params = new java.util.HashMap<>();
                params.put("action", "get_city");
                AIToolResult r = toolManager.executeTool("location", params);
                if (r != null && r.isSuccess() && r.getResult() != null) {
                    holder.set(String.valueOf(r.getResult()));
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

    private String buildToolsJson(List<String> toolNames) {
        JSONArray tools = new JSONArray();
        int schemaTokens = 0;
        for (String name : toolNames) {
            // resolveToolDefinition 支持静态定义 + 动态工具反射生成
            ToolDefinition def = toolManager.resolveToolDefinition(name);
            if (def == null) continue;
            try {
                JSONObject tool = new JSONObject();
                tool.put("type", "function");
                JSONObject function = new JSONObject();
                function.put("name", def.getName());
                // 结构优化：注入的 description 只保留精简版（≤150 字符）。
                // 完整描述/参数仍经 tool_registry(get=工具名) 按需获取——
                // 大幅缩小注入 schema，让更多工具挤进预算，模型需要细节时检索。
                String desc = def.getDescription();
                if (desc != null && desc.length() > MAX_TOOL_DESC_CHARS) {
                    desc = desc.substring(0, MAX_TOOL_DESC_CHARS) + "…";
                }
                function.put("description", desc);
                JSONObject params = new JSONObject();
                params.put("type", "object");
                JSONObject props = new JSONObject();
                JSONArray required = new JSONArray();
                for (ParamDefinition p : def.getParameters()) {
                    JSONObject prop = new JSONObject();
                    prop.put("type", p.getType());
                    prop.put("description", p.getDescription());
                    if (p.getDefaultValue() != null) prop.put("default", p.getDefaultValue());
                    props.put(p.getName(), prop);
                    if (p.isRequired()) required.put(p.getName());
                }
                params.put("properties", props);
                if (required.length() > 0) params.put("required", required);
                function.put("parameters", params);
                tool.put("function", function);

                // schema token 预算守卫：超出则停止追加更多工具
                int addTokens = countTokensSafe(tool.toString());
                if (schemaTokens + addTokens > MAX_SCHEMA_TOKENS && tools.length() > 0) {
                    // 检索型工具豁免：tool_registry（工具发现）与 control_lookup
                    // （低频 UI 控件参数）是全池注入被裁剪后模型按需检索的逃生口，
                    // 被裁掉就失去了发现剩余工具/控件参数的能力
                    String n = def.getName();
                    if (!"tool_registry".equals(n) && !"control_lookup".equals(n)) {
                        AILogger.w(TAG, "Schema token budget reached, dropping tool: " + name);
                        continue;
                    }
                }
                schemaTokens += addTokens;
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
        String cleaned = response
                .replaceAll("(?s)<thought>.*?</thought>", "")
                .replaceAll("(?s)<think>.*?</think>", "")
                .replaceAll("(?s)<tool_response>.*?</tool_response>", "")
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

    /** 同时匹配 <think>（Qwen3等）和 <thought>（通用）两种思考标签 */
    private static final Pattern THOUGHT_PATTERN =
            Pattern.compile("(?:<think>(.*?)</think>|<thought>(.*?)</thought>)", Pattern.DOTALL);

    private String extractThought(String response) {
        if (response == null) return null;
        Matcher m = THOUGHT_PATTERN.matcher(response);
        if (m.find()) {
            // 优先取 <think> 内容（group 1），其次取 <thought> 内容（group 2）
            String think = m.group(1);
            return think != null ? think.trim() : (m.group(2) != null ? m.group(2).trim() : null);
        }
        return null;
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

    // ==================== 工具调用标签解析 ====================

    /** 模型输出的 <tool_call> 标签（Qwen3 实测输出此格式，common_chat_parse 不识别） */
    private static final Pattern TOOL_CALL_TAG =
            Pattern.compile("<tool_call>(.*?)</tool_call>", Pattern.DOTALL);

    /**
     * 从回复文本解析 <tool_call> 标签包裹的 JSON 工具调用（Qwen 原生格式，与 Qwen-Agent fncall 约定一致）。
     * 实测 Qwen3-4B 输出：<tool_call>{"name":"ai_weather","arguments":{"city":"北京"}}</tool_call>
     * 支持一轮多个工具调用（并行）：循环匹配全部标签并展开。
     */
    private List<ToolCall> parseToolCallsTag(String response) {
        List<ToolCall> calls = new ArrayList<>();
        if (response == null) return calls;
        Matcher tagMatcher = TOOL_CALL_TAG.matcher(response);
        while (tagMatcher.find()) {
            calls.addAll(parseToolCallJson(tagMatcher.group(1).trim()));
        }
        return calls;
    }

    /**
     * 解析单个 <tool_call> 标签内 JSON，兼容多种形态：
     * 1. 单条：{"name":"ai_weather","arguments":{"city":"北京"}}
     * 2. 数组包裹（一轮并行）：{"tool_calls":[{"name":..,"arguments":{..}}, ...]}，展开为 0..n 条
     * 3. arguments/parameters/args 三种参数名；arguments 可为 JSON 对象或 JSON 字符串
     * 4. 工具名别名 name/tool/function.name；可选 id（call_xx，R4-1 需要时由上层补齐）
     */
    private List<ToolCall> parseToolCallJson(String jsonStr) {
        List<ToolCall> calls = new ArrayList<>();
        if (jsonStr == null || jsonStr.isEmpty()) return calls;
        try {
            JSONObject json = new JSONObject(jsonStr);
            // 数组包裹形态：一轮多个工具调用
            JSONArray array = json.optJSONArray("tool_calls");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item == null) continue;
                    ToolCall tc = buildToolCallFromJson(item);
                    if (tc != null) calls.add(tc);
                }
                return calls;
            }
            ToolCall tc = buildToolCallFromJson(json);
            if (tc != null) calls.add(tc);
        } catch (Exception e) {
            AILogger.w(TAG, "Parse tool call tag failed: " + truncate(jsonStr, 80));
        }
        return calls;
    }

    /** 从单条工具调用 JSON 构建 ToolCall（名称/参数/ID 多别名兼容） */
    private ToolCall buildToolCallFromJson(JSONObject json) {
        try {
            String name = json.optString("name", "");
            if (name.isEmpty()) name = json.optString("tool", "");
            if (name.isEmpty()) {
                JSONObject fn = json.optJSONObject("function");
                if (fn != null) name = fn.optString("name", "");
            }
            if (name.isEmpty()) return null;
            return new ToolCall(resolveCallId(json), name, resolveArgsObject(json));
        } catch (Exception e) {
            return null;
        }
    }

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

    private AIToolResult executeToolSafely(String toolName, JSONObject args) {
        Map<String, Object> params = jsonToMap(args);
        if (params == null) return AIToolResult.fail("参数解析失败");

        // 程序硬编码：天气与位置强关联——ai_weather 缺位置参数时自动补当前城市
        // （环境缓存的位置，10 分钟 TTL；不依赖模型猜城市名）
        if ("ai_weather".equals(toolName)
                && !params.containsKey("city") && !params.containsKey("lat") && !params.containsKey("lon")) {
            String city = getCachedLocation();
            if (city != null && !city.isEmpty() && !"当前位置".equals(city)) {
                params.put("city", city);
                AILogger.i(TAG, "ai_weather: auto-filled city=" + city);
            }
        }

        AIToolResult result = executeWithTimeout(toolName, params);
        if (result.isSuccess()) return result;

        for (int r = 0; r < MAX_RETRIES; r++) {
            AILogger.w(TAG, "Retrying " + toolName);
            result = executeWithTimeout(toolName, params);
            if (result.isSuccess()) return result;
        }
        return result;
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
        return "抱歉，我暂时无法完整回答这个问题。请尝试换一种方式描述，或使用更强大的模型。";
    }

    // ==================== 工具方法 ====================

    private int countTokensSafe(String text) {
        if (text == null || text.isEmpty()) return 0;
        try {
            int n = LlamaHelper.countTokens(text);
            return n > 0 ? n : Math.max(1, text.length() / 4);
        } catch (Exception e) { return Math.max(1, text.length() / 4); }
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

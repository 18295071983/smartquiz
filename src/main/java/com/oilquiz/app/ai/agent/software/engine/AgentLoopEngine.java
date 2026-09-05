package com.oilquiz.app.ai.agent.software.engine;

import android.content.Context;

import com.oilquiz.app.ai.agent.software.model.AgentResponse;
import com.oilquiz.app.ai.agent.software.model.AgentStats;
import com.oilquiz.app.ai.chat.parser.ThinkingTagConfig;
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
import org.json.JSONException;
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
    /** 单次推理的 prompt token 预算系数（占上下文容量的比例，下限 0.15） */
    private static final double PROMPT_BUDGET_RATIO = 0.8;
    /** 工具结果最大字符数（超长时截断，节省上下文；放宽到 6000：天气/搜索等
     *  结果不再因 1200 截断丢失关键数值——上一轮天气问题的根因。trim 阶段会把
     *  更早的工具结果压缩到 2500，最近的保持完整，平衡信息完整性与上下文占用） */
    private static final int MAX_TOOL_RESULT_LENGTH = 6000;
    /** 最终回复最大生成 token：放宽到 4000，长回答/长篇输出不被截断 */
    private static final int FINAL_RESPONSE_MAX_TOKENS = 4000;
    /** 普通对话（意图未命中）最大生成 token：放宽到 2000 */
    private static final int PLAIN_CHAT_MAX_TOKENS = 2000;
    /** 工具执行超时（毫秒） */
    private static final long TOOL_TIMEOUT_MS = 15000;
    /** 工具失败重试次数 */
    private static final int MAX_RETRIES = 1;
    /** 同步调用超时（毫秒）：必须大于 C++ 生成超时(400s)，否则 Java 先放弃导致回答被掐断 */
    private static final long SYNC_TIMEOUT_MS = 420000;
    /** 单次执行最多注入的工具数（常驻 5 + 关键词命中，保证组合工具能力；
     *  从 5 提到 7：扩大的常驻池（天气/搜索/计算/时间/位置）加关键词命中后
     *  仍能全部注入，减少"查了才能调"的依赖） */
    private static final int MAX_TOOLS_PER_RUN = 7;
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

    /** 关键词路由表：零 decode 的意图识别——消息命中关键词即把对应工具注入首轮集，
     *  常见请求单跳直达（省 tool_registry 两跳 decode），并给模型"用户要什么"的提示 */
    private static final String[][] TOOL_ROUTES = {
            {"ai_weather", "天气,气温,温度,下雨,下雪,刮风,湿度,空气质量,紫外线,预报,雾霾,台风"},
            {"location", "位置,定位,我在哪,附近,周边,坐标,经纬度,地址,城市"},
            {"time_date", "时间,日期,几点,今天几号,星期几,现在几点,当前时间,几月几号"},
            {"network_search", "搜索,搜一下,查一下,新闻,资讯,热点,最新,油价,百度,谷歌"},
            {"text_tools", "json格式化,json校验,base64,url编码,url解码,正则提取,转大写,转小写,去空白,字数统计,文本处理,编码解码"},
            {"unit_converter", "换算,单位转换,单位换算,厘米,公斤,磅,华氏,摄氏,千米,英里,英寸,英尺,加仑,公顷"},
            {"calculator", "计算,算一下,算算,数学,求和,平均,等于多少,多少钱"},
            {"image_gen", "画图,画一张,生成图片,生成图像,画个,画一只,画一幅,ai绘图"},
            {"python_chart", "柱状图,折线图,饼图,散点图,数据可视化,生成图表,图表,画个图,画图表"},
            {"memory", "记住,记一下,别忘了,我的名字,我的喜好,记住我,记忆"},
            {"speech_synthesis", "朗读,读出来,念出来,播报,语音播报,语音朗读,帮我读"},
            {"voice_input", "语音输入,听写,录音识别,语音转文字,语音打字"},
            {"excel_tool", "excel,表格文件,xlsx,xls,电子表格"},
            {"file_reader", "读文件,读取文件,打开文件,文件内容,查看文件,读一下,看看文件"},
            {"workspace", "工作区,保存的文件,生成的文件,工作区文件,看看我生成的文件"},
            {"database", "题库,查题,题目,知识点,刷题,考题"},
            {"system_resource", "内存,cpu,电量,存储空间,系统信息,手机信息,运行内存"},
            {"app_operation", "打开应用,打开app,启动应用,打开微信,打开浏览器,打开设置"},
            {"tool_registry", "工具列表,有哪些工具,工具介绍,会什么,可用工具,工具箱,你能做什么"},
            {"get_models_profile", "模型列表,有哪些模型,模型信息,支持什么模型,模型上下文"},
            {"permission_manager", "权限,授权,权限设置,开启权限,权限管理,权限检查"},
            {"webpage_reader", "网页,链接,网址,url,http,打开网页,看网页,读网页"},
            {"file_generator", "生成文件,写文件,创建文件,保存为,导出文档,生成md,写markdown"},
            {"dashscope_media", "文生视频,生成视频,视频生成,ai视频,ai生成视频,生成一个视频,生成一段视频"},
    };
    /** 常驻基础工具：关键词命中后补入（时间/位置用 time_date/location 工具获取，不注入环境上下文） */
    private static final String[] DEFAULT_CORE_TOOLS = {
            "ai_weather", "network_search", "calculator", "time_date", "location"
    };

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
    /** 最近一次 chatJson 生成是否已有正文 token 流式输出到 UI（防重复回复，见 run()） */
    private volatile boolean lastStreamed = false;
    /**
     * 当前模型的思考标签（来自 chat template，由 native meta 事件下发）。
     * 未收到 meta 时为 empty，此时思考提取回退到旧的正则/字面量路径。
     */
    private volatile ThinkingTagConfig thinkingTags = ThinkingTagConfig.empty();

    /** 最近一次用户消息原文：供 ai_weather 缺城市参数时提取城市名兜底（如"五台县的天气"→city=五台县） */
    private volatile String lastUserMessage = "";

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
        // 记录最近用户消息：ai_weather 缺城市参数时程序从原文提取城市名兜底
        this.lastUserMessage = userMessage != null ? userMessage : "";

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
        String sysPrompt = modelFcMode ? buildFcSystemPrompt() : buildSystemPrompt();
        // 天气场景直给：命中 ai_weather 且用户消息含城市名时，把"本次任务"写进 system 提示词，
        // 2B 模型无需自己推断该查哪个城市，直接照做（根治"五台县"卡死：不调 location、直接 city=五台县）
        if (modelFcMode && selectedTools.contains("ai_weather") && userMessage != null
                && (userMessage.contains("天气") || userMessage.contains("气温") || userMessage.contains("温度")
                    || userMessage.contains("下雨") || userMessage.contains("预报") || userMessage.contains("湿度"))) {
            String city = extractCityFromMessage(userMessage);
            if (city != null && !city.isEmpty()) {
                String cityId = getCityLocationId(city); // 优先用和风城市编码查询，最精确
                String locParam = cityId != null ? cityId : city;
                sysPrompt += "\n【本次任务】用户要查 " + city + " 的天气，优先用 ai_weather 工具（参数 city=" + locParam
                        + "，这是 " + city + " 的和风城市编码，直接按编码调用，工具会返回对应城市天气，无需先验证编码；action 按用户问法选：当前→current，预报→forecast，空气质量→air_quality，默认 current），也可用 network_search 搜索。"
                        + "工具返回的城市与用户原意不符时，按用户原话重查。\n";
            } else {
                // 无具体城市（查"这里/附近/现在天气"）：经纬度查当前位置实时天气最准
                sysPrompt += "\n【本次任务】用户要查当前位置/附近的天气，用 ai_weather 工具，参数用 location 工具定位获取的 lat/lon；action 按问法选 current(实时)/forecast(预报)/air_quality(空气质量) 等。\n";
            }
        }
        history.add(new ChatMessage("system", sysPrompt));
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
            // 不限制思考量：统一用 FINAL_RESPONSE_MAX_TOKENS（原第一轮 800 会让 enableThinking
            // 模型思考就被截断，触发"使用更强大的模型"兜底；具体安全值由 buildRequestJson 按上下文钳制）
            int iterMaxTokens = FINAL_RESPONSE_MAX_TOKENS;
            String requestJson = buildRequestJson(history, toolsJson, toolChoice, iterMaxTokens, enableThinking);
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
                    genResult = generateWithToolsSync(history, toolsJsonBytes, FINAL_RESPONSE_MAX_TOKENS, 0.7f, enableThinking);
                }
            } catch (UnsatisfiedLinkError e) {
                // §10.2：chatJson 不可用 → 自动切回旧路径；仍失败则本轮失败（模型原生 FC，无标签兜底）
                AILogger.w(TAG, "chatJson unavailable (" + e.getMessage() + "), fallback to generateWithToolsSync");
                try {
                    genResult = generateWithToolsSync(history, toolsJsonBytes, FINAL_RESPONSE_MAX_TOKENS, 0.7f, enableThinking);
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
                if (tc.toolName != null && !tc.toolName.isEmpty()) {
                    // 模糊归一：模型可能用猜测名（weather→ai_weather），注入真实名避免重复/无效
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

                // 提示词泄漏检测：模型把系统规则当回答输出时，break 走统一退出路径
                if (isPromptLeakage(cleanResponse)) {
                    AILogger.w(TAG, "Prompt leakage detected, breaking");
                    break;
                }
                // ===== 降级重试结束 =====

                // 模型的回答即最终答案：直接流式输出，不再二次生成
                // 防重复：若本轮 token 已流式输出到 UI（onToken 推送过正文），
                // 只发 onComplete 收尾，不重复 streamDirectAnswer
                if (!streamedThisIteration[0]) {
                    streamDirectAnswer(cleanResponse);
                } else {
                    AILogger.i(TAG, "Answer already streamed via tokens, skipping re-stream");
                }
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
                if (callback != null) callback.onToolResult(tc.toolName, success, resultStr);
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
            // 防重复：generateFinalAnswer 已通过 chatJson onToken 流式输出正文时，
            // 不重复 streamDirectAnswer（lastStreamed 记录最近一次生成是否流式过）
            if (!lastStreamed) {
                streamDirectAnswer(clean);
            } else {
                AILogger.i(TAG, "Final answer already streamed via tokens, skipping re-stream");
            }
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
                // 传 null：不重置本轮标志；onToken 内部仍会更新 lastStreamed 记录流式状态
                return generateWithChatJsonSync(requestJson, null);
            }
            // 旧路径：不带工具生成（native 层 tools 为空 → tool_choice=NONE）
            return generateWithToolsSync(summaryHistory, new byte[0],
                    FINAL_RESPONSE_MAX_TOKENS, 0.7f, enableThinking);
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
                return generateWithChatJsonSync(requestJson, null);
            }
            return generateWithToolsSync(trimmed, new byte[0],
                    PLAIN_CHAT_MAX_TOKENS, 0.7f, enableThinking);
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
    private GenerateResult generateWithChatJsonSync(String requestJson, final boolean[] streamedFlag) {
        CountDownLatch latch = new CountDownLatch(1);
        final List<ToolCall> toolCallsHolder = new ArrayList<>();
        final StringBuilder reasoningBuf = new StringBuilder();
        final String[] contentHolder = {null};
        final String[] errorHolder = {null};
        // Java 侧流式 tool_call 兜底状态：C++ is_tool_call 对 <tool_call> 标签格式失效时，
        // 用跨 token 缓冲识别并吞掉标签片段（避免 tool_call JSON 流式透传到 UI）
        final StringBuilder streamFilterBuf = new StringBuilder();
        final boolean[] streamSwallowing = {false};
        final boolean[] streamJsonDone = {false};
        final StringBuilder streamCloseBuf = new StringBuilder();
        final boolean[] done = {false};   // F10：幂等标志，error/complete 后忽略迟到事件
        final boolean[] thinkingStreamedAgent = {false};   // 思考已实时累积（thinking 事件），reasoning 全文跳过防重复

        LlamaHelper.chatJson(requestJson, new LlamaHelper.JsonCallback() {
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
                            break;
                        case "token":
                            // is_tool_call=true 的 token（tool_call JSON 片段）吞掉不渲染（§5.2）
                            if (!event.optBoolean("is_tool_call", false)) {
                                String token = event.optString("content", "");
                                if (!token.isEmpty()) {
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
                            toolCallsHolder.add(parseToolCallEvent(event));
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

    /**
     * 流式过滤 <tool_call> 标签片段（Java 侧兜底）。
     * C++ 层 common_chat_parse 对 Qwen 的 <tool_call>{...}</tool_call> 标签格式解析不出，
     * is_tool_call 标志失效，标签会当普通正文 token 推到 UI；这里用状态机：
     * - 开标签跨 token 拆分时（如 <tool + _call>）前缀滞留探测，识别后进入吞状态；
     * - 吞状态下持续累积直到闭合标签；若模型漏输出闭合标签，则内容 {} 配平后进入
     *   "观望"状态：继续吞可选的 </tool_call> 残留（含跨 token 拆分），一旦收到非
     *   闭合标签内容即补壳退出，把正常正文还回，保证不泄露标签也不吞掉正常回复；
     * - 超长保护：累积超过上限仍无闭合时强制结束并交回当前 token。
     * <tool_calls> 复数标签同样覆盖（其以 <tool_call 为前缀）。
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
            // 正在吞 tool_call：持续累积（闭合标签可能跨 token 拆分）
            buf.append(token);
            String acc = buf.toString();
            int close = acc.indexOf("</tool_call");
            if (close >= 0) {
                // 有闭合标签 → 正常结束
                swallowing[0] = false;
                jsonDone[0] = false;
                String tail = acc.substring(close + "</tool_call".length());
                buf.setLength(0);
                int gt = tail.indexOf('>');
                if (gt >= 0) tail = tail.substring(gt + 1);
                if (!tail.isEmpty()) return filterStreamToken(tail, buf, swallowing, jsonDone, closeBuf);
                return "";
            }
            if (jsonDone[0]) {
                // 观望：JSON 已配平，吞掉可选的闭合标签残留
                closeBuf.append(token);
                String cb = closeBuf.toString();
                if (cb.indexOf("</tool_call") >= 0) {
                    // 闭合标签补全（含跨 token 拆分）→ 结束，处理其后的正文
                    swallowing[0] = false;
                    jsonDone[0] = false;
                    closeBuf.setLength(0);
                    buf.setLength(0);
                    int c = cb.indexOf("</tool_call");
                    String tail = cb.substring(c + "</tool_call".length());
                    int gt = tail.indexOf('>');
                    if (gt >= 0) tail = tail.substring(gt + 1);
                    if (!tail.isEmpty()) return filterStreamToken(tail, buf, swallowing, jsonDone, closeBuf);
                    return "";
                }
                if ("</tool_call".startsWith(cb)) {
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

        // 查找完整开标签 <tool_call>（<tool_calls 亦命中）
        int open = probe.indexOf("<tool_call");
        if (open >= 0) {
            String before = probe.substring(0, open);
            buf.setLength(0);
            buf.append(probe.substring(open));
            String after = buf.toString();
            if (after.indexOf("</tool_call") >= 0) {
                // 同一缓冲内已闭合：处理闭合标签之后的正文
                buf.setLength(0);
                int c = after.indexOf("</tool_call");
                String tail = after.substring(c + "</tool_call".length());
                int gt = tail.indexOf('>');
                if (gt >= 0) tail = tail.substring(gt + 1);
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

        // 无完整开标签：末尾跨 token 拆分的开标签前缀滞留
        int lastLt = probe.lastIndexOf('<');
        if (lastLt >= 0) {
            String suffix = probe.substring(lastLt);
            if ("<tool_call".startsWith(suffix) && suffix.length() < "<tool_call".length()) {
                String head = probe.substring(0, lastLt);
                buf.setLength(0);
                buf.append(suffix);
                return head;
            }
            if ("<tool_call".equals(suffix)) {
                buf.setLength(0);
                buf.append(suffix);
                closeBuf.setLength(0);
                swallowing[0] = true;
                return probe.substring(0, lastLt);
            }
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

    /** 判断 s 末尾是否存在正在跨 token 流入的闭合标签前缀（如 </ / </t / </tool_c），用于避免提前补壳 */
    private boolean isClosingTagIncomplete(String s) {
        int lt = s.lastIndexOf('<');
        if (lt < 0) return false;
        String suffix = s.substring(lt);
        return "</tool_call".startsWith(suffix) && suffix.length() < "</tool_call".length();
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
            req.put("temperature", 0.7f);   // R8-2：本地 Agent 统一 0.7（Qwen3 官方默认）。过低(0.6)会让 2B 在工具调用时过度保守，只敢用默认 action/参数；过高则破坏 chatJson 结构化输出稳定性
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
        ThinkingTagConfig tags = thinkingTags;
        final String startTag;
        final List<String> endTags;
        if (tags.isAvailable()) {
            startTag = tags.getStartTag();
            endTags = tags.getEndTags();
        } else {
            startTag = "<think>";
            endTags = java.util.Collections.singletonList("</think>");
        }

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

        // 1) 压缩工具结果（最占空间），从早到晚；阈值放宽到 2500：
        //    最近一条工具结果在注入时已是完整 6000，这里只压更早的旧结果，
        //    保证模型推理时手里拿着的是完整数据，而非被压到 400 的残片
        for (int i = 0; i < trimmed.size() && total > budgetTokens; i++) {
            ChatMessage m = trimmed.get(i);
            if ("tool".equals(m.role) && m.content != null && m.content.length() > 2500) {
                trimmed.set(i, new ChatMessage(m.role, truncate(m.content, 2500)));
                total = countTokensSafe(serializeHistory(trimmed)) + schemaTokens;
            }
        }

        // 2) 仍超：从最旧消息开始，把要丢弃的非工具对话压缩成一条"历史要点"
        //    （保留多轮指代上下文，如"那明天呢/多少钱"），而非直接丢弃丢光——
        //    在线引擎用模型摘要历史，本地用轻量要点拼接兜底，保留关键事实。
        //    保留 system + 最新 N 轮（注意不能用 remove(size-2)：那会从中间删，
        //    留下"最旧+最新"两条、丢掉中间较新的上下文）
        List<ChatMessage> evicted = new ArrayList<>();
        while (trimmed.size() > 4 && total > budgetTokens) {
            ChatMessage old = trimmed.remove(1);
            if (old != null) evicted.add(old);
            total = countTokensSafe(serializeHistory(trimmed)) + schemaTokens;
        }
        if (!evicted.isEmpty()) {
            // 生成历史要点：只取最接近当前的多轮对话（最新优先，最多 4 条非工具），
            // 每条截断 90 字符，作为一条 user 消息插入 system 之后，保留指代上下文
            StringBuilder summary = new StringBuilder("【历史对话要点】(较早对话已压缩)");
            int kept = 0;
            for (int i = evicted.size() - 1; i >= 0 && kept < 4; i--) {
                ChatMessage m = evicted.get(i);
                if ("tool".equals(m.role) || m.content == null || m.content.trim().isEmpty()) continue;
                String c = truncate(m.content, 90);
                summary.append("\n").append("user".equals(m.role) ? "用户" : "助手").append(": ").append(c);
                kept++;
            }
            if (kept > 0) {
                trimmed.add(1, new ChatMessage("user", summary.toString()));
                total = countTokensSafe(serializeHistory(trimmed)) + schemaTokens;
            }
        }

        AILogger.i(TAG, "History trimmed to " + trimmed.size() + " messages, " + total + " tokens");
        return trimmed;
    }

    // ==================== Prompt 构建 ====================

    private String buildSystemPrompt() {
        // 极简提示词：意图命中走程序化输出（不经模型），意图未命中走普通对话
        // （模型直接回答、不注入工具），因此提示词只保留身份，
        // 工具调用规则/回答格式等指令段全部移除（模型已不再调用工具）。
        StringBuilder sb = new StringBuilder();
        sb.append("你是答题宝AI助手，用中文简洁自然地与用户对话。\n\n");

        return sb.toString();
    }

    /**
     * FC 模式的 system 提示词（本地小模型精简版 v3，2026-09-05 参考厂商设计重构）。
     *
     * 参考依据：
     * - Hermes 2 Pro 官方 function calling system prompt："You are a function calling AI
     *   model... You may call one or more functions... Don't make assumptions about what
     *   values to plug into functions... If no function call is needed, answer normally"
     *   ——本地模型最熟悉的训练格式，直接用其精神：可调多个、不假设参数值、不需要就不调。
     * - Qwen 官方：system = 角色/任务 + 工具说明 + 调用格式 + 输出要求（四要素）。
     * - DeepSeek：小模型参数结构越简单越准确，短肯定句。
     * - 用户硬性偏好：零引导（不绑场景→工具、不限制单一工具、不放默认值、无示例），
     *   确定肯定句，实时信息一律工具获取，不确定时用工具测试。
     */
    private String buildFcSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是答题宝AI助手，用中文简洁回答，需要信息时调用工具。\n\n");

        // 工具说明：不引导场景→工具映射，模型根据注入的工具定义自行选择
        sb.append("【工具】\n");
        sb.append("根据注入的工具定义（名称/功能/参数）自行选择调用；同一需求可用多个工具配合。\n");
        sb.append("工具支持多种操作（action）与参数方式，按需选择；完整参数用 tool_registry(get=工具名) 查看后调用。\n");
        sb.append("不需要工具时直接回答。\n\n");

        sb.append("【做法】\n");
        sb.append("1. 直接调用工具。用户给的参数（城市/编码/时间/位置等）直接照用，先调用。\n");
        sb.append("2. 工具调用后会返回结果。先分析结果内容：结果是否回答了用户问题？缺什么信息？据此决定下一步——已满足直接回答，不满足继续调工具补齐。\n");
        sb.append("3. 工具返回的数据是准确实时的，直接采纳，不要编造结果里没有的数据。\n");
        sb.append("4. 不确定时用工具测试：不确定参数、数据或结果时，直接调工具拿返回确认，以工具返回为准。\n");
        sb.append("5. 信息不足时用搜索类工具补全再回答。\n");
        sb.append("6. 工具失败换一个工具继续，不要因一次失败就放弃。\n");
        sb.append("7. 可多轮调用：一次工具结果不够时继续调用，直到信息足够再回答。\n");
        sb.append("8. 需要探索时主动用工具：结果不完整、不清晰或与用户问题不符时，换参数/换工具再试，直到拿到可用信息。\n\n");

        sb.append("【回答】\n");
        sb.append("中文简洁，先结论后细节；没把握时直说不知道。\n");
        sb.append("有结构的信息（列表/表格）用文本或简单表格展示。\n\n");

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
                AIToolResult r = toolManager.executeTool("location", params);
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
        String cleaned = stripThinkingSections(response);
        cleaned = cleaned
                .replaceAll("(?s)<thought>.*?</thought>", "")
                .replaceAll("(?s)<think>.*?</think>", "")
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

    /** 同时匹配 <think>（Qwen3等）和 <thought>（通用）两种思考标签 */
    private static final Pattern THOUGHT_PATTERN =
            Pattern.compile("(?:<think>(.*?)</think>|<thought>(.*?)</thought>)", Pattern.DOTALL);

    private String extractThought(String response) {
        if (response == null) return null;
        // 优先用模板标签（native meta 事件下发）：换模型/换模板无需改 Java 代码
        ThinkingTagConfig tags = thinkingTags;
        if (tags.isAvailable()) {
            String thought = extractBetweenTags(response, tags.getStartTag(), tags.getEndTags());
            if (thought != null) return thought;
        }
        // 回退：未收到 meta 事件（老路径 generateWithToolsSync）时沿用旧正则
        Matcher m = THOUGHT_PATTERN.matcher(response);
        if (m.find()) {
            // 优先取 <think> 内容（group 1），其次取 <thought> 内容（group 2）
            String think = m.group(1);
            return think != null ? think.trim() : (m.group(2) != null ? m.group(2).trim() : null);
        }
        return null;
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

    /** 工具失败时的替代工具映射：引导模型换工具而非放弃（4B 模型需要明确指引） */
    private static String getAlternativeTool(String toolName) {
        if (toolName == null) return null;
        switch (toolName) {
            case "ai_weather": return "network_search（搜天气）、location（先定位）";
            case "network_search": return "smart_research（智能研究）、webpage_reader（读网页）";
            case "location": return "ai_weather 带 city 参数";
            case "file_reader": return "python_file_ops、file_analyzer";
            case "python_file_ops": return "file_reader";
            case "calculator": return "python_calculate、python_execute";
            case "python_calculate": return "calculator";
            case "speech_synthesis": return "voice_input（语音转文字）";
            case "excel_tool": return "file_reader(parse_excel)、python_file_ops";
            case "image_gen": return "dashscope_media(image)、python_execute(生成SVG)";
            case "tool_registry": return "直接查看【可用工具】表";
            default: return null;
        }
    }

    private AIToolResult executeToolSafely(String toolName, JSONObject args) {
        Map<String, Object> params = jsonToMap(args);
        if (params == null) return AIToolResult.fail("参数解析失败");

        // 程序硬编码：天气与位置强关联——ai_weather 缺位置参数时自动补当前位置。
        // 优先填经纬度（绕开和风 geo/city lookup）；坐标无缓存时，优先从用户消息
        // 提取城市名（用户明确问的城市 > 当前位置），避免"五台县"场景首轮无 city 报错。
        if ("ai_weather".equals(toolName)
                && !params.containsKey("city") && !params.containsKey("lat") && !params.containsKey("lon")) {
            fillWeatherLocation(params);
        }

        AIToolResult result = executeWithTimeout(toolName, params);
        if (isToolSuccess(result)) return result;

        // 失败感知重试：模型可能把非城市词（"今天/这边/查询下"等）当 city 传进来
        // （实测"查询下今天的天气"→city=今天→"无法定位城市: 今天"）。
        // 判断"传了 city 但执行因无法定位失败" → 丢弃无效 city、改走位置兜底重试，
        // 不依赖本地城市表覆盖度（geo 能查到的小城市不会被误伤）。
        // 注意：AIWeatherManager 会把"无法定位城市"包装成 success 结果，须用 isToolSuccess
        // 统一检测（含失败语义文本），不能只看 isSuccess()。
        if ("ai_weather".equals(toolName)
                && params.containsKey("city")
                && !params.containsKey("lat") && !params.containsKey("lon")) {
            if (containsToolFailure(extractResultText(result))) {
                String badCity = String.valueOf(params.remove("city"));
                AILogger.w(TAG, "ai_weather: model city '" + badCity
                        + "' not locatable, retrying with location fallback");
                fillWeatherLocation(params);
                result = executeWithTimeout(toolName, params);
                if (isToolSuccess(result)) return result;
            }
        }

        for (int r = 0; r < MAX_RETRIES; r++) {
            AILogger.w(TAG, "Retrying " + toolName);
            result = executeWithTimeout(toolName, params);
            if (isToolSuccess(result)) return result;
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
            // ① 内置城市表匹配（最长优先）：直接与 CSV 全量城市比对，不依赖正则，
            //    避免"查询下/看一下/今天"等动词·时间词被吞进地名（"查询下五台县的天气"→"五台县"）
            if (appContext != null) {
                com.oilquiz.app.weather.QWeatherCityManager cityMgr =
                        com.oilquiz.app.weather.QWeatherCityManager.getInstance(appContext);
                java.util.List<String> hits = cityMgr.findCityNamesInMessage(message);
                if (hits != null && !hits.isEmpty()) {
                    return hits.get(0); // 最长 = 最精确
                }
                // ② 兜底：内置常用城市表（港澳台等 CSV 可能缺失）
                for (String name : com.oilquiz.app.ai.tool.AIWeatherManager.getDefaultCityNames()) {
                    if (name.length() >= 2 && message.contains(name)) {
                        return name;
                    }
                }
            }
        } catch (Throwable t) {
            AILogger.w(TAG, "extractCityFromMessage failed: " + t.getMessage());
        }
        return null; // 表上无此城市 → 不猜，交由模型自行提取
    }

    /**
     * 取城市名对应的和风城市编码（locationId），供 ai_weather 按编码查询（最精确，
     * 避免"银川市/银川"等名称不匹配）。CSV 全量表 + 内置常用表都会查；无则 null。
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
                String id = com.oilquiz.app.ai.tool.AIWeatherManager.getDefaultCityId(cityName);
                if (id != null && !id.isEmpty()) return id;
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

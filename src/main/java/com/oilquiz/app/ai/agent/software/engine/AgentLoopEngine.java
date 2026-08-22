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
 * 线程模型：所有回调在调用线程执行（AgentSoftwareLayer 的单线程 executor）
 */
public class AgentLoopEngine {

    private static final String TAG = "AgentLoopEngine";
    /** Agent 总轮次上限（与上下文容量联动，128k+ 允许更多轮次） */
    private static final int MAX_ITERATIONS_BASE = 10;
    /** 循环保护：实际工具调用轮次上限（防止模型反复调工具不收敛） */
    private static final int MAX_TOOL_ROUNDS = 4;
    /** 循环保护：整个 Agent 执行的总时长上限（含工具执行与推理） */
    private static final long TOTAL_TIME_BUDGET_MS = 180000;
    /** 单次推理的 prompt token 预算系数（占上下文容量的比例，下限 0.15） */
    private static final double PROMPT_BUDGET_RATIO = 0.8;
    /** 工具结果最大字符数（超长时截断，节省上下文） */
    private static final int MAX_TOOL_RESULT_LENGTH = 2000;
    /** 最终回复最大生成 token（与预算计算保持一致） */
    private static final int FINAL_RESPONSE_MAX_TOKENS = 1000;
    /** 工具执行超时（毫秒） */
    private static final long TOOL_TIMEOUT_MS = 15000;
    /** 工具失败重试次数 */
    private static final int MAX_RETRIES = 1;
    /** 同步调用超时（毫秒） */
    private static final long SYNC_TIMEOUT_MS = 60000;
    /** 单轮推理最大生成 token（与预算计算保持一致） */
    private static final int ITER_MAX_TOKENS = 500;
    /** 单次执行最多注入的工具数（防止全量工具定义塞满上下文） */
    private static final int MAX_TOOLS_PER_RUN = 6;
    /** 工具 schema 的 token 预算，超过则继续裁剪工具集 */
    private static final int MAX_SCHEMA_TOKENS = 1500;
    /** 用户问题长度上限（字符） */
    private static final int MAX_USER_MESSAGE_CHARS = 2000;

    /** 关键词路由表：根据用户消息智能选择相关工具，避免全量注入 */
    private static final String[][] TOOL_ROUTES = {
            {"ai_weather", "天气,气温,温度,下雨,下雪,刮风,湿度,空气质量,紫外线,预报,雾霾"},
            {"location", "位置,定位,我在哪,附近,周边,坐标"},
            {"network_search", "搜索,搜一下,查一下,新闻,资讯,热点,最新"},
            {"smart_research", "调研,研究,深度搜索,资料,报告"},
            {"file_reader", "读文件,读取文件,打开文件,文件内容,目录,路径"},
            {"file_analyzer", "分析文件,文件分析,解析文件"},
            {"file_generator", "生成文件,创建文件,生成网页,导出,保存为,html,写一个页面"},
            {"database", "题库,题目,数据库,sqlite,sql查询,数据表"},
            {"app_operation", "打开应用,启动应用,跳转应用,打开app"},
            {"app_toolkit", "快捷指令,应用工具箱"},
            {"system_resource", "内存,cpu,电量,存储空间,系统信息"},
            {"python_calculate", "计算,算一下,数学,求和,平均,统计"},
            {"python_analyze_data", "数据分析,数据处理,分析数据,表格分析"},
            {"python_execute", "python,脚本,执行代码"},
            {"webpage_reader", "网页,链接,url,http"},
    };
    /** 无关键词命中时的默认核心工具集 */
    private static final String[] DEFAULT_CORE_TOOLS = {"ai_weather", "network_search", "file_generator"};

    private final AIService aiService;
    private final AIToolManager toolManager;
    private final AIConfig aiConfig;
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
        this.toolManager = AIToolManager.getInstance(context);
        this.aiConfig = new AIConfig(context);
    }

    public void setCallback(LoopCallback callback) {
        this.callback = callback;
    }

    // ==================== 主循环 ====================

    public AgentResponse run(String userMessage, boolean enableThinking) {
        long startTime = System.currentTimeMillis();
        int totalTokens = 0;
        int toolCallCount = 0;

        // 超长问题先截断，避免单条消息撞穿预算
        if (userMessage != null && userMessage.length() > MAX_USER_MESSAGE_CHARS) {
            AILogger.w(TAG, "User message too long, truncating to " + MAX_USER_MESSAGE_CHARS);
            userMessage = userMessage.substring(0, MAX_USER_MESSAGE_CHARS) + "…";
        }

        // 智能工具选择：只注入与本次问题相关的工具，不再全量加载（旧版全量注入会塞满上下文导致 decode 崩溃）
        List<String> selectedTools = selectRelevantTools(userMessage);
        String toolsJson = buildToolsJson(selectedTools);
        byte[] toolsJsonBytes = toolsJson.getBytes(StandardCharsets.UTF_8);
        AILogger.i(TAG, "Selected tools: " + selectedTools + ", schema len: " + toolsJson.length());

        // 单次推理的 prompt token 预算（结合配置上下文容量）
        final int promptBudget = computePromptBudget();
        AILogger.i(TAG, "Prompt budget: " + promptBudget + " tokens");

        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage("system", buildSystemPrompt()));
        history.add(new ChatMessage("user", userMessage));

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

            // 每轮推理前裁剪历史，确保单次推理 prompt 不超预算（防截断/decode崩溃）
            history = trimHistoryToFit(history, toolsJson, promptBudget);

            long genStart = System.currentTimeMillis();
            GenerateResult genResult = null;

            // 构建请求 JSON（spec §7.2.1 step b）
            String toolChoice = (iteration == 1 && !selectedTools.isEmpty()) ? "required" : "auto";
            int iterMaxTokens = iteration == 1 ? 500 : 1000;
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
                // §10.2：chatJson 不可用 → 自动切回旧路径；再失败 → prompt 模式
                AILogger.w(TAG, "chatJson unavailable (" + e.getMessage() + "), fallback to generateWithToolsSync");
                try {
                    genResult = generateWithToolsSync(history, toolsJsonBytes, 1500, 0.6f, enableThinking);
                } catch (UnsatisfiedLinkError e2) {
                    AILogger.w(TAG, "nativeGenerateWithTools unavailable, fallback to prompt mode");
                    String fallbackResponse = generateFallbackPrompt(history, iteration, selectedTools);
                    genResult = new GenerateResult(fallbackResponse, "", new ArrayList<>());
                }
            } catch (Exception e) {
                AILogger.e(TAG, "Generate failed at iter " + iteration + ": " + e.getMessage());
            }

            String response = genResult != null ? genResult.content : null;
            int iterTokens = response != null ? response.length() : 0;
            totalTokens += iterTokens;

            if (callback != null) {
                long elapsed = System.currentTimeMillis() - genStart;
                float tps = elapsed > 0 ? (iterTokens * 1000.0f) / elapsed : 0;
                callback.onInferenceProgress(totalTokens, tps);
                callback.onIterationEnd(iteration, response);
            }

            // 提取思考过程（C++ 层通过 onReasoning 回调传递，也检查文本中的标签）
            if (genResult.reasoning != null && !genResult.reasoning.isEmpty() && callback != null) {
                callback.onThinkingUpdate("第 " + iteration + " 轮思考: " + truncate(genResult.reasoning, 120));
            }

            // 优先使用 C++ 层 common_chat_parse 解析的工具调用，如果没有则回退到文本解析
            List<ToolCall> toolCalls = genResult.toolCalls;
            if (toolCalls == null || toolCalls.isEmpty()) {
                toolCalls = parseToolCalls(response);
            }

            // R4-1：执行工具前补齐 tool_call id（assistant 消息与 tool 消息共用同一 id）
            int callSeq = 0;
            for (ToolCall tc : toolCalls) {
                if (tc.id == null || tc.id.isEmpty()) tc.id = "call_" + (++callSeq);
            }

            // 空回复检查：新协议下工具轮的 complete.content 通常为空（纯 tool_call 输出），
            // 仅在"无工具调用且内容为空"时才处理；首轮 required 空输出走 F4 prompt 模式重试
            if ((response == null || response.trim().isEmpty()) && toolCalls.isEmpty()) {
                if (iteration == 1) {
                    AILogger.i(TAG, "First iteration empty output, trying prompt-mode fallback (F4)");
                    String fallbackResponse = generateFallbackPrompt(history, iteration, selectedTools);
                    if (fallbackResponse != null && !fallbackResponse.trim().isEmpty()) {
                        List<ToolCall> fbCalls = parseToolCalls(fallbackResponse);
                        if (!fbCalls.isEmpty()) {
                            history.add(new ChatMessage("assistant", cleanResponse(fallbackResponse), fbCalls));
                            toolCalls = fbCalls;
                            // 继续走工具执行（不 break）
                        } else {
                            AILogger.w(TAG, "Empty response at iteration " + iteration);
                            break;
                        }
                    } else {
                        AILogger.w(TAG, "Empty response at iteration " + iteration);
                        break;
                    }
                } else {
                    AILogger.w(TAG, "Empty response at iteration " + iteration);
                    break;
                }
            }
            if (response != null && !response.trim().isEmpty()) {
                lastMeaningfulResponse = response;   // 工具轮空内容不覆盖最后有效回复
            }

            if (toolCalls.isEmpty()) {
                AILogger.i(TAG, "No tool call at iteration " + iteration + ", using model answer directly");
                String cleanResponse = cleanResponse(response);

                // ===== 降级重试机制 =====
                // 如果第一轮 native FC 没解析到工具调用，说明模型不支持原生 FC，
                // 走降级路径：使用 prompt 模式（<tool_call>{...}</tool_call> 标签）重试
                boolean fallbackDone = false;
                if (iteration == 1 && genResult.toolCalls == null) {
                    AILogger.i(TAG, "First iteration, native FC returned empty tool calls, falling back to prompt mode");
                    String fallbackResponse = generateFallbackPrompt(history, iteration, selectedTools);
                    if (fallbackResponse != null && !fallbackResponse.trim().isEmpty()) {
                        // 从降级回复中解析工具调用
                        List<ToolCall> fallbackToolCalls = parseToolCalls(fallbackResponse);
                        if (!fallbackToolCalls.isEmpty()) {
                            AILogger.i(TAG, "Fallback prompt mode found " + fallbackToolCalls.size() + " tool calls, retrying");
                            // 将降级回复追加到历史
                            history.add(new ChatMessage("assistant", cleanResponse(fallbackResponse)));
                            // 将降级路径解析到的工具调用作为正式的 toolCalls
                            toolCalls = fallbackToolCalls;
                            fallbackDone = true;
                            // 通过 continue 进入下一轮循环（iteration++），走正常的工具执行流程
                        }
                    }
                }

                if (fallbackDone) {
                    // 降级重试成功，跳出当前 if，进入下一轮循环执行工具
                    continue;
                }

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

            // 执行所有新工具调用，结果以 tool role 追加到历史（P1-3：本地 toolManager，R4-1：带 tool_call_id）
            for (ToolCall tc : freshCalls) {
                toolCallCount++;
                String toolName = tc.toolName;
                String argsStr = tc.args.toString();

                if (callback != null) callback.onToolCall(toolName, argsStr);

                // 本地工具执行（spec §7.2.2.1：AIToolResult 适配）
                AIToolResult result = executeToolSafely(toolName, tc.args);
                boolean success = result.isSuccess();
                String resultStr = success ? String.valueOf(result.getResult()) : result.getErrorMessage();

                if (callback != null) callback.onToolResult(toolName, success, resultStr);

                history.add(new ChatMessage("tool", truncate(resultStr, MAX_TOOL_RESULT_LENGTH), tc.id, true));
                AILogger.i(TAG, "Tool " + toolName + (success ? " OK" : " FAIL")
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
        String clean = cleanResponse(lastMeaningfulResponse);
        if (!clean.isEmpty() && !isPromptLeakage(clean)) {
            AILogger.i(TAG, "Using last meaningful response as final answer");
            streamDirectAnswer(clean);
            if (callback != null) callback.onComplete(clean);
            return buildResponse(clean, totalTokens, System.currentTimeMillis() - startTime, toolCallCount, getAgentMaxIterations());
        }
        // 完全没有有效回答，用简单兜底
        String fb = buildSimpleFallback(userMessage);
        streamDirectAnswer(fb);
        if (callback != null) callback.onComplete(fb);
        return buildResponse(fb, totalTokens, System.currentTimeMillis() - startTime, toolCallCount, getAgentMaxIterations());
    }

    private AgentResponse buildResponse(String answer, int tokens, long time, int toolCalls, int steps) {
        AgentResponse r = new AgentResponse(answer);
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

    /**
     * 降级方案：当 nativeGenerateWithTools 不可用时，使用 prompt 模式模拟工具调用
     */
    private String generateFallbackPrompt(List<ChatMessage> history, int iteration, List<String> toolNames) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个可以使用工具的智能助手。\n");
        prompt.append("可用工具：\n").append(buildToolsPromptText(toolNames)).append("\n");
        prompt.append("对话历史：\n");
        for (ChatMessage m : history) {
            prompt.append(m.role).append(": ").append(m.content).append("\n\n");
        }
        prompt.append("规则：\n");
        prompt.append("1. 需要调用工具时，必须严格按照以下格式输出，不要输出其他内容：\n");
        prompt.append("   <tool_call>{\"name\":\"工具名\",\"arguments\":{\"参数名\":\"参数值\"}}</tool_call>\n");
        prompt.append("2. 信息已足够或不需要工具时，直接回答用户问题，给出结论。\n");
        prompt.append("3. 不要重复问题、不要自问自答、不要描述\"我将调用工具\"而不实际调用。\n");
        return LlamaHelper.generate(prompt.toString(), 500, 0.7f);
    }

    // ==================== 智能工具选择与预算守卫 ====================

    /**
     * 根据用户消息关键词智能选择相关工具（不再全量注入，避免塞满上下文）。
     * 规则：关键词路由命中 → 取命中工具；无命中 → 默认核心工具集；
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

        // 无命中时用默认核心工具集保底
        if (selected.isEmpty()) {
            for (String name : DEFAULT_CORE_TOOLS) {
                if (registered.contains(name) && !selected.contains(name)) {
                    selected.add(name);
                }
            }
        }

        // 全部未命中（工具表异常）时退化为限量全集，保证可用性
        if (selected.isEmpty()) {
            for (String name : registered) {
                if (selected.size() >= 4) break;
                selected.add(name);
            }
        }

        // 动态工具：用户消息中直接提到工具名时选中（修复动态工具无法被本地Agent调用的问题）
        try {
            for (String dynName : toolManager.getDynamicToolNames()) {
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
     * 按上下文容量的 80% 计算，上限留出足够推理空间。
     */
    private int computePromptBudget() {
        try {
            int ctxSize = aiConfig != null ? aiConfig.getContextSize() : 0;
            if (ctxSize > 0) {
                return (int) (ctxSize * PROMPT_BUDGET_RATIO);
            }
        } catch (Throwable t) {
            AILogger.w(TAG, "computePromptBudget failed: " + t.getMessage());
        }
        return 4000;
    }

    /**
     * 获取 Agent 最大迭代轮次，与上下文容量联动。
     */
    private int getAgentMaxIterations() {
        try {
            int ctxSize = aiConfig != null ? aiConfig.getContextSize() : 0;
            if (ctxSize >= 65536) return 15;
            if (ctxSize >= 32768) return 12;
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

        // 2) 仍超：从头部截断最旧的消息，保留 system(0) 与最新 N 轮
        while (trimmed.size() > 3 && total > budgetTokens) {
            trimmed.remove(trimmed.size() - 2);
            total = countTokensSafe(serializeHistory(trimmed)) + schemaTokens;
        }

        AILogger.i(TAG, "History trimmed to " + trimmed.size() + " messages, " + total + " tokens");
        return trimmed;
    }

    // ==================== Prompt 构建 ====================

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个智能AI助手，可以使用工具来帮助用户完成任务。\n\n");

        sb.append("【工具使用】\n");
        sb.append("- 需要外部信息或执行操作时，必须调用工具，不要只用自然语言回复\n");
        sb.append("- 工具返回结果后，分析结果决定是否需要继续调用其他工具\n");
        sb.append("- 同一工具连续失败2次，停止重试并说明原因\n\n");

        sb.append("【工具调用格式】\n");
        sb.append("需要调用工具时，使用以下格式输出：\n");
        sb.append("  <tool_call>{\"name\":\"工具名\",\"arguments\":{\"参数名\":\"参数值\"}}</tool_call>\n");
        sb.append("多个工具调用时依次输出多个上述格式。\n");
        sb.append("不需要工具时，直接给出最终回答。\n\n");

        sb.append("【回答要求】\n");
        sb.append("- 用中文回答，简洁准确\n");
        sb.append("- 基于工具返回的结果给出结论，不要说'请稍等'之类的话\n");
        sb.append("- 不要描述'我将调用工具'，直接调用工具后基于结果回答\n\n");

        sb.append("【UI 组件输出】\n");
        sb.append("有结构的信息（列表/表格/指标/信息卡/步骤/待办/代码等）用组件标记输出，界面自动渲染成卡片，比纯文本更美观：\n");
        sb.append("  ```component:info_card\\n{\"title\":\"标题\",\"items\":[{\"label\":\"字段\",\"value\":\"值\"}]}```\n");
        sb.append("可用类型：info_card(信息卡)/table_card(表格)/list_card(列表)/metric_card(指标)/alert_card(提示)/steps_card(步骤)/todo_card(待办)/chart(图表)/code_card(代码)/note_card(备注)/html(富内容)；也可用任意自定义类型名（通用卡片展示）。\n");
        sb.append("格式：```component:类型\\n{JSON}\\n```，JSON 用双引号，一个标记块一个组件；无法保证 JSON 合法时用普通文本即可。\n");

        return sb.toString();
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
                function.put("description", def.getDescription());
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
                    AILogger.w(TAG, "Schema token budget reached, dropping tool: " + name);
                    continue;
                }
                schemaTokens += addTokens;
                tools.put(tool);
            } catch (Exception e) {
                AILogger.w(TAG, "Build tool JSON failed: " + name);
            }
        }
        return tools.toString();
    }

    private String buildToolsPromptText(List<String> toolNames) {
        StringBuilder sb = new StringBuilder();
        for (String name : toolNames) {
            ToolDefinition def = toolManager.resolveToolDefinition(name);
            if (def != null) sb.append(def.toPromptFormat()).append("\n");
        }
        return sb.toString();
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
            "【工具使用】", "【工具使用规范】", "【输出要求】", "【推理与终止规则】",
            "你是一个智能AI助手，可以使用工具", "你是一个智能AI助手，拥有多种工具",
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

    // ==================== 工具调用解析 ====================

    // 降级模式的 <tool_call> 标签
    private static final Pattern TOOL_CALL_TAG =
            Pattern.compile("<tool_call>(.*?)</tool_call>", Pattern.DOTALL);

    /**
     * 解析模型输出中的工具调用。
     * 支持多种格式：
     * 1. 原生 FC 格式：模型直接输出 JSON（通过 common_chat_templates 处理）
     * 2. 降级标签格式：<tool_call>{...}</tool_call>
     * 3. 通用 JSON 块：响应中的 {...} 块
     */
    private List<ToolCall> parseToolCalls(String response) {
        List<ToolCall> calls = new ArrayList<>();
        if (response == null) return calls;

        // 1. 先尝试标签格式
        Matcher tagMatcher = TOOL_CALL_TAG.matcher(response);
        while (tagMatcher.find()) {
            ToolCall tc = parseToolCallJson(tagMatcher.group(1).trim());
            if (tc != null) calls.add(tc);
        }

        // 2. 如果标签格式没找到，尝试从原始 JSON 块解析。
        // 只接受以 { 开头的回复：避免把正文中的普通 JSON 文本误判为工具调用导致死循环
        if (calls.isEmpty() && response.trim().startsWith("{")) {
            for (String block : findJsonBlocks(response)) {
                ToolCall tc = parseToolCallJson(block);
                if (tc != null) calls.add(tc);
            }
        }

        // 3. 去重：相同工具名 + 相同参数的重复调用只保留一个，避免重复执行
        return dedupeToolCalls(calls);
    }

    /**
     * 去重工具调用：小模型可能重复输出同一 tool_calls，按 name+args 去重。
     */
    private List<ToolCall> dedupeToolCalls(List<ToolCall> calls) {
        if (calls == null || calls.size() <= 1) return calls;
        List<ToolCall> dedup = new ArrayList<>();
        for (ToolCall call : calls) {
            boolean dup = false;
            for (ToolCall existing : dedup) {
                if (existing.toolName.equals(call.toolName)
                        && existing.args.toString().equals(call.args.toString())) {
                    dup = true;
                    break;
                }
            }
            if (!dup) dedup.add(call);
        }
        return dedup;
    }

    /**
     * 查找文本中所有的 JSON 对象块（使用括号计数）
     */
    private List<String> findJsonBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        if (text == null) return blocks;

        int depth = 0;
        boolean inStr = false, started = false;
        StringBuilder cur = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
                inStr = !inStr;
                if (started) cur.append(c);
            } else if (!inStr) {
                if (c == '{') {
                    if (!started) { started = true; depth = 0; cur.setLength(0); }
                    depth++;
                    cur.append(c);
                } else if (c == '}') {
                    depth--;
                    cur.append(c);
                    if (depth == 0 && started) {
                        blocks.add(cur.toString());
                        cur.setLength(0);
                        started = false;
                    }
                } else if (started) {
                    cur.append(c);
                }
            } else if (started) {
                cur.append(c);
            }
        }
        return blocks;
    }

    /**
     * 解析 JSON 为 ToolCall，支持多种格式：
     * - {"name": "xxx", "args": {...}}
     * - {"function": {"name": "xxx", "arguments": "..."}} (OpenAI)
     */
    private ToolCall parseToolCallJson(String jsonStr) {
        try {
            JSONObject json = new JSONObject(jsonStr);

            // 支持 OpenAI envelope 格式：{"tool_calls": [{"function": {"name":..., "arguments":{...}}}]}
            JSONArray toolCallsArr = json.optJSONArray("tool_calls");
            if (toolCallsArr != null && toolCallsArr.length() > 0) {
                JSONObject tc = toolCallsArr.optJSONObject(0);
                if (tc != null) {
                    JSONObject fn = tc.optJSONObject("function");
                    String name = fn != null ? fn.optString("name", "") : tc.optString("name", "");
                    if (name.isEmpty()) return null;
                    JSONObject args = new JSONObject();
                    if (fn != null) {
                        String argsStr = fn.optString("arguments", "");
                        if (!argsStr.trim().isEmpty()) {
                            try { args = new JSONObject(argsStr); } catch (Exception ignored) {}
                        }
                    }
                    return new ToolCall(name, args);
                }
            }

            // 尝试多种字段名提取工具名
            String name = json.optString("name", json.optString("tool", ""));
            if (name.isEmpty()) {
                JSONObject fn = json.optJSONObject("function");
                if (fn != null) name = fn.optString("name", "");
            }

            if (name.isEmpty()) return null;

            // 尝试多种字段名提取参数
            JSONObject args = json.optJSONObject("args");
            if (args == null) args = json.optJSONObject("arguments");
            if (args == null) {
                JSONObject fn = json.optJSONObject("function");
                if (fn != null) {
                    String argsStr = fn.optString("arguments", "");
                    if (!argsStr.isEmpty()) {
                        try { args = new JSONObject(argsStr); } catch (Exception ignored) {}
                    }
                }
            }
            if (args == null) args = new JSONObject();

            return new ToolCall(name, args);
        } catch (Exception e) {
            AILogger.w(TAG, "Parse tool call failed: " + truncate(jsonStr, 80));
        }
        return null;
    }

    // ==================== 工具执行 ====================

    private AIToolResult executeToolSafely(String toolName, JSONObject args) {
        Map<String, Object> params = jsonToMap(args);
        if (params == null) return AIToolResult.fail("参数解析失败");

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

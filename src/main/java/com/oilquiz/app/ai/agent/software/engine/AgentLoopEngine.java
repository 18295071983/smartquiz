package com.oilquiz.app.ai.agent.software.engine;

import android.content.Context;

import com.oilquiz.app.ai.agent.software.model.AgentResponse;
import com.oilquiz.app.ai.agent.software.model.AgentStats;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.service.AIService;
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
    private static final int MAX_ITERATIONS = 10;
    private static final int MAX_PROMPT_TOKENS = 4000;
    private static final int MAX_TOOL_RESULT_LENGTH = 2000;
    private static final int FINAL_RESPONSE_MAX_TOKENS = 1000;
    private static final long TOOL_TIMEOUT_MS = 15000;
    private static final int MAX_RETRIES = 1;
    private static final long SYNC_TIMEOUT_MS = 60000;

    private final AIService aiService;
    private final AIToolManager toolManager;
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
    }

    public void setCallback(LoopCallback callback) {
        this.callback = callback;
    }

    // ==================== 主循环 ====================

    public AgentResponse run(String userMessage) {
        long startTime = System.currentTimeMillis();
        int totalTokens = 0;
        int toolCallCount = 0;

        String toolsJson = buildToolsJson();
        byte[] toolsJsonBytes = toolsJson.getBytes(StandardCharsets.UTF_8);
        AILogger.i(TAG, "Tools: " + toolManager.getToolsMap().size() + ", schema len: " + toolsJson.length());

        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage("system", buildSystemPrompt()));
        history.add(new ChatMessage("user", userMessage));

        for (int iteration = 1; iteration <= MAX_ITERATIONS; iteration++) {
            if (callback != null) {
                callback.onIterationStart(iteration, "第 " + iteration + " 轮推理");
            }

            long genStart = System.currentTimeMillis();
            String response = null;

            try {
                // thinking=false：当前本地模型（Qwen2.5-Instruct）不支持 thinking，
                // 传 true 会触发模板引擎 abort；native 层也会按模板能力自动降级
                response = generateWithToolsSync(history, toolsJsonBytes, 500, 0.7f, false);
            } catch (UnsatisfiedLinkError e) {
                AILogger.w(TAG, "nativeGenerateWithTools unavailable, fallback to prompt mode");
                response = generateFallbackPrompt(history, iteration);
            } catch (Exception e) {
                AILogger.e(TAG, "Generate failed at iter " + iteration + ": " + e.getMessage());
                response = null;
            }

            int iterTokens = response != null ? response.length() : 0;
            totalTokens += iterTokens;

            if (callback != null) {
                long elapsed = System.currentTimeMillis() - genStart;
                float tps = elapsed > 0 ? (iterTokens * 1000.0f) / elapsed : 0;
                callback.onInferenceProgress(totalTokens, tps);
                callback.onIterationEnd(iteration, response);
            }

            if (response == null || response.trim().isEmpty()) {
                AILogger.w(TAG, "Empty response at iteration " + iteration);
                break;
            }

            // 提取思考过程
            String thought = extractThought(response);
            if (thought != null && callback != null) {
                callback.onThinkingUpdate("第 " + iteration + " 轮思考: " + truncate(thought, 120));
            }

            // 解析工具调用
            List<ToolCall> toolCalls = parseToolCalls(response);

            if (toolCalls.isEmpty()) {
                AILogger.i(TAG, "No tool call at iteration " + iteration + ", using model answer directly");
                String cleanResponse = cleanResponse(response);
                if (cleanResponse.isEmpty()) cleanResponse = response;
                // 模型的回答即最终答案：直接流式输出，不再二次生成（避免自问自答）
                streamDirectAnswer(cleanResponse);
                if (callback != null) callback.onComplete(cleanResponse);
                return buildResponse(cleanResponse, totalTokens, System.currentTimeMillis() - startTime, toolCallCount, iteration);
            }

            // 将模型回复追加到历史
            history.add(new ChatMessage("assistant", response));

            // 执行所有工具，结果以 tool role 追加到历史
            boolean anyToolExecuted = false;
            for (ToolCall tc : toolCalls) {
                toolCallCount++;
                String toolName = tc.toolName;
                String argsStr = tc.args.toString();

                if (callback != null) callback.onToolCall(toolName, argsStr);

                AIToolResult result = executeToolSafely(toolName, tc.args);
                boolean success = result.isSuccess();
                String resultStr = result.getResult() != null
                        ? result.getResult().toString() : result.getErrorMessage();

                if (callback != null) callback.onToolResult(toolName, success, resultStr);

                history.add(new ChatMessage("tool", resultStr));
                anyToolExecuted = true;
                AILogger.i(TAG, "Tool " + toolName + (success ? " OK" : " FAIL")
                        + ": " + truncate(resultStr, 100));
            }

            // 上下文长度检查
            if (countTokensSafe(serializeHistory(history)) > MAX_PROMPT_TOKENS - 500) {
                AILogger.w(TAG, "Context too large, forcing final");
                String fr = generateFinalResponse(userMessage, response, history);
                return buildResponse(fr, totalTokens, System.currentTimeMillis() - startTime, toolCallCount, iteration);
            }
        }

        // 达到最大迭代次数
        AILogger.w(TAG, "Reached max iterations");
        String lastResp = generateFinalResponse(userMessage, "", history);
        return buildResponse(lastResp, totalTokens, System.currentTimeMillis() - startTime, toolCallCount, MAX_ITERATIONS);
    }

    private AgentResponse buildResponse(String answer, int tokens, long time, int toolCalls, int steps) {
        AgentResponse r = new AgentResponse(answer);
        r.stats = new AgentStats(tokens, time, toolCalls, steps);
        return r;
    }

    // ==================== 原生 FC 同步调用 ====================

    /**
     * 同步调用 llama.cpp 原生 function calling。
     * 使用 CountDownLatch 等待异步 onComplete 回调，超时返回 null。
     */
    private String generateWithToolsSync(List<ChatMessage> history, byte[] toolsJson,
                                         int maxTokens, float temperature, boolean thinking) {
        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder result = new StringBuilder();
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
                        if (token != null && !token.isEmpty()) {
                            result.append(token);
                            if (thinking && callback != null) {
                                callback.onToken(token);
                            }
                        }
                    }

                    @Override
                    public void onComplete(String fullText) {
                        if (fullText != null && !fullText.isEmpty()) {
                            synchronized (result) {
                                result.setLength(0);
                                result.append(fullText);
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

        return result.toString();
    }

    /**
     * 降级方案：当 nativeGenerateWithTools 不可用时，使用 prompt 模式模拟工具调用
     */
    private String generateFallbackPrompt(List<ChatMessage> history, int iteration) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个可以使用工具的智能助手。\n");
        prompt.append("可用工具：\n").append(buildToolsPromptText()).append("\n");
        prompt.append("对话历史：\n");
        for (ChatMessage m : history) {
            prompt.append(m.role).append(": ").append(m.content).append("\n\n");
        }
        prompt.append("规则：\n");
        prompt.append("1. 需要调用工具时，只输出  <tool_call>{\"name\":\"工具名\",\"args\":{...}}</tool_call>，不要输出其他内容。\n");
        prompt.append("2. 信息已足够或不需要工具时，直接回答用户问题，给出结论。\n");
        prompt.append("3. 不要重复问题、不要自问自答、不要描述\"我将调用工具\"而不实际调用。\n");
        return LlamaHelper.generate(prompt.toString(), 500, 0.7f);
    }

    // ==================== Prompt 构建 ====================

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个智能AI助手，拥有多种工具来帮助用户完成任务。系统会自动执行你发起的工具调用并返回结果。\n\n");

        sb.append("【工具使用规范】\n");
        sb.append("1. 需要实时信息（天气、时间、位置、搜索等）或执行操作时，调用合适的工具。\n");
        sb.append("2. 优先使用专用工具：查天气用 ai_weather，搜索用 network_search，计算用 python_calculate。\n");
        sb.append("3. 你拥有文件写入权限，可以使用 file_generator 创建文件、生成网页、保存数据等。\n");
        sb.append("4. 工具失败时分析原因：参数错误则修正重试，工具不适用则更换工具。\n");
        sb.append("5. 同一工具连续失败2次，停止重试，向用户说明并提供替代建议。\n\n");

        sb.append("【输出要求】\n");
        sb.append("- 用中文回答用户问题\n");
        sb.append("- 回答要简洁、准确、有条理\n");
        sb.append("- 如果使用了工具，在回答中自然地融入工具结果\n");
        sb.append("- 如果工具失败，向用户说明原因并提供替代建议\n\n");

        sb.append("【推理与终止规则】\n");
        sb.append("- 你可以多轮推理和调用工具，每次工具结果返回后继续思考\n");
        sb.append("- 如果已有足够信息，直接回答用户，不要调用不必要的工具\n");
        sb.append("- 每次回复后明确判断任务是否完成：\n");
        sb.append("  · 已完成 → 直接给出最终结论，不再调用任何工具\n");
        sb.append("  · 未完成 → 继续调用工具或补充分析\n");
        sb.append("- 严禁自问自答、重复已说过的内容或在回复中描述\"我将调用工具\"而不实际发起调用\n");
        sb.append("- 最终回答只给出结论本身，不要输出内部思考过程\n");

        return sb.toString();
    }

    private String buildToolsJson() {
        JSONArray tools = new JSONArray();
        for (Map.Entry<String, ?> entry : toolManager.getToolsMap().entrySet()) {
            ToolDefinition def = toolManager.getToolDefinition(entry.getKey());
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
                tools.put(tool);
            } catch (Exception e) {
                AILogger.w(TAG, "Build tool JSON failed: " + entry.getKey());
            }
        }
        return tools.toString();
    }

    private String buildToolsPromptText() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ?> entry : toolManager.getToolsMap().entrySet()) {
            ToolDefinition def = toolManager.getToolDefinition(entry.getKey());
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

    // ==================== 响应清理 ====================

    private String cleanResponse(String response) {
        if (response == null) return "";
        String cleaned = response
                .replaceAll("(?s)<thought>.*?</thought>", "")
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

    private static final Pattern THOUGHT_PATTERN =
            Pattern.compile("<thought>(.*?)</thought>", Pattern.DOTALL);

    private String extractThought(String response) {
        if (response == null) return null;
        Matcher m = THOUGHT_PATTERN.matcher(response);
        return m.find() ? m.group(1).trim() : null;
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

        // 2. 如果标签格式没找到，尝试从原始 JSON 块解析
        if (calls.isEmpty()) {
            for (String block : findJsonBlocks(response)) {
                ToolCall tc = parseToolCallJson(block);
                if (tc != null) calls.add(tc);
            }
        }

        return calls;
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

    // ==================== 结果生成 ====================

    /**
     * 最终回复生成：基于对话历史，通过流式 generateStream 生成最终回复。
     */
    private String generateFinalResponse(String userMessage, String llmResponse, List<ChatMessage> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("基于以下上下文，生成对用户的最终回复：\n\n");
        sb.append("【用户问题】\n").append(userMessage).append("\n\n");

        // 追加工具调用历史（从第 3 条开始，跳过 system + 初始 user）
        int toolStart = Math.min(2, history.size());
        if (history.size() > toolStart) {
            sb.append("【对话上下文】\n");
            for (int i = toolStart; i < history.size(); i++) {
                ChatMessage m = history.get(i);
                sb.append(m.role).append(": ").append(truncate(m.content, 500)).append("\n");
            }
            sb.append("\n");
        }

        if (llmResponse != null && !llmResponse.isEmpty()) {
            sb.append("【模型推理】\n").append(truncate(llmResponse, 500)).append("\n\n");
        }
        sb.append("【要求】\n");
        sb.append("- 直接输出最终回复内容，不要输出任何引导语（如\"好的\"、\"根据上下文\"）\n");
        sb.append("- 不要提问、不要重复用户问题、不要自问自答\n");
        sb.append("- 使用自然中文，基于真实数据给出结论\n");

        AILogger.i(TAG, "Final prompt len: " + sb.length());

        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder full = new StringBuilder();

        LlamaHelper.generateStream(sb.toString(), FINAL_RESPONSE_MAX_TOKENS, 0.7f, 0.9f, 40, false,
                new LlamaHelper.TokenCallback() {
                    @Override
                    public void onToken(String token) {
                        if (token != null && !token.isEmpty()) {
                            full.append(token);
                            if (callback != null) callback.onToken(token);
                        }
                    }
                    @Override
                    public void onComplete(String fullText) {
                        if (fullText != null && !fullText.isEmpty()) {
                            synchronized (full) { full.setLength(0); full.append(fullText); }
                        }
                        latch.countDown();
                    }
                    @Override
                    public void onError(String error) {
                        AILogger.e(TAG, "Final stream error: " + error);
                        latch.countDown();
                    }
                });

        try {
            boolean done = latch.await(SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!done) AILogger.w(TAG, "Final response timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String resp = full.toString();
        if (resp == null || resp.trim().isEmpty()) {
            resp = buildFallback(userMessage, history);
        }
        if (callback != null) callback.onComplete(resp);
        return resp;
    }

    private String buildFallback(String userMessage, List<ChatMessage> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("关于「").append(truncate(userMessage, 100)).append("」");
        boolean hasTools = false;
        for (ChatMessage m : history) {
            if (m.role.equals("user") && m.content.contains("工具调用结果")) {
                hasTools = true;
                break;
            }
        }
        sb.append(hasTools ? "，根据已获取的信息，以下是处理结果。" : "，我暂时无法提供详细回答。");
        return sb.toString();
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

    private static class ChatMessage {
        final String role;
        final String content;
        ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    private static class ToolCall {
        final String toolName;
        final JSONObject args;
        ToolCall(String name, JSONObject args) { this.toolName = name; this.args = args; }
    }
}

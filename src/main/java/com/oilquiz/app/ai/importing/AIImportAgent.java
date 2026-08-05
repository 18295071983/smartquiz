package com.oilquiz.app.ai.importing;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.oilquiz.app.ai.engine.ALChat;
import com.oilquiz.app.ai.agent.ToolResultInterpreter;
import com.oilquiz.app.ai.model.ModelMemoryManager;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.service.OnlineInferenceService;
import com.oilquiz.app.model.Question;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 导入专用 Agent 组件（v5）。
 * <p>
 * 核心设计：Agent 式多轮自纠 + 流式输出 + 动态上下文管理 + 格式化输出。
 * <p>
 * 工作流程（对每个文本块）：
 * <ol>
 *   <li>ANALYZE — 构建结构化 prompt（系统指令 + 文本块 + 格式约束）</li>
 *   <li>EXTRACT — 调用 LLM（在线优先，流式输出到 UI）</li>
 *   <li>VALIDATE — 三层 JSON 解析 + 字段校验</li>
 *   <li>CORRECT — 若校验失败，构建修正 prompt 让 AI 重新输出（最多 2 轮自纠）</li>
 *   <li>OUTPUT — 返回结构化 Question 列表</li>
 * </ol>
 * <p>
 * 与 Orchestrator 的关系：Orchestrator 负责文件读取/分块/入库/历史记录，
 * Agent 专注 AI 推理与题目抽取，职责分离。
 */
public class AIImportAgent {

    private static final String TAG = "AIImportAgent";

    /** 自纠最大轮次 */
    private static final int MAX_CORRECTION_ROUNDS = 2;

    /** 在线模型上下文窗口（充分利用在线模型的大窗口） */
    private static final int ONLINE_CONTEXT_MAX = 4096;
    /** 本地模型上下文窗口（保守值，避免 OOM） */
    private static final int LOCAL_CONTEXT_MAX = 2048;
    /** 上下文缓冲区 */
    private static final int CONTEXT_BUFFER = 256;

    /** Agent 状态 */
    public enum AgentState {
        IDLE, ANALYZING, EXTRACTING, VALIDATING, CORRECTING, DONE, FAILED
    }

    /** Agent 回调接口（所有方法在主线程回调） */
    public interface AgentCallback {
        /** 状态变更 */
        void onStateChanged(AgentState state, String detail);
        /** 流式 token 输出 */
        void onTokenStream(String delta, int tokenCount, float tokPerSec);
        /** 思考过程输出 */
        void onThinking(String text);
        /** 一个 chunk 处理完成 */
        void onChunkComplete(int chunkIndex, int totalChunks, List<Question> extracted);
        /** 错误 */
        void onError(String message, Throwable error);
    }

    private final Context context;
    private final OnlineInferenceService inferenceService;
    private final OnlineModelManager onlineModelManager;
    private final ALChat localChat;
    private final ModelMemoryManager memoryManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 模型模式 */
    public enum ModelMode { ONLINE_PREFERRED, ONLINE_ONLY, LOCAL_ONLY, AUTO }
    private volatile ModelMode modelMode = ModelMode.AUTO;
    private volatile boolean localModelPaused = false;
    private final AtomicInteger localFailCount = new AtomicInteger(0);
    private static final int LOCAL_FAIL_THRESHOLD = 3;

    private volatile boolean cancelled = false;

    /** token 统计 */
    private long chunkStartTimeMs;
    private int totalTokenCount;

    public AIImportAgent(Context context) {
        this.context = context.getApplicationContext();
        this.inferenceService = OnlineInferenceService.getInstance(this.context);
        this.onlineModelManager = OnlineModelManager.getInstance(this.context);
        this.localChat = new ALChat();
        this.memoryManager = ModelMemoryManager.getInstance(this.context);
    }

    public void setModelMode(ModelMode mode) {
        this.modelMode = mode;
        this.localModelPaused = false;
        this.localFailCount.set(0);
    }

    public ModelMode getModelMode() { return modelMode; }

    public void cancel() { cancelled = true; }

    public boolean hasAnyModel() {
        boolean hasOnline = onlineModelManager.getActiveModel() != null;
        boolean hasLocal = !localModelPaused && localChat.isInitialized();
        switch (modelMode) {
            case ONLINE_ONLY: return hasOnline;
            case LOCAL_ONLY:  return hasLocal;
            default:          return hasOnline || hasLocal;
        }
    }

    public String getCurrentModelInfo() {
        OnlineModelManager.OnlineModelConfig online = onlineModelManager.getActiveModel();
        switch (modelMode) {
            case ONLINE_ONLY:
                return online != null ? "在线: " + online.name : "在线(无可用模型)";
            case LOCAL_ONLY:
                if (localModelPaused) return "本地(已暂停-故障)";
                return localChat.isInitialized() ? "本地模型" : "本地(未加载)";
            case ONLINE_PREFERRED:
                if (online != null) return "在线: " + online.name;
                if (localModelPaused) return "本地(已暂停-故障)";
                return localChat.isInitialized() ? "本地模型(降级)" : "本地(未加载)";
            case AUTO:
            default:
                if (online != null) return "在线: " + online.name;
                if (localModelPaused) return "本地(已暂停-故障)";
                return localChat.isInitialized() ? "本地模型" : "无可用模型";
        }
    }

    // ======================== 核心：处理单个 chunk ========================

    /**
     * 对单个文本块执行 Agent 式抽取（含自纠循环）。
     *
     * @param chunk       文本块
     * @param chunkIndex  块索引
     * @param totalChunks 总块数
     * @param callback    回调
     * @return 抽取出的题目列表（可能为空）
     */
    public List<Question> extractFromChunk(String chunk, int chunkIndex, int totalChunks, AgentCallback callback) {
        if (cancelled) return new ArrayList<>();

        try {
            return doExtractFromChunk(chunk, chunkIndex, totalChunks, callback);
        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "Chunk " + chunkIndex + " OOM，紧急回收后跳过", oom);
            emergencyMemoryCleanup();
            notifyError(callback, "内存不足，已跳过 Chunk " + (chunkIndex + 1), oom);
            return new ArrayList<>();
        } catch (Throwable t) {
            // 模型崩溃隔离：任何内部异常都不向外传播，返回空列表让调用方继续处理下一个 chunk
            Log.e(TAG, "Chunk " + chunkIndex + " 抽取异常，隔离崩溃: " + t.getMessage(), t);
            notifyError(callback, "Chunk " + (chunkIndex + 1) + " AI异常: " + t.getMessage(), t);
            return new ArrayList<>();
        }
    }

    /**
     * extractFromChunk 的实际实现（已被 try-catch 保护）。
     */
    private List<Question> doExtractFromChunk(String chunk, int chunkIndex, int totalChunks, AgentCallback callback) {
        // 动态计算上下文预算
        boolean useOnline = shouldUseOnline();
        int contextMax = useOnline ? ONLINE_CONTEXT_MAX : LOCAL_CONTEXT_MAX;

        // 构建 prompt
        notifyState(callback, AgentState.ANALYZING, "分析文本块 " + (chunkIndex + 1) + "/" + totalChunks);

        String systemPrompt = QuestionSchemaDictionary.getMinimalExtractionPrompt();
        JSONObject schema;
        try {
            schema = QuestionSchemaDictionary.getMinimalExtractionSchema();
        } catch (Exception e) {
            notifyError(callback, "构建 schema 失败: " + e.getMessage(), e);
            return new ArrayList<>();
        }

        int promptOverhead = estimateTokens(systemPrompt) + estimateTokens(schema.toString());
        int availableBudget = contextMax - promptOverhead - CONTEXT_BUFFER;

        // Token 预算守卫：截断过长的 chunk
        String workingChunk = chunk;
        if (estimateTokens(workingChunk) > availableBudget) {
            workingChunk = truncateToTokenBudget(workingChunk, availableBudget);
            notifyThinking(callback, "文本块过长，已截断至 " + availableBudget + " token 预算");
        }

        String prompt = systemPrompt + "\n\n## 待提取文本\n" + workingChunk;

        // ========== 诊断：chunk输入+prompt构建 ==========
        com.oilquiz.app.util.ImportDebugTracer.trace("【6】Agent-chunk输入(chunk" + (chunkIndex+1) + ")",
                workingChunk.substring(0, Math.min(500, workingChunk.length())));

        // Agent 循环：EXTRACT → VALIDATE → CORRECT（最多 MAX_CORRECTION_ROUNDS 轮）
        List<Question> result = new ArrayList<>();
        String currentPrompt = prompt;
        int round = 0;

        while (round <= MAX_CORRECTION_ROUNDS && !cancelled) {
            notifyState(callback, round == 0 ? AgentState.EXTRACTING : AgentState.CORRECTING,
                    round == 0 ? "AI 抽取中..." : "自纠第 " + round + " 轮...");

            // 调用 LLM
            chunkStartTimeMs = System.currentTimeMillis();
            String rawOutput = callLlm(currentPrompt, schema, useOnline, callback);

            // ========== 诊断：AI原始输出 ==========
            com.oilquiz.app.util.ImportDebugTracer.trace("【7】Agent-AI原始输出-round" + round,
                    rawOutput == null ? "[null]" : rawOutput.substring(0, Math.min(600, rawOutput.length())));

            if (rawOutput == null || rawOutput.trim().isEmpty()) {
                notifyThinking(callback, "AI 输出为空" + (round < MAX_CORRECTION_ROUNDS ? "，尝试重试..." : ""));
                round++;
                continue;
            }

            notifyState(callback, AgentState.VALIDATING, "校验输出格式...");

            // 三层 JSON 解析
            JSONObject root = ImportValidator.parseStructuredOutput(rawOutput);
            if (root == null) {
                // JSON 解析完全失败
                List<String> errors = new ArrayList<>();
                errors.add("输出无法解析为合法 JSON");
                if (round < MAX_CORRECTION_ROUNDS) {
                    notifyThinking(callback, "JSON 解析失败，启动自纠...");
                    currentPrompt = QuestionSchemaDictionary.getCorrectionPrompt(rawOutput, errors);
                    round++;
                    continue;
                } else {
                    notifyThinking(callback, "JSON 解析失败，已达最大自纠轮次");
                    break;
                }
            }

            // 提取题目
            List<Question> questions = extractQuestionsFromJsonObject(root);

            if (questions.isEmpty()) {
                notifyThinking(callback, "AI 未从该块提取到题目");
                break;
            }

            // 字段校验
            List<String> validationErrors = collectValidationErrors(questions);

            if (validationErrors.isEmpty() || round >= MAX_CORRECTION_ROUNDS) {
                // 校验通过或已达最大轮次，接受当前结果
                result = questions;
                if (!validationErrors.isEmpty()) {
                    notifyThinking(callback, "存在 " + validationErrors.size() + " 个校验警告，已达最大自纠轮次，接受当前结果");
                }
                break;
            } else {
                // 有校验错误且还能自纠
                notifyThinking(callback, "发现 " + validationErrors.size() + " 个校验错误，启动自纠第 " + (round + 1) + " 轮...");
                // 只传前 5 条错误避免 prompt 过长
                List<String> topErrors = validationErrors.subList(0, Math.min(5, validationErrors.size()));
                currentPrompt = QuestionSchemaDictionary.getCorrectionPrompt(rawOutput, topErrors);
                round++;
            }
        }

        notifyState(callback, AgentState.DONE, "块 " + (chunkIndex + 1) + " 完成: 提取 " + result.size() + " 题");
        return result;
    }

    // ======================== LLM 调用 ========================

    /**
     * 判断是否使用在线模型
     */
    private boolean shouldUseOnline() {
        OnlineModelManager.OnlineModelConfig onlineConfig = onlineModelManager.getActiveModel();
        if (modelMode == ModelMode.LOCAL_ONLY) return false;
        if (modelMode == ModelMode.ONLINE_ONLY) return onlineConfig != null;
        // AUTO / ONLINE_PREFERRED: 有在线就用在线
        return onlineConfig != null;
    }

    /**
     * 调用 LLM（在线优先，带流式输出）
     */
    private String callLlm(String prompt, JSONObject schema, boolean useOnline, AgentCallback callback) {
        if (useOnline) {
            try {
                OnlineModelManager.OnlineModelConfig config = onlineModelManager.getActiveModel();
                if (config == null) {
                    if (modelMode == ModelMode.ONLINE_ONLY) return null;
                    // 降级到本地
                    return callLocalLlm(prompt, callback);
                }

                // 在线模型：优先用结构化输出
                try {
                    String result = inferenceService.generateStructuredAsync(prompt, config, schema, 2048).join();
                    if (result != null && !result.trim().isEmpty()) {
                        result = ToolResultInterpreter.cleanModelOutput(result);
                        if (result == null) {
                            Log.w(TAG, "结构化输出检测为乱码，降级到普通生成");
                            throw new Exception("模型输出乱码");
                        }
                        localFailCount.set(0);
                        // 模拟流式输出（结构化输出是同步的，这里拆分成 token 流给 UI）
                        simulateTokenStream(result, callback);
                        return result;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "结构化输出失败，降级到普通生成: " + e.getMessage());
                    // 降级到普通异步生成
                    try {
                        java.util.List<com.oilquiz.app.ai.chat.ChatMessage> emptyHistory = new ArrayList<>();
                        String result = inferenceService.generateAsync(prompt, config, emptyHistory, 2048, false).get();
                        if (result != null && !result.trim().isEmpty()) {
                            result = ToolResultInterpreter.cleanModelOutput(result);
                            if (result == null) {
                                Log.w(TAG, "普通生成也检测为乱码");
                                return null;
                            }
                            localFailCount.set(0);
                            simulateTokenStream(result, callback);
                            return result;
                        }
                    } catch (Exception e2) {
                        Log.w(TAG, "普通生成也失败: " + e2.getMessage());
                    }
                }

                // 在线全部失败，如果是 AUTO/ONLINE_PREFERRED 模式，降级到本地
                if (modelMode == ModelMode.ONLINE_ONLY) return null;
                return callLocalLlm(prompt, callback);

            } catch (Exception e) {
                Log.w(TAG, "在线调用异常: " + e.getMessage());
                if (modelMode == ModelMode.ONLINE_ONLY) return null;
                return callLocalLlm(prompt, callback);
            }
        }

        // 本地模型
        return callLocalLlm(prompt, callback);
    }

    /**
     * 调用本地模型
     */
    private String callLocalLlm(String prompt, AgentCallback callback) {
        if (localModelPaused || !localChat.isInitialized()) {
            return null;
        }

        ModelMemoryManager.MemoryState memState = memoryManager.getMemoryState();
        if (memState == ModelMemoryManager.MemoryState.OUT_OF_MEMORY) return null;
        if (memState == ModelMemoryManager.MemoryState.CRITICAL) {
            System.gc();
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }

        try {
            String raw = localChat.sendMessage(prompt, 1024, 0.1f, 0.9f, 40);
            raw = ToolResultInterpreter.cleanModelOutput(raw);
            if (raw != null && !raw.trim().isEmpty()) {
                localFailCount.set(0);
                simulateTokenStream(raw, callback);
            }
            return (raw != null && !raw.trim().isEmpty()) ? raw : null;
        } catch (OutOfMemoryError oom) {
            handleLocalFailure("内存溢出");
            emergencyMemoryCleanup();
            return null;
        } catch (Throwable t) {
            handleLocalFailure(t.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 将同步结果模拟为 token 流输出到 UI
     */
    private void simulateTokenStream(String text, AgentCallback callback) {
        if (text == null || text.isEmpty() || callback == null) return;
        long elapsed = Math.max(1, System.currentTimeMillis() - chunkStartTimeMs);
        // 按 4 字符一组拆分模拟流式
        int chunkSize = 4;
        int count = 0;
        for (int i = 0; i < text.length(); i += chunkSize) {
            int end = Math.min(i + chunkSize, text.length());
            final String delta = text.substring(i, end);
            count += (end - i);
            final int tokenCount = count / 2; // 粗略 token 估算
            final float tokPerSec = tokenCount / (elapsed / 1000f);
            mainHandler.post(() -> callback.onTokenStream(delta, tokenCount, tokPerSec));
        }
        totalTokenCount += text.length() / 2;
    }

    // ======================== 题目解析 ========================

    private List<Question> extractQuestionsFromJsonObject(JSONObject root) {
        List<Question> list = new ArrayList<>();
        if (root == null) return list;
        try {
            org.json.JSONArray arr = root.optJSONArray("questions");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    try {
                        Question q = parseMinimalQuestion(arr.getJSONObject(i));
                        if (q != null) {
                            list.add(q);
                            // ========== 诊断：JSON解析后首道Question ==========
                            if (i == 0) {
                                com.oilquiz.app.util.ImportDebugTracer.traceQuestion(
                                        "【8】Agent-JSON解析后第1题", q);
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "题目提取失败: " + e.getMessage());
        }
        return list;
    }

    private Question parseMinimalQuestion(org.json.JSONObject jo) {
        // 使用 FieldMappingRegistry 统一提取（自动兼容中英文/变体字段名）
        return FieldMappingRegistry.extractFromJson(jo);
    }

    /**
     * 收集所有校验错误（用于自纠 prompt）
     */
    private List<String> collectValidationErrors(List<Question> questions) {
        List<String> errors = new ArrayList<>();
        if (questions == null) return errors;
        for (int i = 0; i < questions.size(); i++) {
            List<ImportValidator.ValidationError> errs = ImportValidator.validateQuestion(questions.get(i));
            for (ImportValidator.ValidationError ve : errs) {
                errors.add("第" + (i + 1) + "题 " + ve.field + ": " + ve.message);
            }
        }
        return errors;
    }

    // ======================== 辅助方法 ========================

    private void notifyState(AgentCallback callback, AgentState state, String detail) {
        if (callback != null) {
            mainHandler.post(() -> callback.onStateChanged(state, detail));
        }
    }

    private void notifyThinking(AgentCallback callback, String text) {
        if (callback != null) {
            mainHandler.post(() -> callback.onThinking(text));
        }
    }

    private void notifyError(AgentCallback callback, String msg, Throwable error) {
        if (callback != null) {
            mainHandler.post(() -> callback.onError(msg, error));
        }
    }

    private void handleLocalFailure(String reason) {
        int fails = localFailCount.incrementAndGet();
        if (fails >= LOCAL_FAIL_THRESHOLD) {
            localModelPaused = true;
            Log.w(TAG, "本地模型已暂停(连续失败" + LOCAL_FAIL_THRESHOLD + "次: " + reason + ")");
        }
    }

    private void emergencyMemoryCleanup() {
        try {
            System.gc();
            System.runFinalization();
            Thread.sleep(300);
        } catch (InterruptedException ignored) {}
    }

    private int estimateTokens(String s) {
        if (s == null || s.isEmpty()) return 0;
        int chinese = 0, englishWords = 0;
        boolean inWord = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                chinese++;
                if (inWord) { englishWords++; inWord = false; }
            } else if (Character.isLetterOrDigit(c)) {
                inWord = true;
            } else {
                if (inWord) { englishWords++; inWord = false; }
            }
        }
        if (inWord) englishWords++;
        return Math.round(chinese * 1.5f) + englishWords;
    }

    private String truncateToTokenBudget(String text, int maxTokens) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder sb = new StringBuilder();
        int tokens = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int addition = (c >= 0x4E00 && c <= 0x9FFF) ? 2 : 1;
            if (tokens + addition > maxTokens) break;
            sb.append(c);
            tokens += addition;
        }
        return sb.toString();
    }
}

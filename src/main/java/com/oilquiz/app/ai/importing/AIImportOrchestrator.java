package com.oilquiz.app.ai.importing;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.oilquiz.app.ai.engine.ALChat;
import com.oilquiz.app.ai.importing.model.AIImportResult;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.service.OnlineInferenceService;
import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.ai.tool.FileReaderTool;
import com.oilquiz.app.database.DatabaseManager;
import com.oilquiz.app.model.ImportHistory;
import com.oilquiz.app.model.Question;
import com.oilquiz.app.repository.ImportHistoryRepository;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 题库 AI 导入编排引擎(chunk 级流式闭环)。
 * <p>
 * 串联 4 阶段流水线:文件识别(PROFILE) → 结构识别(DISCOVER) → 动态导入(INGEST) → 完成(DONE)。
 * 核心改进:INGEST 阶段对每个 chunk 执行"抽取 → 校验 → 修复 → 入库 → 回调"闭环,
 * 用户可在导入过程中实时看到已入库题数,而非等待全部抽取完毕才入库。
 * <p>
 * 4 阶段作为状态机驱动,通过 {@link ImportListener} 回调 UI 实时显示进度。
 * 所有 IO/推理在单线程 executor 上执行,listener 回调通过主线程 Handler 切回 UI 线程。
 * <p>
 * 不修改任何现有类,仅复用 {@link FileProfiler}/{@link StructureProfile}/{@link DynamicSchemaBuilder}/
 * {@link QuestionSchemaDictionary}/{@link ImportValidator}/{@link OnlineInferenceService}/
 * {@link ALChat}/{@link DatabaseManager} 等公开 API。
 */
public class AIImportOrchestrator {

    private static final String TAG = "AIImportOrchestrator";

    /** 单块最大 token 估算上限 */
    private static final int CHUNK_MAX_TOKENS = 2000;
    /** 单次批量入库题数上限(保留常量,单题入库模式下不再分批) */
    private static final int BATCH_MAX_QUESTIONS = 50;
    /** 大文件题目数阈值 */
    private static final int LARGE_FILE_THRESHOLD = 500;
    /** 每个 chunk 抽取失败后的重试次数 */
    private static final int MAX_RETRY_PER_CHUNK = 1;
    /** 校验自修复最大轮数 */
    private static final int MAX_SELF_REPAIR_ROUND = 1;
    /** chunk 内单次自修复的无效题上限 */
    private static final int REPAIR_BATCH_LIMIT = 10;
    /** chunk overlap 比例(依据 NVIDIA 实验:15% overlap 最优,防边界截断) */
    private static final float CHUNK_OVERLAP_RATIO = 0.15f;
    /** 过短块阈值(字符数),低于此值与前一块合并避免碎块 */
    private static final int MIN_CHUNK_LENGTH = 200;
    /** 低置信度门控阈值(依据 byaiteam:confidence<0.7 不直接入库,待复核) */
    private static final float LOW_CONFIDENCE_THRESHOLD = 0.7f;

    private final Context context;
    private final OnlineInferenceService inferenceService;
    private final OnlineModelManager onlineModelManager;
    private final DatabaseManager databaseManager;
    private final ALChat localChat = new ALChat();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean cancelled = false;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AIImport-Orchestrator");
        t.setDaemon(true);
        return t;
    });

    // ======================== listener 接口 ========================

    /** 导入过程监听器,所有方法在主线程回调。 */
    public interface ImportListener {
        /** 阶段切换 */
        void onStage(Stage stage, String message);
        /** 进度更新 */
        void onProgress(int current, int total, String detail);
        /** 流式 token(本实现为非流式,暂不主动调用) */
        void onTokenStream(String delta, int tokenCount, float tokPerSec);
        /** 思考链片段 */
        void onThinking(String text);
        /** 增量预览题目 */
        void onPreviewQuestions(List<Question> partial);
        /** 动态导入:实时已入库数(每 chunk 入库后回调) */
        void onImportedBatch(int imported, int duplicated, int failed);
        /** 完成 */
        void onComplete(AIImportResult result);
        /** 错误 */
        void onError(String message, Throwable error);
    }

    /** 流水线阶段 */
    public enum Stage {
        PROFILE, DISCOVER, INGEST, DONE
    }

    // ======================== 构造与生命周期 ========================

    public AIImportOrchestrator(Context context) {
        this.context = context.getApplicationContext();
        this.inferenceService = OnlineInferenceService.getInstance(this.context);
        this.onlineModelManager = OnlineModelManager.getInstance(this.context);
        this.databaseManager = DatabaseManager.getInstance(this.context);
    }

    /** 异步执行 4 阶段流水线 */
    public void start(File file, ImportListener listener) {
        cancelled = false;
        executor.execute(() -> runPipeline(file, listener));
    }

    /** 取消导入,各阶段检查 cancelled 后提前返回 */
    public void cancel() {
        cancelled = true;
    }

    // ======================== 流水线主流程 ========================

    private void runPipeline(File file, ImportListener listener) {
        long startMs = System.currentTimeMillis();
        try {
            // 阶段 1 PROFILE:文件识别(编码+采样+预估)
            ProfileResult pr = stageProfile(file, listener);
            if (pr == null || cancelled) return;

            // 阶段 2 DISCOVER:结构识别 → 动态 Schema(失败返回 null 回退固定 schema)
            StructureProfile sp = stageDiscover(pr, listener);
            if (cancelled) return;
            // 无可用模型已报错,中止流水线(避免后续空转触发 onComplete 与 onError 重复)
            if (sp == null && !hasAnyModel()) return;

            // 阶段 3 INGEST:逐 chunk 流式闭环(抽取 → 校验 → 修复 → 入库 → 回调)
            IngestStats stats = stageIngest(pr.chunks, sp, listener);
            if (cancelled) return;

            // 阶段 4 DONE:写历史 + 组装结果
            AIImportResult result = finalizeResult(file, pr, stats, startMs, listener);
            if (cancelled) return;
            final AIImportResult finalResult = result;
            runOnMain(() -> listener.onComplete(finalResult));
        } catch (Exception e) {
            Log.e(TAG, "流水线异常: " + e.getMessage(), e);
            final String msg = "导入失败: " + e.getMessage();
            runOnMain(() -> listener.onError(msg, e));
        }
    }

    // ======================== 阶段 1:PROFILE ========================

    /** 文件识别结果 holder */
    private static class ProfileResult {
        String fullText;
        List<String> chunks;
        FileProfiler.FileProfile fileProfile;
    }

    /** 文件识别:编码探测 + 全文读取 + 分块,失败时已触发 onError 并返回 null */
    private ProfileResult stageProfile(File file, ImportListener listener) {
        runOnMain(() -> listener.onStage(Stage.PROFILE, "🤖 识别文件..."));
        FileProfiler.FileProfile fp = FileProfiler.profile(file);
        if (fp == null) {
            final String msg = "文件识别失败: " + file.getName();
            runOnMain(() -> listener.onError(msg, null));
            return null;
        }
        // 用探测到的编码读全文
        String text = readFileAsTextWithEncoding(file, fp.encoding);
        if (text == null || text.trim().isEmpty()) {
            final String msg = "文件内容为空或读取失败: " + file.getName();
            runOnMain(() -> listener.onError(msg, null));
            return null;
        }
        if (looksLikeGarbled(text)) {
            final String msg = "文件内容疑似乱码,无法解析: " + file.getName();
            runOnMain(() -> listener.onError(msg, null));
            return null;
        }
        List<String> chunks = chunkText(text);
        if (chunks.isEmpty()) {
            final String msg = "文件分块后为空: " + file.getName();
            runOnMain(() -> listener.onError(msg, null));
            return null;
        }
        final String encoding = fp.encoding;
        final int est = fp.estimatedCount;
        final String fmt = fp.formatHint;
        runOnMain(() -> listener.onThinking(
                "编码:" + encoding + ",预估 " + est + " 题,格式:" + fmt));
        final int total = chunks.size();
        runOnMain(() -> listener.onProgress(0, total, "共 " + total + " 块"));

        ProfileResult pr = new ProfileResult();
        pr.fullText = text;
        pr.chunks = chunks;
        pr.fileProfile = fp;
        return pr;
    }

    // ======================== 阶段 2:DISCOVER ========================

    /** 结构识别 → 动态 Schema;LLM 调用失败返回 null(回退固定 schema) */
    private StructureProfile stageDiscover(ProfileResult pr, ImportListener listener) {
        runOnMain(() -> listener.onStage(Stage.DISCOVER, "🔍 结构识别..."));
        if (!hasAnyModel()) {
            runOnMain(() -> listener.onError("无可用 AI 模型,请配置在线模型或加载本地模型", null));
            return null;
        }
        try {
            String prompt = DynamicSchemaBuilder.buildDiscoveryPrompt()
                    + "\n\n文本:\n" + pr.fileProfile.sampleHead;
            String json = callLlmStructured(prompt,
                    DynamicSchemaBuilder.buildDiscoverySchema(), 1024, 0.3f);
            if (json != null) {
                StructureProfile sp = DynamicSchemaBuilder.parseProfile(new JSONObject(json));
                sp.rawAnalysis = json;
                final List<String> types = sp.detectedQuestionTypes;
                final List<String> fields = sp.presentFields;
                final float conf = sp.confidence;
                runOnMain(() -> listener.onThinking(
                        "结构识别:题型" + types + ",字段" + fields + ",置信度" + conf));
                return sp;
            }
        } catch (Exception e) {
            Log.w(TAG, "结构识别失败: " + e.getMessage());
        }
        runOnMain(() -> listener.onThinking("结构识别失败,使用固定 schema 回退"));
        return null;
    }

    // ======================== 阶段 3:INGEST(核心流式闭环) ========================

    /** 动态导入统计 holder */
    private static class IngestStats {
        int imported;
        int duplicated;
        int failed;
        List<Question> allValid = new ArrayList<>();
        List<Question> allInvalid = new ArrayList<>();
        List<String> errors = new ArrayList<>();
    }

    /** 抽取项:Question + 置信度(confidence 不入库,仅过程用,依据 byaiteam) */
    private static class ExtractedItem {
        final Question question;
        final float confidence;
        ExtractedItem(Question q, float c) { question = q; confidence = c; }
    }

    /**
     * 逐 chunk 流式闭环:抽取 → 校验分桶 → 增量修复 → 逐题入库 → 实时回调。
     * 结构识别成功(sp 非空且 presentFields 非空)用动态 schema/prompt,否则回退固定 schema。
     */
    private IngestStats stageIngest(List<String> chunks, StructureProfile sp, ImportListener listener) {
        runOnMain(() -> listener.onStage(Stage.INGEST, "⚙️ 动态导入中..."));
        IngestStats stats = new IngestStats();

        // 动态 schema/prompt(结构识别成功用动态,否则固定回退)
        JSONObject schema;
        String systemPrompt;
        try {
            if (sp != null && sp.presentFields != null && !sp.presentFields.isEmpty()) {
                schema = DynamicSchemaBuilder.buildExtractionSchema(sp);
                systemPrompt = DynamicSchemaBuilder.buildExtractionPrompt(sp);
            } else {
                schema = QuestionSchemaDictionary.getExtractionSchema();
                systemPrompt = QuestionSchemaDictionary.getExtractionSystemPrompt();
            }
        } catch (JSONException e) {
            final String msg = "构建抽取 schema 失败: " + e.getMessage();
            runOnMain(() -> listener.onError(msg, e));
            return stats;
        }

        int total = chunks.size();
        for (int i = 0; i < total; i++) {
            if (cancelled) break;
            String chunk = chunks.get(i);

            // 3a 抽取(失败重试 1 次,降温度)
            String json = extractOneChunk(systemPrompt, schema, chunk);
            if (json == null) {
                final int idx = i + 1;
                final int imp = stats.imported;
                runOnMain(() -> listener.onProgress(idx, total, "已入库 " + imp + " 题"));
                continue;
            }
            List<ExtractedItem> qs = extractQuestionsFromJson(json, listener);

            // 3b 校验分桶 + confidence 门控(依据 byaiteam:单条 confidence + 低置信度复核)
            List<ExtractedItem> valid = new ArrayList<>();
            List<ExtractedItem> invalid = new ArrayList<>();
            List<ExtractedItem> lowConf = new ArrayList<>();  // 校验通过但 confidence<0.7 待复核
            for (ExtractedItem item : qs) {
                List<ImportValidator.ValidationError> errs = ImportValidator.validateQuestion(item.question);
                if (errs.isEmpty()) {
                    if (item.confidence < LOW_CONFIDENCE_THRESHOLD) {
                        lowConf.add(item);
                    } else {
                        valid.add(item);
                    }
                } else {
                    invalid.add(item);
                    for (ImportValidator.ValidationError ve : errs) {
                        stats.errors.add(ve.toString());
                    }
                }
            }

            // 3c 增量修复(invalid + lowConf 一起喂回 LLM,≤ 上限 1 轮;低置信度复核,依据 byaiteam)
            List<ExtractedItem> repairCandidates = new ArrayList<>();
            repairCandidates.addAll(invalid);
            repairCandidates.addAll(lowConf);
            if (!repairCandidates.isEmpty() && repairCandidates.size() <= REPAIR_BATCH_LIMIT
                    && MAX_SELF_REPAIR_ROUND >= 1 && hasAnyModel()) {
                List<ExtractedItem> repaired = repairQuestions(repairCandidates, schema);
                // 再校验:修复通过且 confidence≥0.7 移入 valid;校验未通过 → stillInvalid;通过但低置信 → stillLowConf
                List<ExtractedItem> stillInvalid = new ArrayList<>();
                List<ExtractedItem> stillLowConf = new ArrayList<>();
                for (ExtractedItem rItem : repaired) {
                    List<ImportValidator.ValidationError> errs = ImportValidator.validateQuestion(rItem.question);
                    if (errs.isEmpty()) {
                        if (rItem.confidence < LOW_CONFIDENCE_THRESHOLD) {
                            stillLowConf.add(rItem);
                        } else {
                            valid.add(rItem);
                        }
                    } else {
                        stillInvalid.add(rItem);
                    }
                }
                invalid = stillInvalid;
                lowConf = stillLowConf;
            }

            // 3d 逐题入库(去重)
            for (ExtractedItem item : valid) {
                if (cancelled) break;
                int r = persistOneQuestion(item.question);
                if (r == 0) stats.imported++;
                else if (r == 1) stats.duplicated++;
                else stats.failed++;
                stats.allValid.add(item.question);
            }
            // 校验失败的 invalid 题记入 allInvalid
            for (ExtractedItem item : invalid) {
                stats.allInvalid.add(item.question);
            }
            // 低置信度待复核题:不直接入库,记入 allInvalid + errorMessages 标注"低置信度"(依据 byaiteam)
            for (ExtractedItem item : lowConf) {
                stats.allInvalid.add(item.question);
                String qt = item.question.getQuestionText();
                String preview = (qt == null) ? "" : qt.substring(0, Math.min(20, qt.length()));
                stats.errors.add("低置信度(" + String.format("%.2f", item.confidence) + "):" + preview);
            }

            // 3e 实时回调(chunk 内闭环可见)
            final int imp = stats.imported;
            final int dup = stats.duplicated;
            final int fail = stats.failed;
            final int idx = i + 1;
            runOnMain(() -> listener.onImportedBatch(imp, dup, fail));
            final List<Question> snapshot = new ArrayList<>(stats.allValid);
            runOnMain(() -> listener.onPreviewQuestions(snapshot));
            runOnMain(() -> listener.onProgress(
                    idx, total, "已入库 " + imp + " 题,重复 " + dup));
        }
        return stats;
    }

    /** 单 chunk 抽取:失败重试 1 次(降温度 0.2),返回 JSON 字符串或 null */
    private String extractOneChunk(String systemPrompt, JSONObject schema, String chunk) {
        String prompt = systemPrompt + "\n\n请从以下文本抽取题目,严格输出符合 schema 的 JSON:\n" + chunk;
        try {
            String json = callLlmStructured(prompt, schema, 4096, 0.3f);
            if (json == null && MAX_RETRY_PER_CHUNK >= 1) {
                json = callLlmStructured(prompt, schema, 4096, 0.2f);
            }
            return json;
        } catch (Exception e) {
            Log.w(TAG, "Chunk 抽取异常: " + e.getMessage());
            return null;
        }
    }

    /** 批量自修复:将无效/低置信题连同错误详情喂回 LLM 修正,返回修复后抽取项(可能仍含无效/低置信,依据 byaiteam) */
    private List<ExtractedItem> repairQuestions(List<ExtractedItem> invalid, JSONObject schema) {
        List<ExtractedItem> repaired = new ArrayList<>();
        if (invalid == null || invalid.isEmpty()) return repaired;
        try {
            JSONArray arr = new JSONArray();
            for (ExtractedItem item : invalid) {
                arr.put(questionToJson(item.question));
            }
            StringBuilder errDetail = new StringBuilder();
            for (ExtractedItem item : invalid) {
                List<ImportValidator.ValidationError> errs = ImportValidator.validateQuestion(item.question);
                for (ImportValidator.ValidationError ve : errs) {
                    errDetail.append(ve.toString()).append("\n");
                }
            }
            String prompt = QuestionSchemaDictionary.getExtractionSystemPrompt()
                    + "\n\n以下题目校验失败或置信度偏低:\n" + errDetail
                    + "\n请修正后重新输出符合 schema 的 JSON:\n" + arr.toString();
            String fixed = callLlmStructured(prompt, schema, 4096, 0.2f);
            if (fixed != null) {
                repaired = extractQuestionsFromJson(fixed, null);
            }
        } catch (Exception e) {
            Log.w(TAG, "自修复失败: " + e.getMessage());
        }
        return repaired;
    }

    /** 单题入库去重,返回 0=新增 1=重复更新 2=失败 */
    private int persistOneQuestion(Question q) {
        long now = System.currentTimeMillis();
        q.setSource("AI导入");
        q.setCreatedAt(now);
        q.setUpdatedAt(now);
        // 去重:取 questionText 前 20 字搜索候选
        String text = q.getQuestionText();
        String keyword = (text != null && text.length() > 20) ? text.substring(0, 20) : text;
        if (keyword != null && !keyword.trim().isEmpty()) {
            try {
                List<Question> candidates = databaseManager.searchQuestions(keyword).get();
                if (candidates != null) {
                    for (Question c : candidates) {
                        if (equalsStr(c.getQuestionText(), q.getQuestionText())
                                && equalsStr(c.getCategory(), q.getCategory())) {
                            q.setId(c.getId());
                            databaseManager.updateQuestion(q).get();
                            return 1;
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "去重查询失败: " + e.getMessage());
            }
        }
        try {
            List<Question> one = new ArrayList<>();
            one.add(q);
            databaseManager.addQuestions(one).get();
            return 0;
        } catch (Exception e) {
            Log.w(TAG, "插入失败: " + e.getMessage());
            return 2;
        }
    }

    // ======================== 阶段 4:DONE ========================

    /** 写导入历史 + 组装结果 */
    private AIImportResult finalizeResult(File file, ProfileResult pr, IngestStats stats,
                                          long startMs, ImportListener listener) {
        runOnMain(() -> listener.onStage(Stage.DONE, "✅ 完成"));
        // 记录导入历史
        try {
            Application app = asApplication(context);
            if (app != null) {
                ImportHistory history = new ImportHistory(file.getName(), file.getAbsolutePath(),
                        stats.imported + stats.duplicated, stats.failed,
                        System.currentTimeMillis(), "成功");
                new ImportHistoryRepository(app).addImportHistory(history);
            }
        } catch (Exception e) {
            Log.w(TAG, "记录导入历史失败: " + e.getMessage());
        }
        // 组装结果
        AIImportResult result = new AIImportResult();
        result.setValidQuestions(stats.allValid);
        result.setInvalidQuestions(stats.allInvalid);
        result.setErrorMessages(stats.errors);
        result.setTotalDetected(stats.allValid.size() + stats.allInvalid.size());
        result.setTotalImported(stats.imported + stats.duplicated);
        result.setDuplicatedCount(stats.duplicated);
        result.setImportTimeMs(System.currentTimeMillis() - startMs);
        result.setFieldCoverage(ImportValidator.computeFieldCoverage(stats.allValid));
        result.setSourceFileName(file.getName());
        result.setSuccess(true);
        return result;
    }

    // ======================== LLM 调用辅助 ========================

    /** 是否有任何可用模型(在线或本地) */
    private boolean hasAnyModel() {
        return onlineModelManager.getActiveModel() != null || localChat.isInitialized();
    }

    /**
     * 统一 LLM 结构化调用:在线优先,失败降级本地 ALChat。
     * 返回"经过 repair 的、可被 JSONObject 解析的"JSON 字符串;失败返回 null。
     */
    private String callLlmStructured(String prompt, JSONObject schema, int maxTokens, float temperature) {
        OnlineModelManager.OnlineModelConfig config = onlineModelManager.getActiveModel();
        if (config != null) {
            try {
                return inferenceService.generateStructuredAsync(prompt, config, schema, maxTokens).join();
            } catch (Exception e) {
                Log.w(TAG, "在线结构化调用失败,降级本地: " + e.getMessage());
            }
        }
        // 本地降级
        try {
            if (localChat.isInitialized()) {
                String raw = localChat.sendMessage(prompt, maxTokens, temperature, 0.9f, 40);
                return ImportValidator.repairJson(raw);
            }
        } catch (Exception e) {
            Log.w(TAG, "本地模型调用失败: " + e.getMessage());
        }
        return null;
    }

    // ======================== 私有辅助方法 ========================

    /** 从 JSON 对象填充 Question 全字段,容错(缺失给默认值) */
    private Question parseQuestionFromJson(JSONObject jo) {
        if (jo == null) return null;
        Question q = new Question();
        q.setQuestionText(jo.optString("questionText", ""));
        q.setOptionA(jo.optString("optionA", ""));
        q.setOptionB(jo.optString("optionB", ""));
        q.setOptionC(jo.optString("optionC", ""));
        q.setOptionD(jo.optString("optionD", ""));
        // extraOptions:schema 中为 object,转为字符串存储
        Object extra = jo.opt("extraOptions");
        if (extra instanceof JSONObject) {
            q.setExtraOptions(((JSONObject) extra).toString());
        } else if (extra instanceof String && !((String) extra).isEmpty()) {
            q.setExtraOptions((String) extra);
        } else {
            q.setExtraOptions("");
        }
        q.setCorrectAnswer(jo.optString("correctAnswer", ""));
        q.setQuestionType(jo.optString("questionType", ""));
        q.setCategory(jo.optString("category", ""));
        q.setSubCategory(jo.optString("subCategory", ""));
        q.setDifficulty(jo.optInt("difficulty", 0));
        q.setPoints(jo.optInt("points", 0));
        q.setTimeLimit(jo.optInt("timeLimit", 0));
        q.setHint(jo.optString("hint", ""));
        q.setExplanation(jo.optString("explanation", ""));
        q.setAnalysis(jo.optString("analysis", ""));
        q.setKnowledgePoint(jo.optString("knowledgePoint", ""));
        q.setTags(jo.optString("tags", ""));
        q.setAuthor(jo.optString("author", ""));
        q.setComment(jo.optString("comment", ""));
        return q;
    }

    /** 粗估 token 数:中文 *1.5 + 英文单词数 */
    private int estimateTokens(String s) {
        if (s == null || s.isEmpty()) return 0;
        int chinese = 0;
        int englishWords = 0;
        boolean inWord = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                chinese++;
                if (inWord) {
                    englishWords++;
                    inWord = false;
                }
            } else if (Character.isLetterOrDigit(c)) {
                inWord = true;
            } else {
                if (inWord) {
                    englishWords++;
                    inWord = false;
                }
            }
        }
        if (inWord) englishWords++;
        return Math.round(chinese * 1.5f) + englishWords;
    }

    /**
     * 分块:按题号边界(Q:/题目/第N题/数字编号)切分段落,
     * 再按 token 上限合并,不截断单题(单题超长则单独成块)。
     */
    private List<String> chunkText(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return chunks;

        // 按行扫描,遇题号边界切分段落
        String[] lines = text.split("\n");
        StringBuilder current = new StringBuilder();
        List<String> segments = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            boolean isBoundary = trimmed.startsWith("Q:")
                    || trimmed.startsWith("题目")
                    || trimmed.matches("^(\\d+)[\\.、．)]\\s.*")
                    || trimmed.matches("^第[\\d一二三四五六七八九十百]+题.*");
            if (isBoundary && current.length() > 0) {
                segments.add(current.toString());
                current = new StringBuilder();
            }
            current.append(line).append("\n");
        }
        if (current.length() > 0) segments.add(current.toString());

        // 按 token 上限合并段落
        StringBuilder chunk = new StringBuilder();
        for (String seg : segments) {
            int segTokens = estimateTokens(seg);
            if (segTokens > CHUNK_MAX_TOKENS) {
                // 单题超长,单独成块(不截断)
                if (chunk.length() > 0) {
                    chunks.add(chunk.toString());
                    chunk = new StringBuilder();
                }
                chunks.add(seg);
                continue;
            }
            if (chunk.length() > 0
                    && estimateTokens(chunk.toString()) + segTokens > CHUNK_MAX_TOKENS) {
                chunks.add(chunk.toString());
                chunk = new StringBuilder();
            }
            chunk.append(seg);
        }
        if (chunk.length() > 0) chunks.add(chunk.toString());

        // ===== 15% overlap(依据 NVIDIA 实验:15% overlap 最优,防边界截断)=====
        // 步骤 1:合并过短碎块(<200 字符与前一块合并,避免 overlap 后产生更碎的块)
        List<String> merged = new ArrayList<>();
        for (String c : chunks) {
            if (!merged.isEmpty() && c.length() < MIN_CHUNK_LENGTH) {
                int last = merged.size() - 1;
                merged.set(last, merged.get(last) + c);
            } else {
                merged.add(c);
            }
        }
        // 步骤 2:对 chunk[i](i>0)前缀拼接 chunk[i-1] 的尾部 15% 文本
        // 注意:overlap 会导致少量题重复抽取,后续 persistOneQuestion 的 questionText+category 去重会自动吸收
        List<String> result = new ArrayList<>(merged.size());
        for (int i = 0; i < merged.size(); i++) {
            String cur = merged.get(i);
            if (i > 0) {
                String prev = merged.get(i - 1);
                int overlapLen = Math.round(prev.length() * CHUNK_OVERLAP_RATIO);
                if (overlapLen > 0) {
                    int start = prev.length() - overlapLen;
                    if (start < 0) start = 0;
                    cur = prev.substring(start) + cur;
                }
            }
            result.add(cur);
        }
        return result;
    }

    /** UTF-8 读取文件;docx/pdf 尝试用 FileReaderTool(action=read) */
    @SuppressWarnings("unchecked")
    private String readFileAsText(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".docx") || name.endsWith(".pdf")) {
            try {
                FileReaderTool tool = new FileReaderTool(context);
                Map<String, Object> params = new HashMap<>();
                params.put("action", "read");
                params.put("file_path", file.getAbsolutePath());
                params.put("encoding", "UTF-8");
                AIToolResult result = tool.execute(params);
                if (result.isSuccess() && result.getResult() instanceof Map) {
                    Object content = ((Map<String, Object>) result.getResult()).get("content");
                    if (content instanceof String) {
                        return (String) content;
                    }
                }
                return null;
            } catch (Exception e) {
                Log.w(TAG, "FileReaderTool 读取失败: " + e.getMessage());
                return null;
            }
        }
        // txt/md/csv/json 及其他:UTF-8 直读
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Exception e) {
            Log.w(TAG, "读取文件失败: " + e.getMessage());
            return null;
        }
        return sb.toString();
    }

    /** 按指定编码读取文件;docx/pdf 仍走 FileReaderTool(忽略 encoding) */
    private String readFileAsTextWithEncoding(File file, String encoding) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".docx") || name.endsWith(".pdf")) {
            return readFileAsText(file);
        }
        String enc = (encoding == null || encoding.trim().isEmpty()) ? "UTF-8" : encoding;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), enc))) {
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
        } catch (Exception e) {
            Log.w(TAG, "读取文件失败: " + e.getMessage());
            return null;
        }
        return sb.toString();
    }

    /** 解析单题为 ExtractedItem:复用 parseQuestionFromJson + 并行读 confidence(默认 0.5,依据 byaiteam) */
    private ExtractedItem parseExtractedItem(JSONObject jo) {
        if (jo == null) return null;
        Question q = parseQuestionFromJson(jo);
        if (q == null) return null;
        double conf = jo.optDouble("confidence", 0.5);
        if (Double.isNaN(conf) || Double.isInfinite(conf)) conf = 0.5;
        if (conf < 0) conf = 0;
        if (conf > 1) conf = 1;
        return new ExtractedItem(q, (float) conf);
    }

    /** repair + parse + 转 ExtractedItem 列表(含 confidence,依据 byaiteam) */
    private List<ExtractedItem> extractQuestionsFromJson(String json, ImportListener listener) {
        List<ExtractedItem> list = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return list;
        String repaired = ImportValidator.repairJson(json);
        try {
            // 优先按 {"questions":[...]} 解析
            if (repaired.trim().startsWith("{")) {
                JSONObject root = new JSONObject(repaired);
                JSONArray arr = root.optJSONArray("questions");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        try {
                            JSONObject jo = arr.getJSONObject(i);
                            ExtractedItem item = parseExtractedItem(jo);
                            if (item != null) list.add(item);
                        } catch (Exception e) {
                            Log.w(TAG, "解析单题失败: " + e.getMessage());
                        }
                    }
                }
            } else if (repaired.trim().startsWith("[")) {
                // 兼容直接输出数组的情况
                JSONArray arr = new JSONArray(repaired);
                for (int i = 0; i < arr.length(); i++) {
                    try {
                        JSONObject jo = arr.getJSONObject(i);
                        ExtractedItem item = parseExtractedItem(jo);
                        if (item != null) list.add(item);
                    } catch (Exception e) {
                        Log.w(TAG, "解析单题失败: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "JSON 解析失败: " + e.getMessage());
        }
        return list;
    }

    /** 乱码启发式:统计替换符 U+FFFD 占比 >5% 视为乱码 */
    private boolean looksLikeGarbled(String text) {
        if (text == null) return true;
        int len = Math.min(text.length(), 2000);
        if (len == 0) return false;
        int repl = 0;
        for (int i = 0; i < len; i++) {
            if (text.charAt(i) == '\uFFFD') repl++;
        }
        return repl > len * 0.05;
    }

    /** 将 Question 序列化为 JSONObject,供自修复 prompt 使用 */
    private JSONObject questionToJson(Question q) throws JSONException {
        JSONObject jo = new JSONObject();
        jo.put("questionText", nullToEmpty(q.getQuestionText()));
        jo.put("optionA", nullToEmpty(q.getOptionA()));
        jo.put("optionB", nullToEmpty(q.getOptionB()));
        jo.put("optionC", nullToEmpty(q.getOptionC()));
        jo.put("optionD", nullToEmpty(q.getOptionD()));
        JSONObject extra = new JSONObject();
        if (q.getExtraOptions() != null && !q.getExtraOptions().trim().isEmpty()) {
            try {
                extra = new JSONObject(q.getExtraOptions());
            } catch (JSONException ignore) {
                // 非 JSON 对象,留空
            }
        }
        jo.put("extraOptions", extra);
        jo.put("correctAnswer", nullToEmpty(q.getCorrectAnswer()));
        jo.put("questionType", nullToEmpty(q.getQuestionType()));
        jo.put("category", nullToEmpty(q.getCategory()));
        jo.put("subCategory", nullToEmpty(q.getSubCategory()));
        jo.put("difficulty", q.getDifficulty());
        jo.put("points", q.getPoints());
        jo.put("timeLimit", q.getTimeLimit());
        jo.put("hint", nullToEmpty(q.getHint()));
        jo.put("explanation", nullToEmpty(q.getExplanation()));
        jo.put("analysis", nullToEmpty(q.getAnalysis()));
        jo.put("knowledgePoint", nullToEmpty(q.getKnowledgePoint()));
        jo.put("tags", nullToEmpty(q.getTags()));
        jo.put("author", nullToEmpty(q.getAuthor()));
        jo.put("comment", nullToEmpty(q.getComment()));
        return jo;
    }

    /** 切回主线程回调 */
    private void runOnMain(Runnable r) {
        mainHandler.post(r);
    }

    /** 从 Context 获取 Application 实例 */
    private Application asApplication(Context ctx) {
        if (ctx instanceof Application) return (Application) ctx;
        Context app = ctx.getApplicationContext();
        if (app instanceof Application) return (Application) app;
        return null;
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private boolean equalsStr(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}

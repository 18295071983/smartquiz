package com.oilquiz.app.ai.repair;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.agent.ToolResultInterpreter;
import com.oilquiz.app.ai.engine.ALChat;
import com.oilquiz.app.ai.importing.ImportValidator;
import com.oilquiz.app.ai.importing.QuestionSchemaDictionary;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.model.ModelMemoryManager;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.service.OnlineInferenceService;
import com.oilquiz.app.database.DatabaseManager;
import com.oilquiz.app.model.Question;
import com.oilquiz.app.util.GarbledTextFixer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 题库智能修复引擎。
 * <p>
 * 对已入库题目做问题扫描与修复：
 * <ul>
 *   <li><b>规则自动修复</b>：乱码（GarbledTextFixer）、答案格式规范化（"A;B"→"AB"）、
 *       缺失题型推断（对/错选项→判断题，多字母答案→多选题，单字母→单选题，无选项→填空题）；</li>
 *   <li><b>AI 修复</b>：题干为空、答案缺失、答案与选项不匹配等规则修不了的问题，
 *       交在线模型做最小编辑修复（只补缺失/损坏字段，禁止改写正常内容）；</li>
 *   <li><b>仅报告</b>：重复题（不自动删除，由用户决定）。</li>
 * </ul>
 */
public class QuestionRepairEngine {

    private static final String TAG = "QuestionRepairEngine";
    /** 本地模型修复的输出 token 上限（防小上下文模型溢出） */
    private static final int LOCAL_MAX_TOKENS = 512;
    /** 本地模型修复的 prompt 长度上限（防超出 n_ctx 触发 native 异常，signal 6 防护） */
    private static final int LOCAL_PROMPT_MAX_CHARS = 1800;
    /** 在线模型修复超时（秒） */
    private static final int ONLINE_TIMEOUT_SECONDS = 90;
    /** 在线批量修复每批题数（一批一次推理，避免逐题串行请求） */
    private static final int ONLINE_BATCH_SIZE = 5;
    /** 在线批量修复输出 token 上限（5题×约300字符输出，预留余量防截断） */
    private static final int ONLINE_BATCH_MAX_TOKENS = 3000;
    /** 在线批量修复超时（秒） */
    private static final int ONLINE_BATCH_TIMEOUT_SECONDS = 120;

    /** 批量修复进度回调 */
    public interface RepairProgressListener {
        void onProgress(int done, int total);
    }

    private final ALChat localChat = new ALChat();

    // ==================== 问题类型 ====================

    public static final String ISSUE_GARBLED = "乱码";
    public static final String ISSUE_EMPTY_QUESTION = "题干为空";
    public static final String ISSUE_MISSING_ANSWER = "答案缺失";
    public static final String ISSUE_ANSWER_FORMAT = "答案格式异常";
    public static final String ISSUE_ANSWER_MISMATCH = "答案与选项不匹配";
    public static final String ISSUE_MISSING_TYPE = "题型缺失";
    public static final String ISSUE_DUPLICATE = "重复题目";

    // ==================== 数据结构 ====================

    /** 单个问题点 */
    public static class Issue {
        public final String type;
        public final String description;

        public Issue(String type, String description) {
            this.type = type;
            this.description = description;
        }
    }

    /** 一道题的修复项 */
    public static class RepairItem {
        public final Question question;
        public final List<Issue> issues = new ArrayList<>();
        /** 是否存在可规则自动修复的问题 */
        public boolean autoFixable = false;
        /** 是否存在需 AI 修复的问题 */
        public boolean aiCandidate = false;
        /** 规则修复后的题目副本（autoFixable=true 时非空） */
        public Question fixed;

        public RepairItem(Question question) {
            this.question = question;
        }

        public String issueSummary() {
            StringBuilder sb = new StringBuilder();
            for (Issue i : issues) {
                if (sb.length() > 0) sb.append("；");
                sb.append(i.type);
            }
            return sb.toString();
        }
    }

    // ==================== 扫描 ====================

    /**
     * 扫描题目列表，返回存在问题的修复项列表。
     */
    public List<RepairItem> scan(List<Question> questions) {
        List<RepairItem> items = new ArrayList<>();
        if (questions == null) return items;
        Set<String> seenKeys = new HashSet<>();

        for (Question q : questions) {
            if (q == null) continue;
            RepairItem item = new RepairItem(q);

            // 1. 乱码检测（逐字段对比修复前后）
            List<String> garbledFields = detectGarbledFields(q);
            if (!garbledFields.isEmpty()) {
                item.issues.add(new Issue(ISSUE_GARBLED, "存在乱码字段: " + String.join(",", garbledFields)));
                item.autoFixable = true;
            }

            // 2. 题干为空
            String qt = nz(q.getQuestionText());
            if (qt.isEmpty()) {
                item.issues.add(new Issue(ISSUE_EMPTY_QUESTION, "题干内容为空"));
                item.aiCandidate = true;
            }

            // 3. 答案
            String ans = nz(q.getCorrectAnswer());
            if (ans.isEmpty()) {
                item.issues.add(new Issue(ISSUE_MISSING_ANSWER, "正确答案为空"));
                item.aiCandidate = true;
            } else {
                String normalized = Question.normalizeChoiceAnswer(ans);
                if (!normalized.equals(ans.trim()) && isPureLetterAnswer(normalized)) {
                    item.issues.add(new Issue(ISSUE_ANSWER_FORMAT,
                            "答案格式异常: \"" + ans + "\" → 可规范化为 \"" + normalized + "\""));
                    item.autoFixable = true;
                }
                // 4. 答案与选项不匹配（仅校验纯字母答案）
                if (isPureLetterAnswer(normalized)) {
                    List<Character> missing = missingOptionLetters(q, normalized);
                    if (!missing.isEmpty()) {
                        item.issues.add(new Issue(ISSUE_ANSWER_MISMATCH,
                                "答案指向的选项为空: " + missing.toString()));
                        item.aiCandidate = true;
                    }
                }
            }

            // 5. 题型缺失 → 可推断
            if (nz(q.getQuestionType()).isEmpty()) {
                String inferred = inferQuestionType(q);
                item.issues.add(new Issue(ISSUE_MISSING_TYPE,
                        inferred != null ? "题型缺失，可推断为\"" + inferred + "\"" : "题型缺失"));
                if (inferred != null) item.autoFixable = true;
            }

            // 6. 重复题（题干+分类相同，保留首条，后续仅报告）
            String key = qt + "||" + nz(q.getCategory());
            if (!qt.isEmpty() && !seenKeys.add(key)) {
                item.issues.add(new Issue(ISSUE_DUPLICATE, "与已有题目重复（不自动删除）"));
            }

            if (!item.issues.isEmpty()) {
                if (item.autoFixable) {
                    item.fixed = buildRuleFix(q);
                }
                items.add(item);
            }
        }
        Log.i(TAG, "扫描完成: 共" + questions.size() + "题, 发现问题" + items.size() + "题");
        return items;
    }

    // ==================== 规则修复 ====================

    /**
     * 构建规则修复副本：乱码修复 + 答案规范化 + 题型推断。
     */
    public Question buildRuleFix(Question q) {
        Question fixed = cloneQuestion(q);
        GarbledTextFixer.fixQuestion(fixed);
        String ans = fixed.getCorrectAnswer();
        if (ans != null && !ans.trim().isEmpty()) {
            String normalized = Question.normalizeChoiceAnswer(ans);
            if (isPureLetterAnswer(normalized)) {
                fixed.setCorrectAnswer(normalized);
            }
        }
        if (nz(fixed.getQuestionType()).isEmpty()) {
            String inferred = inferQuestionType(fixed);
            if (inferred != null) fixed.setQuestionType(inferred);
        }
        return fixed;
    }

    /**
     * 批量应用规则修复并写库。
     *
     * @return 成功写库的题目数
     */
    public int applyAutoFixes(Context context, List<RepairItem> items) {
        int applied = 0;
        DatabaseManager dm = DatabaseManager.getInstance(context);
        for (RepairItem item : items) {
            if (!item.autoFixable || item.fixed == null) continue;
            try {
                Boolean ok = dm.updateQuestion(item.fixed).get();
                if (ok != null && ok) {
                    applied++;
                    // 修复成功后同步原对象引用（列表展示用）
                    item.question.setQuestionText(item.fixed.getQuestionText());
                    item.question.setCorrectAnswer(item.fixed.getCorrectAnswer());
                    item.question.setQuestionType(item.fixed.getQuestionType());
                }
            } catch (Exception e) {
                Log.w(TAG, "规则修复写库失败 id=" + item.fixed.getId() + ": " + e.getMessage());
            }
        }
        Log.i(TAG, "规则修复完成: " + applied + " 题");
        return applied;
    }

    // ==================== AI 修复 ====================

    /** 是否有可用模型做 AI 修复（本地或在线） */
    public boolean isAiRepairAvailable(Context context) {
        return isLocalModelReady(context) || isOnlineModelReady(context);
    }

    /** 本地模型是否就绪 */
    private boolean isLocalModelReady(Context context) {
        try {
            if (!LlamaHelper.isModelInitialized()) return false;
            ModelMemoryManager.MemoryState st =
                    ModelMemoryManager.getInstance(context).getMemoryState();
            return st != ModelMemoryManager.MemoryState.OUT_OF_MEMORY;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 在线模型是否就绪 */
    private boolean isOnlineModelReady(Context context) {
        try {
            return OnlineModelManager.getInstance(context).getActiveModel() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * AI 批量修复（同步方法，须在后台线程调用）：修复并写库，返回 [fixed, failed]。
     * <ul>
     *   <li>在线模型就绪：<b>每批最多 {@value #ONLINE_BATCH_SIZE} 题合并为一次推理</b>（单次请求、
     *       不重试、不降级、不兜底），速度比逐题快一个量级；批次失败整批记失败，不回退；</li>
     *   <li>仅本地模型就绪：逐题走本地（带 signal 6 防护）。</li>
     * </ul>
     */
    public int[] aiRepairAll(Context context, List<RepairItem> candidates,
                             RepairProgressListener listener) {
        int fixed = 0;
        int failed = 0;
        int total = candidates != null ? candidates.size() : 0;
        if (total == 0) return new int[]{0, 0};

        boolean onlineReady = isOnlineModelReady(context);
        int done = 0;
        int pos = 0;
        while (pos < total) {
            if (onlineReady) {
                // 在线批量：一批一次推理
                int end = Math.min(pos + ONLINE_BATCH_SIZE, total);
                List<RepairItem> batch = candidates.subList(pos, end);
                List<Question> repaired = callOnlineRepairBatch(context, batch);
                for (int i = 0; i < batch.size(); i++) {
                    Question r = (repaired != null && i < repaired.size()) ? repaired.get(i) : null;
                    if (r != null && persistRepair(context, batch.get(i), r)) {
                        fixed++;
                    } else {
                        failed++;
                    }
                    done++;
                    if (listener != null) listener.onProgress(done, total);
                }
                pos = end;
            } else {
                // 仅本地模型：逐题修复
                RepairItem item = candidates.get(pos);
                Question r = aiRepairOne(context, item.question);
                if (r != null && persistRepair(context, item, r)) {
                    fixed++;
                } else {
                    failed++;
                }
                done++;
                if (listener != null) listener.onProgress(done, total);
                pos++;
            }
        }
        Log.i(TAG, "AI批量修复完成: 成功" + fixed + "/失败" + failed + "(在线批量=" + onlineReady + ")");
        return new int[]{fixed, failed};
    }

    /** 修复结果写库，成功后同步原对象字段 */
    private boolean persistRepair(Context context, RepairItem item, Question repaired) {
        try {
            Boolean ok = DatabaseManager.getInstance(context).updateQuestion(repaired).get();
            if (ok != null && ok) {
                item.fixed = repaired;
                item.aiCandidate = false;
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "修复写库失败 id=" + item.question.getId() + ": " + e.getMessage());
        }
        return false;
    }

    /**
     * 在线批量修复：<b>单次推理</b>处理整批（generateAsync 纯单次请求，
     * 不走 json_schema 结构化降级重试路径），失败不重试不兜底。
     */
    private List<Question> callOnlineRepairBatch(Context context, List<RepairItem> batch) {
        try {
            OnlineModelManager.OnlineModelConfig config =
                    OnlineModelManager.getInstance(context).getActiveModel();
            if (config == null || batch == null || batch.isEmpty()) return null;

            String prompt = buildBatchRepairPrompt(batch);
            if (prompt == null) return null;

            // 纯单次请求：无工具调用、无降级重试、关闭 thinking 防空输出
            Log.i(TAG, "在线批量修复发起: 模型=" + config.modelName + ", 批次题数=" + batch.size());
            long t0 = System.currentTimeMillis();
            String raw = OnlineInferenceService.getInstance(context)
                    .generateOnceAsync(prompt, config, ONLINE_BATCH_MAX_TOKENS)
                    .get(ONLINE_BATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Log.i(TAG, "在线批量修复返回: 耗时" + (System.currentTimeMillis() - t0)
                    + "ms, 输出长度=" + (raw != null ? raw.length() : 0));
            if (raw == null || raw.trim().isEmpty()) return null;

            JSONArray out = parseJsonArray(raw);
            if (out == null && batch.size() == 1) {
                // 单题批次时模型可能直接返回单对象而非数组
                JSONObject single = ImportValidator.parseStructuredOutput(raw);
                if (single != null) {
                    out = new JSONArray().put(single);
                }
            }
            if (out == null) {
                Log.w(TAG, "在线批量修复输出无法解析为JSON数组, batch=" + batch.size()
                        + ", 原始输出预览=" + raw.substring(0, Math.min(300, raw.length())));
                return null;
            }

            List<Question> results = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) results.add(null);
            for (int i = 0; i < out.length(); i++) {
                JSONObject jo = out.optJSONObject(i);
                if (jo == null) continue;
                int idx = jo.optInt("idx", i);
                if (idx < 0 || idx >= batch.size()) idx = Math.min(i, batch.size() - 1);
                results.set(idx, mergeRepair(batch.get(idx).question, jo, "在线模型"));
            }
            return results;
        } catch (Throwable t) {
            Log.w(TAG, "在线批量修复异常: " + t.getMessage());
            return null;
        }
    }

    /** 构建批量修复 prompt（一批题合并为 JSON 数组，一次推理） */
    private String buildBatchRepairPrompt(List<RepairItem> batch) {
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < batch.size(); i++) {
                Question q = batch.get(i).question;
                JSONObject o = new JSONObject();
                o.put("idx", i);
                o.put("questionText", nz(q.getQuestionText()));
                o.put("optionA", nz(q.getOptionA()));
                o.put("optionB", nz(q.getOptionB()));
                o.put("optionC", nz(q.getOptionC()));
                o.put("optionD", nz(q.getOptionD()));
                o.put("correctAnswer", nz(q.getCorrectAnswer()));
                o.put("questionType", nz(q.getQuestionType()));
                arr.put(o);
            }
            return "你是题库数据修复员。下面" + batch.size() + "道题在导入时出现了数据损坏，请逐题做【最小编辑修复】：\n"
                    + arr.toString() + "\n\n"
                    + "修复要求：\n"
                    + "1. 只修复缺失或明显损坏的字段，其余字段必须逐字保持不变\n"
                    + "2. 禁止凭空编造题干、选项或答案；信息不足以可靠修复时，对应字段输出空字符串\n"
                    + "3. correctAnswer 若是字母答案，必须指向非空选项\n"
                    + "4. 禁止输出任何思考过程、分析或解释文字\n\n"
                    + "直接输出一个JSON数组（不要用代码块包裹），元素与输入按idx一一对应，每个元素含："
                    + "{\"idx\":0,\"questionText\":\"\",\"optionA\":\"\",\"optionB\":\"\",\"optionC\":\"\","
                    + "\"optionD\":\"\",\"correctAnswer\":\"\",\"questionType\":\"\"}";
        } catch (Exception e) {
            return null;
        }
    }

    /** 从模型输出中提取 JSON 数组（剥离代码块包裹与前后缀文字） */
    private JSONArray parseJsonArray(String raw) {
        try {
            String s = raw.trim();
            // 剥离 ```json ... ``` 包裹
            int fenceStart = s.indexOf("```");
            if (fenceStart >= 0) {
                int contentStart = s.indexOf('\n', fenceStart);
                int fenceEnd = s.indexOf("```", fenceStart + 3);
                if (contentStart >= 0 && fenceEnd > contentStart) {
                    s = s.substring(contentStart + 1, fenceEnd);
                }
            }
            int start = s.indexOf('[');
            int end = s.lastIndexOf(']');
            if (start >= 0 && end > start) {
                return new JSONArray(s.substring(start, end + 1));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 将 AI 输出的单题 JSON 最小编辑合并到原题，修复无效返回 null */
    private Question mergeRepair(Question q, JSONObject jo, String via) {
        try {
            Question repaired = cloneQuestion(q);
            // 题干：AI 结果非空才采用（题干是题目的根，修不了就放弃）
            String newQt = jo.optString("questionText", "").trim();
            if (!newQt.isEmpty()) {
                repaired.setQuestionText(GarbledTextFixer.fixText(newQt));
            }
            if (nz(repaired.getCorrectAnswer()).isEmpty()) {
                String a = jo.optString("correctAnswer", "").trim();
                if (!a.isEmpty()) repaired.setCorrectAnswer(Question.normalizeChoiceAnswer(a));
            }
            // 选项：仅补空选项，不覆盖已有内容
            fillEmptyOption(repaired, "A", jo.optString("optionA", ""));
            fillEmptyOption(repaired, "B", jo.optString("optionB", ""));
            fillEmptyOption(repaired, "C", jo.optString("optionC", ""));
            fillEmptyOption(repaired, "D", jo.optString("optionD", ""));
            if (nz(repaired.getQuestionType()).isEmpty()) {
                String t = jo.optString("questionType", "").trim();
                if (!t.isEmpty()) repaired.setQuestionType(t);
            }
            // 修复有效性：题干与答案必须齐全
            if (nz(repaired.getQuestionText()).isEmpty() || nz(repaired.getCorrectAnswer()).isEmpty()) {
                Log.w(TAG, "AI修复无效（题干或答案仍缺失，" + via + "）id=" + q.getId());
                return null;
            }
            return repaired;
        } catch (Throwable t) {
            Log.w(TAG, "mergeRepair异常 id=" + q.getId() + ": " + t.getMessage());
            return null;
        }
    }

    /**
     * AI 单题修复（同步方法，须在后台线程调用）。
     * 模型顺序：<b>本地模型优先，在线模型排在其后</b>（本地不可用/失败才用在线）；
     * 在线模型独立调用，失败不回退本地。
     * 原则：只修复缺失/损坏字段，逐字保留正常内容；修不了时返回 null。
     */
    public Question aiRepairOne(Context context, Question q) {
        try {
            String prompt = buildRepairPrompt(q);
            if (prompt == null) return null;

            // 1. 本地模型优先（带 signal 6 防护：初始化/内存/prompt长度/异常全捕获）
            String raw = callLocalRepair(context, prompt);
            String via = raw != null ? "本地模型" : null;

            // 2. 本地不可用或失败 → 在线模型（在线失败不回退）
            if (raw == null) {
                raw = callOnlineRepair(context, prompt);
                via = raw != null ? "在线模型" : null;
            }
            if (raw == null) {
                Log.w(TAG, "AI修复无可用输出 id=" + q.getId());
                return null;
            }

            JSONObject jo = ImportValidator.parseStructuredOutput(raw);
            if (jo == null) {
                Log.w(TAG, "AI修复输出无法解析为JSON(" + via + ") id=" + q.getId());
                return null;
            }
            Question repaired = mergeRepair(q, jo, via);
            if (repaired != null) {
                Log.i(TAG, "AI修复成功(" + via + ") id=" + q.getId());
            }
            return repaired;
        } catch (Throwable e) {
            Log.w(TAG, "AI修复异常 id=" + (q != null ? q.getId() : -1) + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * 本地模型修复调用（signal 6 防护）：
     * 未初始化/内存不足/prompt 超长直接返回 null 不进入 native；
     * native 层已有信号安全包裹，崩溃会转为 "Error: ..." 返回值，这里统一判失败。
     */
    private String callLocalRepair(Context context, String prompt) {
        try {
            if (!LlamaHelper.isModelInitialized()) {
                return null;
            }
            ModelMemoryManager.MemoryState st =
                    ModelMemoryManager.getInstance(context).getMemoryState();
            if (st == ModelMemoryManager.MemoryState.OUT_OF_MEMORY) {
                Log.w(TAG, "本地模型内存不足，跳过本地修复");
                return null;
            }
            if (st == ModelMemoryManager.MemoryState.CRITICAL) {
                System.gc();
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }
            if (prompt.length() > LOCAL_PROMPT_MAX_CHARS) {
                Log.w(TAG, "修复prompt超长(" + prompt.length() + ")，跳过本地模型防上下文溢出");
                return null;
            }
            String raw = localChat.sendMessage(prompt, LOCAL_MAX_TOKENS, 0.1f, 0.9f, 40);
            if (raw == null || raw.trim().isEmpty()) return null;
            if (raw.startsWith("Error:")) {
                Log.w(TAG, "本地模型修复返回错误: " + raw);
                return null;
            }
            String cleaned = ToolResultInterpreter.cleanModelOutput(raw);
            return cleaned != null ? cleaned : null;
        } catch (Throwable t) {
            Log.w(TAG, "本地模型修复异常: " + t.getMessage());
            return null;
        }
    }

    /**
     * 在线模型修复调用：异常全捕获不抛出，带超时保护；失败不回退本地。
     */
    private String callOnlineRepair(Context context, String prompt) {
        try {
            OnlineModelManager.OnlineModelConfig config =
                    OnlineModelManager.getInstance(context).getActiveModel();
            if (config == null) return null;
            String raw = OnlineInferenceService.getInstance(context)
                    .generateStructuredAsync(prompt, config,
                            QuestionSchemaDictionary.getMinimalExtractionSchema(), 800)
                    .get(ONLINE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (raw == null || raw.trim().isEmpty()) return null;
            String cleaned = ToolResultInterpreter.cleanModelOutput(raw);
            return cleaned != null ? cleaned : raw;
        } catch (Throwable t) {
            Log.w(TAG, "在线模型修复异常: " + t.getMessage());
            return null;
        }
    }

    /** 构建修复 prompt（单题 JSON + 最小编辑修复指令） */
    private String buildRepairPrompt(Question q) {
        try {
            JSONObject input = new JSONObject();
            input.put("questionText", nz(q.getQuestionText()));
            input.put("optionA", nz(q.getOptionA()));
            input.put("optionB", nz(q.getOptionB()));
            input.put("optionC", nz(q.getOptionC()));
            input.put("optionD", nz(q.getOptionD()));
            input.put("correctAnswer", nz(q.getCorrectAnswer()));
            input.put("questionType", nz(q.getQuestionType()));

            return "你是题库数据修复员。下面这道题在导入时出现了数据损坏，请做【最小编辑修复】：\n"
                    + input.toString() + "\n\n"
                    + "修复要求：\n"
                    + "1. 只修复缺失或明显损坏的字段，其余字段必须逐字保持不变\n"
                    + "2. 禁止凭空编造题干、选项或答案；信息不足以可靠修复时，对应字段输出空字符串\n"
                    + "3. correctAnswer 若是字母答案，必须指向非空选项\n\n"
                    + "只输出JSON：{\"questionText\":\"\",\"optionA\":\"\",\"optionB\":\"\",\"optionC\":\"\","
                    + "\"optionD\":\"\",\"correctAnswer\":\"\",\"questionType\":\"\"}";
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 内部辅助 ====================

    private static final String[] GARBLED_CHECK_FIELDS = {
            "questionText", "optionA", "optionB", "optionC", "optionD",
            "optionE", "optionF", "optionG", "optionH",
            "correctAnswer", "explanation", "category"
    };

    /** 检测哪些字段存在乱码（对比 GarbledTextFixer 修复前后） */
    private List<String> detectGarbledFields(Question q) {
        List<String> result = new ArrayList<>();
        for (String f : GARBLED_CHECK_FIELDS) {
            String orig = getField(q, f);
            if (orig == null || orig.trim().isEmpty()) continue;
            String fixed = GarbledTextFixer.fixText(orig);
            if (!fixed.equals(orig.trim()) && !fixed.equals(orig)) {
                result.add(f);
            }
        }
        return result;
    }

    /** 推断题型：对/错选项→判断题，多字母答案→多选题，有选项→单选题，否则填空题 */
    private String inferQuestionType(Question q) {
        String a = nz(q.getOptionA());
        String b = nz(q.getOptionB());
        boolean judge = (a.equals("对") || a.equals("正确") || a.equals("√"))
                && (b.equals("错") || b.equals("错误") || b.equals("×"));
        boolean judge2 = (a.equals("错") || a.equals("错误") || a.equals("×"))
                && (b.equals("对") || b.equals("正确") || b.equals("√"));
        if (judge || judge2) return "判断题";

        String ans = Question.normalizeChoiceAnswer(nz(q.getCorrectAnswer()));
        if (isPureLetterAnswer(ans) && ans.length() > 1) return "多选题";
        if (!a.isEmpty() || !b.isEmpty() || !nz(q.getOptionC()).isEmpty() || !nz(q.getOptionD()).isEmpty()) {
            return "单选题";
        }
        if (!nz(q.getQuestionText()).isEmpty()) return "填空题";
        return null;
    }

    /** 纯字母答案（A~L 组成） */
    private boolean isPureLetterAnswer(String ans) {
        if (ans == null || ans.isEmpty()) return false;
        for (char c : ans.toCharArray()) {
            if (c < 'A' || c > 'L') return false;
        }
        return true;
    }

    /** 答案字母指向的选项中为空的字母列表 */
    private List<Character> missingOptionLetters(Question q, String normalizedAnswer) {
        List<Character> missing = new ArrayList<>();
        for (char c : normalizedAnswer.toCharArray()) {
            String opt = getOptionByLetter(q, c);
            if (opt == null || opt.trim().isEmpty()) {
                missing.add(c);
            }
        }
        return missing;
    }

    private String getOptionByLetter(Question q, char letter) {
        switch (letter) {
            case 'A': return q.getOptionA();
            case 'B': return q.getOptionB();
            case 'C': return q.getOptionC();
            case 'D': return q.getOptionD();
            case 'E': return getField(q, "optionE");
            case 'F': return getField(q, "optionF");
            case 'G': return getField(q, "optionG");
            case 'H': return getField(q, "optionH");
            case 'I': return getField(q, "optionI");
            case 'J': return getField(q, "optionJ");
            case 'K': return getField(q, "optionK");
            case 'L': return getField(q, "optionL");
            default: return null;
        }
    }

    private void fillEmptyOption(Question q, String letter, String value) {
        String v = value == null ? "" : GarbledTextFixer.fixText(value.trim());
        if (v.isEmpty()) return;
        String field = "option" + letter;
        if (nz(getField(q, field)).isEmpty()) {
            setField(q, field, v);
        }
    }

    /** 克隆题目（反射逐字段复制，避免污染原对象） */
    private Question cloneQuestion(Question src) {
        Question copy = new Question();
        copy.setId(src.getId());
        for (Field f : Question.class.getDeclaredFields()) {
            try {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                f.set(copy, f.get(src));
            } catch (Exception ignored) {
            }
        }
        return copy;
    }

    private String getField(Question q, String name) {
        try {
            Field f = Question.class.getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(q);
            return v == null ? "" : v.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void setField(Question q, String name, String value) {
        try {
            Field f = Question.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(q, value);
        } catch (Exception ignored) {
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}

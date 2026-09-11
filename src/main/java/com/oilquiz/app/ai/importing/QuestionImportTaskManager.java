package com.oilquiz.app.ai.importing;

import com.oilquiz.app.ai.importing.v2.ImportMain;
import com.oilquiz.app.ai.importing.v2.ImportPythonBridge;
import com.oilquiz.app.ai.spi.AppServices;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 题库导入任务管理器 —— 智能体工具（import_start/import_status/import_cancel）的后端。
 * <p>
 * 进程级单例，持有多个并发的异步 v2 导入任务；任务由 {@link ImportMain}
 * 内部线程池执行（不阻塞工具调用线程），工具侧通过 taskId 轮询状态。
 * <p>
 * 状态字段线程安全（volatile + ConcurrentHashMap），回调在主线程更新。
 */
public class QuestionImportTaskManager {

    private static volatile QuestionImportTaskManager instance;

    /** 任务状态快照（供 import_status 返回） */
    public static class TaskStatus {
        public volatile boolean running;
        public volatile boolean done;
        public volatile String error;
        public volatile String stage = "";
        public volatile String message = "";
        public volatile long current = 0;
        public volatile long total = 0;
        public volatile long imported = 0;
        public volatile long duplicated = 0;
        public volatile long failed = 0;
        public volatile long totalRows = 0;
        /** 最近日志（最新一条） */
        public volatile String lastLog = "";
    }

    private final ConcurrentHashMap<String, TaskStatus> tasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ImportMain> activeMains = new ConcurrentHashMap<>();

    public static QuestionImportTaskManager getInstance() {
        if (instance == null) {
            synchronized (QuestionImportTaskManager.class) {
                if (instance == null) {
                    instance = new QuestionImportTaskManager();
                }
            }
        }
        return instance;
    }

    /** 启动异步导入，返回 taskId */
    public String start(android.content.Context context, File file, int sheetIndex, String docHint,
                        boolean fillMissing, boolean skipIncomplete, String questionType) {
        return start(context, file, sheetIndex, docHint, fillMissing, skipIncomplete, questionType, null);
    }

    /**
     * 启动异步导入，返回 taskId。
     *
     * @param sheetMode 工作表选择模式：null/"all"=全扫全部表（sheetIndex 无效）；
     *                  "best"=AI 自动选字段匹配最多的最佳表（复刻人工"智能选择"流程）；
     *                  "index"=按 sheetIndex 指定表。sheetMode 为 "best"/"index" 时 sheetIndex 生效。
     */
    public String start(android.content.Context context, File file, int sheetIndex, String docHint,
                        boolean fillMissing, boolean skipIncomplete, String questionType,
                        String sheetMode) {
        return start(context, file, sheetIndex, docHint, fillMissing, skipIncomplete, questionType,
                sheetMode, false);
    }

    /**
     * 启动异步导入，返回 taskId。
     *
     * @param interactive true=四个关键决策点（字段映射/数据预览/智能填充/入库）弹窗与用户确认后继续；
     *                    false=自动放行（无人值守）
     */
    public String start(android.content.Context context, File file, int sheetIndex, String docHint,
                        boolean fillMissing, boolean skipIncomplete, String questionType,
                        String sheetMode, boolean interactive) {
        final String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        final TaskStatus status = new TaskStatus();
        status.running = true;
        status.stage = "start";
        tasks.put(taskId, status);

        if (context == null) {
            // 兜底：SPI 注册表已安装时取 applicationContext
            context = AppServices.appContext();
        }
        if (context == null) {
            status.running = false;
            status.done = true;
            status.error = "无法获取应用上下文（工具未注入 Context 且 SPI 未安装）";
            status.stage = "error";
            return taskId;
        }
        final android.content.Context appContext = context.getApplicationContext();
        AppServices.ensure(appContext);

        // sheetMode=best：AI 自动选字段匹配最多的最佳表（枚举 → 各表采样 → 关键词打分）
        int finalSheetIndex = sheetIndex;
        if (sheetMode != null && "best".equalsIgnoreCase(sheetMode)) {
            int best = pickBestSheet(appContext, file);
            if (best >= 0) {
                finalSheetIndex = best;
                status.message = "已自动选择工作表 #" + best + "（字段匹配最多）";
            } else {
                status.message = "未检测到标准题库表头，回退全表扫描";
            }
        }
        if ("all".equalsIgnoreCase(sheetMode)) {
            finalSheetIndex = -1;
        }

        ImportMain v2Main = new ImportMain(appContext, new AIImportOrchestrator(appContext));
        if (docHint != null && !docHint.isEmpty()) {
            v2Main.setDocHint(docHint);
        }
        v2Main.setFillEnabled(fillMissing);
        if (finalSheetIndex >= 0) {
            v2Main.setExcelSheetIndex(finalSheetIndex);
        }
        if (questionType != null && !questionType.isEmpty()) {
            v2Main.setDefaultQuestionType(questionType);
        }
        v2Main.setInteractionHandler(interactive
                ? new InteractiveImportDecisionHandler()
                : new AutoImportDecisionHandler(fillMissing, skipIncomplete));
        activeMains.put(taskId, v2Main);

        v2Main.run(file, new ImportMain.ImportListener() {
            @Override
            public void onStage(String stage, String message) {
                status.stage = stage;
                status.message = message;
            }

            @Override
            public void onLog(String message) {
                status.lastLog = message;
            }

            @Override
            public void onProgress(long current, long total, String detail) {
                status.current = current;
                status.total = total;
            }

            @Override
            public void onComplete(ImportMain.ImportSummary result) {
                status.running = false;
                status.done = true;
                status.stage = "done";
                if (result != null) {
                    status.imported = result.imported;
                    status.duplicated = result.duplicated;
                    status.failed = result.failed;
                    status.totalRows = result.totalRows;
                }
                activeMains.remove(taskId);
            }

            @Override
            public void onError(String message) {
                status.running = false;
                status.done = true;
                status.error = message;
                status.stage = "error";
                activeMains.remove(taskId);
            }

            @Override
            public void onCancelled(String reason) {
                status.running = false;
                status.done = true;
                status.error = reason;
                status.stage = "cancelled";
                activeMains.remove(taskId);
            }
        });
        return taskId;
    }

    /**
     * 多工作表导入：对选定工作表逐个执行完整导入流程（每表独立采样/映射/解析/入库），
     * 全部完成后一次汇总。智能体判断多个工作表都有用时走此入口（sheetMode=multi）。
     */
    public String startMulti(android.content.Context context, File file, List<Integer> sheetIndexes,
                             String docHint, boolean fillMissing, boolean skipIncomplete,
                             String questionType) {
        return startMulti(context, file, sheetIndexes, docHint, fillMissing, skipIncomplete,
                questionType, false);
    }

    /**
     * 多工作表导入（交互版）：interactive=true 时四个关键决策点弹窗与用户确认后继续。
     */
    public String startMulti(android.content.Context context, File file, List<Integer> sheetIndexes,
                             String docHint, boolean fillMissing, boolean skipIncomplete,
                             String questionType, boolean interactive) {
        final String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        final TaskStatus status = new TaskStatus();
        status.running = true;
        status.stage = "start";
        tasks.put(taskId, status);

        if (context == null) {
            context = AppServices.appContext();
        }
        if (context == null) {
            status.running = false;
            status.done = true;
            status.error = "无法获取应用上下文（工具未注入 Context 且 SPI 未安装）";
            status.stage = "error";
            return taskId;
        }
        if (sheetIndexes == null || sheetIndexes.isEmpty()) {
            status.running = false;
            status.done = true;
            status.error = "未选择任何工作表（sheetMode=multi 需传 sheetIndexes）";
            status.stage = "error";
            return taskId;
        }
        final android.content.Context appContext = context.getApplicationContext();
        AppServices.ensure(appContext);

        ImportMain v2Main = new ImportMain(appContext, new AIImportOrchestrator(appContext));
        if (docHint != null && !docHint.isEmpty()) {
            v2Main.setDocHint(docHint);
        }
        v2Main.setFillEnabled(fillMissing);
        if (questionType != null && !questionType.isEmpty()) {
            v2Main.setDefaultQuestionType(questionType);
        }
        v2Main.setInteractionHandler(interactive
                ? new InteractiveImportDecisionHandler()
                : new AutoImportDecisionHandler(fillMissing, skipIncomplete));
        activeMains.put(taskId, v2Main);

        v2Main.runSheets(file, sheetIndexes, null, new ImportMain.ImportListener() {
            @Override
            public void onStage(String stage, String message) {
                status.stage = stage;
                status.message = message;
            }

            @Override
            public void onLog(String message) {
                status.lastLog = message;
            }

            @Override
            public void onProgress(long current, long total, String detail) {
                status.current = current;
                status.total = total;
            }

            @Override
            public void onComplete(ImportMain.ImportSummary result) {
                status.running = false;
                status.done = true;
                status.stage = "done";
                if (result != null) {
                    status.imported = result.imported;
                    status.duplicated = result.duplicated;
                    status.failed = result.failed;
                    status.totalRows = result.totalRows;
                }
                activeMains.remove(taskId);
            }

            @Override
            public void onError(String message) {
                status.running = false;
                status.done = true;
                status.error = message;
                status.stage = "error";
                activeMains.remove(taskId);
            }

            @Override
            public void onCancelled(String reason) {
                status.running = false;
                status.done = true;
                status.error = reason;
                status.stage = "cancelled";
                activeMains.remove(taskId);
            }
        });
        return taskId;
    }

    /** 查询任务状态（JSON 文本，供工具直接返回） */
    public String status(String taskId) {
        TaskStatus s = tasks.get(taskId);
        if (s == null) {
            return "{\"taskId\":\"" + taskId + "\",\"status\":\"NOT_FOUND\",\"message\":\"任务不存在（可能已完成清理）\"}";
        }
        try {
            JSONObject o = new JSONObject();
            o.put("taskId", taskId);
            o.put("status", s.error != null ? "ERROR" : s.done ? "DONE" : "RUNNING");
            o.put("stage", s.stage == null ? "" : s.stage);
            o.put("message", s.message == null ? "" : s.message);
            o.put("current", s.current);
            o.put("total", s.total);
            o.put("imported", s.imported);
            o.put("duplicated", s.duplicated);
            o.put("failed", s.failed);
            o.put("totalRows", s.totalRows);
            if (s.error != null) {
                o.put("error", s.error);
            }
            return o.toString();
        } catch (Exception e) {
            return "{\"taskId\":\"" + taskId + "\",\"status\":\"ERROR\",\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /** 取消任务 */
    public String cancel(String taskId) {
        TaskStatus s = tasks.get(taskId);
        if (s == null) {
            return "{\"taskId\":\"" + taskId + "\",\"status\":\"NOT_FOUND\"}";
        }
        ImportMain main = activeMains.get(taskId);
        if (main != null) {
            main.cancel();
        }
        s.running = false;
        s.done = true;
        s.error = "用户/智能体已取消";
        s.stage = "cancelled";
        activeMains.remove(taskId);
        return "{\"taskId\":\"" + taskId + "\",\"status\":\"CANCELLED\"}";
    }

    // ==================== AI 自动选最佳工作表 ====================

    /** 题库表特征关键词（按字段类别分组）：
     * 判断"是否符合题库表特征"用硬校验——表头必须【同时】命中题干类与答案类字段，
     * 仅命中单类（如只有"题目"无"答案"）或纯数据表不视为题库表。
     * 覆盖常见列名变体；未覆盖的由智能体按语义判断（prompt 已引导）。 */
    private static final String[] STEM_KEYWORDS = {
            "题干", "题目", "问题", "question", "题目内容", "题干内容", "内容", "question_stem", "stem",
            "试题", "试题内容", "题目描述", "题干描述", "quiz", "题目文本", "题干文本", "question_text"
    };
    private static final String[] ANSWER_KEYWORDS = {
            "答案", "answer", "正确答案", "参考答案", "正确", "answer_key", "正确答案内容",
            "标准答案", "答案内容", "正确答案选项", "answer_text", "key", "correct", "答案项"
    };
    private static final String[] OPTION_KEYWORDS = {
            "选项", "option", "答案选项", "备选", "choice", "options"
    };
    private static final String[] EXPLAIN_KEYWORDS = {
            "解析", "explanation", "详解", "分析", "解答", "analysis"
    };
    private static final String[] TYPE_KEYWORDS = {
            "题型", "type", "题目类型", "question_type"
    };
    private static final String[] EXTRA_KEYWORDS = {
            "难度", "difficulty", "等级", "分类", "category", "章节", "知识点", "标签"
    };

    /**
     * 枚举 Excel 工作表并对各表表头做关键词打分，返回字段匹配最多的表索引；
     * 单表 / 非 Excel / 全部无命中时返回 -1（回退 v2 全表扫描）。
     */
    private int pickBestSheet(android.content.Context ctx, File file) {
        try {
            ImportPythonBridge python = ImportPythonBridge.getInstance(ctx);
            JSONObject list = python.listSheets(file.getAbsolutePath());
            org.json.JSONArray sheets = list != null ? list.optJSONArray("sheets") : null;
            if (sheets == null || sheets.length() <= 1) {
                return -1; // 无表或单表：直接全扫
            }
            int bestIndex = -1;
            int bestScore = 0;
            for (int i = 0; i < sheets.length(); i++) {
                org.json.JSONObject sh = sheets.getJSONObject(i);
                int idx = sh.optInt("index", i);
                org.json.JSONObject sample = python.sampleFile(file.getAbsolutePath(), 15, idx);
                if (sample == null || sample.has("error")) continue;
                org.json.JSONArray headers = sample.optJSONArray("headers");
                // 硬校验：不符合题库表特征（题干+答案未同时出现）的表直接排除，
                // 避免示例/说明/纯数据表被误判为题库表
                if (!isQuestionBankSheet(headers)) {
                    continue;
                }
                int score = scoreHeaders(headers);
                // 行数权重：真正的题库表有大量数据行；示例/说明/目录表通常只有几行
                org.json.JSONArray rows = sample.optJSONArray("rows");
                int dataRows = rows != null ? rows.length() : 0;
                if (dataRows <= 3) {
                    score -= 2;
                } else if (dataRows <= 8) {
                    score += 1;
                } else {
                    score += 3;
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestIndex = idx;
                }
            }
            return bestScore > 0 ? bestIndex : -1;
        } catch (Exception e) {
            android.util.Log.w("ImportTaskManager", "自动选表失败，回退全表扫描: " + e.getMessage());
            return -1;
        }
    }

    /** 是否符合题库表特征：表头必须【同时】命中题干类与答案类字段（硬校验）。 */
    private boolean isQuestionBankSheet(org.json.JSONArray headers) {
        if (headers == null) return false;
        boolean hasStem = false;
        boolean hasAnswer = false;
        for (int i = 0; i < headers.length(); i++) {
            String h = headers.optString(i, "").toLowerCase(java.util.Locale.ROOT);
            if (h.isEmpty()) continue;
            if (!hasStem && containsAny(h, STEM_KEYWORDS)) hasStem = true;
            if (!hasAnswer && containsAny(h, ANSWER_KEYWORDS)) hasAnswer = true;
            if (hasStem && hasAnswer) return true;
        }
        return hasStem && hasAnswer;
    }

    private boolean containsAny(String h, String[] kws) {
        for (String kw : kws) {
            if (h.contains(kw.toLowerCase(java.util.Locale.ROOT))) return true;
        }
        return false;
    }

    /** 表头题库字段命中计数（小写匹配；前提已通过 isQuestionBankSheet 硬校验） */
    private int scoreHeaders(org.json.JSONArray headers) {
        if (headers == null) return 0;
        int score = 0;
        for (int i = 0; i < headers.length(); i++) {
            String h = headers.optString(i, "").toLowerCase(java.util.Locale.ROOT);
            if (h.isEmpty()) continue;
            if (containsAny(h, STEM_KEYWORDS)
                    || containsAny(h, ANSWER_KEYWORDS)
                    || containsAny(h, OPTION_KEYWORDS)
                    || containsAny(h, EXPLAIN_KEYWORDS)
                    || containsAny(h, TYPE_KEYWORDS)
                    || containsAny(h, EXTRA_KEYWORDS)) {
                score++;
            }
        }
        return score;
    }
}

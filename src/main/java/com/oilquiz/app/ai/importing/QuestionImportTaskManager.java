package com.oilquiz.app.ai.importing;

import com.oilquiz.app.ai.importing.v2.ImportMain;
import com.oilquiz.app.ai.importing.v2.ImportPythonBridge;
import com.oilquiz.app.ai.spi.AppServices;

import org.json.JSONObject;

import java.io.File;
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
        v2Main.setInteractionHandler(new AutoImportDecisionHandler(fillMissing, skipIncomplete));
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

    /** 题库表头关键词（用于工作表打分；命中越多的表越像题库表） */
    private static final String[] HEADER_KEYWORDS = {
            "题干", "题目", "问题", "question", "题目内容", "内容", "题干内容",
            "选项", "option", "答案选项", "备选",
            "答案", "answer", "正确答案", "正确", "参考答案",
            "解析", "explanation", "详解", "分析", "解答",
            "题型", "type", "题目类型",
            "难度", "difficulty", "等级",
            "分类", "category", "章节", "知识点", "标签"
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
                int score = scoreHeaders(headers);
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

    /** 表头关键词命中计数（小写匹配） */
    private int scoreHeaders(org.json.JSONArray headers) {
        if (headers == null) return 0;
        int score = 0;
        for (int i = 0; i < headers.length(); i++) {
            String h = headers.optString(i, "").toLowerCase(java.util.Locale.ROOT);
            if (h.isEmpty()) continue;
            for (String kw : HEADER_KEYWORDS) {
                if (h.contains(kw.toLowerCase(java.util.Locale.ROOT))) {
                    score++;
                    break; // 一个表头只记一次
                }
            }
        }
        return score;
    }
}

package com.oilquiz.app.ai.importing;

import com.oilquiz.app.ai.importing.v2.ImportMain;
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
    public String start(File file, int sheetIndex, String docHint,
                        boolean fillMissing, boolean skipIncomplete, String questionType) {
        final String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        final TaskStatus status = new TaskStatus();
        status.running = true;
        status.stage = "start";
        tasks.put(taskId, status);

        ImportMain v2Main = new ImportMain(AppServices.appContext(), new AIImportOrchestrator(AppServices.appContext()));
        if (docHint != null && !docHint.isEmpty()) {
            v2Main.setDocHint(docHint);
        }
        v2Main.setFillEnabled(fillMissing);
        if (sheetIndex >= 0) {
            v2Main.setExcelSheetIndex(sheetIndex);
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
}

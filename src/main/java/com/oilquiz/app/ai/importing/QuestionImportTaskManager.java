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
        /** 用户在各决策点的实际操作（字段映射确认/修改、数据预览选择、填充选择、入库确认/取消） */
        public final java.util.List<String> decisions =
                java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        /**
         * 当前待智能体回传的决策点（null=无）。
         * 由 {@link AgentInteractiveDecisionHandler} 发布，import_status 暴露给智能体；
         * 智能体创建 ui_component 与用户交互、get_result 取值后经 import_decide 回传。
         */
        public volatile PendingDecision pendingDecision;
    }

    /**
     * 待智能体确认的决策点：承载给智能体渲染 ui_component 的载荷，
     * 并阻塞导入线程等待 import_decide 回传用户选择。
     * <p>
     * 与 {@link InteractiveImportDecisionHandler} 的语义一致：未回传/超时/取消 → 不导入。
     */
    public static class PendingDecision {
        /** 决策点唯一 ID（import_status 的 pendingDecision.decisionId，import_decide 原样回传） */
        public final String decisionId;
        /** 决策点类型：mapping/preview/fill/ingest（与 ImportMain 四决策点一一对应） */
        public final String type;
        public final String title;
        public final String message;
        /** 可选项（按顺序；下标即 import_decide 的 choice；最后一项为取消） */
        public final org.json.JSONArray options = new org.json.JSONArray();
        /** 结构化载荷（映射表/表头/质量统计等），供智能体渲染更丰富的交互组件 */
        public final org.json.JSONObject payload = new org.json.JSONObject();

        private final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        private volatile int choice = -1;
        private volatile org.json.JSONObject submittedMapping;
        private volatile boolean submitted = false;

        PendingDecision(String decisionId, String type, String title, String message) {
            this.decisionId = decisionId;
            this.type = type;
            this.title = title;
            this.message = message;
        }

        /** 由 import_decide 调用：回传智能体采集到的用户选择；重复提交返回 false */
        boolean submit(int choice, org.json.JSONObject mapping) {
            synchronized (this) {
                if (submitted) return false;
                this.choice = choice;
                this.submittedMapping = mapping;
                this.submitted = true;
            }
            latch.countDown();
            return true;
        }

        /** 取消/超时释放等待线程（未提交，供 cancel/超时兜底） */
        void release() {
            latch.countDown();
        }

        boolean await(long timeoutMs) {
            try {
                return latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        boolean isSubmitted() {
            return submitted;
        }

        int choice() {
            return choice;
        }

        org.json.JSONObject submittedMapping() {
            return submittedMapping;
        }

        /** 序列化为 import_status 的 pendingDecision 字段，指导智能体如何交互与回传 */
        public org.json.JSONObject toJson() {
            org.json.JSONObject o = new org.json.JSONObject();
            try {
                o.put("decisionId", decisionId);
                o.put("type", type);
                o.put("title", title == null ? "" : title);
                o.put("message", message == null ? "" : message);
                o.put("options", options);
                o.put("payload", payload);
                o.put("hint", "请用 ui_component(action=create, component_type=choice, title, message, "
                        + "options=options) 与用户交互并 get_result 取值，"
                        + "再 import_decide(taskId, decisionId, selected=用户选择文本) 回传"
                        + "（也可传 choice=选项下标，0 起；取消=最后一项）。");
            } catch (Exception ignored) {
            }
            return o;
        }
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
     *                  "index"=按 sheetIndex 指定表（智能体显式选择，不再自动选表）。
     *                  多张表请用 startMulti(sheetMode=multi)。
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

        // 不再自动选表：index/multi 由智能体显式指定；all 映射为 -1（后端自动扫表）。
        int finalSheetIndex = sheetIndex;
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
        // interactive：四决策点不再弹系统对话框，改由智能体创建 ui_component 交互后 import_decide 回传。
        v2Main.setInteractionHandler(interactive
                ? new AgentInteractiveDecisionHandler(status)
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

            @Override
            public void onDecision(String description) {
                status.decisions.add(description);
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
        // interactive：四决策点不再弹系统对话框，改由智能体创建 ui_component 交互后 import_decide 回传。
        v2Main.setInteractionHandler(interactive
                ? new AgentInteractiveDecisionHandler(status)
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

            @Override
            public void onDecision(String description) {
                status.decisions.add(description);
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
            if (!s.decisions.isEmpty()) {
                o.put("decisions", new org.json.JSONArray(new java.util.ArrayList<>(s.decisions)));
            }
            // 待智能体回传的决策点：有则智能体需创建 ui_component 交互并 import_decide 回传
            PendingDecision pdec = s.pendingDecision;
            if (pdec != null) {
                o.put("awaitingDecision", true);
                o.put("pendingDecision", pdec.toJson());
            }
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
        // 若正阻塞在待确认决策点：释放等待线程（未提交→按取消处理），否则导入线程会一直挂起
        PendingDecision pdec = s.pendingDecision;
        if (pdec != null) {
            pdec.release();
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

    /**
     * 智能体回传某决策点的用户选择（配合 import_decide 工具）。
     * 由 {@link AgentInteractiveDecisionHandler} 发布的 pendingDecision 消费。
     *
     * @param taskId     导入任务 ID
     * @param decisionId import_status 中 pendingDecision.decisionId（须与当前待确认一致）
     * @param choice     选项下标（0 起；=最后一项即取消）；<0 时用 selected 解析
     * @param selected   用户选择的选项文本（ui_component get_result 的返回；"cancelled"/"取消"=取消）
     * @param mapping    可选：字段映射修改（决策点=mapping 时生效，标准字段→源列名）
     * @return 结果 JSON（{"ok":true,...} 或 {"ok":false,"message":...}）
     */
    public String submitDecision(String taskId, String decisionId, int choice, String selected,
                                 JSONObject mapping) {
        TaskStatus s = tasks.get(taskId);
        if (s == null) {
            return "{\"ok\":false,\"message\":\"任务不存在或已结束\"}";
        }
        PendingDecision pd = s.pendingDecision;
        if (pd == null) {
            return "{\"ok\":false,\"message\":\"当前没有待确认的决策点（可能已回传、已超时，"
                    + "或任务不在交互阶段）。请重新 import_status 查看最新状态。\"}";
        }
        if (decisionId != null && !decisionId.isEmpty() && !decisionId.equals(pd.decisionId)) {
            return "{\"ok\":false,\"message\":\"decisionId 不匹配（当前待确认: " + pd.decisionId
                    + "），请以 import_status 最新的 pendingDecision.decisionId 为准\"}";
        }
        int idx = choice;
        if (idx < 0 && selected != null && !selected.trim().isEmpty()) {
            idx = resolveChoiceByLabel(pd.options, selected.trim());
        }
        if (idx < 0) {
            return "{\"ok\":false,\"message\":\"未识别的选择：请传 choice=选项下标（0 起）"
                    + "或 selected=options 中的文本（取消用最后一项）\"}";
        }
        if (idx >= pd.options.length()) {
            return "{\"ok\":false,\"message\":\"choice 越界（有效范围 0.." + (pd.options.length() - 1) + "）\"}";
        }
        if (!pd.submit(idx, mapping)) {
            return "{\"ok\":false,\"message\":\"该决策点已被处理\"}";
        }
        JSONObject out = new JSONObject();
        try {
            out.put("ok", true);
            out.put("taskId", taskId);
            out.put("decisionId", pd.decisionId);
            out.put("choice", idx);
            out.put("selected", pd.options.optString(idx, ""));
            out.put("message", "已回传决策，导入将继续；请继续 import_status 轮询直到 DONE/ERROR/CANCELLED");
        } catch (Exception e) {
            return "{\"ok\":true,\"taskId\":\"" + taskId + "\"}";
        }
        return out.toString();
    }

    /** 按选项文本解析下标：先精确匹配，再去空格匹配，最后"取消"兜底为末项 */
    private static int resolveChoiceByLabel(org.json.JSONArray options, String label) {
        if (options == null) return -1;
        for (int i = 0; i < options.length(); i++) {
            if (label.equals(options.optString(i, ""))) return i;
        }
        String norm = label.replaceAll("\\s+", "");
        for (int i = 0; i < options.length(); i++) {
            String o = options.optString(i, "").replaceAll("\\s+", "");
            if (!o.isEmpty() && o.equals(norm)) return i;
        }
        if ("cancelled".equalsIgnoreCase(label) || "cancel".equalsIgnoreCase(label)
                || "取消".equals(label) || "取消导入".equals(label)) {
            return options.length() - 1;
        }
        return -1;
    }

}

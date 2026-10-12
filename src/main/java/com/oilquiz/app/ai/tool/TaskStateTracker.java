package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务状态跟踪器（维度四 P0-1：多轮对话任务状态跟踪）。
 *
 * 维护跨轮任务清单（进行中 / 完成 / 待办 / 失败），持久化到 task_states.json：
 * - Agent 通过 task 工具显式维护（add / update / complete / fail / delete / list）
 * - 每次 Agent 执行时注入任务摘要到系统提示词，实现多步任务的跨轮连续性
 *   （长对话中不依赖摘要压缩也能知道"进行到哪一步、还剩什么"）
 *
 * 约束（防失控）：
 * - 上限：最多 20 条任务（超出自动淘汰最旧，不淘汰 in_progress）
 * - 单条描述最长 500 字符
 * - 摘要限额：注入系统提示词最多 8 条（进行中/待办优先，按更新时间降序），
 *   完成后自动从注入摘要中移除，仅 task list 可见
 */
public class TaskStateTracker {

    private static final String TAG = "TaskStateTracker";
    private static final String TASK_FILE = "task_states.json";
    private static final int MAX_TASKS = 20;
    private static final int MAX_DESC_LENGTH = 500;
    /** 注入系统提示词的摘要上限 */
    private static final int MAX_SUMMARY_ENTRIES = 8;

    /** 任务状态 */
    public static final String STATUS_IN_PROGRESS = "in_progress";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_TODO = "todo";
    public static final String STATUS_FAILED = "failed";
    private static final String[] VALID_STATUS = {
            STATUS_IN_PROGRESS, STATUS_COMPLETED, STATUS_TODO, STATUS_FAILED
    };

    public static String normalizeStatus(String status) {
        if (status == null) return STATUS_TODO;
        String s = status.trim().toLowerCase();
        for (String valid : VALID_STATUS) {
            if (valid.equals(s)) return valid;
        }
        return STATUS_TODO;
    }

    /** 进行中/待办为"活跃"，完成/失败为"归档" */
    private static boolean isActive(String status) {
        return STATUS_IN_PROGRESS.equals(status) || STATUS_TODO.equals(status);
    }

    private final File taskFile;
    private final ConcurrentHashMap<String, TaskEntry> tasks = new ConcurrentHashMap<>();

    /** 未归属会话的哨兵键（历史遗留任务或 null 会话落到此桶，不会污染真实会话） */
    public static final String SESSION_DEFAULT = "__default__";
    /** 当前活跃会话键：add/getAll/size/buildTaskSummary 均按此隔离，杜绝跨会话/跨天串味 */
    private volatile String activeSession = SESSION_DEFAULT;

    /** 归一化会话 id：null/空 → SESSION_DEFAULT */
    private static String normSession(String sid) {
        return (sid == null || sid.trim().isEmpty()) ? SESSION_DEFAULT : sid.trim();
    }

    /**
     * 设置当前活跃会话（会话切换 / Agent 执行开始时调用）。
     * 之后所有清单读写仅作用于该会话的任务，实现按会话隔离与"关自动续行跨会话串扰"。
     */
    public void setActiveSession(String sessionId) {
        this.activeSession = normSession(sessionId);
    }

    public String getActiveSession() {
        return activeSession;
    }

    private static volatile TaskStateTracker instance;

    public static TaskStateTracker getInstance(Context context) {
        if (instance == null) {
            synchronized (TaskStateTracker.class) {
                if (instance == null) {
                    instance = new TaskStateTracker(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private TaskStateTracker(Context context) {
        this.taskFile = new File(context.getFilesDir(), TASK_FILE);
        load();
    }

    // ==================== 读写 ====================

    /**
     * 新建任务（自动生成 id，默认状态 in_progress）。
     * 已满上限且无 in_progress/todo 可淘汰时返回 false。
     */
    public synchronized TaskEntry add(String description) {
        return add(description, STATUS_IN_PROGRESS);
    }

    /** 新建任务（指定初始状态） */
    public synchronized TaskEntry add(String description, String status) {
        if (description == null || description.trim().isEmpty()) return null;
        String desc = description.trim();
        if (desc.length() > MAX_DESC_LENGTH) {
            desc = desc.substring(0, MAX_DESC_LENGTH);
        }
        // 上限控制：按「当前会话」计数与淘汰（只淘汰本会话已归档的最旧任务），
        // 避免别的会话/别的天的任务互相挤占或串味
        if (size() >= MAX_TASKS) {
            TaskEntry oldestArchived = findOldestArchived();
            if (oldestArchived == null) {
                AILogger.w(TAG, "任务已达上限且无可归档淘汰，拒绝新建: " + desc);
                return null;
            }
            tasks.remove(oldestArchived.id);
            AILogger.i(TAG, "任务已满，淘汰本会话最旧归档任务: " + oldestArchived.id);
        }
        TaskEntry entry = new TaskEntry(desc, normalizeStatus(status));
        entry.session = this.activeSession;
        tasks.put(entry.id, entry);
        persist();
        return entry;
    }

    /** 更新任务描述/状态/进度 */
    public synchronized boolean update(String id, String description, String status, int progress) {
        TaskEntry entry = tasks.get(id);
        if (entry == null) return false;
        if (description != null && !description.trim().isEmpty()) {
            String desc = description.trim();
            entry.description = desc.length() > MAX_DESC_LENGTH ? desc.substring(0, MAX_DESC_LENGTH) : desc;
        }
        if (status != null) {
            String st = normalizeStatus(status);
            // 完成/失败是终态，不允许回退（防模型误操作翻转）
            if (!STATUS_COMPLETED.equals(entry.status) && !STATUS_FAILED.equals(entry.status)) {
                entry.status = st;
            } else if (STATUS_COMPLETED.equals(entry.status) && STATUS_FAILED.equals(st)) {
                entry.status = st;
            }
        }
        if (progress >= 0 && progress <= 100) {
            entry.progress = progress;
        }
        entry.updatedAt = System.currentTimeMillis();
        persist();
        return true;
    }

    /** 标记完成 */
    public synchronized boolean complete(String id) {
        TaskEntry entry = tasks.get(id);
        if (entry == null) return false;
        entry.status = STATUS_COMPLETED;
        entry.progress = 100;
        entry.updatedAt = System.currentTimeMillis();
        persist();
        return true;
    }

    /** 标记失败 */
    public synchronized boolean fail(String id) {
        TaskEntry entry = tasks.get(id);
        if (entry == null) return false;
        entry.status = STATUS_FAILED;
        entry.updatedAt = System.currentTimeMillis();
        persist();
        return true;
    }

    /**
     * 回滚终态（DLG-04 对话分支/回滚）：completed/failed 任务恢复到 in_progress，
     * 支持"撤销完成/失败判定，继续推进"；已删除任务无法回滚（返回 false）。
     */
    public synchronized boolean revert(String id) {
        TaskEntry entry = tasks.get(id);
        if (entry == null) return false;
        entry.status = STATUS_IN_PROGRESS;
        entry.updatedAt = System.currentTimeMillis();
        persist();
        return true;
    }

    /** 删除任务 */
    public synchronized boolean delete(String id) {
        boolean removed = tasks.remove(id) != null;
        if (removed) persist();
        return removed;
    }

    /**
     * 清除指定会话的全部任务（会话被删除时调用，防幽灵任务占着 task_states.json）。
     * 若删的正是当前活跃会话，顺带把 activeSession 归到默认桶，避免指向已删会话 id。
     * @return 被移除的任务条数
     */
    public synchronized int clearSession(String sessionId) {
        String sid = normSession(sessionId);
        int removed = 0;
        java.util.Iterator<java.util.Map.Entry<String, TaskEntry>> it = tasks.entrySet().iterator();
        while (it.hasNext()) {
            TaskEntry e = it.next().getValue();
            if (e != null && sid.equals(e.session)) { it.remove(); removed++; }
        }
        if (removed > 0) persist();
        // 删除的是当前活跃会话：归位默认桶，避免 getAll/续行仍盯着已删会话 id
        if (sid.equals(this.activeSession)) this.activeSession = SESSION_DEFAULT;
        return removed;
    }

    /**
     * 回收"孤儿会话"任务：移除 session 不在 knownSessionIds 里的任务。
     * 供全清 / 会话列表刷新时兜底，清掉历史遗留或已删会话的残留任务。
     * @param knownSessionIds 仍存在的会话 id 集合（含 SESSION_DEFAULT 视调用方而定）
     * @return 被移除的任务条数
     */
    public synchronized int purgeUnknownSessions(java.util.Set<String> knownSessionIds) {
        if (knownSessionIds == null) return 0;
        int removed = 0;
        java.util.Iterator<java.util.Map.Entry<String, TaskEntry>> it = tasks.entrySet().iterator();
        while (it.hasNext()) {
            TaskEntry e = it.next().getValue();
            String es = e != null && e.session != null ? e.session : SESSION_DEFAULT;
            if (!knownSessionIds.contains(es)) { it.remove(); removed++; }
        }
        if (removed > 0) persist();
        return removed;
    }

    /** 获取单条任务 */
    public TaskEntry get(String id) {
        return tasks.get(id);
    }

    /** 当前会话的全部任务（更新时间降序）；跨会话任务不可见，从根上隔离"别的对话/天的任务" */
    public List<TaskEntry> getAll() {
        String sid = this.activeSession;
        List<TaskEntry> result = new ArrayList<>();
        for (TaskEntry e : tasks.values()) {
            if (e != null && sid.equals(e.session)) result.add(e);
        }
        result.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return result;
    }

    /** 当前会话任务数（供上限与 tool "total" 使用） */
    public int size() {
        String sid = this.activeSession;
        int n = 0;
        for (TaskEntry e : tasks.values()) {
            if (e != null && sid.equals(e.session)) n++;
        }
        return n;
    }

    /** 清空全部会话的任务（"清空全部对话"时调用，与 clearAllConversationSessions 对齐） */
    public synchronized void clearAll() {
        if (tasks.isEmpty()) { this.activeSession = SESSION_DEFAULT; return; }
        tasks.clear();
        this.activeSession = SESSION_DEFAULT;
        persist();
        AILogger.i(TAG, "All tasks cleared (clear-all-conversations)");
    }

    /**
     * 按会话统计「活跃任务（进行中/待办）」条数，供历史抽屉给每条会话显示"N 待办"角标。
     * 不含 SESSION_DEFAULT 桶（孤儿/无会话任务不进任何会话条目）。
     * @return 会话 id -> 活跃任务数（仅含有活跃任务的会话）
     */
    public Map<String, Integer> activeCountBySession() {
        Map<String, Integer> out = new HashMap<>();
        for (TaskEntry e : tasks.values()) {
            if (e == null || !isActive(e.status)) continue;
            String sid = e.session != null ? e.session : SESSION_DEFAULT;
            if (SESSION_DEFAULT.equals(sid)) continue;
            Integer c = out.get(sid);
            out.put(sid, c == null ? 1 : c + 1);
        }
        return out;
    }

    /**
     * 当前会话的进行中/待办任务数，供对话头部「任务进度胶囊」实时显示。
     * 返回 int[]{进行中, 待办}；基于已隔离的 getAll()（仅活跃会话）。
     */
    public int[] activeCountsForCurrentSession() {
        int inProgress = 0, todo = 0;
        for (TaskEntry e : getAll()) {
            if (e == null) continue;
            if (STATUS_IN_PROGRESS.equals(e.status)) inProgress++;
            else if (STATUS_TODO.equals(e.status)) todo++;
        }
        return new int[]{inProgress, todo};
    }

    /** 便捷重载：先切到指定会话再构建摘要（供非 Agent 注入路径按当前 UI 会话读取） */
    public String buildTaskSummary(String sessionId) {
        setActiveSession(sessionId);
        return buildTaskSummary();
    }

    public String buildTaskSummary() {
        List<TaskEntry> all = getAll(); // 已按 activeSession 过滤 + updatedAt 降序
        java.util.LinkedHashMap<String, List<TaskEntry>> byDay = new java.util.LinkedHashMap<>();
        java.text.SimpleDateFormat dayFmt =
                new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        int taken = 0;
        for (TaskEntry e : all) {
            if (!isActive(e.status)) continue;
            if (taken >= MAX_SUMMARY_ENTRIES) break;
            String day = dayFmt.format(new java.util.Date(e.createdAt));
            java.util.List<TaskEntry> bucket = byDay.get(day);
            if (bucket == null) { bucket = new ArrayList<>(); byDay.put(day, bucket); }
            bucket.add(e);
            taken++;
        }
        if (byDay.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        boolean firstDay = true;
        for (java.util.Map.Entry<String, List<TaskEntry>> en : byDay.entrySet()) {
            if (!firstDay) sb.append("\n");
            sb.append("📅 ").append(en.getKey()).append("\n");
            for (TaskEntry e : en.getValue()) {
                String state = STATUS_IN_PROGRESS.equals(e.status) ? "进行中" : "待办";
                sb.append("- [").append(state)
                  .append(e.progress > 0 ? " " + e.progress + "%" : "")
                  .append("] ").append(e.description).append("\n");
            }
            firstDay = false;
        }
        int len = sb.length();
        while (len > 0 && sb.charAt(len - 1) == '\n') sb.deleteCharAt(len - 1);
        return sb.toString();
    }

    /** 找本会话已归档（completed/failed）且 updatedAt 最早的条目（淘汰用） */
    private TaskEntry findOldestArchived() {
        String sid = this.activeSession;
        TaskEntry oldest = null;
        for (TaskEntry e : tasks.values()) {
            if (e == null || !sid.equals(e.session)) continue;
            if (isActive(e.status)) continue;
            if (oldest == null || e.updatedAt < oldest.updatedAt) {
                oldest = e;
            }
        }
        return oldest;
    }

    // ==================== 持久化 ====================

    /** 原子写：先写 .tmp 再 rename 覆盖 */
    private synchronized void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (TaskEntry e : tasks.values()) {
                JSONObject obj = new JSONObject();
                obj.put("id", e.id);
                obj.put("description", e.description);
                obj.put("status", e.status);
                obj.put("progress", e.progress);
                obj.put("createdAt", e.createdAt);
                obj.put("updatedAt", e.updatedAt);
                obj.put("session", e.session != null ? e.session : SESSION_DEFAULT);
                arr.put(obj);
            }
            byte[] data = arr.toString(2).getBytes("UTF-8");
            File tmp = new File(taskFile.getAbsolutePath() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(data);
            }
            if (!tmp.renameTo(taskFile)) {
                try (FileOutputStream fos = new FileOutputStream(taskFile)) {
                    fos.write(data);
                }
                tmp.delete();
            }
            AILogger.d(TAG, "Tasks persisted: " + tasks.size() + " entries");
        } catch (Exception e) {
            AILogger.w(TAG, "Persist tasks failed: " + e.getMessage());
        }
    }

    private synchronized void load() {
        try {
            if (!taskFile.exists()) return;
            byte[] bytes = readAllBytes(taskFile);
            if (bytes.length == 0) return;
            JSONArray arr = new JSONArray(new String(bytes, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                String id = obj.optString("id", "");
                String description = obj.optString("description", "");
                if (id.isEmpty() || description.isEmpty()) continue;
                TaskEntry e = new TaskEntry(id, description,
                        normalizeStatus(obj.optString("status", STATUS_TODO)),
                        obj.optInt("progress", 0),
                        obj.optLong("createdAt", System.currentTimeMillis()),
                        obj.optLong("updatedAt", System.currentTimeMillis()));
                // 历史任务无 session 字段 → 归入 SESSION_DEFAULT，不污染真实会话清单
                e.session = obj.optString("session", SESSION_DEFAULT);
                if (e.session == null || e.session.isEmpty()) e.session = SESSION_DEFAULT;
                tasks.put(id, e);
            }
            AILogger.i(TAG, "Tasks loaded: " + tasks.size() + " entries");
        } catch (Exception e) {
            AILogger.w(TAG, "Load tasks failed: " + e.getMessage());
            try {
                File bak = new File(taskFile.getAbsolutePath() + ".bak");
                if (!bak.exists()) {
                    java.nio.file.Files.copy(taskFile.toPath(), bak.toPath());
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static byte[] readAllBytes(File f) throws java.io.IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** 任务条目 */
    public static class TaskEntry {
        public final String id;
        public String description;
        public String status;
        public int progress;
        public final long createdAt;
        public long updatedAt;
        /** 归属会话键；getAll/size/summary 按当前活跃会话过滤，实现会话隔离 */
        public String session = SESSION_DEFAULT;

        public TaskEntry(String description, String status) {
            this("t" + System.currentTimeMillis() + "_" + (int) (Math.random() * 10000),
                    description, status, 0, System.currentTimeMillis(), System.currentTimeMillis());
        }

        private TaskEntry(String id, String description, String status, int progress,
                          long createdAt, long updatedAt) {
            this.id = id;
            this.description = description;
            this.status = status;
            this.progress = progress;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }
}

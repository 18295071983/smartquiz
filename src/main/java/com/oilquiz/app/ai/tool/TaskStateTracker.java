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
import java.util.List;
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
        // 上限控制：只淘汰已归档（completed/failed）的最旧任务
        if (tasks.size() >= MAX_TASKS) {
            TaskEntry oldestArchived = findOldestArchived();
            if (oldestArchived == null) {
                AILogger.w(TAG, "任务已达上限且无可归档淘汰，拒绝新建: " + desc);
                return null;
            }
            tasks.remove(oldestArchived.id);
            AILogger.i(TAG, "任务已满，淘汰最旧归档任务: " + oldestArchived.id);
        }
        TaskEntry entry = new TaskEntry(desc, normalizeStatus(status));
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

    /** 获取单条任务 */
    public TaskEntry get(String id) {
        return tasks.get(id);
    }

    /** 全部任务（更新时间降序） */
    public List<TaskEntry> getAll() {
        List<TaskEntry> result = new ArrayList<>(tasks.values());
        result.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return result;
    }

    public int size() {
        return tasks.size();
    }

    /**
     * 任务摘要（注入系统提示词用）：活跃任务（进行中/待办）优先，按更新时间降序，
     * 最多 MAX_SUMMARY_ENTRIES 条；无活跃任务返回 null（归档任务仅 task list 可见）。
     */
    public String buildTaskSummary() {
        List<TaskEntry> all = getAll();
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (TaskEntry e : all) {
            if (!isActive(e.status)) continue;
            if (count >= MAX_SUMMARY_ENTRIES) break;
            String state = STATUS_IN_PROGRESS.equals(e.status) ? "进行中" : "待办";
            String line = "- [" + state + (e.progress > 0 ? " " + e.progress + "%" : "")
                    + "] " + e.description;
            if (sb.length() > 0) sb.append("\n");
            sb.append(line);
            count++;
        }
        if (sb.length() == 0) return null;
        return sb.toString();
    }

    /** 找已归档（completed/failed）且 updatedAt 最早的条目（淘汰用） */
    private TaskEntry findOldestArchived() {
        TaskEntry oldest = null;
        for (TaskEntry e : tasks.values()) {
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

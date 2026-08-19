package com.oilquiz.app.ai.agent.online;

import android.content.Context;

import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Agent 长期记忆存储（跨会话持久化）。
 *
 * 结构化记忆（key → value）持久化到 agent_memory.json：
 * - 用户偏好、重要事实、常用信息
 * - 模型通过 memory 工具主动读写（save / recall / delete / list / clear）
 * - 每次 Agent 执行时注入记忆摘要到系统提示词，实现跨会话"记得你"
 *
 * 约束（防失控）：
 * - 上限：最多 100 条，单条 value 最长 1000 字符
 * - 淘汰：达到上限时自动淘汰最旧（updatedAt 最早）的记忆
 * - 摘要限额：注入系统提示词的摘要最多 30 条 / 2000 字符（按最近更新优先），
 *   超出部分仅在管理页/memory list 可见，控制每次对话的 token 成本
 */
public class AgentMemoryStore {

    private static final String TAG = "AgentMemoryStore";
    private static final String MEMORY_FILE = "agent_memory.json";
    private static final int MAX_MEMORIES = 100;
    private static final int MAX_VALUE_LENGTH = 1000;
    /** 注入系统提示词的摘要上限：条数与总字符数 */
    private static final int MAX_SUMMARY_ENTRIES = 30;
    private static final int MAX_SUMMARY_CHARS = 2000;

    private final File memoryFile;
    private final java.util.Map<String, MemoryEntry> memories = new java.util.concurrent.ConcurrentHashMap<>();

    private static volatile AgentMemoryStore instance;

    public static AgentMemoryStore getInstance(Context context) {
        if (instance == null) {
            synchronized (AgentMemoryStore.class) {
                if (instance == null) {
                    instance = new AgentMemoryStore(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private AgentMemoryStore(Context context) {
        this.memoryFile = new File(context.getFilesDir(), MEMORY_FILE);
        load();
    }

    // ==================== 读写 ====================

    /** 保存一条记忆（覆盖同 key 并刷新时间；满上限时淘汰最旧）。调用方可用 get(key) 预判是否覆盖 */
    public synchronized boolean save(String key, String value) {
        if (key == null || key.trim().isEmpty() || value == null || value.isEmpty()) return false;
        key = key.trim();
        if (value.length() > MAX_VALUE_LENGTH) {
            value = value.substring(0, MAX_VALUE_LENGTH);
        }
        // 上限控制：新 key 且已满时淘汰最旧（updatedAt 最早）
        if (!memories.containsKey(key) && memories.size() >= MAX_MEMORIES) {
            MemoryEntry oldest = findOldest();
            if (oldest != null) {
                memories.remove(oldest.key);
                AILogger.i(TAG, "记忆已满，淘汰最旧: " + oldest.key);
            }
        }
        memories.put(key, new MemoryEntry(key, value, System.currentTimeMillis()));
        persist();
        return true;
    }

    /** 读取一条记忆（刷新访问计数，供重要性排序） */
    public String get(String key) {
        if (key == null) return null;
        MemoryEntry e = memories.get(key.trim());
        if (e != null) {
            e.accessCount++;
            e.lastAccess = System.currentTimeMillis();
        }
        return e != null ? e.value : null;
    }

    /** 删除一条记忆 */
    public synchronized boolean remove(String key) {
        if (key == null) return false;
        boolean removed = memories.remove(key.trim()) != null;
        if (removed) persist();
        return removed;
    }

    /** 清空所有记忆 */
    public synchronized void clear() {
        memories.clear();
        persist();
    }

    /** 所有记忆（key 排序，管理页展示稳定） */
    public List<MemoryEntry> getAll() {
        List<MemoryEntry> result = new ArrayList<>(memories.values());
        result.sort((a, b) -> a.key.compareTo(b.key));
        return result;
    }

    public int size() {
        return memories.size();
    }

    /**
     * 记忆摘要（注入系统提示词用）：按最近更新优先，最多 MAX_SUMMARY_ENTRIES 条、
     * 总长 MAX_SUMMARY_CHARS 字符；超出部分提示用 memory list 查看。无记忆返回 null。
     */
    public String buildMemorySummary() {
        if (memories.isEmpty()) return null;
        List<MemoryEntry> all = new ArrayList<>(memories.values());
        // 重要性优先（访问次数 + 更新新鲜度），比纯按更新时间更能保留"重要的"记忆
        all.sort((a, b) -> Double.compare(b.importance(), a.importance()));
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (MemoryEntry e : all) {
            if (count >= MAX_SUMMARY_ENTRIES) break;
            String line = "- " + e.key + ": " + e.value;
            if (sb.length() > 0 && sb.length() + line.length() + 1 > MAX_SUMMARY_CHARS) break;
            if (sb.length() > 0) sb.append("\n");
            sb.append(line);
            count++;
        }
        int omitted = all.size() - count;
        if (omitted > 0) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("(还有 ").append(omitted).append(" 条记忆未列出，可用 memory list 查看)");
        }
        return sb.toString();
    }

    /** 找重要性最低的条目（淘汰用）；无则 null */
    private MemoryEntry findOldest() {
        MemoryEntry lowest = null;
        double lowestScore = Double.MAX_VALUE;
        Iterator<MemoryEntry> it = memories.values().iterator();
        while (it.hasNext()) {
            MemoryEntry e = it.next();
            double score = e.importance();
            if (score < lowestScore) {
                lowestScore = score;
                lowest = e;
            }
        }
        return lowest;
    }

    // ==================== 持久化 ====================

    private synchronized void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (MemoryEntry e : memories.values()) {
                JSONObject obj = new JSONObject();
                obj.put("key", e.key);
                obj.put("value", e.value);
                obj.put("updatedAt", e.updatedAt);
                arr.put(obj);
            }
            FileOutputStream fos = new FileOutputStream(memoryFile);
            fos.write(arr.toString(2).getBytes("UTF-8"));
            fos.close();
            AILogger.d(TAG, "Memory persisted: " + memories.size() + " entries");
        } catch (Exception e) {
            AILogger.w(TAG, "Persist memory failed: " + e.getMessage());
        }
    }

    private synchronized void load() {
        try {
            if (!memoryFile.exists()) return;
            FileInputStream fis = new FileInputStream(memoryFile);
            byte[] bytes = new byte[(int) memoryFile.length()];
            int read = fis.read(bytes);
            fis.close();
            if (read <= 0) return;
            JSONArray arr = new JSONArray(new String(bytes, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                String key = obj.optString("key", "");
                String value = obj.optString("value", "");
                if (!key.isEmpty() && !value.isEmpty()) {
                    // 旧格式无 updatedAt → 记为 0（淘汰时优先），新格式读时间戳
                    long updatedAt = obj.optLong("updatedAt", 0L);
                    memories.put(key, new MemoryEntry(key, value, updatedAt));
                }
            }
            AILogger.i(TAG, "Memory loaded: " + memories.size() + " entries");
        } catch (Exception e) {
            AILogger.w(TAG, "Load memory failed: " + e.getMessage());
        }
    }

    /** 记忆条目（key/value + 最近更新时间 + 访问次数，用于重要性排序/淘汰） */
    public static class MemoryEntry {
        public final String key;
        public final String value;
        public final long updatedAt;
        public int accessCount;
        public long lastAccess;

        public MemoryEntry(String key, String value) {
            this(key, value, System.currentTimeMillis());
        }

        public MemoryEntry(String key, String value, long updatedAt) {
            this.key = key;
            this.value = value;
            this.updatedAt = updatedAt;
            this.accessCount = 0;
            this.lastAccess = updatedAt;
        }

        /** 综合重要性分数：访问越频繁/越新越重要（淘汰时优先移除低分） */
        public double importance() {
            long now = System.currentTimeMillis();
            long age = Math.max(1, now - lastAccess);
            // 访问次数为主 + 更新新鲜度加成
            return accessCount + (1000.0 / age);
        }
    }
}

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
 * - 模型通过 memory 工具主动读写（save / recall / list / clear）
 * - 每次 Agent 执行时注入记忆摘要到系统提示词，实现跨会话"记得你"
 *
 * 上限：最多 100 条记忆，单条 value 最长 1000 字符，防止无限增长。
 */
public class AgentMemoryStore {

    private static final String TAG = "AgentMemoryStore";
    private static final String MEMORY_FILE = "agent_memory.json";
    private static final int MAX_MEMORIES = 100;
    private static final int MAX_VALUE_LENGTH = 1000;

    private final File memoryFile;
    private final java.util.Map<String, String> memories = new java.util.concurrent.ConcurrentHashMap<>();

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

    /** 保存一条记忆（覆盖同 key） */
    public synchronized boolean save(String key, String value) {
        if (key == null || key.trim().isEmpty() || value == null || value.isEmpty()) return false;
        key = key.trim();
        if (value.length() > MAX_VALUE_LENGTH) {
            value = value.substring(0, MAX_VALUE_LENGTH);
        }
        // 上限控制：新 key 且已满时淘汰最旧
        if (!memories.containsKey(key) && memories.size() >= MAX_MEMORIES) {
            String oldestKey = null;
            // ConcurrentHashMap 无序，取任意一个即可（简单淘汰策略）
            Iterator<String> it = memories.keySet().iterator();
            if (it.hasNext()) oldestKey = it.next();
            if (oldestKey != null) memories.remove(oldestKey);
        }
        memories.put(key, value);
        persist();
        return true;
    }

    /** 读取一条记忆 */
    public String get(String key) {
        if (key == null) return null;
        return memories.get(key.trim());
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

    /** 所有记忆（key 排序，保证提示词注入前缀稳定） */
    public List<MemoryEntry> getAll() {
        List<MemoryEntry> result = new ArrayList<>();
        List<String> keys = new ArrayList<>(memories.keySet());
        java.util.Collections.sort(keys);
        for (String k : keys) {
            result.add(new MemoryEntry(k, memories.get(k)));
        }
        return result;
    }

    public int size() {
        return memories.size();
    }

    /** 记忆摘要（注入系统提示词用）："key: value\n..."，无记忆返回 null */
    public String buildMemorySummary() {
        List<MemoryEntry> all = getAll();
        if (all.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (MemoryEntry e : all) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("- ").append(e.key).append(": ").append(e.value);
        }
        return sb.toString();
    }

    // ==================== 持久化 ====================

    private synchronized void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (MemoryEntry e : getAll()) {
                JSONObject obj = new JSONObject();
                obj.put("key", e.key);
                obj.put("value", e.value);
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
                    memories.put(key, value);
                }
            }
            AILogger.i(TAG, "Memory loaded: " + memories.size() + " entries");
        } catch (Exception e) {
            AILogger.w(TAG, "Load memory failed: " + e.getMessage());
        }
    }

    /** 记忆条目 */
    public static class MemoryEntry {
        public final String key;
        public final String value;

        public MemoryEntry(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }
}

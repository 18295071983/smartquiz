package com.oilquiz.app.ai.agent.online;

import android.content.Context;

import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
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
 * 分类（维度三 P0-1 记忆结构化）：
 * - fact（事实）：客观信息，如家庭住址、工作单位
 * - preference（偏好）：用户喜好，如称呼、口味、回复风格
 * - context（情境）：阶段性/临时情境，如当前项目、近期目标
 * 旧数据（无分类）兼容为 fact，读取时自动迁移。
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

    /** 记忆分类（维度三 P0-1） */
    public static final String CATEGORY_FACT = "fact";
    public static final String CATEGORY_PREFERENCE = "preference";
    public static final String CATEGORY_CONTEXT = "context";
    private static final String[] VALID_CATEGORIES = {CATEGORY_FACT, CATEGORY_PREFERENCE, CATEGORY_CONTEXT};

    /** 分类显示名（注入摘要用） */
    private static String categoryLabel(String category) {
        if (CATEGORY_PREFERENCE.equals(category)) return "偏好";
        if (CATEGORY_CONTEXT.equals(category)) return "情境";
        return "事实";
    }

    /** 归一化分类：非法/空 → fact */
    public static String normalizeCategory(String category) {
        if (category == null) return CATEGORY_FACT;
        String c = category.trim().toLowerCase();
        for (String valid : VALID_CATEGORIES) {
            if (valid.equals(c)) return valid;
        }
        return CATEGORY_FACT;
    }

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

    /** 保存一条记忆（默认分类 fact）。调用方可用 get(key) 预判是否覆盖 */
    public boolean save(String key, String value) {
        return save(key, value, CATEGORY_FACT);
    }

    /** 保存一条记忆（指定分类：fact/preference/context，非法回退 fact） */
    public synchronized boolean save(String key, String value, String category) {
        if (key == null || key.trim().isEmpty() || value == null || value.isEmpty()) return false;
        key = key.trim();
        String cat = normalizeCategory(category);
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
        memories.put(key, new MemoryEntry(key, value, cat, System.currentTimeMillis()));
        persist();
        return true;
    }

    /** 读取一条记忆 */
    public String get(String key) {
        if (key == null) return null;
        MemoryEntry e = memories.get(key.trim());
        return e != null ? e.value : null;
    }

    /** 读取一条记忆完整条目（含分类） */
    public MemoryEntry getEntry(String key) {
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

    /** 所有记忆（key 排序，管理页展示稳定） */
    public List<MemoryEntry> getAll() {
        List<MemoryEntry> result = new ArrayList<>(memories.values());
        result.sort((a, b) -> a.key.compareTo(b.key));
        return result;
    }

    /** 按分类过滤记忆（category 为 null 或非法时返回全部） */
    public List<MemoryEntry> getByCategory(String category) {
        String cat = normalizeCategory(category);
        List<MemoryEntry> result = new ArrayList<>();
        for (MemoryEntry e : memories.values()) {
            if (CATEGORY_FACT.equals(cat) || e.category.equals(cat)) {
                // 注意：归一化后 category 必为三类之一，正常应全等匹配
                if (e.category.equals(cat)) result.add(e);
            }
        }
        result.sort((a, b) -> a.key.compareTo(b.key));
        return result;
    }

    public int size() {
        return memories.size();
    }

    /**
     * 记忆摘要（注入系统提示词用）：按最近更新优先，最多 MAX_SUMMARY_ENTRIES 条、
     * 总长 MAX_SUMMARY_CHARS 字符；每条带分类标签（[偏好]/[情境]/[事实]），
     * 帮助模型按语境正确使用记忆。超出部分提示用 memory list 查看。无记忆返回 null。
     */
    public String buildMemorySummary() {
        if (memories.isEmpty()) return null;
        List<MemoryEntry> all = new ArrayList<>(memories.values());
        // 最近更新优先
        all.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (MemoryEntry e : all) {
            if (count >= MAX_SUMMARY_ENTRIES) break;
            String line = "- [" + categoryLabel(e.category) + "] " + e.key + ": " + e.value;
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

    /** 找 updatedAt 最早的条目（淘汰用）；无则 null */
    private MemoryEntry findOldest() {
        MemoryEntry oldest = null;
        Iterator<MemoryEntry> it = memories.values().iterator();
        while (it.hasNext()) {
            MemoryEntry e = it.next();
            if (oldest == null || e.updatedAt < oldest.updatedAt) {
                oldest = e;
            }
        }
        return oldest;
    }

    // ==================== 持久化 ====================

    /** 上次加载是否失败（文件损坏）：防止后续 save 覆盖本可恢复的旧记忆 */
    private volatile boolean loadFailed = false;

    /** 原子写：先写 .tmp 再 rename 覆盖，避免写一半崩溃导致 JSON 损坏 */
    private synchronized void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (MemoryEntry e : memories.values()) {
                JSONObject obj = new JSONObject();
                obj.put("key", e.key);
                obj.put("value", e.value);
                obj.put("category", e.category);
                obj.put("updatedAt", e.updatedAt);
                arr.put(obj);
            }
            byte[] data = arr.toString(2).getBytes("UTF-8");
            File tmp = new File(memoryFile.getAbsolutePath() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(data);
            }
            if (!tmp.renameTo(memoryFile)) {
                // rename 失败（罕见）回退直接写
                try (FileOutputStream fos = new FileOutputStream(memoryFile)) {
                    fos.write(data);
                }
                tmp.delete();
            }
            AILogger.d(TAG, "Memory persisted: " + memories.size() + " entries");
        } catch (Exception e) {
            AILogger.w(TAG, "Persist memory failed: " + e.getMessage());
        }
    }

    private synchronized void load() {
        try {
            if (!memoryFile.exists()) return;
            byte[] bytes = readAllBytes(memoryFile);
            if (bytes.length == 0) return;
            JSONArray arr = new JSONArray(new String(bytes, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                String key = obj.optString("key", "");
                String value = obj.optString("value", "");
                if (!key.isEmpty() && !value.isEmpty()) {
                    // 旧格式无 updatedAt → 记为 0（淘汰时优先）；无 category → 兼容为 fact
                    long updatedAt = obj.optLong("updatedAt", 0L);
                    String category = normalizeCategory(obj.optString("category", CATEGORY_FACT));
                    memories.put(key, new MemoryEntry(key, value, category, updatedAt));
                }
            }
            AILogger.i(TAG, "Memory loaded: " + memories.size() + " entries");
        } catch (Exception e) {
            AILogger.w(TAG, "Load memory failed: " + e.getMessage());
            // 解析失败：备份损坏文件（只备一次），防止后续 save 覆盖导致历史记忆无法恢复
            loadFailed = true;
            try {
                File bak = new File(memoryFile.getAbsolutePath() + ".bak");
                if (!bak.exists()) {
                    java.nio.file.Files.copy(memoryFile.toPath(), bak.toPath());
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** 循环读满文件（单次 read 可能读不满） */
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

    /** 记忆条目（key/value + 分类 + 最近更新时间） */
    public static class MemoryEntry {
        public final String key;
        public final String value;
        public final String category;
        public final long updatedAt;

        public MemoryEntry(String key, String value) {
            this(key, value, CATEGORY_FACT, System.currentTimeMillis());
        }

        public MemoryEntry(String key, String value, String category, long updatedAt) {
            this.key = key;
            this.value = value;
            this.category = normalizeCategory(category);
            this.updatedAt = updatedAt;
        }
    }
}

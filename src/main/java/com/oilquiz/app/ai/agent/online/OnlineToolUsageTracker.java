package com.oilquiz.app.ai.agent.online;

import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 在线工具使用链追踪 —— 记录每次工具调用，统计指标，发现组合模式。
 *
 * 职责：
 * 1. 记录每次工具调用的元数据（工具名、参数、结果、耗时、成功/失败、时间戳）
 * 2. 统计单工具调用次数、成功率、平均耗时、超时率
 * 3. 发现常用工具组合模式（同一会话中连续/相近调用的工具对）
 * 4. 提供性能指标供调用链优化
 *
 * 线程安全：使用 {@link ConcurrentHashMap} 和原子计数器。
 */
public class OnlineToolUsageTracker {

    private static final String TAG = "OnlineToolUsageTracker";

    /** 单工具最大保留记录数（防止内存无限增长） */
    private static final int MAX_RECORDS_PER_TOOL = 50;
    /** 全局最大保留记录数 */
    private static final int MAX_TOTAL_RECORDS = 500;
    /** 模式发现的最小共现次数 */
    private static final int PATTERN_MIN_COOCCURRENCE = 3;
    /** 单条参数/结果摘要最大长度 */
    private static final int SUMMARY_MAX_LENGTH = 200;

    /** 工具调用记录：toolName → 记录列表（按时间序） */
    private final Map<String, LinkedList<ToolCallRecord>> recordsByTool = new ConcurrentHashMap<>();

    /** 工具组合共现计数：toolA|toolB → 次数 */
    private final Map<String, Integer> cooccurrenceCount = new ConcurrentHashMap<>();

    /** 当前会话的工具调用序列（用于共现分析） */
    private final List<String> sessionSequence = Collections.synchronizedList(new ArrayList<>());

    /** 全局统计 */
    private final AtomicInteger totalCalls = new AtomicInteger(0);
    private final AtomicInteger totalSuccess = new AtomicInteger(0);
    private final AtomicInteger totalFailures = new AtomicInteger(0);
    private final AtomicLong totalExecutionTime = new AtomicLong(0);

    // ==================== 持久化（跨重启保留统计，实现自进化） ====================

    /** 统计持久化文件 */
    private java.io.File statsFile;

    /** 绑定持久化文件（由 OnlineToolManager 调用） */
    public void attachStatsFile(java.io.File file) {
        this.statsFile = file;
        loadStats();
    }

    /** 持久化统计：共现计数（长期经验）落盘（原子写：tmp + rename，防写一半崩溃损坏） */
    public synchronized void persistStats() {
        if (statsFile == null) return;
        try {
            org.json.JSONObject root = new org.json.JSONObject();
            org.json.JSONObject co = new org.json.JSONObject();
            for (Map.Entry<String, Integer> e : cooccurrenceCount.entrySet()) {
                co.put(e.getKey(), e.getValue());
            }
            root.put("cooccurrence", co);
            root.put("totalCalls", totalCalls.get());
            root.put("totalSuccess", totalSuccess.get());
            root.put("totalFailures", totalFailures.get());
            java.io.File tmp = new java.io.File(statsFile.getAbsolutePath() + ".tmp");
            try (java.io.FileWriter writer = new java.io.FileWriter(tmp)) {
                writer.write(root.toString());
            }
            if (!tmp.renameTo(statsFile)) {
                try (java.io.FileWriter writer = new java.io.FileWriter(statsFile)) {
                    writer.write(root.toString());
                }
                tmp.delete();
            }
            AILogger.d(TAG, "Usage stats persisted: " + cooccurrenceCount.size() + " patterns");
        } catch (Exception e) {
            AILogger.w(TAG, "Persist usage stats failed: " + e.getMessage());
        }
    }

    /** 启动时恢复历史统计（共现模式跨重启累积 → 组合建议越来越准） */
    private synchronized void loadStats() {
        if (statsFile == null || !statsFile.exists()) return;
        try {
            org.json.JSONObject root = new org.json.JSONObject(new String(
                    readAllBytes(statsFile), "UTF-8"));
            org.json.JSONObject co = root.optJSONObject("cooccurrence");
            if (co != null) {
                java.util.Iterator<String> keys = co.keys();
                while (keys.hasNext()) {
                    String pair = keys.next();
                    cooccurrenceCount.put(pair, co.optInt(pair, 0));
                }
            }
            totalCalls.set(root.optInt("totalCalls", 0));
            totalSuccess.set(root.optInt("totalSuccess", 0));
            totalFailures.set(root.optInt("totalFailures", 0));
            AILogger.i(TAG, "Usage stats restored: " + cooccurrenceCount.size() + " patterns");
        } catch (Exception e) {
            AILogger.w(TAG, "Load usage stats failed: " + e.getMessage());
        }
    }

    private static byte[] readAllBytes(java.io.File file) throws java.io.IOException {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = 0;
            while (read < bytes.length) {
                int r = fis.read(bytes, read, bytes.length - read);
                if (r < 0) break;
                read += r;
            }
            return bytes;
        }
    }

    // ==================== 记录 ====================

    /**
     * 记录一次工具调用。
     *
     * @param toolName      工具名
     * @param arguments     参数 JSON 字符串
     * @param success       是否成功
     * @param executionTime 执行耗时（毫秒）
     * @param resultSummary 结果摘要（成功时为结果，失败时为错误信息）
     */
    public void recordCall(String toolName, String arguments, boolean success,
                           long executionTime, String resultSummary) {
        if (toolName == null) return;

        ToolCallRecord record = new ToolCallRecord(
            toolName,
            truncate(arguments),
            success,
            executionTime,
            truncate(resultSummary),
            System.currentTimeMillis()
        );

        // 记录到工具历史
        LinkedList<ToolCallRecord> list = recordsByTool.computeIfAbsent(toolName,
            k -> new LinkedList<>());
        synchronized (list) {
            list.add(record);
            while (list.size() > MAX_RECORDS_PER_TOOL) list.removeFirst();
        }

        // 更新全局统计
        totalCalls.incrementAndGet();
        if (success) totalSuccess.incrementAndGet();
        else totalFailures.incrementAndGet();
        totalExecutionTime.addAndGet(executionTime);

        // 共现分析
        updateCooccurrence(toolName);

        // 全局记录数控制（按实际保留记录数判断，避免每次调用都做无效裁剪）
        if (getRecordCount() > MAX_TOTAL_RECORDS) {
            trimRecords();
        }

        AILogger.d(TAG, "Recorded call: " + toolName + " success=" + success
            + " time=" + executionTime + "ms");
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() > SUMMARY_MAX_LENGTH ? s.substring(0, SUMMARY_MAX_LENGTH) + "..." : s;
    }

    /**
     * 更新工具共现计数。
     * 当前调用的工具与同一会话中最近 3 次调用的工具形成共现对。
     */
    private void updateCooccurrence(String currentTool) {
        List<String> snapshot;
        synchronized (sessionSequence) {
            // 取最近 3 个不同工具
            snapshot = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (int i = sessionSequence.size() - 1; i >= 0 && snapshot.size() < 3; i--) {
                String t = sessionSequence.get(i);
                if (!t.equals(currentTool) && seen.add(t)) {
                    snapshot.add(t);
                }
            }
            sessionSequence.add(currentTool);
            // 限制会话序列长度
            while (sessionSequence.size() > 20) sessionSequence.remove(0);
        }

        for (String other : snapshot) {
            String pair = normalizePair(currentTool, other);
            cooccurrenceCount.merge(pair, 1, Integer::sum);
        }
    }

    /** 归一化工具对（按字典序），使 A|B 和 B|A 计为同一对 */
    private String normalizePair(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    /** 修剪记录（移除最旧工具的最旧记录） */
    private void trimRecords() {
        // 简单策略：每个工具保留最近 MAX_RECORDS_PER_TOOL 条已足够，此处仅重置会话序列
        synchronized (sessionSequence) {
            if (sessionSequence.size() > 10) {
                sessionSequence.subList(0, sessionSequence.size() - 10).clear();
            }
        }
    }

    /** 当前实际保留的记录总数（跨工具） */
    private int getRecordCount() {
        int count = 0;
        for (LinkedList<ToolCallRecord> list : recordsByTool.values()) {
            count += list.size();
        }
        return count;
    }

    // ==================== 统计 ====================

    /**
     * 获取单工具统计。
     */
    public ToolStats getToolStats(String toolName) {
        LinkedList<ToolCallRecord> list = recordsByTool.get(toolName);
        if (list == null || list.isEmpty()) {
            return new ToolStats(toolName, 0, 0, 0, 0, 0, 0);
        }
        synchronized (list) {
            int calls = list.size();
            int success = 0;
            long totalTime = 0;
            int timeoutCount = 0;
            long lastTime = 0;
            for (ToolCallRecord r : list) {
                if (r.success) success++;
                totalTime += r.executionTime;
                if (r.executionTime > 30_000) timeoutCount++;
                if (r.timestamp > lastTime) lastTime = r.timestamp;
            }
            double successRate = calls > 0 ? (double) success / calls : 0;
            double avgTime = calls > 0 ? (double) totalTime / calls : 0;
            double timeoutRate = calls > 0 ? (double) timeoutCount / calls : 0;
            return new ToolStats(toolName, calls, success, successRate, avgTime, timeoutRate, lastTime);
        }
    }

    /**
     * 获取所有工具统计。
     */
    public Map<String, ToolStats> getAllStats() {
        Map<String, ToolStats> result = new LinkedHashMap<>();
        for (String toolName : recordsByTool.keySet()) {
            result.put(toolName, getToolStats(toolName));
        }
        return result;
    }

    /**
     * 获取全局统计摘要。
     */
    public String getGlobalStatsSummary() {
        int calls = totalCalls.get();
        int success = totalSuccess.get();
        int failures = totalFailures.get();
        long time = totalExecutionTime.get();
        double successRate = calls > 0 ? (double) success / calls : 0;
        double avgTime = calls > 0 ? (double) time / calls : 0;
        return String.format("总调用: %d, 成功: %d, 失败: %d, 成功率: %.1f%%, 平均耗时: %.0fms",
            calls, success, failures, successRate * 100, avgTime);
    }

    // ==================== 模式发现 ====================

    /**
     * 发现常用工具组合模式。
     * 返回共现次数 ≥ PATTERN_MIN_COOCCURRENCE 的工具对，按次数降序。
     */
    public List<ToolPattern> discoverPatterns() {
        List<ToolPattern> patterns = new ArrayList<>();
        for (Map.Entry<String, Integer> e : cooccurrenceCount.entrySet()) {
            if (e.getValue() >= PATTERN_MIN_COOCCURRENCE) {
                String[] parts = e.getKey().split("\\|");
                if (parts.length == 2) {
                    patterns.add(new ToolPattern(parts[0], parts[1], e.getValue()));
                }
            }
        }
        Collections.sort(patterns, (a, b) -> Integer.compare(b.count, a.count));
        return patterns;
    }

    /**
     * 获取与某工具最常共现的工具。
     */
    public List<String> getMostCoOccurred(String toolName) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : cooccurrenceCount.entrySet()) {
            String[] parts = e.getKey().split("\\|");
            if (parts.length == 2) {
                if (parts[0].equals(toolName)) counts.put(parts[1], e.getValue());
                else if (parts[1].equals(toolName)) counts.put(parts[0], e.getValue());
            }
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Integer> e : sorted) result.add(e.getKey());
        return result;
    }

    // ==================== 重置 ====================

    /**
     * 清空所有统计（开始新会话时调用）。
     */
    public void resetStats() {
        recordsByTool.clear();
        cooccurrenceCount.clear();
        synchronized (sessionSequence) {
            sessionSequence.clear();
        }
        totalCalls.set(0);
        totalSuccess.set(0);
        totalFailures.set(0);
        totalExecutionTime.set(0);
        // 同步落盘：否则重启后旧共现统计全部复活，"新会话清零"语义失效
        persistStats();
        AILogger.i(TAG, "Usage stats reset");
    }

    /**
     * 清空单工具统计。
     */
    public void resetToolStats(String toolName) {
        LinkedList<ToolCallRecord> list = recordsByTool.remove(toolName);
        if (list != null) {
            synchronized (list) {
                for (ToolCallRecord r : list) {
                    totalCalls.decrementAndGet();
                    if (r.success) totalSuccess.decrementAndGet();
                    else totalFailures.decrementAndGet();
                    totalExecutionTime.addAndGet(-r.executionTime);
                }
            }
        }
    }

    // ==================== 数据结构 ====================

    /** 工具调用记录 */
    public static class ToolCallRecord {
        public final String toolName;
        public final String arguments;
        public final boolean success;
        public final long executionTime;
        public final String resultSummary;
        public final long timestamp;

        public ToolCallRecord(String toolName, String arguments, boolean success,
                              long executionTime, String resultSummary, long timestamp) {
            this.toolName = toolName;
            this.arguments = arguments;
            this.success = success;
            this.executionTime = executionTime;
            this.resultSummary = resultSummary;
            this.timestamp = timestamp;
        }
    }

    /** 工具统计 */
    public static class ToolStats {
        public final String toolName;
        public final int totalCalls;
        public final int successCount;
        public final double successRate;
        public final double avgExecutionTime;
        public final double timeoutRate;
        public final long lastCallTimestamp;

        public ToolStats(String toolName, int totalCalls, int successCount,
                         double successRate, double avgExecutionTime,
                         double timeoutRate, long lastCallTimestamp) {
            this.toolName = toolName;
            this.totalCalls = totalCalls;
            this.successCount = successCount;
            this.successRate = successRate;
            this.avgExecutionTime = avgExecutionTime;
            this.timeoutRate = timeoutRate;
            this.lastCallTimestamp = lastCallTimestamp;
        }

        @Override
        public String toString() {
            return String.format("%s: 调用%d次, 成功率%.0f%%, 平均%.0fms",
                toolName, totalCalls, successRate * 100, avgExecutionTime);
        }
    }

    /** 工具组合模式 */
    public static class ToolPattern {
        public final String toolA;
        public final String toolB;
        public final int count;

        public ToolPattern(String toolA, String toolB, int count) {
            this.toolA = toolA;
            this.toolB = toolB;
            this.count = count;
        }

        @Override
        public String toString() {
            return toolA + " + " + toolB + " (共现" + count + "次)";
        }
    }
}

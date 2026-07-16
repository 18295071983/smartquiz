package com.oilquiz.app.util;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * @deprecated Use {@link AILogger} for basic file logging. 
 * Visual/structured logging functionality will be migrated to AILogger.
 */
@Deprecated
public class AILogger2 {
    private static final String TAG = "AILogger2";
    private static final SimpleDateFormat TIMESTAMP_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
    private static final SimpleDateFormat FULL_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    
    // 日志级别
    public enum LogLevel {
        VERBOSE("🔍", "#9E9E9E", "VERBOSE"),
        DEBUG("🐛", "#4CAF50", "DEBUG"),
        INFO("ℹ️", "#8B5CF6", "INFO"),
        SUCCESS("✅", "#4CAF50", "SUCCESS"),
        WARNING("⚠️", "#FF9800", "WARN"),
        ERROR("❌", "#F44336", "ERROR"),
        THINKING("🧠", "#9C27B0", "THINKING"),
        ACTION("⚡", "#FF5722", "ACTION"),
        TOOL_CALL("🛠️", "#00BCD4", "TOOL"),
        RESULT("📊", "#3F51B5", "RESULT"),
        STEP("➡️", "#607D8B", "STEP");
        
        public final String icon;
        public final String colorHex;
        public final String name;
        
        LogLevel(String icon, String colorHex, String name) {
            this.icon = icon;
            this.colorHex = colorHex;
            this.name = name;
        }
    }
    
    // 日志分类
    public enum LogCategory {
        GENERAL("通用"),
        AI_SERVICE("AI服务"),
        AGENT_ENGINE("Agent引擎"),
        DEEP_THINKING("深度思考"),
        SKILL_SYSTEM("技能系统"),
        TOOL_EXECUTION("工具执行"),
        CONVERSATION("对话管理"),
        PERFORMANCE("性能监控"),
        MEMORY("内存管理"),
        ERROR_RECOVERY("错误恢复");
        
        public final String displayName;
        
        LogCategory(String displayName) {
            this.displayName = displayName;
        }
    }
    
    // 可视化日志条目
    public static class VisualLogEntry {
        public final long id;
        public final long timestamp;
        public final String formattedTime;
        public LogLevel level;
        public final LogCategory category;
        public final String tag;
        public String message;
        public JSONObject metadata;
        public long durationMs;
        public int progressPercent;
        public final String parentStepId;
        public final List<VisualLogEntry> subEntries;
        
        public VisualLogEntry(long id, long timestamp, LogLevel level, LogCategory category,
                             String tag, String message, JSONObject metadata, 
                             long durationMs, int progressPercent, String parentStepId) {
            this.id = id;
            this.timestamp = timestamp;
            this.formattedTime = TIMESTAMP_FORMAT.format(new Date(timestamp));
            this.level = level;
            this.category = category;
            this.tag = tag;
            this.message = message;
            this.metadata = metadata != null ? metadata : new JSONObject();
            this.durationMs = durationMs;
            this.progressPercent = progressPercent;
            this.parentStepId = parentStepId;
            this.subEntries = new CopyOnWriteArrayList<>();
        }
        
        public String toMarkdown() {
            StringBuilder md = new StringBuilder();
            
            // 标题行：图标 + 时间 + 级别 + 消息
            md.append("**").append(level.icon).append(" [").append(formattedTime).append("]** ");
            md.append("`").append(level.name).append("` **").append(message).append("**\n\n");
            
            // 元数据展示
            if (metadata.length() > 0) {
                md.append("| 属性 | 值 |\n|------|-----|\n");
                for (java.util.Iterator<String> it = metadata.keys(); it.hasNext(); ) {
                    String key = it.next();
                    try {
                        Object value = metadata.get(key);
                        md.append("| `").append(key).append("` | ").append(value.toString()).append(" |\n");
                    } catch (Exception e) {
                    }
                }
                md.append("\n");
            }
            
            // 进度条
            if (progressPercent > 0 && progressPercent < 100) {
                int filled = progressPercent / 10;
                int empty = 10 - filled;
                md.append("[");
                for (int i = 0; i < filled; i++) md.append("█");
                for (int i = 0; i < empty; i++) md.append("░");
                md.append("] ").append(progressPercent).append("%\n\n");
            }
            
            // 耗时信息
            if (durationMs > 0) {
                if (durationMs < 1000) {
                    md.append("*耗时: ").append(durationMs).append("ms*\n");
                } else {
                    md.append(String.format(Locale.getDefault(), "*耗时: %.2fs*", durationMs / 1000.0)).append("\n");
                }
            }
            
            // 子步骤
            if (!subEntries.isEmpty()) {
                md.append("\n**子步骤:**\n");
                for (VisualLogEntry sub : subEntries) {
                    md.append("- ").append(sub.level.icon).append(" ").append(sub.message);
                    if (sub.durationMs > 0) {
                        md.append(" (").append(sub.durationMs).append("ms)");
                    }
                    md.append("\n");
                }
            }
            
            return md.toString();
        }
        
        public String toShortText() {
            return level.icon + " [" + formattedTime + "] " + message;
        }
        
        public String getColoredIcon() {
            return level.icon;
        }
        
        public int getColor() {
            try {
                return Color.parseColor(level.colorHex);
            } catch (Exception e) {
                return Color.GRAY;
            }
        }
    }
    
    // 监听器接口
    public interface OnVisualLogListener {
        void onNewLog(VisualLogEntry entry);
        void onLogUpdated(VisualLogEntry entry);
        void onTimelineCleared();
    }
    
    // 单例实例
    private static volatile AILogger2 instance;
    private static final ReentrantLock initLock = new ReentrantLock();
    
    // 核心数据结构
    private final CopyOnWriteArrayList<VisualLogEntry> logEntries;
    private final Map<String, VisualLogEntry> activeSteps;
    private final List<OnVisualLogListener> listeners;
    private final AtomicInteger idGenerator;
    private final Handler mainHandler;
    
    // 统计信息
    private final Map<LogLevel, AtomicInteger> levelCounts;
    private final Map<LogCategory, AtomicInteger> categoryCounts;
    private long sessionStartTime;
    private long lastLogTime;
    
    // 配置选项
    private boolean enabled = true;
    private int maxEntries = 500;
    private LogLevel minDisplayLevel = LogLevel.DEBUG;
    
    // 私有构造函数
    private AILogger2() {
        logEntries = new CopyOnWriteArrayList<>();
        activeSteps = new HashMap<>();
        listeners = new ArrayList<>();
        idGenerator = new AtomicInteger(1);
        mainHandler = new Handler(Looper.getMainLooper());
        levelCounts = new HashMap<>();
        categoryCounts = new HashMap<>();
        sessionStartTime = System.currentTimeMillis();
        lastLogTime = sessionStartTime;
        
        for (LogLevel level : LogLevel.values()) {
            levelCounts.put(level, new AtomicInteger(0));
        }
        for (LogCategory category : LogCategory.values()) {
            categoryCounts.put(category, new AtomicInteger(0));
        }
    }
    
    /**
     * 获取单例实例
     * @return AILogger2实例
     */
    public static AILogger2 getInstance() {
        if (instance == null) {
            initLock.lock();
            try {
                if (instance == null) {
                    instance = new AILogger2();
                }
            } finally {
                initLock.unlock();
            }
        }
        return instance;
    }
    
    /**
     * 初始化日志系统
     * @param context 应用上下文
     */
    public void init(Context context) {
        enabled = true;
        log(LogLevel.INFO, LogCategory.GENERAL, TAG, "AILogger2 可视化日志系统初始化完成", null);
    }
    
    /**
     * 记录可视化日志
     */
    public VisualLogEntry log(LogLevel level, LogCategory category, String tag, 
                            String message, JSONObject metadata) {
        if (!enabled || level.ordinal() < minDisplayLevel.ordinal()) {
            return null;
        }
        
        long now = System.currentTimeMillis();
        long entryId = idGenerator.getAndIncrement();
        
        VisualLogEntry entry = new VisualLogEntry(
            entryId,
            now,
            level,
            category,
            tag,
            message,
            metadata,
            0,
            0,
            null
        );
        
        addEntry(entry);
        return entry;
    }
    
    /**
     * 开始一个步骤（用于跟踪长时间操作）
     */
    public VisualLogEntry startStep(LogCategory category, String tag, String stepName, 
                                   String description, JSONObject metadata) {
        VisualLogEntry step = log(
            LogLevel.STEP,
            category,
            tag,
            stepName + ": " + description,
            metadata
        );
        
        if (step != null) {
            step.progressPercent = 0;
            activeSteps.put(step.id + "", step);
        }
        
        return step;
    }
    
    /**
     * 更新步骤进度
     */
    public void updateStepProgress(VisualLogEntry step, int progressPercent, 
                                    String statusMessage, JSONObject additionalData) {
        if (step == null) return;
        
        step.progressPercent = Math.min(100, Math.max(0, progressPercent));
        step.durationMs = System.currentTimeMillis() - step.timestamp;
        
        if (statusMessage != null && !statusMessage.isEmpty()) {
            step.message = statusMessage;
        }
        
        if (additionalData != null) {
            try {
                for (java.util.Iterator<String> it = additionalData.keys(); it.hasNext(); ) {
                    String key = it.next();
                    step.metadata.put(key, additionalData.get(key));
                }
            } catch (Exception e) {
            }
        }
        
        notifyListenersUpdate(step);
    }
    
    /**
     * 完成步骤
     */
    public void completeStep(VisualLogEntry step, String resultMessage, 
                              boolean success, JSONObject resultData) {
        if (step == null) return;
        
        step.durationMs = System.currentTimeMillis() - step.timestamp;
        step.progressPercent = 100;
        
        if (resultMessage != null) {
            step.message += "\n→ " + resultMessage;
        }
        
        step.level = success ? LogLevel.SUCCESS : LogLevel.ERROR;
        
        if (resultData != null) {
            try {
                for (java.util.Iterator<String> it = resultData.keys(); it.hasNext(); ) {
                    String key = it.next();
                    step.metadata.put("result_" + key, resultData.get(key));
                }
            } catch (Exception e) {
            }
        }
        
        activeSteps.remove(step.id + "");
        notifyListenersUpdate(step);
    }
    
    /**
     * 记录思考过程
     */
    public VisualLogEntry logThinking(String tag, String thoughtContent) {
        JSONObject meta = new JSONObject();
        try {
            meta.put("type", "thinking");
            meta.put("content_length", thoughtContent.length());
        } catch (Exception e) {}
        
        return log(LogLevel.THINKING, LogCategory.DEEP_THINKING, tag, thoughtContent, meta);
    }
    
    /**
     * 记录工具调用
     */
    public VisualLogEntry logToolCall(String toolName, String action, 
                                       Map<String, Object> params) {
        JSONObject meta = new JSONObject();
        try {
            meta.put("tool_name", toolName);
            meta.put("action", action);
            if (params != null) {
                for (Map.Entry<String, Object> param : params.entrySet()) {
                    meta.put("param_" + param.getKey(), param.getValue().toString());
                }
            }
        } catch (Exception e) {}
        
        String message = "调用工具: " + toolName + "." + action;
        return log(LogLevel.TOOL_CALL, LogCategory.TOOL_EXECUTION, "Tool:" + toolName, message, meta);
    }
    
    /**
     * 记录Agent执行步骤
     */
    public VisualLogEntry logAgentStep(int stepNum, int totalSteps, String stepType,
                                        String description, Object data) {
        JSONObject meta = new JSONObject();
        try {
            meta.put("step_number", stepNum);
            meta.put("total_steps", totalSteps);
            meta.put("step_type", stepType);
            meta.put("progress", (int)((double)stepNum / totalSteps * 100));
            if (data != null) {
                meta.put("data", data.toString());
            }
        } catch (Exception e) {}
        
        String message = String.format(Locale.getDefault(), 
            "步骤 %d/%d [%s]: %s", stepNum, totalSteps, stepType, description);
        
        return log(LogLevel.ACTION, LogCategory.AGENT_ENGINE, "Agent:Step" + stepNum, message, meta);
    }
    
    /**
     * 记录性能指标
     */
    public void logPerformance(String metricName, long value, String unit) {
        JSONObject meta = new JSONObject();
        try {
            meta.put("metric", metricName);
            meta.put("value", value);
            meta.put("unit", unit);
        } catch (Exception e) {}
        
        log(LogLevel.INFO, LogCategory.PERFORMANCE, "Perf:" + metricName, 
            metricName + " = " + value + " " + unit, meta);
    }
    
    /**
     * 生成时间线可视化（Markdown格式）
     */
    public String generateTimelineMarkdown(int maxEntries) {
        StringBuilder timeline = new StringBuilder();
        timeline.append("# 📊 AI 执行时间线\n\n");
        timeline.append("---\n\n");
        
        List<VisualLogEntry> entries = getRecentEntries(maxEntries);
        
        long startTime = entries.isEmpty() ? System.currentTimeMillis() : 
                          entries.get(entries.size() - 1).timestamp;
        
        for (int i = entries.size() - 1; i >= 0; i--) {
            VisualLogEntry entry = entries.get(i);
            long elapsed = entry.timestamp - startTime;
            
            timeline.append(entry.level.icon).append(" **[").append(entry.formattedTime)
                   .append("] (+").append(formatDuration(elapsed)).append(")** ")
                   .append("`").append(entry.category.displayName).append("`")
                   .append("\n**").append(entry.message).append("**\n\n");
            
            if (!entry.subEntries.isEmpty()) {
                timeline.append("<details>\n<summary>子步骤</summary>\n\n");
                for (VisualLogEntry sub : entry.subEntries) {
                    timeline.append("- ").append(sub.level.icon).append(" ")
                           .append(sub.message);
                    if (sub.durationMs > 0) {
                        timeline.append(" `").append(formatDuration(sub.durationMs)).append("`");
                    }
                    timeline.append("\n");
                }
                timeline.append("</details>\n\n");
            }
        }
        
        // 统计摘要
        timeline.append("---\n\n## 📈 统计摘要\n\n");
        timeline.append(generateStatsMarkdown());
        
        return timeline.toString();
    }
    
    /**
     * 生成统计报告（Markdown格式）
     */
    public String generateStatsMarkdown() {
        StringBuilder stats = new StringBuilder();
        
        stats.append("| 类别 | 数量 | 占比 |\n|------|------|------|\n");
        int total = logEntries.size();
        
        for (LogLevel level : LogLevel.values()) {
            int count = levelCounts.get(level).get();
            double percent = total > 0 ? (count * 100.0 / total) : 0;
            stats.append("| ").append(level.icon).append(" ").append(level.name)
                  .append(" | ").append(count)
                  .append(String.format(Locale.getDefault(), " | %.1f%%", percent))
                  .append(" |\n");
        }
        
        stats.append("\n| 分类 | 数量 | 占比 |\n|------|------|------|\n");
        for (LogCategory cat : LogCategory.values()) {
            int count = categoryCounts.get(cat).get();
            double percent = total > 0 ? (count * 100.0 / total) : 0;
            stats.append("| ").append(cat.displayName)
                  .append(" | ").append(count)
                  .append(String.format(Locale.getDefault(), " | %.1f%%", percent))
                  .append(" |\n");
        }
        
        long sessionDuration = System.currentTimeMillis() - sessionStartTime;
        stats.append("\n**会话时长**: ").append(formatDuration(sessionDuration)).append("\n");
        stats.append("**总日志数**: ").append(total).append("\n");
        stats.append("**活跃步骤**: ").append(activeSteps.size()).append("\n");
        
        return stats.toString();
    }
    
    // ========== 监听器管理 ==========
    
    public void addListener(OnVisualLogListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }
    
    public void removeListener(OnVisualLogListener listener) {
        listeners.remove(listener);
    }
    
    // ========== 查询方法 ==========
    
    public List<VisualLogEntry> getAllEntries() {
        return new ArrayList<>(logEntries);
    }
    
    public List<VisualLogEntry> getRecentEntries(int count) {
        if (logEntries.size() <= count) {
            return new ArrayList<>(logEntries);
        }
        return new ArrayList<>(logEntries.subList(logEntries.size() - count, logEntries.size()));
    }
    
    public List<VisualLogEntry> getEntriesByLevel(LogLevel level) {
        List<VisualLogEntry> filtered = new ArrayList<>();
        for (VisualLogEntry entry : logEntries) {
            if (entry.level == level) {
                filtered.add(entry);
            }
        }
        return filtered;
    }
    
    public List<VisualLogEntry> getEntriesByCategory(LogCategory category) {
        List<VisualLogEntry> filtered = new ArrayList<>();
        for (VisualLogEntry entry : logEntries) {
            if (entry.category == category) {
                filtered.add(entry);
            }
        }
        return filtered;
    }
    
    public List<VisualLogEntry> searchEntries(String keyword) {
        List<VisualLogEntry> results = new ArrayList<>();
        Pattern pattern = Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE);
        
        for (VisualLogEntry entry : logEntries) {
            if (pattern.matcher(entry.message).find() || 
                pattern.matcher(entry.tag).find()) {
                results.add(entry);
            }
        }
        return results;
    }
    
    public List<VisualLogEntry> getActiveSteps() {
        return new ArrayList<>(activeSteps.values());
    }
    
    // ========== 管理方法 ==========
    
    public void clearAll() {
        logEntries.clear();
        activeSteps.clear();
        for (AtomicInteger count : levelCounts.values()) {
            count.set(0);
        }
        for (AtomicInteger count : categoryCounts.values()) {
            count.set(0);
        }
        sessionStartTime = System.currentTimeMillis();
        
        notifyListenersCleared();
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public void setMinDisplayLevel(LogLevel level) {
        this.minDisplayLevel = level;
    }
    
    // ========== 内部方法 ==========
    
    private void addEntry(VisualLogEntry entry) {
        // 更新统计
        levelCounts.get(entry.level).incrementAndGet();
        categoryCounts.get(entry.category).incrementAndGet();
        lastLogTime = entry.timestamp;
        
        // 添加到列表
        logEntries.add(entry);
        
        // 限制最大数量
        while (logEntries.size() > maxEntries) {
            logEntries.remove(0);
        }
        
        // 通知监听器
        notifyListenersNew(entry);
        
        // 同时写入原始AILogger
        AILogger.i(entry.tag, "[" + entry.level.name + "] " + entry.message);
    }
    
    private void notifyListenersNew(VisualLogEntry entry) {
        mainHandler.post(() -> {
            for (OnVisualLogListener listener : listeners) {
                try {
                    listener.onNewLog(entry);
                } catch (Exception e) {
                    // 防止监听器异常影响日志系统
                }
            }
        });
    }
    
    private void notifyListenersUpdate(VisualLogEntry entry) {
        mainHandler.post(() -> {
            for (OnVisualLogListener listener : listeners) {
                try {
                    listener.onLogUpdated(entry);
                } catch (Exception e) {
                    // 忽略异常
                }
            }
        });
    }
    
    private void notifyListenersCleared() {
        mainHandler.post(() -> {
            for (OnVisualLogListener listener : listeners) {
                try {
                    listener.onTimelineCleared();
                } catch (Exception e) {
                    // 忽略异常
                }
            }
        });
    }
    
    private String formatDuration(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        } else if (ms < 60000) {
            return String.format(Locale.getDefault(), "%.1fs", ms / 1000.0);
        } else {
            long seconds = ms / 1000;
            long minutes = seconds / 60;
            seconds %= 60;
            return String.format(Locale.getDefault(), "%dm%ds", minutes, seconds);
        }
    }
}

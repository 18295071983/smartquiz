package com.oilquiz.app.ai.agent.software.engine;

import android.content.Context;

import com.oilquiz.app.ai.agent.software.model.ExecutionResult;
import com.oilquiz.app.ai.agent.software.model.Task;
import com.oilquiz.app.ai.agent.software.model.TaskPlan;
import com.oilquiz.app.ai.agent.software.model.TaskResult;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ExecutionEngine - 执行引擎
 *
 * 管理工具调用和任务执行，支持：
 * 1. 重试机制（指数退避，最多 N 次）
 * 2. 超时控制（单工具默认 30s）
 * 3. 并行执行（无依赖任务并行，按依赖分层调度）
 * 4. 结果引用（${taskId.result} 引用前序任务结果）
 *
 * 硬件层调用: LLM.generate(), Tool.execute()
 */
public class ExecutionEngine {

    private static final String TAG = "ExecutionEngine";
    /** 重试基础延迟（毫秒），实际延迟 = base * 2^retryCount */
    private static final long RETRY_BASE_DELAY_MS = 1000;
    /** 并行执行最大线程数 */
    private static final int MAX_PARALLEL_THREADS = 4;
    /** 结果引用格式：${taskId.result} 或 ${taskId.result.field} */
    private static final Pattern RESULT_REF_PATTERN =
            Pattern.compile("\\$\\{(\\w+)\\.result(?:\\.(\\w+))?\\}");

    private final AIService aiService;
    private final AIToolManager toolManager;
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    /** 任务层并行执行池 */
    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_PARALLEL_THREADS);
    /** 工具调用专用池（与并行执行池隔离，避免死锁） */
    private final ExecutorService toolExecutor = Executors.newCachedThreadPool();

    public interface ToolCallback {
        void onToolCallStart(String toolName, String args);
        void onToolCallComplete(String toolName, boolean success, String result);
    }

    public ExecutionEngine(Context context, AIService aiService) {
        this.aiService = aiService;
        this.toolManager = AIToolManager.getInstance(context);
    }

    /**
     * 执行任务计划
     * 按依赖关系分层，同层无依赖任务并行执行
     */
    public ExecutionResult execute(TaskPlan taskPlan, ToolCallback callback) {
        ExecutionResult result = new ExecutionResult();
        List<Task> tasks = taskPlan.getTasks();

        if (tasks.isEmpty()) {
            return result;
        }

        // 按依赖关系分层调度
        List<List<Task>> layers = topologicalSort(tasks);
        AILogger.i(TAG, "Task layers: " + layers.size() + " (total " + tasks.size() + " tasks)");

        for (List<Task> layer : layers) {
            if (isCancelled.get()) {
                result.setError("执行已取消");
                break;
            }

            // 解析本层任务参数中的结果引用
            for (Task task : layer) {
                resolveResultReferences(task, result);
            }

            if (layer.size() == 1) {
                // 单任务直接串行执行
                TaskResult tr = executeTaskWithRetry(layer.get(0), callback);
                result.addTaskResult(layer.get(0).getId(), tr);
                if (tr.isError() && layer.get(0).isCritical()) {
                    result.setError("关键任务失败: " + layer.get(0).getDescription());
                    break;
                }
            } else {
                // 多任务并行执行
                List<Callable<TaskResult>> callables = new ArrayList<>();
                for (Task task : layer) {
                    callables.add(() -> executeTaskWithRetry(task, callback));
                }
                try {
                    List<Future<TaskResult>> futures = executor.invokeAll(callables);
                    boolean criticalFailed = false;
                    for (int i = 0; i < layer.size(); i++) {
                        Task task = layer.get(i);
                        TaskResult tr;
                        try {
                            tr = futures.get(i).get();
                        } catch (Exception e) {
                            tr = TaskResult.error("任务执行异常: " + e.getMessage());
                        }
                        result.addTaskResult(task.getId(), tr);
                        if (tr.isError() && task.isCritical()) {
                            result.setError("关键任务失败: " + task.getDescription());
                            criticalFailed = true;
                        }
                    }
                    if (criticalFailed) break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    result.setError("并行执行被中断: " + e.getMessage());
                    break;
                }
            }
        }

        return result;
    }

    /**
     * 拓扑排序：按依赖关系将任务分层，同层任务可并行执行
     */
    private List<List<Task>> topologicalSort(List<Task> tasks) {
        List<List<Task>> layers = new ArrayList<>();
        Map<String, Task> taskMap = new HashMap<>();
        Map<String, Integer> remainingDeps = new HashMap<>();
        for (Task t : tasks) {
            taskMap.put(t.getId(), t);
            // 只统计在本批次任务中存在的依赖
            int count = 0;
            for (String dep : t.getDependsOn()) {
                if (taskMap.containsKey(dep) || containsId(tasks, dep)) {
                    count++;
                }
            }
            remainingDeps.put(t.getId(), count);
        }

        List<Task> remaining = new ArrayList<>(tasks);
        while (!remaining.isEmpty()) {
            List<Task> currentLayer = new ArrayList<>();
            for (Task t : remaining) {
                if (remainingDeps.get(t.getId()) == 0) {
                    currentLayer.add(t);
                }
            }
            if (currentLayer.isEmpty()) {
                // 存在循环依赖或依赖缺失，强制将剩余任务作为最后一层
                AILogger.w(TAG, "Circular or unresolved dependencies detected, forcing remaining "
                        + remaining.size() + " tasks into one layer");
                currentLayer = new ArrayList<>(remaining);
            }
            layers.add(currentLayer);
            for (Task done : currentLayer) {
                remaining.remove(done);
                for (Task t : remaining) {
                    if (t.getDependsOn().contains(done.getId())) {
                        remainingDeps.put(t.getId(), remainingDeps.get(t.getId()) - 1);
                    }
                }
            }
        }
        return layers;
    }

    private boolean containsId(List<Task> tasks, String id) {
        for (Task t : tasks) {
            if (t.getId().equals(id)) return true;
        }
        return false;
    }

    /**
     * 解析参数中的结果引用 ${taskId.result} 或 ${taskId.result.field}
     */
    private void resolveResultReferences(Task task, ExecutionResult completedResults) {
        Map<String, Object> params = task.getParameters();
        if (params == null || params.isEmpty()) return;

        Map<String, Object> resolved = new HashMap<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                resolved.put(entry.getKey(), resolveRefsInString((String) value, completedResults));
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        task.getParameters().clear();
        task.getParameters().putAll(resolved);
    }

    private String resolveRefsInString(String s, ExecutionResult completedResults) {
        if (s == null || !s.contains("${")) return s;
        Matcher m = RESULT_REF_PATTERN.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String refTaskId = m.group(1);
            String field = m.group(2);
            TaskResult tr = completedResults.getTaskResult(refTaskId);
            String replacement = "";
            if (tr != null && tr.isSuccess() && tr.getResult() != null) {
                String resultStr = tr.getResult();
                if (field != null) {
                    // 尝试从 JSON 结果中提取字段
                    replacement = extractJsonField(resultStr, field);
                } else {
                    replacement = resultStr;
                }
            } else {
                AILogger.w(TAG, "Unresolved result reference: " + m.group(0));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 简单的 JSON 字段提取（避免引入完整 JSON 解析器开销） */
    private String extractJsonField(String json, String field) {
        if (json == null || field == null) return "";
        try {
            Pattern p = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]*)\"");
            Matcher m = p.matcher(json);
            if (m.find()) return m.group(1);
            // 尝试数字字段
            p = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*([0-9.]+)");
            m = p.matcher(json);
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {
        }
        return "";
    }

    /**
     * 带重试和超时的任务执行
     */
    private TaskResult executeTaskWithRetry(Task task, ToolCallback callback) {
        if (task.needsTool() && task.getToolName() != null) {
            return executeWithToolRetry(task, callback);
        } else {
            return executeWithLLM(task);
        }
    }

    /**
     * 使用工具执行任务（带重试 + 超时）
     */
    private TaskResult executeWithToolRetry(Task task, ToolCallback callback) {
        String toolName = task.getToolName();
        Map<String, Object> params = task.getParameters();
        int maxRetries = task.getMaxRetries();
        int timeoutMs = task.getTimeoutMs();

        AILogger.i(TAG, "Executing tool: " + toolName + " with params: " + params
                + " (maxRetries=" + maxRetries + ", timeout=" + timeoutMs + "ms)");

        Exception lastError = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            if (isCancelled.get()) {
                return TaskResult.error("执行已取消");
            }

            if (callback != null) {
                callback.onToolCallStart(toolName, params.toString());
            }

            try {
                // 使用 Future 实现超时控制（工具调用使用独立线程池避免死锁）
                Future<String> future = toolExecutor.submit(() -> executeToolByName(toolName, params));
                String resultStr = future.get(timeoutMs, TimeUnit.MILLISECONDS);

                if (callback != null) {
                    callback.onToolCallComplete(toolName, true, resultStr);
                }
                AILogger.i(TAG, "Tool " + toolName + " succeeded on attempt " + attempt);
                return TaskResult.success(resultStr);

            } catch (TimeoutException e) {
                lastError = new Exception("工具执行超时（" + (timeoutMs / 1000) + "s）");
                AILogger.w(TAG, "Tool " + toolName + " timeout on attempt " + attempt);
            } catch (Exception e) {
                lastError = e;
                AILogger.w(TAG, "Tool " + toolName + " failed on attempt " + attempt
                        + ": " + e.getMessage());
            }

            // 通知本次失败
            if (callback != null) {
                callback.onToolCallComplete(toolName, false,
                        lastError != null ? lastError.getMessage() : "未知错误");
            }

            // 指数退避等待（最后一次不等待）
            if (attempt < maxRetries) {
                long delay = RETRY_BASE_DELAY_MS * (1L << (attempt - 1));
                AILogger.i(TAG, "Retrying in " + delay + "ms...");
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return TaskResult.error("重试被中断: " + ie.getMessage());
                }
            }
        }

        String errorMsg = lastError != null ? lastError.getMessage() : "工具执行失败";
        AILogger.e(TAG, "Tool " + toolName + " failed after " + maxRetries + " attempts: " + errorMsg);
        return TaskResult.error("工具执行失败（重试 " + maxRetries + " 次）: " + errorMsg);
    }

    /**
     * 使用 LLM 执行任务
     */
    private TaskResult executeWithLLM(Task task) {
        try {
            String prompt = buildLLMTaskPrompt(task);
            String response = com.oilquiz.app.ai.jni.LlamaHelper.generate(prompt, 500, 0.7f);
            return TaskResult.success(response);
        } catch (Exception e) {
            return TaskResult.error("LLM 执行失败: " + e.getMessage());
        }
    }

    private String buildLLMTaskPrompt(Task task) {
        StringBuilder sb = new StringBuilder();
        sb.append("请完成以下任务，直接给出结果，不要重复任务描述。\n\n");
        sb.append("【任务】\n").append(task.getDescription()).append("\n\n");
        if (task.getEntity() != null && !task.getEntity().isEmpty()) {
            sb.append("【关键实体】\n").append(task.getEntity()).append("\n\n");
        }
        sb.append("【输出】\n");
        return sb.toString();
    }

    /**
     * 根据工具名称执行工具
     * 使用 AIToolManager 中注册的真实工具名称
     */
    private String executeToolByName(String toolName, Map<String, Object> params) throws Exception {
        AIToolResult result = toolManager.executeTool(toolName, params);

        if (result != null && result.isSuccess()) {
            Object resultObj = result.getResult();
            String resultStr = resultObj != null ? resultObj.toString() : null;
            AILogger.i(TAG, "Tool result: " + (resultStr != null
                    ? resultStr.substring(0, Math.min(100, resultStr.length())) : "null"));
            return resultStr != null ? resultStr : "工具执行成功但返回空结果";
        } else {
            String errorMsg = result != null ? result.getErrorMessage() : "工具执行失败";
            AILogger.e(TAG, "Tool execution failed: " + errorMsg);
            throw new Exception(errorMsg);
        }
    }

    /**
     * 取消执行
     */
    public void cancel() {
        isCancelled.set(true);
        executor.shutdownNow();
        toolExecutor.shutdownNow();
    }
}

package com.oilquiz.app.ai.inference;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class InferenceQueue {
    private static final String TAG = "InferenceQueue";
    private static final int MAX_CONCURRENT_INFERENCES = 4;
    private static final int MAX_QUEUE_SIZE = 100;

    private final Context context;
    private final ExecutorService executor;
    private final PriorityBlockingQueue<InferenceTask> taskQueue;
    private final Map<String, InferenceTask> runningTasks;
    private final Handler mainHandler;

    private final AtomicInteger currentRunningCount = new AtomicInteger(0);
    private final AtomicLong totalProcessedCount = new AtomicLong(0);
    private final AtomicLong totalCancelledCount = new AtomicLong(0);

    public InferenceQueue(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newFixedThreadPool(MAX_CONCURRENT_INFERENCES);
        this.taskQueue = new PriorityBlockingQueue<>(11, (t1, t2) -> Integer.compare(t2.priority.value, t1.priority.value));
        this.runningTasks = new ConcurrentHashMap<>();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public String submitTask(String modelId, String prompt, InferenceParams params, TaskPriority priority, InferenceCallback callback) {
        String taskId = generateTaskId();

        InferenceTask task = new InferenceTask(taskId, modelId, prompt, params, priority, callback);

        if (taskQueue.size() >= MAX_QUEUE_SIZE) {
            Log.w(TAG, "Queue full, rejecting task: " + taskId);
            if (callback != null) {
                callback.onError(new RuntimeException("Queue is full"));
            }
            return taskId;
        }

        taskQueue.offer(task);
        Log.d(TAG, "Task submitted: " + taskId + " (queue size: " + taskQueue.size() + ")");

        processQueue();

        return taskId;
    }

    public List<String> submitBatch(List<BatchRequest> requests, BatchCallback callback) {
        List<String> taskIds = new ArrayList<>();
        for (BatchRequest request : requests) {
            String taskId = submitTask(request.modelId, request.prompt, request.params, request.priority, null);
            taskIds.add(taskId);
        }
        return taskIds;
    }

    private void processQueue() {
        while (currentRunningCount.get() < MAX_CONCURRENT_INFERENCES) {
            InferenceTask task = taskQueue.poll();
            if (task == null) break;
            executeTask(task);
        }
    }

    private void executeTask(InferenceTask task) {
        currentRunningCount.incrementAndGet();
        task.state = TaskState.RUNNING;
        task.startTime = System.currentTimeMillis();
        runningTasks.put(task.id, task);

        executor.execute(() -> {
            try {
                String result = performInference(task);
                task.endTime = System.currentTimeMillis();
                task.state = TaskState.COMPLETED;
                totalProcessedCount.incrementAndGet();

                if (task.callback != null) {
                    mainHandler.post(() -> task.callback.onComplete(result));
                }
            } catch (InterruptedException e) {
                task.state = TaskState.CANCELLED;
                totalCancelledCount.incrementAndGet();
                if (task.callback != null) {
                    mainHandler.post(() -> task.callback.onError(e));
                }
            } catch (Exception e) {
                task.state = TaskState.FAILED;
                task.endTime = System.currentTimeMillis();
                if (task.callback != null) {
                    mainHandler.post(() -> task.callback.onError(e));
                }
            } finally {
                runningTasks.remove(task.id);
                currentRunningCount.decrementAndGet();
                processQueue();
            }
        });
    }

    private String performInference(InferenceTask task) throws InterruptedException {
        Log.d(TAG, "Performing inference for task: " + task.id);

        Thread.sleep(100);

        StringBuilder result = new StringBuilder();
        int maxTokens = Math.min(task.params.nPredict, 50);

        for (int i = 0; i < maxTokens; i++) {
            if (task.state == TaskState.CANCELLED) {
                throw new InterruptedException("Task cancelled");
            }

            String token = "token_" + i + " ";
            result.append(token);

            if (task.params.streaming && i % 5 == 0 && task.callback != null) {
                final int index = i;
                mainHandler.post(() -> {
                    task.callback.onToken(token, index);
                    task.callback.onProgress((index * 100) / maxTokens);
                });
            }

            Thread.sleep(10);
        }

        return result.toString();
    }

    public boolean cancelTask(String taskId) {
        InferenceTask task = runningTasks.get(taskId);
        if (task != null) {
            task.state = TaskState.CANCELLED;
            Log.d(TAG, "Task cancelled: " + taskId);
            return true;
        }

        return taskQueue.removeIf(t -> t.id.equals(taskId));
    }

    public void cancelAll() {
        taskQueue.clear();
        for (String taskId : new ArrayList<>(runningTasks.keySet())) {
            cancelTask(taskId);
        }
        Log.d(TAG, "All tasks cancelled");
    }

    public TaskStatusInfo getTaskStatus(String taskId) {
        InferenceTask task = runningTasks.get(taskId);
        if (task != null) {
            return new TaskStatusInfo(
                task.id,
                task.state,
                calculateProgress(task),
                -1,
                System.currentTimeMillis() - task.submitTime,
                task.startTime > 0 ? System.currentTimeMillis() - task.startTime : 0
            );
        }

        int position = findQueuePosition(taskId);
        if (position >= 0) {
            return new TaskStatusInfo(taskId, TaskState.QUEUED, 0, position, 0, 0);
        }

        return null;
    }

    private int calculateProgress(InferenceTask task) {
        switch (task.state) {
            case QUEUED:
                return 0;
            case RUNNING:
                long elapsed = System.currentTimeMillis() - task.startTime;
                long estimatedTotal = task.params.nPredict * 50L;
                return (int) (elapsed * 100 / estimatedTotal);
            case COMPLETED:
                return 100;
            default:
                return 0;
        }
    }

    private int findQueuePosition(String taskId) {
        int position = 0;
        for (InferenceTask task : taskQueue) {
            if (task.id.equals(taskId)) {
                return position;
            }
            position++;
        }
        return -1;
    }

    public int getQueueSize() {
        return taskQueue.size();
    }

    public int getRunningCount() {
        return currentRunningCount.get();
    }

    public QueueStatistics getStatistics() {
        return new QueueStatistics(
            taskQueue.size(),
            currentRunningCount.get(),
            totalProcessedCount.get(),
            totalCancelledCount.get()
        );
    }

    private String generateTaskId() {
        return "task_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 1000);
    }

    public void cleanup() {
        cancelAll();
        executor.shutdown();
    }

    public interface InferenceCallback {
        void onToken(String token, int index);
        void onComplete(String result);
        void onError(Throwable error);
        void onProgress(int progress);
    }

    public interface BatchCallback {
        void onBatchComplete(Map<String, String> results);
        void onBatchError(Map<String, Throwable> errors);
        void onBatchProgress(int completed, int total);
    }

    public static class InferenceTask {
        public final String id;
        public final String modelId;
        public final String prompt;
        public final InferenceParams params;
        public final TaskPriority priority;
        public final InferenceCallback callback;
        public final long submitTime;
        public long startTime;
        public long endTime;
        public TaskState state;

        public InferenceTask(String id, String modelId, String prompt, InferenceParams params,
                           TaskPriority priority, InferenceCallback callback) {
            this.id = id;
            this.modelId = modelId;
            this.prompt = prompt;
            this.params = params;
            this.priority = priority;
            this.callback = callback;
            this.submitTime = System.currentTimeMillis();
            this.state = TaskState.QUEUED;
        }
    }

    public enum TaskPriority {
        LOW(1), NORMAL(2), HIGH(3), URGENT(4);

        public final int value;

        TaskPriority(int value) {
            this.value = value;
        }
    }

    public enum TaskState {
        QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, TIMEOUT
    }

    public static class InferenceParams {
        public int nPredict = 256;
        public float temperature = 0.7f;
        public float topP = 0.9f;
        public int topK = 40;
        public float repeatPenalty = 1.1f;
        public int nCtx = 4096;
        public int nThreads = 0;
        public int nGpuLayers = 0;
        public boolean streaming = true;

        public static InferenceParams defaults() {
            return new InferenceParams();
        }
    }

    public static class BatchRequest {
        public final String modelId;
        public final String prompt;
        public final InferenceParams params;
        public final TaskPriority priority;

        public BatchRequest(String modelId, String prompt) {
            this(modelId, prompt, InferenceParams.defaults(), TaskPriority.NORMAL);
        }

        public BatchRequest(String modelId, String prompt, InferenceParams params, TaskPriority priority) {
            this.modelId = modelId;
            this.prompt = prompt;
            this.params = params;
            this.priority = priority;
        }
    }

    public static class TaskStatusInfo {
        public final String id;
        public final TaskState state;
        public final int progress;
        public final int queuePosition;
        public final long waitingTimeMs;
        public final long executionTimeMs;

        public TaskStatusInfo(String id, TaskState state, int progress, int queuePosition,
                           long waitingTimeMs, long executionTimeMs) {
            this.id = id;
            this.state = state;
            this.progress = progress;
            this.queuePosition = queuePosition;
            this.waitingTimeMs = waitingTimeMs;
            this.executionTimeMs = executionTimeMs;
        }
    }

    public static class QueueStatistics {
        public final int queueSize;
        public final int runningCount;
        public final long totalProcessed;
        public final long totalCancelled;

        public QueueStatistics(int queueSize, int runningCount, long totalProcessed, long totalCancelled) {
            this.queueSize = queueSize;
            this.runningCount = runningCount;
            this.totalProcessed = totalProcessed;
            this.totalCancelled = totalCancelled;
        }
    }
}

class StreamingInferenceManager {
    private static final String TAG = "StreamingInference";

    private final Context context;
    private final Map<String, StreamingContext> activeStreams = new ConcurrentHashMap<>();
    private final Handler mainHandler;
    private final ExecutorService streamingExecutor = Executors.newSingleThreadExecutor();

    public StreamingInferenceManager(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public String startStreaming(String modelId, String prompt, InferenceQueue.InferenceParams params, StreamingCallback callback) {
        String streamId = "stream_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 1000);

        StreamingContext context = new StreamingContext(streamId, modelId, callback);
        activeStreams.put(streamId, context);

        streamingExecutor.execute(() -> executeStreaming(context, prompt, params));

        return streamId;
    }

    private void executeStreaming(StreamingContext context, String prompt, InferenceQueue.InferenceParams params) {
        try {
            StringBuilder fullResponse = new StringBuilder();

            for (int i = 0; i < params.nPredict; i++) {
                if (!context.isRunning) break;

                Thread.sleep(50);

                String token = "token_" + i + " ";
                fullResponse.append(token);

                final int index = i;
                mainHandler.post(() -> context.callback.onToken(token, index));
            }

            mainHandler.post(() -> context.callback.onComplete(fullResponse.toString()));

        } catch (Exception e) {
            mainHandler.post(() -> context.callback.onError(e));
        } finally {
            activeStreams.remove(context.id);
        }
    }

    public void stopStreaming(String streamId) {
        StreamingContext context = activeStreams.get(streamId);
        if (context != null) {
            context.isRunning = false;
        }
    }

    public void stopAllStreaming() {
        for (StreamingContext context : activeStreams.values()) {
            context.isRunning = false;
        }
    }

    public int getActiveStreamCount() {
        return activeStreams.size();
    }

    public void cleanup() {
        stopAllStreaming();
    }

    public interface StreamingCallback {
        void onToken(String token, int index);
        void onComplete(String fullResponse);
        void onError(Throwable error);
    }

    private static class StreamingContext {
        public final String id;
        public final String modelId;
        public final StreamingCallback callback;
        public volatile boolean isRunning = true;

        public StreamingContext(String id, String modelId, StreamingCallback callback) {
            this.id = id;
            this.modelId = modelId;
            this.callback = callback;
        }
    }
}
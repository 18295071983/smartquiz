package com.oilquiz.app.ai.callback;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public class CallbackHandler {
    private static final String TAG = "CallbackHandler";
    private static final int MAX_CALLBACKS = 100;
    private static final long CALLBACK_TIMEOUT_MS = 300000L;

    private static volatile CallbackHandler INSTANCE;

    private final Handler mainHandler;
    private final Map<String, CallbackInfo> callbacks;
    private final List<EventListener> eventListeners;
    private final AtomicLong callbackSequence;

    private CallbackHandler() {
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.callbacks = new ConcurrentHashMap<>();
        this.eventListeners = new CopyOnWriteArrayList<>();
        this.callbackSequence = new AtomicLong(0);
    }

    public static CallbackHandler getInstance() {
        if (INSTANCE == null) {
            synchronized (CallbackHandler.class) {
                if (INSTANCE == null) {
                    INSTANCE = new CallbackHandler();
                }
            }
        }
        return INSTANCE;
    }

    public <T> String registerCallback(T callback, CallbackType type) {
        String id = generateCallbackId();

        if (callbacks.size() >= MAX_CALLBACKS) {
            cleanupStaleCallbacks();
            if (callbacks.size() >= MAX_CALLBACKS) {
                Log.w(TAG, "Max callbacks reached, rejecting new callback: " + id);
                throw new IllegalStateException("Max callbacks reached");
            }
        }

        callbacks.put(id, new CallbackInfo(id, callback, type));
        Log.d(TAG, "Registered callback: " + id + " (type: " + type + ", total: " + callbacks.size() + ")");
        return id;
    }

    public void unregisterCallback(String callbackId) {
        CallbackInfo info = callbacks.remove(callbackId);
        if (info != null) {
            info.isValid = false;
            Log.d(TAG, "Unregistered callback: " + callbackId);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T getCallback(String callbackId) {
        CallbackInfo info = callbacks.get(callbackId);
        if (info != null && info.isValid) {
            return (T) info.callback;
        }
        return null;
    }

    public void executeOnMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            mainHandler.post(runnable);
        }
    }

    public void executeOnMainDelayed(Runnable runnable, long delayMs) {
        mainHandler.postDelayed(runnable, delayMs);
    }

    public void postInferenceToken(String callbackId, String token, int index) {
        CallbackInfo info = callbacks.get(callbackId);
        if (info == null || !info.isValid) {
            Log.w(TAG, "Callback not found or invalid: " + callbackId);
            return;
        }

        info.lastCalledAt = System.currentTimeMillis();
        info.callCount++;

        executeOnMain(() -> {
            try {
                if (info.callback instanceof InferenceCallback) {
                    ((InferenceCallback) info.callback).onToken(token, index);
                } else if (info.callback instanceof StreamingCallback) {
                    ((StreamingCallback) info.callback).onToken(token, index);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in inference token callback", e);
            }
        });

        notifyEvent(new CallbackEvent(CallbackType.INFERENCE, callbackId, new Object[]{token, index}));
    }

    public void postInferenceComplete(String callbackId, String result) {
        CallbackInfo info = callbacks.get(callbackId);
        if (info == null || !info.isValid) {
            Log.w(TAG, "Callback not found or invalid: " + callbackId);
            return;
        }

        info.lastCalledAt = System.currentTimeMillis();
        info.callCount++;

        executeOnMain(() -> {
            try {
                if (info.callback instanceof InferenceCallback) {
                    ((InferenceCallback) info.callback).onComplete(result);
                } else if (info.callback instanceof StreamingCallback) {
                    ((StreamingCallback) info.callback).onComplete(result != null ? result : "");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in inference complete callback", e);
            }
        });

        notifyEvent(new CallbackEvent(CallbackType.COMPLETE, callbackId, result));

        unregisterCallback(callbackId);
    }

    public void postInferenceError(String callbackId, Throwable error) {
        CallbackInfo info = callbacks.get(callbackId);
        if (info == null || !info.isValid) {
            Log.w(TAG, "Callback not found or invalid: " + callbackId);
            return;
        }

        info.lastCalledAt = System.currentTimeMillis();
        info.callCount++;

        executeOnMain(() -> {
            try {
                if (info.callback instanceof InferenceCallback) {
                    ((InferenceCallback) info.callback).onError(error);
                } else if (info.callback instanceof StreamingCallback) {
                    ((StreamingCallback) info.callback).onError(error);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in inference error callback", e);
            }
        });

        notifyEvent(new CallbackEvent(CallbackType.ERROR, callbackId, null, error));

        unregisterCallback(callbackId);
    }

    public void postInferenceProgress(String callbackId, int progress) {
        CallbackInfo info = callbacks.get(callbackId);
        if (info == null || !info.isValid) {
            return;
        }

        executeOnMain(() -> {
            try {
                if (info.callback instanceof InferenceCallback) {
                    ((InferenceCallback) info.callback).onProgress(progress);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in inference progress callback", e);
            }
        });
    }

    public void postDownloadProgress(String callbackId, int progress, long downloadedMB, long totalMB) {
        CallbackInfo info = callbacks.get(callbackId);
        if (info == null || !info.isValid) {
            return;
        }

        info.lastCalledAt = System.currentTimeMillis();

        executeOnMain(() -> {
            try {
                if (info.callback instanceof DownloadCallback) {
                    ((DownloadCallback) info.callback).onProgress(progress, downloadedMB, totalMB);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in download progress callback", e);
            }
        });

        notifyEvent(new CallbackEvent(CallbackType.PROGRESS, callbackId, new Object[]{progress, downloadedMB, totalMB}));
    }

    public void postDownloadComplete(String callbackId, String filePath) {
        CallbackInfo info = callbacks.get(callbackId);
        if (info == null || !info.isValid) {
            return;
        }

        executeOnMain(() -> {
            try {
                if (info.callback instanceof DownloadCallback) {
                    ((DownloadCallback) info.callback).onComplete(filePath);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in download complete callback", e);
            }
        });

        notifyEvent(new CallbackEvent(CallbackType.COMPLETE, callbackId, filePath));

        unregisterCallback(callbackId);
    }

    public void postDownloadError(String callbackId, String error) {
        CallbackInfo info = callbacks.get(callbackId);
        if (info == null || !info.isValid) {
            return;
        }

        executeOnMain(() -> {
            try {
                if (info.callback instanceof DownloadCallback) {
                    ((DownloadCallback) info.callback).onError(error);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in download error callback", e);
            }
        });

        notifyEvent(new CallbackEvent(CallbackType.ERROR, callbackId, null, new RuntimeException(error)));

        unregisterCallback(callbackId);
    }

    public void postModelLoadProgress(String callbackId, int progress) {
        CallbackInfo info = callbacks.get(callbackId);
        if (info == null || !info.isValid) {
            return;
        }

        executeOnMain(() -> {
            try {
                if (info.callback instanceof ModelLoadCallback) {
                    ((ModelLoadCallback) info.callback).onLoading(progress);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in model load progress callback", e);
            }
        });
    }

    public void postModelLoadComplete(String callbackId, String modelId) {
        CallbackInfo info = callbacks.get(callbackId);
        if (info == null || !info.isValid) {
            return;
        }

        executeOnMain(() -> {
            try {
                if (info.callback instanceof ModelLoadCallback) {
                    ((ModelLoadCallback) info.callback).onLoaded(modelId);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in model load complete callback", e);
            }
        });

        notifyEvent(new CallbackEvent(CallbackType.COMPLETE, callbackId, modelId));

        unregisterCallback(callbackId);
    }

    public void postModelLoadError(String callbackId, String error) {
        CallbackInfo info = callbacks.get(callbackId);
        if (info == null || !info.isValid) {
            return;
        }

        executeOnMain(() -> {
            try {
                if (info.callback instanceof ModelLoadCallback) {
                    ((ModelLoadCallback) info.callback).onError(error);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in model load error callback", e);
            }
        });

        notifyEvent(new CallbackEvent(CallbackType.ERROR, callbackId, null, new RuntimeException(error)));

        unregisterCallback(callbackId);
    }

    public void addEventListener(EventListener listener) {
        eventListeners.add(listener);
    }

    public void removeEventListener(EventListener listener) {
        eventListeners.remove(listener);
    }

    private void notifyEvent(CallbackEvent event) {
        for (EventListener listener : eventListeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                Log.e(TAG, "Error notifying event listener", e);
            }
        }
    }

    private String generateCallbackId() {
        return "cb_" + callbackSequence.getAndIncrement() + "_" + System.currentTimeMillis();
    }

    private void cleanupStaleCallbacks() {
        long now = System.currentTimeMillis();
        List<String> toRemove = new ArrayList<>();

        for (Map.Entry<String, CallbackInfo> entry : callbacks.entrySet()) {
            CallbackInfo info = entry.getValue();
            if (!info.isValid) {
                toRemove.add(entry.getKey());
                continue;
            }
            if (now - info.lastCalledAt > CALLBACK_TIMEOUT_MS && info.callCount > 0) {
                toRemove.add(entry.getKey());
                Log.d(TAG, "Cleaned up stale callback: " + entry.getKey());
            }
        }

        for (String id : toRemove) {
            callbacks.remove(id);
        }
    }

    public int getActiveCallbackCount() {
        return callbacks.size();
    }

    public void clearAllCallbacks() {
        callbacks.clear();
        Log.d(TAG, "All callbacks cleared");
    }

    public boolean validateCallback(String callbackId) {
        CallbackInfo info = callbacks.get(callbackId);
        return info != null && info.isValid;
    }

    public interface EventListener {
        void onEvent(CallbackEvent event);
    }

    public interface InferenceCallback {
        void onToken(String token, int index);
        void onComplete(String result);
        void onError(Throwable error);
        void onProgress(int progress);
    }

    public interface StreamingCallback {
        void onToken(String token, int index);
        void onComplete(String fullResponse);
        void onError(Throwable error);
    }

    public interface DownloadCallback {
        void onProgress(int progress, long downloadedMB, long totalMB);
        void onComplete(String filePath);
        void onError(String error);
        void onPaused();
        void onCancelled();
    }

    public interface ModelLoadCallback {
        void onLoading(int progress);
        void onLoaded(String modelId);
        void onError(String error);
    }

    public enum CallbackType {
        INFERENCE, STREAMING, DOWNLOAD, MODEL_LOAD, ERROR, PROGRESS, COMPLETE
    }

    public static class CallbackInfo {
        public final String id;
        public final Object callback;
        public final CallbackType type;
        public final long createdAt;
        public long lastCalledAt;
        public int callCount;
        public boolean isValid;

        public CallbackInfo(String id, Object callback, CallbackType type) {
            this.id = id;
            this.callback = callback;
            this.type = type;
            this.createdAt = System.currentTimeMillis();
            this.lastCalledAt = 0;
            this.callCount = 0;
            this.isValid = true;
        }
    }

    public static class CallbackEvent {
        public final CallbackType type;
        public final String callbackId;
        public final Object data;
        public final Throwable error;
        public final long timestamp;

        public CallbackEvent(CallbackType type, String callbackId, Object data) {
            this(type, callbackId, data, null);
        }

        public CallbackEvent(CallbackType type, String callbackId, Object data, Throwable error) {
            this.type = type;
            this.callbackId = callbackId;
            this.data = data;
            this.error = error;
            this.timestamp = System.currentTimeMillis();
        }
    }
}

class CompositeCallback {
    private final List<CallbackHandler.InferenceCallback> inferenceCallbacks;
    private final List<CallbackHandler.StreamingCallback> streamingCallbacks;
    private final List<CallbackHandler.DownloadCallback> downloadCallbacks;
    private final List<CallbackHandler.ModelLoadCallback> modelLoadCallbacks;

    public CompositeCallback() {
        this.inferenceCallbacks = new ArrayList<>();
        this.streamingCallbacks = new ArrayList<>();
        this.downloadCallbacks = new ArrayList<>();
        this.modelLoadCallbacks = new ArrayList<>();
    }

    public void addInferenceCallback(CallbackHandler.InferenceCallback callback) {
        inferenceCallbacks.add(callback);
    }

    public void addStreamingCallback(CallbackHandler.StreamingCallback callback) {
        streamingCallbacks.add(callback);
    }

    public void addDownloadCallback(CallbackHandler.DownloadCallback callback) {
        downloadCallbacks.add(callback);
    }

    public void addModelLoadCallback(CallbackHandler.ModelLoadCallback callback) {
        modelLoadCallbacks.add(callback);
    }

    public void notifyInferenceToken(String token, int index) {
        for (CallbackHandler.InferenceCallback cb : inferenceCallbacks) {
            cb.onToken(token, index);
        }
        for (CallbackHandler.StreamingCallback cb : streamingCallbacks) {
            cb.onToken(token, index);
        }
    }

    public void notifyInferenceComplete(String result) {
        for (CallbackHandler.InferenceCallback cb : inferenceCallbacks) {
            cb.onComplete(result);
        }
        for (CallbackHandler.StreamingCallback cb : streamingCallbacks) {
            cb.onComplete(result != null ? result : "");
        }
    }

    public void notifyInferenceError(Throwable error) {
        for (CallbackHandler.InferenceCallback cb : inferenceCallbacks) {
            cb.onError(error);
        }
        for (CallbackHandler.StreamingCallback cb : streamingCallbacks) {
            cb.onError(error);
        }
    }

    public void notifyDownloadProgress(int progress, long downloadedMB, long totalMB) {
        for (CallbackHandler.DownloadCallback cb : downloadCallbacks) {
            cb.onProgress(progress, downloadedMB, totalMB);
        }
    }

    public void notifyDownloadComplete(String filePath) {
        for (CallbackHandler.DownloadCallback cb : downloadCallbacks) {
            cb.onComplete(filePath);
        }
    }

    public void notifyDownloadError(String error) {
        for (CallbackHandler.DownloadCallback cb : downloadCallbacks) {
            cb.onError(error);
        }
    }

    public void notifyModelLoadProgress(int progress) {
        for (CallbackHandler.ModelLoadCallback cb : modelLoadCallbacks) {
            cb.onLoading(progress);
        }
    }

    public void notifyModelLoadComplete(String modelId) {
        for (CallbackHandler.ModelLoadCallback cb : modelLoadCallbacks) {
            cb.onLoaded(modelId);
        }
    }

    public void clear() {
        inferenceCallbacks.clear();
        streamingCallbacks.clear();
        downloadCallbacks.clear();
        modelLoadCallbacks.clear();
    }
}
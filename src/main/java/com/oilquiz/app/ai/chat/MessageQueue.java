package com.oilquiz.app.ai.chat;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class MessageQueue {
    private static final String TAG = "MessageQueue";
    private static final String MESSAGE_ID_PREFIX = "msg_";
    
    private static volatile MessageQueue instance;
    
    private final PriorityBlockingQueue<QueuedMessage> queue;
    private final AtomicInteger sequenceCounter;
    private final Set<String> activeMessageIds;
    private final Set<String> cancelledMessageIds;
    private final AtomicBoolean isProcessing;
    private QueueListener listener;
    
    private MessageQueue() {
        this.queue = new PriorityBlockingQueue<>();
        this.sequenceCounter = new AtomicInteger(0);
        this.activeMessageIds = ConcurrentHashMap.newKeySet();
        this.cancelledMessageIds = ConcurrentHashMap.newKeySet();
        this.isProcessing = new AtomicBoolean(false);
    }
    
    public static MessageQueue getInstance() {
        if (instance == null) {
            synchronized (MessageQueue.class) {
                if (instance == null) {
                    instance = new MessageQueue();
                }
            }
        }
        return instance;
    }
    
    public void setListener(QueueListener listener) {
        this.listener = listener;
    }
    
    public String generateMessageId() {
        long timestamp = System.currentTimeMillis();
        String random = UUID.randomUUID().toString().substring(0, 6);
        int sequence = sequenceCounter.getAndIncrement() % 1000;
        return String.format("%s%d_%s_%03d", MESSAGE_ID_PREFIX, timestamp, random, sequence);
    }
    
    public void submitMessage(String messageId, ChatMessage message, int priority) {
        if (messageId == null || message == null) {
            Log.w(TAG, "submitMessage: messageId or message is null");
            return;
        }
        
        if (cancelledMessageIds.contains(messageId)) {
            Log.w(TAG, "submitMessage: message already cancelled: " + messageId);
            return;
        }
        
        QueuedMessage queuedMessage = new QueuedMessage(messageId, message, priority);
        queue.offer(queuedMessage);
        Log.i(TAG, "Message submitted: " + messageId + ", priority: " + priority + ", queue size: " + queue.size());
        
        if (listener != null) {
            listener.onMessageQueued(messageId, message);
        }
        
        if (!isProcessing.get()) {
            startProcessing();
        }
    }
    
    public void submitMessage(String messageId, ChatMessage message) {
        submitMessage(messageId, message, Priority.NORMAL);
    }
    
    private void startProcessing() {
        if (!isProcessing.compareAndSet(false, true)) {
            return;
        }
        
        new Thread(() -> {
            Log.i(TAG, "Started processing queue");
            try {
                while (!queue.isEmpty() || !Thread.currentThread().isInterrupted()) {
                    QueuedMessage queued = queue.poll();
                    if (queued == null) {
                        try {
                            Thread.sleep(50);
                            if (queue.isEmpty()) {
                                break;
                            }
                            continue;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    
                    String messageId = queued.messageId;
                    
                    if (cancelledMessageIds.contains(messageId)) {
                        Log.i(TAG, "Message cancelled, skipping: " + messageId);
                        cancelledMessageIds.remove(messageId);
                        continue;
                    }
                    
                    activeMessageIds.add(messageId);
                    Log.i(TAG, "Processing message: " + messageId);
                    
                    try {
                        if (listener != null) {
                            listener.onMessageReady(messageId, queued.message);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "Error processing message: " + messageId, t);
                        if (listener != null) {
                            listener.onMessageError(messageId, t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
                        }
                    } finally {
                        activeMessageIds.remove(messageId);
                    }
                }
            } finally {
                isProcessing.set(false);
                Log.i(TAG, "Queue processing completed, remaining: " + queue.size());
            }
        }, "MessageQueue-Worker").start();
    }
    
    public void cancelMessage(String messageId) {
        if (messageId == null) return;
        
        cancelledMessageIds.add(messageId);
        activeMessageIds.remove(messageId);
        
        if (listener != null) {
            listener.onMessageCancelled(messageId);
        }
        
        Log.i(TAG, "Message cancelled: " + messageId);
    }
    
    public void cancelAll() {
        List<String> idsToCancel = new ArrayList<>(activeMessageIds);
        for (String messageId : idsToCancel) {
            cancelMessage(messageId);
        }
        queue.clear();
        cancelledMessageIds.clear();
        Log.i(TAG, "All messages cancelled");
    }
    
    public boolean isActive(String messageId) {
        return activeMessageIds.contains(messageId);
    }
    
    public boolean isCancelled(String messageId) {
        return cancelledMessageIds.contains(messageId);
    }
    
    public int getQueueSize() {
        return queue.size();
    }
    
    public int getActiveCount() {
        return activeMessageIds.size();
    }
    
    public boolean isProcessing() {
        return isProcessing.get();
    }
    
    public void clear() {
        queue.clear();
        cancelledMessageIds.clear();
        Log.i(TAG, "Queue cleared");
    }
    
    public void shutdown() {
        cancelAll();
        Log.i(TAG, "MessageQueue shutdown");
    }
    
    public static class QueuedMessage implements Comparable<QueuedMessage> {
        public final String messageId;
        public final ChatMessage message;
        public final int priority;
        public final long timestamp;
        
        public QueuedMessage(String messageId, ChatMessage message, int priority) {
            this.messageId = messageId;
            this.message = message;
            this.priority = priority;
            this.timestamp = System.currentTimeMillis();
        }
        
        @Override
        public int compareTo(QueuedMessage other) {
            if (this.priority != other.priority) {
                return Integer.compare(other.priority, this.priority);
            }
            return Long.compare(this.timestamp, other.timestamp);
        }
    }
    
    public static class Priority {
        public static final int HIGH = 100;
        public static final int NORMAL = 50;
        public static final int LOW = 0;
    }
    
    public interface QueueListener {
        void onMessageQueued(String messageId, ChatMessage message);
        void onMessageReady(String messageId, ChatMessage message);
        void onMessageCompleted(String messageId, String content);
        void onMessageError(String messageId, String error);
        void onMessageCancelled(String messageId);
    }
    
    public static class SimpleQueueListener implements QueueListener {
        @Override
        public void onMessageQueued(String messageId, ChatMessage message) {}
        
        @Override
        public void onMessageReady(String messageId, ChatMessage message) {}
        
        @Override
        public void onMessageCompleted(String messageId, String content) {}
        
        @Override
        public void onMessageError(String messageId, String error) {}
        
        @Override
        public void onMessageCancelled(String messageId) {}
    }
}

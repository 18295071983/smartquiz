package com.oilquiz.app.ai.chat;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.oilquiz.app.ai.chat.event.StreamingEvent;
import com.oilquiz.app.ai.chat.event.StreamingSubscriber;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class StreamingBroadcaster {
    private static final String TAG = "StreamingBroadcaster";
    
    private static volatile StreamingBroadcaster instance;
    
    private final Map<String, List<StreamingSubscriber>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, List<StreamingEvent>> eventHistory = new ConcurrentHashMap<>();
    private final Handler mainHandler;
    private final int maxHistorySize = 100;
    
    private StreamingBroadcaster() {
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    public static StreamingBroadcaster getInstance() {
        if (instance == null) {
            synchronized (StreamingBroadcaster.class) {
                if (instance == null) {
                    instance = new StreamingBroadcaster();
                }
            }
        }
        return instance;
    }
    
    public void subscribe(String messageId, StreamingSubscriber subscriber) {
        if (messageId == null || subscriber == null) {
            Log.w(TAG, "subscribe: messageId or subscriber is null");
            return;
        }
        
        List<StreamingSubscriber> subList = subscribers.computeIfAbsent(messageId, 
            k -> Collections.synchronizedList(new ArrayList<>()));
        
        boolean added = false;
        synchronized (subList) {
            boolean exists = false;
            for (StreamingSubscriber existing : subList) {
                if (existing.getSubscriberId().equals(subscriber.getSubscriberId())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                subList.add(subscriber);
                added = true;
            }
        }
        
        if (added) {
            Log.d(TAG, "Subscriber added for message: " + messageId + ", subscriber: " + subscriber.getSubscriberId());
            replayHistory(messageId, subscriber);
        }
    }
    
    public void unsubscribe(String messageId, StreamingSubscriber subscriber) {
        if (messageId == null || subscriber == null) {
            return;
        }
        
        List<StreamingSubscriber> subList = subscribers.get(messageId);
        if (subList != null) {
            synchronized (subList) {
                subList.removeIf(s -> s.getSubscriberId().equals(subscriber.getSubscriberId()));
            }
            Log.d(TAG, "Subscriber removed for message: " + messageId);
        }
    }
    
    public void unsubscribeAll(String messageId) {
        if (messageId != null) {
            subscribers.remove(messageId);
            Log.d(TAG, "All subscribers removed for message: " + messageId);
        }
    }
    
    public void broadcastTo(String messageId, StreamingEvent event) {
        if (messageId == null || event == null) {
            Log.w(TAG, "broadcastTo: messageId or event is null");
            return;
        }
        
        storeEvent(messageId, event);
        
        List<StreamingSubscriber> subList = subscribers.get(messageId);
        if (subList != null && !subList.isEmpty()) {
            List<StreamingSubscriber> snapshot;
            synchronized (subList) {
                snapshot = new ArrayList<>(subList);
            }
            
            for (StreamingSubscriber subscriber : snapshot) {
                deliverEvent(subscriber, event);
            }
            
            Log.d(TAG, "Event " + event.type + " delivered to " + snapshot.size() + " subscribers for message: " + messageId);
        } else {
            Log.d(TAG, "No subscribers for message: " + messageId + ", event stored in history");
        }
    }
    
    public void broadcastGlobal(StreamingEvent event) {
        if (event == null) return;
        
        for (Map.Entry<String, List<StreamingSubscriber>> entry : subscribers.entrySet()) {
            List<StreamingSubscriber> subList = entry.getValue();
            if (subList != null) {
                List<StreamingSubscriber> snapshot;
                synchronized (subList) {
                    snapshot = new ArrayList<>(subList);
                }
                for (StreamingSubscriber subscriber : snapshot) {
                    deliverEvent(subscriber, event);
                }
            }
        }
    }
    
    private void deliverEvent(StreamingSubscriber subscriber, StreamingEvent event) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                subscriber.onEvent(event);
            } catch (Exception e) {
                Log.e(TAG, "Error delivering event to subscriber: " + subscriber.getSubscriberId(), e);
            }
        } else {
            mainHandler.post(() -> {
                try {
                    subscriber.onEvent(event);
                } catch (Exception e) {
                    Log.e(TAG, "Error delivering event to subscriber: " + subscriber.getSubscriberId(), e);
                }
            });
        }
    }
    
    private void storeEvent(String messageId, StreamingEvent event) {
        List<StreamingEvent> history = eventHistory.computeIfAbsent(messageId, 
            k -> Collections.synchronizedList(new ArrayList<>()));
        
        synchronized (history) {
            history.add(event);
            while (history.size() > maxHistorySize) {
                history.remove(0);
            }
        }
    }
    
    private void replayHistory(String messageId, StreamingSubscriber subscriber) {
        List<StreamingEvent> history = eventHistory.get(messageId);
        if (history != null && !history.isEmpty()) {
            List<StreamingEvent> snapshot;
            synchronized (history) {
                snapshot = new ArrayList<>(history);
            }
            for (StreamingEvent event : snapshot) {
                deliverEvent(subscriber, event);
            }
            Log.d(TAG, "Replayed " + snapshot.size() + " historical events for message: " + messageId);
        }
    }
    
    public void clearHistory(String messageId) {
        if (messageId != null) {
            eventHistory.remove(messageId);
        }
    }
    
    public void clearAllHistory() {
        eventHistory.clear();
    }
    
    public int getSubscriberCount(String messageId) {
        List<StreamingSubscriber> subList = subscribers.get(messageId);
        return subList != null ? subList.size() : 0;
    }
    
    public int getTotalSubscriberCount() {
        int count = 0;
        for (List<StreamingSubscriber> subList : subscribers.values()) {
            if (subList != null) {
                count += subList.size();
            }
        }
        return count;
    }
    
    public boolean hasSubscribers(String messageId) {
        return getSubscriberCount(messageId) > 0;
    }
    
    public void shutdown() {
        subscribers.clear();
        eventHistory.clear();
        Log.i(TAG, "StreamingBroadcaster shutdown completed");
    }
}

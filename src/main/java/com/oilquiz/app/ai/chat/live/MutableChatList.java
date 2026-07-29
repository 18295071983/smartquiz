package com.oilquiz.app.ai.chat.live;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.oilquiz.app.ai.chat.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MutableChatList - 线程安全的可观察聊天消息列表
 * 
 * 功能：
 * 1. 线程安全 - 支持多线程并发访问
 * 2. 可观察 - 数据变化自动通知 UI
 * 3. 批量操作 - 支持批量添加和更新
 * 4. 防护机制 - 防止并发修改异常
 */
public class MutableChatList {

    private final CopyOnWriteArrayList<ChatMessage> internalList = new CopyOnWriteArrayList<>();
    private final ChatListLiveData liveData = new ChatListLiveData();

    // ========== 基础操作 ==========

    /**
     * 添加消息
     */
    public boolean add(ChatMessage message) {
        if (message == null) return false;
        boolean result = internalList.add(message);
        if (result) {
            notifyChanged();
        }
        return result;
    }

    /**
     * 在指定位置插入消息
     */
    public void add(int index, ChatMessage message) {
        if (message == null) return;
        // CopyOnWriteArrayList 不支持 index 插入，需要重建列表
        List<ChatMessage> newList = new ArrayList<>(internalList);
        newList.add(index, message);
        internalList.clear();
        internalList.addAll(newList);
        notifyChanged();
    }

    /**
     * 移除消息
     */
    public boolean remove(ChatMessage message) {
        boolean result = internalList.remove(message);
        if (result) {
            notifyChanged();
        }
        return result;
    }

    /**
     * 移除指定位置的消息
     */
    public ChatMessage remove(int index) {
        List<ChatMessage> newList = new ArrayList<>(internalList);
        ChatMessage removed = newList.remove(index);
        internalList.clear();
        internalList.addAll(newList);
        notifyChanged();
        return removed;
    }

    /**
     * 更新指定位置的消息
     */
    public ChatMessage set(int index, ChatMessage message) {
        List<ChatMessage> newList = new ArrayList<>(internalList);
        ChatMessage old = newList.set(index, message);
        internalList.clear();
        internalList.addAll(newList);
        notifyChanged();
        return old;
    }

    /**
     * 清空所有消息
     */
    public void clear() {
        internalList.clear();
        notifyChanged();
    }

    /**
     * 批量添加消息
     */
    public void addAll(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return;
        internalList.addAll(messages);
        notifyChanged();
    }

    /**
     * 获取消息
     */
    public ChatMessage get(int index) {
        return internalList.get(index);
    }

    /**
     * 获取消息数量
     */
    public int size() {
        return internalList.size();
    }

    /**
     * 列表是否为空
     */
    public boolean isEmpty() {
        return internalList.isEmpty();
    }

    /**
     * 查找消息位置
     */
    public int indexOf(ChatMessage message) {
        return internalList.indexOf(message);
    }

    /**
     * 根据 ID 查找消息
     */
    public int indexOfById(String messageId) {
        for (int i = 0; i < internalList.size(); i++) {
            ChatMessage msg = internalList.get(i);
            if (msg != null && msg.id != null && msg.id.equals(messageId)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 获取不可变列表副本
     */
    public List<ChatMessage> toImmutableList() {
        return Collections.unmodifiableList(new ArrayList<>(internalList));
    }

    /**
     * 获取可变列表副本
     */
    public List<ChatMessage> toMutableList() {
        return new ArrayList<>(internalList);
    }

    // ========== LiveData ==========

    /**
     * 获取 LiveData 用于观察
     */
    public LiveData<List<ChatMessage>> asLiveData() {
        return liveData;
    }

    /**
     * 手动触发通知
     */
    public void notifyChanged() {
        liveData.postValueSafe(new ArrayList<>(internalList));
    }

    /**
     * 通知指定位置更新
     */
    public void notifyItemChanged(int index) {
        if (index >= 0 && index < internalList.size()) {
            notifyChanged();
        }
    }

    /**
     * 通知指定位置插入
     */
    public void notifyItemInserted(int index) {
        notifyChanged();
    }

    /**
     * 通知指定位置移除
     */
    public void notifyItemRemoved(int index) {
        notifyChanged();
    }

    // ========== LiveData 实现 ==========

    private class ChatListLiveData extends MutableLiveData<List<ChatMessage>> {
        @Override
        protected void onActive() {
            super.onActive();
            // 有观察者时，推送当前数据
            setValue(new ArrayList<>(internalList));
        }

        public void postValueSafe(List<ChatMessage> value) {
            postValue(value);
        }
    }
}

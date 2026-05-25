package com.oilquiz.app.ai.chat;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 流式输出滚动助手
 * 解决大量 token 时滚动卡顿问题
 */
public class StreamingScrollHelper {
    
    // ==================== 常量定义 ====================
    
    /** 每 N 个 token 强制滚动一次 */
    private static final int SCROLL_THRESHOLD_TOKENS = 100;
    
    /** 滚动防抖延迟 (ms) */
    private static final long SCROLL_DEBOUNCE_MS = 80;
    
    /** 用户手动滚动后恢复自动滚动的延迟 (ms) */
    private static final long RECOVER_AUTO_SCROLL_DELAY_MS = 3000;
    
    /** 新消息出现后自动滚动的延迟 (ms) */
    private static final long AUTO_SCROLL_ON_NEW_MSG_MS = 200;
    
    // ==================== 状态变量 ====================
    
    /** 是否正在流式输出 */
    private volatile boolean isStreaming = false;
    
    /** 用户是否正在手动滚动 */
    private volatile boolean isUserScrolling = false;
    
    /** 上次滚动时的 token 计数 */
    private int lastScrollTokenCount = 0;
    
    /** RecyclerView 引用 */
    private final RecyclerView rvMessages;
    
    /** Adapter 引用 */
    private final RecyclerView.Adapter<?> adapter;
    
    /** Handler 用于延迟执行 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // ==================== 构造方法 ====================
    
    public StreamingScrollHelper(RecyclerView rvMessages, RecyclerView.Adapter<?> adapter) {
        this.rvMessages = rvMessages;
        this.adapter = adapter;
        setupRecyclerView();
    }
    
    /**
     * 设置 RecyclerView 监听
     */
    private void setupRecyclerView() {
        if (rvMessages == null) return;
        
        rvMessages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    // 用户开始手动拖动
                    onUserStartScroll();
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    // 用户停止滚动
                    if (isUserScrolling) {
                        onUserEndScroll();
                    }
                }
            }
        });
    }
    
    // ==================== 核心方法 ====================
    
    /**
     * 开始流式输出
     */
    public void startStreaming() {
        this.isStreaming = true;
        this.isUserScrolling = false;
        this.lastScrollTokenCount = 0;
    }
    
    /**
     * 处理每个新 token
     */
    public void onTokenReceived(int currentTokenCount) {
        if (!isStreaming) return;
        
        // 情况1: 用户正在手动滚动 → 不自动滚动
        if (isUserScrolling) {
            return;
        }
        
        // 情况2: 距离上次滚动超过阈值 → 强制滚动
        if ((currentTokenCount - lastScrollTokenCount) >= SCROLL_THRESHOLD_TOKENS) {
            performSmoothScroll();
            lastScrollTokenCount = currentTokenCount;
            return;
        }
        
        // 情况3: 防抖滚动
        scheduleDebouncedScroll();
    }
    
    /**
     * 完成流式输出
     */
    public void finishStreaming() {
        this.isStreaming = false;
        cancelAllPendingScrolls();
        
        if (isUserScrolling) {
            // 延迟恢复，让用户有机会查看内容
            mainHandler.postDelayed(() -> {
                if (isUserScrolling) {
                    isUserScrolling = false;
                    performSmoothScroll();
                }
            }, RECOVER_AUTO_SCROLL_DELAY_MS);
        } else {
            // 最终滚动到底部
            performSmoothScroll();
        }
    }
    
    /**
     * 用户开始手动滚动
     */
    private void onUserStartScroll() {
        isUserScrolling = true;
        cancelAllPendingScrolls();
    }
    
    /**
     * 用户停止手动滚动
     */
    private void onUserEndScroll() {
        cancelAllPendingScrolls();
        mainHandler.postDelayed(RECOVER_MSG, RECOVER_AUTO_SCROLL_DELAY_MS);
    }
    
    /**
     * 恢复自动滚动
     */
    private final Runnable RECOVER_MSG = new Runnable() {
        @Override
        public void run() {
            if (isUserScrolling && isStreaming) {
                isUserScrolling = false;
                performSmoothScroll();
            }
        }
    };
    
    // ==================== 滚动操作 ====================
    
    /**
     * 执行滚动到底部
     */
    private void performSmoothScroll() {
        if (rvMessages == null || adapter == null) return;
        
        int itemCount = adapter.getItemCount();
        if (itemCount > 0) {
            rvMessages.smoothScrollToPosition(itemCount - 1);
        }
    }
    
    /**
     * 调度防抖滚动
     */
    private void scheduleDebouncedScroll() {
        cancelDebouncedScroll();
        mainHandler.postDelayed(DEBOUNCE_MSG, SCROLL_DEBOUNCE_MS);
    }
    
    private final Runnable DEBOUNCE_MSG = new Runnable() {
        @Override
        public void run() {
            if (!isUserScrolling && isStreaming) {
                performSmoothScroll();
            }
        }
    };
    
    /**
     * 取消防抖滚动
     */
    private void cancelDebouncedScroll() {
        mainHandler.removeCallbacks(DEBOUNCE_MSG);
    }
    
    /**
     * 取消所有待执行的滚动
     */
    private void cancelAllPendingScrolls() {
        mainHandler.removeCallbacksAndMessages(null);
    }
    
    /**
     * 释放资源
     */
    public void release() {
        cancelAllPendingScrolls();
        if (rvMessages != null) {
            rvMessages.clearOnScrollListeners();
        }
    }
}
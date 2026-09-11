package com.oilquiz.app.ai.chat.input;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 消息列表容器（全局可复用）。
 *
 * 封装 RecyclerView + 空态视图 + 自动滚动，宿主注入 adapter 后即可用；
 * 不代理 adapter 的具体业务方法（保持与 ChatAdapter 直接交互的自由）。
 *
 * 用法：
 * <pre>
 * ChatMessagesView messagesView = findViewById(R.id.chat_messages_view);
 * messagesView.setAdapter(chatAdapter);
 * messagesView.scrollToBottom();
 * messagesView.showEmpty("开始对话吧");
 * </pre>
 */
public class ChatMessagesView extends FrameLayout {

    private RecyclerView recyclerView;
    private TextView emptyView;
    private RecyclerView.Adapter<?> adapter;

    public ChatMessagesView(Context context) {
        super(context);
        init(context);
    }

    public ChatMessagesView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ChatMessagesView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        recyclerView = new RecyclerView(context);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        // 完全禁用动画：高频流式更新下消息插入/删除无动画换取稳定（对齐对话页策略）
        recyclerView.setItemAnimator(null);
        addView(recyclerView, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setTextColor(0xFF94A3B8);
        emptyView.setTextSize(14f);
        emptyView.setGravity(android.view.Gravity.CENTER);
        emptyView.setVisibility(GONE);
        addView(emptyView, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    // ==================== 对外 API ====================

    public void setAdapter(RecyclerView.Adapter<?> adapter) {
        this.adapter = adapter;
        recyclerView.setAdapter(adapter);
        emptyView.setVisibility(
                (adapter == null || adapter.getItemCount() == 0) ? VISIBLE : GONE);
    }

    @NonNull
    public RecyclerView.Adapter<?> getAdapter() {
        return adapter;
    }

    @NonNull
    public RecyclerView getRecyclerView() {
        return recyclerView;
    }

    /** 显示空态（无消息时） */
    public void showEmpty(String text) {
        emptyView.setText(text != null ? text : "");
        emptyView.setVisibility(VISIBLE);
    }

    /** 隐藏空态（有消息时） */
    public void hideEmpty() {
        emptyView.setVisibility(GONE);
    }

    /** 数据变化后刷新空态与列表 */
    public void notifyDataSetChanged() {
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        emptyView.setVisibility(
                (adapter == null || adapter.getItemCount() == 0) ? VISIBLE : GONE);
    }

    /** 滚动到底部（消息流式更新时） */
    public void scrollToBottom() {
        if (adapter != null && adapter.getItemCount() > 0) {
            recyclerView.smoothScrollToPosition(adapter.getItemCount() - 1);
        }
    }

    /** 立即滚动到底部（不带动画） */
    public void scrollToBottomImmediate() {
        if (adapter != null && adapter.getItemCount() > 0) {
            recyclerView.scrollToPosition(adapter.getItemCount() - 1);
        }
    }
}

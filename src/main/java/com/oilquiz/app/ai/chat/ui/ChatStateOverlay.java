package com.oilquiz.app.ai.chat.ui;

import android.view.View;

/**
 * 聊天状态叠加层（全局可复用 View 渲染组件，薄封装）。
 *
 * 从 AIChatActivity showLoading / hideLoading / hideLoadingUI /
 * updateEmptyState 抽取：思考指示器显隐 + 列表/空态视图切换。
 *
 * <p>页面接入示例：</p>
 * <pre>
 * ChatStateOverlay overlay = new ChatStateOverlay.Builder(context)
 *         .thinkingIndicator(() -> serviceStatusManager.showThinkingIndicator(),
 *                            () -> serviceStatusManager.hideThinkingIndicator())
 *         .emptyView(findViewById(R.id.empty_state_view))
 *         .listView(findViewById(R.id.message_list))
 *         .build();
 * overlay.showLoading();          // 推理中
 * overlay.hideLoading();          // 完成
 * overlay.setEmpty(false);        // 有新消息：隐藏空态
 * </pre>
 */
public class ChatStateOverlay {

    /** 思考指示器（页面注入 ServiceStatusManager 适配） */
    public interface ThinkingIndicator {
        void showThinking();
        void hideThinking();
    }

    private final ThinkingIndicator indicator;
    private final View emptyStateView;
    private final View messageList;

    private ChatStateOverlay(ThinkingIndicator indicator, View emptyStateView, View messageList) {
        this.indicator = indicator;
        this.emptyStateView = emptyStateView;
        this.messageList = messageList;
    }

    /** 推理中：显示思考指示器 */
    public void showLoading() {
        if (indicator != null) indicator.showThinking();
    }

    /** 完成/取消：隐藏思考指示器 */
    public void hideLoading() {
        if (indicator != null) indicator.hideThinking();
    }

    /** 空态切换：true=显示空态隐藏列表；false=显示列表隐藏空态 */
    public void setEmpty(boolean empty) {
        if (emptyStateView != null) {
            emptyStateView.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        if (messageList != null) {
            messageList.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    public static class Builder {
        private ThinkingIndicator indicator;
        private View emptyStateView;
        private View messageList;

        public Builder thinkingIndicator(ThinkingIndicator indicator) { this.indicator = indicator; return this; }
        public Builder emptyView(View v) { this.emptyStateView = v; return this; }
        public Builder listView(View v) { this.messageList = v; return this; }
        public ChatStateOverlay build() { return new ChatStateOverlay(indicator, emptyStateView, messageList); }
    }
}

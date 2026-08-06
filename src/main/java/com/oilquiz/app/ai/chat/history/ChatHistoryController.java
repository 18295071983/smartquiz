package com.oilquiz.app.ai.chat.history;

import android.app.Activity;
import android.view.View;

import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.util.ConversationSession;

import java.util.List;

/**
 * 管理聊天历史 Drawer 的控制器。
 * 从 AIChatActivity 中提取的独立模块。
 *
 * 支持：
 * - 显示所有已保存的会话列表
 * - 点击会话切换到该对话
 * - 长按删除会话
 * - 清空所有历史
 */
public class ChatHistoryController {

    public interface Callback {
        void onClearChat();
        void onShowToast(String message);
        /** 请求切换到指定会话 */
        void onSwitchToSession(ConversationSession session);
        /** 请求删除指定会话 */
        void onDeleteSession(ConversationSession session);
    }

    private final Activity activity;
    private final Callback callback;
    private DrawerLayout drawerLayout;
    private RecyclerView historyList;
    private ChatHistoryAdapter chatHistoryAdapter;

    public ChatHistoryController(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
    }

    public void init(DrawerLayout drawerLayout, RecyclerView historyList) {
        this.drawerLayout = drawerLayout;
        this.historyList = historyList;
        if (historyList != null) {
            historyList.setLayoutManager(new LinearLayoutManager(activity));
        }
    }

    public void openDrawer() {
        if (drawerLayout != null) {
            drawerLayout.openDrawer(findViewById(R.id.history_drawer));
        }
    }

    public void closeDrawer() {
        if (drawerLayout != null) {
            drawerLayout.closeDrawer(findViewById(R.id.history_drawer));
        }
    }

    public boolean isDrawerOpen() {
        return drawerLayout != null && drawerLayout.isDrawerOpen(findViewById(R.id.history_drawer));
    }

    /**
     * 刷新历史列表：从会话列表加载。
     * @param sessions 已保存的会话列表（按时间降序）
     */
    public void refresh(List<ConversationSession> sessions) {
        if (historyList == null) return;
        chatHistoryAdapter = new ChatHistoryAdapter(activity, sessions, new ChatHistoryAdapter.OnHistoryItemClickListener() {
            @Override public void onItemClick(ConversationSession session) {
                closeDrawer();
                if (callback != null) callback.onSwitchToSession(session);
            }
            @Override public void onItemLongClick(ConversationSession session) {}
            @Override public void onItemDelete(ConversationSession session) {
                if (callback != null) callback.onDeleteSession(session);
                // 从列表中移除（延迟刷新以等待实际删除完成）
            }
            @Override public void onItemShare(ConversationSession session) { callback.onShowToast("分享功能开发中"); }
            @Override public void onItemExport(ConversationSession session) { callback.onShowToast("导出功能开发中"); }
            @Override public void onClearAllHistory() { callback.onClearChat(); }
        });
        historyList.setAdapter(chatHistoryAdapter);
    }

    /** 通知列表刷新（删除/新增后调用） */
    public void notifyChanged() {
        if (chatHistoryAdapter != null) {
            chatHistoryAdapter.notifyDataSetChanged();
        }
    }

    private View findViewById(int id) {
        return activity.findViewById(id);
    }
}

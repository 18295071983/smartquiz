package com.oilquiz.app.ai.chat.history;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;

import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.util.ConversationSession;

import java.util.List;

/**
 * 管理聊天历史 Drawer 的控制器。
 * 从 AIChatActivity 中提取的独立模块。
 *
 * 支持：
 * - 显示所有已保存的会话列表（按日期分组）
 * - 点击会话切换到该对话
 * - 长按删除会话
 * - 当前会话高亮 + 空状态提示
 * - 新建对话 / 清空所有历史
 */
public class ChatHistoryController {

    public interface Callback {
        void onClearChat();
        void onShowToast(String message);
        /** 请求切换到指定会话 */
        void onSwitchToSession(ConversationSession session);
        /** 请求删除指定会话 */
        void onDeleteSession(ConversationSession session);
        /** 请求开始新对话 */
        void onStartNewConversation();
        /** 请求切到电脑上的某条会话（2026-11-14：抽屉顶部那组） */
        default void onSwitchToRemoteSession(RemoteSessionRow row) {}
        /** 请求打开「远程连接（电脑）」页（电脑未连接时的组头） */
        default void onOpenRemoteConnect() {}
    }

    private final Activity activity;
    private final Callback callback;
    private DrawerLayout drawerLayout;
    private RecyclerView historyList;
    private TextView emptyView;
    private ChatHistoryAdapter chatHistoryAdapter;
    private String currentSessionId;
    /** 会话 id -> 活跃任务数，refresh 时透传给 adapter 显示"N 待办"角标 */
    private java.util.Map<String, Integer> taskCountsBySession;

    /** 设置各会话活跃任务数（refreshHistoryDrawer 计算后、refresh 前调用） */
    public void setTaskCounts(java.util.Map<String, Integer> counts) {
        this.taskCountsBySession = counts;
    }

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
        this.emptyView = activity.findViewById(R.id.history_empty_view);
    }

    /** 设置当前会话 ID（高亮历史列表中的对应项） */
    public void setCurrentSessionId(String sessionId) {
        this.currentSessionId = sessionId;
    }


    public void closeDrawer() {
        if (drawerLayout != null) {
            drawerLayout.closeDrawer(findViewById(R.id.history_drawer));
        }
    }


    /**
     * 刷新历史列表：从会话列表加载。
     * @param sessions 已保存的会话列表（按时间降序）
     */
    public void refresh(List<ConversationSession> sessions) {
        refresh(sessions, null, null);
    }

    /**
     * 刷新历史列表（2026-11-14：抽屉顶部多一组「电脑上的对话」）。
     *
     * @param sessions   本机会话（按时间降序）
     * @param remoteRows 电脑上的会话（null/空 = 不显示；非空时排在最前面）
     * @param remoteHint 组头文案（例如"电脑上的对话（未连接）"）；null = 按 remoteRows 自动拼
     */
    public void refresh(List<ConversationSession> sessions,
                        List<RemoteSessionRow> remoteRows, String remoteHint) {
        if (historyList == null) return;
        chatHistoryAdapter = new ChatHistoryAdapter(activity, sessions, remoteRows, remoteHint,
                new ChatHistoryAdapter.OnHistoryItemClickListener() {
            @Override public void onItemClick(ConversationSession session) {
                closeDrawer();
                if (callback != null) callback.onSwitchToSession(session);
            }
            @Override public void onItemLongClick(ConversationSession session) {}
            @Override public void onItemDelete(ConversationSession session) {
                if (callback != null) callback.onDeleteSession(session);
            }
            @Override public void onClearAllHistory() { if (callback != null) callback.onClearChat(); }
            @Override public void onRemoteItemClick(RemoteSessionRow row) {
                closeDrawer();
                if (callback != null) callback.onSwitchToRemoteSession(row);
            }
            @Override public void onRemoteHeaderClick() {
                closeDrawer();
                if (callback != null) callback.onOpenRemoteConnect();
            }
        });
        chatHistoryAdapter.setCurrentSessionId(currentSessionId);
        chatHistoryAdapter.setTaskCounts(taskCountsBySession);
        historyList.setAdapter(chatHistoryAdapter);

        // 空状态：本机会话和电脑会话都没有时才提示"还没有对话"
        boolean hasRemote = (remoteRows != null && !remoteRows.isEmpty()) || remoteHint != null;
        boolean empty = (sessions == null || sessions.isEmpty()) && !hasRemote;
        if (emptyView != null) {
            emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        }
        if (historyList != null) {
            historyList.setVisibility(empty ? View.GONE : View.VISIBLE);
        }
    }

    private View findViewById(int id) {
        return activity.findViewById(id);
    }
}

package com.oilquiz.app.ai.chat.history;

import android.app.Activity;
import android.view.View;

import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ui.adapter.ChatHistoryAdapter;
import com.oilquiz.app.ui.adapter.ChatHistoryAdapter.ChatHistoryItem;

import java.util.List;

/**
 * 管理聊天历史 Drawer 的控制器。
 * 从 AIChatActivity 中提取的独立模块。
 */
public class ChatHistoryController {

    public interface Callback {
        void onClearChat();
        void onShowToast(String message);
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

    public void refresh(List<ChatMessage> chatHistory) {
        if (historyList == null || chatHistory == null) return;
        chatHistoryAdapter = new ChatHistoryAdapter(activity, chatHistory, new ChatHistoryAdapter.OnHistoryItemClickListener() {
            @Override public void onItemClick(ChatHistoryItem item, int p) { closeDrawer(); }
            @Override public void onItemLongClick(ChatHistoryItem item, int p) {}
            @Override public void onItemDelete(ChatHistoryItem item, int p) { callback.onClearChat(); refresh(chatHistory); callback.onShowToast("已删除"); }
            @Override public void onItemShare(ChatHistoryItem item, int p) { callback.onShowToast("分享功能开发中"); }
            @Override public void onItemExport(ChatHistoryItem item, int p) { callback.onShowToast("导出功能开发中"); }
            @Override public void onClearAllHistory() { callback.onClearChat(); }
        });
        historyList.setAdapter(chatHistoryAdapter);
    }

    private View findViewById(int id) {
        return activity.findViewById(id);
    }
}

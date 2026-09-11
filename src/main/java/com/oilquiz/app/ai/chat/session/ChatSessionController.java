package com.oilquiz.app.ai.chat.session;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.util.ConversationSession;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天会话管理控制器（全局可复用，逻辑层）。
 *
 * 从 AIChatActivity 会话抽屉编排抽取：清空 / 新建 / 切换 / 删除 / 刷新列表的
 * 完整流程逻辑（先保存当前会话 → 清空上下文 → 引擎按会话隔离恢复），
 * 通过 {@link Store}（持久化）、{@link EngineControl}（生成引擎/本地上下文）、
 * {@link Host}（UI 刷新）三接口解耦，不依赖 Activity。
 *
 * 用法：
 * <pre>
 * ChatSessionController controller = new ChatSessionController(store, engine, host);
 * controller.setCurrentSessionId(id);
 * controller.newConversation(history);          // 保存当前 → 清空
 * controller.switchTo(session, history);        // 保存当前 → 加载目标
 * </pre>
 */
public class ChatSessionController {

    /** 会话持久化存储（宿主实现，通常包 chatHistoryManager） */
    public interface Store {
        void saveCurrentChatAsSession(List<ChatMessage> history, String sessionId);
        ConversationSession loadConversationSession(String id);
        void deleteConversationSession(String id);
        void clearAIChatHistory();
        void clearAllConversationSessions();
        void saveAIChatHistory(List<ChatMessage> history);
        List<ConversationSession> listConversationSessions();
    }

    /** 生成引擎 / 本地上下文 / Token 统计控制（宿主实现） */
    public interface EngineControl {
        boolean isGenerating();
        /** 取消 Agent 引擎生成（若在生成） */
        void cancelAgentIfGenerating();
        /** 停止页面侧生成状态 */
        void stopGeneration();
        /** 清空本地模型上下文 */
        void clearContext();
        void setAgentSessionId(String id);
        void clearAgentHistory();
        void clearAgentAllHistory();
        void resetTokenSession();
    }

    /** UI 刷新回调 */
    public interface Host {
        void runOnUi(Runnable r);
        /** 历史数据被改动：notifyDataSetChanged + 空态 + 滚动等 */
        void onHistoryMutated();
        /** 抽屉列表需刷新 */
        void onDrawerRefresh();
        void onToast(int resId);
        /** 目标会话加载成功（宿主更新标题提示/滚动） */
        void onSessionLoaded(ConversationSession loaded);
        /** 会话列表加载完成（宿主刷新抽屉） */
        void onSessionsLoaded(String currentSessionId, List<ConversationSession> sessions);
    }

    private final Store store;
    private final EngineControl engine;
    private final Host host;
    private volatile String currentSessionId;

    public ChatSessionController(Store store, EngineControl engine, Host host) {
        this.store = store;
        this.engine = engine;
        this.host = host;
    }

    public String getCurrentSessionId() { return currentSessionId; }
    public void setCurrentSessionId(String id) { this.currentSessionId = id; }

    /** 清空全部：停止生成 → 清数据源 → 清引擎历史 → 重置会话与 Token */
    public void clearAll(List<ChatMessage> history) {
        try {
            if (engine.isGenerating()) {
                engine.cancelAgentIfGenerating();
                engine.stopGeneration();
            }
            history.clear();
            if (store != null) {
                new Thread(() -> {
                    store.clearAIChatHistory();
                    store.clearAllConversationSessions();
                    host.runOnUi(host::onDrawerRefresh);
                }).start();
            }
            engine.clearContext();
            engine.clearAgentAllHistory();
            currentSessionId = null;
            engine.resetTokenSession();
            host.onHistoryMutated();
        } catch (Exception e) {
            android.util.Log.e("ChatSessionController", "clearAll: " + e.getMessage());
        }
    }

    /** 新建对话：先自动保存当前会话，再清空上下文 */
    public void newConversation(List<ChatMessage> history) {
        try {
            if (engine.isGenerating()) {
                engine.cancelAgentIfGenerating();
                engine.stopGeneration();
            }
            if (store != null && !history.isEmpty()) {
                final List<ChatMessage> copy = new ArrayList<>(history);
                final String existingId = currentSessionId;
                new Thread(() -> {
                    store.saveCurrentChatAsSession(copy, existingId);
                    host.runOnUi(host::onDrawerRefresh);
                }).start();
            }
            currentSessionId = null;
            history.clear();
            if (store != null) new Thread(() -> store.clearAIChatHistory()).start();
            engine.clearContext();
            engine.setAgentSessionId(null);
            engine.clearAgentHistory();
            host.onHistoryMutated();
        } catch (Exception e) {
            android.util.Log.e("ChatSessionController", "newConversation: " + e.getMessage());
        }
    }

    /** 切换会话：先保存当前会话，再异步加载目标会话（引擎按会话隔离恢复） */
    public void switchTo(ConversationSession session, List<ChatMessage> history) {
        if (session == null || session.id == null) return;
        try {
            if (store != null && !history.isEmpty()) {
                store.saveCurrentChatAsSession(new ArrayList<>(history), currentSessionId);
            }
            new Thread(() -> {
                ConversationSession loaded = store != null ? store.loadConversationSession(session.id) : null;
                if (loaded != null && loaded.messages != null && !loaded.messages.isEmpty()) {
                    host.runOnUi(() -> {
                        if (engine.isGenerating()) {
                            engine.cancelAgentIfGenerating();
                            engine.stopGeneration();
                        }
                        currentSessionId = loaded.id;
                        history.clear();
                        history.addAll(loaded.messages);
                        if (store != null) store.saveAIChatHistory(new ArrayList<>(history));
                        engine.setAgentSessionId(loaded.id);
                        engine.clearContext();
                        host.onHistoryMutated();
                        host.onSessionLoaded(loaded);
                    });
                } else {
                    host.runOnUi(() -> host.onToast(com.oilquiz.app.R.string.h_c59cad21));
                }
            }).start();
        } catch (Exception e) {
            android.util.Log.e("ChatSessionController", "switchTo: " + e.getMessage());
        }
    }

    /** 删除会话：若删除的是当前会话，同步清空引擎历史与页面上下文 */
    public void delete(ConversationSession session, List<ChatMessage> history) {
        if (session == null || session.id == null) return;
        final boolean isCurrent = session.id.equals(currentSessionId);
        new Thread(() -> {
            if (store != null) store.deleteConversationSession(session.id);
            host.runOnUi(() -> {
                if (isCurrent) {
                    currentSessionId = null;
                    history.clear();
                    engine.clearAgentHistory();
                    engine.clearContext();
                    host.onHistoryMutated();
                }
                host.onDrawerRefresh();
                host.onToast(com.oilquiz.app.R.string.h_5cc23262);
            });
        }).start();
    }

    /** 刷新会话列表（异步读取 → 回调宿主） */
    public void loadSessions() {
        if (store == null) return;
        new Thread(() -> {
            List<ConversationSession> sessions = store.listConversationSessions();
            host.runOnUi(() -> host.onSessionsLoaded(currentSessionId, sessions));
        }).start();
    }
}

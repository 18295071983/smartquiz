package com.oilquiz.app.ai.chat.ui;

import android.app.Activity;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.util.ConversationSession;

import java.util.Calendar;
import java.util.List;

/**
 * 聊天页 v2 空状态（demo {@code section.empty}）：问候语 + 四个场景卡 + 最近会话。
 *
 * <p>和 {@link ChatUiV2Chrome} 一样只在开关 {@code chat_ui_v2} 打开时生效；
 * 开关关着时 {@link #bind} 直接返回 null，调用方保留旧的空状态视图。</p>
 *
 * <p>本类只负责把视图和回调接起来，不碰会话存储：场景卡的填充、会话切换都由
 * {@link Listener} 交回宿主（{@code AIChatActivity} 里各自只有一行委托），
 * 时间口径与历史抽屉的 {@code ChatHistoryAdapter} 保持一致（今天 HH:mm / 昨天 HH:mm / M月d日）。</p>
 */
public final class ChatV2EmptyState {

    private static final String TAG = "ChatV2EmptyState";

    /** 最近列表最多几条（demo 的 .recent 只放两条，多一条用于撑住首屏） */
    private static final int MAX_RECENT = 3;

    /** 空状态交互出口 */
    public interface Listener {
        /** 点场景卡：把示例问题填进输入框（demo 的 data-fill 只填充不发送） */
        void onScenePrompt(String prompt);

        /** 点「最近」条目：切到该会话 */
        void onOpenSession(ConversationSession session);
    }

    private final View root;
    private final Listener listener;
    private final TextView greeting;
    private final LinearLayout recentGroup;
    private final LinearLayout recentContainer;

    private ChatV2EmptyState(View root, Listener listener) {
        this.root = root;
        this.listener = listener;
        this.greeting = root.findViewById(R.id.chat_v2_greet_hello);
        this.recentGroup = root.findViewById(R.id.chat_v2_recent_group);
        this.recentContainer = root.findViewById(R.id.chat_v2_recent_container);
        bindScenes();
    }

    /**
     * 开关关闭、或页面上没有 v2 空状态视图时返回 null（调用方按 null 走旧逻辑）。
     */
    public static ChatV2EmptyState bind(Activity activity, Listener listener) {
        if (activity == null || listener == null) return null;
        try {
            if (!ChatUiFlags.isUiV2Enabled(activity)) return null;
            View root = activity.findViewById(R.id.empty_state_v2_view);
            if (root == null) return null;
            return new ChatV2EmptyState(root, listener);
        } catch (Throwable t) {
            Log.w(TAG, "bind skipped: " + t.getMessage());
            return null;
        }
    }

    /** v2 空状态的根视图；宿主把它接到"消息列表为空时显示"的那套可见性逻辑上 */
    public View getRoot() {
        return root;
    }

    private void bindScenes() {
        // demo 的四张场景卡 = 四个提示词模板；文案走 strings，方便后续按学段替换
        setScene(R.id.chat_v2_scene_quiz, R.string.chat_v2_prompt_quiz);
        setScene(R.id.chat_v2_scene_explain, R.string.chat_v2_prompt_explain);
        setScene(R.id.chat_v2_scene_summary, R.string.chat_v2_prompt_summary);
        setScene(R.id.chat_v2_scene_plan, R.string.chat_v2_prompt_plan);
    }

    private void setScene(int viewId, int promptRes) {
        View card = root.findViewById(viewId);
        if (card == null) return;
        card.setOnClickListener(v -> {
            if (listener != null) listener.onScenePrompt(root.getContext().getString(promptRes));
        });
    }

    /** 问候语按小时：5-11 早上好，12-17 下午好，其余晚上好 */
    public void refreshGreeting() {
        if (greeting == null) return;
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res = hour < 5 ? R.string.chat_v2_greet_eve
                : hour < 12 ? R.string.chat_v2_greet_am
                : hour < 18 ? R.string.chat_v2_greet_pm : R.string.chat_v2_greet_eve;
        greeting.setText(res);
    }

    /**
     * 填「最近」列表。传空或 null 时整块隐藏（新用户没有任何历史时不留空标题）。
     * 只展示标题与时间，不展示消息条数——demo 的 .ritem 就是这个信息密度。
     */
    public void showRecent(List<ConversationSession> sessions) {
        if (recentContainer == null || recentGroup == null) return;
        recentContainer.removeAllViews();
        if (sessions == null || sessions.isEmpty()) {
            recentGroup.setVisibility(View.GONE);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(recentContainer.getContext());
        int shown = 0;
        for (ConversationSession session : sessions) {
            if (session == null || shown >= MAX_RECENT) continue;
            View row = inflater.inflate(R.layout.item_chat_v2_recent, recentContainer, false);
            TextView name = row.findViewById(R.id.chat_v2_recent_name);
            TextView time = row.findViewById(R.id.chat_v2_recent_time);
            if (name != null) {
                name.setText(TextUtils.isEmpty(session.title)
                        ? recentContainer.getContext().getString(R.string.chat_v2_untitled)
                        : session.title);
            }
            if (time != null) time.setText(formatItemTime(session.updatedAt > 0
                    ? session.updatedAt : session.createdAt));
            row.setOnClickListener(v -> {
                if (listener != null) listener.onOpenSession(session);
            });
            recentContainer.addView(row);
            shown++;
        }
        recentGroup.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
    }

    /**
     * 与消息流的日期分隔行同口径（今天→HH:mm，昨天→"昨天 HH:mm"，更早→M月d日），
     * 判定与文案都收在 {@link ChatDayLabel} 里，避免"同一天在两个界面长成两种样子"。
     */
    private String formatItemTime(long updatedAt) {
        return ChatDayLabel.listTime(recentContainer.getContext(), updatedAt);
    }
}

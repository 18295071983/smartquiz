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
 * 聊天页空状态（v2 首屏）：问候语 + 三个真入口 + 最近会话。
 *
 * <p>显隐不在本类：根视图交给 {@code ChatAdapter#setEmptyView}，由 adapter 的数据观察者按
 * "消息条数是否为 0" 切换，本类只负责把视图和回调接起来，不碰会话存储。</p>
 *
 * <p>2026-11-02（EMPTY-STATE-REAL-ENTRY）：原来这里是 2×2 四张场景卡，点了只把示例问题
 * 填进输入框（demo 的 data-fill 口径）。那不是入口，是替用户打了一遍字——用户自己按发送
 * 谁不会；四句示例还写死在 strings 里，与真实能力各漂各的。现在换成三个点了真的跳转、
 * 去处各不相同的入口：选择模型（本地+在线都能切换的那页）、下载本地模型、看使用说明。
 * 跳转动作全部交回宿主，本类不碰 Intent。
 * 第一行刻意指「模型选择」页而不是「模型中心」：模型中心只有在线注册+用量两个 Tab，
 * <b>选不了本地模型</b>，它原先那个「专用模型」按钮（现改名「语音模型」）反而是跳回模型页——聊天页要的是能直接换引擎模型的地方。
 * 能力介绍写在 greet_sub 那句里，只讲工具类别；某台引擎实际能挂哪些工具由「使用说明」弹框
 * 按注入集动态报（那侧有不变量钉着，见 {@code ChatGuideToolFactsTest}）。
 * 时间口径与历史抽屉的 {@code ChatHistoryAdapter} 保持一致（今天 HH:mm / 昨天 HH:mm / M月d日），
 * 见 {@link ChatDayLabel}。</p>
 */
public final class ChatV2EmptyState {

    private static final String TAG = "ChatV2EmptyState";

    /** 最近列表最多几条（demo 的 .recent 只放两条，多一条用于撑住首屏） */
    private static final int MAX_RECENT = 3;

    /** 空状态交互出口 */
    public interface Listener {
        /** 点「去选择模型」：宿主打开模型选择页（本地与在线都在那一页点选切换） */
        void onSelectModel();

        /** 点「去下载模型」：宿主打开本地模型预设清单 */
        void onDownloadModels();

        /** 点「看使用说明」：宿主强制打开引导弹框（绕过"每引擎只弹一次"闸门） */
        void onShowGuide();

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
        bindEntries();
    }

    /**
     * 页面上没有 v2 空状态视图时返回 null（调用方按 null 走兜底逻辑）。
     */
    public static ChatV2EmptyState bind(Activity activity, Listener listener) {
        if (activity == null || listener == null) return null;
        try {
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

    /**
     * 绑三个入口。每条都必须在布局里有对应 id，且回调交回宿主——
     * 本类不认识 Activity，只认识"该跳哪儿"这件事由外面决定。
     */
    private void bindEntries() {
        bindEntry(R.id.chat_v2_entry_select_model, () -> listener.onSelectModel());
        bindEntry(R.id.chat_v2_download_row, () -> listener.onDownloadModels());
        bindEntry(R.id.chat_v2_entry_guide, () -> listener.onShowGuide());
    }

    private void bindEntry(int viewId, Runnable action) {
        View row = root.findViewById(viewId);
        if (row == null) return;
        row.setOnClickListener(v -> {
            if (listener != null) action.run();
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

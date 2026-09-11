package com.oilquiz.app.ai.chat.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.oilquiz.app.ai.chat.input.ChatInputBar;
import com.oilquiz.app.ai.chat.input.ChatInputManager;
import com.oilquiz.app.ai.chat.input.ChatMessagesView;
import com.oilquiz.app.ai.stats.TokenStatsManager;

/**
 * 聊天页面整页壳（全局可复用装配容器，新建通用设计）。
 *
 * 把 AI 对话页的视图骨架收进一个容器：顶部状态条（GenPhase/KV/Token 统计）
 * + 消息流（ChatMessagesView）+ 输入栏（ChatInputBar）。宿主只注入数据源
 * 与 adapter，本壳负责装配与生命周期转发——页面退化为壳（仅生命周期+数据源）。
 *
 * <pre>
 * ChatShellView shell = findViewById(R.id.chat_shell);
 * shell.bindSources(nativeSource, statsSource);     // 数据源注入
 * shell.setAdapter(chatAdapter);                    // 消息流
 * ChatInputManager input = shell.getInputBar().attachManager(activity, callback, null);
 * shell.onResume();                                 // 启动状态条轮询
 * shell.onStreamingToken(total, tps);               // 流式统计
 * shell.onDestroy();                                // 停止轮询
 * </pre>
 *
 * 本组件只做装配，不持有业务逻辑；数据源接口与
 * {@link GenerationStatusBar.NativeSource} / {@link TokenStatsBar.StatsSource}
 * 完全一致，可单独替换实现（主题/数据通道扩展点）。
 */
public class ChatShellView extends LinearLayout {

    private TextView tvGenPhase;
    private TextView tvKvStats;
    private TextView tvTokenStats;
    private ChatMessagesView messagesView;
    private ChatInputBar inputBar;

    private GenerationStatusBar statusBar;
    private TokenStatsBar tokenBar;
    private ChatStateOverlay overlay;

    public ChatShellView(Context context) { this(context, null); }

    public ChatShellView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        build();
    }

    private void build() {
        // ---- 顶部状态区 ----
        LinearLayout header = new LinearLayout(getContext());
        header.setOrientation(VERTICAL);
        header.setPadding(dp(14), dp(8), dp(14), 0);

        LinearLayout statusRow = new LinearLayout(getContext());
        statusRow.setOrientation(HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        tvGenPhase = new TextView(getContext());
        tvGenPhase.setTextSize(12f);
        tvGenPhase.setTextColor(0xFF6B7280);
        tvGenPhase.setGravity(Gravity.CENTER_VERTICAL);
        tvGenPhase.setVisibility(GONE);
        statusRow.addView(tvGenPhase, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        tvKvStats = new TextView(getContext());
        tvKvStats.setTextSize(11f);
        tvKvStats.setTextColor(0xFF9CA3AF);
        tvKvStats.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        tvKvStats.setVisibility(GONE);
        statusRow.addView(tvKvStats, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        header.addView(statusRow);

        tvTokenStats = new TextView(getContext());
        tvTokenStats.setTextSize(11f);
        tvTokenStats.setTextColor(0xFF9CA3AF);
        tvTokenStats.setGravity(Gravity.CENTER_VERTICAL);
        tvTokenStats.setTypeface(Typeface.MONOSPACE);
        tvTokenStats.setVisibility(GONE);
        header.addView(tvTokenStats, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(header, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // ---- 消息流 ----
        messagesView = new ChatMessagesView(getContext());
        addView(messagesView, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- 输入栏 ----
        inputBar = new ChatInputBar(getContext());
        addView(inputBar, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    // ==================== 装配 API ====================

    /** 注入状态条数据源（生成状态 + Token 统计），可分别传 null 关闭对应条 */
    public void bindSources(@Nullable GenerationStatusBar.NativeSource nativeSource,
                            @Nullable TokenStatsBar.StatsSource statsSource) {
        if (nativeSource != null) {
            statusBar = new GenerationStatusBar(getContext(), tvGenPhase, tvKvStats, nativeSource);
        }
        if (statsSource != null) {
            tokenBar = new TokenStatsBar(getContext(), tvTokenStats, statsSource);
        }
    }

    /** 绑定消息流 adapter（ChatAdapter 或任意 RecyclerView.Adapter） */
    public void setAdapter(RecyclerView.Adapter<?> adapter) {
        messagesView.setAdapter(adapter);
    }

    // ==================== 门面转发 ====================

    public void onResume() {
        if (statusBar != null) statusBar.startPolling();
    }

    public void onDestroy() {
        if (statusBar != null) statusBar.stopPolling();
    }

    /** 刷新生成状态条（流式回调里定期调用） */
    public void refreshStatusBar() {
        if (statusBar != null) statusBar.refresh();
    }

    /** 思考段到达（流式回调喂显示文本与行数） */
    public void onThinkingSegment(String disp, int lineCount) {
        if (statusBar != null) statusBar.onThinkingSegment(disp, lineCount);
    }

    /** 流式实时统计 */
    public void onStreamingToken(int totalTokens, float tokensPerSecond) {
        if (tokenBar != null) tokenBar.updateFromStreaming(totalTokens, tokensPerSecond);
    }

    /** 完成态统计（TokenStatsManager 汇总） */
    public void onTokenStats(TokenStatsManager.TokenStats stats) {
        if (tokenBar != null) tokenBar.updateFromStats(stats);
    }

    /** 切换思考指示器（推理中/完成） */
    public void setInferring(boolean inferring) {
        if (overlay == null) {
            overlay = new ChatStateOverlay.Builder()
                    .listView(messagesView)
                    .build();
        }
        if (inferring) overlay.showLoading();
        else overlay.hideLoading();
    }

    // ==================== 子件访问 ====================

    public ChatMessagesView getMessagesView() { return messagesView; }

    public ChatInputBar getInputBar() { return inputBar; }

    /** 输入栏一键接线（ChatInputManager） */
    public ChatInputManager attachInput(android.app.Activity activity,
                                        ChatInputManager.Callback callback,
                                        @Nullable RecyclerView attachmentList) {
        return inputBar.attachManager(activity, callback, attachmentList);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}

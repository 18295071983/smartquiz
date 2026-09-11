package com.oilquiz.app.ai.agent.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.ai.agent.AgentCallback;
import com.oilquiz.app.ai.agent.AgentSession;
import com.oilquiz.app.ai.agent.online.OnlineToolResult;
import com.oilquiz.app.ai.chat.AgentExecutionView;

/**
 * 全局 Agent 会话渲染容器 —— 复用 AI 对话页 ChatAdapter 同款执行流视图（{@link AgentExecutionView}）。
 *
 * 独立于消息气泡体系：直接订阅 {@link AgentSession} 事件，把智能体执行流
 * （思考 / 正文 / 步骤 / 工具调用 / 完成 / 错误）渲染到同一套成熟渲染组件上，
 * 可嵌入任意页面或作为弹层宿主，渲染观感与对话页完全一致。
 *
 * 用法：
 * <pre>
 * AgentSessionView agentView = new AgentSessionView(context);
 * someContainer.addView(agentView, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
 * agentView.setSession(AgentSession.create(context));
 * agentView.start("帮我整理需求", 8192);
 * </pre>
 */
public class AgentSessionView extends LinearLayout {

    private static final int COLOR_BG = 0xFFF8FAFC;
    private static final int COLOR_BORDER = 0xFFE2E8F0;
    private static final int COLOR_TEXT = 0xFF1F2937;
    private static final int COLOR_SUB = 0xFF64748B;
    private static final int COLOR_ACCENT = 0xFF2563EB;
    private static final int COLOR_OK = 0xFF059669;
    private static final int COLOR_ERR = 0xFFDC2626;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private AgentSession session;
    private final TextView titleView;
    private final TextView statusView;
    private final Button stopButton;
    /** ChatAdapter 同款 Agent 执行流视图（步骤/日志/工具调用/统计） */
    private final AgentExecutionView agentExecution;
    private final LinearLayout thinkSection;
    private final TextView thinkView;
    private final TextView bodyView;
    private final TextView footView;

    private final StringBuilder thinkBuf = new StringBuilder();
    private final StringBuilder bodyBuf = new StringBuilder();

    public AgentSessionView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(14), dp(12), dp(14), dp(12));
        setBackground(rounded(COLOR_BG, COLOR_BORDER, dp(12), 1));

        // 头部：标题 + 状态 + 停止
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        titleView = new TextView(context);
        titleView.setText("智能体执行");
        titleView.setTextColor(COLOR_TEXT);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setLayoutParams(new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        statusView = new TextView(context);
        statusView.setText("就绪");
        statusView.setTextColor(COLOR_SUB);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        stopButton = new Button(context);
        stopButton.setText("停止");
        stopButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        stopButton.setTextColor(COLOR_ERR);
        stopButton.setBackground(rounded(0xFFFFFFFF, 0xFFFCA5A5, dp(8), 1));
        stopButton.setPadding(dp(10), 0, dp(10), 0);
        stopButton.setVisibility(GONE);
        stopButton.setOnClickListener(v -> stop());
        header.addView(titleView);
        header.addView(statusView);
        header.addView(stopButton);
        addView(header);

        // 思考区（折叠容器，无内容时隐藏）
        thinkSection = new LinearLayout(context);
        thinkSection.setOrientation(VERTICAL);
        thinkSection.setPadding(dp(10), dp(8), dp(10), dp(8));
        thinkSection.setBackground(rounded(0xFFF1F5F9, 0xFFCBD5E1, dp(8), 1));
        thinkSection.setVisibility(GONE);
        TextView thinkLabel = new TextView(context);
        thinkLabel.setText("💭 思考过程");
        thinkLabel.setTextColor(COLOR_ACCENT);
        thinkLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        thinkView = new TextView(context);
        thinkView.setTextColor(COLOR_SUB);
        thinkView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        thinkView.setLineSpacing(0, 1.15f);
        thinkSection.addView(thinkLabel);
        thinkSection.addView(thinkView);
        LayoutParams thinkLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        thinkLp.topMargin = dp(8);
        addView(thinkSection, thinkLp);

        // 执行流核心：ChatAdapter 同款 AgentExecutionView（步骤/日志/工具调用/统计）
        agentExecution = new AgentExecutionView(context);
        LayoutParams execLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        execLp.topMargin = dp(8);
        addView(agentExecution, execLp);

        // 正文区
        bodyView = new TextView(context);
        bodyView.setTextColor(COLOR_TEXT);
        bodyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        bodyView.setLineSpacing(0, 1.25f);
        bodyView.setVisibility(GONE);
        LayoutParams bodyLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = dp(8);
        addView(bodyView, bodyLp);

        // 尾部状态
        footView = new TextView(context);
        footView.setTextColor(COLOR_SUB);
        footView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        LayoutParams footLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footLp.topMargin = dp(8);
        addView(footView, footLp);
    }

    // ==================== 对外 API ====================

    /** 绑定 Agent 会话（事件桥接 AgentCallback → 与 ChatAdapter 同款渲染） */
    public void setSession(AgentSession session) {
        this.session = session;
        session.setCallback(new AgentCallback() {
            @Override public void onToken(String token) {
                mainHandler.post(() -> appendBody(token));
            }
            @Override public void onThinkingToken(String token) {
                mainHandler.post(() -> appendThink(token));
            }
            @Override public void onThinkingEnd() {
                mainHandler.post(() -> setStatus("思考结束", COLOR_SUB));
            }
            @Override public void onToolCallStart(String toolCallId, String toolName, String args) {
                mainHandler.post(() -> {
                    setStatus("调用工具 " + toolName, COLOR_ACCENT);
                    agentExecution.updateToolCall(toolName, args);
                });
            }
            @Override public void onToolCallComplete(String toolCallId, String toolName, OnlineToolResult result) {
                mainHandler.post(() -> {
                    boolean ok = result != null && result.success;
                    String summary = result != null
                            ? (result.success ? result.result : result.error)
                            : null;
                    agentExecution.updateToolResult(toolName, ok, summary);
                });
            }
            @Override public void onStepUpdate(String step, String detail) {
                mainHandler.post(() -> {
                    setStatus(step + (detail != null && !detail.isEmpty() ? " · " + detail : ""), COLOR_SUB);
                    agentExecution.addLogEntry(step.toUpperCase(), detail != null ? detail : "");
                });
            }
            @Override public void onComplete(String fullText) {
                mainHandler.post(() -> {
                    setStatus("完成", COLOR_OK);
                    stopButton.setVisibility(GONE);
                    agentExecution.completeExecution(fullText != null ? fullText : "");
                    if (fullText != null && !fullText.isEmpty() && bodyBuf.length() == 0) {
                        appendBody(fullText);
                    }
                });
            }
            @Override public void onError(String error) {
                mainHandler.post(() -> {
                    setStatus("失败", COLOR_ERR);
                    stopButton.setVisibility(GONE);
                    agentExecution.failExecution(error != null ? error : "未知错误");
                    footView.setText("错误：" + (error != null ? error : "未知错误"));
                    footView.setTextColor(COLOR_ERR);
                });
            }
            @Override public void onThinking(String thought) {
                mainHandler.post(() -> {
                    if (thought != null && !thought.isEmpty()) appendThink(thought);
                });
            }
        });
    }

    /** 启动一轮智能体执行 */
    public void start(String prompt, int maxTokens) {
        reset();
        setStatus("执行中", COLOR_ACCENT);
        stopButton.setVisibility(VISIBLE);
        if (session != null) session.start(prompt, maxTokens);
    }

    /** 启动一轮智能体执行（指定深度思考） */
    public void start(String prompt, int maxTokens, boolean enableThinking) {
        reset();
        setStatus("执行中", COLOR_ACCENT);
        stopButton.setVisibility(VISIBLE);
        if (session != null) session.start(prompt, maxTokens, enableThinking);
    }

    /** 取消当前执行 */
    public void stop() {
        if (session != null) session.stop();
        stopButton.setVisibility(GONE);
        setStatus("已取消", COLOR_SUB);
    }

    /** 清空并复位（下次 start 前自动调用，一般无需手动） */
    public void reset() {
        thinkBuf.setLength(0);
        bodyBuf.setLength(0);
        thinkSection.setVisibility(GONE);
        thinkView.setText("");
        bodyView.setText("");
        bodyView.setVisibility(GONE);
        footView.setText("");
        footView.setTextColor(COLOR_SUB);
        agentExecution.startExecution();
        setStatus("执行中", COLOR_ACCENT);
    }

    // ==================== 内部渲染 ====================

    private void appendThink(String token) {
        thinkBuf.append(token);
        thinkView.setText(thinkBuf);
        thinkSection.setVisibility(VISIBLE);
    }

    private void appendBody(String token) {
        bodyBuf.append(token);
        bodyView.setText(bodyBuf);
        bodyView.setVisibility(VISIBLE);
        setStatus("生成中", COLOR_ACCENT);
    }

    private void setStatus(String text, int color) {
        statusView.setText(text);
        statusView.setTextColor(color);
    }

    private GradientDrawable rounded(int fill, int stroke, int radius, int strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setStroke(strokeWidth, stroke);
        d.setCornerRadius(radius);
        return d;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}

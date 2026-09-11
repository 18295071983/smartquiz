package com.oilquiz.app.ai.agent.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.agent.AgentCallback;
import com.oilquiz.app.ai.agent.AgentSession;
import com.oilquiz.app.ai.agent.online.OnlineToolResult;
import com.oilquiz.app.ai.chat.AgentExecutionView;
import com.oilquiz.app.ai.chat.render.RenderExecutor;
import com.oilquiz.app.theme.ThemeColors;

import io.noties.markwon.core.spans.TextViewSpan;

/**
 * 全局 Agent 会话渲染容器 —— 复用 AI 对话页 ChatAdapter 同款执行流视图（{@link AgentExecutionView}）。
 *
 * 本地化适配（跟随项目主题系统）：
 * - 色板走 {@link ThemeColors}（语义色，跟随换肤 / 深浅色 / 系统动态色）；
 * - 思考过程与主消息输出走 {@link RenderExecutor}（Markdown / 代码高亮 / 表格 / 图片 / 链接，
 *   与对话页正文渲染完全一致），流式刷新 120ms 节流；
 * - 思考区支持点击标题折叠 / 展开。
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

    private static final int COLOR_OK = 0xFF059669;
    private static final int COLOR_ERR = 0xFFDC2626;
    /** 流式渲染节流间隔（与对话页思考刷新一致） */
    private static final long REFRESH_INTERVAL_MS = 120;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private AgentSession session;
    private final TextView titleView;
    private final TextView statusView;
    private final Button stopButton;
    /** ChatAdapter 同款 Agent 执行流视图（步骤/日志/工具调用/统计） */
    private final AgentExecutionView agentExecution;
    private final LinearLayout thinkSection;
    private final TextView thinkLabel;
    private final TextView thinkView;
    private final TextView bodyView;
    private final TextView footView;

    private final StringBuilder thinkBuf = new StringBuilder();
    private final StringBuilder bodyBuf = new StringBuilder();
    private boolean thinkExpanded = true;
    private boolean refreshScheduled = false;

    public AgentSessionView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(14), dp(12), dp(14), dp(12));
        setBackground(rounded(ThemeColors.get(context, R.color.surface),
                ThemeColors.get(context, R.color.outline), dp(12), 1));

        // 头部：标题 + 状态 + 停止
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        titleView = new TextView(context);
        titleView.setText("智能体执行");
        titleView.setTextColor(ThemeColors.get(context, R.color.text_primary));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setLayoutParams(new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        statusView = new TextView(context);
        statusView.setText("就绪");
        statusView.setTextColor(ThemeColors.get(context, R.color.text_secondary));
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

        // 思考区（折叠容器，无内容时隐藏；点击标题折叠/展开）
        thinkSection = new LinearLayout(context);
        thinkSection.setOrientation(VERTICAL);
        thinkSection.setPadding(dp(10), dp(8), dp(10), dp(8));
        thinkSection.setBackground(rounded(ThemeColors.get(context, R.color.surface_variant),
                ThemeColors.get(context, R.color.outline), dp(8), 1));
        thinkSection.setVisibility(GONE);
        thinkLabel = new TextView(context);
        thinkLabel.setText("💭 思考过程");
        thinkLabel.setTextColor(ThemeColors.get(context, R.color.primary));
        thinkLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        thinkLabel.setTypeface(Typeface.DEFAULT_BOLD);
        thinkLabel.setClickable(true);
        thinkLabel.setOnClickListener(v -> toggleThink());
        thinkView = new TextView(context);
        thinkView.setTextColor(ThemeColors.get(context, R.color.text_secondary));
        thinkView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        thinkView.setLineSpacing(0, 1.15f);
        thinkView.setMovementMethod(LinkMovementMethod.getInstance());
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

        // 正文区（对话页同款 Markdown 渲染，支持代码/表格/图片/链接）
        bodyView = new TextView(context);
        bodyView.setTextColor(ThemeColors.get(context, R.color.text_primary));
        bodyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        bodyView.setLineSpacing(0, 1.25f);
        bodyView.setMovementMethod(LinkMovementMethod.getInstance());
        bodyView.setVisibility(GONE);
        LayoutParams bodyLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = dp(8);
        addView(bodyView, bodyLp);

        // 尾部状态
        footView = new TextView(context);
        footView.setTextColor(ThemeColors.get(context, R.color.text_secondary));
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
                mainHandler.post(() -> { bodyBuf.append(token); scheduleRender(); });
            }
            @Override public void onThinkingToken(String token) {
                mainHandler.post(() -> { thinkBuf.append(token); scheduleRender(); });
            }
            @Override public void onThinkingEnd() {
                mainHandler.post(() -> setStatus("思考结束", ThemeColors.get(getContext(), R.color.text_secondary)));
            }
            @Override public void onToolCallStart(String toolCallId, String toolName, String args) {
                mainHandler.post(() -> {
                    setStatus("调用工具 " + toolName, ThemeColors.get(getContext(), R.color.primary));
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
                    setStatus(step + (detail != null && !detail.isEmpty() ? " · " + detail : ""),
                            ThemeColors.get(getContext(), R.color.text_secondary));
                    agentExecution.addLogEntry(step.toUpperCase(), detail != null ? detail : "");
                });
            }
            @Override public void onComplete(String fullText) {
                mainHandler.post(() -> {
                    setStatus("完成", COLOR_OK);
                    stopButton.setVisibility(GONE);
                    agentExecution.completeExecution(fullText != null ? fullText : "");
                    if (fullText != null && !fullText.isEmpty() && bodyBuf.length() == 0) {
                        bodyBuf.append(fullText);
                    }
                    flushRender();
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
                    if (thought != null && !thought.isEmpty()) {
                        thinkBuf.append(thought);
                        scheduleRender();
                    }
                });
            }
        });
    }

    /** 启动一轮智能体执行 */
    public void start(String prompt, int maxTokens) {
        reset();
        setStatus("执行中", ThemeColors.get(getContext(), R.color.primary));
        stopButton.setVisibility(VISIBLE);
        if (session != null) session.start(prompt, maxTokens);
    }

    /** 启动一轮智能体执行（指定深度思考） */
    public void start(String prompt, int maxTokens, boolean enableThinking) {
        reset();
        setStatus("执行中", ThemeColors.get(getContext(), R.color.primary));
        stopButton.setVisibility(VISIBLE);
        if (session != null) session.start(prompt, maxTokens, enableThinking);
    }

    /** 取消当前执行 */
    public void stop() {
        if (session != null) session.stop();
        stopButton.setVisibility(GONE);
        setStatus("已取消", ThemeColors.get(getContext(), R.color.text_secondary));
    }

    /** 清空并复位（下次 start 前自动调用，一般无需手动） */
    public void reset() {
        thinkBuf.setLength(0);
        bodyBuf.setLength(0);
        thinkExpanded = true;
        refreshScheduled = false;
        thinkSection.setVisibility(GONE);
        thinkView.setText("");
        thinkLabel.setText("💭 思考过程");
        bodyView.setText("");
        bodyView.setVisibility(GONE);
        footView.setText("");
        footView.setTextColor(ThemeColors.get(getContext(), R.color.text_secondary));
        agentExecution.startExecution();
        setStatus("执行中", ThemeColors.get(getContext(), R.color.primary));
    }

    // ==================== 内部渲染 ====================

    /** 思考/正文流式渲染节流：120ms 批量渲染一次，避免每 token 全量 Markdown 渲染卡顿 */
    private void scheduleRender() {
        if (refreshScheduled) return;
        refreshScheduled = true;
        mainHandler.postDelayed(this::flushRender, REFRESH_INTERVAL_MS);
    }

    /** 立即渲染思考区 + 正文区（Markdown，与对话页同款） */
    private void flushRender() {
        refreshScheduled = false;
        if (thinkBuf.length() > 0) {
            renderSpanned(thinkView, thinkBuf.toString());
            thinkSection.setVisibility(VISIBLE);
            thinkView.setVisibility(thinkExpanded ? VISIBLE : GONE);
        }
        if (bodyBuf.length() > 0) {
            renderSpanned(bodyView, bodyBuf.toString());
            bodyView.setVisibility(VISIBLE);
            setStatus("生成中", ThemeColors.get(getContext(), R.color.primary));
        }
    }

    /** 对话页同款渲染：RenderExecutor 检测 Markdown/LaTeX/Mermaid/HTML → Spanned + TextViewSpan 绑定 */
    private void renderSpanned(TextView tv, String text) {
        int w = getWidth() > 0 ? getWidth() - getPaddingLeft() - getPaddingRight() : 0;
        Spanned rendered = RenderExecutor.getInstance().execute(text, getContext(), w);
        Spannable spannable = new SpannableStringBuilder(rendered);
        TextViewSpan.applyTo(spannable, tv);
        tv.setText(spannable);
    }

    /** 点击思考标题：折叠 / 展开内容 */
    private void toggleThink() {
        thinkExpanded = !thinkExpanded;
        thinkView.setVisibility(thinkExpanded ? VISIBLE : GONE);
        thinkLabel.setText(thinkExpanded ? "💭 思考过程" : "💭 思考过程（已折叠，点击展开）");
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

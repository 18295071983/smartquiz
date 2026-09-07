package com.oilquiz.app.ai.chat;

import com.oilquiz.app.theme.ThemeColors;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.oilquiz.app.R;

import java.util.LinkedList;
import java.util.List;

public class AgentExecutionView extends LinearLayout {

    private TextView tvTitleIcon;
    private TextView tvTitle;
    private TextView tvStepCount;
    private ScrollView scrollLogs;
    private LinearLayout logsContainer;
    private TextView tvStepType;
    private TextView tvInferenceSpeed;
    private TextView tvTokenCount;
    private TextView tvStepContent;
    private LinearLayout toolParamsContainer;
    private TextView tvToolParams;
    private LinearLayout toolResultContainer;
    private TextView tvToolResultStatus;
    private TextView tvToolResultName;
    private TextView tvToolResultContent;
    private TextView tvTotalTime;
    private TextView tvTotalTokens;

    private List<LogEntry> logEntries = new LinkedList<>();
    private int maxLogEntries = 50;
    private long startTime;
    private int totalTokens;
    private float currentSpeed;

    public AgentExecutionView(Context context) {
        super(context);
        init(context);
    }

    public AgentExecutionView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public AgentExecutionView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.agent_execution_view, this, true);

        tvTitleIcon = findViewById(R.id.tv_title_icon);
        tvTitle = findViewById(R.id.tv_title);
        tvStepCount = findViewById(R.id.tv_step_count);
        scrollLogs = findViewById(R.id.scroll_logs);
        logsContainer = findViewById(R.id.logs_container);
        tvStepType = findViewById(R.id.tv_step_type);
        tvInferenceSpeed = findViewById(R.id.tv_inference_speed);
        tvTokenCount = findViewById(R.id.tv_token_count);
        tvStepContent = findViewById(R.id.tv_step_content);
        toolParamsContainer = findViewById(R.id.tool_params_container);
        tvToolParams = findViewById(R.id.tv_tool_params);
        toolResultContainer = findViewById(R.id.tool_result_container);
        tvToolResultStatus = findViewById(R.id.tv_tool_result_status);
        tvToolResultName = findViewById(R.id.tv_tool_result_name);
        tvToolResultContent = findViewById(R.id.tv_tool_result_content);
        tvTotalTime = findViewById(R.id.tv_total_time);
        tvTotalTokens = findViewById(R.id.tv_total_tokens);

        startTime = System.currentTimeMillis();
        hide();
    }

    public void startExecution() {
        startTime = System.currentTimeMillis();
        totalTokens = 0;
        currentSpeed = 0;
        logEntries.clear();
        logsContainer.removeAllViews();

        tvTitle.setText("Agent执行中...");
        tvTitleIcon.setText("🤖");
        tvStepCount.setText("步骤 1/?");
        tvTotalTime.setText("已用时间: 0秒");
        tvTotalTokens.setText("总计: 0 token");

        show();
    }

    public void addLogEntry(String type, String content) {
        LogEntry entry = new LogEntry(type, content, System.currentTimeMillis());
        logEntries.add(entry);

        if (logEntries.size() > maxLogEntries) {
            logEntries.remove(0);
        }

        TextView logView = new TextView(getContext());
        logView.setTextSize(12f);
        logView.setLineSpacing(0, 1.2f);

        // 日志标签颜色：使用主题色资源（浅色/夜间自动适配），替代硬编码十六进制，
        // 避免夜间模式下深底浅字/浅底深字对比度不足看不清
        int typeColorRes = getTypeColorRes(type);
        String typeColorHex = colorToHex(ThemeColors.get(getContext(), typeColorRes));
        // 内容正文使用主题主文字色（text_primary，比 text_secondary 更深），
        // 保证浅色/夜间两种主题下日志都清晰可读
        String contentColorHex = colorToHex(ThemeColors.attr(getContext(), R.attr.colorControlText));
        String prefix = "<font color=\"" + typeColorHex + "\">" + getTypeIcon(type) + " [" + type + "]</font> ";
        logView.setText(android.text.Html.fromHtml(prefix
                + "<font color=\"" + contentColorHex + "\">" + escapeHtml(content) + "</font>",
                android.text.Html.FROM_HTML_MODE_LEGACY));
        logView.setTextIsSelectable(true);
        logView.setPadding(0, 2, 0, 2);

        logsContainer.addView(logView);

        scrollLogs.post(() -> scrollLogs.fullScroll(ScrollView.FOCUS_DOWN));
    }

    public void updateCurrentStep(int stepNumber, String stepType, String title, String content) {
        tvStepCount.setText("步骤 " + stepNumber + "/?");
        tvStepType.setText(stepType);
        tvStepContent.setText(content != null ? content : "");

        String typeColor = getStepTypeColor(stepType);
        tvStepType.setTextColor(getContext().getResources().getColor(getColorRes(typeColor)));
        tvStepType.setBackgroundResource(getBackgroundRes(typeColor));

        toolParamsContainer.setVisibility(View.GONE);
        toolResultContainer.setVisibility(View.GONE);

        if (stepType.contains("TOOL_CALL")) {
            tvTitleIcon.setText("🔧");
        } else if (stepType.contains("TOOL_RESULT")) {
            tvTitleIcon.setText("✅");
        } else if (stepType.contains("INFERENCE")) {
            tvTitleIcon.setText("🧠");
        } else if (stepType.contains("THINKING")) {
            tvTitleIcon.setText("💭");
        } else {
            tvTitleIcon.setText("🤖");
        }
    }

    public void updateToolCall(String toolName, String args) {
        toolParamsContainer.setVisibility(View.VISIBLE);
        tvToolParams.setText(args != null ? args : "{}");
        updateCurrentStep(0, "工具调用", "调用工具: " + toolName, "正在执行 " + toolName + "...");
    }

    public void updateToolResult(String toolName, boolean success, String result) {
        toolResultContainer.setVisibility(View.VISIBLE);
        tvToolResultStatus.setText(success ? "✓ 成功" : "✗ 失败");
        tvToolResultStatus.setTextColor(getContext().getResources().getColor(
            success ? R.color.success : R.color.error));
        tvToolResultName.setText(toolName);
        tvToolResultContent.setText(result != null ? result : "");

        updateCurrentStep(0, "工具结果", "执行完成: " + toolName, success ? "工具执行成功" : "工具执行失败");
    }

    public void updateInferenceProgress(int tokenCount, float tokensPerSecond) {
        this.totalTokens = tokenCount;
        this.currentSpeed = tokensPerSecond;

        tvTokenCount.setText(tokenCount + " token");
        tvInferenceSpeed.setText(String.format("%.1f t/s", tokensPerSecond));
        tvTotalTokens.setText("总计: " + tokenCount + " token");

        updateElapsedTime();
    }

    public void appendToken(String token) {
        String currentContent = tvStepContent.getText().toString();
        tvStepContent.setText(currentContent + token);
    }

    public void updateElapsedTime() {
        long elapsed = System.currentTimeMillis() - startTime;
        long seconds = elapsed / 1000;
        tvTotalTime.setText(String.format("已用时间: %d秒", seconds));
    }

    public void completeExecution(String finalContent) {
        tvTitle.setText("Agent执行完成");
        tvTitleIcon.setText("🎉");
        tvStepType.setText("完成");
        tvStepType.setTextColor(getContext().getResources().getColor(R.color.success));
        tvStepType.setBackgroundResource(R.drawable.agent_step_type_tag_success);

        if (finalContent != null && !finalContent.isEmpty()) {
            tvStepContent.setText(finalContent);
        }

        updateElapsedTime();
    }

    public void failExecution(String error) {
        tvTitle.setText("Agent执行失败");
        tvTitleIcon.setText("❌");
        tvStepType.setText("失败");
        tvStepType.setTextColor(getContext().getResources().getColor(R.color.error));
        tvStepType.setBackgroundResource(R.drawable.agent_step_type_tag_error);
        tvStepContent.setText(error != null ? error : "未知错误");

        updateElapsedTime();
    }

    public void show() {
        setVisibility(View.VISIBLE);
        animate().alpha(1f).setDuration(200).start();
    }

    public void hide() {
        setVisibility(View.GONE);
        setAlpha(1f);
    }

    /**
     * 返回日志类型对应的主题色资源 id（浅色/夜间自动适配）。
     * 替换旧 getTypeColor（硬编码十六进制，夜间模式下对比度不足）。
     */
    private int getTypeColorRes(String type) {
        if (type == null) return R.color.text_secondary;
        String upper = type.toUpperCase();
        if (upper.contains("THINKING") || upper.contains("思考")) return R.color.blue;
        if (upper.contains("TOOL_CALL") || upper.contains("工具调用")) return R.color.success;
        if (upper.contains("TOOL_RESULT") || upper.contains("工具结果")) return R.color.warning;
        if (upper.contains("ERROR") || upper.contains("失败")) return R.color.error;
        if (upper.contains("INFERENCE") || upper.contains("推理")) return R.color.purple_500;
        return R.color.text_secondary;
    }

    /** 将 int 颜色转为 #RRGGBB 十六进制（用于 Html 片段） */
    private static String colorToHex(int color) {
        return String.format(java.util.Locale.US, "#%06X", 0xFFFFFF & color);
    }

    /** 转义 HTML 特殊字符，避免日志内容破坏 fromHtml 结构 */
    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String getTypeIcon(String type) {
        if (type == null) return "📋";
        String upper = type.toUpperCase();
        if (upper.contains("THINKING") || upper.contains("思考")) return "💭";
        if (upper.contains("TOOL_CALL") || upper.contains("工具调用")) return "🔧";
        if (upper.contains("TOOL_RESULT") || upper.contains("工具结果")) return "📊";
        if (upper.contains("ERROR") || upper.contains("失败")) return "❌";
        if (upper.contains("INFERENCE") || upper.contains("推理")) return "🧠";
        return "📋";
    }

    private String getStepTypeColor(String stepType) {
        if (stepType == null) return "default";
        String upper = stepType.toUpperCase();
        if (upper.contains("TOOL_CALL")) return "green";
        if (upper.contains("TOOL_RESULT")) return "orange";
        if (upper.contains("INFERENCE")) return "purple";
        if (upper.contains("THINKING")) return "blue";
        if (upper.contains("COMPLETE") || upper.contains("SUCCESS")) return "success";
        if (upper.contains("ERROR") || upper.contains("FAIL")) return "error";
        return "default";
    }

    private int getColorRes(String colorName) {
        switch (colorName) {
            case "green": return R.color.primary;
            case "orange": return R.color.accent;
            case "purple": return R.color.purple_500;
            case "blue": return R.color.blue;
            case "success": return R.color.success;
            case "error": return R.color.error;
            default: return R.color.text_secondary;
        }
    }

    private int getBackgroundRes(String colorName) {
        switch (colorName) {
            case "green": return R.drawable.agent_step_type_tag_green;
            case "orange": return R.drawable.agent_step_type_tag_orange;
            case "purple": return R.drawable.agent_step_type_tag_purple;
            case "blue": return R.drawable.agent_step_type_tag_blue;
            case "success": return R.drawable.agent_step_type_tag_success;
            case "error": return R.drawable.agent_step_type_tag_error;
            default: return R.drawable.agent_step_type_tag;
        }
    }

    private static class LogEntry {
        final String type;
        final String content;
        final long timestamp;

        LogEntry(String type, String content, long timestamp) {
            this.type = type;
            this.content = content;
            this.timestamp = timestamp;
        }
    }
}
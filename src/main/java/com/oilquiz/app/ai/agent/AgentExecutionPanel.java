package com.oilquiz.app.ai.agent;

import android.content.Context;
import android.graphics.Color;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.oilquiz.app.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AgentExecutionPanel extends LinearLayout {

    private LinearLayout blocksContainer;
    private TextView statusText;
    private TextView statsText;
    private ScrollView scrollView;
    private View headerView;

    private AgentExecutionState state;
    private final List<View> blockViews = new ArrayList<>();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    // 记录每个block的折叠状态
    private final Map<Integer, Boolean> blockCollapsed = new HashMap<>();

    // 上次渲染的版本号，用于避免重复刷新
    private long lastRenderedVersion = -1;

    public AgentExecutionPanel(Context context) {
        super(context);
        init(context);
    }

    public AgentExecutionPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public AgentExecutionPanel(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setOrientation(VERTICAL);
        setBackgroundResource(R.drawable.agent_execution_background);
        int padding = dpToPx(12);
        setPadding(padding, padding, padding, padding);

        LayoutInflater inflater = LayoutInflater.from(context);

        headerView = inflater.inflate(R.layout.agent_panel_header, this, false);
        addView(headerView);

        statusText = headerView.findViewById(R.id.tv_status);
        statsText = headerView.findViewById(R.id.tv_stats);

        scrollView = new ScrollView(context);
        LayoutParams scrollParams = new LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        );
        scrollParams.topMargin = dpToPx(8);
        scrollView.setLayoutParams(scrollParams);

        blocksContainer = new LinearLayout(context);
        blocksContainer.setOrientation(VERTICAL);
        scrollView.addView(blocksContainer);

        addView(scrollView);
    }

    public void setState(AgentExecutionState state) {
        if (this.state != state) {
            // state引用变化（新消息或重置），清空已渲染的blocks
            this.state = state;
            this.lastRenderedVersion = -1;
            this.blockCollapsed.clear();
            blocksContainer.removeAllViews();
            blockViews.clear();
            updateFromState();
            // 记录当前版本号，避免紧跟的refresh()重复更新
            lastRenderedVersion = state.getVersion();
        }
    }

    public AgentExecutionState getState() {
        return state;
    }

    public void refresh() {
        if (state == null) return;
        // 版本号检测：如果数据没变化，跳过刷新
        long currentVersion = state.getVersion();
        if (currentVersion == lastRenderedVersion) return;
        lastRenderedVersion = currentVersion;
        updateFromState();
    }

    private void updateFromState() {
        if (state == null) return;

        updateHeader();
        syncBlocks();
    }

    private void updateHeader() {
        AgentExecutionState.ExecutionStatus status = state.getStatus();
        String statusTextStr = getStatusText(status);
        int statusColor = getStatusColor(status);
        statusText.setText(statusTextStr);
        statusText.setTextColor(statusColor);

        String stats = String.format(Locale.getDefault(),
            "步骤: %d | Token: %d | %.1f t/s",
            state.getCurrentStep(), state.getTotalTokens(), state.getTokensPerSecond());
        statsText.setText(stats);
    }

    private String getStatusText(AgentExecutionState.ExecutionStatus status) {
        switch (status) {
            case PENDING: return "⏳ 准备中...";
            case RUNNING: return "⚡ 执行中...";
            case COMPLETED: return "✅ 已完成";
            case FAILED: return "❌ 执行失败";
            case CANCELLED: return "⏹️ 已取消";
            default: return "";
        }
    }

    private int getStatusColor(AgentExecutionState.ExecutionStatus status) {
        switch (status) {
            case RUNNING: return getResources().getColor(R.color.primary);
            case COMPLETED: return getResources().getColor(R.color.success);
            case FAILED: return getResources().getColor(R.color.error);
            case CANCELLED: return getResources().getColor(R.color.text_secondary);
            default: return getResources().getColor(R.color.text_secondary);
        }
    }

    private void syncBlocks() {
        // 使用快照避免并发修改
        List<AgentExecutionState.ExecutionBlock> blocks = state.getBlocksSnapshot();

        // 增量添加：只创建新block对应的View
        while (blockViews.size() < blocks.size()) {
            int index = blockViews.size();
            View blockView = createBlockView(blocks.get(index));
            blockViews.add(blockView);
            blocksContainer.addView(blockView);
        }

        // 更新已有block的内容（内容可能变化，如token追加）
        for (int i = 0; i < blocks.size() && i < blockViews.size(); i++) {
            updateBlockView(blockViews.get(i), blocks.get(i));
        }

        // 移除多余的View（reset后block数量可能减少）
        while (blockViews.size() > blocks.size()) {
            View view = blockViews.remove(blockViews.size() - 1);
            blocksContainer.removeView(view);
        }

        post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private View createBlockView(AgentExecutionState.ExecutionBlock block) {
        View view;
        switch (block.getType()) {
            case THINKING:
                view = createThinkingBlock(block);
                break;
            case PLANNING:
                view = createPlanningBlock(block);
                break;
            case TOOL_CALL:
                view = createToolCallBlock(block);
                break;
            case TOOL_RESULT:
                view = createToolResultBlock(block);
                break;
            case INFERENCE:
                view = createInferenceBlock(block);
                break;
            case OBSERVATION:
                view = createObservationBlock(block);
                break;
            case REFLECTION:
                view = createReflectionBlock(block);
                break;
            case ERROR:
                view = createErrorBlock(block);
                break;
            case STATS:
                view = createStatsBlock(block);
                break;
            case TEXT:
            default:
                view = createTextBlock(block);
                break;
        }

        LayoutParams params = new LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dpToPx(6);
        view.setLayoutParams(params);
        return view;
    }

    private View createThinkingBlock(AgentExecutionState.ExecutionBlock block) {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.agent_block_thinking, null);
        bindBlockHeader(view, block, "💭 思考", R.color.primary);
        return view;
    }

    private View createPlanningBlock(AgentExecutionState.ExecutionBlock block) {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.agent_block_text, null);
        bindBlockHeader(view, block, "📋 规划", R.color.blue);
        return view;
    }

    private View createToolCallBlock(AgentExecutionState.ExecutionBlock block) {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.agent_block_tool_call, null);
        bindBlockHeader(view, block, "🔧 调用工具", R.color.accent);

        TextView toolNameView = view.findViewById(R.id.tv_tool_name);
        TextView toolArgsView = view.findViewById(R.id.tv_tool_args);

        if (toolNameView != null && block.getToolName() != null) {
            toolNameView.setText(block.getToolName());
        }
        if (toolArgsView != null && block.getToolArgs() != null) {
            toolArgsView.setText(block.getToolArgs());
        }

        return view;
    }

    private View createToolResultBlock(AgentExecutionState.ExecutionBlock block) {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.agent_block_tool_result, null);
        int colorRes = block.isToolSuccess() ? R.color.success : R.color.error;
        String icon = block.isToolSuccess() ? "✅" : "❌";
        bindBlockHeader(view, block, icon + " 工具结果", colorRes);

        TextView resultView = view.findViewById(R.id.tv_result);
        if (resultView != null && block.getToolResult() != null) {
            resultView.setText(block.getToolResult());
        }
        return view;
    }

    private View createInferenceBlock(AgentExecutionState.ExecutionBlock block) {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.agent_block_text, null);
        bindBlockHeader(view, block, "🧠 推理", R.color.purple_500);
        return view;
    }

    private View createObservationBlock(AgentExecutionState.ExecutionBlock block) {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.agent_block_text, null);
        bindBlockHeader(view, block, "👁️ 观察", R.color.teal_200);
        return view;
    }

    private View createReflectionBlock(AgentExecutionState.ExecutionBlock block) {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.agent_block_text, null);
        bindBlockHeader(view, block, "🔄 反思", R.color.orange_500);
        return view;
    }

    private View createErrorBlock(AgentExecutionState.ExecutionBlock block) {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.agent_block_text, null);
        bindBlockHeader(view, block, "❌ 错误", R.color.error);
        view.setBackgroundColor(getResources().getColor(R.color.red_50));
        return view;
    }

    private View createStatsBlock(AgentExecutionState.ExecutionBlock block) {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.agent_block_stats, null);
        bindBlockHeader(view, block, "📊 统计", R.color.text_secondary);
        return view;
    }

    private View createTextBlock(AgentExecutionState.ExecutionBlock block) {
        View view = LayoutInflater.from(getContext()).inflate(R.layout.agent_block_text, null);
        String title = block.getTitle() != null ? block.getTitle() : "📝";
        bindBlockHeader(view, block, title, R.color.text_secondary);
        return view;
    }

    private void bindBlockHeader(View view, AgentExecutionState.ExecutionBlock block, String defaultTitle, int colorRes) {
        TextView titleView = view.findViewById(R.id.tv_block_title);
        TextView contentView = view.findViewById(R.id.tv_block_content);
        View statusIndicator = view.findViewById(R.id.view_status_indicator);

        String title = block.getTitle() != null ? block.getTitle() : defaultTitle;
        if (titleView != null) {
            titleView.setText(title);
            titleView.setTextColor(getResources().getColor(colorRes));
        }

        // 设置步骤序号
        TextView stepNumberView = view.findViewById(R.id.tv_step_number);
        if (stepNumberView != null) {
            stepNumberView.setText("#" + block.getStepNumber());
        }

        if (contentView != null) {
            contentView.setText(block.getContent() != null ? block.getContent() : "");
        }

        if (statusIndicator != null) {
            int indicatorColor;
            switch (block.getStatus()) {
                case RUNNING:
                    indicatorColor = getResources().getColor(R.color.primary);
                    break;
                case COMPLETED:
                    indicatorColor = getResources().getColor(R.color.success);
                    break;
                case FAILED:
                    indicatorColor = getResources().getColor(R.color.error);
                    break;
                default:
                    indicatorColor = getResources().getColor(R.color.border);
                    break;
            }
            statusIndicator.setBackgroundColor(indicatorColor);
        }

        // 设置折叠/展开
        setupToggle(view, block);
    }

    private void setupToggle(View view, AgentExecutionState.ExecutionBlock block) {
        TextView btnToggle = view.findViewById(R.id.btn_toggle);
        TextView contentView = view.findViewById(R.id.tv_block_content);
        TextView previewView = view.findViewById(R.id.tv_content_preview);

        if (btnToggle == null) return;

        int blockIndex = block.getStepNumber() - 1;
        boolean collapsed = blockCollapsed.getOrDefault(blockIndex, false);

        // 设置预览内容
        if (previewView != null) {
            String content = block.getContent() != null ? block.getContent() : "";
            if (content.length() > 80) {
                previewView.setText(content.substring(0, 80) + "...");
            } else {
                previewView.setText(content);
            }
            previewView.setVisibility(collapsed ? View.VISIBLE : View.GONE);
        }

        if (contentView != null) {
            contentView.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        }
        btnToggle.setText(collapsed ? "▶" : "▼");

        btnToggle.setOnClickListener(v -> {
            boolean currentCollapsed = blockCollapsed.getOrDefault(blockIndex, false);
            boolean newCollapsed = !currentCollapsed;
            blockCollapsed.put(blockIndex, newCollapsed);

            if (contentView != null) {
                contentView.setVisibility(newCollapsed ? View.GONE : View.VISIBLE);
            }
            if (previewView != null) {
                previewView.setVisibility(newCollapsed ? View.VISIBLE : View.GONE);
            }
            btnToggle.setText(newCollapsed ? "▶" : "▼");
        });
    }

    private void updateBlockView(View view, AgentExecutionState.ExecutionBlock block) {
        // 更新标题
        TextView titleView = view.findViewById(R.id.tv_block_title);
        if (titleView != null && block.getTitle() != null) {
            if (!titleView.getText().toString().equals(block.getTitle())) {
                titleView.setText(block.getTitle());
            }
        }

        // 更新步骤序号
        TextView stepNumberView = view.findViewById(R.id.tv_step_number);
        if (stepNumberView != null) {
            stepNumberView.setText("#" + block.getStepNumber());
        }

        // 更新内容
        TextView contentView = view.findViewById(R.id.tv_block_content);
        if (contentView != null) {
            String blockContent = block.getContent() != null ? block.getContent() : "";
            if (!contentView.getText().toString().equals(blockContent)) {
                contentView.setText(blockContent);
            }
        }

        // 更新预览内容
        TextView previewView = view.findViewById(R.id.tv_content_preview);
        if (previewView != null) {
            String content = block.getContent() != null ? block.getContent() : "";
            if (content.length() > 80) {
                previewView.setText(content.substring(0, 80) + "...");
            } else {
                previewView.setText(content);
            }
        }

        // 更新状态指示器
        View statusIndicator = view.findViewById(R.id.view_status_indicator);
        if (statusIndicator != null) {
            int indicatorColor;
            switch (block.getStatus()) {
                case RUNNING:
                    indicatorColor = getResources().getColor(R.color.primary);
                    break;
                case COMPLETED:
                    indicatorColor = getResources().getColor(R.color.success);
                    break;
                case FAILED:
                    indicatorColor = getResources().getColor(R.color.error);
                    break;
                default:
                    indicatorColor = getResources().getColor(R.color.border);
                    break;
            }
            statusIndicator.setBackgroundColor(indicatorColor);
        }

        // 更新耗时
        TextView durationView = view.findViewById(R.id.tv_duration);
        if (durationView != null) {
            long dur = block.getDuration();
            if (dur < 1000) {
                durationView.setText(dur + "ms");
            } else {
                durationView.setText(String.format(Locale.getDefault(), "%.1fs", dur / 1000.0));
            }
        }

        // 更新工具调用相关字段
        if (block.getType() == AgentExecutionState.BlockType.TOOL_CALL) {
            TextView toolNameView = view.findViewById(R.id.tv_tool_name);
            TextView toolArgsView = view.findViewById(R.id.tv_tool_args);
            if (toolNameView != null && block.getToolName() != null) {
                toolNameView.setText(block.getToolName());
            }
            if (toolArgsView != null) {
                String args = block.getToolArgs() != null ? block.getToolArgs() : "";
                if (!toolArgsView.getText().toString().equals(args)) {
                    toolArgsView.setText(args);
                }
            }
        }

        // 更新工具结果相关字段
        if (block.getType() == AgentExecutionState.BlockType.TOOL_RESULT) {
            TextView resultView = view.findViewById(R.id.tv_result);
            if (resultView != null) {
                String result = block.getToolResult() != null ? block.getToolResult() : "";
                if (!resultView.getText().toString().equals(result)) {
                    resultView.setText(result);
                }
            }
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
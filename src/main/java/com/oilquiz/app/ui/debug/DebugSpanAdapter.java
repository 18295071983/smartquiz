package com.oilquiz.app.ui.debug;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Space;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.agent.debug.DebugSpan;

import java.util.List;

/**
 * 追踪瀑布图 adapter：以 Span 为单位渲染层级缩进、类型徽标、状态、耗时/token 与相对时长条。
 */
public class DebugSpanAdapter extends RecyclerView.Adapter<DebugSpanAdapter.VH> {

    public interface OnSpanClick {
        void onClick(DebugSpan span);
    }

    public static final int TYPE_COLOR_SESSION = Color.rgb(56, 189, 248);   // 青
    public static final int TYPE_COLOR_LLM = Color.rgb(129, 140, 248);      // 靛
    public static final int TYPE_COLOR_TOOL = Color.rgb(251, 191, 36);      // 琥珀
    public static final int TYPE_COLOR_THINKING = Color.rgb(52, 211, 153);  // 绿
    public static final int TYPE_COLOR_STEP = Color.rgb(148, 163, 184);     // 灰

    private final List<DebugSpan> spans;
    private final OnSpanClick listener;
    private long totalMs = 1;

    public DebugSpanAdapter(List<DebugSpan> spans, OnSpanClick listener) {
        this.spans = spans;
        this.listener = listener;
        recomputeTotal();
    }

    public void notifySpanChanged() {
        recomputeTotal();
        notifyDataSetChanged();
    }

    private void recomputeTotal() {
        long max = 0;
        for (DebugSpan s : spans) {
            long d = s.durationMs();
            if (d > max) max = d;
        }
        totalMs = Math.max(1, max);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_debug_span, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        DebugSpan s = spans.get(position);

        int color = typeColor(s.type);
        h.dot.setBackgroundResource(android.R.drawable.presence_online);
        h.dot.getBackground().setTint(color);
        h.name.setText(s.name == null || s.name.isEmpty() ? (s.type == null ? "span" : s.type) : s.name);
        h.name.setTextColor(s.isRunning() ? Color.rgb(250, 204, 21) : Color.rgb(226, 232, 240));

        // 状态文字
        String statusText;
        switch (s.status == null ? "" : s.status) {
            case DebugSpan.STATUS_RUNNING: statusText = "● 运行中"; break;
            case DebugSpan.STATUS_ERROR: statusText = "✖ 错误"; break;
            case DebugSpan.STATUS_CANCELLED: statusText = "◼ 已取消"; break;
            default: statusText = "✓ 完成"; break;
        }
        h.status.setText(statusText);
        h.status.setTextColor(s.isRunning()
                ? Color.rgb(250, 204, 21)
                : (DebugSpan.STATUS_ERROR.equals(s.status)
                        ? Color.rgb(252, 165, 165)
                        : Color.rgb(148, 163, 184)));

        // 元信息：类型 + token（含缓存命中）
        StringBuilder meta = new StringBuilder();
        meta.append(typeLabel(s.type));
        if (s.totalTokens > 0) {
            meta.append(" · 总 ").append(s.totalTokens);
            if (s.promptTokens > 0) meta.append(" / 入 ").append(s.promptTokens);
            if (s.completionTokens > 0) meta.append(" / 出 ").append(s.completionTokens);
            if (s.cachedTokens > 0) meta.append(" / 缓存 ").append(s.cachedTokens);
        }
        if (s.detail != null && !s.detail.isEmpty() && s.detail.length() <= 40) {
            meta.append(" · ").append(s.detail);
        } else if (s.detail != null && s.detail.length() > 40) {
            meta.append(" · ").append(s.detail.substring(0, 40)).append("…");
        }
        h.meta.setText(meta.toString());

        h.time.setText(fmtMs(s.durationMs()));
        h.time.setTextColor(s.isRunning() ? Color.rgb(250, 204, 21) : Color.rgb(110, 231, 183));

        // 相对时长条（占 run 内最大 span 时长的比例）
        ViewGroup.LayoutParams lp = h.bar.getLayoutParams();
        int widthPx = Math.max(2, (int) (h.bar.getResources().getDisplayMetrics().density * 4
                + (s.durationMs() / (float) totalMs) * 300 * h.bar.getResources().getDisplayMetrics().density));
        lp.width = widthPx;
        h.bar.setLayoutParams(lp);
        h.bar.setBackgroundColor(s.isRunning() ? Color.rgb(250, 204, 21) : color);
        h.bar.setAlpha(s.isRunning() ? 0.9f : 0.6f);

        // 层级缩进
        ViewGroup.LayoutParams ilp = h.indent.getLayoutParams();
        ilp.width = (int) (h.indent.getResources().getDisplayMetrics().density * 14 * Math.max(0, s.depth));
        h.indent.setLayoutParams(ilp);

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(s);
        });
    }

    @Override
    public int getItemCount() {
        return spans == null ? 0 : spans.size();
    }

    public static int typeColor(String type) {
        if (DebugSpan.TYPE_SESSION.equals(type)) return TYPE_COLOR_SESSION;
        if (DebugSpan.TYPE_LLM.equals(type)) return TYPE_COLOR_LLM;
        if (DebugSpan.TYPE_TOOL.equals(type)) return TYPE_COLOR_TOOL;
        if (DebugSpan.TYPE_THINKING.equals(type)) return TYPE_COLOR_THINKING;
        return TYPE_COLOR_STEP;
    }

    public static String typeLabel(String type) {
        if (DebugSpan.TYPE_SESSION.equals(type)) return "会话";
        if (DebugSpan.TYPE_LLM.equals(type)) return "LLM";
        if (DebugSpan.TYPE_TOOL.equals(type)) return "工具";
        if (DebugSpan.TYPE_THINKING.equals(type)) return "思考";
        return "步骤";
    }

    public static String fmtMs(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60000) return String.format(java.util.Locale.US, "%.1fs", ms / 1000f);
        return String.format(java.util.Locale.US, "%d:%02d", ms / 60000, (ms % 60000) / 1000);
    }

    static class VH extends RecyclerView.ViewHolder {
        final Space indent;
        final View dot;
        final TextView name;
        final TextView status;
        final TextView meta;
        final TextView time;
        final View bar;

        VH(@NonNull View v) {
            super(v);
            indent = v.findViewById(R.id.span_indent);
            dot = v.findViewById(R.id.span_dot);
            name = v.findViewById(R.id.span_name);
            status = v.findViewById(R.id.span_status);
            meta = v.findViewById(R.id.span_meta);
            time = v.findViewById(R.id.span_time);
            bar = v.findViewById(R.id.span_bar);
        }
    }
}

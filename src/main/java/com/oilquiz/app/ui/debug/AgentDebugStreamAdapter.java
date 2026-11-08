package com.oilquiz.app.ui.debug;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.ChatMessage;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * DEBUG-UI(2026-11-08)：调试台专用流适配器。
 *
 * <p>不再复用聊天页的 {@code ChatAdapter}（气泡/头像/Markdown/组件/工具卡片那一整套，
 * 在调试台里既重又难读）。这里只做三件事：</p>
 * <ul>
 *   <li>每行带毫秒时间戳和类型标签（USER/AI/SYS/THINK/TOOL/ERR）；</li>
 *   <li>正文等宽、可选中、无气泡，长日志按行阅读；</li>
 *   <li>思考段单独一行紫色、工具参数/结果按名字对齐。</li>
 * </ul>
 *
 * <p>数据仍复用 {@link ChatMessage}，这样 Activity 的 addUser/addToolCall/addToolResult
 * 不需要改数据模型；只替换渲染层。</p>
 */
public class AgentDebugStreamAdapter extends RecyclerView.Adapter<AgentDebugStreamAdapter.Holder> {

    private static final int BG_A = 0xFF0B1020;
    private static final int BG_B = 0xFF0E1528;

    private final List<ChatMessage> messages;
    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    public AgentDebugStreamAdapter(List<ChatMessage> messages) {
        this.messages = messages;
    }

    public int findLastAiMessageIndex() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m != null && m.type == ChatMessage.MessageType.AI) return i;
        }
        return -1;
    }

    public void updateMessageThinkingContent(int position, String content) {
        if (position < 0 || position >= messages.size()) return;
        ChatMessage m = messages.get(position);
        if (m == null) return;
        m.thinkingContent = content;
        notifyItemChanged(position);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_agent_debug_line, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        ChatMessage m = messages.get(position);
        h.itemView.setBackgroundColor(position % 2 == 0 ? BG_A : BG_B);
        if (m == null) {
            h.time.setText("--:--:--.---");
            h.tag.setText("?");
            h.tag.setTextColor(0xFF94A3B8);
            h.thinking.setVisibility(View.GONE);
            h.text.setText("");
            return;
        }

        h.time.setText(m.timestamp > 0 ? timeFormat.format(new Date(m.timestamp)) : "--:--:--.---");
        ChatMessage.MessageType type = m.type == null ? ChatMessage.MessageType.SYSTEM : m.type;

        String tag;
        int tagColor;
        int textColor;
        switch (type) {
            case USER:
                tag = "USER";
                tagColor = 0xFF60A5FA;
                textColor = 0xFFE2E8F0;
                break;
            case AI:
                tag = "AI";
                tagColor = 0xFF6EE7B7;
                textColor = 0xFFE2E8F0;
                break;
            case THINKING:
                tag = "THINK";
                tagColor = 0xFFC084FC;
                textColor = 0xFFC4B5FD;
                break;
            case TOOL_CALL:
                tag = "TOOL";
                tagColor = 0xFFFBBF24;
                textColor = 0xFFFDE68A;
                break;
            case TOOL_RESULT:
                tag = "TOOL←";
                tagColor = 0xFFF59E0B;
                textColor = 0xFFCBD5E1;
                break;
            case ERROR:
                tag = "ERR";
                tagColor = 0xFFF87171;
                textColor = 0xFFFCA5A5;
                break;
            case AGENT_SUMMARY:
                tag = "AGENT";
                tagColor = 0xFF38BDF8;
                textColor = 0xFFBAE6FD;
                break;
            case SYSTEM:
            default:
                tag = "SYS";
                tagColor = 0xFF94A3B8;
                textColor = 0xFF94A3B8;
                break;
        }
        h.tag.setText(tag);
        h.tag.setTextColor(tagColor);
        h.text.setTextColor(textColor);

        String thinking = m.thinkingContent;
        if (thinking != null && !thinking.trim().isEmpty()) {
            h.thinking.setVisibility(View.VISIBLE);
            h.thinking.setText("think › " + thinking.trim());
        } else {
            h.thinking.setVisibility(View.GONE);
            h.thinking.setText("");
        }

        String body;
        ChatMessage.ToolCallInfo ti = m.toolCallInfo;
        switch (type) {
            case TOOL_CALL:
                body = (ti == null || ti.toolName == null ? "?" : ti.toolName)
                        + (ti == null || ti.parameters == null || ti.parameters.isEmpty()
                        ? "" : "\n" + ti.parameters);
                break;
            case TOOL_RESULT:
                body = (ti == null || ti.toolName == null ? "?" : ti.toolName)
                        + (ti == null || ti.result == null || ti.result.isEmpty()
                        ? "" : "\n" + ti.result);
                break;
            case ERROR:
                body = m.content == null ? "" : m.content;
                if (m.errorDetail != null && !m.errorDetail.isEmpty()) {
                    body += "\n" + m.errorDetail;
                }
                break;
            default:
                body = m.content == null ? "" : m.content;
                break;
        }
        h.text.setText(body);
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView time;
        final TextView tag;
        final TextView thinking;
        final TextView text;

        Holder(@NonNull View itemView) {
            super(itemView);
            time = itemView.findViewById(R.id.tv_time);
            tag = itemView.findViewById(R.id.tv_tag);
            thinking = itemView.findViewById(R.id.tv_thinking);
            text = itemView.findViewById(R.id.tv_text);
        }
    }
}

package com.oilquiz.app.ai.chat.history;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.util.ConversationSession;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 历史会话列表适配器 - 显示所有已保存的对话会话。
 *
 * 每个条目显示：标题（首条用户消息摘要）、消息数、时间、预览。
 * 点击切换到该会话，长按显示操作菜单。
 */
public class ChatHistoryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_ITEM = 1;

    public interface OnHistoryItemClickListener {
        void onItemClick(ConversationSession session);
        void onItemLongClick(ConversationSession session);
        void onItemDelete(ConversationSession session);
        void onItemShare(ConversationSession session);
        void onItemExport(ConversationSession session);
        void onClearAllHistory();
    }

    private final Context context;
    private final List<Object> items; // Date headers + ConversationSession
    private final OnHistoryItemClickListener clickListener;
    private final SimpleDateFormat dateFormat;
    private final SimpleDateFormat timeFormat;

    public ChatHistoryAdapter(Context context, List<ConversationSession> sessions,
                              OnHistoryItemClickListener listener) {
        this.context = context;
        this.clickListener = listener;
        this.dateFormat = new SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault());
        this.timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        this.items = groupByDate(sessions);
    }

    /** 按日期分组会话 */
    private List<Object> groupByDate(List<ConversationSession> sessions) {
        List<Object> result = new ArrayList<>();
        if (sessions == null || sessions.isEmpty()) return result;

        Calendar cal = Calendar.getInstance();
        long lastDateKey = -1;

        for (ConversationSession session : sessions) {
            cal.setTimeInMillis(session.updatedAt);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            long dateKey = cal.getTimeInMillis();

            if (dateKey != lastDateKey) {
                result.add(cal.getTime());
                lastDateKey = dateKey;
            }
            result.add(session);
        }
        return result;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position) instanceof Date ? VIEW_TYPE_HEADER : VIEW_TYPE_ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_HEADER) {
            return new HeaderViewHolder(inflater.inflate(R.layout.item_history_header, parent, false));
        } else {
            return new HistoryViewHolder(inflater.inflate(R.layout.item_chat_history, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = items.get(position);
        if (item instanceof Date) {
            bindHeader((HeaderViewHolder) holder, (Date) item);
        } else if (item instanceof ConversationSession) {
            bindSessionItem((HistoryViewHolder) holder, (ConversationSession) item);
        }
    }

    private void bindHeader(HeaderViewHolder holder, Date date) {
        holder.headerText.setText(formatDateHeader(date));
        holder.countText.setText("对话记录");
    }

    private void bindSessionItem(HistoryViewHolder holder, ConversationSession session) {
        // 标题
        holder.previewText.setText(session.title != null ? session.title : "新对话");

        // 时间
        holder.timeText.setText(timeFormat.format(new Date(session.updatedAt)));

        // 消息数
        holder.messageCountText.setText(String.format(Locale.getDefault(), "%d条消息", session.getMessageCount()));

        // 图标
        holder.iconText.setText("💬");

        // 持续时间
        holder.durationText.setVisibility(View.GONE);

        // 点击切换
        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onItemClick(session);
        });

        // 长按菜单
        holder.itemView.setOnLongClickListener(v -> {
            showPopupMenu(v, session);
            return true;
        });
    }

    private String formatDateHeader(Date date) {
        Calendar today = Calendar.getInstance();
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_MONTH, -1);
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);

        if (isSameDay(cal, today)) return "今天";
        else if (isSameDay(cal, yesterday)) return "昨天";
        else return dateFormat.format(date);
    }

    private boolean isSameDay(Calendar c1, Calendar c2) {
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
               c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR);
    }

    private void showPopupMenu(View anchor, ConversationSession session) {
        PopupMenu popup = new PopupMenu(context, anchor);
        popup.getMenuInflater().inflate(R.menu.popup_history_item, popup.getMenu());

        popup.setOnMenuItemClickListener(menuItem -> {
            int id = menuItem.getItemId();
            if (id == R.id.menu_open) {
                if (clickListener != null) clickListener.onItemClick(session);
                return true;
            } else if (id == R.id.menu_delete) {
                showDeleteConfirmDialog(session);
                return true;
            } else if (id == R.id.menu_share) {
                if (clickListener != null) clickListener.onItemShare(session);
                return true;
            } else if (id == R.id.menu_export) {
                if (clickListener != null) clickListener.onItemExport(session);
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void showDeleteConfirmDialog(ConversationSession session) {
        new MaterialAlertDialogBuilder(context)
            .setTitle("删除对话记录")
            .setMessage("确定要删除这个对话吗？\n\n" +
                       "标题: " + session.title + "\n" +
                       "消息数: " + session.getMessageCount() + "条\n\n" +
                       "此操作不可撤销")
            .setPositiveButton("删除", (dialog, which) -> {
                if (clickListener != null) clickListener.onItemDelete(session);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    public void clearAll() {
        items.clear();
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView headerText;
        TextView countText;
        View divider;

        HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            headerText = itemView.findViewById(R.id.history_header_text);
            countText = itemView.findViewById(R.id.history_header_count);
            divider = itemView.findViewById(R.id.history_header_divider);
        }
    }

    static class HistoryViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView cardView;
        TextView iconText;
        TextView previewText;
        TextView timeText;
        TextView messageCountText;
        TextView durationText;
        ImageView arrowIcon;

        HistoryViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.history_card);
            iconText = itemView.findViewById(R.id.history_icon);
            previewText = itemView.findViewById(R.id.history_preview_text);
            timeText = itemView.findViewById(R.id.history_time_text);
            messageCountText = itemView.findViewById(R.id.history_message_count);
            durationText = itemView.findViewById(R.id.history_duration);
            arrowIcon = itemView.findViewById(R.id.history_arrow);
        }
    }
}

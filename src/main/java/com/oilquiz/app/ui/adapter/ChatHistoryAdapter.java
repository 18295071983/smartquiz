package com.oilquiz.app.ui.adapter;

import android.content.Context;
import android.content.Intent;
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
import com.google.android.material.snackbar.Snackbar;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.util.ChatHistoryManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public class ChatHistoryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final String TAG = "ChatHistoryAdapter";
    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_ITEM = 1;

    public interface OnHistoryItemClickListener {
        void onItemClick(ChatHistoryItem item, int position);
        void onItemLongClick(ChatHistoryItem item, int position);
        void onItemDelete(ChatHistoryItem item, int position);
        void onItemShare(ChatHistoryItem item, int position);
        void onItemExport(ChatHistoryItem item, int position);
        void onClearAllHistory();
    }

    public static class ChatHistoryItem {
        public String sessionId;
        public Date date;
        public List<ChatMessage> messages;
        public String previewText;
        public int messageCount;
        public boolean hasImages;
        public long durationMs;

        public ChatHistoryItem(String sessionId, Date date, List<ChatMessage> messages) {
            this.sessionId = sessionId;
            this.date = date;
            this.messages = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
            this.messageCount = this.messages.size();

            if (!this.messages.isEmpty()) {
                StringBuilder preview = new StringBuilder();
                int count = 0;
                for (ChatMessage msg : this.messages) {
                    if (msg.isUserMessage() && !TextUtils.isEmpty(msg.content)) {
                        if (preview.length() > 0) preview.append(" / ");
                        String content = msg.content.length() > 50 ?
                            msg.content.substring(0, 50) + "..." : msg.content;
                        preview.append(content);
                        count++;
                        if (count >= 2) break;
                    }
                }
                this.previewText = preview.toString();

                for (ChatMessage msg : this.messages) {
                    if (msg.attachments != null && !msg.attachments.isEmpty()) {
                        this.hasImages = true;
                        break;
                    }
                }

                if (this.messages.size() >= 2) {
                    long firstTime = this.messages.get(0).timestamp;
                    long lastTime = this.messages.get(this.messages.size() - 1).timestamp;
                    this.durationMs = lastTime - firstTime;
                }
            } else {
                this.previewText = "空对话";
            }
        }
    }

    private final Context context;
    private final List<Object> items;
    private final OnHistoryItemClickListener clickListener;
    private final SimpleDateFormat dateFormat;
    private final SimpleDateFormat timeFormat;

    public ChatHistoryAdapter(Context context, List<ChatMessage> allMessages,
                           OnHistoryItemClickListener listener) {
        this.context = context;
        this.clickListener = listener;
        this.dateFormat = new SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault());
        this.timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

        this.items = groupByDate(allMessages);
    }

    private List<Object> groupByDate(List<ChatMessage> messages) {
        Map<Date, List<ChatMessage>> groupedMap = new TreeMap<>((d1, d2) -> d2.compareTo(d1));

        Calendar cal = Calendar.getInstance();

        for (ChatMessage msg : messages) {
            if (msg.isUserMessage() || msg.isAIMessage()) {
                cal.setTimeInMillis(msg.timestamp);
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);

                Date dateKey = cal.getTime();
                if (!groupedMap.containsKey(dateKey)) {
                    groupedMap.put(dateKey, new ArrayList<>());
                }
                groupedMap.get(dateKey).add(msg);
            }
        }

        List<Object> result = new ArrayList<>();
        for (Map.Entry<Date, List<ChatMessage>> entry : groupedMap.entrySet()) {
            result.add(entry.getKey());

            ChatHistoryItem item = new ChatHistoryItem(
                "session_" + entry.getKey().getTime(),
                entry.getKey(),
                entry.getValue()
            );
            result.add(item);
        }

        return result;
    }

    @Override
    public int getItemViewType(int position) {
        Object item = items.get(position);
        return item instanceof Date ? VIEW_TYPE_HEADER : VIEW_TYPE_ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());

        if (viewType == VIEW_TYPE_HEADER) {
            View view = inflater.inflate(R.layout.item_history_header, parent, false);
            return new HeaderViewHolder(view);
        } else {
            View view = inflater.inflate(R.layout.item_chat_history, parent, false);
            return new HistoryViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = items.get(position);

        if (item instanceof Date && holder instanceof HeaderViewHolder) {
            bindHeader((HeaderViewHolder) holder, (Date) item);
        } else if (item instanceof ChatHistoryItem && holder instanceof HistoryViewHolder) {
            bindHistoryItem((HistoryViewHolder) holder, (ChatHistoryItem) item, position);
        }
    }

    private void bindHeader(HeaderViewHolder holder, Date date) {
        holder.headerText.setText(formatDateHeader(date));
        holder.countText.setText("对话记录");
    }

    private void bindHistoryItem(HistoryViewHolder holder, ChatHistoryItem item, int position) {
        holder.previewText.setText(item.previewText != null ? item.previewText : "空对话");

        holder.timeText.setText(timeFormat.format(item.date));

        holder.messageCountText.setText(String.format(Locale.getDefault(), "%d条消息", item.messageCount));

        if (item.hasImages) {
            holder.iconText.setText("🖼️");
        } else {
            holder.iconText.setText("💬");
        }

        if (item.durationMs > 0) {
            holder.durationText.setVisibility(View.VISIBLE);
            holder.durationText.setText(formatDuration(item.durationMs));
        } else {
            holder.durationText.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onItemClick(item, position);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            showPopupMenu(v, item, position);
            return true;
        });
    }

    private String formatDateHeader(Date date) {
        Calendar today = Calendar.getInstance();
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_MONTH, -1);
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);

        if (isSameDay(cal, today)) {
            return "今天";
        } else if (isSameDay(cal, yesterday)) {
            return "昨天";
        } else {
            return dateFormat.format(date);
        }
    }

    private boolean isSameDay(Calendar c1, Calendar c2) {
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
               c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR);
    }

    private String formatDuration(long ms) {
        long seconds = ms / 1000;
        if (seconds < 60) {
            return seconds + "秒";
        } else if (seconds < 3600) {
            long minutes = seconds / 60;
            return minutes + "分钟";
        } else {
            long hours = seconds / 3600;
            return hours + "小时";
        }
    }

    private void showPopupMenu(View anchor, ChatHistoryItem item, int position) {
        PopupMenu popup = new PopupMenu(context, anchor);
        popup.getMenuInflater().inflate(R.menu.popup_history_item, popup.getMenu());

        popup.setOnMenuItemClickListener(menuItem -> {
            int id = menuItem.getItemId();
            if (id == R.id.menu_open) {
                if (clickListener != null) clickListener.onItemClick(item, position);
                return true;
            } else if (id == R.id.menu_delete) {
                showDeleteConfirmDialog(item, position);
                return true;
            } else if (id == R.id.menu_share) {
                if (clickListener != null) clickListener.onItemShare(item, position);
                return true;
            } else if (id == R.id.menu_export) {
                if (clickListener != null) clickListener.onItemExport(item, position);
                return true;
            } else {
                return false;
            }
        });

        popup.show();
    }

    private void showDeleteConfirmDialog(ChatHistoryItem item, int position) {
        new MaterialAlertDialogBuilder(context)
            .setTitle("🗑️ 删除对话记录")
            .setMessage("确定要删除这条对话记录吗？\n\n" +
                       "时间: " + timeFormat.format(item.date) + "\n" +
                       "消息数: " + item.messageCount + "条\n\n" +
                       "⚠️ 此操作不可撤销")
            .setPositiveButton("删除", (dialog, which) -> {
                if (clickListener != null) {
                    clickListener.onItemDelete(item, position);
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    public void filterByKeyword(String keyword) {
        // TODO: 实现搜索过滤功能
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

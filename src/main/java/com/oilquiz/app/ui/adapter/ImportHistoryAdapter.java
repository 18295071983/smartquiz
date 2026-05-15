package com.oilquiz.app.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;
import com.oilquiz.app.model.ImportHistory;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ImportHistoryAdapter extends RecyclerView.Adapter<ImportHistoryAdapter.HistoryViewHolder> {

    private List<ImportHistory> importHistoryList;
    private OnHistoryItemClickListener listener;
    private OnHistoryItemDeleteListener deleteListener;

    public interface OnHistoryItemClickListener {
        void onHistoryItemClick(ImportHistory importHistory);
    }

    public interface OnHistoryItemDeleteListener {
        void onHistoryItemDelete(ImportHistory importHistory, int position);
    }

    public ImportHistoryAdapter(List<ImportHistory> importHistoryList, OnHistoryItemClickListener listener) {
        this.importHistoryList = importHistoryList != null ? importHistoryList : new ArrayList<>();
        this.listener = listener;
    }

    public void setOnHistoryItemDeleteListener(OnHistoryItemDeleteListener deleteListener) {
        this.deleteListener = deleteListener;
    }

    public void updateData(List<ImportHistory> importHistoryList) {
        this.importHistoryList = importHistoryList != null ? importHistoryList : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void removeItem(int position) {
        if (position >= 0 && position < importHistoryList.size()) {
            importHistoryList.remove(position);
            notifyItemRemoved(position);
            notifyItemRangeChanged(position, importHistoryList.size() - position);
        }
    }

    @NonNull
    @Override
    public HistoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_import_history, parent, false);
        return new HistoryViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull HistoryViewHolder holder, int position) {
        ImportHistory importHistory = importHistoryList.get(position);
        holder.bind(importHistory);
    }

    @Override
    public int getItemCount() {
        return importHistoryList.size();
    }

    class HistoryViewHolder extends RecyclerView.ViewHolder {
        private TextView textViewFileName;
        private TextView textViewImportTime;
        private TextView textViewImportedCount;
        private TextView textViewFailedCount;
        private TextView textViewStatus;
        private MaterialButton btnDelete;

        public HistoryViewHolder(@NonNull View itemView) {
            super(itemView);
            textViewFileName = itemView.findViewById(R.id.textViewFileName);
            textViewImportTime = itemView.findViewById(R.id.textViewImportTime);
            textViewImportedCount = itemView.findViewById(R.id.textViewImportedCount);
            textViewFailedCount = itemView.findViewById(R.id.textViewFailedCount);
            textViewStatus = itemView.findViewById(R.id.textViewStatus);
            btnDelete = itemView.findViewById(R.id.btnDelete);

            itemView.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION && listener != null) {
                    listener.onHistoryItemClick(importHistoryList.get(position));
                }
            });

            if (btnDelete != null) {
                btnDelete.setOnClickListener(v -> {
                    int position = getAdapterPosition();
                    if (position != RecyclerView.NO_POSITION && deleteListener != null) {
                        deleteListener.onHistoryItemDelete(importHistoryList.get(position), position);
                    }
                });
            }
        }

        public void bind(ImportHistory importHistory) {
            textViewFileName.setText(importHistory.getFileName());
            textViewImportTime.setText(formatTime(importHistory.getImportTime()));
            textViewImportedCount.setText("成功: " + importHistory.getImportedCount());
            textViewFailedCount.setText("失败: " + importHistory.getFailedCount());
            textViewStatus.setText(importHistory.getStatus());

            // 根据状态设置颜色
            if ("成功".equals(importHistory.getStatus())) {
                textViewStatus.setTextColor(itemView.getContext().getResources().getColor(R.color.success_color));
            } else if ("失败".equals(importHistory.getStatus())) {
                textViewStatus.setTextColor(itemView.getContext().getResources().getColor(R.color.error_color));
            } else {
                textViewStatus.setTextColor(itemView.getContext().getResources().getColor(R.color.warning_color));
            }
        }

        private String formatTime(long time) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            return sdf.format(new Date(time));
        }
    }
}

package com.oilquiz.app.ui.adapter;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.ItemTouchHelper;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.ChatMessage.Attachment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.oilquiz.app.theme.ThemeColors;
public class AttachmentAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final String TAG = "AttachmentAdapter";
    private static final int VIEW_TYPE_IMAGE = 0;
    private static final int VIEW_TYPE_FILE = 1;
    private static final int VIEW_TYPE_AUDIO = 2; // 音频类型

    public interface OnAttachmentClickListener {
        void onImageClick(Attachment attachment, int position);
        void onFileClick(Attachment attachment, int position);
        void onAttachmentRemove(Attachment attachment, int position);
        void onAudioClick(Attachment attachment, int position); // 音频点击
    }

    public interface OnAttachmentDragListener {
        void onAttachmentMoved(int fromPosition, int toPosition);
        void onAttachmentDragStart();
        void onAttachmentDragEnd();
    }

    private final Context context;
    private final List<Attachment> attachments;
    private final OnAttachmentClickListener clickListener;
    private OnAttachmentDragListener dragListener; // 拖拽监听器
    private boolean isDragEnabled = false; // 是否启用拖拽
    private boolean isEditMode = false; // 是否处于编辑模式
    private java.util.Set<Integer> selectedPositions = new java.util.HashSet<>(); // 选中的位置

    public AttachmentAdapter(Context context, List<Attachment> attachments, OnAttachmentClickListener listener) {
        this.context = context;
        this.attachments = new ArrayList<>(attachments != null ? attachments : new ArrayList<>());
        this.clickListener = listener;
    }

    /** 设置拖拽监听器 */
    public void setOnAttachmentDragListener(OnAttachmentDragListener listener) {
        this.dragListener = listener;
    }

    /** 启用拖拽排序 */
    public void enableDrag() {
        isDragEnabled = true;
    }

    /** 禁用拖拽排序 */
    public void disableDrag() {
        isDragEnabled = false;
    }

    /** 检查是否启用拖拽 */
    public boolean isDragEnabled() {
        return isDragEnabled;
    }

    /** 启用编辑模式 */
    public void enableEditMode() {
        isEditMode = true;
        selectedPositions.clear();
        notifyDataSetChanged();
    }

    /** 禁用编辑模式 */
    public void disableEditMode() {
        isEditMode = false;
        selectedPositions.clear();
        notifyDataSetChanged();
    }

    /** 检查是否处于编辑模式 */
    public boolean isEditMode() {
        return isEditMode;
    }

    /** 切换选中状态 */
    public void toggleSelection(int position) {
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position);
        } else {
            selectedPositions.add(position);
        }
        notifyItemChanged(position);
    }

    /** 检查是否选中 */
    public boolean isSelected(int position) {
        return selectedPositions.contains(position);
    }

    /** 获取选中的数量 */
    public int getSelectedCount() {
        return selectedPositions.size();
    }

    /** 全选 */
    public void selectAll() {
        selectedPositions.clear();
        for (int i = 0; i < attachments.size(); i++) {
            selectedPositions.add(i);
        }
        notifyDataSetChanged();
    }

    /** 取消全选 */
    public void clearSelection() {
        selectedPositions.clear();
        notifyDataSetChanged();
    }

    /** 批量删除选中的附件 */
    public void deleteSelected() {
        if (selectedPositions.isEmpty()) return;
        
        // 从大到小排序，避免删除时索引变化
        java.util.List<Integer> sortedPositions = new java.util.ArrayList<>(selectedPositions);
        sortedPositions.sort((a, b) -> b - a);
        
        for (int position : sortedPositions) {
            if (position >= 0 && position < attachments.size()) {
                attachments.remove(position);
            }
        }
        
        selectedPositions.clear();
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        Attachment attachment = attachments.get(position);
        if (isAudioAttachment(attachment)) {
            return VIEW_TYPE_AUDIO;
        } else if (isImageAttachment(attachment)) {
            return VIEW_TYPE_IMAGE;
        } else {
            return VIEW_TYPE_FILE;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());

        if (viewType == VIEW_TYPE_IMAGE) {
            View view = inflater.inflate(R.layout.item_attachment_image, parent, false);
            return new ImageAttachmentViewHolder(view);
        } else if (viewType == VIEW_TYPE_AUDIO) {
            View view = inflater.inflate(R.layout.item_attachment_file, parent, false); // 使用文件布局
            return new AudioAttachmentViewHolder(view);
        } else {
            View view = inflater.inflate(R.layout.item_attachment_file, parent, false);
            return new FileAttachmentViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Attachment attachment = attachments.get(position);

        if (holder instanceof ImageAttachmentViewHolder) {
            bindImageAttachment((ImageAttachmentViewHolder) holder, attachment, position);
        } else if (holder instanceof AudioAttachmentViewHolder) {
            bindAudioAttachment((AudioAttachmentViewHolder) holder, attachment, position);
        } else if (holder instanceof FileAttachmentViewHolder) {
            bindFileAttachment((FileAttachmentViewHolder) holder, attachment, position);
        }
    }

    private boolean isImageAttachment(Attachment attachment) {
        if (attachment.type != null && attachment.type.toLowerCase().contains("image")) {
            return true;
        }
        return isImageFileName(attachment.name);
    }

    private boolean isAudioAttachment(Attachment attachment) {
        if (attachment.type != null && attachment.type.toLowerCase().contains("audio")) {
            return true;
        }
        if (attachment.name != null) {
            String lowerName = attachment.name.toLowerCase();
            return lowerName.endsWith(".mp3") || lowerName.endsWith(".mp4") || 
                   lowerName.endsWith(".wav") || lowerName.endsWith(".aac") ||
                   lowerName.endsWith(".m4a") || lowerName.endsWith(".ogg");
        }
        return false;
    }

    private boolean isImageFileName(String fileName) {
        if (fileName == null) return false;
        String lowerName = fileName.toLowerCase();
        return lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ||
               lowerName.endsWith(".png") || lowerName.endsWith(".gif") ||
               lowerName.endsWith(".webp") || lowerName.endsWith(".bmp");
    }

    private void bindImageAttachment(ImageAttachmentViewHolder holder, Attachment attachment, int position) {
        if (attachment.url != null && !attachment.url.isEmpty()) {
            Uri uri = Uri.parse(attachment.url);
            if ("file".equals(uri.getScheme())) {
                File file = new File(uri.getPath());
                if (file.exists()) {
                    // file:// 手动采样解码，避免大图全尺寸解码撑爆内存
                    android.graphics.Bitmap bmp = com.oilquiz.app.util.ImageParserUtil.parseImage(file, 1024, 1024);
                    if (bmp != null) {
                        holder.imageView.setImageBitmap(bmp);
                    } else {
                        holder.imageView.setImageURI(uri);
                    }
                } else {
                    holder.imageView.setImageResource(android.R.drawable.ic_menu_gallery);
                }
            } else if ("content".equals(uri.getScheme()) || "android.resource".equals(uri.getScheme())) {
                holder.imageView.setImageURI(uri);
            } else {
                holder.imageView.setImageURI(uri);
            }
        } else {
            holder.imageView.setImageResource(android.R.drawable.ic_menu_gallery);
        }

        bindAttachmentStatus(holder, attachment);
        
        // 编辑模式下显示选中状态
        updateSelectionState(holder.itemView, position);
        
        // 显示解析状态指示器
        updateExtractionIndicator(holder, attachment);

        holder.itemView.setOnClickListener(v -> {
            if (isEditMode) {
                // 编辑模式：切换选中状态
                toggleSelection(position);
                updateSelectionState(holder.itemView, position);
            } else {
                // 正常模式：如果有提取内容，显示预览；否则打开图片
                if (attachment.isExtracted && attachment.extractedContent != null) {
                    showExtractionPreview(context, attachment);
                } else if (clickListener != null) {
                    clickListener.onImageClick(attachment, position);
                }
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (isDragEnabled) {
                // 如果启用拖拽，长按开始拖拽
                if (dragListener != null) {
                    dragListener.onAttachmentDragStart();
                }
                return false; // 让 ItemTouchHelper 处理
            } else {
                showRemoveDialog(attachment, position);
                return true;
            }
        });

        if (holder.removeButton != null) {
            holder.removeButton.setOnClickListener(v -> showRemoveDialog(attachment, position));
        }
    }

    private void bindAttachmentStatus(ImageAttachmentViewHolder holder, Attachment attachment) {
        if (holder.progressBar != null) {
            if (attachment.isUploading() || attachment.status == com.oilquiz.app.ai.chat.ChatMessage.AttachmentStatus.PENDING ||
                attachment.status == com.oilquiz.app.ai.chat.ChatMessage.AttachmentStatus.PROCESSING) {
                holder.progressBar.setVisibility(View.VISIBLE);
                holder.progressBar.setProgress(attachment.uploadProgress);
                holder.progressBar.setIndeterminate(attachment.uploadProgress <= 0);
            } else {
                holder.progressBar.setVisibility(View.GONE);
            }
        }

        if (holder.overlay != null && attachment.hasError()) {
            holder.overlay.setVisibility(View.VISIBLE);
            if (holder.zoomIcon != null) {
                holder.zoomIcon.setVisibility(View.GONE);
            }
        } else if (holder.overlay != null) {
            holder.overlay.setVisibility(View.GONE);
            if (holder.zoomIcon != null) {
                holder.zoomIcon.setVisibility(View.VISIBLE);
            }
        }
    }

    private void bindFileAttachment(FileAttachmentViewHolder holder, Attachment attachment, int position) {
        holder.fileIcon.setText(attachment.getEmoji());

        if (holder.fileName != null) {
            holder.fileName.setText(attachment.name != null ? attachment.name : "未知文件");
        }

        if (holder.fileSize != null) {
            String statusText = getStatusText(attachment);
            if (statusText != null) {
                holder.fileSize.setText(statusText);
            } else {
                holder.fileSize.setText(attachment.getDisplaySize());
            }
        }

        holder.itemView.setOnClickListener(v -> {
            if (isEditMode) {
                toggleSelection(position);
                updateSelectionState(holder.itemView, position);
            } else {
                // 如果有提取内容，显示预览
                if (attachment.isExtracted && attachment.extractedContent != null) {
                    showExtractionPreview(context, attachment);
                } else if (clickListener != null) {
                    clickListener.onFileClick(attachment, position);
                }
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (isDragEnabled) {
                if (dragListener != null) {
                    dragListener.onAttachmentDragStart();
                }
                return false;
            } else {
                showRemoveDialog(attachment, position);
                return true;
            }
        });

        if (holder.removeButton != null) {
            holder.removeButton.setOnClickListener(v -> showRemoveDialog(attachment, position));
        }
    }

    private void bindAudioAttachment(AudioAttachmentViewHolder holder, Attachment attachment, int position) {
        holder.fileIcon.setText("🎵"); // 音频图标

        if (holder.fileName != null) {
            holder.fileName.setText(attachment.name != null ? attachment.name : "语音消息");
        }

        if (holder.fileSize != null) {
            String statusText = getStatusText(attachment);
            if (statusText != null) {
                holder.fileSize.setText(statusText);
            } else {
                holder.fileSize.setText(attachment.getDisplaySize());
            }
        }

        holder.itemView.setOnClickListener(v -> {
            if (isEditMode) {
                toggleSelection(position);
                updateSelectionState(holder.itemView, position);
            } else {
                // 如果有提取内容，显示预览
                if (attachment.isExtracted && attachment.extractedContent != null) {
                    showExtractionPreview(context, attachment);
                } else if (clickListener != null) {
                    clickListener.onAudioClick(attachment, position);
                }
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (isDragEnabled) {
                if (dragListener != null) {
                    dragListener.onAttachmentDragStart();
                }
                return false;
            } else {
                showRemoveDialog(attachment, position);
                return true;
            }
        });

        if (holder.removeButton != null) {
            holder.removeButton.setOnClickListener(v -> showRemoveDialog(attachment, position));
        }
    }

    private String getStatusText(Attachment attachment) {
        if (attachment.hasError()) {
            return "上传失败: " + (attachment.errorMessage != null ? attachment.errorMessage : "");
        }
        if (attachment.isUploading()) {
            return "上传中... " + attachment.uploadProgress + "%";
        }
        if (attachment.status == com.oilquiz.app.ai.chat.ChatMessage.AttachmentStatus.PROCESSING) {
            return "处理中...";
        }
        if (attachment.status == com.oilquiz.app.ai.chat.ChatMessage.AttachmentStatus.PENDING) {
            return "等待上传...";
        }
        return null;
    }

    /** 更新选中状态显示 */
    private void updateSelectionState(View itemView, int position) {
        float alpha = isSelected(position) ? 0.5f : 1.0f;
        itemView.setAlpha(alpha);
        
        // 可以在这里添加选中标记（如勾选图标）
        if (isSelected(position)) {
            itemView.setBackgroundColor(ThemeColors.get(R.color.hc_ffe3f2fd)); // 浅蓝色背景
        } else {
            itemView.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    private void showRemoveDialog(Attachment attachment, int position) {
        new MaterialAlertDialogBuilder(context)
            .setTitle("🗑️ 删除附件")
            .setMessage("确定要删除这个附件吗？\n" +
                       (attachment.name != null ? attachment.name : ""))
            .setPositiveButton("删除", (dialog, which) -> {
                removeAttachment(position);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    public void addAttachment(Attachment attachment) {
        attachments.add(attachment);
        notifyItemInserted(attachments.size() - 1);
    }

    public void removeAttachment(int position) {
        if (position >= 0 && position < attachments.size()) {
            attachments.remove(position);
            notifyItemRemoved(position);

            if (attachments.isEmpty()) {
                notifyDataSetChanged();
            }
        }
    }

    /** 移动附件位置（用于拖拽） */
    public void moveAttachment(int fromPosition, int toPosition) {
        if (fromPosition < 0 || fromPosition >= attachments.size() || 
            toPosition < 0 || toPosition >= attachments.size()) {
            return;
        }
        
        Attachment item = attachments.remove(fromPosition);
        attachments.add(toPosition, item);
        notifyItemMoved(fromPosition, toPosition);
    }

    public List<Attachment> getAttachments() {
        return new ArrayList<>(attachments);
    }

    public boolean isEmpty() {
        return attachments.isEmpty();
    }

    /**
     * 更新附件列表（带 DiffUtil）
     */
    public void updateAttachments(List<Attachment> newAttachments) {
        List<Attachment> oldAttachments = new ArrayList<>(this.attachments);
        this.attachments.clear();
        this.attachments.addAll(newAttachments != null ? newAttachments : new ArrayList<>());
        
        // 使用 DiffUtil 计算差异
        androidx.recyclerview.widget.DiffUtil.DiffResult diffResult = 
            androidx.recyclerview.widget.DiffUtil.calculateDiff(new androidx.recyclerview.widget.DiffUtil.Callback() {
                @Override
                public int getOldListSize() {
                    return oldAttachments.size();
                }

                @Override
                public int getNewListSize() {
                    return attachments.size();
                }

                @Override
                public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                    Attachment oldItem = oldAttachments.get(oldItemPosition);
                    Attachment newItem = attachments.get(newItemPosition);
                    return oldItem.url != null && oldItem.url.equals(newItem.url);
                }

                @Override
                public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                    Attachment oldItem = oldAttachments.get(oldItemPosition);
                    Attachment newItem = attachments.get(newItemPosition);
                    return oldItem.status == newItem.status 
                        && oldItem.uploadProgress == newItem.uploadProgress;
                }
            });
        
        diffResult.dispatchUpdatesTo(this);
    }

    /**
     * 清空所有附件
     */
    public void clear() {
        int size = attachments.size();
        attachments.clear();
        if (size > 0) {
            notifyItemRangeRemoved(0, size);
        }
    }

    public void updateAttachmentProgress(int position, int progress) {
        if (position >= 0 && position < attachments.size()) {
            Attachment attachment = attachments.get(position);
            attachment.uploadProgress = progress;
            attachment.status = com.oilquiz.app.ai.chat.ChatMessage.AttachmentStatus.UPLOADING;
            notifyItemChanged(position);
        }
    }

    public void updateAttachmentStatus(int position, com.oilquiz.app.ai.chat.ChatMessage.AttachmentStatus status) {
        updateAttachmentStatus(position, status, null);
    }

    public void updateAttachmentStatus(int position, com.oilquiz.app.ai.chat.ChatMessage.AttachmentStatus status, String errorMessage) {
        if (position >= 0 && position < attachments.size()) {
            Attachment attachment = attachments.get(position);
            attachment.status = status;
            if (errorMessage != null) {
                attachment.errorMessage = errorMessage;
            }
            notifyItemChanged(position);
        }
    }

    public void updateAttachment(int position, Attachment newAttachment) {
        if (position >= 0 && position < attachments.size()) {
            attachments.set(position, newAttachment);
            notifyItemChanged(position);
        }
    }

    /** 更新附件解析状态 */
    public void updateExtractionStatus(String attachmentId, String content, boolean isExtracting, String error) {
        int position = findAttachmentById(attachmentId);
        if (position >= 0 && position < attachments.size()) {
            Attachment attachment = attachments.get(position);
            attachment.extractedContent = content;
            attachment.isExtracting = isExtracting;
            attachment.isExtracted = !isExtracting && error == null;
            attachment.extractionError = error;
            notifyItemChanged(position);
        }
    }

    /** 设置AI摘要 */
    public void setAISummary(String attachmentId, String summary) {
        int position = findAttachmentById(attachmentId);
        if (position >= 0 && position < attachments.size()) {
            attachments.get(position).aiSummary = summary;
            notifyItemChanged(position);
        }
    }

    public int findAttachmentById(String attachmentId) {
        if (attachmentId == null) return -1;
        for (int i = 0; i < attachments.size(); i++) {
            if (attachmentId.equals(attachments.get(i).id)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public int getItemCount() {
        return attachments.size();
    }

    static class ImageAttachmentViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView cardView;
        ImageView imageView;
        ProgressBar progressBar;
        View overlay;
        ImageView zoomIcon;
        ImageView removeButton;

        ImageAttachmentViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.attachment_card);
            imageView = itemView.findViewById(R.id.attachment_image);
            progressBar = itemView.findViewById(R.id.attachment_progress);
            overlay = itemView.findViewById(R.id.attachment_overlay);
            zoomIcon = itemView.findViewById(R.id.attachment_zoom_icon);
            removeButton = itemView.findViewById(R.id.attachment_remove);
        }
    }

    static class FileAttachmentViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView cardView;
        TextView fileIcon;
        TextView fileName;
        TextView fileSize;
        ImageView arrowIcon;
        ImageView removeButton;

        FileAttachmentViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.attachment_file_card);
            fileIcon = itemView.findViewById(R.id.attachment_file_icon);
            fileName = itemView.findViewById(R.id.attachment_file_name);
            fileSize = itemView.findViewById(R.id.attachment_file_size);
            arrowIcon = itemView.findViewById(R.id.attachment_file_arrow);
            removeButton = itemView.findViewById(R.id.attachment_file_remove);
        }
    }

    static class AudioAttachmentViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView cardView;
        TextView fileIcon;
        TextView fileName;
        TextView fileSize;
        ImageView arrowIcon;
        ImageView removeButton;

        AudioAttachmentViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.attachment_file_card);
            fileIcon = itemView.findViewById(R.id.attachment_file_icon);
            fileName = itemView.findViewById(R.id.attachment_file_name);
            fileSize = itemView.findViewById(R.id.attachment_file_size);
            arrowIcon = itemView.findViewById(R.id.attachment_file_arrow);
            removeButton = itemView.findViewById(R.id.attachment_file_remove);
        }
    }

    /** 更新解析状态指示器 */
    private void updateExtractionIndicator(ImageAttachmentViewHolder holder, Attachment attachment) {
        if (holder.overlay == null || holder.zoomIcon == null) return;
        
        if (attachment.isExtracting) {
            // 正在解析：显示加载图标
            holder.overlay.setVisibility(View.VISIBLE);
            holder.zoomIcon.setImageResource(android.R.drawable.ic_menu_rotate); // 旋转图标
            holder.zoomIcon.setVisibility(View.VISIBLE);
        } else if (attachment.isExtracted && attachment.extractedContent != null) {
            // 解析完成：显示文档图标
            holder.overlay.setVisibility(View.GONE);
            holder.zoomIcon.setImageResource(android.R.drawable.ic_menu_save); // 保存图标表示已提取
            holder.zoomIcon.setVisibility(View.VISIBLE);
        } else if (attachment.extractionError != null) {
            // 解析失败：显示错误图标
            holder.overlay.setVisibility(View.VISIBLE);
            holder.zoomIcon.setImageResource(android.R.drawable.ic_dialog_alert);
            holder.zoomIcon.setVisibility(View.VISIBLE);
        }
        // 其他情况保持原有逻辑
    }

    /** 显示解析结果预览对话框 */
    public void showExtractionPreview(Context context, Attachment attachment) {
        if (attachment == null) return;
        
        StringBuilder content = new StringBuilder();
        
        // 标题
        content.append("📄 ").append(attachment.name != null ? attachment.name : "未知文件").append("\n\n");
        
        // AI摘要（如果有）
        if (attachment.aiSummary != null && !attachment.aiSummary.isEmpty()) {
            content.append("🤖 AI智能摘要:\n");
            content.append(attachment.aiSummary).append("\n\n---\n\n");
        }
        
        // 提取的原始内容
        if (attachment.extractedContent != null && !attachment.extractedContent.isEmpty()) {
            content.append("📝 提取内容:\n");
            // 限制显示长度
            String preview = attachment.extractedContent.length() > 2000 
                ? attachment.extractedContent.substring(0, 2000) + "...\n[内容过长，已截断]" 
                : attachment.extractedContent;
            content.append(preview);
        } else if (attachment.isExtracting) {
            content.append("⏳ 正在解析中...");
        } else if (attachment.extractionError != null) {
            content.append("❌ 解析失败: ").append(attachment.extractionError);
        } else {
            content.append("ℹ️ 暂无提取内容");
        }
        
        // 创建对话框
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(context);
        builder.setTitle("附件内容预览")
            .setMessage(content.toString())
            .setPositiveButton("复制全文", (dialog, which) -> {
                // 复制到剪贴板
                if (attachment.extractedContent != null) {
                    android.content.ClipboardManager clipboard = 
                        (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText("附件内容", attachment.extractedContent);
                    clipboard.setPrimaryClip(clip);
                    android.widget.Toast.makeText(context, "✅ 已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("关闭", null)
            .setCancelable(true)
            .show();
    }
}

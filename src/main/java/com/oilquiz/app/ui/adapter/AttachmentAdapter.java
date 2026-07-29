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

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.ChatMessage.Attachment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class AttachmentAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final String TAG = "AttachmentAdapter";
    private static final int VIEW_TYPE_IMAGE = 0;
    private static final int VIEW_TYPE_FILE = 1;

    public interface OnAttachmentClickListener {
        void onImageClick(Attachment attachment, int position);
        void onFileClick(Attachment attachment, int position);
        void onAttachmentRemove(Attachment attachment, int position);
    }

    private final Context context;
    private final List<Attachment> attachments;
    private final OnAttachmentClickListener clickListener;

    public AttachmentAdapter(Context context, List<Attachment> attachments, OnAttachmentClickListener listener) {
        this.context = context;
        this.attachments = new ArrayList<>(attachments != null ? attachments : new ArrayList<>());
        this.clickListener = listener;
    }

    @Override
    public int getItemViewType(int position) {
        Attachment attachment = attachments.get(position);
        return isImageAttachment(attachment) ? VIEW_TYPE_IMAGE : VIEW_TYPE_FILE;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());

        if (viewType == VIEW_TYPE_IMAGE) {
            View view = inflater.inflate(R.layout.item_attachment_image, parent, false);
            return new ImageAttachmentViewHolder(view);
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
                    holder.imageView.setImageURI(uri);
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

        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onImageClick(attachment, position);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            showRemoveDialog(attachment, position);
            return true;
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
            if (clickListener != null) {
                clickListener.onFileClick(attachment, position);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            showRemoveDialog(attachment, position);
            return true;
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
}

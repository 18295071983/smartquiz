package com.oilquiz.app.ui.adapter;

import android.content.Context;
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

    private void bindFileAttachment(FileAttachmentViewHolder holder, Attachment attachment, int position) {
        holder.fileIcon.setText(attachment.getEmoji());

        if (holder.fileName != null) {
            holder.fileName.setText(attachment.name != null ? attachment.name : "未知文件");
        }

        if (holder.fileSize != null) {
            holder.fileSize.setText(attachment.getDisplaySize());
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

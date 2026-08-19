package com.oilquiz.app.ai.chat;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.oilquiz.app.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MessageAttachmentAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_IMAGE = 0;
    private static final int VIEW_TYPE_FILE = 1;
    private static final int VIEW_TYPE_MODEL_LINK = 2; // 在线模型链接类型

    // AttachmentItem 类型常量
    public static final int TYPE_IMAGE = 0;
    public static final int TYPE_FILE = 1;
    public static final int TYPE_MODEL_LINK = 2;

    private List<AttachmentItem> attachments = new ArrayList<>();
    private OnAttachmentClickListener listener;

    /**
     * 统一的附件项，支持多种类型
     */
    public static class AttachmentItem {
        public int type;
        public ChatMessage.Attachment attachment;
        // 模型链接专用字段
        public String modelName;
        public String modelType;
        public String apiUrl;
        public int modelStatus; // 0=未知, 1=在线, 2=离线, 3=错误
        public long latencyMs;
        public double costEstimate;

        public static AttachmentItem fromChatAttachment(ChatMessage.Attachment chatAttachment) {
            AttachmentItem item = new AttachmentItem();
            item.attachment = chatAttachment;
            item.type = chatAttachment.isImage() ? TYPE_IMAGE : TYPE_FILE;
            return item;
        }

        public static AttachmentItem modelLink(String modelName, String modelType,
                                               String apiUrl, int status, long latency, double cost) {
            AttachmentItem item = new AttachmentItem();
            item.type = TYPE_MODEL_LINK;
            item.modelName = modelName;
            item.modelType = modelType;
            item.apiUrl = apiUrl;
            item.modelStatus = status;
            item.latencyMs = latency;
            item.costEstimate = cost;
            return item;
        }
    }

    public interface OnAttachmentClickListener {
        void onPreview(ChatMessage.Attachment attachment);
        void onSave(ChatMessage.Attachment attachment);
        void onShare(ChatMessage.Attachment attachment);
        void onModelLinkClick(String modelName, String apiUrl);
    }

    public MessageAttachmentAdapter() {
    }

    public void setOnAttachmentClickListener(OnAttachmentClickListener listener) {
        this.listener = listener;
    }

    public void setAttachments(List<ChatMessage.Attachment> chatAttachments) {
        if (chatAttachments == null) {
            this.attachments.clear();
        } else {
            List<AttachmentItem> newItems = new ArrayList<>();
            for (ChatMessage.Attachment att : chatAttachments) {
                newItems.add(AttachmentItem.fromChatAttachment(att));
            }
            computeDiff(newItems);
        }
    }

    /**
     * 设置包含模型链接的附件
     */
    public void setAttachmentsWithModelLinks(List<ChatMessage.Attachment> chatAttachments,
                                            List<ChatMessage.ModelInfo> modelLinks) {
        List<AttachmentItem> newItems = new ArrayList<>();
        if (chatAttachments != null) {
            for (ChatMessage.Attachment att : chatAttachments) {
                newItems.add(AttachmentItem.fromChatAttachment(att));
            }
        }
        if (modelLinks != null) {
            for (ChatMessage.ModelInfo model : modelLinks) {
                newItems.add(AttachmentItem.modelLink(
                    model.modelName, model.modelType, model.apiUrl,
                    model.status, model.latencyMs, model.costEstimate));
            }
        }
        computeDiff(newItems);
    }

    private void computeDiff(List<AttachmentItem> newItems) {
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return attachments.size();
            }

            @Override
            public int getNewListSize() {
                return newItems.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                AttachmentItem oldItem = attachments.get(oldItemPosition);
                AttachmentItem newItem = newItems.get(newItemPosition);
                if (oldItem.type != newItem.type) return false;
                if (oldItem.type == TYPE_MODEL_LINK) {
                    return oldItem.modelName != null && oldItem.modelName.equals(newItem.modelName);
                }
                return oldItem.attachment != null && oldItem.attachment.id.equals(newItem.attachment.id);
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                AttachmentItem oldItem = attachments.get(oldItemPosition);
                AttachmentItem newItem = newItems.get(newItemPosition);
                if (oldItem.type == TYPE_MODEL_LINK) {
                    return oldItem.modelStatus == newItem.modelStatus
                            && oldItem.latencyMs == newItem.latencyMs;
                }
                return oldItem.attachment.status == newItem.attachment.status
                        && oldItem.attachment.uploadProgress == newItem.attachment.uploadProgress;
            }
        }, false);
        this.attachments = newItems;
        diffResult.dispatchUpdatesTo(this);
    }

    @Override
    public int getItemViewType(int position) {
        return attachments.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_IMAGE) {
            View view = inflater.inflate(R.layout.item_message_attachment_image, parent, false);
            return new ImageAttachmentViewHolder(view);
        } else if (viewType == VIEW_TYPE_MODEL_LINK) {
            View view = inflater.inflate(R.layout.item_message_model_link, parent, false);
            return new ModelLinkViewHolder(view);
        } else {
            View view = inflater.inflate(R.layout.item_message_attachment_file, parent, false);
            return new FileAttachmentViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        AttachmentItem item = attachments.get(position);
        if (holder instanceof ImageAttachmentViewHolder) {
            if (item.attachment != null) {
                bindImageAttachment((ImageAttachmentViewHolder) holder, item.attachment);
            }
        } else if (holder instanceof FileAttachmentViewHolder) {
            if (item.attachment != null) {
                bindFileAttachment((FileAttachmentViewHolder) holder, item.attachment);
            }
        } else if (holder instanceof ModelLinkViewHolder) {
            bindModelLink((ModelLinkViewHolder) holder, item);
        }
    }

    private void bindImageAttachment(ImageAttachmentViewHolder holder, ChatMessage.Attachment attachment) {
        boolean loaded = false;

        // 优先使用本地缩略图文件（采样解码，避免大图全尺寸 setImageURI 崩溃）
        if (attachment.thumbnailPath != null && !attachment.thumbnailPath.isEmpty()) {
            File thumbFile = new File(attachment.thumbnailPath);
            if (thumbFile.exists()) {
                android.graphics.Bitmap thumb = com.oilquiz.app.util.ImageParserUtil.parseImage(thumbFile, 1024, 1024);
                if (thumb != null) {
                    holder.imageView.setImageBitmap(thumb);
                    loaded = true;
                } else {
                    holder.imageView.setImageURI(Uri.fromFile(thumbFile));
                    loaded = true;
                }
            }
        }

        // 回退到 URL（可能是 content:// 或 file:// 或 http:// 或纯文件路径）
        if (!loaded && attachment.url != null && !attachment.url.isEmpty()) {
            try {
                Uri uri;
                // 处理纯文件路径（无 scheme）
                if (attachment.url.startsWith("/")) {
                    File f = new File(attachment.url);
                    if (f.exists()) {
                        uri = Uri.fromFile(f);
                    } else {
                        uri = Uri.parse(attachment.url);
                    }
                } else {
                    uri = Uri.parse(attachment.url);
                }
                if ("file".equals(uri.getScheme())) {
                    // file:// 手动采样解码，避免大图全尺寸解码撑爆内存
                    File localFile = new File(uri.getPath());
                    if (localFile.exists()) {
                        android.graphics.Bitmap bmp = com.oilquiz.app.util.ImageParserUtil.parseImage(localFile, 1024, 1024);
                        if (bmp != null) {
                            holder.imageView.setImageBitmap(bmp);
                            loaded = true;
                        }
                    }
                    if (!loaded) {
                        android.graphics.Bitmap bmp2 = com.oilquiz.app.util.ImageParserUtil.parseImage(localFile, 1024, 1024);
                        if (bmp2 != null) {
                            holder.imageView.setImageBitmap(bmp2);
                            loaded = true;
                        }
                    }
                } else {
                    // content:// 也采样解码（setImageURI 会全尺寸解码超大图导致 Canvas 崩溃）
                    try {
                        android.content.Context ctx = holder.imageView.getContext();
                        java.io.InputStream is = ctx.getContentResolver().openInputStream(uri);
                        if (is != null) {
                            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                            opts.inJustDecodeBounds = true;
                            android.graphics.BitmapFactory.decodeStream(is, null, opts);
                            is.close();
                            int sample = 1;
                            while (opts.outWidth / sample > 1024 || opts.outHeight / sample > 1024) sample *= 2;
                            opts.inJustDecodeBounds = false;
                            opts.inSampleSize = sample;
                            java.io.InputStream is2 = ctx.getContentResolver().openInputStream(uri);
                            if (is2 != null) {
                                android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(is2, null, opts);
                                is2.close();
                                if (bmp != null) {
                                    holder.imageView.setImageBitmap(bmp);
                                    loaded = true;
                                }
                            }
                        }
                    } catch (Exception e) {
                        // 采样失败回退 setImageURI（小图无碍）
                    }
                    if (!loaded) {
                        holder.imageView.setImageURI(uri);
                        loaded = true;
                    }
                }
            } catch (SecurityException e) {
                // content:// URI 权限已过期，无法访问
                android.util.Log.w("MessageAttachmentAdapter", "无法访问图片URI（权限过期）: " + attachment.url, e);
                loaded = false;
            } catch (Exception e) {
                android.util.Log.w("MessageAttachmentAdapter", "加载图片失败: " + attachment.url, e);
                loaded = false;
            }
        }

        // 加载失败时显示占位图
        if (!loaded) {
            holder.imageView.setImageResource(R.drawable.ic_ai_image);
            holder.imageView.setColorFilter(holder.itemView.getContext().getColor(R.color.text_secondary));
        } else {
            holder.imageView.clearColorFilter();
        }

        if (holder.uploadProgress != null) {
            if (attachment.isUploading() || attachment.status == ChatMessage.AttachmentStatus.PENDING ||
                attachment.status == ChatMessage.AttachmentStatus.PROCESSING) {
                holder.uploadProgress.setVisibility(View.VISIBLE);
                holder.uploadProgress.setProgress(attachment.uploadProgress);
                holder.uploadProgress.setIndeterminate(attachment.uploadProgress <= 0);
            } else {
                holder.uploadProgress.setVisibility(View.GONE);
            }
        }

        if (holder.errorOverlay != null) {
            if (attachment.hasError()) {
                holder.errorOverlay.setVisibility(View.VISIBLE);
                if (holder.errorText != null) {
                    holder.errorText.setText(attachment.errorMessage != null ? attachment.errorMessage : "加载失败");
                }
            } else {
                holder.errorOverlay.setVisibility(View.GONE);
            }
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onPreview(attachment);
            }
        });
    }

    private void bindFileAttachment(FileAttachmentViewHolder holder, ChatMessage.Attachment attachment) {
        holder.fileIcon.setText(attachment.getEmoji());
        holder.fileName.setText(attachment.name != null ? attachment.name : "未知文件");
        holder.fileSize.setText(attachment.getDisplaySize());

        if (attachment.hasError()) {
            holder.fileStatus.setText("失败: " + (attachment.errorMessage != null ? attachment.errorMessage : ""));
            holder.fileStatus.setTextColor(0xFFFF4444);
        } else if (attachment.isUploading()) {
            holder.fileStatus.setText("上传中... " + attachment.uploadProgress + "%");
            holder.fileStatus.setTextColor(0xFF2196F3);
        } else if (attachment.status == ChatMessage.AttachmentStatus.PROCESSING) {
            holder.fileStatus.setText("处理中...");
            holder.fileStatus.setTextColor(0xFF2196F3);
        } else if (attachment.status == ChatMessage.AttachmentStatus.PENDING) {
            holder.fileStatus.setText("等待上传...");
            holder.fileStatus.setTextColor(0xFFFF9800);
        } else {
            holder.fileStatus.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onPreview(attachment);
            }
        });

        if (holder.btnSave != null) {
            holder.btnSave.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onSave(attachment);
                }
            });
        }

        if (holder.btnShare != null) {
            holder.btnShare.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onShare(attachment);
                }
            });
        }
    }

    private void bindModelLink(ModelLinkViewHolder holder, AttachmentItem item) {
        holder.modelName.setText(item.modelName != null ? item.modelName : "未知模型");
        holder.modelType.setText(item.modelType != null ? item.modelType : "");

        // 状态指示器
        int indicatorDrawable;
        int statusTextRes;
        switch (item.modelStatus) {
            case 1: // online
                indicatorDrawable = R.drawable.status_indicator_online;
                statusTextRes = R.string.status_online;
                break;
            case 2: // offline
                indicatorDrawable = R.drawable.status_indicator_offline;
                statusTextRes = R.string.status_offline;
                break;
            case 3: // error
                indicatorDrawable = R.drawable.status_indicator_error;
                statusTextRes = R.string.status_error;
                break;
            default:
                indicatorDrawable = R.drawable.status_indicator_unknown;
                statusTextRes = R.string.status_unknown;
                break;
        }
        holder.statusIndicator.setBackgroundResource(indicatorDrawable);
        holder.statusText.setText(statusTextRes);

        // 延迟显示
        if (item.latencyMs > 0) {
            holder.latencyText.setVisibility(View.VISIBLE);
            holder.latencyText.setText(formatLatency(item.latencyMs));
            // 根据延迟设置颜色
            if (item.latencyMs < 200) {
                holder.latencyText.setTextColor(holder.itemView.getContext().getColor(R.color.success_color));
            } else if (item.latencyMs < 500) {
                holder.latencyText.setTextColor(holder.itemView.getContext().getColor(R.color.warning_color));
            } else {
                holder.latencyText.setTextColor(holder.itemView.getContext().getColor(R.color.error_color));
            }
        } else {
            holder.latencyText.setVisibility(View.GONE);
        }

        // 成本估算
        if (item.costEstimate > 0) {
            holder.costText.setVisibility(View.VISIBLE);
            holder.costText.setText(String.format(Locale.getDefault(), "$%.4f", item.costEstimate));
        } else {
            holder.costText.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onModelLinkClick(item.modelName, item.apiUrl);
            }
        });
    }

    private String formatLatency(long latencyMs) {
        if (latencyMs < 1000) {
            return latencyMs + "ms";
        } else {
            return String.format(Locale.getDefault(), "%.1fs", latencyMs / 1000.0);
        }
    }

    @Override
    public int getItemCount() {
        return attachments.size();
    }

    /**
     * 获取附件
     */
    public AttachmentItem getItem(int position) {
        if (position >= 0 && position < attachments.size()) {
            return attachments.get(position);
        }
        return null;
    }

    /**
     * 获取附件列表
     */
    public List<AttachmentItem> getAttachments() {
        return new ArrayList<>(attachments);
    }

    /**
     * 查找附件
     */
    public int findAttachmentById(String attachmentId) {
        for (int i = 0; i < attachments.size(); i++) {
            AttachmentItem item = attachments.get(i);
            if (item.attachment != null && item.attachment.id != null && 
                item.attachment.id.equals(attachmentId)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 更新附件状态
     */
    public void updateAttachmentStatus(String attachmentId, ChatMessage.AttachmentStatus status) {
        int position = findAttachmentById(attachmentId);
        if (position >= 0) {
            AttachmentItem item = attachments.get(position);
            if (item.attachment != null) {
                item.attachment.status = status;
                notifyItemChanged(position);
            }
        }
    }

    /**
     * 更新附件上传进度
     */
    public void updateAttachmentProgress(String attachmentId, int progress) {
        int position = findAttachmentById(attachmentId);
        if (position >= 0) {
            AttachmentItem item = attachments.get(position);
            if (item.attachment != null) {
                item.attachment.uploadProgress = progress;
                notifyItemChanged(position);
            }
        }
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

    /**
     * 获取附件数量
     */
    public int getAttachmentCount() {
        return attachments.size();
    }

    /**
     * 检查是否有附件
     */
    public boolean hasAttachments() {
        return !attachments.isEmpty();
    }

    static class ImageAttachmentViewHolder extends RecyclerView.ViewHolder {
        ImageView imageView;
        ProgressBar uploadProgress;
        View errorOverlay;
        TextView errorText;

        ImageAttachmentViewHolder(View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.attachment_image);
            uploadProgress = itemView.findViewById(R.id.upload_progress);
            errorOverlay = itemView.findViewById(R.id.error_overlay);
            errorText = itemView.findViewById(R.id.error_text);
        }
    }

    static class FileAttachmentViewHolder extends RecyclerView.ViewHolder {
        TextView fileIcon;
        TextView fileName;
        TextView fileSize;
        TextView fileStatus;
        TextView btnSave;
        TextView btnShare;

        FileAttachmentViewHolder(View itemView) {
            super(itemView);
            fileIcon = itemView.findViewById(R.id.file_icon);
            fileName = itemView.findViewById(R.id.file_name);
            fileSize = itemView.findViewById(R.id.file_size);
            fileStatus = itemView.findViewById(R.id.file_status);
            btnSave = itemView.findViewById(R.id.btn_save);
            btnShare = itemView.findViewById(R.id.btn_share);
        }
    }

    /**
     * 模型链接视图持有者
     */
    static class ModelLinkViewHolder extends RecyclerView.ViewHolder {
        View statusIndicator;
        TextView modelName;
        TextView modelType;
        TextView statusText;
        TextView latencyText;
        TextView costText;

        ModelLinkViewHolder(View itemView) {
            super(itemView);
            statusIndicator = itemView.findViewById(R.id.model_link_status_indicator);
            modelName = itemView.findViewById(R.id.model_link_name);
            modelType = itemView.findViewById(R.id.model_link_type);
            statusText = itemView.findViewById(R.id.model_link_status);
            latencyText = itemView.findViewById(R.id.model_link_latency);
            costText = itemView.findViewById(R.id.model_link_cost);
        }
    }
}

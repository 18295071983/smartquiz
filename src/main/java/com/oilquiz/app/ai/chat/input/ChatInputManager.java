package com.oilquiz.app.ai.chat.input;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ui.adapter.AttachmentAdapter;

import java.util.ArrayList;
import java.util.List;

import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;
/**
 * 管理聊天输入面板：输入框、发送按钮、附件按钮、附件列表。
 * 从 AIChatActivity 中提取的独立模块。
 */
public class ChatInputManager {

    public interface Callback {
        void onSendMessage(String text);
        void onAttachFile();
        void onShowToast(String message);
    }

    private final Activity activity;
    private final Callback callback;

    private EditText inputMessage;
    private MaterialButton btnSend;
    private MaterialButton btnAttach;
    private RecyclerView attachmentList;
    private AttachmentAdapter attachmentAdapter;

    private List<ChatMessage.Attachment> currentAttachments = new ArrayList<>();
    private boolean isGenerating = false;

    public ChatInputManager(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
    }

    public void init(EditText inputMessage, MaterialButton btnSend, MaterialButton btnAttach, RecyclerView attachmentList) {
        this.inputMessage = inputMessage;
        this.btnSend = btnSend;
        this.btnAttach = btnAttach;
        this.attachmentList = attachmentList;

        setupInputWatcher();
        setupSendButton();
        setupAttachButton();
        setupAttachmentList();
    }

    public void setGenerating(boolean generating) {
        this.isGenerating = generating;
        if (btnSend != null) {
            btnSend.setEnabled(!generating);
        }
    }

    public String getInputText() {
        if (inputMessage == null) return "";
        Editable text = inputMessage.getText();
        return text != null ? text.toString().trim() : "";
    }

    public void clearInput() {
        if (inputMessage != null) {
            inputMessage.setText("");
        }
    }

    public void appendText(String text) {
        if (inputMessage != null) {
            inputMessage.append(text);
        }
    }

    public void setText(String text) {
        if (inputMessage != null) {
            inputMessage.setText(text);
        }
    }

    public List<ChatMessage.Attachment> getCurrentAttachments() {
        return new ArrayList<>(currentAttachments);
    }

    public void clearAttachments() {
        currentAttachments.clear();
        if (attachmentList != null) {
            attachmentList.setVisibility(View.GONE);
        }
    }

    public void addAttachment(ChatMessage.Attachment attachment) {
        currentAttachments.add(attachment);
        if (attachmentAdapter != null) {
            attachmentAdapter.addAttachment(attachment);
        }
        if (attachmentList != null) {
            attachmentList.setVisibility(View.VISIBLE);
        }
    }

    public boolean hasAttachments() {
        return !currentAttachments.isEmpty();
    }

    public void focusInput() {
        if (inputMessage != null) {
            inputMessage.requestFocus();
        }
    }

    private void setupInputWatcher() {
        if (inputMessage == null) return;
        inputMessage.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (btnSend != null) {
                    boolean hasText = s != null && s.length() > 0;
                    btnSend.setEnabled(hasText || hasAttachments());
                }
            }
        });

        inputMessage.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }
            return false;
        });
    }

    private void setupSendButton() {
        if (btnSend != null) {
            btnSend.setOnClickListener(v -> sendMessage());
        }
    }

    private void setupAttachButton() {
        if (btnAttach != null) {
            btnAttach.setOnClickListener(v -> callback.onAttachFile());
        }
    }

    private void setupAttachmentList() {
        if (attachmentList == null) return;
        attachmentList.setLayoutManager(new LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false));
        attachmentAdapter = new AttachmentAdapter(activity, currentAttachments, new AttachmentAdapter.OnAttachmentClickListener() {
            @Override public void onImageClick(ChatMessage.Attachment a, int p) { openUri(a.url); }
            @Override public void onFileClick(ChatMessage.Attachment a, int p) { callback.onShowToast("文件: " + a.name); }
            @Override public void onAudioClick(ChatMessage.Attachment a, int p) { 
                // 播放音频
                try {
                    if (a.url != null) {
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(Uri.parse(a.url), "audio/*");
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        activity.startActivity(intent);
                    }
                } catch (Exception e) {
                    callback.onShowToast("无法播放音频");
                }
            }
            @Override public void onAttachmentRemove(ChatMessage.Attachment a, int p) {
                if (attachmentAdapter != null) {
                    attachmentAdapter.removeAttachment(p);
                    if (attachmentAdapter.isEmpty()) {
                        attachmentList.setVisibility(View.GONE);
                    }
                }
            }
        });
        attachmentList.setAdapter(attachmentAdapter);
        
        // 启用拖拽排序
        setupDragAndDrop();
    }

    /** 设置拖拽排序功能 */
    private void setupDragAndDrop() {
        if (attachmentAdapter == null || attachmentList == null) return;
        
        // 启用适配器的拖拽功能
        attachmentAdapter.enableDrag();
        
        // 设置拖拽监听器
        attachmentAdapter.setOnAttachmentDragListener(new AttachmentAdapter.OnAttachmentDragListener() {
            @Override
            public void onAttachmentMoved(int fromPosition, int toPosition) {
                // 更新数据源顺序
                if (fromPosition < currentAttachments.size() && toPosition < currentAttachments.size()) {
                    ChatMessage.Attachment item = currentAttachments.remove(fromPosition);
                    currentAttachments.add(toPosition, item);
                }
            }
            
            @Override
            public void onAttachmentDragStart() {
                callback.onShowToast("👆 拖动附件可调整顺序");
            }
            
            @Override
            public void onAttachmentDragEnd() {
                // 拖拽结束，可以保存新顺序
            }
        });
        
        // 创建 ItemTouchHelper
        ItemTouchHelper.Callback callback = new ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, // 支持左右拖动
            0 // 不支持滑动删除
        ) {
            @Override
            public boolean isLongPressDragEnabled() {
                return true; // 长按触发拖拽
            }
            
            @Override
            public boolean isItemViewSwipeEnabled() {
                return false; // 禁用滑动删除
            }
            
            @Override
            public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
                super.onSelectedChanged(viewHolder, actionState);
                if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
                    // 拖拽结束
                    if (attachmentAdapter != null) {
                        attachmentAdapter.disableDrag();
                    }
                }
            }
            
            @Override
            public boolean onMove(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder, 
                                RecyclerView.ViewHolder target) {
                int fromPos = viewHolder.getAdapterPosition();
                int toPos = target.getAdapterPosition();
                if (attachmentAdapter != null) {
                    attachmentAdapter.moveAttachment(fromPos, toPos);
                }
                return true;
            }
            
            @Override
            public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
                // 不处理滑动删除（因为禁用了）
            }
        };
        
        ItemTouchHelper touchHelper = new ItemTouchHelper(callback);
        touchHelper.attachToRecyclerView(attachmentList);
    }

    private void sendMessage() {
        String text = getInputText();
        if (text.isEmpty() && !hasAttachments()) return;
        callback.onSendMessage(text);
    }

    /** 切换编辑模式 */
    public void toggleEditMode() {
        if (attachmentAdapter == null) return;
        
        if (attachmentAdapter.isEditMode()) {
            // 退出编辑模式
            attachmentAdapter.disableEditMode();
            callback.onShowToast("✅ 已退出编辑模式");
        } else {
            // 进入编辑模式
            attachmentAdapter.enableEditMode();
            callback.onShowToast("✏️ 点击附件可选中，长按可拖拽排序");
        }
    }

    /** 全选附件 */
    public void selectAllAttachments() {
        if (attachmentAdapter != null && attachmentAdapter.isEditMode()) {
            attachmentAdapter.selectAll();
            callback.onShowToast("已全选 " + attachmentAdapter.getSelectedCount() + " 个附件");
        }
    }

    /** 取消选择 */
    public void clearSelection() {
        if (attachmentAdapter != null) {
            attachmentAdapter.clearSelection();
        }
    }

    /** 批量删除选中的附件 */
    public void deleteSelectedAttachments() {
        if (attachmentAdapter == null || !attachmentAdapter.isEditMode()) {
            callback.onShowToast("请先开启编辑模式");
            return;
        }
        
        int count = attachmentAdapter.getSelectedCount();
        if (count == 0) {
            callback.onShowToast("请先选择要删除的附件");
            return;
        }
        
        // 显示确认对话框（这里简化为直接删除）
        attachmentAdapter.deleteSelected();
        
        if (attachmentAdapter.isEmpty()) {
            if (attachmentList != null) {
                attachmentList.setVisibility(View.GONE);
            }
            // 退出编辑模式
            attachmentAdapter.disableEditMode();
        }
        
        callback.onShowToast("🗑️ 已删除 " + count + " 个附件");
    }

    private void openUri(String url) {
        if (url != null) {
            try {
                // 图片 → 应用内预览（避免系统无图片查看器时"没有可用打开图片的页面"）
                if (isImage(url)) {
                    showImagePreview(url);
                    return;
                }
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            } catch (Exception e) {
                callback.onShowToast("无法打开");
            }
        }
    }

    private boolean isImage(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp");
    }

    /** 应用内图片预览（统一全屏流：ImagePreviewUtil——PhotoView 双指缩放 + 本地系统解码/网络原生下载） */
    private void showImagePreview(String url) {
        com.oilquiz.app.ai.chat.component.ImagePreviewUtil.show(activity, url);
        return;
        /*
        try {
            android.app.Dialog dialog = new android.app.Dialog(activity);
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            com.github.chrisbanes.photoview.PhotoView photoView = new com.github.chrisbanes.photoview.PhotoView(activity);
            photoView.setBackgroundColor(android.graphics.Color.BLACK);

            // 尝试直接解码本地文件（file:// 或纯路径），绕开 Glide
            boolean decoded = false;
            try {
                java.io.File localFile = null;
                if (url != null && url.startsWith("file://")) {
                    localFile = new java.io.File(Uri.parse(url).getPath());
                } else if (url != null && url.startsWith("/")) {
                    localFile = new java.io.File(url);
                }
                if (localFile != null && localFile.exists()) {
                    android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeFile(localFile.getAbsolutePath(), opts);
                    int sample = 1;
                    while (opts.outWidth / sample > 2048 || opts.outHeight / sample > 2048) {
                        sample *= 2;
                    }
                    opts.inJustDecodeBounds = false;
                    opts.inSampleSize = sample;
                    android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(localFile.getAbsolutePath(), opts);
                    if (bmp != null) {
                        photoView.setImageBitmap(bmp);
                        decoded = true;
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("ChatInputManager", "Bitmap decode failed: " + e.getMessage());
            }
            if (!decoded) {
                com.bumptech.glide.Glide.with(activity).load(url)
                        .error(new android.graphics.drawable.ColorDrawable(ThemeColors.get(R.color.hc_ff1e293b)))
                        .into(photoView);
            }

            dialog.setContentView(photoView, new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            photoView.setOnClickListener(v -> dialog.dismiss());
            dialog.show();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK));
            }
        } catch (Exception e) {
            callback.onShowToast("无法预览图片");
        }
        */
    }
}

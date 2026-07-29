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
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ui.adapter.AttachmentAdapter;

import java.util.ArrayList;
import java.util.List;

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
    }

    private void sendMessage() {
        String text = getInputText();
        if (text.isEmpty() && !hasAttachments()) return;
        callback.onSendMessage(text);
    }

    private void openUri(String url) {
        if (url != null) {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            } catch (Exception e) {
                callback.onShowToast("无法打开");
            }
        }
    }
}

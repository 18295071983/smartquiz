package com.oilquiz.app.ai.chat.input;

import android.app.Activity;
import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;

/**
 * 聊天输入栏组件（全局可复用）。
 *
 * 从 AIChatActivity 输入区抽出的视图容器：加载 {@code view_chat_input_bar}，
 * 暴露输入框/附件/语音/发送/停止控件 getter，并提供便捷接线方法创建
 * {@link ChatInputManager} 统一管理行为。
 *
 * 用法：
 * <pre>
 * ChatInputBar inputBar = findViewById(R.id.chat_input_bar);
 * inputManager = inputBar.attachManager(activity, callback);  // 内部 init 控件
 * inputManager.setGenerating(true);                            // 发送/停止切换
 * </pre>
 */
public class ChatInputBar extends LinearLayout {

    private EditText inputMessage;
    private MaterialButton btnAttach;
    private MaterialButton btnVoice;
    private MaterialButton btnSend;
    private MaterialButton btnStopGeneration;

    public ChatInputBar(Context context) {
        super(context);
        init(context);
    }

    public ChatInputBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ChatInputBar(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setOrientation(HORIZONTAL);
        setGravity(android.view.Gravity.CENTER_VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.view_chat_input_bar, this, true);
        inputMessage = findViewById(R.id.input_message);
        btnAttach = findViewById(R.id.btn_attach);
        btnVoice = findViewById(R.id.btn_voice);
        btnSend = findViewById(R.id.btn_send);
        btnStopGeneration = findViewById(R.id.btn_stop_generation);
    }

    // ==================== 控件 getter ====================

    public EditText getInputMessage() {
        return inputMessage;
    }

    public MaterialButton getBtnAttach() {
        return btnAttach;
    }

    public MaterialButton getBtnVoice() {
        return btnVoice;
    }

    public MaterialButton getBtnSend() {
        return btnSend;
    }

    public MaterialButton getBtnStopGeneration() {
        return btnStopGeneration;
    }

    // ==================== 便捷接线 ====================

    /**
     * 创建并绑定 {@link ChatInputManager}（内部完成控件注入）。
     *
     * @param attachmentList 页面级附件列表（输入栏上方的横向列表），可为 null（无附件列表时安全跳过）
     */
    public ChatInputManager attachManager(Activity activity, ChatInputManager.Callback callback,
                                          RecyclerView attachmentList) {
        ChatInputManager manager = new ChatInputManager(activity, callback);
        manager.init(inputMessage, btnSend, btnAttach, attachmentList);
        return manager;
    }
}

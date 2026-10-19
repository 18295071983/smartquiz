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
import com.oilquiz.app.ai.chat.ui.ChatUiFlags;

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

    /** v2 独有：输入胶囊上方的"正在生成"提示行（demo .gen）；旧版布局下全为 null */
    private android.view.View genRow;
    private android.view.View genDot;
    private android.widget.TextView genText;
    private android.animation.ObjectAnimator genPulse;

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
        boolean v2 = ChatUiFlags.isUiV2Enabled(context);
        // v2 是「生成提示行 + 胶囊」两层竖排，胶囊内部自己横向排控件；旧版整块只有一行
        setOrientation(v2 ? VERTICAL : HORIZONTAL);
        setGravity(android.view.Gravity.CENTER_VERTICAL);
        LayoutInflater.from(context).inflate(
                v2 ? R.layout.view_chat_input_bar_v2 : R.layout.view_chat_input_bar, this, true);
        inputMessage = findViewById(R.id.input_message);
        btnAttach = findViewById(R.id.btn_attach);
        btnVoice = findViewById(R.id.btn_voice);
        btnSend = findViewById(R.id.btn_send);
        btnStopGeneration = findViewById(R.id.btn_stop_generation);
        if (v2) {
            genRow = findViewById(R.id.chat_v2_gen_row);
            genDot = findViewById(R.id.chat_v2_gen_dot);
            genText = findViewById(R.id.chat_v2_gen_text);
        }
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
     * v2：显示/隐藏输入胶囊上方的"正在生成"提示行（demo {@code .composer .gen}）。
     *
     * <p>旧版把生成状态写在大状态条与气泡上方，v2 收敛成胶囊上方这一行极淡的提示，
     * 所以开关关着时（{@code genRow == null}）本方法什么都不做。
     *
     * @param show   是否生成中
     * @param detail 追加在"正在生成"后的细节（阶段名等），null 时只显示默认文案
     */
    public void setGeneratingHint(boolean show, CharSequence detail) {
        if (genRow == null) return;
        if (genText != null) {
            String base = genText.getContext().getString(R.string.chat_v2_generating);
            genText.setText(android.text.TextUtils.isEmpty(detail)
                    ? base : base + " · " + detail);
        }
        genRow.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
        if (genDot != null) {
            if (show) {
                if (genPulse == null) {
                    // demo 的 .pulse：1.1s 一次呼吸；这里用 REVERSE 的 alpha 动画等价实现
                    genPulse = android.animation.ObjectAnimator.ofFloat(genDot, "alpha", 1f, 0.25f);
                    genPulse.setDuration(550);
                    genPulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                    genPulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                    genPulse.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                }
                if (!genPulse.isRunning()) genPulse.start();
            } else {
                if (genPulse != null) {
                    genPulse.cancel();
                    genPulse = null;
                }
                genDot.setAlpha(1f);
            }
        }
    }

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

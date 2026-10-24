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

    /**
     * v2 生成状态行（2026-10-25 重设计）：输入胶囊上方一行，只放状态机 + 速度 + 余额。
     * 结构：{@code ● 生成中 · 思考中 · 模型就绪   42.3 t/s   余额 CNY ¥11.84}
     */
    private android.view.View genRow;
    private android.view.View genDot;
    private android.widget.TextView genText;
    private android.widget.TextView genInfer;
    private android.widget.TextView genModel;
    private android.widget.TextView genSpeed;
    private android.widget.TextView genBalance;
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
        // 「生成提示行 + 胶囊」两层竖排，胶囊内部自己横向排控件
        setOrientation(VERTICAL);
        setGravity(android.view.Gravity.CENTER_VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.view_chat_input_bar_v2, this, true);
        inputMessage = findViewById(R.id.input_message);
        btnAttach = findViewById(R.id.btn_attach);
        btnVoice = findViewById(R.id.btn_voice);
        btnSend = findViewById(R.id.btn_send);
        btnStopGeneration = findViewById(R.id.btn_stop_generation);
        genRow = findViewById(R.id.chat_v2_gen_row);
        genDot = findViewById(R.id.chat_v2_gen_dot);
        genText = findViewById(R.id.chat_v2_gen_text);
        genInfer = findViewById(R.id.chat_v2_gen_infer);
        genModel = findViewById(R.id.chat_v2_gen_model);
        genSpeed = findViewById(R.id.chat_v2_gen_speed);
        genBalance = findViewById(R.id.chat_v2_gen_balance);
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
     * 显示/隐藏输入胶囊上方的生成状态行（2026-10-25 重设计：生成状态从顶部挪到这里）。
     *
     * <p>行内只有四类信息：推理状态机（{@link #setInferencePhase}）、模型状态机
     * （{@link #setModelState}）、推理速度（{@link #setInferenceSpeed}）、余额
     * （{@link #setBalance}）。{@code show=false} 时整行收起，速度/余额等瞬时读数一并清空。</p>
     *
     * @param show   是否生成中
     * @param detail 追加在"生成中"后的细节（一般不用，阶段走 {@link #setInferencePhase}）
     */
    public void setGeneratingHint(boolean show, CharSequence detail) {
        if (genRow == null) return;
        if (genText != null && !android.text.TextUtils.isEmpty(detail)) {
            genText.setText("生成中 · " + detail);
        }
        genRow.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
        if (!show) {
            setInferencePhase(null);
            setModelState(null);
            setInferenceSpeed(0f);
            setBalance(null);
        }
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

    /** 推理状态机阶段（等待响应 / 思考中 / 生成中）；null 或空串隐藏该段 */
    public void setInferencePhase(CharSequence phase) {
        setSegment(genInfer, phase, " · ", true);
    }

    /** 模型状态机（就绪 / 加载模型… / 初始化失败）；null 或空串隐藏该段 */
    public void setModelState(CharSequence modelState) {
        setSegment(genModel, modelState, " · ", true);
    }

    /** 余额（最右端）；null 或空串隐藏 */
    public void setBalance(CharSequence balance) {
        setSegment(genBalance, balance, "", false);
    }

    /** 推理速度（tokens/s）；&lt;=0 隐藏 */
    public void setInferenceSpeed(float tokensPerSecond) {
        if (genSpeed == null) return;
        if (tokensPerSecond > 0.05f) {
            String text = String.format(java.util.Locale.US, "%.1f t/s", tokensPerSecond);
            if (!text.contentEquals(genSpeed.getText())) genSpeed.setText(text);
            if (genSpeed.getVisibility() != android.view.View.VISIBLE) {
                genSpeed.setVisibility(android.view.View.VISIBLE);
            }
        } else if (genSpeed.getVisibility() != android.view.View.GONE) {
            genSpeed.setVisibility(android.view.View.GONE);
        }
    }

    /** 单段文本的通用设置：空值隐藏、有值加前缀后显示（避免每 tick 重复 setText 触发重绘） */
    private void setSegment(android.widget.TextView view, CharSequence text, String prefix,
                            boolean trim) {
        if (view == null) return;
        if (android.text.TextUtils.isEmpty(text)) {
            if (view.getVisibility() != android.view.View.GONE) {
                view.setVisibility(android.view.View.GONE);
            }
            return;
        }
        String value = trim ? String.valueOf(text).trim() : String.valueOf(text);
        if (value.isEmpty()) {
            if (view.getVisibility() != android.view.View.GONE) {
                view.setVisibility(android.view.View.GONE);
            }
            return;
        }
        CharSequence display = prefix.isEmpty() ? value : prefix + value;
        if (!display.toString().contentEquals(view.getText())) {
            view.setText(display);
        }
        if (view.getVisibility() != android.view.View.VISIBLE) {
            view.setVisibility(android.view.View.VISIBLE);
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

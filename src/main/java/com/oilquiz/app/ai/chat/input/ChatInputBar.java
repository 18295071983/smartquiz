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
     * <p>整行只有：**一个当前状态** + 推理速度 + 余额；模型状态机只在加载/异常时作为后缀出现，
     * 就绪不显示（"."就绪"对用户没有信息量，还和顶部副标题重复）。</p>
     *
     * @param show   是否生成中
     * @param detail 追加的状态细节；一般传 null，状态走 {@link #setInferencePhase}
     */
    public void setGeneratingHint(boolean show, CharSequence detail) {
        if (genRow == null) return;
        if (genText != null && !android.text.TextUtils.isEmpty(detail)) {
            setSegment(genText, detail, "", true);
        }
        genRow.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
        if (!show) {
            setInferencePhase(null);
            setModelState(null);
            setInferenceSpeed(0f);
            setBalance(null);
            if (genText != null) genText.setVisibility(android.view.View.GONE);
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

    /** 推理状态机阶段（等待响应 / 思考中 / 生成中）——整行唯一的主动态，不再和"生成中"重复 */
    public void setInferencePhase(CharSequence phase) {
        setSegment(genText, phase, "", true);
    }

    /**
     * 模型状态机：只在加载/异常时作为后缀出现（「· 模型 加载模型…」/「· 模型 初始化失败」）；
     * 就绪/空闲不显示——顶部副标题已经写着模型与"就绪"，这里再写一遍就是噪音。
     */
    public void setModelState(CharSequence modelState) {
        if (modelState == null || isModelReadyState(modelState)) {
            setSegment(genModel, null, "", true);
            return;
        }
        setSegment(genModel, modelState, "· 模型 ", true);
    }

    /** 模型已就绪/空闲的文案：这些不作为状态行后缀显示 */
    private static boolean isModelReadyState(CharSequence state) {
        if (state == null) return true;
        String s = state.toString();
        return s.contains("就绪") || s.contains("空闲") || s.contains("运行中")
                || s.contains("可用") || s.equalsIgnoreCase("ready");
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

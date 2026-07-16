package com.oilquiz.app.ai.chat;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import androidx.annotation.NonNull;

import com.oilquiz.app.R;

/**
 * 模式选择器对话框
 * 
 * 功能：
 * - 显示当前模式
 * - 手动选择模式
 * - 开启/关闭自动模式
 * - 显示模式说明
 */
public class ModeSelectorDialog {

    public interface OnModeSelectedListener {
        void onModeSelected(ChatModeManager.ChatMode mode);
        void onAutoModeChanged(boolean enabled);
    }

    private final Context context;
    private Dialog dialog;
    private ChatModeManager modeManager;
    private OnModeSelectedListener listener;
    private View dialogView;

    // UI 组件
    private RadioButton rbNormal, rbThinking, rbCreative;
    private View autoSwitchContainer;
    private SwitchCompat switchAutoMode;
    private TextView tvModeDesc;

    public ModeSelectorDialog(Context context) {
        this.context = context;
        this.modeManager = ChatModeManager.getInstance(context);
    }

    public void setOnModeSelectedListener(OnModeSelectedListener listener) {
        this.listener = listener;
    }

    public void show() {
        dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        
        dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_mode_selector, null);
        dialog.setContentView(dialogView);

        // 设置背景透明
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setGravity(Gravity.BOTTOM);
            dialog.getWindow().setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            );
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        initViews(dialogView);
        setupListeners();
        updateUI();

        dialog.show();
    }

    private void initViews(View view) {
        rbNormal = view.findViewById(R.id.rb_mode_normal_inner);
        rbThinking = view.findViewById(R.id.rb_mode_thinking_inner);
        rbCreative = view.findViewById(R.id.rb_mode_creative_inner);
        autoSwitchContainer = view.findViewById(R.id.container_auto_switch);
        switchAutoMode = view.findViewById(R.id.switch_auto_mode);
        tvModeDesc = view.findViewById(R.id.tv_mode_description);

        // 点击关闭按钮
        View btnClose = view.findViewById(R.id.btn_close);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dismiss());
        }
    }

    private void setupListeners() {
        // 模式选择 - 点击整个行布局来切换
        View.OnClickListener modeClickListener = v -> {
            ChatModeManager.ChatMode selectedMode;
            int id = v.getId();
            if (id == R.id.rb_mode_normal || id == R.id.rb_mode_normal_inner) {
                selectedMode = ChatModeManager.ChatMode.NORMAL;
                rbNormal.setChecked(true);
                rbThinking.setChecked(false);
                rbCreative.setChecked(false);
            } else if (id == R.id.rb_mode_thinking || id == R.id.rb_mode_thinking_inner) {
                selectedMode = ChatModeManager.ChatMode.DEEP_THINKING;
                rbNormal.setChecked(false);
                rbThinking.setChecked(true);
                rbCreative.setChecked(false);
            } else if (id == R.id.rb_mode_creative || id == R.id.rb_mode_creative_inner) {
                selectedMode = ChatModeManager.ChatMode.CREATIVE;
                rbNormal.setChecked(false);
                rbThinking.setChecked(false);
                rbCreative.setChecked(true);
            } else {
                return;
            }

            modeManager.setManualMode(selectedMode);
            updateModeDescription(selectedMode);

            if (listener != null) {
                listener.onModeSelected(selectedMode);
            }
        };

        // 为RadioButton设置点击监听
        rbNormal.setOnClickListener(modeClickListener);
        rbThinking.setOnClickListener(modeClickListener);
        rbCreative.setOnClickListener(modeClickListener);

        // 为整个行布局设置点击监听
        View rowNormal = dialogView.findViewById(R.id.rb_mode_normal);
        View rowThinking = dialogView.findViewById(R.id.rb_mode_thinking);
        View rowCreative = dialogView.findViewById(R.id.rb_mode_creative);
        if (rowNormal != null) rowNormal.setOnClickListener(modeClickListener);
        if (rowThinking != null) rowThinking.setOnClickListener(modeClickListener);
        if (rowCreative != null) rowCreative.setOnClickListener(modeClickListener);

        // 自动切换开关
        switchAutoMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            modeManager.setAutoModeEnabled(isChecked);
            if (listener != null) {
                listener.onAutoModeChanged(isChecked);
            }
        });
    }

    private void updateUI() {
        // 更新当前选中状态
        ChatModeManager.ChatMode current = modeManager.getCurrentMode();
        switch (current) {
            case DEEP_THINKING:
                rbThinking.setChecked(true);
                break;
            case CREATIVE:
                rbCreative.setChecked(true);
                break;
            case NORMAL:
            default:
                rbNormal.setChecked(true);
                break;
        }

        // 更新自动模式开关
        switchAutoMode.setChecked(modeManager.isAutoModeEnabled());

        // 更新模式描述
        updateModeDescription(current);
    }

    private void updateModeDescription(ChatModeManager.ChatMode mode) {
        String desc;
        switch (mode) {
            case DEEP_THINKING:
                desc = "多角度分析问题，展示完整推理过程\n适合：分析原因、比较选项、深入探讨";
                break;
            case CREATIVE:
                desc = "创作文章、故事、诗歌等作品\n适合：写作任务、创意文案、文学创作";
                break;
            case NORMAL:
            default:
                desc = "友好、专业地回答问题\n适合：日常对话、知识问答、简单任务";
                break;
        }
        tvModeDesc.setText(desc);
    }

    public void dismiss() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }
}

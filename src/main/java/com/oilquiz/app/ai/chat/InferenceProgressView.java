package com.oilquiz.app.ai.chat;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.oilquiz.app.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 推理进度可视化视图
 * 
 * 显示内容：
 * 1. 当前推理阶段（带图标）
 * 2. 进度条（ determinate/indeterminate ）
 * 3. Token生成统计
 * 4. 生成速度
 * 5. 步骤指示器
 */
public class InferenceProgressView extends FrameLayout {
    
    private TextView tvPhase;
    private TextView tvPhaseEmoji;
    private ProgressBar progressBar;
    private ProgressBar indeterminateProgress;
    private TextView tvTokenStats;
    private TextView tvSpeed;
    private TextView tvTimeElapsed;
    private LinearLayout stepsContainer;
    private View contentContainer;
    
    private List<StepIndicator> stepIndicators = new ArrayList<>();
    private ObjectAnimator pulseAnimator;
    
    private InferenceStateManager.InferenceState currentState = InferenceStateManager.InferenceState.IDLE;
    
    public InferenceProgressView(Context context) {
        super(context);
        init(context);
    }
    
    public InferenceProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }
    
    public InferenceProgressView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }
    
    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.view_inference_progress, this, true);
        
        tvPhase = findViewById(R.id.tv_phase);
        tvPhaseEmoji = findViewById(R.id.tv_phase_emoji);
        progressBar = findViewById(R.id.progress_bar);
        indeterminateProgress = findViewById(R.id.indeterminate_progress);
        tvTokenStats = findViewById(R.id.tv_token_stats);
        tvSpeed = findViewById(R.id.tv_speed);
        tvTimeElapsed = findViewById(R.id.tv_time_elapsed);
        stepsContainer = findViewById(R.id.steps_container);
        contentContainer = findViewById(R.id.content_container);
        
        initStepIndicators();
        hide();
    }
    
    /**
     * 初始化步骤指示器
     */
    private void initStepIndicators() {
        stepIndicators.clear();
        stepsContainer.removeAllViews();
        
        String[] stepLabels = {"初始化", "加载模型", "编码", "生成"};
        String[] stepEmojis = {"⚙️", "📦", "🔄", "✍️"};
        
        for (int i = 0; i < stepLabels.length; i++) {
            StepIndicator indicator = new StepIndicator(getContext());
            indicator.setLabel(stepLabels[i]);
            indicator.setEmoji(stepEmojis[i]);
            indicator.setStepNumber(i + 1);
            
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
            if (i < stepLabels.length - 1) {
                params.setMarginEnd(8);
            }
            indicator.setLayoutParams(params);
            
            stepsContainer.addView(indicator);
            stepIndicators.add(indicator);
        }
    }
    
    /**
     * 更新状态显示
     */
    public void updateState(InferenceStateManager.InferenceState state, 
                           InferenceStateManager.StateDetails details) {
        currentState = state;
        
        if (state == InferenceStateManager.InferenceState.IDLE ||
            state == InferenceStateManager.InferenceState.COMPLETED ||
            state == InferenceStateManager.InferenceState.FAILED ||
            state == InferenceStateManager.InferenceState.CANCELLED) {
            hide();
            return;
        }
        
        show();
        
        // 更新阶段文本和图标
        PhaseInfo phaseInfo = getPhaseInfo(state);
        tvPhase.setText(phaseInfo.displayText);
        tvPhaseEmoji.setText(phaseInfo.emoji);
        
        // 更新进度条
        updateProgressBar(state, details);
        
        // 更新步骤指示器
        updateStepIndicators(state);
        
        // 更新统计信息
        updateStats(details);
        
        // 更新动画
        updateAnimation(state);
    }
    
    /**
     * 更新进度
     */
    public void updateProgress(InferenceStateManager.StateDetails details) {
        if (currentState == InferenceStateManager.InferenceState.IDLE) return;
        
        updateStats(details);
        
        // 更新确定性进度条
        if (details.totalTokens > 0) {
            progressBar.setProgress(details.progressPercent);
        }
    }
    
    /**
     * 更新进度条显示
     */
    private void updateProgressBar(InferenceStateManager.InferenceState state,
                                   InferenceStateManager.StateDetails details) {
        boolean isDeterminate = details.totalTokens > 0 || 
            state == InferenceStateManager.InferenceState.GENERATING;
        
        if (isDeterminate) {
            progressBar.setVisibility(View.VISIBLE);
            indeterminateProgress.setVisibility(View.GONE);
            progressBar.setProgress(details.progressPercent);
        } else {
            progressBar.setVisibility(View.GONE);
            indeterminateProgress.setVisibility(View.VISIBLE);
        }
    }
    
    /**
     * 更新步骤指示器
     */
    private void updateStepIndicators(InferenceStateManager.InferenceState state) {
        int activeStep = getStepNumber(state);
        
        for (int i = 0; i < stepIndicators.size(); i++) {
            StepIndicator indicator = stepIndicators.get(i);
            if (i < activeStep) {
                indicator.setStatus(StepIndicator.Status.COMPLETED);
            } else if (i == activeStep) {
                indicator.setStatus(StepIndicator.Status.ACTIVE);
            } else {
                indicator.setStatus(StepIndicator.Status.PENDING);
            }
        }
    }
    
    /**
     * 更新统计信息
     */
    private void updateStats(InferenceStateManager.StateDetails details) {
        // Token统计
        if (details.processedTokens > 0) {
            if (details.totalTokens > 0) {
                tvTokenStats.setText(String.format("Token: %d/%d", 
                    details.processedTokens, details.totalTokens));
            } else {
                tvTokenStats.setText(String.format("Token: %d", details.processedTokens));
            }
            tvTokenStats.setVisibility(View.VISIBLE);
        } else {
            tvTokenStats.setVisibility(View.GONE);
        }
        
        // 生成速度
        if (details.tokensPerSecond > 0) {
            tvSpeed.setText(String.format("%.1f token/s", details.tokensPerSecond));
            tvSpeed.setVisibility(View.VISIBLE);
        } else {
            tvSpeed.setVisibility(View.GONE);
        }
        
        // 已用时间
        long elapsedSeconds = details.getElapsedTime() / 1000;
        tvTimeElapsed.setText(String.format("已用时间: %d秒", elapsedSeconds));
    }
    
    /**
     * 更新动画
     */
    private void updateAnimation(InferenceStateManager.InferenceState state) {
        if (state == InferenceStateManager.InferenceState.GENERATING ||
            state == InferenceStateManager.InferenceState.THINKING) {
            startPulseAnimation();
        } else {
            stopPulseAnimation();
        }
    }
    
    /**
     * 开始脉冲动画
     */
    private void startPulseAnimation() {
        if (pulseAnimator != null && pulseAnimator.isRunning()) return;
        
        pulseAnimator = ObjectAnimator.ofFloat(tvPhaseEmoji, "alpha", 1f, 0.5f, 1f);
        pulseAnimator.setDuration(1000);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setInterpolator(new LinearInterpolator());
        pulseAnimator.start();
    }
    
    /**
     * 停止脉冲动画
     */
    private void stopPulseAnimation() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
        tvPhaseEmoji.setAlpha(1f);
    }
    
    /**
     * 获取阶段信息
     */
    private PhaseInfo getPhaseInfo(InferenceStateManager.InferenceState state) {
        switch (state) {
            case INITIALIZING:
                return new PhaseInfo("正在初始化...", "⚙️");
            case MODEL_LOADING:
                return new PhaseInfo("正在加载模型...", "📦");
            case PROMPT_ENCODING:
                return new PhaseInfo("正在编码输入...", "🔄");
            case PREFILL:
                return new PhaseInfo("正在处理上下文...", "📝");
            case THINKING:
                return new PhaseInfo("正在深度思考...", "🤔");
            case GENERATING:
                return new PhaseInfo("正在生成回复...", "✍️");
            case DECODING:
                return new PhaseInfo("正在解码...", "📤");
            case TIMEOUT:
                return new PhaseInfo("响应超时", "⏰");
            default:
                return new PhaseInfo("准备中...", "⏳");
        }
    }
    
    /**
     * 获取步骤编号
     */
    private int getStepNumber(InferenceStateManager.InferenceState state) {
        switch (state) {
            case INITIALIZING:
                return 0;
            case MODEL_LOADING:
                return 1;
            case PROMPT_ENCODING:
            case PREFILL:
                return 2;
            case THINKING:
            case GENERATING:
            case DECODING:
                return 3;
            default:
                return 0;
        }
    }
    
    /**
     * 显示视图
     */
    public void show() {
        if (getVisibility() != View.VISIBLE) {
            setVisibility(View.VISIBLE);
            contentContainer.setAlpha(0f);
            contentContainer.animate()
                .alpha(1f)
                .setDuration(200)
                .start();
        }
    }
    
    /**
     * 隐藏视图
     */
    public void hide() {
        stopPulseAnimation();
        if (getVisibility() == View.VISIBLE) {
            contentContainer.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction(() -> setVisibility(View.GONE))
                .start();
        }
    }
    
    /**
     * 阶段信息
     */
    private static class PhaseInfo {
        final String displayText;
        final String emoji;
        
        PhaseInfo(String displayText, String emoji) {
            this.displayText = displayText;
            this.emoji = emoji;
        }
    }
    
    /**
     * 步骤指示器视图
     */
    private static class StepIndicator extends FrameLayout {
        private TextView tvEmoji;
        private TextView tvLabel;
        private View indicatorBg;
        private int stepNumber;
        
        enum Status {
            PENDING, ACTIVE, COMPLETED
        }
        
        public StepIndicator(Context context) {
            super(context);
            init(context);
        }
        
        private void init(Context context) {
            LayoutInflater.from(context).inflate(R.layout.view_step_indicator, this, true);
            tvEmoji = findViewById(R.id.tv_step_emoji);
            tvLabel = findViewById(R.id.tv_step_label);
            indicatorBg = findViewById(R.id.indicator_bg);
        }
        
        public void setLabel(String label) {
            tvLabel.setText(label);
        }
        
        public void setEmoji(String emoji) {
            tvEmoji.setText(emoji);
        }
        
        public void setStepNumber(int number) {
            this.stepNumber = number;
        }
        
        public void setStatus(Status status) {
            switch (status) {
                case PENDING:
                    indicatorBg.setBackgroundResource(R.drawable.bg_step_pending);
                    tvEmoji.setAlpha(0.5f);
                    break;
                case ACTIVE:
                    indicatorBg.setBackgroundResource(R.drawable.bg_step_active);
                    tvEmoji.setAlpha(1f);
                    break;
                case COMPLETED:
                    indicatorBg.setBackgroundResource(R.drawable.bg_step_completed);
                    tvEmoji.setAlpha(1f);
                    tvEmoji.setText("✓");
                    break;
            }
        }
    }
}

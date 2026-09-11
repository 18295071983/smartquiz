package com.oilquiz.app.ai.chat.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.ScaleAnimation;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * 录音状态指示条（全局可复用 View 组件，新建通用设计）。
 *
 * 覆盖对话页语音输入中的通用录音形态：红点脉冲 + 实时时长(mm:ss) +
 * 停止/取消操作。时长计时由组件内 Handler 驱动，宿主只需 start/stop。
 *
 * <pre>
 * RecordingIndicatorView r = new RecordingIndicatorView(context);
 * r.setOnStop(() -> recorder.stopRecording());
 * r.setOnCancel(() -> recorder.cancelRecording());
 * r.start();   // 开始计时与脉冲
 * r.stop();    // 结束（宿主可随后隐藏）
 * </pre>
 */
public class RecordingIndicatorView extends LinearLayout {

    public interface OnStop { void onStop(); }
    public interface OnCancel { void onCancel(); }

    private final TextView timeText;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            elapsedMs += 500;
            timeText.setText(formatTime(elapsedMs));
            handler.postDelayed(this, 500);
        }
    };
    private long elapsedMs;
    private boolean recording;
    private OnStop onStop;
    private OnCancel onCancel;

    public RecordingIndicatorView(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFF5F5);
        bg.setCornerRadius(dp(16));
        setBackground(bg);
        setPadding(dp(12), dp(6), dp(10), dp(6));

        View pulse = new View(context);
        pulse.setBackground(rounded(0xFFEF4444, dp(5)));
        addView(pulse, new LinearLayout.LayoutParams(dp(10), dp(10)));
        pulse.startAnimation(pulseAnim());

        TextView label = new TextView(context);
        label.setText("录音中");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTextColor(0xFFEF4444);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        labelLp.setMargins(dp(8), 0, 0, 0);
        addView(label, labelLp);

        timeText = new TextView(context);
        timeText.setText("00:00");
        timeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        timeText.setTextColor(0xFF9CA3AF);
        LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        timeLp.setMargins(dp(6), 0, 0, 0);
        addView(timeText, timeLp);

        addSpacer();

        TextView stop = actionText("■ 停止", 0xFFEF4444);
        stop.setOnClickListener(v -> { if (onStop != null) onStop.onStop(); });
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        stopLp.setMargins(dp(14), 0, 0, 0);
        addView(stop, stopLp);

        TextView cancel = actionText("✕ 取消", 0xFF9CA3AF);
        cancel.setOnClickListener(v -> { if (onCancel != null) onCancel.onCancel(); });
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        cancelLp.setMargins(dp(10), 0, 0, 0);
        addView(cancel, cancelLp);
    }

    private void addSpacer() {
        LinearLayout spacer = new LinearLayout(getContext());
        addView(spacer, new LinearLayout.LayoutParams(0, 1, 1.0f));
    }

    private TextView actionText(String label, int color) {
        TextView tv = new TextView(getContext());
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(color);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFFFFFFFF);
        g.setCornerRadius(dp(12));
        tv.setBackground(g);
        tv.setPadding(dp(12), dp(5), dp(12), dp(5));
        return tv;
    }

    public void setOnStop(OnStop l) { this.onStop = l; }
    public void setOnCancel(OnCancel l) { this.onCancel = l; }

    /** 开始计时（幂等） */
    public void start() {
        if (recording) return;
        recording = true;
        elapsedMs = 0;
        timeText.setText("00:00");
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, 500);
        setVisibility(VISIBLE);
    }

    /** 停止计时 */
    public void stop() {
        recording = false;
        handler.removeCallbacks(ticker);
    }

    /** 停止并隐藏 */
    public void stopAndHide() {
        stop();
        setVisibility(GONE);
    }

    private Animation pulseAnim() {
        AnimationSet set = new AnimationSet(true);
        ScaleAnimation sa = new ScaleAnimation(1f, 1.8f, 1f, 1.8f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f, ScaleAnimation.RELATIVE_TO_SELF, 0.5f);
        sa.setDuration(600);
        AlphaAnimation aa = new AlphaAnimation(0.9f, 0.2f);
        aa.setDuration(600);
        set.addAnimation(sa);
        set.addAnimation(aa);
        set.setRepeatCount(Animation.INFINITE);
        return set;
    }

    private static String formatTime(long ms) {
        long totalSec = ms / 1000;
        return String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60);
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }
}

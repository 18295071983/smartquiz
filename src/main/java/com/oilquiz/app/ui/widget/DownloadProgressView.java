package com.oilquiz.app.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.oilquiz.app.R;

/**
 * 下载 / 初始化进度控件
 *
 * 组合展示：渐变进度环 + 主模型进度条 + 视觉模块（mmproj）独立进度 + 状态文案。
 * 下载阶段与模型初始化阶段共用本控件展示进度。
 */
public class DownloadProgressView extends LinearLayout {

    private GradientRingProgress ringProgress;
    private ProgressBar modelProgress;
    private TextView tvModelStatus;
    private LinearLayout mmprojContainer;
    private ProgressBar mmprojProgress;
    private TextView tvMmprojStatus;
    private TextView tvStatus;

    public DownloadProgressView(Context context) {
        this(context, null);
    }

    public DownloadProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        LayoutInflater.from(context).inflate(R.layout.view_download_progress, this, true);
        setOrientation(VERTICAL);
        ringProgress = findViewById(R.id.ring_progress);
        modelProgress = findViewById(R.id.model_progress);
        tvModelStatus = findViewById(R.id.tv_model_status);
        mmprojContainer = findViewById(R.id.mmproj_container);
        mmprojProgress = findViewById(R.id.mmproj_progress);
        tvMmprojStatus = findViewById(R.id.tv_mmproj_status);
        tvStatus = findViewById(R.id.tv_status);
    }

    /** 更新主进度（0-100；percent<0 表示不确定/速度心跳，保持当前进度不回调） */
    public void updateProgress(int percent, String message) {
        if (tvStatus != null && message != null) tvStatus.setText(message);
        if (percent < 0) return; // 不确定进度：只更新文案，不动进度环/进度条/百分比
        int p = Math.max(0, Math.min(100, percent));
        if (ringProgress != null) ringProgress.setProgress(p);
        if (modelProgress != null) modelProgress.setProgress(p);
        if (tvModelStatus != null) tvModelStatus.setText(p + "%");
    }

    /** 更新视觉模块（mmproj）独立进度 */
    public void updateSecondaryProgress(String label, int percent, long downloadedMB, long totalMB) {
        if (mmprojContainer != null && mmprojContainer.getVisibility() != View.VISIBLE) {
            mmprojContainer.setVisibility(View.VISIBLE);
        }
        if (percent >= 0 && mmprojProgress != null) {
            mmprojProgress.setProgress(Math.max(0, Math.min(100, percent)));
        }
        if (tvMmprojStatus != null && totalMB > 0) {
            tvMmprojStatus.setText(downloadedMB + " / " + totalMB + " MB");
        }
    }

    /** 仅更新状态文案 */
    public void setStatus(String message) {
        if (tvStatus != null && message != null) tvStatus.setText(message);
    }

    /** 重置进度显示 */
    public void reset() {
        if (ringProgress != null) ringProgress.setProgress(0);
        if (modelProgress != null) modelProgress.setProgress(0);
        if (tvModelStatus != null) tvModelStatus.setText("0%");
        if (mmprojProgress != null) mmprojProgress.setProgress(0);
        if (mmprojContainer != null) mmprojContainer.setVisibility(View.GONE);
        if (tvStatus != null) tvStatus.setText("");
    }

    /** 进度环当前值（percent<0 保持用） */
    public float getCurrentProgress() {
        return ringProgress != null ? ringProgress.getCurrentProgress() : 0f;
    }
}

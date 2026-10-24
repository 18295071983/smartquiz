package com.oilquiz.app.ui.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.oilquiz.app.ai.chat.media.AudioPlaybackManager;

/**
 * 视频全屏页（2026-10-25，播放器 P0）。
 *
 * <p>不新建播放器、不持有 Surface 生命周期：直接拿 {@link AudioPlaybackManager#playerOrNull()}
 * 那个进程级 ExoPlayer，退出时只 {@code setPlayer(null)}，播放/声音继续在
 * {@code AudioPlaybackService} 里。这样聊天卡片和全屏页看的是同一个进度，不会出现
 * "进全屏从头播/退出后声音断"。</p>
 *
 * <p>横屏、沉浸式、保持常亮；左上角切"适应/裁剪"，右上角关闭。播放器控制条仍用
 * Media3 默认的 {@link PlayerView}，字幕/音轨/PiP 在 P1 再接。</p>
 */
public class VideoFullscreenActivity extends Activity {

    private PlayerView playerView;

    /** 从卡片打开；非 Activity Context 补 NEW_TASK，避免 crash。 */
    public static void start(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, VideoFullscreenActivity.class);
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        ExoPlayer player = AudioPlaybackManager.get(this).playerOrNull();
        if (player == null) {
            Toast.makeText(this, "播放器尚未就绪，请先在卡片里播放", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        playerView = new PlayerView(this);
        playerView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        playerView.setUseController(true);
        playerView.setControllerShowTimeoutMs(3000);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setPlayer(player);
        root.addView(playerView);

        final boolean[] zoom = {false};
        TextView ratio = chromeButton(zoom[0] ? "裁剪" : "适应");
        FrameLayout.LayoutParams ratioLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        ratioLp.topMargin = dp(16);
        ratioLp.leftMargin = dp(16);
        ratio.setLayoutParams(ratioLp);
        ratio.setOnClickListener(v -> {
            zoom[0] = !zoom[0];
            playerView.setResizeMode(zoom[0]
                    ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    : AspectRatioFrameLayout.RESIZE_MODE_FIT);
            ((TextView) v).setText(zoom[0] ? "裁剪" : "适应");
        });
        root.addView(ratio);

        TextView close = chromeButton("关闭");
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        closeLp.topMargin = dp(16);
        closeLp.rightMargin = dp(16);
        close.setLayoutParams(closeLp);
        close.setOnClickListener(v -> finish());
        root.addView(close);

        setContentView(root);
        hideSystemBars();
    }

    private void hideSystemBars() {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private TextView chromeButton(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x99000000);
        bg.setCornerRadius(dp(16));
        tv.setBackground(bg);
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    @Override
    protected void onDestroy() {
        // 只解绑 Surface，不 release：播放器属于前台服务，退出全屏后声音继续。
        if (playerView != null) {
            playerView.setPlayer(null);
            playerView = null;
        }
        // 通知卡片按当前曲目重新绑 Surface；否则全屏退出后卡片可能保持黑屏/空 Surface。
        AudioPlaybackManager.get(this).notifyStateNow();
        super.onDestroy();
    }
}

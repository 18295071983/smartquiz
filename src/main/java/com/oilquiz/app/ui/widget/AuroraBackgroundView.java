package com.oilquiz.app.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * AI 服务初始化界面的极光背景
 *
 * 视觉元素：
 * 1. 深蓝紫纵向渐变底
 * 2. 两个缓慢流动的径向光晕（紫 / 青）
 * 3. 数十颗缓慢漂浮、随机闪烁的微光粒子
 * 全部自绘 + ValueAnimator 驱动，无外部依赖。
 */
public class AuroraBackgroundView extends View {

    private static final int[] BG_COLORS = {
            Color.rgb(13, 10, 40),   // 深蓝紫
            Color.rgb(24, 16, 58),
            Color.rgb(44, 20, 74)
    };
    private static final int BLOBS = 3;
    private static final int PARTICLES = 46;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Blob> blobs = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private final Random random = new Random();
    private ValueAnimator animator;
    private long lastFrame = 0;
    private float scale = 1f;

    public AuroraBackgroundView(Context context) {
        super(context);
        init();
    }

    public AuroraBackgroundView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        blobPaint.setMaskFilter(null);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        scale = Math.max(w, h) / 720f;
        if (blobs.isEmpty()) {
            for (int i = 0; i < BLOBS; i++) {
                Blob b = new Blob();
                b.radius = (0.30f + random.nextFloat() * 0.25f) * Math.max(w, h);
                b.x = w * (0.15f + random.nextFloat() * 0.7f);
                b.y = h * (0.10f + random.nextFloat() * 0.8f);
                b.vx = (random.nextFloat() * 18f + 6f) * (random.nextBoolean() ? 1 : -1);
                b.vy = (random.nextFloat() * 14f + 4f) * (random.nextBoolean() ? 1 : -1);
                b.alpha = 0.10f + random.nextFloat() * 0.10f;
                b.color = (i % 2 == 0) ? 0xFF8B5CF6 : 0xFF06B6D4;
                blobs.add(b);
            }
        }
        if (particles.isEmpty()) {
            for (int i = 0; i < PARTICLES; i++) {
                Particle p = new Particle();
                p.x = random.nextFloat() * w;
                p.y = random.nextFloat() * h;
                p.radius = 1.0f + random.nextFloat() * 2.2f;
                p.speed = 0.2f + random.nextFloat() * 0.8f;
                p.alpha = 0.15f + random.nextFloat() * 0.5f;
                p.phase = random.nextFloat() * (float) Math.PI * 2f;
                p.twinkleSpeed = 0.6f + random.nextFloat() * 1.4f;
                p.drift = 6f + random.nextFloat() * 14f;
                particles.add(p);
            }
        }
        startAnimator();
    }

    private void startAnimator() {
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(10000);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.addUpdateListener(a -> {
            long now = android.os.SystemClock.uptimeMillis();
            long dt = (lastFrame == 0) ? 16 : (now - lastFrame);
            lastFrame = now;
            update(dt);
            invalidate();
        });
        animator.start();
    }

    private void update(long dt) {
        float s = dt / 1000f;
        for (Blob b : blobs) {
            b.x += b.vx * s;
            b.y += b.vy * s;
            if (b.x < -b.radius) b.x = getWidth() + b.radius;
            if (b.x > getWidth() + b.radius) b.x = -b.radius;
            if (b.y < -b.radius) b.y = getHeight() + b.radius;
            if (b.y > getHeight() + b.radius) b.y = -b.radius;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        // 1. 纵向渐变底
        LinearGradient lg = new LinearGradient(0, 0, 0, h, BG_COLORS[0], BG_COLORS[2], Shader.TileMode.CLAMP);
        bgPaint.setShader(lg);
        canvas.drawRect(0, 0, w, h, bgPaint);
        bgPaint.setShader(null);

        // 2. 光晕
        float t = System.currentTimeMillis() / 1000f;
        for (Blob b : blobs) {
            float pulse = 0.85f + 0.15f * (float) Math.sin(t * 0.6f + b.x * 0.01f);
            RadialGradient rg = new RadialGradient(b.x, b.y, b.radius * pulse,
                    Color.argb((int) (255 * b.alpha), Color.red(b.color), Color.green(b.color), Color.blue(b.color)),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP);
            blobPaint.setShader(rg);
            canvas.drawCircle(b.x, b.y, b.radius * pulse, blobPaint);
            blobPaint.setShader(null);
        }

        // 3. 粒子（向上漂浮 + 呼吸闪烁）
        for (Particle p : particles) {
            p.y -= p.speed * scale * 2f;
            p.x += (float) Math.sin(t * 0.5f + p.phase) * 0.15f * p.drift;
            if (p.y < -10) {
                p.y = h + 10;
                p.x = random.nextFloat() * w;
            }
            if (p.x < 0) p.x = w;
            if (p.x > w) p.x = 0;
            float tw = 0.55f + 0.45f * (float) Math.sin(t * p.twinkleSpeed + p.phase);
            int a = (int) (p.alpha * tw * 255);
            particlePaint.setColor(Color.argb(a, 220, 220, 255));
            canvas.drawCircle(p.x, p.y, p.radius * scale, particlePaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }

    private static class Blob {
        float x, y, radius, vx, vy, alpha;
        int color;
    }

    private static class Particle {
        float x, y, radius, speed, alpha, phase, twinkleSpeed, drift;
    }
}

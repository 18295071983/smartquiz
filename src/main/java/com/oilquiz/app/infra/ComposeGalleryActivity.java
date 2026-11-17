package com.oilquiz.app.infra;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieDrawable;
import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;
import com.oilquiz.app.theme.Tokens;

/**
 * 组件画廊（Compose 试点 + Lottie 试点），2026-11-16。
 *
 * <p><b>为什么有这个页面</b>：审计发现项目里 Compose 全家（BOM/material3/icons）
 * 和 Lottie 都是"声明了、一行没用"的悬空依赖。与其删掉，不如先用起来验证工具链：
 * <ul>
 *   <li>上半屏用 <b>Compose（Material3）</b>画一遍动作类组件：按钮四态、开关、单选、复选、
 *       滑条、Chip、进度条 —— 确认 Compose 能在本项目里编译、渲染、跟随主题；</li>
 *   <li>下半屏用 <b>Lottie</b>播放加载动画（{@code assets/lottie/loading_dots.json}），
 *       确认 Lottie 可用、可作为"思考中/加载中"的标准动效。</li>
 * </ul>
 * 它同时是"动作类组件"的对照样板：同一批控件在这里分别用 Compose 和 View 两种方式画，
 * 便于后续决定新页面走哪条路。</p>
 */
public class ComposeGalleryActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ThemeColors.get(this, R.color.background));
        root.setPadding(Tokens.space(this, Tokens.SPACE_XL), Tokens.space(this, Tokens.SPACE_XL),
                Tokens.space(this, Tokens.SPACE_XL), Tokens.space(this, Tokens.SPACE_XL));

        TextView title = new TextView(this);
        title.setText("组件画廊 · Compose + Lottie 试点");
        Tokens.applyText(title, Tokens.TEXT_TITLE, Tokens.Weight.SEMIBOLD,
                ThemeColors.get(this, R.color.text_primary));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("上：Compose（Material3）动作类组件　下：Lottie 加载动效");
        Tokens.applyText(sub, Tokens.TEXT_FOOTNOTE, Tokens.Weight.REGULAR,
                ThemeColors.get(this, R.color.text_secondary));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.setMargins(0, Tokens.space(this, Tokens.SPACE_SM), 0,
                Tokens.space(this, Tokens.SPACE_XL));
        sub.setLayoutParams(subLp);
        root.addView(sub);

        // ---- Compose 区 ----
        View composeHost = new ComposeHostView(this);
        LinearLayout.LayoutParams composeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        composeLp.setMargins(0, 0, 0, Tokens.space(this, Tokens.SPACE_XL));
        composeHost.setLayoutParams(composeLp);
        root.addView(composeHost);

        // ---- Lottie 区 ----
        LinearLayout lottieCard = new LinearLayout(this);
        lottieCard.setOrientation(LinearLayout.HORIZONTAL);
        lottieCard.setGravity(Gravity.CENTER_VERTICAL);
        lottieCard.setPadding(Tokens.space(this, Tokens.SPACE_XL), Tokens.space(this, Tokens.SPACE_XL),
                Tokens.space(this, Tokens.SPACE_XL), Tokens.space(this, Tokens.SPACE_XL));

        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        cardBg.setCornerRadius(Tokens.radius(this, Tokens.RADIUS_LG));
        cardBg.setColor(ThemeColors.get(this, R.color.card_background));
        cardBg.setStroke(1, ThemeColors.get(this, R.color.border));
        lottieCard.setBackground(cardBg);

        LottieAnimationView lottie = new LottieAnimationView(this);
        lottie.setAnimation("lottie/loading_dots.json");
        lottie.setRepeatCount(LottieDrawable.INFINITE);
        lottie.setRepeatMode(LottieDrawable.RESTART);
        lottie.setSpeed(1.2f);
        lottie.playAnimation();
        LinearLayout.LayoutParams lottieLp = new LinearLayout.LayoutParams(
                Tokens.space(this, Tokens.SPACE_3XL) * 2,
                Tokens.space(this, Tokens.SPACE_3XL) * 2);
        lottieLp.setMargins(0, 0, Tokens.space(this, Tokens.SPACE_LG), 0);
        lottie.setLayoutParams(lottieLp);
        lottieCard.addView(lottie);

        TextView lottieLabel = new TextView(this);
        lottieLabel.setText("Lottie 加载动效\n（AI 思考中 / 列表加载中可直接复用）");
        Tokens.applyText(lottieLabel, Tokens.TEXT_BODY, Tokens.Weight.REGULAR,
                ThemeColors.get(this, R.color.text_primary));
        lottieCard.addView(lottieLabel);

        root.addView(lottieCard);

        ScrollView scroll = new ScrollView(this);
        // 整页铺主题底色：否则内容不满屏时露出全局壁纸，看起来像"页面没画完"
        scroll.setBackgroundColor(ThemeColors.get(this, R.color.background));
        scroll.addView(root);
        // 显式给 MATCH_PARENT：setContentView 不传 params 时 ScrollView 会按 wrap_content，
        // 内容不满屏就露壁纸（真机踩到的就是这条）。
        android.widget.FrameLayout holder = new android.widget.FrameLayout(this);
        holder.setBackgroundColor(ThemeColors.get(this, R.color.background));
        holder.addView(scroll, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(holder);
    }
}

package com.oilquiz.app.infra;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieDrawable;
import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;
import com.oilquiz.app.theme.Tokens;
import com.oilquiz.app.ui.UiFx;

import java.util.ArrayList;
import java.util.List;

import coil.Coil;
import coil.ImageLoader;
import coil.request.ImageRequest;
import de.hdodenhof.circleimageview.CircleImageView;

/**
 * UI 工具包画廊（2026-11-16）：把"以前声明但没用"的 UI 能力集中跑一遍，
 * 让它们成为可复用的基础设施，而不是悬空依赖。
 *
 * <p>本页覆盖：</p>
 * <ul>
 *   <li>{@link UiFx}：防抖点击 / 长按 / 选中态 / 按压缩放；</li>
 *   <li><b>SwipeRefreshLayout</b>（此前 0 引用）：下拉刷新，统一加载态；</li>
 *   <li><b>CircleImageView</b>（此前 0 引用）：圆形头像；</li>
 *   <li><b>Coil</b>（此前 0 引用，图片实际都用 Glide）：异步图片加载；</li>
 *   <li><b>Lottie</b>：加载/思考动效；</li>
 *   <li><b>res/anim 全部 10 个动画资源</b>：每个一行，点一下就播放，作为动效清单。</li>
 * </ul>
 */
public class UiKitGalleryActivity extends AppCompatActivity {

    private final List<TextView> selectionChips = new ArrayList<>();
    private int refreshCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(ThemeColors.get(this, R.color.background));
        scroll.setFillViewport(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Tokens.space(this, Tokens.SPACE_XL), Tokens.space(this, Tokens.SPACE_XL),
                Tokens.space(this, Tokens.SPACE_XL), Tokens.space(this, Tokens.SPACE_3XL));
        content.setBackgroundColor(ThemeColors.get(this, R.color.background));

        content.addView(title("UI 工具包 · 已接入的能力"));
        content.addView(subtitle("点一下每个控件都能看到反馈；下拉可刷新"));

        // ---------- 1. UiFx：点击 / 长按 / 选中 / 按压 ----------
        content.addView(section("1. 交互（UiFx）"));
        LinearLayout iaRow = new LinearLayout(this);
        iaRow.setOrientation(LinearLayout.HORIZONTAL);

        final TextView clickBtn = pill("防抖点击 0");
        final int[] clicks = {0};
        UiFx.click(clickBtn, () -> clickBtn.setText("防抖点击 " + (++clicks[0])));
        iaRow.addView(clickBtn);

        final TextView longBtn = pill("长按我");
        UiFx.longPress(longBtn, () -> Toast.makeText(this, "长按触发", Toast.LENGTH_SHORT).show());
        UiFx.pressFeedback(longBtn);
        iaRow.addView(longBtn);
        content.addView(iaRow);

        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 3; i++) {
            final TextView chip = pill("选中项 " + (i + 1));
            final boolean[] on = {false};
            UiFx.toggleSelection(chip, false);
            UiFx.click(chip, () -> {
                on[0] = !on[0];
                UiFx.toggleSelection(chip, on[0]);
            });
            selectionChips.add(chip);
            chipRow.addView(chip);
        }
        content.addView(chipRow);

        // ---------- 2. 下拉刷新 ----------
        content.addView(section("2. SwipeRefreshLayout（此前 0 引用）"));
        final TextView refreshLabel = subtitle("下拉刷新试试（当前 0 次）");
        content.addView(refreshLabel);
        final SwipeRefreshLayout swipe = new SwipeRefreshLayout(this);
        LinearLayout swipeBody = new LinearLayout(this);
        swipeBody.setOrientation(LinearLayout.VERTICAL);
        for (int i = 1; i <= 3; i++) {
            TextView row = new TextView(this);
            row.setText("列表项 " + i + "（下拉刷新会重新计数）");
            Tokens.applyText(row, Tokens.TEXT_BODY, Tokens.Weight.REGULAR,
                    ThemeColors.get(this, R.color.text_primary));
            int p = Tokens.space(this, Tokens.SPACE_MD);
            row.setPadding(p, p, p, p);
            swipeBody.addView(row);
        }
        swipe.addView(swipeBody);
        swipe.setOnRefreshListener(() -> {
            refreshCount++;
            refreshLabel.setText("下拉刷新试试（当前 " + refreshCount + " 次）");
            swipe.postDelayed(() -> swipe.setRefreshing(false), 800);
        });
        content.addView(swipe);

        // ---------- 3. 图片：CircleImageView + Coil ----------
        content.addView(section("3. 图片（CircleImageView / Coil）"));
        LinearLayout imgRow = new LinearLayout(this);
        imgRow.setOrientation(LinearLayout.HORIZONTAL);
        imgRow.setGravity(Gravity.CENTER_VERTICAL);

        CircleImageView avatar = new CircleImageView(this);
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(dp(56), dp(56));
        avLp.setMargins(0, 0, Tokens.space(this, Tokens.SPACE_LG), 0);
        avatar.setLayoutParams(avLp);
        avatar.setImageResource(R.mipmap.ic_launcher);
        imgRow.addView(avatar);

        ImageView coilImage = new ImageView(this);
        LinearLayout.LayoutParams ciLp = new LinearLayout.LayoutParams(dp(56), dp(56));
        ciLp.setMargins(0, 0, Tokens.space(this, Tokens.SPACE_LG), 0);
        coilImage.setLayoutParams(ciLp);
        ImageLoader loader = Coil.imageLoader(this);
        loader.enqueue(new ImageRequest.Builder(this)
                .data(R.mipmap.ic_launcher)
                .target(coilImage)
                .build());
        imgRow.addView(coilImage);

        TextView imgNote = subtitle("左：CircleImageView　右：Coil 加载\n（Coil 此前 0 引用，图片都走 Glide）");
        imgRow.addView(imgNote);
        content.addView(imgRow);

        // ---------- 4. Lottie ----------
        content.addView(section("4. Lottie 加载动效"));
        LinearLayout lottieRow = new LinearLayout(this);
        lottieRow.setOrientation(LinearLayout.HORIZONTAL);
        lottieRow.setGravity(Gravity.CENTER_VERTICAL);
        LottieAnimationView lottie = new LottieAnimationView(this);
        lottie.setAnimation("lottie/loading_dots.json");
        lottie.setRepeatCount(LottieDrawable.INFINITE);
        lottie.playAnimation();
        LinearLayout.LayoutParams loLp = new LinearLayout.LayoutParams(dp(72), dp(72));
        loLp.setMargins(0, 0, Tokens.space(this, Tokens.SPACE_LG), 0);
        lottie.setLayoutParams(loLp);
        lottieRow.addView(lottie);
        lottieRow.addView(subtitle("AI 思考中 / 列表加载的标准动效\n与下方 res/anim 的 thinking_dot_bounce 二选一"));
        content.addView(lottieRow);

        // ---------- 5. 10 个动画资源逐个试用 ----------
        content.addView(section("5. res/anim 全部动画资源（点一下播放）"));
        final TextView stage = new TextView(this);
        stage.setText("动效预览区");
        stage.setGravity(Gravity.CENTER);
        Tokens.applyText(stage, Tokens.TEXT_BODY_LG, Tokens.Weight.MEDIUM,
                ThemeColors.get(this, R.color.text_primary));
        GradientDrawable stageBg = new GradientDrawable();
        stageBg.setCornerRadius(Tokens.radius(this, Tokens.RADIUS_MD));
        stageBg.setColor(ThemeColors.get(this, R.color.card_background));
        stageBg.setStroke(1, ThemeColors.get(this, R.color.border));
        stage.setBackground(stageBg);
        LinearLayout.LayoutParams stageLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(72));
        stageLp.setMargins(0, 0, 0, Tokens.space(this, Tokens.SPACE_MD));
        stage.setLayoutParams(stageLp);
        content.addView(stage);

        addAnimRow(content, "message_enter_bottom（消息入场）", () -> UiFx.messageIn(stage));
        addAnimRow(content, "message_enter_right（我发出的消息）", () -> UiFx.messageInFromRight(stage));
        addAnimRow(content, "message_streaming_typing（流式打字）", () -> UiFx.streamingTyping(stage));
        addAnimRow(content, "welcome_card_enter（欢迎卡入场）", () -> UiFx.welcomeIn(stage));
        addAnimRow(content, "thinking_dot_bounce（思考中）", () -> UiFx.thinking(stage));
        addAnimRow(content, "cursor_blink（光标闪烁）", () -> UiFx.cursorBlink(stage));
        addAnimRow(content, "weather_icon_anim（天气图标）", () -> UiFx.weatherIcon(stage));
        addAnimRow(content, "slide_in_left（左滑入）", () -> UiFx.slideInLeft(stage));
        addAnimRow(content, "slide_in_right（右滑入）", () -> UiFx.slideInRight(stage));
        addAnimRow(content, "button_state_list_animator（按钮状态）", () -> UiFx.buttonState(stage));

        scroll.addView(content);

        FrameLayout holder = new FrameLayout(this);
        holder.setBackgroundColor(ThemeColors.get(this, R.color.background));
        holder.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(holder);
    }

    // ---------------- 构建辅助 ----------------

    private TextView title(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        Tokens.applyText(tv, Tokens.TEXT_TITLE, Tokens.Weight.SEMIBOLD,
                ThemeColors.get(this, R.color.text_primary));
        return tv;
    }

    private TextView subtitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        Tokens.applyText(tv, Tokens.TEXT_FOOTNOTE, Tokens.Weight.REGULAR,
                ThemeColors.get(this, R.color.text_secondary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Tokens.space(this, Tokens.SPACE_XS), 0, Tokens.space(this, Tokens.SPACE_SM));
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView section(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        Tokens.applyText(tv, Tokens.TEXT_SUBTITLE, Tokens.Weight.SEMIBOLD,
                ThemeColors.get(this, R.color.text_primary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Tokens.space(this, Tokens.SPACE_XL), 0, Tokens.space(this, Tokens.SPACE_SM));
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView pill(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        Tokens.applyText(tv, Tokens.TEXT_LABEL, Tokens.Weight.MEDIUM,
                ThemeColors.get(this, R.color.text_primary));
        int p = Tokens.space(this, Tokens.SPACE_MD);
        tv.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Tokens.radius(this, Tokens.RADIUS_PILL));
        bg.setColor(ThemeColors.get(this, R.color.card_background));
        bg.setStroke(1, ThemeColors.get(this, R.color.border));
        tv.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, Tokens.space(this, Tokens.SPACE_SM),
                Tokens.space(this, Tokens.SPACE_SM));
        tv.setLayoutParams(lp);
        return tv;
    }

    private void addAnimRow(LinearLayout parent, String label, final Runnable action) {
        final TextView row = pill(label);
        UiFx.click(row, action);
        parent.addView(row);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

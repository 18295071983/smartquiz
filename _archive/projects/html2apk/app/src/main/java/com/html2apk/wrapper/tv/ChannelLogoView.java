package com.html2apk.wrapper.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 频道台标（v9.14）。
 *
 * <p>优先显示 M3U 的 {@code tvg-logo}（远程 URL / 包内相对路径）；
 * 没有台标或加载失败时使用内置自适应占位：分组渐变底色 + CCTV 数字/频道首字
 * + 分组小图标（央视/卫视/地方/新闻/体育/电影/少儿）。不依赖任何外部图片库。</p>
 */
public final class ChannelLogoView extends FrameLayout {

    private static final Pattern CCTV = Pattern.compile("CCTV\\s*[-_]?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private final int sizeDp;
    private final TextView label;
    private final ImageView badge;
    private final ImageView logo;
    private String currentUrl = "";

    public ChannelLogoView(Context context) {
        this(context, 46);
    }

    public ChannelLogoView(Context context, int sizeDp) {
        super(context);
        this.sizeDp = sizeDp;
        setClipToOutline(true);

        label = new TextView(context);
        label.setTextColor(Color.WHITE);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setGravity(Gravity.CENTER);
        addView(label, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        logo = new ImageView(context);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        logo.setVisibility(GONE);
        addView(logo, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        badge = new ImageView(context);
        badge.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        badge.setColorFilter(Color.WHITE);
        int badgeSize = dp(14);
        LayoutParams blp = new LayoutParams(badgeSize, badgeSize);
        blp.gravity = Gravity.TOP | Gravity.END;
        blp.setMargins(0, dp(3), dp(3), 0);
        addView(badge, blp);
    }

    public void bind(LiveChannel channel) {
        if (channel == null) return;
        currentUrl = channel.logo == null ? "" : channel.logo.trim();
        setBackground(background(colorFor(channel)));
        label.setText(labelFor(channel));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                isCctv(channel) ? Math.max(13, sizeDp / 3.2f) : Math.max(13, sizeDp / 3.6f));
        badge.setImageResource(iconFor(channel));
        badge.setVisibility(VISIBLE);
        label.setVisibility(VISIBLE);
        logo.setVisibility(GONE);
        logo.setImageDrawable(null);

        if (!currentUrl.isEmpty()) {
            final String wanted = currentUrl;
            LogoLoader.get(getContext()).load(wanted, getSizePx(), (url, bitmap) -> {
                if (!wanted.equals(currentUrl)) return;
                if (bitmap != null) {
                    logo.setImageBitmap(bitmap);
                    logo.setVisibility(VISIBLE);
                    label.setVisibility(GONE);
                    badge.setVisibility(GONE);
                }
            });
        }
    }

    private int getSizePx() {
        int w = getWidth();
        return w > 0 ? w : dp(sizeDp);
    }

    private GradientDrawable background(int color) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{lighten(color, 0.18f), color});
        d.setCornerRadius(dp(Math.max(8, sizeDp / 4)));
        return d;
    }

    private static int colorFor(LiveChannel c) {
        String name = c.name == null ? "" : c.name;
        String group = c.group == null ? "" : c.group;
        if (name.toUpperCase(Locale.US).contains("CCTV")) return Color.parseColor("#B3261E");
        if (group.contains("卫视")) return Color.parseColor("#1E5F74");
        if (group.contains("地方")) return Color.parseColor("#2F5D50");
        int[] colors = {
                Color.parseColor("#4A4A6B"), Color.parseColor("#35506B"),
                Color.parseColor("#5A3B6E"), Color.parseColor("#6B3F2A")
        };
        return colors[Math.abs((group + name).hashCode()) % colors.length];
    }

    private static boolean isCctv(LiveChannel c) {
        return c.name != null && CCTV.matcher(c.name).find();
    }

    private static String labelFor(LiveChannel c) {
        Matcher m = CCTV.matcher(c.name == null ? "" : c.name);
        if (m.find()) return m.group(1);
        String name = c.name == null ? "" : c.name.trim();
        if (name.toUpperCase(Locale.US).contains("CCTV")) return "C";
        return name.isEmpty() ? "TV" : new String(Character.toChars(name.codePointAt(0)));
    }

    private int iconFor(LiveChannel c) {
        String name = c.name == null ? "" : c.name;
        String group = c.group == null ? "" : c.group;
        String hay = name + " " + group;
        if (name.toUpperCase(Locale.US).contains("CCTV")) return resId("ic_tv_default");
        if (group.contains("卫视")) return resId("ic_tv_satellite");
        if (group.contains("地方")) return resId("ic_tv_local");
        if (hay.contains("新闻")) return resId("ic_tv_news");
        if (hay.contains("体育") || hay.contains("赛事")) return resId("ic_tv_sport");
        if (hay.contains("电影") || hay.contains("影视")) return resId("ic_tv_movie");
        if (hay.contains("少儿") || hay.contains("卡通")) return resId("ic_tv_kids");
        return resId("ic_tv_default");
    }

    private int resId(String name) {
        int id = getResources().getIdentifier(name, "drawable", getContext().getPackageName());
        return id > 0 ? id : android.R.drawable.ic_media_play;
    }

    private static int lighten(int color, float amount) {
        int r = (int) (Color.red(color) + (255 - Color.red(color)) * amount);
        int g = (int) (Color.green(color) + (255 - Color.green(color)) * amount);
        int b = (int) (Color.blue(color) + (255 - Color.blue(color)) * amount);
        return Color.rgb(Math.min(255, r), Math.min(255, g), Math.min(255, b));
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}

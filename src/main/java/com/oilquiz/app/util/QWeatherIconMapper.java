package com.oilquiz.app.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import com.oilquiz.app.R;

import java.util.Calendar;

public class QWeatherIconMapper {

    public static String getIconName(String weatherCode) {
        if (weatherCode == null) return "unknown";
        return "qweather_" + weatherCode;
    }

    public static String getFontIcon(Context context, String weatherCode) {
        return QWeatherIconFont.getIcon(weatherCode);
    }

    public static Typeface getIconTypeface(Context context) {
        return QWeatherIconFont.getTypeface(context);
    }

    public static String getDayNightIcon(String weatherCode, boolean isNight) {
        if (weatherCode == null || weatherCode.isEmpty()) return weatherCode;

        try {
            int code = Integer.parseInt(weatherCode);
            if (isNight) {
                return convertToNightIcon(code);
            } else {
                return convertToDayIcon(code);
            }
        } catch (NumberFormatException e) {
            return weatherCode;
        }
    }

    public static String getIconByTime(String weatherCode) {
        Calendar calendar = Calendar.getInstance();
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        boolean isNight = hour < 6 || hour >= 18;
        return getDayNightIcon(weatherCode, isNight);
    }

    private static String convertToNightIcon(int code) {
        if (code >= 100 && code <= 104) {
            return String.valueOf(code + 50);
        }
        if (code >= 150 && code <= 154) {
            return String.valueOf(code);
        }
        if (code >= 300 && code <= 303) {
            return String.valueOf(code + 50);
        }
        if (code >= 350 && code <= 351) {
            return String.valueOf(code);
        }
        return String.valueOf(code);
    }

    private static String convertToDayIcon(int code) {
        if (code >= 150 && code <= 154) {
            return String.valueOf(code - 50);
        }
        if (code >= 350 && code <= 351) {
            return String.valueOf(code - 50);
        }
        return String.valueOf(code);
    }

    public static boolean isNightIcon(int code) {
        return (code >= 150 && code <= 154) || (code >= 350 && code <= 351);
    }

    public static boolean shouldHaveNightVariant(int code) {
        return (code >= 100 && code <= 104) || (code >= 300 && code <= 303);
    }

    public static String getEmojiIcon(String weatherCode) {
        if (weatherCode == null || weatherCode.isEmpty()) return "🌤️";
        switch (weatherCode) {
            case "100":
            case "800":
                return "☀️";
            case "150":
                return "🌙";
            case "101":
            case "151":
                return "⛅";
            case "102":
            case "103":
            case "152":
            case "153":
            case "802":
            case "803":
                return "⛅";
            case "104":
            case "804":
                return "☁️";
            case "200":
            case "201":
            case "202":
                return "🌤️";
            case "203":
            case "204":
                return "☁️";
            case "205":
            case "206":
            case "300":
            case "301":
            case "305":
            case "306":
            case "307":
            case "308":
            case "309":
            case "350":
            case "351":
                return "🌧️";
            case "207":
            case "208":
            case "302":
            case "303":
            case "310":
            case "311":
            case "312":
                return "⛈️";
            case "209":
            case "210":
            case "315":
            case "316":
            case "317":
            case "318":
                return "🌨️";
            case "211":
            case "212":
            case "213":
            case "313":
            case "314":
            case "400":
            case "401":
            case "402":
            case "403":
            case "404":
            case "405":
            case "406":
            case "407":
            case "408":
            case "409":
            case "410":
            case "456":
            case "457":
            case "499":
                return "❄️";
            case "399":
                return "🌧️";
            case "304":
                return "🌩️";
            case "500":
            case "501":
            case "502":
            case "503":
            case "504":
            case "507":
            case "508":
            case "509":
            case "510":
            case "511":
            case "512":
            case "513":
            case "514":
            case "515":
                return "🌫️";
            case "602":
            case "611":
            case "612":
            case "613":
                return "💨";
            case "600":
                return "🔥";
            case "601":
                return "🥶";
            case "900":
                return "🥵";
            case "901":
                return "🥶";
            case "999":
                return "❓";
            default:
                return "🌤️";
        }
    }

    /**
     * 天气卡片图标解析（2026-10-29）：把 {@code props.icon} 归一成和风图标代码。
     *
     * <p>卡片此前把 icon 直接 {@code setText}，而模型从 {@code ai_weather} 结果里
     * 看到的是图标代码（{@code 图标: 104}），照抄进卡片就成了"104"数字；自动桥接
     * 一侧则存的是 emoji。两种形态都要落到本地图标包（{@code assets/fonts/qweather-icons.ttf}，
     * 与天气详情页同一套），不联网。</p>
     *
     * @param rawIcon      props.icon 原值（代码 "104"/"104-fill"、emoji、中文或空）
     * @param fallbackText 没有 icon 时的天气文案（"小雨"），用来兜底选图标
     * @return 图标代码；返回 null 表示这不是可翻译的图标（调用端按原文展示或留空）
     */
    public static String resolveCardIconCode(String rawIcon, String fallbackText) {
        String code = normalizeIconCode(rawIcon);
        if (code != null) return code;
        code = codeByEmoji(rawIcon);
        if (code != null) return code;
        // 是代码形态但长度不对（如 1001+ 生活指数）、或压根没给 icon：都不该把数字画在卡上，
        // 有天气文案就按文案选图标；没有就留空
        if (!isCodeLike(rawIcon) && rawIcon != null && !rawIcon.trim().isEmpty()) {
            // 认不出的 emoji/文案：原样展示，不硬塞一个天气图标
            return null;
        }
        if (fallbackText == null || fallbackText.trim().isEmpty()) return null;
        return codeByText(fallbackText);
    }

    /** 纯数字（允许 "-fill" 后缀）的图标代码形态，长度不合法也算 */
    private static boolean isCodeLike(String raw) {
        if (raw == null) return false;
        String v = raw.trim();
        if (v.endsWith("-fill")) v = v.substring(0, v.length() - "-fill".length()).trim();
        if (v.isEmpty()) return false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** "104" / " 104 " / "104-fill" → "104"；不是纯图标代码返回 null（1001+ 生活指数不在此列） */
    public static String normalizeIconCode(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.endsWith("-fill")) {
            v = v.substring(0, v.length() - "-fill".length()).trim();
        }
        if (v.length() != 3) return null;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        return v;
    }

    /**
     * 已存的 emoji 图标 → 图标代码（兼容自动桥接落盘的旧数据，以及模型直接给 emoji）。
     * 认不出返回 null。忽略 emoji 的变体选择符（U+FE0F/U+FE0E），"☀" 与 "☀️" 等价。
     */
    public static String codeByEmoji(String raw) {
        if (raw == null) return null;
        String v = raw.replace("\uFE0F", "").replace("\uFE0E", "").trim();
        switch (v) {
            case "☀": return "100";
            case "🌙": return "150";
            case "⛅": return "101";
            case "🌤": return "101";
            case "☁": return "104";
            case "🌧": return "305";
            case "⛈": return "302";
            case "🌩": return "303";
            case "🌨": return "400";
            case "❄": return "400";
            case "🌫": return "501";
            case "😷": return "502";
            case "🔥": return "900";
            case "🥶": return "901";
            case "❓": return "999";
            default: return null;
        }
    }

    /**
     * 天气现象文案 → 图标代码（工具侧 forecast 只给文字，没有图标码）。
     * 只挑图标包里真实存在的代码；认不出返回 "999"（未知图标）。
     */
    public static String codeByText(String text) {
        if (text == null || text.isEmpty()) return "999";
        if (text.contains("雷")) return "302";
        if (text.contains("冰雹")) return "304";
        if (text.contains("雨夹雪")) return "404";
        if (text.contains("阵雪")) return "407";
        if (text.contains("暴雪")) return "403";
        if (text.contains("大雪")) return "402";
        if (text.contains("中雪")) return "401";
        if (text.contains("雪")) return "400";
        if (text.contains("暴雨")) return "310";
        if (text.contains("大雨")) return "307";
        if (text.contains("中雨")) return "306";
        if (text.contains("小雨") || text.contains("细雨")) return "305";
        if (text.contains("阵雨")) return "300";
        if (text.contains("雨")) return "305";
        if (text.contains("雾")) return "501";
        if (text.contains("霾")) return "502";
        if (text.contains("沙尘") || text.contains("扬沙") || text.contains("浮尘")) return "503";
        if (text.contains("晴")) return "100";
        if (text.contains("多云")) return "101";
        if (text.contains("阴")) return "104";
        if (text.contains("云")) return "101";
        return "999";
    }

    public static String getWeatherType(String weatherCode) {
        if (weatherCode == null || weatherCode.isEmpty()) return "unknown";
        try {
            int code = Integer.parseInt(weatherCode);
            if (code >= 100 && code < 200) return "sunny";
            if (code >= 200 && code < 300) return "cloudy";
            if (code >= 300 && code < 400) return "rain";
            if (code >= 400 && code < 500) return "snow";
            if (code >= 500 && code < 600) return "fog";
            if (code >= 600 && code < 700) return "wind";
            if (code >= 800 && code < 900) return "moon";
            if (code >= 900) return "extreme";
            return "unknown";
        } catch (NumberFormatException e) {
            return "unknown";
        }
    }

    public static String getWeatherTextDesc(String weatherCode) {
        if (weatherCode == null || weatherCode.isEmpty()) return "未知";
        try {
            int code = Integer.parseInt(weatherCode);
            if (code >= 100 && code <= 104) return "晴";
            if (code >= 150 && code <= 154) return "晴(夜)";
            if (code >= 300 && code <= 309) return "雨";
            if (code >= 310 && code <= 318) return "暴雨";
            if (code >= 350 && code <= 351) return "阵雨(夜)";
            if (code >= 400 && code <= 410) return "雪";
            if (code >= 456 && code <= 457) return "阵雪(夜)";
            if (code >= 500 && code <= 515) return "雾/霾";
            if (code >= 800 && code <= 807) return "月相";
            if (code == 900) return "热";
            if (code == 901) return "冷";
            return "未知";
        } catch (NumberFormatException e) {
            return "未知";
        }
    }

    public static int getIconDrawableRes(String weatherCode, Context context) {
        if (weatherCode == null || weatherCode.isEmpty()) {
            return R.drawable.wi_999;
        }
        try {
            String resName = "wi_" + weatherCode;
            int resId = context.getResources().getIdentifier(resName, "drawable", context.getPackageName());
            if (resId != 0) return resId;
        } catch (Exception ignored) {
        }
        return 0;
    }

    public static Drawable getIconDrawable(String weatherCode, Context context, int sizeDp) {
        return createFontIconDrawable(context, weatherCode, sizeDp, Color.WHITE);
    }

    public static Drawable createFontIconDrawable(Context context, String weatherCode, int sizeDp, int color) {
        int sizePx = (int) (sizeDp * context.getResources().getDisplayMetrics().density);
        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setTypeface(QWeatherIconFont.getTypeface(context));
        paint.setColor(color);
        paint.setTextSize(sizePx * 0.85f);
        paint.setAntiAlias(true);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setSubpixelText(true);

        String iconText = QWeatherIconFont.getIcon(weatherCode);
        float y = (sizePx / 2f) - ((paint.descent() + paint.ascent()) / 2f);
        canvas.drawText(iconText, sizePx / 2f, y, paint);
        return new BitmapDrawable(context.getResources(), bitmap);
    }

    private static Drawable createEmojiDrawable(Context context, String emoji, int sizeDp) {
        int sizePx = (int) (sizeDp * context.getResources().getDisplayMetrics().density);
        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setColor(Color.WHITE);
        paint.setTextSize(sizePx * 0.75f);
        paint.setAntiAlias(true);
        paint.setTextAlign(Paint.Align.CENTER);
        float textWidth = paint.measureText(emoji);
        float textHeight = paint.getTextSize();
        float x = (sizePx - textWidth) / 2;
        float y = (sizePx + textHeight) / 2 - paint.descent();
        canvas.drawText(emoji, x, y, paint);
        return new BitmapDrawable(context.getResources(), bitmap);
    }
}

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

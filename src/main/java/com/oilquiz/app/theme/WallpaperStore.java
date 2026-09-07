package com.oilquiz.app.theme;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.net.Uri;


import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 壁纸库：内置壁纸 + 用户添加壁纸的统一管理。
 * <ul>
 *   <li>内置壁纸：首次访问时程序生成 10 张深色系壁纸（星空/极光/日落等），存入 files/wallpapers；</li>
 *   <li>用户壁纸：从相册/文件选择（PhotoPicker）复制到 files/wallpapers，可删除；</li>
 * </ul>
 */
public final class WallpaperStore {

    private static final String DIR = "wallpapers";
    private static final String BUILTIN_PREFIX = "builtin_";
    private static final String USER_PREFIX = "user_";
    private static final int W = 720;
    private static final int H = 1280;

    /** 内置壁纸名称（与 createWallpaper 的 index 一一对应） */
    private static final String[] BUILTIN_NAMES = {
            "深蓝星空", "紫色极光", "墨绿森林", "橙红日落", "粉色晚霞",
            "深海涟漪", "午夜星夜", "雾蓝极简", "荧光青绿", "鎏金暖阳",
            "猫和老鼠"
    };

    /** 内置壁纸中来自 assets 的（index -> assets 文件名），其余为程序绘制 */
    private static final String ASSETS_WALLPAPER = "wallpapers/tomcat.jpg";
    private static final int ASSETS_WALLPAPER_INDEX = 10;

    private WallpaperStore() {
    }

    public static File dir(Context context) {
        File d = new File(context.getFilesDir(), DIR);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    /** 确保内置壁纸已生成，返回内置壁纸文件（数量/版本不匹配自动重建） */
    public static List<File> ensureBuiltin(Context context) {
        File d = dir(context);
        File[] old = d.listFiles((f, n) -> n.startsWith(BUILTIN_PREFIX));
        if (old == null || old.length != BUILTIN_NAMES.length) {
            if (old != null) {
                for (File f : old) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            for (int i = 0; i < BUILTIN_NAMES.length; i++) {
                try {
                    if (i == ASSETS_WALLPAPER_INDEX) {
                        // 内置图片壁纸：从 assets 拷贝（保持原图，不二次绘制）
                        InputStream in = context.getAssets().open(ASSETS_WALLPAPER);
                        FileOutputStream fos = new FileOutputStream(new File(d, BUILTIN_PREFIX + i + ".jpg"));
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            fos.write(buf, 0, n);
                        }
                        fos.flush();
                        fos.close();
                        in.close();
                    } else {
                        Bitmap bmp = createWallpaper(i, W, H);
                        FileOutputStream fos = new FileOutputStream(new File(d, BUILTIN_PREFIX + i + ".png"));
                        bmp.compress(Bitmap.CompressFormat.PNG, 90, fos);
                        fos.flush();
                        fos.close();
                        bmp.recycle();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        List<File> result = new ArrayList<>();
        for (int i = 0; i < BUILTIN_NAMES.length; i++) {
            File f = new File(d, BUILTIN_PREFIX + i + ".png");
            if (f.exists()) {
                result.add(f);
            }
        }
        return result;
    }

    /** 全部壁纸：内置在前，用户添加在后 */
    public static List<File> listAll(Context context) {
        List<File> list = ensureBuiltin(context);
        File[] files = dir(context).listFiles((d, name) -> name.startsWith(USER_PREFIX));
        if (files != null) {
            for (File f : files) {
                list.add(f);
            }
        }
        return list;
    }

    public static boolean isBuiltin(File f) {
        return f.getName().startsWith(BUILTIN_PREFIX);
    }

    /** 添加用户壁纸（从 PhotoPicker 返回的 Uri 复制到私有目录） */
    public static File addFromUri(Context context, Uri uri) {
        try {
            InputStream in = context.getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            File f = new File(dir(context), USER_PREFIX + System.currentTimeMillis() + ".png");
            FileOutputStream out = new FileOutputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            out.close();
            in.close();
            return f;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 删除用户壁纸（内置壁纸不可删） */
    public static boolean delete(Context context, File f) {
        if (f == null || isBuiltin(f)) {
            return false;
        }
        return f.delete();
    }

    /** 壁纸显示名：内置用中文名，用户壁纸用时间戳 */
    public static String displayName(File f) {
        String name = f.getName();
        if (name.startsWith(BUILTIN_PREFIX)) {
            try {
                int idx = Integer.parseInt(name.substring(BUILTIN_PREFIX.length(), name.indexOf('.')));
                if (idx >= 0 && idx < BUILTIN_NAMES.length) {
                    return BUILTIN_NAMES[idx];
                }
            } catch (Throwable ignored) {
            }
        }
        long ts = 0;
        try {
            ts = Long.parseLong(name.substring(USER_PREFIX.length(), name.indexOf('.')));
        } catch (Throwable ignored) {
        }
        return ts > 0 ? java.text.SimpleDateFormat.getDateTimeInstance().format(new java.util.Date(ts)) : name;
    }

    /** 按索引生成对应风格壁纸 */
    private static Bitmap createWallpaper(int idx, int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        switch (idx) {
            case 0: drawStarry(canvas, w, h, 0xFF0B1E3B, 0xFF1E3A8A, 0xFF60A5FA); break;
            case 1: drawAurora(canvas, w, h, 0xFF1E1136, 0xFF4C1D95, 0xFFA78BFA); break;
            case 2: drawForest(canvas, w, h, 0xFF06281F, 0xFF065F46, 0xFF34D399); break;
            case 3: drawSunset(canvas, w, h, 0xFF431407, 0xFF9A3412, 0xFFFB923C); break;
            case 4: drawDusk(canvas, w, h, 0xFF4C0519, 0xFF831843, 0xFFF9A8D4); break;
            case 5: drawOcean(canvas, w, h, 0xFF082F49, 0xFF155E75, 0xFF22D3EE); break;
            case 6: drawStarry(canvas, w, h, 0xFF2E1065, 0xFF4C1D95, 0xFFC4B5FD); break;
            case 7: drawMinimal(canvas, w, h, 0xFF0F172A, 0xFF334155, 0xFF94A3B8); break;
            case 8: drawAurora(canvas, w, h, 0xFF022C22, 0xFF065F46, 0xFF6EE7B7); break;
            default: drawSunset(canvas, w, h, 0xFF451A03, 0xFFB45309, 0xFFFCD34D); break;
        }
        return bmp;
    }

    private static void baseGradient(Canvas c, int w, int h, int s, int e) {
        LinearGradient g = new LinearGradient(0, 0, w, h, s, e, Shader.TileMode.CLAMP);
        Paint p = new Paint();
        p.setShader(g);
        c.drawRect(0, 0, w, h, p);
    }

    private static void glow(Canvas c, float cx, float cy, float r, int color) {
        RadialGradient g = new RadialGradient(cx, cy, r, color, 0x00000000, Shader.TileMode.CLAMP);
        Paint p = new Paint();
        p.setShader(g);
        c.drawCircle(cx, cy, r, p);
    }

    private static void stars(Canvas c, int w, int h, int n, int color, float maxR) {
        java.util.Random rnd = new java.util.Random(2024);
        Paint p = new Paint();
        p.setColor(color);
        for (int i = 0; i < n; i++) {
            float x = rnd.nextFloat() * w;
            float y = rnd.nextFloat() * h;
            float r = 1 + rnd.nextFloat() * maxR;
            c.drawCircle(x, y, r, p);
        }
    }

    /** 波浪光带 */
    private static void band(Canvas c, int w, int h, int color, float y0, float amp, float strokeW) {
        Paint p = new Paint();
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(strokeW);
        p.setStrokeCap(Paint.Cap.ROUND);
        Path path = new Path();
        path.moveTo(0, y0);
        for (float x = 0; x <= w; x += w / 24f) {
            path.lineTo(x, y0 + (float) Math.sin(x / w * Math.PI * 2) * amp);
        }
        c.drawPath(path, p);
    }

    private static void drawStarry(Canvas c, int w, int h, int s, int e, int starColor) {
        baseGradient(c, w, h, s, e);
        glow(c, w * 0.22f, h * 0.18f, w * 0.55f, 0x33FFFFFF);
        glow(c, w * 0.8f, h * 0.75f, w * 0.6f, 0x2EFFFFFF);
        stars(c, w, h, 90, starColor | 0xCC000000, 2.2f);
        stars(c, w, h, 30, 0xFFFFFFFF, 1.4f);
    }

    private static void drawAurora(Canvas c, int w, int h, int s, int e, int bandColor) {
        baseGradient(c, w, h, s, e);
        band(c, w, h, bandColor | 0x22000000, h * 0.42f, h * 0.10f, h * 0.10f);
        band(c, w, h, bandColor | 0x55000000, h * 0.55f, h * 0.14f, h * 0.07f);
        band(c, w, h, 0x33FFFFFF, h * 0.30f, h * 0.08f, h * 0.04f);
        glow(c, w * 0.5f, h * 0.2f, w * 0.5f, 0x22FFFFFF);
    }

    private static void drawForest(Canvas c, int w, int h, int s, int e, int lightColor) {
        baseGradient(c, w, h, s, e);
        glow(c, w * 0.18f, h * 0.25f, w * 0.5f, 0x26FFFFFF);
        glow(c, w * 0.75f, h * 0.7f, w * 0.65f, 0x22FFFFFF);
        java.util.Random rnd = new java.util.Random(7);
        Paint p = new Paint();
        for (int i = 0; i < 14; i++) {
            float x = rnd.nextFloat() * w;
            float y = rnd.nextFloat() * h;
            float r = 8 + rnd.nextFloat() * 26;
            p.setColor(lightColor | ((int) (0x18 + rnd.nextFloat() * 0x14)) << 24);
            c.drawCircle(x, y, r, p);
        }
        p.setColor(0x22000000);
        for (int i = 0; i < 26; i++) {
            float x = rnd.nextFloat() * w;
            float y = rnd.nextFloat() * h;
            float r = 3 + rnd.nextFloat() * 9;
            c.drawCircle(x, y, r, p);
        }
    }

    private static void drawSunset(Canvas c, int w, int h, int s, int e, int sunColor) {
        baseGradient(c, w, h, s, e);
        glow(c, w * 0.5f, h * 0.42f, w * 0.38f, sunColor | 0x66000000);
        glow(c, w * 0.5f, h * 0.42f, w * 0.18f, sunColor | 0x99FF0000);
        c.drawCircle(w * 0.5f, h * 0.42f, w * 0.07f, (Paint) new Paint() {{ setColor(sunColor); }});
        band(c, w, h, 0x22000000, h * 0.62f, h * 0.04f, h * 0.02f);
        band(c, w, h, 0x33000000, h * 0.68f, h * 0.05f, h * 0.025f);
    }

    private static void drawDusk(Canvas c, int w, int h, int s, int e, int cloudColor) {
        baseGradient(c, w, h, s, e);
        glow(c, w * 0.3f, h * 0.35f, w * 0.5f, 0x33FFFFFF);
        glow(c, w * 0.72f, h * 0.55f, w * 0.55f, 0x2EFFFFFF);
        Paint p = new Paint();
        p.setColor(cloudColor | 0x33000000);
        java.util.Random rnd = new java.util.Random(9);
        for (int i = 0; i < 10; i++) {
            float x = rnd.nextFloat() * w;
            float y = h * (0.2f + rnd.nextFloat() * 0.6f);
            float rx = w * (0.08f + rnd.nextFloat() * 0.16f);
            float ry = h * (0.02f + rnd.nextFloat() * 0.02f);
            c.drawOval(x - rx / 2, y - ry / 2, x + rx / 2, y + ry / 2, p);
        }
        p.setColor(0x22FFFFFF);
        for (int i = 0; i < 6; i++) {
            float x = rnd.nextFloat() * w;
            float y = h * (0.3f + rnd.nextFloat() * 0.5f);
            float rx = w * (0.1f + rnd.nextFloat() * 0.14f);
            c.drawOval(x - rx / 2, y - h * 0.008f, x + rx / 2, y + h * 0.008f, p);
        }
    }

    private static void drawOcean(Canvas c, int w, int h, int s, int e, int waveColor) {
        baseGradient(c, w, h, s, e);
        band(c, w, h, waveColor | 0x44FF0000, h * 0.55f, h * 0.05f, h * 0.025f);
        band(c, w, h, waveColor | 0x33FF0000, h * 0.66f, h * 0.07f, h * 0.02f);
        band(c, w, h, 0x26FFFFFF, h * 0.80f, h * 0.04f, h * 0.012f);
        glow(c, w * 0.5f, h * 0.15f, w * 0.45f, 0x22FFFFFF);
    }

    private static void drawMinimal(Canvas c, int w, int h, int s, int e, int lineColor) {
        baseGradient(c, w, h, s, e);
        Paint p = new Paint();
        p.setColor(lineColor | 0x22000000);
        p.setStrokeWidth(h * 0.004f);
        for (float y = h * 0.2f; y < h; y += h * 0.09f) {
            c.drawLine(w * 0.06f, y, w * 0.94f, y, p);
        }
        glow(c, w * 0.5f, h * 0.28f, w * 0.5f, 0x1AFFFFFF);
    }
}

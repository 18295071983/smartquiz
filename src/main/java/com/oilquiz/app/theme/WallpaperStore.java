package com.oilquiz.app.theme;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 壁纸库：内置壁纸 + 用户添加壁纸的统一管理。
 *
 * <ul>
 *   <li><b>内置壁纸</b>：来自 {@code assets/wallpapers/builtin_XX.jpg} 的实拍图
 *       （Unsplash，免费可商用；来源见 {@code assets/wallpapers/CREDITS.md}），
 *       首次访问时拷贝到 {@code files/wallpapers}；</li>
 *   <li><b>用户壁纸</b>：从相册/文件选择（PhotoPicker）复制到 files/wallpapers，可删除。</li>
 * </ul>
 *
 * <p><b>2026-11-18</b>：原先 10 张内置壁纸是代码画的深色渐变（星空/极光/日落），
 * 又暗又糊，已全部替换为 11 张实拍照片；同时加了 {@link #BUILTIN_VERSION}
 * 版本号，老用户升级后旧的自绘壁纸会被清掉重铺，不会和新图混在一起。</p>
 */
public final class WallpaperStore {

    private static final String DIR = "wallpapers";
    private static final String BUILTIN_PREFIX = "builtin_";
    private static final String USER_PREFIX = "user_";

    /**
     * 内置壁纸版本：内置图内容/数量变化时 +1，旧缓存（含老的自绘壁纸）会被清掉重铺。
     * v1 = 代码绘制的 10 张深色渐变 + tomcat；v2 = 11 张实拍图（2026-11-18）。
     */
    private static final int BUILTIN_VERSION = 2;
    private static final String VERSION_FILE = ".builtin_version";

    /** 内置壁纸名称（与 BUILTIN_ASSETS 一一对应） */
    private static final String[] BUILTIN_NAMES = {
            "雪山峡谷", "翡翠湖泊", "春日樱花", "黄金海岸", "草原落日", "红岩公路",
            "黄色花海", "狸花小猫", "薰衣草田", "林间晨光", "镜面湖"
    };

    /** 内置壁纸素材：全部是 assets 里的实拍图，不再程序绘制 */
    private static final String[] BUILTIN_ASSETS = {
            "wallpapers/builtin_00.jpg",
            "wallpapers/builtin_01.jpg",
            "wallpapers/builtin_02.jpg",
            "wallpapers/builtin_03.jpg",
            "wallpapers/builtin_04.jpg",
            "wallpapers/builtin_05.jpg",
            "wallpapers/builtin_06.jpg",
            "wallpapers/builtin_07.jpg",
            "wallpapers/builtin_08.jpg",
            "wallpapers/builtin_09.jpg",
            "wallpapers/builtin_10.jpg",
    };

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

    /** 确保内置壁纸已拷贝，返回内置壁纸文件（版本/数量不匹配自动重建） */
    public static List<File> ensureBuiltin(Context context) {
        File d = dir(context);
        File versionFile = new File(d, VERSION_FILE);
        File[] old = d.listFiles((f, n) -> n.startsWith(BUILTIN_PREFIX));
        boolean needRebuild = readVersion(versionFile) != BUILTIN_VERSION
                || old == null
                || old.length != BUILTIN_ASSETS.length;
        if (needRebuild) {
            if (old != null) {
                for (File f : old) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            boolean allCopied = true;
            for (int i = 0; i < BUILTIN_ASSETS.length; i++) {
                try {
                    copyAsset(context, BUILTIN_ASSETS[i], builtinFile(d, i));
                } catch (Throwable notStored) {
                    allCopied = false;
                    com.oilquiz.app.util.AILogger.w("WallpaperStore",
                            "内置壁纸拷贝失败，这张会从选择列表里静默消失: " + notStored);
                }
            }
            // 全部成功才记版本；有失败下次进壁纸库会重试
            if (allCopied) {
                writeVersion(versionFile, BUILTIN_VERSION);
            }
        }
        List<File> result = new ArrayList<>();
        for (int i = 0; i < BUILTIN_NAMES.length; i++) {
            File f = builtinFile(d, i);
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

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private static File builtinFile(File dir, int index) {
        return new File(dir, String.format(java.util.Locale.ROOT, "%s%02d.jpg", BUILTIN_PREFIX, index));
    }

    private static void copyAsset(Context context, String assetPath, File target) throws Exception {
        try (InputStream in = context.getAssets().open(assetPath);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
    }

    private static int readVersion(File f) {
        try {
            if (!f.exists()) {
                return 0;
            }
            byte[] buf = new byte[16];
            try (InputStream in = new java.io.FileInputStream(f)) {
                int n = in.read(buf);
                if (n <= 0) {
                    return 0;
                }
                return Integer.parseInt(new String(buf, 0, n, "UTF-8").trim());
            }
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void writeVersion(File f, int version) {
        try {
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(Integer.toString(version).getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
        }
    }
}

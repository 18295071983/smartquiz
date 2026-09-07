package com.oilquiz.app.theme;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;

import java.io.File;

/**
 * 应用壁纸管理器：把「系统壁纸」或「应用壁纸库图片」作为 App 页面背景。
 * <ul>
 *   <li>模式三档：{@link #MODE_FOLLOW_SYSTEM} 跟随系统壁纸（换系统壁纸自动跟随）、
 *       {@link #MODE_LIBRARY} 使用壁纸库图片、{@link #MODE_OFF} 关闭；</li>
 *   <li>跟随系统：{@link WallpaperManager#getDrawable()} 实时读取系统壁纸，
 *       由 SmartQuizApplication 注册的 OnColorsChangedListener 在壁纸变化时重建前台页面；</li>
 *   <li>可读性：壁纸上层叠加约 35% 黑色遮罩（LayerDrawable），保证前景文字/控件清晰。</li>
 * </ul>
 */
public final class AppWallpaperManager {

    public static final int MODE_FOLLOW_SYSTEM = 0;
    public static final int MODE_LIBRARY = 1;
    public static final int MODE_OFF = 2;

    private static final String PREF_NAME = "wallpaper_preferences";
    private static final String KEY_MODE = "mode";
    private static final String KEY_PATH = "path";
    /** 黑色遮罩透明度（0-255）：0x59 ≈ 35%，兼顾壁纸观感与前景可读性 */
    private static final int SCRIM_ALPHA = 0x59;
    /** 兜底壁纸：跟随系统壁纸读取失败时的默认壁纸（内置「猫和老鼠」） */
    private static final String FALLBACK_BUILTIN = "builtin_10.jpg";
    private static final String FALLBACK_ASSET = "wallpapers/tomcat.jpg";

    private AppWallpaperManager() {
    }

    /** 当前应用壁纸模式（默认跟随系统壁纸） */
    public static int getMode(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_MODE, MODE_FOLLOW_SYSTEM);
    }

    public static void setMode(Context context, int mode) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_MODE, mode).apply();
    }

    /** 壁纸库模式选中的壁纸文件路径 */
    public static String getLibraryPath(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PATH, null);
    }

    public static void setLibraryPath(Context context, String path) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_PATH, path).apply();
    }


    /**
     * 兜底默认壁纸：内置「猫和老鼠」（builtin_10.jpg）。
     * 文件不存在时同步从 assets 拷贝（首装/卸载重装后立即可用），无需等待后台预生成。
     */
    private static Drawable getFallbackDrawable(Context context) {
        try {
            File f = new File(WallpaperStore.dir(context), FALLBACK_BUILTIN);
            if (!f.exists()) {
                java.io.InputStream in = context.getAssets().open(FALLBACK_ASSET);
                java.io.FileOutputStream out = new java.io.FileOutputStream(f);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
                out.close();
                in.close();
            }
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (bmp != null) {
                return new BitmapDrawable(context.getResources(), bmp);
            }
        } catch (Throwable ignored) {
            android.util.Log.w("WallpaperDebug", "兜底壁纸读取失败: " + ignored);
        }
        return null;
    }

    /**
     * 底层直连：反射 ServiceManager.getService("wallpaper") + IWallpaperManager.getWallpaperFile(FLAG_SYSTEM)。
     * 小米 HyperOS 在 WallpaperManager 客户端硬编码检查 READ_EXTERNAL_STORAGE（Android 13+ 无法授予），
     * 但 AOSP 的 WallpaperManagerService.getWallpaperFile 服务端不校验该权限，直接返回 ParcelFileDescriptor，
     * 因此绕过客户端即可读到系统壁纸。hidden API 限制下失败时返回 null，不影响现有回退。
     */
    private static Drawable readSystemWallpaperViaBinder(Context context) {
        try {
            Class<?> smCls = Class.forName("android.os.ServiceManager");
            java.lang.reflect.Method getService = smCls.getDeclaredMethod("getService", String.class);
            getService.setAccessible(true);
            Object binderObj = getService.invoke(null, "wallpaper");
            if (!(binderObj instanceof android.os.IBinder)) {
                android.util.Log.w("WallpaperDebug", "binder直连: wallpaper 服务不可用");
                return null;
            }
            android.os.IBinder binder = (android.os.IBinder) binderObj;
            Class<?> stubCls = Class.forName("android.app.IWallpaperManager$Stub");
            java.lang.reflect.Method asInterface = stubCls.getDeclaredMethod("asInterface", android.os.IBinder.class);
            asInterface.setAccessible(true);
            Object wm = asInterface.invoke(null, binder);
            Class<?> iwmCls = Class.forName("android.app.IWallpaperManager");
            java.lang.reflect.Method getFile = iwmCls.getDeclaredMethod("getWallpaperFile", int.class);
            getFile.setAccessible(true);
            // FLAG_SYSTEM = 1；FLAG_LOCK = 2（先系统后锁屏）
            int[] flags = {1, 2};
            for (int f : flags) {
                try {
                    Object pfdObj = getFile.invoke(wm, f);
                    if (!(pfdObj instanceof android.os.ParcelFileDescriptor)) {
                        continue;
                    }
                    android.os.ParcelFileDescriptor pfd = (android.os.ParcelFileDescriptor) pfdObj;
                    try {
                        Bitmap bmp = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
                        if (bmp != null) {
                            android.util.Log.i("WallpaperDebug", "binder直连读取系统壁纸成功 flag=" + f);
                            return new BitmapDrawable(context.getResources(), bmp);
                        }
                    } finally {
                        try {
                            pfd.close();
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (Throwable ignoredInner) {
                    android.util.Log.w("WallpaperDebug", "binder直连 flag=" + f + " 失败: " + ignoredInner);
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("WallpaperDebug", "binder直连失败(整体): " + t);
        }
        return null;
    }


    /** 当前壁纸 drawable（已叠加暗化遮罩）；模式关闭或获取失败返回 null */
    public static Drawable getWallpaperDrawable(Context context) {
        int mode = getMode(context);
        if (mode == MODE_OFF) {
            return null;
        }
        Drawable base = null;
        if (mode == MODE_FOLLOW_SYSTEM) {
            WallpaperManager wm = null;
            try {
                wm = WallpaperManager.getInstance(context);
                base = wm.getDrawable();
                android.util.Log.i("WallpaperDebug", "FOLLOW_SYSTEM getDrawable=" + (base != null));
            } catch (Throwable ignored) {
                android.util.Log.w("WallpaperDebug", "getDrawable 异常: " + ignored);
            }
            if (base == null) {
                // 兜底1：getWallpaperFile 读壁纸文件（独立 try，不受上面异常影响）
                if (wm != null && android.os.Build.VERSION.SDK_INT >= 33) {
                    int[] flags = {WallpaperManager.FLAG_SYSTEM, WallpaperManager.FLAG_LOCK};
                    for (int f : flags) {
                        try {
                            android.os.ParcelFileDescriptor pfd = wm.getWallpaperFile(f);
                            if (pfd != null) {
                                Bitmap bmp = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
                                pfd.close();
                                if (bmp != null) {
                                    base = new BitmapDrawable(context.getResources(), bmp);
                                    android.util.Log.i("WallpaperDebug", "getWallpaperFile 读取成功 flag=" + f);
                                    break;
                                }
                            }
                        } catch (Throwable ignored2) {
                            android.util.Log.w("WallpaperDebug", "getWallpaperFile flag=" + f + " 失败: " + ignored2);
                        }
                    }
                }
            }
            if (base == null) {
                // 兜底2：getBitmap 反射（独立 try）
                if (wm != null) {
                    try {
                        java.lang.reflect.Method m = WallpaperManager.class.getMethod("getBitmap");
                        Object o = m.invoke(wm);
                        if (o instanceof Bitmap) {
                            base = new BitmapDrawable(context.getResources(), (Bitmap) o);
                        }
                    } catch (Throwable ignored3) {
                        android.util.Log.w("WallpaperDebug", "getBitmap反射失败: " + ignored3);
                    }
                }
            }
            if (base == null) {
                // 兜底3：绕过 MIUI 客户端，直接连安卓系统底层 IWallpaperManager Binder 服务。
                // AOSP 服务端 getWallpaperFile 不校验存储权限（fd 由 system 进程打开后传回），
                // 可绕开小米 READ_EXTERNAL_STORAGE 检查。
                base = readSystemWallpaperViaBinder(context);
            }
            if (base == null) {
                // 兜底4（最终兜底）：系统壁纸读取失败（如小米 HyperOS 锁 READ_EXTERNAL_STORAGE），
                // 回退到内置默认壁纸「猫和老鼠」，保证页面始终有壁纸背景。
                base = getFallbackDrawable(context);
                if (base != null) {
                    android.util.Log.w("WallpaperDebug", "系统壁纸读取失败，已回退内置默认壁纸（猫和老鼠）");
                }
            }
        } else {
            String path = getLibraryPath(context);
            if (path != null) {
                File f = new File(path);
                if (f.exists()) {
                    Bitmap bmp = BitmapFactory.decodeFile(path);
                    if (bmp != null) {
                        base = new BitmapDrawable(context.getResources(), bmp);
                    }
                }
            }
        }
        if (base == null) {
            android.util.Log.w("WallpaperDebug", "壁纸 drawable 为 null, mode=" + mode);
            return null;
        }
        android.util.Log.i("WallpaperDebug", "壁纸已获取, mode=" + mode);
        return new LayerDrawable(new Drawable[]{base, new ColorDrawable(SCRIM_ALPHA << 24)});
    }

    /**
     * 把当前应用壁纸设到页面根 View 背景。
     * 模式为「关闭」时不操作（页面 XML 自带背景保留）；无壁纸可用也不操作。
     *
     * @return 是否已应用壁纸
     */
    public static boolean applyTo(Context context, View root) {
        if (root == null || getMode(context) == MODE_OFF) {
            return false;
        }
        Drawable d = getWallpaperDrawable(context);
        if (d == null) {
            return false;
        }
        root.setBackground(d);
        return true;
    }
}

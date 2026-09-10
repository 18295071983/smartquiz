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


    /**
     * 实时读取系统壁纸（文件直读优先，避免客户端缓存返回旧壁纸）。
     * 读取顺序（每级独立 try，逐级兜底）：
     * ① getWallpaperFile（API 33+，公开 API，直读 WallpaperManagerService 当前壁纸文件——实时、无客户端缓存）；
     * ② binder 直连（反射读服务端当前壁纸文件，绕过小米 HyperOS 的 READ_EXTERNAL_STORAGE 客户端检查）；
     * ③ getDrawable()（标准路径，部分 ROM 可能返回缓存的旧壁纸，仅作后备）；
     * ④ getBitmap 反射（最终后备）。
     * 全部失败返回 null（调用方决定内置兜底）。
     */
    public static Drawable readSystemWallpaperDrawable(Context context) {
        WallpaperManager wm = null;
        try {
            wm = WallpaperManager.getInstance(context);
        } catch (Throwable ignored) {
        }
        Drawable base = null;

        // ① 公开 API 文件直读：getWallpaperFile 直接返回服务端当前壁纸文件 fd（实时，无客户端缓存）
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
                            android.util.Log.i("WallpaperDebug", "系统壁纸: getWallpaperFile 文件直读成功 flag=" + f);
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                    android.util.Log.w("WallpaperDebug", "getWallpaperFile flag=" + f + " 失败: " + ignored);
                }
            }
        }

        // ② binder 直连：绕过客户端缓存/权限，直读服务端当前壁纸文件（换壁纸后必返回新图）
        if (base == null) {
            base = readSystemWallpaperViaBinder(context);
        }

        // ③ 标准路径 getDrawable（部分 ROM 可能带缓存，仅作后备）
        if (base == null && wm != null) {
            try {
                base = wm.getDrawable();
                android.util.Log.i("WallpaperDebug", "系统壁纸: getDrawable=" + (base != null));
            } catch (Throwable ignored) {
                android.util.Log.w("WallpaperDebug", "getDrawable 异常: " + ignored);
            }
        }

        // ④ getBitmap 反射（最终后备）
        if (base == null && wm != null) {
            try {
                java.lang.reflect.Method m = WallpaperManager.class.getMethod("getBitmap");
                Object o = m.invoke(wm);
                if (o instanceof Bitmap) {
                    base = new BitmapDrawable(context.getResources(), (Bitmap) o);
                    android.util.Log.i("WallpaperDebug", "系统壁纸: getBitmap 反射成功");
                }
            } catch (Throwable ignored) {
                android.util.Log.w("WallpaperDebug", "getBitmap反射失败: " + ignored);
            }
        }

        if (base == null) {
            if (isLiveWallpaper(context)) {
                // 动态壁纸无静态壁纸文件：系统不提供当前帧静态图（AOSP 限制），回退内置默认壁纸并标记
                android.util.Log.w("WallpaperDebug", "当前为动态壁纸，无静态壁纸文件可读，回退内置默认壁纸");
            } else {
                android.util.Log.w("WallpaperDebug", "系统壁纸全部读取失败");
            }
        }
        return base;
    }

    /**
     * 主动探测：读取系统壁纸文件 fd（getWallpaperFile API 33+ → binder 直连，均直读服务端当前壁纸文件，
     * 绕过客户端缓存/权限）。
     */
    private static android.os.ParcelFileDescriptor readSystemWallpaperPfd(Context context) {
        WallpaperManager wm = null;
        try {
            wm = WallpaperManager.getInstance(context);
        } catch (Throwable ignored) {
        }
        if (wm != null && android.os.Build.VERSION.SDK_INT >= 33) {
            int[] flags = {WallpaperManager.FLAG_SYSTEM, WallpaperManager.FLAG_LOCK};
            for (int f : flags) {
                try {
                    android.os.ParcelFileDescriptor pfd = wm.getWallpaperFile(f);
                    if (pfd != null) {
                        return pfd;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        // binder 直连（绕过小米 HyperOS 客户端权限检查，直读服务端当前壁纸文件）
        try {
            Class<?> smCls = Class.forName("android.os.ServiceManager");
            java.lang.reflect.Method getService = smCls.getDeclaredMethod("getService", String.class);
            getService.setAccessible(true);
            Object binderObj = getService.invoke(null, "wallpaper");
            if (!(binderObj instanceof android.os.IBinder)) {
                return null;
            }
            Class<?> stubCls = Class.forName("android.app.IWallpaperManager$Stub");
            java.lang.reflect.Method asInterface = stubCls.getDeclaredMethod("asInterface", android.os.IBinder.class);
            asInterface.setAccessible(true);
            Object wmSvc = asInterface.invoke(null, (android.os.IBinder) binderObj);
            Class<?> iwmCls = Class.forName("android.app.IWallpaperManager");
            java.lang.reflect.Method getFile = iwmCls.getDeclaredMethod("getWallpaperFile", int.class);
            getFile.setAccessible(true);
            int[] flags = {1, 2}; // FLAG_SYSTEM=1, FLAG_LOCK=2
            for (int f : flags) {
                try {
                    Object pfdObj = getFile.invoke(wmSvc, f);
                    if (pfdObj instanceof android.os.ParcelFileDescriptor) {
                        return (android.os.ParcelFileDescriptor) pfdObj;
                    }
                } catch (Throwable ignoredInner) {
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("WallpaperDebug", "binder pfd 直连失败: " + t.getMessage());
        }
        return null;
    }

    /**
     * 系统壁纸指纹：壁纸文件头 16KB 字节 + 文件总大小 混合哈希。
     * 换壁纸后服务端壁纸文件内容必然变化 → 指纹必然不同；读取失败返回 0（不参与比对）。
     */
    public static long getSystemWallpaperFingerprint(Context context) {
        android.os.ParcelFileDescriptor pfd = readSystemWallpaperPfd(context);
        if (pfd == null) {
            return 0;
        }
        try {
            long size = pfd.getStatSize();
            java.io.FileInputStream fis = new java.io.FileInputStream(pfd.getFileDescriptor());
            long h = 1125899906842597L;
            byte[] buf = new byte[16384];
            long total = 0;
            int n = fis.read(buf);
            if (n > 0) {
                total = n;
                for (int i = 0; i < n; i++) {
                    h = h * 31 + buf[i];
                }
            }
            // 文件总大小（getStatSize 走 fstat，对 binder 壁纸 fd 可靠；避免 skip 超长在部分 ROM 抛 EINVAL）
            h = h * 31 + size;
            return h;
        } catch (Throwable t) {
            android.util.Log.w("WallpaperDebug", "壁纸指纹计算失败: " + t.getMessage());
            return 0;
        } finally {
            try {
                pfd.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static volatile long lastFingerprint = 0;
    private static volatile boolean fingerprintInitialized = false;

    /**
     * 主动探测：系统壁纸是否已变化（与上次指纹对比）。
     * 前台周期轮询调用，不依赖系统广播/颜色回调（小米等 ROM 上两者均可能不可靠）。
     * 读取失败返回 false（不误判）。
     */
    public static boolean hasSystemWallpaperChanged(Context context) {
        long f = getSystemWallpaperFingerprint(context);
        return f != 0 && wallpaperFingerprintChanged(f);
    }

    /** 指纹变化判定（内部缓存对比）；首轮仅初始化不判变。 */
    public static boolean wallpaperFingerprintChanged(long fingerprint) {
        synchronized (AppWallpaperManager.class) {
            if (!fingerprintInitialized) {
                lastFingerprint = fingerprint;
                fingerprintInitialized = true;
                return false;
            }
            if (fingerprint != lastFingerprint) {
                lastFingerprint = fingerprint;
                return true;
            }
        }
        return false;
    }

    /** 当前系统壁纸是否为动态壁纸（WallpaperService 渲染，无静态壁纸文件） */
    public static boolean isLiveWallpaper(Context context) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(context);
            if (wm == null) {
                return false;
            }
            // getWallpaperInfo() 对动态壁纸返回非 null（API 34 起 deprecated 但仍可用）
            Object info = wm.getWallpaperInfo();
            return info != null;
        } catch (Throwable t) {
            android.util.Log.w("WallpaperDebug", "动态壁纸检测失败: " + t.getMessage());
            return false;
        }
    }

    private static volatile long lastWallpaperRefreshMs = 0;
    private static final long WALLPAPER_REFRESH_COOLDOWN_MS = 60000L;

    /** 是否处于重建冷却期（指纹探测发现变化后，短时间内不再重建，防轮播/动态壁纸频繁重建闪烁） */
    public static boolean isWallpaperRefreshCoolingDown() {
        return android.os.SystemClock.elapsedRealtime() - lastWallpaperRefreshMs < WALLPAPER_REFRESH_COOLDOWN_MS;
    }

    /** 标记一次壁纸重建（进入冷却） */
    public static void markWallpaperRefreshed() {
        lastWallpaperRefreshMs = android.os.SystemClock.elapsedRealtime();
    }

    /** 当前壁纸 drawable（已叠加暗化遮罩）；模式关闭或获取失败返回 null */
    public static Drawable getWallpaperDrawable(Context context) {
        int mode = getMode(context);
        if (mode == MODE_OFF) {
            return null;
        }
        Drawable base = null;
        if (mode == MODE_FOLLOW_SYSTEM) {
            // 文件直读优先（getWallpaperFile/binder 直连），避免客户端 getDrawable 缓存返回旧壁纸；
            // 全部失败回退内置默认壁纸（猫和老鼠），保证页面始终有壁纸背景
            base = readSystemWallpaperDrawable(context);
            if (base == null) {
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

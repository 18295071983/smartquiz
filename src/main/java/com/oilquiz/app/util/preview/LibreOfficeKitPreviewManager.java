package com.oilquiz.app.util.preview;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.Log;

import com.oilquiz.app.infra.AppLogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LibreOfficeKit 预览管理器
 * 基于 LibreOffice 开源库的文档渲染方案
 */
public class LibreOfficeKitPreviewManager {
    private static final String TAG = "LibreOfficeKitPreviewManager";
    private static LibreOfficeKitPreviewManager instance;
    
    private Context context;
    private ExecutorService executorService;
    private boolean isInitialized = false;
    
    // LibreOfficeKit 接口（使用反射避免类加载时崩溃）
    private Object office = null;
    private Object document = null;
    private Class<?> libreOfficeKitClass = null;
    private Class<?> officeClass = null;
    private Class<?> documentClass = null;
    
    private LibreOfficeKitPreviewManager(Context context) {
        this.context = context.getApplicationContext();
        this.executorService = Executors.newSingleThreadExecutor();
    }
    
    public static synchronized LibreOfficeKitPreviewManager getInstance(Context context) {
        if (instance == null) {
            instance = new LibreOfficeKitPreviewManager(context);
        }
        return instance;
    }
    
    /**
     * 初始化 LibreOfficeKit
     * @return true 如果初始化成功
     */
    public boolean initialize() {
        try {
            // 尝试加载库
            System.loadLibrary("lo-native-code");
            
            // 动态加载 LibreOfficeKit 相关类
            libreOfficeKitClass = Class.forName("org.libreoffice.kit.LibreOfficeKit");
            officeClass = Class.forName("org.libreoffice.kit.Office");
            documentClass = Class.forName("org.libreoffice.kit.Document");
            
            // 将 LO 运行资源(program/share/unpack)从 assets 解压到 dataDir，
            // 供 lo-bootstrap/UNO 读取（与 LO 安卓官方 App 的 Bootstrap 一致，否则报
            // "Cannot open uno ini file:///assets/program/unorc" / "program/udkapi.rdb: no such file"）
            extractRuntimeAssets(context);

            // 初始化 LibreOfficeKit
            if (context instanceof android.app.Activity) {
                Method initMethod = libreOfficeKitClass.getMethod("init", android.app.Activity.class);
                initMethod.invoke(null, context);
                
                // 创建 Office 实例
                Method getHandleMethod = libreOfficeKitClass.getMethod("getLibreOfficeKitHandle");
                Object handle = getHandleMethod.invoke(null);
                
                // 调用 Office 构造函数
                java.lang.reflect.Constructor<?> officeConstructor = officeClass.getConstructor(java.nio.ByteBuffer.class);
                office = officeConstructor.newInstance(handle);
                
                isInitialized = true;
                AppLogger.i(TAG, "LibreOfficeKit 初始化成功");
                return true;
            } else {
                AppLogger.e(TAG, "Context is not an Activity");
                return false;
            }
        } catch (Throwable t) {
            // 注意：System.loadLibrary 缺失时抛 UnsatisfiedLinkError（属于 Error 而非 Exception），
            // 必须捕获 Throwable，否则缺失原生库时 initialize() 会直接崩溃。
            AppLogger.e(TAG, "LibreOfficeKit 初始化错误: " + t.getMessage(), t);
            isInitialized = false;
            return false;
        }
    }

    /**
     * 用指定的 Activity 初始化 LibreOfficeKit。
     * 单例在构造时固化了 application context（导致 initialize() 里 context instanceof Activity 恒为 false），
     * 本方法用传入的 Activity 重新绑定 context，让 LibreOfficeKit.init() 能拿到真正的 Activity。
     * 由预览 Activity 调用；之后调用 openDocument/renderPage。
     */
    public synchronized boolean initialize(android.app.Activity activity) {
        if (activity != null) {
            this.context = activity;
        }
        return initialize();
    }
    
    /**
     * 打开文档
     * @param filePath 文件路径
     * @return true 如果成功打开
     */
    public boolean openDocument(String filePath) {
        if (!isInitialized) {
            if (!initialize()) {
                return false;
            }
        }
        
        try {
            // 关闭之前的文档
            closeDocument();
            
            // 打开新文档
            Method documentLoadMethod = officeClass.getMethod("documentLoad", String.class);
            document = documentLoadMethod.invoke(office, filePath);
            if (document == null) {
                Method getErrorMethod = officeClass.getMethod("getError");
                String error = (String) getErrorMethod.invoke(office);
                AppLogger.e(TAG, "打开文档失败: " + filePath + "，错误: " + error);
                return false;
            }
            
            // 初始化渲染
            Method initializeForRenderingMethod = documentClass.getMethod("initializeForRendering");
            initializeForRenderingMethod.invoke(document);
            
            AppLogger.i(TAG, "文档打开成功: " + filePath);
            return true;
        } catch (Exception e) {
            AppLogger.e(TAG, "打开文档错误: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 渲染文档页面
     * @param pageIndex 页面索引（从0开始）
     * @param width 目标宽度
     * @param height 目标高度
     * @return 渲染后的 Bitmap
     */
    public Bitmap renderPage(int pageIndex, int width, int height) {
        if (!isInitialized || document == null) {
            AppLogger.e(TAG, "LibreOfficeKit 未初始化或文档未打开");
            return null;
        }
        
        try {
            // 设置页面
            Method setPartMethod = documentClass.getMethod("setPart", int.class);
            setPartMethod.invoke(document, pageIndex);

            // 获取文档页面真实尺寸（文档坐标）
            Method getDocumentWidthMethod = documentClass.getMethod("getDocumentWidth");
            long documentWidth = (Long) getDocumentWidthMethod.invoke(document);
            Method getDocumentHeightMethod = documentClass.getMethod("getDocumentHeight");
            long documentHeight = (Long) getDocumentHeightMethod.invoke(document);

            // 按页面真实纵横比计算渲染尺寸，避免整页被拉伸/压缩变形
            float pageAspect = (documentWidth > 0) ? (float) documentHeight / documentWidth : 1f;
            int renderW, renderH;
            if (width * pageAspect <= height) {
                renderW = width;
                renderH = (int) Math.round(width * pageAspect);
            } else {
                renderH = height;
                renderW = (int) Math.round(height / pageAspect);
            }
            if (renderW <= 0 || renderH <= 0) {
                renderW = width;
                renderH = height;
            }

            // 创建 ByteBuffer
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(renderW * renderH * 4);
            if (buffer == null) {
                AppLogger.e(TAG, "创建缓冲区失败");
                return null;
            }

            // 渲染页面（整页映射到 aspect-correct 的缓冲区）
            Method paintTileMethod = documentClass.getMethod("paintTile", java.nio.ByteBuffer.class, int.class, int.class, int.class, int.class, int.class, int.class);
            paintTileMethod.invoke(document, buffer, renderW, renderH, 0, 0, (int) documentWidth, (int) documentHeight);

            // 创建 Bitmap
            Bitmap bitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);

            AppLogger.d(TAG, "页面渲染成功: " + pageIndex + " (" + renderW + "x" + renderH + ")");
            return bitmap;
        } catch (Exception e) {
            AppLogger.e(TAG, "渲染页面错误: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 获取文档总页数
     * @return 总页数
     */
    public int getPageCount() {
        if (!isInitialized || document == null) {
            return 0;
        }
        
        try {
            Method getPartsMethod = documentClass.getMethod("getParts");
            return (Integer) getPartsMethod.invoke(document);
        } catch (Exception e) {
            AppLogger.e(TAG, "获取页数错误: " + e.getMessage(), e);
            return 0;
        }
    }
    
    /**
     * 获取页面尺寸
     * @param pageIndex 页面索引
     * @return 页面尺寸矩形
     */
    public Rect getPageSize(int pageIndex) {
        if (!isInitialized || document == null) {
            return null;
        }
        
        try {
            // 设置页面
            Method setPartMethod = documentClass.getMethod("setPart", int.class);
            setPartMethod.invoke(document, pageIndex);
            
            // 获取页面尺寸
            Method getDocumentWidthMethod = documentClass.getMethod("getDocumentWidth");
            long width = (Long) getDocumentWidthMethod.invoke(document);
            Method getDocumentHeightMethod = documentClass.getMethod("getDocumentHeight");
            long height = (Long) getDocumentHeightMethod.invoke(document);
            
            return new Rect(0, 0, (int) width, (int) height);
        } catch (Exception e) {
            AppLogger.e(TAG, "获取页面尺寸错误: " + e.getMessage(), e);
        }
        return null;
    }
    
    /**
     * 获取文档类型（DOCTYPE_TEXT=0, DOCTYPE_SPREADSHEET=1, DOCTYPE_PRESENTATION=2, DOCTYPE_DRAWING=3, DOCTYPE_OTHER=4）
     * @return 文档类型常量，失败返回 -1
     */
    public int getDocumentType() {
        if (!isInitialized || document == null) {
            return -1;
        }
        try {
            Method m = documentClass.getMethod("getDocumentType");
            return (Integer) m.invoke(document);
        } catch (Exception e) {
            AppLogger.e(TAG, "获取文档类型错误: " + e.getMessage(), e);
            return -1;
        }
    }

    /**
     * 获取指定 part 的名字（工作表名/幻灯片名/页面名）
     * @param index part 索引（从 0 开始）
     * @return part 名，失败返回 null
     */
    public String getPartName(int index) {
        if (!isInitialized || document == null) {
            return null;
        }
        try {
            Method m = documentClass.getMethod("getPartName", int.class);
            return (String) m.invoke(document, index);
        } catch (Exception e) {
            AppLogger.e(TAG, "获取 part 名错误: " + e.getMessage(), e);
            return null;
        }
    }

    /** 获取当前文档宽度（文档单位）。 */
    public int getDocumentWidth() {
        if (!isInitialized || document == null) {
            return 0;
        }
        try {
            Method m = documentClass.getMethod("getDocumentWidth");
            return ((Long) m.invoke(document)).intValue();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 获取当前文档高度（文档单位）。 */
    public int getDocumentHeight() {
        if (!isInitialized || document == null) {
            return 0;
        }
        try {
            Method m = documentClass.getMethod("getDocumentHeight");
            return ((Long) m.invoke(document)).intValue();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 按整表真实纵横比渲染整个 part（工作表/页），并受高度与像素上限约束，避免压缩变形。
     * @param pageIndex    part 索引
     * @param desiredWidth 期望渲染宽度(px)
     * @param maxHeight    最大高度(px)
     * @param maxPixels    最大像素数（内存约束）
     * @return 渲染出的 Bitmap（纵横比=版面真实比例），失败返回 null
     */
    public Bitmap renderPartBounded(int pageIndex, int desiredWidth, int maxHeight, long maxPixels) {
        if (!isInitialized || document == null) {
            return null;
        }
        try {
            // 切换 part
            Method setPartMethod = documentClass.getMethod("setPart", int.class);
            setPartMethod.invoke(document, pageIndex);

            int docW = getDocumentWidth();
            int docH = getDocumentHeight();
            if (docW <= 0) docW = 1;
            if (docH <= 0) docH = 1;
            float aspect = (float) docH / docW;

            int rw = Math.max(desiredWidth, 400);
            int rh = (int) Math.round(rw * aspect);
            if (rh > maxHeight) {
                rh = maxHeight;
                rw = Math.max((int) Math.round(rh / aspect), 300);
            }
            // 像素上限约束：超出则逐步降分辨率
            while ((long) rw * rh > maxPixels && rw > 300) {
                rw = (int) (rw * 0.8f);
                rh = (int) Math.round(rw * aspect);
            }
            if (rw < 300) {
                rw = 300;
                rh = (int) Math.round(rw * aspect);
            }

            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(rw * rh * 4);
            if (buffer == null) {
                AppLogger.e(TAG, "创建缓冲区失败");
                return null;
            }
            Method paintTileMethod = documentClass.getMethod("paintTile",
                    java.nio.ByteBuffer.class, int.class, int.class, int.class, int.class, int.class, int.class);
            paintTileMethod.invoke(document, buffer, rw, rh, 0, 0, docW, docH);

            Bitmap bitmap = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            AppLogger.d(TAG, "整表part渲染成功: " + pageIndex + " (" + rw + "x" + rh + ")");
            return bitmap;
        } catch (Exception e) {
            AppLogger.e(TAG, "整表part渲染错误: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 渲染指定区域（用于连续文档的条带渲染）。
     * @param width  目标位图宽度(px)
     * @param height 目标位图高度(px)
     * @param offsetX 文档坐标 X 偏移
     * @param offsetY 文档坐标 Y 偏移
     * @param tileWidth  文档坐标区域宽度
     * @param tileHeight 文档坐标区域高度
     * @return 渲染出的 Bitmap，失败返回 null
     */
    public Bitmap renderRegion(int width, int height, int offsetX, int offsetY, int tileWidth, int tileHeight) {
        if (!isInitialized || document == null) {
            AppLogger.e(TAG, "LibreOfficeKit 未初始化或文档未打开");
            return null;
        }
        try {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(width * height * 4);
            if (buffer == null) {
                AppLogger.e(TAG, "创建缓冲区失败");
                return null;
            }
            Method paintTileMethod = documentClass.getMethod("paintTile",
                    java.nio.ByteBuffer.class, int.class, int.class, int.class, int.class, int.class, int.class);
            paintTileMethod.invoke(document, buffer, width, height, offsetX, offsetY, tileWidth, tileHeight);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            return bitmap;
        } catch (Exception e) {
            AppLogger.e(TAG, "渲染区域错误: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 设置 part 模式（PPT：PART_MODE_SLIDE=0 幻灯片 / PART_MODE_NOTES=1 备注）。
     */
    public void setPartMode(int mode) {
        if (!isInitialized || document == null) {
            return;
        }
        try {
            Method m = documentClass.getMethod("setPartMode", int.class);
            m.invoke(document, mode);
            AppLogger.i(TAG, "setPartMode: " + mode);
        } catch (Exception e) {
            AppLogger.w(TAG, "setPartMode 失败: " + e.getMessage());
        }
    }

    /**
     * 设置客户端缩放（影响 LO 渲染尺度，用于清晰度）。
     */
    public void setClientZoom(int zoomX, int zoomY, int offsetX, int offsetY) {
        if (!isInitialized || document == null) {
            return;
        }
        try {
            Method m = documentClass.getMethod("setClientZoom", int.class, int.class, int.class, int.class);
            m.invoke(document, zoomX, zoomY, offsetX, offsetY);
            AppLogger.i(TAG, "setClientZoom: " + zoomX + "x" + zoomY);
        } catch (Exception e) {
            AppLogger.w(TAG, "setClientZoom 失败: " + e.getMessage());
        }
    }

    /**
     * 获取当前 part 内各页面矩形（返回原始字符串，供分页参考）。失败返回 null。
     */
    public String getPartPageRectangles() {
        if (!isInitialized || document == null) {
            return null;
        }
        try {
            Method m = documentClass.getMethod("getPartPageRectangles");
            return (String) m.invoke(document);
        } catch (Exception e) {
            AppLogger.w(TAG, "getPartPageRectangles 失败: " + e.getMessage());
            return null;
        }
    }

    /** 尝试从页面矩形字符串解析页码数（JSON 数组计数），失败返回 0。 */
    public int getPartPageCount() {
        String s = getPartPageRectangles();
        if (s == null || s.isEmpty()) {
            return 0;
        }
        AppLogger.d(TAG, "getPartPageRectangles: " + s);
        // LibreOfficeKit 返回 JSON 数组，如 [{"x":..,"y":..,"w":..,"h":..}, ...]
        int count = 0;
        int idx = s.indexOf('{');
        while (idx >= 0) {
            count++;
            idx = s.indexOf('{', idx + 1);
        }
        return count;
    }

    /**
     * 注册文档消息回调（用动态代理实现 Document.MessageCallback，记录 LO 消息，如失效/光标/进度）。
     */
    public void setMessageCallback() {
        if (!isInitialized || document == null) {
            return;
        }
        try {
            Class<?> cbClass = Class.forName("org.libreoffice.kit.Document$MessageCallback");
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    cbClass.getClassLoader(), new Class<?>[]{cbClass},
                    (p, method, args) -> {
                        if ("message".equals(method.getName()) && args != null && args.length >= 2) {
                            AppLogger.d(TAG, "LO消息: type=" + args[0] + " data=" + args[1]);
                        }
                        return null;
                    });
            Method m = documentClass.getMethod("setMessageCallback", cbClass);
            m.invoke(document, proxy);
            AppLogger.i(TAG, "文档消息回调已注册");
        } catch (Exception e) {
            AppLogger.w(TAG, "setMessageCallback 失败: " + e.getMessage());
        }
    }

    /** 当前 part 索引。 */
    public int getPart() {
        if (!isInitialized || document == null) {
            return -1;
        }
        try {
            Method m = documentClass.getMethod("getPart");
            return (Integer) m.invoke(document);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 切换到指定 part（工作表/页/幻灯片）。 */
    public void setPart(int index) {
        if (!isInitialized || document == null) {
            return;
        }
        try {
            Method m = documentClass.getMethod("setPart", int.class);
            m.invoke(document, index);
        } catch (Exception e) {
            AppLogger.w(TAG, "setPart 失败: " + e.getMessage());
        }
    }

    /** 获取指定 part 的尺寸（文档单位）。返回 {宽, 高}，失败返回 null。 */
    public int[] getPartSize(int index) {
        if (!isInitialized || document == null) {
            return null;
        }
        try {
            Method setPartMethod = documentClass.getMethod("setPart", int.class);
            setPartMethod.invoke(document, index);
            int w = getDocumentWidth();
            int h = getDocumentHeight();
            if (w > 0 && h > 0) {
                return new int[]{w, h};
            }
        } catch (Exception e) {
            AppLogger.w(TAG, "getPartSize 失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 关闭文档
     */
    public void closeDocument() {
        if (document != null) {
            try {
                Method destroyMethod = documentClass.getMethod("destroy");
                destroyMethod.invoke(document);
                document = null;
                AppLogger.i(TAG, "文档已关闭");
            } catch (Exception e) {
                AppLogger.e(TAG, "关闭文档错误: " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * 释放资源
     */
    public void release() {
        closeDocument();
        
        if (office != null) {
            try {
                Method destroyMethod = officeClass.getMethod("destroy");
                destroyMethod.invoke(office);
                office = null;
                AppLogger.i(TAG, "LibreOfficeKit 资源已释放");
            } catch (Exception e) {
                AppLogger.e(TAG, "释放资源错误: " + e.getMessage(), e);
            }
        }
        
        isInitialized = false;
    }
    
    /**
     * 检查 LibreOfficeKit 是否可用
     * @return true 如果可用
     */
    public boolean isAvailable() {
        try {
            System.loadLibrary("lo-native-code");
            return true;
        } catch (Throwable t) {
            AppLogger.w(TAG, "LibreOfficeKit 库不可用: " + t.getMessage());
            return false;
        }
    }
    
    /**
     * 检查是否已初始化
     * @return true 如果已初始化
     */
    public boolean isInitialized() {
        return isInitialized;
    }
    
    // 不再需要原生方法声明，使用反射调用

    /**
     * 把 LO 运行资源(program/share/unpack)从 assets 解压到 dataDir。
     * LO 安卓版需要这些文件位于 dataDir 下，UNO/bootstrap 才会启动；
     * 已解压过(存在 program/udkapi.rdb)则跳过，避免每次重复拷贝 38MB。
     */
    private void extractRuntimeAssets(Context context) {
        if (context == null) {
            return;
        }
        try {
            File dataDir = new File(context.getApplicationInfo().dataDir);
            File programFile = new File(dataDir, "program/udkapi.rdb");
            if (programFile.exists()) {
                return; // 已解压
            }
            AssetManager am = context.getAssets();
            // LO 安卓版 dataDir 布局为合并式：
            //   dataDir/program = assets/program/* + assets/unpack/program/*（unorc/services.rdb + offapi/udkapi/sofficerc）
            //   dataDir/share   = assets/share/*
            //   dataDir/etc, dataDir/user = assets/unpack/etc, assets/unpack/user
            copyAssetDir(am, "share", new File(dataDir, "share"));
            copyAssetDir(am, "unpack", dataDir);            // unpack/{program,etc,user} -> dataDir/{program,etc,user}
            copyAssetDir(am, "program", new File(dataDir, "program")); // 叠加 unorc/services.rdb 等到 dataDir/program
            AppLogger.i(TAG, "LibreOffice 运行资源已解压到 " + dataDir.getAbsolutePath());
        } catch (Throwable t) {
            AppLogger.e(TAG, "解压 LO 运行资源失败: " + t.getMessage(), t);
        }
    }

    private void copyAssetDir(AssetManager am, String assetPath, File destDir) {
        try {
            if (!destDir.exists()) {
                destDir.mkdirs();
            }
            String[] children = am.list(assetPath);
            if (children == null) {
                return;
            }
            for (String child : children) {
                String childAsset = assetPath.endsWith("/") ? assetPath + child : assetPath + "/" + child;
                String[] sub = am.list(childAsset);
                if (sub != null && sub.length > 0) {
                    copyAssetDir(am, childAsset, new File(destDir, child));
                } else {
                    copyAssetFile(am, childAsset, new File(destDir, child));
                }
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "拷贝资源目录失败: " + assetPath + " - " + t.getMessage(), t);
        }
    }

    private void copyAssetFile(AssetManager am, String assetPath, File out) {
        try {
            if (out.getParentFile() != null) {
                out.getParentFile().mkdirs();
            }
            try (InputStream is = am.open(assetPath); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    os.write(buf, 0, n);
                }
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "拷贝资源文件失败: " + assetPath + " - " + t.getMessage(), t);
        }
    }
}

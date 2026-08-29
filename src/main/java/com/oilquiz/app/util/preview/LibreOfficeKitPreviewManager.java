package com.oilquiz.app.util.preview;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Rect;

import com.oilquiz.app.infra.AppLogger;

import org.libreoffice.kit.Document;
import org.libreoffice.kit.LibreOfficeKit;
import org.libreoffice.kit.Office;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * LibreOfficeKit 预览管理器 —— 使用官方 org.libreoffice.kit 绑定（LibreOfficeKit/Office/Document）。
 * 直接驱动 liblo-native-code.so 渲染，替换之前的反射调用。
 */
public class LibreOfficeKitPreviewManager {
    private static final String TAG = "LibreOfficeKitPreviewManager";
    private static LibreOfficeKitPreviewManager instance;

    private Context context;
    private boolean isInitialized = false;

    private Office office = null;
    private Document document = null;

    private LibreOfficeKitPreviewManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized LibreOfficeKitPreviewManager getInstance(Context context) {
        if (instance == null) {
            instance = new LibreOfficeKitPreviewManager(context);
        }
        return instance;
    }

    /**
     * 初始化 LibreOfficeKit（使用官方 LibreOfficeKit.init 绑定）。
     */
    public boolean initialize() {
        try {
            // 确保 native 库加载（LibreOfficeKit 静态块会加载 NSS/SSL + lo-native-code）
            System.loadLibrary("lo-native-code");

            // 将 LO 运行资源(program/share/unpack)从 assets 解压到 dataDir，
            // 供 lo-bootstrap/UNO 读取（否则报 uno ini / udkapi.rdb 缺失）
            extractRuntimeAssets(context);

            // 官方入口：初始化 JNI 端
            if (context instanceof android.app.Activity) {
                LibreOfficeKit.init((android.app.Activity) context);
                office = new Office(LibreOfficeKit.getLibreOfficeKitHandle());
                isInitialized = true;
                AppLogger.i(TAG, "LibreOfficeKit 初始化成功(官方绑定)");
                return true;
            } else {
                AppLogger.e(TAG, "Context is not an Activity");
                return false;
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "LibreOfficeKit 初始化错误: " + t.getMessage(), t);
            isInitialized = false;
            return false;
        }
    }

    /**
     * 用指定的 Activity 初始化 LibreOfficeKit。
     */
    public synchronized boolean initialize(android.app.Activity activity) {
        if (activity != null) {
            this.context = activity;
        }
        return initialize();
    }

    /**
     * 打开文档。
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

            document = office.documentLoad(filePath);
            if (document == null) {
                // 首次失败，尝试重建 Office 再载（官方做法）
                String err = office.getError();
                AppLogger.e(TAG, "documentLoad 返回 null，错误: " + err);
                office.destroy();
                office = new Office(LibreOfficeKit.getLibreOfficeKitHandle());
                document = office.documentLoad(filePath);
            }
            if (document == null) {
                AppLogger.e(TAG, "打开文档失败: " + filePath + "，错误: " + office.getError());
                return false;
            }

            document.initializeForRendering();
            AppLogger.i(TAG, "文档打开成功(官方绑定): " + filePath);
            return true;
        } catch (Throwable t) {
            AppLogger.e(TAG, "打开文档错误: " + t.getMessage(), t);
            return false;
        }
    }

    /**
     * 渲染文档单个 part（页/工作表/幻灯片）。按版面真实纵横比计算渲染尺寸，避免拉伸。
     */
    public Bitmap renderPage(int pageIndex, int width, int height) {
        if (!isInitialized || document == null) {
            AppLogger.e(TAG, "LibreOfficeKit 未初始化或文档未打开");
            return null;
        }
        try {
            document.setPart(pageIndex);

            int docW = (int) document.getDocumentWidth();
            int docH = (int) document.getDocumentHeight();
            if (docW <= 0) docW = 1;
            if (docH <= 0) docH = 1;
            float pageAspect = (float) docH / docW;

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

            ByteBuffer buffer = ByteBuffer.allocateDirect(renderW * renderH * 4);
            document.paintTile(buffer, renderW, renderH, 0, 0, docW, docH);

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
     * 渲染指定区域（瓦片/条带）。区域坐标为文档单位。
     */
    public Bitmap renderRegion(int width, int height, int offsetX, int offsetY, int tileW, int tileH) {
        if (!isInitialized || document == null) {
            AppLogger.e(TAG, "LibreOfficeKit 未初始化或文档未打开");
            return null;
        }
        try {
            ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
            document.paintTile(buffer, width, height, offsetX, offsetY, tileW, tileH);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            return bitmap;
        } catch (Exception e) {
            AppLogger.e(TAG, "渲染区域错误: " + e.getMessage(), e);
            return null;
        }
    }

    /** 获取总页数/工作表数/幻灯片数。 */
    public int getPageCount() {
        return (isInitialized && document != null) ? document.getParts() : 0;
    }

    /** 获取文档类型（DOCTYPE_TEXT=0, SPREADSHEET=1, PRESENTATION=2, DRAWING=3, OTHER=4）。 */
    public int getDocumentType() {
        return (isInitialized && document != null) ? document.getDocumentType() : -1;
    }

    /** 获取指定 part 名（工作表/页/幻灯片名）。 */
    public String getPartName(int index) {
        return (isInitialized && document != null) ? document.getPartName(index) : null;
    }

    /** 当前 part 索引。 */
    public int getPart() {
        return (isInitialized && document != null) ? document.getPart() : -1;
    }

    /** 切换到指定 part。 */
    public void setPart(int index) {
        if (isInitialized && document != null) {
            document.setPart(index);
        }
    }

    /** 切换 part 模式（PPT：0 幻灯片 / 1 备注）。 */
    public void setPartMode(int mode) {
        if (isInitialized && document != null) {
            document.setPartMode(mode);
        }
    }

    /** 获取指定 part 尺寸（文档单位）。返回 {宽, 高}，失败返回 null。 */
    public int[] getPartSize(int index) {
        if (!isInitialized || document == null) {
            return null;
        }
        try {
            document.setPart(index);
            int w = (int) document.getDocumentWidth();
            int h = (int) document.getDocumentHeight();
            if (w > 0 && h > 0) {
                return new int[]{w, h};
            }
        } catch (Exception e) {
            AppLogger.w(TAG, "getPartSize 失败: " + e.getMessage());
        }
        return null;
    }

    /** 获取当前文档宽度（文档单位）。 */
    public int getDocumentWidth() {
        return (isInitialized && document != null) ? (int) document.getDocumentWidth() : 0;
    }

    /** 获取当前文档高度（文档单位）。 */
    public int getDocumentHeight() {
        return (isInitialized && document != null) ? (int) document.getDocumentHeight() : 0;
    }

    /** 设置客户端缩放。 */
    public void setClientZoom(int zoomX, int zoomY, int offsetX, int offsetY) {
        if (isInitialized && document != null) {
            document.setClientZoom(zoomX, zoomY, offsetX, offsetY);
        }
    }

    /** 获取当前 part 内页面矩形（原始字符串）。 */
    public String getPartPageRectangles() {
        return (isInitialized && document != null) ? document.getPartPageRectangles() : null;
    }

    /** 从页面矩形字符串解析页码数。 */
    public int getPartPageCount() {
        String s = getPartPageRectangles();
        if (s == null || s.isEmpty()) {
            return 0;
        }
        AppLogger.d(TAG, "getPartPageRectangles: " + s);
        int count = 0;
        int idx = s.indexOf('{');
        while (idx >= 0) {
            count++;
            idx = s.indexOf('{', idx + 1);
        }
        return count;
    }

    /** 注册文档消息回调（官方 Document.MessageCallback）。 */
    public void setMessageCallback() {
        if (isInitialized && document != null) {
            document.setMessageCallback(new Document.MessageCallback() {
                @Override
                public void messageRetrieved(int signalNumber, String payload) {
                    AppLogger.d(TAG, "LO消息: type=" + signalNumber + " payload=" + payload);
                }
            });
            AppLogger.i(TAG, "文档消息回调已注册(官方)");
        }
    }

    /** 关闭文档。 */
    public void closeDocument() {
        if (document != null) {
            try {
                document.destroy();
                document = null;
                AppLogger.i(TAG, "文档已关闭");
            } catch (Exception e) {
                AppLogger.e(TAG, "关闭文档错误: " + e.getMessage(), e);
            }
        }
    }

    /** 释放资源。 */
    public void release() {
        closeDocument();
        if (office != null) {
            try {
                office.destroy();
                office = null;
                AppLogger.i(TAG, "LibreOfficeKit 资源已释放");
            } catch (Exception e) {
                AppLogger.e(TAG, "释放资源错误: " + e.getMessage(), e);
            }
        }
        isInitialized = false;
    }

    /** 检查 LibreOfficeKit 是否可用。 */
    public boolean isAvailable() {
        try {
            System.loadLibrary("lo-native-code");
            return true;
        } catch (Throwable t) {
            AppLogger.w(TAG, "LibreOfficeKit 库不可用: " + t.getMessage());
            return false;
        }
    }

    /** 检查是否已初始化。 */
    public boolean isInitialized() {
        return isInitialized;
    }

    /** 获取页面尺寸矩形（文档单位）。 */
    public Rect getPageSize(int pageIndex) {
        if (!isInitialized || document == null) {
            return null;
        }
        int[] sz = getPartSize(pageIndex);
        return sz != null ? new Rect(0, 0, sz[0], sz[1]) : null;
    }

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

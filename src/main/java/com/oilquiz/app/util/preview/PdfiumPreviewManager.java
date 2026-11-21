package com.oilquiz.app.util.preview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.oilquiz.app.infra.AppLogger;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 预览管理器（系统 {@link PdfRenderer} 渲染 + PDFBox 元信息）。
 *
 * <p><b>PDFIUM-REMOVED(2026-11-20)</b>：原来用 {@code com.github.barteksc:pdfium-android:1.9.0}
 * （GCC 4.9 / 2016 年的构建），它带来的 {@code libjniPdfium.so} + {@code libmodpdfium.so} +
 * {@code libmodft2.so} + {@code libmodpng.so} 的 LOAD 段全是 4 KB 对齐，16 KB 页设备上加载失败。
 * 维护分支 {@code com.github.mhiew:pdfium-android:1.9.2} 实测仍是 4 KB，所以整体换成平台
 * {@link PdfRenderer}（API 21+，零 native 依赖）。</p>
 *
 * <p><b>PDF-META-BACKFILL(2026-11-20)</b>：PdfRenderer 没有「文档元信息 / 加密判定」的 API，
 * 这两项用已在依赖里的 pure-Java {@code pdfbox-android}（{@code com.tom_roush.pdfbox}）补回来：
 * <ul>
 *   <li>{@link #getDocumentMeta()}：标题/作者/主题/创建者/生产者/页数/是否加密；</li>
 *   <li>{@link #getLastError()}：打开失败时给出人类可读原因，能区分「PDF 已加密」与「文件损坏」。</li>
 * </ul>
 * PDFBox 解析有内存/耗时成本，所以只在「打开失败」和「显式取元信息」时用，且设了 {@link #MAX_META_BYTES}
 * 上限；渲染主路径不碰 PDFBox。</p>
 *
 * <p>线程约定：{@link PdfRenderer} 不是线程安全的，所有涉及渲染的方法都加了 {@code synchronized}。</p>
 */
public class PdfiumPreviewManager {
    private static final String TAG = "PdfiumPreviewManager";

    /** 元信息解析的体积上限（超过就不解析，避免 OOM / 卡顿） */
    private static final long MAX_META_BYTES = 80L * 1024 * 1024;

    private final Context context;
    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor fileDescriptor;
    private int pageCount = 0;
    private String currentFilePath;

    // 元信息/错误提示用（PDFBox 需要原始来源，渲染本身只用 fd）
    private File currentFile;
    private Uri currentUri;
    private String lastError;
    private PdfMeta pdfMeta;

    public PdfiumPreviewManager(Context context) {
        this.context = context.getApplicationContext();
        try {
            // pdfbox-android 需要先初始化资源加载器（字体/icc 等），否则解析时可能抛异常
            PDFBoxResourceLoader.init(this.context);
        } catch (Throwable t) {
            AppLogger.e(TAG, "PDFBox 资源初始化失败（元信息将不可用）: " + t.getMessage(), t);
        }
    }

    /**
     * 通过 SAF 的 content:// Uri 直接打开 PDF（无需解析真实路径）。
     * Android 10+ 分区存储下 MediaStore DATA 列已废弃，SAF 返回的 Uri 没有真实文件路径，
     * 正确做法是用 ContentResolver.openFileDescriptor 直接拿 ParcelFileDescriptor。
     */
    public synchronized boolean openDocument(Context context, Uri uri) {
        closeDocument();
        resetMeta(null, uri);
        try {
            ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r");
            if (fd == null) {
                lastError = "无法打开 PDF：文件描述符为空";
                AppLogger.e(TAG, "打开 PDF(Uri) 失败: openFileDescriptor 返回 null: " + uri);
                return false;
            }
            return openInternal(fd, uri.toString());
        } catch (Exception e) {
            lastError = describeFailure(e);
            AppLogger.e(TAG, "打开 PDF(Uri) 失败: " + lastError, e);
            return false;
        }
    }

    /**
     * 打开 PDF 文件
     * @param filePath PDF 文件路径
     * @return true 如果成功打开
     */
    public synchronized boolean openDocument(String filePath) {
        closeDocument();
        File file = new File(filePath);
        resetMeta(file, null);
        try {
            if (!file.exists()) {
                lastError = "PDF 文件不存在";
                AppLogger.e(TAG, "PDF 文件不存在: " + filePath);
                return false;
            }
            ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            return openInternal(fd, filePath);
        } catch (Exception e) {
            lastError = describeFailure(e);
            AppLogger.e(TAG, "打开 PDF 失败: " + lastError, e);
            return false;
        }
    }

    private void resetMeta(File file, Uri uri) {
        currentFile = file;
        currentUri = uri;
        lastError = null;
        pdfMeta = null;
    }

    private boolean openInternal(ParcelFileDescriptor fd, String pathOrUri) {
        try {
            pdfRenderer = new PdfRenderer(fd);
            fileDescriptor = fd;
            pageCount = pdfRenderer.getPageCount();
            currentFilePath = pathOrUri;
            lastError = null;
            AppLogger.d(TAG, "PDF 打开成功: " + pathOrUri + ", 页数: " + pageCount);
            return true;
        } catch (Exception e) {
            lastError = describeFailure(e);
            AppLogger.e(TAG, "创建 PdfRenderer 失败: " + lastError, e);
            try { fd.close(); } catch (Exception ignored) { }
            return false;
        }
    }

    /**
     * 把异常翻译成用户能看懂的原因。优先用 PDFBox 判定「是否加密」，判不出来才退化成通用文案。
     */
    private String describeFailure(Exception cause) {
        try {
            PDDocument doc = loadPdfBoxDocument();
            if (doc != null) {
                boolean encrypted = doc.isEncrypted();
                try { doc.close(); } catch (Exception ignored) { }
                if (encrypted) {
                    return "PDF 已加密，需要密码才能打开";
                }
                return "PDF 无法预览（文件可能已损坏或格式不受支持）";
            }
        } catch (InvalidPasswordException e) {
            return "PDF 已加密，需要密码才能打开";
        } catch (Throwable t) {
            AppLogger.e(TAG, "PDFBox 判定失败: " + t.getMessage(), t);
        }
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isEmpty()) {
            return "PDF 无法预览: " + cause.getMessage();
        }
        return "PDF 无法预览";
    }

    /**
     * 用 PDFBox 打开当前文档（用于元信息/加密判定）。超过体积上限直接返回 null。
     */
    private PDDocument loadPdfBoxDocument() throws Exception {
        if (currentFile != null) {
            if (!currentFile.exists() || currentFile.length() > MAX_META_BYTES) {
                return null;
            }
            return PDDocument.load(currentFile);
        }
        if (currentUri != null) {
            try (InputStream in = context.getContentResolver().openInputStream(currentUri)) {
                if (in == null) {
                    return null;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                long total = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_META_BYTES) {
                        return null;
                    }
                    bos.write(buf, 0, n);
                }
                return PDDocument.load(bos.toByteArray());
            }
        }
        return null;
    }

    /**
     * 读取文档元信息（标题/作者/…/页数/是否加密）。
     * <p>PDFBox 解析，属耗时操作：请在工作线程调用；解析不出时返回 null。</p>
     */
    public synchronized PdfMeta getDocumentMeta() {
        if (pdfMeta != null) {
            return pdfMeta;
        }
        PDDocument doc = null;
        try {
            doc = loadPdfBoxDocument();
            if (doc == null) {
                return null;
            }
            PdfMeta meta = new PdfMeta();
            meta.encrypted = doc.isEncrypted();
            meta.pageCount = doc.getNumberOfPages();
            PDDocumentInformation info = doc.getDocumentInformation();
            if (info != null) {
                meta.title = info.getTitle();
                meta.author = info.getAuthor();
                meta.subject = info.getSubject();
                meta.creator = info.getCreator();
                meta.producer = info.getProducer();
            }
            pdfMeta = meta;
            return meta;
        } catch (InvalidPasswordException e) {
            lastError = "PDF 已加密，需要密码才能读取元信息";
            return null;
        } catch (Throwable t) {
            AppLogger.e(TAG, "读取 PDF 元信息失败: " + t.getMessage(), t);
            return null;
        } finally {
            if (doc != null) {
                try { doc.close(); } catch (Exception ignored) { }
            }
        }
    }

    /**
     * 最近一次打开/解析失败的原因（成功时为 null）。
     */
    public synchronized String getLastError() {
        return lastError;
    }

    /**
     * 渲染指定页面为 Bitmap（保持宽高比）。
     * @param pageIndex 页面索引（从0开始）
     * @param width 目标宽度上限
     * @param height 目标高度上限
     * @return 渲染后的 Bitmap；失败返回 null
     */
    public synchronized Bitmap renderPage(int pageIndex, int width, int height) {
        if (pdfRenderer == null || pageIndex < 0 || pageIndex >= pageCount) {
            return null;
        }
        PdfRenderer.Page page = null;
        try {
            page = pdfRenderer.openPage(pageIndex);

            // getWidth/getHeight 返回的是页面在 72dpi 下的点尺寸
            int pageWidth = Math.max(page.getWidth(), 1);
            int pageHeight = Math.max(page.getHeight(), 1);

            float scale = Math.min((float) width / pageWidth, (float) height / pageHeight);
            if (scale <= 0) scale = 1f;

            int bitmapWidth = Math.max((int) (pageWidth * scale), 1);
            int bitmapHeight = Math.max((int) (pageHeight * scale), 1);

            Bitmap bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
            // PdfRenderer 画在透明底上，不先铺白会出现“黑底白字”
            bitmap.eraseColor(Color.WHITE);

            Matrix matrix = new Matrix();
            matrix.postScale(scale, scale);
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

            return bitmap;
        } catch (Exception e) {
            AppLogger.e(TAG, "渲染页面失败(index=" + pageIndex + "): " + e.getMessage(), e);
            return null;
        } finally {
            if (page != null) {
                try { page.close(); } catch (Exception ignored) { }
            }
        }
    }

    /**
     * 获取页面尺寸（单位：点，1/72 英寸；与旧 pdfium 实现的 getPageWidthPoint 同口径）
     * @param pageIndex 页面索引
     * @return RectF 包含页面宽度和高度
     */
    public synchronized RectF getPageSize(int pageIndex) {
        if (pdfRenderer == null || pageIndex < 0 || pageIndex >= pageCount) {
            return null;
        }
        PdfRenderer.Page page = null;
        try {
            page = pdfRenderer.openPage(pageIndex);
            return new RectF(0, 0, page.getWidth(), page.getHeight());
        } catch (Exception e) {
            AppLogger.e(TAG, "获取页面尺寸失败: " + e.getMessage(), e);
            return null;
        } finally {
            if (page != null) {
                try { page.close(); } catch (Exception ignored) { }
            }
        }
    }

    /**
     * 获取所有页面的缩略图
     * @param thumbnailWidth 缩略图宽度
     * @return 缩略图列表
     */
    public synchronized List<Bitmap> getThumbnails(int thumbnailWidth) {
        List<Bitmap> thumbnails = new ArrayList<>();
        for (int i = 0; i < pageCount; i++) {
            RectF size = getPageSize(i);
            if (size != null && size.width() > 0) {
                float scale = thumbnailWidth / size.width();
                int thumbnailHeight = Math.max((int) (size.height() * scale), 1);
                Bitmap thumbnail = renderPage(i, thumbnailWidth, thumbnailHeight);
                if (thumbnail != null) {
                    thumbnails.add(thumbnail);
                }
            }
        }
        return thumbnails;
    }

    /**
     * 获取总页数
     */
    public int getPageCount() {
        return pageCount;
    }

    /**
     * 获取当前文件路径（SAF 打开时是 Uri 字符串）
     */
    public String getCurrentFilePath() {
        return currentFilePath;
    }

    /**
     * 检查是否有打开的文档
     */
    public synchronized boolean hasOpenDocument() {
        return pdfRenderer != null;
    }

    /**
     * 关闭文档（释放 PdfRenderer 与文件描述符）
     */
    public synchronized void closeDocument() {
        if (pdfRenderer != null) {
            try {
                pdfRenderer.close();
                AppLogger.d(TAG, "PDF 文档已关闭");
            } catch (Exception e) {
                AppLogger.e(TAG, "关闭 PDF 文档失败: " + e.getMessage(), e);
            }
            pdfRenderer = null;
        }
        if (fileDescriptor != null) {
            try {
                fileDescriptor.close();
            } catch (Exception ignored) { }
            fileDescriptor = null;
        }
        pageCount = 0;
        currentFilePath = null;
    }

    /**
     * 释放资源
     */
    public void release() {
        closeDocument();
    }

    /**
     * PDF 文档元信息（由 PDFBox 解析；渲染用不到，属于「补全」能力）。
     */
    public static class PdfMeta {
        public String title;
        public String author;
        public String subject;
        public String creator;
        public String producer;
        public int pageCount;
        public boolean encrypted;

        /** 展示用标题：没有 PDF 标题时退回作者/文件名由调用方决定 */
        public String displayTitle() {
            if (title != null && !title.trim().isEmpty()) {
                return title.trim();
            }
            return null;
        }
    }
}

package com.oilquiz.app.util.preview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.oilquiz.app.infra.AppLogger;
import com.shockwave.pdfium.PdfDocument;
import com.shockwave.pdfium.PdfiumCore;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Pdfium 预览管理器
 * 基于 Google Pdfium 核心库的免费开源 PDF 渲染方案
 */
public class PdfiumPreviewManager {
    private static final String TAG = "PdfiumPreviewManager";
    
    private Context context;
    private PdfiumCore pdfiumCore;
    private PdfDocument pdfDocument;
    private int pageCount = 0;
    private String currentFilePath;
    
    public PdfiumPreviewManager(Context context) {
        this.context = context.getApplicationContext();
        this.pdfiumCore = new PdfiumCore(this.context);
    }
    
    /**
     * 打开 PDF 文件
     * @param filePath PDF 文件路径
     * @return true 如果成功打开
     */
    public boolean openDocument(String filePath) {
        try {
            closeDocument(); // 先关闭之前的文档
            
            File file = new File(filePath);
            if (!file.exists()) {
                AppLogger.e(TAG, "PDF 文件不存在: " + filePath);
                return false;
            }
            
            ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            pdfDocument = pdfiumCore.newDocument(fd);
            pageCount = pdfiumCore.getPageCount(pdfDocument);
            currentFilePath = filePath;
            
            AppLogger.d(TAG, "PDF 打开成功: " + filePath + ", 页数: " + pageCount);
            return true;
            
        } catch (Exception e) {
            AppLogger.e(TAG, "打开 PDF 失败: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 渲染指定页面为 Bitmap
     * @param pageIndex 页面索引（从0开始）
     * @param width 目标宽度
     * @param height 目标高度
     * @return 渲染后的 Bitmap
     */
    public Bitmap renderPage(int pageIndex, int width, int height) {
        if (pdfDocument == null || pageIndex < 0 || pageIndex >= pageCount) {
            return null;
        }
        
        try {
            pdfiumCore.openPage(pdfDocument, pageIndex);
            
            // 获取页面实际尺寸
            int pageWidth = pdfiumCore.getPageWidthPoint(pdfDocument, pageIndex);
            int pageHeight = pdfiumCore.getPageHeightPoint(pdfDocument, pageIndex);
            
            // 计算缩放比例以保持宽高比
            float scale = Math.min(
                (float) width / pageWidth,
                (float) height / pageHeight
            );
            
            int bitmapWidth = (int) (pageWidth * scale);
            int bitmapHeight = (int) (pageHeight * scale);
            
            Bitmap bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
            pdfiumCore.renderPageBitmap(pdfDocument, bitmap, pageIndex, 0, 0, bitmapWidth, bitmapHeight);
            
            return bitmap;
            
        } catch (Exception e) {
            AppLogger.e(TAG, "渲染页面失败: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 获取页面尺寸
     * @param pageIndex 页面索引
     * @return RectF 包含页面宽度和高度
     */
    public RectF getPageSize(int pageIndex) {
        if (pdfDocument == null || pageIndex < 0 || pageIndex >= pageCount) {
            return null;
        }
        
        try {
            int width = pdfiumCore.getPageWidthPoint(pdfDocument, pageIndex);
            int height = pdfiumCore.getPageHeightPoint(pdfDocument, pageIndex);
            return new RectF(0, 0, width, height);
        } catch (Exception e) {
            AppLogger.e(TAG, "获取页面尺寸失败: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 获取所有页面的缩略图
     * @param thumbnailWidth 缩略图宽度
     * @return 缩略图列表
     */
    public List<Bitmap> getThumbnails(int thumbnailWidth) {
        List<Bitmap> thumbnails = new ArrayList<>();
        
        for (int i = 0; i < pageCount; i++) {
            RectF size = getPageSize(i);
            if (size != null) {
                float scale = thumbnailWidth / size.width();
                int thumbnailHeight = (int) (size.height() * scale);
                Bitmap thumbnail = renderPage(i, thumbnailWidth, thumbnailHeight);
                if (thumbnail != null) {
                    thumbnails.add(thumbnail);
                }
            }
        }
        
        return thumbnails;
    }
    
    /**
     * 获取 PDF 元数据
     */
    public PdfDocument.Meta getDocumentMeta() {
        if (pdfDocument == null) {
            return null;
        }
        try {
            return pdfiumCore.getDocumentMeta(pdfDocument);
        } catch (Exception e) {
            AppLogger.e(TAG, "获取文档元数据失败: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 获取总页数
     */
    public int getPageCount() {
        return pageCount;
    }
    
    /**
     * 获取当前文件路径
     */
    public String getCurrentFilePath() {
        return currentFilePath;
    }
    
    /**
     * 检查是否有打开的文档
     */
    public boolean hasOpenDocument() {
        return pdfDocument != null;
    }
    
    /**
     * 关闭文档
     */
    public void closeDocument() {
        if (pdfDocument != null) {
            try {
                pdfiumCore.closeDocument(pdfDocument);
                AppLogger.d(TAG, "PDF 文档已关闭");
            } catch (Exception e) {
                AppLogger.e(TAG, "关闭 PDF 文档失败: " + e.getMessage(), e);
            }
            pdfDocument = null;
            pageCount = 0;
            currentFilePath = null;
        }
    }
    
    /**
     * 释放资源
     */
    public void release() {
        closeDocument();
    }
}

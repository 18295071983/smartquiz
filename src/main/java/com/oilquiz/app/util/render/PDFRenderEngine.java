package com.oilquiz.app.util.render;

import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;

import com.oilquiz.app.util.preview.PdfiumPreviewManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 渲染引擎（基于 Google Pdfium 核心库，经 PdfiumPreviewManager 渲染）。
 * 每页渲染为 JPEG（quality 85），输出单 HTML 预览页。
 */
public class PDFRenderEngine implements FileRenderEngine {
    private static final String TAG = "PDFRenderEngine";
    private static final String[] SUPPORTED_EXTENSIONS = {"pdf"};
    private static final int MAX_PAGES = 50; // 最多渲染50页，防止内存溢出
    private static final int RENDER_WIDTH = 1200; // 渲染宽度，提高清晰度

    @Override
    public boolean canRender(File file) {
        String fileName = file.getName().toLowerCase();
        for (String extension : SUPPORTED_EXTENSIONS) {
            if (fileName.endsWith("." + extension)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String getEngineName() {
        return "PDF渲染引擎";
    }

    @Override
    public String getFileTypeDescription(File file) {
        return "PDF文档";
    }

    @Override
    public void render(File file, RenderCallback callback) {
        try {
            Log.d(TAG, "Rendering PDF file: " + file.getName());

            PdfiumPreviewManager pdfManager = new PdfiumPreviewManager(
                    com.oilquiz.app.SmartQuizApplication.getAppContext());

            try {
                if (!pdfManager.openDocument(file.getAbsolutePath())) {
                    callback.onSuccess(generateErrorPdfHtml(file, "PDF 打开失败（可能已损坏或加密）"));
                    return;
                }

                int pageCount = pdfManager.getPageCount();
                Log.d(TAG, "PDF总页数: " + pageCount);

                if (pageCount > 0) {
                    // 限制页数，防止内存溢出
                    int renderPageCount = Math.min(pageCount, MAX_PAGES);
                    if (pageCount > MAX_PAGES) {
                        Log.w(TAG, "PDF页数过多(" + pageCount + ")，只渲染前" + MAX_PAGES + "页");
                    }

                    // 生成HTML内容
                    StringBuilder htmlContent = new StringBuilder();
                    htmlContent.append("<!DOCTYPE html>");
                    htmlContent.append("<html lang='zh-CN'>");
                    htmlContent.append("<head>");
                    htmlContent.append("<meta charset='UTF-8'>");
                    htmlContent.append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>");
                    htmlContent.append("<title>PDF预览 - ").append(file.getName()).append("</title>");
                    htmlContent.append("<style>");
                    htmlContent.append("* { box-sizing: border-box; margin: 0; padding: 0; }");
                    htmlContent.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #f1f5f9; min-height: 100vh; padding: 12px; }");
                    htmlContent.append(".container { max-width: 1000px; margin: 0 auto; }");
                    htmlContent.append(".header { background: #fff; color: #1e293b; padding: 12px 16px; border: 1px solid #e2e8f0; border-radius: 8px; margin-bottom: 12px; }");
                    htmlContent.append(".header h1 { font-size: 17px; margin-bottom: 4px; display: flex; align-items: center; gap: 8px; color: #1e293b; }");
                    htmlContent.append(".header .meta { font-size: 12px; color: #64748b; display: flex; gap: 16px; flex-wrap: wrap; }");
                    htmlContent.append(".header .meta span { display: flex; align-items: center; gap: 4px; }");
                    htmlContent.append(".page-container { background: white; margin-bottom: 12px; border-radius: 6px; box-shadow: 0 1px 4px rgba(0,0,0,0.08); overflow: hidden; }");
                    htmlContent.append(".page-header { background: #f8fafc; padding: 8px 16px; border-bottom: 1px solid #eef2f7; font-size: 12px; color: #94a3b8; display: flex; justify-content: space-between; align-items: center; }");
                    htmlContent.append(".page-content { padding: 12px; text-align: center; background: #fff; }");
                    htmlContent.append(".page-content img { max-width: 100%; height: auto; box-shadow: 0 1px 6px rgba(0,0,0,0.08); }");
                    htmlContent.append(".warning { background: #fffbeb; border: 1px solid #fde68a; color: #92400e; padding: 12px; border-radius: 8px; margin-bottom: 12px; text-align: center; font-size: 13px; }");
                    htmlContent.append("</style>");
                    htmlContent.append("</head>");
                    htmlContent.append("<body>");

                    // 头部信息
                    htmlContent.append("<div class='container'>");
                    htmlContent.append("<div class='header'>");
                    htmlContent.append("<h1>📄 ").append(file.getName()).append("</h1>");
                    htmlContent.append("<div class='meta'>");
                    htmlContent.append("<span>📊 共 ").append(pageCount).append(" 页</span>");
                    htmlContent.append("<span>📦 ").append(formatFileSize(file.length())).append("</span>");
                    if (pageCount > MAX_PAGES) {
                        htmlContent.append("<span>⚠️ 显示前 ").append(MAX_PAGES).append(" 页</span>");
                    }
                    htmlContent.append("</div>");
                    htmlContent.append("</div>");

                    // 渲染每一页（Pdfium：按宽度自适应保持宽高比）
                    for (int i = 0; i < renderPageCount; i++) {
                        int progress = (i * 90) / renderPageCount;
                        callback.onProgress(progress);

                        Bitmap bitmap = pdfManager.renderPage(i, RENDER_WIDTH, 5000);
                        if (bitmap == null) {
                            htmlContent.append("<div class='page-container'><div class='page-content'>")
                                    .append("⚠️ 第 ").append(i + 1).append(" 页渲染失败</div></div>");
                            continue;
                        }

                        String base64Image = bitmapToBase64(bitmap);
                        bitmap.recycle();

                        htmlContent.append("<div class='page-container'>");
                        htmlContent.append("<div class='page-header'>");
                        htmlContent.append("<span>第 ").append(i + 1).append(" 页</span>");
                        htmlContent.append("</div>");
                        htmlContent.append("<div class='page-content'>");
                        htmlContent.append("<img src='data:image/jpeg;base64,").append(base64Image).append("' alt='第").append(i + 1).append("页'>");
                        htmlContent.append("</div>");
                        htmlContent.append("</div>");
                    }

                    if (pageCount > MAX_PAGES) {
                        htmlContent.append("<div class='warning'>");
                        htmlContent.append("⚠️ PDF文件页数过多，仅显示前 ").append(MAX_PAGES).append(" 页。请使用专业PDF阅读器查看完整内容。");
                        htmlContent.append("</div>");
                    }

                    htmlContent.append("</div>");
                    htmlContent.append("</body>");
                    htmlContent.append("</html>");

                    callback.onProgress(100);
                    callback.onSuccess(htmlContent.toString());
                    Log.d(TAG, "PDF渲染完成，共 " + renderPageCount + " 页");
                } else {
                    Log.w(TAG, "PDF文件无页面: " + file.getName());
                    callback.onSuccess(generateEmptyPdfHtml(file, "PDF文件无页面"));
                }
            } catch (Exception e) {
                Log.e(TAG, "渲染PDF文件失败: " + e.getMessage(), e);
                callback.onSuccess(generateErrorPdfHtml(file, "渲染PDF文件失败: " + e.getMessage()));
            } finally {
                try {
                    pdfManager.closeDocument();
                } catch (Exception e) {
                    Log.w(TAG, "关闭 pdfium 文档失败: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "渲染PDF文件失败: " + e.getMessage(), e);
            callback.onSuccess(generateErrorPdfHtml(file, "渲染PDF文件失败: " + e.getMessage()));
        }
    }

    /**
     * 将Bitmap转换为Base64字符串（JPEG quality 85，体积比 PNG 小一个量级）
     */
    private String bitmapToBase64(Bitmap bitmap) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream);
            byte[] byteArray = outputStream.toByteArray();
            outputStream.close();
            return Base64.encodeToString(byteArray, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "图片转Base64失败: " + e.getMessage());
            return "";
        }
    }

    /**
     * 格式化文件大小
     */
    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024.0));
        return String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
    }

    /**
     * 生成空PDF的HTML
     */
    private String generateEmptyPdfHtml(File file, String message) {
        return generateErrorPdfHtml(file, message);
    }

    /**
     * 生成错误PDF的HTML
     */
    private String generateErrorPdfHtml(File file, String errorMessage) {
        return "<!DOCTYPE html>" +
            "<html lang='zh-CN'>" +
            "<head>" +
            "<meta charset='UTF-8'>" +
            "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
            "<title>PDF预览 - " + file.getName() + "</title>" +
            "<style>" +
            "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #f1f5f9; min-height: 100vh; display: flex; align-items: center; justify-content: center; margin: 0; }" +
            ".container { max-width: 500px; background: white; padding: 32px; border-radius: 12px; box-shadow: 0 4px 16px rgba(0,0,0,0.08); text-align: center; }" +
            ".icon { font-size: 64px; margin-bottom: 16px; }" +
            "h1 { color: #dc2626; font-size: 20px; margin-bottom: 12px; }" +
            ".info { background: #f8fafc; padding: 12px; border-radius: 8px; margin: 16px 0; text-align: left; }" +
            ".info p { margin: 6px 0; color: #475569; font-size: 13px; }" +
            ".error { color: #dc2626; margin-top: 12px; padding: 10px; background: #fef2f2; border-radius: 6px; font-size: 13px; }" +
            "</style>" +
            "</head>" +
            "<body>" +
            "<div class='container'>" +
            "<div class='icon'>📄</div>" +
            "<h1>PDF预览失败</h1>" +
            "<div class='info'>" +
            "<p><strong>文件名:</strong> " + file.getName() + "</p>" +
            "<p><strong>文件大小:</strong> " + formatFileSize(file.length()) + "</p>" +
            "</div>" +
            "<div class='error'>" +
            "<p><strong>错误信息:</strong> " + errorMessage + "</p>" +
            "</div>" +
            "<p style='margin-top: 16px; color: #64748b; font-size: 13px;'>请尝试使用其他PDF阅读器打开</p>" +
            "</div>" +
            "</body>" +
            "</html>";
    }
}

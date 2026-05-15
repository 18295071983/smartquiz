package com.oilquiz.app.util.render;

import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
    public void render(File file, RenderCallback callback) {
        try {
            Log.d(TAG, "Rendering PDF file: " + file.getName());
            
            ParcelFileDescriptor fileDescriptor = null;
            PdfRenderer pdfRenderer = null;
            
            try {
                fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                pdfRenderer = new PdfRenderer(fileDescriptor);
                
                int pageCount = pdfRenderer.getPageCount();
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
                    htmlContent.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #2c3e50; min-height: 100vh; padding: 20px; }");
                    htmlContent.append(".container { max-width: 1000px; margin: 0 auto; }");
                    htmlContent.append(".header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 25px; border-radius: 12px; margin-bottom: 25px; box-shadow: 0 10px 30px rgba(0,0,0,0.3); }");
                    htmlContent.append(".header h1 { font-size: 22px; margin-bottom: 10px; display: flex; align-items: center; gap: 10px; }");
                    htmlContent.append(".header .meta { font-size: 14px; opacity: 0.9; display: flex; gap: 20px; flex-wrap: wrap; }");
                    htmlContent.append(".header .meta span { display: flex; align-items: center; gap: 5px; }");
                    htmlContent.append(".page-container { background: white; margin-bottom: 25px; border-radius: 8px; box-shadow: 0 5px 20px rgba(0,0,0,0.2); overflow: hidden; }");
                    htmlContent.append(".page-header { background: #f8f9fa; padding: 12px 20px; border-bottom: 1px solid #e0e0e0; font-size: 14px; color: #666; display: flex; justify-content: space-between; align-items: center; }");
                    htmlContent.append(".page-content { padding: 20px; text-align: center; background: #fff; }");
                    htmlContent.append(".page-content img { max-width: 100%; height: auto; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }");
                    htmlContent.append(".warning { background: #fff3cd; border: 1px solid #ffc107; color: #856404; padding: 15px; border-radius: 8px; margin-bottom: 20px; text-align: center; }");
                    htmlContent.append(".loading { text-align: center; padding: 40px; color: white; }");
                    htmlContent.append(".loading-spinner { display: inline-block; width: 40px; height: 40px; border: 4px solid rgba(255,255,255,0.3); border-top: 4px solid white; border-radius: 50%; animation: spin 1s linear infinite; margin-bottom: 15px; }");
                    htmlContent.append("@keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }");
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
                    
                    // 渲染每一页
                    for (int i = 0; i < renderPageCount; i++) {
                        // 发送进度
                        int progress = (i * 90) / renderPageCount;
                        callback.onProgress(progress);
                        
                        // 获取当前页
                        PdfRenderer.Page page = pdfRenderer.openPage(i);
                        
                        // 计算缩放比例，使宽度为RENDER_WIDTH
                        int width = page.getWidth();
                        int height = page.getHeight();
                        float scale = (float) RENDER_WIDTH / width;
                        int renderHeight = (int) (height * scale);
                        
                        // 创建Bitmap用于渲染
                        Bitmap bitmap = Bitmap.createBitmap(RENDER_WIDTH, renderHeight, Bitmap.Config.ARGB_8888);
                        
                        // 渲染页面到Bitmap（使用缩放）
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        
                        // 将Bitmap转换为Base64
                        String base64Image = bitmapToBase64(bitmap);
                        
                        // 添加页面到HTML
                        htmlContent.append("<div class='page-container'>");
                        htmlContent.append("<div class='page-header'>");
                        htmlContent.append("<span>第 ").append(i + 1).append(" 页</span>");
                        htmlContent.append("<span>").append(width).append(" × ").append(height).append("</span>");
                        htmlContent.append("</div>");
                        htmlContent.append("<div class='page-content'>");
                        htmlContent.append("<img src='data:image/png;base64,").append(base64Image).append("' alt='第").append(i + 1).append("页'>");
                        htmlContent.append("</div>");
                        htmlContent.append("</div>");
                        
                        // 回收Bitmap
                        bitmap.recycle();
                        
                        // 关闭页面
                        page.close();
                    }
                    
                    // 如果页数过多，添加提示
                    if (pageCount > MAX_PAGES) {
                        htmlContent.append("<div class='warning'>");
                        htmlContent.append("⚠️ PDF文件页数过多，仅显示前 ").append(MAX_PAGES).append(" 页。请使用专业PDF阅读器查看完整内容。");
                        htmlContent.append("</div>");
                    }
                    
                    htmlContent.append("</div>");
                    htmlContent.append("</body>");
                    htmlContent.append("</html>");
                    
                    // 关闭渲染器
                    pdfRenderer.close();
                    fileDescriptor.close();
                    
                    // 发送进度
                    callback.onProgress(100);
                    
                    // 返回HTML内容
                    callback.onSuccess(htmlContent.toString());
                    
                    Log.d(TAG, "PDF渲染完成，共 " + renderPageCount + " 页");
                } else {
                    Log.w(TAG, "PDF文件无页面: " + file.getName());
                    callback.onSuccess(generateEmptyPdfHtml(file, "PDF文件无页面"));
                }
            } catch (IOException e) {
                Log.e(TAG, "渲染PDF文件失败: " + e.getMessage(), e);
                callback.onSuccess(generateErrorPdfHtml(file, "渲染PDF文件失败: " + e.getMessage()));
            } finally {
                // 确保资源被释放
                if (pdfRenderer != null) {
                    try {
                        pdfRenderer.close();
                    } catch (Exception e) {
                        Log.w(TAG, "关闭PdfRenderer失败: " + e.getMessage());
                    }
                }
                if (fileDescriptor != null) {
                    try {
                        fileDescriptor.close();
                    } catch (Exception e) {
                        Log.w(TAG, "关闭FileDescriptor失败: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "渲染PDF文件失败: " + e.getMessage(), e);
            callback.onSuccess(generateErrorPdfHtml(file, "渲染PDF文件失败: " + e.getMessage()));
        }
    }
    
    /**
     * 将Bitmap转换为Base64字符串
     */
    private String bitmapToBase64(Bitmap bitmap) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        // 使用PNG格式，质量100%
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
        byte[] byteArray = outputStream.toByteArray();
        return Base64.encodeToString(byteArray, Base64.DEFAULT);
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
            "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: linear-gradient(135deg, #ff6b6b, #ee5a24); min-height: 100vh; display: flex; align-items: center; justify-content: center; margin: 0; }" +
            ".container { max-width: 500px; background: white; padding: 40px; border-radius: 16px; box-shadow: 0 20px 60px rgba(0,0,0,0.3); text-align: center; }" +
            ".icon { font-size: 80px; margin-bottom: 20px; }" +
            "h1 { color: #e74c3c; font-size: 24px; margin-bottom: 15px; }" +
            ".info { background: #f8f9fa; padding: 15px; border-radius: 8px; margin: 20px 0; text-align: left; }" +
            ".info p { margin: 8px 0; color: #555; font-size: 14px; }" +
            ".error { color: #e74c3c; margin-top: 15px; padding: 12px; background: #fdf2f2; border-radius: 6px; font-size: 13px; }" +
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
            "<p style='margin-top: 20px; color: #7f8c8d; font-size: 14px;'>请尝试使用其他PDF阅读器打开</p>" +
            "</div>" +
            "</body>" +
            "</html>";
    }

    @Override
    public String getEngineName() {
        return "PDF渲染引擎";
    }

    @Override
    public String getFileTypeDescription(File file) {
        return "PDF文档";
    }
}

package com.oilquiz.app.util.preview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import com.oilquiz.app.infra.AppLogger;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class OfficePreviewEngine extends BasePreviewEngine {
    private static final String TAG = "OfficePreviewEngine";
    private static final String[] SUPPORTED_EXTENSIONS = {".doc", ".docx", ".ppt", ".pptx"};
    
    @Override
    public boolean supports(File file) {
        return hasExtension(file, SUPPORTED_EXTENSIONS);
    }
    
    @Override
    public String getEngineName() {
        return "Office预览引擎";
    }
    
    @Override
    protected Bitmap generatePreview(Context context, File file, PreviewProgressCallback progressCallback) throws Exception {
        String fileName = file.getName().toLowerCase();
        
        if (fileName.endsWith(".docx")) {
            return renderDocxFile(file);
        } else if (fileName.endsWith(".pptx")) {
            return renderPptxFile(file);
        } else {
            return createPlaceholderPreview(file, getFileType(fileName));
        }
    }
    
    @Override
    protected Bitmap generatePreview(Context context, File file) throws Exception {
        return generatePreview(context, file, null);
    }
    
    /**
     * 渲染 DOCX 文件
     */
    private Bitmap renderDocxFile(File file) {
        try (InputStream is = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(is)) {
            
            List<String> paragraphs = new ArrayList<>();
            for (XWPFParagraph para : document.getParagraphs()) {
                String text = para.getText();
                if (text != null && !text.trim().isEmpty()) {
                    paragraphs.add(text);
                }
            }
            
            if (paragraphs.isEmpty()) {
                return createPlaceholderPreview(file, "Word文档（无文本内容）");
            }
            
            return renderTextToBitmap(paragraphs, "Word文档: " + file.getName());
            
        } catch (Exception e) {
            AppLogger.e(TAG, "渲染DOCX失败: " + e.getMessage(), e);
            return createPlaceholderPreview(file, "Word文档");
        }
    }
    
    /**
     * 渲染 PPTX 文件
     */
    private Bitmap renderPptxFile(File file) {
        try (InputStream is = new FileInputStream(file);
             XMLSlideShow ppt = new XMLSlideShow(is)) {
            
            List<XSLFSlide> slides = ppt.getSlides();
            if (slides.isEmpty()) {
                return createPlaceholderPreview(file, "PowerPoint（无幻灯片）");
            }
            
            // 提取第一张幻灯片的文本
            XSLFSlide firstSlide = slides.get(0);
            List<String> slideTexts = new ArrayList<>();
            
            for (XSLFShape shape : firstSlide.getShapes()) {
                if (shape instanceof XSLFTextShape) {
                    XSLFTextShape textShape = (XSLFTextShape) shape;
                    for (XSLFTextParagraph para : textShape.getTextParagraphs()) {
                        String text = para.getText();
                        if (text != null && !text.trim().isEmpty()) {
                            slideTexts.add(text);
                        }
                    }
                }
            }
            
            String title = "PowerPoint: " + file.getName() + " (共" + slides.size() + "页)";
            if (slideTexts.isEmpty()) {
                return createPlaceholderPreview(file, title);
            }
            
            return renderTextToBitmap(slideTexts, title);
            
        } catch (Exception e) {
            AppLogger.e(TAG, "渲染PPTX失败: " + e.getMessage(), e);
            return createPlaceholderPreview(file, "PowerPoint演示文稿");
        }
    }
    
    /**
     * 将文本渲染为 Bitmap
     */
    private Bitmap renderTextToBitmap(List<String> texts, String title) {
        int width = 600;
        int height = 800;
        int margin = 40;
        int lineHeight = 45;
        
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        
        // 背景
        canvas.drawColor(Color.WHITE);
        
        // 绘制标题
        Paint titlePaint = new Paint();
        titlePaint.setColor(Color.parseColor("#3B82F6"));
        titlePaint.setTextSize(20);
        titlePaint.setTypeface(Typeface.DEFAULT_BOLD);
        titlePaint.setAntiAlias(true);
        
        // 绘制标题背景条
        canvas.drawRect(0, 0, width, 60, titlePaint);
        titlePaint.setColor(Color.WHITE);
        
        // 绘制标题文本
        float titleX = margin;
        float titleY = 42;
        String displayTitle = title.length() > 30 ? title.substring(0, 27) + "..." : title;
        canvas.drawText(displayTitle, titleX, titleY, titlePaint);
        
        // 绘制分隔线
        Paint linePaint = new Paint();
        linePaint.setColor(Color.parseColor("#E5E7EB"));
        canvas.drawLine(margin, 70, width - margin, 70, linePaint);
        
        // 绘制正文
        TextPaint textPaint = new TextPaint();
        textPaint.setColor(Color.parseColor("#1F2937"));
        textPaint.setTextSize(16);
        textPaint.setAntiAlias(true);
        
        float y = 100;
        int maxLines = (height - 120) / lineHeight;
        int lineCount = 0;
        
        for (String text : texts) {
            if (lineCount >= maxLines) {
                break;
            }
            
            // 使用 StaticLayout 来处理多行文本
            StaticLayout staticLayout = StaticLayout.Builder.obtain(
                text, 0, text.length(), textPaint, width - 2 * margin)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(8, 1)
                .setIncludePad(true)
                .build();
            
            canvas.save();
            canvas.translate(margin, y);
            staticLayout.draw(canvas);
            canvas.restore();
            
            y += staticLayout.getHeight() + 10;
            lineCount += staticLayout.getLineCount();
        }
        
        // 如果还有更多文本，显示省略提示
        if (texts.size() > lineCount) {
            Paint morePaint = new Paint();
            morePaint.setColor(Color.parseColor("#9CA3AF"));
            morePaint.setTextSize(14);
            morePaint.setAntiAlias(true);
            canvas.drawText("... (更多内容)", margin, height - 30, morePaint);
        }
        
        return bitmap;
    }
    
    /**
     * 获取文件类型
     */
    private String getFileType(String fileName) {
        if (fileName.endsWith(".doc") || fileName.endsWith(".docx")) {
            return "Word文档";
        } else if (fileName.endsWith(".ppt") || fileName.endsWith(".pptx")) {
            return "PowerPoint演示文稿";
        } else if (fileName.endsWith(".xls") || fileName.endsWith(".xlsx")) {
            return "Excel表格";
        } else {
            return "Office文件";
        }
    }
    
    /**
     * 创建占位预览图
     */
    private Bitmap createPlaceholderPreview(File file, String fileType) {
        int width = 300;
        int height = 400;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        
        // 绘制背景
        canvas.drawColor(Color.WHITE);
        
        // 绘制图标背景
        Paint bgPaint = new Paint();
        bgPaint.setColor(Color.parseColor("#3B82F6"));
        canvas.drawRect(20, 20, 280, 200, bgPaint);
        
        // 绘制文件类型
        Paint typePaint = new Paint();
        typePaint.setColor(Color.WHITE);
        typePaint.setTextSize(32);
        typePaint.setTypeface(Typeface.DEFAULT_BOLD);
        typePaint.setTextAlign(Paint.Align.CENTER);
        typePaint.setAntiAlias(true);
        
        String iconText = fileType.contains("Word") ? "W" : 
                          fileType.contains("PowerPoint") ? "P" : 
                          fileType.contains("Excel") ? "X" : "O";
        
        canvas.drawText(iconText, width / 2, 130, typePaint);
        
        // 绘制文件类型
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        paint.setTextSize(18);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setAntiAlias(true);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(fileType, width / 2, 240, paint);
        
        // 绘制文件名称
        paint.setTextSize(14);
        paint.setTypeface(Typeface.DEFAULT);
        
        String fileName = file.getName();
        if (fileName.length() > 20) {
            fileName = fileName.substring(0, 17) + "...";
        }
        canvas.drawText(fileName, width / 2, 280, paint);
        
        // 绘制提示信息
        paint.setTextSize(12);
        paint.setColor(Color.parseColor("#6B7280"));
        canvas.drawText("点击查看完整内容", width / 2, 320, paint);
        
        return bitmap;
    }
}

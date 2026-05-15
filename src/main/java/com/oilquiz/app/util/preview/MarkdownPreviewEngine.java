package com.oilquiz.app.util.preview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.Html;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;

public class MarkdownPreviewEngine extends BasePreviewEngine {

    private static final String[] SUPPORTED_EXTENSIONS = {".md", ".markdown"};

    @Override
    protected Bitmap generatePreview(Context context, File file, PreviewProgressCallback progressCallback) throws Exception {
        String content = readFileContent(file);
        String htmlContent = convertMarkdownToHtml(content);
        return createHtmlBitmap(htmlContent);
    }

    @Override
    protected Bitmap generatePreview(Context context, File file) throws Exception {
        return generatePreview(context, file, null);
    }

    @Override
    public boolean supports(File file) {
        return hasExtension(file, SUPPORTED_EXTENSIONS);
    }

    @Override
    public String getEngineName() {
        return "Markdown Preview Engine";
    }

    private String readFileContent(File file) throws IOException {
        StringBuilder content = new StringBuilder();
        try (Reader reader = new InputStreamReader(new FileInputStream(file), "UTF-8")) {
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                content.append(buffer, 0, read);
            }
        }
        return content.toString();
    }

    private String convertMarkdownToHtml(String markdown) {
        // 改进的Markdown到HTML转换
        // 处理标题
        markdown = markdown.replaceAll("(?m)^# (.*?)$", "<h1>$1</h1>")
                .replaceAll("(?m)^## (.*?)$", "<h2>$1</h2>")
                .replaceAll("(?m)^### (.*?)$", "<h3>$1</h3>")
                .replaceAll("(?m)^#### (.*?)$", "<h4>$1</h4>")
                .replaceAll("(?m)^##### (.*?)$", "<h5>$1</h5>")
                .replaceAll("(?m)^###### (.*?)$", "<h6>$1</h6>");
        
        // 处理粗体和斜体
        markdown = markdown.replaceAll("\\*\\*(.*?)\\*\\*", "<b>$1</b>")
                .replaceAll("\\*(.*?)\\*", "<i>$1</i>")
                .replaceAll("__([^_]+)__", "<b>$1</b>")
                .replaceAll("_([^_]+)_", "<i>$1</i>");
        
        // 处理列表
        markdown = markdown.replaceAll("(?m)^- (.*?)$", "<li>$1</li>")
                .replaceAll("(?m)^\\d+\\. (.*?)$", "<li>$1</li>");
        
        // 处理代码块
        markdown = markdown.replaceAll("```([\\s\\S]*?)```", "<pre><code>$1</code></pre>");
        
        // 处理行内代码
        markdown = markdown.replaceAll("`([^`]+)`", "<code>$1</code>");
        
        // 处理链接
        markdown = markdown.replaceAll("\\[([^\\]]+)\\]\\(([^\\)]+)\\)", "<a href=\"$2\">$1</a>");
        
        // 处理段落
        markdown = markdown.replaceAll("\n\n", "</p><p>");
        
        return "<html><body><p>" + markdown + "</p></body></html>";
    }

    private Bitmap createHtmlBitmap(String htmlContent) {
        int width = 800;
        int height = 600;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        
        canvas.drawColor(Color.WHITE);
        
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        paint.setTextSize(14);
        
        // 简单绘制HTML内容（实际应用中可能需要更复杂的HTML渲染）
        String text = Html.fromHtml(htmlContent).toString();
        String[] lines = text.split("\n");
        int y = 20;
        for (String line : lines) {
            if (y > height - 20) {
                canvas.drawText("... (truncated)", 10, y, paint);
                break;
            }
            canvas.drawText(line, 10, y, paint);
            y += 20;
        }
        
        return bitmap;
    }
}
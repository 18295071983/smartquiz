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

public class HTMLPreviewEngine extends BasePreviewEngine {

    private static final String[] SUPPORTED_EXTENSIONS = {".html", ".htm"};

    @Override
    protected Bitmap generatePreview(Context context, File file, PreviewProgressCallback progressCallback) throws Exception {
        String content = readFileContent(file);
        return createHtmlBitmap(content);
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
        return "HTML Preview Engine";
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

    private Bitmap createHtmlBitmap(String htmlContent) {
        int width = 800;
        int height = 600;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        
        canvas.drawColor(Color.WHITE);
        
        // 提取HTML中的文本内容，保留基本结构
        String text = extractTextFromHtml(htmlContent);
        
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        paint.setTextSize(14);
        
        // 绘制HTML内容
        String[] lines = text.split("\n");
        int y = 20;
        for (String line : lines) {
            if (y > height - 20) {
                canvas.drawText("... (truncated)", 10, y, paint);
                break;
            }
            // 处理缩进
            int indent = 0;
            while (line.startsWith("  ")) {
                indent += 20;
                line = line.substring(2);
            }
            canvas.drawText(line, 10 + indent, y, paint);
            y += 20;
        }
        
        return bitmap;
    }
    
    private String extractTextFromHtml(String htmlContent) {
        // 移除HTML标签，保留基本结构
        htmlContent = htmlContent.replaceAll("<script[^>]*>.*?</script>", "");
        htmlContent = htmlContent.replaceAll("<style[^>]*>.*?</style>", "");
        
        // 处理标题
        htmlContent = htmlContent.replaceAll("<h1[^>]*>(.*?)</h1>", "\n# $1\n");
        htmlContent = htmlContent.replaceAll("<h2[^>]*>(.*?)</h2>", "\n## $1\n");
        htmlContent = htmlContent.replaceAll("<h3[^>]*>(.*?)</h3>", "\n### $1\n");
        htmlContent = htmlContent.replaceAll("<h4[^>]*>(.*?)</h4>", "\n#### $1\n");
        htmlContent = htmlContent.replaceAll("<h5[^>]*>(.*?)</h5>", "\n##### $1\n");
        htmlContent = htmlContent.replaceAll("<h6[^>]*>(.*?)</h6>", "\n###### $1\n");
        
        // 处理列表
        htmlContent = htmlContent.replaceAll("<ul[^>]*>(.*?)</ul>", "\n$1\n");
        htmlContent = htmlContent.replaceAll("<ol[^>]*>(.*?)</ol>", "\n$1\n");
        htmlContent = htmlContent.replaceAll("<li[^>]*>(.*?)</li>", "  - $1\n");
        
        // 处理段落
        htmlContent = htmlContent.replaceAll("<p[^>]*>(.*?)</p>", "\n$1\n");
        
        // 处理链接
        htmlContent = htmlContent.replaceAll("<a[^>]*href=\"([^\"]+)\">([^<]+)</a>", "$2 ($1)");
        
        // 移除剩余的HTML标签
        htmlContent = htmlContent.replaceAll("<[^>]*>", "");
        
        // 处理转义字符
        htmlContent = htmlContent.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
        
        return htmlContent.trim();
    }
}
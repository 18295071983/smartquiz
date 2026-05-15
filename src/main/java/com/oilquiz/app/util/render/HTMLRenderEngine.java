package com.oilquiz.app.util.render;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class HTMLRenderEngine implements FileRenderEngine {
    private static final String TAG = "HTMLRenderEngine";
    private static final String[] SUPPORTED_EXTENSIONS = {"html", "htm", "xhtml"};
    private static final int MAX_LINES = 1000;
    private static final int MAX_CONTENT_LENGTH = 10000;
    
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
        return "HTML渲染引擎";
    }
    
    @Override
    public String getFileTypeDescription(File file) {
        String fileName = file.getName().toLowerCase();
        if (fileName.endsWith(".xhtml")) {
            return "XHTML文件";
        } else if (fileName.endsWith(".htm")) {
            return "HTML文件 (HTM)";
        }
        return "HTML文件";
    }
    
    @Override
    public void render(File file, RenderCallback callback) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            StringBuilder content = new StringBuilder();
            String line;
            int lineCount = 0;
            int contentLength = 0;
            
            // 读取文件内容
            while ((line = reader.readLine()) != null && lineCount < MAX_LINES && contentLength < MAX_CONTENT_LENGTH) {
                content.append(line).append("\n");
                lineCount++;
                contentLength += line.length() + 1;
                
                // 更新进度
                int progress = Math.min(100, (lineCount * 100) / MAX_LINES);
                callback.onProgress(progress);
            }
            
            if (line != null) {
                content.append("... 还有更多内容未显示\n");
            }
            
            // 直接返回原始HTML内容，让WebView来渲染
            String htmlContent = content.toString();
            
            // 确保HTML内容包含viewport meta标签，支持自适应显示
            if (!htmlContent.contains("viewport")) {
                // 在head标签中添加viewport meta标签
                if (htmlContent.contains("<head>")) {
                    htmlContent = htmlContent.replace("<head>", "<head>\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">\n<meta name=\"format-detection\" content=\"telephone=no, email=no, address=no\">");
                } else if (htmlContent.contains("<html>")) {
                    // 如果没有head标签，在html标签后添加
                    htmlContent = htmlContent.replace("<html>", "<html>\n<head>\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">\n<meta name=\"format-detection\" content=\"telephone=no, email=no, address=no\">\n</head>");
                }
            }
            
            callback.onProgress(100);
            callback.onSuccess(htmlContent);
            
        } catch (IOException e) {
            Log.e(TAG, "Error rendering HTML file: " + e.getMessage(), e);
            callback.onError("渲染失败: " + e.getMessage());
        }
    }
    
    /**
     * 从HTML中提取文本内容
     * @param html HTML内容
     * @return 提取的文本内容
     */
    private String extractTextFromHtml(String html) {
        // 移除脚本和样式
        html = html.replaceAll("<script[^>]*>.*?</script>", "");
        html = html.replaceAll("<style[^>]*>.*?</style>", "");
        
        // 处理标题
        html = html.replaceAll("<h1[^>]*>(.*?)</h1>", "\n# $1\n");
        html = html.replaceAll("<h2[^>]*>(.*?)</h2>", "\n## $1\n");
        html = html.replaceAll("<h3[^>]*>(.*?)</h3>", "\n### $1\n");
        html = html.replaceAll("<h4[^>]*>(.*?)</h4>", "\n#### $1\n");
        html = html.replaceAll("<h5[^>]*>(.*?)</h5>", "\n##### $1\n");
        html = html.replaceAll("<h6[^>]*>(.*?)</h6>", "\n###### $1\n");
        
        // 处理列表
        html = html.replaceAll("<ul[^>]*>(.*?)</ul>", "\n$1\n");
        html = html.replaceAll("<ol[^>]*>(.*?)</ol>", "\n$1\n");
        html = html.replaceAll("<li[^>]*>(.*?)</li>", "  - $1\n");
        
        // 处理段落
        html = html.replaceAll("<p[^>]*>(.*?)</p>", "\n$1\n");
        
        // 处理链接
        html = html.replaceAll("<a[^>]*href=\"([^\"]+)\">([^<]+)</a>", "$2 ($1)");
        
        // 移除剩余的HTML标签
        html = html.replaceAll("<[^>]*>", "");
        
        // 处理转义字符
        html = html.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
        
        // 移除多余的空白字符
        html = html.trim().replaceAll("\\s+", " ");
        
        return html;
    }
    
    /**
     * 从HTML中提取标题
     * @param html HTML内容
     * @return 提取的标题
     */
    private String extractTitleFromHtml(String html) {
        int titleStart = html.indexOf("<title>");
        int titleEnd = html.indexOf("</title>");
        if (titleStart != -1 && titleEnd != -1) {
            return html.substring(titleStart + 7, titleEnd).trim();
        }
        return "";
    }
}

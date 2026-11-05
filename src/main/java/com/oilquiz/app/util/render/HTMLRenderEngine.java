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
            
            // 编码探测读取（UTF-8 优先 + GBK 回退）
            String htmlContent = FileEncodingUtil.readText(file, MAX_CONTENT_LENGTH);

            // 确保HTML内容包含viewport meta标签（大小写不敏感匹配 head/html）
            if (!htmlContent.toLowerCase().contains("viewport")) {
                java.util.regex.Matcher headMatcher =
                        java.util.regex.Pattern.compile("(?i)<head>").matcher(htmlContent);
                if (headMatcher.find()) {
                    htmlContent = headMatcher.replaceFirst("<head>\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">\n<meta name=\"format-detection\" content=\"telephone=no, email=no, address=no\">");
                } else {
                    java.util.regex.Matcher htmlMatcher =
                            java.util.regex.Pattern.compile("(?i)<html>").matcher(htmlContent);
                    if (htmlMatcher.find()) {
                        htmlContent = htmlMatcher.replaceFirst("<html>\n<head>\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">\n<meta name=\"format-detection\" content=\"telephone=no, email=no, address=no\">\n</head>");
                    }
                }
            }
            
            callback.onProgress(100);
            callback.onSuccess(htmlContent);
            
        } catch (IOException e) {
            Log.e(TAG, "Error rendering HTML file: " + e.getMessage(), e);
            callback.onError("渲染失败: " + e.getMessage());
        }
    }
    
}

package com.oilquiz.app.util.render;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class MarkdownRenderEngine implements FileRenderEngine {
    private static final String TAG = "MarkdownRenderEngine";
    private static final String[] SUPPORTED_EXTENSIONS = {"md", "markdown", "mdown", "mkd", "mkdn"};
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
        return "Markdown渲染引擎";
    }
    
    @Override
    public String getFileTypeDescription(File file) {
        return "Markdown文件";
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
            
            // 转换Markdown到HTML
            String markdownContent = content.toString();
            String htmlContent = convertMarkdownToHtml(markdownContent);
            
            // 收集文件信息
            Map<String, Object> markdownInfo = new HashMap<>();
            markdownInfo.put("content", markdownContent);
            markdownInfo.put("htmlContent", htmlContent);
            markdownInfo.put("lineCount", lineCount);
            markdownInfo.put("fileSize", file.length() / 1024 + "KB");
            markdownInfo.put("fileName", file.getName());
            
            callback.onProgress(100);
            callback.onSuccess(markdownInfo);
            
        } catch (IOException e) {
            Log.e(TAG, "Error rendering Markdown file: " + e.getMessage(), e);
            callback.onError("渲染失败: " + e.getMessage());
        }
    }
    
    /**
     * 将Markdown转换为HTML
     * @param markdown Markdown内容
     * @return HTML内容
     */
    private String convertMarkdownToHtml(String markdown) {
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
        
        // 处理图片
        markdown = markdown.replaceAll("!\\[([^\\]]*)\\]\\(([^\\)]+)\\)", "<img src=\"$2\" alt=\"$1\" />");
        
        // 处理引用
        markdown = markdown.replaceAll("(?m)^> (.*?)$", "<blockquote>$1</blockquote>");
        
        // 处理段落
        markdown = markdown.replaceAll("\n\n", "</p><p>");
        
        // 包装HTML并添加CSS样式
        String htmlContent = "<!DOCTYPE html>" +
                "<html lang=\"zh-CN\">" +
                "<head>" +
                "<meta charset=\"UTF-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                "<title>Markdown预览</title>" +
                "<style>" +
                "body { font-family: 'Microsoft YaHei', 'SimSun', 'Arial', sans-serif; margin: 0; padding: 20px; background-color: #f5f5f5; }" +
                ".container { max-width: 800px; margin: 0 auto; background-color: white; padding: 40px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }" +
                "h1, h2, h3, h4, h5, h6 { color: #333; margin-top: 24px; margin-bottom: 16px; font-weight: 600; }" +
                "h1 { font-size: 28px; border-bottom: 1px solid #eaecef; padding-bottom: 0.3em; }" +
                "h2 { font-size: 24px; border-bottom: 1px solid #eaecef; padding-bottom: 0.3em; }" +
                "h3 { font-size: 20px; }" +
                "h4 { font-size: 18px; }" +
                "h5 { font-size: 16px; }" +
                "h6 { font-size: 14px; color: #666; }" +
                "p { margin-top: 0; margin-bottom: 16px; line-height: 1.8; }" +
                "ul, ol { margin-top: 0; margin-bottom: 16px; padding-left: 24px; }" +
                "li { margin-bottom: 8px; }" +
                "blockquote { border-left: 4px solid #ddd; padding-left: 16px; color: #666; margin: 0 0 16px 0; }" +
                "code { background-color: #f6f8fa; padding: 2px 4px; border-radius: 3px; font-family: 'Consolas', 'Monaco', 'Courier New', monospace; font-size: 0.9em; }" +
                "pre { background-color: #f6f8fa; padding: 16px; border-radius: 6px; overflow-x: auto; margin: 0 0 16px 0; }" +
                "pre code { background-color: transparent; padding: 0; }" +
                "a { color: #0366d6; text-decoration: none; }" +
                "a:hover { text-decoration: underline; }" +
                "img { max-width: 100%; height: auto; border-radius: 4px; margin: 16px 0; }" +
                "</style>" +
                "</head>" +
                "<body>" +
                "<div class=\"container\">" +
                "<p>" + markdown + "</p>" +
                "</div>" +
                "</body>" +
                "</html>";
        
        return htmlContent;
    }
}

package com.oilquiz.app.util.render;

import android.content.Context;
import android.content.res.Configuration;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 增强型 HTML 渲染引擎
 * - 深色模式支持
 * - 大文件分页加载
 * - CSS 样式注入
 * - 图片懒加载
 */
public class EnhancedHTMLRenderEngine implements FileRenderEngine {
    private static final String TAG = "EnhancedHTMLRenderEngine";
    
    // 支持的文件扩展名
    private static final String[] SUPPORTED_EXTENSIONS = {"html", "htm", "xhtml", "md", "markdown"};
    
    // 分页配置
    private static final int LINES_PER_PAGE = 500;
    private static final int MAX_CONTENT_LENGTH = 50000;
    
    private Context context;
    private boolean isDarkMode = false;
    private int currentPage = 1;
    private int totalPages = 1;
    private String fullContent = "";

    public EnhancedHTMLRenderEngine(Context context) {
        this.context = context.getApplicationContext();
        updateDarkModeStatus();
    }

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
        return "增强HTML渲染引擎";
    }

    @Override
    public String getFileTypeDescription(File file) {
        String fileName = file.getName().toLowerCase();
        if (fileName.endsWith(".md") || fileName.endsWith(".markdown")) {
            return "Markdown 文件";
        } else if (fileName.endsWith(".xhtml")) {
            return "XHTML 文件";
        }
        return "HTML 文件";
    }

    @Override
    public void render(File file, RenderCallback callback) {
        try {
            updateDarkModeStatus();
            
            // 1. 读取原始内容
            String rawContent = readFileContent(file);
            
            if (rawContent == null || rawContent.isEmpty()) {
                callback.onError("文件为空或无法读取");
                return;
            }
            
            fullContent = rawContent;
            currentPage = 1;
            totalPages = calculateTotalPages(rawContent);
            
            // 2. 处理内容（注入样式、转换 Markdown 等）
            String processedContent = processContent(rawContent, file.getName());
            
            // 3. 分页
            String pageContent = extractPage(processedContent, currentPage);
            
            // 4. 构建完整 HTML
            String html = buildCompleteHTML(pageContent, file.getName());
            
            // 5. 回调
            callback.onSuccess(html);
            
        } catch (Exception e) {
            Log.e(TAG, "渲染失败", e);
            callback.onError("渲染失败: " + e.getMessage());
        }
    }

    /**
     * 渲染指定页
     */
    public void renderPage(int page, RenderCallback callback) {
        if (page < 1 || page > totalPages) {
            callback.onError("页码超出范围");
            return;
        }
        
        currentPage = page;
        String pageContent = extractPage(fullContent, page);
        String html = buildCompleteHTML(pageContent, "");
        
        callback.onProgress((page * 100) / totalPages);
        callback.onSuccess(html);
    }

    /**
     * 读取文件内容
     */
    private String readFileContent(File file) throws IOException {
        StringBuilder content = new StringBuilder();
        
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int lineCount = 0;
            
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
                lineCount++;
                
                // 进度回调
                int progress = Math.min(90, (lineCount * 90) / LINES_PER_PAGE);
                
                // 超长文件截断
                if (content.length() > MAX_CONTENT_LENGTH) {
                    content.append("\n<!-- 内容已截断，原文件共 ").append(lineCount).append(" 行 -->");
                    break;
                }
            }
        }
        
        return content.toString();
    }

    /**
     * 处理内容（核心转换逻辑）
     */
    private String processContent(String content, String fileName) {
        String processed = content;
        
        // 1. 检测并处理 Markdown
        if (fileName.endsWith(".md") || fileName.endsWith(".markdown")) {
            processed = convertMarkdownToHTML(processed);
        }
        
        // 2. 注入深色模式样式
        if (isDarkMode) {
            processed = injectDarkModeStyles(processed);
        }
        
        // 3. 添加图片懒加载
        processed = enableImageLazyLoading(processed);
        
        // 4. 添加代码高亮占位
        processed = prepareCodeHighlighting(processed);
        
        return processed;
    }

    /**
     * 简单的 Markdown 转 HTML
     */
    private String convertMarkdownToHTML(String markdown) {
        String html = markdown;
        
        // 标题
        html = html.replaceAll("(?m)^######\\s+(.*)$", "<h6>$1</h6>");
        html = html.replaceAll("(?m)^#####\\s+(.*)$", "<h5>$1</h5>");
        html = html.replaceAll("(?m)^####\\s+(.*)$", "<h4>$1</h4>");
        html = html.replaceAll("(?m)^###\\s+(.*)$", "<h3>$1</h3>");
        html = html.replaceAll("(?m)^##\\s+(.*)$", "<h2>$1</h2>");
        html = html.replaceAll("(?m)^#\\s+(.*)$", "<h1>$1</h1>");
        
        // 粗体和斜体
        html = html.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
        html = html.replaceAll("\\*(.+?)\\*", "<em>$1</em>");
        html = html.replaceAll("__(.+?)__", "<strong>$1</strong>");
        html = html.replaceAll("_(.+?)_", "<em>$1</em>");
        
        // 代码块
        html = html.replaceAll("```(\\w+)?\\n([\\s\\S]*?)```", "<pre><code class=\"$1\">$2</code></pre>");
        html = html.replaceAll("`([^`]+)`", "<code>$1</code>");
        
        // 链接
        html = html.replaceAll("\\[(.+?)\\]\\((.+?)\\)", "<a href=\"$2\">$1</a>");
        
        // 图片
        html = html.replaceAll("!\\[([^\\[]*)\\]\\(([^)]+)\\)", 
            "<img src=\"$2\" alt=\"$1\" loading=\"lazy\">");
        
        // 列表
        html = html.replaceAll("(?m)^-\\s+(.*)$", "<li>$1</li>");
        html = html.replaceAll("(?m)^\\*\\s+(.*)$", "<li>$1</li>");
        html = html.replaceAll("(?m)^\\d+\\.\\s+(.*)$", "<li>$1</li>");
        
        // 水平线
        html = html.replaceAll("(?m)^---$", "<hr>");
        html = html.replaceAll("(?m)^\\*\\*\\*$", "<hr>");
        
        // 换行
        html = html.replaceAll("\n\n", "</p><p>");
        html = "<p>" + html + "</p>";
        
        return html;
    }

    /**
     * 注入深色模式样式
     */
    private String injectDarkModeStyles(String html) {
        String darkStyles = """
            <style id="dark-mode-styles">
                body {
                    background-color: #121212 !important;
                    color: #E1E1E1 !important;
                }
                a {
                    color: #BB86FC !important;
                }
                h1, h2, h3, h4, h5, h6 {
                    color: #FFFFFF !important;
                }
                pre, code {
                    background-color: #1E1E1E !important;
                    color: #E0E0E0 !important;
                }
                img {
                    opacity: 0.9;
                }
                hr {
                    border-color: #333 !important;
                }
            </style>
            """;
        
        // 插入到 </head> 之前
        if (html.contains("</head>")) {
            return html.replace("</head>", darkStyles + "</head>");
        }
        
        return darkStyles + html;
    }

    /**
     * 启用图片懒加载
     */
    private String enableImageLazyLoading(String html) {
        // 替换所有 <img> 标签添加 lazy loading
        return html.replaceAll(
            "<img(.*?)(/?>)",
            "<img$1 loading=\"lazy\" decoding=\"async\"$2"
        );
    }

    /**
     * 准备代码高亮
     */
    private String prepareCodeHighlighting(String html) {
        // 添加简单的代码高亮 CSS
        String codeStyles = """
            <style>
                pre {
                    background-color: #f5f5f5;
                    padding: 12px;
                    border-radius: 4px;
                    overflow-x: auto;
                    font-family: 'Courier New', monospace;
                }
                code {
                    font-family: 'Courier New', monospace;
                }
            </style>
            """;
        
        if (html.contains("</head>")) {
            return html.replace("</head>", codeStyles + "</head>");
        }
        
        return codeStyles + html;
    }

    /**
     * 计算总页数
     */
    private int calculateTotalPages(String content) {
        int lineCount = content.split("\n").length;
        return Math.max(1, (lineCount + LINES_PER_PAGE - 1) / LINES_PER_PAGE);
    }

    /**
     * 提取指定页内容
     */
    private String extractPage(String content, int page) {
        String[] lines = content.split("\n");
        int start = (page - 1) * LINES_PER_PAGE;
        int end = Math.min(page * LINES_PER_PAGE, lines.length);
        
        if (start >= lines.length) {
            return "";
        }
        
        StringBuilder pageContent = new StringBuilder();
        for (int i = start; i < end; i++) {
            pageContent.append(lines[i]).append("\n");
        }
        
        // 添加分页指示
        if (totalPages > 1) {
            pageContent.append("\n<div class='page-indicator'>")
                       .append("第 ").append(page).append(" / ").append(totalPages).append(" 页")
                       .append("</div>");
        }
        
        return pageContent.toString();
    }

    /**
     * 构建完整的 HTML 文档
     */
    private String buildCompleteHTML(String bodyContent, String title) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"zh-CN\">\n");
        html.append("<head>\n");
        html.append("<meta charset=\"UTF-8\">\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes\">\n");
        html.append("<meta name=\"format-detection\" content=\"telephone=no, email=no, address=no\">\n");
        
        if (!title.isEmpty()) {
            html.append("<title>").append(escapeHtml(title)).append("</title>\n");
        }
        
        // 全局样式
        html.append(getGlobalStyles());
        
        html.append("</head>\n");
        html.append("<body>\n");
        html.append(bodyContent);
        html.append("\n</body>\n");
        html.append("</html>");
        
        return html.toString();
    }

    /**
     * 获取全局样式
     */
    private String getGlobalStyles() {
        return """
            <style>
                * {
                    box-sizing: border-box;
                }
                body {
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
                    line-height: 1.6;
                    padding: 16px;
                    max-width: 100%;
                    word-wrap: break-word;
                }
                h1, h2, h3, h4, h5, h6 {
                    margin-top: 1.5em;
                    margin-bottom: 0.5em;
                    font-weight: 600;
                }
                p {
                    margin-bottom: 1em;
                }
                img {
                    max-width: 100%;
                    height: auto;
                    border-radius: 8px;
                }
                a {
                    color: #8B5CF6;
                    text-decoration: none;
                }
                a:hover {
                    text-decoration: underline;
                }
                pre {
                    background-color: #f5f5f5;
                    padding: 12px;
                    border-radius: 8px;
                    overflow-x: auto;
                    font-size: 14px;
                }
                code {
                    font-family: 'SF Mono', Consolas, monospace;
                    font-size: 14px;
                }
                blockquote {
                    border-left: 4px solid #ddd;
                    margin: 1em 0;
                    padding-left: 1em;
                    color: #666;
                }
                table {
                    width: 100%;
                    border-collapse: collapse;
                    margin: 1em 0;
                }
                th, td {
                    border: 1px solid #ddd;
                    padding: 8px;
                    text-align: left;
                }
                th {
                    background-color: #f5f5f5;
                }
                .page-indicator {
                    text-align: center;
                    color: #666;
                    font-size: 14px;
                    padding: 16px;
                    margin-top: 24px;
                }
            </style>
            """;
    }

    /**
     * HTML 转义
     */
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    /**
     * 获取渲染元数据
     */
    private Map<String, Object> getRenderMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("totalPages", totalPages);
        metadata.put("currentPage", currentPage);
        metadata.put("totalLength", fullContent.length());
        metadata.put("isDarkMode", isDarkMode);
        return metadata;
    }

    /**
     * 更新深色模式状态
     */
    private void updateDarkModeStatus() {
        if (context != null) {
            int nightModeFlags = context.getResources().getConfiguration().uiMode 
                & Configuration.UI_MODE_NIGHT_MASK;
            isDarkMode = (nightModeFlags == Configuration.UI_MODE_NIGHT_YES);
        }
    }

    /**
     * 获取分页信息
     */
    public int getTotalPages() {
        return totalPages;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public boolean isDarkMode() {
        return isDarkMode;
    }
}

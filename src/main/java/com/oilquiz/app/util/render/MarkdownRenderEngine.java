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
        try {
            // UTF-8 优先，GBK 回退（FileEncodingUtil 处理编码探测）
            String markdownContent = FileEncodingUtil.readText(file, MAX_CONTENT_LENGTH);
            int lineCount = markdownContent.split("\n", -1).length;

            // 转换Markdown到HTML（flexmark 标准渲染）
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
        /**
     * 将Markdown转换为HTML（flexmark 成熟库，标准渲染：
     * 标题/列表/表格/代码块/行内代码/链接/图片/引用/转义全部正确）
     */
    private String convertMarkdownToHtml(String markdown) {
        try {
            com.vladsch.flexmark.util.data.MutableDataSet options =
                    new com.vladsch.flexmark.util.data.MutableDataSet();
            com.vladsch.flexmark.parser.Parser parser =
                    com.vladsch.flexmark.parser.Parser.builder(options).build();
            com.vladsch.flexmark.html.HtmlRenderer renderer =
                    com.vladsch.flexmark.html.HtmlRenderer.builder(options).build();
            return renderer.render(parser.parse(markdown));
        } catch (Throwable t) {
            Log.e(TAG, "flexmark 渲染失败，回退基本转换: " + t.getMessage());
            return basicFallbackHtml(markdown);
        }
    }

    /** 兜底：flexmark 不可用时的最简转换（转义 + 换行保留） */
    private String basicFallbackHtml(String markdown) {
        String escaped = markdown.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
        return "<p>" + escaped.replace("\n\n", "</p><p>").replace("\n", "<br/>") + "</p>";
    }
}

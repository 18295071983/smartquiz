package com.oilquiz.app.util.render;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class TextRenderEngine implements FileRenderEngine {
    private static final String TAG = "TextRenderEngine";
    private static final String[] SUPPORTED_EXTENSIONS = {
        "txt", "log", "properties", "ini", "conf",
        "csv", "json", "xml", "html", "css",
        "js", "java", "kt", "py", "cpp",
        "h", "cs", "php", "rb", "go",
        "rs", "swift", "m", "jsx", "ts",
        "tsx", "md"
    };
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
        return "文本渲染引擎";
    }
    
    @Override
    public String getFileTypeDescription(File file) {
        String fileName = file.getName().toLowerCase();
        if (fileName.endsWith(".txt")) return "文本文件";
        if (fileName.endsWith(".log")) return "日志文件";
        if (fileName.endsWith(".properties")) return "属性文件";
        if (fileName.endsWith(".ini")) return "配置文件";
        if (fileName.endsWith(".conf")) return "配置文件";
        if (fileName.endsWith(".csv")) return "CSV文件";
        if (fileName.endsWith(".json")) return "JSON文件";
        if (fileName.endsWith(".xml")) return "XML文件";
        if (fileName.endsWith(".html")) return "HTML文件";
        if (fileName.endsWith(".css")) return "CSS文件";
        if (fileName.endsWith(".js")) return "JavaScript文件";
        if (fileName.endsWith(".java")) return "Java文件";
        if (fileName.endsWith(".kt")) return "Kotlin文件";
        if (fileName.endsWith(".py")) return "Python文件";
        if (fileName.endsWith(".cpp")) return "C++文件";
        if (fileName.endsWith(".h")) return "头文件";
        if (fileName.endsWith(".cs")) return "C#文件";
        if (fileName.endsWith(".php")) return "PHP文件";
        if (fileName.endsWith(".rb")) return "Ruby文件";
        if (fileName.endsWith(".go")) return "Go文件";
        if (fileName.endsWith(".rs")) return "Rust文件";
        if (fileName.endsWith(".swift")) return "Swift文件";
        if (fileName.endsWith(".m")) return "Objective-C文件";
        if (fileName.endsWith(".jsx")) return "JSX文件";
        if (fileName.endsWith(".ts")) return "TypeScript文件";
        if (fileName.endsWith(".tsx")) return "TypeScript JSX文件";
        if (fileName.endsWith(".md")) return "Markdown文件";
        return "文本文件";
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
            
            // 收集文件信息
            Map<String, Object> textInfo = new HashMap<>();
            textInfo.put("content", content.toString());
            textInfo.put("lineCount", lineCount);
            textInfo.put("fileSize", file.length() / 1024 + "KB");
            textInfo.put("fileName", file.getName());
            textInfo.put("fileType", getFileTypeDescription(file));
            
            callback.onProgress(100);
            callback.onSuccess(textInfo);
            
        } catch (IOException e) {
            Log.e(TAG, "Error rendering text file: " + e.getMessage(), e);
            callback.onError("渲染失败: " + e.getMessage());
        }
    }
}

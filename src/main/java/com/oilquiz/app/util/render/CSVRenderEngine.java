package com.oilquiz.app.util.render;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class CSVRenderEngine implements FileRenderEngine {
    private static final String TAG = "CSVRenderEngine";
    private static final String[] SUPPORTED_EXTENSIONS = {"csv"};
    private static final int MAX_ROWS = 100;
    private static final int MAX_COLUMNS = 20;

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
            Log.d(TAG, "Rendering CSV file: " + file.getName());
            
            // 读取CSV文件内容
            List<String[]> csvData = new ArrayList<>();
            int lineCount = 0;
            
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null && lineCount < MAX_ROWS) {
                    String[] columns = parseCSVLine(line);
                    csvData.add(columns);
                    lineCount++;
                }
            } catch (IOException e) {
                Log.e(TAG, "Error reading CSV file: " + e.getMessage(), e);
                // 如果读取失败，显示基本信息
                StringBuilder errorContent = new StringBuilder();
                errorContent.append("CSV文件:\n");
                errorContent.append("文件名: " + file.getName() + "\n");
                errorContent.append("大小: " + (file.length() / 1024) + "KB\n");
                errorContent.append("\n提示: 无法读取CSV文件内容，仅显示基本信息\n");
                errorContent.append("错误: " + e.getMessage() + "\n");
                callback.onSuccess(errorContent.toString());
                return;
            }
            
            // 生成HTML内容
            StringBuilder htmlContent = new StringBuilder();
            htmlContent.append("<!DOCTYPE html>");
            htmlContent.append("<html>");
            htmlContent.append("<head>");
            htmlContent.append("<meta charset='UTF-8'>");
            htmlContent.append("<style>");
            htmlContent.append("body { font-family: Arial, sans-serif; margin: 10px; }");
            htmlContent.append("h1 { color: #333; font-size: 18px; }");
            htmlContent.append("table { border-collapse: collapse; width: 100%; margin-top: 10px; }");
            htmlContent.append("th, td { border: 1px solid #ddd; padding: 8px; text-align: left; font-size: 14px; }");
            htmlContent.append("th { background-color: #f2f2f2; font-weight: bold; }");
            htmlContent.append("tr:nth-child(even) { background-color: #f9f9f9; }");
            htmlContent.append("tr:hover { background-color: #f5f5f5; }");
            htmlContent.append(".info { background-color: #e8f4f8; padding: 10px; border-radius: 4px; margin-bottom: 10px; }");
            htmlContent.append("</style>");
            htmlContent.append("</head>");
            htmlContent.append("<body>");
            
            // 添加文件基本信息
            htmlContent.append("<div class='info'>");
            htmlContent.append("<h1>CSV文件</h1>");
            htmlContent.append("<p><strong>文件名:</strong> " + file.getName() + "</p>");
            htmlContent.append("<p><strong>大小:</strong> " + (file.length() / 1024) + "KB</p>");
            htmlContent.append("<p><strong>行数:</strong> " + lineCount + "</p>");
            htmlContent.append("</div>");
            
            // 生成表格
            htmlContent.append("<table>");
            
            // 计算最大列数
            int maxCols = 0;
            for (String[] row : csvData) {
                maxCols = Math.max(maxCols, row.length);
            }
            maxCols = Math.min(maxCols, MAX_COLUMNS);
            
            // 生成表头
            htmlContent.append("<tr>");
            for (int i = 0; i < maxCols; i++) {
                htmlContent.append("<th>列 " + (i + 1) + "</th>");
            }
            htmlContent.append("</tr>");
            
            // 生成数据行
            for (String[] row : csvData) {
                htmlContent.append("<tr>");
                for (int i = 0; i < maxCols; i++) {
                    htmlContent.append("<td>");
                    if (i < row.length) {
                        htmlContent.append(row[i]);
                    }
                    htmlContent.append("</td>");
                }
                htmlContent.append("</tr>");
            }
            
            // 如果行数超过限制，显示提示
            if (lineCount >= MAX_ROWS) {
                htmlContent.append("<tr><td colspan='" + maxCols + "' style='text-align: center; color: #666;'>...</td></tr>");
            }
            
            htmlContent.append("</table>");
            htmlContent.append("</body>");
            htmlContent.append("</html>");
            
            callback.onSuccess(htmlContent.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error rendering CSV file: " + e.getMessage(), e);
            callback.onError("渲染CSV文件失败: " + e.getMessage());
        }
    }

    // 解析CSV行，处理包含逗号的字段
    private String[] parseCSVLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder currentField = new StringBuilder();
        boolean inQuotes = false;
        
        for (char c : line.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(currentField.toString());
                currentField.setLength(0);
            } else {
                currentField.append(c);
            }
        }
        
        fields.add(currentField.toString());
        return fields.toArray(new String[0]);
    }

    @Override
    public String getEngineName() {
        return "CSV渲染引擎";
    }

    @Override
    public String getFileTypeDescription(File file) {
        return "CSV文件";
    }
}

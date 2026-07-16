package com.oilquiz.app.util.render;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebView;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WebView文件渲染器 - 使用WebView渲染Excel文件数据
 * 支持完整的表格布局和样式，提供更好的用户体验
 */
public class WebViewRenderer {
    private static final String TAG = "WebViewRenderer";
    
    // 线程池配置
    private static final ExecutorService executorService = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    /**
     * 渲染回调接口
     */
    public interface RenderCallback {
        void onRenderStart();
        void onRenderProgress(int current, int total);
        void onRenderComplete(String htmlContent);
        void onRenderError(String message);
    }
    
    /**
     * 渲染Excel文件为HTML（带映射功能）
     * @param file Excel文件
     * @param sheetIndex 工作表索引
     * @param fieldMapping 字段映射
     * @param fieldOptions 字段选项列表
     * @param callback 渲染回调
     */
    public static void renderExcelToHtml(File file, int sheetIndex, Map<String, Integer> fieldMapping, java.util.List<String> fieldOptions, RenderCallback callback) {
        if (callback != null) {
            callback.onRenderStart();
        }
        
        executorService.execute(() -> {
            try {
                if (file == null) {
                    if (callback != null) {
                        mainHandler.post(() -> callback.onRenderError("文件对象为空"));
                    }
                    return;
                }
                
                if (!file.exists()) {
                    if (callback != null) {
                        mainHandler.post(() -> callback.onRenderError("文件不存在"));
                    }
                    return;
                }
                
                StringBuilder htmlBuilder = new StringBuilder();
                
                try (Workbook workbook = WorkbookFactory.create(file)) {
                    Sheet sheet = workbook.getSheetAt(sheetIndex);
                    int rowCount = sheet.getLastRowNum();
                    
                    // 生成HTML头部
                    htmlBuilder.append("<!DOCTYPE html>")
                            .append("<html>")
                            .append("<head>")
                            .append("<meta charset=\"UTF-8\">")
                            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
                            .append("<style>")
                            .append("body { font-family: Arial, sans-serif; margin: 10px; background-color: #f5f5f5; }")
                            .append("table { border-collapse: collapse; width: 100%; table-layout: auto; background-color: white; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }")
                            .append("th, td { padding: 8px 12px; text-align: left; border: 1px solid #ddd; white-space: normal; line-height: normal; }")
                            .append("th { background-color: #4CAF50; color: white; font-weight: bold; position: sticky; top: 0; z-index: 10; min-width: 100px; }")
                            .append("tr:nth-child(even) { background-color: #f2f2f2; }")
                            .append("tr:hover { background-color: #e8f5e8; }")
                            .append(".mapping-select { margin-top: 5px; padding: 4px; font-size: 12px; width: 100%; min-width: 90px; }")
                            .append(".mapped { background-color: #e3f2fd !important; }")
                            .append("</style>")
                            .append("<script>")
                            .append("function handleSelectChange(columnIndex, selectElement) {")
                            .append("    var fieldName = selectElement.value;")
                            .append("    updateMapping(columnIndex, fieldName);")
                            .append("}")
                            .append("function updateMapping(columnIndex, fieldName) {")
                            .append("    if (window.Android) {")
                            .append("        window.Android.updateFieldMapping(columnIndex, fieldName);")
                            .append("    }")
                            .append("    var th = document.getElementById('column-' + columnIndex);")
                            .append("    if (th) {")
                            .append("        if (fieldName !== '不映射') {")
                            .append("            th.classList.add('mapped');")
                            .append("        } else {")
                            .append("            th.classList.remove('mapped');")
                            .append("        }")
                            .append("    }")
                            .append("}")
                            .append("</script>")
                            .append("</head>")
                            .append("<body>")
                            .append("<table>");
                    
                    // 读取表头
                    Row headerRow = sheet.getRow(0);
                    if (headerRow != null) {
                        htmlBuilder.append("<thead><tr>");
                        int cellCount = headerRow.getLastCellNum();
                        
                        // 创建反向映射：列索引 -> 字段名称
                        Map<Integer, String> columnToFieldMap = new HashMap<>();
                        if (fieldMapping != null) {
                            for (Map.Entry<String, Integer> entry : fieldMapping.entrySet()) {
                                columnToFieldMap.put(entry.getValue(), entry.getKey());
                            }
                        }
                        
                        for (int i = 0; i < cellCount; i++) {
                            Cell cell = headerRow.getCell(i);
                            String cellValue = cell != null ? ExcelUtil.getCellValue(cell).trim() : "列 " + (i + 1);
                            String currentField = columnToFieldMap.get(i);
                            
                            htmlBuilder.append("<th id=\"column-").append(i).append("\">")
                                    .append(escapeHtml(cellValue))
                                    .append("<select class=\"mapping-select\" onchange=\"handleSelectChange(").append(i).append(", this)\">");
                            
                            // 生成选项
                            java.util.List<String> options = fieldOptions;
                            if (options == null) {
                                options = java.util.Arrays.asList("不映射", "题目", "选项A", "选项B", "选项C", "选项D", "正确答案", "解析", "难度", "分类", "题型");
                            }
                            for (String option : options) {
                                boolean selected = option.equals(currentField);
                                htmlBuilder.append("<option value=\"").append(option).append("\"").append(selected ? " selected" : "").append(">").append(option).append("</option>");
                            }
                            
                            htmlBuilder.append("</select>")
                                    .append("</th>");
                        }
                        htmlBuilder.append("</tr></thead><tbody>");
                    }
                    
                    // 读取数据行
                    for (int i = 1; i <= rowCount; i++) {
                        Row row = sheet.getRow(i);
                        if (row != null) {
                            htmlBuilder.append("<tr>");
                            int cellCount = row.getLastCellNum();
                            for (int j = 0; j < cellCount; j++) {
                                Cell cell = row.getCell(j);
                                String cellValue = cell != null ? ExcelUtil.getCellValue(cell).trim() : "";
                                htmlBuilder.append("<td>").append(escapeHtml(cellValue)).append("</td>");
                            }
                            htmlBuilder.append("</tr>");
                        }
                        
                        // 每处理50行更新一次进度
                        if (i % 50 == 0 && callback != null) {
                            final int current = i;
                            final int total = rowCount;
                            mainHandler.post(() -> callback.onRenderProgress(current, total));
                        }
                    }
                    
                    // 生成HTML尾部
                    htmlBuilder.append("</tbody></table></body></html>");
                }
                
                // 渲染完成
                if (callback != null) {
                    final String htmlContent = htmlBuilder.toString();
                    mainHandler.post(() -> callback.onRenderComplete(htmlContent));
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error rendering Excel to HTML: " + e.getMessage(), e);
                if (callback != null) {
                    final String errorMessage = "渲染失败: " + e.getMessage();
                    mainHandler.post(() -> callback.onRenderError(errorMessage));
                }
            }
        });
    }
    
    /**
     * 渲染Excel文件为HTML（纯预览模式，不包含映射功能）
     * @param file Excel文件
     * @param sheetIndex 工作表索引
     * @param callback 渲染回调
     */
    public static void renderExcelToHtmlForPreview(File file, int sheetIndex, RenderCallback callback) {
        if (callback != null) {
            callback.onRenderStart();
        }
        
        executorService.execute(() -> {
            try {
                if (file == null) {
                    if (callback != null) {
                        mainHandler.post(() -> callback.onRenderError("文件对象为空"));
                    }
                    return;
                }
                
                if (!file.exists()) {
                    if (callback != null) {
                        mainHandler.post(() -> callback.onRenderError("文件不存在"));
                    }
                    return;
                }
                
                StringBuilder htmlBuilder = new StringBuilder();
                
                try (Workbook workbook = WorkbookFactory.create(file)) {
                    Sheet sheet = workbook.getSheetAt(sheetIndex);
                    int rowCount = sheet.getLastRowNum();
                    
                    // 生成HTML头部
                    htmlBuilder.append("<!DOCTYPE html>")
                            .append("<html>")
                            .append("<head>")
                            .append("<meta charset=\"UTF-8\">")
                            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
                            .append("<style>")
                            .append("body { font-family: Arial, sans-serif; margin: 10px; background-color: #f5f5f5; }")
                            .append("table { border-collapse: collapse; width: 100%; table-layout: auto; background-color: white; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }")
                            .append("th, td { padding: 8px 12px; text-align: left; border: 1px solid #ddd; white-space: normal; line-height: normal; }")
                            .append("th { background-color: #4CAF50; color: white; font-weight: bold; position: sticky; top: 0; z-index: 10; }")
                            .append("tr:nth-child(even) { background-color: #f2f2f2; }")
                            .append("tr:hover { background-color: #e8f5e8; }")
                            .append("</style>")
                            .append("</head>")
                            .append("<body>")
                            .append("<table>");
                    
                    // 读取表头
                    Row headerRow = sheet.getRow(0);
                    if (headerRow != null) {
                        htmlBuilder.append("<thead><tr>");
                        int cellCount = headerRow.getLastCellNum();
                        
                        for (int i = 0; i < cellCount; i++) {
                            Cell cell = headerRow.getCell(i);
                            String cellValue = cell != null ? ExcelUtil.getCellValue(cell).trim() : "列 " + (i + 1);
                            htmlBuilder.append("<th>")
                                    .append(escapeHtml(cellValue))
                                    .append("</th>");
                        }
                        htmlBuilder.append("</tr></thead><tbody>");
                    }
                    
                    // 读取数据行
                    for (int i = 1; i <= rowCount; i++) {
                        Row row = sheet.getRow(i);
                        if (row != null) {
                            htmlBuilder.append("<tr>");
                            int cellCount = row.getLastCellNum();
                            for (int j = 0; j < cellCount; j++) {
                                Cell cell = row.getCell(j);
                                String cellValue = cell != null ? ExcelUtil.getCellValue(cell).trim() : "";
                                htmlBuilder.append("<td>").append(escapeHtml(cellValue)).append("</td>");
                            }
                            htmlBuilder.append("</tr>");
                        }
                        
                        // 每处理50行更新一次进度
                        if (i % 50 == 0 && callback != null) {
                            final int current = i;
                            final int total = rowCount;
                            mainHandler.post(() -> callback.onRenderProgress(current, total));
                        }
                    }
                    
                    // 生成HTML尾部
                    htmlBuilder.append("</tbody></table></body></html>");
                }
                
                // 渲染完成
                if (callback != null) {
                    final String htmlContent = htmlBuilder.toString();
                    mainHandler.post(() -> callback.onRenderComplete(htmlContent));
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error rendering Excel to HTML for preview: " + e.getMessage(), e);
                if (callback != null) {
                    final String errorMessage = "渲染失败: " + e.getMessage();
                    mainHandler.post(() -> callback.onRenderError(errorMessage));
                }
            }
        });
    }
    
    /**
     * 在WebView中显示渲染结果
     * @param webView WebView实例
     * @param htmlContent HTML内容
     */
    public static void displayInWebView(WebView webView, String htmlContent) {
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null);
    }
    
    /**
     * 转义HTML特殊字符
     * @param text 原始文本
     * @return 转义后的文本
     */
    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#039;");
    }
}
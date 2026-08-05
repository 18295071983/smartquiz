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
                            .append("th { background-color: #4CAF50; color: white; font-weight: bold; position: sticky; top: 0; z-index: 10; min-width: 100px; vertical-align: top; }")
                            .append("tr:nth-child(even) { background-color: #f2f2f2; }")
                            .append("tr:hover { background-color: #e8f5e8; }")
                            .append(".mapped { background-color: #e3f2fd !important; }")
                            /* —— 自定义下拉组件（规避 WebView 原生 <select> 弹层无法滚动/被裁剪问题）—— */
                            .append(".mapping-dd { margin-top: 5px; position: relative; width: 100%; min-width: 90px; box-sizing: border-box; }")
                            .append(".mapping-dd-trigger {")
                            .append("  display: flex; justify-content: space-between; align-items: center;")
                            .append("  padding: 4px 8px; font-size: 12px; width: 100%; box-sizing: border-box;")
                            .append("  background: #fff; color: #333; border: 1px solid #ccc; border-radius: 3px;")
                            .append("  cursor: pointer; user-select: none;")
                            .append("}")
                            .append(".mapping-dd-trigger::after { content: '▼'; font-size: 9px; color: #666; margin-left: 6px; flex-shrink: 0; }")
                            .append(".mapping-dd.open .mapping-dd-trigger { border-color: #4CAF50; }")
                            .append(".mapping-dd-panel {")
                            .append("  display: none; position: fixed; z-index: 99999; background: #fff;")
                            .append("  border: 1px solid #ccc; border-radius: 3px; box-shadow: 0 4px 12px rgba(0,0,0,0.15);")
                            .append("  max-height: 300px; overflow-y: auto; overflow-x: hidden; padding: 2px 0;")
                            .append("}")
                            .append(".mapping-dd.open .mapping-dd-panel { display: block; }")
                            .append(".mapping-dd-item {")
                            .append("  padding: 6px 10px; font-size: 12px; color: #333; cursor: pointer; user-select: none; white-space: nowrap;")
                            .append("}")
                            .append(".mapping-dd-item:hover { background-color: #f0f8ff; }")
                            .append(".mapping-dd-item.selected { background-color: #4CAF50; color: white; }")
                            .append("</style>")
                            .append("<script>")
                            /* 自定义下拉逻辑：点击 trigger 展开固定定位面板，点击外部关闭，选项点击后写回 Android */
                            .append("(function(){")
                            .append("  var openDd = null;")
                            .append("  function closeAll(){ if(openDd){ openDd.classList.remove('open'); openDd=null; } }")
                            .append("  function positionPanel(dd){")
                            .append("    var panel = dd.querySelector('.mapping-dd-panel');")
                            .append("    var trig  = dd.querySelector('.mapping-dd-trigger');")
                            .append("    var r = trig.getBoundingClientRect();")
                            .append("    panel.style.left = r.left + 'px';")
                            .append("    panel.style.top  = (r.bottom + 2) + 'px';")
                            .append("    panel.style.minWidth = r.width + 'px';")
                            .append("    var vw = window.innerWidth || document.documentElement.clientWidth;")
                            .append("    var vh = window.innerHeight|| document.documentElement.clientHeight;")
                            .append("    var pw = Math.max(r.width, 180);")
                            .append("    if (r.left + pw > vw - 4) panel.style.left = Math.max(4, vw - pw - 4) + 'px';")
                            .append("    if (r.bottom + 304 > vh) { var nh = Math.max(120, vh - r.top - 8); panel.style.maxHeight = nh + 'px'; panel.style.top = (r.top - nh - 2) + 'px'; }")
                            .append("  }")
                            .append("  function initDds(){")
                            .append("    document.querySelectorAll('.mapping-dd').forEach(function(dd){")
                            .append("      var trig  = dd.querySelector('.mapping-dd-trigger');")
                            .append("      var panel = dd.querySelector('.mapping-dd-panel');")
                            .append("      trig.addEventListener('click', function(e){")
                            .append("        e.stopPropagation();")
                            .append("        var wasOpen = dd.classList.contains('open');")
                            .append("        closeAll();")
                            .append("        if(!wasOpen){ dd.classList.add('open'); openDd = dd; positionPanel(dd); }")
                            .append("      });")
                            .append("      panel.querySelectorAll('.mapping-dd-item').forEach(function(item){")
                            .append("        item.addEventListener('click', function(ev){")
                            .append("          ev.stopPropagation();")
                            .append("          var value = item.getAttribute('data-value');")
                            .append("          var col   = parseInt(dd.getAttribute('data-column'),10);")
                            .append("          trig.setAttribute('data-current', value);")
                            .append("          trig.childNodes[0].nodeValue = value + ' ';")
                            .append("          panel.querySelectorAll('.mapping-dd-item').forEach(function(x){ x.classList.remove('selected'); });")
                            .append("          item.classList.add('selected');")
                            .append("          closeAll();")
                            .append("          handleSelectChange(col, value);")
                            .append("        });")
                            .append("      });")
                            .append("    });")
                            .append("    document.addEventListener('click', closeAll);")
                            .append("    window.addEventListener('scroll', closeAll, true);")
                            .append("    window.addEventListener('resize', function(){ closeAll(); });")
                            .append("  }")
                            .append("  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initDds);")
                            .append("  else initDds();")
                            .append("})();")
                            .append("function handleSelectChange(columnIndex, fieldName) {")
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
                            if (currentField == null) currentField = "不映射";

                            // 生成选项
                            java.util.List<String> options = fieldOptions;
                            if (options == null || options.isEmpty()) {
                                options = com.oilquiz.app.model.QuestionField.getFieldOptions();
                            }

                            htmlBuilder.append("<th id=\"column-").append(i).append("\">")
                                       .append(escapeHtml(cellValue))
                                       .append("<div class=\"mapping-dd\" data-column=\"").append(i).append("\">")
                                       .append("<div class=\"mapping-dd-trigger\" data-current=\"").append(escapeHtml(currentField)).append("\">")
                                       .append(escapeHtml(currentField)).append(" </div>")
                                       .append("<div class=\"mapping-dd-panel\">");
                            for (String option : options) {
                                boolean selected = option.equals(currentField);
                                htmlBuilder.append("<div class=\"mapping-dd-item").append(selected ? " selected" : "")
                                           .append("\" data-value=\"").append(escapeHtml(option)).append("\">")
                                           .append(escapeHtml(option))
                                           .append("</div>");
                            }
                            htmlBuilder.append("</div></div></th>");
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
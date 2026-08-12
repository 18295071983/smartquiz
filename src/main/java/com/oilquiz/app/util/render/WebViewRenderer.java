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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
     * 精简的映射字段列表
     * 包含选项E/F用于独立列映射，"选项(拆分)"用于单列拆分模式
     */
    private static final String[] SIMPLE_FIELDS = {
        "不映射",
        "题目", "题型", "难度", "分值",
        "选项A", "选项B", "选项C", "选项D", "选项E", "选项F",
        "选项(拆分)",
        "正确答案", "解析",
        "分类", "知识点"
    };
    
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
     * @param fieldOptions 字段选项列表（忽略，使用精简列表）
     * @param callback 渲染回调
     */
    public static void renderExcelToHtml(File file, int sheetIndex, Map<String, Integer> fieldMapping, java.util.List<String> fieldOptions, RenderCallback callback) {
        renderExcelToHtml(file, sheetIndex, fieldMapping, fieldOptions, -1, null, callback);
    }

    /**
     * 渲染Excel文件为HTML（带映射功能 + 拆分预览）
     * @param file Excel文件
     * @param sheetIndex 工作表索引
     * @param fieldMapping 字段映射
     * @param fieldOptions 字段选项列表
     * @param splitColIdx 拆分列索引（-1 表示不拆分）
     * @param splitDelimiter 分隔符（null 或 "LETTER_PATTERN"）
     * @param callback 渲染回调
     */
    public static void renderExcelToHtml(File file, int sheetIndex, Map<String, Integer> fieldMapping, java.util.List<String> fieldOptions, int splitColIdx, String splitDelimiter, RenderCallback callback) {
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
                    
                    // 使用精简字段列表
                    List<String> simpleOptions = new ArrayList<>();
                    for (String f : SIMPLE_FIELDS) simpleOptions.add(f);

                    // ── 拆分预览：扫描数据确定拆分后的虚拟列数 ──
                    boolean hasSplitPreview = splitColIdx >= 0 && splitDelimiter != null;
                    int virtualColCount = 0;
                    if (hasSplitPreview) {
                        char[] letters = {'A','B','C','D','E','F','G','H','I','J','K','L'};
                        int checkRows = Math.min(rowCount, 20);
                        for (int r = 1; r <= checkRows; r++) {
                            Row rr = sheet.getRow(r);
                            if (rr == null) continue;
                            Cell cc = rr.getCell(splitColIdx);
                            if (cc == null) continue;
                            String val = ExcelUtil.getCellValue(cc).trim();
                            if (val.isEmpty()) continue;
                            int parts;
                            if ("LETTER_PATTERN".equals(splitDelimiter)) {
                                java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                                    "(?s)([A-H])[.\u3001\uff0e]\\s*(.*?)(?=\\s*[A-H][.\u3001\uff0e]|$)")
                                    .matcher(val);
                                parts = 0;
                                while (m.find()) parts++;
                            } else {
                                parts = val.split(java.util.regex.Pattern.quote(splitDelimiter), -1).length;
                            }
                            if (parts > virtualColCount) virtualColCount = parts;
                            if (virtualColCount >= letters.length) break;
                        }
                        if (virtualColCount > letters.length) virtualColCount = letters.length;
                    }

                    // 生成HTML
                    htmlBuilder.append("<!DOCTYPE html>")
                            .append("<html>")
                            .append("<head>")
                            .append("<meta charset=\"UTF-8\">")
                            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
                            .append("<style>")
                            .append("body { font-family: -apple-system, BlinkMacSystemFont, Arial, sans-serif; margin: 8px; background: #f5f5f5; }")
                            .append("table { border-collapse: collapse; width: 100%; table-layout: auto; background: #fff; box-shadow: 0 2px 6px rgba(0,0,0,0.08); border-radius: 8px; overflow: hidden; }")
                            .append("th, td { padding: 8px 10px; text-align: left; border: 1px solid #e0e0e0; white-space: normal; font-size: 13px; line-height: 1.4; }")
                            .append("th { background: #4f46e5; color: #fff; font-weight: 600; position: sticky; top: 0; z-index: 10; min-width: 80px; vertical-align: top; }")
                            .append("tr:nth-child(even) { background-color: #f8fafc; }")
                            .append("tr:hover { background-color: #eef2ff; }")
                            .append(".mapped { background-color: #e8f5e9 !important; }")
                            /* 自定义下拉（固定定位面板，避免被表格裁剪） */
                            .append(".mdd { margin-top: 6px; position: relative; }")
                            .append(".mdd-trig { display: flex; justify-content: space-between; align-items: center; padding: 4px 8px; font-size: 12px; width: 100%; min-width: 70px; box-sizing: border-box; background: rgba(255,255,255,0.95); color: #333; border: 1px solid rgba(255,255,255,0.4); border-radius: 4px; cursor: pointer; user-select: none; }")
                            .append(".mdd-trig::after { content: '\\25BC'; font-size: 8px; color: rgba(255,255,255,0.7); margin-left: 6px; flex-shrink: 0; }")
                            .append(".mdd.open .mdd-trig { border-color: #fff; background: #fff; color: #333; }")
                            .append(".mdd-panel { display: none; position: fixed; z-index: 99999; background: #fff; border: 1px solid #ddd; border-radius: 6px; box-shadow: 0 6px 20px rgba(0,0,0,0.18); max-height: 65vh; overflow-y: auto; padding: 2px 0; }")
                            .append(".mdd.open .mdd-panel { display: block; }")
                            .append(".mdd-item { padding: 9px 14px; font-size: 13px; color: #334155; cursor: pointer; user-select: none; }")
                            .append(".mdd-item:hover { background: #eef2ff; }")
                            .append(".mdd-item.sel { background: #4f46e5; color: #fff; font-weight: 600; }")
                            .append(".mdd-sep { padding: 4px 14px 2px; font-size: 10px; color: #94a3b8; font-weight: 600; background: #f8fafc; border-bottom: 1px solid #f1f5f9; }")
                            .append(".split-col { background-color: #fff8e1 !important; }")
                            .append(".split-col-header { background: #ff9800 !important; color: #fff !important; }")
                            .append("</style>")
                            .append("<script>")
                            .append("(function(){")
                            .append("  var openDd=null;")
                            .append("  function closeAll(){if(openDd){openDd.classList.remove('open');openDd=null;}}")
                            .append("  function posPanel(dd){")
                            .append("    var p=dd.querySelector('.mdd-panel'),t=dd.querySelector('.mdd-trig');")
                            .append("    var r=t.getBoundingClientRect();")
                            .append("    var vh=window.innerHeight, vw=window.innerWidth;")
                            .append("    var spaceBelow=vh-r.bottom-8, spaceAbove=r.top-8;")
                            .append("    p.style.minWidth=Math.max(r.width,140)+'px';")
                            .append("    p.style.left=Math.max(4,Math.min(r.left,vw-144))+'px';")
                            .append("    if(spaceBelow>=200){")
                            .append("      p.style.top=(r.bottom+2)+'px'; p.style.maxHeight=Math.min(spaceBelow,vh*0.65)+'px';")
                            .append("    }else if(spaceAbove>spaceBelow){")
                            .append("      p.style.bottom=(vh-r.top+2)+'px'; p.style.top='auto'; p.style.maxHeight=Math.min(spaceAbove,vh*0.65)+'px';")
                            .append("    }else{")
                            .append("      p.style.top=(r.bottom+2)+'px'; p.style.maxHeight=Math.min(Math.max(spaceBelow,160),vh*0.65)+'px';")
                            .append("    }")
                            .append("  }")
                            .append("  function init(){")
                            .append("    document.querySelectorAll('.mdd').forEach(function(dd){")
                            .append("      var trig=dd.querySelector('.mdd-trig'),panel=dd.querySelector('.mdd-panel');")
                            .append("      trig.addEventListener('click',function(e){")
                            .append("        e.stopPropagation();")
                            .append("        var was=dd.classList.contains('open'); closeAll();")
                            .append("        if(!was){dd.classList.add('open');openDd=dd;posPanel(dd);}")
                            .append("      });")
                            .append("      panel.querySelectorAll('.mdd-item').forEach(function(it){")
                            .append("        it.addEventListener('click',function(ev){")
                            .append("          ev.stopPropagation();")
                            .append("          var v=it.getAttribute('data-v'),c=parseInt(dd.getAttribute('data-c'),10);")
                            .append("          trig.childNodes[0].nodeValue=v+' ';")
                            .append("          panel.querySelectorAll('.mdd-item').forEach(function(x){x.classList.remove('sel');});")
                            .append("          it.classList.add('sel'); closeAll();")
                            .append("          if(window.Android) window.Android.updateFieldMapping(c,v);")
                            .append("          var th=document.getElementById('col-'+c);")
                            .append("          if(th){if(v!=='不映射')th.classList.add('mapped');else th.classList.remove('mapped');}")
                            .append("        });")
                            .append("      });")
                            .append("    });")
                            .append("    document.addEventListener('click',closeAll);")
                            .append("    window.addEventListener('scroll',closeAll,true);")
                            .append("    window.addEventListener('resize',closeAll);")
                            .append("  }")
                            .append("  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',init);else init();")
                            .append("})();")
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

                            htmlBuilder.append("<th id=\"col-").append(i).append("\"")
                                       .append(currentField.equals("不映射") ? "" : " class=\"mapped\"")
                                       .append(">")
                                       .append(escapeHtml(cellValue))
                                       .append("<div class=\"mdd\" data-c=\"").append(i).append("\">")
                                       .append("<div class=\"mdd-trig\">")
                                       .append(escapeHtml(currentField)).append(" ")
                                       .append("</div>")
                                       .append("<div class=\"mdd-panel\">");

                            // 精简选项列表，按分组显示
                            // 第1组：不映射
                            htmlBuilder.append("<div class=\"mdd-item").append("不映射".equals(currentField) ? " sel" : "")
                                       .append("\" data-v=\"不映射\">不映射</div>");
                            // 分隔：基础信息
                            htmlBuilder.append("<div class=\"mdd-sep\">基础</div>");
                            String[] basics = {"题目", "题型", "难度", "分值"};
                            for (String b : basics) {
                                htmlBuilder.append("<div class=\"mdd-item").append(b.equals(currentField) ? " sel" : "")
                                           .append("\" data-v=\"").append(b).append("\">").append(b).append("</div>");
                            }
                            // 分隔：选项
                            htmlBuilder.append("<div class=\"mdd-sep\">选项</div>");
                            String[] opts = {"选项A", "选项B", "选项C", "选项D", "选项E", "选项F", "选项(拆分)"};
                            for (String o : opts) {
                                htmlBuilder.append("<div class=\"mdd-item").append(o.equals(currentField) ? " sel" : "")
                                           .append("\" data-v=\"").append(o).append("\">").append(o).append("</div>");
                            }
                            // 分隔：答案
                            htmlBuilder.append("<div class=\"mdd-sep\">答案</div>");
                            String[] answers = {"正确答案", "解析"};
                            for (String a : answers) {
                                htmlBuilder.append("<div class=\"mdd-item").append(a.equals(currentField) ? " sel" : "")
                                           .append("\" data-v=\"").append(a).append("\">").append(a).append("</div>");
                            }
                            // 分隔：分类
                            htmlBuilder.append("<div class=\"mdd-sep\">分类</div>");
                            String[] cats = {"分类", "知识点"};
                            for (String c : cats) {
                                htmlBuilder.append("<div class=\"mdd-item").append(c.equals(currentField) ? " sel" : "")
                                           .append("\" data-v=\"").append(c).append("\">").append(c).append("</div>");
                            }

                            htmlBuilder.append("</div></div></th>");

                            // ── 拆分预览：在拆分列后渲染虚拟选项列 ──
                            if (hasSplitPreview && i == splitColIdx) {
                                char[] vLetters = {'A','B','C','D','E','F','G','H','I','J','K','L'};
                                String[] optNames = {"选项A","选项B","选项C","选项D","选项E","选项F"};
                                for (int vi = 0; vi < virtualColCount && vi < vLetters.length; vi++) {
                                    int virtualColId = 1000 + vi;
                                    String vField = vi < optNames.length ? optNames[vi] : "选项" + vLetters[vi];
                                    htmlBuilder.append("<th id=\"col-").append(virtualColId)
                                               .append("\" class=\"mapped split-col-header\">")
                                               .append("↗ ").append(vField)
                                               .append("<div class=\"mdd\" data-c=\"").append(virtualColId).append("\">")
                                               .append("<div class=\"mdd-trig\">")
                                               .append(vField).append(" ")
                                               .append("</div>")
                                               .append("<div class=\"mdd-panel\">");
                                    String[] vOpts = {"选项A","选项B","选项C","选项D","选项E","选项F","不映射"};
                                    for (String vo : vOpts) {
                                        htmlBuilder.append("<div class=\"mdd-item").append(vo.equals(vField) ? " sel" : "")
                                                   .append("\" data-v=\"").append(vo).append("\">").append(vo).append("</div>");
                                    }
                                    htmlBuilder.append("</div></div></th>");
                                }
                            }
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
                                // ── 拆分预览：在拆分列数据后渲染拆分结果 ──
                                if (hasSplitPreview && j == splitColIdx && !cellValue.isEmpty()) {
                                    String[] parts;
                                    if ("LETTER_PATTERN".equals(splitDelimiter)) {
                                        java.util.List<String> partList = new ArrayList<>();
                                        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                                            "(?s)([A-H])[.、．]\\s*(.*?)(?=\\s*[A-H][.、．]|$)")
                                            .matcher(cellValue);
                                        while (m.find()) {
                                            String content = m.group(2).trim();
                                            if (!content.isEmpty()) partList.add(content);
                                        }
                                        parts = partList.toArray(new String[0]);
                                    } else {
                                        parts = cellValue.split(java.util.regex.Pattern.quote(splitDelimiter), -1);
                                    }
                                    for (int vi = 0; vi < virtualColCount; vi++) {
                                        String partVal = (vi < parts.length) ? parts[vi].trim() : "";
                                        htmlBuilder.append("<td class=\"split-col\">").append(escapeHtml(partVal)).append("</td>");
                                    }
                                }
                            }
                            // 处理拆分列在末尾或为空的情况
                            if (hasSplitPreview && splitColIdx >= cellCount) {
                                for (int vi = 0; vi < virtualColCount; vi++) {
                                    htmlBuilder.append("<td class=\"split-col\"></td>");
                                }
                            } else if (hasSplitPreview && splitColIdx < cellCount) {
                                Cell splitCell = row.getCell(splitColIdx);
                                String splitVal = splitCell != null ? ExcelUtil.getCellValue(splitCell).trim() : "";
                                if (splitVal.isEmpty()) {
                                    for (int vi = 0; vi < virtualColCount; vi++) {
                                        htmlBuilder.append("<td class=\"split-col\"></td>");
                                    }
                                }
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

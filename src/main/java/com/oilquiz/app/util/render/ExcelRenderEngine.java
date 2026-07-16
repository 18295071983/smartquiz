package com.oilquiz.app.util.render;

import android.util.Log;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;

import java.io.File;
import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.*;

public class ExcelRenderEngine implements FileRenderEngine {
    private static final String TAG = "ExcelRenderEngine";
    private static final String[] SUPPORTED_EXTENSIONS = {"xls", "xlsx", "xlsm", "xltx", "xlt"};

    private Map<String, String> themeColors = new HashMap<>();

    public ExcelRenderEngine() {
        initThemeColors();
    }

    private void initThemeColors() {
        themeColors.put("theme-accent-1", "4472c4");
        themeColors.put("theme-accent-2", "5b9bd5");
        themeColors.put("theme-accent-3", "70ad47");
        themeColors.put("theme-accent-4", "ffc000");
        themeColors.put("theme-accent-5", "ed7d31");
        themeColors.put("theme-accent-6", "ff0000");
        themeColors.put("theme-dark-1", "1f497d");
        themeColors.put("theme-dark-2", "2e75b6");
        themeColors.put("theme-light-1", "ffffff");
        themeColors.put("theme-light-2", "d9d9d9");
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
    public void render(File file, RenderCallback callback) {
        try {
            Log.d(TAG, "Rendering Excel document: " + file.getName());

            Workbook workbook = null;
            List<SheetInfo> sheetsInfo = new ArrayList<>();

            try (FileInputStream fis = new FileInputStream(file)) {
                if (file.getName().toLowerCase().endsWith(".xlsx")) {
                    workbook = new XSSFWorkbook(fis);
                } else if (file.getName().toLowerCase().endsWith(".xls")) {
                    workbook = new HSSFWorkbook(fis);
                }

                if (workbook != null) {
                    for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                        Sheet sheet = workbook.getSheetAt(i);
                        SheetInfo info = analyzeSheet(sheet);
                        info.index = i;
                        info.name = sheet.getSheetName() != null ? sheet.getSheetName() : "工作表 " + (i + 1);
                        info.html = renderSheetContent(sheet, info);
                        sheetsInfo.add(info);
                    }
                    workbook.close();
                }
            }

            StringBuilder htmlContent = new StringBuilder();
            htmlContent.append("<!DOCTYPE html>");
            htmlContent.append("<html>");
            htmlContent.append("<head>");
            htmlContent.append("<meta charset='UTF-8'>");
            htmlContent.append("<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes'>");
            htmlContent.append("<style>");
            htmlContent.append(getEnhancedStyles());
            htmlContent.append("</style>");
            htmlContent.append("</head>");
            htmlContent.append("<body>");

            htmlContent.append("<div class='excel-header'>");
            htmlContent.append("<h1>Excel文档</h1>");
            htmlContent.append("<div class='file-info'>");
            htmlContent.append("<span><strong>文件名:</strong> " + escapeHtml(file.getName()) + "</span>");
            htmlContent.append("<span><strong>大小:</strong> " + (file.length() / 1024) + "KB</span>");
            htmlContent.append("<span><strong>工作表:</strong> " + sheetsInfo.size() + "个</span>");
            htmlContent.append("</div>");
            htmlContent.append("</div>");

            htmlContent.append("<div class='toolbar'>");
            htmlContent.append("<div class='zoom-controls'>");
            htmlContent.append("<button onclick='changeZoom(-0.1)' class='zoom-btn'>-</button>");
            htmlContent.append("<span id='zoom-level'>100%</span>");
            htmlContent.append("<button onclick='changeZoom(0.1)' class='zoom-btn'>+</button>");
            htmlContent.append("<button onclick='resetZoom()' class='zoom-btn reset'>重置</button>");
            htmlContent.append("</div>");
            htmlContent.append("<div class='wrap-toggle'>");
            htmlContent.append("<label class='switch'>");
            htmlContent.append("<input type='checkbox' id='wrap-toggle' checked onchange='toggleWrap()'>");
            htmlContent.append("<span class='slider'></span>");
            htmlContent.append("</label>");
            htmlContent.append("<span class='wrap-label'>自动换行</span>");
            htmlContent.append("</div>");
            htmlContent.append("</div>");

            htmlContent.append(renderSheetsList(sheetsInfo));
            htmlContent.append(renderAllSheetsContent(sheetsInfo));
            htmlContent.append(renderJavaScript());

            htmlContent.append("</body>");
            htmlContent.append("</html>");

            callback.onSuccess(htmlContent.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error rendering Excel document: " + e.getMessage(), e);
            callback.onSuccess(renderErrorPage(file, e.getMessage()));
        }
    }

    private SheetInfo analyzeSheet(Sheet sheet) {
        SheetInfo info = new SheetInfo();
        info.name = sheet.getSheetName() != null ? sheet.getSheetName() : "工作表";

        int firstRow = sheet.getFirstRowNum();
        int lastRow = sheet.getLastRowNum();

        int firstCol = Integer.MAX_VALUE;
        int lastCol = -1;

        boolean hasData = false;
        for (int r = firstRow; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
                Cell cell = row.getCell(c);
                if (cell != null && cell.getCellType() != CellType.BLANK) {
                    hasData = true;
                    if (c < firstCol) firstCol = c;
                    if (c > lastCol) lastCol = c;
                }
            }
        }

        if (!hasData) {
            info.firstRow = firstRow;
            info.lastRow = lastRow;
            info.firstCol = 0;
            info.lastCol = 0;
            info.rowCount = 0;
            info.colCount = 0;
            return info;
        }

        info.firstRow = firstRow;
        info.lastRow = lastRow;
        info.firstCol = firstCol;
        info.lastCol = lastCol;
        info.rowCount = lastRow - firstRow + 1;
        info.colCount = lastCol - firstCol + 1;

        info.mergedRegions = new HashSet<>(sheet.getMergedRegions());

        return info;
    }

    private String renderSheetsList(List<SheetInfo> sheetsInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='sheets-list-container'>");
        sb.append("<div class='sheets-list'>");

        for (int i = 0; i < sheetsInfo.size(); i++) {
            SheetInfo sheet = sheetsInfo.get(i);
            sb.append("<button class='sheet-tab");
            if (i == 0) sb.append(" sheet-tab-active");
            sb.append("' onclick=\"showSheet(").append(i).append(")\">");
            sb.append("<span class='sheet-tab-name'>").append(escapeHtml(sheet.name)).append("</span>");
            sb.append("<span class='sheet-tab-meta'>").append(sheet.rowCount).append("行 × ").append(sheet.colCount).append("列</span>");
            sb.append("</button>");
        }

        sb.append("</div>");
        sb.append("</div>");
        return sb.toString();
    }

    private String renderAllSheetsContent(List<SheetInfo> sheetsInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='sheets-container'>");

        for (int i = 0; i < sheetsInfo.size(); i++) {
            SheetInfo sheetInfo = sheetsInfo.get(i);
            sb.append("<div class='sheet-content' id='sheet-").append(i).append("'");
            if (i != 0) sb.append(" style='display:none;'");
            sb.append(">");

            sb.append("<div class='sheet-card'>");
            sb.append("<div class='sheet-header'>");
            sb.append("<h2 class='sheet-title'>").append(escapeHtml(sheetInfo.name)).append("</h2>");
            sb.append("<span class='sheet-meta'>").append(sheetInfo.rowCount).append("行 × ").append(sheetInfo.colCount).append("列</span>");
            sb.append("</div>");
            sb.append(sheetInfo.html);
            sb.append("</div>");

            sb.append("</div>");
        }

        sb.append("</div>");
        return sb.toString();
    }

    private String renderSheetContent(Sheet sheet, SheetInfo sheetInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='table-wrapper'>");
        sb.append("<table class='excel-table' id='excel-table-").append(sheetInfo.index).append("'>");

        for (int rowIdx = sheetInfo.firstRow; rowIdx <= sheetInfo.lastRow; rowIdx++) {
            Row row = sheet.getRow(rowIdx);
            if (row == null) continue;

            boolean isRowHeader = false;
            if (row.getCell(sheetInfo.firstCol) != null) {
                Cell firstCell = row.getCell(sheetInfo.firstCol);
                if (firstCell != null && firstCell.getCellType() == CellType.STRING) {
                    String val = firstCell.getStringCellValue();
                    if (val != null && (val.matches("^[A-Za-z]+$") || val.matches("^第[一二三四五六七八九十]+[组行]?$"))) {
                        isRowHeader = true;
                    }
                }
            }

            sb.append("<tr data-row='").append(rowIdx).append("'>");

            for (int colIdx = sheetInfo.firstCol; colIdx <= sheetInfo.lastCol; colIdx++) {
                boolean skipCell = false;

                for (CellRangeAddress merged : sheetInfo.mergedRegions) {
                    if (merged.isInRange(rowIdx, colIdx)) {
                        if (merged.getFirstRow() == rowIdx && merged.getFirstColumn() == colIdx) {
                            int colspan = merged.getLastColumn() - merged.getFirstColumn() + 1;
                            int rowspan = merged.getLastRow() - merged.getFirstRow() + 1;
                            sb.append("<td");
                            if (colspan > 1) sb.append(" colspan='").append(colspan).append("'");
                            if (rowspan > 1) sb.append(" rowspan='").append(rowspan).append("'");
                            sb.append(" class='merged-cell'");
                        } else {
                            skipCell = true;
                        }
                        break;
                    }
                }

                if (skipCell) {
                    sb.append("<td></td>");
                    continue;
                }

                Cell cell = row.getCell(colIdx);
                String cellClass = "";
                String cellValue = "";

                if (cell != null) {
                    cellValue = getCellDisplayValue(cell);
                    cellClass = getCellClass(cell);
                }

                sb.append("<td data-col='").append(colIdx).append("'");
                if (!cellClass.isEmpty()) sb.append(" class='").append(cellClass).append("'");
                sb.append(">");
                sb.append("<div class='cell-content'>").append(escapeHtml(cellValue)).append("</div>");
                sb.append("<div class='resize-handle' data-resize='col'></div>");
                sb.append("</td>");
            }

            sb.append("<td class='row-resize-handle' data-row='").append(rowIdx).append("'><div class='resize-handle-v' data-resize='row'></div></td>");

            sb.append("</tr>");
        }

        sb.append("</table>");
        sb.append("</div>");
        return sb.toString();
    }

    private String renderJavaScript() {
        StringBuilder sb = new StringBuilder();
        sb.append("<script>");
        sb.append("var currentZoom = 1;");
        sb.append("var autoWrap = true;");

        sb.append("function changeZoom(delta) {");
        sb.append("  currentZoom = Math.max(0.5, Math.min(3, currentZoom + delta));");
        sb.append("  applyZoom();");
        sb.append("}");

        sb.append("function resetZoom() {");
        sb.append("  currentZoom = 1;");
        sb.append("  applyZoom();");
        sb.append("}");

        sb.append("function applyZoom() {");
        sb.append("  document.querySelectorAll('.excel-table').forEach(function(table) {");
        sb.append("    table.style.transform = 'scale(' + currentZoom + ')';");
        sb.append("    table.style.transformOrigin = 'top left';");
        sb.append("    table.style.width = (100 / currentZoom) + '%';");
        sb.append("  });");
        sb.append("  document.getElementById('zoom-level').textContent = Math.round(currentZoom * 100) + '%';");
        sb.append("}");

        sb.append("function toggleWrap() {");
        sb.append("  autoWrap = document.getElementById('wrap-toggle').checked;");
        sb.append("  document.querySelectorAll('.excel-table td, .excel-table th').forEach(function(cell) {");
        sb.append("    var content = cell.querySelector('.cell-content');");
        sb.append("    if (content) {");
        sb.append("      content.style.whiteSpace = autoWrap ? 'normal' : 'nowrap';");
        sb.append("    }");
        sb.append("  });");
        sb.append("}");

        sb.append("function showSheet(index) {");
        sb.append("  var tabs = document.querySelectorAll('.sheet-tab');");
        sb.append("  for (var i = 0; i < tabs.length; i++) {");
        sb.append("    tabs[i].classList.remove('sheet-tab-active');");
        sb.append("  }");
        sb.append("  tabs[index].classList.add('sheet-tab-active');");
        sb.append("  var contents = document.querySelectorAll('.sheet-content');");
        sb.append("  for (var i = 0; i < contents.length; i++) {");
        sb.append("    contents[i].style.display = 'none';");
        sb.append("  }");
        sb.append("  document.getElementById('sheet-' + index).style.display = 'block';");
        sb.append("}");

        sb.append("document.addEventListener('DOMContentLoaded', function() {");
        sb.append("  initResizeHandlers();");
        sb.append("});");

        sb.append("function initResizeHandlers() {");
        sb.append("  var tables = document.querySelectorAll('.excel-table');");
        sb.append("  tables.forEach(function(table) {");

        sb.append("    var resizeHandles = table.querySelectorAll('.resize-handle[data-resize=\"col\"]');");
        sb.append("    resizeHandles.forEach(function(handle) {");
        sb.append("      handle.addEventListener('mousedown', startColResize);");
        sb.append("      handle.addEventListener('touchstart', startColResize, {passive: false});");
        sb.append("    });");

        sb.append("    var rowHandles = table.querySelectorAll('.resize-handle-v[data-resize=\"row\"]');");
        sb.append("    rowHandles.forEach(function(handle) {");
        sb.append("      handle.addEventListener('mousedown', startRowResize);");
        sb.append("      handle.addEventListener('touchstart', startRowResize, {passive: false});");
        sb.append("    });");
        sb.append("  });");
        sb.append("}");

        sb.append("var isResizing = false;");
        sb.append("var resizeType = null;");
        sb.append("var currentCell = null;");
        sb.append("var startX, startY, startWidth, startHeight;");

        sb.append("function startColResize(e) {");
        sb.append("  e.preventDefault();");
        sb.append("  isResizing = true;");
        sb.append("  resizeType = 'col';");
        sb.append("  currentCell = e.target.closest('td');");
        sb.append("  startX = e.pageX || e.touches[0].pageX;");
        sb.append("  startWidth = currentCell.offsetWidth;");
        sb.append("  document.body.classList.add('resizing');");
        sb.append("  document.addEventListener('mousemove', doColResize);");
        sb.append("  document.addEventListener('mouseup', stopResize);");
        sb.append("  document.addEventListener('touchmove', doColResize, {passive: false});");
        sb.append("  document.addEventListener('touchend', stopResize);");
        sb.append("}");

        sb.append("function startRowResize(e) {");
        sb.append("  e.preventDefault();");
        sb.append("  isResizing = true;");
        sb.append("  resizeType = 'row';");
        sb.append("  var rowHandle = e.target.closest('td');");
        sb.append("  startY = e.pageY || e.touches[0].pageY;");
        sb.append("  startHeight = rowHandle.offsetHeight;");
        sb.append("  var row = rowHandle.closest('tr');");
        sb.append("  currentCell = row ? row.cells[0] : null;");
        sb.append("  document.body.classList.add('resizing');");
        sb.append("  document.addEventListener('mousemove', doRowResize);");
        sb.append("  document.addEventListener('mouseup', stopResize);");
        sb.append("  document.addEventListener('touchmove', doRowResize, {passive: false});");
        sb.append("  document.addEventListener('touchend', stopResize);");
        sb.append("}");

        sb.append("function doColResize(e) {");
        sb.append("  if (!isResizing || resizeType !== 'col') return;");
        sb.append("  e.preventDefault();");
        sb.append("  var pageX = e.pageX || e.touches[0].pageX;");
        sb.append("  var diff = pageX - startX;");
        sb.append("  var newWidth = Math.max(40, startWidth + diff);");
        sb.append("  currentCell.style.width = newWidth + 'px';");
        sb.append("  currentCell.style.minWidth = newWidth + 'px';");
        sb.append("}");

        sb.append("function doRowResize(e) {");
        sb.append("  if (!isResizing || resizeType !== 'row') return;");
        sb.append("  e.preventDefault();");
        sb.append("  var pageY = e.pageY || e.touches[0].pageY;");
        sb.append("  var diff = pageY - startY;");
        sb.append("  var newHeight = Math.max(30, startHeight + diff);");
        sb.append("  var row = currentCell ? currentCell.closest('tr') : null;");
        sb.append("  if (row) {");
        sb.append("    var cells = row.querySelectorAll('td');");
        sb.append("    cells.forEach(function(cell) {");
        sb.append("      cell.style.height = newHeight + 'px';");
        sb.append("      cell.style.minHeight = newHeight + 'px';");
        sb.append("    });");
        sb.append("  }");
        sb.append("}");

        sb.append("function stopResize() {");
        sb.append("  isResizing = false;");
        sb.append("  resizeType = null;");
        sb.append("  currentCell = null;");
        sb.append("  document.body.classList.remove('resizing');");
        sb.append("  document.removeEventListener('mousemove', doColResize);");
        sb.append("  document.removeEventListener('mouseup', stopResize);");
        sb.append("  document.removeEventListener('touchmove', doColResize);");
        sb.append("  document.removeEventListener('touchend', stopResize);");
        sb.append("  document.removeEventListener('mousemove', doRowResize);");
        sb.append("  document.removeEventListener('touchmove', doRowResize);");
        sb.append("}");

        sb.append("</script>");
        return sb.toString();
    }

    private String getCellDisplayValue(Cell cell) {
        if (cell == null) return "";

        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
                    return sdf.format(cell.getDateCellValue());
                } else {
                    double value = cell.getNumericCellValue();
                    if (value == Math.floor(value) && !Double.isInfinite(value)) {
                        return String.valueOf((long) value);
                    } else {
                        return String.format(Locale.getDefault(), "%.4f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
                    }
                }
            case BOOLEAN:
                return cell.getBooleanCellValue() ? "是" : "否";
            case FORMULA:
                try {
                    return cell.getCellFormula();
                } catch (Exception e) {
                    return "#错误!";
                }
            case BLANK:
                return "";
            case ERROR:
                return "#错误!";
            default:
                return "";
        }
    }

    private String getCellClass(Cell cell) {
        if (cell == null) return "";

        switch (cell.getCellType()) {
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return "date";
                }
                return "number";
            case BOOLEAN:
                return "boolean";
            case FORMULA:
                return "formula";
            case ERROR:
                return "error-cell";
            default:
                return "";
        }
    }

    private String getEnhancedStyles() {
        StringBuilder styles = new StringBuilder();

        styles.append("body { ");
        styles.append("font-family: 'Microsoft YaHei', 'PingFang SC', 'Hiragino Sans GB', 'Helvetica Neue', Arial, sans-serif; ");
        styles.append("margin: 0; padding: 0; ");
        styles.append("background-color: #").append(themeColors.getOrDefault("theme-light-2", "d9d9d9")).append("; ");
        styles.append("}");

        styles.append(".resizing * { cursor: col-resize !important; user-select: none !important; }");

        styles.append(".excel-header { ");
        styles.append("background: linear-gradient(135deg, #").append(themeColors.getOrDefault("theme-accent-1", "4472c4")).append(" 0%, #").append(themeColors.getOrDefault("theme-dark-2", "2e75b6")).append(" 100%); ");
        styles.append("color: white; padding: 20px; ");
        styles.append("box-shadow: 0 4px 12px rgba(0,0,0,0.15); ");
        styles.append("}");

        styles.append(".excel-header h1 { margin: 0 0 10px 0; font-size: 24px; font-weight: 600; }");
        styles.append(".file-info { display: flex; gap: 20px; font-size: 13px; opacity: 0.95; flex-wrap: wrap; }");
        styles.append(".file-info span { background: rgba(255,255,255,0.15); padding: 4px 12px; border-radius: 4px; }");

        styles.append(".toolbar { ");
        styles.append("background: white; padding: 12px 20px; ");
        styles.append("display: flex; justify-content: space-between; align-items: center; ");
        styles.append("border-bottom: 1px solid #eee; ");
        styles.append("flex-wrap: wrap; gap: 10px; ");
        styles.append("}");

        styles.append(".zoom-controls { display: flex; align-items: center; gap: 8px; }");
        styles.append(".zoom-btn { padding: 8px 16px; border: none; border-radius: 6px; background: #").append(themeColors.getOrDefault("theme-accent-1", "4472c4")).append("; color: white; cursor: pointer; font-size: 18px; transition: all 0.2s; }");
        styles.append(".zoom-btn:hover { background: #").append(themeColors.getOrDefault("theme-dark-2", "2e75b6")).append("; }");
        styles.append(".zoom-btn.reset { font-size: 12px; padding: 8px 12px; }");
        styles.append("#zoom-level { min-width: 50px; text-align: center; font-weight: 600; color: #333; }");

        styles.append(".wrap-toggle { display: flex; align-items: center; gap: 8px; }");
        styles.append(".wrap-label { font-size: 14px; color: #333; }");

        styles.append(".switch { position: relative; width: 48px; height: 26px; }");
        styles.append(".switch input { opacity: 0; width: 0; height: 0; }");
        styles.append(".slider { position: absolute; cursor: pointer; top: 0; left: 0; right: 0; bottom: 0; background-color: #ccc; transition: 0.3s; border-radius: 26px; }");
        styles.append(".slider:before { position: absolute; content: ''; height: 20px; width: 20px; left: 3px; bottom: 3px; background-color: white; transition: 0.3s; border-radius: 50%; }");
        styles.append("input:checked + .slider { background-color: #").append(themeColors.getOrDefault("theme-accent-1", "4472c4")).append("; }");
        styles.append("input:checked + .slider:before { transform: translateX(22px); }");

        styles.append(".sheets-list-container { background: white; border-bottom: 2px solid #eee; position: sticky; top: 0; z-index: 100; box-shadow: 0 2px 8px rgba(0,0,0,0.08); }");
        styles.append(".sheets-list { display: flex; overflow-x: auto; gap: 4px; padding: 12px 20px; -webkit-overflow-scrolling: touch; }");

        styles.append(".sheet-tab { ");
        styles.append("display: flex; flex-direction: column; gap: 4px; ");
        styles.append("padding: 10px 16px; border: none; border-radius: 8px; ");
        styles.append("background: #f8f9fa; color: #666; cursor: pointer; white-space: nowrap; transition: all 0.2s; min-width: 90px; align-items: center; ");
        styles.append("}");

        styles.append(".sheet-tab:hover { background: #e9ecef; color: #444; }");
        styles.append(".sheet-tab-active { ");
        styles.append("background: linear-gradient(135deg, #").append(themeColors.getOrDefault("theme-accent-1", "4472c4")).append(" 0%, #").append(themeColors.getOrDefault("theme-dark-2", "2e75b6")).append(" 100%); ");
        styles.append("color: white; box-shadow: 0 2px 8px rgba(68, 114, 196, 0.3); ");
        styles.append("}");

        styles.append(".sheet-tab-name { font-size: 13px; font-weight: 600; }");
        styles.append(".sheet-tab-meta { font-size: 10px; opacity: 0.8; }");

        styles.append(".sheets-container { padding: 20px; max-width: 1400px; margin: 0 auto; }");
        styles.append(".sheet-content { animation: fadeIn 0.3s ease; }");

        styles.append("@keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }");

        styles.append(".sheet-card { background: white; border-radius: 12px; box-shadow: 0 4px 15px rgba(0,0,0,0.1); overflow: hidden; }");

        styles.append(".sheet-header { ");
        styles.append("background: linear-gradient(135deg, #").append(themeColors.getOrDefault("theme-dark-1", "1f497d")).append(" 0%, #").append(themeColors.getOrDefault("theme-dark-2", "2e75b6")).append(" 100%); ");
        styles.append("color: white; padding: 16px 20px; ");
        styles.append("display: flex; justify-content: space-between; align-items: center; ");
        styles.append("}");

        styles.append(".sheet-title { font-size: 18px; font-weight: 600; margin: 0; }");
        styles.append(".sheet-meta { font-size: 13px; opacity: 0.9; background: rgba(255,255,255,0.15); padding: 4px 12px; border-radius: 4px; }");

        styles.append(".table-wrapper { overflow: auto; max-height: 70vh; padding: 0; position: relative; }");

        styles.append(".excel-table { ");
        styles.append("border-collapse: collapse; ");
        styles.append("width: 100%; ");
        styles.append("table-layout: auto; ");
        styles.append("}");

        styles.append(".excel-table td, .excel-table th { ");
        styles.append("padding: 0; ");
        styles.append("border: 1px solid #e0e0e0; ");
        styles.append("text-align: left; ");
        styles.append("vertical-align: middle; ");
        styles.append("font-size: 14px; ");
        styles.append("min-width: 80px; ");
        styles.append("width: 120px; ");
        styles.append("min-height: 40px; ");
        styles.append("height: 40px; ");
        styles.append("position: relative; ");
        styles.append("transition: background-color 0.2s; ");
        styles.append("}");

        styles.append(".cell-content { ");
        styles.append("padding: 10px 12px; ");
        styles.append("white-space: normal; ");
        styles.append("word-wrap: break-word; ");
        styles.append("overflow: hidden; ");
        styles.append("text-overflow: ellipsis; ");
        styles.append("}");

        styles.append(".excel-table th { ");
        styles.append("background: linear-gradient(135deg, #").append(themeColors.getOrDefault("theme-accent-1", "4472c4")).append(" 0%, #").append(themeColors.getOrDefault("theme-dark-2", "2e75b6")).append(" 100%); ");
        styles.append("color: white; font-weight: 600; ");
        styles.append("position: sticky; top: 0; z-index: 10; ");
        styles.append("}");

        styles.append(".excel-table tbody tr:nth-child(even) { background-color: #f9f9f9; }");
        styles.append(".excel-table tbody tr:hover { background-color: #e8f4fc; }");

        styles.append(".excel-table .row-header { background-color: #ecf0f1; font-weight: bold; }");
        styles.append(".excel-table .merged-cell { background-color: #fff9e6; }");

        styles.append(".excel-table .formula .cell-content { ");
        styles.append("color: #").append(themeColors.getOrDefault("theme-accent-4", "ffc000")).append("; ");
        styles.append("font-family: 'Consolas', 'Monaco', monospace; font-size: 13px; ");
        styles.append("background: #fff8e1; padding: 2px 6px; border-radius: 3px; ");
        styles.append("}");

        styles.append(".excel-table .number .cell-content { text-align: right; font-variant-numeric: tabular-nums; }");
        styles.append(".excel-table .date .cell-content { color: #").append(themeColors.getOrDefault("theme-accent-3", "70ad47")).append("; }");
        styles.append(".excel-table .boolean .cell-content { color: #").append(themeColors.getOrDefault("theme-accent-1", "4472c4")).append("; font-weight: bold; }");
        styles.append(".excel-table .error-cell .cell-content { color: #").append(themeColors.getOrDefault("theme-accent-6", "ff0000")).append("; font-style: italic; }");

        styles.append(".resize-handle { ");
        styles.append("position: absolute; ");
        styles.append("right: 0; ");
        styles.append("top: 0; ");
        styles.append("bottom: 0; ");
        styles.append("width: 8px; ");
        styles.append("cursor: col-resize; ");
        styles.append("background: rgba(68, 114, 196, 0.3); ");
        styles.append("opacity: 0; ");
        styles.append("transition: opacity 0.2s; ");
        styles.append("z-index: 5; ");
        styles.append("}");

        styles.append(".resize-handle:hover { ");
        styles.append("opacity: 1; ");
        styles.append("background: rgba(68, 114, 196, 0.6); ");
        styles.append("}");

        styles.append(".excel-table td:hover .resize-handle, .excel-table th:hover .resize-handle { ");
        styles.append("opacity: 1; ");
        styles.append("}");

        styles.append(".row-resize-handle { ");
        styles.append("width: 20px; ");
        styles.append("min-width: 20px; ");
        styles.append("background: #f0f0f0; ");
        styles.append("border-left: 2px solid #ddd; ");
        styles.append("cursor: row-resize; ");
        styles.append("}");

        styles.append(".resize-handle-v { ");
        styles.append("width: 100%; ");
        styles.append("height: 8px; ");
        styles.append("cursor: row-resize; ");
        styles.append("background: rgba(68, 114, 196, 0.3); ");
        styles.append("opacity: 0; ");
        styles.append("transition: opacity 0.2s; ");
        styles.append("position: absolute; ");
        styles.append("bottom: 0; ");
        styles.append("left: 0; ");
        styles.append("}");

        styles.append(".row-resize-handle:hover { ");
        styles.append("background: #e0e0e0; ");
        styles.append("}");

        styles.append(".row-resize-handle:hover .resize-handle-v { ");
        styles.append("opacity: 1; ");
        styles.append("}");

        styles.append("@media (max-width: 600px) { ");
        styles.append(".excel-header { padding: 16px; } ");
        styles.append(".excel-header h1 { font-size: 20px; } ");
        styles.append(".file-info { gap: 10px; } ");
        styles.append(".toolbar { padding: 10px 16px; } ");
        styles.append(".sheets-list { padding: 8px 16px; } ");
        styles.append(".sheet-tab { padding: 8px 12px; min-width: 70px; } ");
        styles.append(".sheet-tab-name { font-size: 12px; } ");
        styles.append(".sheet-tab-meta { font-size: 9px; } ");
        styles.append(".sheets-container { padding: 16px; } ");
        styles.append(".excel-table td, .excel-table th { min-width: 60px; width: 100px; font-size: 12px; } ");
        styles.append(".cell-content { padding: 8px 6px; } ");
        styles.append("}");

        styles.append("@media (orientation: landscape) { ");
        styles.append(".table-wrapper { max-height: 60vh; } ");
        styles.append("}");

        return styles.toString();
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String renderErrorPage(File file, String errorMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        sb.append("<style>body{font-family:Arial,sans-serif;margin:20px;background:#f5f5f5;}");
        sb.append(".container{background:white;padding:20px;border-radius:8px;max-width:800px;margin:0 auto;}");
        sb.append("h1{color:#333;}p{color:#666;}.error{color:#e74c3c;background:#fdf2f2;padding:10px;border-radius:4px;}</style>");
        sb.append("</head><body><div class='container'>");
        sb.append("<h1>Excel文档</h1>");
        sb.append("<p><strong>文件名:</strong> " + escapeHtml(file.getName()) + "</p>");
        sb.append("<p><strong>大小:</strong> " + (file.length() / 1024) + "KB</p>");
        sb.append("<div class='error'><p>无法读取Excel文件内容: " + escapeHtml(errorMessage) + "</p></div>");
        sb.append("</div></body></html>");
        return sb.toString();
    }

    @Override
    public String getEngineName() {
        return "Excel文档渲染引擎";
    }

    @Override
    public String getFileTypeDescription(File file) {
        String fileName = file.getName().toLowerCase();
        if (fileName.endsWith(".xlsx")) return "Excel文档 (XLSX)";
        else if (fileName.endsWith(".xls")) return "Excel文档 (XLS)";
        else if (fileName.endsWith(".xlsm")) return "Excel宏文档 (XLSM)";
        return "Excel文档";
    }

    private static class SheetInfo {
        int index;
        String name;
        int firstRow;
        int lastRow;
        int firstCol;
        int lastCol;
        int rowCount;
        int colCount;
        Set<CellRangeAddress> mergedRegions;
        String html;
    }
}
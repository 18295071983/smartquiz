package com.oilquiz.app.util.render;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.oilquiz.app.R;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Excel文件渲染器 - 用于渲染Excel文件的全部数据
 * 支持大数据量的高效渲染，提供进度反馈
 */
public class ExcelRenderer {
    private static final String TAG = "ExcelRenderer";
    
    // 线程池配置
    private static final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    /**
     * 渲染回调接口
     */
    public interface RenderCallback {
        void onRenderStart();
        void onRenderProgress(int current, int total);
        void onRenderComplete(List<List<String>> data, List<String> headers);
        void onRenderError(String message);
    }
    
    /**
     * 渲染Excel文件的全部数据
     * @param file Excel文件
     * @param sheetIndex 工作表索引
     * @param callback 渲染回调
     */
    public static void renderExcel(File file, int sheetIndex, RenderCallback callback) {
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
                
                List<List<String>> dataRows = new ArrayList<>();
                List<String> headers = new ArrayList<>();
                
                try (Workbook workbook = WorkbookFactory.create(file)) {
                    Sheet sheet = workbook.getSheetAt(sheetIndex);
                    int rowCount = sheet.getLastRowNum();
                    
                    // 读取表头
                    Row headerRow = sheet.getRow(0);
                    if (headerRow != null) {
                        int cellCount = headerRow.getLastCellNum();
                        for (int i = 0; i < cellCount; i++) {
                            Cell cell = headerRow.getCell(i);
                            if (cell != null) {
                                headers.add(ExcelUtil.getCellValue(cell).trim());
                            } else {
                                headers.add("列 " + (i + 1));
                            }
                        }
                    }
                    
                    // 读取全部数据行
                    for (int i = 1; i <= rowCount; i++) {
                        Row row = sheet.getRow(i);
                        if (row != null) {
                            List<String> rowData = new ArrayList<>();
                            int cellCount = row.getLastCellNum();
                            for (int j = 0; j < cellCount; j++) {
                                Cell cell = row.getCell(j);
                                if (cell != null) {
                                    rowData.add(ExcelUtil.getCellValue(cell).trim());
                                } else {
                                    rowData.add("");
                                }
                            }
                            dataRows.add(rowData);
                        }
                        
                        // 每处理100行更新一次进度
                        if (i % 100 == 0 && callback != null) {
                            final int current = i;
                            final int total = rowCount;
                            mainHandler.post(() -> callback.onRenderProgress(current, total));
                        }
                    }
                }
                
                // 渲染完成
                if (callback != null) {
                    final List<List<String>> finalDataRows = dataRows;
                    final List<String> finalHeaders = headers;
                    mainHandler.post(() -> callback.onRenderComplete(finalDataRows, finalHeaders));
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error rendering Excel file: " + e.getMessage(), e);
                if (callback != null) {
                    final String errorMessage = "渲染失败: " + e.getMessage();
                    mainHandler.post(() -> callback.onRenderError(errorMessage));
                }
            }
        });
    }
    
    /**
     * 渲染Excel文件的指定范围数据
     * @param file Excel文件
     * @param sheetIndex 工作表索引
     * @param startRow 开始行
     * @param endRow 结束行
     * @param callback 渲染回调
     */
    public static void renderExcelRange(File file, int sheetIndex, int startRow, int endRow, RenderCallback callback) {
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
                
                List<List<String>> dataRows = new ArrayList<>();
                List<String> headers = new ArrayList<>();
                
                try (Workbook workbook = WorkbookFactory.create(file)) {
                    Sheet sheet = workbook.getSheetAt(sheetIndex);
                    int rowCount = sheet.getLastRowNum();
                    
                    // 读取表头
                    Row headerRow = sheet.getRow(0);
                    if (headerRow != null) {
                        int cellCount = headerRow.getLastCellNum();
                        for (int i = 0; i < cellCount; i++) {
                            Cell cell = headerRow.getCell(i);
                            if (cell != null) {
                                headers.add(ExcelUtil.getCellValue(cell).trim());
                            } else {
                                headers.add("列 " + (i + 1));
                            }
                        }
                    }
                    
                    // 读取指定范围的数据行
                    int actualEndRow = Math.min(endRow, rowCount);
                    for (int i = startRow; i <= actualEndRow; i++) {
                        Row row = sheet.getRow(i);
                        if (row != null) {
                            List<String> rowData = new ArrayList<>();
                            int cellCount = row.getLastCellNum();
                            for (int j = 0; j < cellCount; j++) {
                                Cell cell = row.getCell(j);
                                if (cell != null) {
                                    rowData.add(ExcelUtil.getCellValue(cell).trim());
                                } else {
                                    rowData.add("");
                                }
                            }
                            dataRows.add(rowData);
                        }
                        
                        // 每处理50行更新一次进度
                        if (i % 50 == 0 && callback != null) {
                            final int current = i - startRow;
                            final int total = actualEndRow - startRow;
                            mainHandler.post(() -> callback.onRenderProgress(current, total));
                        }
                    }
                }
                
                // 渲染完成
                if (callback != null) {
                    final List<List<String>> finalDataRows = dataRows;
                    final List<String> finalHeaders = headers;
                    mainHandler.post(() -> callback.onRenderComplete(finalDataRows, finalHeaders));
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error rendering Excel range: " + e.getMessage(), e);
                if (callback != null) {
                    final String errorMessage = "渲染失败: " + e.getMessage();
                    mainHandler.post(() -> callback.onRenderError(errorMessage));
                }
            }
        });
    }
    
    /**
     * 计算Excel文件的总行数
     * @param file Excel文件
     * @param sheetIndex 工作表索引
     * @return 总行数
     */
    public static int getRowCount(File file, int sheetIndex) {
        if (file == null || !file.exists()) {
            Log.e(TAG, "File is null or does not exist");
            return 0;
        }
        try (Workbook workbook = WorkbookFactory.create(file)) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            return sheet.getLastRowNum();
        } catch (Exception e) {
            Log.e(TAG, "Error getting row count: " + e.getMessage(), e);
            return 0;
        }
    }
    
    /**
     * 计算Excel文件的总列数
     * @param file Excel文件
     * @param sheetIndex 工作表索引
     * @return 总列数
     */
    public static int getColumnCount(File file, int sheetIndex) {
        if (file == null || !file.exists()) {
            Log.e(TAG, "File is null or does not exist");
            return 0;
        }
        try (Workbook workbook = WorkbookFactory.create(file)) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            Row headerRow = sheet.getRow(0);
            if (headerRow != null) {
                return headerRow.getLastCellNum();
            }
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "Error getting column count: " + e.getMessage(), e);
            return 0;
        }
    }
}

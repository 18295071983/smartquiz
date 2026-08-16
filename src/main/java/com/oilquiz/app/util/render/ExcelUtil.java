package com.oilquiz.app.util.render;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.oilquiz.app.ai.importing.FieldMappingRegistry;
import com.oilquiz.app.model.Question;
import com.oilquiz.app.util.CharsetDetector;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public class ExcelUtil {
    private static final String TAG = "ExcelUtil";
    
    public enum FileFormat {
        EXCEL, CSV, JSON, WORD, PDF, MARKDOWN, UNKNOWN
    }
    
    public static class ImportHistoryItem {
        public String fileName;
        public FileFormat fileFormat;
        public int totalQuestions;
        public int validQuestions;
        public long importTime;
        public long timestamp;
    }
    
    public static class ImportTemplate {
        public String templateName;
        public Map<String, Integer> finalFieldMapping;
    }
    
    public interface DatabaseValidator {
        boolean isQuestionExists(Question question);
        String getQuestionUniqueId(Question question);
    }
    
    public static class SheetInfo {
        public String sheetName;
        public int sheetIndex;
        public int rowCount;
        public int columnCount;
    }
    
    public static class ImportPreviewItem {
        public Question question;
        public boolean isValid;
        public boolean isDuplicate;
        public String errorMessage;
        public int rowNumber;
    }
    
    public static class ImportConfirmation {
        public List<ImportPreviewItem> previewItems;
        public int totalItems;
        public int validItems;
        public int invalidItems;
        public int duplicateItems;
        public Map<String, Integer> fieldMapping;
        public String selectedSheetName;
    }
    
    public interface ImportConfirmationCallback {
        void onConfirmationReady(ImportConfirmation confirmation);
        void onError(String message);
        void onProgress(int current, int total);
    }
    
    public static class ImportStatistics {
        public int totalImports;
        public int successfulImports;
        public int failedImports;
        public long totalImportTime;
        public int totalQuestionsImported;
    }
    
    public static class ImportPreviewResult {
        public int totalRows;
        public int validRows;
        public int invalidRows;
        public int duplicateRows;
        public List<Question> sampleQuestions;
        public Map<String, Integer> finalFieldMapping;
        public List<String> errorMessages;
    }
    
    public interface ImportPreviewCallback {
        void onPreviewComplete(ImportPreviewResult result);
        void onPreviewError(String message);
        void onPreviewProgress(int current, int total);
    }
    
    public interface ImportStatusCallback {
        void onStatusChange(ImportStatus status);
        void onError(String message);
    }
    
    public enum ImportStatus {
        IDLE, PREPARING, PREVIEWING, IMPORTING, COMPLETED, FAILED, CANCELLED
    }
    
    public interface ImportCallback {
        void onProgress(int current, int total);
        void onError(String message);
        void onComplete(List<Question> questions, ImportResult result);
    }
    
    public static class ValidationRule implements java.io.Serializable {
        public String ruleName;
        public String fieldName;
        public String operator;
        public String value;
        public String errorMessage;
        public boolean enabled;
    }
    
    public static class ImportSettings implements java.io.Serializable {
        public boolean overwriteExisting;
        public boolean enableFuzzyMatch;
        public boolean autoDetectHeaders;
        public boolean skipInvalidQuestions;
        public int maxQuestions;
        public int batchSize;
        public boolean enableBatchProcessing;
        public int memoryCheckInterval;
        public boolean saveMapping;
        public String mappingProfileName;
        public boolean useSavedMapping;
        public boolean enableDataValidation;
        public boolean enableAdvancedMapping;
        public boolean enableStatistics;
        public int previewRowCount;
        public boolean enableParallelProcessing;
        public boolean autoCorrectEmptyCells;
        public boolean skipEmptyQuestions;
        public String defaultQuestionType;
        public int defaultDifficulty;
        public String defaultQuestion;
        public String defaultOption;
        public String defaultAnswer;
        public String optionsDelimiter;
        /** 拆分部分自定义映射：拆分部分索引(0-based) → 选项字段名（如"选项A"），为null时按A~L顺序分配 */
        public Map<Integer, String> splitPartMapping;
        public List<ValidationRule> customValidationRules;
    }
    
    public static class ErrorInfo {
        public int rowNumber;
        public String fieldName;
        public String errorType;
        public String errorMessage;
        public Map<String, Object> fieldValues;
        public String questionType;
    }
    
    public enum DataIssueType {
        EMPTY_REQUIRED_FIELD,
        INVALID_VALUE,
        MISSING_QUESTION_TYPE,
        MISSING_QUESTION_TEXT,
        MISSING_CORRECT_ANSWER
    }
    
    public static class DataIssueItem implements java.io.Serializable {
        public int rowNumber;
        public DataIssueType issueType;
        public String fieldName;
        public String currentValue;
        public String suggestedValue;
        public String userCorrectedValue;
        public boolean isResolved;
        public List<String> suggestedValuesList;
        
        public DataIssueItem() {
            rowNumber = 0;
            issueType = DataIssueType.EMPTY_REQUIRED_FIELD;
            fieldName = "";
            currentValue = "";
            suggestedValue = "";
            userCorrectedValue = "";
            isResolved = false;
            suggestedValuesList = new ArrayList<>();
        }
        
        public String getIssueDescription() {
            switch (issueType) {
                case EMPTY_REQUIRED_FIELD:
                    return "必填字段 '" + fieldName + "' 为空";
                case INVALID_VALUE:
                    return "字段 '" + fieldName + "' 的值无效";
                case MISSING_QUESTION_TYPE:
                    return "缺少题型";
                case MISSING_QUESTION_TEXT:
                    return "缺少题目内容";
                case MISSING_CORRECT_ANSWER:
                    return "缺少正确答案";
                default:
                    return "未知数据问题";
            }
        }
    }
    
    public static class DataIssueReport implements java.io.Serializable {
        public List<DataIssueItem> issues;
        public Map<Integer, List<DataIssueItem>> issuesByRow;
        public int totalIssues;
        public int resolvedIssues;
        
        public DataIssueReport() {
            issues = new ArrayList<>();
            issuesByRow = new HashMap<>();
            totalIssues = 0;
            resolvedIssues = 0;
        }
        
        public void addIssue(DataIssueItem issue) {
            issues.add(issue);
            totalIssues++;
            if (!issuesByRow.containsKey(issue.rowNumber)) {
                issuesByRow.put(issue.rowNumber, new ArrayList<>());
            }
            issuesByRow.get(issue.rowNumber).add(issue);
        }
        
        public List<DataIssueItem> getIssuesByRow(int rowNumber) {
            return issuesByRow.getOrDefault(rowNumber, new ArrayList<>());
        }
        
        public void updateResolvedCount() {
            resolvedIssues = 0;
            for (DataIssueItem issue : issues) {
                if (issue.isResolved) {
                    resolvedIssues++;
                }
            }
        }
    }
    
    public static class ImportResult {
        public int totalQuestions;
        public int validQuestions;
        public int invalidQuestions;
        public List<ErrorInfo> errorInfos;
        public List<String> errorMessages;
        public Map<String, Integer> errorTypeCount;
        public long importTime;
        public int skippedQuestions;
        public String summary;
        /** 用户是否取消了导入（取消时不保存任何部分数据） */
        public boolean cancelled;

        public ImportResult() {
            totalQuestions = 0;
            validQuestions = 0;
            invalidQuestions = 0;
            errorInfos = new ArrayList<>();
            errorMessages = new ArrayList<>();
            errorTypeCount = new HashMap<>();
            importTime = 0;
            skippedQuestions = 0;
            summary = "";
            cancelled = false;
        }
    }
    
    public static class ValidationResult {
        public boolean isValid;
        public String errorMessage;
        public Question question;
        public int rowNumber;
        public Map<String, Object> fieldValues;
        
        public ValidationResult() {
            isValid = false;
            errorMessage = "";
            question = new Question();
            rowNumber = 0;
            fieldValues = new HashMap<>();
        }
    }
    
    public static void importExcel(File file, ImportCallback callback) {
        // 委托完整导入逻辑（无字段映射时仍可分析文件结构/统计）
        importExcel(file, 0, null, new ImportSettings(), null, null, null, null, callback);
    }
    
    public static void importExcel(File file, ImportSettings finalSettings, ImportCallback callback) {
        importExcel(file, 0, null, finalSettings, null, null, null, null, callback);
    }
    
    public static void setDatabaseValidator(DatabaseValidator validator) {
    }
    
    public static List<SheetInfo> getExcelSheets(File file) {
        List<SheetInfo> sheets = new ArrayList<>();
        
        if (file == null || !file.exists()) {
            return sheets;
        }

        // CSV/JSON 虚拟为单工作表
        FileFormat fmt = detectFileFormat(file);
        if (fmt == FileFormat.CSV) {
            try {
                List<List<String>> rows = readCsvFile(file);
                SheetInfo info = new SheetInfo();
                info.sheetName = "CSV数据";
                info.sheetIndex = 0;
                info.rowCount = rows.size();
                info.columnCount = rows.isEmpty() ? 0 : rows.get(0).size();
                sheets.add(info);
                return sheets;
            } catch (Exception e) { Log.w(TAG, "读取CSV工作表信息失败: " + e.getMessage()); }
        } else if (fmt == FileFormat.JSON) {
            try {
                String raw = readJsonFile(file);
                int count = 0;
                int cols = 0;
                try {
                    org.json.JSONArray arr = new org.json.JSONArray(raw);
                    count = arr.length();
                    if (count > 0) {
                        org.json.JSONObject first = arr.getJSONObject(0);
                        cols = first.length();
                    }
                } catch (Exception e) {
                    try {
                        org.json.JSONObject obj = new org.json.JSONObject(raw);
                        count = 1; cols = obj.length();
                    } catch (Exception ignore) {}
                }
                SheetInfo info = new SheetInfo();
                info.sheetName = "JSON数据";
                info.sheetIndex = 0;
                info.rowCount = count + 1; // +1 表头
                info.columnCount = cols;
                sheets.add(info);
                return sheets;
            } catch (Exception e) { Log.w(TAG, "读取JSON工作表信息失败: " + e.getMessage()); }
        }
        
        FileInputStream fis = null;
        Workbook workbook = null;
        
        try {
            fis = new FileInputStream(file);
            workbook = WorkbookFactory.create(fis);
            
            // 获取所有工作表信息
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                SheetInfo info = new SheetInfo();
                info.sheetName = sheet.getSheetName();
                info.sheetIndex = i;
                info.rowCount = sheet.getLastRowNum() + 1; // 包括表头
                info.columnCount = 0;
                
                // 获取列数（从第一行获取）
                Row firstRow = sheet.getRow(0);
                if (firstRow != null) {
                    info.columnCount = firstRow.getLastCellNum();
                }
                
                sheets.add(info);
            }
            
        } catch (IOException e) {
            Log.e(TAG, "Error reading Excel file: " + e.getMessage(), e);
        } finally {
            try {
                if (workbook != null) {
                    workbook.close();
                }
                if (fis != null) {
                    fis.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error closing resources: " + e.getMessage(), e);
            }
        }
        
        return sheets;
    }
    
    public static List<String> getExcelColumnHeaders(File file, int sheetIndex) {
        List<String> headers = new ArrayList<>();
        
        if (file == null || !file.exists()) {
            return headers;
        }

        // CSV 直接读第一行；JSON 取第一个对象的 key
        FileFormat fmt = detectFileFormat(file);
        if (fmt == FileFormat.CSV) {
            return getCsvHeaders(file);
        } else if (fmt == FileFormat.JSON) {
            try {
                String raw = readJsonFile(file);
                org.json.JSONArray arr;
                try {
                    arr = new org.json.JSONArray(raw);
                } catch (Exception e) {
                    org.json.JSONObject obj = new org.json.JSONObject(raw);
                    arr = new org.json.JSONArray(); arr.put(obj);
                }
                if (arr.length() > 0) {
                    org.json.JSONObject first = arr.getJSONObject(0);
                    java.util.Iterator<String> it = first.keys();
                    while (it.hasNext()) headers.add(it.next());
                }
                return headers;
            } catch (Exception e) { Log.w(TAG, "读取JSON表头失败: " + e.getMessage()); return headers; }
        }
        
        FileInputStream fis = null;
        Workbook workbook = null;
        
        try {
            fis = new FileInputStream(file);
            workbook = WorkbookFactory.create(fis);
            
            // 获取指定工作表
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            if (sheet == null) {
                return headers;
            }
            
            // 获取第一行（表头行）
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                return headers;
            }
            
            // 读取所有列标题
            for (int i = 0; i < headerRow.getLastCellNum(); i++) {
                Cell cell = headerRow.getCell(i);
                String headerValue = getCellValueAsString(cell);
                headers.add(headerValue);
            }
            
        } catch (IOException e) {
            Log.e(TAG, "Error reading Excel file: " + e.getMessage(), e);
        } finally {
            try {
                if (workbook != null) {
                    workbook.close();
                }
                if (fis != null) {
                    fis.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error closing resources: " + e.getMessage(), e);
            }
        }
        
        return headers;
    }
    
    /**
     * 将单元格值转换为字符串
     */
    private static String getCellValueAsString(Cell cell) {
        if (cell == null) {
            return "";
        }
        
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue().trim();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                } else {
                    return formatNumericValue(cell.getNumericCellValue());
                }
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                // 公式单元格：读取文件内缓存的计算结果（Excel 保存时通常带缓存值）。
                // 不能直接用 getStringCellValue/getNumericCellValue —— 对 FORMULA 类型一律抛异常，
                // 必须按 getCachedFormulaResultType() 分派取值，否则公式列导入/预览恒为空。
                try {
                    switch (cell.getCachedFormulaResultType()) {
                        case STRING:
                            return cell.getRichStringCellValue().getString().trim();
                        case NUMERIC:
                            return formatNumericValue(cell.getNumericCellValue());
                        case BOOLEAN:
                            return String.valueOf(cell.getBooleanCellValue());
                        default:
                            return "";
                    }
                } catch (Exception e) {
                    return "";
                }
            case BLANK:
                return "";
            default:
                return "";
        }
    }

    /**
     * 数字格式化：整数值不带小数点；小数用 BigDecimal 十进制化，
     * 避免科学计数法（如 1.0E-7）和二进制浮点尾巴（如 0.30000000000000004）。
     */
    private static String formatNumericValue(double value) {
        if (!Double.isFinite(value)) return "";
        long l = (long) value;
        if (l == value) {
            return String.valueOf(l);
        }
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
    
    public static List<List<String>> readExcelData(File file, int sheetIndex, int maxRows) {
        List<List<String>> data = new ArrayList<>();
        
        if (file == null || !file.exists()) {
            return data;
        }

        // CSV 走 readCsvData；JSON 转成二维表格（首行key、后续行值）
        FileFormat fmt = detectFileFormat(file);
        if (fmt == FileFormat.CSV) {
            return readCsvData(file, maxRows);
        } else if (fmt == FileFormat.JSON) {
            try {
                List<String> headers = getExcelColumnHeaders(file, sheetIndex);
                if (headers.isEmpty()) return data;
                String raw = readJsonFile(file);
                org.json.JSONArray arr;
                try {
                    arr = new org.json.JSONArray(raw);
                } catch (Exception e) {
                    org.json.JSONObject obj = new org.json.JSONObject(raw);
                    arr = new org.json.JSONArray(); arr.put(obj);
                }
                int count = (maxRows <= 0) ? arr.length() : Math.min(arr.length(), maxRows);
                for (int i = 0; i < count; i++) {
                    List<String> row = new ArrayList<>();
                    org.json.JSONObject jo = arr.getJSONObject(i);
                    for (String h : headers) row.add(jo.optString(h, ""));
                    data.add(row);
                }
                return data;
            } catch (Exception e) { Log.w(TAG, "读取JSON数据失败: " + e.getMessage()); return data; }
        }
        
        FileInputStream fis = null;
        Workbook workbook = null;
        
        try {
            fis = new FileInputStream(file);
            workbook = WorkbookFactory.create(fis);
            
            // 获取指定工作表
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            if (sheet == null) {
                return data;
            }
            
            // 读取数据行（从第二行开始，第一行是表头）
            int rowCount = 0;
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                if (maxRows > 0 && rowCount >= maxRows) {
                    break;
                }
                
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                
                List<String> rowData = new ArrayList<>();
                for (int j = 0; j < row.getLastCellNum(); j++) {
                    Cell cell = row.getCell(j);
                    String cellValue = getCellValueAsString(cell);
                    rowData.add(cellValue);
                }
                
                data.add(rowData);
                rowCount++;
            }
            
        } catch (IOException e) {
            Log.e(TAG, "Error reading Excel file: " + e.getMessage(), e);
        } finally {
            try {
                if (workbook != null) {
                    workbook.close();
                }
                if (fis != null) {
                    fis.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error closing resources: " + e.getMessage(), e);
            }
        }
        
        return data;
    }

    /**
     * 带行号读取（用于数据检测/修正）：
     * 返回 [行号(显示), List&lt;String&gt;] 列表。
     * - Excel：物理行号 + 1（空行保留占位，与 importExcel 的修正行号一致）
     * - CSV/JSON：序号 + 1（无空行概念）
     */
    private static List<Object[]> readExcelRowsWithRowNumbers(File file, int sheetIndex) {
        List<Object[]> rows = new ArrayList<>();
        if (file == null || !file.exists()) return rows;

        FileFormat fmt = detectFileFormat(file);
        if (fmt == FileFormat.CSV) {
            List<List<String>> data = readCsvData(file, 0);
            // CSV：readCsvData 含表头，数据从序号 1 开始；行号 = 序号+1（与 importCsv 的修正行号一致）
            for (int i = 0; i < data.size(); i++) {
                rows.add(new Object[]{i + 1, data.get(i)});
            }
            return rows;
        } else if (fmt == FileFormat.JSON) {
            List<List<String>> data = readExcelData(file, sheetIndex, 0);
            // JSON：无表头，序号+1（与 importJson 的修正行号一致）
            for (int i = 0; i < data.size(); i++) {
                rows.add(new Object[]{i + 1, data.get(i)});
            }
            return rows;
        }

        FileInputStream fis = null;
        Workbook workbook = null;
        try {
            fis = new FileInputStream(file);
            workbook = WorkbookFactory.create(fis);
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            if (sheet == null) return rows;
            // 物理行号遍历（空行也占位，保持行号 = 物理行号+1）
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                List<String> rowData = new ArrayList<>();
                if (row != null) {
                    for (int j = 0; j < row.getLastCellNum(); j++) {
                        rowData.add(getCellValueAsString(row.getCell(j)));
                    }
                }
                rows.add(new Object[]{i + 1, rowData});
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading rows with row numbers: " + e.getMessage(), e);
        } finally {
            try {
                if (workbook != null) workbook.close();
                if (fis != null) fis.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing resources: " + e.getMessage(), e);
            }
        }
        return rows;
    }
    
    // 缺失的方法实现
    public static void generateImportConfirmation(File file, int sheetIndex, Map<String, Integer> fieldMapping, ImportSettings settings, ImportConfirmationCallback callback) {
        if (file == null || !file.exists()) {
            if (callback != null) {
                callback.onError("文件不存在");
            }
            return;
        }
        
        final Map<String, Integer> finalFieldMapping = fieldMapping;
        final ImportSettings finalSettings = settings;
        
        executorService.execute(() -> {
            try {
                ImportConfirmation confirmation = new ImportConfirmation();
                confirmation.fieldMapping = finalFieldMapping;
                confirmation.previewItems = new ArrayList<>();
                
                // 获取工作表名称
                List<SheetInfo> sheets = getExcelSheets(file);
                if (sheetIndex >= 0 && sheetIndex < sheets.size()) {
                    confirmation.selectedSheetName = sheets.get(sheetIndex).sheetName;
                }
                
                // 读取数据并生成预览
                List<List<String>> data = readExcelData(file, sheetIndex, 0);
                confirmation.totalItems = data.size();
                confirmation.validItems = 0;
                confirmation.invalidItems = 0;
                confirmation.duplicateItems = 0;
                
                // 获取关键列索引（统一用 resolveColumn，兼容 canonical/中英文别名）
                Integer questionColumn = resolveColumn(finalFieldMapping, "questionText");
                
                for (int i = 0; i < data.size(); i++) {
                    List<String> row = data.get(i);
                    ImportPreviewItem item = new ImportPreviewItem();
                    item.rowNumber = i + 2;
                    
                    // 基本验证
                    boolean isValid = true;
                    String errorMessage = "";
                    
                    if (questionColumn != null && questionColumn < row.size()) {
                        String question = row.get(questionColumn).trim();
                        if (question.isEmpty()) {
                            isValid = false;
                            errorMessage = "题目内容为空";
                        }
                    }
                    
                    item.isValid = isValid;
                    item.errorMessage = errorMessage;
                    item.isDuplicate = false; // 可以添加重复检测逻辑
                    
                    if (isValid) {
                        confirmation.validItems++;
                    } else {
                        confirmation.invalidItems++;
                    }
                    
                    confirmation.previewItems.add(item);
                    
                    // 更新进度（节流：每 100 行回调一次）
                    if (callback != null && (i % 100 == 0 || i == data.size() - 1)) {
                        callback.onProgress(i + 1, data.size());
                    }
                }
                
                if (callback != null) {
                    callback.onConfirmationReady(confirmation);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error generating import confirmation: " + e.getMessage(), e);
                if (callback != null) {
                    callback.onError("生成导入确认信息失败: " + e.getMessage());
                }
            }
        });
    }
    
    public static final ExecutorService executorService = Executors.newSingleThreadExecutor();
    
    public static DataIssueReport detectDataIssues(File file, int sheetIndex, Map<String, Integer> finalFieldMapping, ImportSettings finalSettings, Map<String, String> questionTypeMapping) {
        DataIssueReport report = new DataIssueReport();
        
        if (file == null || !file.exists() || finalFieldMapping == null) {
            return report;
        }
        
        // 使用 FieldMappingRegistry 统一查找必填字段列索引
        Integer questionColumn = resolveColumn(finalFieldMapping, "questionText");
        Integer answerColumn = resolveColumn(finalFieldMapping, "correctAnswer");
        Integer typeColumn = resolveColumn(finalFieldMapping, "questionType");
        
        // 读取数据并检测问题（带物理行号：Excel 空行不跳过，行号 = 物理行号+1，
        // 与 importExcel 的 applyCorrectionsToQuestion 行号约定一致，修正不再错位）
        List<Object[]> rows = readExcelRowsWithRowNumbers(file, sheetIndex);
        for (Object[] entry : rows) {
            int rowNumber = (Integer) entry[0];
            @SuppressWarnings("unchecked")
            List<String> row = (List<String>) entry[1];
            
            // 检测题目内容是否为空
            if (questionColumn != null && questionColumn < row.size()) {
                String question = row.get(questionColumn).trim();
                if (question.isEmpty()) {
                    DataIssueItem issue = new DataIssueItem();
                    issue.rowNumber = rowNumber;
                    issue.issueType = DataIssueType.MISSING_QUESTION_TEXT;
                    issue.fieldName = "题目";
                    issue.currentValue = "";
                    if (finalSettings != null && finalSettings.defaultQuestion != null) {
                        issue.suggestedValue = finalSettings.defaultQuestion;
                    }
                    report.addIssue(issue);
                }
            }
            
            // 检测正确答案是否为空
            if (answerColumn != null && answerColumn < row.size()) {
                String answer = row.get(answerColumn).trim();
                if (answer.isEmpty()) {
                    DataIssueItem issue = new DataIssueItem();
                    issue.rowNumber = rowNumber;
                    issue.issueType = DataIssueType.MISSING_CORRECT_ANSWER;
                    issue.fieldName = "正确答案";
                    issue.currentValue = "";
                    if (finalSettings != null && finalSettings.defaultAnswer != null) {
                        issue.suggestedValue = finalSettings.defaultAnswer;
                    }
                    report.addIssue(issue);
                }
            }
            
            // 检测题型是否为空
            if (typeColumn != null && typeColumn < row.size()) {
                String type = row.get(typeColumn).trim();
                if (type.isEmpty()) {
                    DataIssueItem issue = new DataIssueItem();
                    issue.rowNumber = rowNumber;
                    issue.issueType = DataIssueType.MISSING_QUESTION_TYPE;
                    issue.fieldName = "题型";
                    issue.currentValue = "";
                    if (finalSettings != null && finalSettings.defaultQuestionType != null) {
                        issue.suggestedValue = finalSettings.defaultQuestionType;
                    }
                    // 添加题型建议值列表
                    if (questionTypeMapping != null && !questionTypeMapping.isEmpty()) {
                        // 使用映射中的题型作为建议值
                        for (String mappedType : questionTypeMapping.values()) {
                            if (!issue.suggestedValuesList.contains(mappedType)) {
                                issue.suggestedValuesList.add(mappedType);
                            }
                        }
                    } else {
                        // 如果没有映射，使用默认题型
                        issue.suggestedValuesList.add("单选题");
                        issue.suggestedValuesList.add("多选题");
                        issue.suggestedValuesList.add("判断题");
                        issue.suggestedValuesList.add("简答题");
                        issue.suggestedValuesList.add("填空题");
                    }
                    report.addIssue(issue);
                }
            }
        }
        
        return report;
    }
    
    public static void applyCorrectionsToQuestion(Question question, DataIssueReport issueReport, int rowNumber) {
        if (question == null || issueReport == null) {
            return;
        }
        
        List<DataIssueItem> issues = issueReport.getIssuesByRow(rowNumber);
        for (DataIssueItem issue : issues) {
            if (issue.isResolved && issue.userCorrectedValue != null && !issue.userCorrectedValue.isEmpty()) {
                switch (issue.fieldName) {
                    case "题目":
                    case "question":
                    case "questionText":
                        question.setQuestionText(issue.userCorrectedValue);
                        break;
                    case "正确答案":
                    case "answer":
                    case "答案":
                    case "correctAnswer":
                        question.setCorrectAnswer(issue.userCorrectedValue);
                        break;
                    case "题型":
                    case "question_type":
                    case "类型":
                    case "questionType":
                        question.setQuestionType(issue.userCorrectedValue);
                        break;
                }
            }
        }
    }
    
    public static List<String> detectQuestionTypes(File file, int sheetIndex, Map<String, Integer> finalFieldMapping) {
        List<String> questionTypes = new ArrayList<>();
        
        if (file == null || !file.exists() || finalFieldMapping == null) {
            return questionTypes;
        }
        
        // 使用 FieldMappingRegistry 统一查找题型列索引
        Integer typeColumnIndex = resolveColumn(finalFieldMapping, "questionType");
        
        if (typeColumnIndex == null) {
            return questionTypes;
        }
        
        // 读取数据并检测题型（读取所有行）
        List<List<String>> data = readExcelData(file, sheetIndex, 0); // 0 表示读取所有行
        for (List<String> row : data) {
            if (typeColumnIndex < row.size()) {
                String type = row.get(typeColumnIndex).trim();
                if (!type.isEmpty() && !questionTypes.contains(type)) {
                    questionTypes.add(type);
                }
            }
        }
        
        return questionTypes;
    }
    
    public static List<String> detectDifficultyLevels(File file, int sheetIndex, Map<String, Integer> finalFieldMapping) {
        List<String> difficultyLevels = new ArrayList<>();
        
        if (file == null || !file.exists() || finalFieldMapping == null) {
            return difficultyLevels;
        }
        
        // 获取难度列的索引
        // 使用 FieldMappingRegistry 统一查找难度列索引
        Integer difficultyColumnIndex = resolveColumn(finalFieldMapping, "difficulty");

        if (difficultyColumnIndex == null) {
            return difficultyLevels;
        }
        
        // 读取数据并检测难度（读取所有行）
        List<List<String>> data = readExcelData(file, sheetIndex, 0); // 0 表示读取所有行
        for (List<String> row : data) {
            if (difficultyColumnIndex < row.size()) {
                String difficulty = row.get(difficultyColumnIndex).trim();
                if (!difficulty.isEmpty() && !difficultyLevels.contains(difficulty)) {
                    difficultyLevels.add(difficulty);
                }
            }
        }
        
        return difficultyLevels;
    }
    
    public static List<String> detectCategories(File file, int sheetIndex, Map<String, Integer> finalFieldMapping) {
        List<String> categories = new ArrayList<>();
        
        if (file == null || !file.exists() || finalFieldMapping == null) {
            return categories;
        }
        
        // 获取分类列的索引
        // 使用 FieldMappingRegistry 统一查找分类列索引
        Integer categoryColumnIndex = resolveColumn(finalFieldMapping, "category");

        if (categoryColumnIndex == null) {
            return categories;
        }
        
        // 读取数据并检测分类（读取所有行）
        List<List<String>> data = readExcelData(file, sheetIndex, 0); // 0 表示读取所有行
        for (List<String> row : data) {
            if (categoryColumnIndex < row.size()) {
                String category = row.get(categoryColumnIndex).trim();
                if (!category.isEmpty() && !categories.contains(category)) {
                    categories.add(category);
                }
            }
        }
        
        return categories;
    }
    
    public static Map<String, String> generateMappingSuggestions(List<String> detected, List<String> standard) {
        Map<String, String> mapping = new HashMap<>();
        
        if (detected == null || standard == null) {
            return mapping;
        }
        
        for (String detect : detected) {
            String bestMatch = null;
            int bestScore = 0;
            
            for (String std : standard) {
                int score = calculateSimilarity(detect, std);
                if (score > bestScore && score > 60) { // 相似度阈值60%
                    bestScore = score;
                    bestMatch = std;
                }
            }
            
            if (bestMatch != null) {
                mapping.put(detect, bestMatch);
            }
        }
        
        return mapping;
    }
    
    /**
     * 计算两个字符串的相似度（0-100）
     */
    private static int calculateSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) {
            return 0;
        }
        
        s1 = s1.toLowerCase().trim();
        s2 = s2.toLowerCase().trim();
        
        if (s1.equals(s2)) {
            return 100;
        }
        
        // 包含关系
        if (s1.contains(s2) || s2.contains(s1)) {
            return 80;
        }
        
        // 计算编辑距离
        int distance = calculateLevenshteinDistance(s1, s2);
        int maxLength = Math.max(s1.length(), s2.length());
        
        if (maxLength == 0) {
            return 100;
        }
        
        return (int) ((1.0 - (double) distance / maxLength) * 100);
    }
    
    /**
     * 计算Levenshtein编辑距离
     */
    private static int calculateLevenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        
        for (int i = 0; i <= s1.length(); i++) {
            dp[i][0] = i;
        }
        
        for (int j = 0; j <= s2.length(); j++) {
            dp[0][j] = j;
        }
        
        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(
                    dp[i - 1][j] + 1,      // 删除
                    dp[i][j - 1] + 1),     // 插入
                    dp[i - 1][j - 1] + cost // 替换
                );
            }
        }
        
        return dp[s1.length()][s2.length()];
    }
    
    public static String getCellValue(Object cell) {
        if (cell == null) {
            return "";
        }
        
        if (cell instanceof Cell) {
            Cell excelCell = (Cell) cell;
            return getCellValueAsString(excelCell);
        }
        
        return cell.toString();
    }
    
    public static void smartImport(File file, ImportCallback callback) {
        if (callback != null) {
            callback.onError("功能暂时不可用");
        }
    }
    
    // 取消导入标志
    private static volatile boolean isImportCancelled = false;
    
    public static void cancelImport() {
        isImportCancelled = true;
    }
    
    public static void importExcel(File file, int sheetIndex, Map<String, Integer> fieldMapping, ImportSettings settings, Map<String, String> questionTypeMapping, Map<String, String> difficultyMapping, Map<String, String> categoryMapping, DataIssueReport issueReport, ImportCallback callback) {
        if (file == null || !file.exists()) {
            if (callback != null) {
                callback.onError("文件不存在");
            }
            return;
        }

        // ================ 智能格式分发：CSV/JSON 走各自的导入器（带CharsetDetector编码）============
        FileFormat format = detectFileFormat(file);
        if (format == FileFormat.CSV) {
            Log.d(TAG, "检测到CSV格式，走importCsv分支（含智能编码检测）");
            importCsv(file, fieldMapping, settings, questionTypeMapping, difficultyMapping, categoryMapping, issueReport, callback);
            return;
        } else if (format == FileFormat.JSON) {
            Log.d(TAG, "检测到JSON格式，走importJson分支（含智能编码检测）");
            importJson(file, fieldMapping, settings, questionTypeMapping, difficultyMapping, categoryMapping, issueReport, callback);
            return;
        }
        // EXCEL / 其他格式继续走原有POI逻辑
        
        isImportCancelled = false;
        
        final Map<String, Integer> finalFieldMapping = fieldMapping;
        final ImportSettings finalSettings = settings;
        final Map<String, String> finalQuestionTypeMapping = questionTypeMapping;
        final Map<String, String> finalDifficultyMapping = difficultyMapping;
        final Map<String, String> finalCategoryMapping = categoryMapping;
        final ImportCallback finalCallback = callback;
        
        executorService.execute(() -> {
            long startTime = System.currentTimeMillis();
            ImportResult result = new ImportResult();
            List<Question> questions = new ArrayList<>();
            
            FileInputStream fis = null;
            Workbook workbook = null;
            
            try {
                fis = new FileInputStream(file);
                workbook = WorkbookFactory.create(fis);
                
                // 获取指定工作表
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                if (sheet == null) {
                    if (callback != null) {
                        callback.onError("工作表不存在");
                    }
                    return;
                }
                
                // 使用 FieldMappingRegistry 统一解析字段映射（消除硬编码中英文对应）
                Integer questionColumn = resolveColumn(finalFieldMapping, "questionText");
                Integer answerColumn = resolveColumn(finalFieldMapping, "correctAnswer");
                Integer typeColumn = resolveColumn(finalFieldMapping, "questionType");
                Integer optionAColumn = resolveColumn(finalFieldMapping, "optionA");
                Integer optionBColumn = resolveColumn(finalFieldMapping, "optionB");
                Integer optionCColumn = resolveColumn(finalFieldMapping, "optionC");
                Integer optionDColumn = resolveColumn(finalFieldMapping, "optionD");
                Integer difficultyColumn = resolveColumn(finalFieldMapping, "difficulty");
                Integer categoryColumn = resolveColumn(finalFieldMapping, "category");
                Integer explanationColumn = resolveColumn(finalFieldMapping, "explanation");
                // —— 扩展：E~L 选项列 & 填空题 blankAnswer1~12 列 ——
                Integer[] extraOptionColumns = new Integer[8]; // 0->E, 1->F, ..., 7->L
                char[] extraLetters = new char[]{'E','F','G','H','I','J','K','L'};
                for (int _i = 0; _i < extraLetters.length; _i++) {
                    extraOptionColumns[_i] = resolveColumn(finalFieldMapping, "option" + extraLetters[_i]);
                }
                // —— 选项(拆分)列：单列包含所有选项，按分隔符拆分分配到 A~L ——
                Integer optionSplitCol = null;
                if (finalFieldMapping != null && finalFieldMapping.containsKey("选项(拆分)")) {
                    optionSplitCol = finalFieldMapping.get("选项(拆分)");
                }
                // 冲突检测：拆分模式与独立选项列同时存在时，拆分优先并记录警告
                if (optionSplitCol != null && (optionAColumn != null || optionBColumn != null
                        || optionCColumn != null || optionDColumn != null)) {
                    Log.w(TAG, "选项(拆分)与独立选项列同时映射，将使用拆分模式，忽略独立选项列");
                }
                Integer[] blankAnswerColumns = new Integer[12];
                for (int n = 0; n < 12; n++) {
                    blankAnswerColumns[n] = resolveColumn(finalFieldMapping, "blankAnswer" + (n + 1));
                }
                
                // 读取数据行（从第二行开始，第一行是表头）
                int totalRows = sheet.getLastRowNum();
                result.totalQuestions = totalRows;
                
                for (int i = 1; i <= totalRows; i++) {
                    // 检查是否取消导入
                    if (isImportCancelled) {
                        result.summary = "导入已取消";
                        result.cancelled = true;
                        if (callback != null) {
                            callback.onComplete(questions, result);
                        }
                        return;
                    }
                    
                    Row row = sheet.getRow(i);
                    if (row == null) {
                        result.skippedQuestions++;
                        continue;
                    }
                    
                    try {
                        Question question = new Question();
                        
                        // 设置题目内容
                        if (questionColumn != null) {
                            String questionText = getCellValueAsString(row.getCell(questionColumn));
                            if (questionText.isEmpty() && finalSettings != null && finalSettings.skipEmptyQuestions) {
                                result.skippedQuestions++;
                                continue;
                            }
                            question.setQuestionText(questionText);
                        }
                        
                        // 设置正确答案（含 blankAnswer1~12 合并）
                        String answer = (answerColumn != null) ? getCellValueAsString(row.getCell(answerColumn)) : "";
                        java.util.List<String> blankParts = new ArrayList<>();
                        for (int bn = 0; bn < blankAnswerColumns.length; bn++) {
                            Integer bc = blankAnswerColumns[bn];
                            if (bc != null) {
                                String bv = getCellValueAsString(row.getCell(bc));
                                if (bv != null && !bv.trim().isEmpty()) blankParts.add(bv.trim());
                            }
                        }
                        if (!blankParts.isEmpty()) {
                            String joined = String.join("；", blankParts);
                            if (answer == null || answer.trim().isEmpty()) {
                                answer = joined;
                            } else if (!answer.contains(joined)) {
                                answer = answer + "；" + joined;
                            }
                        }
                        if (answer != null) question.setCorrectAnswer(answer);
                        
                        // 设置题型
                        if (typeColumn != null) {
                            String type = getCellValueAsString(row.getCell(typeColumn));
                            // 应用题型映射
                            if (finalQuestionTypeMapping != null && finalQuestionTypeMapping.containsKey(type)) {
                                type = finalQuestionTypeMapping.get(type);
                            }
                            question.setQuestionType(type);
                        } else if (finalSettings != null && finalSettings.defaultQuestionType != null) {
                            question.setQuestionType(finalSettings.defaultQuestionType);
                        }
                        
                        // —— 选项(拆分)模式：单列拆分为 A~L 多个选项 ——
                        String optDelimiter = (finalSettings != null) ? finalSettings.optionsDelimiter : null;
                        boolean splitMode = optDelimiter != null && !optDelimiter.isEmpty() && optionSplitCol != null;
                        
                        if (splitMode) {
                            // 拆分模式：从"选项(拆分)"列拆分，支持自定义部分→选项映射
                            String rawValue = getCellValueAsString(row.getCell(optionSplitCol));
                            if (rawValue != null && !rawValue.isEmpty()) {
                                char[] allLetters = {'A','B','C','D','E','F','G','H','I','J','K','L'};
                                // 1) 先拆分得到各部分内容
                                java.util.List<String> partList = new java.util.ArrayList<>();
                                if ("LETTER_PATTERN".equals(optDelimiter)) {
                                    java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                                        "(?s)([A-H])[.、．]\\s*(.*?)(?=\\s*[A-H][.、．]|$)")
                                        .matcher(rawValue);
                                    while (m.find() && partList.size() < allLetters.length) {
                                        partList.add(m.group(2).trim());
                                    }
                                } else {
                                    String[] parts = rawValue.split(java.util.regex.Pattern.quote(optDelimiter), -1);
                                    for (int pi = 0; pi < parts.length && pi < allLetters.length; pi++) {
                                        partList.add(parts[pi].trim());
                                    }
                                }
                                // 2) 计算每个部分的目标选项字母（优先用户自定义映射，剩余按可用字母顺序补位）
                                java.util.Map<Integer, String> partMap = (finalSettings != null) ? finalSettings.splitPartMapping : null;
                                java.util.Set<String> usedLetters = new java.util.HashSet<>();
                                String[] targets = new String[partList.size()];
                                if (partMap != null && !partMap.isEmpty()) {
                                    for (int pi = 0; pi < partList.size(); pi++) {
                                        String f = partMap.get(pi);
                                        if (f != null && !f.isEmpty()) {
                                            String letter = f.substring(f.length() - 1).toUpperCase();
                                            targets[pi] = letter;
                                            usedLetters.add(letter);
                                        }
                                    }
                                }
                                for (int pi = 0; pi < partList.size(); pi++) {
                                    if (targets[pi] == null) {
                                        for (char letter : allLetters) {
                                            if (!usedLetters.contains(String.valueOf(letter))) {
                                                targets[pi] = String.valueOf(letter);
                                                usedLetters.add(String.valueOf(letter));
                                                break;
                                            }
                                        }
                                    }
                                }
                                // 3) 按目标字母写入选项
                                for (int pi = 0; pi < partList.size(); pi++) {
                                    String part = partList.get(pi);
                                    if (!part.isEmpty() && targets[pi] != null) {
                                        question.setOptionByLetter(targets[pi], part);
                                    }
                                }
                            }
                        } else {
                            // 普通模式：每列对应一个选项（A~L）
                            if (optionAColumn != null) {
                                question.setOptionA(getCellValueAsString(row.getCell(optionAColumn)));
                            }
                            if (optionBColumn != null) {
                                question.setOptionB(getCellValueAsString(row.getCell(optionBColumn)));
                            }
                            if (optionCColumn != null) {
                                question.setOptionC(getCellValueAsString(row.getCell(optionCColumn)));
                            }
                            if (optionDColumn != null) {
                                question.setOptionD(getCellValueAsString(row.getCell(optionDColumn)));
                            }
                            for (int ei = 0; ei < extraLetters.length; ei++) {
                                Integer col = extraOptionColumns[ei];
                                if (col != null) {
                                    String v = getCellValueAsString(row.getCell(col));
                                    if (v != null && !v.isEmpty()) {
                                        question.setOptionByLetter(String.valueOf(extraLetters[ei]), v);
                                    }
                                }
                            }
                        }
                        
                        // 设置难度
                        if (difficultyColumn != null) {
                            String difficultyStr = getCellValueAsString(row.getCell(difficultyColumn));
                            // 应用难度映射
                            if (finalDifficultyMapping != null && finalDifficultyMapping.containsKey(difficultyStr)) {
                                difficultyStr = finalDifficultyMapping.get(difficultyStr);
                            }
                            try {
                                int difficulty = Integer.parseInt(difficultyStr);
                                question.setDifficulty(difficulty);
                            } catch (NumberFormatException e) {
                                // 如果解析失败，使用默认值
                                if (finalSettings != null && finalSettings.defaultDifficulty > 0) {
                                    question.setDifficulty(finalSettings.defaultDifficulty);
                                } else {
                                    question.setDifficulty(1); // 默认难度为1
                                }
                            }
                        } else if (finalSettings != null && finalSettings.defaultDifficulty > 0) {
                            question.setDifficulty(finalSettings.defaultDifficulty);
                        }
                        
                        // 设置分类
                        if (categoryColumn != null) {
                            String category = getCellValueAsString(row.getCell(categoryColumn));
                            // 应用分类映射
                            if (finalCategoryMapping != null && finalCategoryMapping.containsKey(category)) {
                                category = finalCategoryMapping.get(category);
                            }
                            question.setCategory(category);
                        }
                        
                        // 设置解析
                        if (explanationColumn != null) {
                            question.setExplanation(getCellValueAsString(row.getCell(explanationColumn)));
                        }
                        
                        // 应用数据问题修复（在导入循环内，行号精确匹配）
                        if (issueReport != null) {
                            applyCorrectionsToQuestion(question, issueReport, i + 1);
                        }
                        
                        // 验证题目
                        if (isValidQuestion(question, finalSettings)) {
                            questions.add(question);
                            result.validQuestions++;
                        } else {
                            result.invalidQuestions++;
                            ErrorInfo errorInfo = new ErrorInfo();
                            errorInfo.rowNumber = i + 1;
                            errorInfo.errorMessage = "题目验证失败";
                            result.errorInfos.add(errorInfo);
                        }
                        
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing row " + (i + 1) + ": " + e.getMessage(), e);
                        result.invalidQuestions++;
                        ErrorInfo errorInfo = new ErrorInfo();
                        errorInfo.rowNumber = i + 1;
                        errorInfo.errorMessage = "解析错误: " + e.getMessage();
                        result.errorInfos.add(errorInfo);
                    }
                    
                    // 更新进度（节流：每 100 行回调一次，避免万行导入刷爆主线程）
                    if (callback != null && (i % 100 == 0 || i == totalRows)) {
                        callback.onProgress(i, totalRows);
                    }
                }
                
                result.importTime = System.currentTimeMillis() - startTime;
                result.summary = "导入完成，成功: " + result.validQuestions + ", 失败: " + result.invalidQuestions;
                
                if (callback != null) {
                    callback.onComplete(questions, result);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error importing Excel file: " + e.getMessage(), e);
                if (callback != null) {
                    // Word/其他非 Excel 文档会抛 POIXMLException（RuntimeException），需友好提示
                    if (e instanceof org.apache.poi.ooxml.POIXMLException) {
                        callback.onError("文件不是有效的 Excel 表格文件（请选择 .xlsx/.xls/.csv/.json）");
                    } else {
                        callback.onError("导入失败: " + e.getMessage());
                    }
                }
            } finally {
                try {
                    if (workbook != null) {
                        workbook.close();
                    }
                    if (fis != null) {
                        fis.close();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error closing resources: " + e.getMessage(), e);
                }
            }
        });
    }
    
    /**
     * 验证题目是否有效
     */
    private static boolean isValidQuestion(Question question, ImportSettings finalSettings) {
        if (question == null) {
            return false;
        }
        
        // 检查题目内容
        if (question.getQuestionText() == null || question.getQuestionText().trim().isEmpty()) {
            return false;
        }
        
        // 检查正确答案
        if (question.getCorrectAnswer() == null || question.getCorrectAnswer().trim().isEmpty()) {
            return false;
        }
        
        return true;
    }
    
    public static FileFormat detectFileFormat(File file) {
        if (file == null || !file.exists()) {
            return FileFormat.UNKNOWN;
        }
        
        String fileName = file.getName().toLowerCase();
        
        // 检测Excel文件
        if (fileName.endsWith(".xlsx")) {
            return FileFormat.EXCEL;
        } else if (fileName.endsWith(".xls")) {
            return FileFormat.EXCEL;
        }
        // 检测CSV文件
        else if (fileName.endsWith(".csv")) {
            return FileFormat.CSV;
        }
        // 检测JSON文件
        else if (fileName.endsWith(".json")) {
            return FileFormat.JSON;
        }
        // 检测Word文件
        else if (fileName.endsWith(".doc") || fileName.endsWith(".docx")) {
            return FileFormat.WORD;
        }
        // 检测PDF文件
        else if (fileName.endsWith(".pdf")) {
            return FileFormat.PDF;
        }
        // 检测Markdown文件
        else if (fileName.endsWith(".md") || fileName.endsWith(".markdown")) {
            return FileFormat.MARKDOWN;
        }
        
        return FileFormat.UNKNOWN;
    }

    // ==================== CSV / JSON 读取（使用CharsetDetector智能编码） ====================

    /**
     * 读取CSV文件全部内容（使用CharsetDetector智能编码检测）
     */
    public static List<List<String>> readCsvFile(File file) throws IOException {
        List<List<String>> data = new ArrayList<>();
        if (file == null || !file.exists()) return data;

        Object[] readerInfo = CharsetDetector.openBufferedReaderAutoDetect(file);
        BufferedReader reader = (BufferedReader) readerInfo[0];
        String detectedCharset = (String) readerInfo[1];
        Log.d(TAG, "CSV文件 [" + file.getName() + "] 检测到编码: " + detectedCharset);

        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                List<String> row = splitCsvLine(line);
                data.add(row);
            }
        } finally {
            reader.close();
        }
        return data;
    }

    /**
     * 读取JSON文件全部内容（使用CharsetDetector智能编码检测）
     */
    public static String readJsonFile(File file) throws IOException {
        if (file == null || !file.exists()) return "";
        String content = CharsetDetector.readFileAutoDetect(file);
        Log.d(TAG, "JSON文件 [" + file.getName() + "] 读取完毕,长度=" + content.length());
        return content;
    }

    /**
     * 按 RFC4180 风格解析 CSV 行（支持引号包裹、引号内逗号、双引号转义）
     * 复用与AIFileParser中一致的健壮实现
     */
    public static List<String> splitCsvLine(String line) {
        if (line == null || line.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == ',') {
                    result.add(cur.toString());
                    cur.setLength(0);
                } else if (c == '"' && cur.length() == 0) {
                    inQuotes = true;
                } else {
                    cur.append(c);
                }
            }
        }
        result.add(cur.toString());
        return result;
    }

    /**
     * 导入CSV文件（使用智能编码 + RFC4180解析）
     */
    public static void importCsv(File file, Map<String, Integer> fieldMapping, ImportSettings settings,
                                  Map<String, String> questionTypeMapping,
                                  Map<String, String> difficultyMapping,
                                  Map<String, String> categoryMapping,
                                  DataIssueReport issueReport,
                                  ImportCallback callback) {
        if (file == null || !file.exists()) {
            if (callback != null) callback.onError("CSV文件不存在");
            return;
        }
        isImportCancelled = false;
        final Map<String, Integer> finalFieldMapping = (fieldMapping != null) ? fieldMapping : buildDefaultCsvFieldMapping(file);
        final ImportSettings finalSettings = (settings != null) ? settings : new ImportSettings();
        final ImportCallback finalCallback = callback;

        executorService.execute(() -> {
            long startTime = System.currentTimeMillis();
            ImportResult result = new ImportResult();
            List<Question> questions = new ArrayList<>();
            try {
                List<List<String>> rows = readCsvFile(file);
                if (rows.isEmpty()) {
                    if (finalCallback != null) finalCallback.onError("CSV文件内容为空");
                    return;
                }
                // 第0行是表头
                int totalRows = Math.max(0, rows.size() - 1);
                result.totalQuestions = totalRows;

                // 使用 FieldMappingRegistry 统一解析字段映射（兼容中文/英文/变体表头）
                // 先用 Registry 从表头构建标准映射，再合并用户传入的 fieldMapping
                Map<String, Integer> canonicalMapping = FieldMappingRegistry.buildMappingFromHeaders(rows.get(0));
                if (finalFieldMapping != null) {
                    // 用户手动映射优先：将中文 key 转为 canonical 后覆盖
                    for (Map.Entry<String, Integer> e : finalFieldMapping.entrySet()) {
                        String canonical = FieldMappingRegistry.resolve(e.getKey());
                        if (canonical != null) {
                            canonicalMapping.put(canonical, e.getValue());
                        } else {
                            // 无法识别的 key 直接保留
                            canonicalMapping.put(e.getKey(), e.getValue());
                        }
                    }
                }

                for (int i = 1; i < rows.size(); i++) {
                    if (isImportCancelled) {
                        result.summary = "导入已取消";
                        result.cancelled = true;
                        if (finalCallback != null) finalCallback.onComplete(questions, result);
                        return;
                    }
                    List<String> row = rows.get(i);
                    if (row.isEmpty()) { result.skippedQuestions++; continue; }
                    try {
                        // 使用 Registry 统一提取 Question
                        Question question = FieldMappingRegistry.extractFromRow(row, canonicalMapping);

                        // 空题检查
                        if ((question.getQuestionText() == null || question.getQuestionText().trim().isEmpty())
                                && finalSettings.skipEmptyQuestions) {
                            result.skippedQuestions++;
                            continue;
                        }
                        if (question.getQuestionText() == null || question.getQuestionText().trim().isEmpty()) {
                            if (finalSettings.defaultQuestion != null) {
                                question.setQuestionText(finalSettings.defaultQuestion);
                            }
                        }

                        // 题型映射
                        String t = question.getQuestionType();
                        if (t != null && !t.isEmpty() && questionTypeMapping != null && questionTypeMapping.containsKey(t)) {
                            question.setQuestionType(questionTypeMapping.get(t));
                        } else if ((t == null || t.isEmpty()) && finalSettings.defaultQuestionType != null) {
                            question.setQuestionType(finalSettings.defaultQuestionType);
                        }

                        // 难度映射
                        if (question.getDifficulty() == 0) {
                            if (finalSettings.defaultDifficulty > 0) {
                                question.setDifficulty(finalSettings.defaultDifficulty);
                            }
                        }

                        // 分类映射
                        String c = question.getCategory();
                        if (c != null && !c.isEmpty() && categoryMapping != null && categoryMapping.containsKey(c)) {
                            question.setCategory(categoryMapping.get(c));
                        }

                        // 解析字段已由 Registry.extractFromRow 填充

                        // 应用数据问题修复
                        if (issueReport != null) {
                            applyCorrectionsToQuestion(question, issueReport, i + 1);
                        }

                        if (isValidQuestion(question, finalSettings)) {
                            questions.add(question);
                            result.validQuestions++;
                        } else {
                            result.invalidQuestions++;
                            ErrorInfo ei = new ErrorInfo(); ei.rowNumber = i + 1; ei.errorMessage = "题目验证失败";
                            result.errorInfos.add(ei);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "CSV解析第" + (i + 1) + "行出错: " + e.getMessage(), e);
                        result.invalidQuestions++;
                        ErrorInfo ei = new ErrorInfo(); ei.rowNumber = i + 1; ei.errorMessage = "解析错误: " + e.getMessage();
                        result.errorInfos.add(ei);
                    }
                    if (finalCallback != null && (i % 100 == 0 || i == totalRows)) finalCallback.onProgress(i, totalRows);
                }

                result.importTime = System.currentTimeMillis() - startTime;
                result.summary = "CSV导入完成，成功: " + result.validQuestions + ", 失败: " + result.invalidQuestions;
                if (finalCallback != null) finalCallback.onComplete(questions, result);
            } catch (Exception e) {
                Log.e(TAG, "CSV导入失败: " + e.getMessage(), e);
                if (finalCallback != null) finalCallback.onError("CSV导入失败: " + e.getMessage());
            }
        });
    }

    /**
     * 导入JSON文件（使用智能编码检测）
     */
    public static void importJson(File file, Map<String, Integer> fieldMapping, ImportSettings settings,
                                   Map<String, String> questionTypeMapping,
                                   Map<String, String> difficultyMapping,
                                   Map<String, String> categoryMapping,
                                   DataIssueReport issueReport,
                                   ImportCallback callback) {
        if (file == null || !file.exists()) {
            if (callback != null) callback.onError("JSON文件不存在");
            return;
        }
        isImportCancelled = false;
        final ImportSettings finalSettings = (settings != null) ? settings : new ImportSettings();
        final ImportCallback finalCallback = callback;

        executorService.execute(() -> {
            long startTime = System.currentTimeMillis();
            ImportResult result = new ImportResult();
            List<Question> questions = new ArrayList<>();
            try {
                String rawJson = readJsonFile(file);
                org.json.JSONArray arr;
                try {
                    arr = new org.json.JSONArray(rawJson);
                } catch (Exception e) {
                    // 兼容单对象形式
                    org.json.JSONObject obj = new org.json.JSONObject(rawJson);
                    arr = new org.json.JSONArray(); arr.put(obj);
                }
                int total = arr.length();
                result.totalQuestions = total;

                for (int i = 0; i < arr.length(); i++) {
                    if (isImportCancelled) {
                        result.summary = "导入已取消";
                        result.cancelled = true;
                        if (finalCallback != null) finalCallback.onComplete(questions, result);
                        return;
                    }
                    try {
                        org.json.JSONObject jo = arr.getJSONObject(i);
                        // 使用 FieldMappingRegistry 统一提取（自动尝试所有中英文别名）
                        Question q = FieldMappingRegistry.extractFromJson(jo);

                        // 空题检查
                        if ((q.getQuestionText() == null || q.getQuestionText().trim().isEmpty())
                                && finalSettings.skipEmptyQuestions) {
                            result.skippedQuestions++;
                            continue;
                        }
                        if (q.getQuestionText() == null || q.getQuestionText().trim().isEmpty()) {
                            if (finalSettings.defaultQuestion != null) q.setQuestionText(finalSettings.defaultQuestion);
                        }

                        // 题型映射
                        String type = q.getQuestionType();
                        if (type != null && !type.isEmpty() && questionTypeMapping != null && questionTypeMapping.containsKey(type)) {
                            q.setQuestionType(questionTypeMapping.get(type));
                        } else if ((type == null || type.isEmpty()) && finalSettings.defaultQuestionType != null) {
                            q.setQuestionType(finalSettings.defaultQuestionType);
                        }

                        // 难度映射（Registry 已解析中文难度，这里处理映射表）
                        if (q.getDifficulty() == 0 && finalSettings.defaultDifficulty > 0) {
                            q.setDifficulty(finalSettings.defaultDifficulty);
                        }

                        // 分类映射
                        String cat = q.getCategory();
                        if (cat != null && !cat.isEmpty() && categoryMapping != null && categoryMapping.containsKey(cat)) {
                            q.setCategory(categoryMapping.get(cat));
                        }

                        // 应用数据问题修复
                        if (issueReport != null) {
                            applyCorrectionsToQuestion(q, issueReport, i + 1);
                        }

                        if (isValidQuestion(q, finalSettings)) {
                            questions.add(q);
                            result.validQuestions++;
                        } else {
                            result.invalidQuestions++;
                            ErrorInfo ei = new ErrorInfo(); ei.rowNumber = i + 1; ei.errorMessage = "题目验证失败";
                            result.errorInfos.add(ei);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "JSON解析第" + (i + 1) + "项出错: " + e.getMessage(), e);
                        result.invalidQuestions++;
                        ErrorInfo ei = new ErrorInfo(); ei.rowNumber = i + 1; ei.errorMessage = "解析错误: " + e.getMessage();
                        result.errorInfos.add(ei);
                    }
                    if (finalCallback != null && (i % 100 == 0 || i == total - 1)) finalCallback.onProgress(i, total);
                }
                result.importTime = System.currentTimeMillis() - startTime;
                result.summary = "JSON导入完成，成功: " + result.validQuestions + ", 失败: " + result.invalidQuestions;
                if (finalCallback != null) finalCallback.onComplete(questions, result);
            } catch (Exception e) {
                Log.e(TAG, "JSON导入失败: " + e.getMessage(), e);
                if (finalCallback != null) finalCallback.onError("JSON导入失败: " + e.getMessage());
            }
        });
    }

    /** 从JSONObject中按候选key数组取值（第一个非空即返回） */
    private static String optStringMulti(org.json.JSONObject jo, String[] keys) {
        // 已由 FieldMappingRegistry.extractFromJson 替代，保留以防外部调用
        if (jo == null || keys == null) return "";
        for (String k : keys) {
            if (jo.has(k)) {
                try {
                    Object v = jo.get(k);
                    if (v != null && !String.valueOf(v).trim().isEmpty()) return String.valueOf(v).trim();
                } catch (Exception ignore) {}
            }
        }
        return "";
    }

    /**
     * 通过 FieldMappingRegistry 从 fieldMapping 中查找标准字段对应的列索引。
     * 兼容用户手动映射（中文 key）和自动映射（canonical key）。
     *
     * @param fieldMapping 字段映射表（key 可能是中文显示名或英文名）
     * @param canonical    标准字段名（如 "questionText"）
     * @return 列索引，未找到返回 null
     */
    private static Integer resolveColumn(Map<String, Integer> fieldMapping, String canonical) {
        if (fieldMapping == null || canonical == null) return null;
        // 1. 直接用标准名查
        Integer idx = fieldMapping.get(canonical);
        if (idx != null) return idx;
        // 2. 用 Registry 查所有别名
        for (String alias : FieldMappingRegistry.getAliases(canonical)) {
            idx = fieldMapping.get(alias);
            if (idx != null) return idx;
        }
        return null;
    }

    /**
     * 根据CSV文件表头自动建立默认字段映射（兼容中文/英文表头）
     */
    public static Map<String, Integer> buildDefaultCsvFieldMapping(File file) {
        Map<String, Integer> map = new HashMap<>();
        try {
            List<List<String>> rows = readCsvFile(file);
            if (!rows.isEmpty()) {
                List<String> header = rows.get(0);
                // 使用 FieldMappingRegistry 统一构建映射（返回中文显示名→列索引）
                map = FieldMappingRegistry.buildLegacyMappingFromHeaders(header);
                Log.d(TAG, "CSV自动字段映射(Registry): " + map);
            }
        } catch (Exception e) {
            Log.w(TAG, "CSV默认字段映射失败: " + e.getMessage());
        }
        return map;
    }

    /**
     * 获取CSV文件表头（用于预览和映射）
     */
    public static List<String> getCsvHeaders(File file) {
        List<String> headers = new ArrayList<>();
        try {
            List<List<String>> rows = readCsvFile(file);
            if (!rows.isEmpty()) headers.addAll(rows.get(0));
        } catch (Exception e) {
            Log.w(TAG, "读取CSV表头失败: " + e.getMessage());
        }
        return headers;
    }

    /**
     * 读取CSV数据行（不含表头，最多maxRows行）
     */
    public static List<List<String>> readCsvData(File file, int maxRows) {
        List<List<String>> data = new ArrayList<>();
        try {
            List<List<String>> rows = readCsvFile(file);
            int start = 1; // 跳过表头
            int end = (maxRows <= 0) ? rows.size() : Math.min(rows.size(), start + maxRows);
            for (int i = start; i < end; i++) data.add(rows.get(i));
        } catch (Exception e) {
            Log.w(TAG, "读取CSV数据失败: " + e.getMessage());
        }
        return data;
    }
}
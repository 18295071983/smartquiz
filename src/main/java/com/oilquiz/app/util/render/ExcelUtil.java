package com.oilquiz.app.util.render;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.oilquiz.app.model.Question;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
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
        if (callback != null) {
            callback.onError("Excel导入功能暂时不可用");
        }
    }
    
    public static void importExcel(File file, ImportSettings finalSettings, ImportCallback callback) {
        if (callback != null) {
            callback.onError("Excel导入功能暂时不可用");
        }
    }
    
    public static void setDatabaseValidator(DatabaseValidator validator) {
    }
    
    public static List<SheetInfo> getExcelSheets(File file) {
        List<SheetInfo> sheets = new ArrayList<>();
        
        if (file == null || !file.exists()) {
            return sheets;
        }
        
        FileInputStream fis = null;
        Workbook workbook = null;
        
        try {
            fis = new FileInputStream(file);
            
            // 根据文件扩展名创建对应的工作簿
            String fileName = file.getName().toLowerCase();
            if (fileName.endsWith(".xlsx")) {
                workbook = new XSSFWorkbook(fis);
            } else if (fileName.endsWith(".xls")) {
                workbook = new HSSFWorkbook(fis);
            } else {
                return sheets;
            }
            
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
        
        FileInputStream fis = null;
        Workbook workbook = null;
        
        try {
            fis = new FileInputStream(file);
            
            // 根据文件扩展名创建对应的工作簿
            String fileName = file.getName().toLowerCase();
            if (fileName.endsWith(".xlsx")) {
                workbook = new XSSFWorkbook(fis);
            } else if (fileName.endsWith(".xls")) {
                workbook = new HSSFWorkbook(fis);
            } else {
                return headers;
            }
            
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
                    // 处理数字，避免科学计数法
                    double value = cell.getNumericCellValue();
                    if (value == (long) value) {
                        return String.valueOf((long) value);
                    } else {
                        return String.valueOf(value);
                    }
                }
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue();
                } catch (Exception e) {
                    try {
                        return String.valueOf(cell.getNumericCellValue());
                    } catch (Exception ex) {
                        return "";
                    }
                }
            case BLANK:
                return "";
            default:
                return "";
        }
    }
    
    public static List<List<String>> readExcelData(File file, int sheetIndex, int maxRows) {
        List<List<String>> data = new ArrayList<>();
        
        if (file == null || !file.exists()) {
            return data;
        }
        
        FileInputStream fis = null;
        Workbook workbook = null;
        
        try {
            fis = new FileInputStream(file);
            
            // 根据文件扩展名创建对应的工作簿
            String fileName = file.getName().toLowerCase();
            if (fileName.endsWith(".xlsx")) {
                workbook = new XSSFWorkbook(fis);
            } else if (fileName.endsWith(".xls")) {
                workbook = new HSSFWorkbook(fis);
            } else {
                return data;
            }
            
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
                
                // 获取关键列索引
                Integer questionColumn = finalFieldMapping.get("题目");
                if (questionColumn == null) questionColumn = finalFieldMapping.get("question");
                
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
                    
                    // 更新进度
                    if (callback != null) {
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
        
        // 获取必填字段的列索引
        Integer questionColumn = finalFieldMapping.get("题目");
        if (questionColumn == null) questionColumn = finalFieldMapping.get("question");
        if (questionColumn == null) questionColumn = finalFieldMapping.get("问题");
        
        Integer answerColumn = finalFieldMapping.get("正确答案");
        if (answerColumn == null) answerColumn = finalFieldMapping.get("answer");
        if (answerColumn == null) answerColumn = finalFieldMapping.get("答案");
        
        Integer typeColumn = finalFieldMapping.get("题型");
        if (typeColumn == null) typeColumn = finalFieldMapping.get("question_type");
        if (typeColumn == null) typeColumn = finalFieldMapping.get("类型");
        
        // 读取数据并检测问题
        List<List<String>> data = readExcelData(file, sheetIndex, 0); // 读取所有行
        for (int i = 0; i < data.size(); i++) {
            List<String> row = data.get(i);
            int rowNumber = i + 2; // 行号从2开始（1是表头）
            
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
                        question.setQuestionText(issue.userCorrectedValue);
                        break;
                    case "正确答案":
                    case "answer":
                    case "答案":
                        question.setCorrectAnswer(issue.userCorrectedValue);
                        break;
                    case "题型":
                    case "question_type":
                    case "类型":
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
        
        // 获取题型列的索引
        Integer typeColumnIndex = finalFieldMapping.get("题型");
        if (typeColumnIndex == null) {
            typeColumnIndex = finalFieldMapping.get("question_type");
        }
        if (typeColumnIndex == null) {
            typeColumnIndex = finalFieldMapping.get("类型");
        }
        
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
        Integer difficultyColumnIndex = finalFieldMapping.get("难度");
        if (difficultyColumnIndex == null) {
            difficultyColumnIndex = finalFieldMapping.get("difficulty");
        }
        
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
        Integer categoryColumnIndex = finalFieldMapping.get("分类");
        if (categoryColumnIndex == null) {
            categoryColumnIndex = finalFieldMapping.get("category");
        }
        if (categoryColumnIndex == null) {
            categoryColumnIndex = finalFieldMapping.get("科目");
        }
        
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
    
    public static void importExcel(File file, int sheetIndex, Map<String, Integer> fieldMapping, ImportSettings settings, Map<String, String> questionTypeMapping, Map<String, String> difficultyMapping, Map<String, String> categoryMapping, ImportCallback callback) {
        if (file == null || !file.exists()) {
            if (callback != null) {
                callback.onError("文件不存在");
            }
            return;
        }
        
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
                
                // 根据文件扩展名创建对应的工作簿
                String fileName = file.getName().toLowerCase();
                if (fileName.endsWith(".xlsx")) {
                    workbook = new XSSFWorkbook(fis);
                } else if (fileName.endsWith(".xls")) {
                    workbook = new HSSFWorkbook(fis);
                } else {
                    if (callback != null) {
                        callback.onError("不支持的文件格式");
                    }
                    return;
                }
                
                // 获取指定工作表
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                if (sheet == null) {
                    if (callback != null) {
                        callback.onError("工作表不存在");
                    }
                    return;
                }
                
                // 获取关键列索引
                Integer questionColumn = finalFieldMapping.get("题目");
                if (questionColumn == null) questionColumn = finalFieldMapping.get("question");
                if (questionColumn == null) questionColumn = finalFieldMapping.get("问题");
                
                Integer answerColumn = finalFieldMapping.get("正确答案");
                if (answerColumn == null) answerColumn = finalFieldMapping.get("answer");
                if (answerColumn == null) answerColumn = finalFieldMapping.get("答案");
                
                Integer typeColumn = finalFieldMapping.get("题型");
                if (typeColumn == null) typeColumn = finalFieldMapping.get("question_type");
                if (typeColumn == null) typeColumn = finalFieldMapping.get("类型");
                
                Integer optionAColumn = finalFieldMapping.get("选项A");
                if (optionAColumn == null) optionAColumn = finalFieldMapping.get("option_a");
                
                Integer optionBColumn = finalFieldMapping.get("选项B");
                if (optionBColumn == null) optionBColumn = finalFieldMapping.get("option_b");
                
                Integer optionCColumn = finalFieldMapping.get("选项C");
                if (optionCColumn == null) optionCColumn = finalFieldMapping.get("option_c");
                
                Integer optionDColumn = finalFieldMapping.get("选项D");
                if (optionDColumn == null) optionDColumn = finalFieldMapping.get("option_d");
                
                Integer difficultyColumn = finalFieldMapping.get("难度");
                if (difficultyColumn == null) difficultyColumn = finalFieldMapping.get("difficulty");
                
                Integer categoryColumn = finalFieldMapping.get("分类");
                if (categoryColumn == null) categoryColumn = finalFieldMapping.get("category");
                if (categoryColumn == null) categoryColumn = finalFieldMapping.get("科目");
                
                Integer explanationColumn = finalFieldMapping.get("解析");
                if (explanationColumn == null) explanationColumn = finalFieldMapping.get("explanation");
                if (explanationColumn == null) explanationColumn = finalFieldMapping.get("答案解析");
                
                // 读取数据行（从第二行开始，第一行是表头）
                int totalRows = sheet.getLastRowNum();
                result.totalQuestions = totalRows;
                
                for (int i = 1; i <= totalRows; i++) {
                    // 检查是否取消导入
                    if (isImportCancelled) {
                        result.summary = "导入已取消";
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
                        
                        // 设置正确答案
                        if (answerColumn != null) {
                            String answer = getCellValueAsString(row.getCell(answerColumn));
                            question.setCorrectAnswer(answer);
                        }
                        
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
                        
                        // 设置选项
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
                    
                    // 更新进度
                    if (callback != null) {
                        callback.onProgress(i, totalRows);
                    }
                }
                
                result.importTime = System.currentTimeMillis() - startTime;
                result.summary = "导入完成，成功: " + result.validQuestions + ", 失败: " + result.invalidQuestions;
                
                if (callback != null) {
                    callback.onComplete(questions, result);
                }
                
            } catch (IOException e) {
                Log.e(TAG, "Error importing Excel file: " + e.getMessage(), e);
                if (callback != null) {
                    callback.onError("导入失败: " + e.getMessage());
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
}
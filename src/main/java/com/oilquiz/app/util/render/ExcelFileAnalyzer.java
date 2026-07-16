package com.oilquiz.app.util.render;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.model.Question;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Excel文件分析器- 用于分析Excel文件的结构和内容
 */
public class ExcelFileAnalyzer {
    private static final String TAG = "ExcelFileAnalyzer";
    private static List<String> analysisLogs = new ArrayList<>();

    /**
     * 分析Excel文件
     * @param file Excel文件
     * @param context 上下文
     * @param callback 分析完成回调
     */
    public static void analyzeExcelFile(File file, Context context, AnalysisCallback callback) {
        // 清空之前的日志
        analysisLogs.clear();
        
        if (file == null || !file.exists()) {
            String errorMessage = "文件不存在: " + file;
            Log.e(TAG, errorMessage);
            analysisLogs.add("[错误] " + errorMessage);
            if (callback != null) {
                callback.onComplete(analysisLogs);
            }
            return;
        }

        String log = "开始分析文件: " + file.getName();
        Log.i(TAG, log);
        analysisLogs.add("[信息] " + log);
        
        log = "文件大小: " + (file.length() / 1024) + " KB";
        Log.i(TAG, log);
        analysisLogs.add("[信息] " + log);

        // 使用ExcelUtil导入文件以分析其结构
        ExcelUtil.importExcel(file, new ExcelUtil.ImportCallback() {
            @Override
            public void onProgress(int current, int total) {
                // 分析过程中的进度更新
                if (current % 100 == 0) {
                    String progressLog = "分析进度: " + current + "/" + total;
                    Log.i(TAG, progressLog);
                    analysisLogs.add("[进度] " + progressLog);
                }
            }

            @Override
            public void onError(String message) {
                String errorLog = "分析错误: " + message;
                Log.e(TAG, errorLog);
                analysisLogs.add("[错误] " + errorLog);
            }

            @Override
            public void onComplete(List<Question> questions, ExcelUtil.ImportResult result) {
                String completeLog = "分析完成";
                Log.i(TAG, completeLog);
                analysisLogs.add("[信息] " + completeLog);
                
                String log = "总题目数: " + result.totalQuestions;
                Log.i(TAG, log);
                analysisLogs.add("[信息] " + log);
                
                log = "有效题目数: " + result.validQuestions;
                Log.i(TAG, log);
                analysisLogs.add("[信息] " + log);
                
                log = "无效题目数: " + result.invalidQuestions;
                Log.i(TAG, log);
                analysisLogs.add("[信息] " + log);
                
                log = "跳过题目数: " + result.skippedQuestions;
                Log.i(TAG, log);
                analysisLogs.add("[信息] " + log);
                
                log = "导入时间: " + result.importTime + " ms";
                Log.i(TAG, log);
                analysisLogs.add("[信息] " + log);

                if (result.errorMessages != null && !result.errorMessages.isEmpty()) {
                    log = "错误信息 (前10条):";
                    Log.i(TAG, log);
                    analysisLogs.add("[信息] " + log);
                    
                    for (int i = 0; i < Math.min(10, result.errorMessages.size()); i++) {
                        String errorMessage = "- " + result.errorMessages.get(i);
                        Log.i(TAG, errorMessage);
                        analysisLogs.add("[错误] " + errorMessage);
                    }
                    if (result.errorMessages.size() > 10) {
                        String moreErrors = "... 还有 " + (result.errorMessages.size() - 10) + " 条错误信息";
                        Log.i(TAG, moreErrors);
                        analysisLogs.add("[信息] " + moreErrors);
                    }
                }

                // 分析题目类型分布
                analyzeQuestionTypes(questions);

                // 分析难度分布
                analyzeDifficultyDistribution(questions);

                // 分析题目长度
                analyzeQuestionLength(questions);
                
                // 调用回调
                if (callback != null) {
                    callback.onComplete(analysisLogs);
                }
            }
        });
    }

    /**
     * 分析题目类型分布
     * @param questions 题目列表
     */
    private static void analyzeQuestionTypes(List<Question> questions) {
        Map<String, Integer> typeCount = new java.util.HashMap<>();

        for (Question question : questions) {
            String type = question.getQuestionType();
            if (type == null || type.isEmpty()) {
                type = "未分类";
            }
            typeCount.put(type, typeCount.getOrDefault(type, 0) + 1);
        }

        String log = "题目类型分布:";
        Log.i(TAG, log);
        analysisLogs.add("[信息] " + log);
        
        for (Map.Entry<String, Integer> entry : typeCount.entrySet()) {
            String typeLog = entry.getKey() + ": " + entry.getValue() + " 个";
            Log.i(TAG, typeLog);
            analysisLogs.add("[信息] " + typeLog);
        }
    }

    /**
     * 分析难度分布
     * @param questions 题目列表
     */
    private static void analyzeDifficultyDistribution(List<Question> questions) {
        int[] difficultyCount = new int[6]; // 难度1-5

        for (Question question : questions) {
            int difficulty = question.getDifficulty();
            if (difficulty >= 1 && difficulty <= 5) {
                difficultyCount[difficulty]++;
            } else {
                difficultyCount[0]++;
            }
        }

        String log = "难度分布:";
        Log.i(TAG, log);
        analysisLogs.add("[信息] " + log);
        
        for (int i = 1; i <= 5; i++) {
            String difficultyLog = "难度 " + i + ": " + difficultyCount[i] + " 个";
            Log.i(TAG, difficultyLog);
            analysisLogs.add("[信息] " + difficultyLog);
        }
        if (difficultyCount[0] > 0) {
            String noDifficultyLog = "未设置难度 " + difficultyCount[0] + " 个";
            Log.i(TAG, noDifficultyLog);
            analysisLogs.add("[信息] " + noDifficultyLog);
        }
    }

    /**
     * 分析题目长度
     * @param questions 题目列表
     */
    private static void analyzeQuestionLength(List<Question> questions) {
        if (questions.isEmpty()) {
            return;
        }

        int minLength = Integer.MAX_VALUE;
        int maxLength = 0;
        int totalLength = 0;

        for (Question question : questions) {
            String questionText = question.getQuestionText();
            if (questionText != null) {
                int length = questionText.length();
                minLength = Math.min(minLength, length);
                maxLength = Math.max(maxLength, length);
                totalLength += length;
            }
        }

        double averageLength = (double) totalLength / questions.size();

        String log = "题目长度分析:";
        Log.i(TAG, log);
        analysisLogs.add("[信息] " + log);
        
        log = "最短题目: " + minLength + " 字符";
        Log.i(TAG, log);
        analysisLogs.add("[信息] " + log);
        
        log = "最长题目: " + maxLength + " 字符";
        Log.i(TAG, log);
        analysisLogs.add("[信息] " + log);
        
        log = "平均长度: " + String.format("%.2f", averageLength) + " 字符";
        Log.i(TAG, log);
        analysisLogs.add("[信息] " + log);
    }
    
    /**
     * 分析完成回调接口
     */
    public interface AnalysisCallback {
        void onComplete(List<String> logs);
    }
    
    /**
     * 获取分析日志
     * @return 分析日志列表
     */
    public static List<String> getAnalysisLogs() {
        return analysisLogs;
    }
}

package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.database.DatabaseManager;
import com.oilquiz.app.model.Question;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class AIFileParser {

    private static final String TAG = "AIFileParser";
    private final Context context;
    private final DatabaseManager databaseManager;

    public AIFileParser(Context context) {
        this.context = context;
        this.databaseManager = DatabaseManager.getInstance(context);
    }

    // 解析文件并导入题目
    public String parseAndImportFile(String filePath) {
        try {
            // 检测文件类型
            String fileType = getFileType(filePath);
            List<Question> questions = new ArrayList<>();

            switch (fileType) {
                case "txt":
                    questions = parseTxtFile(filePath);
                    break;
                case "csv":
                    questions = parseCsvFile(filePath);
                    break;
                case "json":
                    questions = parseJsonFile(filePath);
                    break;
                default:
                    return "不支持的文件类型: " + fileType;
            }

            // 导入题目到数据库
            int importedCount = importQuestionsToDatabase(questions);
            return "文件解析完成，成功导入 " + importedCount + " 道题目";
        } catch (Exception e) {
            Log.e(TAG, "Error parsing file", e);
            return "解析文件时出错: " + e.getMessage();
        }
    }

    // 获取文件类型
    private String getFileType(String filePath) {
        int lastDotIndex = filePath.lastIndexOf('.');
        if (lastDotIndex != -1 && lastDotIndex < filePath.length() - 1) {
            return filePath.substring(lastDotIndex + 1).toLowerCase();
        }
        return "";
    }

    // 解析文本文件
    private List<Question> parseTxtFile(String filePath) throws IOException {
        List<Question> questions = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new FileReader(filePath));
        StringBuilder questionText = new StringBuilder();
        StringBuilder options = new StringBuilder();
        String answer = "";
        String explanation = "";
        boolean isQuestion = false;

        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("Q:") || line.startsWith("题目:")) {
                // 保存上一个题目
                if (isQuestion) {
                    questions.add(createQuestion(questionText.toString(), options.toString(), answer, explanation));
                }
                // 开始新题目
                questionText = new StringBuilder(line.substring(line.indexOf(':') + 1).trim());
                options = new StringBuilder();
                answer = "";
                explanation = "";
                isQuestion = true;
            } else if (line.startsWith("A:") || line.startsWith("选项:")) {
                options.append(line.substring(line.indexOf(':') + 1).trim()).append("\n");
            } else if (line.startsWith("答案:") || line.startsWith("正确答案:")) {
                answer = line.substring(line.indexOf(':') + 1).trim();
            } else if (line.startsWith("解析:") || line.startsWith("说明:")) {
                explanation = line.substring(line.indexOf(':') + 1).trim();
            } else if (isQuestion) {
                // 继续题目文本
                questionText.append(" ").append(line);
            }
        }

        // 保存最后一个题目
        if (isQuestion) {
            questions.add(createQuestion(questionText.toString(), options.toString(), answer, explanation));
        }

        reader.close();
        return questions;
    }

    // 解析CSV文件
    private List<Question> parseCsvFile(String filePath) throws IOException {
        List<Question> questions = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new FileReader(filePath));

        // 跳过表头
        reader.readLine();

        String line;
        while ((line = reader.readLine()) != null) {
            String[] parts = line.split(",");
            if (parts.length >= 4) {
                String questionText = parts[0];
                String optionsText = parts[1];
                String answer = parts[2];
                String explanation = parts.length > 3 ? parts[3] : "";
                questions.add(createQuestion(questionText, optionsText, answer, explanation));
            }
        }

        reader.close();
        return questions;
    }

    // 解析JSON文件
    private List<Question> parseJsonFile(String filePath) throws IOException {
        // 这里应该实现JSON解析
        // 暂时返回空列表
        return new ArrayList<>();
    }

    // 创建题目对象
    private Question createQuestion(String questionText, String optionsText, String answer, String explanation) {
        Question question = new Question();
        question.setQuestionText(questionText);
        
        // 解析选项
        String[] optionLines = optionsText.split("\n");
        for (int i = 0; i < optionLines.length && i < 4; i++) {
            String option = optionLines[i].trim();
            switch (i) {
                case 0:
                    question.setOptionA(option);
                    break;
                case 1:
                    question.setOptionB(option);
                    break;
                case 2:
                    question.setOptionC(option);
                    break;
                case 3:
                    question.setOptionD(option);
                    break;
            }
        }
        
        question.setCorrectAnswer(answer);
        question.setExplanation(explanation);
        question.setDifficulty(2); // 1=简单, 2=中等, 3=困难
        question.setQuestionType("选择题");
        question.setCategory("通用");
        
        return question;
    }

    // 导入题目到数据库
    private int importQuestionsToDatabase(List<Question> questions) {
        int count = 0;
        try {
            boolean success = databaseManager.addQuestions(questions).get();
            if (success) {
                count = questions.size();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error inserting questions", e);
        }
        return count;
    }
}

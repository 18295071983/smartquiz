package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.database.DatabaseManager;
import com.oilquiz.app.model.Question;

import org.json.JSONObject;

import com.oilquiz.app.model.ScoreHistory;
import com.oilquiz.app.model.User;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class AIToolsManager {

    private static final String TAG = "AIToolsManager";
    private final Context context;
    private final AIService aiService;
    private final DatabaseManager databaseManager;

    public AIToolsManager(Context context) {
        this.context = context;
        this.aiService = AIService.getInstance(context);
        this.databaseManager = DatabaseManager.getInstance(context);
    }

    // 工具列表
    public static class Tool {
        public static final String GENERATE_QUESTIONS = "generate_questions";
        public static final String ANALYZE_QUESTION = "analyze_question";
        public static final String TRANSLATE_TEXT = "translate_text";
        public static final String CREATE_STUDY_PLAN = "create_study_plan";
        public static final String GET_STATISTICS = "get_statistics";
        public static final String SEARCH_QUESTIONS = "search_questions";
        public static final String GET_WEATHER = "get_weather";
        public static final String IMPORT_QUESTIONS = "import_questions";
        public static final String EXPORT_QUESTIONS = "export_questions";
        public static final String DATABASE_OPERATIONS = "database_operations";
    }

    // 执行工具调用
    public CompletableFuture<String> executeTool(String toolName, String parameters) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (toolName == null || toolName.trim().isEmpty()) {
                    Log.e(TAG, "Tool name is null or empty");
                    return "错误: 工具名称不能为空";
                }
                
                String params = normalizeParameters(parameters);
                
                switch (toolName) {
                    case Tool.GENERATE_QUESTIONS:
                        return generateQuestions(params);
                    case Tool.ANALYZE_QUESTION:
                        return analyzeQuestion(params);
                    case Tool.TRANSLATE_TEXT:
                    case "translation":
                        return translateText(params);
                    case Tool.CREATE_STUDY_PLAN:
                        return createStudyPlan(params);
                    case Tool.GET_STATISTICS:
                        return getStatistics(params);
                    case Tool.SEARCH_QUESTIONS:
                        return searchQuestions(params);
                    case Tool.GET_WEATHER:
                        return getWeather(params);
                    case Tool.IMPORT_QUESTIONS:
                        return importQuestions(params);
                    case Tool.EXPORT_QUESTIONS:
                        return exportQuestions(params);
                    case Tool.DATABASE_OPERATIONS:
                    case "database_query":
                    case "database":
                        return databaseOperations(params);
                    default:
                        return "错误: 未知的工具名称: " + toolName;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error executing tool: " + toolName, e);
                return "错误: 执行工具时出错: " + e.getMessage();
            }
        });
    }

    private String normalizeParameters(String parameters) {
        if (parameters == null || parameters.trim().isEmpty()) return "";
        String trimmed = parameters.trim();
        if (trimmed.startsWith("{")) {
            try {
                JSONObject json = new JSONObject(trimmed);
                StringBuilder sb = new StringBuilder();
                Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(key).append(": ").append(json.optString(key, ""));
                }
                return sb.toString();
            } catch (Exception e) {
                Log.w(TAG, "Failed to parse JSON parameters, using as-is");
            }
        }
        return trimmed;
    }

    // 生成题目
    private String generateQuestions(String parameters) {
        // 解析参数: topic, count, difficulty, type
        // 格式: "topic: 石油工程, count: 5, difficulty: 中等, type: 选择题"
        try {
            String[] params = parameters.split(",");
            String topic = ""; 
            int count = 5;
            String difficulty = "中等";
            String type = "选择题";

            for (String param : params) {
                param = param.trim();
                if (param.startsWith("topic:")) {
                    topic = param.substring(6).trim();
                } else if (param.startsWith("count:")) {
                    count = Integer.parseInt(param.substring(6).trim());
                } else if (param.startsWith("difficulty:")) {
                    difficulty = param.substring(11).trim();
                } else if (param.startsWith("type:")) {
                    type = param.substring(5).trim();
                }
            }

            // 构建提示词
            StringBuilder prompt = new StringBuilder();
            prompt.append("Generate " + count + " " + type + " questions about " + topic + " at " + difficulty + " difficulty level.\n");
            prompt.append("For each question, provide:\n");
            prompt.append("1. Question text\n");
            prompt.append("2. Options (if applicable)\n");
            prompt.append("3. Correct answer\n");
            prompt.append("4. Explanation\n");

            // 调用AI服务
            String result = aiService.generateSync(prompt.toString(), 2000);
            return "题目生成成功:\n" + result;
        } catch (Exception e) {
            return "生成题目时出错: " + e.getMessage();
        }
    }

    // 分析题目
    private String analyzeQuestion(String parameters) {
        // 解析参数: question, options, answer
        // 格式: "question: 石油的主要成分是什么?, options: A. 碳氢化合物 B. 水 C. 矿物质 D. 金属, answer: A"
        try {
            String[] params = parameters.split(",");
            String question = "";
            String options = "";
            String answer = "";

            for (String param : params) {
                param = param.trim();
                if (param.startsWith("question:")) {
                    question = param.substring(9).trim();
                } else if (param.startsWith("options:")) {
                    options = param.substring(8).trim();
                } else if (param.startsWith("answer:")) {
                    answer = param.substring(7).trim();
                }
            }

            // 构建提示词
            StringBuilder prompt = new StringBuilder();
            prompt.append("Analyze the following question:\n");
            prompt.append("Question: " + question + "\n");
            if (!options.isEmpty()) {
                prompt.append("Options: " + options + "\n");
            }
            if (!answer.isEmpty()) {
                prompt.append("Correct Answer: " + answer + "\n");
            }
            prompt.append("\nPlease provide:\n");
            prompt.append("1. A detailed explanation\n");
            prompt.append("2. Related knowledge points\n");
            prompt.append("3. Learning suggestions");

            // 调用AI服务
            String result = aiService.generateSync(prompt.toString(), 1500);
            return "题目分析结果:\n" + result;
        } catch (Exception e) {
            return "分析题目时出错: " + e.getMessage();
        }
    }

    // 翻译文本
    private String translateText(String parameters) {
        // 解析参数: text, source, target
        // 格式: "text: Hello world, source: English, target: Chinese"
        try {
            String[] params = parameters.split(",");
            String text = "";
            String source = "English";
            String target = "Chinese";

            for (String param : params) {
                param = param.trim();
                if (param.startsWith("text:")) {
                    text = param.substring(5).trim();
                } else if (param.startsWith("source:")) {
                    source = param.substring(7).trim();
                } else if (param.startsWith("target:")) {
                    target = param.substring(7).trim();
                }
            }

            // 构建提示词
            String prompt = "Translate the following text from " + source + " to " + target + ":\n" + text;

            // 调用AI服务
            String result = aiService.generateSync(prompt.toString(), 1000);
            return "翻译结果:\n" + result;
        } catch (Exception e) {
            return "翻译时出错: " + e.getMessage();
        }
    }

    // 创建学习计划
    private String createStudyPlan(String parameters) {
        // 解析参数: subject, hours, difficulty
        // 格式: "subject: 石油工程, hours: 5, difficulty: 中级"
        try {
            String[] params = parameters.split(",");
            String subject = "";
            int hours = 5;
            String difficulty = "中级";

            for (String param : params) {
                param = param.trim();
                if (param.startsWith("subject:")) {
                    subject = param.substring(8).trim();
                } else if (param.startsWith("hours:")) {
                    hours = Integer.parseInt(param.substring(6).trim());
                } else if (param.startsWith("difficulty:")) {
                    difficulty = param.substring(11).trim();
                }
            }

            // 构建提示词
            String prompt = "Create a study plan for " + subject + " with " + hours + " hours per week at " + difficulty + " level. Include weekly topics, daily schedule, and resources.";

            // 调用AI服务
            String result = aiService.generateSync(prompt.toString(), 1500);
            return "学习计划:\n" + result;
        } catch (Exception e) {
            return "创建学习计划时出错: " + e.getMessage();
        }
    }

    // 获取统计信息
    private String getStatistics(String parameters) {
        // 解析参数: type (可选), userId (可选)
        // 格式: "type: 答题, userId: 1"
        try {
            String type = "all";
            Long userId = null;
            if (parameters != null && !parameters.isEmpty()) {
                if (parameters.contains("type:")) {
                    type = parameters.split("type:")[1].split(",")[0].trim();
                }
                if (parameters.contains("userId:")) {
                    String userIdStr = parameters.split("userId:")[1].split(",")[0].trim();
                    try {
                        userId = Long.parseLong(userIdStr);
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "无效的用户ID: " + userIdStr);
                    }
                }
            }

            // 从数据库获取统计信息
            int totalQuestions = databaseManager.getQuestionCount().get(10, TimeUnit.SECONDS);
            DatabaseManager.QuestionStatistics stats = databaseManager.getQuestionStatistics().get(10, TimeUnit.SECONDS);
            
            int completedQuizzes = 0;
            double averageScore = 0.0;
            
            if (userId != null) {
                List<ScoreHistory> scoreHistory = databaseManager.getScoreHistory(userId).get(10, TimeUnit.SECONDS);
                completedQuizzes = scoreHistory.size();
                Float avgScore = databaseManager.getAverageScore(userId).get(10, TimeUnit.SECONDS);
                averageScore = avgScore != null ? avgScore : 0.0;
            } else {
                List<ScoreHistory> allScores = databaseManager.getScoreHistoryByCategory(type).get(10, TimeUnit.SECONDS);
                completedQuizzes = allScores.size();
                if (!allScores.isEmpty()) {
                    float totalScore = 0;
                    for (ScoreHistory score : allScores) {
                        totalScore += score.getScore();
                    }
                    averageScore = totalScore / allScores.size();
                }
            }

            StringBuilder result = new StringBuilder();
            result.append("学习统计信息:\n");
            result.append("总题目数: " + totalQuestions + "\n");
            result.append("简单题: " + stats.easyQuestions + "\n");
            result.append("中等题: " + stats.mediumQuestions + "\n");
            result.append("困难题: " + stats.hardQuestions + "\n");
            result.append("分类数: " + stats.categories + "\n");
            result.append("已完成测验数: " + completedQuizzes + "\n");
            result.append("平均得分: " + String.format("%.2f", averageScore) + "\n");

            return result.toString();
        } catch (Exception e) {
            return "获取统计信息时出错: " + e.getMessage();
        }
    }

    // 搜索题目
    private String searchQuestions(String parameters) {
        // 解析参数: keyword
        // 格式: "keyword: 石油开采"
        try {
            String keyword = "";
            if (parameters != null && !parameters.isEmpty()) {
                if (parameters.contains("keyword:")) {
                    keyword = parameters.split("keyword:")[1].trim();
                }
            }

            // 从数据库搜索题目
            List<Question> questions = databaseManager.searchQuestions(keyword).get(10, TimeUnit.SECONDS);
            if (questions.isEmpty()) {
                return "未找到相关题目";
            }
            StringBuilder result = new StringBuilder();
            result.append("搜索结果 (共 " + questions.size() + " 个题目):\n\n");

            for (int i = 0; i < Math.min(5, questions.size()); i++) {
                Question q = questions.get(i);
                result.append((i + 1) + ". " + q.getQuestionText() + "\n");
                result.append("答案: " + q.getCorrectAnswer() + "\n\n");
            }

            if (questions.size() > 5) {
                result.append("... 还有 " + (questions.size() - 5) + " 个题目未显示");
            }

            return result.toString();
        } catch (Exception e) {
            return "搜索题目时出错: " + e.getMessage();
        }
    }

    // 获取天气信息
    private String getWeather(String parameters) {
        // 解析参数
        // 格式1: "city: 北京" - 基本天气查询
        // 格式2: "使用说明" - 显示使用说明
        // 格式3: "详细 39.9 116.4" - 详细天气查询
        // 格式4: "历史 39.9 116.4 1620000000" - 历史天气查询
        // 格式5: "每日 39.9 116.4 1620000000 1620864000" - 每日聚合天气
        // 格式6: "概览 39.9 116.4" - 天气概览
        try {
            AIWeatherManager weatherManager = new AIWeatherManager(context, AIWeatherManager.WeatherProvider.HEFENG);
            
            if (parameters == null || parameters.isEmpty()) {
                // 默认查询北京天气
                String result = weatherManager.getWeather("北京").get(15, TimeUnit.SECONDS);
                return result;
            }
            
            // 检查是否是使用说明
            if (parameters.trim().equals("使用说明")) {
                return weatherManager.getWeatherToolUsage();
            }
            
            // 检查是否是详细天气查询
            if (parameters.trim().startsWith("详细 ")) {
                String[] parts = parameters.trim().split(" ");
                if (parts.length >= 3) {
                    try {
                        double lat = Double.parseDouble(parts[1]);
                        double lon = Double.parseDouble(parts[2]);
                        String result = weatherManager.getOneCallWeather(lat, lon, null, "metric", "zh_cn").get(15, TimeUnit.SECONDS);
                        return result;
                    } catch (NumberFormatException e) {
                        return "经纬度格式错误，请提供有效的数字";
                    }
                } else {
                    return "请提供经纬度，例如: 天气 详细 39.9 116.4";
                }
            }
            
            // 检查是否是历史天气查询
            if (parameters.trim().startsWith("历史 ")) {
                String[] parts = parameters.trim().split(" ");
                if (parts.length >= 4) {
                    try {
                        double lat = Double.parseDouble(parts[1]);
                        double lon = Double.parseDouble(parts[2]);
                        long timestamp = Long.parseLong(parts[3]);
                        String result = weatherManager.getTimestampWeather(lat, lon, timestamp, "metric", "zh_cn").get(15, TimeUnit.SECONDS);
                        return result;
                    } catch (NumberFormatException e) {
                        return "参数格式错误，请提供有效的数字";
                    }
                } else {
                    return "请提供经纬度和时间戳，例如: 天气 历史 39.9 116.4 1620000000";
                }
            }
            
            // 检查是否是每日聚合天气
            if (parameters.trim().startsWith("每日 ")) {
                String[] parts = parameters.trim().split(" ");
                if (parts.length >= 5) {
                    try {
                        double lat = Double.parseDouble(parts[1]);
                        double lon = Double.parseDouble(parts[2]);
                        long startDate = Long.parseLong(parts[3]);
                        long endDate = Long.parseLong(parts[4]);
                        String result = weatherManager.getDailyAggregationWeather(lat, lon, startDate, endDate, "metric", "zh_cn").get(15, TimeUnit.SECONDS);
                        return result;
                    } catch (NumberFormatException e) {
                        return "参数格式错误，请提供有效的数字";
                    }
                } else {
                    return "请提供经纬度和日期范围，例如: 天气 每日 39.9 116.4 1620000000 1620864000";
                }
            }
            
            // 检查是否是天气概览
            if (parameters.trim().startsWith("概览 ")) {
                String[] parts = parameters.trim().split(" ");
                if (parts.length >= 3) {
                    try {
                        double lat = Double.parseDouble(parts[1]);
                        double lon = Double.parseDouble(parts[2]);
                        String result = weatherManager.getWeatherOverview(lat, lon, "metric", "zh_cn").get(15, TimeUnit.SECONDS);
                        return result;
                    } catch (NumberFormatException e) {
                        return "经纬度格式错误，请提供有效的数字";
                    }
                } else {
                    return "请提供经纬度，例如: 天气 概览 39.9 116.4";
                }
            }
            
            // 基本天气查询
            String city = "北京";
            if (parameters.contains("city:")) {
                city = parameters.split("city:")[1].trim();
            } else {
                city = parameters.trim();
            }
            
            String result = weatherManager.getWeather(city).get(15, TimeUnit.SECONDS);
            return result;
        } catch (Exception e) {
            return "获取天气时出错: " + e.getMessage();
        }
    }

    // 导入题目
    private String importQuestions(String parameters) {
        // 解析参数: file_path
        // 格式: "file_path: /storage/emulated/0/Download/questions.txt"
        try {
            String filePath = "";
            if (parameters != null && !parameters.isEmpty() && parameters.contains("file_path:")) {
                filePath = parameters.split("file_path:")[1].trim();
            }

            if (filePath.isEmpty()) {
                return "错误: 请提供文件路径";
            }

            // 使用AIFileParser解析文件并导入题目
            AIFileParser fileParser = new AIFileParser(context);
            return fileParser.parseAndImportFile(filePath);
        } catch (Exception e) {
            return "导入题目时出错: " + e.getMessage();
        }
    }

    // 导出题目
    private String exportQuestions(String parameters) {
        // 解析参数: format, output_path
        // 格式: "format: csv, output_path: /storage/emulated/0/Download"
        try {
            String format = "csv";
            String outputPath = "/storage/emulated/0/Download";

            if (parameters != null && !parameters.isEmpty()) {
                if (parameters.contains("format:")) {
                    format = parameters.split("format:")[1].split(",")[0].trim();
                }
                if (parameters.contains("output_path:")) {
                    outputPath = parameters.split("output_path:")[1].trim();
                }
            }

            // 使用AIFileExporter导出题目
            AIFileExporter fileExporter = new AIFileExporter(context);
            return fileExporter.exportQuestions(format, outputPath);
        } catch (Exception e) {
            return "导出题目时出错: " + e.getMessage();
        }
    }

    // 数据库操作
    private String databaseOperations(String parameters) {
        // 解析参数: operation, table, category, userId
        // 格式: "operation: count, table: questions"
        try {
            String operation = "count";
            String table = "questions";
            String category = null;
            Long userId = null;

            if (parameters != null && !parameters.isEmpty()) {
                if (parameters.contains("operation:")) {
                    operation = parameters.split("operation:")[1].split(",")[0].trim();
                }
                if (parameters.contains("table:")) {
                    table = parameters.split("table:")[1].split(",")[0].trim();
                }
                if (parameters.contains("category:")) {
                    category = parameters.split("category:")[1].split(",")[0].trim();
                }
                if (parameters.contains("userId:")) {
                    String userIdStr = parameters.split("userId:")[1].split(",")[0].trim();
                    try {
                        userId = Long.parseLong(userIdStr);
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "无效的用户ID: " + userIdStr);
                    }
                }
            }

            StringBuilder result = new StringBuilder();
            result.append("数据库操作结果:\n");
            
            // 执行数据库操作
            if (operation.equals("count")) {
                if (table.equals("questions")) {
                    int count = databaseManager.getQuestionCount().get(10, TimeUnit.SECONDS);
                    result.append("题目总数: " + count);
                    return result.toString();
                } else if (table.equals("scores") || table.equals("quizzes")) {
                    List<ScoreHistory> scores;
                    if (userId != null) {
                        scores = databaseManager.getScoreHistory(userId).get(10, TimeUnit.SECONDS);
                    } else if (category != null) {
                        scores = databaseManager.getScoreHistoryByCategory(category).get(10, TimeUnit.SECONDS);
                    } else {
                        scores = databaseManager.getScoreHistoryByCategory("all").get(10, TimeUnit.SECONDS);
                    }
                    result.append("测验记录数: " + scores.size());
                    if (!scores.isEmpty()) {
                        float totalScore = 0;
                        for (ScoreHistory score : scores) {
                            totalScore += score.getScore();
                        }
                        result.append("\n平均得分: " + String.format("%.2f", totalScore / scores.size()));
                    }
                    return result.toString();
                } else if (table.equals("categories")) {
                    List<String> categories = databaseManager.getAllCategories().get(10, TimeUnit.SECONDS);
                    result.append("分类数: " + categories.size());
                    if (!categories.isEmpty()) {
                        result.append("\n分类列表: " + String.join(", ", categories));
                    }
                    return result.toString();
                } else if (table.equals("types")) {
                    List<String> types = databaseManager.getAllQuestionTypes().get(10, TimeUnit.SECONDS);
                    result.append("题目类型数: " + types.size());
                    if (!types.isEmpty()) {
                        result.append("\n类型列表: " + String.join(", ", types));
                    }
                    return result.toString();
                }
            } else if (operation.equals("statistics")) {
                DatabaseManager.QuestionStatistics stats = databaseManager.getQuestionStatistics().get(10, TimeUnit.SECONDS);
                result.append("总题目数: " + stats.totalQuestions + "\n");
                result.append("简单题: " + stats.easyQuestions + "\n");
                result.append("中等题: " + stats.mediumQuestions + "\n");
                result.append("困难题: " + stats.hardQuestions + "\n");
                result.append("无难度题: " + stats.noDifficultyQuestions + "\n");
                result.append("分类数: " + stats.categories + "\n");
                result.append("类型数: " + stats.questionTypes);
                return result.toString();
            } else if (operation.equals("version")) {
                int version = databaseManager.getDatabaseVersion();
                result.append("数据库版本: " + version);
                return result.toString();
            } else if (operation.equals("size")) {
                long size = databaseManager.getDatabaseSize();
                result.append("数据库大小: " + String.format("%.2f KB", size / 1024.0));
                return result.toString();
            }

            return "数据库操作失败，操作: " + operation + "，表: " + table;
        } catch (Exception e) {
            return "数据库操作时出错: " + e.getMessage();
        }
    }

    // 获取可用工具列表
    public String getAvailableTools() {
        StringBuilder tools = new StringBuilder();
        tools.append("可用工具:\n");
        tools.append("1. generate_questions - 生成题目\n");
        tools.append("   参数: topic: 主题, count: 数量, difficulty: 难度, type: 类型\n");
        tools.append("2. analyze_question - 分析题目\n");
        tools.append("   参数: question: 题目, options: 选项, answer: 答案\n");
        tools.append("3. translate_text - 翻译文本\n");
        tools.append("   参数: text: 文本, source: 源语言, target: 目标语言\n");
        tools.append("4. create_study_plan - 创建学习计划\n");
        tools.append("   参数: subject: 科目, hours: 每周小时数, difficulty: 难度\n");
        tools.append("5. get_statistics - 获取学习统计\n");
        tools.append("   参数: type: 统计类型 (可选)\n");
        tools.append("6. search_questions - 搜索题目\n");
        tools.append("   参数: keyword: 关键词\n");
        tools.append("7. get_weather - 获取天气\n");
        tools.append("   参数: city: 城市名称\n");
        tools.append("8. import_questions - 导入题目\n");
        tools.append("   参数: file_path: 文件路径\n");
        tools.append("9. export_questions - 导出题目\n");
        tools.append("   参数: format: 格式, output_path: 输出路径\n");
        tools.append("10. database_operations - 数据库操作\n");
        tools.append("   参数: operation: 操作, table: 表名\n");
        return tools.toString();
    }
}

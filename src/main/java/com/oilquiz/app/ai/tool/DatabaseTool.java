package com.oilquiz.app.ai.tool;

import android.content.Context;
import com.oilquiz.app.database.DatabaseManager;
import com.oilquiz.app.model.Question;
import com.oilquiz.app.model.User;
import com.oilquiz.app.model.ScoreHistory;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class DatabaseTool implements AITool {
    private static final String TAG = "DatabaseTool";
    private final Context context;
    private final DatabaseManager databaseManager;
    
    public DatabaseTool(Context context) {
        this.context = context.getApplicationContext();
        this.databaseManager = DatabaseManager.getInstance(context);
    }
    
    @Override
    public String getName() { return "database"; }
    
    @Override
    public String getDescription() { return "数据库操作工具，用于执行题目查询、用户管理、分数记录等操作"; }
    
    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            Object actionObj = parameters.get("action");
            if (actionObj == null) {
                return new AIToolResult("缺少参数: action", parameters);
            }
            String action = actionObj.toString();
            
            switch (action) {
                case "execute_query":
                    return executeQuery(parameters);
                case "get_questions":
                    return getQuestions(parameters);
                case "search_questions":
                    return searchQuestions(parameters);
                case "get_question_count":
                    return getQuestionCount(parameters);
                case "get_question_statistics":
                    return getQuestionStatistics(parameters);
                case "get_all_categories":
                    return getAllCategories(parameters);
                case "get_all_question_types":
                    return getAllQuestionTypes(parameters);
                case "get_question_by_id":
                    return getQuestionById(parameters);
                case "add_questions":
                    return addQuestions(parameters);
                case "update_question":
                    return updateQuestion(parameters);
                case "delete_question":
                    return deleteQuestion(parameters);
                case "clear_all_questions":
                    return clearAllQuestions(parameters);
                case "get_user":
                    return getUser(parameters);
                case "add_user":
                    return addUser(parameters);
                case "get_score_history":
                    return getScoreHistory(parameters);
                case "add_score":
                    return addScore(parameters);
                case "get_average_score":
                    return getAverageScore(parameters);
                case "get_database_version":
                    return getDatabaseVersion(parameters);
                default:
                    return new AIToolResult("未知操作: " + action, parameters);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "数据库操作失败: " + e.getMessage(), e);
            return new AIToolResult("错误: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult executeQuery(Map<String, Object> parameters) {
        Object queryObj = parameters.get("query");
        if (queryObj == null) {
            return new AIToolResult("缺少参数: query", parameters);
        }
        String query = queryObj.toString().toLowerCase().trim();
        
        try {
            if (query.contains("select") && query.contains("question")) {
                return getQuestions(parameters);
            } else if (query.contains("count") && query.contains("question")) {
                return getQuestionCount(parameters);
            } else if (query.contains("statistics")) {
                return getQuestionStatistics(parameters);
            } else if (query.contains("category")) {
                return getAllCategories(parameters);
            } else {
                return getQuestions(parameters);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "执行查询失败: " + e.getMessage(), e);
            return new AIToolResult("执行查询失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult getQuestions(Map<String, Object> parameters) {
        try {
            Integer page = (Integer) parameters.get("page");
            Integer pageSize = (Integer) parameters.get("page_size");
            String category = (String) parameters.get("category");
            String type = (String) parameters.get("type");
            Integer difficulty = (Integer) parameters.get("difficulty");
            String keyword = (String) parameters.get("keyword");
            
            Future<List<Question>> future;
            
            if (keyword != null && !keyword.isEmpty()) {
                if (category != null || type != null || difficulty != null) {
                    future = databaseManager.searchQuestionsWithFilters(keyword, category, type, difficulty);
                } else {
                    future = databaseManager.searchQuestions(keyword);
                }
            } else if (category != null) {
                future = databaseManager.getQuestionsByCategory(category);
            } else if (type != null) {
                future = databaseManager.getQuestionsByType(type);
            } else if (difficulty != null) {
                future = databaseManager.getQuestionsByDifficulty(difficulty);
            } else if (page != null && pageSize != null) {
                future = databaseManager.getQuestionsByPage(page, pageSize);
            } else {
                future = databaseManager.getAllQuestions();
            }
            
            List<Question> questions = future.get(10, TimeUnit.SECONDS);
            List<Map<String, Object>> questionList = convertQuestionsToMap(questions);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("count", questions.size());
            result.put("questions", questionList);
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("获取题目失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult searchQuestions(Map<String, Object> parameters) {
        Object keywordObj = parameters.get("keyword");
        if (keywordObj == null) {
            return new AIToolResult("缺少参数: keyword", parameters);
        }
        String keyword = keywordObj.toString();
        
        try {
            String category = (String) parameters.get("category");
            String type = (String) parameters.get("type");
            Integer difficulty = (Integer) parameters.get("difficulty");
            
            Future<List<Question>> future;
            if (category != null || type != null || difficulty != null) {
                future = databaseManager.searchQuestionsWithFilters(keyword, category, type, difficulty);
            } else {
                future = databaseManager.searchQuestions(keyword);
            }
            
            List<Question> questions = future.get(10, TimeUnit.SECONDS);
            List<Map<String, Object>> questionList = convertQuestionsToMap(questions);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("keyword", keyword);
            result.put("count", questions.size());
            result.put("questions", questionList);
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("搜索题目失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult getQuestionCount(Map<String, Object> parameters) {
        try {
            Future<Integer> future = databaseManager.getQuestionCount();
            int count = future.get(10, TimeUnit.SECONDS);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("count", count);
            result.put("message", "当前题库共有 " + count + " 道题目");
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("获取题目数量失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult getQuestionStatistics(Map<String, Object> parameters) {
        try {
            Future<DatabaseManager.QuestionStatistics> future = databaseManager.getQuestionStatistics();
            DatabaseManager.QuestionStatistics stats = future.get(10, TimeUnit.SECONDS);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("totalQuestions", stats.totalQuestions);
            result.put("easyQuestions", stats.easyQuestions);
            result.put("mediumQuestions", stats.mediumQuestions);
            result.put("hardQuestions", stats.hardQuestions);
            result.put("noDifficultyQuestions", stats.noDifficultyQuestions);
            result.put("categories", stats.categories);
            result.put("questionTypes", stats.questionTypes);
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("获取统计信息失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult getAllCategories(Map<String, Object> parameters) {
        try {
            Future<List<String>> future = databaseManager.getAllCategories();
            List<String> categories = future.get(10, TimeUnit.SECONDS);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("count", categories.size());
            result.put("categories", categories);
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("获取分类失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult getAllQuestionTypes(Map<String, Object> parameters) {
        try {
            Future<List<String>> future = databaseManager.getAllQuestionTypes();
            List<String> types = future.get(10, TimeUnit.SECONDS);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("count", types.size());
            result.put("types", types);
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("获取题目类型失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult getQuestionById(Map<String, Object> parameters) {
        Object idObj = parameters.get("id");
        if (idObj == null) {
            return new AIToolResult("缺少参数: id", parameters);
        }
        
        try {
            long id;
            if (idObj instanceof Number) {
                id = ((Number) idObj).longValue();
            } else {
                id = Long.parseLong(idObj.toString());
            }
            
            Future<Question> future = databaseManager.getQuestionById(id);
            Question question = future.get(10, TimeUnit.SECONDS);
            
            if (question == null) {
                return new AIToolResult("未找到 ID 为 " + id + " 的题目", parameters);
            }
            
            Map<String, Object> questionMap = convertQuestionToMap(question);
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("question", questionMap);
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("获取题目失败: " + e.getMessage(), parameters);
        }
    }
    
    @SuppressWarnings("unchecked")
    private AIToolResult addQuestions(Map<String, Object> parameters) {
        Object questionsObj = parameters.get("questions");
        if (questionsObj == null || !(questionsObj instanceof List)) {
            return new AIToolResult("缺少参数: questions (应为题目列表)", parameters);
        }
        
        try {
            List<Map<String, Object>> questionMaps = (List<Map<String, Object>>) questionsObj;
            List<Question> questions = new ArrayList<>();
            
            for (Map<String, Object> qm : questionMaps) {
                Question q = new Question();
                if (qm.containsKey("questionText")) q.setQuestionText((String) qm.get("questionText"));
                if (qm.containsKey("optionA")) q.setOptionA((String) qm.get("optionA"));
                if (qm.containsKey("optionB")) q.setOptionB((String) qm.get("optionB"));
                if (qm.containsKey("optionC")) q.setOptionC((String) qm.get("optionC"));
                if (qm.containsKey("optionD")) q.setOptionD((String) qm.get("optionD"));
                if (qm.containsKey("correctAnswer")) q.setCorrectAnswer((String) qm.get("correctAnswer"));
                if (qm.containsKey("explanation")) q.setExplanation((String) qm.get("explanation"));
                if (qm.containsKey("category")) q.setCategory((String) qm.get("category"));
                if (qm.containsKey("questionType")) q.setQuestionType((String) qm.get("questionType"));
                if (qm.containsKey("difficulty")) {
                    Object diff = qm.get("difficulty");
                    if (diff instanceof Number) q.setDifficulty(((Number) diff).intValue());
                }
                questions.add(q);
            }
            
            Future<Boolean> future = databaseManager.addQuestions(questions);
            boolean success = future.get(10, TimeUnit.SECONDS);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", success ? "success" : "failed");
            result.put("count", questions.size());
            result.put("message", success ? "成功添加 " + questions.size() + " 道题目" : "添加题目失败");
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("添加题目失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult updateQuestion(Map<String, Object> parameters) {
        Object idObj = parameters.get("id");
        if (idObj == null) {
            return new AIToolResult("缺少参数: id", parameters);
        }
        
        try {
            long id;
            if (idObj instanceof Number) {
                id = ((Number) idObj).longValue();
            } else {
                id = Long.parseLong(idObj.toString());
            }
            
            Future<Question> getFuture = databaseManager.getQuestionById(id);
            Question question = getFuture.get(10, TimeUnit.SECONDS);
            
            if (question == null) {
                return new AIToolResult("未找到 ID 为 " + id + " 的题目", parameters);
            }
            
            if (parameters.containsKey("questionText")) question.setQuestionText((String) parameters.get("questionText"));
            if (parameters.containsKey("optionA")) question.setOptionA((String) parameters.get("optionA"));
            if (parameters.containsKey("optionB")) question.setOptionB((String) parameters.get("optionB"));
            if (parameters.containsKey("optionC")) question.setOptionC((String) parameters.get("optionC"));
            if (parameters.containsKey("optionD")) question.setOptionD((String) parameters.get("optionD"));
            if (parameters.containsKey("correctAnswer")) question.setCorrectAnswer((String) parameters.get("correctAnswer"));
            if (parameters.containsKey("explanation")) question.setExplanation((String) parameters.get("explanation"));
            if (parameters.containsKey("category")) question.setCategory((String) parameters.get("category"));
            if (parameters.containsKey("questionType")) question.setQuestionType((String) parameters.get("questionType"));
            if (parameters.containsKey("difficulty")) {
                Object diff = parameters.get("difficulty");
                if (diff instanceof Number) question.setDifficulty(((Number) diff).intValue());
            }
            
            Future<Boolean> future = databaseManager.updateQuestion(question);
            boolean success = future.get(10, TimeUnit.SECONDS);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", success ? "success" : "failed");
            result.put("id", id);
            result.put("message", success ? "题目更新成功" : "题目更新失败");
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("更新题目失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult deleteQuestion(Map<String, Object> parameters) {
        Object idObj = parameters.get("id");
        if (idObj == null) {
            return new AIToolResult("缺少参数: id", parameters);
        }
        
        try {
            long id;
            if (idObj instanceof Number) {
                id = ((Number) idObj).longValue();
            } else {
                id = Long.parseLong(idObj.toString());
            }
            
            Future<Boolean> future = databaseManager.deleteQuestion(id);
            boolean success = future.get(10, TimeUnit.SECONDS);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", success ? "success" : "failed");
            result.put("id", id);
            result.put("message", success ? "题目删除成功" : "题目删除失败");
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("删除题目失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult clearAllQuestions(Map<String, Object> parameters) {
        try {
            Future<Boolean> future = databaseManager.clearAllQuestions();
            boolean success = future.get(10, TimeUnit.SECONDS);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", success ? "success" : "failed");
            result.put("message", success ? "所有题目已清空" : "清空失败");
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("清空题目失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult getUser(Map<String, Object> parameters) {
        Object usernameObj = parameters.get("username");
        if (usernameObj == null) {
            return new AIToolResult("缺少参数: username", parameters);
        }
        String username = usernameObj.toString();
        
        try {
            Future<User> future = databaseManager.getUser(username);
            User user = future.get(10, TimeUnit.SECONDS);
            
            if (user == null) {
                return new AIToolResult("未找到用户: " + username, parameters);
            }
            
            Map<String, Object> userMap = new HashMap<>();
            userMap.put("id", user.getId());
            userMap.put("username", user.getUsername());
            userMap.put("email", user.getEmail());
            userMap.put("phone", user.getPhone());
            userMap.put("avatar", user.getAvatar());
            userMap.put("isLoggedIn", user.getIsLoggedIn());
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("user", userMap);
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("获取用户失败: " + e.getMessage(), parameters);
        }
    }
    
    @SuppressWarnings("unchecked")
    private AIToolResult addUser(Map<String, Object> parameters) {
        try {
            User user = new User();
            if (parameters.containsKey("username")) user.setUsername((String) parameters.get("username"));
            if (parameters.containsKey("email")) user.setEmail((String) parameters.get("email"));
            if (parameters.containsKey("phone")) user.setPhone((String) parameters.get("phone"));
            if (parameters.containsKey("password")) user.setPassword((String) parameters.get("password"));
            if (parameters.containsKey("avatar")) user.setAvatar((String) parameters.get("avatar"));
            
            Future<Long> future = databaseManager.addUser(user);
            long userId = future.get(10, TimeUnit.SECONDS);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", userId > 0 ? "success" : "failed");
            result.put("userId", userId);
            result.put("message", userId > 0 ? "用户添加成功" : "用户添加失败");
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("添加用户失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult getScoreHistory(Map<String, Object> parameters) {
        Object userIdObj = parameters.get("userId");
        String category = (String) parameters.get("category");
        
        try {
            Future<List<ScoreHistory>> future;
            if (category != null) {
                future = databaseManager.getScoreHistoryByCategory(category);
            } else if (userIdObj != null) {
                long userId;
                if (userIdObj instanceof Number) {
                    userId = ((Number) userIdObj).longValue();
                } else {
                    userId = Long.parseLong(userIdObj.toString());
                }
                future = databaseManager.getScoreHistory(userId);
            } else {
                return new AIToolResult("缺少参数: userId 或 category", parameters);
            }
            
            List<ScoreHistory> scores = future.get(10, TimeUnit.SECONDS);
            List<Map<String, Object>> scoreList = new ArrayList<>();
            
            for (ScoreHistory score : scores) {
                Map<String, Object> scoreMap = new HashMap<>();
                scoreMap.put("id", score.getId());
                scoreMap.put("userId", score.getUserId());
                scoreMap.put("category", score.getCategory());
                scoreMap.put("difficulty", score.getDifficulty());
                scoreMap.put("quizType", score.getQuizType());
                scoreMap.put("score", score.getScore());
                scoreMap.put("totalQuestions", score.getTotalQuestions());
                scoreMap.put("correctCount", score.getCorrectCount());
                scoreMap.put("startTime", score.getStartTime());
                scoreMap.put("endTime", score.getEndTime());
                scoreList.add(scoreMap);
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("count", scoreList.size());
            result.put("scores", scoreList);
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("获取分数记录失败: " + e.getMessage(), parameters);
        }
    }
    
    @SuppressWarnings("unchecked")
    private AIToolResult addScore(Map<String, Object> parameters) {
        try {
            ScoreHistory score = new ScoreHistory();
            if (parameters.containsKey("userId")) {
                Object userIdObj = parameters.get("userId");
                if (userIdObj instanceof Number) {
                    score.setUserId(((Number) userIdObj).longValue());
                }
            }
            if (parameters.containsKey("category")) score.setCategory((String) parameters.get("category"));
            if (parameters.containsKey("difficulty")) score.setDifficulty((String) parameters.get("difficulty"));
            if (parameters.containsKey("quizType")) score.setQuizType((String) parameters.get("quizType"));
            if (parameters.containsKey("score")) {
                Object s = parameters.get("score");
                if (s instanceof Number) score.setScore(((Number) s).intValue());
            }
            if (parameters.containsKey("totalQuestions")) {
                Object t = parameters.get("totalQuestions");
                if (t instanceof Number) score.setTotalQuestions(((Number) t).intValue());
            }
            if (parameters.containsKey("correctCount")) {
                Object c = parameters.get("correctCount");
                if (c instanceof Number) score.setCorrectCount(((Number) c).intValue());
            }
            if (parameters.containsKey("startTime")) {
                Object t = parameters.get("startTime");
                if (t instanceof Number) score.setStartTime(((Number) t).longValue());
            }
            if (parameters.containsKey("endTime")) {
                Object t = parameters.get("endTime");
                if (t instanceof Number) score.setEndTime(((Number) t).longValue());
            }
            
            Future<Long> future = databaseManager.addScore(score);
            long scoreId = future.get(10, TimeUnit.SECONDS);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", scoreId > 0 ? "success" : "failed");
            result.put("scoreId", scoreId);
            result.put("message", scoreId > 0 ? "分数记录添加成功" : "分数记录添加失败");
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("添加分数记录失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult getAverageScore(Map<String, Object> parameters) {
        Object userIdObj = parameters.get("userId");
        if (userIdObj == null) {
            return new AIToolResult("缺少参数: userId", parameters);
        }
        
        try {
            long userId;
            if (userIdObj instanceof Number) {
                userId = ((Number) userIdObj).longValue();
            } else {
                userId = Long.parseLong(userIdObj.toString());
            }
            
            Future<Float> future = databaseManager.getAverageScore(userId);
            float avgScore = future.get();
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("userId", userId);
            result.put("averageScore", avgScore);
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("获取平均分失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult getDatabaseVersion(Map<String, Object> parameters) {
        try {
            int version = databaseManager.getDatabaseVersion();
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("version", version);
            result.put("message", "当前数据库版本: " + version);
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("获取数据库版本失败: " + e.getMessage(), parameters);
        }
    }
    
    private List<Map<String, Object>> convertQuestionsToMap(List<Question> questions) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Question q : questions) {
            list.add(convertQuestionToMap(q));
        }
        return list;
    }
    
    private Map<String, Object> convertQuestionToMap(Question question) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", question.getId());
        map.put("questionText", question.getQuestionText());
        map.put("optionA", question.getOptionA());
        map.put("optionB", question.getOptionB());
        map.put("optionC", question.getOptionC());
        map.put("optionD", question.getOptionD());
        map.put("options", question.getOptions());
        map.put("correctAnswer", question.getCorrectAnswer());
        map.put("explanation", question.getExplanation());
        map.put("category", question.getCategory());
        map.put("questionType", question.getQuestionType());
        map.put("difficulty", question.getDifficulty());
        map.put("createdAt", question.getCreatedAt());
        map.put("updatedAt", question.getUpdatedAt());
        map.put("source", question.getSource());
        map.put("tags", question.getTags());
        map.put("points", question.getPoints());
        map.put("timeLimit", question.getTimeLimit());
        map.put("hint", question.getHint());
        map.put("analysis", question.getAnalysis());
        map.put("knowledgePoint", question.getKnowledgePoint());
        map.put("subCategory", question.getSubCategory());
        return map;
    }
    
    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("action", "操作类型: execute_query, get_questions, search_questions, get_question_count, get_question_statistics, get_all_categories, get_all_question_types, get_question_by_id, add_questions, update_question, delete_question, clear_all_questions, get_user, add_user, get_score_history, add_score, get_average_score, get_database_version");
        descriptions.put("query", "SQL查询语句（用于execute_query操作）");
        descriptions.put("keyword", "搜索关键词（用于search_questions操作）");
        descriptions.put("page", "页码（用于get_questions操作）");
        descriptions.put("page_size", "每页数量（用于get_questions操作）");
        descriptions.put("category", "分类（用于get_questions和search_questions操作）");
        descriptions.put("type", "题目类型（用于get_questions和search_questions操作）");
        descriptions.put("difficulty", "难度: 1-简单, 2-中等, 3-困难（用于get_questions和search_questions操作）");
        descriptions.put("id", "题目ID（用于get_question_by_id, update_question, delete_question操作）");
        descriptions.put("questions", "题目列表（用于add_questions操作，格式：[{questionText, optionA, optionB, optionC, optionD, correctAnswer, explanation, category, questionType, difficulty}]）");
        descriptions.put("username", "用户名（用于get_user和add_user操作）");
        descriptions.put("userId", "用户ID（用于get_score_history和get_average_score操作）");
        descriptions.put("questionText", "题目文本（用于update_question操作）");
        descriptions.put("optionA", "选项A（用于update_question操作）");
        descriptions.put("optionB", "选项B（用于update_question操作）");
        descriptions.put("optionC", "选项C（用于update_question操作）");
        descriptions.put("optionD", "选项D（用于update_question操作）");
        descriptions.put("correctAnswer", "正确答案（用于update_question操作）");
        descriptions.put("explanation", "解析（用于update_question操作）");
        return descriptions;
    }
}

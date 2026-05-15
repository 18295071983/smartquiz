package com.oilquiz.app.webview.js;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oilquiz.app.database.AppDatabase;
import com.oilquiz.app.database.DatabaseManager;
import com.oilquiz.app.model.Question;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JavaScript 数据库操作接口
 */
public class JSDatabaseInterface {
    private static final String TAG = "JSDatabaseInterface";
    private final Context context;
    private final DatabaseManager dbManager;
    private final Handler mainHandler;
    private final ExecutorService executor;
    private final Gson gson;

    public JSDatabaseInterface(Context context) {
        this.context = context;
        this.dbManager = DatabaseManager.getInstance(context);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newSingleThreadExecutor();
        this.gson = new Gson();
    }

    /**
     * 搜索题目
     */
    @JavascriptInterface
    public void searchQuestions(String jsonParams, String callback) {
        executor.execute(() -> {
            try {
                String keyword = "";
                int limit = 50;
                String category = null;
                Integer difficulty = null;

                if (jsonParams != null && !jsonParams.isEmpty()) {
                    JSONObject params = new JSONObject(jsonParams);
                    keyword = params.optString("keyword", "");
                    limit = params.optInt("limit", 50);
                    category = params.optString("category", null);
                    if (category != null && category.isEmpty()) category = null;
                    if (params.has("difficulty")) {
                        difficulty = params.getInt("difficulty");
                    }
                }

                List<Question> results = dbManager.searchQuestions(keyword).get();
                
                // 应用过滤
                if (category != null || difficulty != null) {
                    List<Question> filtered = new ArrayList<>();
                    for (Question q : results) {
                        boolean match = true;
                        if (category != null && !category.equals(q.getCategory())) {
                            match = false;
                        }
                        if (difficulty != null && q.getDifficulty() != difficulty) {
                            match = false;
                        }
                        if (match) filtered.add(q);
                    }
                    results = filtered;
                }

                // 限制数量
                List<Question> finalResults;
                if (results.size() > limit) {
                    finalResults = results.subList(0, limit);
                } else {
                    finalResults = results;
                }

                String json = convertQuestionsToJson(finalResults);
                final String finalJson = json;
                
                mainHandler.post(() -> {
                    String js = String.format("%s(%s)", callback, finalJson);
                    // WebView 会通过 evaluateJavascript 执行
                    Log.d(TAG, "搜索完成: " + finalResults.size() + " 条结果");
                });

            } catch (Exception e) {
                Log.e(TAG, "搜索失败", e);
                mainHandler.post(() -> {
                    Log.d(TAG, "搜索失败: " + e.getMessage());
                });
            }
        });
    }

    /**
     * 获取随机题目
     */
    @JavascriptInterface
    public void getRandomQuestions(int count, String callback) {
        executor.execute(() -> {
            try {
                List<Question> allQuestions = dbManager.getAllQuestions().get();
                List<Question> randomQuestions = new ArrayList<>();
                
                java.util.Collections.shuffle(allQuestions);
                int actualCount = Math.min(count, allQuestions.size());
                final List<Question> finalRandomQuestions = allQuestions.subList(0, actualCount);

                String json = convertQuestionsToJson(finalRandomQuestions);
                
                mainHandler.post(() -> {
                    Log.d(TAG, "获取随机题目: " + finalRandomQuestions.size() + " 条");
                });

            } catch (Exception e) {
                Log.e(TAG, "获取随机题目失败", e);
            }
        });
    }

    /**
     * 获取题目详情
     */
    @JavascriptInterface
    public String getQuestionById(long id) {
        try {
            Question question = dbManager.getQuestionById(id).get();
            if (question != null) {
                return convertQuestionToJson(question).toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "获取题目详情失败: " + id, e);
        }
        return "{}";
    }

    /**
     * 获取题目统计
     */
    @JavascriptInterface
    public String getStatistics() {
        try {
            DatabaseManager.QuestionStatistics stats = dbManager.getQuestionStatistics().get();
            JSONObject result = new JSONObject();
            result.put("totalQuestions", stats.totalQuestions);
            result.put("easyQuestions", stats.easyQuestions);
            result.put("mediumQuestions", stats.mediumQuestions);
            result.put("hardQuestions", stats.hardQuestions);
            result.put("categories", stats.categories);
            result.put("questionTypes", stats.questionTypes);
            return result.toString();
        } catch (Exception e) {
            Log.e(TAG, "获取统计失败", e);
            return "{}";
        }
    }

    /**
     * 获取所有分类
     */
    @JavascriptInterface
    public String getCategories() {
        try {
            List<String> categories = dbManager.getAllCategories().get();
            JSONArray arr = new JSONArray();
            for (String cat : categories) {
                arr.put(cat);
            }
            return arr.toString();
        } catch (Exception e) {
            Log.e(TAG, "获取分类失败", e);
            return "[]";
        }
    }

    /**
     * 获取所有题型
     */
    @JavascriptInterface
    public String getQuestionTypes() {
        try {
            List<String> types = dbManager.getAllQuestionTypes().get();
            JSONArray arr = new JSONArray();
            for (String type : types) {
                arr.put(type);
            }
            return arr.toString();
        } catch (Exception e) {
            Log.e(TAG, "获取题型失败", e);
            return "[]";
        }
    }

    /**
     * 获取错题
     */
    @JavascriptInterface
    public String getWrongQuestions() {
        try {
            // 暂时返回空列表，因为 DatabaseManager 中没有 getAllWrongQuestions 方法
            return "[]";
        } catch (Exception e) {
            Log.e(TAG, "获取错题失败", e);
            return "[]";
        }
    }

    /**
     * 获取收藏
     */
    @JavascriptInterface
    public String getFavoriteQuestions() {
        try {
            // 暂时返回空列表，因为 DatabaseManager 中没有 getFavoriteQuestions 方法
            return "[]";
        } catch (Exception e) {
            Log.e(TAG, "获取收藏失败", e);
            return "[]";
        }
    }

    /**
     * 添加收藏
     */
    @JavascriptInterface
    public boolean addFavorite(long questionId) {
        try {
            // 暂时返回 false，因为 DatabaseManager 中没有 addToFavorites 方法
            return false;
        } catch (Exception e) {
            Log.e(TAG, "添加收藏失败", e);
            return false;
        }
    }

    /**
     * 移除收藏
     */
    @JavascriptInterface
    public boolean removeFavorite(long questionId) {
        try {
            // 暂时返回 false，因为 DatabaseManager 中没有 removeFromFavorites 方法
            return false;
        } catch (Exception e) {
            Log.e(TAG, "移除收藏失败", e);
            return false;
        }
    }

    // ==================== 辅助方法 ====================

    private String convertQuestionsToJson(List<Question> questions) {
        JSONArray arr = new JSONArray();
        for (Question q : questions) {
            try {
                arr.put(convertQuestionToJson(q));
            } catch (Exception e) {
                Log.w(TAG, "转换题目失败", e);
            }
        }
        return arr.toString();
    }

    private JSONObject convertQuestionToJson(Question q) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", q.getId());
            obj.put("questionText", q.getQuestionText());
            obj.put("answer", q.getCorrectAnswer());
            obj.put("analysis", q.getExplanation());
            obj.put("category", q.getCategory());
            obj.put("difficulty", q.getDifficulty());
            obj.put("questionType", q.getQuestionType());
            obj.put("options", q.getOptions() != null ? q.getOptions() : "{}");
            obj.put("explanation", q.getExplanation());
        } catch (JSONException e) {
            Log.w(TAG, "转换题目 JSON 失败", e);
        }
        return obj;
    }

    /**
     * 清理资源
     */
    public void destroy() {
        executor.shutdown();
    }
}

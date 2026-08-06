package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.database.Cursor;
import androidx.sqlite.db.SupportSQLiteDatabase;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.database.AppDatabase;
import com.oilquiz.app.database.DatabaseManager;
import com.oilquiz.app.model.Question;
import com.oilquiz.app.model.User;
import com.oilquiz.app.model.ScoreHistory;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.json.JSONArray;
import org.json.JSONObject;

@Tool(
    value = "database",
    description = "数据库操作工具。支持：执行任意SQL查询(execute_sql)、列出所有表(list_tables)、"
        + "查看表结构(get_table_schema)、题目管理、用户管理、分数记录等。"
        + "execute_sql可执行任意SELECT/INSERT语句。"
        + "【重要】add_questions每次最多传10道题，超过10道必须分批调用。"
        + "大批量导入建议用execute_sql执行INSERT语句，每次INSERT 20-30条。",
    category = "data",
    actions = {
        @Action(name = "execute_sql", description = "执行任意SQL(SELECT/INSERT/UPDATE/DELETE，支持多语句分号分隔)"),
        @Action(name = "list_tables", description = "列出数据库所有表"),
        @Action(name = "get_table_schema", description = "获取指定表的字段结构"),
        @Action(name = "execute_query", description = "执行SQL查询(兼容旧接口)"),
        @Action(name = "get_questions", description = "获取题目列表"),
        @Action(name = "search_questions", description = "搜索题目"),
        @Action(name = "get_question_count", description = "获取题目数量"),
        @Action(name = "get_question_statistics", description = "获取题目统计信息"),
        @Action(name = "get_question_by_id", description = "根据ID获取题目"),
        @Action(name = "add_questions", description = "添加题目(每次最多10道，超过须分批调用)"),
        @Action(name = "update_question", description = "更新题目"),
        @Action(name = "delete_question", description = "删除题目"),
        @Action(name = "get_user", description = "获取用户信息"),
        @Action(name = "add_user", description = "添加用户"),
        @Action(name = "get_score_history", description = "获取分数历史"),
        @Action(name = "add_score", description = "添加分数记录"),
        @Action(name = "get_average_score", description = "获取平均分")
    },
    params = {
        @Param(name = "action", type = "string", description = "操作类型", required = true),
        @Param(name = "query", type = "string", description = "SQL查询语句", required = false),
        @Param(name = "sql", type = "string", description = "SQL语句(用于execute_sql)", required = false),
        @Param(name = "table_name", type = "string", description = "表名(用于get_table_schema)", required = false),
        @Param(name = "keyword", type = "string", description = "搜索关键词", required = false),
        @Param(name = "id", type = "string", description = "题目/用户ID", required = false),
        @Param(name = "category", type = "string", description = "题目分类", required = false),
        @Param(name = "type", type = "string", description = "题目类型", required = false),
        @Param(name = "difficulty", type = "int", description = "难度: 1-简单, 2-中等, 3-困难", required = false),
        @Param(name = "page", type = "int", description = "页码", required = false),
        @Param(name = "page_size", type = "int", description = "每页数量", required = false)
    }
)
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
    public String getDescription() { return "数据库操作工具。支持任意SQL查询、列出表、查看表结构、题目管理、用户管理、分数记录等"; }
    
    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            Object actionObj = parameters.get("action");
            if (actionObj == null) {
                return new AIToolResult("缺少参数: action", parameters);
            }
            String action = actionObj.toString();
            
            switch (action) {
                case "execute_sql":
                    return executeSql(parameters);
                case "list_tables":
                    return listTables(parameters);
                case "get_table_schema":
                    return getTableSchema(parameters);
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
    
    /**
     * 执行任意SQL查询（SELECT/PRAGMA/INSERT/UPDATE/DELETE）
     */
    private AIToolResult executeSql(Map<String, Object> parameters) {
        Object sqlObj = parameters.get("sql");
        if (sqlObj == null) sqlObj = parameters.get("query");
        if (sqlObj == null) {
            return new AIToolResult("缺少参数: sql", parameters);
        }
        String sql = sqlObj.toString().trim();
        String sqlUpper = sql.toUpperCase();
        boolean isWrite = sqlUpper.startsWith("INSERT") || sqlUpper.startsWith("UPDATE") 
            || sqlUpper.startsWith("DELETE") || sqlUpper.startsWith("CREATE") 
            || sqlUpper.startsWith("DROP") || sqlUpper.startsWith("ALTER");
        
        try {
            AppDatabase db = AppDatabase.getDatabase(context);
            
            if (isWrite) {
                // 写操作：INSERT/UPDATE/DELETE等
                SupportSQLiteDatabase sqlite = db.getOpenHelper().getWritableDatabase();
                sqlite.beginTransaction();
                try {
                    // 支持多条SQL语句（用分号分隔）
                    String[] statements = sql.split(";");
                    int totalChanges = 0;
                    for (String stmt : statements) {
                        stmt = stmt.trim();
                        if (!stmt.isEmpty()) {
                            sqlite.execSQL(stmt);
                            totalChanges++;
                        }
                    }
                    sqlite.setTransactionSuccessful();
                    
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", "success");
                    result.put("sql", sql.length() > 200 ? sql.substring(0, 200) + "..." : sql);
                    result.put("statements_executed", totalChanges);
                    
                    // 查询受影响的行数
                    Cursor countCursor = sqlite.query("SELECT changes() AS affected_rows");
                    if (countCursor.moveToFirst()) {
                        result.put("affected_rows", countCursor.getInt(0));
                    }
                    countCursor.close();
                    
                    return new AIToolResult(result, parameters);
                } finally {
                    sqlite.endTransaction();
                }
            } else {
                // 读操作：SELECT/PRAGMA等
                SupportSQLiteDatabase sqlite = db.getOpenHelper().getReadableDatabase();
                Cursor cursor = sqlite.query(sql);
                
                List<Map<String, Object>> rows = new ArrayList<>();
                List<String> columns = new ArrayList<>();
                
                // 获取列名
                for (int i = 0; i < cursor.getColumnCount(); i++) {
                    columns.add(cursor.getColumnName(i));
                }
                
                // 读取数据行
                while (cursor.moveToNext()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 0; i < cursor.getColumnCount(); i++) {
                        String colName = columns.get(i);
                        switch (cursor.getType(i)) {
                            case Cursor.FIELD_TYPE_NULL:
                                row.put(colName, null);
                                break;
                            case Cursor.FIELD_TYPE_INTEGER:
                                row.put(colName, cursor.getLong(i));
                                break;
                            case Cursor.FIELD_TYPE_FLOAT:
                                row.put(colName, cursor.getDouble(i));
                                break;
                            case Cursor.FIELD_TYPE_STRING:
                                row.put(colName, cursor.getString(i));
                                break;
                            case Cursor.FIELD_TYPE_BLOB:
                                row.put(colName, "<blob>");
                                break;
                        }
                    }
                    rows.add(row);
                }
                cursor.close();
                
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "success");
                result.put("sql", sql);
                result.put("columns", columns);
                result.put("rows", rows);
                result.put("count", rows.size());
                
                return new AIToolResult(result, parameters);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "SQL执行失败: " + e.getMessage(), e);
            return new AIToolResult("SQL执行失败: " + e.getMessage(), parameters);
        }
    }
    
    /**
     * 列出数据库所有表
     */
    private AIToolResult listTables(Map<String, Object> parameters) {
        try {
            AppDatabase db = AppDatabase.getDatabase(context);
            SupportSQLiteDatabase sqlite = db.getOpenHelper().getReadableDatabase();
            Cursor cursor = sqlite.query(
                "SELECT name, sql FROM sqlite_master WHERE type='table' " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' " +
                "ORDER BY name");
            
            List<Map<String, String>> tables = new ArrayList<>();
            while (cursor.moveToNext()) {
                Map<String, String> table = new LinkedHashMap<>();
                table.put("name", cursor.getString(0));
                table.put("sql", cursor.getString(1));
                tables.add(table);
            }
            cursor.close();
            
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("count", tables.size());
            result.put("tables", tables);
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("获取表列表失败: " + e.getMessage(), parameters);
        }
    }
    
    /**
     * 获取指定表的结构信息
     */
    private AIToolResult getTableSchema(Map<String, Object> parameters) {
        Object tableNameObj = parameters.get("table_name");
        if (tableNameObj == null) {
            return new AIToolResult("缺少参数: table_name", parameters);
        }
        String tableName = tableNameObj.toString();
        
        try {
            AppDatabase db = AppDatabase.getDatabase(context);
            SupportSQLiteDatabase sqlite = db.getOpenHelper().getReadableDatabase();
            
            // 获取表结构
            Cursor cursor = sqlite.query("PRAGMA table_info(" + tableName + ")");
            List<Map<String, Object>> columns = new ArrayList<>();
            while (cursor.moveToNext()) {
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("cid", cursor.getInt(0));
                col.put("name", cursor.getString(1));
                col.put("type", cursor.getString(2));
                col.put("notnull", cursor.getInt(3) != 0);
                col.put("default_value", cursor.isNull(4) ? null : cursor.getString(4));
                col.put("pk", cursor.getInt(5) != 0);
                columns.add(col);
            }
            cursor.close();
            
            // 获取行数
            long rowCount = 0;
            try {
                Cursor countCursor = sqlite.query("SELECT COUNT(*) FROM " + tableName);
                if (countCursor.moveToNext()) {
                    rowCount = countCursor.getLong(0);
                }
                countCursor.close();
            } catch (Exception ignored) {}
            
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "success");
            result.put("table_name", tableName);
            result.put("row_count", rowCount);
            result.put("columns", columns);
            result.put("column_count", columns.size());
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("获取表结构失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult executeQuery(Map<String, Object> parameters) {
        Object queryObj = parameters.get("query");
        if (queryObj == null) {
            return new AIToolResult("缺少参数: query", parameters);
        }
        String query = queryObj.toString().trim();
        
        try {
            // 尝试匹配特定模式，否则直接执行SQL
            String queryLower = query.toLowerCase();
            if (queryLower.contains("select") && queryLower.contains("question") 
                && !queryLower.contains("join") && !queryLower.contains("where") 
                && !queryLower.contains("group") && !queryLower.contains("order")) {
                return getQuestions(parameters);
            } else if (queryLower.contains("count") && queryLower.contains("question")) {
                return getQuestionCount(parameters);
            } else {
                // 直接执行任意SQL
                return executeSql(parameters);
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
        if (questionsObj == null) {
            return new AIToolResult("缺少参数: questions (应为题目列表或JSON数组)", parameters);
        }
        
        try {
            List<Map<String, Object>> questionMaps;
            
            if (questionsObj instanceof List) {
                questionMaps = (List<Map<String, Object>>) questionsObj;
            } else if (questionsObj instanceof String) {
                // Agent 通过 API 传来的 questions 是 JSON 字符串
                String jsonStr = ((String) questionsObj).trim();
                questionMaps = parseQuestionsJson(jsonStr);
                if (questionMaps == null) {
                    return new AIToolResult("questions 参数 JSON 解析失败，请检查格式", parameters);
                }
            } else {
                return new AIToolResult("questions 参数类型不支持: " + questionsObj.getClass().getSimpleName(), parameters);
            }
            
            if (questionMaps.isEmpty()) {
                return new AIToolResult("questions 列表为空", parameters);
            }
            
            List<Question> questions = new ArrayList<>();
            
            for (Map<String, Object> qm : questionMaps) {
                Question q = new Question();
                // 支持多种字段名格式
                q.setQuestionText(getStr(qm, "questionText", "question_text", "question"));
                q.setOptionA(getStr(qm, "optionA", "option_a", "A"));
                q.setOptionB(getStr(qm, "optionB", "option_b", "B"));
                q.setOptionC(getStr(qm, "optionC", "option_c", "C"));
                q.setOptionD(getStr(qm, "optionD", "option_d", "D"));
                q.setOptionE(getStr(qm, "optionE", "option_e", "E"));
                q.setOptionF(getStr(qm, "optionF", "option_f", "F"));
                q.setOptionG(getStr(qm, "optionG", "option_g", "G"));
                q.setOptionH(getStr(qm, "optionH", "option_h", "H"));
                q.setOptionI(getStr(qm, "optionI", "option_i", "I"));
                q.setOptionJ(getStr(qm, "optionJ", "option_j", "J"));
                q.setOptionK(getStr(qm, "optionK", "option_k", "K"));
                q.setOptionL(getStr(qm, "optionL", "option_l", "L"));
                q.setCorrectAnswer(getStr(qm, "correctAnswer", "correct_answer", "answer"));
                q.setExplanation(getStr(qm, "explanation", "解析"));
                q.setCategory(getStr(qm, "category", "分类"));
                q.setQuestionType(getStr(qm, "questionType", "question_type", "type"));
                // v21 新增字段
                q.setAnswerText(getStr(qm, "answerText", "answer_text", "standard_answer"));
                q.setImageUri(getStr(qm, "imageUri", "image_uri", "image"));
                q.setAudioUri(getStr(qm, "audioUri", "audio_uri", "audio"));
                q.setSource(getStr(qm, "source", "来源"));
                q.setTags(getStr(qm, "tags", "标签"));
                q.setAnalysis(getStr(qm, "analysis", "详细解析"));
                q.setKnowledgePoint(getStr(qm, "knowledgePoint", "knowledge_point", "知识点"));
                q.setSubCategory(getStr(qm, "subCategory", "sub_category", "子分类"));
                q.setHint(getStr(qm, "hint", "提示"));
                q.setAuthor(getStr(qm, "author", "作者"));
                q.setComment(getStr(qm, "comment", "备注"));
                
                Object diff = qm.get("difficulty");
                if (diff instanceof Number) {
                    q.setDifficulty(((Number) diff).intValue());
                } else if (diff instanceof String) {
                    try { q.setDifficulty(Integer.parseInt((String) diff)); } catch (Exception ignored) {}
                }
                
                Object pts = qm.get("points");
                if (pts instanceof Number) {
                    q.setPoints(((Number) pts).intValue());
                } else if (pts instanceof String) {
                    try { q.setPoints(Integer.parseInt((String) pts)); } catch (Exception ignored) {}
                }
                
                Object tl = qm.get("timeLimit");
                if (tl instanceof Number) {
                    q.setTimeLimit(((Number) tl).intValue());
                } else if (tl instanceof String) {
                    try { q.setTimeLimit(Integer.parseInt((String) tl)); } catch (Exception ignored) {}
                }
                
                Object so = qm.get("sortOrder");
                if (so instanceof Number) {
                    q.setSortOrder(((Number) so).intValue());
                } else if (so instanceof String) {
                    try { q.setSortOrder(Integer.parseInt((String) so)); } catch (Exception ignored) {}
                }
                
                Object pid = qm.get("parentId");
                if (pid instanceof Number) {
                    q.setParentId(((Number) pid).longValue());
                } else if (pid instanceof String) {
                    try { q.setParentId(Long.parseLong((String) pid)); } catch (Exception ignored) {}
                }
                
                if (q.getQuestionText() != null && !q.getQuestionText().trim().isEmpty()) {
                    questions.add(q);
                }
            }
            
            if (questions.isEmpty()) {
                return new AIToolResult("没有有效题目（题目内容为空）", parameters);
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
    
    /** 从 Map 中按多个可能的键名获取字符串值 */
    private String getStr(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object val = map.get(key);
            if (val != null && !val.toString().isEmpty()) return val.toString();
        }
        return null;
    }
    
    /** 解析 JSON 数组字符串为 List<Map> */
    private List<Map<String, Object>> parseQuestionsJson(String jsonStr) {
        try {
            JSONArray arr = new JSONArray(jsonStr);
            List<Map<String, Object>> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                Map<String, Object> map = new HashMap<>();
                JSONArray names = obj.names();
                if (names != null) {
                    for (int j = 0; j < names.length(); j++) {
                        String key = names.getString(j);
                        map.put(key, obj.get(key));
                    }
                }
                list.add(map);
            }
            return list;
        } catch (Exception e) {
            AILogger.e(TAG, "JSON解析失败: " + e.getMessage(), e);
            return null;
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
            float avgScore = future.get(10, TimeUnit.SECONDS);
            
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

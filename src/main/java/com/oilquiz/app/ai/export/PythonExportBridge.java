package com.oilquiz.app.ai.export;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.chaquo.python.Python;
import com.chaquo.python.PyObject;
import com.chaquo.python.android.AndroidPlatform;
import com.oilquiz.app.model.Question;

import org.json.JSONObject;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Python 导出桥接器
 * 通过 Chaquopy 调用 Python 导出模块，避免使用 Apache POI/iText 等不兼容 Android 的库
 */
public class PythonExportBridge {
    private static final String TAG = "PythonExportBridge";
    private static volatile PythonExportBridge instance;
    
    private Python python;
    private PyObject exportModule;
    private boolean initialized = false;
    
    private PythonExportBridge() {}
    
    public static PythonExportBridge getInstance() {
        if (instance == null) {
            synchronized (PythonExportBridge.class) {
                if (instance == null) {
                    instance = new PythonExportBridge();
                }
            }
        }
        return instance;
    }
    
    /**
     * 初始化 Python 导出模块
     */
    public synchronized boolean initialize(Context context) {
        if (initialized) {
            return true;
        }
        
        try {
            if (!Python.isStarted()) {
                Log.i(TAG, "Starting Python interpreter for export...");
                Python.start(new AndroidPlatform(context));
            }
            
            python = Python.getInstance();
            
            // 导入导出模块
            exportModule = python.getModule("export");
            if (exportModule == null) {
                Log.e(TAG, "Failed to load export module");
                return false;
            }
            
            // 检测 openpyxl 是否可用
            Log.i(TAG, "Checking openpyxl availability during initialization...");
            try {
                PyObject checkResult = exportModule.callAttr("_test_openpyxl");
                if (checkResult != null) {
                    boolean openpyxlOk = checkResult.toBoolean();
                    if (openpyxlOk) {
                        Log.i(TAG, "openpyxl is available - Excel export should work");
                    } else {
                        Log.e(TAG, "WARNING: openpyxl is NOT available! Excel export will likely fail.");
                        Log.e(TAG, "This means openpyxl was not installed by Chaquopy. Try: rebuild APK");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to check openpyxl: " + e.getMessage(), e);
            }
            
            initialized = true;
            Log.i(TAG, "Python export bridge initialized successfully");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize Python export bridge: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * 获取 exportModule，如果为 null 则返回 null 并记录日志
     */
    private PyObject getExportModule(String method) {
        if (exportModule == null) {
            Log.e(TAG, "exportModule is null in method: " + method);
            return null;
        }
        return exportModule;
    }
    
    /**
     * 调用 exportModule 属性，如果为 null 则初始化
     */
    private PyObject getOrInitModule(String method, Context context) {
        if (exportModule == null) {
            if (!initialize(context)) {
                Log.e(TAG, "Failed to initialize Python in " + method);
                return null;
            }
        }
        return exportModule;
    }
    
    /**
     * 导出为 Excel 格式
     */
    public File exportToExcel(File outputFile, List<Question> questions, 
                               java.util.Map<String, Object> config, Context context) {
        if (!initialized && !initialize(context)) {
            Log.e(TAG, "Python export bridge not initialized");
            return null;
        }
        
        PyObject module = getExportModule("exportToExcel");
        if (module == null) {
            return null;
        }
        
        try {
            // 先测试 openpyxl 是否可用
            Log.i(TAG, "Testing openpyxl availability...");
            PyObject testResult = module.callAttr("_test_openpyxl");
            if (testResult != null) {
                boolean testOk = testResult.toBoolean();
                Log.i(TAG, "openpyxl test result: " + testOk);
                if (!testOk) {
                    Log.e(TAG, "openpyxl test failed, will try anyway");
                }
            }
            
            // 转换问题列表为 JSON 字符串
            String jsonQuestions = convertQuestionsToJson(questions);
            
            // 转换配置为 JSONObject，然后转 JSON 字符串
            JSONObject jsonConfig = new JSONObject(config);
            String jsonString = jsonConfig.toString();
            
            Log.i(TAG, "Calling Python export_to_excel, questions: " + questions.size());
            
            // 调用 Python 导出函数 - 传递 JSON 字符串，让 Python 解析
            PyObject result = module.callAttr(
                "export_to_excel",
                outputFile.getAbsolutePath(),
                jsonQuestions,  // JSON 字符串
                jsonString      // JSON 字符串
            );
            
            Log.i(TAG, "Python export_to_excel returned result: " + result);
            
            return parseExportResult(result, outputFile);
            
        } catch (Exception e) {
            Log.e(TAG, "Excel export exception: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 导出为 CSV 格式
     */
    public File exportToCsv(File outputFile, List<Question> questions,
                             java.util.Map<String, Object> config, Context context) {
        if (!initialized && !initialize(context)) {
            return null;
        }
        
        PyObject module = getExportModule("exportToCsv");
        if (module == null) {
            return null;
        }
        
        try {
            String jsonQuestions = convertQuestionsToJson(questions);
            JSONObject jsonConfig = new JSONObject(config);
            String jsonString = jsonConfig.toString();
            
            PyObject result = module.callAttr(
                "export_to_csv",
                outputFile.getAbsolutePath(),
                jsonQuestions,
                jsonString
            );
            
            return parseExportResult(result, outputFile);
            
        } catch (Exception e) {
            Log.e(TAG, "CSV export failed: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 导出为 Markdown 格式
     */
    public File exportToMarkdown(File outputFile, List<Question> questions,
                                  java.util.Map<String, Object> config, Context context) {
        if (!initialized && !initialize(context)) {
            return null;
        }
        
        PyObject module = getExportModule("exportToMarkdown");
        if (module == null) {
            return null;
        }
        
        try {
            String jsonQuestions = convertQuestionsToJson(questions);
            JSONObject jsonConfig = new JSONObject(config);
            String jsonString = jsonConfig.toString();
            
            PyObject result = module.callAttr(
                "export_to_markdown",
                outputFile.getAbsolutePath(),
                jsonQuestions,
                jsonString
            );
            
            return parseExportResult(result, outputFile);
            
        } catch (Exception e) {
            Log.e(TAG, "Markdown export failed: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 导出为 JSON 格式
     */
    public File exportToJson(File outputFile, List<Question> questions,
                              java.util.Map<String, Object> config, Context context) {
        if (!initialized && !initialize(context)) {
            return null;
        }
        
        PyObject module = getExportModule("exportToJson");
        if (module == null) {
            return null;
        }
        
        try {
            String jsonQuestions = convertQuestionsToJson(questions);
            JSONObject jsonConfig = new JSONObject(config);
            String jsonString = jsonConfig.toString();
            
            PyObject result = module.callAttr(
                "export_to_json",
                outputFile.getAbsolutePath(),
                jsonQuestions,
                jsonString
            );
            
            return parseExportResult(result, outputFile);
            
        } catch (Exception e) {
            Log.e(TAG, "JSON export failed: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 将题目列表转换为 JSON 字符串
     */
    private String convertQuestionsToJson(List<Question> questions) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < questions.size(); i++) {
            Question q = questions.get(i);
            if (i > 0) sb.append(",");
            sb.append(questionToJson(q));
        }
        sb.append("]");
        return sb.toString();
    }
    
    /**
     * 将单个题目转换为 JSON 字符串
     */
    private String questionToJson(Question question) {
        StringBuilder sb = new StringBuilder("{");
        
        sb.append("\"id\":").append(question.getId());
        sb.append(",\"questionType\":").append(jsonString(question.getQuestionType()));
        sb.append(",\"questionText\":").append(jsonString(question.getQuestionText()));
        sb.append(",\"optionA\":").append(jsonString(question.getOptionA()));
        sb.append(",\"optionB\":").append(jsonString(question.getOptionB()));
        sb.append(",\"optionC\":").append(jsonString(question.getOptionC()));
        sb.append(",\"optionD\":").append(jsonString(question.getOptionD()));
        sb.append(",\"optionE\":").append(jsonString(question.getOptionE()));
        sb.append(",\"optionF\":").append(jsonString(question.getOptionF()));
        sb.append(",\"correctAnswer\":").append(jsonString(question.getCorrectAnswer()));
        sb.append(",\"answerText\":").append(jsonString(question.getAnswerText()));
        sb.append(",\"explanation\":").append(jsonString(question.getExplanation()));
        sb.append(",\"analysis\":").append(jsonString(question.getAnalysis()));
        sb.append(",\"category\":").append(jsonString(question.getCategory()));
        sb.append(",\"subCategory\":").append(jsonString(question.getSubCategory()));
        sb.append(",\"difficulty\":").append(question.getDifficulty());
        sb.append(",\"knowledgePoint\":").append(jsonString(question.getKnowledgePoint()));
        sb.append(",\"tags\":").append(jsonString(question.getTags()));
        sb.append(",\"source\":").append(jsonString(question.getSource()));
        sb.append(",\"author\":").append(jsonString(question.getAuthor()));
        sb.append(",\"points\":").append(question.getPoints());
        sb.append(",\"hint\":").append(jsonString(question.getHint()));
        sb.append(",\"comment\":").append(jsonString(question.getComment()));
        sb.append(",\"relatedQuestion\":").append(jsonString(question.getRelatedQuestion()));
        sb.append(",\"favorite\":").append(question.isFavorite());
        
        sb.append("}");
        return sb.toString();
    }
    
    /**
     * 将字符串转换为 JSON 格式
     */
    private String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        // 转义 JSON 特殊字符
        String escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
        return "\"" + escaped + "\"";
    }
    
    /**
     * 导出为 PDF 格式（支持中文）
     */
    public File exportToPdf(File outputFile, List<Question> questions, Map<String, Object> config, Context context) {
        Log.i(TAG, "=== Exporting to PDF ===");
        
        PyObject module = getOrInitModule("exportToPdf", context);
        if (module == null) {
            return null;
        }
        
        Log.i(TAG, "Output file: " + outputFile.getAbsolutePath());
        Log.i(TAG, "Questions count: " + questions.size());
        
        // 测试 reportlab 可用性
        try {
            Log.i(TAG, "Testing reportlab availability...");
            PyObject testResult = module.callAttr("_test_reportlab");
            Log.i(TAG, "reportlab test result: " + testResult.toString());
        } catch (Exception e) {
            Log.w(TAG, "WARNING: reportlab not available! " + e.getMessage());
        }
        
        // 转换问题列表为 JSON 字符串
        String jsonQuestions = convertQuestionsToJson(questions);
        
        // 转换配置为 JSONObject，然后转 JSON 字符串
        JSONObject jsonConfig = new JSONObject(config);
        String jsonString = jsonConfig.toString();
        
        Log.i(TAG, "Calling Python export_to_pdf...");
        
        // 调用 Python 导出函数
        PyObject result = null;
        try {
            result = module.callAttr(
                "export_to_pdf",
                outputFile.getAbsolutePath(),
                jsonQuestions,
                jsonString
            );
        } catch (Exception pyE) {
            Log.e(TAG, "Python export_to_pdf call failed: " + pyE.getMessage(), pyE);
            return null;
        }
        
        if (result == null) {
            Log.e(TAG, "Python export_to_pdf returned null");
            return null;
        }
        
        try {
            Log.i(TAG, "Python export_to_pdf result: " + result.toString());
        } catch (Exception e) {
            Log.w(TAG, "Cannot log result: " + e.getMessage());
        }
        
        // 解析结果
        return parseExportResult(result, outputFile);
    }
    
    /**
     * 导出为长图片格式（PNG）
     */
    public File exportToLongImage(File outputFile, List<Question> questions, Map<String, Object> config, Context context) {
        Log.i(TAG, "=== Exporting to Long Image ===");
        
        PyObject module = getOrInitModule("exportToLongImage", context);
        if (module == null) {
            return null;
        }
        
        Log.i(TAG, "Output file: " + outputFile.getAbsolutePath());
        Log.i(TAG, "Questions count: " + questions.size());
        
        // 转换问题列表为 JSON 字符串
        String jsonQuestions = convertQuestionsToJson(questions);
        
        // 转换配置为 JSONObject，然后转 JSON 字符串
        JSONObject jsonConfig = new JSONObject(config);
        String jsonString = jsonConfig.toString();
        
        Log.i(TAG, "Calling Python export_to_long_image...");
        
        // 调用 Python 导出函数
        PyObject result = module.callAttr(
            "export_to_long_image",
            outputFile.getAbsolutePath(),
            jsonQuestions,
            jsonString
        );
        
        Log.i(TAG, "Python export_to_long_image result: " + result.toString());
        
        // 解析结果
        return parseExportResult(result, outputFile);
    }
    
    /**
     * 导出为 HTML 格式（学习查看版）
     */
    public File exportToHtml(File outputFile, List<Question> questions, Map<String, Object> config, Context context) {
        Log.i(TAG, "=== Exporting to HTML ===");
        
        PyObject module = getOrInitModule("exportToHtml", context);
        if (module == null) {
            return null;
        }
        
        Log.i(TAG, "Output file: " + outputFile.getAbsolutePath());
        Log.i(TAG, "Questions count: " + questions.size());
        
        // 转换问题列表为 JSON 字符串
        String jsonQuestions = convertQuestionsToJson(questions);
        
        // 转换配置为 JSONObject，然后转 JSON 字符串
        JSONObject jsonConfig = new JSONObject(config);
        String jsonString = jsonConfig.toString();
        
        Log.i(TAG, "Calling Python export_to_html...");
        
        // 调用 Python 导出函数
        PyObject result = module.callAttr(
            "export_to_html",
            outputFile.getAbsolutePath(),
            jsonQuestions,
            jsonString
        );
        
        Log.i(TAG, "Python export_to_html result: " + result.toString());
        
        // 解析结果
        return parseExportResult(result, outputFile);
    }
    
    /**
     * 解析 Python 导出结果
     */
    private File parseExportResult(PyObject result, File expectedFile) {
        if (result == null) {
            Log.e(TAG, "Export result is null");
            return null;
        }
        
        try {
            Map<PyObject, PyObject> resultMap = result.asMap();
            
            // 获取 success 字段
            PyObject successObj = resultMap.get(PyObject.fromJava("success"));
            boolean success = successObj != null && successObj.toBoolean();
            
            if (!success) {
                PyObject errorObj = resultMap.get(PyObject.fromJava("error"));
                String errorMsg = "未知错误";
                if (errorObj != null) {
                    try {
                        errorMsg = errorObj.toString();
                    } catch (Exception e) {
                        errorMsg = "Python 返回错误对象解析失败: " + e.getMessage();
                    }
                }
                Log.e(TAG, "Python export failed - Error: " + errorMsg + 
                        ", Result keys: " + resultMap.keySet());
                return null;
            }
            
            // 获取 filepath 字段
            PyObject filepathObj = resultMap.get(PyObject.fromJava("filepath"));
            if (filepathObj != null) {
                File exportFile = new File(filepathObj.toString());
                if (exportFile.exists()) {
                    Log.i(TAG, "Export successful: " + exportFile.getAbsolutePath());
                    return exportFile;
                }
            }
            
            // 如果 filepath 不存在，返回期望的文件
            return expectedFile.exists() ? expectedFile : null;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse export result: " + e.getMessage(), e);
            return null;
        }
    }
    
    // ==================== Android UI 操作方法 ====================
    
    /**
     * 显示 Toast 提示
     * 
     * @param message 提示文本
     * @param duration 显示时长（毫秒）：2000=短，3500=长
     * @return {'success': bool, 'message': str}
     */
    @SuppressWarnings("PythonIdentityBasedComparison")
    public com.chaquo.python.PyObject showToast(String message, long duration) {
        try {
            if (!initialized && !initialize(null)) {
                return null;
            }
            
            // 通过 PyObject 获取当前 Activity Context
            android.content.Context ctx = getApplicationContext();
            if (ctx != null) {
                android.widget.Toast toast = android.widget.Toast.makeText(ctx, message, 
                    duration == 3500 ? android.widget.Toast.LENGTH_LONG : android.widget.Toast.LENGTH_SHORT);
                toast.show();
            }
            
            // 返回成功结果给 Python
            return exportModule.callAttr("_ui_return_success", new Object[]{"Toast 显示成功"});
            
        } catch (Exception e) {
            Log.e(TAG, "showToast error: " + e.getMessage(), e);
            return exportModule.callAttr("_ui_return_error", new Object[]{e.getMessage()});
        }
    }
    
    /**
     * 显示对话框
     * 
     * @param title 对话框标题
     * @param message 对话框内容
     * @param dialogType 对话框类型："info" / "confirm" / "warning"
     * @return {'success': bool, 'result': str, 'message': str}
     */
    @SuppressWarnings("PythonIdentityBasedComparison")
    public com.chaquo.python.PyObject showDialog(String title, String message, String dialogType) {
        try {
            android.content.Context ctx = getApplicationContext();
            if (ctx == null) {
                return exportModule.callAttr("_ui_return_error", new Object[]{"Context 不可用"});
            }
            
            final String type = dialogType != null ? dialogType : "info";
            
            // 创建对话框
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(ctx);
            builder.setTitle(title);
            builder.setMessage(message);
            
            final com.chaquo.python.PyObject[] resultHolder = new com.chaquo.python.PyObject[1];
            
            if ("confirm".equals(type)) {
                // 确认对话框：有确定/取消按钮
                builder.setPositiveButton("确定", (dialog, which) -> {
                    resultHolder[0] = exportModule.callAttr("_ui_return_success", new Object[]{"positive"});
                });
                builder.setNegativeButton("取消", (dialog, which) -> {
                    resultHolder[0] = exportModule.callAttr("_ui_return_success", new Object[]{"negative"});
                });
            } else if ("warning".equals(type)) {
                // 警告对话框：红色样式
                builder.setPositiveButton("知道了", (dialog, which) -> {
                    resultHolder[0] = exportModule.callAttr("_ui_return_success", new Object[]{"positive"});
                });
            } else {
                // 信息对话框：只有确定按钮
                builder.setPositiveButton("确定", (dialog, which) -> {
                    resultHolder[0] = exportModule.callAttr("_ui_return_success", new Object[]{"positive"});
                });
            }
            
            // 在 UI 线程显示对话框
            new Handler(Looper.getMainLooper()).post(() -> builder.show());
            
            // 等待对话框结果（最多 30 秒）
            long startTime = System.currentTimeMillis();
            while (resultHolder[0] == null && (System.currentTimeMillis() - startTime) < 30000) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            
            // 超时返回
            if (resultHolder[0] == null) {
                return exportModule.callAttr("_ui_return_success", new Object[]{"timeout"});
            }
            
            return resultHolder[0];
            
        } catch (Exception e) {
            Log.e(TAG, "showDialog error: " + e.getMessage(), e);
            return exportModule.callAttr("_ui_return_error", new Object[]{e.getMessage()});
        }
    }
    
    /**
     * 更新进度
     * 
     * @param progress 进度百分比（0-100）
     * @param message 进度消息
     * @return {'success': bool, 'progress': int, 'message': str}
     */
    @SuppressWarnings("PythonIdentityBasedComparison")
    public com.chaquo.python.PyObject updateProgress(int progress, String message) {
        try {
            // 通过 Python 回调通知 Java 层
            if (!initialized) {
                return null;
            }
            
            // 这里可以通过 PyObject 调用 Java 的回调函数
            // 暂时返回成功
            return exportModule.callAttr("_ui_return_success", new Object[]{
                "进度已更新: " + progress + "%"
            });
            
        } catch (Exception e) {
            Log.e(TAG, "updateProgress error: " + e.getMessage(), e);
            return exportModule.callAttr("_ui_return_error", new Object[]{e.getMessage()});
        }
    }
    
    /**
     * 通知 Java 层事件
     * 
     * @param eventType 事件类型："export_start" / "export_complete" / "error"
     * @param data 附加数据（JSON 字符串）
     * @return {'success': bool, 'message': str}
     */
    @SuppressWarnings("PythonIdentityBasedComparison")
    public com.chaquo.python.PyObject notifyJavaEvent(String eventType, String data) {
        try {
            Log.i(TAG, "Java event notified: " + eventType + " | Data: " + data);
            
            // 这里可以通过回调接口通知 ExportManager
            // 暂时只记录日志
            return exportModule.callAttr("_ui_return_success", new Object[]{
                "事件已通知: " + eventType
            });
            
        } catch (Exception e) {
            Log.e(TAG, "notifyJavaEvent error: " + e.getMessage(), e);
            return exportModule.callAttr("_ui_return_error", new Object[]{e.getMessage()});
        }
    }
    
    /**
     * 获取当前 Activity 的 Context
     * @deprecated 此方法不再使用，PythonExportBridge 由 Activity 创建时已持有 context
     */
    @Deprecated
    private android.content.Context getApplicationContext() {
        // 此方法不再使用，返回 null
        return null;
    }
}

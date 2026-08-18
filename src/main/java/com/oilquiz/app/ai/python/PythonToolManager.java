package com.oilquiz.app.ai.python;

import android.content.Context;
import android.util.Log;

import com.chaquo.python.Python;
import com.chaquo.python.PyObject;
import com.chaquo.python.android.AndroidPlatform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class PythonToolManager {
    private static final String TAG = "PythonToolManager";
    private static volatile PythonToolManager instance;
    
    private final Context context;
    private Python python;
    private PyObject aiPythonToolModule;
    private PyObject aiPythonTool;
    private final Map<String, PyObject> cachedTools = new ConcurrentHashMap<>();
    private boolean initialized = false;
    
    private PythonToolManager(Context context) {
        this.context = context.getApplicationContext();
        // 跟踪当前前台 Activity：供 Python UI 动作（show_dialog 弹出对话框）使用
        try {
            if (this.context instanceof android.app.Application) {
                ((android.app.Application) this.context)
                        .registerActivityLifecycleCallbacks(activityCallbacks);
            }
        } catch (Throwable t) {
            Log.w(TAG, "注册 Activity 生命周期回调失败: " + t.getMessage());
        }
    }

    /** 当前前台 Activity（弱跟踪：pause/destroy 后清空） */
    private volatile android.app.Activity currentActivity;

    private final android.app.Application.ActivityLifecycleCallbacks activityCallbacks =
            new android.app.Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(android.app.Activity activity) {
                    currentActivity = activity;
                }

                @Override public void onActivityPaused(android.app.Activity activity) {
                    if (currentActivity == activity) currentActivity = null;
                }

                @Override public void onActivityDestroyed(android.app.Activity activity) {
                    if (currentActivity == activity) currentActivity = null;
                }

                @Override public void onActivityCreated(android.app.Activity activity, android.os.Bundle b) {}
                @Override public void onActivityStarted(android.app.Activity activity) {}
                @Override public void onActivityStopped(android.app.Activity activity) {}
                @Override public void onActivitySaveInstanceState(android.app.Activity activity, android.os.Bundle b) {}
            };

    /** 当前可用的前台 Activity（无前台 Activity 返回 null，调用方降级） */
    public android.app.Activity getCurrentActivity() {
        android.app.Activity a = currentActivity;
        if (a == null || a.isFinishing()) return null;
        if (android.os.Build.VERSION.SDK_INT >= 17 && a.isDestroyed()) return null;
        return a;
    }
    
    public static PythonToolManager getInstance(Context context) {
        if (instance == null) {
            synchronized (PythonToolManager.class) {
                if (instance == null) {
                    instance = new PythonToolManager(context);
                }
            }
        }
        return instance;
    }
    
    public synchronized boolean initialize() {
        if (initialized) {
            return true;
        }
        
        try {
            if (!Python.isStarted()) {
                Log.i(TAG, "Starting Python interpreter...");
                Python.start(new AndroidPlatform(context));
                Log.i(TAG, "Python started successfully");
            } else {
                Log.i(TAG, "Python already started");
            }
            
            python = Python.getInstance();
            Log.i(TAG, "Got Python instance: " + (python != null));
            
            Log.i(TAG, "Loading ai_python_tool module...");
            aiPythonToolModule = python.getModule("ai_python_tool");
            Log.i(TAG, "Module loaded: " + (aiPythonToolModule != null));
            
            Log.i(TAG, "Getting AI tool instance...");
            aiPythonTool = aiPythonToolModule.callAttr("get_ai_tool", context);
            Log.i(TAG, "AI tool instance: " + (aiPythonTool != null));
            
            if (aiPythonTool == null) {
                Log.e(TAG, "Failed to get AI tool instance - get_ai_tool returned null");
                return false;
            }

            // 注入 UI 动作回调：Python 代码里 show_toast/show_dialog/update_progress 等
            // 通过 android_ui 模块转发到 Java 执行（Toast/Dialog/日志），不再空转
            try {
                PyObject uiModule = python.getModule("android_ui");
                if (uiModule != null) {
                    uiModule.callAttr("set_ui_callback",
                            PyObject.fromJava(new AndroidUiActionHandler()));
                    Log.i(TAG, "Android UI callback injected");
                }
            } catch (Throwable t) {
                Log.w(TAG, "注入 UI 回调失败(不影响执行): " + t.getMessage());
            }
            
            initialized = true;
            Log.i(TAG, "Python tool manager initialized successfully");
            
            // logPythonInfo() 可能导致 SIGSEGV（Chaquopy 在某些设备上 platform.platform() 崩溃）
            // 改为安全地仅记录版本信息
            try {
                PyObject sys = python.getModule("sys");
                if (sys != null) {
                    PyObject version = sys.get("version");
                    if (version != null) {
                        String verStr = version.toString();
                        Log.i(TAG, "Python version: " + (verStr.length() > 60 ? verStr.substring(0, 60) + "..." : verStr));
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Skipped Python version log (unsafe on this device)");
            }
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize Python tool manager: " + e.getMessage(), e);
            // 尝试获取更详细的错误信息
            String errorDetail = getPythonErrorDetail();
            if (errorDetail != null) {
                Log.e(TAG, "Python error detail: " + errorDetail);
            }
            // 清除缓存的失败模块，以便下次重试时可以重新导入
            try {
                if (python != null) {
                    PyObject sys = python.getModule("sys");
                    PyObject modules = sys.get("modules");
                    if (modules != null) {
                        modules.callAttr("pop", "ai_python_tool");
                    }
                }
            } catch (Exception clearEx) {
                Log.w(TAG, "Failed to clear module cache: " + clearEx.getMessage());
            }
            return false;
        }
    }
    
    private String getPythonErrorDetail() {
        try {
            if (python != null) {
                // 尝试获取 sys.exc_info() 中的错误信息
                PyObject sys = python.getModule("sys");
                PyObject excInfo = sys.get("exc_info");
                if (excInfo != null) {
                    Log.e(TAG, "sys.exc_info: " + excInfo);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting Python error detail: " + e.getMessage());
        }
        return null;
    }
    
    private void logPythonInfo() {
        try {
            PyObject sys = python.getModule("sys");
            String version = sys.get("version").toString();
            Log.i(TAG, "Python version: " + version);
            
            PyObject platform = python.getModule("platform");
            String platformInfo = platform.callAttr("platform").toString();
            Log.i(TAG, "Platform: " + platformInfo);
            
        } catch (Exception e) {
            Log.w(TAG, "Failed to log Python info: " + e.getMessage());
        }
    }
    
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * 直接执行 Python 代码（不解释为任务描述）
     * 调用 Python 端的 run_code 方法，直接执行代码而不生成模板
     */
    public ExecutionResult executeCode(String code, Map<String, Object> contextData) {
        if (!initialized) {
            if (!initialize()) {
                return new ExecutionResult(false, null, "Python tool manager not initialized");
            }
        }
        
        try {
            Log.i(TAG, "Executing code directly (" + code.length() + " chars)");
            
            PyObject result = aiPythonTool.callAttr("run_code", code);
            
            if (result == null) {
                return new ExecutionResult(false, null, "No result returned from run_code");
            }
            
            return parseExecutionResult(result);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to execute code: " + e.getMessage(), e);
            return new ExecutionResult(false, null, e.getMessage());
        }
    }
    
    public ExecutionResult processTask(String task, Map<String, Object> contextData) {
        if (!initialized) {
            if (!initialize()) {
                return new ExecutionResult(false, null, "Python tool manager not initialized");
            }
        }
        
        try {
            Log.i(TAG, "Processing task: " + task.substring(0, Math.min(100, task.length())));
            
            PyObject pyContext = null;
            if (contextData != null && !contextData.isEmpty()) {
                pyContext = PyObject.fromJava(contextData);
            }
            
            PyObject result;
            if (pyContext != null) {
                result = aiPythonTool.callAttr("process_task", task, pyContext);
            } else {
                result = aiPythonTool.callAttr("process_task", task);
            }
            
            if (result == null) {
                return new ExecutionResult(false, null, "No result returned");
            }
            
            return parseExecutionResult(result);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to process task: " + e.getMessage(), e);
            return new ExecutionResult(false, null, e.getMessage());
        }
    }
    
    @SuppressWarnings("unchecked")
    private ExecutionResult parseExecutionResult(PyObject pyResult) {
        ExecutionResult result = new ExecutionResult();
        
        try {
            // Python 返回的是 dict，必须用 asMap() 访问键值，不能用 get()（get 是访问属性的）
            Map<PyObject, PyObject> pyMap = pyResult.asMap();
            
            PyObject successObj = getFromPyMap(pyMap, "success");
            result.success = successObj != null && successObj.toBoolean();
            
            PyObject resultObj = getFromPyMap(pyMap, "result");
            if (resultObj != null && resultObj != PyObject.fromJava(null)) {
                result.result = resultObj.toString();
            }
            
            PyObject stdoutObj = getFromPyMap(pyMap, "stdout");
            if (stdoutObj != null) {
                result.stdout = stdoutObj.toString();
            }
            
            PyObject stderrObj = getFromPyMap(pyMap, "stderr");
            if (stderrObj != null) {
                result.stderr = stderrObj.toString();
            }
            
            PyObject errorObj = getFromPyMap(pyMap, "error");
            if (errorObj != null && errorObj != PyObject.fromJava(null)) {
                result.error = errorObj.toString();
            }
            
            PyObject codeObj = getFromPyMap(pyMap, "code");
            if (codeObj != null) {
                result.code = codeObj.toString();
            }
            
            PyObject attemptsObj = getFromPyMap(pyMap, "attempts");
            if (attemptsObj != null) {
                result.attempts = attemptsObj.toInt();
            }
            
            PyObject fixesObj = getFromPyMap(pyMap, "fixes");
            if (fixesObj != null && fixesObj != PyObject.fromJava(null)) {
                try {
                    result.fixes = new ArrayList<>();
                    for (PyObject fix : fixesObj.asList()) {
                        Map<PyObject, PyObject> fixMap = fix.asMap();
                        PyObject typeObj = getFromPyMap(fixMap, "error_type");
                        Map<String, String> fixEntry = new HashMap<>();
                        if (typeObj != null) {
                            fixEntry.put("error_type", typeObj.toString());
                        }
                        result.fixes.add(fixEntry);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to parse fixes: " + e.getMessage());
                }
            }
            
            Log.i(TAG, "Parsed result: success=" + result.success + ", attempts=" + result.attempts 
                + ", stdout_len=" + (result.stdout != null ? result.stdout.length() : 0)
                + ", error=" + (result.error != null ? result.error.substring(0, Math.min(100, result.error.length())) : "null"));
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse execution result: " + e.getMessage(), e);
            result.success = false;
            result.error = "Failed to parse result: " + e.getMessage();
        }
        
        return result;
    }
    
    /**
     * 从 PyObject Map 中按字符串键名获取值
     * Python dict 的键可能是 PyObject(str) 或 PyUnicode，需要匹配字符串值
     */
    private PyObject getFromPyMap(Map<PyObject, PyObject> map, String key) {
        if (map == null || key == null) return null;
        // 直接尝试用 PyObject.fromJava(key) 查找
        PyObject pyKey = PyObject.fromJava(key);
        PyObject value = map.get(pyKey);
        if (value != null) return value;
        // 备用：遍历匹配字符串值
        for (Map.Entry<PyObject, PyObject> entry : map.entrySet()) {
            if (key.equals(entry.getKey().toString())) {
                return entry.getValue();
            }
        }
        return null;
    }
    
    public List<ToolInfo> listTools() {
        List<ToolInfo> tools = new ArrayList<>();
        
        if (!initialized) {
            return tools;
        }
        
        try {
            PyObject toolsObj = aiPythonTool.callAttr("list_tools");
            if (toolsObj != null) {
                for (PyObject toolObj : toolsObj.asList()) {
                    ToolInfo tool = new ToolInfo();
                    Map<PyObject, PyObject> toolMap = toolObj.asMap();
                    
                    PyObject nameObj = getFromPyMap(toolMap, "name");
                    if (nameObj != null) {
                        tool.name = nameObj.toString();
                    }
                    
                    PyObject filenameObj = getFromPyMap(toolMap, "filename");
                    if (filenameObj != null) {
                        tool.filename = filenameObj.toString();
                    }
                    
                    PyObject pathObj = getFromPyMap(toolMap, "path");
                    if (pathObj != null) {
                        tool.path = pathObj.toString();
                    }
                    
                    PyObject modifiedObj = getFromPyMap(toolMap, "modified");
                    if (modifiedObj != null) {
                        tool.modified = modifiedObj.toString();
                    }
                    
                    tools.add(tool);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to list tools: " + e.getMessage(), e);
        }
        
        return tools;
    }
    
    public boolean deleteTool(String name) {
        if (!initialized) {
            return false;
        }
        
        try {
            PyObject result = aiPythonTool.callAttr("delete_tool", name);
            return result != null && result.toBoolean();
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete tool: " + e.getMessage(), e);
            return false;
        }
    }
    
    public ExecutionResult executeScript(String name, Map<String, Object> args) {
        if (!initialized) {
            if (!initialize()) {
                return new ExecutionResult(false, null, "Python tool manager not initialized");
            }
        }
        
        try {
            PyObject pyArgs = null;
            if (args != null && !args.isEmpty()) {
                pyArgs = PyObject.fromJava(args);
            }
            
            PyObject result;
            if (pyArgs != null) {
                result = aiPythonTool.callAttr("execute_script", name, pyArgs);
            } else {
                result = aiPythonTool.callAttr("execute_script", name);
            }
            
            if (result == null) {
                return new ExecutionResult(false, null, "No result returned");
            }
            
            return parseExecutionResult(result);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to execute script: " + e.getMessage(), e);
            return new ExecutionResult(false, null, e.getMessage());
        }
    }
    
    public Python getPython() {
        return python;
    }

    /**
     * 利用 LLM 生成 Python 代码
     * @param task 任务描述
     * @param llmGenerateFunc LLM 生成函数（接受 prompt 返回代码）
     * @return 生成的代码，失败返回 null
     */
    public String generateCode(String task, Function<String, String> llmGenerateFunc) {
        if (!initialized) {
            if (!initialize()) {
                return null;
            }
        }

        try {
            // 调用 Python 层的 LLM 生成器
            PyObject result = aiPythonTool.callAttr("generate_code_with_llm", task, null);
            if (result != null) {
                return result.toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate code with Python: " + e.getMessage());
        }

        // 如果 Python 层失败，使用内置生成器
        try {
            PyObject result = aiPythonTool.callAttr("_generate_simple_code", task);
            if (result != null) {
                return result.toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate code with simple generator: " + e.getMessage());
        }

        return null;
    }

    /**
     * 执行代码并获取详细结果
     * @param code Python 代码
     * @param maxAttempts 最大尝试次数（包含自动修复）
     * @param timeoutSeconds 超时时间
     * @return 执行结果
     */
    public ExecutionResult executeWithRetry(String code, int maxAttempts, int timeoutSeconds) {
        if (!initialized) {
            if (!initialize()) {
                return new ExecutionResult(false, null, "Python tool manager not initialized");
            }
        }

        try {
            PyObject result = aiPythonTool.callAttr(
                "process_task",
                "执行代码: " + code,
                null  // contextData
            );

            if (result == null) {
                return new ExecutionResult(false, null, "No result returned");
            }

            return parseExecutionResult(result);

        } catch (Exception e) {
            Log.e(TAG, "Failed to execute with retry: " + e.getMessage(), e);
            return new ExecutionResult(false, null, e.getMessage());
        }
    }

    /**
     * 获取上次错误信息
     */
    public String getLastError() {
        return lastError;
    }

    private String lastError = null;

    public static class ExecutionResult {
        public boolean success;
        public String result;
        public String stdout;
        public String stderr;
        public String error;
        public String code;
        public int attempts;
        public List<Map<String, String>> fixes;
        
        public ExecutionResult() {
            this.success = false;
            this.result = null;
            this.stdout = "";
            this.stderr = "";
            this.error = null;
            this.code = "";
            this.attempts = 0;
            this.fixes = new ArrayList<>();
        }
        
        public ExecutionResult(boolean success, String result, String error) {
            this.success = success;
            this.result = result;
            this.stdout = "";
            this.stderr = "";
            this.error = error;
            this.code = "";
            this.attempts = 0;
            this.fixes = new ArrayList<>();
        }
        
        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"success\":").append(success);
            if (result != null) {
                sb.append(",\"result\":\"").append(escapeJson(result)).append("\"");
            }
            if (stdout != null && !stdout.isEmpty()) {
                sb.append(",\"stdout\":\"").append(escapeJson(stdout)).append("\"");
            }
            if (stderr != null && !stderr.isEmpty()) {
                sb.append(",\"stderr\":\"").append(escapeJson(stderr)).append("\"");
            }
            if (error != null) {
                sb.append(",\"error\":\"").append(escapeJson(error)).append("\"");
            }
            if (code != null && !code.isEmpty()) {
                sb.append(",\"code\":\"").append(escapeJson(code)).append("\"");
            }
            sb.append(",\"attempts\":").append(attempts);
            sb.append("}");
            return sb.toString();
        }
        
        private String escapeJson(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }
        
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("ExecutionResult{");
            sb.append("success=").append(success);
            if (result != null) {
                sb.append(", result='").append(result.substring(0, Math.min(100, result.length()))).append("'");
            }
            if (error != null) {
                sb.append(", error='").append(error).append("'");
            }
            sb.append(", attempts=").append(attempts);
            sb.append("}");
            return sb.toString();
        }
    }
    
    public static class ToolInfo {
        public String name;
        public String filename;
        public String path;
        public String modified;
        
        @Override
        public String toString() {
            return "ToolInfo{" +
                    "name='" + name + '\'' +
                    ", filename='" + filename + '\'' +
                    ", modified='" + modified + '\'' +
                    '}';
        }
    }

    /**
     * Python UI 动作处理器（注入 android_ui.set_ui_callback）。
     * Python 代码 show_toast/show_dialog/update_progress/notify_java 的 action dict
     * 由 Chaquopy 以 PyObject 传入，本类在主线程执行 Toast/日志，返回执行结果 dict。
     */
    public class AndroidUiActionHandler {

        public Object handle(PyObject action) {
            Map<String, Object> reply = new HashMap<>();
            try {
                String type = "", message = "", title = "", duration = "short", eventType = "";
                String dialogType = "info";
                int current = 0, total = 0;
                if (action != null) {
                    Map<PyObject, PyObject> m = action.asMap();
                    for (Map.Entry<PyObject, PyObject> e : m.entrySet()) {
                        String k = e.getKey() == null ? "" : e.getKey().toString();
                        String v = e.getValue() == null ? "" : e.getValue().toString();
                        switch (k) {
                            case "type": type = v; break;
                            case "message": message = v; break;
                            case "title": title = v; break;
                            case "duration": duration = v; break;
                            case "event_type": eventType = v; break;
                            case "dialog_type": dialogType = v; break;
                            case "current": current = parseInt(v); break;
                            case "total": total = parseInt(v); break;
                            default: break;
                        }
                    }
                }
                switch (type) {
                    case "toast":
                        showToast(message, "long".equalsIgnoreCase(duration));
                        break;
                    case "dialog":
                        // 用当前前台 Activity 弹真实对话框（info/confirm/warning 三种按钮形态）
                        showDialog(title, message, dialogType);
                        break;
                    case "progress":
                        Log.i(TAG, "[Python progress] " + current + "/" + total + " " + message);
                        break;
                    case "java_event":
                        Log.i(TAG, "[Python event] " + eventType + " " + message);
                        break;
                    default:
                        Log.i(TAG, "[Python UI] unknown action: " + type);
                        break;
                }
                reply.put("success", true);
                reply.put("result", "executed");
                reply.put("message", "UI 动作已执行: " + type);
            } catch (Throwable t) {
                Log.w(TAG, "处理 Python UI 动作失败: " + t.getMessage());
                reply.put("success", false);
                reply.put("message", "UI 动作处理失败: " + t.getMessage());
            }
            return reply;
        }

        private void showToast(String msg, boolean longDuration) {
            if (msg == null || msg.isEmpty()) return;
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(() -> {
                try {
                    android.widget.Toast.makeText(context, msg,
                            longDuration ? android.widget.Toast.LENGTH_LONG : android.widget.Toast.LENGTH_SHORT)
                            .show();
                } catch (Throwable ignored) {
                }
            });
        }

        /** 弹出真实对话框：info=确定；confirm=确定/取消；warning=知道了（红色警告）。无前台 Activity 时降级 Toast */
        private void showDialog(String title, String message, String dialogType) {
            String dType = dialogType != null ? dialogType : "info";
            String toastText = (title != null && !title.isEmpty() ? title + "：" : "") + message;
            android.app.Activity act = getCurrentActivity();
            if (act == null) {
                Log.w(TAG, "无前台 Activity，对话框降级为 Toast: " + toastText);
                showToast(toastText, true);
                return;
            }
            final android.app.Activity fAct = act;
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(() -> {
                try {
                    if (fAct.isFinishing() || fAct.isDestroyed()) {
                        showToast(toastText, true);
                        return;
                    }
                    android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(fAct);
                    if (title != null && !title.isEmpty()) b.setTitle(title);
                    if (message != null && !message.isEmpty()) b.setMessage(message);
                    switch (dType) {
                        case "confirm":
                            b.setPositiveButton("确定", null);
                            b.setNegativeButton("取消", null);
                            break;
                        case "warning":
                            b.setPositiveButton("知道了", null);
                            break;
                        default:
                            b.setPositiveButton("确定", null);
                            break;
                    }
                    b.setCancelable(true);
                    b.show();
                    Log.i(TAG, "[Python dialog] shown: " + dType + " - " + title);
                } catch (Throwable t) {
                    Log.w(TAG, "弹出对话框失败，降级 Toast: " + t.getMessage());
                    showToast(toastText, true);
                }
            });
        }

        private int parseInt(String v) {
            try {
                // Python float/int toString 可能带 .0
                return (int) Double.parseDouble(v.trim());
            } catch (Exception e) {
                return 0;
            }
        }
    }
}

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

    // ==================== 系统 UI 组件公开 API（供 SystemUIComponentTool / Agent 直接调用） ====================

    private volatile AndroidUiActionHandler uiActionHandler;

    /** 获取（懒创建）UI 动作处理器，供工具/Agent 直接填参数调用系统 UI 组件 */
    public AndroidUiActionHandler getUiActionHandler() {
        if (uiActionHandler == null) {
            synchronized (this) {
                if (uiActionHandler == null) {
                    uiActionHandler = new AndroidUiActionHandler();
                }
            }
        }
        return uiActionHandler;
    }

    /**
     * 创建系统 UI 组件（供工具直接调用，不经过 Python）。
     * @param componentType dialog/progress/input/choice/multi_choice/date/time/image/snackbar 或内置组件类型
     * @return result Map：success / component_id / result
     */
    public Map<String, Object> createUiComponent(String componentType, java.util.Map<String, Object> params) {
        try {
            Map<String, Object> action = new HashMap<>();
            action.put("type", "create_component");
            action.put("component_type", componentType != null ? componentType : "dialog");
            if (params != null) {
                for (Map.Entry<String, Object> e : params.entrySet()) {
                    action.put(e.getKey(), e.getValue());
                }
            }
            return getUiActionHandler().handleMap(action);
        } catch (Throwable t) {
            Log.e(TAG, "创建UI组件失败: " + t.getMessage(), t);
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "创建UI组件失败: " + t.getMessage());
            return err;
        }
    }

    /** 更新系统 UI 组件 */
    public Map<String, Object> updateUiComponent(String componentId, java.util.Map<String, Object> params) {
        try {
            Map<String, Object> action = new HashMap<>();
            action.put("type", "update_component");
            action.put("component_id", componentId);
            if (params != null) action.putAll(params);
            return getUiActionHandler().handleMap(action);
        } catch (Throwable t) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "更新UI组件失败: " + t.getMessage());
            return err;
        }
    }

    /** 关闭系统 UI 组件 */
    public Map<String, Object> closeUiComponent(String componentId) {
        try {
            Map<String, Object> action = new HashMap<>();
            action.put("type", "close_component");
            action.put("component_id", componentId);
            return getUiActionHandler().handleMap(action);
        } catch (Throwable t) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "关闭UI组件失败: " + t.getMessage());
            return err;
        }
    }

    /** 获取系统 UI 组件结果（阻塞等待用户操作） */
    public Map<String, Object> getUiComponentResult(String componentId, int waitSeconds) {
        try {
            Map<String, Object> action = new HashMap<>();
            action.put("type", "get_component_result");
            action.put("component_id", componentId);
            action.put("wait_seconds", waitSeconds);
            return getUiActionHandler().handleMap(action);
        } catch (Throwable t) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "获取UI组件结果失败: " + t.getMessage());
            return err;
        }
    }

    /**
     * 注册聊天流组件到组件注册表（无对话框实例）。
     * 供 SystemUIComponentTool 的内置组件进聊天流后，用户点击 actions 按钮
     * （action=callback）回写结果，Agent 可 getUiComponentResult 取回。
     * @return true 注册成功
     */
    public boolean registerChatComponent(String componentId) {
        try {
            return getUiActionHandler().registerPendingComponent(componentId);
        } catch (Throwable t) {
            Log.e(TAG, "注册聊天流组件失败: " + t.getMessage(), t);
            return false;
        }
    }

    /**
     * 组件结果回调（供 ComponentActions 调用）：component_id → value 写入注册表 result。
     */
    public void notifyChatComponentResult(String componentId, String value) {
        try {
            getUiActionHandler().setComponentResult(componentId, value);
        } catch (Throwable t) {
            Log.e(TAG, "写入聊天流组件结果失败: " + t.getMessage(), t);
        }
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
        
        // Chaquopy 的 Python 解释器启动与模块加载要求主线程（Android 平台限制）。
        // Agent 工具调用在后台线程执行，必须切到主线程做初始化，否则 getModule 可能失败
        // （表现为"python不能初始化"）。
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            final boolean[] done = {false};
            final boolean[] ok = {false};
            final Throwable[] err = {null};
            final Object lock = new Object();
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(() -> {
                try {
                    ok[0] = doInitialize();
                } catch (Throwable t) {
                    err[0] = t;
                } finally {
                    synchronized (lock) {
                        done[0] = true;
                        lock.notifyAll();
                    }
                }
            });
            try {
                synchronized (lock) {
                    while (!done[0]) {
                        lock.wait(30000);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.e(TAG, "等待主线程 Python 初始化被中断", e);
                return false;
            }
            if (err[0] != null) {
                Log.e(TAG, "主线程 Python 初始化异常: " + err[0].getMessage(), err[0]);
            }
            if (!ok[0]) {
                clearFailedModuleCache();
            }
            return ok[0];
        }
        
        boolean success = doInitialize();
        if (!success) {
            clearFailedModuleCache();
        }
        return success;
    }

    /** 实际初始化（必须在主线程调用） */
    private boolean doInitialize() {
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

            // 注入 UI 动作回调：Python 代码里 show_toast/show_dialog/create_component 等
            // 通过 android_ui 模块转发到 Java 执行（Toast/Dialog/进度条/日志），真实显示在手机界面
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
            return false;
        }
    }

    /** 清除缓存的失败模块，以便下次重试时可以重新导入 */
    private void clearFailedModuleCache() {
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
     *
     * 动态系统 UI 组件 API（组件"握手"：create → 拿 component_id → update/close → get_result）：
     *   {"type":"create_component","component_type":"dialog|progress","component_id":"可选自定义ID",
     *    "title":..,"message":..,"dialog_type":"info|confirm|warning","max":100}
     *   {"type":"update_component","component_id":"..","progress":60,"message":"..","title":"..","max":..}
     *   {"type":"close_component","component_id":".."}
     *   {"type":"get_component_result","component_id":"..","wait_seconds":0}
     *     → {"result":"positive|negative|cancelled|pending"}
     */
    public class AndroidUiActionHandler {

        /** 动态组件注册表：component_id → 组件运行时（对话框实例/进度条实例/结果） */
        private final java.util.Map<String, ComponentRuntime> dynamicComponents = new java.util.concurrent.ConcurrentHashMap<>();

        /** 组件运行时：UI 实例 + 用户选择结果（线程安全） */
        private static class ComponentRuntime {
            volatile android.app.Dialog dialog;
            final java.util.concurrent.atomic.AtomicReference<String> result =
                    new java.util.concurrent.atomic.AtomicReference<>("pending");
            final Object resultLock = new Object();
            /** 自动关闭（auto_close 秒后 dismiss）：主线程 handler + 任务，close 时移除 */
            volatile android.os.Handler autoCloseHandler;
            volatile Runnable autoCloseTask;
        }

        /** Python 入口：把 PyObject 转为 Map 后统一走纯 Java 处理 */
        public Object handle(PyObject action) {
            Map<String, Object> map = new HashMap<>();
            if (action != null) {
                try {
                    Map<PyObject, PyObject> m = action.asMap();
                    for (Map.Entry<PyObject, PyObject> e : m.entrySet()) {
                        map.put(e.getKey() == null ? "" : e.getKey().toString(),
                                e.getValue() == null ? "" : e.getValue().toString());
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Python action 解析失败: " + t.getMessage());
                }
            }
            return handleMap(map);
        }

        /** 纯 Java 入口：工具/Agent 直接填参数调用（无需 Python 解释器） */
        public Map<String, Object> handleMap(Map<String, Object> action) {
            Map<String, Object> reply = new HashMap<>();
            try {
                String type = "", message = "", title = "", duration = "short", eventType = "";
                String dialogType = "info", componentType = "", componentId = "";
                int current = 0, total = 0, max = 100, progress = 0, waitSeconds = 0;
                String options = "", defaultValue = "", inputHint = "", props = "", actionLabel = "";
                String items = "", url = "", clickAction = "";
                int autoClose = 0;
                if (action != null) {
                    for (Map.Entry<String, Object> e : action.entrySet()) {
                        String k = e.getKey() == null ? "" : e.getKey().toString();
                        Object vObj = e.getValue();
                        String v = vObj == null ? "" : String.valueOf(vObj);
                        switch (k) {
                            case "type": type = v; break;
                            case "message": message = v; break;
                            case "title": title = v; break;
                            case "duration": duration = v; break;
                            case "event_type": eventType = v; break;
                            case "dialog_type": dialogType = v; break;
                            case "current": current = parseInt(v); break;
                            case "total": total = parseInt(v); break;
                            case "component_type": componentType = v; break;
                            case "component_id": componentId = v; break;
                            case "max": max = parseInt(v); break;
                            case "progress": progress = parseInt(v); break;
                            case "wait_seconds": waitSeconds = parseInt(v); break;
                            case "options": options = v; break;
                            case "default_value": defaultValue = v; break;
                            case "input_hint": inputHint = v; break;
                            case "props": props = v; break;
                            case "action_label": actionLabel = v; break;
                            case "items": items = v; break;
                            case "url": url = v; break;
                            case "click_action": clickAction = v; break;
                            case "auto_close": autoClose = parseInt(v); break;
                            default: break;
                        }
                    }
                }
                switch (type) {
                    case "toast":
                        showToast(message, "long".equalsIgnoreCase(duration));
                        break;
                    case "dialog":
                        // 弹真实对话框并阻塞等待用户选择，把结果(positive/negative/cancelled)回传 Python
                        reply.put("result", showDialog(title, message, dialogType));
                        break;
                    case "progress":
                        Log.i(TAG, "[Python progress] " + current + "/" + total + " " + message);
                        break;
                    case "java_event":
                        Log.i(TAG, "[Python event] " + eventType + " " + message);
                        break;
                    case "create_component":
                        reply.putAll(createComponent(componentType, componentId, title, message,
                                dialogType, max, options, defaultValue, inputHint, props, actionLabel,
                                items, url, clickAction, autoClose));
                        break;
                    case "update_component":
                        reply.putAll(updateComponent(componentId, title, message, progress, max));
                        break;
                    case "close_component":
                        reply.putAll(closeComponent(componentId));
                        break;
                    case "get_component_result":
                        reply.putAll(getComponentResult(componentId, waitSeconds));
                        break;                    default:
                        Log.i(TAG, "[Python UI] unknown action: " + type);
                        break;
                }
                if (!reply.containsKey("success")) {
                    reply.put("success", true);
                }
                // 只有未设置 result 的动作才补默认值；create_component 等已设置 result/component_id 的不覆盖
                if (!reply.containsKey("result") && !reply.containsKey("component_id")) {
                    reply.put("result", "executed");
                }
                reply.putIfAbsent("message", "UI 动作已执行: " + type);
            } catch (Throwable t) {
                Log.w(TAG, "处理 Python UI 动作失败: " + t.getMessage());
                reply.put("success", false);
                reply.put("message", "UI 动作处理失败: " + t.getMessage());
            }
            return reply;
        }

        /** 创建动态组件：dialog/progress/input/choice/snackbar/date/time/multi_choice/image/list/web/notification（系统原生）或内置 UI 组件(chart 等 22 种)。返回 component_id。autoCloseSeconds>0 时创建后 N 秒自动关闭（并置 result=closed）。 */
        private Map<String, Object> createComponent(String componentType, String componentId,
                                                    String title, String message,
                                                    String dialogType, int max,
                                                    String options, String defaultValue, String inputHint,
                                                    String props, String actionLabel,
                                                    String items, String url, String clickAction,
                                                    int autoCloseSeconds) {
            Map<String, Object> reply = new HashMap<>();
            String type = componentType != null ? componentType : "dialog";
            final String id = (componentId != null && !componentId.isEmpty())
                    ? componentId : "comp_" + System.currentTimeMillis() + "_" + (int) (Math.random() * 10000);
            if (dynamicComponents.containsKey(id)) {
                reply.put("success", false);
                reply.put("message", "组件ID已存在: " + id);
                reply.put("component_id", id);
                return reply;
            }
            final ComponentRuntime rt = new ComponentRuntime();
            dynamicComponents.put(id, rt);

            // 系统通知栏组件：不依赖前台 Activity（后台任务也能用），create 即发通知，
            // update 更新内容/进度，close 移除通知；get_result 返回 clicked/closed
            if ("notification".equals(type)) {
                android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                mainHandler.post(() -> {
                    try {
                        android.app.NotificationManager nm = (android.app.NotificationManager)
                                context.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
                        if (nm == null) {
                            rt.result.set("cancelled");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                            return;
                        }
                        String channelId = "ai_component_notification";
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                                    channelId, "AI 组件通知", android.app.NotificationManager.IMPORTANCE_DEFAULT);
                            channel.setDescription("Agent 动态创建的组件通知");
                            nm.createNotificationChannel(channel);
                        }
                        android.content.Intent intent = new android.content.Intent(context,
                                com.oilquiz.app.ui.activity.AIChatActivity.class);
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(context, (int) (System.currentTimeMillis() % 100000), intent,
                                android.os.Build.VERSION.SDK_INT >= 23
                                        ? android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
                                        : android.app.PendingIntent.FLAG_UPDATE_CURRENT);
                        android.app.Notification.Builder builder = android.os.Build.VERSION.SDK_INT >= 26
                                ? new android.app.Notification.Builder(context, channelId)
                                : new android.app.Notification.Builder(context);
                        builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                                .setContentTitle(title != null && !title.isEmpty() ? title : "AI 通知")
                                .setContentText(message != null ? message : "")
                                .setContentIntent(pi)
                                .setAutoCancel(true)
                                .setOngoing(clickAction != null && clickAction.contains("ongoing"));
                        // 进度样式通知（create 时不带进度；update 时由 updateComponent 重发带进度）
                        if (max > 0) {
                            builder.setProgress(max, 0, false);
                        }
                        int notifId = id.hashCode();
                        nm.notify(notifId, builder.build());
                        rt.dialog = null;
                        Log.i(TAG, "[Python component] notification created: " + id);
                    } catch (Throwable t) {
                        Log.w(TAG, "创建通知失败: " + t.getMessage());
                        rt.result.set("cancelled");
                        synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                    }
                });
                reply.put("success", true);
                reply.put("component_id", id);
                Map<String, Object> resultWrap = new HashMap<>();
                resultWrap.put("component_id", id);
                resultWrap.put("type", "notification");
                reply.put("result", resultWrap);
                reply.put("message", "通知已创建: " + id);
                return reply;
            }

            android.app.Activity act = getCurrentActivity();
            if (act == null) {
                Log.w(TAG, "无前台 Activity，组件降级为 Toast: " + title);
                showToast((title != null && !title.isEmpty() ? title + "：" : "") + message, true);
                rt.result.set("cancelled");
                synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                reply.put("success", true);
                reply.put("component_id", id);
                reply.put("message", "无前台Activity，组件降级为Toast");
                return reply;
            }
            final android.app.Activity fAct = act;
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            final String fType = type;
            final String fOptions = options != null ? options : "";
            final String fDefault = defaultValue != null ? defaultValue : "";
            final String fHint = inputHint != null ? inputHint : "";
            final String fProps = props != null ? props : "";
            final String fActionLabel = actionLabel != null ? actionLabel : "";
            final String fItems = items != null ? items : "";
            final String fUrl = url != null ? url : "";
            final String fClickAction = clickAction != null ? clickAction : "";
            main.post(() -> {
                try {
                    if (fAct.isFinishing() || fAct.isDestroyed()) {
                        rt.result.set("cancelled");
                        synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        return;
                    }
                    // 内置 UI 组件（ComponentRegistry 已注册类型：chart/info_card/table_card/... 22 种）：
                    // 用项目自带组件库渲染成真实 View，弹窗展示
                    com.oilquiz.app.ai.chat.component.ComponentRegistry registry =
                            com.oilquiz.app.ai.chat.component.ComponentRegistry.getInstance();
                    if (registry.hasType(fType)) {
                        org.json.JSONObject propsObj = new org.json.JSONObject();
                        if (!fProps.isEmpty()) {
                            try {
                                propsObj = new org.json.JSONObject(fProps);
                            } catch (Exception e) {
                                Log.w(TAG, "内置组件 props 解析失败，使用空 props: " + e.getMessage());
                            }
                        }
                        // 未显式传 title 时用组件的 title 参数
                        if (title != null && !title.isEmpty() && !propsObj.has("title")) {
                            propsObj.put("title", title);
                        }
                        com.oilquiz.app.ai.chat.component.ComponentData data =
                                new com.oilquiz.app.ai.chat.component.ComponentData(fType, propsObj);
                        android.view.View view = registry.render(fAct, data);
                        if (view == null) {
                            view = registry.renderFallback(fAct, data);
                        }
                        // 组件放进 Dialog：标题栏 + 组件内容 + 关闭按钮
                        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(fAct);
                        android.widget.LinearLayout root = new android.widget.LinearLayout(fAct);
                        root.setOrientation(android.widget.LinearLayout.VERTICAL);
                        root.setPadding(
                                (int) (16 * fAct.getResources().getDisplayMetrics().density),
                                (int) (12 * fAct.getResources().getDisplayMetrics().density),
                                (int) (16 * fAct.getResources().getDisplayMetrics().density),
                                (int) (8 * fAct.getResources().getDisplayMetrics().density));
                        android.widget.ScrollView scroll = new android.widget.ScrollView(fAct);
                        scroll.addView(view);
                        root.addView(scroll, new android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
                        b.setView(root);
                        b.setPositiveButton("关闭", (d, w) -> {
                            rt.result.compareAndSet("pending", "closed");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                            d.dismiss();
                        });
                        b.setCancelable(true);
                        b.setOnCancelListener(d -> {
                            rt.result.compareAndSet("pending", "closed");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        });
                        android.app.AlertDialog dialog = b.create();
                        dialog.setOnDismissListener(d -> {
                            rt.result.compareAndSet("pending", "closed");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        });
                        dialog.show();
                        rt.dialog = dialog;
                        scheduleAutoClose(rt, main, autoCloseSeconds);
                        Log.i(TAG, "[Python component] builtin UI component shown: " + fType + " (" + id + ")");
                        return;
                    }
                    if ("progress".equals(fType)) {
                        // 系统进度条对话框（水平进度条，可 update_component 更新；到 max 自动关闭，也可手动取消）
                        android.app.ProgressDialog pd = new android.app.ProgressDialog(fAct);
                        pd.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
                        pd.setCancelable(true);
                        pd.setOnCancelListener(d -> {
                            rt.result.compareAndSet("pending", "cancelled");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        });
                        if (title != null && !title.isEmpty()) pd.setTitle(title);
                        if (message != null && !message.isEmpty()) pd.setMessage(message);
                        pd.setMax(max > 0 ? max : 100);
                        pd.setProgress(0);
                        pd.show();
                        rt.dialog = pd;
                        Log.i(TAG, "[Python component] progress created: " + id);
                    } else if ("input".equals(fType)) {
                        // 系统文本输入对话框：结果=用户输入文本
                        android.widget.EditText input = new android.widget.EditText(fAct);
                        input.setHint(fHint.isEmpty() ? "请输入" : fHint);
                        if (!fDefault.isEmpty()) input.setText(fDefault);
                        input.setSingleLine(true);
                        input.setSelection(input.getText().length());
                        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(fAct);
                        if (title != null && !title.isEmpty()) b.setTitle(title);
                        if (message != null && !message.isEmpty()) b.setMessage(message);
                        b.setView(input);
                        b.setPositiveButton("确定", (d, w) -> {
                            rt.result.set(input.getText() != null ? input.getText().toString() : "");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                            d.dismiss();
                        });
                        b.setNegativeButton("取消", (d, w) -> {
                            rt.result.set("cancelled");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                            d.dismiss();
                        });
                        b.setCancelable(true);
                        b.setOnCancelListener(d -> {
                            rt.result.compareAndSet("pending", "cancelled");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        });
                        android.app.AlertDialog dialog = b.create();
                        dialog.setOnDismissListener(d -> {
                            rt.result.compareAndSet("pending", "cancelled");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        });
                        dialog.show();
                        rt.dialog = dialog;
                        Log.i(TAG, "[Python component] input created: " + id);
                    } else if ("choice".equals(fType)) {
                        // 系统选项选择对话框：结果=选中项文本（或 JSON 数组形式 options）
                        final java.util.List<String> choices = new ArrayList<>();
                        try {
                            if (!fOptions.isEmpty()) {
                                org.json.JSONArray arr = new org.json.JSONArray(fOptions);
                                for (int i = 0; i < arr.length(); i++) {
                                    Object o = arr.opt(i);
                                    if (o instanceof org.json.JSONObject) {
                                        org.json.JSONObject jo = (org.json.JSONObject) o;
                                        String label = jo.optString("label", jo.optString("value", ""));
                                        choices.add(label);
                                    } else if (o != null) {
                                        choices.add(String.valueOf(o));
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                        }
                        final String[] selected = {null};
                        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(fAct);
                        if (title != null && !title.isEmpty()) b.setTitle(title);
                        if (message != null && !message.isEmpty()) b.setMessage(message);
                        if (choices.isEmpty()) {
                            choices.add("确定");
                        }
                        b.setItems(choices.toArray(new String[0]), (d, which) -> {
                            if (which >= 0 && which < choices.size()) {
                                selected[0] = choices.get(which);
                            }
                        });
                        b.setPositiveButton("确定", (d, w) -> {
                            rt.result.set(selected[0] != null ? selected[0] : "cancelled");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                            d.dismiss();
                        });
                        b.setNegativeButton("取消", (d, w) -> {
                            rt.result.set("cancelled");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                            d.dismiss();
                        });
                        b.setCancelable(true);
                        b.setOnCancelListener(d -> {
                            rt.result.compareAndSet("pending", "cancelled");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        });
                        android.app.AlertDialog dialog = b.create();
                        dialog.setOnDismissListener(d -> {
                            rt.result.compareAndSet("pending", "cancelled");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        });
                        dialog.show();
                        rt.dialog = dialog;
                        Log.i(TAG, "[Python component] choice created: " + id);
                    } else if ("multi_choice".equals(fType)) {
                        // 系统多选列表对话框：结果=选中项 JSON 数组
                        final java.util.List<String> choices = new ArrayList<>();
                        try {
                            if (!fOptions.isEmpty()) {
                                org.json.JSONArray arr = new org.json.JSONArray(fOptions);
                                for (int i = 0; i < arr.length(); i++) {
                                    Object o = arr.opt(i);
                                    if (o instanceof org.json.JSONObject) {
                                        org.json.JSONObject jo = (org.json.JSONObject) o;
                                        choices.add(jo.optString("label", jo.optString("value", "")));
                                    } else if (o != null) {
                                        choices.add(String.valueOf(o));
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                        }
                        if (choices.isEmpty()) {
                            choices.add("确定");
                        }
                        final boolean[] checked = new boolean[choices.size()];
                        // 默认选中 default_value（分号分隔的索引或文本列表）
                        String[] preselect = fDefault.split(";");
                        for (String ps : preselect) {
                            String p = ps.trim();
                            if (p.isEmpty()) continue;
                            try {
                                int idx = Integer.parseInt(p);
                                if (idx >= 0 && idx < choices.size()) checked[idx] = true;
                            } catch (NumberFormatException ignored) {
                                for (int i = 0; i < choices.size(); i++) {
                                    if (choices.get(i).equals(p)) checked[i] = true;
                                }
                            }
                        }
                        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(fAct);
                        if (title != null && !title.isEmpty()) b.setTitle(title);
                        b.setMultiChoiceItems(choices.toArray(new String[0]), checked, (d, which, isChecked) -> {
                            if (which >= 0 && which < checked.length) checked[which] = isChecked;
                        });
                        b.setPositiveButton("确定", (d, w) -> {
                            try {
                                org.json.JSONArray sel = new org.json.JSONArray();
                                for (int i = 0; i < choices.size(); i++) {
                                    if (checked[i]) sel.put(choices.get(i));
                                }
                                rt.result.set(sel.toString());
                            } catch (Exception e) {
                                rt.result.set("[]");
                            }
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                            d.dismiss();
                        });
                        b.setNegativeButton("取消", (d, w) -> {
                            rt.result.set("cancelled");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                            d.dismiss();
                        });
                        b.setCancelable(true);
                        b.setOnCancelListener(d -> {
                            rt.result.compareAndSet("pending", "cancelled");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        });
                        android.app.AlertDialog dialog = b.create();
                        dialog.setOnDismissListener(d -> {
                            rt.result.compareAndSet("pending", "cancelled");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        });
                        dialog.show();
                        rt.dialog = dialog;
                        Log.i(TAG, "[Python component] multi_choice created: " + id);
                    } else if ("date".equals(fType)) {
                        // 系统日期选择器：结果=YYYY-MM-DD
                        final java.util.Calendar cal = java.util.Calendar.getInstance();
                        try {
                            if (!fDefault.isEmpty()) {
                                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault());
                                java.util.Date d = sdf.parse(fDefault);
                                if (d != null) cal.setTime(d);
                            }
                        } catch (Exception ignored) {
                        }
                        android.app.DatePickerDialog dpd = new android.app.DatePickerDialog(fAct,
                                (view, year, month, dayOfMonth) -> {
                                    rt.result.set(String.format(java.util.Locale.getDefault(),
                                            "%04d-%02d-%02d", year, month + 1, dayOfMonth));
                                    synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                                },
                                cal.get(java.util.Calendar.YEAR),
                                cal.get(java.util.Calendar.MONTH),
                                cal.get(java.util.Calendar.DAY_OF_MONTH));
                        dpd.setCancelable(true);
                        dpd.setOnCancelListener(d -> {
                            rt.result.compareAndSet("pending", "cancelled");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        });
                        dpd.setOnDismissListener(d -> {
                            rt.result.compareAndSet("pending", "cancelled");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        });
                        dpd.show();
                        rt.dialog = dpd;
                        Log.i(TAG, "[Python component] date created: " + id);
                    } else if ("time".equals(fType)) {
                        // 系统时间选择器：结果=HH:mm
                        final java.util.Calendar cal = java.util.Calendar.getInstance();
                        try {
                            if (!fDefault.isEmpty()) {
                                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
                                java.util.Date d = sdf.parse(fDefault);
                                if (d != null) cal.setTime(d);
                            }
                        } catch (Exception ignored) {
                        }
                        android.app.TimePickerDialog tpd = new android.app.TimePickerDialog(fAct,
                                (view, hourOfDay, minute) -> {
                                    rt.result.set(String.format(java.util.Locale.getDefault(),
                                            "%02d:%02d", hourOfDay, minute));
                                    synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                                },
                                cal.get(java.util.Calendar.HOUR_OF_DAY),
                                cal.get(java.util.Calendar.MINUTE),
                                true);
                        tpd.setCancelable(true);
                        tpd.setOnCancelListener(d -> {
                            rt.result.compareAndSet("pending", "cancelled");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        });
                        tpd.setOnDismissListener(d -> {
                            rt.result.compareAndSet("pending", "cancelled");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        });
                        tpd.show();
                        rt.dialog = tpd;
                        Log.i(TAG, "[Python component] time created: " + id);
                    } else if ("image".equals(fType)) {
                        // 系统图片预览：显示本地图片文件（path）或网络图片（url）到对话框
                        android.widget.ImageView iv = new android.widget.ImageView(fAct);
                        iv.setAdjustViewBounds(true);
                        iv.setMaxHeight((int) (fAct.getResources().getDisplayMetrics().heightPixels * 0.6f));
                        final String imgSrc = !fDefault.isEmpty() ? fDefault : fProps;
                        boolean loaded = false;
                        try {
                            if (imgSrc != null && !imgSrc.isEmpty()) {
                                if (imgSrc.startsWith("http://") || imgSrc.startsWith("https://")) {
                                    try {
                                        com.bumptech.glide.Glide.with(fAct).load(imgSrc).into(iv);
                                        loaded = true;
                                    } catch (Throwable ignored) {
                                    }
                                } else {
                                    String path = imgSrc;
                                    if (path.startsWith("file://")) path = android.net.Uri.parse(path).getPath();
                                    java.io.File imgFile = new java.io.File(path);
                                    if (imgFile.exists()) {
                                        android.graphics.Bitmap bmp = com.oilquiz.app.util.ImageParserUtil.parseImage(imgFile, 1600, 1600);
                                        if (bmp != null) {
                                            iv.setImageBitmap(bmp);
                                            loaded = true;
                                        }
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "加载图片失败: " + t.getMessage());
                        }
                        if (!loaded) {
                            rt.result.set("cancelled");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                            return;
                        }
                        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(fAct);
                        if (title != null && !title.isEmpty()) b.setTitle(title);
                        b.setView(iv);
                        b.setPositiveButton("关闭", (d, w) -> {
                            rt.result.compareAndSet("pending", "closed");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                            d.dismiss();
                        });
                        b.setCancelable(true);
                        b.setOnCancelListener(d -> {
                            rt.result.compareAndSet("pending", "closed");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        });
                        android.app.AlertDialog dialog = b.create();
                        dialog.setOnDismissListener(d -> {
                            rt.result.compareAndSet("pending", "closed");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        });
                        dialog.show();
                        rt.dialog = dialog;
                        Log.i(TAG, "[Python component] image created: " + id);
                    } else if ("snackbar".equals(fType)) {
                        // 系统 Snackbar 底部提示（可带操作按钮 action_label；结果=action/closed）
                        final android.view.View root = fAct.findViewById(android.R.id.content);
                        if (root == null) {
                            showToast((title != null && !title.isEmpty() ? title + "：" : "") + message, true);
                            rt.result.set("closed");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                            return;
                        }
                        android.widget.FrameLayout content = (android.widget.FrameLayout) root;
                        com.google.android.material.snackbar.Snackbar sb =
                                com.google.android.material.snackbar.Snackbar.make(
                                        content, message != null && !message.isEmpty() ? message : title, 
                                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG);
                        if (!fActionLabel.isEmpty()) {
                            sb.setAction(fActionLabel, v -> {
                                rt.result.compareAndSet("pending", "action");
                                synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                            });
                        }
                        sb.addCallback(new com.google.android.material.snackbar.BaseTransientBottomBar.BaseCallback<com.google.android.material.snackbar.Snackbar>() {
                            @Override
                            public void onDismissed(com.google.android.material.snackbar.Snackbar transientBottomBar, int event) {
                                rt.result.compareAndSet("pending", "closed");
                                synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                            }
                        });
                        sb.show();
                        rt.dialog = null;
                        Log.i(TAG, "[Python component] snackbar created: " + id);
                    } else if ("list".equals(fType)) {
                        // 系统原生列表弹窗：items 为 JSON 数组（字符串列表 或 [{"title"/"label","description"/"value","icon"}]）
                        final java.util.List<String> listLines = new ArrayList<>();
                        try {
                            if (!fItems.isEmpty()) {
                                org.json.JSONArray arr = new org.json.JSONArray(fItems);
                                for (int i = 0; i < arr.length(); i++) {
                                    Object o = arr.opt(i);
                                    if (o instanceof org.json.JSONObject) {
                                        org.json.JSONObject jo = (org.json.JSONObject) o;
                                        String line = jo.optString("title", jo.optString("label", ""));
                                        String desc = jo.optString("description", jo.optString("value", ""));
                                        String icon = jo.optString("icon", "");
                                        StringBuilder sbLine = new StringBuilder();
                                        if (!icon.isEmpty()) sbLine.append(icon).append(" ");
                                        sbLine.append(line);
                                        if (!desc.isEmpty()) sbLine.append("  ").append(desc);
                                        listLines.add(sbLine.toString());
                                    } else if (o != null) {
                                        listLines.add(String.valueOf(o));
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                        }
                        if (listLines.isEmpty()) {
                            if (fOptions != null && !fOptions.isEmpty()) {
                                try {
                                    org.json.JSONArray arr = new org.json.JSONArray(fOptions);
                                    for (int i = 0; i < arr.length(); i++) {
                                        Object o = arr.opt(i);
                                        if (o != null) listLines.add(String.valueOf(o));
                                    }
                                } catch (Exception ignored2) {
                                }
                            }
                        }
                        if (listLines.isEmpty()) {
                            listLines.add(title != null && !title.isEmpty() ? title : "列表为空");
                        }
                        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(fAct);
                        if (title != null && !title.isEmpty()) b.setTitle(title);
                        b.setItems(listLines.toArray(new String[0]), (d, which) -> {
                            if (which >= 0 && which < listLines.size()) {
                                rt.result.set(listLines.get(which));
                                synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                            }
                            d.dismiss();
                        });
                        b.setPositiveButton("关闭", (d, w) -> {
                            rt.result.compareAndSet("pending", "closed");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                            d.dismiss();
                        });
                        b.setCancelable(true);
                        b.setOnCancelListener(d -> {
                            rt.result.compareAndSet("pending", "closed");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        });
                        android.app.AlertDialog dialog = b.create();
                        dialog.setOnDismissListener(d -> {
                            rt.result.compareAndSet("pending", "closed");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        });
                        dialog.show();
                        rt.dialog = dialog;
                        Log.i(TAG, "[Python component] list created: " + id);
                    } else if ("web".equals(fType)) {
                        // 系统 WebView 富页面弹窗：url=http(s) 地址，或 html 内容（fUrl 为 HTML 字符串）
                        android.webkit.WebView wv = new android.webkit.WebView(fAct);
                        android.webkit.WebSettings ws = wv.getSettings();
                        ws.setJavaScriptEnabled(true);
                        ws.setDomStorageEnabled(true);
                        ws.setLoadWithOverviewMode(true);
                        ws.setUseWideViewPort(false);
                        wv.setWebViewClient(new android.webkit.WebViewClient() {
                            @Override
                            public boolean shouldOverrideUrlLoading(android.webkit.WebView view, String url) {
                                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                                    com.oilquiz.app.ai.chat.component.ComponentActions.openLink(view.getContext(), url);
                                    return true;
                                }
                                return false;
                            }
                        });
                        boolean isHtml = fUrl != null && (fUrl.trim().startsWith("<") || fUrl.trim().startsWith("<!"));
                        if (isHtml) {
                            String full = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>"
                                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>"
                                    + "<style>body{font-family:sans-serif;padding:8px;line-height:1.5;}</style></head><body>"
                                    + fUrl + "</body></html>";
                            wv.loadDataWithBaseURL(null, full, "text/html", "UTF-8", null);
                        } else if (fUrl != null && !fUrl.isEmpty()) {
                            wv.loadUrl(fUrl);
                        } else {
                            rt.result.set("cancelled");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                            return;
                        }
                        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(fAct);
                        if (title != null && !title.isEmpty()) b.setTitle(title);
                        b.setView(wv);
                        b.setPositiveButton("关闭", (d, w) -> {
                            rt.result.compareAndSet("pending", "closed");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                            d.dismiss();
                            wv.destroy();
                        });
                        b.setCancelable(true);
                        b.setOnCancelListener(d -> {
                            rt.result.compareAndSet("pending", "closed");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        });
                        android.app.AlertDialog dialog = b.create();
                        dialog.setOnDismissListener(d -> {
                            rt.result.compareAndSet("pending", "closed");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        });
                        dialog.show();
                        rt.dialog = dialog;
                        Log.i(TAG, "[Python component] web created: " + id);
                    } else {
                        // 系统对话框（info/confirm/warning）
                        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(fAct);
                        if (title != null && !title.isEmpty()) b.setTitle(title);
                        if (message != null && !message.isEmpty()) b.setMessage(message);
                        android.content.DialogInterface.OnClickListener onPositive =
                                (d, w) -> { rt.result.set("positive"); synchronized (rt.resultLock) { rt.resultLock.notifyAll(); } d.dismiss(); };
                        android.content.DialogInterface.OnClickListener onNegative =
                                (d, w) -> { rt.result.set("negative"); synchronized (rt.resultLock) { rt.resultLock.notifyAll(); } d.dismiss(); };
                        String dType = dialogType != null ? dialogType : "info";
                        if ("confirm".equals(dType)) {
                            b.setPositiveButton("确定", onPositive);
                            b.setNegativeButton("取消", onNegative);
                        } else if ("warning".equals(dType)) {
                            b.setPositiveButton("知道了", onPositive);
                        } else {
                            b.setPositiveButton("确定", onPositive);
                        }
                        b.setCancelable(true);
                        b.setOnCancelListener(d -> {
                            rt.result.compareAndSet("pending", "cancelled");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        });
                        android.app.AlertDialog dialog = b.create();
                        dialog.setOnDismissListener(d -> {
                            rt.result.compareAndSet("pending", "cancelled");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        });
                        dialog.show();
                        rt.dialog = dialog;
                        Log.i(TAG, "[Python component] dialog created: " + id);
                    }
                    // 统一自动关闭调度：auto_close 秒后 dismiss（所有系统组件类型通用）
                    scheduleAutoClose(rt, main, autoCloseSeconds);
                } catch (Throwable t) {
                    Log.w(TAG, "创建组件失败: " + t.getMessage());
                    rt.result.set("cancelled");
                    synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                }
            });
            reply.put("success", true);
            reply.put("component_id", id);
            // 兼容两种取法：resp['component_id'] 和 resp['result']['component_id']
            Map<String, Object> resultWrap = new HashMap<>();
            resultWrap.put("component_id", id);
            resultWrap.put("type", type);
            reply.put("result", resultWrap);
            reply.put("message", "组件已创建: " + type + " (" + id + ")");
            return reply;
        }

        /** 自动关闭调度：autoCloseSeconds>0 且组件已显示时，N 秒后 dismiss 并置 result=closed。
         *  必须在主线程调用（createComponent 的 main.post 内）。 */
        private void scheduleAutoClose(ComponentRuntime rt, android.os.Handler main, int autoCloseSeconds) {
            if (autoCloseSeconds <= 0 || rt == null || main == null) return;
            rt.autoCloseHandler = main;
            rt.autoCloseTask = () -> {
                try {
                    if (rt.dialog != null && rt.dialog.isShowing()) {
                        rt.dialog.dismiss();
                    }
                    rt.dialog = null;
                    rt.result.compareAndSet("pending", "closed");
                    synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                    Log.i(TAG, "[Python component] auto closed after " + autoCloseSeconds + "s");
                } catch (Throwable t) {
                    Log.w(TAG, "自动关闭组件失败: " + t.getMessage());
                }
            };
            main.postDelayed(rt.autoCloseTask, autoCloseSeconds * 1000L);
        }

        /** 更新组件：progress 更新进度/消息；dialog 更新标题/内容；notification 更新通知内容/进度。 */        private Map<String, Object> updateComponent(String componentId, String title,
                                                    String message, int progress, int max) {
            Map<String, Object> reply = new HashMap<>();
            ComponentRuntime rt = dynamicComponents.get(componentId);
            if (rt == null) {
                reply.put("success", false);
                reply.put("message", "组件不存在: " + componentId);
                return reply;
            }
            // 通知组件：rt.dialog 为 null，重发通知更新内容/进度
            if (rt.dialog == null) {
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    try {
                        android.app.NotificationManager nm = (android.app.NotificationManager)
                                context.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
                        if (nm == null) return;
                        String channelId = "ai_component_notification";
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                                    channelId, "AI 组件通知", android.app.NotificationManager.IMPORTANCE_DEFAULT);
                            nm.createNotificationChannel(channel);
                        }
                        android.content.Intent intent = new android.content.Intent(context,
                                com.oilquiz.app.ui.activity.AIChatActivity.class);
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(context,
                                (int) (System.currentTimeMillis() % 100000), intent,
                                android.os.Build.VERSION.SDK_INT >= 23
                                        ? android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
                                        : android.app.PendingIntent.FLAG_UPDATE_CURRENT);
                        android.app.Notification.Builder builder = android.os.Build.VERSION.SDK_INT >= 26
                                ? new android.app.Notification.Builder(context, channelId)
                                : new android.app.Notification.Builder(context);
                        builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                                .setContentTitle(title != null && !title.isEmpty() ? title : "AI 通知")
                                .setContentText(message != null ? message : "")
                                .setContentIntent(pi)
                                .setAutoCancel(true);
                        if (max > 0 && progress >= 0) {
                            builder.setProgress(max, progress, false);
                        }
                        nm.notify(componentId.hashCode(), builder.build());
                        if (progress >= 100 && max > 0) {
                            nm.cancel(componentId.hashCode());
                            rt.result.compareAndSet("pending", "completed");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                            dynamicComponents.remove(componentId);
                        }
                        Log.i(TAG, "[Python component] notification updated: " + componentId);
                    } catch (Throwable t) {
                        Log.w(TAG, "更新通知失败: " + t.getMessage());
                    }
                });
                reply.put("success", true);
                reply.put("component_id", componentId);
                reply.put("message", "通知已更新");
                return reply;
            }
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(() -> {
                try {
                    android.app.Dialog d = rt.dialog;
                    if (d instanceof android.app.ProgressDialog) {
                        android.app.ProgressDialog pd = (android.app.ProgressDialog) d;
                        if (max > 0) pd.setMax(max);
                        if (progress >= 0) pd.setProgress(progress);
                        if (title != null && !title.isEmpty()) pd.setTitle(title);
                        if (message != null && !message.isEmpty()) pd.setMessage(message);
                        // 到 max（默认100）自动关闭（保留注册表条目供 get_result 查询 completed）
                        int target = pd.getMax() > 0 ? pd.getMax() : 100;
                        if (progress >= target) {
                            cancelAutoClose(rt);
                            pd.dismiss();
                            rt.dialog = null;
                            rt.result.compareAndSet("pending", "completed");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                            Log.i(TAG, "[Python component] progress auto closed at max: " + componentId);
                        }
                    } else if (d instanceof android.app.AlertDialog) {
                        android.app.AlertDialog ad = (android.app.AlertDialog) d;
                        if (title != null && !title.isEmpty()) ad.setTitle(title);
                        if (message != null && !message.isEmpty()) ad.setMessage(message);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "更新组件失败: " + t.getMessage());
                }
            });
            reply.put("success", true);
            reply.put("component_id", componentId);
            reply.put("message", "组件已更新");
            return reply;
        }

        /** 取消自动关闭定时任务（组件被正常关闭/完成时调用，防止误关后续复用的 rt） */
        private void cancelAutoClose(ComponentRuntime rt) {
            if (rt == null) return;
            try {
                if (rt.autoCloseTask != null && rt.autoCloseHandler != null) {
                    rt.autoCloseHandler.removeCallbacks(rt.autoCloseTask);
                }
                rt.autoCloseTask = null;
                rt.autoCloseHandler = null;
            } catch (Throwable ignored) {
            }
        }

        /** 关闭组件：dismiss 对话框并移除注册表条目。 */
        private Map<String, Object> closeComponent(String componentId) {
            Map<String, Object> reply = new HashMap<>();
            ComponentRuntime rt = dynamicComponents.get(componentId);
            if (rt == null) {
                reply.put("success", false);
                reply.put("message", "组件不存在: " + componentId);
                return reply;
            }
            cancelAutoClose(rt);
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(() -> {
                try {
                    if (rt.dialog != null && rt.dialog.isShowing()) {
                        rt.dialog.dismiss();
                    } else {
                        // 通知组件：cancel 系统通知
                        android.app.NotificationManager nm = (android.app.NotificationManager)
                                context.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
                        if (nm != null) {
                            nm.cancel(componentId.hashCode());
                        }
                    }
                    rt.dialog = null;
                    rt.result.compareAndSet("pending", "cancelled");
                    synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                } catch (Throwable ignored) {
                }
            });
            dynamicComponents.remove(componentId);
            reply.put("success", true);
            reply.put("component_id", componentId);
            reply.put("message", "组件已关闭");
            return reply;
        }

        /** 注册无对话框的待处理组件（聊天流内置组件用），结果由外部回调写入 */
        public boolean registerPendingComponent(String componentId) {
            if (componentId == null || componentId.isEmpty()) return false;
            if (dynamicComponents.containsKey(componentId)) return true;
            dynamicComponents.put(componentId, new ComponentRuntime());
            return true;
        }

        /** 写入组件结果并唤醒等待者（聊天流组件按钮回调） */
        public void setComponentResult(String componentId, String value) {
            ComponentRuntime rt = dynamicComponents.get(componentId);
            if (rt == null) return;
            rt.result.set(value != null ? value : "closed");
            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
        }

        /** 获取组件结果：pending（未点击）/positive/negative/cancelled/completed；wait_seconds>0 时阻塞等待。 */
        private Map<String, Object> getComponentResult(String componentId, int waitSeconds) {
            Map<String, Object> reply = new HashMap<>();
            ComponentRuntime rt = dynamicComponents.get(componentId);
            if (rt == null) {
                reply.put("success", false);
                reply.put("message", "组件不存在: " + componentId);
                reply.put("result", "not_found");
                return reply;
            }
            if (waitSeconds > 0) {
                synchronized (rt.resultLock) {
                    long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
                    while ("pending".equals(rt.result.get()) && System.currentTimeMillis() < deadline) {
                        try {
                            rt.resultLock.wait(Math.max(1, deadline - System.currentTimeMillis()));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            reply.put("success", true);
            reply.put("component_id", componentId);
            reply.put("result", rt.result.get());
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

        /**
         * 弹出真实对话框并阻塞等待用户选择（info=确定；confirm=确定/取消；warning=知道了）。
         * 无前台 Activity 时降级 Toast 并返回 cancelled。
         * @return "positive"（确定）/ "negative"（取消）/ "cancelled"（外部关闭或降级）
         */
        private String showDialog(String title, String message, String dialogType) {
            String dType = dialogType != null ? dialogType : "info";
            String toastText = (title != null && !title.isEmpty() ? title + "：" : "") + message;
            android.app.Activity act = getCurrentActivity();
            if (act == null) {
                Log.w(TAG, "无前台 Activity，对话框降级为 Toast: " + toastText);
                showToast(toastText, true);
                return "cancelled";
            }
            final android.app.Activity fAct = act;
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final String[] result = {"cancelled"};
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(() -> {
                try {
                    if (fAct.isFinishing() || fAct.isDestroyed()) {
                        showToast(toastText, true);
                        latch.countDown();
                        return;
                    }
                    android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(fAct);
                    if (title != null && !title.isEmpty()) b.setTitle(title);
                    if (message != null && !message.isEmpty()) b.setMessage(message);
                    android.content.DialogInterface.OnClickListener onPositive =
                            (d, w) -> { result[0] = "positive"; latch.countDown(); };
                    android.content.DialogInterface.OnClickListener onNegative =
                            (d, w) -> { result[0] = "negative"; latch.countDown(); };
                    switch (dType) {
                        case "confirm":
                            b.setPositiveButton("确定", onPositive);
                            b.setNegativeButton("取消", onNegative);
                            break;
                        case "warning":
                            b.setPositiveButton("知道了", onPositive);
                            break;
                        default:
                            b.setPositiveButton("确定", onPositive);
                            break;
                    }
                    b.setCancelable(true);
                    b.setOnCancelListener(d -> { result[0] = "cancelled"; latch.countDown(); });
                    b.show();
                    Log.i(TAG, "[Python dialog] shown: " + dType + " - " + title);
                } catch (Throwable t) {
                    Log.w(TAG, "弹出对话框失败，降级 Toast: " + t.getMessage());
                    showToast(toastText, true);
                    result[0] = "cancelled";
                    latch.countDown();
                }
            });
            try {
                // 阻塞等待用户点击（Chaquopy 工具调用线程，非主线程，不会卡 UI）；
                // 用户 5 分钟未操作视为取消，避免线程永久挂起
                latch.await(5, java.util.concurrent.TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result[0] = "cancelled";
            }
            Log.i(TAG, "[Python dialog] result=" + result[0]);
            return result[0];
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

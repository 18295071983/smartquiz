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

import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;
public class PythonToolManager {
    private static final String TAG = "PythonToolManager";

    /**
     * Agent 发起的系统选择器（文件/图片/联系人）回调表：requestCode → 结果回调。
     * 由宿主 Activity（AIChatActivity 等）的 onActivityResult 转发到
     * {@link #onAgentPickerResult(int, int, android.content.Intent)}。
     */
    private static final java.util.concurrent.ConcurrentHashMap<Integer, java.util.function.Consumer<android.content.Intent>>
            AGENT_PICKERS = new java.util.concurrent.ConcurrentHashMap<>();

    /** 转发系统选择器结果（宿主 Activity.onActivityResult 调用） */
    public void onAgentPickerResult(int requestCode, int resultCode, android.content.Intent data) {
        java.util.function.Consumer<android.content.Intent> cb = AGENT_PICKERS.remove(requestCode);
        if (cb == null) return;
        cb.accept((resultCode == android.app.Activity.RESULT_OK && data != null) ? data : null);
    }

    /** 预留内部 requestCode 起点（避免与宿主 Activity 业务码冲突） */
    private static final int AGENT_PICKER_REQUEST_BASE = 30000;

    /** 代码执行默认超时（秒）：Python 端 join 超时后返回 TimeoutError */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
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
            // 兜底：若注册回调晚于 Activity resumed（生命周期回调不补发历史），
            // 从 SmartQuizApplication 同步一次当前 Activity，否则 UI 组件全部降级 Toast
            try {
                android.app.Activity a = com.oilquiz.app.SmartQuizApplication.getCurrentActivity();
                if (a != null && !a.isFinishing() && !a.isDestroyed()) {
                    currentActivity = a;
                }
            } catch (Throwable ignored) {
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
    /** UI handler 专用锁：与 this 锁分离，避免渲染主线程在 Python 初始化持锁期间被阻塞（ANR） */
    private final Object uiActionHandlerLock = new Object();

    /** 获取（懒创建）UI 动作处理器，供工具/Agent 直接填参数调用系统 UI 组件。
     *  使用独立锁对象而非 synchronized(this)：Python 初始化在后台线程持有 this 锁并等待主线程时，
     *  渲染主线程调本方法不再被 this 锁阻塞（修复 UI 渲染 ANR）。 */
    public AndroidUiActionHandler getUiActionHandler() {
        if (uiActionHandler == null) {
            synchronized (uiActionHandlerLock) {
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

    /** UI-07 组件生命周期：列出全部存活组件（id/类型/状态） */
    public Map<String, Object> listUiComponents() {
        try {
            Map<String, Object> action = new HashMap<>();
            action.put("type", "list_components");
            return getUiActionHandler().handleMap(action);
        } catch (Throwable t) {
            Map<String, Object> err = new HashMap<>();
            err.put("success", false);
            err.put("message", "列出UI组件失败: " + t.getMessage());
            return err;
        }
    }

    /** 关闭全部动态组件 + 清理临时组件插件/临时类型（Agent 任务/会话结束兜底，防止组件残留卡界面）。线程安全，可任意线程调用。 */
    public void closeAllUiComponents() {
        try {
            AndroidUiActionHandler h = uiActionHandler;
            if (h != null) {
                h.closeAllComponents();
            }
            try {
                com.oilquiz.app.ai.tool.UIComponentPluginManager.getInstance(context)
                        .clearTemporaryPlugins();
            } catch (Throwable ignored) {
            }
            try {
                com.oilquiz.app.ai.tool.UIComponentTypeRegistry.getInstance(context)
                        .clearTemporaryTypes();
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            Log.e(TAG, "关闭全部UI组件失败: " + t.getMessage(), t);
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
     * 创建语音合成专用组件（原生播放对话框）：
     * 自动朗读 text，用户可点"停止"中断；播放完成 → result="completed"，
     * 停止 → "stopped"。供 speech_synthesis 工具播放时让用户可见/可控。
     */
    public Map<String, Object> createSpeechPlayerComponent(String componentId, String title,
                                                           String text, int autoCloseSeconds) {
        Map<String, Object> action = new HashMap<>();
        action.put("type", "create_component");
        action.put("component_type", "speech_player");
        action.put("component_id", componentId != null ? componentId : "");
        action.put("title", title != null ? title : "🔊 正在朗读");
        action.put("message", text != null ? text : "");
        if (autoCloseSeconds > 0) {
            action.put("auto_close", autoCloseSeconds);
        }
        return getUiActionHandler().handleMap(action);
    }

    /**
     * 创建语音识别专用组件（原生录音对话框）：
     * 自动开始录音，用户点"完成"或 autoClose 到点后停止并保存音频，
     * 后续 getUiComponentResult 返回音频文件路径（供 voice_input recognize）。
     */
    public Map<String, Object> createVoiceRecorderComponent(String componentId, String title,
                                                           String message, int autoCloseSeconds) {
        Map<String, Object> action = new HashMap<>();
        action.put("type", "create_component");
        action.put("component_type", "voice_recorder");
        action.put("component_id", componentId != null ? componentId : "");
        action.put("title", title != null ? title : "🎤 请说话");
        action.put("message", message != null ? message : "");
        if (autoCloseSeconds > 0) {
            action.put("auto_close", autoCloseSeconds);
        }
        return getUiActionHandler().handleMap(action);
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
            // 注意：必须注入 getUiActionHandler() 同一实例——若 new 一个独立 handler，
            // Python 创建的组件与工具侧 ui_component(get_result) 是两个注册表互不相通（Bug2）
            try {
                PyObject uiModule = python.getModule("android_ui");
                if (uiModule != null) {
                    uiModule.callAttr("set_ui_callback",
                            PyObject.fromJava(getUiActionHandler()));
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
        return executeCode(code, contextData, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * 直接执行 Python 代码（带超时与上下文透传）
     *
     * @param timeoutSeconds 超时秒数；Python 端工作线程 join 超时后返回 TimeoutError。
     *                       超时后的 daemon 线程无法强杀，调用方应避免频繁超时。
     * @param contextData    上下文数据（以 dict 形式传给 Python 端，脚本内可用；null 则不传）
     */
    public ExecutionResult executeCode(String code, Map<String, Object> contextData, int timeoutSeconds) {
        if (!initialized) {
            if (!initialize()) {
                return new ExecutionResult(false, null, "Python tool manager not initialized");
            }
        }
        
        try {
            Log.i(TAG, "Executing code directly (" + code.length() + " chars, timeout=" + timeoutSeconds
                    + "s, ctx=" + (contextData != null ? contextData.size() : 0) + ")");
            
            PyObject result;
            if (contextData != null && !contextData.isEmpty()) {
                // 真正透传 contextData（此前被丢弃）：Python 端 run_code(code, timeout, context)
                result = aiPythonTool.callAttr("run_code", code, timeoutSeconds,
                        PyObject.fromJava(contextData));
            } else {
                result = aiPythonTool.callAttr("run_code", code, timeoutSeconds);
            }
            
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
            /** 组件创建类型（createComponent 时记录；聊天流卡片标记 chat_card，notification 标记 notification） */
            volatile String createType;
            /** 创建参数快照（update 传 props 真重建时：按原参数 + 新 props 重新 createComponent，保留 component_id） */
            final java.util.Map<String, Object> createArgs = new java.util.concurrent.ConcurrentHashMap<>();
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
                int current = 0, total = 0, max = 100, progress = -1, waitSeconds = 0;
                String options = "", defaultValue = "", inputHint = "", props = "", actionLabel = "";
                String items = "", url = "", clickAction = "", html = "";
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
                            case "html": html = v; break;
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
                                items, url, clickAction, autoClose, html));
                        break;
                    case "update_component":
                        reply.putAll(updateComponent(componentId, title, message, progress, max, props));
                        break;
                    case "close_component":
                        reply.putAll(closeComponent(componentId));
                        break;
                    case "list_components":
                        reply.putAll(listComponents());
                        break;
                    case "close_all_components":
                        closeAllComponents();
                        reply.put("success", true);
                        reply.put("result", "closed_all");
                        reply.put("message", "已关闭全部组件");
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
                                                    int autoCloseSeconds, String html) {
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
            // 记录创建类型与参数快照（update 传 props 真重建时使用，保留 component_id）
            rt.createType = type;
            java.util.Map<String, Object> createArgs = new java.util.HashMap<>();
            createArgs.put("title", title == null ? "" : title);
            createArgs.put("message", message == null ? "" : message);
            createArgs.put("dialog_type", dialogType == null ? "info" : dialogType);
            createArgs.put("max", max);
            createArgs.put("options", options == null ? "" : options);
            createArgs.put("default_value", defaultValue == null ? "" : defaultValue);
            createArgs.put("input_hint", inputHint == null ? "" : inputHint);
            createArgs.put("action_label", actionLabel == null ? "" : actionLabel);
            createArgs.put("items", items == null ? "" : items);
            createArgs.put("url", url == null ? "" : url);
            createArgs.put("click_action", clickAction == null ? "" : clickAction);
            createArgs.put("auto_close", autoCloseSeconds);
            createArgs.put("html", html == null ? "" : html);
            createArgs.put("props", props == null ? "" : props);
            rt.createArgs.putAll(createArgs);

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
            final String fHtml = html != null ? html : "";
            main.post(() -> {
                try {
                    if (fAct.isFinishing() || fAct.isDestroyed()) {
                        rt.result.set("cancelled");
                        synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        return;
                    }
                    // 组件插件：Agent 动态注册的自定义组件类型（ui_component_plugin create）
                    if (com.oilquiz.app.ai.tool.UIComponentPluginManager
                            .getInstance(context).hasPlugin(fType)) {
                        showPluginComponentDialog(fAct, rt, id, title, message, fProps, fType, autoCloseSeconds);
                        return;
                    }
                    // 自定义注册类型（ui_component action=register_type）：render/monitor 定义在 props 内
                    if (fProps.contains("__type_render")) {
                        try {
                            org.json.JSONObject pj = new org.json.JSONObject(fProps);
                            if (pj.has("__type_render")) {
                                org.json.JSONObject tmpPlugin = new org.json.JSONObject();
                                tmpPlugin.put("name", fType);
                                tmpPlugin.put("description", "自定义注册类型");
                                tmpPlugin.put("render", new org.json.JSONObject(pj.optString("__type_render", "{}")));
                                if (pj.has("__type_monitor")) {
                                    tmpPlugin.put("monitor", new org.json.JSONObject(pj.optString("__type_monitor", "{}")));
                                }
                                pj.remove("__type_render");
                                pj.remove("__type_monitor");
                                showPluginComponentDialog(fAct, rt, id, title, message,
                                        pj.toString(), fType, autoCloseSeconds, tmpPlugin);
                                return;
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "自定义注册类型解析失败: " + e.getMessage());
                        }
                    }
                    // 语音识别专用组件：原生录音对话框（提示用户说话 → 录音 → 完成后返回音频文件路径）
                    if ("voice_recorder".equals(fType)) {
                        showVoiceRecorderDialog(fAct, rt, id, title, message, autoCloseSeconds);
                        return;
                    }
                    // 语音合成专用组件：原生播放对话框（朗读文本 + 停止按钮，播放完成/停止后返回状态）
                    if ("speech_player".equals(fType)) {
                        showSpeechPlayerDialog(fAct, rt, id, title, message, autoCloseSeconds);
                        return;
                    }
                    // 内置 UI 组件（ComponentRegistry 已注册类型：chart/info_card/table_card/... 22 种）：
                    // 用项目自带组件库渲染成真实 View，弹窗展示
                    com.oilquiz.app.ai.chat.component.ComponentRegistry registry =
                            com.oilquiz.app.ai.chat.component.ComponentRegistry.getInstance();
                    // web/image 别名映射（与 ui_component 工具一致）：web→html 卡片、image→image_grid 卡片
                    String renderType = fType;
                    org.json.JSONObject propsObj = new org.json.JSONObject();
                    if (!fProps.isEmpty()) {
                        try {
                            propsObj = new org.json.JSONObject(fProps);
                        } catch (Exception e) {
                            Log.w(TAG, "内置组件 props 解析失败，使用空 props: " + e.getMessage());
                        }
                    }
                    if ("web".equals(fType)) {
                        renderType = "html";
                        // url 参数 → url 字段（WebView 直接加载网页/本地文件）
                        if (!propsObj.has("html") && !propsObj.has("url") && !fUrl.isEmpty()) {
                            String u = fUrl.trim();
                            if (u.startsWith("http://") || u.startsWith("https://")) {
                                propsObj.put("url", u);
                            } else if (isLocalFilePath(u)) {
                                // 本地网页文件：转 file:// 由 WebView 直接加载渲染（不当 HTML 字符串显示源码）
                                propsObj.put("url", u.startsWith("file://") ? u : "file://" + u);
                            } else {
                                propsObj.put("html", u);
                            }
                        }
                        // 顶层 message/html/content 参数 → html 字段（模型常把 HTML 内容放
                        // message/html/content 顶层参数而非 props 内，否则 HtmlCardView 取不到
                        // 内容显示"未获取到组件内容"）
                        if (!propsObj.has("html") && !propsObj.has("url")) {
                            String topContent = !fHtml.trim().isEmpty() ? fHtml : message;
                            if (topContent != null && !topContent.trim().isEmpty()) {
                                propsObj.put("html", topContent);
                            }
                        }
                    } else if ("image".equals(fType)) {
                        renderType = "image_grid";
                        if (!propsObj.has("images") && !fDefault.isEmpty()) {
                            propsObj.put("images", new org.json.JSONArray().put(fDefault));
                        }
                    } else if ("video".equals(fType) || "audio".equals(fType)) {
                        // 媒体组件：顶层 url/src/default_value → props.url（VideoCardView/AudioCardView 取 url）
                        renderType = fType;
                        if (!propsObj.has("url")) {
                            String mediaUrl = !fUrl.trim().isEmpty() ? fUrl.trim()
                                    : (!fDefault.isEmpty() ? fDefault.trim() : "");
                            if (!mediaUrl.isEmpty()) {
                                propsObj.put("url", mediaUrl);
                            }
                        }
                    }
                    if (registry.hasType(renderType)) {
                        // 未显式传 title 时用组件的 title 参数
                        if (title != null && !title.isEmpty() && !propsObj.has("title")) {
                            propsObj.put("title", title);
                        }
                        // 交互支持：给每个 action 注入 component_id（action=callback 时用）。
                        // 否则 Python/模型路径渲染的按钮无 component_id，点击落入 execute() 无回调，
                        // get_result 只能等满超时（Bug2）
                        org.json.JSONArray actionsArr = propsObj.optJSONArray("actions");
                        if (actionsArr != null) {
                            for (int i = 0; i < actionsArr.length(); i++) {
                                org.json.JSONObject a = actionsArr.optJSONObject(i);
                                if (a != null) {
                                    a.put("component_id", id);
                                    if (!a.has("action")) a.put("action", "callback");
                                }
                            }
                        }
                        com.oilquiz.app.ai.chat.component.ComponentData data =
                                new com.oilquiz.app.ai.chat.component.ComponentData(renderType, propsObj);
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
                        setupScrollViewForInput(scroll);
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
                        // UI-06 弹窗外观：窗口背景圆角（消除默认方形白框；卡片圆角已生效时外层不再露方角）
                        try {
                            if (dialog.getWindow() != null) {
                                android.graphics.drawable.GradientDrawable dlgBg =
                                        new android.graphics.drawable.GradientDrawable();
                                dlgBg.setColor(0xFFFFFFFF);
                                dlgBg.setCornerRadius(16 * fAct.getResources().getDisplayMetrics().density);
                                dialog.getWindow().setBackgroundDrawable(dlgBg);
                            }
                        } catch (Throwable ignored) {
                        }
                        configureDialogInput(dialog);

                        dialog.show();
                        rt.dialog = dialog;
                        scheduleAutoClose(rt, main, autoCloseSeconds);
                        Log.i(TAG, "[Python component] builtin UI component shown: " + renderType + " (" + id + ")");
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
                        configureDialogInput(dialog);

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
                        final int[] selectedIndex = {-1};
                        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(fAct);
                        // 注意：不能同时 setMessage + setItems（AlertDialog 中 message 会占据内容区，
                        // 导致选项列表不显示，实测 choice 只显示确认/取消）——message 并入 title 展示
                        StringBuilder titleText = new StringBuilder();
                        if (title != null && !title.isEmpty()) titleText.append(title);
                        if (message != null && !message.isEmpty()) {
                            if (titleText.length() > 0) titleText.append("\n");
                            titleText.append(message);
                        }
                        if (titleText.length() > 0) b.setTitle(titleText.toString());
                        if (choices.isEmpty()) {
                            choices.add("确定");
                        }
                        // setSingleChoiceItems：单选钮选中反馈（普通 setItems 点选无视觉反馈）
                        b.setSingleChoiceItems(choices.toArray(new String[0]), selectedIndex[0],
                                (d, which) -> {
                                    if (which >= 0 && which < choices.size()) {
                                        selectedIndex[0] = which;
                                    }
                                });
                        b.setPositiveButton("确定", (d, w) -> {
                            rt.result.set(selectedIndex[0] >= 0 && selectedIndex[0] < choices.size()
                                    ? choices.get(selectedIndex[0]) : "cancelled");
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
                        configureDialogInput(dialog);

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
                        configureDialogInput(dialog);

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
                        configureDialogInput(dialog);

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
                        configureDialogInput(dialog);

                        dialog.show();
                        rt.dialog = dialog;
                        Log.i(TAG, "[Python component] list created: " + id);
                    } else if ("custom".equals(fType)) {
                        // 动态自定义原生表单：fields JSON 定义任意字段
                        // rounds=N（props 或顶层，N>1）→ 连续输入模式：多轮提交，完成返回 {"rounds":[...],"total":N}
                        int rounds = 0;
                        try {
                            org.json.JSONObject pjR = new org.json.JSONObject(fProps.isEmpty() ? "{}" : fProps);
                            rounds = pjR.optInt("rounds", 0);
                        } catch (Exception ignored) {
                        }
                        showCustomFormDialog(fAct, rt, id, title, message, fProps, autoCloseSeconds, rounds);
                    } else if ("layout_canvas".equals(fType)) {
                        // 常驻布局画布：渲染 layout 树，Agent 可用 layout_editor 工具动态编辑(set/add/patch/get)
                        showLayoutCanvasDialog(fAct, rt, id, title, fProps, fType, autoCloseSeconds);
                    } else if ("file_picker".equals(fType) || "image_picker".equals(fType)
                            || "contact_picker".equals(fType)) {
                        // 系统级选择器：文件/图片/联系人
                        if ("contact_picker".equals(fType)) {
                            launchAgentPicker(fAct, rt, id, title, "contacts");
                        } else {
                            launchAgentPicker(fAct, rt, id, title,
                                    "image_picker".equals(fType) ? "image/*" : "*/*");
                        }
                    } else if ("rating".equals(fType)) {
                        showRatingDialog(fAct, rt, id, title, message, fDefault);
                    } else if ("color".equals(fType)) {
                        showColorDialog(fAct, rt, id, title, message);
                    } else if ("otp".equals(fType) || "number".equals(fType)) {
                        showOtpOrNumberDialog(fAct, rt, id, title, message, fProps, fDefault,
                                "otp".equals(fType));
                    } else if ("marquee".equals(fType)) {
                        showMarqueeDialog(fAct, rt, id, title, message, fProps, autoCloseSeconds);
                    } else if ("media_task".equals(fType)) {
                        showMediaTaskDialog(fAct, rt, id, title, message, fProps, autoCloseSeconds);
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
                        configureDialogInput(dialog);

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
                        configureDialogInput(dialog);

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

        /** 启动系统选择器（文件/图片/联系人）：结果经宿主 Activity.onActivityResult 转发回填 rt.result */
        private void launchAgentPicker(final android.app.Activity act, final ComponentRuntime rt,
                                       final String componentId, final String title, final String kind) {
            if (act == null || act.isFinishing()) {
                rt.result.set("cancelled:无前台Activity");
                synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                return;
            }
            final int requestCode = PythonToolManager.AGENT_PICKER_REQUEST_BASE
                    + (int) (Math.random() * 10000);
            PythonToolManager.AGENT_PICKERS.put(requestCode, data -> {
                if (data == null) {
                    rt.result.set("cancelled");
                } else {
                    android.net.Uri uri = data.getData();
                    if (uri == null) {
                        rt.result.set("cancelled");
                    } else if ("contacts".equals(kind)) {
                        rt.result.set(queryContact(act, uri));
                    } else {
                        try {
                            act.getContentResolver().takePersistableUriPermission(uri,
                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Exception ignored) {
                        }
                        rt.result.set(uri.toString());
                    }
                }
                synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
            });
            android.content.Intent intent;
            if ("contacts".equals(kind)) {
                intent = new android.content.Intent(android.content.Intent.ACTION_PICK,
                        android.provider.ContactsContract.Contacts.CONTENT_URI);
            } else {
                intent = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
                intent.setType(kind);
                intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            }
            try {
                act.startActivityForResult(intent, requestCode);
            } catch (Throwable t) {
                PythonToolManager.AGENT_PICKERS.remove(requestCode);
                rt.result.set("cancelled:无法打开选择器");
                synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
            }
            Log.i(TAG, "[Python component] picker launched: " + kind + " (" + componentId + ")");
        }

        /** 查询联系人姓名+电话，返回 JSON 字符串 */
        private String queryContact(android.app.Activity act, android.net.Uri uri) {
            String name = "", phone = "", id = "";
            try (android.database.Cursor c = act.getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int ni = c.getColumnIndex(android.provider.ContactsContract.Contacts.DISPLAY_NAME);
                    if (ni >= 0) name = c.getString(ni);
                    int ii = c.getColumnIndex(android.provider.ContactsContract.Contacts._ID);
                    if (ii >= 0) id = c.getString(ii);
                }
            } catch (Exception ignored) {
            }
            if (!id.isEmpty()) {
                try (android.database.Cursor pc = act.getContentResolver().query(
                        android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null,
                        android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID + "=?",
                        new String[]{id}, null)) {
                    if (pc != null && pc.moveToFirst()) {
                        int pi = pc.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER);
                        if (pi >= 0) phone = pc.getString(pi);
                    }
                } catch (Exception ignored) {
                }
            }
            org.json.JSONObject jo = new org.json.JSONObject();
            try {
                jo.put("name", name);
                jo.put("phone", phone);
                jo.put("uri", uri.toString());
            } catch (Exception ignored) {
            }
            return jo.toString();
        }

        /** 星级评分：1-5 星，确定返回分数（数字字符串），取消 → cancelled */
        private void showRatingDialog(final android.app.Activity act, final ComponentRuntime rt,
                                      final String componentId, final String title,
                                      final String message, final String defValue) {
            final float density = act.getResources().getDisplayMetrics().density;
            final int[] score = {-1};
            android.widget.LinearLayout starRow = new android.widget.LinearLayout(act);
            starRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            starRow.setGravity(android.view.Gravity.CENTER);
            final android.widget.TextView[] stars = new android.widget.TextView[5];
            final int emptyStar = com.oilquiz.app.ai.chat.component.ComponentColors.ratingEmpty(act);
            final int filledStar = com.oilquiz.app.ai.chat.component.ComponentColors.warning(act);
            for (int si = 0; si < 5; si++) {
                final int idx = si;
                android.widget.TextView star = new android.widget.TextView(act);
                star.setText("★");
                star.setTextSize(34);
                star.setTextColor(emptyStar);
                star.setPadding((int) (6 * density), (int) (6 * density), (int) (6 * density), (int) (6 * density));
                star.setOnClickListener(v -> {
                    score[0] = idx + 1;
                    for (int k = 0; k < 5; k++) {
                        stars[k].setTextColor(k <= idx ? filledStar : emptyStar);
                    }
                });
                stars[si] = star;
                starRow.addView(star);
            }
            int defScore = -1;
            try {
                defScore = Integer.parseInt(defValue.trim());
            } catch (Exception ignored) {
            }
            if (defScore >= 1 && defScore <= 5) {
                score[0] = defScore;
                for (int k = 0; k < defScore; k++) stars[k].setTextColor(filledStar);
            }
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(act);
            if (title != null && !title.isEmpty()) b.setTitle(title);
            if (message != null && !message.isEmpty()) b.setMessage(message);
            b.setView(starRow);
            b.setPositiveButton("确定", (d, w) -> {
                rt.result.set(score[0] > 0 ? String.valueOf(score[0]) : "cancelled");
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
            configureDialogInput(dialog);

            dialog.show();
            rt.dialog = dialog;
            Log.i(TAG, "[Python component] rating created: " + componentId);
        }

        /** 取色器：预设色板，确定返回 #RRGGBB，取消 → cancelled */
        private void showColorDialog(final android.app.Activity act, final ComponentRuntime rt,
                                     final String componentId, final String title,
                                     final String message) {
            final float density = act.getResources().getDisplayMetrics().density;
            final String[] palette = {"#EF4444", "#F97316", "#F59E0B", "#EAB308", "#84CC16",
                    "#10B981", "#14B8A6", "#06B6D4", "#3B82F6", "#6366F1",
                    "#8B5CF6", "#A855F7", "#D946EF", "#EC4899", "#F43F5E"};
            final String[] picked = {null};
            android.widget.LinearLayout grid = new android.widget.LinearLayout(act);
            grid.setOrientation(android.widget.LinearLayout.VERTICAL);
            for (int row = 0; row < 3; row++) {
                android.widget.LinearLayout rowL = new android.widget.LinearLayout(act);
                rowL.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                for (int col = 0; col < 5; col++) {
                    int idx = row * 5 + col;
                    if (idx >= palette.length) break;
                    final String color = palette[idx];
                    android.view.View sw = new android.view.View(act);
                    android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                            (int) (44 * density), (int) (44 * density));
                    lp.setMargins((int) (4 * density), (int) (4 * density), (int) (4 * density), (int) (4 * density));
                    sw.setLayoutParams(lp);
                    sw.setBackgroundColor(parseColorSafe(color, ThemeColors.get(R.color.hc_ff374151)));
                    sw.setOnClickListener(v -> {
                        picked[0] = color;
                        for (int i = 0; i < grid.getChildCount(); i++) {
                            android.view.ViewGroup g = (android.view.ViewGroup) grid.getChildAt(i);
                            for (int j = 0; j < g.getChildCount(); j++) {
                                g.getChildAt(j).setPadding(0, 0, 0, 0);
                            }
                        }
                        sw.setPadding((int) (3 * density), (int) (3 * density), (int) (3 * density), (int) (3 * density));
                    });
                    rowL.addView(sw);
                }
                grid.addView(rowL);
            }
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(act);
            if (title != null && !title.isEmpty()) b.setTitle(title);
            if (message != null && !message.isEmpty()) b.setMessage(message);
            b.setView(grid);
            b.setPositiveButton("确定", (d, w) -> {
                rt.result.set(picked[0] != null ? picked[0] : "cancelled");
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
            configureDialogInput(dialog);

            dialog.show();
            rt.dialog = dialog;
            Log.i(TAG, "[Python component] color created: " + componentId);
        }

        private int parseColorSafe(String hex, int def) {
            try {
                if (hex != null && hex.startsWith("#") && hex.length() >= 7) {
                    return android.graphics.Color.parseColor(hex);
                }
            } catch (Exception ignored) {
            }
            return def;
        }

        /** 验证码输入（otp：数字串，可选长度校验）/ 数字输入（number：范围校验 min/max） */
        private void showOtpOrNumberDialog(final android.app.Activity act, final ComponentRuntime rt,
                                           final String componentId, final String title,
                                           final String message, final String propsJson,
                                           final String defValue, final boolean isOtp) {
            final float density = act.getResources().getDisplayMetrics().density;
            final android.widget.EditText input = new android.widget.EditText(act);
            input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                    | (isOtp ? 0 : android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED));
            int len = 6;
            double rangeMin = 0, rangeMax = 0;
            boolean hasRange = false;
            try {
                org.json.JSONObject pj = new org.json.JSONObject(propsJson != null ? propsJson : "");
                len = pj.optInt("length", 6);
                if (pj.has("min") && pj.has("max")) {
                    rangeMin = pj.optDouble("min");
                    rangeMax = pj.optDouble("max");
                    hasRange = true;
                }
            } catch (Exception ignored) {
            }
            input.setHint(isOtp ? ("请输入" + len + "位验证码") : "请输入数值");
            if (defValue != null && !defValue.isEmpty()) input.setText(defValue);
            final int fLen = Math.max(1, Math.min(12, len));
            final double fMin = rangeMin, fMax = rangeMax;
            final boolean fRange = hasRange;
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(act);
            if (title != null && !title.isEmpty()) b.setTitle(title);
            if (message != null && !message.isEmpty()) b.setMessage(message);
            b.setView(input);
            b.setPositiveButton("确定", null);
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
            dialog.setOnShowListener(d -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> {
                        String val = input.getText() != null ? input.getText().toString().trim() : "";
                        if (isOtp) {
                            if (val.length() != fLen || !val.matches("\\d+")) {
                                showToast("请输入" + fLen + "位数字验证码", false);
                                return;
                            }
                        } else {
                            try {
                                double num = Double.parseDouble(val);
                                if (fRange && (num < fMin || num > fMax)) {
                                    showToast("数值需在 " + fMin + " ~ " + fMax + " 之间", false);
                                    return;
                                }
                            } catch (NumberFormatException ex) {
                                showToast("请输入有效数字", false);
                                return;
                            }
                        }
                        rt.result.set(val);
                        synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        dialog.dismiss();
                    }));
            configureDialogInput(dialog);

            dialog.show();
            rt.dialog = dialog;
            Log.i(TAG, "[Python component] " + (isOtp ? "otp" : "number") + " created: " + componentId);
        }

        /** 跑马灯组件（type=marquee）：单行文字自动水平滚动。props: text/speed/bold/size/color/repeat */
        private void showMarqueeDialog(final android.app.Activity act, final ComponentRuntime rt,
                                       final String componentId, final String title,
                                       final String message, final String propsJson,
                                       final int autoCloseSeconds) {
            final float density = act.getResources().getDisplayMetrics().density;
            String text = message != null ? message : "";
            int speed = 1, size = 16;
            boolean bold = false;
            String color = "";
            try {
                org.json.JSONObject pj = new org.json.JSONObject(propsJson != null && !propsJson.isEmpty()
                        ? propsJson : "{}");
                text = pj.optString("text", text);
                speed = Math.max(0, Math.min(3, pj.optInt("speed", 1)));
                size = Math.max(10, Math.min(30, pj.optInt("size", 16)));
                bold = pj.optBoolean("bold", false);
                color = pj.optString("color", "");
            } catch (Exception ignored) {
            }
            if (text.isEmpty()) {
                // 兜底：text 为空时用 title 顶替，避免空白跑马灯
                text = (title != null && !title.trim().isEmpty()) ? title : " ";
            }
            final String fText = text;
            android.widget.TextView tv = new android.widget.TextView(act);
            tv.setText(fText);
            tv.setTextSize(size);
            tv.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD
                    : android.graphics.Typeface.DEFAULT);
            tv.setTextColor(parseColorSafe(color, ThemeColors.get(R.color.hc_ffdc2626)));
            tv.setPadding((int) (6 * density), (int) (8 * density), (int) (6 * density), (int) (8 * density));
            // 跑马灯滚动：位移动画实现（不依赖系统 marquee 焦点机制，Dialog 内也能滚动）；
            // speed 0=不滚动 1=慢 2=中 3=快，无限循环
            com.oilquiz.app.ai.python.NativeLayoutRenderer.startMarquee(tv, fText, speed, -1);
            android.widget.LinearLayout box = new android.widget.LinearLayout(act);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            box.setPadding((int) (12 * density), (int) (6 * density), (int) (12 * density), (int) (6 * density));
            box.addView(tv);
            android.widget.TextView sub = new android.widget.TextView(act);
            sub.setText(fText);
            sub.setTextSize(12);
            sub.setTextColor(com.oilquiz.app.ai.chat.component.ComponentColors.textTertiary(act));
            sub.setPadding(0, (int) (6 * density), 0, 0);
            box.addView(sub);
            final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(act)
                    .setTitle(title != null && !title.isEmpty() ? title : "📢 滚动公告")
                    .setView(box)
                    .setPositiveButton("关闭", (d, w) -> {
                        rt.result.compareAndSet("pending", "closed");
                        synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        d.dismiss();
                    })
                    .setCancelable(true)
                    .setOnCancelListener(d -> {
                        rt.result.compareAndSet("pending", "closed");
                        synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                    })
                    .create();
            rt.dialog = dialog;
            dialog.setOnDismissListener(d -> {
                rt.result.compareAndSet("pending", "closed");
                synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
            });
            configureDialogInput(dialog);

            dialog.show();
            if (autoCloseSeconds > 0) {
                final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.postDelayed(() -> {
                    if (rt.dialog != null && rt.dialog.isShowing()) {
                        rt.dialog.dismiss();
                    }
                }, autoCloseSeconds * 1000L);
            }
            Log.i(TAG, "[Python component] marquee created: " + componentId);
        }

        /** 文生图/文生视频任务监控组件：自动轮询查询任务状态，完成后展示图片/视频 */
        private void showMediaTaskDialog(final android.app.Activity act, final ComponentRuntime rt,
                                         final String componentId, final String title,
                                         final String message, final String propsJson,
                                         final int autoCloseSeconds) {
            final float density = act.getResources().getDisplayMetrics().density;
            final String[] taskIdRef = {""};
            final String[] typeRef = {"video"};
            final String[] apiUrlRef = {""};
            final String[] apiKeyRef = {""};
            final int[] pollSeconds = {5};
            try {
                org.json.JSONObject pj = new org.json.JSONObject(propsJson != null ? propsJson : "{}");
                taskIdRef[0] = pj.optString("task_id", "");
                typeRef[0] = pj.optString("type", "video");
                apiUrlRef[0] = pj.optString("api_url", "");
                apiKeyRef[0] = pj.optString("api_key", "");
                pollSeconds[0] = Math.max(2, Math.min(30, pj.optInt("poll_seconds", 5)));
            } catch (Exception ignored) {
            }
            if (taskIdRef[0].isEmpty()) {
                rt.result.set("failed:缺少 task_id（需先提交任务拿到 task_id）");
                synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                return;
            }
            final String fType = typeRef[0];
            android.widget.TextView statusView = new android.widget.TextView(act);
            statusView.setTextSize(14);
            statusView.setPadding(0, (int) (6 * density), 0, (int) (6 * density));
            statusView.setText("⏳ 任务处理中…");
            android.widget.ImageView imgView = new android.widget.ImageView(act);
            imgView.setVisibility(android.view.View.GONE);
            imgView.setAdjustViewBounds(true);
            imgView.setMaxHeight((int) (320 * density));
            android.widget.TextView videoInfo = new android.widget.TextView(act);
            videoInfo.setVisibility(android.view.View.GONE);
            videoInfo.setTextSize(13);
            videoInfo.setPadding(0, (int) (4 * density), 0, (int) (4 * density));
            android.widget.Button btnPlay = new android.widget.Button(act);
            btnPlay.setText("▶ 播放 / 查看");
            btnPlay.setVisibility(android.view.View.GONE);
            android.widget.Button btnShare = new android.widget.Button(act);
            btnShare.setText("📤 分享");
            btnShare.setVisibility(android.view.View.GONE);
            android.widget.LinearLayout layout = new android.widget.LinearLayout(act);
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            layout.setPadding((int) (16 * density), (int) (8 * density), (int) (16 * density), (int) (8 * density));
            layout.addView(statusView);
            layout.addView(imgView);
            layout.addView(videoInfo);
            android.widget.LinearLayout btnRow = new android.widget.LinearLayout(act);
            btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            btnRow.addView(btnPlay, new android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            btnRow.addView(btnShare, new android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            layout.addView(btnRow);

            final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            final boolean[] done = {false};
            final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(act)
                    .setTitle(title != null && !title.isEmpty() ? title
                            : ("image".equals(fType) ? "🖼️ 文生图任务" : "🎬 文生视频任务"))
                    .setMessage(message != null && !message.isEmpty() ? message : "任务ID: " + taskIdRef[0])
                    .setView(layout)
                    .setPositiveButton("关闭", (d, w) -> {
                        rt.result.compareAndSet("pending", "closed");
                        synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        d.dismiss();
                    })
                    .setCancelable(true)
                    .setOnCancelListener(d -> {
                        rt.result.compareAndSet("pending", "closed");
                        synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                    })
                    .create();
            rt.dialog = dialog;
            dialog.setOnDismissListener(d -> {
                done[0] = true;
                rt.result.compareAndSet("pending", "closed");
                synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
            });
            configureDialogInput(dialog);

            dialog.show();
            final String[] resultFilePath = {null};
            final Runnable[] poller = new Runnable[1];

            final java.util.function.Consumer<String> finisher = new java.util.function.Consumer<String>() {
                @Override
                public void accept(String resultJson) {
                    if (done[0]) return;
                    done[0] = true;
                    rt.result.set(resultJson);
                    synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                }
            };

            final Runnable[] queryOnce = new Runnable[1];
            queryOnce[0] = () -> {
                if (done[0]) return;
                java.util.Map<String, Object> p = new HashMap<>();
                p.put("action", "query");
                p.put("task_id", taskIdRef[0]);
                p.put("type", fType);
                if (apiUrlRef[0] != null && !apiUrlRef[0].isEmpty()) p.put("api_url", apiUrlRef[0]);
                if (apiKeyRef[0] != null && !apiKeyRef[0].isEmpty()) p.put("api_key", apiKeyRef[0]);
                new Thread(() -> {
                    try {
                        com.oilquiz.app.ai.tool.DashscopeMediaTool tool =
                                new com.oilquiz.app.ai.tool.DashscopeMediaTool(context);
                        com.oilquiz.app.ai.tool.AIToolResult r = tool.execute(p);
                        handler.post(() -> {
                            if (done[0]) return;
                            if (r != null && r.isSuccess() && r.getResult() instanceof Map) {
                                @SuppressWarnings("unchecked")
                                java.util.Map<String, Object> res =
                                        (java.util.Map<String, Object>) r.getResult();
                                Object filePath = res.get("filePath");
                                if (filePath != null) {
                                    resultFilePath[0] = String.valueOf(filePath);
                                    String ext = resultFilePath[0].toLowerCase();
                                    if (ext.endsWith(".png") || ext.endsWith(".jpg") || ext.endsWith(".jpeg")
                                            || ext.endsWith(".webp") || ext.endsWith(".gif")) {
                                        imgView.setVisibility(android.view.View.VISIBLE);
                                        com.bumptech.glide.Glide.with(act)
                                                .load(new java.io.File(resultFilePath[0])).into(imgView);
                                    } else {
                                        videoInfo.setVisibility(android.view.View.VISIBLE);
                                        java.io.File f = new java.io.File(resultFilePath[0]);
                                        videoInfo.setText("🎬 " + f.getName() + "\n"
                                                + formatFileSize(f.length()) + "\n" + resultFilePath[0]);
                                    }
                                    btnPlay.setVisibility(android.view.View.VISIBLE);
                                    btnShare.setVisibility(android.view.View.VISIBLE);
                                    statusView.setText("✅ " + ("image".equals(fType) ? "图片" : "视频") + "已生成");
                                    org.json.JSONObject ok = new org.json.JSONObject();
                                    try {
                                        ok.put("status", "success");
                                        ok.put("task_id", taskIdRef[0]);
                                        ok.put("type", fType);
                                        ok.put("filePath", resultFilePath[0]);
                                    } catch (Exception ignored) {
                                    }
                                    finisher.accept(ok.toString());
                                    return;
                                }
                                Object st = res.get("status");
                                statusView.setText("⏳ 任务处理中…\n"
                                        + (st != null ? "状态: " + st : "")
                                        + "\n任务ID: " + taskIdRef[0]);
                                handler.postDelayed(poller[0], pollSeconds[0] * 1000L);
                            } else {
                                String err = (r != null && r.getErrorMessage() != null)
                                        ? r.getErrorMessage() : "任务失败";
                                statusView.setText("❌ " + err);
                                org.json.JSONObject fail = new org.json.JSONObject();
                                try {
                                    fail.put("status", "failed");
                                    fail.put("task_id", taskIdRef[0]);
                                    fail.put("error", err);
                                } catch (Exception ignored) {
                                }
                                finisher.accept(fail.toString());
                            }
                        });
                    } catch (Throwable t) {
                        handler.post(() -> {
                            if (done[0]) return;
                            statusView.setText("❌ 查询异常: " + t.getMessage());
                            org.json.JSONObject fail = new org.json.JSONObject();
                            try {
                                fail.put("status", "failed");
                                fail.put("task_id", taskIdRef[0]);
                                fail.put("error", String.valueOf(t.getMessage()));
                            } catch (Exception ignored) {
                            }
                            finisher.accept(fail.toString());
                        });
                    }
                }).start();
            };
            poller[0] = queryOnce[0];

            btnPlay.setOnClickListener(v -> {
                if (resultFilePath[0] == null) return;
                try {
                    // 应用内渲染/播放：视频走 VideoRenderEngine（VideoView 不依赖系统播放器），
                    // 图片走 ImageRenderEngine；FileRenderActivity 内有失败兜底（用其他应用打开）
                    android.content.Intent intent = new android.content.Intent(act,
                            com.oilquiz.app.ui.activity.FileRenderActivity.class);
                    intent.putExtra(com.oilquiz.app.ui.activity.FileRenderActivity.EXTRA_FILE_PATH,
                            resultFilePath[0]);
                    act.startActivity(intent);
                } catch (Throwable t) {
                    showToast("打开失败: " + t.getMessage(), true);
                }
            });
            btnShare.setOnClickListener(v -> {
                if (resultFilePath[0] == null) return;
                try {
                    android.net.Uri shareUri = copyToPublicDownloads(act, new java.io.File(resultFilePath[0]));
                    if (shareUri == null) {
                        showToast("分享准备失败", true);
                        return;
                    }
                    android.content.Intent share = new android.content.Intent(android.content.Intent.ACTION_SEND);
                    share.setType("*/*");
                    share.putExtra(android.content.Intent.EXTRA_STREAM, shareUri);
                    share.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    act.startActivity(android.content.Intent.createChooser(share, "分享生成结果"));
                } catch (Throwable t) {
                    showToast("分享失败: " + t.getMessage(), true);
                }
            });

            if (autoCloseSeconds > 0) {
                handler.postDelayed(() -> {
                    if (!done[0]) {
                        dialog.dismiss();
                    }
                }, autoCloseSeconds * 1000L);
            }
            handler.post(poller[0]);
        }

        /** 组件插件渲染（type=插件名）：按插件 render（card/layout）渲染，monitor 自动轮询，支持后端按钮与 update 刷新 */
        private void showPluginComponentDialog(final android.app.Activity act, final ComponentRuntime rt,
                                               final String componentId, final String title,
                                               final String message, final String propsJson,
                                               final String pluginName, final int autoCloseSeconds) {
            org.json.JSONObject plugin = com.oilquiz.app.ai.tool.UIComponentPluginManager
                    .getInstance(context).getPlugin(pluginName);
            if (plugin == null) {
                plugin = new org.json.JSONObject();
                try {
                    plugin.put("name", pluginName);
                    plugin.put("description", "自定义注册类型");
                } catch (Exception ignored) {
                }
            }
            showPluginComponentDialog(act, rt, componentId, title, message, propsJson,
                    pluginName, autoCloseSeconds, plugin);
        }

        /** 重载：显式传入插件定义（自定义注册类型 register_type 用） */
        private void showPluginComponentDialog(final android.app.Activity act, final ComponentRuntime rt,
                                               final String componentId, final String title,
                                               final String message, final String propsJson,
                                               final String pluginName, final int autoCloseSeconds,
                                               final org.json.JSONObject plugin) {
            final float density = act.getResources().getDisplayMetrics().density;
            if (plugin == null) {
                rt.result.set("failed:插件定义为空: " + pluginName);
                synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                return;
            }
            final org.json.JSONObject props = new org.json.JSONObject();
            try {
                org.json.JSONObject src = new org.json.JSONObject(
                        propsJson != null && !propsJson.isEmpty() ? propsJson : "{}");
                java.util.Iterator<String> pk = src.keys();
                while (pk.hasNext()) {
                    String k = pk.next();
                    props.put(k, src.get(k));
                }
            } catch (Exception ignored) {
            }
            // 按插件 params schema 校验/转换创建参数：
            // 缺必填 → 明确报错；类型不符 → 按 type 转换（number/boolean/array/object）；
            // 转换失败 → 返回类型错误（不崩溃）；有 default 且未传 → 填充默认值。
            try {
                org.json.JSONObject paramSchema = plugin.optJSONObject("params");
                if (paramSchema != null && paramSchema.length() > 0) {
                    java.util.Iterator<String> pki = paramSchema.keys();
                    while (pki.hasNext()) {
                        String pn = pki.next();
                        org.json.JSONObject def = paramSchema.optJSONObject(pn);
                        if (def == null) continue;
                        String type = def.optString("type", "string");
                        boolean required = def.optBoolean("required", false);
                        Object val = props.has(pn) ? props.opt(pn) : null;
                        boolean missing = val == null
                                || (val instanceof String && ((String) val).trim().isEmpty());
                        if (missing) {
                            if (required) {
                                rt.result.set("failed:缺少必填参数: " + pn
                                        + "（插件 " + pluginName + " 的 params schema 要求必填）");
                                synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                                return;
                            }
                            if (def.has("default")) {
                                props.put(pn, def.opt(pn)); // 默认值填充
                            }
                            continue;
                        }
                        // 类型转换（值类型与 schema 不符时转换；转换失败返回明确错误而非崩溃）
                        try {
                            switch (type) {
                                case "number":
                                    if (!(val instanceof Number)) {
                                        props.put(pn, Double.parseDouble(String.valueOf(val).trim()));
                                    }
                                    break;
                                case "boolean":
                                    if (!(val instanceof Boolean)) {
                                        props.put(pn, Boolean.parseBoolean(String.valueOf(val).trim()));
                                    }
                                    break;
                                case "array":
                                    if (!(val instanceof org.json.JSONArray)) {
                                        props.put(pn, new org.json.JSONArray(String.valueOf(val)));
                                    }
                                    break;
                                case "object":
                                    if (!(val instanceof org.json.JSONObject)) {
                                        props.put(pn, new org.json.JSONObject(String.valueOf(val)));
                                    }
                                    break;
                                default:
                                    break; // string 原样
                            }
                        } catch (Exception ex) {
                            rt.result.set("failed:参数 " + pn + " 类型应为 " + type
                                    + "，无法转换: " + val);
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                            return;
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "插件参数校验异常(继续执行): " + t.getMessage());
            }
            String pluginTitle = plugin.optJSONObject("render") != null
                    ? plugin.optJSONObject("render").optString("title", "") : "";
            if (pluginTitle.isEmpty() && title != null && !title.isEmpty()) pluginTitle = title;
            if (pluginTitle.isEmpty()) pluginTitle = plugin.optString("name", pluginName);
            final String fTitle = pluginTitle;

            android.widget.TextView statusView = new android.widget.TextView(act);
            statusView.setTextSize(14);
            statusView.setPadding(0, (int) (6 * density), 0, (int) (6 * density));
            statusView.setText("⏳ 任务处理中…");
            android.widget.ImageView imgView = new android.widget.ImageView(act);
            imgView.setVisibility(android.view.View.GONE);
            imgView.setAdjustViewBounds(true);
            imgView.setMaxHeight((int) (320 * density));
            android.widget.TextView videoInfo = new android.widget.TextView(act);
            videoInfo.setVisibility(android.view.View.GONE);
            videoInfo.setTextSize(13);
            videoInfo.setPadding(0, (int) (4 * density), 0, (int) (4 * density));
            final android.widget.LinearLayout cardArea = new android.widget.LinearLayout(act);
            cardArea.setOrientation(android.widget.LinearLayout.VERTICAL);
            final java.util.Map<String, Object> layoutViewRefs = new java.util.HashMap<>();

            android.widget.LinearLayout layout = new android.widget.LinearLayout(act);
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            layout.setPadding((int) (16 * density), (int) (8 * density), (int) (16 * density), (int) (8 * density));
            layout.addView(cardArea);
            layout.addView(statusView);
            layout.addView(imgView);
            layout.addView(videoInfo);
            // 内容可滚动：多控件布局（10+ 种输入控件）超出 Dialog 高度时被截断，
            // 后半部分控件（switch/checkbox/date/slider/提交按钮）完全看不到、无法操作
            // （此前 plugin 路径无 ScrollView，只有 custom 表单路径有——布局溢出根因）。
            // 结构与 custom 表单一致：ScrollView 直接作为 dialog view（scrollWrap → layout），
            // 不额外包 LinearLayout——多层嵌套会导致 ScrollView 触摸被外层拦截，
            // EditText 收不到 ACTION_UP，setupInputView 的键盘唤起不触发（实测 served 失效）。
            final android.widget.ScrollView scrollWrap = new android.widget.ScrollView(act);
            scrollWrap.addView(layout, new android.widget.ScrollView.LayoutParams(
                    android.widget.ScrollView.LayoutParams.MATCH_PARENT,
                    android.widget.ScrollView.LayoutParams.WRAP_CONTENT));
            setupScrollViewForInput(scrollWrap);

            final boolean[] doneRef = {false};
            // 是否有可交互按钮（提交/工具按钮）：有则等待用户操作，不立即完成组件
            final boolean[] hasInteractiveButtons = {false};
            final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(act)
                    .setTitle(fTitle)
                    .setMessage(message != null && !message.isEmpty() ? message : null)
                    .setView(scrollWrap)
                    .setPositiveButton("关闭", (d, w) -> {
                        rt.result.compareAndSet("pending", "closed");
                        synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        d.dismiss();
                    })
                    .setCancelable(true)
                    .setOnCancelListener(d -> {
                        rt.result.compareAndSet("pending", "closed");
                        synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                    })
                    .create();
            rt.dialog = dialog;
            dialog.setOnDismissListener(d -> {
                doneRef[0] = true;
                rt.result.compareAndSet("pending", "closed");
                synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
            });
            configureDialogInput(dialog);

            final Runnable[] renderCard = new Runnable[1];
            renderCard[0] = () -> {
                try {
                    cardArea.removeAllViews();
                    org.json.JSONObject render = plugin.optJSONObject("render");
                    if (render != null) {
                        String card = render.optString("card", "");
                        Object layoutObj = render.opt("layout");
                        if (!card.isEmpty()) {
                            org.json.JSONObject cardProps = new org.json.JSONObject();
                            org.json.JSONObject renderProps = render.optJSONObject("props");
                            if (renderProps != null) {
                                java.util.Iterator<String> rk = renderProps.keys();
                                while (rk.hasNext()) {
                                    String rk2 = rk.next();
                                    Object rv = renderProps.get(rk2);
                                    // render.props 支持 {key} 占位符：由创建参数替换
                                    // （如 "path": "{local_path}" → 创建时传 local_path 自动填入）
                                    if (rv instanceof String) {
                                        String rs = (String) rv;
                                        java.util.Iterator<String> pk2 = props.keys();
                                        while (pk2.hasNext()) {
                                            String pk3 = pk2.next();
                                            Object pv = props.opt(pk3);
                                            if (pv != null) {
                                                rs = rs.replace("{" + pk3 + "}",
                                                        String.valueOf(pv));
                                            }
                                        }
                                        cardProps.put(rk2, rs);
                                    } else {
                                        cardProps.put(rk2, rv);
                                    }
                                }
                            }
                            java.util.Iterator<String> keys = props.keys();
                            while (keys.hasNext()) {
                                String k = keys.next();
                                cardProps.put(k, props.get(k));
                            }
                            // 字段映射：file_card/file_list 需要 path 才可点击打开。
                            // 插件创建参数常用 local_path/url/file_path 等别名，统一映射到 path，
                            // 否则渲染出无路径的空壳卡片（video_player 等插件"不能点击播放"根因）。
                            if (("file_card".equals(card) || "file_list".equals(card)) && !cardProps.has("path")) {
                                Object mapped = cardProps.opt("local_path");
                                if (mapped == null || String.valueOf(mapped).trim().isEmpty()) {
                                    mapped = cardProps.opt("file_path");
                                }
                                if (mapped == null || String.valueOf(mapped).trim().isEmpty()) {
                                    mapped = cardProps.opt("path2");
                                }
                                if (mapped == null || String.valueOf(mapped).trim().isEmpty()) {
                                    mapped = cardProps.opt("url");
                                }
                                if (mapped != null && !String.valueOf(mapped).trim().isEmpty()) {
                                    // 工作区相对路径 → 绝对路径（resolveExistingFile）
                                    String mp = String.valueOf(mapped).trim();
                                    cardProps.put("path", com.oilquiz.app.ai.agent.online.AgentWorkspace
                                            .getInstance(context).resolveExistingFile(mp) != null
                                            ? com.oilquiz.app.ai.agent.online.AgentWorkspace
                                                    .getInstance(context).resolveExistingFile(mp).getAbsolutePath()
                                            : mp);
                                }
                            }
                            com.oilquiz.app.ai.chat.component.ComponentRegistry registry =
                                    com.oilquiz.app.ai.chat.component.ComponentRegistry.getInstance();
                            if (registry.hasType(card)) {
                                com.oilquiz.app.ai.chat.component.ComponentData data =
                                        new com.oilquiz.app.ai.chat.component.ComponentData(card, cardProps);
                                android.view.View v = registry.render(act, data);
                                if (v != null) cardArea.addView(v);
                            }
                        } else if (layoutObj != null) {
                            layoutViewRefs.clear();
                            android.view.View v = com.oilquiz.app.ai.python.NativeLayoutRenderer.render(
                                    act, layoutObj, props, layoutViewRefs);
                            if (v != null) cardArea.addView(v);
                            // 绑定布局内全部按钮（含无 key 的提交按钮）：点击时收集
                            // layout 树内输入控件值（collectValues）并回传 result。
                            // 注意：此前只遍历 layoutViewRefs 中带 key 的 Button，无 key 的
                            // 提交按钮点不动；且无 monitor 时组件创建后立即完成（doneRef=true）
                            // 短路按钮收集——"layout 控件输入值无法回传"根因。
                            java.util.List<android.widget.Button> btns = new java.util.ArrayList<>();
                            collectButtons(cardArea, btns);
                            hasInteractiveButtons[0] = !btns.isEmpty();
                            for (final android.widget.Button lb : btns) {
                                final String tag = lb.getTag() != null ? lb.getTag().toString() : "";
                                lb.setOnClickListener(btnV -> {
                                    if (doneRef[0]) return;
                                    // UI-10 交互反馈：点击立即提示，避免"点了没反应"的感知
                                    showToast("✓ 已提交，正在处理…", false);
                                    // UI-10 loading 态：按钮禁用+文案变化，防重复点击
                                    try {
                                        lb.setEnabled(false);
                                        lb.setText("处理中…");
                                    } catch (Throwable ignored) {
                                    }
                                    java.util.Map<String, Object> values =
                                            com.oilquiz.app.ai.python.NativeLayoutRenderer
                                                    .collectValues(layoutViewRefs);
                                    if (tag.startsWith("{")) {
                                        try {
                                            org.json.JSONObject bind = new org.json.JSONObject(tag);
                                            String tool = bind.optString("tool", "");
                                            if (!tool.isEmpty()) {
                                                org.json.JSONObject tp = bind.optJSONObject("tool_params");
                                                final java.util.Map<String, Object> callParams = new HashMap<>();
                                                if (tp != null) {
                                                    java.util.Iterator<String> tk = tp.keys();
                                                    while (tk.hasNext()) {
                                                        String tk2 = tk.next();
                                                        Object tv = tp.opt(tk2);
                                                        if (tv instanceof String) {
                                                            String s = (String) tv;
                                                            for (java.util.Map.Entry<String, Object> ve : values.entrySet()) {
                                                                s = s.replace("{" + ve.getKey() + "}", String.valueOf(ve.getValue()));
                                                            }
                                                            callParams.put(tk2, s);
                                                        } else {
                                                            callParams.put(tk2, tv);
                                                        }
                                                    }
                                                }
                                                statusView.setText("⏳ 调用后端 " + tool + "…");
                                                new Thread(() -> {
                                                    try {
                                                        com.oilquiz.app.ai.tool.AIToolManager tm =
                                                                com.oilquiz.app.ai.tool.AIToolManager.getInstance(context);
                                                        com.oilquiz.app.ai.tool.AIToolResult br = tm.executeTool(tool, callParams);
                                                        handler.post(() -> {
                                                            if (doneRef[0]) return;
                                                            org.json.JSONObject res = new org.json.JSONObject();
                                                            try {
                                                                res.put("status", "success");
                                                                res.put("component_type", pluginName);
                                                                res.put("action", bind.optString("action", ""));
                                                                res.put("tool", tool);
                                                                res.put("values", new org.json.JSONObject(
                                                                        new com.google.gson.Gson().toJson(values)));
                                                                if (br != null) {
                                                                    if (br.getResult() != null) {
                                                                        res.put("tool_result",
                                                                                br.getResult() instanceof String
                                                                                        ? (String) br.getResult()
                                                                                        : new org.json.JSONObject(
                                                                                                new com.google.gson.Gson().toJson(br.getResult())).toString());
                                                                    }
                                                                    if (br.getErrorMessage() != null) {
                                                                        res.put("tool_error", br.getErrorMessage());
                                                                    }
                                                                    res.put("tool_success", br.isSuccess());
                                                                }
                                                            } catch (Exception ignored) {
                                                            }
                                                            doneRef[0] = true;
                                                            rt.result.set(res.toString());
                                                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                                                            dialog.dismiss();
                                                        });
                                                    } catch (Throwable t) {
                                                        handler.post(() -> {
                                                            if (doneRef[0]) return;
                                                            org.json.JSONObject res = new org.json.JSONObject();
                                                            try {
                                                                res.put("status", "failed");
                                                                res.put("tool", tool);
                                                                res.put("error", String.valueOf(t.getMessage()));
                                                            } catch (Exception ignored) {
                                                            }
                                                            doneRef[0] = true;
                                                            rt.result.set(res.toString());
                                                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                                                            dialog.dismiss();
                                                        });
                                                    }
                                                }).start();
                                                return;
                                            }
                                        } catch (Exception ignored) {
                                        }
                                    }
                                    org.json.JSONObject res = new org.json.JSONObject();
                                    try {
                                        res.put("status", "success");
                                        res.put("component_type", pluginName);
                                        res.put("action", tag);
                                        res.put("values", new org.json.JSONObject(
                                                new com.google.gson.Gson().toJson(values)));
                                    } catch (Exception ignored) {
                                    }
                                    doneRef[0] = true;
                                    rt.result.set(res.toString());
                                    synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                                    dialog.dismiss();
                                });
                            }
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "插件卡片渲染失败: " + t.getMessage());
                }
            };
            // 关键顺序：先渲染内容再 show()——若 show 后再 addView，窗口显示时视图树中
            // 没有 EditText，窗口焦点落在 DecorView，之后加入的 EditText 永远不会被 IME
            // serve（"is not served"，layout 控件输入法不唤起根因；对照实测 showDialog
            // 先渲染后 show 成功 served=true，plugin 路径 show 后渲染 served=false）。
            renderCard[0].run();
            dialog.show();

            final boolean[] done = doneRef;
            final String[] resultFilePath = {null};
            final Runnable[] poller = new Runnable[1];
            final java.util.function.Consumer<String> finisher = new java.util.function.Consumer<String>() {
                @Override
                public void accept(String resultJson) {
                    if (done[0]) return;
                    done[0] = true;
                    rt.result.set(resultJson);
                    synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                }
            };

            final boolean hasMonitor = plugin.optJSONObject("monitor") != null;
            org.json.JSONObject monitor = plugin.optJSONObject("monitor");
            if (!hasMonitor && !hasInteractiveButtons[0]) {
                // 纯展示组件（无按钮可点）：创建即完成，返回组件信息（不等待用户操作）。
                // 注意：含交互按钮（提交/工具按钮）的组件不能走这里——否则 doneRef 提前置 true，
                // 用户点击按钮时收集被短路，"layout 控件输入值无法回传"（Bug 报告根因）。
                statusView.setText("✅ 组件已创建");
                try {
                    org.json.JSONObject ok = new org.json.JSONObject();
                    ok.put("status", "success");
                    ok.put("component_type", pluginName);
                    ok.put("props", props.toString());
                    finisher.accept(ok.toString());
                } catch (Exception ignored) {
                }
            }
            final String monitorTool = monitor != null ? monitor.optString("tool", "") : "";
            final String monitorAction = monitor != null ? monitor.optString("action", "") : "";
            final int pollSeconds = monitor != null
                    ? Math.max(2, Math.min(30, monitor.optInt("poll_seconds", 5))) : 5;
            final String successField = monitor != null ? monitor.optString("success_field", "") : "";
            final String errorField = monitor != null ? monitor.optString("error_field", "") : "";

            final Runnable[] queryOnce = new Runnable[1];
            queryOnce[0] = () -> {
                if (done[0] || monitorTool.isEmpty()) return;
                final java.util.Map<String, Object> queryParams = new HashMap<>();
                queryParams.put("action", monitorAction.isEmpty() ? "query" : monitorAction);
                try {
                    org.json.JSONObject paramMap = monitor.optJSONObject("param_map");
                    if (paramMap != null) {
                        java.util.Iterator<String> keys = paramMap.keys();
                        while (keys.hasNext()) {
                            String propsKey = keys.next();
                            String queryKey = paramMap.optString(propsKey, propsKey);
                            Object v = props.opt(propsKey);
                            if (v != null) queryParams.put(queryKey, v);
                        }
                    }
                } catch (Exception ignored) {
                }
                new Thread(() -> {
                    try {
                        com.oilquiz.app.ai.tool.AIToolManager tm =
                                com.oilquiz.app.ai.tool.AIToolManager.getInstance(context);
                        com.oilquiz.app.ai.tool.AIToolResult r = tm.executeTool(monitorTool, queryParams);
                        handler.post(() -> {
                            if (done[0]) return;
                            if (r != null && r.isSuccess() && r.getResult() instanceof Map) {
                                @SuppressWarnings("unchecked")
                                java.util.Map<String, Object> res =
                                        (java.util.Map<String, Object>) r.getResult();
                                Object fp = successField.isEmpty() ? null : res.get(successField);
                                if (fp != null && !String.valueOf(fp).isEmpty()) {
                                    resultFilePath[0] = String.valueOf(fp);
                                    String ext = resultFilePath[0].toLowerCase();
                                    if (ext.endsWith(".png") || ext.endsWith(".jpg") || ext.endsWith(".jpeg")
                                            || ext.endsWith(".webp") || ext.endsWith(".gif")) {
                                        imgView.setVisibility(android.view.View.VISIBLE);
                                        com.bumptech.glide.Glide.with(act)
                                                .load(new java.io.File(resultFilePath[0])).into(imgView);
                                    } else {
                                        videoInfo.setVisibility(android.view.View.VISIBLE);
                                        java.io.File f = new java.io.File(resultFilePath[0]);
                                        videoInfo.setText("📄 " + f.getName() + "\n"
                                                + formatFileSize(f.length()) + "\n" + resultFilePath[0]);
                                    }
                                    statusView.setText("✅ 任务完成");
                                    org.json.JSONObject ok = new org.json.JSONObject();
                                    try {
                                        ok.put("status", "success");
                                        ok.put("component_type", pluginName);
                                        ok.put("filePath", resultFilePath[0]);
                                        java.util.Iterator<String> ks = res.keySet().iterator();
                                        while (ks.hasNext()) {
                                            String k = ks.next();
                                            if (!"status".equals(k) && !"message".equals(k)) {
                                                ok.put(k, String.valueOf(res.get(k)));
                                            }
                                        }
                                    } catch (Exception ignored) {
                                    }
                                    finisher.accept(ok.toString());
                                    return;
                                }
                                String err = null;
                                if (!errorField.isEmpty() && res.get(errorField) != null) {
                                    err = String.valueOf(res.get(errorField));
                                } else if (res.get("error") != null) {
                                    err = String.valueOf(res.get("error"));
                                }
                                if (err != null && !err.isEmpty()) {
                                    statusView.setText("❌ " + err);
                                    org.json.JSONObject fail = new org.json.JSONObject();
                                    try {
                                        fail.put("status", "failed");
                                        fail.put("component_type", pluginName);
                                        fail.put("error", err);
                                    } catch (Exception ignored) {
                                    }
                                    finisher.accept(fail.toString());
                                    return;
                                }
                                Object st = res.get("status");
                                Object taskId = res.get("task_id");
                                StringBuilder sb = new StringBuilder("⏳ 任务处理中…");
                                if (st != null) sb.append("\n状态: ").append(st);
                                if (taskId != null) sb.append("\n任务ID: ").append(taskId);
                                if (res.get("message") != null) sb.append("\n").append(res.get("message"));
                                statusView.setText(sb.toString());
                                handler.postDelayed(poller[0], pollSeconds * 1000L);
                            } else {
                                String err = (r != null && r.getErrorMessage() != null)
                                        ? r.getErrorMessage() : "任务失败（无返回）";
                                statusView.setText("❌ " + err);
                                org.json.JSONObject fail = new org.json.JSONObject();
                                try {
                                    fail.put("status", "failed");
                                    fail.put("component_type", pluginName);
                                    fail.put("error", err);
                                } catch (Exception ignored) {
                                }
                                finisher.accept(fail.toString());
                            }
                        });
                    } catch (Throwable t) {
                        handler.post(() -> {
                            if (done[0]) return;
                            statusView.setText("❌ 查询异常: " + t.getMessage());
                            org.json.JSONObject fail = new org.json.JSONObject();
                            try {
                                fail.put("status", "failed");
                                fail.put("component_type", pluginName);
                                fail.put("error", String.valueOf(t.getMessage()));
                            } catch (Exception ignored) {
                            }
                            finisher.accept(fail.toString());
                        });
                    }
                }).start();
            };
            poller[0] = queryOnce[0];

            if (autoCloseSeconds > 0) {
                handler.postDelayed(() -> {
                    if (!done[0]) {
                        dialog.dismiss();
                    }
                }, autoCloseSeconds * 1000L);
            }
            if (hasMonitor) {
                handler.post(poller[0]);
            }
        }

        /** 递归收集容器内全部 Button（含无 key 的提交按钮，此前只遍历 layoutViewRefs 漏掉无 key 按钮）。
         *  注意：RadioButton/CheckBox/Switch/ToggleButton 都是 CompoundButton 的子类，
         *  instanceof Button 也会命中——但它们不是提交按钮，必须排除，否则点击单选圆点/
         *  复选框/开关会误触发"提交→收集→完成 dialog"（实测点击单选圆点 dialog 自动退出）。 */
        private void collectButtons(android.view.View root, java.util.List<android.widget.Button> out) {
            if (root == null) return;
            if (root instanceof android.widget.CompoundButton) return; // RadioButton/CheckBox/Switch/Toggle 排除
            if (root instanceof android.widget.Button) {
                out.add((android.widget.Button) root);
                return;
            }
            if (root instanceof android.view.ViewGroup) {
                android.view.ViewGroup vg = (android.view.ViewGroup) root;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    collectButtons(vg.getChildAt(i), out);
                }
            }
        }

        /** 复制到公共 Download/OilQuiz（私有工作区分享用），返回 content URI */
        private android.net.Uri copyToPublicDownloads(android.app.Activity act, java.io.File source) {
            try {
                String fileName = source.getName();
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/OilQuiz");
                android.net.Uri collection = android.os.Build.VERSION.SDK_INT >= 29
                        ? android.provider.MediaStore.Downloads
                                .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                android.net.Uri item = act.getContentResolver().insert(collection, values);
                if (item == null) return null;
                try (java.io.OutputStream os = act.getContentResolver().openOutputStream(item)) {
                    if (os == null) return null;
                    try (java.io.InputStream is = new java.io.FileInputStream(source)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
                    }
                }
                return item;
            } catch (Throwable t) {
                Log.w(TAG, "复制到公共目录失败: " + t.getMessage());
                return null;
            }
        }

        private String formatFileSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
            if (bytes < 1024 * 1024 * 1024) {
                return String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
            }
            return String.format(java.util.Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0);
        }

        /** 动态自定义原生表单组件（type=custom）：fields JSON 定义任意字段，确定返回全部值 JSON。
         *  rounds>1 时进入连续输入模式：弹窗含"添加下一条"(收集本轮并清空重建)与"完成"(收集本轮并结束)，
         *  完成时返回 {"rounds":[第1轮values, 第2轮...], "total":N}；取消返回 cancelled。 */
        private void showCustomFormDialog(final android.app.Activity act, final ComponentRuntime rt,
                                          final String componentId, final String title,
                                          final String message, final String propsJson,
                                          final int autoCloseSeconds, final int rounds) {
            final org.json.JSONArray fieldDefs = new org.json.JSONArray();
            try {
                org.json.JSONObject pj = new org.json.JSONObject(propsJson != null ? propsJson : "");
                org.json.JSONArray arr = pj.optJSONArray("fields");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        fieldDefs.put(arr.get(i));
                    }
                }
            } catch (Exception ignored) {
            }
            if (fieldDefs.length() == 0) {
                android.app.AlertDialog.Builder b0 = new android.app.AlertDialog.Builder(act);
                if (title != null && !title.isEmpty()) b0.setTitle(title);
                if (message != null && !message.isEmpty()) b0.setMessage(message);
                b0.setPositiveButton("确定", (d, w) -> {
                    rt.result.set("{}");
                    synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                    d.dismiss();
                });
                b0.setNegativeButton("取消", (d, w) -> {
                    rt.result.set("cancelled");
                    synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                    d.dismiss();
                });
                b0.setCancelable(true);
                b0.setOnCancelListener(d -> {
                    rt.result.compareAndSet("pending", "cancelled");
                    synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                });
                android.app.AlertDialog d0 = b0.create();
                d0.setOnDismissListener(d -> {
                    rt.result.compareAndSet("pending", "cancelled");
                    synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                });
                d0.show();
                rt.dialog = d0;
                return;
            }
            final java.util.Map<String, Boolean> fieldRequired = new java.util.LinkedHashMap<>();
            final java.util.Map<String, String> fieldLabels = new java.util.LinkedHashMap<>();
            final boolean multiMode = rounds > 1;
            final java.util.List<org.json.JSONObject> collectedRounds = new java.util.ArrayList<>();
            // 表单内容容器：多轮"添加下一条"时替换内部 view（控件全部归零重建，无需逐控件清空）。
            // 用 ScrollView 直接作为 dialog view（与原单轮方案一致，wrap 测量正常），内部 LinearLayout 可替换。
            final android.widget.ScrollView formHolder = new android.widget.ScrollView(act);
            final android.widget.LinearLayout formColumn = new android.widget.LinearLayout(act);
            formColumn.setOrientation(android.widget.LinearLayout.VERTICAL);
            formHolder.addView(formColumn, new android.widget.ScrollView.LayoutParams(
                    android.widget.ScrollView.LayoutParams.MATCH_PARENT,
                    android.widget.ScrollView.LayoutParams.WRAP_CONTENT));
            // 当前轮 viewRefs（每次重建替换）
            final java.util.concurrent.atomic.AtomicReference<java.util.Map<String, Object>> curRefs =
                    new java.util.concurrent.atomic.AtomicReference<>(new java.util.HashMap<>());
            final android.widget.TextView roundHint = new android.widget.TextView(act);
            roundHint.setPadding(dp4(8), dp4(4), dp4(8), dp4(4));
            roundHint.setTextSize(12);
            roundHint.setTextColor(ThemeColors.get(R.color.hc_ff888888));
            setupScrollViewForInput(formHolder);

            // ---- 构建单轮表单 view 并装入 formHolder ----
            final Runnable rebuildForm = () -> {
                try {
                    java.util.Map<String, Object> refs = new java.util.HashMap<>();
                    curRefs.set(refs);
                    org.json.JSONArray kids = new org.json.JSONArray();
                    for (int i = 0; i < fieldDefs.length(); i++) {
                        org.json.JSONObject fd = fieldDefs.optJSONObject(i);
                        if (fd == null) continue;
                        String key = fd.optString("key", "");
                        if (key.isEmpty()) key = "field" + i;
                        String label = fd.optString("label", key);
                        String ftype = fd.optString("type", "text").toLowerCase();
                        String def = fd.optString("default", "");
                        boolean required = fd.optBoolean("required", false);
                        fieldRequired.put(key, required);
                        fieldLabels.put(key, label);
                        org.json.JSONObject labelNode = new org.json.JSONObject();
                        labelNode.put("type", "text");
                        labelNode.put("text", (required ? "* " : "") + label);
                        labelNode.put("size", 14);
                        kids.put(labelNode);
                        org.json.JSONObject ctrl = new org.json.JSONObject();
                        String ltype;
                        switch (ftype) {
                            case "select": ltype = "select"; break;
                            case "switch": ltype = "switch"; break;
                            case "checkbox": ltype = "checkbox"; break;
                            case "radio": ltype = "radio"; break;
                            case "slider": ltype = "slider"; break;
                            case "date": ltype = "date"; break;
                            case "time": ltype = "time"; break;
                            case "datetime": ltype = "datetime"; break;
                            case "password": ltype = "password"; break;
                            case "number": ltype = "number"; break;
                            case "multiline": ltype = "multiline"; break;
                            case "otp": ltype = "otp"; break;
                            case "email": ltype = "email"; break;
                            case "tel": ltype = "tel"; break;
                            case "url": ltype = "url"; break;
                            case "search": ltype = "search"; break;
                            case "file": ltype = "file"; break;
                            default: ltype = "input"; break;
                        }
                        ctrl.put("type", ltype);
                        ctrl.put("key", key);
                        if ("switch".equals(ftype) || "checkbox".equals(ftype)) {
                            ctrl.put("checked", "true".equalsIgnoreCase(def));
                        } else if (!def.isEmpty()) {
                            ctrl.put("value", def);
                        }
                        if ("select".equals(ftype) || "radio".equals(ftype)) {
                            org.json.JSONArray opts = fd.optJSONArray("options");
                            if (opts != null && opts.length() > 0) ctrl.put("options", opts);
                        }
                        if ("otp".equals(ftype)) ctrl.put("length", fd.optInt("length", 6));
                        if ("slider".equals(ftype)) {
                            ctrl.put("max", fd.optInt("max", 100));
                            ctrl.put("show_value", true);
                        }
                        String hint = fd.optString("hint", "");
                        if (!hint.isEmpty()) ctrl.put("hint", hint);
                        kids.put(ctrl);
                    }
                    org.json.JSONObject root = new org.json.JSONObject();
                    org.json.JSONObject layout = new org.json.JSONObject();
                    root.put("type", "column");
                    root.put("children", kids);
                    layout.put("root", root);
                    android.view.View formView = com.oilquiz.app.ai.python.NativeLayoutRenderer.render(
                            act, layout, new org.json.JSONObject(), refs);
                    if (formView == null) {
                        android.widget.TextView tvErr = new android.widget.TextView(act);
                        tvErr.setText("⚠ 表单渲染失败");
                        tvErr.setTextSize(12);
                        formView = tvErr;
                    }
                    android.widget.ScrollView scrollForm = new android.widget.ScrollView(act);
                    scrollForm.addView(formView);
                    setupScrollViewForInput(scrollForm);
                    formColumn.removeAllViews();
                    formColumn.addView(scrollForm, new android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
                    if (multiMode) {
                        roundHint.setText("已录入 " + collectedRounds.size() + " / " + rounds
                                + " 条（点「添加下一条」继续）");
                        formColumn.addView(roundHint, new android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "custom 表单重建失败: " + t.getMessage());
                }
            };

            // ---- 收集本轮 values（类型转换同原逻辑）并校验必填；通过返回 JSONObject，否则 null ----
            final java.util.function.Supplier<org.json.JSONObject> collectRound = () -> {
                java.util.Map<String, Object> values =
                        com.oilquiz.app.ai.python.NativeLayoutRenderer.collectValues(curRefs.get());
                for (int i = 0; i < fieldDefs.length(); i++) {
                    try {
                        org.json.JSONObject fd = fieldDefs.optJSONObject(i);
                        if (fd == null) continue;
                        String key = fd.optString("key", "");
                        if (key.isEmpty()) key = "field" + i;
                        if (Boolean.TRUE.equals(fieldRequired.get(key))) {
                            Object val = values.get(key);
                            String s = val == null ? "" : String.valueOf(val);
                            if (s.trim().isEmpty()) {
                                showToast("请填写必填项：" + fieldLabels.get(key), false);
                                return null;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                org.json.JSONObject out = new org.json.JSONObject();
                for (int i = 0; i < fieldDefs.length(); i++) {
                    try {
                        org.json.JSONObject fd = fieldDefs.optJSONObject(i);
                        if (fd == null) continue;
                        String key = fd.optString("key", "");
                        if (key.isEmpty()) key = "field" + i;
                        String ftype = fd.optString("type", "text").toLowerCase();
                        Object val = values.get(key);
                        if ("number".equals(ftype)) {
                            String s = val == null ? "" : String.valueOf(val).trim();
                            try {
                                out.put(key, Double.parseDouble(s.isEmpty() ? "0" : s));
                            } catch (NumberFormatException ex) {
                                out.put(key, s);
                            }
                        } else if ("switch".equals(ftype) || "checkbox".equals(ftype)) {
                            out.put(key, Boolean.TRUE.equals(val));
                        } else {
                            out.put(key, val == null ? "" : String.valueOf(val));
                        }
                    } catch (Exception ignored) {
                    }
                }
                return out;
            };

            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(act);
            if (title != null && !title.isEmpty()) b.setTitle(title);
            if (message != null && !message.isEmpty()) b.setMessage(message);
            b.setView(formHolder);
            if (multiMode) {
                // 多轮连续输入：添加下一条（收集并清空重建）+ 完成（收集并结束）
                b.setPositiveButton("添加下一条", null);
                b.setNeutralButton("完成", (d, w) -> {
                    try {
                        org.json.JSONObject last = collectRound.get();
                        if (last == null) return; // 必填未通过，停留本页
                        collectedRounds.add(last);
                        org.json.JSONObject res = new org.json.JSONObject();
                        org.json.JSONArray arr = new org.json.JSONArray();
                        for (org.json.JSONObject r : collectedRounds) arr.put(r);
                        res.put("rounds", arr);
                        res.put("total", collectedRounds.size());
                        rt.result.set(res.toString());
                        synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        Log.i(TAG, "[Python component] multi-round form result: " + res);
                    } catch (Exception ex) {
                        Log.w(TAG, "多轮表单结果序列化失败: " + ex.getMessage());
                    }
                    d.dismiss();
                });
            } else {
                b.setPositiveButton("确定", null);
            }
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
            dialog.setOnShowListener(d -> {
                android.widget.Button positive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
                if (multiMode) {
                    positive.setOnClickListener(v -> {
                        org.json.JSONObject round = collectRound.get();
                        if (round == null) return; // 必填未通过
                        collectedRounds.add(round);
                        Log.i(TAG, "[Python component] multi-round added #" + collectedRounds.size()
                                + ": " + round);
                        rebuildForm.run(); // 清空重建，继续下一轮
                    });
                } else {
                    positive.setOnClickListener(v -> {
                        org.json.JSONObject out = collectRound.get();
                        if (out == null) return; // 必填未通过
                        rt.result.set(out.toString());
                        synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        dialog.dismiss();
                    });
                }
            });
            configureDialogInput(dialog);
            rebuildForm.run();
            dialog.show();
            rt.dialog = dialog;
            Log.i(TAG, "[Python component] custom form created: " + componentId
                    + (multiMode ? " (multi-round x" + rounds + ")" : ""));
        }

        /** dp 换算（本类内小工具，供多轮表单提示条 padding 用） */
        private static int dp4(int dp) {
            return (int) android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_DIP, dp,
                    android.content.res.Resources.getSystem().getDisplayMetrics());
        }

        /**
         * 常驻布局画布（type=layout_canvas）：渲染 layout 树到弹窗，注册 LayoutCanvasSession，
         * 供 layout_editor 工具动态编辑（set/add/patch/get）。每次编辑后重渲染弹窗内容，
         * 并把上一轮的输入控件值回填（保留用户输入）。关闭时移除会话、返回 cancelled/closed。
         *
         * props 内可取：
         * - layout: 初始布局树（{"root":{...}} 或单节点）
         * - 顶层 layout 参数（经 createComponent 传入 fProps）
         */
        private void showLayoutCanvasDialog(final android.app.Activity act, final ComponentRuntime rt,
                                            final String componentId, final String title,
                                            final String propsJson, final String pluginName,
                                            final int autoCloseSeconds) {
            final float density = act.getResources().getDisplayMetrics().density;
            // 解析初始 layout + props
            final org.json.JSONObject propsObj = new org.json.JSONObject();
            org.json.JSONObject initLayout = null;
            try {
                if (propsJson != null && !propsJson.isEmpty()) {
                    org.json.JSONObject pj = new org.json.JSONObject(propsJson);
                    java.util.Iterator<String> pk = pj.keys();
                    while (pk.hasNext()) {
                        String k = pk.next();
                        propsObj.put(k, pj.get(k));
                    }
                    Object lo = pj.opt("layout");
                    if (lo == null) lo = pj.opt("root");
                    if (lo instanceof org.json.JSONObject) {
                        initLayout = (org.json.JSONObject) lo;
                    } else if (lo != null) {
                        initLayout = new org.json.JSONObject(String.valueOf(lo));
                    }
                }
            } catch (Exception ignored) {
            }
            if (initLayout == null) {
                // 默认空画布：column 容器
                initLayout = new org.json.JSONObject();
                try {
                    org.json.JSONObject root = new org.json.JSONObject();
                    root.put("type", "column");
                    root.put("children", new org.json.JSONArray());
                    initLayout.put("root", root);
                } catch (Exception ignored) {
                }
            }
            final org.json.JSONObject fInitLayout = initLayout;

            // 画布内容容器：整体重渲染时替换内部 view（只用一层 ScrollView，避免多层包裹影响输入法）
            final android.widget.ScrollView canvasScroll = new android.widget.ScrollView(act);
            setupScrollViewForInput(canvasScroll);

            // 会话：当前布局 + 重渲染回调 + 当前值收集
            final com.oilquiz.app.ai.chat.component.LayoutCanvasManager.LayoutCanvasSession session =
                    new com.oilquiz.app.ai.chat.component.LayoutCanvasManager.LayoutCanvasSession();
            session.layout = fInitLayout;
            session.props = propsObj;
            // 当前渲染的 viewRefs（重渲染时替换）
            final java.util.concurrent.atomic.AtomicReference<java.util.Map<String, Object>> curRefs =
                    new java.util.concurrent.atomic.AtomicReference<>(new java.util.HashMap<>());
            // 上一轮收集的值（回填）
            final java.util.concurrent.atomic.AtomicReference<java.util.Map<String, Object>> prevValues =                    new java.util.concurrent.atomic.AtomicReference<>(new java.util.HashMap<>());

            /**
             * 画布默认设计兜底（每次重渲染前调用）：
             * 1. 根节点无 style/background/radius/padding 时注入默认装饰（浅底 #F6F8FC + 16dp 圆角
             *    + 16 内边距 + 12 间距）——助手不传样式画布也有层次，不再是裸白方框；
             *    助手显式传了 style 则完全不覆盖。
             * 2. children 为空时给默认骨架（标题 + 副标题 + 分隔线 + 引导提示），避免空画布。
             */
            final Runnable[] canvasDecorator = new Runnable[1];
            canvasDecorator[0] = () -> {
                try {
                    org.json.JSONObject layout = session.layout != null ? session.layout : fInitLayout;
                    if (layout == null) return;
                    org.json.JSONObject root = layout.optJSONObject("root");
                    if (root == null) root = layout;
                    // 跟随项目主题系统动态变化（深浅模式/主题资源变更自动适配）：
                    // CanvasThemeResolver.DEFAULT 从 values/values-night 主题资源读取，
                    // 不硬编码色值；未来换主题/皮肤仅需替换该实现。
                    com.oilquiz.app.ai.python.CanvasThemeResolver.CanvasPalette pal =
                            com.oilquiz.app.ai.python.CanvasThemeResolver.DEFAULT.resolve(act);
                    // 1) 样式兜底：style 存在时合并缺失键，不存在则新建
                    org.json.JSONObject style = root.optJSONObject("style");
                    if (style == null) {
                        style = new org.json.JSONObject();
                        root.put("style", style);
                    }
                    if (!style.has("background")) style.put("background", pal.background);
                    if (!style.has("border")) style.put("border", pal.border);
                    if (!style.has("radius")) style.put("radius", pal.radius);
                    if (!style.has("padding")) style.put("padding", pal.padding);
                    // spacing 渲染器只读节点顶层字段（style 内不生效），放顶层
                    if (!root.has("spacing")) root.put("spacing", pal.spacing);
                    // 2) 空画布骨架（颜色随主题动态变化）
                    org.json.JSONArray children = root.optJSONArray("children");
                    if (children == null || children.length() == 0) {
                        org.json.JSONArray arr = new org.json.JSONArray();
                        org.json.JSONObject t = new org.json.JSONObject();
                        t.put("type", "text");
                        t.put("text", "画布");
                        t.put("bold", true);
                        t.put("fontSize", 20);
                        t.put("color", pal.title);
                        arr.put(t);
                        org.json.JSONObject sub = new org.json.JSONObject();
                        sub.put("type", "text");
                        sub.put("text", "这是一个空画布，告诉助手继续添加内容，或让我用 layout_editor 继续编辑。");
                        sub.put("fontSize", 13);
                        sub.put("color", pal.subtitle);
                        arr.put(sub);
                        org.json.JSONObject div = new org.json.JSONObject();
                        div.put("type", "divider");
                        arr.put(div);
                        org.json.JSONObject hint = new org.json.JSONObject();
                        hint.put("type", "text");
                        hint.put("text", "✦ 示例：输入框、按钮、卡片、图表——都可以放上来");
                        hint.put("fontSize", 12);
                        hint.put("color", pal.hint);
                        arr.put(hint);
                        root.put("children", arr);
                    }
                } catch (Throwable ignored) {
                }
            };

            // 重渲染：用 session.layout 重新 render，回填旧值
            final Runnable renderCanvas = () -> {
                try {
                    // 1) 先收集当前已渲染控件的值（保留用户输入）——此时 curRefs 是上一个 render 的结果
                    Map<String, Object> live = com.oilquiz.app.ai.python.NativeLayoutRenderer
                            .collectValues(curRefs.get());
                    if (!live.isEmpty()) {
                        prevValues.set(live);
                    }
                    // 2) 重新渲染画布（新 refs）
                    java.util.Map<String, Object> refs = new java.util.HashMap<>();
                    curRefs.set(refs);
                    org.json.JSONObject layout = session.layout;
                    if (layout == null) layout = fInitLayout;
                    // 画布默认设计兜底：根节点无装饰时给浅底+圆角+内边距；空画布给默认骨架，
                    // 避免"裸白方框画布"（卡片有 style 而画布没有的问题）
                    canvasDecorator[0].run();
                    android.view.View v = com.oilquiz.app.ai.python.NativeLayoutRenderer.render(
                            act, layout, propsObj, refs);
                    if (v == null) {
                        android.widget.TextView tvErr = new android.widget.TextView(act);
                        tvErr.setText("⚠ 画布渲染失败");
                        tvErr.setTextSize(12);
                        v = tvErr;
                    }
                    // 3) 把上一轮值回填到新控件（保留用户输入）
                    Map<String, Object> prev = prevValues.get();
                    if (prev != null && !prev.isEmpty()) {
                        com.oilquiz.app.ai.python.NativeLayoutRenderer.applyValues(refs, prev);
                    }
                    canvasScroll.removeAllViews();
                    canvasScroll.addView(v, new android.widget.ScrollView.LayoutParams(
                            android.widget.ScrollView.LayoutParams.MATCH_PARENT,
                            android.widget.ScrollView.LayoutParams.WRAP_CONTENT));
                    // 重渲染后为新 EditText 注入"点击弹键盘"：layout_editor 编辑画布会重建控件，
                    // 需重新绑定 IME（仅加 OnClickListener，不覆盖 setupInputView 的 OnTouchListener）
                    final android.view.View iv = v;
                    canvasScroll.postDelayed(() -> {
                        injectEditTextIme(iv, 0);
                        // 绑定画布内按钮：点击收集全部带 key 控件值并回传 result（与插件弹窗一致）
                        try {
                            java.util.List<android.widget.Button> btns = new java.util.ArrayList<>();
                            collectButtons(iv, btns);
                            for (final android.widget.Button lb : btns) {
                                final String btnTag = lb.getTag() != null ? lb.getTag().toString() : "";
                                lb.setOnClickListener(btnV -> {
                                    if (rt.result != null && rt.result.get() != null
                                            && !"pending".equals(rt.result.get())) return;
                                    // UI-10 交互反馈：点击立即提示（画布按钮）
                                    showToast("✓ 已提交，正在处理…", false);
                                    // UI-10 loading 态：按钮禁用+文案变化，防重复点击
                                    try {
                                        lb.setEnabled(false);
                                        lb.setText("处理中…");
                                    } catch (Throwable ignored) {
                                    }
                                    Map<String, Object> values = com.oilquiz.app.ai.python.NativeLayoutRenderer
                                            .collectValues(curRefs.get());
                                    org.json.JSONObject res = new org.json.JSONObject();
                                    try {
                                        res.put("action", btnTag.isEmpty() ? "click" : btnTag);
                                        res.put("values", new org.json.JSONObject(
                                                new com.google.gson.Gson().toJson(values)));
                                    } catch (Exception ex) {
                                        try {
                                            res.put("action", btnTag.isEmpty() ? "click" : btnTag);
                                            res.put("values", values);
                                        } catch (Exception ignored) {
                                        }
                                    }
                                    Log.i(TAG, "[canvas] button clicked: " + btnTag + " → " + res);
                                    if (rt.result != null) {
                                        rt.result.set(res.toString());
                                        synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                                    }
                                });
                            }
                        } catch (Throwable ignored) {
                        }
                    }, 200);
                } catch (Throwable t) {
                    Log.w(TAG, "画布重渲染失败: " + t.getMessage());
                }
            };
            session.applyLayout = s -> {
                // layout_editor 工具可能在后台线程调用：切回主线程再重渲染（View 操作必须主线程）
                android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                mainHandler.post(() -> renderCanvas.run());
            };
            session.collectCurrentValues = () -> com.oilquiz.app.ai.python.NativeLayoutRenderer
                    .collectValues(curRefs.get());

            // 注册会话
            com.oilquiz.app.ai.chat.component.LayoutCanvasManager.getInstance()
                    .register(componentId, session);

            // 弹窗
            android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(act)
                    .setTitle(title != null && !title.isEmpty() ? title : "布局画布")
                    .setView(canvasScroll)
                    .setCancelable(true)
                    .setOnCancelListener(d -> {
                        rt.result.compareAndSet("pending", "cancelled");
                        synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                    })
                    .create();
            rt.dialog = dialog;
            // 画布弹窗统一走 configureDialogInput 的 IME 配置（与 custom 表单一致，保证输入框可弹键盘）；
            // 不额外设置 FLAG_DIM_BEHIND/setCanceledOnTouchOutside，避免干扰 window 输入焦点链。
            dialog.setOnDismissListener(d -> {
                com.oilquiz.app.ai.chat.component.LayoutCanvasManager.getInstance()
                        .unregister(componentId);
                rt.result.compareAndSet("pending", "closed");
                synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
            });
            configureDialogInput(dialog);
            renderCanvas.run();
            dialog.show();
            Log.i(TAG, "[Python component] layout canvas created: " + componentId);
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

        /**
         * 弹窗输入法配置：检测 view 树中是否含输入控件（EditText/Spinner/SeekBar/DatePicker 等），
         * 有则设置 SOFT_INPUT_ADJUST_RESIZE + STATE_ALWAYS_VISIBLE——修复自定义 UI
         * 无法聚焦输入框/不弹输入法的问题（AlertDialog 默认 softInputMode 不弹键盘）。
         * 无输入控件时仅设 ADJUST_UNSPECIFIED（不弹输入法，不影响纯展示）。
         */
        private void configureDialogInput(android.app.AlertDialog dialog) {
            if (dialog == null || dialog.getWindow() == null) return;
            try {
                // 统一 ADJUST_RESIZE：弹窗内容随输入法收缩，避免遮挡（无输入控件也无害）。
                dialog.getWindow().setSoftInputMode(
                        android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                // 清除 ALT_FOCUSABLE_IM：部分 ROM 主题设置后窗口声明"不关心输入法"，
                // IME 拒绝为该窗口建立输入连接（layout 控件输入法不唤起）。
                // 注意：绝不能动 DecorView 的 focusable——Dialog 窗口焦点必须能落到 DecorView，
                // 否则窗口输入焦点链断裂，EditText requestFocus 只生效在 view 树内（hasFocus=true）
                // 但无法成为 ViewRootImpl 的输入目标，served 停留在旧窗口（实测 served=false）。
                android.view.WindowManager.LayoutParams wlp = dialog.getWindow().getAttributes();
                if (wlp != null) {
                    wlp.flags &= ~android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
                    dialog.getWindow().setAttributes(wlp);
                }
                // show 后（decorView 挂载完成）为所有 EditText 注入"点击强制弹键盘"：
                // 自定义 layout 面板的 EditText 在 Dialog 内点击可能不自动唤起输入法，
                // 这里显式绑定触摸/点击 → requestFocus + showSoftInput（双保险）。
                final android.view.View decor = dialog.getWindow().getDecorView();
                decor.postDelayed(() -> {
                    try {
                        if (!dialog.isShowing()) return;
                        injectEditTextIme(decor, 0);
                    } catch (Throwable ignored) {
                    }
                }, 250);
            } catch (Throwable t) {
                Log.w(TAG, "配置弹窗输入法失败: " + t.getMessage());
            }
        }

        /** 递归为 view 树中所有 EditText 注入"点击强制唤起键盘"（仅兜底 OnClickListener）。
         *  注意：不覆盖已有 OnTouchListener——NativeLayoutRenderer.setupInputView 已为输入控件
         *  设置 ACTION_UP → showSoftInput 的完整链路（含 attach 等待，实测 served=true 键盘正常）；
         *  此前 injectEditTextIme 用 forceShowIme 整体覆盖 OnTouchListener，而 forceShowIme 缺少
         *  attach 等待导致 served=false、"is not served"（layout 控件输入法不唤起根因）。
         *  用 tag 标记（0x5A17F002）判断是否已由 setupInputView 处理，避免重复注入。 */
        private static final int TAG_KEY_IME_HANDLED = 0x5A17F002;

        private void injectEditTextIme(android.view.View view, int depth) {
            if (view == null || depth > 15) return;
            if (view instanceof android.widget.EditText) {
                final android.widget.EditText et = (android.widget.EditText) view;
                // 仅当未设置任何触摸/点击处理时才补 OnClickListener 兜底
                if (et.getTag(TAG_KEY_IME_HANDLED) == null) {
                    et.setTag(TAG_KEY_IME_HANDLED, Boolean.TRUE);
                    et.setOnClickListener(v -> forceShowIme(et));
                }
                return;
            }
            if (view instanceof android.view.ViewGroup) {
                android.view.ViewGroup vg = (android.view.ViewGroup) view;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    injectEditTextIme(vg.getChildAt(i), depth + 1);
                }
            }
        }

        /** 强制唤起键盘：requestFocus + restartInput 重建 IME 绑定 + showSoftInput，防抖 400ms。
         *  与 DebugLayoutInjectActivity 验证成功的 injectIme 保持一致：
         *  - showSoftInput flag=0（SHOW_FORCED 在部分 MIUI 版本上会被服务端忽略）
         *  - requestFocus 后 80ms 延迟再弹（等窗口输入焦点落定） */
        private void forceShowIme(final android.widget.EditText et) {
            try {
                long now = System.currentTimeMillis();
                Long last = (Long) et.getTag(0x5A17F001);
                if (last != null && now - last < 400) return;
                et.setTag(0x5A17F001, now);
                final android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) et.getContext()
                                .getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm == null) return;
                et.postDelayed(() -> {
                    try {
                        et.setFocusableInTouchMode(true);
                        if (!et.hasFocus()) {
                            et.requestFocus();
                        }
                        boolean shown = imm.showSoftInput(et, 0);
                        android.util.Log.i(TAG, "forceShowIme result=" + shown
                                + " focused=" + et.hasFocus() + " served=" + (imm.isActive(et)));
                        if (!shown || !imm.isActive(et)) {
                            // 首次失败：restartInput 强制以 et 为目标重建输入连接后重试
                            et.postDelayed(() -> {
                                try {
                                    et.requestFocus();
                                    try {
                                        imm.restartInput(et);
                                    } catch (Throwable ignored) {
                                    }
                                    imm.showSoftInput(et, 0);
                                } catch (Throwable ignored) {
                                }
                            }, 200);
                        }
                    } catch (Throwable ignored) {
                    }
                }, 80);
            } catch (Throwable ignored) {
            }
        }

        /** 聚焦 view 树中第一个 EditText */
        private void focusFirstInput(android.view.View view, int depth) {
            if (view == null || depth > 12) return;
            if (view instanceof android.widget.EditText) {
                view.requestFocus();
                return;
            }
            if (view instanceof android.view.ViewGroup) {
                android.view.ViewGroup vg = (android.view.ViewGroup) view;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    focusFirstInput(vg.getChildAt(i), depth + 1);
                }
            }
        }

        /**
         * 弹窗 ScrollView 触摸/焦点注入（"hit" 注入）：
         * 1. ScrollView 不抢焦点（focusable=false），否则 EditText 点击不聚焦；
         * 2. 触摸放行：DOWN/MOVE 不拦截（默认 ScrollView 对 EditText 会放行 DOWN，
         *    但滚动判定可能在 DOWN 后吞掉事件），这里显式放行子控件命中，
         *    仅保留 ScrollView 自身的滚动能力（返回 false 走默认 onTouchEvent）。
         */
        private void setupScrollViewForInput(final android.widget.ScrollView sv) {
            if (sv == null) return;
            sv.setFocusable(false);
            sv.setFocusableInTouchMode(false);
            // 触摸放行：DOWN 让子控件先接收（EditText 聚焦需要 DOWN），
            // 滚动由 ScrollView 默认逻辑处理（不消费事件，仅确保不拦截 DOWN 的命中传递）
            sv.setOnTouchListener((v, event) -> {
                if (event == null) return false;
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    // 通知父级不要拦截，确保子 EditText 收到 DOWN 完成聚焦
                    if (v.getParent() != null) {
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                return false; // 不消费：ScrollView 默认滚动逻辑保留
            });
        }

        /** 递归检测 view 树是否含输入控件 */
        private boolean containsInputView(android.view.View view, int depth) {
            if (view == null || depth > 12) return false;
            if (view instanceof android.widget.EditText
                    || view instanceof android.widget.Spinner
                    || view instanceof android.widget.SeekBar
                    || view instanceof android.widget.SearchView
                    || view instanceof android.widget.DatePicker
                    || view instanceof android.widget.TimePicker
                    || view instanceof android.widget.NumberPicker
                    || view instanceof android.webkit.WebView) {
                return true;
            }
            if (view instanceof android.view.ViewGroup) {
                android.view.ViewGroup vg = (android.view.ViewGroup) view;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    if (containsInputView(vg.getChildAt(i), depth + 1)) return true;
                }
            }
            return false;
        }

        /**
         * 语音识别专用组件：原生录音对话框。
         * 自动开始录音（MediaRecorder），显示计时；用户点"完成"或 autoClose 到点后停止，
         * 保存 m4a 到缓存目录，result 返回音频文件路径（供 voice_input recognize）。
         * 用户取消/返回键 → result="cancelled"。无录音权限 → result="cancelled:缺少录音权限"。
         */
        private void showVoiceRecorderDialog(final android.app.Activity act, final ComponentRuntime rt,
                                             final String componentId, final String title,
                                             final String message, final int autoCloseSeconds) {
            // 录音权限检查
            if (androidx.core.content.ContextCompat.checkSelfPermission(context,
                    android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                rt.result.set("cancelled:缺少录音权限");
                synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                return;
            }
            // 麦克风互斥：应用层正在录音时，Agent 不能同时录（防止串音/冲突）
            if (!com.oilquiz.app.ai.speech.SpeechManager.getInstance(context)
                    .tryAcquireRecording("agent")) {
                rt.result.set("cancelled:应用层正在录音，请先停止应用层录音再试");
                synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                return;
            }
            final java.io.File audioFile = new java.io.File(context.getCacheDir(),
                    "agent_voice_" + System.currentTimeMillis() + ".m4a");
            final android.media.MediaRecorder[] recorderRef = new android.media.MediaRecorder[1];
            final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            final int[] seconds = {0};
            final Runnable[] ticker = new Runnable[1];
            final boolean[] stopped = {false};

            android.widget.TextView statusView = new android.widget.TextView(act);
            statusView.setTextSize(18);
            statusView.setGravity(android.view.Gravity.CENTER);
            statusView.setPadding(0, 20, 0, 20);
            android.widget.Button doneBtn = new android.widget.Button(act);
            doneBtn.setText("完成");
            android.widget.LinearLayout layout = new android.widget.LinearLayout(act);
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            layout.setPadding(60, 20, 60, 20);
            layout.addView(statusView);
            layout.addView(doneBtn);

            final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(act)
                    .setTitle(title != null && !title.isEmpty() ? title : "🎤 请说话")
                    .setMessage(message != null && !message.isEmpty() ? message : "正在录音，说完请点「完成」")
                    .setView(layout)
                    .setCancelable(true)
                    .setOnCancelListener(d -> {
                        stopRecorder(recorderRef);
                        if (ticker[0] != null) handler.removeCallbacks(ticker[0]);
                        if (!stopped[0]) {
                            stopped[0] = true;
                            com.oilquiz.app.ai.speech.SpeechManager.getInstance(context).releaseRecording("agent");
                            rt.result.set("cancelled");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                        }
                    })
                    .create();
            rt.dialog = dialog;

            ticker[0] = () -> {
                if (!stopped[0]) {
                    seconds[0]++;
                    statusView.setText("🔴 录音中 " + String.format(java.util.Locale.US,
                            "%02d:%02d", seconds[0] / 60, seconds[0] % 60));
                    handler.postDelayed(ticker[0], 1000);
                }
            };

            doneBtn.setOnClickListener(v -> {
                if (stopped[0]) return;
                stopped[0] = true;
                if (ticker[0] != null) handler.removeCallbacks(ticker[0]);
                com.oilquiz.app.ai.speech.SpeechManager.getInstance(context).releaseRecording("agent");
                // 停止录音与文件 finalize 放后台线程：MediaRecorder.stop 同步阻塞且录音过短会抛异常，
                // 避免主线程卡顿；同时必须等录音真正启动（recorderRef 赋值）后再 stop，
                // 并校验文件有效性，防止把损坏/0 字节文件交给识别（"音频文件不包含音轨"）
                new Thread(() -> {
                    long deadline = System.currentTimeMillis() + 3000;
                    while (recorderRef[0] == null && System.currentTimeMillis() < deadline) {
                        try {
                            Thread.sleep(30);
                        } catch (InterruptedException ignored) {
                            break;
                        }
                    }
                    android.media.MediaRecorder r = recorderRef[0];
                    recorderRef[0] = null;
                    if (r != null) {
                        try {
                            r.stop();
                        } catch (Exception ignored) {
                        }
                        try {
                            r.release();
                        } catch (Exception ignored) {
                        }
                    }
                    boolean valid = audioFile.exists() && audioFile.length() > 1024;
                    act.runOnUiThread(() -> {
                        try {
                            if (dialog.isShowing()) dialog.dismiss();
                        } catch (Throwable ignored) {
                        }
                    });
                    rt.result.set(valid ? audioFile.getAbsolutePath()
                            : "cancelled:录音时长过短或未生成有效音频，请重新录音");
                    synchronized (rt.resultLock) {
                        rt.resultLock.notifyAll();
                    }
                }).start();
            });

            // 录音准备/启动放后台线程（MediaRecorder prepare 可能耗时）
            new Thread(() -> {
                try {
                    android.media.MediaRecorder recorder = new android.media.MediaRecorder();
                    recorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);
                    recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);
                    recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);
                    recorder.setAudioSamplingRate(44100);
                    recorder.setAudioEncodingBitRate(128000);
                    recorder.setOutputFile(audioFile.getAbsolutePath());
                    recorder.prepare();
                    recorder.start();
                    recorderRef[0] = recorder;
                    act.runOnUiThread(() -> {
                        if (act.isFinishing() || act.isDestroyed()) {
                            stopRecorder(recorderRef);
                            com.oilquiz.app.ai.speech.SpeechManager.getInstance(context).releaseRecording("agent");
                            rt.result.set("cancelled");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                            return;
                        }
                        configureDialogInput(dialog);

                        dialog.show();
                        statusView.setText("🔴 录音中 00:00");
                        handler.postDelayed(ticker[0], 1000);
                    });
                } catch (Exception e) {
                    Log.w(TAG, "录音启动失败: " + e.getMessage());
                    stopRecorder(recorderRef);
                    com.oilquiz.app.ai.speech.SpeechManager.getInstance(context).releaseRecording("agent");
                    rt.result.set("cancelled:录音启动失败");
                    synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                }
            }).start();

            // 录音上限：autoClose 秒后自动停止（语义=最长录音时长）
            if (autoCloseSeconds > 0) {
                handler.postDelayed(() -> {
                    if (!stopped[0]) {
                        doneBtn.performClick();
                    }
                }, autoCloseSeconds * 1000L);
            }
        }

        /**
         * 语音合成专用组件：原生播放对话框。
         * 自动用 SpeechManager 朗读文本（与应用层共享同一播放器，应用层"停止朗读"同样生效）；
         * 播放完成 → result="completed"；用户点停止/返回键 → 停止播放 + result="stopped"。
         * autoCloseSeconds>0 时作为最长播放时长。
         */
        private void showSpeechPlayerDialog(final android.app.Activity act, final ComponentRuntime rt,
                                            final String componentId, final String title,
                                            final String text, final int autoCloseSeconds) {
            if (text == null || text.trim().isEmpty()) {
                rt.result.set("cancelled:无朗读文本");
                synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                return;
            }
            final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

            android.widget.TextView statusView = new android.widget.TextView(act);
            statusView.setTextSize(16);
            statusView.setGravity(android.view.Gravity.CENTER);
            statusView.setPadding(20, 20, 20, 20);
            android.widget.Button stopBtn = new android.widget.Button(act);
            stopBtn.setText("⏹️ 停止");
            android.widget.LinearLayout layout = new android.widget.LinearLayout(act);
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            layout.setPadding(50, 10, 50, 20);
            layout.addView(statusView);
            layout.addView(stopBtn);

            final boolean[] finished = {false};

            // 播放完成/停止的统一收尾（幂等，线程安全：result.set + 主线程 dismiss）
            final java.util.function.Consumer<String> finisher = new java.util.function.Consumer<String>() {                @Override
                public void accept(String result) {
                    if (finished[0]) return;
                    finished[0] = true;
                    handler.post(() -> {
                        if (rt.dialog != null && rt.dialog.isShowing()) {
                            rt.dialog.dismiss();
                        }
                    });
                    rt.result.set(result);
                    synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                }
            };

            final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(act)
                    .setTitle(title != null && !title.isEmpty() ? title : "🔊 正在朗读")
                    .setView(layout)
                    .setCancelable(true)
                    .setOnCancelListener(d -> {
                        com.oilquiz.app.ai.speech.SpeechManager.getInstance(context).stopSpeaking();
                        finisher.accept("stopped");
                    })
                    .create();
            rt.dialog = dialog;

            stopBtn.setOnClickListener(v -> {
                com.oilquiz.app.ai.speech.SpeechManager.getInstance(context).stopSpeaking();
                finisher.accept("stopped");
            });

            final com.oilquiz.app.ai.speech.SpeechManager speech =
                    com.oilquiz.app.ai.speech.SpeechManager.getInstance(context);
            speech.speak(text, new com.oilquiz.app.ai.speech.TTSService.PlaybackCallback() {
                @Override
                public void onStart() {
                    handler.post(() -> {
                        if (!finished[0] && act != null && !act.isFinishing()) {
                            configureDialogInput(dialog);

                            dialog.show();
                            statusView.setText("🔊 正在朗读...（点「停止」或应用层停止按钮可中断）");
                        }
                    });
                }

                @Override
                public void onComplete() {
                    finisher.accept("completed");
                }

                @Override
                public void onError(String message) {
                    finisher.accept("error:" + (message != null ? message : "播放失败"));
                }
            });

            // 最长播放时长（autoClose 语义）
            if (autoCloseSeconds > 0) {
                handler.postDelayed(() -> {
                    if (!finished[0]) {
                        speech.stopSpeaking();
                    }
                }, autoCloseSeconds * 1000L);
            }
        }

        /** 停止并释放 MediaRecorder（幂等） */
        private void stopRecorder(android.media.MediaRecorder[] ref) {
            android.media.MediaRecorder r = ref[0];
            ref[0] = null;
            if (r != null) {
                try { r.stop(); } catch (Exception ignored) { }
                try { r.release(); } catch (Exception ignored) { }
            }
        }

        /** 更新组件：progress 更新进度/消息；dialog 更新标题/内容；notification 更新通知内容/进度；插件组件传 props 动态刷新。 */
        /**
         * P1：按创建参数快照 + 新 props 真重建组件弹窗（保留 component_id，等效 onRefresh）。
         * 旧 rt 从注册表移除后复用同一 id 重新 createComponent，弹窗按新 props 渲染，
         * 插件监控（monitor）也随重建重新启动。
         */
        private Map<String, Object> rebuildComponent(String componentId, ComponentRuntime oldRt, String newProps) {
            Map<String, Object> reply = new HashMap<>();
            String type = oldRt.createType != null ? oldRt.createType : "dialog";
            java.util.Map<String, Object> args = oldRt.createArgs;
            // 先关闭旧弹窗（主线程），避免新旧弹窗叠屏；随后 createComponent 重建
            if (oldRt.dialog != null) {
                final android.app.Dialog oldD = oldRt.dialog;
                oldRt.dialog = null;
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    try {
                        if (oldD.isShowing()) oldD.dismiss();
                    } catch (Throwable ignored) {
                    }
                });
            }
            // 移除旧 rt：createComponent 对已存在 id 会报"组件ID已存在"
            dynamicComponents.remove(componentId);
            String title = strArg(args, "title");
            String message = strArg(args, "message");
            String dialogType = strArg(args, "dialog_type");
            if (dialogType.isEmpty()) dialogType = "info";
            int max0 = intArg(args, "max");
            String options = strArg(args, "options");
            String defaultValue = strArg(args, "default_value");
            String inputHint = strArg(args, "input_hint");
            String actionLabel = strArg(args, "action_label");
            String items = strArg(args, "items");
            String url = strArg(args, "url");
            String clickAction = strArg(args, "click_action");
            int autoClose = intArg(args, "auto_close");
            String html = strArg(args, "html");
            java.util.Map<String, Object> rebuilt = createComponent(type, componentId, title, message,
                    dialogType, max0, options, defaultValue, inputHint, newProps, actionLabel, items, url,
                    clickAction, autoClose, html);
            boolean ok = Boolean.TRUE.equals(rebuilt.get("success"))
                    || "true".equalsIgnoreCase(String.valueOf(rebuilt.get("success")));
            if (ok) {
                reply.put("success", true);
                reply.put("component_id", componentId);
                reply.put("result", "refreshed");
                reply.put("message", "组件已按新参数重建刷新: " + componentId);
            } else {
                reply.put("success", false);
                reply.put("result", "refresh_failed");
                reply.put("component_id", componentId);
                reply.put("message", "组件刷新失败: " + rebuilt.get("message"));
            }
            return reply;
        }

        private static String strArg(java.util.Map<String, Object> args, String key) {
            Object v = args.get(key);
            return v == null ? "" : String.valueOf(v);
        }

        private static int intArg(java.util.Map<String, Object> args, String key) {
            Object v = args.get(key);
            if (v instanceof Number) return ((Number) v).intValue();
            try {
                return Integer.parseInt(String.valueOf(v).trim());
            } catch (Exception e) {
                return 0;
            }
        }

        private Map<String, Object> updateComponent(String componentId, String title,
                                                    String message, int progress, int max,
                                                    String propsJson) {
            Map<String, Object> reply = new HashMap<>();
            ComponentRuntime rt = dynamicComponents.get(componentId);
            if (rt == null) {
                reply.put("success", false);
                reply.put("message", "组件不存在: " + componentId);
                return reply;
            }
            // P2：聊天流卡片（无对话框实例、非通知组件）不支持 update——明确报错，不再误发系统通知
            if (rt.dialog == null && !"notification".equals(rt.createType)) {
                reply.put("success", false);
                reply.put("result", "not_supported");
                reply.put("message", "该组件是聊天流卡片（无对话框实例），不支持 update 刷新；"
                        + "如需更新内容请重新 create，或通过组件 actions 按钮交互: " + componentId);
                return reply;
            }
            // P1：插件/注册类型/内置弹窗等组件 update 传 props → 真重建（保留 component_id，等效 onRefresh）。
            // progress 组件除外：进度应在原实例上增量更新，重建会重置进度。
            if (propsJson != null && !propsJson.trim().isEmpty() && rt.dialog != null
                    && !"progress".equals(rt.createType)) {
                return rebuildComponent(componentId, rt, propsJson.trim());
            }
            // progress 组件兼容：模型常把 progress/message/title/max 放 props 内（而非顶层参数）。
            // 顶层已传的值优先，props 内缺失的字段从 props 兜底，否则进度条 update 不更新。
            if (propsJson != null && !propsJson.trim().isEmpty()) {
                try {
                    org.json.JSONObject pj = new org.json.JSONObject(propsJson.trim());
                    if (progress < 0 && pj.has("progress")) {
                        progress = Math.max(0, pj.optInt("progress", 0));
                    }
                    if (pj.has("max")) {
                        max = pj.optInt("max", max);
                    }
                    if ((title == null || title.isEmpty()) && pj.has("title")) {
                        title = pj.optString("title", "");
                    }
                    if ((message == null || message.isEmpty()) && pj.has("message")) {
                        message = pj.optString("message", "");
                    }
                } catch (Exception ignored) {
                }
            }
            // final 副本：下方 lambda（主线程更新）引用
            final String fTitle = title;
            final String fMessage = message;
            final int fProgress = progress;
            final int fMax = max;
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
                                .setContentTitle(fTitle != null && !fTitle.isEmpty() ? fTitle : "AI 通知")
                                .setContentText(fMessage != null ? fMessage : "")
                                .setContentIntent(pi)
                                .setAutoCancel(true);
                        if (fMax > 0 && fProgress >= 0) {
                            builder.setProgress(fMax, fProgress, false);
                        }
                        nm.notify(componentId.hashCode(), builder.build());
                        if (fProgress >= 100 && fMax > 0) {
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
                        if (fMax > 0) pd.setMax(fMax);
                        if (fProgress >= 0) pd.setProgress(fProgress);
                        if (fTitle != null && !fTitle.isEmpty()) pd.setTitle(fTitle);
                        if (fMessage != null && !fMessage.isEmpty()) pd.setMessage(fMessage);
                        // 到 max（默认100）自动关闭（保留注册表条目供 get_result 查询 completed）
                        int target = pd.getMax() > 0 ? pd.getMax() : 100;
                        if (fProgress >= target) {
                            cancelAutoClose(rt);
                            pd.dismiss();
                            rt.dialog = null;
                            rt.result.compareAndSet("pending", "completed");
                            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
                            Log.i(TAG, "[Python component] progress auto closed at max: " + componentId);
                        }
                    } else if (d instanceof android.app.AlertDialog) {
                        android.app.AlertDialog ad = (android.app.AlertDialog) d;
                        if (fTitle != null && !fTitle.isEmpty()) ad.setTitle(fTitle);
                        if (fMessage != null && !fMessage.isEmpty()) ad.setMessage(fMessage);
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

        /** 关闭全部动态组件（Agent 任务结束兜底：防止创建后未 close 的组件残留卡界面）。 */
        public void closeAllComponents() {
            for (String id : new java.util.ArrayList<>(dynamicComponents.keySet())) {
                try {
                    closeComponent(id);
                } catch (Throwable ignored) {
                }
            }
            Log.i(TAG, "[Python component] closed all dynamic components, remaining=" + dynamicComponents.size());
        }

        /** UI-07 组件生命周期管理：列出全部存活组件（id/类型/结果状态），供模型查询与批量管理 */
        public Map<String, Object> listComponents() {
            Map<String, Object> reply = new HashMap<>();
            java.util.List<Map<String, Object>> items = new java.util.ArrayList<>();
            for (Map.Entry<String, ComponentRuntime> e : dynamicComponents.entrySet()) {
                ComponentRuntime rt = e.getValue();
                if (rt == null) continue;
                Map<String, Object> item = new HashMap<>();
                item.put("component_id", e.getKey());
                item.put("type", rt.createType != null ? rt.createType : "");
                item.put("result", rt.result != null ? rt.result.get() : "");
                item.put("alive", !"closed".equals(rt.result != null ? rt.result.get() : ""));
                items.add(item);
            }
            items.sort((a, b) -> String.valueOf(a.get("component_id")).compareTo(String.valueOf(b.get("component_id"))));
            reply.put("success", true);
            reply.put("count", items.size());
            reply.put("components", items);
            reply.put("message", items.isEmpty() ? "当前无存活组件" : "当前存活组件 " + items.size() + " 个，可用 close_component(component_id=...) 逐个关闭或 close_all_components 全部关闭");
            return reply;
        }

        /** 注册无对话框的待处理组件（聊天流内置组件用），结果由外部回调写入 */
        public boolean registerPendingComponent(String componentId) {
            if (componentId == null || componentId.isEmpty()) return false;
            ComponentRuntime rt = dynamicComponents.get(componentId);
            if (rt == null) {
                rt = new ComponentRuntime();
                rt.createType = "chat_card";
                dynamicComponents.put(componentId, rt);
            } else if (rt.createType == null) {
                rt.createType = "chat_card";
            }
            return true;
        }

        /** 写入组件结果并唤醒等待者（聊天流组件按钮回调） */
        public void setComponentResult(String componentId, String value) {
            Log.i(TAG, "[Python component] setComponentResult cid=" + componentId + " value=" + value);
            ComponentRuntime rt = dynamicComponents.get(componentId);
            if (rt == null) {
                // 历史会话/重启后注册表可能丢失该组件：自动重新注册，保证点击结果仍可被 Agent get_result 取回
                Log.w(TAG, "[Python component] setComponentResult 组件不存在, 自动重注册: " + componentId);
                rt = new ComponentRuntime();
                dynamicComponents.put(componentId, rt);
            }
            rt.result.set(value != null ? value : "closed");
            synchronized (rt.resultLock) { rt.resultLock.notifyAll(); }
        }

        /** 获取组件结果：pending（未点击）/positive/negative/cancelled/completed；wait_seconds>0 时阻塞等待。
         *  等待期间 20 秒提醒一次"Agent 正在等你操作"，超时后 Toast 提醒交互超时。 */
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
                final long[] lastReminder = {0L};
                synchronized (rt.resultLock) {
                    long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
                    while ("pending".equals(rt.result.get()) && System.currentTimeMillis() < deadline) {
                        try {
                            rt.resultLock.wait(Math.max(1, deadline - System.currentTimeMillis()));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        // 等待中提醒：每 20 秒提示用户 Agent 在等操作（仅仍 pending 时）
                        if ("pending".equals(rt.result.get())) {
                            long now = System.currentTimeMillis();
                            if (now - lastReminder[0] >= 20_000L) {
                                lastReminder[0] = now;
                                showToast("⏳ Agent 正在等待你的操作，请点击组件按钮", true);
                            }
                        }
                    }
                }
                // 超时仍未操作：明确提醒
                if ("pending".equals(rt.result.get())) {
                    showToast("⏰ 交互等待超时（" + waitSeconds + "秒），已告知 Agent 你未操作", true);
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

        /** 判断是否本地文件路径（file:// 前缀、/ 开头绝对路径、常见网页扩展名） */
        private static boolean isLocalFilePath(String s) {
            if (s == null || s.isEmpty()) return false;
            if (s.startsWith("file://") || s.startsWith("/")) return true;
            String lower = s.toLowerCase();
            return lower.endsWith(".html") || lower.endsWith(".htm")
                    || lower.endsWith(".xhtml") || lower.endsWith(".mht")
                    || lower.endsWith(".svg");
        }
    }
}

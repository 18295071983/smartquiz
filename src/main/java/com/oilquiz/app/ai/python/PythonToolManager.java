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
            
            initialized = true;
            Log.i(TAG, "Python tool manager initialized successfully");
            
            logPythonInfo();
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
            PyObject successObj = pyResult.get("success");
            result.success = successObj != null && successObj.toBoolean();
            
            PyObject resultObj = pyResult.get("result");
            if (resultObj != null) {
                result.result = resultObj.toString();
            }
            
            PyObject stdoutObj = pyResult.get("stdout");
            if (stdoutObj != null) {
                result.stdout = stdoutObj.toString();
            }
            
            PyObject stderrObj = pyResult.get("stderr");
            if (stderrObj != null) {
                result.stderr = stderrObj.toString();
            }
            
            PyObject errorObj = pyResult.get("error");
            if (errorObj != null) {
                result.error = errorObj.toString();
            }
            
            PyObject codeObj = pyResult.get("code");
            if (codeObj != null) {
                result.code = codeObj.toString();
            }
            
            PyObject attemptsObj = pyResult.get("attempts");
            if (attemptsObj != null) {
                result.attempts = attemptsObj.toInt();
            }
            
            PyObject fixesObj = pyResult.get("fixes");
            if (fixesObj != null) {
                try {
                    result.fixes = new ArrayList<>();
                    for (PyObject fix : fixesObj.asList()) {
                        Map<String, String> fixMap = new HashMap<>();
                        PyObject typeObj = fix.get("error_type");
                        if (typeObj != null) {
                            fixMap.put("error_type", typeObj.toString());
                        }
                        result.fixes.add(fixMap);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to parse fixes: " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse execution result: " + e.getMessage(), e);
            result.success = false;
            result.error = "Failed to parse result: " + e.getMessage();
        }
        
        return result;
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
                    
                    PyObject nameObj = toolObj.get("name");
                    if (nameObj != null) {
                        tool.name = nameObj.toString();
                    }
                    
                    PyObject filenameObj = toolObj.get("filename");
                    if (filenameObj != null) {
                        tool.filename = filenameObj.toString();
                    }
                    
                    PyObject pathObj = toolObj.get("path");
                    if (pathObj != null) {
                        tool.path = pathObj.toString();
                    }
                    
                    PyObject modifiedObj = toolObj.get("modified");
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
}

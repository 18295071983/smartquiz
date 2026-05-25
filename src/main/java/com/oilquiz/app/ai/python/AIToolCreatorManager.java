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

public class AIToolCreatorManager {
    private static final String TAG = "AIToolCreatorManager";
    private static volatile AIToolCreatorManager instance;
    
    private final Context context;
    private Python python;
    private PyObject creatorModule;
    private PyObject creator;
    private boolean initialized = false;
    
    private AIToolCreatorManager(Context context) {
        this.context = context.getApplicationContext();
    }
    
    public static AIToolCreatorManager getInstance(Context context) {
        if (instance == null) {
            synchronized (AIToolCreatorManager.class) {
                if (instance == null) {
                    instance = new AIToolCreatorManager(context);
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
            }
            
            python = Python.getInstance();
            
            Log.i(TAG, "Loading ai_tool_creator module...");
            creatorModule = python.getModule("ai_tool_creator");
            
            Log.i(TAG, "Getting tool creator instance...");
            creator = creatorModule.callAttr("get_creator", context);
            
            if (creator == null) {
                Log.e(TAG, "Failed to get tool creator instance");
                return false;
            }
            
            initialized = true;
            Log.i(TAG, "AI Tool Creator Manager initialized successfully");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize AI Tool Creator Manager: " + e.getMessage(), e);
            return false;
        }
    }
    
    public boolean isInitialized() {
        return initialized;
    }
    
    public ToolAnalysisResult analyzeTaskForTool(String task, List<String> existingTools) {
        if (!initialized) {
            if (!initialize()) {
                return new ToolAnalysisResult(false, "Tool creator not initialized");
            }
        }
        
        try {
            Log.i(TAG, "Analyzing task for tool: " + task.substring(0, Math.min(100, task.length())));
            
            PyObject pyTools = PyObject.fromJava(existingTools);
            PyObject result = creator.callAttr("analyze_task_for_tool", task, pyTools);
            
            if (result == null) {
                return new ToolAnalysisResult(false, "No result returned");
            }
            
            return parseToolAnalysisResult(result);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to analyze task: " + e.getMessage(), e);
            return new ToolAnalysisResult(false, e.getMessage());
        }
    }
    
    @SuppressWarnings("unchecked")
    private ToolAnalysisResult parseToolAnalysisResult(PyObject pyResult) {
        ToolAnalysisResult result = new ToolAnalysisResult();
        
        try {
            PyObject needsObj = pyResult.get("needs_new_tool");
            result.needsNewTool = needsObj != null && needsObj.toBoolean();
            
            PyObject nameObj = pyResult.get("tool_name");
            if (nameObj != null) {
                result.toolName = nameObj.toString();
            }
            
            PyObject descObj = pyResult.get("tool_description");
            if (descObj != null) {
                result.toolDescription = descObj.toString();
            }
            
            PyObject paramsObj = pyResult.get("parameters");
            if (paramsObj != null) {
                result.parameters = new HashMap<>();
                for (PyObject key : paramsObj.asMap().keySet()) {
                    String keyStr = key.toString();
                    PyObject valueObj = paramsObj.callAttr("__getitem__", key);
                    if (valueObj != null) {
                        result.parameters.put(keyStr, valueObj.toString());
                    }
                }
            }
            
            PyObject reasonObj = pyResult.get("reason");
            if (reasonObj != null) {
                result.reason = reasonObj.toString();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse analysis result: " + e.getMessage(), e);
            result.error = "Failed to parse result: " + e.getMessage();
        }
        
        return result;
    }
    
    public ToolCreationResult createTool(String name, String description,
                                         Map<String, String> parameters,
                                         List<String> examples,
                                         String code) {
        if (!initialized) {
            if (!initialize()) {
                return new ToolCreationResult(false, name, "Tool creator not initialized");
            }
        }
        
        try {
            Log.i(TAG, "Creating tool: " + name);
            
            PyObject pyParams = parameters != null ? PyObject.fromJava(parameters) : null;
            PyObject pyExamples = examples != null ? PyObject.fromJava(examples) : null;
            
            PyObject result;
            if (code != null && !code.isEmpty()) {
                result = creator.callAttr("create_tool", name, description, pyParams, pyExamples, code);
            } else if (pyParams != null) {
                result = creator.callAttr("create_tool", name, description, pyParams, pyExamples);
            } else {
                result = creator.callAttr("create_tool", name, description);
            }
            
            if (result == null) {
                return new ToolCreationResult(false, name, "No result returned");
            }
            
            return parseToolCreationResult(result);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to create tool: " + e.getMessage(), e);
            return new ToolCreationResult(false, name, e.getMessage());
        }
    }
    
    @SuppressWarnings("unchecked")
    private ToolCreationResult parseToolCreationResult(PyObject pyResult) {
        ToolCreationResult result = new ToolCreationResult();
        
        try {
            PyObject successObj = pyResult.get("success");
            result.success = successObj != null && successObj.toBoolean();
            
            PyObject nameObj = pyResult.get("tool_name");
            if (nameObj != null) {
                result.toolName = nameObj.toString();
            }
            
            PyObject errorObj = pyResult.get("error");
            if (errorObj != null) {
                result.error = errorObj.toString();
            }
            
            PyObject attemptsObj = pyResult.get("attempts");
            if (attemptsObj != null) {
                result.attempts = attemptsObj.toInt();
            }
            
            PyObject specObj = pyResult.get("spec");
            if (specObj != null) {
                result.spec = parseToolSpec(specObj);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse creation result: " + e.getMessage(), e);
            result.error = "Failed to parse result: " + e.getMessage();
        }
        
        return result;
    }
    
    @SuppressWarnings("unchecked")
    private ToolSpec parseToolSpec(PyObject pySpec) {
        ToolSpec spec = new ToolSpec();
        
        try {
            PyObject nameObj = pySpec.get("name");
            if (nameObj != null) {
                spec.name = nameObj.toString();
            }
            
            PyObject descObj = pySpec.get("description");
            if (descObj != null) {
                spec.description = descObj.toString();
            }
            
            PyObject codeObj = pySpec.get("code");
            if (codeObj != null) {
                spec.code = codeObj.toString();
            }
            
            PyObject categoryObj = pySpec.get("category");
            if (categoryObj != null) {
                spec.category = categoryObj.toString();
            }
            
            PyObject versionObj = pySpec.get("version");
            if (versionObj != null) {
                spec.version = versionObj.toString();
            }
            
            PyObject paramsObj = pySpec.get("parameters");
            if (paramsObj != null) {
                spec.parameters = new HashMap<>();
                for (PyObject key : paramsObj.asMap().keySet()) {
                    String keyStr = key.toString();
                    PyObject valueObj = paramsObj.callAttr("__getitem__", key);
                    if (valueObj != null) {
                        spec.parameters.put(keyStr, valueObj.toString());
                    }
                }
            }
            
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse tool spec: " + e.getMessage());
        }
        
        return spec;
    }
    
    public List<ToolInfo> listTools() {
        List<ToolInfo> tools = new ArrayList<>();
        
        if (!initialized) {
            return tools;
        }
        
        try {
            PyObject toolsObj = creator.callAttr("list_tools");
            if (toolsObj != null) {
                for (PyObject toolObj : toolsObj.asList()) {
                    ToolInfo tool = parseToolInfo(toolObj);
                    if (tool.name != null) {
                        tools.add(tool);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to list tools: " + e.getMessage(), e);
        }
        
        return tools;
    }
    
    private ToolInfo parseToolInfo(PyObject pyTool) {
        ToolInfo info = new ToolInfo();
        
        try {
            PyObject nameObj = pyTool.get("name");
            if (nameObj != null) {
                info.name = nameObj.toString();
            }
            
            PyObject descObj = pyTool.get("description");
            if (descObj != null) {
                info.description = descObj.toString();
            }
            
            PyObject categoryObj = pyTool.get("category");
            if (categoryObj != null) {
                info.category = categoryObj.toString();
            }
            
            PyObject versionObj = pyTool.get("version");
            if (versionObj != null) {
                info.version = versionObj.toString();
            }
            
            PyObject paramsObj = pyTool.get("parameters");
            if (paramsObj != null) {
                info.parameters = new HashMap<>();
                for (PyObject key : paramsObj.asMap().keySet()) {
                    String keyStr = key.toString();
                    PyObject valueObj = paramsObj.callAttr("__getitem__", key);
                    if (valueObj != null) {
                        info.parameters.put(keyStr, valueObj.toString());
                    }
                }
            }
            
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse tool info: " + e.getMessage());
        }
        
        return info;
    }
    
    public PythonToolManager.ExecutionResult executeTool(String name, Map<String, Object> parameters) {
        if (!initialized) {
            if (!initialize()) {
                return new PythonToolManager.ExecutionResult(false, null, "Tool creator not initialized");
            }
        }
        
        try {
            Log.i(TAG, "Executing AI tool: " + name);
            
            PyObject pyParams = parameters != null ? PyObject.fromJava(parameters) : null;
            
            PyObject result;
            if (pyParams != null) {
                result = creator.callAttr("execute_tool", name, pyParams);
            } else {
                result = creator.callAttr("execute_tool", name);
            }
            
            if (result == null) {
                return new PythonToolManager.ExecutionResult(false, null, "No result returned");
            }
            
            return parseExecutionResult(result);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to execute tool: " + e.getMessage(), e);
            return new PythonToolManager.ExecutionResult(false, null, e.getMessage());
        }
    }
    
    @SuppressWarnings("unchecked")
    private PythonToolManager.ExecutionResult parseExecutionResult(PyObject pyResult) {
        PythonToolManager.ExecutionResult result = new PythonToolManager.ExecutionResult();
        
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
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse execution result: " + e.getMessage(), e);
            result.error = "Failed to parse result: " + e.getMessage();
        }
        
        return result;
    }
    
    public boolean deleteTool(String name) {
        if (!initialized) {
            return false;
        }
        
        try {
            PyObject result = creator.callAttr("delete_tool", name);
            return result != null && result.toBoolean();
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete tool: " + e.getMessage(), e);
            return false;
        }
    }
    
    public static class ToolAnalysisResult {
        public boolean needsNewTool;
        public String toolName;
        public String toolDescription;
        public Map<String, String> parameters;
        public String reason;
        public String error;
        
        public ToolAnalysisResult() {
            this.needsNewTool = false;
            this.toolName = null;
            this.toolDescription = null;
            this.parameters = new HashMap<>();
            this.reason = "";
            this.error = null;
        }
        
        public ToolAnalysisResult(boolean needsNewTool, String error) {
            this.needsNewTool = needsNewTool;
            this.error = error;
            this.parameters = new HashMap<>();
        }
        
        @Override
        public String toString() {
            return "ToolAnalysisResult{" +
                    "needsNewTool=" + needsNewTool +
                    ", toolName='" + toolName + '\'' +
                    ", reason='" + reason + '\'' +
                    ", error='" + error + '\'' +
                    '}';
        }
    }
    
    public static class ToolCreationResult {
        public boolean success;
        public String toolName;
        public String error;
        public int attempts;
        public ToolSpec spec;
        
        public ToolCreationResult() {
            this.success = false;
            this.attempts = 0;
        }
        
        public ToolCreationResult(boolean success, String toolName, String error) {
            this.success = success;
            this.toolName = toolName;
            this.error = error;
            this.attempts = 0;
        }
        
        @Override
        public String toString() {
            return "ToolCreationResult{" +
                    "success=" + success +
                    ", toolName='" + toolName + '\'' +
                    ", error='" + error + '\'' +
                    ", attempts=" + attempts +
                    '}';
        }
    }
    
    public static class ToolSpec {
        public String name;
        public String description;
        public Map<String, String> parameters;
        public String code;
        public String category;
        public String version;
        
        public ToolSpec() {
            this.parameters = new HashMap<>();
            this.category = "general";
            this.version = "1.0.0";
        }
    }
    
    public static class ToolInfo {
        public String name;
        public String description;
        public String category;
        public String version;
        public Map<String, String> parameters;
        
        public ToolInfo() {
            this.parameters = new HashMap<>();
        }
        
        @Override
        public String toString() {
            return "ToolInfo{" +
                    "name='" + name + '\'' +
                    ", description='" + description + '\'' +
                    ", category='" + category + '\'' +
                    ", version='" + version + '\'' +
                    '}';
        }
    }
}

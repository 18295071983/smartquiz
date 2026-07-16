package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import com.oilquiz.app.SmartQuizApplication;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具依赖检查与执行器
 * 
 * 功能：
 * 1. 在执行工具前检查依赖条件
 * 2. 自动执行前置工具（如权限请求）
 * 3. 生成依赖链执行报告
 * 
 * 使用示例：
 * - LocationTool 依赖定位权限，检查未授予时自动调用 permission_manager 请求
 */
public class ToolDependencyChecker {
    private static final String TAG = "ToolDependencyChecker";
    
    private final Context context;
    private final AIToolManager toolManager;
    
    public ToolDependencyChecker(Context context, AIToolManager toolManager) {
        this.context = context.getApplicationContext();
        this.toolManager = toolManager;
    }
    
    /**
     * 依赖检查结果
     */
    public static class DependencyCheckResult {
        public final boolean allSatisfied;
        public final List<PreToolCall> requiredPreCalls;
        public final List<String> unresolvableDeps;
        
        public DependencyCheckResult(boolean allSatisfied, List<PreToolCall> preCalls, List<String> unresolvableDeps) {
            this.allSatisfied = allSatisfied;
            this.requiredPreCalls = preCalls;
            this.unresolvableDeps = unresolvableDeps;
        }
    }
    
    /**
     * 前置工具调用
     */
    public static class PreToolCall {
        public final String toolName;
        public final Map<String, Object> params;
        public final ToolSchemaExtractor.ToolDependency dependency;
        public boolean executed;
        public boolean success;
        public String result;
        
        public PreToolCall(String toolName, Map<String, Object> params, ToolSchemaExtractor.ToolDependency dep) {
            this.toolName = toolName;
            this.params = params;
            this.dependency = dep;
            this.executed = false;
            this.success = false;
            this.result = null;
        }
    }
    
    /**
     * 检查工具依赖并返回需要执行的前置工具调用
     */
    public DependencyCheckResult checkDependencies(String toolName, Map<String, Object> originalParams) {
        List<PreToolCall> preCalls = new ArrayList<>();
        List<String> unresolvableDeps = new ArrayList<>();
        
        AITool tool = toolManager.getTool(toolName);
        if (tool == null) {
            return new DependencyCheckResult(true, preCalls, unresolvableDeps);
        }
        
        List<ToolSchemaExtractor.ToolDependency> dependencies = ToolSchemaExtractor.extractDependencies(tool);
        if (dependencies.isEmpty()) {
            return new DependencyCheckResult(true, preCalls, unresolvableDeps);
        }
        
        // 构建权限状态上下文
        Map<String, Object> context = buildPermissionContext();
        
        for (ToolSchemaExtractor.ToolDependency dep : dependencies) {
            if (dep.isConditionMet(context)) {
                Map<String, Object> args = dep.buildArgs();
                PreToolCall preCall = new PreToolCall(dep.tool, args, dep);
                preCalls.add(preCall);
                Log.i(TAG, "Dependency requires pre-call: " + dep.tool + " (action=" + dep.action + ", condition=" + dep.condition + ")");
            }
        }
        
        boolean allSatisfied = preCalls.isEmpty() && unresolvableDeps.isEmpty();
        return new DependencyCheckResult(allSatisfied, preCalls, unresolvableDeps);
    }
    
    /**
     * 执行前置工具调用链
     * @return 所有前置调用是否成功
     */
    public boolean executePreCalls(List<PreToolCall> preCalls) {
        if (preCalls == null || preCalls.isEmpty()) {
            return true;
        }
        
        boolean allSuccess = true;
        for (PreToolCall preCall : preCalls) {
            if (!preCall.executed) {
                boolean success = executePreCall(preCall);
                if (!success && preCall.dependency.blockOnFailure) {
                    allSuccess = false;
                    Log.w(TAG, "Pre-call failed and blockOnFailure=true: " + preCall.toolName);
                    break;
                }
            }
        }
        
        return allSuccess;
    }
    
    /**
     * 执行单个前置工具调用
     */
    private boolean executePreCall(PreToolCall preCall) {
        preCall.executed = true;
        
        if (!toolManager.hasTool(preCall.toolName)) {
            preCall.result = "Tool not found: " + preCall.toolName;
            preCall.success = false;
            Log.w(TAG, preCall.result);
            return false;
        }
        
        try {
            Log.i(TAG, "Executing pre-call: " + preCall.toolName + " with params: " + preCall.params);
            AIToolResult result = toolManager.executeTool(preCall.toolName, preCall.params);
            preCall.success = result.isSuccess();
            preCall.result = result.getResult().toString();
            Log.i(TAG, "Pre-call result: success=" + preCall.success);
            return preCall.success;
        } catch (Exception e) {
            preCall.result = "Error: " + e.getMessage();
            preCall.success = false;
            Log.e(TAG, "Pre-call error: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 构建权限状态上下文
     */
    private Map<String, Object> buildPermissionContext() {
        Map<String, Object> context = new HashMap<>();
        
        // 检查常用权限状态
        String[] permissions = {
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.READ_EXTERNAL_STORAGE",
        };
        
        for (String permission : permissions) {
            int result = this.context.checkSelfPermission(permission);
            boolean granted = result == PackageManager.PERMISSION_GRANTED;
            String shortName = getShortPermissionName(permission);
            
            // 英文短名
            context.put("permission_" + shortName, granted);
            // 完整权限名
            context.put("permission_" + permission, granted);
            
            // 中文权限名映射（与 PermissionManagerTool 的 PERMISSION_MAP 保持一致）
            addChinesePermissionName(context, permission, granted);
        }
        
        return context;
    }
    
    private void addChinesePermissionName(Map<String, Object> context, String permission, boolean granted) {
        if (permission.contains("ACCESS_FINE_LOCATION") || permission.contains("ACCESS_COARSE_LOCATION")) {
            context.put("permission_位置", granted);
            context.put("permission_定位", granted);
            context.put("permission_精确位置", granted);
            context.put("permission_粗略位置", granted);
            if (permission.contains("BACKGROUND_LOCATION")) {
                context.put("permission_后台定位", granted);
            }
        } else if (permission.contains("CAMERA")) {
            context.put("permission_camera", granted);
        } else if (permission.contains("RECORD_AUDIO")) {
            context.put("permission_录音", granted);
            context.put("permission_麦克风", granted);
        } else if (permission.contains("WRITE_EXTERNAL_STORAGE") || permission.contains("READ_EXTERNAL_STORAGE")) {
            context.put("permission_存储", granted);
            context.put("permission_读取存储", granted);
        } else if (permission.contains("SEND_SMS")) {
            context.put("permission_发送短信", granted);
        } else if (permission.contains("READ_SMS")) {
            context.put("permission_读取短信", granted);
        } else if (permission.contains("CALL_PHONE")) {
            context.put("permission_拨打电话", granted);
        } else if (permission.contains("READ_CONTACTS")) {
            context.put("permission_读取联系人", granted);
        } else if (permission.contains("WRITE_CONTACTS")) {
            context.put("permission_写入联系人", granted);
        } else if (permission.contains("READ_PHONE_STATE")) {
            context.put("permission_电话状态", granted);
        } else if (permission.contains("READ_CALL_LOG")) {
            context.put("permission_读取通话记录", granted);
        } else if (permission.contains("WRITE_CALL_LOG")) {
            context.put("permission_写入通话记录", granted);
        } else if (permission.contains("BLUETOOTH")) {
            context.put("permission_蓝牙", granted);
        } else if (permission.contains("REQUEST_INSTALL_PACKAGES")) {
            context.put("permission_安装应用", granted);
        } else if (permission.contains("SYSTEM_ALERT_WINDOW")) {
            context.put("permission_悬浮窗", granted);
        } else if (permission.contains("WAKE_LOCK")) {
            context.put("permission_唤醒锁定", granted);
        } else if (permission.contains("ACCESS_NETWORK_STATE")) {
            context.put("permission_网络状态", granted);
        } else if (permission.contains("ACCESS_WIFI_STATE")) {
            context.put("permission_WiFi状态", granted);
        } else if (permission.contains("CHANGE_NETWORK_STATE")) {
            context.put("permission_更改网络状态", granted);
        } else if (permission.contains("CHANGE_WIFI_STATE")) {
            context.put("permission_更改WiFi状态", granted);
        } else if (permission.contains("RECEIVE_BOOT_COMPLETED")) {
            context.put("permission_开机自启", granted);
        }
    }
    
    private String getShortPermissionName(String permission) {
        if (permission.contains("ACCESS_FINE_LOCATION") || permission.contains("ACCESS_COARSE_LOCATION")) {
            return "location";
        }
        if (permission.contains("CAMERA")) return "camera";
        if (permission.contains("RECORD_AUDIO")) return "record_audio";
        if (permission.contains("WRITE_EXTERNAL_STORAGE") || permission.contains("READ_EXTERNAL_STORAGE")) {
            return "storage";
        }
        return permission.replace("android.permission.", "");
    }
}

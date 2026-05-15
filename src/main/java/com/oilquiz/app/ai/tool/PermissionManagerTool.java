package com.oilquiz.app.ai.tool;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能权限管理工具，提供权限检查、请求和管理功能
 */
public class PermissionManagerTool implements AITool {
    private static final String TAG = "PermissionManagerTool";
    private final Context context;
    
    private static final Map<String, String> PERMISSION_MAP = new HashMap<>();
    static {
        PERMISSION_MAP.put("camera", Manifest.permission.CAMERA);
        PERMISSION_MAP.put("录音", Manifest.permission.RECORD_AUDIO);
        PERMISSION_MAP.put("麦克风", Manifest.permission.RECORD_AUDIO);
        PERMISSION_MAP.put("位置", Manifest.permission.ACCESS_FINE_LOCATION);
        PERMISSION_MAP.put("定位", Manifest.permission.ACCESS_FINE_LOCATION);
        PERMISSION_MAP.put("蓝牙", Manifest.permission.BLUETOOTH);
        PERMISSION_MAP.put("存储", Manifest.permission.WRITE_EXTERNAL_STORAGE);
        PERMISSION_MAP.put("读取存储", Manifest.permission.READ_EXTERNAL_STORAGE);
        PERMISSION_MAP.put("发送短信", Manifest.permission.SEND_SMS);
        PERMISSION_MAP.put("读取短信", Manifest.permission.READ_SMS);
        PERMISSION_MAP.put("拨打电话", Manifest.permission.CALL_PHONE);
        PERMISSION_MAP.put("读取联系人", Manifest.permission.READ_CONTACTS);
        PERMISSION_MAP.put("写入联系人", Manifest.permission.WRITE_CONTACTS);
        PERMISSION_MAP.put("电话状态", Manifest.permission.READ_PHONE_STATE);
        PERMISSION_MAP.put("读取通话记录", Manifest.permission.READ_CALL_LOG);
        PERMISSION_MAP.put("写入通话记录", Manifest.permission.WRITE_CALL_LOG);
        PERMISSION_MAP.put("后台定位", Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        PERMISSION_MAP.put("精确位置", Manifest.permission.ACCESS_FINE_LOCATION);
        PERMISSION_MAP.put("粗略位置", Manifest.permission.ACCESS_COARSE_LOCATION);
        PERMISSION_MAP.put("安装应用", Manifest.permission.REQUEST_INSTALL_PACKAGES);
        PERMISSION_MAP.put("悬浮窗", Manifest.permission.SYSTEM_ALERT_WINDOW);
        PERMISSION_MAP.put("唤醒锁定", Manifest.permission.WAKE_LOCK);
        PERMISSION_MAP.put("网络状态", Manifest.permission.ACCESS_NETWORK_STATE);
        PERMISSION_MAP.put("WiFi状态", Manifest.permission.ACCESS_WIFI_STATE);
        PERMISSION_MAP.put("更改网络状态", Manifest.permission.CHANGE_NETWORK_STATE);
        PERMISSION_MAP.put("更改WiFi状态", Manifest.permission.CHANGE_WIFI_STATE);
        PERMISSION_MAP.put("开机自启", Manifest.permission.RECEIVE_BOOT_COMPLETED);
    }
    
    public PermissionManagerTool(Context context) {
        this.context = context.getApplicationContext();
    }
    
    @Override
    public String getName() {
        return "permission_manager";
    }
    
    @Override
    public String getDescription() {
        return "智能权限管理工具，支持权限检查、请求和管理功能";
    }
    
    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            String action = (String) parameters.get("action");
            if (action == null) {
                action = "check";
            }
            
            switch (action) {
                case "check":
                    return checkPermission(parameters);
                case "check_all":
                    return checkAllPermissions(parameters);
                case "request":
                    return requestPermission(parameters);
                case "get_status":
                    return getPermissionStatus(parameters);
                case "list_permissions":
                    return listPermissions();
                case "explain_permission":
                    return explainPermission(parameters);
                default:
                    return checkPermission(parameters);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Error executing permission manager: " + e.getMessage(), e);
            return new AIToolResult("权限管理失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult checkPermission(Map<String, Object> parameters) {
        String permission = (String) parameters.get("permission");
        
        if (permission == null) {
            return new AIToolResult("缺少参数: permission", parameters);
        }
        
        String androidPermission = getAndroidPermission(permission);
        if (androidPermission == null) {
            return new AIToolResult("未知权限: " + permission, parameters);
        }
        
        int result = context.checkSelfPermission(androidPermission);
        boolean granted = result == PackageManager.PERMISSION_GRANTED;
        
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("permission", permission);
        resultMap.put("granted", granted);
        resultMap.put("androidPermission", androidPermission);
        
        return new AIToolResult(resultMap, parameters);
    }
    
    private AIToolResult checkAllPermissions(Map<String, Object> parameters) {
        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) parameters.get("permissions");
        
        if (permissions == null || permissions.isEmpty()) {
            return new AIToolResult("缺少参数: permissions", parameters);
        }
        
        Map<String, Object> results = new HashMap<>();
        List<String> granted = new ArrayList<>();
        List<String> denied = new ArrayList<>();
        
        for (String permission : permissions) {
            String androidPermission = getAndroidPermission(permission);
            if (androidPermission != null) {
                int result = context.checkSelfPermission(androidPermission);
                if (result == PackageManager.PERMISSION_GRANTED) {
                    granted.add(permission);
                } else {
                    denied.add(permission);
                }
            }
        }
        
        results.put("status", "success");
        results.put("granted", granted);
        results.put("denied", denied);
        results.put("totalChecked", permissions.size());
        results.put("grantedCount", granted.size());
        results.put("deniedCount", denied.size());
        
        return new AIToolResult(results, parameters);
    }
    
    private AIToolResult requestPermission(Map<String, Object> parameters) {
        String permission = (String) parameters.get("permission");
        
        if (permission == null) {
            return new AIToolResult("缺少参数: permission", parameters);
        }
        
        String androidPermission = getAndroidPermission(permission);
        if (androidPermission == null) {
            return new AIToolResult("未知权限: " + permission, parameters);
        }
        
        int result = context.checkSelfPermission(androidPermission);
        boolean alreadyGranted = result == PackageManager.PERMISSION_GRANTED;
        
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("permission", permission);
        resultMap.put("alreadyGranted", alreadyGranted);
        resultMap.put("androidPermission", androidPermission);
        
        if (alreadyGranted) {
            resultMap.put("message", "权限已授予");
        } else {
            resultMap.put("message", "需要请求权限，请在应用中手动授权");
            resultMap.put("needsRequest", true);
        }
        
        return new AIToolResult(resultMap, parameters);
    }
    
    private AIToolResult getPermissionStatus(Map<String, Object> parameters) {
        String permission = (String) parameters.get("permission");
        
        if (permission == null) {
            return new AIToolResult("缺少参数: permission", parameters);
        }
        
        String androidPermission = getAndroidPermission(permission);
        if (androidPermission == null) {
            return new AIToolResult("未知权限: " + permission, parameters);
        }
        
        int result = context.checkSelfPermission(androidPermission);
        boolean granted = result == PackageManager.PERMISSION_GRANTED;
        
        String status;
        if (granted) {
            status = "granted";
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                boolean shouldShow = ((android.app.Activity) context).shouldShowRequestPermissionRationale(androidPermission);
                status = shouldShow ? "denied" : "denied_never_ask";
            } else {
                status = "denied";
            }
        }
        
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("permission", permission);
        resultMap.put("androidPermission", androidPermission);
        resultMap.put("permissionStatus", status);
        resultMap.put("granted", granted);
        
        return new AIToolResult(resultMap, parameters);
    }
    
    private AIToolResult listPermissions() {
        List<Map<String, Object>> permissions = new ArrayList<>();
        
        for (Map.Entry<String, String> entry : PERMISSION_MAP.entrySet()) {
            Map<String, Object> permissionInfo = new HashMap<>();
            permissionInfo.put("name", entry.getKey());
            permissionInfo.put("androidPermission", entry.getValue());
            
            int result = context.checkSelfPermission(entry.getValue());
            permissionInfo.put("granted", result == PackageManager.PERMISSION_GRANTED);
            
            permissions.add(permissionInfo);
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("permissions", permissions);
        result.put("count", permissions.size());
        
        return new AIToolResult(result, new HashMap<>());
    }
    
    private AIToolResult explainPermission(Map<String, Object> parameters) {
        String permission = (String) parameters.get("permission");
        
        if (permission == null) {
            return new AIToolResult("缺少参数: permission", parameters);
        }
        
        Map<String, Object> explanation = new HashMap<>();
        explanation.put("status", "success");
        explanation.put("permission", permission);
        
        switch (permission.toLowerCase()) {
            case "camera":
            case "相机":
                explanation.put("description", "允许应用访问相机设备");
                explanation.put("usage", "拍照、录像、扫码等功能");
                explanation.put("protectionLevel", "危险权限");
                break;
            case "录音":
            case "麦克风":
                explanation.put("description", "允许应用访问麦克风");
                explanation.put("usage", "语音通话、语音识别、录音等功能");
                explanation.put("protectionLevel", "危险权限");
                break;
            case "位置":
            case "定位":
            case "精确位置":
                explanation.put("description", "允许应用获取精确位置信息");
                explanation.put("usage", "地图导航、基于位置的服务等");
                explanation.put("protectionLevel", "危险权限");
                break;
            case "存储":
            case "读取存储":
                explanation.put("description", "允许应用读取外部存储");
                explanation.put("usage", "读取文件、图片、视频等");
                explanation.put("protectionLevel", "危险权限");
                break;
            case "发送短信":
                explanation.put("description", "允许应用发送短信");
                explanation.put("usage", "发送验证码、消息通知等");
                explanation.put("protectionLevel", "危险权限");
                break;
            case "拨打电话":
                explanation.put("description", "允许应用拨打电话");
                explanation.put("usage", "一键拨号、电话服务等");
                explanation.put("protectionLevel", "危险权限");
                break;
            case "读取联系人":
                explanation.put("description", "允许应用读取联系人信息");
                explanation.put("usage", "联系人管理、分享等功能");
                explanation.put("protectionLevel", "危险权限");
                break;
            case "蓝牙":
                explanation.put("description", "允许应用使用蓝牙功能");
                explanation.put("usage", "蓝牙设备连接、数据传输等");
                explanation.put("protectionLevel", "普通权限");
                break;
            default:
                explanation.put("description", "未知权限");
                explanation.put("usage", "未知用途");
                explanation.put("protectionLevel", "未知");
                break;
        }
        
        return new AIToolResult(explanation, parameters);
    }
    
    private String getAndroidPermission(String permission) {
        String lowerPermission = permission.toLowerCase();
        return PERMISSION_MAP.get(lowerPermission);
    }
    
    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("action", "操作类型: check, check_all, request, get_status, list_permissions, explain_permission");
        descriptions.put("permission", "权限名称（用于check, request, get_status, explain_permission操作）");
        descriptions.put("permissions", "权限列表（用于check_all操作）");
        return descriptions;
    }
}
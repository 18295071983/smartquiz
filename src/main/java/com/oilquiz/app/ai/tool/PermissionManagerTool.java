package com.oilquiz.app.ai.tool;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.oilquiz.app.SmartQuizApplication;
import com.oilquiz.app.resource.PermissionResourceProvider;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class PermissionManagerTool implements AITool {
    private static final String TAG = "PermissionManagerTool";
    private static final long PERMISSION_REQUEST_TIMEOUT_MS = 30000;
    private final Context context;
    private final Handler mainHandler;
    
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
        this.mainHandler = new Handler(Looper.getMainLooper());
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
                case "request_and_wait":
                    return requestPermissionAndWait(parameters);
                case "get_status":
                    return getPermissionStatus(parameters);
                case "list_permissions":
                    return listPermissions();
                case "explain_permission":
                    return explainPermission(parameters);
                case "can_request":
                    return canRequestPermission(parameters);
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
    
    private AIToolResult canRequestPermission(Map<String, Object> parameters) {
        Activity activity = SmartQuizApplication.getCurrentActivity();
        
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        
        if (activity == null) {
            resultMap.put("canRequest", false);
            resultMap.put("reason", "没有可用的 Activity，无法请求权限");
            resultMap.put("suggestion", "请确保应用处于前台且有 Activity 正在运行");
            return new AIToolResult(resultMap, parameters);
        }
        
        String permission = (String) parameters.get("permission");
        if (permission == null) {
            resultMap.put("canRequest", false);
            resultMap.put("reason", "缺少参数: permission");
            return new AIToolResult(resultMap, parameters);
        }
        
        String androidPermission = getAndroidPermission(permission);
        if (androidPermission == null) {
            resultMap.put("canRequest", false);
            resultMap.put("reason", "未知权限: " + permission);
            return new AIToolResult(resultMap, parameters);
        }
        
        int result = context.checkSelfPermission(androidPermission);
        boolean alreadyGranted = result == PackageManager.PERMISSION_GRANTED;
        
        if (alreadyGranted) {
            resultMap.put("canRequest", false);
            resultMap.put("alreadyGranted", true);
            resultMap.put("reason", "权限已授予，无需请求");
            return new AIToolResult(resultMap, parameters);
        }
        
        boolean shouldShowRationale = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            shouldShowRationale = activity.shouldShowRequestPermissionRationale(androidPermission);
        }
        
        resultMap.put("canRequest", true);
        resultMap.put("shouldShowRationale", shouldShowRationale);
        resultMap.put("alreadyGranted", false);
        
        if (shouldShowRationale) {
            resultMap.put("suggestion", "建议先向用户解释为什么需要这个权限");
        } else {
            resultMap.put("suggestion", "可以直接请求权限，或者用户可能已选择不再询问");
        }
        
        return new AIToolResult(resultMap, parameters);
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
        resultMap.put("androidPermission", androidPermission);
        
        if (alreadyGranted) {
            resultMap.put("granted", true);
            resultMap.put("message", "权限已授予");
            return new AIToolResult(resultMap, parameters);
        }
        
        Activity activity = SmartQuizApplication.getCurrentActivity();
        if (activity == null) {
            resultMap.put("granted", false);
            resultMap.put("needsRequest", true);
            resultMap.put("message", "没有可用的 Activity，无法弹出权限请求对话框");
            resultMap.put("suggestion", "请确保应用处于前台，然后重试");
            return new AIToolResult(resultMap, parameters);
        }
        
        PermissionResourceProvider provider = PermissionResourceProvider.getInstance(context);
        
        mainHandler.post(() -> {
            provider.requestPermission(activity, androidPermission, new PermissionResourceProvider.PermissionCallback() {
                @Override
                public void onGranted() {
                    AILogger.i(TAG, "权限请求成功: " + permission);
                }
                
                @Override
                public void onDenied(List<String> deniedPermissions) {
                    AILogger.w(TAG, "权限请求被拒绝: " + permission);
                }
            });
        });
        
        resultMap.put("granted", false);
        resultMap.put("requested", true);
        resultMap.put("message", "已弹出权限请求对话框，请等待用户授权");
        resultMap.put("suggestion", "使用 request_and_wait action 可以等待授权结果");
        
        return new AIToolResult(resultMap, parameters);
    }
    
    private AIToolResult requestPermissionAndWait(Map<String, Object> parameters) {
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
        resultMap.put("androidPermission", androidPermission);
        
        if (alreadyGranted) {
            resultMap.put("granted", true);
            resultMap.put("message", "权限已授予");
            return new AIToolResult(resultMap, parameters);
        }
        
        Activity activity = SmartQuizApplication.getCurrentActivity();
        if (activity == null) {
            resultMap.put("granted", false);
            resultMap.put("needsRequest", true);
            resultMap.put("message", "没有可用的 Activity，无法弹出权限请求对话框");
            resultMap.put("suggestion", "请确保应用处于前台，然后重试");
            return new AIToolResult(resultMap, parameters);
        }
        
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean granted = new AtomicBoolean(false);
        final PermissionResourceProvider provider = PermissionResourceProvider.getInstance(context);
        
        mainHandler.post(() -> {
            provider.requestPermission(activity, androidPermission, new PermissionResourceProvider.PermissionCallback() {
                @Override
                public void onGranted() {
                    AILogger.i(TAG, "权限请求成功: " + permission);
                    granted.set(true);
                    latch.countDown();
                }
                
                @Override
                public void onDenied(List<String> deniedPermissions) {
                    AILogger.w(TAG, "权限请求被拒绝: " + permission);
                    granted.set(false);
                    latch.countDown();
                }
            });
        });
        
        try {
            boolean completed = latch.await(PERMISSION_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            
            if (!completed) {
                resultMap.put("granted", false);
                resultMap.put("timeout", true);
                resultMap.put("message", "权限请求超时，用户可能未做出选择");
                
                result = context.checkSelfPermission(androidPermission);
                resultMap.put("currentGranted", result == PackageManager.PERMISSION_GRANTED);
            } else {
                resultMap.put("granted", granted.get());
                if (granted.get()) {
                    resultMap.put("message", "权限请求成功");
                } else {
                    resultMap.put("message", "权限请求被用户拒绝");
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        boolean shouldShowRationale = activity.shouldShowRequestPermissionRationale(androidPermission);
                        resultMap.put("shouldShowRationale", shouldShowRationale);
                        
                        if (!shouldShowRationale) {
                            resultMap.put("suggestion", "用户可能选择了不再询问，需要引导用户去设置中手动授权");
                        } else {
                            resultMap.put("suggestion", "可以向用户解释为什么需要这个权限后再次请求");
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            resultMap.put("granted", false);
            resultMap.put("message", "权限请求被中断");
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
        Activity activity = SmartQuizApplication.getCurrentActivity();
        boolean shouldShowRationale = false;
        
        if (granted) {
            status = "granted";
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && activity != null) {
                shouldShowRationale = activity.shouldShowRequestPermissionRationale(androidPermission);
                status = shouldShowRationale ? "denied" : "denied_never_ask";
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
        resultMap.put("shouldShowRationale", shouldShowRationale);
        resultMap.put("hasActivity", activity != null);
        
        if (!granted && activity != null) {
            resultMap.put("canRequest", true);
        }
        
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
                explanation.put("usage", "地图导航、基于位置的服务、天气查询等");
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
        descriptions.put("action", "操作类型: check, check_all, request, request_and_wait, get_status, list_permissions, explain_permission, can_request");
        descriptions.put("permission", "权限名称（用于check, request, get_status, explain_permission操作）");
        descriptions.put("permissions", "权限列表（用于check_all操作）");
        return descriptions;
    }
}

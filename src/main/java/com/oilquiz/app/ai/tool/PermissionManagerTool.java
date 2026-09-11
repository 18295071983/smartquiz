package com.oilquiz.app.ai.tool;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.oilquiz.app.SmartQuizApplication;
import com.oilquiz.app.resource.PermissionResourceProvider;
import com.oilquiz.app.util.AILogger;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Tool(
    value = "permission_manager",
    description = "智能权限管理工具，支持权限检查、请求和管理功能",
    category = "system",
    actions = {
        @Action(name = "check", description = "检查单个权限状态"),
        @Action(name = "check_all", description = "批量检查权限状态"),
        @Action(name = "request", description = "请求权限（不等待结果）"),
        @Action(name = "request_and_wait", description = "请求权限并等待结果（推荐）"),
        @Action(name = "get_status", description = "获取权限详细状态"),
        @Action(name = "list_permissions", description = "列出所有已知权限"),
        @Action(name = "explain_permission", description = "解释权限用途"),
        @Action(name = "can_request", description = "检查是否可以请求权限")
    },
    params = {
        @Param(name = "permission", type = "string", description = "权限名称（如camera/位置/录音/存储/拨打电话/发送短信等）", required = false),
        @Param(name = "permissions", type = "list", description = "权限列表（用于check_all操作）", required = false)
    }
)
public class PermissionManagerTool implements AITool {
    private static final String TAG = "PermissionManagerTool";
    private static final long PERMISSION_REQUEST_TIMEOUT_MS = 30000;
    private final Context context;
    private final Handler mainHandler;
    
    private static final Map<String, String> PERMISSION_MAP = new HashMap<>();
    static {
        // === 以下所有权限均在 AndroidManifest.xml 中声明 ===
        
        // 相机
        PERMISSION_MAP.put("camera", Manifest.permission.CAMERA);
        PERMISSION_MAP.put("相机", Manifest.permission.CAMERA);
        // 录音/麦克风
        PERMISSION_MAP.put("录音", Manifest.permission.RECORD_AUDIO);
        PERMISSION_MAP.put("麦克风", Manifest.permission.RECORD_AUDIO);
        PERMISSION_MAP.put("microphone", Manifest.permission.RECORD_AUDIO);
        PERMISSION_MAP.put("record_audio", Manifest.permission.RECORD_AUDIO);
        // 位置/定位
        PERMISSION_MAP.put("位置", Manifest.permission.ACCESS_FINE_LOCATION);
        PERMISSION_MAP.put("定位", Manifest.permission.ACCESS_FINE_LOCATION);
        PERMISSION_MAP.put("location", Manifest.permission.ACCESS_FINE_LOCATION);
        PERMISSION_MAP.put("精确位置", Manifest.permission.ACCESS_FINE_LOCATION);
        PERMISSION_MAP.put("粗略位置", Manifest.permission.ACCESS_COARSE_LOCATION);
        PERMISSION_MAP.put("后台定位", Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        // 存储（根据 Android 版本映射不同权限）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PERMISSION_MAP.put("存储", "manage_external_storage");
            PERMISSION_MAP.put("storage", "manage_external_storage");
            PERMISSION_MAP.put("读取存储", "manage_external_storage");
            PERMISSION_MAP.put("文件管理", "manage_external_storage");
            // 常用英文别名（模型可能传这些）
            PERMISSION_MAP.put("manage_external_storage", "manage_external_storage");
            PERMISSION_MAP.put("all_files_access", "manage_external_storage");
            PERMISSION_MAP.put("all files access", "manage_external_storage");
            PERMISSION_MAP.put("file_access", "manage_external_storage");
            PERMISSION_MAP.put("file access", "manage_external_storage");
            PERMISSION_MAP.put("external_storage", "manage_external_storage");
            PERMISSION_MAP.put("external storage", "manage_external_storage");
            PERMISSION_MAP.put("files", "manage_external_storage");
            PERMISSION_MAP.put("公共存储", "manage_external_storage");
            PERMISSION_MAP.put("公共目录", "manage_external_storage");
            PERMISSION_MAP.put("下载目录", "manage_external_storage");
            PERMISSION_MAP.put("媒体文件", "read_media");
            PERMISSION_MAP.put("图片", Manifest.permission.READ_MEDIA_IMAGES);
            PERMISSION_MAP.put("视频", Manifest.permission.READ_MEDIA_VIDEO);
            PERMISSION_MAP.put("音频文件", Manifest.permission.READ_MEDIA_AUDIO);
        } else {
            PERMISSION_MAP.put("存储", Manifest.permission.WRITE_EXTERNAL_STORAGE);
            PERMISSION_MAP.put("storage", Manifest.permission.WRITE_EXTERNAL_STORAGE);
            PERMISSION_MAP.put("读取存储", Manifest.permission.READ_EXTERNAL_STORAGE);
            PERMISSION_MAP.put("manage_external_storage", Manifest.permission.WRITE_EXTERNAL_STORAGE);
            PERMISSION_MAP.put("all_files_access", Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        // 电话
        PERMISSION_MAP.put("拨打电话", Manifest.permission.CALL_PHONE);
        PERMISSION_MAP.put("phone", Manifest.permission.CALL_PHONE);
        PERMISSION_MAP.put("call_phone", Manifest.permission.CALL_PHONE);
        PERMISSION_MAP.put("电话状态", Manifest.permission.READ_PHONE_STATE);
        PERMISSION_MAP.put("read_phone_state", Manifest.permission.READ_PHONE_STATE);
        // 联系人
        PERMISSION_MAP.put("读取联系人", Manifest.permission.READ_CONTACTS);
        PERMISSION_MAP.put("read_contacts", Manifest.permission.READ_CONTACTS);
        PERMISSION_MAP.put("contacts", Manifest.permission.READ_CONTACTS);
        PERMISSION_MAP.put("写入联系人", Manifest.permission.WRITE_CONTACTS);
        PERMISSION_MAP.put("write_contacts", Manifest.permission.WRITE_CONTACTS);
        // 安装应用
        PERMISSION_MAP.put("安装应用", "request_install_packages");
        PERMISSION_MAP.put("install", "request_install_packages");
        PERMISSION_MAP.put("install_packages", "request_install_packages");
        // 通知
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PERMISSION_MAP.put("通知", Manifest.permission.POST_NOTIFICATIONS);
            PERMISSION_MAP.put("notification", Manifest.permission.POST_NOTIFICATIONS);
            PERMISSION_MAP.put("notifications", Manifest.permission.POST_NOTIFICATIONS);
        }
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
        
        // 特殊权限检查（非标准运行时权限）
        Boolean specialResult = checkSpecialPermission(androidPermission);
        boolean granted;
        if (specialResult != null) {
            granted = specialResult;
        } else {
            int result = context.checkSelfPermission(androidPermission);
            granted = result == PackageManager.PERMISSION_GRANTED;
        }
        
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", "success");
        resultMap.put("permission", permission);
        resultMap.put("granted", granted);
        resultMap.put("androidPermission", androidPermission);
        
        return new AIToolResult(resultMap, parameters);
    }

    /**
     * 判断是否为特殊权限（非标准运行时权限，不能用 checkSelfPermission 检查）
     */
    private boolean isSpecialPermission(String androidPermission) {
        return "manage_external_storage".equals(androidPermission)
                || "request_install_packages".equals(androidPermission)
                || "read_media".equals(androidPermission);
    }

    /**
     * 检查特殊权限状态。返回 null 表示非特殊权限，走标准 checkSelfPermission。
     */
    private Boolean checkSpecialPermission(String androidPermission) {
        if ("manage_external_storage".equals(androidPermission)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return Environment.isExternalStorageManager();
            }
            return true;
        }
        if ("request_install_packages".equals(androidPermission)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 检查是否允许安装未知来源应用
                try {
                    int allowed = Settings.Secure.getInt(context.getContentResolver(),
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? "install_non_market_apps" : "install_non_market_apps");
                    return allowed == 1;
                } catch (Settings.SettingNotFoundException e) {
                    return false;
                }
            }
            return true;
        }
        if ("read_media".equals(androidPermission)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                boolean img = context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
                boolean vid = context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
                return img || vid; // 至少有一个媒体权限即视为已授权
            }
            int result = context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE);
            return result == PackageManager.PERMISSION_GRANTED;
        }
        return null;
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
                Boolean specialResult = checkSpecialPermission(androidPermission);
                boolean isGranted;
                if (specialResult != null) {
                    isGranted = specialResult;
                } else {
                    int result = context.checkSelfPermission(androidPermission);
                    isGranted = result == PackageManager.PERMISSION_GRANTED;
                }
                if (isGranted) {
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
        
        // 特殊权限用专用检查
        Boolean specialResult = checkSpecialPermission(androidPermission);
        boolean alreadyGranted;
        if (specialResult != null) {
            alreadyGranted = specialResult;
        } else {
            int result = context.checkSelfPermission(androidPermission);
            alreadyGranted = result == PackageManager.PERMISSION_GRANTED;
        }
        
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
        
        // 特殊权限用专用检查
        Boolean specialResult = checkSpecialPermission(androidPermission);
        boolean alreadyGranted;
        if (specialResult != null) {
            alreadyGranted = specialResult;
        } else {
            int result = context.checkSelfPermission(androidPermission);
            alreadyGranted = result == PackageManager.PERMISSION_GRANTED;
        }
        
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
        
        // 特殊权限：跳转对应设置页
        if ("manage_external_storage".equals(androidPermission)) {
            mainHandler.post(() -> {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + context.getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(intent);
                } catch (Exception e) {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(intent);
                    } catch (Exception e2) {
                        AILogger.e(TAG, "打开文件管理设置失败: " + e2.getMessage());
                    }
                }
            });
            resultMap.put("granted", false);
            resultMap.put("requested", true);
            resultMap.put("message", "已打开文件管理设置页，请手动开启");
            resultMap.put("suggestion", "使用 request_and_wait action 可以等待授权结果");
            return new AIToolResult(resultMap, parameters);
        }
        if ("request_install_packages".equals(androidPermission)) {
            mainHandler.post(() -> {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                    intent.setData(Uri.parse("package:" + context.getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(intent);
                } catch (Exception e) {
                    AILogger.e(TAG, "打开安装设置失败: " + e.getMessage());
                }
            });
            resultMap.put("granted", false);
            resultMap.put("requested", true);
            resultMap.put("message", "已打开安装应用设置页，请手动开启");
            resultMap.put("suggestion", "使用 request_and_wait action 可以等待授权结果");
            return new AIToolResult(resultMap, parameters);
        }
        
        PermissionResourceProvider provider = PermissionResourceProvider.getInstance(context);
        
        mainHandler.post(() -> {
            // 使用 requestPermissionDirect 跳过自定义 Dialog，直接调用系统权限请求
            provider.requestPermissionDirect(activity, androidPermission, new PermissionResourceProvider.PermissionCallback() {
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
        
        // 特殊权限：先检查实际状态
        Boolean specialCheck = checkSpecialPermission(androidPermission);
        boolean alreadyGranted;
        if (specialCheck != null) {
            alreadyGranted = specialCheck;
        } else {
            int result = context.checkSelfPermission(androidPermission);
            alreadyGranted = result == PackageManager.PERMISSION_GRANTED;
        }
        
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
        
        // 特殊处理：MANAGE_EXTERNAL_STORAGE 需要跳转系统设置页
        if ("manage_external_storage".equals(androidPermission)) {
            try {
                final CountDownLatch openLatch = new CountDownLatch(1);
                mainHandler.post(() -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        intent.setData(Uri.parse("package:" + context.getPackageName()));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(intent);
                    } catch (Exception e) {
                        AILogger.w(TAG, "打开文件管理设置失败: " + e.getMessage());
                        try {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            activity.startActivity(intent);
                        } catch (Exception e2) {
                            AILogger.e(TAG, "兑底设置也失败: " + e2.getMessage());
                        }
                    }
                    openLatch.countDown();
                });
                openLatch.await(5, TimeUnit.SECONDS);
                // 轮询等待用户操作（1s 间隔，最多 60 秒；授权后 ~1s 内即返回）
                for (int i = 0; i < 60; i++) {
                    Thread.sleep(1000);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                        // 授权成功：工作区重建(切公共目录)+迁移旧文件
                        try {
                            com.oilquiz.app.ai.agent.online.AgentWorkspace.rebuildInstance(context);
                            int migrated = com.oilquiz.app.ai.agent.online.AgentWorkspace
                                    .getInstance(context).migrateFromPrivate(context);
                            AILogger.i(TAG, "文件管理权限已授予，工作区切换到公共目录，迁移 " + migrated + " 个文件");
                        } catch (Throwable t) {
                            AILogger.w(TAG, "授权后工作区切换失败: " + t.getMessage());
                        }
                        resultMap.put("granted", true);
                        resultMap.put("message", "用户已在设置中授予文件管理权限，工作区已切换到公共目录");
                        return new AIToolResult(resultMap, parameters);
                    }
                }
                boolean finalGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager();
                resultMap.put("granted", finalGranted);
                resultMap.put("message", finalGranted ? "文件管理权限已授予" : "权限请求超时，用户未在设置中授权");
                return new AIToolResult(resultMap, parameters);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                resultMap.put("granted", false);
                resultMap.put("message", "权限请求被中断");
                return new AIToolResult(resultMap, parameters);
            }
        }
        
        // 特殊处理：REQUEST_INSTALL_PACKAGES 需要跳转安装设置页
        if ("request_install_packages".equals(androidPermission)) {
            try {
                final CountDownLatch openLatch = new CountDownLatch(1);
                mainHandler.post(() -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                        intent.setData(Uri.parse("package:" + context.getPackageName()));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(intent);
                    } catch (Exception e) {
                        AILogger.e(TAG, "打开安装设置失败: " + e.getMessage());
                    }
                    openLatch.countDown();
                });
                openLatch.await(5, TimeUnit.SECONDS);
                // 轮询等待用户操作（1s 间隔，最多 60 秒）
                for (int i = 0; i < 60; i++) {
                    Thread.sleep(1000);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        boolean installOk;
                        try {
                            installOk = Settings.Secure.getInt(context.getContentResolver(), "install_non_market_apps") == 1;
                        } catch (Settings.SettingNotFoundException e) {
                            installOk = false;
                        }
                        if (installOk) {
                            resultMap.put("granted", true);
                            resultMap.put("message", "用户已在设置中授予安装应用权限");
                            return new AIToolResult(resultMap, parameters);
                        }
                    }
                }
                boolean finalInstallGranted = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        finalInstallGranted = Settings.Secure.getInt(context.getContentResolver(), "install_non_market_apps") == 1;
                    } catch (Settings.SettingNotFoundException e) {
                        finalInstallGranted = false;
                    }
                }
                resultMap.put("granted", finalInstallGranted);
                resultMap.put("message", finalInstallGranted ? "安装应用权限已授予" : "权限请求超时，用户未在设置中授权");
                return new AIToolResult(resultMap, parameters);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                resultMap.put("granted", false);
                resultMap.put("message", "权限请求被中断");
                return new AIToolResult(resultMap, parameters);
            }
        }
        
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean granted = new AtomicBoolean(false);
        final PermissionResourceProvider provider = PermissionResourceProvider.getInstance(context);
        
        mainHandler.post(() -> {
            // 使用 requestPermissionDirect 跳过自定义 Dialog，直接调用系统权限请求
            provider.requestPermissionDirect(activity, androidPermission, new PermissionResourceProvider.PermissionCallback() {
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
            
            // 无论回调结果如何，都重新检查实际权限状态（回调可能延迟，但系统已更新）
            Boolean specialRecheck = checkSpecialPermission(androidPermission);
            boolean actuallyGranted;
            if (specialRecheck != null) {
                actuallyGranted = specialRecheck;
            } else {
                int currentResult = context.checkSelfPermission(androidPermission);
                actuallyGranted = currentResult == PackageManager.PERMISSION_GRANTED;
            }
            
            if (!completed) {
                resultMap.put("granted", actuallyGranted);
                resultMap.put("timeout", true);
                resultMap.put("message", actuallyGranted 
                    ? "权限请求超时，但检测到权限已授予" 
                    : "权限请求超时，用户可能未做出选择");
            } else {
                // 以实际检查结果为准（比回调更可靠）
                resultMap.put("granted", actuallyGranted);
                if (actuallyGranted) {
                    resultMap.put("message", "权限请求成功，权限已授予");
                } else {
                    resultMap.put("message", "权限请求被用户拒绝");
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        boolean shouldShowRationale = activity.shouldShowRequestPermissionRationale(androidPermission);
                        resultMap.put("shouldShowRationale", shouldShowRationale);
                        
                        if (!shouldShowRationale) {
                            resultMap.put("suggestion", "用户已选择“不再询问”，需要引导用户去应用设置页手动授权");
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
        
        // 特殊权限用专用检查
        Boolean specialResult = checkSpecialPermission(androidPermission);
        boolean granted;
        if (specialResult != null) {
            granted = specialResult;
        } else {
            int result = context.checkSelfPermission(androidPermission);
            granted = result == PackageManager.PERMISSION_GRANTED;
        }
        
        String status;
        Activity activity = SmartQuizApplication.getCurrentActivity();
        boolean shouldShowRationale = false;
        
        if (granted) {
            status = "granted";
        } else {
            // 特殊权限没有 shouldShowRationale 概念
            if (isSpecialPermission(androidPermission)) {
                status = "denied";
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && activity != null) {
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
        // 去重：同一个 android 权限只报告一次
        Map<String, Map<String, Object>> uniquePermissions = new java.util.LinkedHashMap<>();
        List<String> grantedList = new ArrayList<>();
        List<String> deniedList = new ArrayList<>();
        
        for (Map.Entry<String, String> entry : PERMISSION_MAP.entrySet()) {
            String androidPerm = entry.getValue();
            if (uniquePermissions.containsKey(androidPerm)) continue;
            
            // 特殊权限用专用检查方法
            Boolean specialResult = checkSpecialPermission(androidPerm);
            boolean granted;
            if (specialResult != null) {
                granted = specialResult;
            } else {
                int result = context.checkSelfPermission(androidPerm);
                granted = result == PackageManager.PERMISSION_GRANTED;
            }
            
            Map<String, Object> permissionInfo = new HashMap<>();
            permissionInfo.put("name", entry.getKey());
            permissionInfo.put("androidPermission", androidPerm);
            permissionInfo.put("granted", granted);
            uniquePermissions.put(androidPerm, permissionInfo);
            
            if (granted) {
                grantedList.add(entry.getKey());
            } else {
                deniedList.add(entry.getKey());
            }
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("granted", grantedList);
        result.put("denied", deniedList);
        result.put("grantedCount", grantedList.size());
        result.put("deniedCount", deniedList.size());
        result.put("permissions", new ArrayList<>(uniquePermissions.values()));
        result.put("summary", "已授权 " + grantedList.size() + " 项，未授权 " + deniedList.size() + " 项");
        
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
            case "震动":
            case "vibrate":
                explanation.put("description", "允许应用控制设备震动");
                explanation.put("usage", "闹钟/定时提醒到点震动（与通知铃声配合），以及各类提示震动");
                explanation.put("protectionLevel", "普通权限（安装时自动授予，无需请求）");
                break;
            case "通知":
            case "notification":
            case "post_notifications":
                explanation.put("description", "允许应用发送通知");
                explanation.put("usage", "闹钟/定时提醒、消息推送等系统通知（Android 13+ 需要用户授权）");
                explanation.put("protectionLevel", "运行时权限");
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
        if (permission == null) return null;
        // 1. 先尝试原始值（支持中文键）
        String direct = PERMISSION_MAP.get(permission);
        if (direct != null) return direct;
        // 2. 尝试小写
        String lower = permission.toLowerCase();
        String lowerResult = PERMISSION_MAP.get(lower);
        if (lowerResult != null) return lowerResult;
        // 3. 模糊匹配：模型可能传"存储权限"/"文件管理权限"/"storage permission"等带后缀/前缀的
        for (Map.Entry<String, String> e : PERMISSION_MAP.entrySet()) {
            if (e.getKey() == null || e.getKey().length() < 2) continue;
            if (lower.contains(e.getKey().toLowerCase())
                    || e.getKey().toLowerCase().contains(lower)) {
                return e.getValue();
            }
        }
        // 4. 如果传入的已经是 Android 权限字符串（如 android.permission.CAMERA），直接返回
        if (permission.contains(".")) return permission;
        return null;
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

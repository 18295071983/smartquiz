package com.oilquiz.app.resource;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class PermissionResourceProvider {

    private static final String TAG = "PermissionResourceProvider";
    private static final int REQUEST_CODE_BASE = 10000;
    private static final long DEFAULT_PERMISSION_REQUEST_TIMEOUT_MS = 30000;

    private static PermissionResourceProvider instance;
    private Context context;
    private final Handler mainHandler;

    private Map<String, String[]> permissionGroups;
    private PermissionRequestListener permissionRequestListener;
    private final AtomicInteger requestCodeCounter = new AtomicInteger(REQUEST_CODE_BASE);
    private final Map<Integer, PermissionCallback> pendingCallbacks = new HashMap<>();

    public interface PermissionRequestListener {
        void onPermissionGranted(String permission);
        void onPermissionDenied(String permission);
        void onAllPermissionsGranted();
        void onPermissionsDenied(List<String> deniedPermissions);
    }

    public interface PermissionCallback {
        void onGranted();
        void onDenied(List<String> deniedPermissions);
    }

    private PermissionResourceProvider(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        initPermissionGroups();
    }

    public static synchronized PermissionResourceProvider getInstance(Context context) {
        if (instance == null) {
            instance = new PermissionResourceProvider(context);
        }
        return instance;
    }

    private void initPermissionGroups() {
        permissionGroups = new HashMap<>();

        permissionGroups.put("storage", new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        });

        permissionGroups.put("camera", new String[]{
                Manifest.permission.CAMERA
        });

        permissionGroups.put("location", new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        });

        permissionGroups.put("microphone", new String[]{
                Manifest.permission.RECORD_AUDIO
        });

        permissionGroups.put("phone", new String[]{
                Manifest.permission.READ_PHONE_STATE
        });

        permissionGroups.put("contacts", new String[]{
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_CONTACTS
        });

        permissionGroups.put("calendar", new String[]{
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR
        });

        permissionGroups.put("sensors", new String[]{
                Manifest.permission.BODY_SENSORS
        });

        permissionGroups.put("sms", new String[]{
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionGroups.put("notifications", new String[]{
                    Manifest.permission.POST_NOTIFICATIONS
            });
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionGroups.put("nearby_devices", new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
            });
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionGroups.put("media", new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
            });
        }
    }

    private int nextRequestCode() {
        return requestCodeCounter.incrementAndGet();
    }

    public void setPermissionRequestListener(PermissionRequestListener listener) {
        this.permissionRequestListener = listener;
    }

    public boolean isPermissionGranted(String permission) {
        return ContextCompat.checkSelfPermission(context, permission)
                == PackageManager.PERMISSION_GRANTED;
    }

    public boolean isPermissionGroupGranted(String groupName) {
        String[] permissions = permissionGroups.get(groupName);
        if (permissions == null) {
            Log.w(TAG, "Permission group not found: " + groupName);
            return false;
        }
        for (String permission : permissions) {
            if (!isPermissionGranted(permission)) {
                return false;
            }
        }
        return true;
    }

    public boolean arePermissionsGranted(String... permissions) {
        for (String permission : permissions) {
            if (!isPermissionGranted(permission)) {
                return false;
            }
        }
        return true;
    }

    public List<String> getDeniedPermissions(String groupName) {
        List<String> deniedPermissions = new ArrayList<>();
        String[] permissions = permissionGroups.get(groupName);
        if (permissions == null) {
            Log.w(TAG, "Permission group not found: " + groupName);
            return deniedPermissions;
        }
        for (String permission : permissions) {
            if (!isPermissionGranted(permission)) {
                deniedPermissions.add(permission);
            }
        }
        return deniedPermissions;
    }

    public void requestPermission(Activity activity, String permission) {
        requestPermission(activity, permission, null);
    }

    public void requestPermission(Activity activity, String permission, PermissionCallback callback) {
        if (isPermissionGranted(permission)) {
            if (callback != null) {
                callback.onGranted();
            } else if (permissionRequestListener != null) {
                permissionRequestListener.onPermissionGranted(permission);
                permissionRequestListener.onAllPermissionsGranted();
            }
            return;
        }
        showPermissionRequestDialog(activity, new String[]{permission}, callback);
    }

    public void requestPermissions(Activity activity, String[] permissions) {
        requestPermissions(activity, permissions, null);
    }

    public void requestPermissions(Activity activity, String[] permissions, PermissionCallback callback) {
        List<String> permissionsToRequest = new ArrayList<>();
        for (String permission : permissions) {
            if (!isPermissionGranted(permission)) {
                permissionsToRequest.add(permission);
            } else if (permissionRequestListener != null) {
                permissionRequestListener.onPermissionGranted(permission);
            }
        }
        if (permissionsToRequest.isEmpty()) {
            if (callback != null) {
                callback.onGranted();
            } else if (permissionRequestListener != null) {
                permissionRequestListener.onAllPermissionsGranted();
            }
            return;
        }
        showPermissionRequestDialog(activity, permissionsToRequest.toArray(new String[0]), callback);
    }

    public void requestPermissionGroup(Activity activity, String groupName) {
        requestPermissionGroup(activity, groupName, null);
    }

    public void requestPermissionGroup(Activity activity, String groupName, PermissionCallback callback) {
        List<String> deniedPermissions = getDeniedPermissions(groupName);
        if (deniedPermissions.isEmpty()) {
            if (callback != null) {
                callback.onGranted();
            } else if (permissionRequestListener != null) {
                permissionRequestListener.onAllPermissionsGranted();
            }
            return;
        }
        requestPermissions(activity, deniedPermissions.toArray(new String[0]), callback);
    }

    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        PermissionCallback callback = pendingCallbacks.remove(requestCode);
        List<String> deniedPermissions = new ArrayList<>();

        for (int i = 0; i < permissions.length; i++) {
            if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                if (permissionRequestListener != null) {
                    permissionRequestListener.onPermissionGranted(permissions[i]);
                }
            } else {
                deniedPermissions.add(permissions[i]);
                if (permissionRequestListener != null) {
                    permissionRequestListener.onPermissionDenied(permissions[i]);
                }
            }
        }

        if (deniedPermissions.isEmpty()) {
            if (callback != null) {
                callback.onGranted();
            } else if (permissionRequestListener != null) {
                permissionRequestListener.onAllPermissionsGranted();
            }
        } else {
            if (callback != null) {
                callback.onDenied(deniedPermissions);
            } else if (permissionRequestListener != null) {
                permissionRequestListener.onPermissionsDenied(deniedPermissions);
            }
        }
    }

    public boolean shouldShowRequestPermissionRationale(Activity activity, String permission) {
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission);
    }

    public String[] getPermissionGroupNames() {
        return permissionGroups.keySet().toArray(new String[0]);
    }

    public String[] getPermissionsInGroup(String groupName) {
        return permissionGroups.get(groupName);
    }

    public String getPermissionFriendlyName(String permission) {
        switch (permission) {
            case Manifest.permission.READ_EXTERNAL_STORAGE:
                return "读取存储";
            case Manifest.permission.WRITE_EXTERNAL_STORAGE:
                return "写入存储";
            case Manifest.permission.CAMERA:
                return "相机";
            case Manifest.permission.RECORD_AUDIO:
                return "录音";
            case Manifest.permission.ACCESS_FINE_LOCATION:
                return "精确定位";
            case Manifest.permission.ACCESS_COARSE_LOCATION:
                return "粗略定位";
            case Manifest.permission.READ_PHONE_STATE:
                return "读取手机状态";
            case Manifest.permission.READ_CONTACTS:
                return "读取联系人";
            case Manifest.permission.WRITE_CONTACTS:
                return "写入联系人";
            case Manifest.permission.READ_CALENDAR:
                return "读取日历";
            case Manifest.permission.WRITE_CALENDAR:
                return "写入日历";
            case Manifest.permission.BODY_SENSORS:
                return "身体传感器";
            case Manifest.permission.SEND_SMS:
                return "发送短信";
            case Manifest.permission.RECEIVE_SMS:
                return "接收短信";
            case Manifest.permission.READ_SMS:
                return "读取短信";
            default:
                return permission;
        }
    }

    public String getPermissionGroupFriendlyName(String groupName) {
        switch (groupName) {
            case "storage":
                return "存储权限";
            case "camera":
                return "相机权限";
            case "location":
                return "位置权限";
            case "microphone":
                return "麦克风权限";
            case "phone":
                return "电话权限";
            case "contacts":
                return "联系人权限";
            case "calendar":
                return "日历权限";
            case "sensors":
                return "传感器权限";
            case "sms":
                return "短信权限";
            case "notifications":
                return "通知权限";
            case "nearby_devices":
                return "附近设备权限";
            case "media":
                return "媒体权限";
            default:
                return groupName;
        }
    }

    public void requestStoragePermission(Activity activity) {
        requestStoragePermission(activity, null);
    }

    public void requestStoragePermission(Activity activity, PermissionCallback callback) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (callback != null) {
                callback.onGranted();
            } else if (permissionRequestListener != null) {
                permissionRequestListener.onAllPermissionsGranted();
            }
            return;
        }
        requestPermissionGroup(activity, "storage", callback);
    }

    public void requestCameraPermission(Activity activity) {
        requestCameraPermission(activity, null);
    }

    public void requestCameraPermission(Activity activity, PermissionCallback callback) {
        requestPermissionGroup(activity, "camera", callback);
    }

    public void requestLocationPermission(Activity activity) {
        requestLocationPermission(activity, null);
    }

    public void requestLocationPermission(Activity activity, PermissionCallback callback) {
        requestPermissionGroup(activity, "location", callback);
    }

    public void requestMicrophonePermission(Activity activity) {
        requestMicrophonePermission(activity, null);
    }

    public void requestMicrophonePermission(Activity activity, PermissionCallback callback) {
        requestPermissionGroup(activity, "microphone", callback);
    }

    public void requestNotificationPermission(Activity activity) {
        requestNotificationPermission(activity, null);
    }

    public void requestNotificationPermission(Activity activity, PermissionCallback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (callback != null) {
                callback.onGranted();
            } else if (permissionRequestListener != null) {
                permissionRequestListener.onAllPermissionsGranted();
            }
            return;
        }
        requestPermissionGroup(activity, "notifications", callback);
    }

    public boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        return isPermissionGroupGranted("storage");
    }

    public boolean hasCameraPermission() {
        return isPermissionGroupGranted("camera");
    }

    public boolean hasLocationPermission() {
        return isPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
               isPermissionGranted(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    public boolean hasMicrophonePermission() {
        return isPermissionGroupGranted("microphone");
    }

    public boolean hasPhonePermission() {
        return isPermissionGroupGranted("phone");
    }

    public boolean hasContactsPermission() {
        return isPermissionGroupGranted("contacts");
    }

    public boolean hasCalendarPermission() {
        return isPermissionGroupGranted("calendar");
    }

    public boolean hasSensorsPermission() {
        return isPermissionGroupGranted("sensors");
    }

    public boolean hasSmsPermission() {
        return isPermissionGroupGranted("sms");
    }

    public boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        return isPermissionGroupGranted("notifications");
    }

    public boolean hasMediaPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return hasStoragePermission();
        }
        return isPermissionGroupGranted("media");
    }

    public static class PermissionRequestResult {
        public boolean success;
        public boolean granted;
        public boolean timeout;
        public boolean hasActivity;
        public List<String> grantedPermissions;
        public List<String> deniedPermissions;
        public String errorMessage;

        public PermissionRequestResult() {
            this.success = false;
            this.granted = false;
            this.timeout = false;
            this.hasActivity = false;
            this.grantedPermissions = new ArrayList<>();
            this.deniedPermissions = new ArrayList<>();
            this.errorMessage = null;
        }
    }

    public PermissionRequestResult ensurePermission(String permission) {
        return ensurePermission(permission, DEFAULT_PERMISSION_REQUEST_TIMEOUT_MS);
    }

    public PermissionRequestResult ensurePermission(final String permission, final long timeoutMs) {
        final PermissionRequestResult result = new PermissionRequestResult();
        
        if (isPermissionGranted(permission)) {
            result.success = true;
            result.granted = true;
            result.hasActivity = true;
            result.grantedPermissions.add(permission);
            result.errorMessage = "权限已授予";
            Log.i(TAG, "Permission already granted: " + permission);
            return result;
        }

        final android.app.Activity activity = com.oilquiz.app.SmartQuizApplication.getCurrentActivity();
        if (activity == null) {
            result.success = false;
            result.granted = false;
            result.hasActivity = false;
            result.errorMessage = "没有可用的 Activity，无法请求权限";
            Log.e(TAG, "No activity available to request permission: " + permission);
            return result;
        }

        result.hasActivity = true;

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean granted = new AtomicBoolean(false);
        final List<String> deniedList = new ArrayList<>();

        mainHandler.post(() -> {
            try {
                requestPermission(activity, permission, new PermissionCallback() {
                    @Override
                    public void onGranted() {
                        Log.i(TAG, "Permission request success: " + permission);
                        granted.set(true);
                        latch.countDown();
                    }

                    @Override
                    public void onDenied(List<String> deniedPermissions) {
                        Log.w(TAG, "Permission request denied: " + permission);
                        deniedList.addAll(deniedPermissions);
                        granted.set(false);
                        latch.countDown();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error requesting permission: " + permission, e);
                result.errorMessage = "请求权限异常: " + e.getMessage();
                granted.set(false);
                latch.countDown();
            }
        });

        try {
            boolean completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            
            if (!completed) {
                result.success = true;
                result.granted = false;
                result.timeout = true;
                result.errorMessage = "权限请求超时";
                
                boolean currentGranted = isPermissionGranted(permission);
                result.granted = currentGranted;
                if (currentGranted) {
                    result.grantedPermissions.add(permission);
                    result.errorMessage = "权限请求超时，但检测到权限已授予";
                } else {
                    result.deniedPermissions.add(permission);
                }
                
                Log.w(TAG, "Permission request timeout: " + permission + ", currentGranted=" + currentGranted);
            } else {
                result.success = true;
                result.granted = granted.get();
                
                if (granted.get()) {
                    result.grantedPermissions.add(permission);
                    result.errorMessage = "权限请求成功";
                } else {
                    result.deniedPermissions.addAll(deniedList);
                    if (deniedList.isEmpty()) {
                        result.deniedPermissions.add(permission);
                    }
                    result.errorMessage = "权限被拒绝";
                    
                    boolean shouldShowRationale = false;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        shouldShowRationale = activity.shouldShowRequestPermissionRationale(permission);
                    }
                    
                    if (!shouldShowRationale) {
                        result.errorMessage += "（用户可能选择了不再询问）";
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.success = false;
            result.granted = false;
            result.errorMessage = "权限请求被中断";
            Log.e(TAG, "Permission request interrupted: " + permission, e);
        }

        return result;
    }

    public PermissionRequestResult ensurePermissions(String... permissions) {
        return ensurePermissions(DEFAULT_PERMISSION_REQUEST_TIMEOUT_MS, permissions);
    }

    public PermissionRequestResult ensurePermissions(final long timeoutMs, final String... permissions) {
        final PermissionRequestResult result = new PermissionRequestResult();
        final List<String> permissionsToRequest = new ArrayList<>();

        for (String permission : permissions) {
            if (isPermissionGranted(permission)) {
                result.grantedPermissions.add(permission);
            } else {
                permissionsToRequest.add(permission);
            }
        }

        if (permissionsToRequest.isEmpty()) {
            result.success = true;
            result.granted = true;
            result.hasActivity = true;
            result.errorMessage = "所有权限已授予";
            return result;
        }

        final android.app.Activity activity = com.oilquiz.app.SmartQuizApplication.getCurrentActivity();
        if (activity == null) {
            result.success = false;
            result.granted = false;
            result.hasActivity = false;
            result.deniedPermissions.addAll(permissionsToRequest);
            result.errorMessage = "没有可用的 Activity，无法请求权限";
            Log.e(TAG, "No activity available to request permissions");
            return result;
        }

        result.hasActivity = true;

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean allGranted = new AtomicBoolean(false);
        final List<String> deniedList = new ArrayList<>();

        mainHandler.post(() -> {
            try {
                requestPermissions(activity, permissionsToRequest.toArray(new String[0]), new PermissionCallback() {
                    @Override
                    public void onGranted() {
                        Log.i(TAG, "All permissions granted");
                        allGranted.set(true);
                        latch.countDown();
                    }

                    @Override
                    public void onDenied(List<String> deniedPermissions) {
                        Log.w(TAG, "Some permissions denied: " + deniedPermissions);
                        deniedList.addAll(deniedPermissions);
                        allGranted.set(false);
                        latch.countDown();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error requesting permissions", e);
                result.errorMessage = "请求权限异常: " + e.getMessage();
                allGranted.set(false);
                latch.countDown();
            }
        });

        try {
            boolean completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            
            if (!completed) {
                result.success = true;
                result.granted = false;
                result.timeout = true;
                result.errorMessage = "权限请求超时";
                
                for (String permission : permissionsToRequest) {
                    if (isPermissionGranted(permission)) {
                        result.grantedPermissions.add(permission);
                    } else {
                        result.deniedPermissions.add(permission);
                    }
                }
                
                result.granted = result.deniedPermissions.isEmpty();
                Log.w(TAG, "Permissions request timeout");
            } else {
                result.success = true;
                result.granted = allGranted.get();
                
                if (allGranted.get()) {
                    result.grantedPermissions.addAll(permissionsToRequest);
                    result.errorMessage = "所有权限请求成功";
                } else {
                    result.deniedPermissions.addAll(deniedList);
                    if (deniedList.isEmpty()) {
                        for (String permission : permissionsToRequest) {
                            if (!isPermissionGranted(permission)) {
                                result.deniedPermissions.add(permission);
                            }
                        }
                    }
                    result.errorMessage = "部分或全部权限被拒绝";
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.success = false;
            result.granted = false;
            result.errorMessage = "权限请求被中断";
            Log.e(TAG, "Permissions request interrupted", e);
        }

        return result;
    }

    public PermissionRequestResult ensurePermissionGroup(String groupName) {
        return ensurePermissionGroup(groupName, DEFAULT_PERMISSION_REQUEST_TIMEOUT_MS);
    }

    public PermissionRequestResult ensurePermissionGroup(String groupName, long timeoutMs) {
        String[] permissions = getPermissionsInGroup(groupName);
        if (permissions == null || permissions.length == 0) {
            PermissionRequestResult result = new PermissionRequestResult();
            result.success = false;
            result.errorMessage = "未知的权限组: " + groupName;
            Log.e(TAG, "Unknown permission group: " + groupName);
            return result;
        }
        return ensurePermissions(timeoutMs, permissions);
    }

    public PermissionRequestResult ensureLocationPermission() {
        return ensureLocationPermission(DEFAULT_PERMISSION_REQUEST_TIMEOUT_MS);
    }

    public PermissionRequestResult ensureLocationPermission(long timeoutMs) {
        if (hasLocationPermission()) {
            PermissionRequestResult result = new PermissionRequestResult();
            result.success = true;
            result.granted = true;
            result.hasActivity = true;
            if (isPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                result.grantedPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
            if (isPermissionGranted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                result.grantedPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            }
            result.errorMessage = "位置权限已授予";
            return result;
        }
        return ensurePermissionGroup("location", timeoutMs);
    }

    private void showPermissionRequestDialog(Activity activity, String[] permissions, PermissionCallback callback) {
        StringBuilder permissionNames = new StringBuilder();
        for (int i = 0; i < permissions.length; i++) {
            if (i > 0) {
                permissionNames.append("、");
            }
            permissionNames.append(getPermissionFriendlyName(permissions[i]));
        }

        String message = String.format(activity.getString(com.oilquiz.app.R.string.permission_request_message), permissionNames.toString());

        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(com.oilquiz.app.R.string.permission_request_title))
                .setMessage(message)
                .setPositiveButton(activity.getString(android.R.string.ok), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        executePermissionRequest(activity, permissions, callback);
                    }
                })
                .setNegativeButton(activity.getString(android.R.string.cancel), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (callback != null) {
                            List<String> deniedList = new ArrayList<>();
                            for (String p : permissions) {
                                deniedList.add(p);
                            }
                            callback.onDenied(deniedList);
                        }
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void executePermissionRequest(Activity activity, String[] permissions, PermissionCallback callback) {
        int requestCode = nextRequestCode();
        if (callback != null) {
            pendingCallbacks.put(requestCode, callback);
        }
        ActivityCompat.requestPermissions(activity, permissions, requestCode);
    }

    /**
     * 直接请求权限（跳过自定义确认 Dialog，直接调用系统权限请求）。
     * 用于 Agent 工具调用场景，避免弹出两个对话框。
     * 如果系统不允许再弹出权限请求（用户选择了“不再询问”），
     * 则通过 callback.onDenied() 返回，调用方可引导用户去设置页。
     */
    public void requestPermissionDirect(Activity activity, String permission, PermissionCallback callback) {
        if (isPermissionGranted(permission)) {
            if (callback != null) {
                callback.onGranted();
            }
            return;
        }
        // 直接调用系统权限请求，不弹自定义 Dialog
        int requestCode = nextRequestCode();
        if (callback != null) {
            pendingCallbacks.put(requestCode, callback);
        }
        ActivityCompat.requestPermissions(activity, new String[]{permission}, requestCode);
    }
}

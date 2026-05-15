package com.oilquiz.app.resource;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class PermissionResourceProvider {

    private static final String TAG = "PermissionResourceProvider";
    private static final int REQUEST_CODE_BASE = 10000;

    private static PermissionResourceProvider instance;
    private Context context;

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
        return isPermissionGroupGranted("location");
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
}

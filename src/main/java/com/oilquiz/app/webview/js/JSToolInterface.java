package com.oilquiz.app.webview.js;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import com.oilquiz.app.SmartQuizApplication;

/**
 * JavaScript 工具接口
 * 低风险方法，不需要特殊权限
 */
public class JSToolInterface {
    private static final String TAG = "JSToolInterface";
    private final Context context;

    public JSToolInterface(Context context) {
        this.context = context;
    }

    @JavascriptInterface
    public void showToast(String message) {
        if (context == null) return;
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    @JavascriptInterface
    public void showToastLong(String message) {
        if (context == null) return;
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }

    @JavascriptInterface
    public void vibrate(long milliseconds) {
        if (context == null) return;
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(milliseconds);
            }
        }
    }

    @JavascriptInterface
    public String getAppVersion() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "未知";
        }
    }

    @JavascriptInterface
    public String getPackageName() {
        return context.getPackageName();
    }

    @JavascriptInterface
    public long getCurrentTime() {
        return System.currentTimeMillis();
    }

    @JavascriptInterface
    public String getDeviceInfo() {
        try {
            org.json.JSONObject info = new org.json.JSONObject();
            info.put("manufacturer", Build.MANUFACTURER);
            info.put("model", Build.MODEL);
            info.put("androidVersion", Build.VERSION.RELEASE);
            info.put("sdkVersion", Build.VERSION.SDK_INT);
            info.put("appVersion", getAppVersion());
            return info.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    @JavascriptInterface
    public void finishActivity() {
        if (context instanceof android.app.Activity) {
            ((android.app.Activity) context).finish();
        }
    }

    @JavascriptInterface
    public void openUrlInBrowser(String url) {
        if (context == null || url == null) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "无法打开链接: " + url, e);
            showToast("无法打开链接");
        }
    }
}

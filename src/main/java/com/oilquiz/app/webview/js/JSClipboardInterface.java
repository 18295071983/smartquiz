package com.oilquiz.app.webview.js;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Log;
import android.webkit.JavascriptInterface;

/**
 * JavaScript 剪贴板接口
 */
public class JSClipboardInterface {
    private static final String TAG = "JSClipboardInterface";
    private final Context context;

    public JSClipboardInterface(Context context) {
        this.context = context;
    }

    /**
     * 写入剪贴板
     */
    @JavascriptInterface
    public void setClipboard(String text) {
        if (context == null || text == null) return;
        
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                ClipData clip = ClipData.newPlainText("Copied Text", text);
                clipboard.setPrimaryClip(clip);
                Log.d(TAG, "剪贴板内容已设置，长度: " + text.length());
            }
        } catch (Exception e) {
            Log.e(TAG, "设置剪贴板失败", e);
        }
    }

    /**
     * 读取剪贴板
     */
    @JavascriptInterface
    public String getClipboard() {
        if (context == null) return "";
        
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                ClipData clip = clipboard.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence text = clip.getItemAt(0).getText();
                    if (text != null) {
                        Log.d(TAG, "剪贴板内容已读取，长度: " + text.length());
                        return text.toString();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "读取剪贴板失败", e);
        }
        return "";
    }

    /**
     * 清空剪贴板
     */
    @JavascriptInterface
    public void clearClipboard() {
        if (context == null) return;
        
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                ClipData emptyClip = ClipData.newPlainText("", "");
                clipboard.setPrimaryClip(emptyClip);
                Log.d(TAG, "剪贴板已清空");
            }
        } catch (Exception e) {
            Log.e(TAG, "清空剪贴板失败", e);
        }
    }

    /**
     * 检查剪贴板是否有内容
     */
    @JavascriptInterface
    public boolean hasClipboard() {
        if (context == null) return false;
        
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                return clipboard.hasPrimaryClip() && clipboard.getPrimaryClipDescription() != null;
            }
        } catch (Exception e) {
            Log.e(TAG, "检查剪贴板失败", e);
        }
        return false;
    }
}

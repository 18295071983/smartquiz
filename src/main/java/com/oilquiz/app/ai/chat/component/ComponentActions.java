package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 组件动作行渲染与执行（actions 按钮）。
 *
 * 组件数据 props.actions 声明可点击按钮：
 * <pre>
 * "actions": [
 *   {"label":"打开","link":"https://..."},   // http/https 应用内打开；file:// 或绝对路径系统打开
 *   {"label":"复制","copy":"要复制的文本"}
 * ]
 * </pre>
 * 供 AlertCardView / DynamicCardView（自定义组件兜底）等组件共用，
 * 让 Agent 输出的组件按钮真实可点、点击有反应（不再"不能回调"）。
 */
public final class ComponentActions {

    /** 组件结果回调：component_id → 用户操作结果（供 Agent get_result 取回） */
    private static volatile java.util.function.BiConsumer<String, String> resultCallback;

    private ComponentActions() {
    }

    /**
     * 注册组件结果回调（由系统 UI 组件工具设置）。
     * 聊天流组件按钮点击 action=callback 时，把 value 回传给组件注册表。
     */
    public static void setResultCallback(java.util.function.BiConsumer<String, String> callback) {
        resultCallback = callback;
    }

    /** 确保回调已注册：app 重启/历史会话恢复后静态回调丢失，点击时自动补注册兜底。 */
    private static java.util.function.BiConsumer<String, String> ensureResultCallback(android.content.Context context) {
        java.util.function.BiConsumer<String, String> cb = resultCallback;
        if (cb != null) return cb;
        synchronized (ComponentActions.class) {
            if (resultCallback == null) {
                try {
                    android.content.Context appCtx = context != null
                            ? context.getApplicationContext()
                            : com.oilquiz.app.SmartQuizApplication.getAppContext();
                    if (appCtx == null) return null;
                    com.oilquiz.app.ai.python.PythonToolManager ptm =
                            com.oilquiz.app.ai.python.PythonToolManager.getInstance(appCtx);
                    resultCallback = (cid, value) -> {
                        if (cid != null && value != null) {
                            ptm.notifyChatComponentResult(cid, value);
                        }
                    };
                } catch (Throwable t) {
                    android.util.Log.w("ComponentActions", "兜底注册回调失败: " + t.getMessage());
                }
            }
            return resultCallback;
        }
    }

    /** 通知组件结果（组件按钮 action=callback 时调用）。回调为空时用 context 兜底注册。 */
    private static void notifyResult(Context context, String componentId, String value) {
        android.util.Log.i("ComponentActions", "notifyResult cid=" + componentId + " value=" + value
                + " cb=" + (resultCallback != null));
        java.util.function.BiConsumer<String, String> cb = resultCallback;
        if (cb == null) {
            cb = ensureResultCallback(context);
            android.util.Log.i("ComponentActions", "兜底注册后 cb=" + (cb != null));
        }
        if (cb != null && componentId != null && value != null) {
            try {
                cb.accept(componentId, value);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 在卡片底部渲染动作按钮行（无 actions 或全部无效时返回 false，不添加任何 View）。
     */
    public static boolean renderActions(LinearLayout card, Context context, JSONArray actions) {
        if (card == null || context == null || actions == null || actions.length() == 0) {
            return false;
        }
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(context, 8), 0, 0);
        boolean added = false;
        for (int i = 0; i < actions.length(); i++) {
            JSONObject a = actions.optJSONObject(i);
            if (a == null) continue;
            final String label = a.optString("label", "打开");
            final String link = a.optString("link", "");
            final String copy = a.optString("copy", "");
            final String action = a.optString("action", "");
            final String componentId = a.optString("component_id", "");
            final String value = a.optString("value", "");
            if (link.isEmpty() && copy.isEmpty() && action.isEmpty()) continue;
            TextView btn = new TextView(context);
            btn.setText(label);
            btn.setTextSize(12);
            btn.setTextColor(ComponentColors.accent(context));
            btn.setPadding(0, 0, dp(context, 16), 0);
            btn.setGravity(Gravity.CENTER_VERTICAL);
            btn.setClickable(true);
            btn.setOnClickListener(v -> {
                if ("callback".equals(action) && !componentId.isEmpty()) {
                    // 交互内置组件：按钮点击把 value 回传给组件注册表（Agent get_result 取回）
                    notifyResult(context, componentId, value.isEmpty() ? label : value);
                    return;
                }
                execute(context, link, copy, action);
            });
            row.addView(btn);
            added = true;
        }
        if (added) {
            card.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        return added;
    }

    /** 执行单个动作（link 打开 / copy 复制 / action 内置动作） */
    public static void execute(Context context, String link, String copy, String action) {
        if (context == null) return;
        try {
            if (!copy.isEmpty()) {
                copyToClipboard(context, copy);
                return;
            }
            if (!action.isEmpty()) {
                switch (action) {
                    case "copy":
                        // action=copy 时复制组件内容（调用方把内容放入 copy 字段即可，此处兜底无内容）
                        Toast.makeText(context, "无可用动作", Toast.LENGTH_SHORT).show();
                        return;
                    case "refresh":
                        // 预留：动态组件刷新（当前无数据源，提示即可）
                        Toast.makeText(context, "无可用动作", Toast.LENGTH_SHORT).show();
                        return;
                    default:
                        Toast.makeText(context, "未知动作: " + action, Toast.LENGTH_SHORT).show();
                        return;
                }
            }
            if (!link.isEmpty()) {
                openLink(context, link);
            }
        } catch (Exception e) {
            Toast.makeText(context, "动作执行失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 打开链接：http/https 应用内 WebView；file:// 或绝对路径系统 Intent 打开 */
    public static void openLink(Context context, String link) {
        try {
            if (link == null || link.isEmpty()) {
                Toast.makeText(context, "链接为空", Toast.LENGTH_SHORT).show();
                return;
            }
            if (link.startsWith("http://") || link.startsWith("https://")) {
                Intent intent = new Intent(context, com.oilquiz.app.WebViewActivity.class);
                intent.putExtra("url", link);
                if (!(context instanceof android.app.Activity)) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                context.startActivity(intent);
                return;
            }
            // file:// 或绝对路径：FileProvider + 系统 Intent（与 SimpleWebViewActivity 打开文件一致）
            String path = link;
            if (link.startsWith("file://")) {
                path = Uri.parse(link).getPath();
            }
            java.io.File f = new java.io.File(path);
            if (!f.exists() || !f.isFile()) {
                Toast.makeText(context, "文件不存在: " + path, Toast.LENGTH_SHORT).show();
                return;
            }
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "com.oilquiz.app.fileprovider", f);
            String ext = "";
            int dot = f.getName().lastIndexOf('.');
            if (dot > 0) ext = f.getName().substring(dot + 1).toLowerCase();
            String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mime != null ? mime : "*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (!(context instanceof android.app.Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, "无法打开: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static void copyToClipboard(Context context, String text) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("组件内容", text));
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(context, "复制失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}

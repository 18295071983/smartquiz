package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import com.oilquiz.app.R;

import org.json.JSONObject;

/**
 * 应用/页面卡片（2026-10-21）：把 app_operation 的导航结果做成一张可再次进入的卡。
 *
 * <p>props：
 * <pre>
 * {
 *   "title": "AI 生图",
 *   "message": "已导航到: media_gen",
 *   "target": "com.oilquiz.app.ui.activity.MediaGenActivity",
 *   "uri": "content://...",        // 可选，外部 URI
 *   "package": "com.xxx.app",      // 可选，外部 App
 *   "params": "{\"mode\":\"image\"}" // 可选，重开时注入 extras
 * }
 * </pre>
 * 内部页面按 target 类名启动；外部 App 按 package；都没有则显示错误态。
 */
public class AppCardView implements ChatComponent {

    @Override
    public String getType() {
        return "app_card";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null
                && (!TextUtils.isEmpty(data.props.optString("title", ""))
                || !TextUtils.isEmpty(data.props.optString("target", ""))
                || !TextUtils.isEmpty(data.props.optString("package", "")));
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        String title = p.optString("title", "");
        String message = p.optString("message", "");
        final String target = p.optString("target", "");
        final String pkg = p.optString("package", "");
        final String uri = p.optString("uri", "");
        final String params = p.optString("params", "");
        if (title.isEmpty()) title = simpleName(target);

        CardShell shell = CardShell.create(context)
                .setHeaderIcon(R.drawable.ic_home_toolbox, ComponentTheme.brand(context))
                .setHeader(title, null);
        if (!message.isEmpty()) shell.setSubtitle(message);

        if (target.isEmpty() && pkg.isEmpty() && uri.isEmpty()) {
            shell.setState(CardShell.State.ERROR, context.getString(R.string.card_app_no_target));
            return shell;
        }
        shell.addAction(context.getString(R.string.card_app_open), true,
                () -> open(context, target, pkg, uri, params));
        return shell;
    }

    private static void open(Context context, String target, String pkg, String uri, String params) {
        try {
            Intent intent = null;
            if (!TextUtils.isEmpty(target)) {
                Class<?> cls = Class.forName(target);
                intent = new Intent(context, cls);
                if (!TextUtils.isEmpty(params)) {
                    try {
                        JSONObject pj = new JSONObject(params);
                        java.util.Iterator<String> keys = pj.keys();
                        while (keys.hasNext()) {
                            String k = keys.next();
                            Object v = pj.opt(k);
                            intent.putExtra(k, v != null ? String.valueOf(v) : "");
                        }
                    } catch (Exception ignored) {
                    }
                }
            } else if (!TextUtils.isEmpty(pkg)) {
                intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
            } else if (!TextUtils.isEmpty(uri)) {
                if (uri.startsWith("http://") || uri.startsWith("https://")) {
                    ComponentActions.openLink(context, uri);
                    return;
                }
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            }
            if (intent == null) {
                Toast.makeText(context, R.string.card_app_not_found, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!(context instanceof android.app.Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(context, context.getString(R.string.card_app_open_failed, t.getMessage()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private static String simpleName(String target) {
        if (target == null || target.isEmpty()) return "";
        int dot = target.lastIndexOf('.');
        return dot >= 0 ? target.substring(dot + 1) : target;
    }
}

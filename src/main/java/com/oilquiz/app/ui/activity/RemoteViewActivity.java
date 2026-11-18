package com.oilquiz.app.ui.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.oilquiz.app.ai.remote.view.EffectExecutor;
import com.oilquiz.app.ai.remote.view.ViewRenderer;
import com.oilquiz.app.ai.tool.RemoteDshTool;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 插件视图页（C3，2026-11-14）：把插件推来的 JSON 组件树渲染成一屏。
 *
 * <p>这是"只改插件就能加界面"的落地证明：手机这里没有任何业务代码，只做三件事——
 * 渲染（{@link ViewRenderer}）、点按发 invoke、收到 effects 交给 {@link EffectExecutor}。</p>
 */
public class RemoteViewActivity extends Activity {

    /** 视图 JSON（静态传参：树可能较大，避免走 Intent 序列化的坑） */
    public static volatile String pendingViewJson = null;
    /** 点按时间戳：用于 3 秒效果窗口 */
    private long clickAt = 0L;

    public static void open(Context ctx, JSONObject view) {
        if (view == null) return;
        pendingViewJson = view.toString();
        Intent it = new Intent(ctx, RemoteViewActivity.class);
        if (!(ctx instanceof Activity)) it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(it);
    }

    /**
     * 按名字打开插件下发的视图（C5）：定义由 view_def 帧缓存，命令里只传名字即可。
     */
    public static void openNamed(Context ctx, String name) {
        JSONObject view = com.oilquiz.app.ai.tool.RemoteViewDefs.get(ctx, name);
        if (view == null) {
            Toast.makeText(ctx, "插件还没下发视图：" + name, Toast.LENGTH_SHORT).show();
            return;
        }
        open(ctx, view);
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String json = pendingViewJson;
        pendingViewJson = null;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF7F7FA);
        int pad = dp(16);
        root.setPadding(pad, dp(16), pad, dp(16));

        TextView back = new TextView(this);
        back.setText("‹ 返回");
        back.setTextSize(15);
        back.setTextColor(0xFF2F5BD6);
        back.setPadding(0, 0, 0, dp(10));
        back.setOnClickListener(v -> finish());
        root.addView(back);

        if (json == null) {
            TextView tv = new TextView(this);
            tv.setText("没有要显示的插件界面");
            root.addView(tv);
            setContentView(root);
            return;
        }

        try {
            JSONObject view = new JSONObject(json);
            View rendered = ViewRenderer.render(this, view, (action, args) -> {
                clickAt = System.currentTimeMillis();
                invoke(action, args);
            });
            ScrollView scroll = new ScrollView(this);
            scroll.addView(rendered, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } catch (Throwable t) {
            TextView tv = new TextView(this);
            tv.setText("插件界面有误：" + t.getMessage());
            root.addView(tv);
        }
        setContentView(root);
        setTitle("插件界面");
    }

    /** 点按 → 插件 → 效果 */
    private void invoke(final String action, final JSONObject args) {
        new Thread(() -> {
            try {
                JSONObject resp = RemoteDshTool.invokeAction(this, action, args);
                final JSONArray effects = resp == null ? null : resp.optJSONArray("effects");
                runOnUiThread(() -> {
                    int n = EffectExecutor.execute(this, effects, clickAt, host);
                    if (n == 0) Toast.makeText(this, "没有响应", Toast.LENGTH_SHORT).show();
                });
            } catch (Throwable t) {
                Log.w("RemoteView", "invoke 失败: " + t.getMessage());
                runOnUiThread(() -> Toast.makeText(this, "动作失败：" + t.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }, "remote-view-invoke").start();
    }

    /** 效果落到本页能做的事；跨页面的（open_page / open_file）先给提示，后续接 */
    private final EffectExecutor.Host host = new EffectExecutor.Host() {
        @Override public void insertText(String text) { Toast.makeText(RemoteViewActivity.this, "已收到插入文本", Toast.LENGTH_SHORT).show(); }
        @Override public void runCommand(String name, String args) { Toast.makeText(RemoteViewActivity.this, "命令：" + name, Toast.LENGTH_SHORT).show(); }
        @Override public void openPage(String page) { Toast.makeText(RemoteViewActivity.this, "打开页面：" + page, Toast.LENGTH_SHORT).show(); }
        @Override public void openLink(String url) { EffectExecutor.openLinkWithBrowser(RemoteViewActivity.this, url); }
        @Override public void toast(String text) { Toast.makeText(RemoteViewActivity.this, text, Toast.LENGTH_SHORT).show(); }
        @Override public void copy(String text) {
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("插件", text));
                Toast.makeText(RemoteViewActivity.this, "已复制", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) { /* 忽略 */ }
        }
        @Override public void refresh() { Toast.makeText(RemoteViewActivity.this, "刷新", Toast.LENGTH_SHORT).show(); }
        @Override public void patch(JSONObject data) { Toast.makeText(RemoteViewActivity.this, "数据已更新", Toast.LENGTH_SHORT).show(); }
        @Override public void openFile(String path) {
        // 复用聊天里的文件渲染器（图片/代码/pdf/音视频/文件卡），不再只弹 Toast
        com.oilquiz.app.ai.chat.parts.ToolAssetPreview.show(RemoteViewActivity.this, path);
    }
    };

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}

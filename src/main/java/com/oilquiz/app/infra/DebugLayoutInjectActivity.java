package com.oilquiz.app.infra;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Debug 测试注入页：adb 一键注入自定义 layout 控件树，用于回归验证
 * （divider 渲染 / 输入法唤起 等）。不参与正式功能。
 *
 * 用法（adb shell）：
 *  am start -n com.oilquiz.app/.infra.DebugLayoutInjectActivity \
 *    --es layout '{"root":{"type":"column","children":[{"type":"text","text":"divider测试","bold":true},{"type":"divider"},{"type":"input","hint":"点击输入"}]}}'
 *
 * 可选 extra：
 *  --es mode dialog|chat  渲染模式（默认 dialog 弹窗；chat 进聊天流）
 *  --es title 弹窗标题
 */
public class DebugLayoutInjectActivity extends Activity {

    private static final String TAG = "DebugLayoutInject";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String layoutJson = getIntent().getStringExtra("layout");
        // 支持 base64 编码传入（adb shell 传 JSON 转义易错，base64 最稳）
        String layoutB64 = getIntent().getStringExtra("layout_b64");
        if ((layoutJson == null || layoutJson.isEmpty()) && layoutB64 != null && !layoutB64.isEmpty()) {
            try {
                byte[] bytes = android.util.Base64.decode(layoutB64, android.util.Base64.DEFAULT);
                layoutJson = new String(bytes, "UTF-8");
            } catch (Exception e) {
                Log.w(TAG, "base64 解码失败: " + e.getMessage());
            }
        }
        String mode = getIntent().getStringExtra("mode");
        String title = getIntent().getStringExtra("title");
        if (layoutJson == null || layoutJson.isEmpty()) {
            layoutJson = "{\"root\":{\"type\":\"column\",\"children\":["
                    + "{\"type\":\"text\",\"text\":\"debug注入测试\",\"bold\":true},"
                    + "{\"type\":\"divider\"},"
                    + "{\"type\":\"input\",\"hint\":\"点击输入测试\",\"key\":\"t1\"}]}}";
        }

        try {
            org.json.JSONObject layout = new org.json.JSONObject(layoutJson);
            if ("chat".equals(mode)) {
                injectToChat(layout, title);
            } else if ("card".equals(mode)) {
                // 内置组件卡片测试：card_type=内置类型名，props=JSON 字符串 → ui_component create
                // （createBuiltinChatComponent → 聊天流卡片渲染）。延迟到 AIChatActivity resume 后执行。
                String cardType = getIntent().getStringExtra("card_type");
                String propsJson = getIntent().getStringExtra("props");
                final String fType = cardType;
                final String fProps = propsJson;
                finish();
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        java.util.Map<String, Object> params = new java.util.HashMap<>();
                        params.put("action", "create");
                        params.put("component_type", fType);
                        if (fProps != null && !fProps.isEmpty()) params.put("props", fProps);
                        if (title != null && !title.isEmpty()) params.put("title", title);
                        com.oilquiz.app.ai.tool.AIToolManager tm =
                                com.oilquiz.app.ai.tool.AIToolManager.getInstance(getApplicationContext());
                        com.oilquiz.app.ai.tool.AIToolResult r = tm.executeTool("ui_component", params);
                        Log.i(TAG, "内置卡片注入: " + fType + " → " + (r != null ? r.getResult() : "null"));
                    } catch (Throwable t) {
                        Log.e(TAG, "内置卡片注入失败: " + t.getMessage(), t);
                    }
                }, 600);
            } else if ("plugin".equals(mode)) {
                // 完整链路验证：register_type(render.layout 含输入控件+提交按钮) → create → 弹窗 → get_result
                // 延迟到 Activity resume 后执行（onCreate 时 getCurrentActivity 尚为 null，createUiComponent 会降级 Toast）
                final String fLayout = layout.toString();
                final String fTitle = title;
                getWindow().getDecorView().postDelayed(() -> injectPluginFlow(fLayout, fTitle), 600);
            } else {
                showDialog(layout, title);
            }
        } catch (Exception e) {
            Log.e(TAG, "注入失败: " + e.getMessage(), e);
            LinearLayout err = new LinearLayout(this);
            err.setOrientation(LinearLayout.VERTICAL);
            TextView tv = new TextView(this);
            tv.setText("注入失败: " + e.getMessage());
            err.addView(tv);
            setContentView(err);
        }
    }

    /** 插件流验证：注册类型 → 创建（弹窗渲染 layout）→ 后台 get_result（10s 等待用户操作后回传） */
    private void injectPluginFlow(String layoutJson, String title) {
        try {
            com.oilquiz.app.ai.tool.AIToolManager tm = com.oilquiz.app.ai.tool.AIToolManager.getInstance(this);
            // 1) 注册临时类型（render.layout = 注入的控件树）
            java.util.Map<String, Object> reg = new java.util.HashMap<>();
            reg.put("action", "register_type");
            reg.put("name", "debug_plugin_flow");
            reg.put("description", "输入回传验证");
            reg.put("persist", false);
            org.json.JSONObject renderObj = new org.json.JSONObject();
            renderObj.put("layout", new org.json.JSONObject(layoutJson));
            reg.put("render", renderObj.toString());
            com.oilquiz.app.ai.tool.AIToolResult r1 = tm.executeTool("ui_component", reg);
            Log.i(TAG, "注册类型结果: " + (r1 != null ? r1.getResult() : "null"));
            // 2) 创建该类型（弹窗渲染）
            java.util.Map<String, Object> create = new java.util.HashMap<>();
            create.put("action", "create");
            create.put("component_type", "debug_plugin_flow");
            if (title != null && !title.isEmpty()) create.put("title", title);
            com.oilquiz.app.ai.tool.AIToolResult r2 = tm.executeTool("ui_component", create);
            Log.i(TAG, "创建结果: " + (r2 != null ? r2.getResult() : "null"));
            String componentId = null;
            try {
                java.util.Map<String, Object> m = (java.util.Map<String, Object>) r2.getResult();
                if (m != null) componentId = String.valueOf(m.get("component_id"));
            } catch (Exception ignored) {
            }
            // 3) 后台 get_result：等待用户操作（测试脚本 tap 输入+提交），超时 60s
            final String cid = componentId;
            new Thread(() -> {
                try {
                    Thread.sleep(3000);
                    java.util.Map<String, Object> gr = new java.util.HashMap<>();
                    gr.put("action", "get_result");
                    gr.put("component_id", cid);
                    gr.put("wait_seconds", 60);
                    com.oilquiz.app.ai.tool.AIToolResult r3 = tm.executeTool("ui_component", gr);
                    Log.i(TAG, "get_result 回传: " + (r3 != null ? r3.getResult() : "null"));
                } catch (Throwable t) {
                    Log.e(TAG, "get_result 异常: " + t.getMessage(), t);
                }
            }).start();
            // 组件关闭后结束本 Activity（避免窗口泄漏）；给慢速交互测试充足时间
            android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(() -> {
                if (!isFinishing()) {
                    Log.i(TAG, "验证超时结束 Activity");
                    finish();
                }
            }, 120000);
        } catch (Throwable t) {
            Log.e(TAG, "插件流注入失败: " + t.getMessage(), t);
            finish();
        }
    }

    /** 弹窗模式：直接用 NativeLayoutRenderer 渲染 layout 到 AlertDialog（含 input → 验证输入法） */
    private void showDialog(org.json.JSONObject layout, String title) {
        android.view.View view = com.oilquiz.app.ai.python.NativeLayoutRenderer.render(
                this, layout.toString(), null, new java.util.HashMap<>());
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        if (title != null && !title.isEmpty()) b.setTitle(title);
        b.setView(view);
        b.setPositiveButton("关闭", (d, w) -> d.dismiss());
        b.setCancelable(true);
        android.app.AlertDialog dialog = b.create();
        // 输入法配置：ADJUST_RESIZE + show 后注入 EditText 触摸弹键盘
        dialog.getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.setOnShowListener(d -> {
            dialog.getWindow().getDecorView().postDelayed(() -> {
                injectIme(dialog.getWindow().getDecorView(), 0);
            }, 250);
        });
        // 注意：不能 show() 后立即 finish——Activity 销毁时对话框窗口还活着会抛 WindowLeaked。
        // 改为对话框关闭时才结束本 Activity，保证弹窗全程可交互（含输入法唤起验证）。
        dialog.setOnDismissListener(d -> finish());
        dialog.show();
        Log.i(TAG, "弹窗已显示（layout 注入）");
    }

    /** 递归为 EditText 注入点击弹键盘（回归验证输入法唤起） */
    private void injectIme(android.view.View view, int depth) {
        if (view == null || depth > 15) return;
        if (view instanceof android.widget.EditText) {
            final android.widget.EditText et = (android.widget.EditText) view;
            et.setOnClickListener(v -> {
                et.requestFocus();
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    et.postDelayed(() -> {
                        boolean shown = imm.showSoftInput(et, 0);
                        Log.i(TAG, "showSoftInput result=" + shown
                                + " focused=" + et.hasFocus()
                                + " served=" + imm.isActive(et));
                    }, 80);
                }
            });
            return;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                injectIme(vg.getChildAt(i), depth + 1);
            }
        }
    }

    /** 聊天流模式：通过 ui_component 工具创建卡片（走 withComponent → 聊天流渲染）。
     *  注意：本 Activity 启动会抢占前台，onCreate 时 getCurrentActivity() 仍为 null（上个
     *  Activity 已 pause、本 Activity 未 resume），直接注入会降级 Toast。正确做法：
     *  先 finish 自己让 AIChatActivity 回到前台，延迟 600ms 等其 resume 后再注入，
     *  createUiComponent 才能找到前台 Activity 渲染聊天流卡片。
     *  用 Application context + 全局 Handler（Activity finish 后 decorView 的 post 不执行）。 */
    private void injectToChat(org.json.JSONObject layout, String title) {
        finish();
        final String layoutStr = layout.toString();
        final String titleStr = title;
        final android.content.Context appCtx = getApplicationContext();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            try {
                java.util.Map<String, Object> params = new java.util.HashMap<>();
                params.put("action", "create");
                params.put("component_type", "debug_layout_test");
                params.put("layout", layoutStr);
                if (titleStr != null) params.put("title", titleStr);
                com.oilquiz.app.ai.tool.AIToolManager tm =
                        com.oilquiz.app.ai.tool.AIToolManager.getInstance(appCtx);
                com.oilquiz.app.ai.tool.AIToolResult r = tm.executeTool("ui_component", params);
                Log.i(TAG, "聊天流注入结果: " + (r != null ? r.getResult() : "null"));
            } catch (Throwable t) {
                Log.e(TAG, "聊天流注入失败: " + t.getMessage(), t);
            }
        }, 600);
    }
}

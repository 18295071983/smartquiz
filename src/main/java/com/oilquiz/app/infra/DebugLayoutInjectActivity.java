package com.oilquiz.app.infra;

import com.oilquiz.app.R;
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
 *    --es layout '{"root":{"type":"column","children":[{"type":"text","text":getString(R.string.h_cf9ec974),"bold":true},{"type":"divider"},{"type":"input","hint":getString(R.string.h_d6f251e4)}]}}'
 *
 * 可选 extra：
 *  --es mode dialog|chat  渲染模式（默认 dialog 弹窗；chat 进聊天流）
 *  --es title 弹窗标题
 *
 * 2026-11-16（梯队 1.1）：不带 layout/card/url 等注入参数直接打开时，本入口升级为
 * 可视化「UI 预览台」（{@link UiPreviewConsoleActivity}：样例列表 + JSON 编辑 + 即时渲染）；
 * 也可显式 --es mode preview 进入，并支持 --es sample <id> / --es json_b64 <base64> 定位样例。
 * 带 layout 等参数的旧 adb 注入模式全部保持原行为。
 */
public class DebugLayoutInjectActivity extends Activity {

    private static final String TAG = "DebugLayoutInject";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 仅 debug 包可用（release 构建里清单还会把 exported 置 false，这里是第二道防线）：
        // 这个入口能用 adb 注入任意卡片/直接播放媒体，绝不能留在正式包里。
        boolean debuggable = (getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (!debuggable) {
            android.util.Log.w(TAG, "release 构建不允许使用调试注入入口，finish()");
            finish();
            return;
        }

        // 系统动态色开关（2026-11-16）：--es mode set_dynamic --ez on true|false
        if ("set_dynamic".equals(getIntent().getStringExtra("mode"))) {
            boolean on = getIntent().getBooleanExtra("on", true);
            com.oilquiz.app.manager.ThemeManager.setSystemDynamicColor(this, on);
            recreate();
            finish();
            return;
        }

        // 打开任意本 App 的 Activity（2026-11-16）：--es mode open_activity --es target <全类名>
        // 用途：远程连接/扩展面板这些 exported=false 的页面，adb am start 起不来，用它截图验收。
        if ("open_activity".equals(getIntent().getStringExtra("mode"))) {
            String target = getIntent().getStringExtra("target");
            if (target != null && !target.isEmpty()) {
                try {
                    android.content.Intent i = new android.content.Intent();
                    i.setClassName(this, target);
                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Throwable t) {
                    Log.e(TAG, "open_activity 失败: " + t.getMessage(), t);
                }
            }
            finish();
            return;
        }

        // 交互回传自检（2026-11-16）：--es mode input_feedback
        // 创建一个 slider_range 交互组件 → 等 2s → get_result，验证 input collector 是否生效
        if ("input_feedback".equals(getIntent().getStringExtra("mode"))) {
            try {
                com.oilquiz.app.ai.tool.AIToolManager tm =
                        com.oilquiz.app.ai.tool.AIToolManager.getInstance(this);
                java.util.Map<String, Object> create = new java.util.HashMap<>();
                create.put("action", "create");
                // primitive_* 是"聊天流只读卡"（无 component_id、不支持 get_result），
                // 官方可交互容器是 layout_canvas：输入控件由 LayoutCardView 自动注册 collector。
                create.put("component_type", "layout_canvas");
                create.put("title", "交互回传自检");
                org.json.JSONObject props = new org.json.JSONObject();
                props.put("layout", "{\"root\":{\"type\":\"column\",\"children\":[{\"type\":\"text\",\"text\":\"交互回传自检\"},{\"type\":\"slider_range\",\"key\":\"amount\",\"label\":\"金额\",\"min\":0,\"max\":100,\"values\":[20,80]},{\"type\":\"toggle\",\"key\":\"auto\",\"label\":\"自动\"},{\"type\":\"button\",\"text\":\"提交\",\"action\":\"submit\"}]}}");
                props.put("min", 0);
                props.put("max", 100);
                props.put("values", new org.json.JSONArray().put(20).put(80));
                create.put("props", props.toString());
                com.oilquiz.app.ai.tool.AIToolResult r1 = tm.executeTool("ui_component", create);
                Log.i(TAG, "INPUT_CREATE=" + (r1 == null ? "null" : r1.getResult()));
                String cid = null;
                try {
                    java.util.Map<String, Object> m = (java.util.Map<String, Object>) r1.getResult();
                    if (m != null) cid = String.valueOf(m.get("component_id"));
                } catch (Throwable ignored) {
                }
                final String fcid = cid;
                new Thread(() -> {
                    try {
                        Thread.sleep(2500);
                        java.util.Map<String, Object> gr = new java.util.HashMap<>();
                        gr.put("action", "get_result");
                        gr.put("component_id", fcid);
                        gr.put("wait_seconds", 2);
                        com.oilquiz.app.ai.tool.AIToolResult r2 = tm.executeTool("ui_component", gr);
                        Log.i(TAG, "INPUT_FEEDBACK=" + (r2 == null ? "null" : r2.getResult()));
                        // 再等 12 秒取一次：主机侧会在这段时间里 adb swipe 拖动滑块，
                        // 第二次 get_result 应拿到 {value/values} 而不是 pending
                        Thread.sleep(12000);
                        com.oilquiz.app.ai.tool.AIToolResult r3 = tm.executeTool("ui_component", gr);
                        Log.i(TAG, "INPUT_VALUE=" + (r3 == null ? "null" : r3.getResult()));
                    } catch (Throwable t) {
                        Log.e(TAG, "input_feedback 失败: " + t.getMessage(), t);
                    }
                }).start();
            } catch (Throwable t) {
                Log.e(TAG, "input_feedback 注入失败: " + t.getMessage(), t);
            }
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!isFinishing()) finish();
            }, 20000);
            return;
        }

        // 渲染反馈回传自检：--es mode render_feedback
        // 1) 用错误 props 渲染一张 table_card（必然走兜底并记诊断）
        // 2) 紧接着调一次 time_date（工具结果里应带上 render_warnings）
        if ("render_feedback".equals(getIntent().getStringExtra("mode"))) {
            try {
                com.oilquiz.app.ai.chat.component.ComponentData bad =
                        com.oilquiz.app.ai.chat.component.ComponentData.of("table_card",
                                new org.json.JSONObject().put("title", "坏数据"));
                com.oilquiz.app.ai.chat.component.ComponentRegistry.getInstance()
                        .render(this, bad);   // 触发兜底 + 记录诊断
                com.oilquiz.app.ai.tool.AIToolManager tm =
                        com.oilquiz.app.ai.tool.AIToolManager.getInstance(this);
                java.util.Map<String, Object> args = new java.util.HashMap<>();
                args.put("action", "now");
                com.oilquiz.app.ai.tool.AIToolResult r = tm.executeTool("time_date", args);
                Object warn = r.getAdditionalInfo() == null ? null
                        : r.getAdditionalInfo().get("render_warning_text");
                Log.i(TAG, "RENDER_FEEDBACK=" + (warn == null ? "缺失" : warn));
            } catch (Throwable t) {
                Log.e(TAG, "render_feedback 自检失败: " + t.getMessage(), t);
            }
            finish();
            return;
        }

        // 列出全部已注册卡片类型（批量冒烟时先拿名单）：--es mode list_cards
        if ("list_cards".equals(getIntent().getStringExtra("mode"))) {
            java.util.Set<String> types = com.oilquiz.app.ai.chat.component.ComponentRegistry
                    .getInstance().allTypes();
            Log.i(TAG, "CARD_TYPES(" + types.size() + ")=" + String.join(",", types));
            finish();
            return;
        }

        // render.card 插件验证（2026-11-16）：注册一个「借内置卡片」的动态组件并弹窗渲染
        if ("card_plugin".equals(getIntent().getStringExtra("mode"))) {
            try {
                com.oilquiz.app.ai.tool.AIToolManager tm =
                        com.oilquiz.app.ai.tool.AIToolManager.getInstance(this);
                org.json.JSONObject render = new org.json.JSONObject();
                String cardType = getIntent().getStringExtra("card");
                if (cardType == null || cardType.trim().isEmpty()) {
                    cardType = "progress_card";
                }
                render.put("card", cardType);                  // ← 复用内置卡片（--es card 可指定）
                render.put("title", "动态组件 · render.card 验证");
                org.json.JSONObject props = new org.json.JSONObject();
                String propsB64 = getIntent().getStringExtra("props_b64");
                if (propsB64 != null && !propsB64.isEmpty()) {
                    // 批量冒烟：每个卡片类型可以带自己的最小 props（base64 避免 shell 转义地狱）
                    props = new org.json.JSONObject(new String(
                            android.util.Base64.decode(propsB64, android.util.Base64.DEFAULT), "UTF-8"));
                } else {
                    props.put("progress", 66);
                    props.put("title", "进度");
                    props.put("description", "动态组件借内置卡片渲染");
                    props.put("status", "running");
                }
                render.put("props", props);

                java.util.Map<String, Object> reg = new java.util.HashMap<>();
                reg.put("action", "register_type");
                reg.put("name", "debug_card_plugin");   // 同名覆盖注册，循环试卡不会堆积
                reg.put("description", "render.card 验证插件");
                reg.put("persist", false);
                reg.put("render", render.toString());
                com.oilquiz.app.ai.tool.AIToolResult r1 = tm.executeTool("ui_component", reg);
                Log.i(TAG, "register_type(render.card): " + (r1 != null ? r1.getResult() : "null"));

                java.util.Map<String, Object> create = new java.util.HashMap<>();
                create.put("action", "create");
                create.put("component_type", "debug_card_plugin");
                create.put("title", "render.card 验证");
                com.oilquiz.app.ai.tool.AIToolResult r2 = tm.executeTool("ui_component", create);
                Log.i(TAG, "create(render.card): " + (r2 != null ? r2.getResult() : "null"));
            } catch (Throwable t) {
                Log.e(TAG, "card_plugin 注入失败: " + t.getMessage(), t);
            }
            // 不立刻 finish：create 需要前台 Activity 才能弹窗渲染组件（否则降级成 Toast，看不到卡片）。
            // 留 60s 给截图，然后自动收尾避免窗口泄漏。
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!isFinishing()) {
                    finish();
                }
            }, 60000);
            return;
        }

        // Agent 设置（Git 凭据 + 工具权限分级展示）：--es mode agent_settings
        if ("agent_settings".equals(getIntent().getStringExtra("mode"))) {
            startActivity(new android.content.Intent(this,
                    com.oilquiz.app.infra.AgentSettingsActivity.class));
            finish();
            return;
        }

        // 规则引擎自检（真机验证 MVEL 表达式在 Android 运行时可用）：--es mode rule_selftest
        if ("rule_selftest".equals(getIntent().getStringExtra("mode"))) {
            java.util.Map<String, Object> args = new java.util.HashMap<>();
            args.put("rules", "[{\"name\":\"真机表达式\",\"when\":{\"expr\":\"balance < 10 && calls > 50\"},\"then\":\"MVEL-on-Android-OK\"}]");
            args.put("facts", "{\"balance\":5,\"calls\":80}");
            com.oilquiz.app.ai.tool.AIToolResult r =
                    new com.oilquiz.app.ai.tool.RuleEngineTool().execute(args);
            android.util.Log.i("RuleSelfTest", "ok=" + r.isSuccess() + " result=" + r.getResult()
                    + " err=" + r.getErrorMessage());
            finish();
            return;
        }

        // 列表工具包（ItemDecoration/ConcatAdapter/多选/骨架屏/FAB/ViewStub）：--es mode list_kit
        if ("list_kit".equals(getIntent().getStringExtra("mode"))) {
            startActivity(new android.content.Intent(this,
                    com.oilquiz.app.infra.ListKitGalleryActivity.class));
            finish();
            return;
        }

        // UI 工具包画廊（交互/动画/下拉刷新/圆形头像/Coil）：--es mode ui_kit
        if ("ui_kit".equals(getIntent().getStringExtra("mode"))) {
            startActivity(new android.content.Intent(this,
                    com.oilquiz.app.infra.UiKitGalleryActivity.class));
            finish();
            return;
        }

        // 组件画廊（Compose + Lottie 试点）：--es mode compose_gallery
        if ("compose_gallery".equals(getIntent().getStringExtra("mode"))) {
            startActivity(new android.content.Intent(this,
                    com.oilquiz.app.infra.ComposeGalleryActivity.class));
            finish();
            return;
        }

        // 主题设置页（2026-11-16）：exported=false，adb 只能从本调试入口进。
        if ("theme_settings".equals(getIntent().getStringExtra("mode"))) {
            startActivity(new android.content.Intent(this,
                    com.oilquiz.app.ui.activity.ThemeActivity.class)
                    .putExtra("open_custom_color", getIntent().getBooleanExtra("open_custom_color", false)));
            finish();
            return;
        }

        // 主题档位切换（2026-11-16）：--es mode set_theme --es theme light|dark|system|oled
        // 纯黑档没法用 adb 点 UI 验证，用它当开关。
        if ("set_theme".equals(getIntent().getStringExtra("mode"))) {
            String which = getIntent().getStringExtra("theme");
            int m = com.oilquiz.app.manager.ThemeManager.THEME_SYSTEM;
            if ("light".equals(which)) m = com.oilquiz.app.manager.ThemeManager.THEME_LIGHT;
            else if ("dark".equals(which)) m = com.oilquiz.app.manager.ThemeManager.THEME_DARK;
            else if ("oled".equals(which)) m = com.oilquiz.app.manager.ThemeManager.THEME_OLED;
            com.oilquiz.app.manager.ThemeManager.setMode(this, m);
            android.widget.Toast.makeText(this, "主题档位=" + which + "（" + m + "）",
                    android.widget.Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 主题自检页（2026-11-16）：本 Activity 是 debugExported，用它当 adb 入口。
        if ("theme_audit".equals(getIntent().getStringExtra("mode"))) {
            startActivity(new android.content.Intent(this,
                    com.oilquiz.app.infra.ThemeAuditActivity.class));
            finish();
            return;
        }

        // 新版画布（独立工具 ui_canvas）自检：--es mode canvas_v2 --es template form|settings|dashboard
        if ("canvas_v2".equals(getIntent().getStringExtra("mode"))) {
            try {
                java.util.Map<String, Object> args = new java.util.HashMap<>();
                args.put("action", "create");
                args.put("title", "V2 画布");
                String jsonB64 = getIntent().getStringExtra("json_b64");
                String ctype = getIntent().getStringExtra("ctype");
                if (jsonB64 != null && !jsonB64.isEmpty()) {
                    args.put("layout", new String(android.util.Base64.decode(
                            jsonB64, android.util.Base64.NO_WRAP),
                            java.nio.charset.StandardCharsets.UTF_8));
                } else if (ctype != null && !ctype.isEmpty()) {
                    args.put("component_type", ctype);
                } else {
                    args.put("template", getIntent().getStringExtra("template"));
                }
                com.oilquiz.app.ai.tool.AIToolResult r =
                        com.oilquiz.app.ai.tool.AIToolManager.getInstance(this)
                                .executeTool("ui_canvas", args);
                Log.i(TAG, "CANVAS_V2=" + (r == null ? "null"
                        : "success=" + r.isSuccess() + " result=" + r.getResult()
                        + " err=" + r.getErrorMessage()));
            } catch (Throwable t) {
                Log.e(TAG, "canvas_v2 自检失败: " + t.getMessage(), t);
            }
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!isFinishing()) finish();
            }, 60000);
            return;
        }

        // 无注入参数直接打开 = 可视化预览台；带 layout/card/url 等参数时保持原 adb 注入行为。
        if (isPreviewRequest()) {
            openPreviewConsole();
            return;
        }

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
                    + getString(R.string.h_1533a43c);
        }

        try {
            org.json.JSONObject layout = new org.json.JSONObject(layoutJson);
            if ("chat".equals(mode)) {
                injectToChat(layout, title);
            } else if ("play".equals(mode)) {
                // 调试用直通播放：不经聊天流，直接把地址交给播放器单例。
                // 用于 HLS 连续取流/直播窗口这类"要连续观察几十秒"的回归，省得每次造卡片。
                playDirect(getIntent().getStringExtra("url"), title);
            } else if ("card".equals(mode)) {
                // 内置组件卡片测试：card_type=内置类型名，props=JSON 字符串 → ui_component create
                // （createBuiltinChatComponent → 聊天流卡片渲染）。延迟到 AIChatActivity resume 后执行。
                String cardType = getIntent().getStringExtra("card_type");
                String propsJson = getIntent().getStringExtra("props");
                // 支持 base64 props（shell 传 JSON 转义易错）：props_b64 优先生效
                String propsB64 = getIntent().getStringExtra("props_b64");
                if ((propsJson == null || propsJson.isEmpty()) && propsB64 != null && !propsB64.isEmpty()) {
                    try {
                        propsJson = new String(android.util.Base64.decode(propsB64, android.util.Base64.DEFAULT), "UTF-8");
                    } catch (Exception e) {
                        Log.w(TAG, "props base64 解码失败: " + e.getMessage());
                    }
                }
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
            tv.setText(getString(R.string.h_0de5227a) + e.getMessage());
            err.addView(tv);
            setContentView(err);
        }
    }

    /** 无注入参数 / mode=preview 时进入可视化 UI 预览台；带 layout/card/url 等参数时返回 false。 */
    private boolean isPreviewRequest() {
        android.content.Intent intent = getIntent();
        String mode = intent.getStringExtra("mode");
        if ("preview".equals(mode)) return true;
        if (mode != null && !mode.isEmpty()) return false;
        return !intent.hasExtra("layout") && !intent.hasExtra("layout_b64")
                && !intent.hasExtra("card_type") && !intent.hasExtra("props")
                && !intent.hasExtra("props_b64") && !intent.hasExtra("url");
    }

    /** 转发 sample/json/json_b64（旧 layout 参数兼容成 json），进入预览台后结束本页。 */
    private void openPreviewConsole() {
        android.content.Intent source = getIntent();
        android.content.Intent preview = new android.content.Intent(
                this, UiPreviewConsoleActivity.class);
        preview.putExtra("sample", source.getStringExtra("sample"));
        String json = source.getStringExtra("json");
        if (json == null || json.isEmpty()) {
            json = source.getStringExtra("layout");
        }
        if (json != null && !json.isEmpty()) preview.putExtra("json", json);
        preview.putExtra("json_b64", source.getStringExtra("json_b64"));
        startActivity(preview);
        finish();
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
    @SuppressWarnings("deprecation") // 弹窗窗口仍靠 ADJUST_RESIZE 跟输入法收缩，暂无等价替代
    private void showDialog(org.json.JSONObject layout, String title) {
        android.view.View view = com.oilquiz.app.ai.python.NativeLayoutRenderer.render(
                this, layout.toString(), null, new java.util.HashMap<>());
        com.oilquiz.app.ui.modal.AppDialog.Builder b = com.oilquiz.app.ui.modal.AppDialog.builder(this);
        if (title != null && !title.isEmpty()) b.setTitle(title);
        b.setView(view);
        b.setPositiveButton(getString(R.string.h_b15d9127), (d, w) -> d.dismiss());
        b.setCancelable(true);
        androidx.appcompat.app.AlertDialog dialog = b.create();
        // 输入法配置：ADJUST_RESIZE + show 后注入 EditText 触摸弹键盘
        dialog.getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.setOnShowListener(d -> {
            dialog.getWindow().getDecorView().postDelayed(() -> {
                injectIme(dialog.getWindow().getDecorView(), 0);
            }, 250);
        });
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

    /**
     * 调试直通播放：把 url 直接交给 {@code AudioPlaybackManager}，并每 5s 打一行状态。
     * 用于"要连续观察几十秒"的回归（HLS 连续取流、直播窗口过期自愈），不必每次造卡片。
     * 只在 debug 构建生效。
     */
    private void playDirect(String url, String title) {
        finish();
        if (url == null || url.isEmpty()) {
            Log.w(TAG, "play: 缺少 url");
            return;
        }
        // 只在 debug 包生效（release 没有 BuildConfig，用 FLAG_DEBUGGABLE 判）
        boolean debuggable = (getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (!debuggable) {
            Log.w(TAG, "play: 仅 debug 构建可用");
            return;
        }
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final android.content.Context app = getApplicationContext();
        h.postDelayed(() -> {
            try {
                final com.oilquiz.app.ai.chat.media.AudioPlaybackManager mgr =
                        com.oilquiz.app.ai.chat.media.AudioPlaybackManager.get(app);
                mgr.playOrToggle(url, title == null ? "调试播放" : title, "");
                Log.i(TAG, "play: " + url);
                for (int i = 0; i < 24; i++) {
                    final int tick = i;
                    h.postDelayed(() -> {
                        try {
                            com.oilquiz.app.ai.chat.media.AudioPlaybackManager.State s = mgr.snapshot();
                            Log.i(TAG, "play-tick " + tick + " playing=" + s.playing
                                    + " pos=" + s.positionMs + " dur=" + s.durationMs
                                    + " err=" + (s.error == null ? "null" : s.error));
                        } catch (Throwable ignored) {
                        }
                    }, 5000L * (i + 1));
                }
            } catch (Throwable t) {
                Log.e(TAG, "play 失败", t);
            }
        }, 600);
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

package com.oilquiz.app.ai.tool;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.ai.chat.component.CanvasControls;
import com.oilquiz.app.ai.chat.component.CanvasTemplates;
import com.oilquiz.app.ai.python.CanvasV2Renderer;
import com.oilquiz.app.theme.ThemeColors;
import com.oilquiz.app.theme.Tokens;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 独立的新版动态画布工具（2026-11-18，工具名 {@code ui_canvas}）。
 *
 * <p><b>为什么单独开一个工具</b>：现有 {@code ui_component} 的 layout_canvas 是"平台控件 + 手绘背景"
 * 的旧实现，直接改它会牵动聊天流卡片与旧画布行为。这里做成完全独立的一条链路：</p>
 * <ul>
 *   <li>渲染器：{@link CanvasV2Renderer}（{@code CanvasControls} 产出 Material 组件）；</li>
 *   <li>外壳：自带浮动卡 Dialog（24dp 圆角 / 无壁纸底 / 内容自适应高度），不经过
 *       {@code ui_component} 的会话与弹窗；</li>
 *   <li>取值：复用 {@link CanvasV2Renderer#collectValues} 的同口径；按钮点击即回传并关闭。</li>
 * </ul>
 *
 * <p>与旧工具零共享状态：会话表在本类内，组件 id 前缀 {@code cv2_}，不注册到
 * {@code LayoutCanvasManager}，因此 {@code layout_editor} 也碰不到它。</p>
 */
@Tool(
        value = "ui_canvas",
        description = "新版动态画布（独立于 ui_component）。用 JSON 布局树声明一张浮动画布："
                + "控件走 Material 框架（按钮/输入框/开关/下拉/滑块/日期/标签），外壳是 24dp 圆角浮动卡、"
                + "不使用壁纸背景、高度默认随内容自适应（72% 屏高封顶）。"
                + "**先用 action=screen_info 拿推荐尺寸**，再 action=create：layout=控件树、"
                + "template=form|settings|dashboard、component_type=任意类型（内置卡片/已注册动态类型，props 传参）、"
                + "use=layout模板名（props 占位）四种入口都收；"
                + "用户点提交按钮后 action=get_result 取回全部带 key 控件的值。"
                + "与 ui_component 的 layout_canvas 完全隔离：同一个 component_id 不互通，不会互相覆盖。",
        category = "system",
        params = {
                @Param(name = "action", type = "string", description = "操作：screen_info/create/get_result/close/list", required = true),
                @Param(name = "title", type = "string", description = "画布标题（浮动卡头部）", required = false),
                @Param(name = "layout", type = "object", description = "控件树 JSON：{\"root\":{\"type\":\"column\",\"children\":[...]}}；控件 type 与 ui_component 的 layout 一致（input/select/switch/checkbox_group/radio_group/slider/date/time/tag_input/toggle/stepper/card/button 等）", required = false),
                @Param(name = "template", type = "string", description = "内置模板：form（表单）/settings（设置）/dashboard（概览）；未传 layout 时生效", required = false),
                @Param(name = "component_type", type = "string", description = "直接作为画布根节点的类型（内置卡片/控件/已注册动态类型），与 ui_component 的 component_type 同口径；props 作为该节点参数", required = false),
                @Param(name = "use", type = "string", description = "layout 模板名（ui_component_plugin 注册的模板），props 作为模板占位参数", required = false),
                @Param(name = "props", type = "object", description = "配合 component_type / use 使用的节点/模板参数", required = false),
                @Param(name = "canvas_width", type = "string", description = "窗口宽：dp 数字 / 0.8 / 80% / full；默认屏宽-32dp", required = false),
                @Param(name = "canvas_height", type = "string", description = "内容高：dp 数字 / 比例 / auto；默认 auto（随内容，72% 屏高封顶）", required = false),
                @Param(name = "canvas_max_height", type = "string", description = "内容高上限：dp 数字 / 比例", required = false),
                @Param(name = "component_id", type = "string", description = "create 返回的画布 id（get_result/close 用）", required = false),
                @Param(name = "wait_seconds", type = "int", description = "get_result 等待秒数（默认 30）", required = false)
        },
        actions = {
                @Action(name = "screen_info", description = "获取屏幕尺寸与可用区域（px/dp/密度/系统栏 inset/推荐画布宽高），create 前先调它"),
                @Action(name = "create", description = "创建画布：layout=控件树 JSON（或 template=form|settings|dashboard 用内置模板）+ title；"
                        + "可选 canvas_width/canvas_height/canvas_max_height（dp 数字 / 0.8 / 80% / auto / full）。返回 component_id"),
                @Action(name = "get_result", description = "取用户提交结果（阻塞等待 wait_seconds，默认 30；超时返回 pending）。"
                        + "返回 {result, values, component_id}，values 是全部带 key 控件的当前值"),
                @Action(name = "close", description = "关闭画布（不取结果）"),
                @Action(name = "list", description = "列出当前存活的 v2 画布")
        }
)
public class UICanvasTool implements AITool {

    private static final String TAG = "UICanvasTool";

    /** 会话表：component_id -> 会话（独立于 ui_component 的 LayoutCanvasManager）。 */
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final AtomicInteger SEQ = new AtomicInteger();

    private final Context context;

    public UICanvasTool(Context context) {
        this.context = context;
    }

    private static final class Session {
        final String id;
        final AtomicReference<String> result = new AtomicReference<>("pending");
        final CountDownLatch done = new CountDownLatch(1);
        volatile android.app.Dialog dialog;
        volatile Map<String, Object> refs = new HashMap<>();

        Session(String id) {
            this.id = id;
        }
    }

    @Override
    public String getName() {
        return "ui_canvas";
    }

    @Override
    public String getDescription() {
        return "新版动态画布（独立于 ui_component）：Material 控件 + 浮动卡外壳 + 自适应高度。"
                + "screen_info → create(layout/template) → get_result。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> d = new HashMap<>();
        d.put("action", "screen_info / create / get_result / close / list");
        d.put("title", "画布标题（浮动卡头部）");
        d.put("layout", "控件树 JSON，如 {\"root\":{\"type\":\"column\",\"children\":[...]}}");
        d.put("template", "内置模板：form / settings / dashboard（未传 layout 时生效）");
        d.put("component_type", "直接把某类型作为画布根节点（chart/info_card/已注册动态类型等），props 传参");
        d.put("use", "layout 模板名，props 作为模板占位参数");
        d.put("props", "配合 component_type / use 的节点参数");
        d.put("canvas_width", "窗口宽：dp 数字 / 0.8 / 80% / full（默认屏宽-32dp）");
        d.put("canvas_height", "内容高：dp 数字 / 比例 / auto（默认 auto，随内容，72% 屏高封顶）");
        d.put("canvas_max_height", "内容高上限：dp 数字 / 比例");
        d.put("component_id", "create 返回的画布 id（get_result/close 用）");
        d.put("wait_seconds", "get_result 等待秒数（默认 30）");
        return d;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        String action = str(parameters.get("action"));
        if (action == null || action.isEmpty()) {
            return AIToolResult.fail("缺少参数: action (screen_info/create/get_result/close/list)");
        }
        switch (action) {
            case "screen_info":
                return screenInfo();
            case "create":
                return create(parameters);
            case "get_result":
                return getResult(parameters);
            case "close":
                return close(parameters);
            case "list":
                return listSessions();
            default:
                return AIToolResult.fail("未知 action: " + action
                        + "（可用 screen_info/create/get_result/close/list）");
        }
    }

    // ==================== screen_info ====================

    private AIToolResult screenInfo() {
        try {
            android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
            float density = dm.density > 0 ? dm.density : 1f;
            int widthPx = dm.widthPixels;
            int heightPx = dm.heightPixels;
            int insetT = 0;
            int insetB = 0;
            try {
                android.view.WindowManager wm = (android.view.WindowManager)
                        context.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null && android.os.Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets insets = wm.getCurrentWindowMetrics().getWindowInsets()
                            .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars()
                                    | android.view.WindowInsets.Type.displayCutout());
                    insetT = insets.top;
                    insetB = insets.bottom;
                }
            } catch (Throwable ignored) {
            }
            int usableWDp = Math.round(widthPx / density);
            int usableHDp = Math.round((heightPx - insetT - insetB) / density);
            JSONObject out = new JSONObject();
            out.put("width_dp", Math.round(widthPx / density));
            out.put("height_dp", Math.round(heightPx / density));
            out.put("usable_width_dp", usableWDp);
            out.put("usable_height_dp", usableHDp);
            out.put("density", density);
            out.put("recommended_canvas_width_dp", Math.max(240, usableWDp - 32));
            out.put("recommended_canvas_height_dp", Math.max(320, Math.round(usableHDp * 0.65f)));
            out.put("recommended_canvas_max_height_dp", Math.max(320, usableHDp - 32));
            return AIToolResult.success(out.toString());
        } catch (Throwable t) {
            return AIToolResult.fail("获取屏幕信息失败: " + t.getMessage());
        }
    }

    // ==================== create ====================

    private AIToolResult create(Map<String, Object> parameters) {
        // 参数校验先于环境检查：模型参数写错时应直接拿到"参数错"，而不是"没有 Activity"
        Object layoutRaw = parameters.get("layout");
        String template = str(parameters.get("template"));
        String componentType = str(parameters.get("component_type"));
        String use = str(parameters.get("use"));
        // 与旧工具的入口对齐：layout / template / component_type / use 四种都收，
        // 这样任意布局控件、内置卡片、已注册动态类型、layout 模板都能直接开在 v2 画布上。
        if (layoutRaw == null && isEmpty(template) && isEmpty(componentType) && isEmpty(use)) {
            return AIToolResult.fail(
                    "缺少 layout（控件树 JSON）/ template / component_type / use 之一");
        }
        Map<String, Object> p = new HashMap<>(parameters);
        if (layoutRaw == null && !isEmpty(template)) {
            String tpl = CanvasTemplates.apply(p);
            if (tpl == null) {
                return AIToolResult.fail("未知模板: " + template + "（可用 form/settings/dashboard）");
            }
            layoutRaw = p.get("layout");
        }
        if (layoutRaw == null && !isEmpty(use)) {
            JSONObject root = new JSONObject();
            try {
                root.put("use", use);
                Object propsObj = parameters.get("props");
                if (propsObj != null) {
                    root.put("props", new JSONObject(
                            new com.google.gson.Gson().toJson(propsObj)));
                }
                JSONObject wrap = new JSONObject();
                wrap.put("root", root);
                layoutRaw = wrap.toString();
            } catch (Exception e) {
                return AIToolResult.fail("use 模板参数转换失败: " + e.getMessage());
            }
        }
        if (layoutRaw == null && !isEmpty(componentType)) {
            // 与 ui_component 一致：component_type 也可以直接作为画布根节点（内置卡片/已注册类型）
            JSONObject root = new JSONObject();
            try {
                root.put("type", componentType);
                Object propsObj = parameters.get("props");
                if (propsObj != null) {
                    root.put("props", new JSONObject(
                            new com.google.gson.Gson().toJson(propsObj)));
                }
                Object titleObj = parameters.get("card_title");
                if (titleObj != null) {
                    root.put("title", String.valueOf(titleObj));
                }
                JSONObject wrap = new JSONObject();
                wrap.put("root", root);
                layoutRaw = wrap.toString();
            } catch (Exception e) {
                return AIToolResult.fail("component_type 参数转换失败: " + e.getMessage());
            }
        }
        final Activity act = findActivity(context);
        if (act == null) {
            return AIToolResult.fail("当前没有可用的 Activity，无法显示画布（请在聊天/设置页调用）");
        }
        final String layoutText = layoutRaw instanceof String ? (String) layoutRaw
                : new com.google.gson.Gson().toJson(layoutRaw);
        final String title = firstNonEmpty(str(parameters.get("title")), "画布");
        final com.oilquiz.app.ai.chat.component.LayoutCanvasConstraints.CanvasSize size =
                com.oilquiz.app.ai.chat.component.LayoutCanvasConstraints
                        .resolveCanvasSize(toJson(parameters), act);
        final Session session = new Session("cv2_" + System.currentTimeMillis() + "_" + SEQ.incrementAndGet());

        final CountDownLatch shown = new CountDownLatch(1);
        final AtomicReference<String> error = new AtomicReference<>();
        act.runOnUiThread(() -> {
            try {
                showCanvas(act, session, title, layoutText, toJson(parameters), size);
            } catch (Throwable t) {
                error.set(t.getClass().getSimpleName() + ": " + t.getMessage());
                session.result.set("error");
                session.done.countDown();
            } finally {
                shown.countDown();
            }
        });
        try {
            shown.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (error.get() != null) {
            return AIToolResult.fail("画布打开失败: " + error.get());
        }
        SESSIONS.put(session.id, session);
        JSONObject out = new JSONObject();
        try {
            out.put("component_id", session.id);
            out.put("status", "shown");
            out.put("message", "画布已打开；用户提交后调用 get_result(wait_seconds) 取值");
        } catch (Exception ignored) {
        }
        return AIToolResult.success(out.toString());
    }

    /** 在主线程构建并显示浮动画布（结构同 ui_component 的新版外壳，但完全独立）。 */
    private void showCanvas(Activity act, Session session, String title,
                            String layoutText, JSONObject props,
                            com.oilquiz.app.ai.chat.component.LayoutCanvasConstraints.CanvasSize size) {
        Map<String, Object> refs = new HashMap<>();
        View content = CanvasV2Renderer.render(act, layoutText, props, refs);
        session.refs = refs;
        ScrollView scroll = new ScrollView(act);
        scroll.setFillViewport(false);
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        float density = act.getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * density);

        com.google.android.material.card.MaterialCardView card =
                new com.google.android.material.card.MaterialCardView(act);
        card.setRadius(Math.round(24 * density));
        card.setCardElevation(Math.round(4 * density));
        card.setUseCompatPadding(false);
        int bg = ThemeColors.attr(act, R.attr.colorSurfaceContainerLow);
        card.setCardBackgroundColor(bg != 0 ? bg : ThemeColors.get(act, R.color.surface));
        int stroke = ThemeColors.attr(act, R.attr.colorOutlineVariant);
        card.setStrokeWidth(1);
        card.setStrokeColor(stroke != 0 ? stroke : ThemeColors.get(act, R.color.border));

        int textColor = ThemeColors.attr(act, R.attr.colorControlText);
        if (textColor == 0) {
            textColor = ThemeColors.get(act, R.color.text_primary);
        }

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = new LinearLayout(act);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(pad, Math.round(10 * density), Math.round(6 * density), 0);
        TextView titleTv = new TextView(act);
        titleTv.setText(title);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, Tokens.textSizeSp(act, Tokens.TEXT_SUBTITLE));
        titleTv.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.BOLD));
        titleTv.setTextColor(textColor);
        titleTv.setMaxLines(1);
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(titleTv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(act);
        close.setText("✕");
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("关闭画布");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        close.setTextColor(textColor);
        android.util.TypedValue ripple = new android.util.TypedValue();
        act.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        if (ripple.resourceId != 0) {
            close.setBackgroundResource(ripple.resourceId);
        }
        int touch = Math.round(44 * density);
        close.setLayoutParams(new LinearLayout.LayoutParams(touch, touch));
        header.addView(close);
        root.addView(header);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(root, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));

        final android.app.Dialog dialog = new android.app.Dialog(act);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(card);
        dialog.setCancelable(true);

        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
                    android.graphics.Color.TRANSPARENT));
            window.setGravity(Gravity.CENTER);
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            int availW = Math.max(Math.round(240 * density),
                    act.getResources().getDisplayMetrics().widthPixels - pad * 2);
            window.setLayout(availW, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        // 画布底：不透明主题底（不用壁纸），点外部关闭
        View backdrop = buildBackdrop(act, dialog);
        close.setOnClickListener(v -> dialog.dismiss());

        // 高度：默认 auto（WRAP + 72% 屏高封顶）；显式数字/比例则固定
        int maxH = size != null && size.maxHeightDp > 0 ? (int) (size.maxHeightDp * density)
                : Math.round(act.getResources().getDisplayMetrics().heightPixels * 0.72f);
        ViewGroup.LayoutParams scrollLp = scroll.getLayoutParams();
        boolean wrap = size == null || size.wrapHeight || size.heightDp <= 0;
        if (!wrap) {
            scrollLp.height = Math.min((int) (size.heightDp * density), maxH);
            scroll.setLayoutParams(scrollLp);
        } else {
            scrollLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            scroll.setLayoutParams(scrollLp);
            scroll.post(() -> {
                if (scroll.getMeasuredHeight() > maxH) {
                    ViewGroup.LayoutParams lp2 = scroll.getLayoutParams();
                    lp2.height = maxH;
                    scroll.setLayoutParams(lp2);
                }
            });
        }

        // 按钮：点击收集全部带 key 的值并回传
        List<Button> buttons = new ArrayList<>();
        collectButtons(scroll, buttons);
        for (final Button b : buttons) {
            final String action = b.getTag() == null ? "" : String.valueOf(b.getTag());
            b.setOnClickListener(v -> {
                if (!"pending".equals(session.result.get())) {
                    return;
                }
                try {
                    JSONObject r = new JSONObject();
                    r.put("action", action.isEmpty() ? "click" : action);
                    r.put("values", new JSONObject(
                            new com.google.gson.Gson().toJson(
                                    CanvasV2Renderer.collectValues(session.refs))));
                    session.result.set(r.toString());
                } catch (Throwable t) {
                    session.result.set("{\"action\":\"click\",\"error\":\""
                            + String.valueOf(t.getMessage()).replace("\"", "'") + "\"}");
                }
                session.done.countDown();
                dialog.dismiss();
            });
        }

        dialog.setOnDismissListener(d -> {
            removeBackdrop(backdrop);
            if ("pending".equals(session.result.get())) {
                session.result.set("closed");
            }
            session.done.countDown();
            SESSIONS.remove(session.id);
            try {
                com.oilquiz.app.theme.EdgeToEdgeHelper.applySystemBarIcons(act);
            } catch (Throwable ignored) {
            }
        });
        session.dialog = dialog;
        dialog.show();
        // 打开即定位到顶部：控件 attach 后可能自动聚焦并滚动到中部
        scroll.post(() -> scroll.scrollTo(0, 0));
        scroll.postDelayed(() -> {
            try {
                scroll.scrollTo(0, 0);
                scroll.clearFocus();
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager)
                                act.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null && window != null) {
                    imm.hideSoftInputFromWindow(window.getDecorView().getWindowToken(), 0);
                }
            } catch (Throwable ignored) {
            }
        }, 150);
    }

    private View buildBackdrop(Activity act, android.app.Dialog dialog) {
        try {
            ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
            View backdrop = new View(act);
            int bg = ThemeColors.attr(act, R.attr.colorBackground);
            if (bg == 0) {
                bg = ThemeColors.get(act, R.color.background);
            }
            backdrop.setBackgroundColor(bg);
            backdrop.setClickable(true);
            backdrop.setOnClickListener(v -> dialog.dismiss());
            decor.addView(backdrop, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
            com.oilquiz.app.theme.EdgeToEdgeHelper.syncStatusBarIcons(act, backdrop);
            return backdrop;
        } catch (Throwable t) {
            return null;
        }
    }

    private void removeBackdrop(View backdrop) {
        try {
            if (backdrop != null && backdrop.getParent() instanceof ViewGroup) {
                ((ViewGroup) backdrop.getParent()).removeView(backdrop);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void collectButtons(View root, List<Button> out) {
        if (root == null) {
            return;
        }
        if (root instanceof android.widget.CompoundButton) {
            return;
        }
        if (CanvasControls.isOwned(root)) {
            return;
        }
        if (root instanceof Button) {
            out.add((Button) root);
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectButtons(g.getChildAt(i), out);
            }
        }
    }

    // ==================== get_result / close / list ====================

    private AIToolResult getResult(Map<String, Object> parameters) {
        String id = str(parameters.get("component_id"));
        Session s = id == null ? null : SESSIONS.get(id);
        if (s == null) {
            return AIToolResult.fail("画布不存在或已关闭: " + id);
        }
        int wait = intOf(parameters.get("wait_seconds"), 30);
        try {
            s.done.await(Math.max(1, wait), TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        String result = s.result.get();
        JSONObject out = new JSONObject();
        try {
            out.put("component_id", s.id);
            out.put("status", "pending".equals(result) ? "pending" : "done");
            out.put("result", result);
            out.put("values", new JSONObject(
                    new com.google.gson.Gson().toJson(CanvasV2Renderer.collectValues(s.refs))));
        } catch (Exception ignored) {
        }
        return AIToolResult.success(out.toString());
    }

    private AIToolResult close(Map<String, Object> parameters) {
        String id = str(parameters.get("component_id"));
        Session s = id == null ? null : SESSIONS.remove(id);
        if (s == null) {
            return AIToolResult.fail("画布不存在或已关闭: " + id);
        }
        if (s.result.compareAndSet("pending", "closed")) {
            s.done.countDown();
        }
        try {
            if (s.dialog != null) {
                Activity act = findActivity(context);
                if (act != null) {
                    act.runOnUiThread(() -> s.dialog.dismiss());
                }
            }
        } catch (Throwable ignored) {
        }
        return AIToolResult.success("{\"component_id\":\"" + id + "\",\"status\":\"closed\"}");
    }

    private AIToolResult listSessions() {
        return AIToolResult.success(new org.json.JSONArray(SESSIONS.keySet()).toString());
    }

    // ==================== 工具 ====================

    private static Activity findActivity(Context ctx) {
        Context c = ctx;
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) {
                return (Activity) c;
            }
            c = ((ContextWrapper) c).getBaseContext();
        }
        // AIToolManager 持有的是 Application context，工具拿不到宿主 Activity；
        // 与 ui_component 同口径走全局前台 Activity。
        try {
            return com.oilquiz.app.SmartQuizApplication.getCurrentActivity();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JSONObject toJson(Map<String, Object> params) {
        try {
            return new JSONObject(new com.google.gson.Gson().toJson(params));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstNonEmpty(String a, String b) {
        return a == null || a.isEmpty() ? b : a;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static int intOf(Object o, int def) {
        try {
            if (o instanceof Number) {
                return ((Number) o).intValue();
            }
            if (o != null) {
                return Integer.parseInt(String.valueOf(o).trim());
            }
        } catch (Exception ignored) {
        }
        return def;
    }
}

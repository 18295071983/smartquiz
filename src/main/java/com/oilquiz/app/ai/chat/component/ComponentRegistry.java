package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话 UI 组件注册表（单例）。
 *
 * 插件式渲染核心：组件按类型注册，渲染时按 type 匹配对应组件插件。
 * 新增组件 = 实现 {@link ChatComponent} + {@link #register(ChatComponent)}。
 */
public class ComponentRegistry {

    private static final String TAG = "ComponentRegistry";

    private static volatile ComponentRegistry instance;

    /** 已注册组件（type → 组件） */
    private final Map<String, ChatComponent> components = new ConcurrentHashMap<>();

    /** 动态组件兜底渲染器：Agent/工具动态创建的自定义类型未注册时也用通用卡片展示 */
    private final ChatComponent dynamicFallback = new DynamicCardView();

    private ComponentRegistry() {
        registerBuiltin();
    }

    public static ComponentRegistry getInstance() {
        if (instance == null) {
            synchronized (ComponentRegistry.class) {
                if (instance == null) {
                    instance = new ComponentRegistry();
                }
            }
        }
        return instance;
    }

    /** 注册内置组件（插件式扩展点：外部也可继续 register） */
    private void registerBuiltin() {
        // 通用展示
        register(new ChartCardView());
        register(new InfoCardView());
        register(new TableCardView());
        register(new ImageGridCardView());
        register(new LinkCardView());
        // Agent 常用
        register(new ListCardView());
        register(new AlertCardView());
        register(new MetricCardView());
        register(new JsonViewerCard());
        register(new StepsCardView());
        register(new NoteCardView());
        register(new FileListCardView());
        register(new GridCardView());
        register(new ContactCardView());
        register(new TodoCardView());
        // 业务场景
        register(new QuizCardView());
        register(new WeatherCardView());
        register(new FileCardView());
        register(new CodeCardView());
        register(new ProgressCardView());
        // HTML 富内容（Agent/Python 生成，WebView 渲染，支持 CSS/简单 JS）
        register(new HtmlCardView());
        // Markdown 富文本（内容框架文本承载组件，渲染加粗/列表/链接/代码块等）
        register(new MarkdownCardView());
        // 媒体播放（原生播放器：视频/音频）
        register(new VideoCardView());
        register(new AudioCardView());
        // Agent 执行过程（插入式显示在 AI 消息内）
        register(new ToolCallCardView());
        // Layout 原语桥接：把 NativeLayoutRenderer 的单控件渲染暴露为聊天流卡片
        // （table/steps/timeline/alert/stat/qrcode/barcode/calendar/图表/stepper 等，
        //  props 直接作为单控件节点渲染，Agent 可用 component_type=xxx 直接生成）
        for (String lt : LayoutPrimitiveCardView.BRIDGED_TYPES) {
            register(new LayoutPrimitiveCardView(lt));
        }
        // Layout 树卡片：ui_component 传 layout 控件树时渲染为聊天流卡片
        register(new LayoutCardView());
    }

    /**
     * 注册组件插件。
     *
     * @param component 组件插件，getType() 返回唯一类型标识
     */
    public void register(ChatComponent component) {
        if (component == null || component.getType() == null) return;
        components.put(component.getType(), component);
        Log.i(TAG, "registered component: " + component.getType());
    }

    /** 是否注册了指定类型组件 */
    public boolean hasType(String type) {
        return type != null && components.containsKey(type);
    }

    /**
     * 按类型渲染组件 View。
     * 渲染链路容错：
     * 1. 类型未注册 → 通用兜底组件（DynamicCardView）
     * 2. 已注册但数据不匹配（canRender=false）→ 同样尝试通用兜底组件
     * 3. 渲染抛异常 → 返回 null（调用方显示降级占位）
     * 保证 Agent 输出的组件尽可能可见可用，而不是"渲染失败"。
     *
     * 统一交互支持：组件 props 带 actions 时，在组件底部统一附加动作按钮行
     * （callback/link/copy），所有组件类型（info_card/table_card/chart 等）均可交互，
     * 结果经 ComponentActions 回调 → 组件注册表 → Agent get_result 取回。
     *
     * @param context Android Context
     * @param data    组件数据
     * @return 组件 View；数据非法或渲染异常时返回 null（调用方自行降级）
     */
    public View render(Context context, ComponentData data) {
        if (data == null || data.type == null) return null;
        ChatComponent component = components.get(data.type);
        if (component == null) {
            Log.i(TAG, "unregistered type, using dynamic fallback: " + data.type);
            component = dynamicFallback;
        }
        View view = null;
        ChatComponent renderedBy = component;
        try {
            if (component.canRender(data)) {
                view = component.createView(context, data);
            } else if (component != dynamicFallback && dynamicFallback.canRender(data)) {
                Log.w(TAG, "registered type data mismatch, using dynamic fallback: " + data.type);
                view = dynamicFallback.createView(context, data);
                renderedBy = dynamicFallback;
            }
        } catch (Exception e) {
            Log.e(TAG, "component render failed: " + data.type, e);
        }
        if (view == null) return null;
        // UI-06 统一 style 对象（容器级）：padding/radius/background/border 应用到组件根 View。
        // 覆盖全部渲染路径（聊天流卡片/弹窗/插件卡片）——此前 style 只对 layout 树节点生效，
        // 内置卡片(info_card/chart/table_card等)传 style 会被忽略（"样式都没生效"根因）。
        if (data.props != null && data.props.has("style")) {
            JSONObject styleObj = data.props.optJSONObject("style");
            if (styleObj != null) {
                applyViewStyle(context, view, styleObj);
            }
        }
        // 统一附加 actions 按钮行；以下情况跳过避免重复：
        // 1. 由 DynamicCardView 渲染（其内部已渲染 actions）
        // 2. 组件自带 actions 渲染（alert_card/contact_card 内部已渲染）
        boolean actionsAlreadyRendered = renderedBy == dynamicFallback || selfRendersActions(data.type);
        if (!actionsAlreadyRendered
                && data.props != null && data.props.optJSONArray("actions") != null
                && data.props.optJSONArray("actions").length() > 0) {
            try {
                android.widget.LinearLayout wrapper = new android.widget.LinearLayout(context);
                wrapper.setOrientation(android.widget.LinearLayout.VERTICAL);
                wrapper.addView(view, new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
                ComponentActions.renderActions(wrapper, context, data.props.optJSONArray("actions"));
                return wrapper;
            } catch (Throwable t) {
                Log.w(TAG, "attach actions failed, return raw view: " + t.getMessage());
            }
        }
        return view;
    }

    /** 自身已渲染 actions 按钮的类型（避免外层重复附加）：
     *  alert_card/contact_card 内部自带 actions 渲染。 */
    private boolean selfRendersActions(String type) {
        return "alert_card".equals(type) || "contact_card".equals(type);
    }

    /**
     * 渲染失败时的兜底占位（显示组件类型，便于排查）。
     */
    public View renderFallback(Context context, ComponentData data) {
        TextView tv = new TextView(context);
        tv.setText("⚠ 组件渲染失败: " + (data != null ? data.type : "null"));
        tv.setTextSize(12);
        return tv;
    }

    // ---------- UI-06 统一 style 对象（容器级） ----------
    // 与 NativeLayoutRenderer.applyContainerStyle 同口径：
    // padding=整数 或 "t r b l" 或 [4个数]；radius=圆角dp；background=颜色；border=宽度+颜色 或 border_width+border_color

    private static void applyViewStyle(Context context, View v, JSONObject style) {
        if (v == null || style == null) return;
        float density = context.getResources().getDisplayMetrics().density;
        // padding
        try {
            if (style.has("padding")) {
                Object pad = style.opt("padding");
                if (pad instanceof Number) {
                    int p = dp(((Number) pad).intValue(), density);
                    v.setPadding(p, p, p, p);
                } else if (pad instanceof JSONArray) {
                    JSONArray pa = (JSONArray) pad;
                    if (pa.length() == 1) {
                        int p = dp(pa.optInt(0), density);
                        v.setPadding(p, p, p, p);
                    } else if (pa.length() == 2) {
                        int ph = dp(pa.optInt(0), density), pv2 = dp(pa.optInt(1), density);
                        v.setPadding(ph, pv2, ph, pv2);
                    } else if (pa.length() >= 4) {
                        v.setPadding(dp(pa.optInt(0), density), dp(pa.optInt(1), density),
                                dp(pa.optInt(2), density), dp(pa.optInt(3), density));
                    }
                } else {
                    String ps = String.valueOf(pad).trim();
                    String[] parts = ps.split("\\s+");
                    if (parts.length == 1) {
                        int p = dp(parseIntSafe(parts[0]), density);
                        v.setPadding(p, p, p, p);
                    } else if (parts.length == 4) {
                        v.setPadding(dp(parseIntSafe(parts[0]), density), dp(parseIntSafe(parts[1]), density),
                                dp(parseIntSafe(parts[2]), density), dp(parseIntSafe(parts[3]), density));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // radius + background + border（有其一才设置，避免覆盖控件已有背景）
        try {
            boolean hasRadius = style.has("radius");
            boolean hasBg = style.has("background");
            boolean hasBorder = style.has("border") || style.has("border_width");
            if (hasRadius || hasBg || hasBorder) {
                int radiusDp = hasRadius ? dp(style.optInt("radius"), density) : 0;
                int bgColor = 0;
                boolean bgOk = false;
                if (hasBg) {
                    int c = parseColorSafe(style.optString("background", ""));
                    if (c != Integer.MIN_VALUE) { bgColor = c; bgOk = true; }
                }
                int borderWidth = 0;
                int borderColor = 0xFFFFFFFF;
                boolean borderOk = false;
                if (style.has("border")) {
                    String bs = style.optString("border", "").trim();
                    if (bs.matches("\\d+(\\s+#?[0-9A-Fa-f]{6,8})?")) {
                        String[] bp = bs.split("\\s+");
                        borderWidth = parseIntSafe(bp[0]);
                        if (bp.length > 1) {
                            int c = parseColorSafe(bp[1]);
                            if (c != Integer.MIN_VALUE) { borderColor = c; borderOk = true; }
                        }
                    }
                }
                if (style.has("border_width")) {
                    borderWidth = parseIntSafe(style.optString("border_width", "0"));
                    int c = parseColorSafe(style.optString("border_color", ""));
                    if (c != Integer.MIN_VALUE) { borderColor = c; borderOk = true; }
                }
                if (borderWidth > 0) borderOk = true;
                // 仅 radius（无 background/border）且控件已有背景 → 跳过，保留原背景
                if (hasRadius && !bgOk && !borderOk && v.getBackground() != null) return;
                if (hasRadius || bgOk || borderOk) {
                    android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                    if (bgOk) gd.setColor(bgColor);
                    if (hasRadius) gd.setCornerRadius(radiusDp);
                    if (borderOk && borderWidth > 0) gd.setStroke(dp(borderWidth, density), borderColor);
                    v.setBackground(gd);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static int dp(int v, float density) {
        return (int) (v * density + 0.5f);
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static int parseColorSafe(String s) {
        try {
            String cs = s.trim();
            if (cs.isEmpty()) return Integer.MIN_VALUE;
            if (cs.startsWith("#")) {
                long l = Long.parseLong(cs.substring(1), 16);
                if (cs.length() == 7) l |= 0xFF000000L;
                return (int) l;
            }
            return android.graphics.Color.parseColor(cs);
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }
}

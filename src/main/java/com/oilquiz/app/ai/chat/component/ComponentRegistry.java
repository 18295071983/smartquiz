package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.oilquiz.app.R;

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
        // note_card / grid_card 的原生实现已删（2026-10-20）：ComponentTypes 的 alias 把
        // note_card→info、grid_card→list，而 render() 先按 canonical 解析（见本类 :158），
        // 所以这两个键从注册那天起就永远不被命中——NoteCardView/GridCardView 是死实现。
        // 语义去处：条目型用 info_card，引用/笔记用 quote 原语（真读 text/content + author），
        // 网格式列表用 list_card。目录里不再推销这两个名字。
        register(new FileListCardView());
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
        // 播放队列 / 日程 / 位置 / 变更对比（2026-10-23）
        register(new PlaylistCardView());
        register(new AgendaCardView());
        register(new LocationCardView());
        register(new DiffCardView());
        // 应用/页面卡片：app_operation 的导航回执 + 再次进入
        register(new AppCardView());
        // Agent 执行过程（插入式显示在 AI 消息内）
        register(new ToolCallCardView());
        register(new FeedbackBarCardView());
        register(new AgentStatusChipCardView());
        // Layout 原语桥接：把 NativeLayoutRenderer 的单控件渲染暴露为聊天流卡片
        // （table/steps/timeline/alert/stat/qrcode/barcode/calendar/图表/stepper 等，
        //  props 直接作为单控件节点渲染，Agent 可用 component_type=xxx 直接生成）
        for (String lt : LayoutPrimitiveCardView.BRIDGED_TYPES) {
            register(new LayoutPrimitiveCardView(lt));
        }
        // Layout 树卡片：ui_component 传 layout 控件树时渲染为聊天流卡片
        register(new LayoutCardView());

        // ---------- canonical 归一（B1-2）----------
        // canonical 名字指向"原生卡片"实现：table/steps/alert/stat… 这些名字此前被
        // Layout 原语占着，模型选它拿到的是另一套长相；现在原生赢下名字，
        // 原语仍可通过 primitive_<名字> 访问（下面同时登记这个逃生口）。
        for (String primitive : LayoutPrimitiveCardView.BRIDGED_TYPES) {
            ChatComponent p = components.get(primitive);
            if (p instanceof LayoutPrimitiveCardView) {
                components.put("primitive_" + primitive, p);
            }
        }
        for (Map.Entry<String, String> e : ComponentTypes.CANONICAL_TARGET.entrySet()) {
            ChatComponent impl = components.get(e.getValue());
            if (impl != null) {
                components.put(e.getKey(), impl);
            }
        }
        // 场景短名同样占位（weather/contact/todo…）：目录教的是短名，实现 getType() 报 *_card，
        // 不补短名 key 的话模型照目录用短名会解析不到实现、掉进 DynamicCardView 原始 JSON 兜底。
        for (Map.Entry<String, String> e : ComponentTypes.SCENE_TARGET.entrySet()) {
            ChatComponent impl = components.get(e.getValue());
            if (impl != null) {
                components.put(e.getKey(), impl);
            }
        }
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

    /** 是否注册了指定类型组件（旧名/别名按 canonical 解析后再判断） */
    public boolean hasType(String type) {
        if (type == null) return false;
        return components.containsKey(type)
                || components.containsKey(ComponentTypes.resolve(type));
    }

    /**
     * 取某类型当前绑定的渲染器（不做兜底）：目录展示、单测与"这名字到底谁来画"的排查用。
     */
    public ChatComponent componentFor(String type) {
        if (type == null) return null;
        ChatComponent byCanonical = components.get(ComponentTypes.resolve(type));
        return byCanonical != null ? byCanonical : components.get(type);
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
        // canonical 优先（table_card/steps_card 等旧名与重复名归一到同一实现），再退回原名
        ChatComponent component = components.get(ComponentTypes.resolve(data.type));
        if (component == null) component = components.get(data.type);
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
        // 统一附加 actions；以下情况跳过避免重复：
        // 1. 由 DynamicCardView 渲染（其内部已渲染 actions）
        // 2. 组件自带 actions 渲染（alert_card/contact_card 内部已渲染）
        JSONArray actions = data.props != null ? data.props.optJSONArray("actions") : null;
        boolean hasActions = actions != null && actions.length() > 0;
        boolean actionsAlreadyRendered = renderedBy == dynamicFallback || selfRendersActions(data.type);
        if (hasActions && !actionsAlreadyRendered) {
            // 已接 CardShell 的组件：动作进卡片 footer（最多 2 个 + 更多），
            // 不再外面包一层 LinearLayout 把按钮"飘"在卡片背景之外
            CardShell shell = findShell(view);
            if (shell != null) {
                try {
                    shell.addActions(actions);
                    return view;
                } catch (Throwable t) {
                    Log.w(TAG, "footer actions failed, fall back to outer row: " + t.getMessage());
                }
            }
            try {
                android.widget.LinearLayout wrapper = new android.widget.LinearLayout(context);
                wrapper.setOrientation(android.widget.LinearLayout.VERTICAL);
                wrapper.addView(view, new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
                ComponentActions.renderActions(wrapper, context, actions);
                return wrapper;
            } catch (Throwable t) {
                Log.w(TAG, "attach actions failed, return raw view: " + t.getMessage());
            }
        }
        return view;
    }

    /** 在渲染结果里找 CardShell（组件可能把外壳包在包装容器下） */
    private static CardShell findShell(View view) {
        if (view == null) return null;
        if (view instanceof CardShell) return (CardShell) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                CardShell found = findShell(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** 自身已渲染 actions 的类型（避免外层重复附加）：alert / contact 卡片内部自带动作区 */
    private boolean selfRendersActions(String type) {
        String canonical = ComponentTypes.resolve(type);
        return "alert".equals(canonical) || "contact".equals(canonical);
    }

    /**
     * 渲染失败时的兜底卡片（Track B0-4：从"⚠ 组件渲染失败: type"一行裸文本
     * 升级为统一错误态外壳，带上类型便于排查，视觉与其它卡片一致）。
     */
    public View renderFallback(Context context, ComponentData data) {
        return CardShell.create(context)
                .setHeaderIcon(R.drawable.ic_ai_error, ComponentTheme.danger(context))
                .setState(CardShell.State.ERROR, context.getString(
                        R.string.component_render_failed,
                        data != null && data.type != null ? data.type : "unknown"));
    }

    // ---------- UI-06 统一 style 对象（容器级） ----------
    // 与 NativeLayoutRenderer.applyContainerStyle 同口径：
    // padding=整数 或 "t r b l" 或 [4个数]；radius=圆角dp；background=颜色；border=宽度+颜色 或 border_width+border_color

    private static void applyViewStyle(Context context, View v, JSONObject style) {
        if (v == null || style == null) return;
        float density = context.getResources().getDisplayMetrics().density;
        // padding（B2：上限 32dp，防止模型把卡片内边距撑成半屏）
        try {
            if (style.has("padding")) {
                Object pad = style.opt("padding");
                if (pad instanceof Number) {
                    int p = dpPad(((Number) pad).intValue(), density);
                    v.setPadding(p, p, p, p);
                } else if (pad instanceof JSONArray) {
                    JSONArray pa = (JSONArray) pad;
                    if (pa.length() == 1) {
                        int p = dpPad(pa.optInt(0), density);
                        v.setPadding(p, p, p, p);
                    } else if (pa.length() == 2) {
                        int ph = dpPad(pa.optInt(0), density), pv2 = dpPad(pa.optInt(1), density);
                        v.setPadding(ph, pv2, ph, pv2);
                    } else if (pa.length() >= 4) {
                        v.setPadding(dpPad(pa.optInt(0), density), dpPad(pa.optInt(1), density),
                                dpPad(pa.optInt(2), density), dpPad(pa.optInt(3), density));
                    }
                } else {
                    String ps = String.valueOf(pad).trim();
                    String[] parts = ps.split("\\s+");
                    if (parts.length == 1) {
                        int p = dpPad(parseIntSafe(parts[0]), density);
                        v.setPadding(p, p, p, p);
                    } else if (parts.length == 4) {
                        v.setPadding(dpPad(parseIntSafe(parts[0]), density),
                                dpPad(parseIntSafe(parts[1]), density),
                                dpPad(parseIntSafe(parts[2]), density),
                                dpPad(parseIntSafe(parts[3]), density));
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
                int radiusPx = hasRadius ? dp(clampRadius(style.optInt("radius")), density) : 0;
                int bgColor = 0;
                boolean bgOk = false;
                if (hasBg) {
                    Integer c = ComponentTheme.styleColor(context, style.optString("background", ""));
                    if (c != null) { bgColor = c; bgOk = true; }
                }
                int borderWidth = 0;
                int borderColor = ComponentTheme.outline(context);
                boolean borderOk = false;
                if (style.has("border")) {
                    String bs = style.optString("border", "").trim();
                    // "1 surface" / "2 outline"：宽度 + 颜色 token（B2 起不再接受十六进制色值）
                    if (bs.matches("\\d+(\\s+\\S+)?")) {
                        String[] bp = bs.split("\\s+");
                        borderWidth = clampBorderWidth(parseIntSafe(bp[0]));
                        if (bp.length > 1) {
                            Integer c = ComponentTheme.styleColor(context, bp[1]);
                            if (c != null) { borderColor = c; borderOk = true; }
                        }
                    }
                }
                if (style.has("border_width")) {
                    borderWidth = clampBorderWidth(parseIntSafe(style.optString("border_width", "0")));
                    Integer c = ComponentTheme.styleColor(context, style.optString("border_color", ""));
                    if (c != null) { borderColor = c; borderOk = true; }
                }
                if (borderWidth > 0) borderOk = true;
                // 仅 radius（无 background/border）且控件已有背景 → 跳过，保留原背景
                if (hasRadius && !bgOk && !borderOk && v.getBackground() != null) return;
                if (hasRadius || bgOk || borderOk) {
                    android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                    if (bgOk) gd.setColor(bgColor);
                    if (hasRadius) gd.setCornerRadius(radiusPx);
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

    /** 内边距上限 32dp（B2：模型给的 style 不能把卡片内边距撑成半屏） */
    private static int dpPad(int v, float density) {
        return dp(Math.max(0, Math.min(32, v)), density);
    }

    /** 圆角上限 24dp */
    private static int clampRadius(int dpValue) {
        return Math.max(0, Math.min(24, dpValue));
    }

    /** 描边宽度上限 4dp */
    private static int clampBorderWidth(int dpValue) {
        return Math.max(0, Math.min(4, dpValue));
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}

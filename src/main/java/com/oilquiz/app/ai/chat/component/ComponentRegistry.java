package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

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
}

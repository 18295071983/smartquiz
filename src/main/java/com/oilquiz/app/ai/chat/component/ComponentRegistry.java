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
        // Agent 执行过程（插入式显示在 AI 消息内）
        register(new ToolCallCardView());
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
     *
     * @param context Android Context
     * @param data    组件数据
     * @return 组件 View；类型未注册或数据非法时返回 null（调用方自行降级）
     */
    public View render(Context context, ComponentData data) {
        if (data == null || data.type == null) return null;
        ChatComponent component = components.get(data.type);
        if (component == null) {
            Log.w(TAG, "no component registered for type: " + data.type);
            return null;
        }
        try {
            if (component.canRender(data)) {
                return component.createView(context, data);
            }
        } catch (Exception e) {
            Log.e(TAG, "component render failed: " + data.type, e);
        }
        return null;
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

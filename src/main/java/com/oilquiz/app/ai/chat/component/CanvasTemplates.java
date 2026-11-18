package com.oilquiz.app.ai.chat.component;

import java.util.Map;

/**
 * 画布内置模板（2026-11-18）：{@code ui_component(action=create, component_type=layout_canvas,
 * template=form|settings|dashboard)} 直接用一套"长得像样"的版式起手，而不是让模型每次从头拼 JSON。
 *
 * <p>只做三件事：</p>
 * <ol>
 *   <li>显式传了 {@code layout} / {@code props.layout} 时<b>不覆盖</b>（模板只作兜底）；</li>
 *   <li>按模板给默认画布尺寸（宽吃满推荐宽、高走 auto 随内容扩展）；</li>
 *   <li>尺寸仍可被显式 {@code canvas_width/height/max_height} 覆盖。</li>
 * </ol>
 *
 * <p>模板本身只用 {@code NativeLayoutRenderer} 已有节点，不引入新控件类型。</p>
 */
public final class CanvasTemplates {

    private CanvasTemplates() {
    }

    public static final String FORM = "form";
    public static final String SETTINGS = "settings";
    public static final String DASHBOARD = "dashboard";

    private static final String FORM_LAYOUT = "{\"root\":{\"type\":\"column\",\"spacing\":12,\"children\":["
            + "{\"type\":\"text\",\"text\":\"表单\",\"bold\":true,\"fontSize\":18},"
            + "{\"type\":\"input\",\"label\":\"名称\",\"hint\":\"请输入名称\",\"key\":\"name\"},"
            + "{\"type\":\"select\",\"label\":\"类型\",\"options\":[\"A\",\"B\",\"C\"],\"key\":\"kind\"},"
            + "{\"type\":\"number\",\"label\":\"数量\",\"key\":\"count\"},"
            + "{\"type\":\"switch\",\"text\":\"启用\",\"key\":\"enabled\"},"
            + "{\"type\":\"button\",\"text\":\"提交\",\"action\":\"submit\"}"
            + "]}}";

    private static final String SETTINGS_LAYOUT = "{\"root\":{\"type\":\"column\",\"spacing\":12,\"children\":["
            + "{\"type\":\"text\",\"text\":\"设置\",\"bold\":true,\"fontSize\":18},"
            + "{\"type\":\"card\",\"title\":\"常规\",\"children\":["
            + "{\"type\":\"switch\",\"text\":\"消息通知\",\"key\":\"notify\"},"
            + "{\"type\":\"select\",\"label\":\"主题\",\"options\":[\"跟随系统\",\"浅色\",\"深色\"],\"key\":\"theme\"}"
            + "]},"
            + "{\"type\":\"card\",\"title\":\"高级\",\"children\":["
            + "{\"type\":\"slider\",\"label\":\"字号\",\"min\":12,\"max\":24,\"value\":16,\"key\":\"font\"},"
            + "{\"type\":\"checkbox\",\"text\":\"开启调试日志\",\"key\":\"debug\"}"
            + "]},"
            + "{\"type\":\"button\",\"text\":\"保存\",\"action\":\"save\"}"
            + "]}}";

    private static final String DASHBOARD_LAYOUT = "{\"root\":{\"type\":\"column\",\"spacing\":12,\"children\":["
            + "{\"type\":\"text\",\"text\":\"概览\",\"bold\":true,\"fontSize\":18},"
            + "{\"type\":\"row\",\"spacing\":8,\"children\":["
            + "{\"type\":\"stat\",\"label\":\"今日\",\"value\":\"128\"},"
            + "{\"type\":\"stat\",\"label\":\"本周\",\"value\":\"1.2k\"}"
            + "]},"
            + "{\"type\":\"bar_chart\",\"categories\":[\"一\",\"二\",\"三\",\"四\",\"五\"],"
            + "\"series\":[{\"name\":\"完成\",\"data\":[3,5,2,6,4]}]},"
            + "{\"type\":\"divider\"},"
            + "{\"type\":\"text\",\"text\":\"数据每 5 分钟刷新\",\"fontSize\":12}"
            + "]}}";

    /**
     * 把模板展开进 create 参数（就地修改 {@code parameters}）。
     *
     * @return 实际使用的模板名；未传/不认识返回 null（调用方保持原行为）
     */
    public static String apply(Map<String, Object> parameters) {
        if (parameters == null) {
            return null;
        }
        Object raw = parameters.get("template");
        String name = raw == null ? "" : String.valueOf(raw).trim().toLowerCase(java.util.Locale.US);
        String layout = layoutOf(name);
        if (layout == null) {
            return null;
        }
        // 显式 layout 优先：顶层 layout 或 props 里已有 layout 时不覆盖
        boolean explicitLayout = parameters.get("layout") != null
                || String.valueOf(parameters.get("props")).contains("\"layout\"");
        if (!explicitLayout) {
            parameters.put("layout", layout);
        }
        // 默认尺寸：宽不写（宿主按屏宽-32dp 自适应），高 auto 随内容扩展，上限 80%
        putIfAbsent(parameters, "canvas_height", "auto");
        putIfAbsent(parameters, "canvas_max_height", "80%");
        return name;
    }

    /** 模板对应的 layout JSON；未知模板返回 null。 */
    public static String layoutOf(String name) {
        if (name == null) {
            return null;
        }
        switch (name.trim().toLowerCase(java.util.Locale.US)) {
            case FORM:
                return FORM_LAYOUT;
            case SETTINGS:
                return SETTINGS_LAYOUT;
            case DASHBOARD:
                return DASHBOARD_LAYOUT;
            default:
                return null;
        }
    }

    /** 全部模板名（工具定义/文档用）。 */
    public static String[] names() {
        return new String[]{FORM, SETTINGS, DASHBOARD};
    }

    private static void putIfAbsent(Map<String, Object> parameters, String key, Object value) {
        if (parameters.get(key) == null) {
            parameters.put(key, value);
        }
    }
}

package com.oilquiz.app.ai.chat.component;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 交互控件取值收集器（2026-11-16，第 3 项：交互回传）。
 *
 * <p><b>问题</b>：动态组件走 {@code render.card} 时，`primitive_slider_range` / `toggle` /
 * `checkbox_group` 等控件**画得出来但拖了没人收** —— 因为 {@code LayoutPrimitiveCardView}
 * 只把 view 返回，没有向 {@link ComponentActions} 注册 input collector，
 * `ui_component get_result` 自然拿不到值。</p>
 *
 * <p><b>做法</b>：渲染完成后遍历返回的视图树，把可交互控件的当前值收集成 map，
 * 再注册成该 componentId 的 collector。键名规则（可预测、便于模型使用）：</p>
 * <ul>
 *   <li>{@code EditText} → 键取 {@code contentDescription}（渲染器会写 key），否则 {@code input_N}；</li>
 *   <li>{@code CompoundButton}（switch/checkbox/radio）→ {@code toggle_N} → boolean；</li>
 *   <li>{@code SeekBar} → {@code slider_N} → int progress；</li>
 *   <li>Material {@code Slider} / {@code RangeSlider} → 反射取值（避免硬依赖具体子类）；</li>
 *   <li>{@code TextView} 且带 contentDescription 且可点击 → 忽略（不是输入）。</li>
 * </ul>
 * 遍历深度上限 40、控件数上限 50，避免超大视图树拖慢 {@code get_result}。
 */
public final class PrimitiveInputCollector {

    private static final int MAX_DEPTH = 40;
    private static final int MAX_FIELDS = 50;

    private PrimitiveInputCollector() {
    }

    /**
     * 如果这个组件有 componentId，就注册一个"实时取值"的 collector。
     *
     * @param data 组件数据（用于解析 componentId）
     * @param root 渲染出来的视图树
     */
    public static void registerIfPossible(ComponentData data, View root) {
        if (data == null || root == null) {
            return;
        }
        String componentId = ComponentActions.resolveComponentId(data);
        if (componentId == null || componentId.isEmpty()) {
            return;
        }
        // collector 是回调而非快照：get_result 时才遍历，取到的是那一刻的真实值
        ComponentActions.registerInputCollector(componentId, () -> collect(root));
    }

    /** 遍历视图树，收集可交互控件的当前值（纯函数，可单测）。 */
    public static Map<String, Object> collect(View root) {
        Map<String, Object> out = new LinkedHashMap<>();
        collectInto(root, out, 0);
        return out;
    }

    private static void collectInto(View view, Map<String, Object> out, int depth) {
        if (view == null || depth > MAX_DEPTH || out.size() >= MAX_FIELDS) {
            return;
        }
        String key = view.getContentDescription() == null
                ? "" : view.getContentDescription().toString().trim();

        if (view instanceof EditText) {
            String text = ((EditText) view).getText() == null ? "" : ((EditText) view).getText().toString();
            put(out, TextUtils.isEmpty(key) ? nextKey(out, "input") : key, text);
        } else if (view instanceof CompoundButton) {
            put(out, TextUtils.isEmpty(key) ? nextKey(out, "toggle") : key,
                    ((CompoundButton) view).isChecked());
        } else if (view instanceof SeekBar) {
            put(out, TextUtils.isEmpty(key) ? nextKey(out, "slider") : key,
                    ((SeekBar) view).getProgress());
        } else {
            collectMaterialSlider(view, key, out);
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && out.size() < MAX_FIELDS; i++) {
                collectInto(group.getChildAt(i), out, depth + 1);
            }
        }
    }

    /** Material Slider / RangeSlider：反射取 value / values，避免依赖具体类版本。 */
    private static void collectMaterialSlider(View view, String key, Map<String, Object> out) {
        String cls = view.getClass().getName();
        if (!cls.endsWith("Slider") && !cls.endsWith("RangeSlider")) {
            return;
        }
        try {
            java.lang.reflect.Method getValue = view.getClass().getMethod("getValue");
            Object v = getValue.invoke(view);
            put(out, TextUtils.isEmpty(key) ? nextKey(out, "slider") : key, v);
            return;
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Method getValues = view.getClass().getMethod("getValues");
            Object v = getValues.invoke(view);
            put(out, TextUtils.isEmpty(key) ? nextKey(out, "slider") : key, String.valueOf(v));
        } catch (Throwable ignored) {
        }
    }

    private static void put(Map<String, Object> out, String key, Object value) {
        if (key == null || key.isEmpty() || value == null) {
            return;
        }
        if (!out.containsKey(key)) {
            out.put(key, value);
        }
    }

    private static String nextKey(Map<String, Object> out, String prefix) {
        int i = out.size() + 1;
        while (out.containsKey(prefix + "_" + i)) {
            i++;
        }
        return prefix + "_" + i;
    }

    /** 调试：把收集结果变成一行 JSON（日志/回传诊断用）。 */
    public static String toJson(Map<String, Object> values) {
        return new JSONObject(values).toString();
    }
}

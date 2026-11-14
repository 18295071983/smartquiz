package com.oilquiz.app.ai.python;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一张 layout 卡内的绑定作用域（2026-11-15）。
 *
 * <p>职责只有三件：</p>
 * <ol>
 *   <li>渲染前把节点上的 {@code {"$":"data.a.b"}} 绑定换成 {@code props.data} 里的值；</li>
 *   <li>把 {@code text}/{@code stat|metric.value} 上的绑定/公式登记下来；</li>
 *   <li>输入控件变化时写回 {@code props.data}，并只刷新登记过的文本节点
 *       （不重建整棵树，输入焦点和滚动位置都不丢）。</li>
 * </ol>
 *
 * <p><b>限制（如实写在这里）</b>：监听只覆盖原生 EditText / Spinner / CompoundButton /
 * RadioGroup / SeekBar；日期、时间、取色、评分、步进器、双滑块、标签输入这些是自定义
 * ref 对象，没有统一监听接口，暂不参与"改动即重算"（提交按钮仍会经 collectValues 回传）。
 * 公式只在 text 与 stat/metric 的 value 位置生效，其它位置写了公式一律降级成 "&mdash;"。</p>
 */
final class LayoutBindingScope {

    /** viewRefs 里的保留 key；collectValues/applyValues 会跳过下划线开头的 key。 */
    static final String REF_KEY = "__layout_bindings__";

    private final JSONObject props;
    private final JSONObject data;
    private final List<BoundText> boundTexts = new ArrayList<>();
    private final Set<View> watched = Collections.newSetFromMap(new IdentityHashMap<>());

    private LayoutBindingScope(JSONObject props, JSONObject data) {
        this.props = props;
        this.data = data;
    }

    static LayoutBindingScope create(JSONObject props) {
        JSONObject data = props != null ? props.optJSONObject("data") : null;
        return new LayoutBindingScope(props, data != null ? data : new JSONObject());
    }

    static LayoutBindingScope from(Map<String, Object> viewRefs) {
        if (viewRefs == null) return null;
        Object scope = viewRefs.get(REF_KEY);
        return scope instanceof LayoutBindingScope ? (LayoutBindingScope) scope : null;
    }

    /**
     * 渲染前解析节点属性绑定。
     *
     * <p>只对"取值位置"保留原始对象（text 的 text、stat/metric 的 value），
     * 这样输入变化后还能重新求值；其余位置的绑定当场换成值。公式写在别处直接 "&mdash;"。</p>
     */
    JSONObject resolveNode(JSONObject node) {
        if (node == null) return null;
        JSONObject out = new JSONObject();
        try {
            Iterator<String> keys = node.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = node.opt(key);
                if (LayoutFormula.isBinding(value)) {
                    if (isLivePosition(node, key)) {
                        out.put(key, value);
                    } else {
                        Object resolved = LayoutFormula.resolvePath(data, LayoutFormula.bindingPath(value));
                        out.put(key, resolved != null ? resolved : "");
                    }
                } else if (LayoutFormula.isFormula(value)) {
                    if (isLivePosition(node, key)) {
                        out.put(key, value);
                    } else {
                        // 公式只允许出现在 text / metric 的取值位置，其它位置明确降级
                        out.put(key, LayoutFormula.FALLBACK);
                    }
                } else {
                    out.put(key, value);
                }
            }
        } catch (Throwable ignored) {
            return node;
        }
        return out;
    }

    private static boolean isLivePosition(JSONObject node, String key) {
        String type = node.optString("type", "column");
        if ("text".equals(type) && "text".equals(key)) return true;
        return ("stat".equals(type) || "metric".equals(type)) && "value".equals(key);
    }

    /** 登记一个绑定/公式文本并立刻写入初始值；返回展示文本。 */
    String bind(TextView view, Object rawValue) {
        if (view != null) {
            boundTexts.add(new BoundText(view, rawValue));
        }
        String text = LayoutFormula.resolveDisplay(rawValue, data);
        if (view != null) view.setText(text);
        return text;
    }

    /**
     * 渲染完成后接线：先把 props.data 的初值灌进输入控件（没有则把控件当前值写回 data），
     * 再加监听，最后重算一遍绑定文本 —— 这样 "input value=3 + 公式 a*2" 首屏就显示 6。
     */
    void attach(Map<String, Object> viewRefs) {
        if (viewRefs == null) return;
        boolean touchedInputs = false;
        for (Map.Entry<String, Object> entry : new ArrayList<>(viewRefs.entrySet())) {
            String key = entry.getKey();
            if (key == null || key.startsWith("_") || key.startsWith("text_")) continue;
            Object ref = entry.getValue();
            String path = normalizePath(key);
            if (path.isEmpty()) continue;
            if (ref instanceof EditText) {
                EditText et = (EditText) ref;
                if (watched.contains(et)) continue;
                String text = et.getText() != null ? et.getText().toString() : "";
                Object stored = LayoutFormula.resolvePath(data, path);
                if (stored != null && !String.valueOf(stored).equals(text)) {
                    et.setText(LayoutFormula.displayValue(stored));
                    text = et.getText() != null ? et.getText().toString() : "";
                } else if (stored == null && !text.isEmpty()) {
                    putValue(path, text);
                }
                et.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                        putValue(path, s != null ? s.toString() : "");
                    }
                });
                watched.add(et);
                touchedInputs = true;
            } else if (ref instanceof Spinner) {
                Spinner spinner = (Spinner) ref;
                if (watched.contains(spinner)) continue;
                Object stored = LayoutFormula.resolvePath(data, path);
                if (stored != null) {
                    selectSpinnerValue(spinner, LayoutFormula.displayValue(stored));
                } else if (spinner.getSelectedItem() != null) {
                    putValue(path, String.valueOf(spinner.getSelectedItem()));
                }
                spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                               int position, long id) {
                        if (parent != null && parent.getSelectedItem() != null) {
                            putValue(path, String.valueOf(parent.getSelectedItem()));
                        }
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {
                    }
                });
                watched.add(spinner);
                touchedInputs = true;
            } else if (ref instanceof CompoundButton) {
                CompoundButton button = (CompoundButton) ref;
                if (watched.contains(button)) continue;
                Object stored = LayoutFormula.resolvePath(data, path);
                if (stored instanceof Boolean) {
                    button.setChecked((Boolean) stored);
                } else {
                    putValue(path, button.isChecked());
                }
                button.setOnCheckedChangeListener((v, checked) -> putValue(path, checked));
                watched.add(button);
                touchedInputs = true;
            } else if (ref instanceof RadioGroup) {
                RadioGroup group = (RadioGroup) ref;
                if (watched.contains(group)) continue;
                group.setOnCheckedChangeListener((g, checkedId) -> {
                    if (checkedId == -1) {
                        putValue(path, "");
                        return;
                    }
                    View checked = g.findViewById(checkedId);
                    Object value = checked != null ? checked.getTag() : null;
                    putValue(path, value != null ? String.valueOf(value)
                            : (checked instanceof TextView
                            ? ((TextView) checked).getText().toString() : ""));
                });
                watched.add(group);
                touchedInputs = true;
            } else if (ref instanceof SeekBar) {
                SeekBar bar = (SeekBar) ref;
                if (watched.contains(bar)) continue;
                int min = bar.getTag() instanceof Integer ? (Integer) bar.getTag() : 0;
                Object stored = LayoutFormula.resolvePath(data, path);
                if (stored instanceof Number) {
                    int progress = (int) Math.round(((Number) stored).doubleValue()) - min;
                    bar.setProgress(Math.max(0, Math.min(bar.getMax(), progress)));
                } else {
                    putValue(path, bar.getProgress() + min);
                }
                bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        putValue(path, progress + min);
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                    }
                });
                watched.add(bar);
                touchedInputs = true;
            }
        }
        if (touchedInputs) {
            ensureDataInProps();
            refresh();
        }
    }

    /** 刷新所有登记过的文本节点（输入变化 / 初值灌入后调用）。 */
    void refresh() {
        for (BoundText bound : boundTexts) {
            try {
                String text = LayoutFormula.resolveDisplay(bound.rawValue, data);
                if (!text.equals(bound.view.getText().toString())) {
                    bound.view.setText(text);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void putValue(String path, Object value) {
        putPath(data, path, value);
        ensureDataInProps();
        refresh();
    }

    private void ensureDataInProps() {
        if (props == null || props.has("data")) return;
        try {
            props.put("data", data);
        } catch (Throwable ignored) {
        }
    }

    private void selectSpinnerValue(Spinner spinner, String value) {
        try {
            if (spinner.getAdapter() == null) return;
            for (int i = 0; i < spinner.getAdapter().getCount(); i++) {
                Object item = spinner.getAdapter().getItem(i);
                if (item != null && value.equals(String.valueOf(item))) {
                    spinner.setSelection(i);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String normalizePath(String key) {
        String path = key == null ? "" : key.trim();
        if (path.startsWith("data.")) path = path.substring("data.".length());
        return path;
    }

    /** 按 a.b.c 写入（中间对象不存在就建）；只做对象路径，不支持数组下标。 */
    private static void putPath(JSONObject root, String path, Object value) {
        if (root == null || path == null || path.isEmpty()) return;
        String[] parts = path.split("\\.");
        try {
            JSONObject current = root;
            for (int i = 0; i < parts.length - 1; i++) {
                if (parts[i].isEmpty()) return;
                JSONObject next = current.optJSONObject(parts[i]);
                if (next == null) {
                    next = new JSONObject();
                    current.put(parts[i], next);
                }
                current = next;
            }
            String leaf = parts[parts.length - 1];
            if (!leaf.isEmpty()) current.put(leaf, value);
        } catch (Throwable ignored) {
        }
    }

    private static final class BoundText {
        final TextView view;
        final Object rawValue;

        BoundText(TextView view, Object rawValue) {
            this.view = view;
            this.rawValue = rawValue;
        }
    }
}

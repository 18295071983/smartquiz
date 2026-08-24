package com.oilquiz.app.ai.python;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 项目级原生 UI 框架：把 JSON 声明的原生控件树（layout）编译渲染成真实 Android View。
 *
 * Agent 在插件 render.layout 中用 JSON 描述任意原生 UI：
 * <pre>
 * {"root": {"type": "column", "children": [
 *   {"type": "text", "text": "任务监控", "bold": true, "size": 18},
 *   {"type": "input", "hint": "任务ID", "key": "task_id", "value": "abc123"},
 *   {"type": "progress", "progress": 30, "max": 100},
 *   {"type": "row", "children": [
 *     {"type": "button", "text": "刷新", "action": "refresh"},
 *     {"type": "button", "text": "关闭", "action": "close"}
 *   ]}
 * ]}}
 * </pre>
 *
 * 控件类型（type）：
 * - 布局容器: column(纵向)/row(横向)/scroll(可滚动)
 * - 展示: text(文本, bold/size/color)/marquee(跑马灯,speed 0~3)/image(url 或 path, width/height)
 * - 输入: input(单行,key/hint/value)/number(数字)/password(密码)/multiline(多行)/otp(验证码,length)
 * - 选择: select(下拉,key/options/value)/switch(开关,key/checked)/checkbox(复选,key/checked)/
 *         radio(单选组,key/options/value)/date(日期选择,key/value)/time(时间选择,key/value)/
 *         color(取色器,key/value)/rating(星级评分,key/value 1~5)
 * - 交互: button(text,action回传 或 tool+tool_params调后端)/slider(滑块,key/min/max/value,show_value)
 * - 进度: progress(progress/max)
 * - 装饰: divider(分割线)
 *
 * 值与回传：控件带 key 的值（input/number/password/multiline/otp/select/switch/checkbox/radio/slider/
 * date/time/color/rating/progress）在按钮点击时收集为 JSON；button 的 action 作为点击结果回传。
 * 后端组件：button 支持 {action, tool, tool_params} —— tool=后端工具名（如 dashscope_media），
 * 点击直接调用后端工具，tool_params 中 {key} 占位符替换为控件值，结果经组件 result 回传。
 * 尺寸自定义：任意节点支持 width/height 属性（"match"/"fill"=铺满, "wrap"=自适应, 数字=dp），
 * 如 {"type":"text","text":"标题","width":200,"height":40}。
 * 所有 text 支持 {key} 占位符，从 props 替换。
 */
public class NativeLayoutRenderer {

    private static final String TAG = "NativeLayoutRenderer";
    private static final String[] LAYOUT_TYPES = {"column", "row", "scroll"};
    private static final String[] CONTROL_TYPES = {
            // 展示
            "text", "image", "marquee",
            // 输入
            "input", "number", "password", "multiline", "otp",
            // 选择
            "select", "switch", "checkbox", "radio", "date", "time", "color", "rating",
            // 交互/进度
            "button", "slider", "progress",
            // 装饰
            "divider"
    };

    /** 校验 layout 定义，返回错误描述；null=通过 */
    public static String validate(Object layoutObj) {
        try {
            JSONObject root = layoutObj instanceof JSONObject
                    ? (JSONObject) layoutObj : new JSONObject(String.valueOf(layoutObj));
            if (!root.has("root")) {
                JSONObject r = new JSONObject();
                r.put("type", "column");
                r.put("children", new JSONArray().put(root));
                root = r;
            }
            JSONObject node = root.optJSONObject("root");
            if (node == null) return "root 必须是对象";
            return validateNode(node, 0);
        } catch (Exception e) {
            return "layout 解析失败: " + e.getMessage();
        }
    }

    private static String validateNode(JSONObject node, int depth) {
        if (depth > 8) return "布局嵌套过深（>8 层）";
        String type = node.optString("type", "column");
        boolean isLayout = contains(LAYOUT_TYPES, type);
        boolean isControl = contains(CONTROL_TYPES, type);
        if (!isLayout && !isControl) {
            return "未知控件类型: " + type
                    + "（可选: column/row/scroll/text/marquee/image/input/number/password/multiline/otp/select/switch/checkbox/radio/date/time/color/rating/button/slider/progress/divider）";
        }
        JSONArray children = node.optJSONArray("children");
        if (children != null) {
            for (int i = 0; i < children.length(); i++) {
                JSONObject c = children.optJSONObject(i);
                if (c == null) return "children[" + i + "] 不是对象";
                String e = validateNode(c, depth + 1);
                if (e != null) return e;
            }
        }
        return null;
    }

    private static boolean contains(String[] arr, String v) {
        for (String s : arr) if (s.equals(v)) return true;
        return false;
    }

    /** 渲染 layout 树为真实 View；viewRefs 收集带 key 的控件引用/值引用。返回根 View。 */
    public static View render(Context context, Object layoutObj, JSONObject props,
                              Map<String, Object> viewRefs) {
        try {
            JSONObject root = layoutObj instanceof JSONObject
                    ? (JSONObject) layoutObj : new JSONObject(String.valueOf(layoutObj));
            if (!root.has("root")) {
                JSONObject r = new JSONObject();
                r.put("type", "column");
                r.put("children", new JSONArray().put(root));
                root = r;
            }
            JSONObject node = root.optJSONObject("root");
            if (node == null) node = root;
            return buildNode(context, node, props, viewRefs, 0);
        } catch (Exception e) {
            android.util.Log.w(TAG, "layout 渲染失败: " + e.getMessage());
            TextView tv = new TextView(context);
            tv.setText("⚠ 原生布局渲染失败: " + e.getMessage());
            tv.setTextSize(12);
            return tv;
        }
    }

    private static View buildNode(Context context, JSONObject node, JSONObject props,
                                  Map<String, Object> viewRefs, int depth) {
        String type = node.optString("type", "column");
        int density = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1,
                context.getResources().getDisplayMetrics());
        switch (type) {
            case "column": {
                LinearLayout ll = new LinearLayout(context);
                ll.setOrientation(LinearLayout.VERTICAL);
                ll.setPadding(dp(8, density), dp(4, density), dp(8, density), dp(4, density));
                addChildren(context, ll, node, props, viewRefs, depth);
                return ll;
            }
            case "row": {
                LinearLayout ll = new LinearLayout(context);
                ll.setOrientation(LinearLayout.HORIZONTAL);
                ll.setGravity(Gravity.CENTER_VERTICAL);
                ll.setPadding(dp(8, density), dp(4, density), dp(8, density), dp(4, density));
                addChildren(context, ll, node, props, viewRefs, depth);
                return ll;
            }
            case "scroll": {
                android.widget.ScrollView sv = new android.widget.ScrollView(context);
                JSONArray kids = node.optJSONArray("children");
                if (kids != null && kids.length() > 0) {
                    JSONObject child = kids.optJSONObject(0);
                    if (child != null) {
                        View v = buildNode(context, child, props, viewRefs, depth + 1);
                        sv.addView(v, new android.widget.ScrollView.LayoutParams(
                                android.widget.ScrollView.LayoutParams.MATCH_PARENT,
                                android.widget.ScrollView.LayoutParams.WRAP_CONTENT));
                    }
                }
                return sv;
            }
            case "text": {
                TextView tv = new TextView(context);
                tv.setText(interpolate(node.optString("text", ""), props));
                tv.setTextSize(node.has("size") ? (float) node.optDouble("size", 14) : 14);
                tv.setTypeface(node.optBoolean("bold", false)
                        ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
                if (node.has("color")) tv.setTextColor(parseColor(context, node.optString("color", "")));
                tv.setPadding(0, dp(2, density), 0, dp(2, density));
                if (node.has("key")) viewRefs.put("text_" + node.optString("key"), tv);
                return tv;
            }
            case "marquee": {
                TextView tv = new TextView(context);
                String raw = interpolate(node.optString("text", ""), props);
                tv.setText(raw);
                tv.setTextSize(node.has("size") ? (float) node.optDouble("size", 16) : 16);
                tv.setTypeface(node.optBoolean("bold", false)
                        ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
                if (node.has("color")) tv.setTextColor(parseColor(context, node.optString("color", "")));
                tv.setPadding(0, dp(4, density), 0, dp(4, density));
                int speed = Math.max(0, Math.min(3, node.optInt("speed", 1)));
                int rep = node.optInt("repeat", -1);
                // 位移动画实现跑马灯（不依赖系统 marquee 焦点机制，Dialog/任意容器内均可滚动）
                startMarquee(tv, raw, speed, rep < 0 ? -1 : rep);
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put("text_" + key, tv);
                return tv;
            }
            case "input": {
                EditText et = new EditText(context);
                et.setHint(interpolate(node.optString("hint", "请输入"), props));
                String value = node.has("value") ? interpolate(node.optString("value", ""), props)
                        : (props != null ? props.optString(node.optString("key", ""), "") : "");
                if (!value.isEmpty()) et.setText(value);
                et.setSingleLine(true);
                et.setTextSize(14);
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put(key, et);
                return et;
            }
            case "number": {
                EditText et = new EditText(context);
                et.setHint(interpolate(node.optString("hint", "请输入数字"), props));
                String value = node.has("value") ? interpolate(node.optString("value", ""), props)
                        : (props != null ? props.optString(node.optString("key", ""), "") : "");
                if (!value.isEmpty()) et.setText(value);
                et.setSingleLine(true);
                et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                        | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
                et.setTextSize(14);
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put(key, et);
                return et;
            }
            case "password": {
                EditText et = new EditText(context);
                et.setHint(interpolate(node.optString("hint", "请输入密码"), props));
                String value = node.has("value") ? interpolate(node.optString("value", ""), props)
                        : (props != null ? props.optString(node.optString("key", ""), "") : "");
                if (!value.isEmpty()) et.setText(value);
                et.setSingleLine(true);
                et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                        | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
                et.setTextSize(14);
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put(key, et);
                return et;
            }
            case "multiline": {
                EditText et = new EditText(context);
                et.setHint(interpolate(node.optString("hint", "请输入内容"), props));
                String value = node.has("value") ? interpolate(node.optString("value", ""), props)
                        : (props != null ? props.optString(node.optString("key", ""), "") : "");
                if (!value.isEmpty()) et.setText(value);
                et.setSingleLine(false);
                et.setMinLines(2);
                et.setMaxLines(6);
                et.setGravity(Gravity.TOP | Gravity.START);
                et.setTextSize(14);
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put(key, et);
                return et;
            }
            case "otp": {
                EditText et = new EditText(context);
                int len = Math.max(1, Math.min(12, node.optInt("length", 6)));
                et.setHint("请输入" + len + "位验证码");
                String value = node.has("value") ? interpolate(node.optString("value", ""), props)
                        : (props != null ? props.optString(node.optString("key", ""), "") : "");
                if (!value.isEmpty()) et.setText(value);
                et.setSingleLine(true);
                et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                et.setTextSize(14);
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put(key, et);
                return et;
            }
            case "checkbox": {
                android.widget.CheckBox cb = new android.widget.CheckBox(context);
                cb.setText(interpolate(node.optString("text", ""), props));
                cb.setChecked(node.optBoolean("checked", false));
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put(key, cb);
                return cb;
            }
            case "radio": {
                android.widget.RadioGroup rg = new android.widget.RadioGroup(context);
                rg.setOrientation(LinearLayout.VERTICAL);
                JSONArray opts = node.optJSONArray("options");
                String selected = node.optString("value", node.optString("default", ""));
                if (opts != null) {
                    for (int i = 0; i < opts.length(); i++) {
                        String label = String.valueOf(opts.opt(i));
                        android.widget.RadioButton rb = new android.widget.RadioButton(context);
                        rb.setText(label);
                        rb.setId(android.view.View.generateViewId());
                        rg.addView(rb);
                        if (label.equals(selected)) rg.check(rb.getId());
                    }
                }
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put(key, rg);
                return rg;
            }
            case "slider": {
                android.widget.SeekBar sb = new android.widget.SeekBar(context);
                int max = node.optInt("max", 100);
                int min = node.optInt("min", 0);
                sb.setMax(max - min);
                int val = node.optInt("value", node.optInt("default", 0));
                sb.setProgress(Math.max(0, Math.min(max - min, val - min)));
                // 记录 min：collectValues 收集时返回 progress+min（否则 min>0 时值偏移）
                sb.setTag(min);
                final int fMin = min;
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put(key, sb);
                if (node.optBoolean("show_value", false)) {
                    final TextView valTv = new TextView(context);
                    valTv.setTextSize(12);
                    valTv.setText(String.valueOf(val));
                    valTv.setPadding(0, dp(2, density), 0, 0);
                    sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                        @Override public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                            valTv.setText(String.valueOf(progress + fMin));
                        }
                        @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
                        @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
                    });
                    LinearLayout wrap = new LinearLayout(context);
                    wrap.setOrientation(LinearLayout.VERTICAL);
                    wrap.addView(sb);
                    wrap.addView(valTv);
                    return wrap;
                }
                return sb;
            }
            case "date": {
                final TextView tv = new TextView(context);
                final String defVal = node.has("value") ? node.optString("value", "")
                        : node.optString("default", "");
                final String[] cur = {defVal.isEmpty() ? "点击选择日期" : defVal};
                tv.setText(cur[0]);
                tv.setTextSize(14);
                tv.setPadding(0, dp(6, density), 0, dp(6, density));
                tv.setBackgroundColor(com.oilquiz.app.ai.chat.component.ComponentColors.fieldBg(context));
                final String key = node.optString("key", "");
                tv.setOnClickListener(v -> {
                    java.util.Calendar cal = java.util.Calendar.getInstance();
                    try {
                        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                                "yyyy-MM-dd", java.util.Locale.getDefault());
                        java.util.Date d = sdf.parse(cur[0]);
                        if (d != null) cal.setTime(d);
                    } catch (Exception ignored) {
                    }
                    new android.app.DatePickerDialog(context,
                            (view, year, month, dayOfMonth) -> {
                                cur[0] = String.format(java.util.Locale.getDefault(),
                                        "%04d-%02d-%02d", year, month + 1, dayOfMonth);
                                tv.setText(cur[0]);
                            },
                            cal.get(java.util.Calendar.YEAR),
                            cal.get(java.util.Calendar.MONTH),
                            cal.get(java.util.Calendar.DAY_OF_MONTH)).show();
                });
                if (!key.isEmpty()) viewRefs.put(key, tv);
                return tv;
            }
            case "time": {
                final TextView tv = new TextView(context);
                final String defVal = node.has("value") ? node.optString("value", "")
                        : node.optString("default", "");
                final String[] cur = {defVal.isEmpty() ? "点击选择时间" : defVal};
                tv.setText(cur[0]);
                tv.setTextSize(14);
                tv.setPadding(0, dp(6, density), 0, dp(6, density));
                tv.setBackgroundColor(com.oilquiz.app.ai.chat.component.ComponentColors.fieldBg(context));
                final String key = node.optString("key", "");
                tv.setOnClickListener(v -> {
                    java.util.Calendar cal = java.util.Calendar.getInstance();
                    try {
                        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                                "HH:mm", java.util.Locale.getDefault());
                        java.util.Date d = sdf.parse(cur[0]);
                        if (d != null) cal.setTime(d);
                    } catch (Exception ignored) {
                    }
                    new android.app.TimePickerDialog(context,
                            (view, hourOfDay, minute) -> {
                                cur[0] = String.format(java.util.Locale.getDefault(),
                                        "%02d:%02d", hourOfDay, minute);
                                tv.setText(cur[0]);
                            },
                            cal.get(java.util.Calendar.HOUR_OF_DAY),
                            cal.get(java.util.Calendar.MINUTE),
                            true).show();
                });
                if (!key.isEmpty()) viewRefs.put(key, tv);
                return tv;
            }
            case "color": {
                final String[] palette = {"#EF4444", "#F97316", "#F59E0B", "#84CC16",
                        "#10B981", "#06B6D4", "#3B82F6", "#8B5CF6", "#EC4899", "#6B7280"};
                LinearLayout grid = new LinearLayout(context);
                grid.setOrientation(LinearLayout.HORIZONTAL);
                final String[] picked = {node.optString("value", node.optString("default", "#EF4444"))};
                for (final String c : palette) {
                    android.view.View sw = new android.view.View(context);
                    android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                            dp(28, density), dp(28, density));
                    lp.setMargins(dp(3, density), dp(3, density), dp(3, density), dp(3, density));
                    sw.setLayoutParams(lp);
                    sw.setBackgroundColor(parseColor(context, c));
                    sw.setOnClickListener(v -> {
                        picked[0] = c;
                        for (int i = 0; i < grid.getChildCount(); i++) {
                            android.view.View gv = grid.getChildAt(i);
                            gv.setPadding(0, 0, 0, 0);
                        }
                        sw.setPadding(dp(2, density), dp(2, density), dp(2, density), dp(2, density));
                    });
                    grid.addView(sw);
                }
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put(key, new ColorRef(picked));
                return grid;
            }
            case "rating": {
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                final int[] score = {node.optInt("value", node.optInt("default", 0))};
                final TextView[] stars = new TextView[5];
                final int emptyColor = com.oilquiz.app.ai.chat.component.ComponentColors.ratingEmpty(context);
                final int filledColor = com.oilquiz.app.ai.chat.component.ComponentColors.warning(context);
                for (int i = 0; i < 5; i++) {
                    final int idx = i;
                    TextView star = new TextView(context);
                    star.setText("★");
                    star.setTextSize(30);
                    star.setTextColor(emptyColor);
                    star.setPadding(dp(4, density), 0, dp(4, density), 0);
                    star.setOnClickListener(v -> {
                        score[0] = idx + 1;
                        for (int k = 0; k < 5; k++) {
                            stars[k].setTextColor(k <= idx ? filledColor : emptyColor);
                        }
                    });
                    stars[i] = star;
                    row.addView(star);
                }
                if (score[0] >= 1 && score[0] <= 5) {
                    for (int k = 0; k < score[0]; k++) stars[k].setTextColor(filledColor);
                }
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put(key, new RatingRef(score));
                return row;
            }
            case "button": {
                Button btn = new Button(context);
                btn.setText(interpolate(node.optString("text", "确定"), props));
                btn.setTextSize(13);
                btn.setAllCaps(false);
                String bind = node.optString("action", "");
                if (node.has("tool")) {
                    try {
                        JSONObject bindObj = new JSONObject();
                        bindObj.put("action", node.optString("action", ""));
                        bindObj.put("tool", node.optString("tool", ""));
                        Object tp = node.opt("tool_params");
                        bindObj.put("tool_params", tp != null ? tp : new JSONObject());
                        bind = bindObj.toString();
                    } catch (Exception e) {
                        bind = node.optString("action", "");
                    }
                }
                btn.setTag(bind);
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put(key, btn);
                return btn;
            }
            case "image": {
                ImageView iv = new ImageView(context);
                int wDp = node.has("width") ? (int) Math.round(node.optDouble("width", -1)) : -1;
                int hDp = node.has("height") ? (int) Math.round(node.optDouble("height", -1)) : -1;
                if (wDp > 0 && hDp > 0) {
                    iv.setAdjustViewBounds(false);
                    iv.setLayoutParams(new LinearLayout.LayoutParams(dp(wDp, density), dp(hDp, density)));
                } else {
                    iv.setAdjustViewBounds(true);
                    iv.setMaxHeight((int) (240 * density));
                }
                String src = interpolate(node.optString("url", ""), props);
                final android.graphics.drawable.Drawable ph =
                        new android.graphics.drawable.ColorDrawable(
                                com.oilquiz.app.ai.chat.component.ComponentColors.imagePlaceholder(context));
                final android.graphics.drawable.Drawable err =
                        new android.graphics.drawable.ColorDrawable(
                                com.oilquiz.app.ai.chat.component.ComponentColors.imageError(context));
                if (!src.isEmpty()) {
                    try {
                        com.bumptech.glide.request.RequestOptions opts =
                                new com.bumptech.glide.request.RequestOptions();
                        if (wDp > 0 && hDp > 0) {
                            opts = opts.override(dp(wDp, density), dp(hDp, density));
                        }
                        opts = opts.centerCrop();
                        if (src.startsWith("http://") || src.startsWith("https://")) {
                            com.bumptech.glide.Glide.with(context)
                                    .load(src)
                                    .apply(opts)
                                    .placeholder(ph)
                                    .error(err)
                                    .into(iv);
                        } else {
                            String path = src.startsWith("file://")
                                    ? android.net.Uri.parse(src).getPath() : src;
                            java.io.File f = new java.io.File(path);
                            if (f.exists()) {
                                com.bumptech.glide.Glide.with(context)
                                        .load(f)
                                        .apply(opts)
                                        .placeholder(ph)
                                        .error(err)
                                        .into(iv);
                            } else {
                                iv.setBackground(err);
                            }
                        }
                    } catch (Throwable t) {
                        android.util.Log.w(TAG, "图片加载失败: " + t.getMessage());
                        iv.setBackground(err);
                    }
                } else {
                    iv.setBackground(err);
                }
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put(key, iv);
                return iv;
            }
            case "progress": {
                ProgressBar pb = new ProgressBar(context, null,
                        android.R.attr.progressBarStyleHorizontal);
                pb.setMax(node.optInt("max", 100));
                pb.setProgress(node.optInt("progress", 0));
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put(key, pb);
                return pb;
            }
            case "select": {
                Spinner sp = new Spinner(context);
                JSONArray opts = node.optJSONArray("options");
                java.util.List<String> items = new java.util.ArrayList<>();
                if (opts != null) {
                    for (int i = 0; i < opts.length(); i++) {
                        items.add(String.valueOf(opts.opt(i)));
                    }
                }
                if (items.isEmpty()) items.add("请选择");
                android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<>(
                        context, android.R.layout.simple_spinner_item, items);
                ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                sp.setAdapter(ad);
                String sel = node.optString("value", "");
                if (sel.isEmpty()) sel = node.optString("default", "");
                String key = node.optString("key", "");
                if (sel.isEmpty() && !key.isEmpty() && props != null) {
                    sel = props.optString(key, "");
                }
                if (!sel.isEmpty()) {
                    int idx = items.indexOf(sel);
                    if (idx >= 0) sp.setSelection(idx);
                }
                if (!key.isEmpty()) viewRefs.put(key, sp);
                return sp;
            }
            case "switch": {
                android.widget.Switch sw = new android.widget.Switch(context);
                sw.setChecked(node.optBoolean("checked", false));
                String text = interpolate(node.optString("text", ""), props);
                if (!text.isEmpty()) sw.setText(text);
                String key = node.optString("key", "");
                if (!key.isEmpty()) viewRefs.put(key, sw);
                return sw;
            }
            case "divider": {
                View v = new View(context);
                v.setBackgroundColor(com.oilquiz.app.ai.chat.component.ComponentColors.border(context));
                v.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1, density)));
                return v;
            }
            default: {
                TextView tv = new TextView(context);
                tv.setText("⚠ 未知控件: " + type);
                tv.setTextSize(12);
                return tv;
            }
        }
    }

    private static void addChildren(Context context, LinearLayout parent, JSONObject node,
                                    JSONObject props, Map<String, Object> viewRefs, int depth) {
        JSONArray children = node.optJSONArray("children");
        if (children == null) return;
        for (int i = 0; i < children.length(); i++) {
            JSONObject c = children.optJSONObject(i);
            if (c == null) continue;
            View v = buildNode(context, c, props, viewRefs, depth + 1);
            if (v instanceof ImageView && v.getLayoutParams() != null) {
                parent.addView(v);
                continue;
            }
            int w = resolveSize(c.opt("width"), LinearLayout.LayoutParams.MATCH_PARENT, density(context));
            int h = resolveSize(c.opt("height"), LinearLayout.LayoutParams.WRAP_CONTENT, density(context));
            parent.addView(v, new LinearLayout.LayoutParams(w, h));
        }
    }

    private static int resolveSize(Object val, int def, int density) {
        if (val == null) return def;
        String s = String.valueOf(val).trim();
        if ("match".equalsIgnoreCase(s) || "fill".equalsIgnoreCase(s) || "match_parent".equalsIgnoreCase(s)) {
            return LinearLayout.LayoutParams.MATCH_PARENT;
        }
        if ("wrap".equalsIgnoreCase(s) || "wrap_content".equalsIgnoreCase(s)) {
            return LinearLayout.LayoutParams.WRAP_CONTENT;
        }
        try {
            return (int) (Double.parseDouble(s) * density);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int density(Context context) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1,
                context.getResources().getDisplayMetrics());
    }

    private static String interpolate(String text, JSONObject props) {
        if (text == null || props == null) return text == null ? "" : text;
        String out = text;
        try {
            Iterator<String> keys = props.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object v = props.opt(k);
                out = out.replace("{" + k + "}", String.valueOf(v));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static int dp(int base, int density) {
        return base * density;
    }

    private static int parseColor(Context context, String hex) {
        try {
            if (hex != null && hex.startsWith("#") && hex.length() >= 7) {
                return Color.parseColor(hex);
            }
        } catch (Exception ignored) {
        }
        return com.oilquiz.app.ai.chat.component.ComponentColors.textPrimary(context);
    }

    /**
     * 跑马灯滚动：位移动画实现（不依赖系统 TextView marquee 的焦点机制，
     * 在 Dialog / 插件弹窗等无焦点容器内也能正常滚动）。
     *
     * @param tv     目标 TextView（必须已 setText）
     * @param text   原始文本（用于测量宽度）
     * @param speed  0=不滚动（单行截断） 1=慢 2=中 3=快
     * @param repeat 循环次数；-1 = 无限循环
     */
    public static void startMarquee(final TextView tv, final String text, final int speed, final int repeat) {
        if (tv == null) return;
        tv.setSingleLine(true);
        if (speed <= 0 || text == null || text.isEmpty()) {
            // 不滚动：单行尾部截断（保证布局稳定）
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tv.setSelected(false);
            return;
        }
        // 关闭系统 marquee（selected/focus 机制），滚动完全交给位移动画，避免两者叠加冲突
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setSelected(false);
        tv.setFocusable(false);
        tv.setFocusableInTouchMode(false);
        tv.post(() -> {
            try {
                int viewWidth = tv.getWidth();
                if (viewWidth <= 0) {
                    viewWidth = tv.getResources().getDisplayMetrics().widthPixels / 2;
                }
                float textWidth = tv.getPaint().measureText(text);
                if (textWidth <= viewWidth) {
                    return; // 文本未超宽：静止展示即可，无需滚动
                }
                long duration = speed >= 3 ? 4000L : (speed == 2 ? 6500L : 10000L);
                android.view.animation.TranslateAnimation anim =
                        new android.view.animation.TranslateAnimation(
                                viewWidth, -textWidth, 0, 0); // 从右缘进入 → 完全移出左缘
                anim.setDuration(duration);
                anim.setRepeatMode(android.view.animation.Animation.RESTART);
                anim.setRepeatCount(repeat < 0
                        ? android.view.animation.Animation.INFINITE : Math.max(0, repeat));
                anim.setInterpolator(new android.view.animation.LinearInterpolator());
                tv.startAnimation(anim);
            } catch (Throwable ignored) {
            }
        });
    }

    /** 收集带 key 的控件值（input/number/password/multiline/otp→文本、select→选中项、switch/checkbox→布尔、
     *  radio→选中项、slider→数值、date/time→文本、color→#RRGGBB、rating→分数、progress→数值） */
    public static Map<String, Object> collectValues(Map<String, Object> viewRefs) {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, Object> e : viewRefs.entrySet()) {
            String key = e.getKey();
            if (key.startsWith("text_") || key.startsWith("_")) continue;
            Object v = e.getValue();
            if (v instanceof EditText) {
                out.put(key, ((EditText) v).getText() != null ? ((EditText) v).getText().toString() : "");
            } else if (v instanceof Spinner) {
                Spinner sp = (Spinner) v;
                out.put(key, sp.getSelectedItem() != null ? String.valueOf(sp.getSelectedItem()) : "");
            } else if (v instanceof android.widget.Switch) {
                out.put(key, ((android.widget.Switch) v).isChecked());
            } else if (v instanceof android.widget.CheckBox) {
                out.put(key, ((android.widget.CheckBox) v).isChecked());
            } else if (v instanceof android.widget.RadioGroup) {
                android.widget.RadioGroup rg = (android.widget.RadioGroup) v;
                int checkedId = rg.getCheckedRadioButtonId();
                if (checkedId != -1) {
                    android.widget.RadioButton rb = rg.findViewById(checkedId);
                    out.put(key, rb != null ? rb.getText().toString() : "");
                } else {
                    out.put(key, "");
                }
            } else if (v instanceof android.widget.SeekBar) {
                android.widget.SeekBar sb = (android.widget.SeekBar) v;
                int min = 0;
                Object tag = sb.getTag();
                if (tag instanceof Integer) min = (Integer) tag;
                out.put(key, sb.getProgress() + min);
            } else if (v instanceof ProgressBar) {
                out.put(key, ((ProgressBar) v).getProgress());
            } else if (v instanceof ColorRef) {
                out.put(key, ((ColorRef) v).getValue());
            } else if (v instanceof RatingRef) {
                out.put(key, ((RatingRef) v).getValue());
            } else if (v instanceof TextView && !(v instanceof android.widget.Button)
                    && !(v instanceof android.widget.CompoundButton)) {
                // date/time 选择器等纯文本值控件（点击弹系统选择器后回写文本）；按钮文本不收集
                out.put(key, ((TextView) v).getText() != null ? ((TextView) v).getText().toString() : "");
            }
        }
        return out;
    }

    /** 取色器值引用（key → 当前选中色 #RRGGBB） */
    static class ColorRef {
        private final String[] picked;
        ColorRef(String[] picked) { this.picked = picked; }
        String getValue() { return picked[0]; }
    }

    /** 评分值引用（key → 分数 1-5，0=未选） */
    static class RatingRef {
        private final int[] score;
        RatingRef(int[] score) { this.score = score; }
        int getValue() { return score[0]; }
    }
}

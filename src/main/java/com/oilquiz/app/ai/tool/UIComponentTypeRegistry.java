package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自定义原生组件类型注册表（外部注入）：Agent 可用 ui_component action=register_type
 * 注册"新组件类型名 → 渲染定义"，之后 ui_component action=create 即可用该类型名创建
 * 原生组件（无需写代码，纯 JSON 声明）。注册表持久化到 files/ui_component_types.json。
 *
 * 类型定义（typeDef）：
 * <pre>
 * {
 *   "name": "alarm_panel",                    // 新组件类型名（字母数字下划线）
 *   "description": "闹钟面板：显示时间+开关+按钮",
 *   "render": {                                // 渲染定义
 *     "card": "info_card",                     // 可选：内置卡片类型
 *     "layout": {"root": {"type": "column",    // 可选：项目原生控件框架树
 *       "children": [...]}},
 *     "props": {"title": "默认标题"}           // 可选：固定字段
 *   },
 *   "monitor": {...}                           // 可选：任务监控（同 ui_component_plugin）
 * }
 * </pre>
 *
 * 与 UIComponentPluginManager 的区别：这里只注册"类型名→渲染定义"（轻量别名），
 * 不要求 params schema/生命周期；适合 Agent 现场把复杂 layout 封装成可复用类型名。
 */
public class UIComponentTypeRegistry {

    private static final String TAG = "UIComponentTypeRegistry";
    private static volatile UIComponentTypeRegistry instance;

    private final Context context;
    private final ConcurrentHashMap<String, JSONObject> types = new ConcurrentHashMap<>();

    private UIComponentTypeRegistry(Context context) {
        this.context = context.getApplicationContext();
        load();
    }

    public static UIComponentTypeRegistry getInstance(Context context) {
        if (instance == null) {
            synchronized (UIComponentTypeRegistry.class) {
                if (instance == null) {
                    instance = new UIComponentTypeRegistry(context);
                }
            }
        }
        return instance;
    }

    private File typesFile() {
        return new File(context.getFilesDir(), "ui_component_types.json");
    }

    /** 注册类型。persist=true 落盘跨重启保留（默认）；false=临时仅内存（任务结束/clear_temporary 消失）。返回问题列表（空=成功）。 */
    public synchronized List<String> registerType(JSONObject typeDef, boolean persist) {
        List<String> problems = new ArrayList<>();
        try {
            String name = typeDef != null ? typeDef.optString("name", "").trim() : "";
            if (!com.oilquiz.app.ai.tool.UIComponentPluginManager.isValidName(name)) {
                problems.add("类型名非法（仅字母/数字/下划线）: " + name);
                return problems;
            }
            if (typeDef.optString("description", "").trim().isEmpty()) {
                problems.add("类型描述 description 不能为空");
                return problems;
            }
            // 渲染定义必须含 card 或 layout 之一
            JSONObject render = typeDef.optJSONObject("render");
            if (render == null || (!render.has("card") && !render.has("layout"))) {
                problems.add("render 必须含 card(内置卡片) 或 layout(原生控件框架树) 之一");
                return problems;
            }
            if (render.has("card") && !render.optString("card", "").isEmpty()
                    && !com.oilquiz.app.ai.chat.component.ComponentRegistry
                            .getInstance().hasType(render.optString("card", ""))) {
                problems.add("render.card 不是项目内置卡片类型: " + render.optString("card", ""));
                return problems;
            }
            if (render.has("layout")) {
                // 校验时放行已注册类型名/插件名/模板名（layout 内可嵌套引用它们）
                java.util.Set<String> known = collectRegisteredNames();
                String layoutErr = com.oilquiz.app.ai.python.NativeLayoutRenderer
                        .validate(render.opt("layout"), known);
                if (layoutErr != null) {
                    problems.add("render.layout 非法: " + layoutErr);
                    return problems;
                }
            }
            typeDef.put("name", name);
            typeDef.put("createdAt", System.currentTimeMillis());
            typeDef.put("mode", persist ? "persistent" : "temporary");
            types.put(name, typeDef);
            if (persist) {
                persist();
            }
            Log.i(TAG, "自定义组件类型已注册: " + name + " (" + (persist ? "长久" : "临时") + ")");
        } catch (Exception e) {
            Log.e(TAG, "注册类型失败: " + e.getMessage(), e);
            problems.add("注册类型失败: " + e.getMessage());
        }
        return problems;
    }

    public boolean hasType(String name) {
        return name != null && types.containsKey(name);
    }

    /** 已注册类型名集合（不含 layout 模板/插件，仅本表） */
    public java.util.Set<String> typeNames() {
        return new java.util.HashSet<>(types.keySet());
    }

    /**
     * 收集当前已注册的全部可引用名：类型表 + 插件表 + layout 模板表。
     * 供 layout 校验放行嵌套引用（render.layout 内可用它们作节点 type）。
     */
    private java.util.Set<String> collectRegisteredNames() {
        java.util.Set<String> known = typeNames();
        try {
            known.addAll(com.oilquiz.app.ai.tool.UIComponentPluginManager.getInstance(context).pluginNames());
        } catch (Throwable ignored) {
        }
        try {
            known.addAll(com.oilquiz.app.ai.python.LayoutTemplateRegistry.getInstance(context).templateNames());
        } catch (Throwable ignored) {
        }
        return known;
    }

    public JSONObject getType(String name) {
        return types.get(name);
    }

    public List<JSONObject> listTypes() {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject t : types.values()) {
            JSONObject brief = new JSONObject();
            try {
                brief.put("name", t.optString("name", ""));
                brief.put("description", t.optString("description", ""));
                brief.put("mode", t.optString("mode", "persistent"));
                JSONObject render = t.optJSONObject("render");
                brief.put("card", render != null ? render.optString("card", "") : "");
                brief.put("has_layout", render != null && render.has("layout"));
                brief.put("has_monitor", t.has("monitor"));
            } catch (Exception ignored) {
            }
            out.add(brief);
        }
        return out;
    }

    public synchronized boolean removeType(String name) {
        boolean removed = types.remove(name) != null;
        if (removed) persist();
        return removed;
    }

    /** 清除全部临时类型（仅内存，任务收尾/clear_temporary 用）；长久类型保留 */
    public synchronized void clearTemporaryTypes() {
        List<String> toRemove = new ArrayList<>();
        for (java.util.Map.Entry<String, JSONObject> e : types.entrySet()) {
            if ("temporary".equals(e.getValue().optString("mode", ""))) {
                toRemove.add(e.getKey());
            }
        }
        for (String n : toRemove) types.remove(n);
        if (!toRemove.isEmpty()) {
            Log.i(TAG, "已清除临时类型: " + toRemove);
        }
    }

    private synchronized void persist() {
        try {
            JSONArray arr = new JSONArray();
            // 只持久化长久类型（临时类型仅内存）
            for (JSONObject t : types.values()) {
                if (!"temporary".equals(t.optString("mode", ""))) {
                    arr.put(t);
                }
            }
            File f = typesFile();
            if (arr.length() == 0) {
                if (f.exists() && !f.delete()) Log.w(TAG, "删除类型文件失败");
                return;
            }
            java.io.FileWriter writer = new java.io.FileWriter(f);
            writer.write(arr.toString(2));
            writer.close();
            Log.i(TAG, "自定义组件类型持久化: " + arr.length());
        } catch (Exception e) {
            Log.e(TAG, "持久化类型失败: " + e.getMessage(), e);
        }
    }

    private synchronized void load() {
        try {
            File f = typesFile();
            if (!f.exists()) return;
            String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            // 容错：剥离 UTF-8 BOM（外部写入可能带 \uFEFF，org.json 无法解析会致类型"丢失"）
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
                text = text.substring(1);
            }
            JSONArray arr = new JSONArray(text);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.optJSONObject(i);
                if (t == null) continue;
                String name = t.optString("name", "").trim();
                if (!name.isEmpty()) types.put(name, t);
            }
            Log.i(TAG, "自定义组件类型加载: " + types.size());
        } catch (Exception e) {
            Log.e(TAG, "加载类型失败: " + e.getMessage(), e);
        }
    }

    /** 重新从磁盘加载类型（管理界面刷新用）：单例可能先于外部注册初始化，reload 保证显示磁盘最新。 */
    public synchronized void reload() {
        types.clear();
        load();
    }
}

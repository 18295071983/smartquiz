package com.oilquiz.app.ai.python;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 原生 layout 模板库（单例）—— 插件式注册全局可复用的控件模板。
 *
 * Agent 可用 ui_component_plugin(action=register_layout) 注册命名 layout 模板，
 * 之后任何 render.layout 树内都能用 {"use": "模板名", "props": {...}} 引用：
 * <pre>
 * // 注册（持久化，跨重启保留）
 * ui_component_plugin(action=register_layout, name="stat_item",
 *     layout={"type":"card","title":"{label}","children":[
 *         {"type":"text","text":"{value}","bold":true,"size":20}]},
 *     description="统计项卡片", persist=true)
 *
 * // 复用（任意 layout 内）
 * {"root":{"type":"column","children":[
 *     {"use":"stat_item","props":{"label":"收入","value":"¥1000"}},
 *     {"use":"stat_item","props":{"label":"支出","value":"¥200"}}
 * ]}}
 * </pre>
 *
 * 模板内 {key} 占位符由 use 节点的 props 替换。局部 define（layout 顶层）优先于全局模板。
 */
public class LayoutTemplateRegistry {

    private static final String TAG = "LayoutTemplateRegistry";

    private static volatile LayoutTemplateRegistry instance;

    private final Context context;
    private final File templatesFile;
    private final ConcurrentHashMap<String, JSONObject> templates = new ConcurrentHashMap<>();

    private LayoutTemplateRegistry(Context context) {
        this.context = context.getApplicationContext();
        this.templatesFile = new File(context.getFilesDir(), "layout_templates.json");
        load();
    }

    public static LayoutTemplateRegistry getInstance(Context context) {
        if (instance == null) {
            synchronized (LayoutTemplateRegistry.class) {
                if (instance == null) {
                    instance = new LayoutTemplateRegistry(context);
                }
            }
        }
        return instance;
    }

    /** 注册模板。返回问题列表（空=成功）。 */
    public synchronized List<String> registerTemplate(String name, JSONObject layout, String description,
                                                      boolean persist) {
        List<String> problems = new ArrayList<>();
        try {
            if (name == null || !name.matches("[A-Za-z0-9_]+")) {
                problems.add("模板名非法（仅字母/数字/下划线）: " + name);
                return problems;
            }
            if (layout == null) {
                problems.add("layout 不能为空");
                return problems;
            }
            // 校验 layout 结构（复用渲染器校验；放行已注册类型名/插件名/模板名嵌套引用）
            java.util.Set<String> known = new java.util.HashSet<>(templates.keySet());
            try {
                known.addAll(com.oilquiz.app.ai.tool.UIComponentTypeRegistry.getInstance(context).typeNames());
            } catch (Throwable ignored) {
            }
            try {
                known.addAll(com.oilquiz.app.ai.tool.UIComponentPluginManager.getInstance(context).pluginNames());
            } catch (Throwable ignored) {
            }
            String err = NativeLayoutRenderer.validate(layout.toString(), known);
            if (err != null) {
                problems.add("layout 非法: " + err);
                return problems;
            }
            JSONObject entry = new JSONObject();
            entry.put("name", name);
            entry.put("layout", layout);
            entry.put("description", description == null ? "" : description);
            entry.put("mode", persist ? "persistent" : "temporary");
            entry.put("createdAt", System.currentTimeMillis());
            templates.put(name, entry);
            if (persist) {
                persist();
            }
            Log.i(TAG, "layout 模板已注册: " + name + " (" + (persist ? "长久" : "临时") + ")");
        } catch (Exception e) {
            Log.e(TAG, "注册 layout 模板失败: " + e.getMessage(), e);
            problems.add("注册失败: " + e.getMessage());
        }
        return problems;
    }

    /** 获取模板定义（不存在返回 null） */
    public JSONObject getTemplate(String name) {
        if (name == null) return null;
        JSONObject entry = templates.get(name);
        if (entry == null) return null;
        JSONObject layout = entry.optJSONObject("layout");
        if (layout == null) return null;
        // 深拷贝：调用方可安全修改
        try {
            return new JSONObject(layout.toString());
        } catch (Exception e) {
            return layout;
        }
    }

    /** 已注册模板名集合 */
    public java.util.Set<String> templateNames() {
        return new java.util.HashSet<>(templates.keySet());
    }

    /** 获取全部模板（含 mode/description/createdAt） */
    public JSONArray listTemplates() {
        JSONArray arr = new JSONArray();
        for (JSONObject entry : templates.values()) {
            try {
                JSONObject meta = new JSONObject();
                meta.put("name", entry.optString("name", ""));
                meta.put("description", entry.optString("description", ""));
                meta.put("mode", entry.optString("mode", "persistent"));
                meta.put("createdAt", entry.optLong("createdAt", 0));
                meta.put("layout", entry.optJSONObject("layout"));
                arr.put(meta);
            } catch (Exception ignored) {
            }
        }
        return arr;
    }

    /** 删除模板 */
    public synchronized boolean removeTemplate(String name) {
        if (name == null) return false;
        boolean removed = templates.remove(name) != null;
        if (removed) persist();
        return removed;
    }

    /** 清除临时模板 */
    public synchronized int clearTemporary() {
        List<String> toRemove = new ArrayList<>();
        for (java.util.Map.Entry<String, JSONObject> e : templates.entrySet()) {
            if ("temporary".equals(e.getValue().optString("mode", "persistent"))) {
                toRemove.add(e.getKey());
            }
        }
        for (String n : toRemove) templates.remove(n);
        if (!toRemove.isEmpty()) persist();
        return toRemove.size();
    }

    /** 合并全局模板到 defines（局部 define 优先覆盖） */
    public JSONObject mergeDefines(JSONObject localDefines) {
        JSONObject merged = new JSONObject();
        try {
            for (java.util.Map.Entry<String, JSONObject> e : templates.entrySet()) {
                JSONObject layout = e.getValue().optJSONObject("layout");
                if (layout != null) {
                    merged.put(e.getKey(), new JSONObject(layout.toString()));
                }
            }
            if (localDefines != null) {
                java.util.Iterator<String> ik = localDefines.keys();
                while (ik.hasNext()) {
                    String k = ik.next();
                    merged.put(k, localDefines.get(k));
                }
            }
        } catch (Exception ignored) {
        }
        return merged;
    }

    private synchronized void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject entry : templates.values()) {
                if ("persistent".equals(entry.optString("mode", "persistent"))) {
                    arr.put(entry);
                }
            }
            if (arr.length() == 0) {
                if (templatesFile.exists() && !templatesFile.delete()) {
                    Log.w(TAG, "删除模板文件失败");
                }
                return;
            }
            java.io.FileWriter writer = new java.io.FileWriter(templatesFile);
            writer.write(arr.toString(2));
            writer.close();
            Log.i(TAG, "layout 模板持久化: " + arr.length());
        } catch (Exception e) {
            Log.e(TAG, "持久化 layout 模板失败: " + e.getMessage(), e);
        }
    }

    private synchronized void load() {
        try {
            if (!templatesFile.exists()) return;
            String text = new String(java.nio.file.Files.readAllBytes(templatesFile.toPath()), "UTF-8");
            // 容错：剥离 UTF-8 BOM
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
                text = text.substring(1);
            }
            JSONArray arr = new JSONArray(text);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject entry = arr.optJSONObject(i);
                if (entry == null) continue;
                String name = entry.optString("name", "").trim();
                if (name.isEmpty()) continue;
                entry.put("mode", "persistent");
                templates.put(name, entry);
            }
            Log.i(TAG, "layout 模板加载: " + templates.size());
        } catch (Exception e) {
            Log.e(TAG, "加载 layout 模板失败: " + e.getMessage(), e);
        }
    }

    /**
     * 重新从磁盘加载模板（管理界面刷新用）：
     * 单例在 app 启动早期可能先于模板注册初始化，磁盘上的新注册/外部写入
     * 不会自动进内存——管理页显示"暂无模板"的根因。调用后 templates 与
     * 磁盘文件一致（磁盘为权威数据源，合并不覆盖已有同名）。
     */
    public synchronized void reload() {
        templates.clear();
        load();
    }
}

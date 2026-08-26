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
 * 原生 UI 组件插件系统（单例）—— 类型安全版。
 *
 * Agent 可用 ui_component_plugin 工具动态注册"组件插件"，把任意自定义组件类型接入
 * ui_component 工具的原生渲染链路。插件有两种生命周期，由 Agent 按任务决定：
 *
 * <ul>
 *   <li><b>长久插件</b>（persist=true / mode=persistent，默认）：保存到
 *       files/ui_component_plugins.json，跨应用重启保留，可被后续任务复用；</li>
 *   <li><b>临时插件</b>（persist=false / mode=temporary）：仅内存，任务结束/应用退出即消失，
 *       适合一次性任务的自定义组件。</li>
 * </ul>
 *
 * 插件定义（类型安全约束，注册时校验，防止不兼容/空指针/参数不匹配/类型转换崩溃）：
 * <pre>
 * {
 *   "name": "video_gen_monitor",            // 插件名 = 新的 component_type（必填，字母数字下划线）
 *   "description": "文生视频任务监控组件",    // 用途说明（必填，给模型看）
 *   "mode": "persistent",                    // 生命周期: persistent(默认,落盘) | temporary(仅内存)
 *   "params": {                              // 创建时参数 schema（类型化，注册时校验）
 *     "task_id": {"type": "string", "required": true, "description": "任务ID"},
 *     "type":    {"type": "string", "enum": ["image","video"], "default": "video"},
 *     "count":   {"type": "number", "default": 1}
 *   },
 *   "render": {                              // 渲染配置（可选；card 必须是项目内置卡片类型）
 *     "card": "progress_card",               // 仅限 ComponentRegistry 已注册类型（info_card/progress_card/...）
 *     "title": "视频生成任务",
 *     "props": {"description": "正在生成..."} // 卡片固定字段（可选）
 *   },
 *   "monitor": {                             // 任务监控配置（可选；tool 必须是项目已注册工具）
 *     "tool": "dashscope_media",             // 仅限 AIToolManager.hasTool 的工具
 *     "action": "query",
 *     "poll_seconds": 5,
 *     "param_map": {"task_id": "task_id", "type": "type"},
 *     "success_field": "filePath",
 *     "error_field": "error"
 *   }
 * }
 * </pre>
 *
 * 注册时自动校验：
 * 1) name 格式（字母数字下划线，禁止空格/点/斜杠，防注入与类型冲突）；
 * 2) params 每个字段的 type 必须 ∈ {string, number, boolean, array, object}；
 * 3) render.card 必须已被 ComponentRegistry 注册（否则渲染空指针）；
 * 4) monitor.tool 必须是 AIToolManager.hasTool 的工具（否则轮询调用空指针/找不到工具）。
 *
 * 创建组件时（showPluginComponentDialog）按 params schema 校验/转换 props：
 * 缺必填 → 明确报错；类型不符 → 按 type 转换（number→parseDouble、boolean→parseBoolean、
 * array/object→JSON 解析），转换失败返回类型错误信息而不是崩溃。
 */
public class UIComponentPluginManager {

    private static final String TAG = "UIComponentPluginManager";
    private static volatile UIComponentPluginManager instance;

    private final Context context;
    /** 全部插件（临时 + 长久）：name → 插件定义 */
    private final ConcurrentHashMap<String, JSONObject> plugins = new ConcurrentHashMap<>();

    private UIComponentPluginManager(Context context) {
        this.context = context.getApplicationContext();
        load();
    }

    public static UIComponentPluginManager getInstance(Context context) {
        if (instance == null) {
            synchronized (UIComponentPluginManager.class) {
                if (instance == null) {
                    instance = new UIComponentPluginManager(context);
                }
            }
        }
        return instance;
    }

    private File pluginsFile() {
        return new File(context.getFilesDir(), "ui_component_plugins.json");
    }

    /** 校验插件名格式：字母数字下划线，禁止空格/点/斜杠（防注入与类型冲突） */
    public static boolean isValidName(String name) {
        if (name == null || name.isEmpty() || name.length() > 64) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) return false;
        }
        return true;
    }

    /** 校验插件定义，返回问题列表（空 = 通过）。兼容性校验：render.card 白名单、monitor.tool 存在、params 类型合法。 */
    public List<String> validatePlugin(JSONObject plugin) {
        List<String> problems = new ArrayList<>();
        if (plugin == null) {
            problems.add("插件定义为空");
            return problems;
        }
        String name = plugin.optString("name", "").trim();
        if (!isValidName(name)) {
            problems.add("插件名非法（仅允许字母/数字/下划线，且不能为空）: " + (name.isEmpty() ? "(空)" : name));
        }
        if (plugin.optString("description", "").trim().isEmpty()) {
            problems.add("插件描述 description 不能为空");
        }
        // params 类型校验
        JSONObject params = plugin.optJSONObject("params");
        if (params != null) {
            java.util.Iterator<String> keys = params.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object v = params.opt(k);
                if (v instanceof JSONObject) {
                    String t = ((JSONObject) v).optString("type", "string");
                    if (!isValidParamType(t)) {
                        problems.add("参数 " + k + " 的 type 非法（仅支持 string/number/boolean/array/object）: " + t);
                    }
                }
            }
        }
        // render.card 必须已注册（防渲染空指针）；render.layout = 项目原生控件框架树（无需注册）
        JSONObject render = plugin.optJSONObject("render");
        // render 既无 card 也无 layout → 插件创建后无内容可渲染，注册无意义
        if (render != null && render.optString("card", "").trim().isEmpty()
                && !render.has("layout")) {
            problems.add("render 不能为空（需提供 render.card 内置卡片类型 或 render.layout 原生控件树）");
        }
        if (render != null && !render.optString("card", "").isEmpty()) {
            String card = render.optString("card", "").trim();
            if (!com.oilquiz.app.ai.chat.component.ComponentRegistry.getInstance().hasType(card)) {
                problems.add("render.card 不是项目内置卡片类型（见 ui_component 工具 component_type 说明）: " + card);
            }
        }
        // render.layout 存在时校验根节点 type（column/row/scroll/控件）；
        // 放行已注册类型名/插件名/模板名（layout 内可嵌套引用它们）
        if (render != null && render.has("layout")) {
            java.util.Set<String> known = new java.util.HashSet<>(pluginNames());
            try {
                known.addAll(com.oilquiz.app.ai.tool.UIComponentTypeRegistry.getInstance(context).typeNames());
            } catch (Throwable ignored) {
            }
            try {
                known.addAll(com.oilquiz.app.ai.python.LayoutTemplateRegistry.getInstance(context).templateNames());
            } catch (Throwable ignored) {
            }
            String layoutErr = com.oilquiz.app.ai.python.NativeLayoutRenderer
                    .validate(render.opt("layout"), known);
            if (layoutErr != null) {
                problems.add("render.layout 非法: " + layoutErr);
            }
        }
        // monitor.tool 必须已注册（防轮询找不到工具）
        JSONObject monitor = plugin.optJSONObject("monitor");
        if (monitor != null) {
            String tool = monitor.optString("tool", "").trim();
            if (!tool.isEmpty()) {
                try {
                    if (!AIToolManager.getInstance(context).hasTool(tool)) {
                        problems.add("monitor.tool 不是已注册工具（可用 tool_registry 查看工具清单）: " + tool);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "校验 monitor.tool 失败: " + t.getMessage());
                }
            }
        }
        return problems;
    }

    private boolean isValidParamType(String t) {
        return "string".equals(t) || "number".equals(t) || "boolean".equals(t)
                || "array".equals(t) || "object".equals(t);
    }

    /**
     * 注册插件。
     *
     * @param plugin    插件定义 JSON（name/description/params/render/monitor）
     * @param persist   true=长久插件（落盘跨重启保留，默认）；false=临时插件（仅内存，任务结束即消失）
     * @return 失败原因列表（空 = 注册成功）
     */
    public synchronized List<String> createPlugin(JSONObject plugin, boolean persist) {
        List<String> problems = validatePlugin(plugin);
        if (!problems.isEmpty()) return problems;
        try {
            String name = plugin.optString("name", "").trim();
            plugin.put("name", name);
            plugin.put("mode", persist ? "persistent" : "temporary");
            plugin.put("createdAt", System.currentTimeMillis());
            if (!plugin.has("params")) plugin.put("params", new JSONObject());
            if (!plugin.has("render")) plugin.put("render", new JSONObject());
            plugins.put(name, plugin);
            if (persist) persist();
            Log.i(TAG, "UI 组件插件已注册: " + name + " (" + (persist ? "长久" : "临时") + ")");
            return problems;
        } catch (Exception e) {
            Log.e(TAG, "注册插件失败: " + e.getMessage(), e);
            problems.add("注册插件失败: " + e.getMessage());
            return problems;
        }
    }

    /** 是否存在插件（临时+长久） */
    public boolean hasPlugin(String name) {
        return name != null && plugins.containsKey(name);
    }

    /** 已注册插件名集合（临时+长久） */
    public java.util.Set<String> pluginNames() {
        return new java.util.HashSet<>(plugins.keySet());
    }

    /** 获取插件定义（原对象，勿修改） */
    public JSONObject getPlugin(String name) {
        return plugins.get(name);
    }

    /** 列出全部插件（name + description + mode + params + has_monitor + render.card） */
    public List<JSONObject> listPlugins() {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject p : plugins.values()) {
            JSONObject brief = new JSONObject();
            try {
                brief.put("name", p.optString("name", ""));
                brief.put("description", p.optString("description", ""));
                brief.put("mode", p.optString("mode", "persistent"));
                brief.put("params", p.optJSONObject("params") != null ? p.optJSONObject("params") : new JSONObject());
                JSONObject render = p.optJSONObject("render");
                brief.put("card", render != null ? render.optString("card", "") : "");
                brief.put("has_monitor", p.has("monitor"));
            } catch (Exception ignored) {
            }
            out.add(brief);
        }
        return out;
    }

    /** 删除插件（临时+长久；长久删除同时更新磁盘） */
    public synchronized boolean removePlugin(String name) {
        boolean removed = plugins.remove(name) != null;
        if (removed) persist();
        return removed;
    }

    /** 清空全部临时插件（Agent 任务收尾时调用，避免临时组件残留） */
    public synchronized void clearTemporaryPlugins() {
        List<String> toRemove = new ArrayList<>();
        for (java.util.Map.Entry<String, JSONObject> e : plugins.entrySet()) {
            if ("temporary".equals(e.getValue().optString("mode", ""))) {
                toRemove.add(e.getKey());
            }
        }
        for (String n : toRemove) plugins.remove(n);
        if (!toRemove.isEmpty()) {
            Log.i(TAG, "已清除临时插件: " + toRemove);
        }
    }

    private synchronized void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject p : plugins.values()) {
                if ("persistent".equals(p.optString("mode", "persistent"))) {
                    arr.put(p);
                }
            }
            File f = pluginsFile();
            if (arr.length() == 0) {
                if (f.exists() && !f.delete()) {
                    Log.w(TAG, "删除插件文件失败");
                }
                return;
            }
            java.io.FileWriter writer = new java.io.FileWriter(f);
            writer.write(arr.toString(2));
            writer.close();
            Log.i(TAG, "UI 组件插件持久化: " + arr.length());
        } catch (Exception e) {
            Log.e(TAG, "持久化插件失败: " + e.getMessage(), e);
        }
    }

    private synchronized void load() {
        try {
            File f = pluginsFile();
            if (!f.exists()) return;
            String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            // 容错：剥离 UTF-8 BOM（PowerShell/编辑器写入可能带 \uFEFF，org.json 无法解析会致插件"丢失"）
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
                text = text.substring(1);
            }
            JSONArray arr = new JSONArray(text);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p == null) continue;
                String name = p.optString("name", "").trim();
                if (name.isEmpty()) continue;
                p.put("mode", "persistent");
                plugins.put(name, p);
            }
            Log.i(TAG, "UI 组件插件加载（长久）: " + plugins.size());
        } catch (Exception e) {
            Log.e(TAG, "加载插件失败: " + e.getMessage(), e);
        }
    }

    /** 重新从磁盘加载插件（管理界面刷新用）：单例可能先于外部注册初始化，reload 保证显示磁盘最新。 */
    public synchronized void reload() {
        plugins.clear();
        load();
    }

    /** 返回一个标准的插件模板（给 Agent 填参用，含设计方法注释字段） */
    public static JSONObject template() {
        try {
            JSONObject params = new JSONObject();
            params.put("task_id", new JSONObject()
                    .put("type", "string").put("required", true).put("description", "任务ID（必填）"));
            params.put("type", new JSONObject()
                    .put("type", "string").put("enum", new JSONArray().put("image").put("video"))
                    .put("default", "video").put("description", "任务类型"));
            JSONObject render = new JSONObject();
            render.put("card", "progress_card");
            render.put("title", "任务监控");
            JSONObject monitor = new JSONObject();
            monitor.put("tool", "dashscope_media");
            monitor.put("action", "query");
            monitor.put("poll_seconds", 5);
            JSONObject paramMap = new JSONObject();
            paramMap.put("task_id", "task_id");
            paramMap.put("type", "type");
            monitor.put("param_map", paramMap);
            monitor.put("success_field", "filePath");
            JSONObject tpl = new JSONObject();
            tpl.put("name", "my_task_monitor");
            tpl.put("description", "任务监控组件：自动轮询查询进度，完成展示结果");
            tpl.put("params", params);
            tpl.put("render", render);
            tpl.put("monitor", monitor);
            return tpl;
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}

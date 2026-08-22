package com.oilquiz.app.ai.agent.software.engine;

import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * UI 交互辅助：程序直接调用 ui_component 工具与用户交互（不依赖模型）。
 *
 * 覆盖 ui_component 全部能力：
 * - 系统原生组件：dialog（info/confirm/warning）/ input / choice / multi_choice / progress / snackbar / notification
 * - 内置卡片（进聊天流展示）：info_card / table_card / chart / weather_card / file_list 等
 *
 * 交互模式：create 创建组件拿 component_id → get_result 阻塞等待用户操作（带超时）。
 * 用户未操作（pending/超时）、取消（cancelled）、关闭（closed）→ 返回 null，调用方回退文本交互。
 */
public class UiInteractor {

    private static final String TAG = "UiInteractor";
    private final AIToolManager toolManager;

    public UiInteractor(AIToolManager toolManager) {
        this.toolManager = toolManager;
    }

    /** 创建 UI 组件，返回 component_id（失败返回 null） */
    public String create(String componentType, JSONObject props) {
        try {
            JSONObject args = new JSONObject();
            args.put("action", "create");
            args.put("component_type", componentType);
            if (props != null) {
                if (props.has("title")) args.put("title", props.optString("title"));
                if (props.has("message")) args.put("message", props.optString("message"));
                if (props.has("dialog_type")) args.put("dialog_type", props.optString("dialog_type"));
                if (props.has("options")) args.put("options", props.optJSONArray("options"));
                if (props.has("input_hint")) args.put("input_hint", props.optString("input_hint"));
                if (props.has("default_value")) args.put("default_value", props.optString("default_value"));
                if (props.has("props")) args.put("props", props.optJSONObject("props"));
                if (props.has("auto_close")) args.put("auto_close", props.optInt("auto_close", 0));
            }
            AIToolResult r = execute(args, 15000);
            return extractComponentId(r);
        } catch (Throwable t) {
            AILogger.w(TAG, "create failed: " + t.getMessage());
            return null;
        }
    }

    /** 从 create 结果中提取 component_id */
    private String extractComponentId(AIToolResult r) {
        if (r == null || r.getResult() == null) return null;
        Object res = r.getResult();
        try {
            if (res instanceof Map) {
                Object cid = ((Map<?, ?>) res).get("component_id");
                if (cid != null) return cid.toString();
            }
        } catch (Throwable ignored) {
        }
        String out = String.valueOf(res);
        try {
            JSONObject j = new JSONObject(out);
            if (j.has("component_id")) return j.optString("component_id");
        } catch (Exception ignored) {
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("component_id[\"']?\\s*[:=]\\s*[\"']?([A-Za-z0-9_\\-]+)")
                .matcher(out);
        return m.find() ? m.group(1) : null;
    }

    /** 阻塞等待用户操作结果（带超时），返回用户操作值；未操作/取消/关闭返回 null */
    public String getResult(String componentId, long waitMs) {
        if (componentId == null) return null;
        final AtomicReference<String> holder = new AtomicReference<>();
        final Object lock = new Object();
        Thread t = new Thread(() -> {
            try {
                JSONObject args = new JSONObject();
                args.put("action", "get_result");
                args.put("component_id", componentId);
                args.put("wait_seconds", Math.max(1, (int) (waitMs / 1000)));
                AIToolResult r = toolManager.executeTool("ui_component", toMap(args));
                if (r != null && r.getResult() != null) {
                    Object res = r.getResult();
                    String value;
                    if (res instanceof Map) {
                        Object v = ((Map<?, ?>) res).get("result");
                        value = v != null ? v.toString() : null;
                    } else {
                        value = String.valueOf(res);
                    }
                    holder.set(value);
                }
            } catch (Throwable ignored) {
            } finally {
                synchronized (lock) { lock.notifyAll(); }
            }
        }, "ui-get-result");
        t.setDaemon(true);
        t.start();
        synchronized (lock) {
            try {
                long wait = waitMs;
                long start = System.currentTimeMillis();
                while (holder.get() == null && wait > 0) {
                    lock.wait(Math.min(wait, 500));
                    wait -= (System.currentTimeMillis() - start);
                    start = System.currentTimeMillis();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String value = holder.get();
        // 未操作(pending)/取消(cancelled)/关闭(closed)/不存在(not_found) → 视为未交互
        if (value == null) return null;
        String v = value.trim();
        if ("pending".equals(v) || "cancelled".equals(v) || "closed".equals(v)
                || "not_found".equals(v) || "executed".equals(v)) {
            return null;
        }
        return v;
    }

    /** 选择交互：弹 choice 组件，返回用户选中的选项值（label=value） */
    public String askChoice(String title, String message, List<String> options, long waitMs) {
        try {
            JSONObject props = new JSONObject();
            props.put("title", title != null ? title : "请选择");
            if (message != null) props.put("message", message);
            JSONArray optArr = new JSONArray();
            for (String o : options) {
                JSONObject opt = new JSONObject();
                opt.put("label", o);
                opt.put("value", o);
                optArr.put(opt);
            }
            props.put("options", optArr);
            String cid = create("choice", props);
            if (cid == null) return null;
            String r = getResult(cid, waitMs);
            if (r != null) AILogger.i(TAG, "askChoice result: " + r);
            return r;
        } catch (Throwable t) {
            AILogger.w(TAG, "askChoice failed: " + t.getMessage());
            return null;
        }
    }

    /** 输入交互：弹 input 组件，返回用户输入文本 */
    public String askInput(String title, String hint, long waitMs) {
        try {
            JSONObject props = new JSONObject();
            props.put("title", title != null ? title : "请输入");
            if (hint != null) props.put("input_hint", hint);
            String cid = create("input", props);
            if (cid == null) return null;
            String r = getResult(cid, waitMs);
            if (r != null) AILogger.i(TAG, "askInput result: " + r);
            return r;
        } catch (Throwable t) {
            AILogger.w(TAG, "askInput failed: " + t.getMessage());
            return null;
        }
    }

    /** 确认交互：原生 confirm 对话框（确定/取消），返回是否确认；超时/取消返回 null */
    public Boolean confirm(String title, String message, long waitMs) {
        try {
            JSONObject props = new JSONObject();
            props.put("dialog_type", "confirm");
            if (title != null) props.put("title", title);
            props.put("message", message);
            String cid = create("dialog", props);
            if (cid == null) return null;
            String r = getResult(cid, waitMs);
            if (r == null) return null;
            AILogger.i(TAG, "confirm result: " + r);
            return "positive".equals(r.trim());
        } catch (Throwable t) {
            AILogger.w(TAG, "confirm failed: " + t.getMessage());
            return null;
        }
    }

    /** 展示卡片（内置组件，进聊天流渲染）：info_card / table_card / chart / weather_card 等 */
    public void showCard(String componentType, JSONObject cardProps) {
        try {
            JSONObject props = new JSONObject();
            props.put("props", cardProps);
            create(componentType, props);
        } catch (Throwable t) {
            AILogger.w(TAG, "showCard failed: " + t.getMessage());
        }
    }

    /**
     * 创建进度条组件，返回 component_id（后续用 updateProgress/closeProgress 驱动）。
     * 完整用法：createProgress → 每步 updateProgress → closeProgress 收尾。
     */
    public String createProgress(String title, String message) {
        try {
            JSONObject args = new JSONObject();
            args.put("action", "create");
            args.put("component_type", "progress");
            if (title != null) args.put("title", title);
            if (message != null) args.put("message", message);
            args.put("max_value", 100);
            AIToolResult r = execute(args, 10000);
            String cid = extractComponentId(r);
            AILogger.i(TAG, "createProgress cid=" + cid + " title=" + title);
            return cid;
        } catch (Throwable t) {
            AILogger.w(TAG, "createProgress failed: " + t.getMessage());
            return null;
        }
    }

    /** 更新进度条（percent 0-100，message 可空） */
    public void updateProgress(String componentId, int percent, String message) {
        if (componentId == null) return;
        try {
            JSONObject args = new JSONObject();
            args.put("action", "update");
            args.put("component_id", componentId);
            args.put("progress", Math.max(0, Math.min(100, percent)));
            args.put("max_value", 100);
            if (message != null) args.put("message", message);
            execute(args, 10000);
        } catch (Throwable t) {
            AILogger.w(TAG, "updateProgress failed: " + t.getMessage());
        }
    }

    /** 关闭进度条（任务完成/失败收尾，防止残留卡界面） */
    public void closeProgress(String componentId) {
        if (componentId == null) return;
        try {
            JSONObject args = new JSONObject();
            args.put("action", "close");
            args.put("component_id", componentId);
            execute(args, 10000);
            AILogger.i(TAG, "closeProgress cid=" + componentId);
        } catch (Throwable t) {
            AILogger.w(TAG, "closeProgress failed: " + t.getMessage());
        }
    }

    /** 进度提示（原生 progress 组件） */
    public void progress(String title, int percent, String message) {
        try {
            JSONObject args = new JSONObject();
            args.put("action", "create");
            args.put("component_type", "progress");
            if (title != null) args.put("title", title);
            if (message != null) args.put("message", message);
            args.put("max_value", 100);
            execute(args, 10000);
        } catch (Throwable ignored) {
        }
    }

    /** 执行 ui_component 工具调用（后台线程 + 超时，防工具内部阻塞卡死 Agent 线程） */
    private AIToolResult execute(JSONObject args, long timeoutMs) {
        final AtomicReference<AIToolResult> holder = new AtomicReference<>();
        final Object lock = new Object();
        Thread t = new Thread(() -> {
            try {
                holder.set(toolManager.executeTool("ui_component", toMap(args)));
            } catch (Throwable ignored) {
            } finally {
                synchronized (lock) { lock.notifyAll(); }
            }
        }, "ui-component");
        t.setDaemon(true);
        t.start();
        synchronized (lock) {
            try {
                long wait = timeoutMs;
                long start = System.currentTimeMillis();
                while (holder.get() == null && wait > 0) {
                    lock.wait(Math.min(wait, 500));
                    wait -= (System.currentTimeMillis() - start);
                    start = System.currentTimeMillis();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return holder.get();
    }

    private static Map<String, Object> toMap(JSONObject args) {
        Map<String, Object> map = new java.util.HashMap<>();
        java.util.Iterator<String> keys = args.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            map.put(k, args.opt(k));
        }
        return map;
    }
}

package com.oilquiz.app.ai.tool;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 布局画布编辑器：让 Agent 动态编辑常驻的布局画布（layout_canvas 组件）。
 *
 * 前置：ui_component(action=create, component_type=layout_canvas, layout={...},
 *       title=画布标题) 创建画布，返回 component_id。本工具以该 id 操作其布局：
 *
 * action=set   整体替换布局（layout: 完整布局 JSON，或 root: 单节点，或 node: 单节点）
 * action=add   向指定容器追加子节点（component_id, container: 容器path或"root", node: 新节点JSON, index: 可选插入位置）
 * action=patch 修改/删除节点（component_id, key: 节点key 或 path: 节点path; props: 合并/替换的属性; remove: true=删除）
 * action=get   返回当前布局结构（含节点数/顶层结构快照，供查看）
 * action=rebuild 强制重渲染画布
 *
 * 说明：
 * - path 格式："/children/0/children/2" 或 "root/children/0"，空或"root"=根；
 *   兼容用节点 key 定位（容器节点带 key 时，用 key 匹配）。
 * - 输入控件值在重渲染时回填（保留用户已输入的内容）。
 * - 每次编辑后画布即时刷新；get_result 提示用户操作完成。
 */
public class LayoutEditorTool implements AITool {

    private static final String TAG = "LayoutEditorTool";
    private final Context context;

    public LayoutEditorTool() {
        this.context = null;
    }

    public LayoutEditorTool(Context context) {
        this.context = context;
    }

    @Override
    public String getName() {
        return "layout_editor";
    }

    @Override
    public String getDescription() {
        return "布局画布编辑器：动态编辑常驻布局画布（layout_canvas 组件）的控件树。"
                + "动作:set(整体替换布局)/add(追加子节点)/patch(修改或删除节点)/get(查看当前布局)/rebuild(强制重渲染)。"
                + "前置:先用 ui_component(action=create, component_type=layout_canvas, layout={...}) 创建画布拿到 component_id。"
                + "每次编辑后画布即时刷新，输入控件值自动回填。适合 Agent 逐步搭建/调整 UI：先 create 空画布，再 add 一个个控件，"
                + "或用 set 一次替换整树；用 get 查看当前结构避免重复添加。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作: set/add/patch/get/rebuild");
        params.put("component_id", "画布组件ID（layout_canvas 创建返回的 component_id），必填");
        params.put("layout", "set 用：完整布局 JSON（如 {\"root\":{\"type\":\"column\",\"children\":[...]}} 或单节点 {\"type\":\"column\"}）");
        params.put("container", "add 用：目标容器路径（如 'root' 或 'root/children/0'），默认 'root'");
        params.put("node", "add 用：追加的子节点 JSON（如 {\"type\":\"text\",\"text\":\"标题\"}）");
        params.put("index", "add 用：插入位置（0 为开头，省略则追加到末尾）");
        params.put("key", "patch 用：节点 key（容器/节点带 key 时用 key 定位）");
        params.put("path", "patch 用：节点路径（如 'root/children/0'）；与 key 二选一");
        params.put("props", "patch 用：要合并到节点的属性（替换 text/key/value 等）；传 {\"remove\":true} 则删除该节点");
        params.put("remove", "patch 用：true 删除该节点（默认 false）");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            String action = str(parameters.get("action"), "");
            String componentId = str(parameters.get("component_id"), "");
            if (componentId.isEmpty()) {
                return AIToolResult.fail("缺少参数: component_id（layout_canvas 创建时返回的画布ID）");
            }
            com.oilquiz.app.ai.chat.component.LayoutCanvasManager mgr =
                    com.oilquiz.app.ai.chat.component.LayoutCanvasManager.getInstance();
            com.oilquiz.app.ai.chat.component.LayoutCanvasManager.LayoutCanvasSession session =
                    mgr.get(componentId);
            if (session == null) {
                return AIToolResult.fail("画布不存在或已关闭: " + componentId
                        + "（请先用 ui_component create layout_canvas 创建）");
            }

            switch (action) {
                case "set": {
                    JSONObject layout = parseLayout(parameters);
                    if (layout == null) {
                        return AIToolResult.fail("缺少参数: layout（需传完整布局 JSON 或根节点）");
                    }
                    session.layout = layout;
                    apply(session);
                    return ok(action, componentId, "布局已整体替换，共 " + countNodes(layout) + " 个节点");
                }
                case "add": {
                    if (session.layout == null) {
                        return AIToolResult.fail("画布布局为空，请先 set 一次或 create 时传 layout");
                    }
                    // 兼容多种参数名：node/layout/item/children/根节点对象（Agent 常用 layout 传节点）
                    JSONObject node = parseNode(parameters);
                    if (node == null) {
                        return AIToolResult.fail("缺少参数: node（要追加的子节点 JSON，也可用 layout/item 传单节点）");
                    }
                    String containerPath = str(parameters.get("container"), "root");
                    if (containerPath.isEmpty()) containerPath = "root";
                    int index = parameters.get("index") == null ? -1
                            : parseInt(parameters.get("index").toString(), -1);
                    JSONObject rootNode = resolveRoot(session.layout);
                    JSONArray container = findContainerChildren(rootNode, containerPath);
                    if (container == null) {
                        return AIToolResult.fail("容器不存在: " + containerPath);
                    }
                    if (index >= 0 && index <= container.length()) {
                        JSONArray newArr = new JSONArray();
                        for (int i = 0; i < index && i < container.length(); i++) newArr.put(container.get(i));
                        newArr.put(node);
                        for (int i = index; i < container.length(); i++) newArr.put(container.get(i));
                        container = newArr;
                        replaceContainerChildren(rootNode, containerPath, container);
                    } else {
                        container.put(node);
                    }
                    apply(session);
                    return ok(action, componentId, "已向 " + containerPath + " 追加子节点，当前共 " + countNodes(session.layout) + " 个节点");
                }
                case "patch": {
                    if (session.layout == null) {
                        return AIToolResult.fail("画布布局为空，请先 set 或 create 时传 layout");
                    }
                    boolean remove = parameters.get("remove") != null
                            && Boolean.parseBoolean(String.valueOf(parameters.get("remove")));
                    String key = str(parameters.get("key"), "");
                    String path = str(parameters.get("path"), "");
                    JSONObject props = parseProps(parameters);
                    JSONObject rootNode = resolveRoot(session.layout);
                    if (remove) {
                        // 删除：从父容器 children 中移除目标节点（按 key 或 path 定位）
                        JSONObject parent = findParent(rootNode, key, path);
                        JSONObject targetNode = findNode(rootNode, key, path);
                        if (parent == null || targetNode == null) {
                            return AIToolResult.fail("未找到要删除的节点（key=" + key + " path=" + path + "）");
                        }
                        JSONArray siblings = parent.optJSONArray("children");
                        if (siblings == null) {
                            return AIToolResult.fail("目标节点不在 children 列表中，无法删除");
                        }
                        JSONArray newArr = new JSONArray();
                        for (int i = 0; i < siblings.length(); i++) {
                            if (siblings.opt(i) != targetNode) newArr.put(siblings.get(i));
                        }
                        parent.put("children", newArr);
                        apply(session);
                        return ok(action, componentId, "已删除节点（key=" + key + " path=" + path + "）");
                    }
                    JSONObject target = findNode(rootNode, key, path);
                    if (target == null) {
                        return AIToolResult.fail("未找到目标节点（key=" + key + " path=" + path + "）");
                    }
                    if (props != null) {
                        java.util.Iterator<String> ik = props.keys();
                        while (ik.hasNext()) {
                            String pk = ik.next();
                            target.put(pk, props.get(pk));
                        }
                    }
                    apply(session);
                    return ok(action, componentId, "已更新节点（key=" + key + " path=" + path + "）");
                }
                case "get": {
                    JSONObject snapshot = session.layout != null ? session.layout : new JSONObject();
                    JSONObject brief = new JSONObject();
                    brief.put("component_id", componentId);
                    brief.put("nodes", countNodes(snapshot));
                    brief.put("layout", snapshot);
                    return AIToolResult.success(brief);
                }
                case "rebuild": {
                    apply(session);
                    return ok(action, componentId, "画布已强制重渲染");
                }
                default:
                    return AIToolResult.fail("未知操作: " + action + "（支持 set/add/patch/get/rebuild）");
            }
        } catch (Throwable t) {
            return AIToolResult.fail("布局编辑失败: " + t.getMessage());
        }
    }

    // ---- 工具方法 ----

    private void apply(com.oilquiz.app.ai.chat.component.LayoutCanvasManager.LayoutCanvasSession session) {
        try {
            if (session.applyLayout != null) {
                session.applyLayout.accept(session);
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "画布重渲染失败: " + t.getMessage());
        }
    }

    private JSONObject parseLayout(Map<String, Object> p) {
        Object layoutObj = p.get("layout");
        if (layoutObj == null) layoutObj = p.get("root");
        if (layoutObj == null) return null;
        if (layoutObj instanceof JSONObject) return (JSONObject) layoutObj;
        try {
            return new JSONObject(String.valueOf(layoutObj));
        } catch (Exception e) {
            return null;
        }
    }

    private JSONObject parseNode(Map<String, Object> p) {
        // 兼容多种参数名（Agent 常用）：node / layout / item / child / 顶层节点
        Object nodeObj = p.get("node");
        if (nodeObj == null) nodeObj = p.get("layout");
        if (nodeObj == null) nodeObj = p.get("item");
        if (nodeObj == null) nodeObj = p.get("child");
        if (nodeObj == null) return null;
        if (nodeObj instanceof JSONObject) return (JSONObject) nodeObj;
        try {
            return new JSONObject(String.valueOf(nodeObj));
        } catch (Exception e) {
            return null;
        }
    }

    private JSONObject parseProps(Map<String, Object> p) {
        Object propsObj = p.get("props");
        if (propsObj == null) return null;
        if (propsObj instanceof JSONObject) return (JSONObject) propsObj;
        try {
            return new JSONObject(String.valueOf(propsObj));
        } catch (Exception e) {
            return null;
        }
    }

    /** 取布局的根节点：layout 顶层带 root 则取 root，否则 layout 本身 */
    private JSONObject resolveRoot(JSONObject layout) {
        if (layout == null) return null;
        JSONObject root = layout.optJSONObject("root");
        return root != null ? root : layout;
    }

    /** 容器 path（"root/children/0"）→ 其 children 数组；找不到返回 null */
    private JSONArray findContainerChildren(JSONObject rootNode, String path) {
        if (rootNode == null) return null;
        if (path == null || path.isEmpty() || "root".equals(path) || "/".equals(path)) {
            return rootNode.optJSONArray("children");
        }
        String[] segs = path.split("/");
        JSONObject cur = rootNode;
        // 跳过前导空段与 "root"
        int start = 0;
        if (segs.length > 0 && (segs[0].isEmpty() || "root".equals(segs[0]))) start = 1;
        for (int i = start; i < segs.length - 1; i++) {
            String seg = segs[i];
            if (seg.isEmpty()) continue;
            JSONArray children = cur.optJSONArray("children");
            if (children == null) return null;
            int idx = parseInt(seg, -1);
            JSONObject child = idx >= 0 && idx < children.length()
                    ? children.optJSONObject(idx) : null;
            if (child == null) {
                child = findChildByKeyOrType(children, seg);
            }
            if (child == null) return null;
            cur = child;
        }
        // 最后一段：子容器（节点本身）
        JSONArray children = cur.optJSONArray("children");
        return children;
    }

    /** 替换容器的 children（add 指定 index 时重建） */
    private void replaceContainerChildren(JSONObject rootNode, String path, JSONArray newChildren) {
        try {
            if (rootNode == null || newChildren == null) return;
            if (path == null || path.isEmpty() || "root".equals(path) || "/".equals(path)) {
                rootNode.put("children", newChildren);
                return;
            }
            String[] segs = path.split("/");
            int start = 0;
            if (segs.length > 0 && (segs[0].isEmpty() || "root".equals(segs[0]))) start = 1;
            JSONObject cur = rootNode;
            for (int i = start; i < segs.length - 1; i++) {
                String seg = segs[i];
                if (seg.isEmpty()) continue;
                JSONArray children = cur.optJSONArray("children");
                cur = resolveChild(children, seg);
                if (cur == null) return;
            }
            cur.put("children", newChildren);
        } catch (Exception ignored) {
        }
    }

    /** 按 path/key 定位节点；key 优先，其次 path */
    private JSONObject findNode(JSONObject rootNode, String key, String path) {
        if (rootNode == null) return null;
        if (key != null && !key.isEmpty()) {
            JSONObject byKey = findNodeByKey(rootNode, key, 0);
            if (byKey != null) return byKey;
        }
        if (path != null && !path.isEmpty()) {
            return findNodeByPath(rootNode, path);
        }
        return null;
    }

    private JSONObject findNodeByKey(JSONObject node, String key, int depth) {
        if (node == null || depth > 20) return null;
        if (key.equals(node.optString("key", ""))) return node;
        JSONArray children = node.optJSONArray("children");
        if (children != null) {
            for (int i = 0; i < children.length(); i++) {
                JSONObject c = children.optJSONObject(i);
                JSONObject found = findNodeByKey(c, key, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private JSONObject findNodeByPath(JSONObject rootNode, String path) {
        String[] segs = path.split("/");
        int start = 0;
        if (segs.length > 0 && (segs[0].isEmpty() || "root".equals(segs[0]))) start = 1;
        JSONObject cur = rootNode;
        for (int i = start; i < segs.length; i++) {
            String seg = segs[i];
            if (seg.isEmpty()) continue;
            JSONArray children = cur.optJSONArray("children");
            if (children == null) return null;
            int idx = parseInt(seg, -1);
            JSONObject c = idx >= 0 && idx < children.length()
                    ? children.optJSONObject(idx) : findChildByKeyOrType(children, seg);
            if (c == null) return null;
            cur = c;
        }
        return cur;
    }

    /** 定位目标节点的父容器节点（按 key 优先、path 其次），返回 null=未找到或根节点 */
    private JSONObject findParent(JSONObject rootNode, String key, String path) {
        if (rootNode == null) return null;
        // 用 path 定位父：取 path 的前段找到父容器
        if (path != null && !path.isEmpty()) {
            String[] segs = path.split("/");
            int start = 0;
            if (segs.length > 0 && (segs[0].isEmpty() || "root".equals(segs[0]))) start = 1;
            JSONObject cur = rootNode;
            for (int i = start; i < segs.length - 1; i++) {
                String seg = segs[i];
                if (seg.isEmpty()) continue;
                JSONArray children = cur.optJSONArray("children");
                if (children == null) return null;
                int idx = parseInt(seg, -1);
                JSONObject c = idx >= 0 && idx < children.length()
                        ? children.optJSONObject(idx) : findChildByKeyOrType(children, seg);
                if (c == null) return null;
                cur = c;
            }
            return cur;
        }
        // 按 key 递归查找父
        if (key != null && !key.isEmpty()) {
            return findParentByKey(rootNode, key, 0);
        }
        return null;
    }

    private JSONObject findParentByKey(JSONObject node, String key, int depth) {
        if (node == null || depth > 20) return null;
        JSONArray children = node.optJSONArray("children");
        if (children != null) {
            for (int i = 0; i < children.length(); i++) {
                JSONObject c = children.optJSONObject(i);
                if (c != null && key.equals(c.optString("key", ""))) return node;
                JSONObject found = findParentByKey(c, key, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private JSONObject resolveChild(JSONArray children, String seg) {
        int idx = parseInt(seg, -1);
        if (idx >= 0 && idx < children.length()) return children.optJSONObject(idx);
        return findChildByKeyOrType(children, seg);
    }

    private JSONObject findChildByKeyOrType(JSONArray children, String seg) {
        for (int i = 0; i < children.length(); i++) {
            JSONObject c = children.optJSONObject(i);
            if (c == null) continue;
            if (seg.equals(c.optString("key", "")) || seg.equals(c.optString("type", ""))) {
                return c;
            }
        }
        return null;
    }

    private int countNodes(JSONObject node) {
        if (node == null) return 0;
        int n = 1;
        JSONArray children = node.optJSONArray("children");
        if (children != null) {
            for (int i = 0; i < children.length(); i++) {
                JSONObject c = children.optJSONObject(i);
                n += countNodes(c);
            }
        }
        return n;
    }

    private AIToolResult ok(String action, String componentId, String msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", "success");
        m.put("action", action);
        m.put("component_id", componentId);
        m.put("message", msg);
        return AIToolResult.success(m);
    }

    private String str(Object v, String def) {
        return v == null ? def : String.valueOf(v);
    }

    private int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }
}

package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.chat.component.LayoutCanvasConstraints;
import com.oilquiz.app.ai.tool.annotation.Tool;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * action=validate 提交前 dry-run 校验（layout: 完整布局 JSON，不渲染；返回合法性与节点级问题列表）
 * action=rebuild 强制重渲染画布
 *
 * 说明：
 * - path 格式："/children/0/children/2" 或 "root/children/0"，空或"root"=根；
 *   兼容用节点 key 定位（容器节点带 key 时，用 key 匹配）。
 * - 输入控件值在重渲染时回填（保留用户已输入的内容）。
 * - 每次编辑后画布即时刷新；get_result 提示用户操作完成。
 */
@Tool(value = "layout_editor", category = "ui")
public class LayoutEditorTool implements AITool {

    private static final String TAG = "LayoutEditorTool";
    private final Context context;

    // 控件类型白名单与"输入控件必须带 key"规则统一由 LayoutCanvasConstraints 持有
    // （类型清单以 NativeLayoutRenderer 为唯一权威），本类不再自带一份，避免两处漂移。

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
                + "动作:set(整体替换布局)/add(追加子节点)/patch(修改或删除节点)/get(查看当前布局)/validate(dry-run校验布局不渲染)/resize(改画布窗口宽高)/pin(把当前画布固定为永久组件类型)/rebuild(强制重渲染)。"
                + "前置:先用 ui_component(action=create, component_type=layout_canvas, layout={完整含输入控件的布局}) 创建画布拿到 component_id。"
                + "**注意:create 时就要带完整 layout（含带 key 的 input/select/switch、带 action 的 button），不要只创建空画布**。"
                + "**重要**:①每个输入控件必须带 key（input/select/switch/checkbox_group/date/number 都要），否则值无法收集;"
                + "②button 必须带 action;③全程用同一个 component_id，不要反复重建画布;"
                + "④set 一次性放完整布局，add 放**单个控件节点**（勿传含 children 的容器）;"
                + "⑤提交复杂布局前先 action=validate 预检（dry-run，报错精确到节点路径），通过后再 set/add。"
                + "⑥**画布硬约束（代码级强制，违反会被拒绝且画布保持原样）**：单画布≤150 节点、"
                + "单容器≤40 子节点、嵌套≤8 层、layout JSON≤200KB、key 全局唯一（重复=值互相覆盖，拒绝）、"
                + "同一画布≤5 次编辑/秒（改动请合并成一次 set）；输入控件缺 key 会自动补并回告实际键名，"
                + "button 缺 action 只告警。"
                + "⑦**画布尺寸可由你指定**：create 时在 props/顶层放 canvas_width/canvas_height/canvas_max_height，"
                + "或事后 layout_editor(action=resize, component_id, width=420, height=\"0.7\"|auto) 调整；"
                + "取值支持 dp 数字、比例(\"0.8\"/\"80%\")、full/half/auto(auto=高度随内容自适应)，越界会被夹取并回告。"
                + "⑧**固定复用（默认临时，固化要显式说）**：画布与 pin 注册默认都不落盘。"
                + "layout_editor(action=pin, component_id, name=类型名) 把当前布局注册成组件类型——"
                + "**默认临时**（仅本次会话可复用）；要跨重启长期保留，显式传 persist=true "
                + "（写入 ui_component register_type 表）。"
                + "之后 ui_component(action=create, component_type=类型名) 直接打开，不必每次重新生成；"
                + "用户也能在「智能体 · 工具」页点「▶ 打开」直接使用。"
                + "每次编辑后画布即时刷新，控件值自动回填。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作: set/add/patch/get/validate/resize/pin/rebuild");
        params.put("component_id", "画布组件ID（layout_canvas 创建返回的 component_id），validate 可不传");
        params.put("name", "pin 用：固定后的组件类型名（字母/数字/下划线），同名覆盖");
        params.put("description", "pin 用：该类型的用途说明（省略则自动生成）");
        params.put("persist", "pin 用：true=永久固化（写入 ui_component_types.json，跨重启保留）；"
                + "默认 false=临时类型（仅本次会话可复用，不写盘）");
        params.put("width", "resize 用：画布窗口宽，dp 数字(如 420)/比例(\"0.8\"/\"80%\")/full/half，省略=保持当前");
        params.put("height", "resize 用：内容区高，dp 数字/比例/full/half/auto(随内容自适应)，省略=保持当前");
        params.put("max_height", "resize 用：内容区高度上限（dp/比例/auto 模式下用作封顶），省略=保持当前");
        params.put("layout", "set/validate 用：完整布局 JSON（如 {\"root\":{\"type\":\"column\",\"children\":[...]}} 或单节点 {\"type\":\"column\"}）");
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
            // 维度一 P0-1：validate 为 dry-run 预检，不依赖已存在画布，独立处理
            if ("validate".equals(action)) {
                return validateLayout(parameters);
            }
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

            // 约束层开关（ai_chat_prefs.layout_canvas_constraints，默认开）
            final Context rctx = rulesContext();
            final boolean strict = rctx != null && LayoutCanvasConstraints.enabled(rctx);

            switch (action) {
                case "set": {
                    JSONObject layout = parseLayout(parameters);
                    if (layout == null) {
                        return AIToolResult.fail("缺少参数: layout（需传完整布局 JSON 或根节点）");
                    }
                    if (strict) {
                        String limited = LayoutCanvasConstraints.throttleEdit(componentId);
                        if (limited != null) {
                            return AIToolResult.fail(limited);
                        }
                    }
                    // 约束在传入的候选树上校验（可就地补 key）；不通过时画布保持原样
                    LayoutCanvasConstraints.Report rep =
                            strict ? LayoutCanvasConstraints.inspect(rctx, layout) : null;
                    if (rep != null && !rep.ok()) {
                        LayoutCanvasConstraints.logRejected("set", componentId, rep);
                        return AIToolResult.fail(rep.describe());
                    }
                    synchronized (session.layoutLock) {
                        session.layout = layout;
                    }
                    if (strict) {
                        LayoutCanvasConstraints.recordEdit(componentId);
                    }
                    apply(session);
                    return ok(action, componentId, "布局已整体替换，共 " + countNodes(layout)
                            + " 个节点" + extra(rep));
                }
                case "add": {
                    // 在 session.layout 的副本上试装，校验通过才整体换入（失败不破坏现状）
                    JSONObject candidate;
                    synchronized (session.layoutLock) {
                        candidate = strict ? LayoutCanvasConstraints.copyOf(session.layout)
                                : session.layout;
                    }
                    if (candidate == null) {
                        return AIToolResult.fail("画布布局为空，请先 set 一次或 create 时传 layout");
                    }
                    // 兼容多种参数名：node/layout/item/children/根节点对象（Agent 常用 layout 传节点）
                    JSONObject node = parseNode(parameters);
                    if (node == null) {
                        return AIToolResult.fail("缺少参数: node（要追加的子节点 JSON，也可用 layout/item 传单节点）");
                    }
                    if (strict) {
                        String limited = LayoutCanvasConstraints.throttleEdit(componentId);
                        if (limited != null) {
                            return AIToolResult.fail(limited);
                        }
                    }
                    String containerPath = str(parameters.get("container"), "root");
                    if (containerPath.isEmpty()) containerPath = "root";
                    int index = parameters.get("index") == null ? -1
                            : parseInt(parameters.get("index").toString(), -1);
                    JSONObject rootNode = resolveRoot(candidate);
                    JSONArray container = findContainerChildren(rootNode, containerPath);
                    if (container == null) {
                        return AIToolResult.fail("容器不存在: " + containerPath
                                + "（先用 layout_editor(action=get) 查看当前结构再定位）");
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
                    LayoutCanvasConstraints.Report rep =
                            strict ? LayoutCanvasConstraints.inspect(rctx, candidate) : null;
                    if (rep != null && !rep.ok()) {
                        LayoutCanvasConstraints.logRejected("add", componentId, rep);
                        return AIToolResult.fail(rep.describe() + "\n（本次 add 未生效，节点数预算：上限 "
                                + LayoutCanvasConstraints.MAX_NODES + " 节点 / 单容器 "
                                + LayoutCanvasConstraints.MAX_CHILDREN_PER_CONTAINER + " 子项 / 深度 "
                                + LayoutCanvasConstraints.MAX_DEPTH + " 层）");
                    }
                    synchronized (session.layoutLock) {
                        session.layout = candidate;
                    }
                    if (strict) {
                        LayoutCanvasConstraints.recordEdit(componentId);
                    }
                    apply(session);
                    return ok(action, componentId, "已向 " + containerPath + " 追加子节点，当前共 "
                            + countNodes(candidate) + " 个节点" + extra(rep));
                }
                case "patch": {
                    JSONObject candidate;
                    synchronized (session.layoutLock) {
                        candidate = strict ? LayoutCanvasConstraints.copyOf(session.layout)
                                : session.layout;
                    }
                    if (candidate == null) {
                        return AIToolResult.fail("画布布局为空，请先 set 或 create 时传 layout");
                    }
                    if (strict) {
                        String limited = LayoutCanvasConstraints.throttleEdit(componentId);
                        if (limited != null) {
                            return AIToolResult.fail(limited);
                        }
                    }
                    boolean remove = parameters.get("remove") != null
                            && Boolean.parseBoolean(String.valueOf(parameters.get("remove")));
                    String key = str(parameters.get("key"), "");
                    String path = str(parameters.get("path"), "");
                    JSONObject props = parseProps(parameters);
                    if (strict && remove && (key.isEmpty() && path.isEmpty())) {
                        return AIToolResult.fail("删除节点必须指定 key 或 path（避免误清空画布）");
                    }
                    JSONObject rootNode = resolveRoot(candidate);
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
                        synchronized (session.layoutLock) {
                            session.layout = candidate;
                        }
                        if (strict) {
                            LayoutCanvasConstraints.recordEdit(componentId);
                        }
                        apply(session);
                        return ok(action, componentId, "已删除节点（key=" + key + " path=" + path + "）");
                    }
                    JSONObject target = findNode(rootNode, key, path);
                    if (target == null) {
                        return AIToolResult.fail("未找到目标节点（key=" + key + " path=" + path
                                + "）；先用 layout_editor(action=get) 确认 key/path");
                    }
                    if (props != null) {
                        java.util.Iterator<String> ik = props.keys();
                        while (ik.hasNext()) {
                            String pk = ik.next();
                            target.put(pk, props.get(pk));
                        }
                    }
                    // patch 改的可能是 type/key/children，同样要整树复校（key 重复、深度、类型）
                    LayoutCanvasConstraints.Report rep =
                            strict ? LayoutCanvasConstraints.inspect(rctx, candidate) : null;
                    if (rep != null && !rep.ok()) {
                        LayoutCanvasConstraints.logRejected("patch", componentId, rep);
                        return AIToolResult.fail("patch 后布局不合法，已回滚（画布保持原样）:\n"
                                + rep.describe());
                    }
                    synchronized (session.layoutLock) {
                        session.layout = candidate;
                    }
                    if (strict) {
                        LayoutCanvasConstraints.recordEdit(componentId);
                    }
                    apply(session);
                    return ok(action, componentId, "已更新节点（key=" + key + " path=" + path + "）"
                            + extra(rep));
                }
                case "get": {
                    // 取副本：brief 序列化可能在别的线程，直接引用活树会和下一次编辑并发读写
                    JSONObject snapshot;
                    int nodes;
                    synchronized (session.layoutLock) {
                        JSONObject current = session.layout != null ? session.layout : new JSONObject();
                        snapshot = strict ? LayoutCanvasConstraints.copyOf(current) : current;
                        if (snapshot == null) snapshot = current;
                        nodes = countNodes(snapshot);
                    }
                    JSONObject brief = new JSONObject();
                    brief.put("component_id", componentId);
                    brief.put("nodes", nodes);
                    if (strict) {
                        brief.put("node_budget", nodes + "/" + LayoutCanvasConstraints.MAX_NODES);
                    }
                    brief.put("layout", snapshot);
                    return AIToolResult.success(brief);
                }
                case "resize": {
                    // 模型自选画布窗口尺寸：只改弹窗宽高，不重建弹窗、不丢用户已输入的值
                    Object wRaw = parameters.get("width");
                    Object hRaw = parameters.get("height");
                    Object mhRaw = parameters.get("max_height");
                    if (wRaw == null && hRaw == null && mhRaw == null) {
                        return AIToolResult.fail("缺少参数: width / height / max_height（任一即可），"
                                + "取值支持 dp 数字(如 420)、比例(\"0.8\"/\"80%\")、关键字"
                                + "(full/half/auto=高度随内容自适应)");
                    }
                    JSONObject sizeProps = new JSONObject();
                    if (wRaw != null) sizeProps.put("canvas_width", wRaw);
                    if (hRaw != null) sizeProps.put("canvas_height", hRaw);
                    if (mhRaw != null) sizeProps.put("canvas_max_height", mhRaw);
                    LayoutCanvasConstraints.CanvasSize parsed =
                            LayoutCanvasConstraints.resolveCanvasSize(sizeProps, rctx);
                    LayoutCanvasConstraints.CanvasSize cur = session.size;
                    LayoutCanvasConstraints.CanvasSize next = new LayoutCanvasConstraints.CanvasSize();
                    next.widthDp = wRaw != null ? parsed.widthDp
                            : (cur != null ? cur.widthDp : 0);
                    boolean touchH = hRaw != null;
                    next.wrapHeight = touchH ? parsed.wrapHeight : (cur != null && cur.wrapHeight);
                    next.heightDp = touchH ? parsed.heightDp : (cur != null ? cur.heightDp : 0);
                    next.maxHeightDp = (mhRaw != null || touchH)
                            ? (parsed.maxHeightDp > 0 ? parsed.maxHeightDp
                                    : (cur != null ? cur.maxHeightDp : 0))
                            : (cur != null ? cur.maxHeightDp : 0);
                    next.specified = true;
                    session.size = next;
                    if (session.applySize != null) {
                        session.applySize.accept(session);
                    } else {
                        return AIToolResult.fail("该画布宿主不支持动态改尺寸（非 layout_canvas 弹窗）");
                    }
                    return ok(action, componentId, "画布尺寸已更新：宽 "
                            + (next.widthDp > 0 ? next.widthDp + "dp" : "默认")
                            + "，高 " + (next.wrapHeight ? "随内容自适应"
                                    : (next.heightDp > 0 ? next.heightDp + "dp" : "默认"))
                            + (next.maxHeightDp > 0 ? "（上限 " + next.maxHeightDp + "dp）" : "")
                            + (parsed.note == null || parsed.note.isEmpty()
                                    ? "" : "\n取值纠正: " + parsed.note));
                }
                case "pin": {
                    // 把当前画布"固定"成一个可复用的组件类型：写进 ui_component register_type
                    // 那张已有的持久化表（files/ui_component_types.json），不新建第四套注册表。
                    String pinName = str(parameters.get("name"), "");
                    String pinDesc = str(parameters.get("description"), "");
                    if (pinName.isEmpty()) {
                        return AIToolResult.fail("缺少参数: name（固定后的组件类型名，仅字母/数字/下划线）");
                    }
                    JSONObject currentLayout;
                    synchronized (session.layoutLock) {
                        currentLayout = session.layout;
                    }
                    if (currentLayout == null) {
                        return AIToolResult.fail("画布内容为空，无法固定");
                    }
                    Context rctx2 = rulesContext();
                    if (rctx2 == null) {
                        return AIToolResult.fail("无法访问组件类型注册表（context 为空）");
                    }
                    // 默认**临时**（只进内存，不写盘）：固化与否由模型/用户显式要求
                    // （persist=true）。画布与其产物的默认档保持一致——都不擅自落盘。
                    boolean persistPin = parameters.get("persist") != null
                            && Boolean.parseBoolean(String.valueOf(parameters.get("persist")));
                    JSONObject typeDef = new JSONObject();
                    typeDef.put("name", pinName);
                    typeDef.put("description", pinDesc.isEmpty()
                            ? "固定画布（layout_editor pin，来自画布 " + componentId + "）"
                            : pinDesc);
                    JSONObject render = new JSONObject();
                    render.put("layout", currentLayout);
                    typeDef.put("render", render);
                    java.util.List<String> problems =
                            com.oilquiz.app.ai.tool.UIComponentTypeRegistry.getInstance(rctx2)
                                    .registerType(typeDef, persistPin);
                    if (problems != null && !problems.isEmpty()) {
                        return AIToolResult.fail("固定失败:\n  - "
                                + android.text.TextUtils.join("\n  - ", problems));
                    }
                    Map<String, Object> pinned = new HashMap<>();
                    pinned.put("status", "success");
                    pinned.put("action", "pin");
                    pinned.put("type_name", pinName);
                    pinned.put("component_id", componentId);
                    pinned.put("mode", persistPin ? "persistent" : "temporary");
                    pinned.put("nodes", countNodes(currentLayout));
                    pinned.put("message", "画布已注册为组件类型「" + pinName + "」"
                            + (persistPin
                                ? "（**永久**，写入 ui_component_types.json，跨重启保留）。"
                                  + "下次直接 ui_component(action=create, component_type=" + pinName
                                  + ") 打开同一套界面，无需重新生成；用户也可在「智能体 · 工具」页点「▶ 打开」直接使用。"
                                : "（**临时**，仅本次会话内可复用，重启或被「清除全部临时注册」后消失）。"
                                  + "本次会话内可用 ui_component(action=create, component_type=" + pinName
                                  + ") 直接打开；确认这个界面要长期保留时，再调一次 "
                                  + "layout_editor(action=pin, component_id=..., name=" + pinName
                                  + ", persist=true) 固化为永久类型。")
                            + "需要改动时：用该类型 create 出新 component_id 后照常 layout_editor 编辑，"
                            + "再 pin 回同名类型即可覆盖。");
                    return AIToolResult.success(pinned);
                }
                case "rebuild": {
                    if (strict) {
                        String limited = LayoutCanvasConstraints.throttleEdit(componentId);
                        if (limited != null) {
                            return AIToolResult.fail(limited);
                        }
                    }
                    apply(session);
                    if (strict) {
                        LayoutCanvasConstraints.recordEdit(componentId);
                    }
                    return ok(action, componentId, "画布已强制重渲染");
                }
                default:
                    return AIToolResult.fail("未知操作: " + action
                            + "（支持 set/add/patch/get/validate/resize/rebuild）");
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

    // ==================== 维度一 P0-1/2：dry-run 预检 ====================

    /**
     * 提交前 dry-run 校验：不渲染，返回布局合法性与问题列表（精确到节点路径）。
     * 校验项：type 合法性、children 结构、输入控件 key、button action。
     * 未知 type 且节点自带 layout（现场定义）或 use 引用（模板）时放行。
     */
    /**
     * 提交前 dry-run 预检：与 set/add/patch 走<b>同一套</b>规则
     * （{@link LayoutCanvasConstraints#inspect}），只是不落到画布上。
     * 校验对象是副本，dry-run 不会改写调用方传入的 layout。
     */
    private AIToolResult validateLayout(Map<String, Object> parameters) {
        JSONObject layout = parseLayout(parameters);
        if (layout == null) {
            return AIToolResult.fail("缺少参数: layout（需传完整布局 JSON 或根节点）");
        }
        JSONObject probe = LayoutCanvasConstraints.copyOf(layout);
        LayoutCanvasConstraints.Report rep =
                LayoutCanvasConstraints.inspect(rulesContext(), probe);
        Map<String, Object> result = new HashMap<>();
        result.put("valid", rep.ok());
        result.put("nodeCount", rep.nodes);
        result.put("maxDepth", rep.maxDepth);
        result.put("budget", rep.nodes + "/" + LayoutCanvasConstraints.MAX_NODES
                + " 节点，单容器 " + LayoutCanvasConstraints.MAX_CHILDREN_PER_CONTAINER
                + "，深度 " + LayoutCanvasConstraints.MAX_DEPTH);
        result.put("problems", rep.errors);
        result.put("warnings", rep.warnings);
        result.put("will_auto_fix", rep.repairs);
        result.put("message", rep.ok()
                ? "布局校验通过（dry-run，未渲染）"
                        + (rep.repairs.isEmpty() ? "" : "，提交时会自动补齐: " + rep.repairs)
                        + (rep.warnings.isEmpty() ? "" : "；另有提示: " + rep.warnings)
                : "布局校验未通过（dry-run，未渲染）:\n" + rep.describe());
        return AIToolResult.success(result);
    }

    /** context 可能为空（无参构造仅用于注解扫描）：约束层用全局 appContext 兜底 */
    private Context rulesContext() {
        return context != null ? context : com.oilquiz.app.ai.spi.AppServices.appContext();
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

    /**
     * 约束层"放行但有改动"的附注：自动补的 key、button 缺 action 等一定要回给模型，
     * 否则模型按自己原本的键名取值会取空。
     */
    private String extra(LayoutCanvasConstraints.Report rep) {
        if (rep == null || (rep.repairs.isEmpty() && rep.warnings.isEmpty())) {
            return "";
        }
        return "\n" + rep.describe();
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

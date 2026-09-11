package com.oilquiz.app.ai.tool;

import com.oilquiz.app.ai.importing.QuestionImportTaskManager;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 题库导入工具（决策回传）—— 把用户在智能体创建的 ui_component 上的选择回传给导入管线。
 * <p>
 * 配合 {@code import_status}：当返回中出现 {@code pendingDecision} 时，智能体应先用
 * {@code ui_component(action=create, component_type=choice, ...)} 与用户交互、{@code get_result}
 * 取值，再调用本工具把选择回传；回传后导入线程继续，继续用 import_status 轮询直到 DONE/ERROR/CANCELLED。
 */
public class ImportDecideTool implements AITool {

    private android.content.Context context;

    public ImportDecideTool() {
    }

    public ImportDecideTool(android.content.Context context) {
        this.context = context;
    }

    @Override
    public String getName() {
        return "import_decide";
    }

    @Override
    public String getDescription() {
        return "题库导入（决策回传）：当 import_status 返回 pendingDecision（待确认的决策点）时，"
                + "智能体用 ui_component 与用户交互后，用本工具把用户选择回传给导入管线，导入才会继续。"
                + "参数：taskId 必填；decisionId 必填（待确认列表，须与 import_status 的 pendingDecision.decisionId 一致）；"
                + "selected 必填或 choice 二选一（selected=用户选择的选项文本，如 ui_component get_result 返回值；"
                + "choice=选项下标，0 起；取消=最后一项）。"
                + "决策点=mapping 时可选传 mapping（修改后的字段映射 标准字段→源列名）。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("taskId", "导入任务ID（import_start 返回，必填）");
        params.put("decisionId", "待确认决策点ID（import_status 的 pendingDecision.decisionId，必填）");
        params.put("selected", "用户选择的选项文本（ui_component get_result 的返回；取消传\"取消导入\"或\"cancelled\"）");
        params.put("choice", "用户选择的选项下标（int，0 起；与 selected 二选一；取消=最后一项）");
        params.put("mapping", "字段映射修改（JSON 对象，标准字段→源列名；仅决策点=mapping 时可传）");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        Object taskId = parameters.get("taskId");
        if (taskId == null) {
            return AIToolResult.fail("缺少必填参数 taskId（import_start 返回的任务ID）");
        }
        Object decisionId = parameters.get("decisionId");
        if (decisionId == null || String.valueOf(decisionId).trim().isEmpty()) {
            return AIToolResult.fail("缺少必填参数 decisionId（import_status 的 pendingDecision.decisionId）");
        }
        int choice = -1;
        if (parameters.get("choice") != null) {
            try {
                choice = (int) Double.parseDouble(String.valueOf(parameters.get("choice")));
            } catch (Exception e) {
                return AIToolResult.fail("参数 choice 非法（应为整数下标，0 起）: " + parameters.get("choice"));
            }
        }
        String selected = parameters.get("selected") != null
                ? String.valueOf(parameters.get("selected")) : null;
        if (choice < 0 && (selected == null || selected.trim().isEmpty())) {
            return AIToolResult.fail("缺少选择：请传 selected=用户选择的选项文本，或 choice=选项下标（0 起）");
        }
        JSONObject mapping = toJsonObject(parameters.get("mapping"));

        String json = QuestionImportTaskManager.getInstance()
                .submitDecision(String.valueOf(taskId), String.valueOf(decisionId), choice, selected, mapping);
        try {
            JSONObject o = new JSONObject(json);
            if (o.optBoolean("ok", false)) {
                return AIToolResult.success(json);
            }
            return AIToolResult.fail(o.optString("message", json));
        } catch (Exception e) {
            return AIToolResult.success(json);
        }
    }

    /** 把 mapping 参数（JSON 字符串 / Map）转为 JSONObject；非法返回 null */
    private static JSONObject toJsonObject(Object raw) {
        if (raw == null) return null;
        try {
            if (raw instanceof JSONObject) return (JSONObject) raw;
            if (raw instanceof Map) {
                JSONObject o = new JSONObject();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                    o.put(String.valueOf(e.getKey()), e.getValue());
                }
                return o;
            }
            String s = String.valueOf(raw).trim();
            if (s.isEmpty()) return null;
            if (!s.startsWith("{")) return null;
            return new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }
}

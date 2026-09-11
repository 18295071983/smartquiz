package com.oilquiz.app.ai.tool;

import com.oilquiz.app.ai.importing.QuestionImportTaskManager;

import java.util.HashMap;
import java.util.Map;

/**
 * 题库导入工具（状态查询）—— 配合 {@code import_start} 轮询任务进度与结果。
 * <p>
 * 返回 JSON：taskId / status（RUNNING|DONE|ERROR|CANCELLED|NOT_FOUND）/
 * stage（start|sheet|mapping|parse|fill|ingest|done|error）/
 * 进度 current/total / 结果 imported/duplicated/failed/totalRows。
 */
public class ImportStatusTool implements AITool {

    public ImportStatusTool() {
    }

    public ImportStatusTool(android.content.Context context) {
    }

    @Override
    public String getName() {
        return "import_status";
    }

    @Override
    public String getDescription() {
        return "题库导入（状态查询）：查询 import_start 返回的 taskId 的导入进度与结果。"
                + "返回 status=RUNNING 表示仍在导入（可隔数秒再查）；status=DONE 表示完成并含新增/重复/失败统计；"
                + "status=ERROR 表示失败（含 error 原因）；status=CANCELLED 表示已取消。"
                + "参数：taskId 必填（import_start 返回的任务ID）。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("taskId", "导入任务ID（import_start 返回，必填）");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        Object taskId = parameters.get("taskId");
        if (taskId == null) {
            return AIToolResult.fail("缺少必填参数 taskId（import_start 返回的任务ID）");
        }
        String json = QuestionImportTaskManager.getInstance().status(String.valueOf(taskId));
        return AIToolResult.success(json);
    }
}

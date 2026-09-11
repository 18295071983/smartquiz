package com.oilquiz.app.ai.tool;

import com.oilquiz.app.ai.importing.QuestionImportTaskManager;

import java.util.HashMap;
import java.util.Map;

/**
 * 题库导入工具（取消）—— 停止进行中的导入任务。
 */
public class ImportCancelTool implements AITool {

    public ImportCancelTool() {
    }

    public ImportCancelTool(android.content.Context context) {
    }

    @Override
    public String getName() {
        return "import_cancel";
    }

    @Override
    public String getDescription() {
        return "题库导入（取消）：取消 import_start 启动的导入任务（参数 taskId 必填）。"
                + "取消后任务状态变为 CANCELLED，已入库的题目不会回滚。";
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
        String json = QuestionImportTaskManager.getInstance().cancel(String.valueOf(taskId));
        return AIToolResult.success(json);
    }
}

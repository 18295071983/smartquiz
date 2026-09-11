package com.oilquiz.app.ai.tool;

import com.oilquiz.app.ai.importing.QuestionImportTaskManager;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 题库导入工具（启动）—— 智能体驱动 AI 导入的第一环。
 * <p>
 * 异步启动 v2 智能导入管线（字段映射→解析→填充→入库，自动决策不弹窗），
 * 立即返回 taskId；随后用 {@code import_status} 轮询进度/结果，
 * 用 {@code import_cancel} 取消。
 */
public class ImportStartTool implements AITool {

    public ImportStartTool() {
    }

    public ImportStartTool(android.content.Context context) {
    }

    @Override
    public String getName() {
        return "import_start";
    }

    @Override
    public String getDescription() {
        return "题库导入（启动）：异步启动智能导入管线，把题库文件（Excel/CSV/JSON 等）解析为题目并入库。"
                + "支持字段自动映射、AI 智能填充缺失字段、去重、多题型。"
                + "用法：先调本工具启动（返回 taskId），再用 import_status 查询 taskId 进度，完成后会返回新增/重复/失败统计；"
                + "需要停止时调 import_cancel。导入耗时较长（数十秒到数分钟），不要重复启动同一文件。"
                + "参数：filePath 必填（完整文件路径），sheetIndex 可选（Excel 工作表索引，-1=自动检测，默认 -1），"
                + "docHint 可选（题库说明/字段约定，帮助映射），fillMissing 可选（是否 AI 补缺失字段，默认 true），"
                + "skipIncomplete 可选（是否跳过缺字段的行，默认 false），questionType 可选（强制题型，如\"单选题\"）。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("filePath", "题库文件完整路径（必填）");
        params.put("sheetIndex", "Excel 工作表索引（int，-1=自动检测，默认 -1）");
        params.put("docHint", "题库说明/字段约定文本（可选）");
        params.put("fillMissing", "是否 AI 填充缺失字段（bool，默认 true）");
        params.put("skipIncomplete", "是否跳过缺字段行（bool，默认 false）");
        params.put("questionType", "强制题型（可选，如\"单选题\"）");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            Object pathObj = parameters.get("filePath");
            if (pathObj == null) {
                return AIToolResult.fail("缺少必填参数 filePath（题库文件完整路径）");
            }
            File file = new File(String.valueOf(pathObj));
            if (!file.exists()) {
                return AIToolResult.fail("文件不存在: " + file.getAbsolutePath()
                        + "（需先通过应用内文件选择获取可访问路径，或在公共目录 OilQuiz 下）");
            }
            int sheetIndex = -1;
            if (parameters.get("sheetIndex") != null) {
                sheetIndex = (int) Double.parseDouble(String.valueOf(parameters.get("sheetIndex")));
            }
            String docHint = parameters.get("docHint") != null
                    ? String.valueOf(parameters.get("docHint")) : null;
            boolean fillMissing = parameters.get("fillMissing") == null
                    || Boolean.parseBoolean(String.valueOf(parameters.get("fillMissing")));
            boolean skipIncomplete = parameters.get("skipIncomplete") != null
                    && Boolean.parseBoolean(String.valueOf(parameters.get("skipIncomplete")));
            String questionType = parameters.get("questionType") != null
                    ? String.valueOf(parameters.get("questionType")) : null;

            String taskId = QuestionImportTaskManager.getInstance()
                    .start(file, sheetIndex, docHint, fillMissing, skipIncomplete, questionType);
            return AIToolResult.success(
                    "{\"taskId\":\"" + taskId + "\",\"status\":\"RUNNING\",\"message\":\"导入已启动，请用 import_status 查询进度\"}");
        } catch (Exception e) {
            return AIToolResult.fail("启动导入失败: " + e.getMessage());
        }
    }
}

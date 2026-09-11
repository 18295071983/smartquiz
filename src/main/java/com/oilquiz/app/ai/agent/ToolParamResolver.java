package com.oilquiz.app.ai.agent;

import java.util.List;
import java.util.Map;

/**
 * 工具参数解析与补参决策器（全局可复用）。
 *
 * 从 AIChatActivity 工具补参链路抽取的纯判定/填充逻辑：已有值判定、时间/日期参数自动填充、
 * 路径类参数识别、选择器类型推断。不依赖 UI，可在任意工具调用场景复用。
 */
public class ToolParamResolver {

    private ToolParamResolver() {}

    /** 检查参数是否已有非空值 */
    public static boolean hasParamValue(Map<String, Object> params, String key) {
        if (params == null) return false;
        Object v = params.get(key);
        if (v == null) return false;
        if (v instanceof String) return !((String) v).trim().isEmpty();
        return true;
    }

    /** 补充时间类参数（time/datetime/date，仅当缺失时） */
    public static void fillTimeParams(List<ToolErrorRecovery.MissingParam> missing, Map<String, Object> params) {
        for (ToolErrorRecovery.MissingParam mp : missing) {
            if (mp == null || mp.key == null) continue;
            if ("time".equals(mp.key) || "datetime".equals(mp.key)) {
                if (!hasParamValue(params, mp.key)) {
                    params.put(mp.key, ToolContextProvider.getCurrentDateTime());
                }
            } else if ("date".equals(mp.key)) {
                if (!hasParamValue(params, "date")) {
                    params.put("date", ToolContextProvider.getCurrentDate());
                }
            }
        }
    }

    /** 参数键/描述是否暗示路径类（走文件选择器） */
    public static boolean isPathLikeParam(String key, String desc) {
        if (key == null) return false;
        String k = key.toLowerCase();
        if (k.contains("file_path") || k.contains("image_path") || k.contains("directory_path")
                || k.contains("source_path") || k.contains("target_path") || k.contains("output_path")
                || k.contains("save_path") || k.equals("path")
                || k.contains("folder") || k.contains("dir_path")) {
            return true;
        }
        if (k.equals("file_name") && desc != null) {
            String d = desc.toLowerCase();
            if (d.contains("路径") || d.contains("目录") || d.contains("文件夹")) return true;
        }
        if (desc != null) {
            String d = desc.toLowerCase();
            if (d.contains("路径") || d.contains("文件路径") || d.contains("目录路径")
                    || d.contains("文件夹") || d.contains("图片路径")) {
                return true;
            }
        }
        return false;
    }

    /** 根据参数键名和描述推断选择器类型（文件/图片/目录） */
    public static ToolGuideFlow.GuideStep.StepType deducePickerType(String key, String desc) {
        if (key != null) {
            String k = key.toLowerCase();
            if (k.contains("image_path") || k.contains("picture") || k.contains("photo") || k.contains("img")) {
                return ToolGuideFlow.GuideStep.StepType.IMAGE_PICKER;
            }
            if (k.contains("directory") || k.contains("folder") || k.contains("dir_path")) {
                return ToolGuideFlow.GuideStep.StepType.DIRECTORY_PICKER;
            }
        }
        if (desc != null) {
            String d = desc.toLowerCase();
            if (d.contains("图片") || d.contains("照片")) {
                return ToolGuideFlow.GuideStep.StepType.IMAGE_PICKER;
            }
            if (d.contains("目录") || d.contains("文件夹")) {
                return ToolGuideFlow.GuideStep.StepType.DIRECTORY_PICKER;
            }
        }
        return ToolGuideFlow.GuideStep.StepType.FILE_PICKER;
    }
}

package com.oilquiz.app.ai.python;

import java.util.List;
import java.util.Map;

/**
 * Python 系工具"模型可见文本"的<b>唯一渲染处</b>。
 *
 * <p>为什么要有这个类：{@code PythonExecuteTool}、{@code PythonCalculateTool}、
 * {@code PythonDataAnalysisTool}、{@code PythonDynamicTool}、{@code PythonChartTool}、
 * {@code AIToolCreatorTool} 六个地方各自把同一个 {@code ExecutionResult} 用
 * {@code StringBuilder} 拼了一遍，形状几乎一样、详略却各处漏一点：
 * {@code attempts} 只有部分出口带（成功的 python_calculate 就不带，模型无从判断
 * 引擎已经替它试过几轮）；自动修复摘要有的只报类型、有的干脆不报；{@code error} 与
 * {@code stderr} 的段落名有三种叫法（"错误"/"错误: "/"详细信息"）。
 * 这就是本项目反复踩到的并行写者形状 —— 改一处口径要记得改六处，迟早忘一处。
 *
 * <p>只管模型通道（{@code AIToolResult.getResult()}）：两条执行路径的格式化都只序列化
 * getResult()，{@code additionalInfo} 是给卡片用的另一条通道（见 ToolResultCardBinder），
 * 所以各工具的 additionalInfo 填充保持原样，本类不代替它做决定。
 */
public final class PythonExecutionResultText {

    /** 两种形状，规则只写在这一个类里 */
    public enum Style {
        /** 段落化：脚本是"一段程序"，输出与返回值是两件事，分开列（python_execute / 动态工具 / 工具创建） */
        FULL,
        /** 原样：stdout 本身就是答案（计算结果、分析结论、图片路径），套段落名会把同一份内容打两遍 */
        COMPACT
    }

    private PythonExecutionResultText() {
    }

    /**
     * 渲染一次 Python 执行的模型可见文本。
     *
     * @param result      执行结果（成功/失败都走这里）
     * @param okHeading   成功抬头，原样作第一行（含各工具自己的措辞与 emoji）
     * @param failHeading 失败抬头，原样作第一行
     */
    public static String render(PythonToolManager.ExecutionResult result,
                                String okHeading, String failHeading) {
        return render(result, okHeading, failHeading, Style.FULL);
    }

    /**
     * 渲染一次 Python 执行的模型可见文本。
     *
     * @param result      执行结果（成功/失败都走这里）
     * @param okHeading   成功抬头，原样作第一行（含各工具自己的措辞与 emoji）
     * @param failHeading 失败抬头，原样作第一行
     * @param style       {@link Style#FULL} 段落化 / {@link Style#COMPACT} 输出即答案
     */
    public static String render(PythonToolManager.ExecutionResult result,
                                String okHeading, String failHeading, Style style) {
        StringBuilder out = new StringBuilder();
        if (result == null) {
            out.append(failHeading).append("\n\n");
            out.append("(没有拿到执行结果：Python 引擎未初始化或调用被中断)\n");
            return out.toString();
        }

        out.append(result.success ? okHeading : failHeading).append("\n\n");

        // 环境错误必须顶格说：Python 引擎区分"用户代码报错"与"执行器/环境坏了"
        // （run_code 的 environment_error），目的就是不让 agent 对着环境问题反复改脚本。
        // 这个标记以前只活在 Python 侧，Java 解析时被丢掉 → 模型看到的永远像"你代码写错了"。
        if (result.environmentError) {
            out.append("⚠️ 这是执行器/环境问题，不是你这段代码的问题：")
                    .append("不要靠反复改写脚本重试，先按下面的错误原文判断环境（缺库/初始化失败/引擎异常）。\n\n");
        }

        // 尝试次数成功失败都给：模型要靠它判断"引擎已经替我试过几轮、还值不值得再改"
        out.append("尝试次数: ").append(Math.max(0, result.attempts)).append("\n\n");

        boolean compact = style == Style.COMPACT;

        if (result.success) {
            if (compact) {
                // 输出即答案：原样给，不加段落标题。stdout 优先、result 兜底 ——
                // 与三个紧凑工具原先的语义一致（python_analyze_data 原本连 result 都不看），
                // 同时避免把同一个数字在两个段落里打两遍。
                if (isNotBlank(result.stdout)) {
                    out.append(result.stdout);
                } else if (isNotBlank(result.result)) {
                    out.append("结果: ").append(result.result).append("\n");
                }
            } else {
                if (isNotBlank(result.stdout)) {
                    out.append("=== 输出 ===\n").append(result.stdout).append("\n\n");
                }
                if (isNotBlank(result.result)) {
                    out.append("=== 结果 ===\n").append(result.result).append("\n");
                }
            }
            appendFixes(out, result.fixes, "=== 自动修复 ===");
            return out.toString();
        }

        if (isNotBlank(result.error)) {
            out.append(compact ? "错误: " : "=== 错误 ===\n").append(result.error)
                    .append(compact ? "\n" : "\n\n");
        }
        if (isNotBlank(result.stderr)) {
            // 两种形状都给 stderr：python_analyze_data 以前失败只报 error，把 traceback 整个丢了
            out.append(compact ? "错误输出: " : "=== 错误输出 ===\n").append(result.stderr)
                    .append(compact ? "\n" : "\n\n");
        }
        if (isNotBlank(result.stdout)) {
            // 失败脚本也可能已经 print 过一部分，丢掉就等于把线索扔了
            out.append("=== 部分输出 ===\n").append(result.stdout).append("\n");
        }
        appendFixes(out, result.fixes, "=== 尝试的修复 ===");
        return out.toString();
    }

    /**
     * 把"引擎替你改过代码"如实写进文本。
     *
     * <p>自动修复会改写脚本再重跑，<b>实际执行的版本与模型提交的那份不同</b>。只报错误类型
     * 不够 —— 模型下一轮仍按自己原始的代码推断，同一个错误会被反复"修好"再犯；
     * 但也不能贴整份代码（token 翻倍）。所以带上 Python 侧算好的增删行摘要。
     */
    public static void appendFixes(StringBuilder out, List<Map<String, String>> fixes, String header) {
        if (fixes == null || fixes.isEmpty()) {
            return;
        }
        out.append("\n").append(header).append("\n");
        out.append("注意：实际执行的代码不是你提交的那一份，引擎改写过；下面的摘要就是改动点。\n");
        for (Map<String, String> fix : fixes) {
            if (fix == null) {
                continue;
            }
            String type = fix.get("error_type");
            String change = fix.get("change_summary");
            if (isBlank(type) && isBlank(change)) {
                continue;
            }
            out.append("- ").append(isBlank(type) ? "修复" : type);
            if (!isBlank(change)) {
                out.append(" → ").append(change);
            }
            out.append("\n");
        }
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isEmpty();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}

package com.oilquiz.app.ai.chat.parts;

import org.json.JSONObject;

import java.util.Locale;

/**
 * 工具调用 → 终端命令卡的适配器。
 *
 * <p>问题：DSH 与本机引擎的 shell 调用以前都渲染成“单行工具卡 + 一行裸 JSON 参数”，
 * 手机上读不出这是一条命令，更看不到它跑成什么样。App 里其实已经有专门的
 * {@code dsh_command} 原生卡（等宽命令 + 状态灯 + 输出预览），但只有电脑端事件映射
 * 走得到，工具调用这条路没接上。</p>
 *
 * <p>这里做纯数据转换：认得 shell 类工具、且参数里有 {@code command} 时才返回命令卡 props，
 * 否则返回 null 让调用方退回原来的工具卡（不改变任何既有渲染）。</p>
 */
final class ToolCommandCard {

    static final String TYPE = "dsh_command";

    /** 视为“跑一条命令”的工具名（小写比较，包含匹配）。 */
    private static final String[] SHELL_TOOLS = {
            "pwsh", "powershell", "bash", "sh", "shell", "zsh", "cmd", "terminal", "exec", "run_command"
    };

    private ToolCommandCard() {
    }

    /** 是否 shell 类工具（只看工具名，不看参数）。 */
    static boolean isShellToolName(String toolName) {
        if (toolName == null) return false;
        String n = toolName.toLowerCase(Locale.ROOT).trim();
        if (n.isEmpty()) return false;
        for (String s : SHELL_TOOLS) {
            if (n.equals(s) || n.contains(s)) return true;
        }
        return false;
    }

    /**
     * 是否是“能变成命令卡”的调用：shell 工具 + 参数里解析得出 command。
     *
     * @param props 工具卡 props（toolName/args/status/result）
     */
    static boolean isShellTool(JSONObject props) {
        return build(props) != null;
    }

    /**
     * 组装命令卡 props：{command, status, output}。
     *
     * @return 无法提取命令时返回 null（调用方退回原工具卡）
     */
    static JSONObject build(JSONObject props) {
        if (props == null) return null;
        String toolName = props.optString("toolName", "");
        if (!isShellToolName(toolName)) return null;
        String args = props.optString("args", "").trim();
        if (args.isEmpty()) return null;

        String command = extractCommand(args);
        if (command.isEmpty()) return null;

        JSONObject out = new JSONObject();
        try {
            out.put("command", command);
            out.put("status", normalizeStatus(props.optString("status", "running")));
            String result = props.optString("result", "");
            if (!result.isEmpty()) {
                out.put("output", trimOutput(result));
            }
            String cwd = props.optString("cwd", "");
            if (!cwd.isEmpty()) out.put("cwd", cwd);
        } catch (org.json.JSONException e) {
            return null;
        }
        return out;
    }

    /** 从参数里取命令本体：JSON 取 command，纯文本原样。 */
    static String extractCommand(String args) {
        String a = args.trim();
        if (!a.startsWith("{")) return a;
        try {
            JSONObject o = new JSONObject(a);
            String cmd = o.optString("command", o.optString("cmd", "")).trim();
            if (!cmd.isEmpty()) return cmd;
        } catch (Throwable ignored) {
            // 坏 JSON：按原文当命令（比显示 JSON 好）
        }
        return "";
    }

    private static String normalizeStatus(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "success":
            case "done":
            case "completed":
            case "ok":
                return "success";
            case "failed":
            case "fail":
            case "error":
                return "failed";
            default:
                return "running";
        }
    }

    /** 输出只留尾部若干行：命令卡是概览，不是日志查看器。 */
    private static String trimOutput(String result) {
        String r = result.trim();
        if (r.isEmpty()) return "";
        String[] lines = r.split("\\r?\\n");
        int max = 6;
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, lines.length - max);
        for (int i = start; i < lines.length; i += 1) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        String out = sb.toString();
        if (out.length() > 600) out = out.substring(0, 600) + "…";
        return out;
    }
}

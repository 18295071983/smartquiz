package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;

import java.util.HashMap;
import java.util.Map;

/**
 * 内置 Linux 命令行工具箱（从 system_resource 里独立出来的专用工具）。
 *
 * <p>为什么独立：shell 能力已经不只是"系统资源调用"的一个动作了 —— 现在自带 busybox、
 * openssl（真 TLS）、openssh、curl、aria2c、ripgrep、jq、sqlite3、tmux… 后续还会继续加，
 * 单独一个工具更好扩展、也更容易让模型选对。
 *
 * <p>环境与实现全部复用 {@link SystemResourceTool}（PATH / TMPDIR / HOME / LD_LIBRARY_PATH /
 * 内置下载服务 / CA 包），保证两条入口行为完全一致。
 */
@Tool(
    value = "linux_shell",
    description = "内置 Linux 命令行工具箱（随 App 打包，无需安装 Termux、无需任何权限）："
        + "busybox(ash/awk/vi/telnet/tar/gzip…)、openssl(真 TLS)、ssh/scp/sftp/ssh-keygen、curl、aria2c、"
        + "rg(ripgrep)、jq、sqlite3、zstd、zip/unzip、file、tree、ncdu、htop/ps/free、tmux、nano、gawk。"
        + "action=exec 执行命令；action=tools 列出内置工具与版本；action=download 下载 URL 到文件。"
        + "单条命令 25 秒超时（超时返回已产生的输出）；默认不做任何命令拦截（可用 system_resource 的 shell_mode 开只读）",
    category = "system",
    actions = {
        @Action(name = "exec", description = "执行 shell 命令（/system/bin/sh -c，带完整内置工具环境）"),
        @Action(name = "tools", description = "列出内置工具及版本"),
        @Action(name = "download", description = "下载 URL 到本地文件（https 走系统证书校验）")
    },
    params = {
        @Param(name = "action", type = "string", description = "操作: exec/tools/download", required = true),
        @Param(name = "command", type = "string", description = "要执行的命令（exec 用）", required = false),
        @Param(name = "url", type = "string", description = "下载地址（download 用）", required = false),
        @Param(name = "path", type = "string", description = "保存路径（download 用；缺省存工作区 files/ 并用 URL 文件名）", required = false)
    }
)
public class LinuxShellTool implements AITool {

    private final Context context;

    public LinuxShellTool(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String getName() {
        return "linux_shell";
    }

    @Override
    public String getDescription() {
        return "内置 Linux 命令行工具箱：busybox/openssl/ssh/curl/aria2c/ripgrep/jq/sqlite3/tmux 等，"
                + "无需安装 Termux、无需权限；exec 执行命令，tools 列出工具，download 下载文件";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("action", "操作: exec(执行命令)/tools(列出内置工具与版本)/download(下载URL到文件)");
        descriptions.put("command", "要执行的命令（exec 用，如 curl -sI https://example.com | head -3、rg -n TODO /sdcard/Download、jq . f.json）");
        descriptions.put("url", "下载地址（download 用，https 走系统证书校验）");
        descriptions.put("path", "保存路径（download 用；缺省存工作区 files/ 并用 URL 文件名）");
        return descriptions;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        String action = (String) parameters.get("action");
        if (action == null || action.trim().isEmpty()) {
            action = "exec";
        }
        switch (action.trim().toLowerCase()) {
            case "tools":
            case "list":
                return SystemResourceTool.toolkitStatus(context, parameters);
            case "download":
            case "wget":
                return SystemResourceTool.httpDownloadStatic(context, parameters);
            case "exec":
            case "run":
            default:
                return SystemResourceTool.runShellStatic(context, parameters);
        }
    }
}

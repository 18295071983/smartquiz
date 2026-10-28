package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;

import org.json.JSONObject;

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
    description = "内置 Linux 命令行工具箱（默认随 App 打包的 busybox 沙箱，无需安装 Termux、无需任何权限）："
        + "busybox(ash/awk/vi/telnet/tar/gzip…)、openssl(真 TLS)、ssh/scp/sftp/ssh-keygen、curl、aria2c、"
        + "rg(ripgrep)、jq、sqlite3、zstd、zip/unzip、file、tree、ncdu、htop/ps/free、tmux、nano、gawk。"
        + "action=exec 执行命令；action=tools 列出内置工具与版本；action=download 下载 URL 到文件。"
        + "单条命令 25 秒超时（超时返回已产生的输出）；超时或输出超限会连同该命令的子进程一起终止，"
        + "所以 & 丢后面的任务也随之结束 —— 要跨调用长跑请交给 tmux（server 不在这条命令的进程树里）。"
        + "默认不做任何命令拦截（system_resource 的 shell_mode=readonly 可开启防误操作黑名单——它按命令文本匹配，"
        + "不是安全边界，也不是权限开关）。"
        + "结果含 exit_code/timed_out/truncated：退出码非零按 status=failed 上报，但其中一部分是正常语义"
        + "（grep/rg 无匹配=1、diff 有差异=1、test 不成立=1），请按 output 判断而不是无脑重试。"
        + "注意 action=route 的 command 参数是**命令名**（如 curl），不是整条命令。"
        + "【执行引擎 engine】缺省 engine=sandbox 跑在 App 内置 busybox 沙箱（cwd 可能是只读 /、无完整 Linux 生态）；"
        + "需要真 Linux（可写文件系统、apt/pip/git、tmux 长跑、完整 Python）时显式传 engine=termux，"
        + "命令改在**已安装的 Termux**里执行（20 秒超时，需先在「完整 Python 环境」调试配置页装 Termux 并授权）。"
        + "engine=termux 只后台起 Termux 服务、不会把 Termux 界面抢到前台。"
        + "action=termux_wake 后台唤醒 Termux 服务；action=termux_status 自检 Termux 通道（是否装/授权/进程存活/容器就绪）；"
        + "action=termux_submit 把超过 20 秒的长命令 detached 到 Termux 后台跑（写公共 jobs/ 目录 + 挂一张会自动刷新的活动卡）；"
        + "action=termux_purge 清理某作业的活动文件（job_id 必填）",
    category = "system",
    actions = {
        @Action(name = "exec", description = "执行 shell 命令（engine=sandbox 用 /system/bin/sh -c 带内置工具环境；engine=termux 交给已安装的 Termux 的 bash -lc，需 Termux 进程在跑——不确定先调 termux_wake 或 termux_status）"),
        @Action(name = "tools", description = "列出内置工具及版本"),
        @Action(name = "download", description = "下载 URL 到本地文件（https 走系统证书校验）"),
        @Action(name = "route", description = "查询/修改某命令的路由顺序（b=内置 s=系统 k=busybox t=toybox，默认 bskt）"),
        @Action(name = "termux_wake", description = "后台唤醒 Termux 服务进程（不打开 Termux 界面），让后续 engine=termux 命令有进程可落"),
        @Action(name = "termux_status", description = "自检 Termux 通道：是否已安装/已授权 RUN_COMMAND/进程存活/存储权限/容器就绪"),
        @Action(name = "termux_submit", description = "把 >20 秒的长命令交给 Termux detached 后台跑（tmux，没装回退 nohup），立即返回并挂一张自刷新的活动卡；command=要跑的命令，job_id=作业名(选填，缺省自动生成)"),
        @Action(name = "termux_purge", description = "清理某作业的活动文件（删公共 jobs/ 下 <id>.log/.rc/.done/.sh 并关 tmux 会话）；job_id 必填")
    },
    params = {
        @Param(name = "action", type = "string", description = "操作: exec/tools/download/route/termux_wake/termux_status/termux_submit/termux_purge", required = true),
        @Param(name = "engine", type = "string", description = "exec 用的执行引擎: sandbox(默认，App 内置 busybox 沙箱) / termux(已安装的 Termux 完整 Linux 环境，可写、可 apt/pip/tmux 长跑)", required = false),
        @Param(name = "job_id", type = "string", description = "termux_submit/termux_purge 用：作业名，只允许字母数字和 . _ -（submit 选填、缺省自动生成；purge 必填）", required = false),
        @Param(name = "order", type = "string", description = "route 用：路由顺序，如 bskt/stkb/b；reset 恢复默认", required = false),
        @Param(name = "command", type = "string", description = "要执行的命令（exec 用）；action=route 时改为填**命令名**（如 curl）；action=termux_submit 时为要在 Termux 后台跑的长命令", required = false),
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
                + "无需安装 Termux、无需权限；exec 执行命令（engine=sandbox 内置沙箱 / engine=termux 已装的 Termux 完整 Linux），"
                + "tools 列出工具，download 下载文件，termux_wake 后台唤醒 Termux 服务，termux_status 自检 Termux 通道";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("action", "操作: exec/tools/download/route/termux_wake(后台唤醒Termux服务)/termux_status(自检Termux通道)/termux_submit(长命令detached后台跑+挂自刷新活动卡)/termux_purge(清理作业文件)");
        descriptions.put("engine", "exec 的执行引擎: sandbox(默认，App 内置 busybox 沙箱) / termux(已安装的 Termux 完整 Linux 环境：可写文件系统、apt/pip/git、tmux 长跑、完整 Python；20 秒超时，需先装 Termux 并授权)");
        descriptions.put("job_id", "termux_submit/termux_purge 用：作业名，只允许字母数字和 . _ -（submit 选填、缺省自动生成；purge 必填）");
        descriptions.put("command", "要执行的命令（exec 用，如 curl -sI https://example.com | head -3、rg -n TODO /sdcard/Download、jq . f.json）；action=route 时填命令名（如 curl）");
        descriptions.put("url", "下载地址（download 用，https 走系统证书校验）");
        descriptions.put("path", "保存路径（download 用；缺省存工作区 files/ 并用 URL 文件名）");
        descriptions.put("order", "route 用：路由顺序（b=内置 s=系统 k=busybox t=toybox），如 bskt、stkb；reset 恢复默认");
        return descriptions;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        String action = (String) parameters.get("action");
        if (action == null || action.trim().isEmpty()) {
            action = "exec";
        }
        Object eng = parameters.get("engine");
        String engine = eng == null ? "" : String.valueOf(eng).trim();
        switch (action.trim().toLowerCase()) {
            case "tools":
            case "list":
                return SystemResourceTool.toolkitStatus(context, parameters);
            case "download":
            case "wget":
                return SystemResourceTool.httpDownloadStatic(context, parameters);
            case "route":
                return SystemResourceTool.routeStatic(context, parameters);
            case "termux_wake":
                return termuxWake(parameters);
            case "termux_status":
                return termuxStatus(parameters);
            case "termux_submit":
                return termuxSubmit(parameters);
            case "termux_purge":
                return termuxPurge(parameters);
            case "exec":
            case "run":
            default:
                // 显式引擎：engine=termux 走已安装的 Termux（完整 Linux/可写/tmux/长跑），
                // 缺省 sandbox 走 App 内置 busybox 沙箱（无需 Termux、无需权限），行为与以前完全一致。
                if ("termux".equalsIgnoreCase(engine)) {
                    return SystemResourceTool.termuxExecStatic(context, parameters);
                }
                return SystemResourceTool.runShellStatic(context, parameters);
        }
    }

    /**
     * 后台唤醒 Termux 服务进程（**不打开 Termux 界面**）：让后续 engine=termux 的命令有进程可落。
     * 只 startService 一条 no-op，进程已在跑就直接返回成功。
     */
    private AIToolResult termuxWake(Map<String, Object> parameters) {
        String err = TermuxEnvInstaller.wakeServiceInBackground(context);
        Map<String, Object> out = new HashMap<>();
        out.put("environment", "termux");
        out.put("installed", TermuxEnvInstaller.termuxVersion(context) != null);
        out.put("version", TermuxEnvInstaller.termuxVersion(context));
        out.put("run_command_permission", TermuxEnvInstaller.hasRunCommandPermission(context));
        out.put("process_alive", TermuxEnvInstaller.termuxProcessAlive(context));
        if (err == null) {
            out.put("status", "success");
            out.put("message", "Termux 服务已在后台唤醒/已在运行（未打开 Termux 界面）。现在可用 engine=termux 执行命令。");
        } else {
            out.put("status", "failed");
            out.put("error", err);
        }
        return new AIToolResult(out, parameters);
    }

    /** 自检 Termux 通道：装没装 / 授权没 / 进程活没活 / 存储权限 / 容器就绪（真发一条诊断命令等回执）。 */
    private AIToolResult termuxStatus(Map<String, Object> parameters) {
        Map<String, Object> out = new HashMap<>();
        out.put("environment", "termux");
        String ver = TermuxEnvInstaller.termuxVersion(context);
        out.put("installed", ver != null);
        out.put("version", ver);
        out.put("run_command_permission", TermuxEnvInstaller.hasRunCommandPermission(context));
        out.put("process_alive", TermuxEnvInstaller.termuxProcessAlive(context));
        out.put("storage_permission", TermuxEnvInstaller.termuxHasStoragePermission(context));
        if (ver == null) {
            out.put("status", "failed");
            out.put("error", "Termux 未安装：打开答题宝「工具集 → 设置与系统环境 → 完整 Python 环境」调试配置页，按向导一键安装并授权。");
            return new AIToolResult(out, parameters);
        }
        TermuxEnvInstaller.ChannelResult ch = TermuxEnvInstaller.runInTermuxAndWait(
                context, TermuxEnvInstaller.buildDiagnoseCommand(), 20);
        out.put("channel_ok", ch.ok);
        out.put("stdout", ch.stdout);
        out.put("status", ch.ok ? "success" : "failed");
        if (!ch.ok) {
            out.put("error", ch.error);
            out.put("hint", "按上面 error 的归因处理：说看不到 Termux 进程 → 先在手机上打开一次 Termux，或调 linux_shell(action=termux_wake) 后台起服务（process_alive=false 在高版本 Android 上只表示\"看不见\"，不是定论）；"
                    + "写明 allow-external-apps → 去「完整 Python 环境」调试配置页点「自检并修复通道」，按提示粘一行命令（改完要重启 Termux 进程才生效）。");
        }
        return new AIToolResult(out, parameters);
    }

    /**
     * termux_submit：把 >20 秒的长命令 detached 到 Termux 后台跑，挂一张自刷新的活动卡。
     * 复用 {@link TermuxEnvInstaller#submitDetachedJob}（与 android_termux.submit 同一目录契约/标记）。
     * 卡片走 AIToolResult.withComponent 直接挂载（AIToolManager 收集），不经 binder、不抢屏。
     */
    private AIToolResult termuxSubmit(Map<String, Object> parameters) {
        String command = str(parameters.get("command"));
        String jobId = str(parameters.get("job_id"));
        if (jobId.isEmpty()) {
            jobId = "job_" + System.currentTimeMillis();
        } else {
            jobId = TermuxEnvInstaller.safeJobId(jobId);
            if (jobId == null) {
                return new AIToolResult("job_id 非法（只允许字母数字和 . _ -）", parameters);
            }
        }
        String[] launcher = new String[1];
        String err = TermuxEnvInstaller.submitDetachedJob(context, command, jobId, launcher);
        if (err != null) {
            return new AIToolResult(err, parameters);
        }
        JSONObject props = new JSONObject();
        try {
            props.put("job_id", jobId);
            props.put("title", "Termux 作业");
            props.put("cmd", command.length() > 120 ? command.substring(0, 120) + "…" : command);
        } catch (Exception ignored) {
        }
        Map<String, Object> out = new HashMap<>();
        out.put("environment", "termux");
        out.put("job_id", jobId);
        out.put("launcher", launcher[0]);
        out.put("log", TermuxEnvInstaller.JOBS_DIR + "/" + jobId + ".log");
        out.put("status", "success");
        AIToolResult res = new AIToolResult(out, parameters);
        res.withComponent(ComponentData.of("job", props));
        return res;
    }

    /** termux_purge：清理某作业的活动文件（删公共 jobs/ 下 <id>.log/.rc/.done/.sh 并关 tmux 会话）。 */
    private AIToolResult termuxPurge(Map<String, Object> parameters) {
        String jobId = TermuxEnvInstaller.safeJobId(str(parameters.get("job_id")));
        if (jobId == null) {
            return new AIToolResult("termux_purge 需要合法的 job_id（只允许字母数字和 . _ -）", parameters);
        }
        String err = TermuxEnvInstaller.purgeDetachedJob(context, jobId);
        Map<String, Object> out = new HashMap<>();
        out.put("environment", "termux");
        out.put("job_id", jobId);
        if (err == null) {
            out.put("status", "success");
            out.put("message", "已清理作业 " + jobId + " 的活动文件");
        } else {
            out.put("status", "failed");
            out.put("error", err);
        }
        return new AIToolResult(out, parameters);
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}

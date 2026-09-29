package com.oilquiz.app.ai.tool;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;

import java.io.File;

/**
 * 「完整 Python 环境（Termux + Ubuntu）」一键准备界面。
 *
 * <p>入口：工具集 → 设置与数据 → 完整 Python 环境。
 * 三步：① 装内置的 Termux 官方包 ② 导出内置的 Ubuntu 根文件系统 ③ 在 Termux 里准备容器 + apt 装 python3-full/tk。
 * 第 ③ 步优先用 Termux 的 RUN_COMMAND 自动下发（需要用户在 App 内点一次"允许"，小米禁止 adb 代授）；
 * 若权限没给，界面提供"复制手动命令"，粘进 Termux 即可完成同样的事。
 */
public class TermuxEnvSetupActivity extends AppCompatActivity {

    private TextView statusView;
    private TextView logView;
    private MaterialButton installBtn;
    private MaterialButton exportBtn;
    private MaterialButton runBtn;
    private MaterialButton grantBtn;
    private MaterialButton copyBtn;
    private volatile boolean busy;
    /** 通道自检结论（展示在状态区） */
    private String channelState = "未检测（点「自检并修复通道」）";
    /** 是否正处于"等用户去 Termux 粘一行命令"的状态（回到本页自动复检） */
    private volatile boolean pendingFix;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("完整 Python 环境");
        }
        setContentView(R.layout.activity_termux_env_setup);

        statusView = findViewById(R.id.env_status);
        logView = findViewById(R.id.env_log);
        installBtn = findViewById(R.id.btn_install_termux);
        findViewById(R.id.btn_open_vnc).setOnClickListener(v -> {
            String err = TermuxEnvInstaller.startGuiInTermux(this);
            if (err != null) {
                log("启动图形界面失败：" + err + "\n（需要先装好 Termux、授予 RUN_COMMAND 权限，并跑过一次「一键准备」）");
                return;
            }
            log("已让 Termux 启动图形界面（Xvfb 1280x720 + x11vnc :5900）。\n"
                    + "马上打开「图形界面（VNC）」页，若显示「等待图形界面就绪…」会自动重试到连上为止。\n"
                    + "Termux 侧日志：~/.quiz_gui.log");
            startActivity(new Intent(this, com.oilquiz.app.vnc.VncActivity.class));
        });
        exportBtn = findViewById(R.id.btn_export_rootfs);
        runBtn = findViewById(R.id.btn_run_setup);
        grantBtn = findViewById(R.id.btn_grant);
        copyBtn = findViewById(R.id.btn_copy_cmd);

        installBtn.setOnClickListener(v -> doInstallTermux());
        exportBtn.setOnClickListener(v -> doExportRootfs());
        runBtn.setOnClickListener(v -> doRunSetup());
        grantBtn.setOnClickListener(v -> {
            if (TermuxEnvInstaller.hasRunCommandPermission(this)) {
                log("RUN_COMMAND 权限已授予。\n如果还需要 Termux 读取本地包（ｾ/storage 访问）：\n在 Termux 里执行  termux-setup-storage  并在系统弹窗点「允许」（或 Termux 设置——应用——打开“所有文件访问”）。");
            } else {
                startActivity(new Intent(this, TermuxPermissionActivity.class));
                log("已打开 Termux 权限请求：请在弹窗里点「允许」。\n（若没弹窗，说明厂商 ROM 拦了，改用「复制手动命令」粘到 Termux 执行）");
            }
        });
        copyBtn.setOnClickListener(v -> copyManualCommand());
        findViewById(R.id.btn_fix_channel).setOnClickListener(v -> doFixChannel());
        findViewById(R.id.btn_refresh).setOnClickListener(v -> {
            refresh();
            toast("已刷新状态");
        });
        findViewById(R.id.btn_open_ubuntu).setOnClickListener(v -> doOpenUbuntuShell());
        findViewById(R.id.btn_view_logs).setOnClickListener(v -> doViewEnvLogs());
        findViewById(R.id.btn_restart_gui).setOnClickListener(v -> doRestartGui());
        findViewById(R.id.btn_stop_gui).setOnClickListener(v -> doStopGui());

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        // 用户去 Termux 粘完那一行命令回来后，自动复检通道（通了就不用再点任何东西）
        if (pendingFix && !busy) {
            pendingFix = false;
            log("检测到你回到本页了，正在复检 Termux 通道…");
            doFixChannel();
        }
    }

    /**
     * 复制"手动兜底命令"。
     *
     * <p>优先给**一行短命令**（脚本全文已写到 Download/OilQuiz/termux_env/setup.sh，Termux 有存储权限就能读），
     * 而不是把 5KB 脚本全文塞进剪贴板；没有存储权限或写不进去时，才退回复制全文。
     */
    private void copyManualCommand() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        boolean storage = TermuxEnvInstaller.termuxHasStoragePermission(this);
        String shortCmd = storage ? TermuxEnvInstaller.shortManualCommand(this) : null;
        String text = shortCmd != null ? shortCmd : TermuxEnvInstaller.buildSetupScript(rootfsPath());
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("termux-setup", text));
        }
        if (shortCmd != null) {
            log("已复制一行命令：\n" + shortCmd + "\n\n"
                    + "到 Termux 里长按终端 → 粘贴 → 回车即可（脚本全文已写到 "
                    + "Download/OilQuiz/termux_env/setup.sh，与 App 当前版本一致，以后改脚本这行命令不用变）。");
        } else {
            log("已复制准备脚本全文（Termux 还没有存储权限，只能整段粘贴；"
                    + "授权后本按钮会改成只复制一行）。到 Termux 里长按粘贴并回车（约 3~6 分钟）。");
        }
        toast("命令已复制到剪贴板");
    }

    private String rootfsPath() {
        File f = TermuxEnvInstaller.exportedRootfs(this);
        return f == null ? null : f.getAbsolutePath();
    }

    /** 刷新状态区与按钮可用性 */
    private void refresh() {
        String ver = TermuxEnvInstaller.termuxVersion(this);
        boolean perm = TermuxEnvInstaller.hasRunCommandPermission(this);
        boolean store = TermuxEnvInstaller.termuxHasStoragePermission(this);
        File rootfs = TermuxEnvInstaller.exportedRootfs(this);
        long apkAsset = TermuxEnvInstaller.assetSize(this, TermuxEnvInstaller.ASSET_TERMUX_APK);
        long rootAsset = TermuxEnvInstaller.assetSize(this, TermuxEnvInstaller.ASSET_ROOTFS);

        StringBuilder sb = new StringBuilder();
        sb.append("Termux：").append(ver == null ? "未安装 ✗" : ("已安装 ✓ v" + ver)).append("\n");
        sb.append("RUN_COMMAND 权限：").append(perm ? "已授予 ✓" : "未授予（需手点一次）").append("\n");
        sb.append("Termux 存储权限：").append(store
                ? "已授予 ✓（用本地 28.5MB 包，不联网）"
                : "未授予 ⚠️（读不到本地包 → 会联网下 30MB；一键准备时会弹授权框，请点『允许』）").append("\n");
        sb.append("Ubuntu 根文件系统：").append(rootfs == null ? "未导出 ✗" : ("已导出 ✓ " + mb(rootfs.length()))).append("\n");
        sb.append("Termux 签名：").append(ver == null ? "—"
                : (TermuxEnvInstaller.termuxSignerMatchesBundled(this)
                ? "与内置包一致 ✓（可直接更新安装）"
                : "与内置包不一致 ⚠️（覆盖安装会被系统拒绝；继续用现有 Termux 也行）")).append("\n");
        sb.append("内置包：Termux ").append(apkAsset > 0 ? mb(apkAsset) : "（未内置，需自行下载）")
                .append(" / Ubuntu ").append(rootAsset > 0 ? mb(rootAsset) : "（未内置）").append("\n");
        sb.append("通道自检：").append(channelState);
        statusView.setText(sb.toString());

        installBtn.setEnabled(!busy && ver == null);
        installBtn.setText(ver == null ? "安装 Termux" : "Termux 已安装，无需重复安装");
        styleStepBtn(installBtn, ver != null);
        exportBtn.setEnabled(!busy && rootfs == null);
        exportBtn.setText(rootfs == null ? "导出 Ubuntu 根文件系统（28.5 MB）" : "根文件系统已导出，无需重复导出");
        styleStepBtn(exportBtn, rootfs != null);
        runBtn.setEnabled(!busy && ver != null);
        // 不再因 RUN_COMMAND 已授予而禁用：点击后按状态分支（未授→请求页；已授→存储指引）
        grantBtn.setEnabled(!busy);
        copyBtn.setEnabled(!busy);
    }

    /** 完成态按钮视觉：未完成=主色+下载图标；已完成=次色+勾图标 */
    private void styleStepBtn(MaterialButton b, boolean done) {
        b.setIconResource(done ? R.drawable.ic_check : R.drawable.ic_ai_download);
        int bg = ThemeColors.attr(this, done ? R.attr.colorSecondaryContainer : R.attr.colorPrimary);
        int fg = ThemeColors.attr(this, done ? R.attr.colorOnSecondaryContainer : R.attr.colorOnPrimary);
        b.setBackgroundTintList(ColorStateList.valueOf(bg));
        b.setTextColor(fg);
        b.setIconTint(ColorStateList.valueOf(fg));
    }

    /** 进 Ubuntu 终端：在 Termux 可见会话里 exec ~/ubuntu */
    private void doOpenUbuntuShell() {
        if (TermuxEnvInstaller.termuxVersion(this) == null) { log("Termux 还没装，请先执行第 1 步。"); return; }
        if (!TermuxEnvInstaller.hasRunCommandPermission(this)) { log("RUN_COMMAND 权限未授予，先点「授予 Termux 权限」。"); return; }
        String err = TermuxEnvInstaller.runInTermux(this, "exec ~/ubuntu", false);
        log(err == null
                ? "已在 Termux 打开 Ubuntu 终端，直接输入命令即可；输入 exit 退回 Termux。"
                : "打开失败：" + err);
    }

    /** 查看 Termux 侧环境日志：在可见会话里 tail 三个日志 */
    private void doViewEnvLogs() {
        if (TermuxEnvInstaller.termuxVersion(this) == null) { log("Termux 还没装，请先执行第 1 步。"); return; }
        if (!TermuxEnvInstaller.hasRunCommandPermission(this)) { log("RUN_COMMAND 权限未授予。"); return; }
        String cmd = "echo '===== setup 日志（尾 60 行）====='; tail -60 ~/.quiz_env_setup.log 2>/dev/null; "
                + "echo; echo '===== GUI 日志（尾 30 行）====='; tail -30 ~/.quiz_gui.log 2>/dev/null; "
                + "echo; echo '===== GUI 启动跟踪（尾 20 行）====='; tail -20 ~/.quiz_gui_start_trace.log 2>/dev/null; "
                + "echo; echo '===== 结束 ====='";
        String err = TermuxEnvInstaller.runInTermux(this, cmd, false);
        log(err == null ? "已打开 Termux 会话显示环境日志。" : "查看日志失败：" + err);
    }

    /** 重启图形界面：先刷新启动器（与设备同版本）再 restart */
    private void doRestartGui() {
        String shortCmd = refreshLauncherCmdForActivity()
                + "test -x $HOME/ubuntu-gui && bash $HOME/ubuntu-gui restart || echo NO_UBUNTU_GUI_请先点一次一键准备";
        String err = TermuxEnvInstaller.runInTermux(this, shortCmd, true);
        log(err == null ? "已让 Termux 重启图形界面（可去 VNC 页看效果）。" : "重启失败：" + err);
    }

    /** 停止图形界面 */
    private void doStopGui() {
        String shortCmd = "bash $HOME/ubuntu-gui stop 2>/dev/null; "
                + "pkill -f 'quiz_gui_dem[o]' >/dev/null 2>&1; echo GUI_STOPPED";
        String err = TermuxEnvInstaller.runInTermux(this, shortCmd, true);
        log(err == null ? "已让 Termux 停止图形界面（VNC 断开）。" : "停止失败：" + err);
    }

    /** 拷贝自 TermuxEnvInstaller.refreshLauncherCmd 的体的快速版（用于 restart 前刷新启动器） */
    private String refreshLauncherCmdForActivity() {
        return "SRC=\"\"; "
                + "for c in /sdcard/Download/" + TermuxEnvInstaller.EXPORT_SUBDIR + "/ubuntu-gui.sh "
                + "\"$HOME/storage/downloads/" + TermuxEnvInstaller.EXPORT_SUBDIR + "/ubuntu-gui.sh\"; do "
                + "[ -f \"$c\" ] && SRC=\"$c\" && break; done; "
                + "[ -n \"$SRC\" ] && cp \"$SRC\" \"$HOME/ubuntu-gui\" && chmod 700 \"$HOME/ubuntu-gui\"; ";
    }

    private void doInstallTermux() {
        if (TermuxEnvInstaller.termuxVersion(this) != null) {
            if (!TermuxEnvInstaller.termuxSignerMatchesBundled(this)) {
                log("已装的 Termux 与内置包**签名不一致**，系统会拒绝覆盖安装。两个选择：\n"
                        + "· 【推荐】继续用你现在这个 Termux，不用装内置包 —— 只需要让它允许外部应用调用，"
                        + "点「自检并修复通道」，我会给你一行命令（粘一次即可）；\n"
                        + "· 或者：卸载 Termux 再装内置包 —— 注意容器/Ubuntu 环境会一起被删除，之后要重新跑一次「一键准备」。");
                toast("签名不一致，请看下方说明");
                return;
            }
            toast("Termux 已安装");
            return;
        }
        busy = true;
        refresh();
        log("正在把内置的 Termux 安装包释放到缓存目录（约 108 MB，请稍候）…");
        new Thread(() -> {
            String err = null;
            try {
                TermuxEnvInstaller.installTermux(this);
            } catch (Exception e) {
                err = String.valueOf(e.getMessage());
            }
            final String fErr = err;
            runOnUiThread(() -> {
                busy = false;
                refresh();
                log(fErr == null
                        ? "已唤起系统安装器：请点「安装」→ 装完回到本页。\n（若提示「未知来源」，按系统引导允许一次即可）"
                        : "释放安装包失败: " + fErr);
            });
        }, "termux-apk-extract").start();
    }

    private void doExportRootfs() {
        busy = true;
        refresh();
        log("正在导出 Ubuntu 根文件系统到 Download/OilQuiz/termux_env/（28.5 MB）…");
        new Thread(() -> {
            String msg;
            try {
                File out = TermuxEnvInstaller.exportRootfs(this);
                msg = "已导出: " + out.getAbsolutePath() + "（" + mb(out.length()) + "）";
            } catch (Exception e) {
                msg = "导出失败: " + e.getMessage();
            }
            final String fMsg = msg;
            runOnUiThread(() -> {
                busy = false;
                refresh();
                log(fMsg);
            });
        }, "rootfs-export").start();
    }

    private void doRunSetup() {
        // 先把 setup.sh / ubuntu-gui.sh / zh_fix.sh 落到公共下载目录：
        // setup.sh 里 step 3.5/6 要从 sdcard 取回后两个文件（中文化与 GUI 入口），
        // 只下发文本的话它们永远不在设备上，那两个步骤会被跳过（真机已踩到）。
        // 落盘失败（无存储权限）不影响主流程 —— 脚本文本照常经 RUN_COMMAND 下发。
        TermuxEnvInstaller.writeSetupScriptFile(this);
        String script = TermuxEnvInstaller.buildSetupScript(rootfsPath());
        String err = TermuxEnvInstaller.runInTermux(this, script, false);
        boolean store = TermuxEnvInstaller.termuxHasStoragePermission(this);
        String hint = store ? "" : "\n提示：Termux 还没有存储权限，本次会联网下 30MB；屏幕上弹「允许访问文件」时点『允许』，下次就能用本地包了。";
        if (err == null) {
            log("已把准备脚本下发给 Termux（会打开一个可见会话显示进度）。" + hint + "\n"
                    + "脚本是幂等的：已装过的部分会跳过，重复点不会报 already exists。\n"
                    + "全程日志写在 Termux 的 ~/.quiz_env_setup.log，出问题就看它（或在 Termux 里执行 cat ~/.quiz_env_setup.log）。\n"
                    + "如果 Termux 窗口里报错：多半是 allow-external-apps 没开 —— 用「复制手动命令」粘一次即可（脚本会自己把它打开）。");
            toast("已在 Termux 里开始准备");
        } else {
            log("一键下发失败：" + err + hint + "\n改用「复制手动命令」：粘到 Termux 里执行同样能装好。");
            toast("请用「复制手动命令」");
        }
    }

    /**
     * 通道自检 + 一键修复。
     *
     * <p>Android 不允许 App A 写 App B 的私有目录，也不允许绕过 Termux 自己声明的
     * {@code allow-external-apps} 开关（MIUI 还禁掉了 adb 代授 pm grant），所以"首次打开这个开关"
     * 只能由用户在 Termux 里执行一次。本方法把这个唯一的手动步骤压到极限：
     * 自动复制**一行**命令 + 自动打开 Termux + 用户回来时自动复检，之后全自动。
     */
    private void doFixChannel() {
        if (TermuxEnvInstaller.termuxVersion(this) == null) {
            log("Termux 还没装。先点第 1 步「安装 Termux」（内置官方包）。");
            return;
        }
        busy = true;
        refresh();
        log("正在自检 Termux 通道（真发一条命令并等回执，约 20 秒）…");
        new Thread(() -> {
            TermuxEnvInstaller.ChannelResult probe =
                    TermuxEnvInstaller.runInTermuxAndWait(this, "echo QUIZ_CHANNEL_OK", 20);
            if (probe.ok) {
                TermuxEnvInstaller.ChannelResult diag = TermuxEnvInstaller.runInTermuxAndWait(
                        this, TermuxEnvInstaller.buildDiagnoseCommand(), 25);
                channelState = "可用 ✓";
                runOnUiThread(() -> {
                    busy = false;
                    refresh();
                    log("✅ 通道可用：答题宝已经能把命令送进 Termux 并拿回输出，一键准备/图形界面都能自动化了。\n\n"
                            + "环境现状：\n" + diag.stdout);
                });
                return;
            }
            channelState = "不可用 ✗";
            String shortCmd = TermuxEnvInstaller.termuxHasStoragePermission(this)
                    ? TermuxEnvInstaller.shortManualCommand(this) : null;
            final String cmd = shortCmd;
            runOnUiThread(() -> {
                busy = false;
                refresh();
                boolean copied = false;
                if (cmd != null) {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("termux-fix", cmd));
                        copied = true;
                    }
                }
                boolean opened = TermuxEnvInstaller.openTermux(this);
                pendingFix = true;
                log("❌ 通道不可用：" + probe.error + "\n\n"
                        + (cmd != null
                        ? (copied ? "已把修复命令复制到剪贴板：\n" + cmd + "\n\n" : "修复命令：\n" + cmd + "\n\n")
                        : "修复命令（Termux 没存储权限，只能整段粘贴）：点「复制手动命令」\n\n")
                        + (opened ? "已打开 Termux：" : "请手动打开 Termux：")
                        + "长按终端 → 粘贴 → 回车。\n"
                        + "这一行会写好 allow-external-apps 并立即生效（只需做这一次）。\n"
                        + "做完切回答题宝，我会自动复检并继续。");
            });
        }, "termux-channel-probe").start();
    }

    private void log(String s) {
        logView.setText(s);
    }

    private static String mb(long bytes) {
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

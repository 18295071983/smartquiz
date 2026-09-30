package com.oilquiz.app.ai.tool;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
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
 * <p>入口：工具集 → 设置与系统环境 → 完整 Python 环境。
 * 三步：① 装内置的 Termux 官方包 ② 导出内置的 Ubuntu 根文件系统 ③ 在 Termux 里准备容器 + apt 装 python3-full/tk。
 * 第 ③ 步优先用 Termux 的 RUN_COMMAND 自动下发（需要用户在 App 内点一次"允许"，小米禁止 adb 代授）；
 * 若权限没给，界面提供"复制手动命令"，粘进 Termux 即可完成同样的事。
 */
public class TermuxEnvSetupActivity extends AppCompatActivity {

    private TextView statusView;
    private TextView logView;
    /** Termux 执行过程监控：轮询公共日志实时显示（用户要求"能监控执行过程"） */
    private final android.os.Handler monitorHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean monitoring;
    /** 上次显示过的日志内容：内容没变化就不刷新（避免每 2 秒全量重写导致闪烁跳动） */
    private String lastMonitorText = "";

    private final Runnable monitorRunnable = new Runnable() {
        @Override
        public void run() {
            if (!monitoring) {
                return;
            }
            String tail = TermuxEnvInstaller.readPublicSetupLog(TermuxEnvSetupActivity.this);
            if (!tail.isEmpty()) {
                String clean = tail.length() > 6000 ? tail.substring(tail.length() - 6000) : tail;
                if (!clean.equals(lastMonitorText)) {
                    lastMonitorText = clean;
                    logView.setText("📡 正在监控 Termux 执行过程（每 2 秒刷新，切回本页即可看到实时进度）…\n\n" + clean);
                    scrollLogToBottom();
                }
            }
            // 完成/失败判定只看日志里的明确标记（🎉=完成，❌=某个 step 失败），
            // 不再用宽泛的 fail=0/fail=1 匹配（apt/pip 输出可能误撞）
            if (tail.contains("🎉") || tail.contains("❌")) {
                stopLogMonitor();
                return;
            }
            monitorHandler.postDelayed(this, 2000);
        }
    };
    /** 异签名 Termux 警告行（UI 明示，默认隐藏） */
    private TextView signerWarnView;
    /** 首次打开 Termux 初始化提示行（UI 明示，默认隐藏） */
    private TextView firstInitHintView;
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
    /** 智能引导进行到的阶段：null=未进行；probe/perm/rootfs/storage/setup=正在引导该项 */
    private volatile String smartStage;
    /** 动态辅助按钮：按当前缺失项自动变化（install/perm/rootfs/storage/setup，null=隐藏） */
    private MaterialButton auxFixBtn;
    private String auxFixAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("完整 Python 环境");
        }
        setContentView(R.layout.activity_termux_env_setup);

        statusView = findViewById(R.id.env_status);
        logView = findViewById(R.id.env_log);
        logView.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        signerWarnView = findViewById(R.id.tv_signer_warn);
        firstInitHintView = findViewById(R.id.tv_first_init_hint);
        installBtn = findViewById(R.id.btn_install_termux);
        findViewById(R.id.btn_enable_ssh).setOnClickListener(v -> doEnableSsh());
        findViewById(R.id.btn_open_termux).setOnClickListener(v -> {
            if (TermuxEnvInstaller.openTermux(this)) {
                toast("已打开 Termux（显示上次会话）");
                log("已打开 Termux —— 显示的是上次用的会话（一般是 ubuntu 容器）。\n要在多个会话间切换：Termux 里从屏幕左边缘向右滑 → 会话抽屉 → 点会话名。");
            } else {
                toast("打开 Termux 失败");
            }
        });
        findViewById(R.id.btn_open_vnc).setOnClickListener(v -> {
            if (busy) { toast("正在执行中，请稍候"); return; }
            if (TermuxEnvInstaller.termuxVersion(this) == null) { toast("请先安装 Termux（第 1 步）"); return; }
            if (!TermuxEnvInstaller.hasRunCommandPermission(this)) { toast("请先授予 RUN_COMMAND 权限"); return; }
            toast("正在启动图形界面…");
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
        findViewById(R.id.btn_smart_guide).setOnClickListener(v -> {
            if (busy) { toast("正在执行中，请稍候"); return; }
            doSmartGuide();
        });
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
        findViewById(R.id.btn_fix_channel).setOnClickListener(v -> {
            if (busy) { toast("正在执行中，请稍候"); return; }
            if (TermuxEnvInstaller.termuxVersion(this) == null) { toast("请先安装 Termux（第 1 步）"); return; }
            doFixChannel();
        });
        findViewById(R.id.btn_refresh).setOnClickListener(v -> {
            refresh();
            toast("已刷新状态");
        });
        // 「更多工具」折叠：默认收起，点击展开/收起高级功能
        View llMoreTools = findViewById(R.id.ll_more_tools);
        MaterialButton toggleMore = findViewById(R.id.btn_toggle_more);
        toggleMore.setOnClickListener(v -> {
            boolean show = llMoreTools.getVisibility() != View.VISIBLE;
            llMoreTools.setVisibility(show ? View.VISIBLE : View.GONE);
            toggleMore.setText(show ? "更多工具 ▲" : "更多工具 ▾");
        });
        // 动态辅助按钮：检测缺什么就给什么动作，点一下自动复制/执行，不用手输命令
        auxFixBtn = findViewById(R.id.btn_aux_fix);
        auxFixBtn.setOnClickListener(v -> {
            String action = auxFixAction;
            if (action == null) { return; }
            if (busy) { toast("正在执行中，请稍候"); return; }
            if ("install".equals(action)) {
                doInstallTermux();
            } else if ("perm".equals(action)) {
                startActivity(new Intent(this, TermuxPermissionActivity.class));
                log("已打开 Termux 权限请求：请在弹窗里点「允许」。");
            } else if ("rootfs".equals(action)) {
                doExportRootfs();
            } else if ("storage".equals(action)) {
                copyStorageCmdAndOpen();
            } else if ("setup".equals(action)) {
                startSetupStep();
            } else if ("allowex".equals(action)) {
                fixAllowExternalApps();
            } else if ("overlay".equals(action)) {
                // 直接跳到 Termux 的「显示在其他应用上层」设置页（Android 8+ 专用入口）
                Intent oi = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + TermuxEnvInstaller.TERMUX_PACKAGE));
                startActivity(oi);
                log("已打开 Termux 的悬浮窗权限设置：打开「显示在其他应用上层」开关后回到本页点「刷新」。\n"
                        + "（没有这项的话，走：设置 → 应用管理 → Termux → 高级/其他权限 → 显示在其他应用上层）");
            }
        });
        findViewById(R.id.btn_open_ubuntu).setOnClickListener(v -> doOpenUbuntuShell());
        findViewById(R.id.btn_open_ubuntu).setOnLongClickListener(v -> {
            if (TermuxEnvInstaller.termuxVersion(this) == null) { toast("请先安装 Termux"); return true; }
            if (TermuxEnvInstaller.openUbuntuSession(this)) {
                ubuntuSessionOpened = true;
                toast("已强制新建容器会话");
                log("✅ 已强制新建一个容器会话并自动进入（自动执行 ~/ubuntu）。");
            } else {
                toast("新建失败：先授予 RUN_COMMAND 权限");
            }
            return true;
        });
        findViewById(R.id.btn_view_logs).setOnClickListener(v -> doViewEnvLogs());
        findViewById(R.id.btn_restart_gui).setOnClickListener(v -> doRestartGui());
        findViewById(R.id.btn_stop_gui).setOnClickListener(v -> doStopGui());
        findViewById(R.id.btn_install_api).setOnClickListener(v ->
                doInstallPlugin(TermuxEnvInstaller.ASSET_API_APK, TermuxEnvInstaller.TERMUX_API_PACKAGE, "Termux:API"));
        findViewById(R.id.btn_install_boot).setOnClickListener(v ->
                doInstallPlugin(TermuxEnvInstaller.ASSET_BOOT_APK, TermuxEnvInstaller.TERMUX_BOOT_PACKAGE, "Termux:Boot"));

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        // 用户去 Termux 粘完命令/授权回来后，按当前引导阶段自动复检续接：
        // 智能引导模式 → resumeSmartGuide(阶段)；普通自检 → 复检通道
        if (pendingFix && !busy) {
            pendingFix = false;
            String stage = smartStage;
            if (stage != null) {
                resumeSmartGuide(stage);
            } else {
                log("检测到你回到本页了，正在复检 Termux 通道…");
                doFixChannel();
            }
        }
    }

    /**
     * 复制"手动兜底命令"。
     *
     * <p>优先给**一行短命令**（脚本全文已写到 Download/OilQuiz/termux_env/setup.sh，Termux 有存储权限就能读），
     * 而不是把 5KB 脚本全文塞进剪贴板；没有存储权限或写不进去时，才退回复制全文。
     */
    private void copyManualCommand() {
        toast("已复制命令，去 Termux 粘贴回车");
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
        sb.append("Termux:API：").append(TermuxEnvInstaller.isAppInstalled(this, TermuxEnvInstaller.TERMUX_API_PACKAGE)
                ? "已装 ✓（选装）" : "未装（选装，更多工具里可装）").append("\n");
        sb.append("Termux:Boot：").append(TermuxEnvInstaller.isAppInstalled(this, TermuxEnvInstaller.TERMUX_BOOT_PACKAGE)
                ? "已装 ✓（选装）" : "未装（选装，更多工具里可装）").append("\n");
        sb.append("通道自检：").append(channelState);
        statusView.setText(sb.toString());

        // UI 明示两处引导：
        // · 异签名 Termux（P2-7）：已装版与内置包签名不一致 → 常驻警告行（点「安装 Termux」会被系统拒绝覆盖）
        // · 首次打开初始化（P0-2）：Termux 已装但 RUN_COMMAND 还没授权 → 大概率没完成首次初始化 → 提示等 bootstrap
        boolean signerMismatch = ver != null && !TermuxEnvInstaller.termuxSignerMatchesBundled(this);
        signerWarnView.setVisibility(signerMismatch ? View.VISIBLE : View.GONE);
        firstInitHintView.setVisibility(ver != null && !perm ? View.VISIBLE : View.GONE);

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
        // 辅助按钮保持可点，点击时由 onClick 守卫判条件，不满足弹小字提示并不执行（防乱点但不置灰）
        findViewById(R.id.btn_smart_guide).setEnabled(!busy);
        findViewById(R.id.btn_open_vnc).setEnabled(!busy);
        findViewById(R.id.btn_fix_channel).setEnabled(!busy);
        findViewById(R.id.btn_open_ubuntu).setEnabled(!busy);
        findViewById(R.id.btn_view_logs).setEnabled(!busy);
        findViewById(R.id.btn_restart_gui).setEnabled(!busy);
        findViewById(R.id.btn_stop_gui).setEnabled(!busy);
        updateAuxFixButton();
    }

    /** 动态辅助按钮：按当前"缺什么"显示对应动作；全就绪时隐藏（不手输、不用去翻更多工具） */
    private void updateAuxFixButton() {
        String ver = TermuxEnvInstaller.termuxVersion(this);
        if (ver == null) {
            showAux("📥 第 1 步：安装 Termux", "install");
        } else if (!TermuxEnvInstaller.hasRunCommandPermission(this)) {
            showAux("🔑 授予 RUN_COMMAND 权限", "perm");
        } else if (TermuxEnvInstaller.exportedRootfs(this) == null) {
            showAux("📦 导出 Ubuntu 根文件系统", "rootfs");
        } else if (!TermuxEnvInstaller.termuxHasStoragePermission(this)) {
            showAux("🔓 设置 Termux 存储（复制命令，弹窗点允许）", "storage");
        } else if (!TermuxEnvInstaller.termuxHasOverlayPermission(this)) {
            // Android 10+ 后台启动终端会话必需；缺失时 Termux 弹 "Display over other apps" 并拒开
            showAux("🪟 开启 Termux 悬浮窗权限（后台自动执行必需）", "overlay");
        } else if (!"可用 ✓".equals(channelState)) {
            // 通道不通：最典型是 allow-external-apps 配置写了但 Termux 没重启加载（真机常见）。
            // 给最短修复：一行命令写入配置并自动重启 Termux；回来复检通道，通了自动续跑环境。
            showAux("⚡ 修复 allow-external-apps（粘贴后自动重启 Termux）", "allowex");
        } else {
            showAux(null, null);
        }
    }

    private void showAux(String text, String action) {
        auxFixAction = action;
        if (text == null) {
            auxFixBtn.setVisibility(View.GONE);
        } else {
            auxFixBtn.setText(text);
            auxFixBtn.setVisibility(View.VISIBLE);
        }
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
        if (busy) { toast("正在执行中，请稍候"); return; }
        if (TermuxEnvInstaller.termuxVersion(this) == null) { toast("请先安装 Termux（第 1 步）"); return; }
        if (!TermuxEnvInstaller.hasRunCommandPermission(this)) { toast("请先授予 RUN_COMMAND 权限"); return; }
        if (TermuxEnvInstaller.termuxVersion(this) == null) { log("Termux 还没装，请先执行第 1 步。"); return; }
        if (!TermuxEnvInstaller.hasRunCommandPermission(this)) { log("RUN_COMMAND 权限未授予，先点「授予 Termux 权限」。"); return; }
        // 不用 RUN_COMMAND 可见会话 exec 交互 shell：长期挂起会占住命令通道，
        // 导致后续其他按钮的 RUN_COMMAND 下发排队/无响应（真机实测）。
        // 改为：唤起 Termux 前台，用户输入 ~/ubuntu 回车进入，通道不被占用。
        Intent ti = getPackageManager().getLaunchIntentForPackage("com.termux");
        if (ti == null) {
            toast("未找到 Termux");
            return;
        }
        // 一键进容器：首次新建容器会话（自动执行 ~/ubuntu）；之后再点只切回 Termux（显示上次会话），不再新建
        if (ubuntuSessionOpened) {
            TermuxEnvInstaller.openTermux(this);
            toast("已打开 Termux（回容器会话）");
            log("已打开 Termux —— 显示的是上次的容器会话（不会重复新建）。\n"
                    + "· 若上次容器会话已被关闭（Termux 里关掉了），可在 Termux 输入 ~/ubuntu 重进，或长按本按钮强制新建一个。");
            return;
        }
        if (TermuxEnvInstaller.openUbuntuSession(this)) {
            ubuntuSessionOpened = true;
            toast("已自动进入 Ubuntu 容器");
            log("✅ 已让 Termux 打开新的容器终端并自动执行 ~/ubuntu —— 你现在就在 Ubuntu 容器里了（可跑完整 Python）。\n"
                    + "· 输入 exit 退回 Termux；之后再点「进 Ubuntu」只切回，不会重复新建会话。\n"
                    + "· 长按本按钮可强制新建一个容器会话。\n"
                    + "· 说明：为不占用自动下发通道，容器会话由 Termux 前台显示；其他按钮不受影响。");
            return;
        }
        // 兜底：打开 Termux 前台（用户粘贴一行即可）
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("ubuntu-enter", "~/ubuntu"));
            }
        } catch (Exception ignored) {
        }
        ti.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(ti);
        toast("已打开 Termux，粘贴回车即进入 Ubuntu");
        log("自动进容器没成功，已打开 Termux 并把  ~/ubuntu  复制到剪贴板 —— 长按终端 → 粘贴 → 回车即可进入。");
    }

    /** 查看 Termux 侧环境日志：在可见会话里 tail 三个日志 */
    private void doViewEnvLogs() {
        toast("正在读取环境日志…");
        if (busy) { toast("正在执行中，请稍候"); return; }
        if (TermuxEnvInstaller.termuxVersion(this) == null) { toast("请先安装 Termux（第 1 步）"); return; }
        if (!TermuxEnvInstaller.hasRunCommandPermission(this)) { toast("请先授予 RUN_COMMAND 权限"); return; }
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
        toast("正在重启图形界面…");
        if (busy) { toast("正在执行中，请稍候"); return; }
        if (TermuxEnvInstaller.termuxVersion(this) == null) { toast("请先安装 Termux（第 1 步）"); return; }
        if (!TermuxEnvInstaller.hasRunCommandPermission(this)) { toast("请先授予 RUN_COMMAND 权限"); return; }
        String shortCmd = refreshLauncherCmdForActivity()
                + "test -x $HOME/ubuntu-gui && bash $HOME/ubuntu-gui restart || echo NO_UBUNTU_GUI_请先点一次一键准备";
        String err = TermuxEnvInstaller.runInTermux(this, shortCmd, true);
        log(err == null ? "已让 Termux 重启图形界面（可去 VNC 页看效果）。" : "重启失败：" + err);
    }

    /** 停止图形界面 */
    private void doStopGui() {
        toast("正在停止图形界面…");
        if (busy) { toast("正在执行中，请稍候"); return; }
        if (TermuxEnvInstaller.termuxVersion(this) == null) { toast("请先安装 Termux（第 1 步）"); return; }
        if (!TermuxEnvInstaller.hasRunCommandPermission(this)) { toast("请先授予 RUN_COMMAND 权限"); return; }
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
                        ? "已唤起系统安装器：请点「安装」→ 装完回到本页。\n（若提示「未知来源」，按系统引导允许一次即可）\n"
                        + "第一次打开 Termux 会自动初始化（下载基础包）：请等到出现命令行提示符 $ 再继续。\n"
                        + "没等它初始化完就执行后续步骤会报 command not found，容易误判成脚本坏了。"
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
                // 智能引导进行到 rootfs 阶段时，导出完成自动续接下一步
                if ("rootfs".equals(smartStage) && TermuxEnvInstaller.exportedRootfs(this) != null) {
                    log(fMsg + "\n✅ 根文件系统已导出，继续检测…");
                    continueSmartGuide();
                }
            });
        }, "rootfs-export").start();
    }

    private void doRunSetup() {
        toast("正在下发准备脚本…");
        // 先把 setup.sh / ubuntu-gui.sh / zh_fix.sh 落到公共下载目录：
        // setup.sh 里 step 3.5/6 要从 sdcard 取回后两个文件（中文化与 GUI 入口），
        // 只下发文本的话它们永远不在设备上，那两个步骤会被跳过（真机已踩到）。
        // 落盘失败（无存储权限）不影响主流程 —— 脚本文本照常经 RUN_COMMAND 下发。
        TermuxEnvInstaller.writeSetupScriptFile(this);
        String script = TermuxEnvInstaller.buildSetupScript(rootfsPath());
        boolean store = TermuxEnvInstaller.termuxHasStoragePermission(this);
        String hint = store ? "" : "\n提示：Termux 还没有存储权限，本次会联网下 30MB；屏幕上弹「允许访问文件」时点『允许』，下次就能用本地包了。";
        execInTermux(script, false, () -> {
            log("已把准备脚本下发给 Termux（会打开一个可见会话显示进度）。" + hint + "\n"
                    + "脚本是幂等的：已装过的部分会跳过，重复点不会报 already exists。\n"
                    + "全程日志写在 Termux 的 ~/.quiz_env_setup.log，出问题就看它（或在 Termux 里执行 cat ~/.quiz_env_setup.log）。\n"
                    + "如果 Termux 窗口里报错：多半是 allow-external-apps 没开 —— 用「复制手动命令」粘一次即可（脚本会自己把它打开）。");
            toast("已在 Termux 里开始准备");
            // 打开专用监控页：实时看 8 个步骤的 ✅/⏳/❌ 和原始日志
            startActivity(new Intent(this, TermuxSetupMonitorActivity.class));
        });
    }

    /**
     * 智能检测引导（新手一键走完整个流程）。
     *
     * <p>按依赖顺序自动检测：Termux 已装 → 签名一致 → 命令通道通（= 首次初始化完成 + allow-external-apps 生效）
     * → RUN_COMMAND 授权 → rootfs 已导出 → 存储权限 → 环境已准备。
     * 每一项不满足时：能自动的（导出 rootfs / 弹授权页 / 下发一键准备）直接做；
     * 必须用户手动的（粘命令、点系统弹窗）自动复制命令 + 打开 Termux，用户做完切回来自动复检并继续下一项。
     */
    private void doSmartGuide() {
        if (TermuxEnvInstaller.termuxVersion(this) == null) {
            log("🔍 第 1 项未就绪：Termux 还没安装。\n点上方「安装 Termux」→ 系统弹窗点「安装」→ 装完回到本页再点一次「智能检测引导」。");
            toast("先安装 Termux");
            return;
        }
        if (!TermuxEnvInstaller.termuxSignerMatchesBundled(this)) {
            log("🔍 检测到已装 Termux 与内置包**签名不一致**（系统会拒绝覆盖安装），需要你先定一条路：\n"
                    + "· 【推荐】继续用现有 Termux：粘贴 setup.sh 那一行就能用，不用卸载；\n"
                    + "· 或卸载旧 Termux 再装内置包（会清空已有容器环境，之后重新一键准备）。");
            toast("签名不一致，见下方说明");
            return;
        }
        // ===== 核心：一步手动 + 之后全自动 =====
        // 唯一需要用户做的：在 Termux 里粘贴这一行回车（命令开头自动 termux-setup-storage 建桥，
        // 系统弹窗点一次允许；然后脚本自动完成 allow-external-apps / 建容器 / Python / GUI）。
        // 用户切回答题宝后，App 自动诊断确认并收尾，之后全自动运行。
        startSetupStep();
    }

    /** 第 2 步：App 落盘三件套，复制执行命令让用户跑准备脚本（唯一手动步骤，之后 App 全自动收尾） */
    private void startSetupStep() {
        // setup.sh 由 App 生成到公共目录（Download/OilQuiz/termux_env/），Termux 拷到 $HOME 再执行
        TermuxEnvInstaller.writeSetupScriptFile(this);
        String shortCmd = TermuxEnvInstaller.shortManualCommand(this);
        String text = shortCmd != null ? shortCmd : TermuxEnvInstaller.buildSetupScript(rootfsPath());
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("termux-setup", text));
        }
        boolean opened = TermuxEnvInstaller.openTermux(this);
        pendingFix = true;
        smartStage = "setup";
        startLogMonitor();
        log(shortCmd != null
                ? "🔍 智能引导：只需在 Termux 里输入**这一行**（已复制到剪贴板），之后全部自动：\n\n" + shortCmd + "\n\n"
                + (opened ? "已打开 Termux：" : "请手动打开 Termux：") + "长按终端 → 粘贴 → 回车。\n"
                + "这一行会：\n"
                + "① 开头自动执行 termux-setup-storage → 系统弹「允许访问文件」→ 点「允许」（只点这一次）\n"
                + "② 自动完成：allow-external-apps（打开后 App 就能自动运行）· 用本地 28.5MB 包建 Ubuntu 容器 · 装完整 Python + 图形界面 · 写好 ~/ubuntu、~/ubuntu-gui 入口\n"
                + "（⚠️ 不能直接 bash /sdcard/...：noexec 会被拒绝，这行已自动处理）\n"
                + "跑完看到「🎉 环境准备完成」后切回答题宝，我自动确认收尾 —— 之后 App 全自动，你不用再碰 Termux。"
                : "🔍 智能引导：已复制完整准备脚本（Termux 无存储权限，只能整段粘贴）。\n"
                + (opened ? "已打开 Termux：" : "请手动打开 Termux：") + "长按终端 → 粘贴 → 回车。\n"
                + "脚本会自动：建立存储桥（弹窗点允许）· allow-external-apps · 建 Ubuntu 容器 · 装完整 Python + 图形界面 · 写好入口。\n"
                + "跑完看到「🎉 环境准备完成」后切回答题宝，我自动确认收尾。");
    }

    /**
     * probe 失败后的二次检测：判断 Termux bootstrap 是否初始化完成。
     * 用纯文件检查（bash 存在性），不依赖 Termux 侧任何服务状态：
     * <ul>
     *   <li>INIT_PENDING → Termux 刚装/基础包没装完 → 引导等初始化出 $ 提示符，不复制命令；</li>
     *   <li>INIT_DONE → 初始化完成但通道不通 → allow-external-apps 等配置问题 → 复制修复命令；</li>
     *   <li>完全无响应 → Termux 进程/通道问题 → 引导打开 Termux 确认初始化后重试。</li>
     * </ul>
     */
    private void initCheckThenGuide(String probeError) {
        new Thread(() -> {
            TermuxEnvInstaller.ChannelResult init = TermuxEnvInstaller.runInTermuxAndWait(
                    this, "test -x /data/data/com.termux/files/usr/bin/bash && echo INIT_DONE || echo INIT_PENDING", 12);
            runOnUiThread(() -> {
                busy = false;
                refresh();
                if (init.ok && init.stdout.contains("INIT_PENDING")) {
                    // 刚装上、bootstrap 未就绪：只引导等待，不复制命令
                    smartStage = "probe";
                    pendingFix = true;
                    boolean opened = TermuxEnvInstaller.openTermux(this);
                    log("🔍 Termux 检测到**刚装上、基础包还没初始化完成**（bootstrap 未就绪）。\n"
                            + (opened ? "已打开 Termux：" : "请手动打开 Termux：")
                            + "首次打开会自动下载并解压基础包（约 30 秒~2 分钟，视网络），\n"
                            + "请等到出现命令行提示符 $ 再操作 —— 现在粘贴任何命令都会报 command not found。\n"
                            + "初始化完成后切回答题宝，我会自动复检并继续下一步。");
                } else if (init.ok && init.stdout.contains("INIT_DONE")) {
                    // 初始化完成但通道不通：allow-external-apps / 权限问题 → 复制修复命令
                    guidePasteFix(probeError);
                } else {
                    // 通道完全无响应（连文件检查都发不出去）
                    smartStage = "probe";
                    pendingFix = true;
                    boolean opened = TermuxEnvInstaller.openTermux(this);
                    log("🔍 Termux 命令通道没有响应（" + probeError + "）。\n"
                            + (opened ? "已打开 Termux：" : "请手动打开 Termux：")
                            + "先确认 Termux 已完成首次初始化（出现 $ 提示符且能输入命令），\n"
                            + "确认后切回答题宝点「智能检测引导」重试；如果 Termux 卡在初始化，检查网络后重启 Termux 再等。");
                }
            });
        }, "smart-guide-initcheck").start();
    }

    /** 通道不通：复制修复命令 + 打开 Termux，等用户粘贴回车后自动复检 */
    private void guidePasteFix(String err) {
        String shortCmd = TermuxEnvInstaller.termuxHasStoragePermission(this)
                ? TermuxEnvInstaller.shortManualCommand(this) : null;
        boolean copied = false;
        if (shortCmd != null) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("termux-fix", shortCmd));
                copied = true;
            }
        }
        boolean opened = TermuxEnvInstaller.openTermux(this);
        pendingFix = true;
        smartStage = "probe";
        log("🔍 第 1 项未就绪：Termux 命令通道不通（" + err + "）\n"
                + "最常见原因是「首次打开 Termux 还没初始化完」或「allow-external-apps 还没开」。\n\n"
                + (shortCmd != null
                ? (copied ? "✅ 已复制修复命令：\n" + shortCmd + "\n\n" : "修复命令：\n" + shortCmd + "\n\n")
                : "修复命令（Termux 没存储权限，只能整段粘贴）：点「复制手动命令」\n\n")
                + (opened ? "已打开 Termux：" : "请手动打开 Termux：") + "长按终端 → 粘贴 → 回车。\n"
                + "（若刚装 Termux，请先等它自动初始化出命令行提示符 $，再粘贴）\n"
                + "做完切回答题宝，我会自动复检并继续下一步。");
    }

    /** 通道通之后的后续步骤（按依赖顺序，缺哪项引导哪项） */
    private void continueSmartGuide() {
        busy = false;
        refresh();
        if (!TermuxEnvInstaller.hasRunCommandPermission(this)) {
            smartStage = "perm";
            log("🔍 下一步：授予 RUN_COMMAND 权限。\n即将打开权限请求页 —— 在 Termux 弹窗里点「允许」（可勾选始终允许）。");
            startActivity(new Intent(this, TermuxPermissionActivity.class));
            return;
        }
        if (TermuxEnvInstaller.exportedRootfs(this) == null) {
            smartStage = "rootfs";
            log("🔍 下一步：自动导出 Ubuntu 根文件系统（28.5MB 内置包 → Download/OilQuiz/termux_env/）…");
            doExportRootfs();
            return;
        }
        if (!TermuxEnvInstaller.termuxHasStoragePermission(this)) {
            smartStage = "storage";
            copyStorageCmdAndOpen();
            return;
        }
        // 环境是否已准备：跑诊断命令看 ubuntu-gui 入口与上次准备结果（fail=0）
        smartStage = "setup";
        new Thread(() -> {
            TermuxEnvInstaller.ChannelResult diag = TermuxEnvInstaller.runInTermuxAndWait(
                    this, TermuxEnvInstaller.buildDiagnoseCommand(), 25);
            runOnUiThread(() -> {
                String out = diag.ok ? diag.stdout : "（诊断失败：" + diag.error + "）";
                boolean guiReady = diag.ok && out.contains("ubuntu-gui=yes");
                boolean envReady = diag.ok && out.contains("fail=0");
                refresh();
                if (guiReady && envReady) {
                    smartStage = null;
                    log("✅ 智能检测全部通过：Termux 通道可用、权限齐全、本地包就绪、环境已准备。\n\n环境现状：\n" + out);
                } else {
                    log("🔍 最后一步：环境还没准备（" + (guiReady ? "" : "缺 ~/ubuntu-gui 入口；")
                            + (envReady ? "" : "上次准备未成功；") + "）。\n自动下发「一键准备」脚本（约 3~6 分钟，幂等可重复，已装的会跳过）…\n\n环境现状：\n" + out);
                    doRunSetup();
                }
            });
        }, "smart-guide-diag").start();
    }

    /** 存储权限：复制 termux-setup-storage 命令 + 打开 Termux，用户点完系统弹窗后回来复检 */
    private void copyStorageCmdAndOpen() {
        String cmd = "termux-setup-storage; sleep 2; test -d $HOME/storage && echo STORAGE_OK || echo STORAGE_NO";
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("termux-storage", cmd));
        }
        boolean opened = TermuxEnvInstaller.openTermux(this);
        pendingFix = true;
        log("🔍 下一步：授予 Termux 存储权限（决定用本地 28.5MB 包还是联网下 30MB）。\n"
                + "✅ 已复制命令：\n" + cmd + "\n\n"
                + (opened ? "已打开 Termux：" : "请手动打开 Termux：") + "长按终端 → 粘贴 → 回车。\n"
                + "系统弹「允许访问文件」→ 点「允许」。\n做完切回答题宝，我会自动复检并继续下一步。");
    }

    /** 修复 allow-external-apps：复制一行（写入配置 + 自动重启 Termux）+ 打开 Termux；回来复检通道，通了自动续跑 */
    private void fixAllowExternalApps() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("termux-allowex", TermuxEnvInstaller.ALLOWEX_FIX_COMMAND));
        }
        boolean opened = TermuxEnvInstaller.openTermux(this);
        pendingFix = true;
        smartStage = "probe";
        log("⚡ 已复制 allow-external-apps 修复命令：\n\n" + TermuxEnvInstaller.ALLOWEX_FIX_COMMAND + "\n\n"
                + (opened ? "已打开 Termux：" : "请手动打开 Termux：") + "长按终端 → 粘贴 → 回车。\n"
                + "这一行会：① 写入 allow-external-apps=true ② 自动退出 Termux（强制重启，配置立即生效）。\n"
                + "重新打开 Termux 后切回答题宝 —— 我自动复检通道，通了就自动继续装环境，全程不用再手输。");
    }

    /**
     * 用户从 Termux 回来后，按当前阶段复检并续接。
     *
     * <p><b>铁律：这里绝不自动 openTermux()。</b>用户设置"始终允许启动 Termux"时，
     * 每次切回主应用触发复检，若复检又自动跳回 Termux，就会死循环（真机已踩）。
     * 未就绪时只提示，由用户自己决定何时去 Termux。
     */
    private void resumeSmartGuide(String stage) {
        switch (stage) {
            case "probe": {
                // doFixChannel / initCheckThenGuide 引导后回来：复检通道（只检测，不跳转）
                busy = true;
                refresh();
                log("复检 Termux 通道…");
                new Thread(() -> {
                    TermuxEnvInstaller.ChannelResult probe =
                            TermuxEnvInstaller.runInTermuxAndWait(this, "echo QUIZ_CHANNEL_OK", 20);
                    runOnUiThread(() -> {
                        busy = false;
                        if (probe.ok) {
                            channelState = "可用 ✓";
                            log("✅ 通道通了（allow-external-apps 已生效），之后 App 就能自动下发命令。自动继续下一步…");
                            continueSmartGuide();
                        } else {
                            channelState = "不可用 ✗";
                            smartStage = "probe";
                            pendingFix = true;
                            log("通道还是不通（" + probe.error + "）：确认 Termux 已完成首次初始化（出 $ 提示符）了吗？\n"
                                    + "确认后切到 Termux 检查/粘贴命令，做完再切回来我自动复检（不会自动跳转，等你准备好）。");
                        }
                    });
                }, "smart-guide-reprobe").start();
                break;
            }
            case "storage-first":
                // 第 1 步复检：存储桥是否建立（termux-setup-storage + 点允许）
                if (TermuxEnvInstaller.termuxHasStoragePermission(this)) {
                    log("✅ 存储权限已授予，进入第 2 步…");
                    startSetupStep();
                } else {
                    log("存储权限还没检测到：确认刚才在系统弹窗里点「允许」了吗？\n"
                            + "（没点的话，切到 Termux 重新粘贴 termux-setup-storage 回车，弹窗点允许，再切回来）\n"
                            + "已复制命令：termux-setup-storage，随时可去 Termux 粘贴。");
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("termux-storage-first", "termux-setup-storage"));
                    }
                }
                break;
            case "setup": {
                // 第 2 步复检：用户跑完那一行回来，跑诊断确认环境是否就绪
                busy = true;
                refresh();
                log("正在确认环境是否就绪…");
                new Thread(() -> {
                    TermuxEnvInstaller.ChannelResult diag = TermuxEnvInstaller.runInTermuxAndWait(
                            this, TermuxEnvInstaller.buildDiagnoseCommand(), 25);
                    runOnUiThread(() -> {
                        busy = false;
                        refresh();
                        String out = diag.ok ? diag.stdout : "（诊断失败：" + diag.error + "）";
                        boolean guiReady = diag.ok && out.contains("ubuntu-gui=yes");
                        boolean envReady = diag.ok && out.contains("fail=0");
                        if (guiReady && envReady) {
                            smartStage = null;
                            channelState = "可用 ✓";
                            log("✅ 环境已就绪！Termux 通道也通了（allow-external-apps 已由脚本写好），以后 App 就能全自动：AI 调用、图形界面、进 Ubuntu 都行。\n\n环境现状：\n" + out);
                        } else if (diag.ok && out.contains("fail=1")) {
                            smartStage = "setup";
                            pendingFix = true;
                            log("⚠️ 刚才那一行跑完有步骤失败（fail=1）：看 Termux 里的 ❌ 行，多数是网络问题。\n"
                                    + "在 Termux 里再粘一次同一行即可（脚本幂等，已装过的会跳过）。\n"
                                    + "跑完看到「🎉 环境准备完成」后再切回来。\n"
                                    + "（不回自动跳转：等你准备好随时去 Termux）");
                        } else if (!diag.ok) {
                            smartStage = "setup";
                            pendingFix = true;
                            log("通道还没通（" + diag.error + "）：确认 Termux 里粘贴过那一行并看到「🎉 环境准备完成」了吗？\n"
                                    + "如果没跑：切到 Termux 粘贴那一行回车（等 $ 提示符出现后再粘），跑完再切回来。\n"
                                    + "如果跑了还报这个：切回来我再查。");
                        } else {
                            smartStage = "setup";
                            pendingFix = true;
                            log("通道已通，但环境还没完全就绪（缺 " + (guiReady ? "" : "~/ubuntu-gui 入口；")
                                    + (envReady ? "" : "fail=0 记录；") + "）。\n"
                                    + "在 Termux 里再粘一次那一行跑完即可（幂等）。\n\n环境现状：\n" + out);
                        }
                    });
                }, "smart-guide-setup-check").start();
                break;
            }
            default:
                smartStage = null;
                break;
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
            // 先区分「Termux 刚装上还没初始化」与「初始化完成但 allow-external-apps 没开」，
            // 未初始化时只引导等待，不复制命令（避免 command not found）。
            initCheckThenGuide(probe.error);
        }, "termux-channel-probe").start();
    }

    private void log(String s) {
        logView.setText(s);
        scrollLogToBottom();
    }

    /** 日志固定 240dp 高度，内容超了内部滚动；这里把滚动位置移到最新一行 */
    private void scrollLogToBottom() {
        logView.post(() -> {
            if (logView.getLayout() != null) {
                int h = logView.getLayout().getHeight();
                if (h > logView.getHeight()) {
                    logView.scrollTo(0, h - logView.getHeight());
                }
            }
        });
    }

    /** 开始监控 Termux 执行过程（2 秒轮询公共日志，检测到完成/失败自动停） */
    private void startLogMonitor() {
        monitoring = true;
        monitorHandler.removeCallbacks(monitorRunnable);
        monitorHandler.post(monitorRunnable);
    }

    private void stopLogMonitor() {
        monitoring = false;
        monitorHandler.removeCallbacks(monitorRunnable);
    }

    private int sshTries;
    /** 是否已开过容器会话：开过就不再新建（避免反复点「进 Ubuntu」堆积一堆 ubuntu 会话） */
    private volatile boolean ubuntuSessionOpened = false;

    private final Runnable sshPollRunnable = new Runnable() {
        @Override
        public void run() {
            String txt = TermuxEnvInstaller.readPublicText(TermuxEnvSetupActivity.this, TermuxEnvInstaller.SSH_INFO_NAME);
            if (!txt.isEmpty()) {
                if (txt.contains("SSH_OK")) {
                    String user = pickLine(txt, "user");
                    String ip = pickLine(txt, "ip");
                    log("✅ SSH 已开启！\n\n电脑与手机连同一 WiFi，在电脑终端（PowerShell / 任意 ssh 客户端）执行：\n\n  ssh " + user + "@" + ip + " -p 8022\n\n密码：quiz2026（在 Termux 里执行 passwd 可改成自己的）\n进 Ubuntu 容器：登录后在 Termux 提示符输入 ~/ubuntu\n");
                    return;
                }
                if (txt.contains("FAIL")) {
                    log("❌ " + txt + "\n到 Termux 手动执行：pkg install openssh -y && sshd，再回本页点「🔑 开启 SSH」");
                    return;
                }
            }
            if (++sshTries > 12) {
                log("⏳ 还在安装中（首次要联网装 openssh，约 30 秒~1 分钟；网络慢会久一点）。\n稍后点「🔑 开启 SSH」会重试；也可以到 Termux 执行：pkg install openssh -y && sshd");
                return;
            }
            monitorHandler.postDelayed(this, 2000);
        }
    };

    /** 统一执行：直接发给 Termux（进程死了 Android 会自动拉起它执行服务）；
     * 只有真下发失败（异常）才唤起 Termux 并在 5 秒后自动重发一次。 */
    private void execInTermux(String script, boolean bg, Runnable onOk) {
        String err = TermuxEnvInstaller.runInTermux(this, script, bg);
        if (err == null) {
            if (onOk != null) onOk.run();
            return;
        }
        if (err.startsWith("还没有授予") || err.startsWith("Termux 还没安装")) {
            log(err);
            return;
        }
        log("⚠️ " + err + "\n正在打开 Termux 唤起进程，起来后自动重发…");
        TermuxEnvInstaller.openTermux(this);
        monitorHandler.postDelayed(() -> {
            String e2 = TermuxEnvInstaller.runInTermux(TermuxEnvSetupActivity.this, script, bg);
            if (e2 == null) {
                if (onOk != null) onOk.run();
                // 命令已发出：自动回到答题宝页面（避免留在 Termux 干等）
                Intent back = new Intent(TermuxEnvSetupActivity.this, TermuxEnvSetupActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(back);
            } else {
                log("重试仍失败：" + e2 + "\n可改用「复制手动命令」粘贴执行。");
            }
        }, 5000);
    }

    private void doEnableSsh() {
        toast("正在开启 SSH…");
        if (TermuxEnvInstaller.termuxVersion(this) == null) {
            log("🔍 还没装 Termux，先点上方「安装 Termux」，装完再回来开 SSH。");
            return;
        }
        String script = TermuxEnvInstaller.buildSshEnableScript();
        execInTermux(script, true, () -> {
            sshTries = 0;
            monitorHandler.removeCallbacks(sshPollRunnable);
            monitorHandler.post(sshPollRunnable);
            log("🔑 正在 Termux 里安装 openssh 并启动 sshd（首次约 30 秒），装完自动显示连接信息…");
        });
    }

    private static String pickLine(String txt, String key) {
        for (String l : txt.split("\n")) {
            if (l.startsWith(key + "=")) {
                return l.substring(key.length() + 1).trim();
            }
        }
        return "";
    }

    @Override
    protected void onDestroy() {
        monitoring = false;
        monitorHandler.removeCallbacks(monitorRunnable);
        monitorHandler.removeCallbacks(sshPollRunnable);
        super.onDestroy();
    }

    /** 选装配套插件：未装 → 释放内置 APK 唤起安装器；Termux:Boot 装好后自动引导启用开机自启 */
    private void doInstallPlugin(String assetName, String pkg, String label) {
        if (TermuxEnvInstaller.isAppInstalled(this, pkg)) {
            toast(label + " 已安装");
            if (TermuxEnvInstaller.TERMUX_BOOT_PACKAGE.equals(pkg)) {
                enableBootSelfStart();
            }
            return;
        }
        busy = true;
        refresh();
        log("正在释放 " + label + " 安装包（内置，秒装）…");
        new Thread(() -> {
            String err = null;
            try {
                TermuxEnvInstaller.installPluginApk(this, assetName, assetName);
            } catch (Exception e) {
                err = String.valueOf(e.getMessage());
            }
            final String fErr = err;
            runOnUiThread(() -> {
                busy = false;
                refresh();
                log(fErr == null
                        ? "已唤起安装器：点「安装」装好 " + label + "。\n装好后回本页再点一次本按钮"
                        + (TermuxEnvInstaller.TERMUX_BOOT_PACKAGE.equals(pkg)
                        ? "，我会自动写入开机自启脚本（开机自动拉起 Ubuntu 图形界面）。"
                        : "即配置完成（在 Termux 里 pkg install termux-api 装配套库即可调用手机硬件）。")
                        : "释放 " + label + " 安装包失败: " + fErr);
            });
        }, "plugin-apk-extract").start();
    }

    /** 启用 Termux:Boot 开机自启：通道通 → 直接下发写脚本；不通 → 复制命令引导粘贴一次 */
    private void enableBootSelfStart() {
        String cmd = TermuxEnvInstaller.buildBootScriptCommand();
        if (!"可用 ✓".equals(channelState)) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("termux-boot", cmd));
            }
            boolean opened = TermuxEnvInstaller.openTermux(this);
            pendingFix = true;
            log("已复制开机自启脚本命令：\n\n" + cmd + "\n\n"
                    + (opened ? "已打开 Termux：" : "请手动打开 Termux：") + "长按终端 → 粘贴 → 回车。\n"
                    + "看到 BOOT_SCRIPT_OK 即写入成功：重启手机后约 1 分钟会自动拉起 Ubuntu 图形界面。");
            return;
        }
        String err = TermuxEnvInstaller.runInTermux(this, cmd, true);
        log(err == null
                ? "✅ 已写入开机自启脚本（~/.termux/boot/start-env.sh）：重启手机后约 1 分钟会自动拉起 Ubuntu 图形界面。"
                : "写入失败：" + err + "\n（确认 Termux 通道可用后重试，或复制命令到 Termux 手动执行）");
    }

    private static String mb(long bytes) {
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}

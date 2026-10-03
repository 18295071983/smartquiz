# TermuxEnvSetupActivity 向导化改造补丁
$ErrorActionPreference = 'Stop'
$p = "D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\tool\TermuxEnvSetupActivity.java"
$t = [IO.File]::ReadAllText($p)
$nl = "`n"

# 1. 字段：加向导 UI 字段
$f_anchor = '    private MaterialButton auxFixBtn;' + $nl + '    private String auxFixAction;'
$f_add = $f_anchor + $nl + @"
    /** 向导 UI：进度条 / 当前步骤标题 / 说明 */
    private android.widget.ProgressBar guideProgress;
    private TextView guideTitle;
    private TextView guideDesc;
"@
if ($t.Contains($f_anchor)) { $t = $t.Replace($f_anchor, $f_add); Write-Output "字段: OK" } else { Write-Output "字段: MISS" }

# 2. onCreate：绑定向导控件（在 auxFixBtn = findViewById 后）
$b_anchor = '        auxFixBtn = findViewById(R.id.btn_aux_fix);'
$b_add = $b_anchor + $nl + '        guideProgress = findViewById(R.id.progress_guide);' + $nl + '        guideTitle = findViewById(R.id.guide_title);' + $nl + '        guideDesc = findViewById(R.id.guide_desc);'
if ($t.Contains($b_anchor)) { $t = $t.Replace($b_anchor, $b_add); Write-Output "绑定: OK" } else { Write-Output "绑定: MISS" }

# 3. btn_smart_guide 点击：doSmartGuide -> onGuideMainClick（向导主按钮执行当前动作）
$c_anchor = @"
        findViewById(R.id.btn_smart_guide).setOnClickListener(v -> {
            if (busy) { toast("正在执行中，请稍候"); return; }
            doSmartGuide();
        });
"@
$c_anchor = $c_anchor.Replace("`r`n", $nl)
$c_new = @"
        findViewById(R.id.btn_smart_guide).setOnClickListener(v -> {
            if (busy) { toast("正在执行中，请稍候"); return; }
            onGuideMainClick();
        });
"@
$c_new = $c_new.Replace("`r`n", $nl)
if ($t.Contains($c_anchor)) { $t = $t.Replace($c_anchor, $c_new); Write-Output "主按钮: OK" } else { Write-Output "主按钮: MISS" }

# 4. refresh() 末尾 updateAuxFixButton() -> refreshGuide()
$d_anchor = '        updateAuxFixButton();'
$d_new = '        refreshGuide();'
if ($t.Contains($d_anchor)) { $t = $t.Replace($d_anchor, $d_new); Write-Output "刷新挂钩: OK" } else { Write-Output "刷新挂钩: MISS" }

# 5. updateAuxFixButton 方法体 -> refreshGuide + 新增 onGuideMainClick
$e_anchor = @"
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
"@
$e_anchor = $e_anchor.Replace("`r`n", $nl)
$e_new = @"
    /** 刷新向导卡：按当前缺失项更新进度条/步骤标题/说明/主按钮（唯一主入口，其余按钮收进「更多工具」） */
    private void refreshGuide() {
        String ver = TermuxEnvInstaller.termuxVersion(this);
        boolean perm = TermuxEnvInstaller.hasRunCommandPermission(this);
        boolean store = TermuxEnvInstaller.termuxHasStoragePermission(this);
        boolean overlay = TermuxEnvInstaller.termuxHasOverlayPermission(this);
        File rootfs = TermuxEnvInstaller.exportedRootfs(this);
        boolean signerOk = ver != null && TermuxEnvInstaller.termuxSignerMatchesBundled(this);

        String title;
        String desc;
        String btnText;
        String action;
        int progress;
        if (ver == null) {
            progress = 5; title = "第 1 步 · 安装 Termux";
            desc = "内置官方包 108.6MB。点下面按钮 → 系统弹一次安装确认 → 装完回来，我自动继续。\n提示：第一次打开 Termux 会自动初始化（下载基础包），请等出现命令行提示符 $ 再继续。";
            btnText = "📥 安装 Termux"; action = "install";
        } else if (!signerOk) {
            progress = 10; title = "签名不一致 · 先定路线";
            desc = "已装 Termux 与内置包签名不同，覆盖安装会被系统拒绝。推荐继续用现有 Termux：展开「更多工具」→「自检修复通道」粘一行命令即可；或卸载重装内置版（会清空容器环境）。";
            btnText = "📋 查看解决说明"; action = null;
        } else if (!perm) {
            progress = 25; title = "第 2 步 · 授予 RUN_COMMAND 权限";
            desc = "这是 App 能自动控制 Termux 的前提。点下面按钮 → 系统弹窗点「允许」（可勾选始终允许）。";
            btnText = "🔑 授予权限（弹窗点允许）"; action = "perm";
        } else if (rootfs == null) {
            progress = 40; title = "第 3 步 · 导出 Ubuntu 根文件系统";
            desc = "内置 28.5MB 包 → Download/OilQuiz/termux_env/。点一下自动导出，约几秒。";
            btnText = "📦 导出 Ubuntu 包（28.5MB）"; action = "rootfs";
        } else if (!store) {
            progress = 55; title = "第 4 步 · 设置 Termux 存储";
            desc = "复制命令到 Termux 粘贴回车 → 系统弹「允许访问文件」点「允许」。做完切回来，我自动继续。";
            btnText = "🔓 复制存储命令并打开 Termux"; action = "storage";
        } else if (!overlay) {
            progress = 70; title = "第 5 步 · 开启悬浮窗权限";
            desc = "Termux 后台自动执行必需。点下面按钮跳到设置页，打开「显示在其他应用上层」开关后回来。";
            btnText = "🪟 去开启悬浮窗权限"; action = "overlay";
        } else if (!"可用 ✓".equals(channelState)) {
            progress = 80; title = "第 6 步 · 修复命令通道";
            desc = "App 控制 Termux 的通道未通（最常见：allow-external-apps 没生效）。复制一行命令到 Termux 粘贴，会自动重启 Termux 生效。做完切回来，我自动复检并继续。";
            btnText = "⚡ 复制修复命令并打开 Termux"; action = "allowex";
        } else {
            progress = 100; title = "环境就绪 🎉";
            desc = "Termux 通道、权限、Ubuntu 容器、Python 全部就绪。可在「更多工具」里进 Ubuntu 或启动图形界面。";
            btnText = "✅ 环境已就绪（点此重新检测）"; action = "done";
        }
        if (guideProgress != null) { guideProgress.setProgress(progress); }
        if (guideTitle != null) { guideTitle.setText(title); }
        if (guideDesc != null) { guideDesc.setText(desc); }
        MaterialButton main = findViewById(R.id.btn_smart_guide);
        main.setText(btnText);
        main.setEnabled(!busy);
        auxFixAction = action;
    }

    /** 向导主按钮：执行当前步骤动作（install/perm/rootfs/storage/overlay/allowex/done） */
    private void onGuideMainClick() {
        String action = auxFixAction;
        if ("install".equals(action)) {
            doInstallTermux();
        } else if ("perm".equals(action)) {
            startActivity(new Intent(this, TermuxPermissionActivity.class));
            log("已打开 Termux 权限请求：请在弹窗里点「允许」。");
        } else if ("rootfs".equals(action)) {
            doExportRootfs();
        } else if ("storage".equals(action)) {
            copyStorageCmdAndOpen();
        } else if ("overlay".equals(action)) {
            Intent oi = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + TermuxEnvInstaller.TERMUX_PACKAGE));
            startActivity(oi);
            log("已打开 Termux 的悬浮窗权限设置：打开「显示在其他应用上层」开关后回到本页点「刷新」。\n"
                    + "（没有这项的话，走：设置 → 应用管理 → Termux → 高级/其他权限 → 显示在其他应用上层）");
        } else if ("allowex".equals(action)) {
            fixAllowExternalApps();
        } else if ("done".equals(action)) {
            // 已就绪：重新跑一次通道诊断，确认环境真实状态
            toast("重新检测环境…");
            doFixChannel();
        } else {
            // action 为空（签名不一致等需先定路线的场景）：给路线说明
            doSmartGuide();
        }
    }
"@
$e_new = $e_new.Replace("`r`n", $nl)
if ($t.Contains($e_anchor)) { $t = $t.Replace($e_anchor, $e_new); Write-Output "向导方法: OK" } else { Write-Output "向导方法: MISS" }

[IO.File]::WriteAllText($p, $t, (New-Object Text.UTF8Encoding $false))
Write-Output "已写回"

# 精简一键准备按钮：删 6 个与向导主按钮重复的（install/export/run/grant/fix_channel/aux_fix）
# 保留 8 个独特功能：open_vnc / open_ubuntu / open_termux / view_logs / restart_gui / stop_gui / install_api / install_boot / enable_ssh / copy_cmd
$ErrorActionPreference = 'Stop'
$nl = "`n"
$j = "D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\tool\TermuxEnvSetupActivity.java"
$t = [IO.File]::ReadAllText($j)

# ---------- Activity ----------
# 1. 删字段
$f = '    private MaterialButton installBtn;' + $nl + '    private MaterialButton exportBtn;' + $nl + '    private MaterialButton runBtn;' + $nl + '    private MaterialButton grantBtn;'
if ($t.Contains($f)) { $t = $t.Replace($f, '    private MaterialButton copyBtn;'); Write-Output "字段1: OK" } else { Write-Output "字段1: MISS" }
# 看上面 copyBtn 是否已在别处声明：若已存在会重复——先查
$f2 = '    /** 动态辅助按钮：按当前缺失项自动变化（install/perm/rootfs/storage/setup，null=隐藏） */' + $nl + '    private MaterialButton auxFixBtn;'
$f2n = '    /** 向导动作（onGuideMainClick 使用；由 refreshGuide 驱动） */'
if ($t.Contains($f2)) { $t = $t.Replace($f2, $f2n); Write-Output "字段2: OK" } else { Write-Output "字段2: MISS" }

# 2. onCreate 删 findViewById/onClick（install / export / run / grant / fix_channel / auxFixBtn）
$a1 = '        installBtn = findViewById(R.id.btn_install_termux);' + $nl
if ($t.Contains($a1)) { $t = $t.Replace($a1, ''); Write-Output "onCreate-install: OK" } else { Write-Output "onCreate-install: MISS" }
$a2 = '        exportBtn = findViewById(R.id.btn_export_rootfs);' + $nl + '        runBtn = findViewById(R.id.btn_run_setup);' + $nl + '        grantBtn = findViewById(R.id.btn_grant);' + $nl
if ($t.Contains($a2)) { $t = $t.Replace($a2, ''); Write-Output "onCreate-3btn: OK" } else { Write-Output "onCreate-3btn: MISS" }
$a3 = '        installBtn.setOnClickListener(v -> doInstallTermux());' + $nl
if ($t.Contains($a3)) { $t = $t.Replace($a3, ''); Write-Output "onClick-install: OK" } else { Write-Output "onClick-install: MISS" }
$a4 = @'
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
'@
$a4 = $a4.Replace("`r`n", $nl)
if ($t.Contains($a4)) { $t = $t.Replace($a4, ''); Write-Output "onClick-3btn: OK" } else { Write-Output "onClick-3btn: MISS" }
$a5 = @'
        findViewById(R.id.btn_fix_channel).setOnClickListener(v -> {
            if (busy) { toast("正在执行中，请稍候"); return; }
            if (TermuxEnvInstaller.termuxVersion(this) == null) { toast("请先安装 Termux（第 1 步）"); return; }
            doFixChannel();
        });
'@
$a5 = $a5.Replace("`r`n", $nl)
if ($t.Contains($a5)) { $t = $t.Replace($a5, ''); Write-Output "onClick-fix_channel: OK" } else { Write-Output "onClick-fix_channel: MISS" }
$a6 = '        auxFixBtn = findViewById(R.id.btn_aux_fix);' + $nl
if ($t.Contains($a6)) { $t = $t.Replace($a6, ''); Write-Output "onCreate-aux: OK" } else { Write-Output "onCreate-aux: MISS" }
# auxFixBtn onClick 大块：从 setOnClickListener 到 btn_open_ubuntu 之前
$a7 = @'
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
'@
$a7 = $a7.Replace("`r`n", $nl)
if ($t.Contains($a7)) { $t = $t.Replace($a7, ''); Write-Output "onClick-aux: OK" } else { Write-Output "onClick-aux: MISS" }

# 3. refresh() 删 install/export/run/grant 状态更新 + fix_channel setEnabled
$r = @'
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
'@
$r = $r.Replace("`r`n", $nl)
$rn = '        copyBtn.setEnabled(!busy);' + $nl
if ($t.Contains($r)) { $t = $t.Replace($r, $rn); Write-Output "refresh-steps: OK" } else { Write-Output "refresh-steps: MISS" }
$r2 = '        findViewById(R.id.btn_fix_channel).setEnabled(!busy);' + $nl
if ($t.Contains($r2)) { $t = $t.Replace($r2, ''); Write-Output "refresh-fix: OK" } else { Write-Output "refresh-fix: MISS" }

# 4. 删 showAux 方法
$s = @'
    private void showAux(String text, String action) {
        auxFixAction = action;
        if (text == null) {
            auxFixBtn.setVisibility(View.GONE);
        } else {
            auxFixBtn.setText(text);
            auxFixBtn.setVisibility(View.VISIBLE);
        }
    }

'@
$s = $s.Replace("`r`n", $nl)
if ($t.Contains($s)) { $t = $t.Replace($s, ''); Write-Output "showAux: OK" } else { Write-Output "showAux: MISS" }

# 5. done 分支：复检 -> 真正执行环境安装（向导全流程唯一收尾）
$d = @'
            // 已就绪：重新跑一次通道诊断，确认环境真实状态
            toast("重新检测环境…");
            doFixChannel();
'@
$d = $d.Replace("`r`n", $nl)
$dn = @'
            // 已就绪：真正执行 8 步环境安装（proot/容器/Python/GUI）——向导全流程的收尾动作
            toast("环境准备开始…");
            doRunSetup();
'@
$dn = $dn.Replace("`r`n", $nl)
if ($t.Contains($d)) { $t = $t.Replace($d, $dn); Write-Output "done-分支: OK" } else { Write-Output "done-分支: MISS" }

[IO.File]::WriteAllText($j, $t, (New-Object Text.UTF8Encoding $false))
Write-Output "Activity 已写回"

# ---------- 布局：删 6 个按钮块 ----------
$l = "D:\qzq\smartquiz\src\main\res\layout\activity_termux_env_setup.xml"
$x = [IO.File]::ReadAllText($l)
function DelBlock($xml, $startMark, $endMark, $label) {
    $i = $xml.IndexOf($startMark)
    if ($i -lt 0) { Write-Output "${label}: MISS"; return $xml }
    $j2 = $xml.IndexOf($endMark, $i + $startMark.Length)
    if ($j2 -lt 0) { Write-Output "${label}: END-MISS"; return $xml }
    $j2 = $j2 + $endMark.Length
    # 顺带吃掉块后面的空行
    if ($xml.Substring($j2, 2) -eq "`n`n") { $j2 += 1 }
    $xml = $xml.Remove($i, $j2 - $i)
    Write-Output "${label}: OK"
    return $xml
}
# 删 6 个按钮（每个块从 <com.google.android.material.button.MaterialButton ...btn_xxx" 到 </com.google.android.material.button>）
$x = DelBlock $x 'android:id="@+id/btn_install_termux"' '</com.google.android.material.button>' '布局-install'
$x = DelBlock $x 'android:id="@+id/btn_export_rootfs"' '</com.google.android.material.button>' '布局-export'
$x = DelBlock $x 'android:id="@+id/btn_run_setup"' '</com.google.android.material.button>' '布局-run'
$x = DelBlock $x 'android:id="@+id/btn_grant"' '</com.google.android.material.button>' '布局-grant'
$x = DelBlock $x 'android:id="@+id/btn_fix_channel"' '</com.google.android.material.button>' '布局-fix_channel'
$x = DelBlock $x 'android:id="@+id/btn_aux_fix"' '</com.google.android.material.button>' '布局-aux_fix'
[IO.File]::WriteAllText($l, $x, (New-Object Text.UTF8Encoding $false))
Write-Output "布局已写回"

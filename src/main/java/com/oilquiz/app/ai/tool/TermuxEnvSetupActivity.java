package com.oilquiz.app.ai.tool;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;

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
        exportBtn = findViewById(R.id.btn_export_rootfs);
        runBtn = findViewById(R.id.btn_run_setup);
        grantBtn = findViewById(R.id.btn_grant);
        copyBtn = findViewById(R.id.btn_copy_cmd);

        installBtn.setOnClickListener(v -> doInstallTermux());
        exportBtn.setOnClickListener(v -> doExportRootfs());
        runBtn.setOnClickListener(v -> doRunSetup());
        grantBtn.setOnClickListener(v -> {
            startActivity(new Intent(this, TermuxPermissionActivity.class));
            log("已打开 Termux 权限请求：请在弹窗里点「允许」。\n（若没弹窗，说明厂商 ROM 拦了，改用「复制手动命令」粘到 Termux 执行）");
        });
        copyBtn.setOnClickListener(v -> {
            String script = TermuxEnvInstaller.buildSetupScript(rootfsPath());
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("termux-setup", script));
                log("已复制准备脚本。到 Termux 里长按粘贴并回车即可（约 3~6 分钟）。");
                toast("命令已复制到剪贴板");
            }
        });

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private String rootfsPath() {
        File f = TermuxEnvInstaller.exportedRootfs(this);
        return f == null ? null : f.getAbsolutePath();
    }

    /** 刷新状态区与按钮可用性 */
    private void refresh() {
        String ver = TermuxEnvInstaller.termuxVersion(this);
        boolean perm = TermuxEnvInstaller.hasRunCommandPermission(this);
        File rootfs = TermuxEnvInstaller.exportedRootfs(this);
        long apkAsset = TermuxEnvInstaller.assetSize(this, TermuxEnvInstaller.ASSET_TERMUX_APK);
        long rootAsset = TermuxEnvInstaller.assetSize(this, TermuxEnvInstaller.ASSET_ROOTFS);

        StringBuilder sb = new StringBuilder();
        sb.append("Termux：").append(ver == null ? "未安装 ✗" : ("已安装 ✓ v" + ver)).append("\n");
        sb.append("RUN_COMMAND 权限：").append(perm ? "已授予 ✓" : "未授予（需手点一次）").append("\n");
        sb.append("Ubuntu 根文件系统：").append(rootfs == null ? "未导出 ✗" : ("已导出 ✓ " + mb(rootfs.length()))).append("\n");
        sb.append("内置包：Termux ").append(apkAsset > 0 ? mb(apkAsset) : "（未内置，需自行下载）")
                .append(" / Ubuntu ").append(rootAsset > 0 ? mb(rootAsset) : "（未内置）");
        statusView.setText(sb.toString());

        installBtn.setEnabled(!busy && ver == null);
        installBtn.setText(ver == null ? "安装 Termux" : "Termux 已安装，无需重复安装");
        exportBtn.setEnabled(!busy && rootfs == null);
        exportBtn.setText(rootfs == null ? "导出 Ubuntu 根文件系统（28.5 MB）" : "根文件系统已导出，无需重复导出");
        runBtn.setEnabled(!busy && ver != null);
        grantBtn.setEnabled(!busy && !perm);
        copyBtn.setEnabled(!busy);
    }

    private void doInstallTermux() {
        if (TermuxEnvInstaller.termuxVersion(this) != null) {
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
        String script = TermuxEnvInstaller.buildSetupScript(rootfsPath());
        String err = TermuxEnvInstaller.runInTermux(this, script, false);
        if (err == null) {
            log("已把准备脚本下发给 Termux（会打开一个可见会话显示进度）。\n"
                    + "如果 Termux 窗口里报错：多半是 allow-external-apps 没开 —— 用「复制手动命令」粘一次即可（脚本会自己把它打开）。");
            toast("已在 Termux 里开始准备");
        } else {
            log("一键下发失败：" + err + "\n改用「复制手动命令」：粘到 Termux 里执行同样能装好。");
            toast("请用「复制手动命令」");
        }
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

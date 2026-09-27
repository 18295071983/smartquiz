package com.oilquiz.app.ai.tool;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 真机验证「完整 Python 环境（Termux + Ubuntu）」内置包与准备流程（2026-09-27）。
 *
 * 关键点：内置的必须是**官方产物**（F-Droid 签名 Termux + Ubuntu 官方 rootfs），
 * 且导出的根文件系统要真的落到公共目录（Termux 用 termux-setup-storage 后能读到），
 * 生成的准备脚本必须是**合法 bash**（否则用户粘进 Termux 会报语法错）。
 */
@RunWith(AndroidJUnit4.class)
public class TermuxEnvSetupDeviceTest {

    private static Context ctx() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    /** 内置包齐全且大小正确 */
    @Test
    public void bundledAssetsPresent() {
        Context c = ctx();
        long apk = TermuxEnvInstaller.assetSize(c, TermuxEnvInstaller.ASSET_TERMUX_APK);
        long root = TermuxEnvInstaller.assetSize(c, TermuxEnvInstaller.ASSET_ROOTFS);
        System.out.println("[EXP] 内置 Termux APK = " + (apk / 1048576) + " MB, Ubuntu rootfs = " + (root / 1048576) + " MB");
        assertTrue("内置 Termux 包应有 ~108MB（实际 " + apk + " 字节）", apk > 100L * 1024 * 1024);
        assertTrue("内置 Ubuntu rootfs 应有 ~28MB（实际 " + root + " 字节）", root > 25L * 1024 * 1024);
    }

    /** 导出根文件系统到 Download/OilQuiz/termux_env/ */
    @Test
    public void exportRootfsToPublicDir() throws Exception {
        Context c = ctx();
        File out = TermuxEnvInstaller.exportRootfs(c);
        System.out.println("[EXP] 导出: " + out.getAbsolutePath() + "  " + out.length() + " 字节");
        assertTrue("导出文件应存在", out.isFile());
        assertTrue("导出大小应 >25MB", out.length() > 25L * 1024 * 1024);
        assertNotNull("exportedRootfs() 应能查到它", TermuxEnvInstaller.exportedRootfs(c));
    }

    /**
     * 生成的准备脚本必须是合法 bash：用 App 自带的 busybox ash -n 做语法检查，
     * 并核对关键步骤都在（换源 / python3-full / python3-tk / 本地 rootfs 路径 / allow-external-apps）。
     */
    @Test
    public void setupScriptIsValidBash() throws Exception {
        Context c = ctx();
        String script = TermuxEnvInstaller.buildSetupScript("/sdcard/Download/OilQuiz/termux_env/ubuntu-base.tar.gz");
        System.out.println("[EXP] 脚本长度=" + script.length() + " 行数=" + script.split("\n").length);
        assertTrue("应设置 allow-external-apps", script.contains("allow-external-apps=true"));
        assertTrue("应用本地 rootfs 建容器",
                script.contains("ROOTFS=\"/sdcard/Download/OilQuiz/termux_env/ubuntu-base.tar.gz\"")
                        && script.contains("file://$ROOTFS"));
        assertTrue("本地包读不到时应回退清华镜像",
                script.contains("mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage"));
        assertTrue("应装 python3-full", script.contains("python3-full"));
        assertTrue("应装 python3-tk", script.contains("python3-tk"));
        assertTrue("应换清华源", script.contains("mirrors.tuna.tsinghua.edu.cn"));

        // 真语法检查：App 自带 busybox 的 ash -n
        File bb = new File(c.getFilesDir(), "bin/busybox");
        File scriptFile = new File(c.getCacheDir(), "termux_env/_setup_check.sh");
        assertTrue("缓存目录应可用", scriptFile.getParentFile().isDirectory() || scriptFile.getParentFile().mkdirs());
        Files.write(scriptFile.toPath(), script.getBytes(StandardCharsets.UTF_8));
        if (bb.isFile()) {
            Process p = new ProcessBuilder(bb.getAbsolutePath(), "ash", "-n", scriptFile.getAbsolutePath())
                    .redirectErrorStream(true).start();
            p.getOutputStream().close();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = p.getInputStream().read(buf)) > 0) bos.write(buf, 0, n);
            boolean done = p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
            String out = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            System.out.println("[EXP] ash -n exit=" + (done ? p.exitValue() : -1) + " 输出=" + out.trim());
            assertTrue("ash -n 应无语法错误: " + out, done && p.exitValue() == 0);
        } else {
            System.out.println("[EXP] 未找到 " + bb.getAbsolutePath() + "，跳过 ash 语法检查");
        }

        // 复制命令形态也要能被 bash 解析：manualCommand 生成的引号必须闭合
        String manual = TermuxEnvInstaller.manualCommand(script);
        assertTrue("manualCommand 应以 bash -lc 开头", manual.startsWith("bash -lc "));
        int quoteCount = 0;
        for (char ch : manual.toCharArray()) {
            if (ch == '\'') quoteCount++;
        }
        assertTrue("单引号应成对（实际 " + quoteCount + "）", quoteCount % 2 == 0);
    }

    /** 未授予 RUN_COMMAND 权限时，一键下发要给出明确原因（不静默失败） */
    @Test
    public void runInTermuxReportsMissingPermission() {
        Context c = ctx();
        String ver = TermuxEnvInstaller.termuxVersion(c);
        System.out.println("[EXP] Termux 版本=" + ver + "  权限=" + TermuxEnvInstaller.hasRunCommandPermission(c));
        if (ver == null) {
            System.out.println("[EXP] 本机没装 Termux，跳过下发测试");
            return;
        }
        if (TermuxEnvInstaller.hasRunCommandPermission(c)) {
            System.out.println("[EXP] 权限已授予，跳过（避免真的跑安装）");
            return;
        }
        String err = TermuxEnvInstaller.runInTermux(c, "echo hi", false);
        System.out.println("[EXP] 未授权时下发返回: " + err);
        assertNotNull("未授权应返回原因", err);
        assertTrue("原因应提到权限: " + err, err.contains("权限"));
    }

    /** 界面布局能按主题 inflate，关键控件齐全 */
    @Test
    public void layoutInflates() {
        Context base = ctx();
        Context themed = new android.view.ContextThemeWrapper(base, base.getApplicationInfo().theme);
        android.view.View root = android.view.LayoutInflater.from(themed)
                .inflate(com.oilquiz.app.R.layout.activity_termux_env_setup, null);
        int[] ids = {com.oilquiz.app.R.id.env_status, com.oilquiz.app.R.id.env_log,
                com.oilquiz.app.R.id.btn_install_termux, com.oilquiz.app.R.id.btn_export_rootfs,
                com.oilquiz.app.R.id.btn_run_setup, com.oilquiz.app.R.id.btn_grant,
                com.oilquiz.app.R.id.btn_copy_cmd};
        StringBuilder missing = new StringBuilder();
        for (int id : ids) {
            if (root.findViewById(id) == null) {
                missing.append(missing.length() == 0 ? "" : ", ")
                        .append(base.getResources().getResourceEntryName(id));
            }
        }
        System.out.println("[EXP] 环境准备页 inflate 成功，缺失: " + (missing.length() == 0 ? "(无)" : missing));
        assertFalse("布局缺控件: " + missing, missing.length() > 0);
    }

    /**
     * App → Termux 下发链路（一键准备的命脉）：
     * 通过 RUN_COMMAND 让 Termux 跑一条命令写标记文件；文件在 Termux 私有目录，
     * 用例只能断言"下发被接受"，实际落地由外部 adb 核对（_quiz_push.txt = PUSH_OK）。
     */
    @Test
    public void pushCommandThroughTermux() {
        Context c = ctx();
        if (TermuxEnvInstaller.termuxVersion(c) == null) {
            System.out.println("[EXP] 未装 Termux，跳过下发链路测试");
            return;
        }
        if (!TermuxEnvInstaller.hasRunCommandPermission(c)) {
            System.out.println("[EXP] 未授予 RUN_COMMAND 权限，跳过下发链路测试");
            return;
        }
        String script = "echo PUSH_OK > /data/data/com.termux/files/home/_quiz_push.txt";
        String err = TermuxEnvInstaller.runInTermux(c, script, true);
        System.out.println("[EXP] 下发结果: " + (err == null ? "已接受（后台执行）" : err));
        org.junit.Assert.assertNull("下发应被接受: " + err, err);
        // 给 Termux 一点时间执行
        try {
            Thread.sleep(6000);
        } catch (InterruptedException ignored) {
        }
    }

}
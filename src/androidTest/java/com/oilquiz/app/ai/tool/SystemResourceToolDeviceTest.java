package com.oilquiz.app.ai.tool;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 真机(instrumented)验证 system_resource 的 shell_command / termux_exec。
 * 为什么要有它：本机 adb 输入注入被 ROM 禁用，没法用 UI 自动化驱动 agent，
 * 这段测试直接以 App 自身 uid/权限调用工具，等价于 agent 的调用路径。
 *
 * 运行：.\gradlew.bat connectedDebugAndroidTest --tests "*SystemResourceToolDeviceTest*"
 */
@RunWith(AndroidJUnit4.class)
public class SystemResourceToolDeviceTest {

    private SystemResourceTool tool;

    @Before
    public void setUp() {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        tool = new SystemResourceTool(ctx);
    }

    private AIToolResult shell(String cmd) {
        Map<String, Object> p = new HashMap<>();
        p.put("action", "shell_command");
        p.put("command", cmd);
        return tool.execute(p);
    }

    private String out(AIToolResult r) {
        if (!r.isSuccess()) {
            return "FAIL: " + r.getErrorMessage();
        }
        Object res = r.getResult();
        if (res instanceof Map) {
            Object o = ((Map<?, ?>) res).get("output");
            return String.valueOf(o);
        }
        return String.valueOf(res);
    }

    /** 内置 busybox 工具链（不需要 Termux、不需要任何权限） */
    @Test
    public void busyboxToolkitWorks() {
        AIToolResult piped = shell("echo hello | sed s/hello/HELLO/");
        System.out.println("[TEST] sed      => " + out(piped));
        assertTrue("busybox sed 应可用: " + out(piped), out(piped).contains("HELLO"));

        System.out.println("[TEST] md5sum   => " + out(shell("md5sum /system/bin/sh")));
        System.out.println("[TEST] busybox  => " + out(shell("busybox | head -2")));
        System.out.println("[TEST] awk/grep => " + out(shell("printf 'b\\na\\nc\\n' | sort | tr a-z A-Z")));
        System.out.println("[TEST] toolkit  => " + piped.getResult());
        System.out.println("[TEST] find     => " + out(shell("find $HOME -maxdepth 2 -name *.db 2>/dev/null | head -3")));
    }

    /** 安全层回归：2>/dev/null 放行、/proc 拦截、sleep 秒拒 */
    @Test
    public void securityChecks() {
        AIToolResult devNull = shell("ls /sdcard/Download 2>/dev/null");
        System.out.println("[TEST] 2>/dev/null => success=" + devNull.isSuccess() + " " + out(devNull));
        assertTrue("2>/dev/null 应放行", devNull.isSuccess());

        AIToolResult proc = shell("ls /proc");
        System.out.println("[TEST] ls /proc   => success=" + proc.isSuccess() + " err=" + proc.getErrorMessage());
        assertFalse("ls /proc 应被拦", proc.isSuccess());

        AIToolResult sleeping = shell("sleep 30");
        System.out.println("[TEST] sleep 30   => success=" + sleeping.isSuccess() + " err=" + sleeping.getErrorMessage());
        assertFalse("sleep 30 应被秒拒", sleeping.isSuccess());

        AIToolResult rm = shell("rm -rf /sdcard/x");
        System.out.println("[TEST] rm -rf     => success=" + rm.isSuccess() + " err=" + rm.getErrorMessage());
        assertFalse("rm 应被拦", rm.isSuccess());
    }

    /** 真超时：25 秒强杀并返回已产生输出（旧实现在这里会永远卡住，最后被框架 30s 报成 null） */
    @Test
    public void realTimeoutKillsHangingCommand() {
        long t0 = System.currentTimeMillis();
        AIToolResult r = shell("sh -c 'echo before-hang; sleep 40'");
        long ms = System.currentTimeMillis() - t0;
        String text = out(r);
        System.out.println("[TEST] timeout in " + ms + "ms => " + text);
        assertTrue("应在 24~31 秒之间被强杀（实际 " + ms + "ms）", ms >= 24000 && ms < 31000);
        assertTrue("超时信息里应带已产生的输出: " + text, text.contains("before-hang"));
    }

    /** 探测内置 busybox 哪些 applet 在 App 域可用（静态 musl 二进制部分 applet 会被 seccomp SIGSYS） */
    @Test
    public void busyboxAppletProbe() {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // 走真实调用路径（shell_command 会注入 PATH 与 LD_LIBRARY_PATH），而不是裸 ProcessBuilder
        System.out.println("[EXP] bootstrap => " + out(shell("true")));
        System.out.println("[EXP] busybox-version => " + out(shell("busybox | head -1")));
        for (String a : new String[]{"busybox", "wget", "vi", "nc", "sh", "ash", "awk", "sed", "grep"}) {
            String r = out(shell(a + " --help 2>&1 | head -1"));
            System.out.println("[EXP] applet:" + a + " => " + (r.length() > 140 ? r.substring(0, 140) : r));
        }
        String[] applets = {"true", "false", "echo", "cat", "ls", "uname", "id", "sort", "head", "tail",
                "md5sum", "base64", "wc", "tr", "cut", "sed", "grep", "awk", "find", "xargs",
                "tar", "gzip", "wget", "vi", "nc", "sh", "ash", "du", "df", "ps", "printf"};
    }

    /**
     * 关键实验：App 自己写进 data 目录的文件能不能 exec？
     *   · nativeLibraryDir = 系统解压的，理论上可 exec（busybox 就装在那）
     *   · filesDir / 工作区 = 自己写的，targetSdk>=29 是否被 W^X 拦，必须实测
     * 结论决定 proot + Alpine rootfs（apk add）这条路能不能走。
     */
    @Test
    public void execFromAppDataDirExperiment() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File lib = new File(ctx.getApplicationInfo().nativeLibraryDir, "libbusybox.so");
        System.out.println("[EXP] nativeLibraryDir exists=" + lib.exists()
                + " canExecute=" + lib.canExecute() + " path=" + lib.getAbsolutePath());
        execAndPrint("baseline-nativeLibDir", lib.getAbsolutePath() + " echo NATIVE_OK");

        File inFiles = new File(ctx.getFilesDir(), "busybox_in_filesdir");
        copyFile(lib, inFiles);
        inFiles.setExecutable(true, false);
        System.out.println("[EXP] filesDir copy canExecute=" + inFiles.canExecute());
        execAndPrint("filesDir", inFiles.getAbsolutePath() + " echo FILESDIR_OK");

        // 软链接指向 nativeLibraryDir：能否 exec（applet 就是靠软链接工作的）
        File link = new File(ctx.getFilesDir(), "busybox_symlink");
        try {
            java.nio.file.Files.deleteIfExists(link.toPath());
            java.nio.file.Files.createSymbolicLink(link.toPath(), lib.toPath());
            System.out.println("[EXP] symlink created canExecute=" + link.canExecute());
            execAndPrint("symlink-to-nativeLibDir", link.getAbsolutePath() + " echo SYMLINK_OK");
            execAndPrint("symlink-busybox-version", link.getAbsolutePath() + " | head -1");
        } catch (Exception e) {
            System.out.println("[EXP] symlink EXCEPTION: " + e);
        }

        File ws = new File(ctx.getFilesDir(), "agent_workspace");
        if (!ws.exists()) {
            ws.mkdirs();
        }
        File inWs = new File(ws, "busybox_in_workspace");
        copyFile(lib, inWs);
        inWs.setExecutable(true, false);
        System.out.println("[EXP] workspace copy canExecute=" + inWs.canExecute());
        execAndPrint("workspace", inWs.getAbsolutePath() + " echo WORKSPACE_OK");
    }

    private void execAndPrint(String label, String cmd) {
        try {
            Process p = new ProcessBuilder("/system/bin/sh", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('|');
            }
            boolean done = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            System.out.println("[EXP] " + label + " done=" + done + " exit=" + (done ? p.exitValue() : -999)
                    + " out=" + sb);
        } catch (Exception e) {
            System.out.println("[EXP] " + label + " EXEC-THREW: " + e);
        }
    }

    private void copyFile(File src, File dst) throws Exception {
        InputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        out.close();
        in.close();
    }

    /** Termux 桥：未授权时应提示并拉起授权弹窗（授权后这里会返回真实 stdout/exit_code） */
    @Test
    public void termuxExecReports() {
        Map<String, Object> p = new HashMap<>();
        p.put("action", "termux_exec");
        p.put("command", "uname -a; id; echo TERMUX_OK");
        AIToolResult r = tool.execute(p);
        System.out.println("[TEST] termux success=" + r.isSuccess()
                + " err=" + r.getErrorMessage() + " res=" + r.getResult());
    }
}

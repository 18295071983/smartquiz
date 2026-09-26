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
 * 运行（本机 MIUI 会拦截 Gradle 的自动安装，报 INSTALL_FAILED_USER_RESTRICTED，所以用 am instrument）：
 *   adb install -r build\outputs\apk\debug\*.apk 且 adb install -r build\outputs\apk\androidTest\debug\*.apk
 *   adb shell am instrument -w -e class com.oilquiz.app.ai.tool.SystemResourceToolDeviceTest com.oilquiz.app.test/androidx.test.runner.AndroidJUnitRunner
 * 单个用例：-e class ...SystemResourceToolDeviceTest#方法名（如 #busyboxAppletProbe）
 */
@RunWith(AndroidJUnit4.class)
public class SystemResourceToolDeviceTest {

    private SystemResourceTool tool;

    @Before
    public void setUp() {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        tool = new SystemResourceTool(ctx);
    }

    /** 按已有参数 map 执行 linux_shell 并取 output 文本 */
    private String shellOut(java.util.Map<String, Object> params) {
        return out(new com.oilquiz.app.ai.tool.LinuxShellTool(
                InstrumentationRegistry.getInstrumentation().getTargetContext()).execute(params));
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

    /** 护栏已全部移除：这些以前被拦的写法现在都必须放行；另外验证 wget 的 https（走 App 内下载服务） */
    @Test
    public void guardrailsRemoved() {
        AIToolResult rm = shell("rm -rf $HOME/__no_such_dir__ ; echo rm-ok");
        System.out.println("[TEST] rm -rf   => success=" + rm.isSuccess() + " " + out(rm).replace('\n', '|'));
        assertTrue("rm 应放行", rm.isSuccess());

        AIToolResult proc = shell("ls /proc | head -3");
        System.out.println("[TEST] ls /proc => success=" + proc.isSuccess() + " " + out(proc).replace('\n', '|'));
        assertTrue("ls /proc 应放行", proc.isSuccess());

        AIToolResult sub = shell("echo year=$(date +%Y) home=$HOME");
        System.out.println("[TEST] 命令替换 => success=" + sub.isSuccess() + " " + out(sub).replace('\n', '|'));
        assertTrue("命令替换应放行", sub.isSuccess());

        AIToolResult https = shell("wget -O $HOME/ws_https_test.html https://www.baidu.com && wc -c < $HOME/ws_https_test.html");
        String h = out(https).trim().replace('\n', '|');
        System.out.println("[TEST] wget https => success=" + https.isSuccess() + " out=" + h);
        assertTrue("wget https 应下到内容，实际: " + h, https.isSuccess() && h.matches("(?s).*\\d{4,}.*"));
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

    /**
     * Python 通道也要能用内置 busybox。
     * 实测坑：shell_command 注入了 PATH/LD_LIBRARY_PATH，而 python_execute 里 subprocess 用的是
     * Python 进程自己的环境，两条都没有 → which busybox 找不到、裸跑启动器报 libbusybox.so not found。
     */
    @Test
    public void pythonSubprocessSeesBusybox() {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SystemResourceTool.prepareToolkit(ctx);
        String code = "import os, subprocess\n"
                + "print('BINDIR=' + str(os.environ.get('BUSYBOX_BIN_DIR')))\n"
                + "print('LD=' + str(os.environ.get('LD_LIBRARY_PATH')))\n"
                + "p = subprocess.run('busybox | head -1', shell=True, capture_output=True, text=True)\n"
                + "print('RUN=' + (p.stdout.strip() or p.stderr.strip()))\n"
                + "q = subprocess.run('echo hi | sed s/hi/HI/', shell=True, capture_output=True, text=True)\n"
                + "print('SED=' + q.stdout.strip())\n";
        com.oilquiz.app.ai.python.PythonToolManager.ExecutionResult r =
                com.oilquiz.app.ai.python.PythonToolManager.getInstance(ctx).executeCode(code, null);
        String flat = String.valueOf(r.stdout).replace('\n', '|').replace('\r', ' ');
        System.out.println("[EXP] python success=" + r.success + " stdout=" + flat + " err=" + r.error);
        String bin = ctx.getFilesDir().getAbsolutePath() + "/bin";
        // 注：为了让系统二进制（/system/bin/curl）不被我们的 libcrypto 污染，全局 LD_LIBRARY_PATH 已移除，
        // 内置工具改由 liblauncher.so 自带库路径；这里只校验 BINDIR 与工具本身可用。
        assertTrue("BINDIR 应指向 " + bin + "，实际输出: " + flat, flat.contains("BINDIR=" + bin));
        assertTrue("python 里 busybox 应能执行，实际输出: " + flat, flat.contains("BusyBox v1.38.0"));
        assertTrue("python 里 sed 管道应可用，实际输出: " + flat, flat.contains("SED=HI"));
    }

    /**
     * 裸跑：清空环境（env -i）直接执行内置 busybox 与 applet 软链接。
     * 以前会报 CANNOT LINK EXECUTABLE ... library "libbusybox.so" not found，
     * 因为要把 LD_LIBRARY_PATH 指向库所在目录；现已把三个 ELF 的 RUNPATH 补成 $ORIGIN。
     */
    @Test
    public void bareRunWithoutEnv() {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SystemResourceTool.prepareToolkit(ctx);
        String binDir = ctx.getFilesDir().getAbsolutePath() + "/bin";
        String r = out(shell("env -i " + binDir + "/busybox | head -1")).trim();
        System.out.println("[EXP] bare busybox => " + r);
        assertTrue("裸跑 busybox 应能执行（RUNPATH=$ORIGIN）: " + r, r.contains("BusyBox v1.38.0"));
        String r2 = out(shell("env -i " + binDir + "/md5sum /system/bin/sh")).trim();
        System.out.println("[EXP] bare md5sum => " + r2);
        assertTrue("裸跑 applet 应能执行: " + r2, r2.matches("(?s).*[0-9a-f]{32}.*"));
    }

    /** 路由可运行时改写：把 jq 限成只用 toybox（没有）应当失败，reset 后恢复 */
    @Test
    public void routeActionWorks() {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SystemResourceTool.prepareToolkit(ctx);
        com.oilquiz.app.ai.tool.LinuxShellTool shell = new com.oilquiz.app.ai.tool.LinuxShellTool(ctx);
        java.util.Map<String, Object> p = new java.util.HashMap<>();
        p.put("action", "route");
        p.put("command", "jq");
        p.put("order", "b");
        System.out.println("[EXP] route set => " + shell.execute(p).getResult());
        String ok = shellOut(execOf("jq --version"));
        p.put("order", "t");   // toybox 没有 jq，且不允许回退到内置 -> 应当失败
        System.out.println("[EXP] route set t => " + shell.execute(p).getResult());
        String bad = shellOut(execOf("jq --version 2>&1"));
        System.out.println("[EXP] jq with order=t => " + bad.trim());
        p.put("order", "reset");
        System.out.println("[EXP] route reset => " + shell.execute(p).getResult());
        String back = shellOut(execOf("jq --version"));
        System.out.println("[EXP] jq after reset => " + back.trim());
        assertTrue("默认应可用: " + ok, ok.contains("jq-"));
        assertTrue("限定只有 toybox 且无实现时应报错: " + bad, !bad.contains("jq-"));
        assertTrue("reset 后应恢复: " + back, back.contains("jq-"));
    }

    /** 小工具：把 exec 参数包成 map 给 LinuxShellTool 用 */
    private java.util.Map<String, Object> execOf(String command) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("action", "exec");
        m.put("command", command);
        return m;
    }

    /** 路由器路径解析：APP_FILES/HOME 都不可用时，按 uid 自动推导（多用户/工作资料也适用） */
    @Test
    public void routerPathFallback() {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SystemResourceTool.prepareToolkit(ctx);
        // 清掉 APP_FILES 并把 HOME 指到不存在的目录：路由器必须忽略，改按 getuid()/100000 推导
        String r = out(shell("env -u APP_FILES HOME=/nonexistent-dir ash -c 'echo ROUTER_OK'")).trim();
        System.out.println("[EXP] router auto-path => " + r);
        assertTrue("HOME 不可用时应自动推导数据目录: " + r, r.contains("ROUTER_OK"));
        String r2 = out(shell("APP_FILES=" + ctx.getFilesDir().getAbsolutePath() + " ash -c 'echo APPFILES_OK'")).trim();
        System.out.println("[EXP] router APP_FILES => " + r2);
        assertTrue("APP_FILES 应被采用: " + r2, r2.contains("APPFILES_OK"));
    }

    /** 路由：内置 -> 系统 -> busybox -> toybox，任一环节不可用不应把命令搞挂 */
    @Test
    public void routingWorks() {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SystemResourceTool.prepareToolkit(ctx);
        // 注意：ls / 对普通 App 本来就是 Permission denied（根目录不可读），要用可访问目录
        String r = out(shell("ls $HOME >/dev/null && echo LS_OK; cat /system/build.prop >/dev/null 2>&1; echo CAT_DONE;"
                + " ash -c 'echo ASH_OK'; sed --version 2>&1 | head -1; jq --version; openssl version;"
                + " wget -O /dev/null https://www.baidu.com >/dev/null 2>&1 && echo WGET_OK")).replace('\n', '|');
        System.out.println("[EXP] routing => " + r);
        assertTrue("路由应保证常用命令都可用: " + r,
                r.contains("LS_OK") && r.contains("CAT_DONE") && r.contains("ASH_OK")
                        && r.contains("jq-") && r.contains("OpenSSL") && r.contains("WGET_OK"));
    }

    /** 独立工具 linux_shell + 新内置工具链（curl/jq/rg/sqlite3/zstd…） */
    @Test
    public void linuxShellToolWorks() {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SystemResourceTool.prepareToolkit(ctx);
        com.oilquiz.app.ai.tool.LinuxShellTool shell = new com.oilquiz.app.ai.tool.LinuxShellTool(ctx);
        java.util.Map<String, Object> p = new java.util.HashMap<>();
        p.put("action", "exec");
        p.put("command", "jq --version; rg --version | head -1; sqlite3 --version; zstd --version 2>&1 | head -1;"
                + " curl -sI https://www.baidu.com | head -1");
        AIToolResult r = shell.execute(p);
        String o = String.valueOf(((java.util.Map<?, ?>) r.getResult()).get("output")).replace('\n', '|');
        System.out.println("[EXP] linux_shell exec => " + o);
        // sqlite3 --version 只输出 "3.53.4 2026-07-24 ..."（不含 sqlite 字样），所以按版本号判断
        assertTrue("jq/rg/sqlite3/zstd/curl 都应可用: " + o, r.isSuccess() && o.contains("jq-")
                && o.contains("ripgrep") && o.contains("3.53.") && o.contains("Zstandard") && o.contains("HTTP"));
        p.put("action", "tools");
        AIToolResult t = shell.execute(p);
        String tools = String.valueOf(((java.util.Map<?, ?>) t.getResult()).get("tools")).replace('\n', '|');
        System.out.println("[EXP] linux_shell tools => " + tools.substring(0, Math.min(400, tools.length())));
        assertTrue("tools 应列出内置工具", t.isSuccess() && tools.contains("openssl"));
    }

    /** Python 也能用同一套内置工具（android_shell 模块） */
    @Test
    public void pythonAndroidShellWorks() {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SystemResourceTool.prepareToolkit(ctx);
        String code = "import android_shell\n"
                + "r = android_shell.run('curl -sI https://www.baidu.com | head -1')\n"
                + "print('HTTP=' + r['stdout'].strip())\n"
                + "print('JQ=' + str(android_shell.tool_path('jq')))\n"
                + "print('RG=' + android_shell.run('rg --version')[ 'stdout'].split()[1])\n";
        com.oilquiz.app.ai.python.PythonToolManager.ExecutionResult res =
                com.oilquiz.app.ai.python.PythonToolManager.getInstance(ctx).executeCode(code, null);
        String out = String.valueOf(res.stdout).replace('\n', '|');
        System.out.println("[EXP] python android_shell => " + out);
        assertTrue("python 里 curl/jq/rg 都应可用: " + out,
                out.contains("HTTP=") && out.contains("jq") && out.contains("RG="));
    }

    /** 只读模式开关：默认 full（不拦）→ readonly（拦 rm/敏感路径）→ 切回 full 恢复放行 */
    @Test
    public void shellModeSwitch() {
        java.util.Map<String, Object> p = new java.util.HashMap<>();
        p.put("action", "shell_mode");
        p.put("mode", "full");
        System.out.println("[TEST] switch full => " + tool.execute(p).getResult());
        assertTrue("full 模式 rm 应放行", shell("rm -rf $HOME/__no_such__ ; echo ok").isSuccess());
        p.put("mode", "readonly");
        System.out.println("[TEST] switch readonly => " + tool.execute(p).getResult());
        AIToolResult rm = shell("rm -rf /sdcard/x");
        System.out.println("[TEST] readonly rm => success=" + rm.isSuccess() + " err=" + rm.getErrorMessage());
        assertFalse("[readonly] rm 应被拦", rm.isSuccess());
        AIToolResult proc = shell("ls /proc");
        System.out.println("[TEST] readonly /proc => success=" + proc.isSuccess() + " err=" + proc.getErrorMessage());
        assertFalse("[readonly] /proc 应被拦", proc.isSuccess());
        p.put("mode", "full");
        tool.execute(p);
        assertTrue("切回 full 后 rm 应放行", shell("rm -rf $HOME/__no_such__ ; echo ok").isSuccess());
    }

    /** 内置 openssl：真 TLS 能力（busybox 的 wget 没有 TLS，这个有） */
    @Test
    public void opensslTlsWorks() {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SystemResourceTool.prepareToolkit(ctx);
        String v = out(shell("openssl version")).trim();
        System.out.println("[EXP] openssl version => " + v);
        assertTrue("openssl 应可用: " + v, v.contains("OpenSSL"));
        String hs = out(shell("echo | openssl s_client -connect www.baidu.com:443 -servername www.baidu.com 2>&1"
                + " | grep -E 'CONNECTED|subject=|issuer=|Verify return code' | head -6"));
        String flat = hs.replace('\n', '|');
        System.out.println("[EXP] openssl handshake => " + flat);
        assertTrue("应完成 TLS 握手并拿到证书: " + flat, flat.contains("CONNECTED") || flat.contains("subject="));
    }

    /** 内置 openssh：ssh/scp/sftp/ssh-keygen 可用（依赖库解包在 files/lib，可执行文件在 nativeLibraryDir） */
    @Test
    public void opensshWorks() {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SystemResourceTool.prepareToolkit(ctx);
        String v = out(shell("ssh -V 2>&1 | head -1")).trim();
        System.out.println("[EXP] ssh -V => " + v);
        assertTrue("ssh 应可执行: " + v, v.contains("OpenSSH"));
        String kg = out(shell("mkdir -p $HOME/.ssh; rm -f $HOME/.ssh/test_k*; ssh-keygen -t ed25519 -f $HOME/.ssh/test_k -N '' -q"
                + " && ls -l $HOME/.ssh/test_k | wc -l")).trim();
        System.out.println("[EXP] ssh-keygen => " + kg.replace('\n', '|'));
        assertTrue("ssh-keygen 应能生成密钥: " + kg, kg.contains("1"));
        String net = out(shell("ssh -o StrictHostKeyChecking=no -o ConnectTimeout=8 -p 443 -T git@ssh.github.com 2>&1 | head -3"))
                .replace('\n', '|');
        System.out.println("[EXP] ssh 真实连接 => " + net);
    }

    /**
     * 决定 ssh 打包方案的关键实验：动态库能不能从 App 数据目录加载？
     * （execve 数据目录已被证明禁止；若 dlopen 允许，则一批带版本号 SONAME 的库可以
     *   原样解包到数据目录用 LD_LIBRARY_PATH 加载，省掉逐个改名补丁）
     */
    @Test
    public void dlopenFromAppDataProbe() {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try {
            java.io.File src = new java.io.File(ctx.getApplicationInfo().nativeLibraryDir, "libz.so");
            java.io.File dst = new java.io.File(ctx.getFilesDir(), "probe_libz.so");
            java.nio.file.Files.copy(src.toPath(), dst.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            // 用 System.load（dlopen）判断：数据目录里的 .so 能不能被加载
            try {
                System.load(dst.getAbsolutePath());
                System.out.println("[EXP] dlopen-from-appdata => OK（数据目录的 .so 可以 dlopen）");
            } catch (Throwable e) {
                System.out.println("[EXP] dlopen-from-appdata => FAILED: " + e);
            }
        } catch (Exception e) {
            System.out.println("[EXP] dlopen-from-appdata EXCEPTION " + e);
        }
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

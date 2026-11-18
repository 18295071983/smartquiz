package com.oilquiz.app.ai.dsh;

import android.content.Context;
import android.system.Os;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 方案 B：DSH 运行时完全内置（2026-11-19）。
 *
 * <p>不依赖 Termux：proot 二进制来自 jniLibs，Ubuntu rootfs / Node / DSH / 插件来自 APK assets，
 * 首次启动解压到 {@code files/dsh/rootfs}，然后由 App 自己 spawn：</p>
 *
 * <pre>
 * libproot.so -r rootfs -0 -b /dev -b /proc -b /sys -b /sdcard -w /root \
 *     /usr/local/bin/dsh phone --no-open --host 127.0.0.1 --port 7788
 * </pre>
 *
 * <p>端口 7788 = DSH Web（登录账号用），8218 = smartquiz-remote 桥接。token 从 DSH 输出里解析。</p>
 */
public final class EmbeddedDsh {

    private static final String TAG = "EmbeddedDsh";
    private static final Pattern TOKEN_RE = Pattern.compile("token=([A-Za-z0-9_-]+)");
    private static final Object LOCK = new Object();

    private static Process process;
    private static volatile String token = "";
    private static volatile String lastLine = "";

    private EmbeddedDsh() {
    }

    public static File base(Context c) {
        return new File(c.getFilesDir(), "dsh");
    }

    public static File rootfs(Context c) {
        return new File(base(c), "rootfs");
    }

    public static File logFile(Context c) {
        return new File(base(c), "dsh.log");
    }

    public static boolean isPrepared(Context c) {
        return new File(rootfs(c), "opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js").isFile()
                && new File(rootfs(c), "usr/local/bin/dsh").isFile();
    }

    public static boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive();
    }

    /** 解压运行时（首次约几分钟）。阻塞，必须在后台线程调用。 */
    public static void prepare(Context c) throws Exception {
        synchronized (LOCK) {
            if (isPrepared(c)) return;
            File r = rootfs(c);
            File base = base(c);
            base.mkdirs();
            if (r.exists()) deleteRecursive(r);
            if (!r.mkdirs()) throw new java.io.IOException("无法创建 rootfs 目录");
            Log.i(TAG, "extract rootfs");
            DshTarGz.extractAsset(c, "termux_env/ubuntu-base-24.04.5-base-arm64.targz.bin", r, "");
            Log.i(TAG, "extract node");
            DshTarGz.extractAsset(c, "dsh_runtime/node.tar.bin", r, "");
            Log.i(TAG, "extract dsh");
            DshTarGz.extractAsset(c, "dsh_runtime/dsh.tar.bin", r, "opt/dsh");
            Log.i(TAG, "extract plugin");
            DshTarGz.extractAsset(c, "dsh_runtime/plugin.tar.bin", r, "root/plugin");
            writeRuntimeConfig(c, r);
            Log.i(TAG, "runtime prepared");
        }
    }

    private static void writeRuntimeConfig(Context c, File r) throws Exception {
        File bin = new File(r, "usr/local/bin");
        bin.mkdirs();
        File dsh = new File(bin, "dsh");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(dsh), StandardCharsets.UTF_8)) {
            w.write("#!/bin/bash\n");
            w.write("exec /opt/node/bin/node --expose-internals "
                    + "/opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js \"$@\"\n");
        }
        chmod(dsh, 0755);

        File xdg = new File(bin, "xdg-user-dir");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(xdg), StandardCharsets.UTF_8)) {
            w.write("#!/bin/sh\n");
            w.write("case \"$1\" in\n  DOCUMENTS) echo \"$HOME/Documents\" ;;\n  *) echo \"$HOME\" ;;\nesac\n");
        }
        chmod(xdg, 0755);

        new File(r, "root/Documents").mkdirs();
        new File(r, "root/.dsh").mkdirs();
        new File(r, "tmp").mkdirs();
        File resolv = new File(r, "etc/resolv.conf");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(resolv), StandardCharsets.UTF_8)) {
            w.write("nameserver 223.5.5.5\nnameserver 8.8.8.8\n");
        }
        // 内置场景：预生成插件令牌，App 直接读用；不再走"电脑端配对/二维码/设备令牌"那套。
        File pluginDir = new File(r, "root/plugin");
        pluginDir.mkdirs();
        File tokFile = new File(pluginDir, ".token");
        if (!tokFile.isFile() || tokFile.length() == 0) {
            byte[] raw = new byte[16];
            new java.security.SecureRandom().nextBytes(raw);
            StringBuilder sb = new StringBuilder(32);
            for (byte b : raw) sb.append(String.format("%02x", b));
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tokFile),
                    StandardCharsets.UTF_8)) {
                w.write(sb.toString());
            }
            chmod(tokFile, 0600);
        }

        // 首次启动需要的 phone profile（等价于 dsh plugin --profile phone add，免 pnpm）
        File prof = new File(r, "root/.dsh/profiles/phone");
        File profMods = new File(prof, "node_modules/@local");
        profMods.mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(prof, "package.json")),
                StandardCharsets.UTF_8)) {
            w.write("{\"name\":\"dsh-profile-phone\",\"private\":true,\n"
                    + " \"dependencies\":{\"@local/smartquiz-remote\":\"link:/root/plugin\"},\n"
                    + " \"dsh\":{\"profile\":{\"bundles\":[\"@deepseek-ai/dsh-base\","
                    + "\"@deepseek-ai/dsh-web-app\",\"@local/smartquiz-remote\"]}}}\n");
        }
        File profLink = new File(profMods, "smartquiz-remote");
        deleteAnyFile(profLink);
        try {
            Os.symlink("/root/plugin", profLink.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(TAG, "profile plugin symlink failed: " + t.getMessage());
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(prof, "cordis.patch.yml")),
                StandardCharsets.UTF_8)) {
            w.write("- id: ui-settings-general\n"
                    + "  name: \"@deepseek-ai/dsh-client-ui-settings-general\"\n"
                    + "  config:\n    welcomeNoticeVersion: 2026-09-28.1\n"
                    + "- id: agent-default-model\n"
                    + "  name: \"@deepseek-ai/dsh-agent-default-model\"\n"
                    + "  config:\n    provider: deepseek-account\n    model: deepseek-flash\n");
        }

        // 工作区直接指到 App 公共工作区（proot 已 bind /sdcard）
        File ws = new File(r, "root/Documents/deepseek-harness/default-workspace");
        File parent = ws.getParentFile();
        if (parent != null) parent.mkdirs();
        deleteQuietly(ws);
        try {
            Os.symlink("/sdcard/Download/OilQuiz/agent_workspace", ws.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(TAG, "workspace symlink failed: " + t.getMessage());
            ws.mkdirs();
        }
    }

    private static void chmod(File f, int mode) {
        try {
            Os.chmod(f.getAbsolutePath(), mode);
        } catch (Throwable ignored) {
        }
    }

    /** 启动（必要时先解压）。阻塞，后台线程调用。返回状态文字。 */
    public static String start(Context c) {
        synchronized (LOCK) {
            if (isRunning()) return "DSH 已在运行";
            try {
                prepare(c);
            } catch (Throwable t) {
                Log.e(TAG, "prepare failed", t);
                return "运行时解压失败：" + t.getMessage();
            }
            try {
                Process p = spawn(c);
                process = p;
                startLogPump(c, p);
                return "DSH 已启动（首次启动 10~20 秒后 7788 可用）";
            } catch (Throwable t) {
                Log.e(TAG, "spawn failed", t);
                return "启动失败：" + t.getMessage();
            }
        }
    }

    private static Process spawn(Context c) throws Exception {
        String nativeDir = c.getApplicationInfo().nativeLibraryDir;
        File rootfs = rootfs(c);
        File tmp = new File(base(c), "tmp");
        tmp.mkdirs();
        chmod(new File(nativeDir, "libproot.so"), 0755);
        chmod(new File(nativeDir, "libproot_loader.so"), 0755);

        List<String> cmd = new ArrayList<>();
        cmd.add(new File(nativeDir, "libproot.so").getAbsolutePath());
        cmd.add("-r");
        cmd.add(rootfs.getAbsolutePath());
        cmd.add("-0");
        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        cmd.add("-b");
        cmd.add("/sdcard");
        cmd.add("-b");
        cmd.add("/storage");
        cmd.add("-w");
        cmd.add("/root");
        cmd.add("/usr/local/bin/dsh");
        cmd.add("phone");
        cmd.add("--no-open");
        cmd.add("--host");
        cmd.add("127.0.0.1");
        cmd.add("--port");
        cmd.add("7788");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("PROOT_LOADER", new File(nativeDir, "libproot_loader.so").getAbsolutePath());
        env.put("PROOT_LOADER_32", new File(nativeDir, "libproot_loader32.so").getAbsolutePath());
        env.put("PROOT_TMP_DIR", tmp.getAbsolutePath());
        env.put("HOME", "/root");
        env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        env.put("TERM", "xterm-256color");
        env.put("LANG", "C.UTF-8");
        env.put("LD_LIBRARY_PATH", nativeDir);
        Log.i(TAG, "spawn: " + cmd);
        return pb.start();
    }

    private static void startLogPump(Context c, Process p) {
        File log = logFile(c);
        log.getParentFile().mkdirs();
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(),
                    StandardCharsets.UTF_8));
                 Writer w = new OutputStreamWriter(new FileOutputStream(log, true),
                         StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    lastLine = line;
                    w.write(line);
                    w.write('\n');
                    w.flush();
                    Matcher m = TOKEN_RE.matcher(line);
                    if (m.find()) token = m.group(1);
                }
            } catch (Throwable ignored) {
            }
        }, "dsh-log");
        t.setDaemon(true);
        t.start();
    }

    public static void stop(Context c) {
        synchronized (LOCK) {
            Process p = process;
            process = null;
            if (p != null) {
                try {
                    p.destroy();
                    if (p.isAlive()) p.destroyForcibly();
                } catch (Throwable ignored) {
                }
            }
            try {
                Runtime.getRuntime().exec(new String[]{"sh", "-c",
                        "pkill -f 'dsh/lib/bin.js' 2>/dev/null || true"});
            } catch (Throwable ignored) {
            }
        }
    }

    public static String statusText(Context c) {
        if (!isPrepared(c)) return "未准备（首次启动会自动解压运行时，约 2~4 分钟）";
        if (!isRunning()) return "未运行";
        if (token.isEmpty()) return "启动中…（等待 token）";
        return "运行中 · " + loginUrl();
    }

    public static String loginUrl() {
        return "http://127.0.0.1:7788/?token=" + token;
    }

    public static String token() {
        return token;
    }

    /** 插件令牌（桥接 8218 用），从 rootfs 里读；App 拥有这些文件。 */
    public static String pluginToken(Context c) {
        File f = new File(rootfs(c), "root/plugin/.token");
        try {
            if (!f.isFile()) return "";
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            return new String(b, StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void deleteAnyFile(File f) {
        try {
            if (f.isDirectory()) {
                File[] kids = f.listFiles();
                if (kids != null) for (File k : kids) deleteAnyFile(k);
            }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        } catch (Throwable ignored) {
        }
    }

    private static void deleteQuietly(File f) {
        try {
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void deleteRecursive(File f) {
        try {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        } catch (Throwable ignored) {
        }
    }
}

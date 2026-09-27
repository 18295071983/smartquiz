package com.oilquiz.app.ai.tool;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 「完整 Python 环境（Termux + Ubuntu）」的准备逻辑。
 *
 * <p>为什么要内置并引导安装 Termux（而不是把它合并进答题宝）：
 * Android 只允许 **targetSdk &lt; 29** 的应用执行自己私有目录里的二进制文件；Termux 正是靠这一点
 * 才能运行 proot/apt（proot 要 execve 容器里的二进制）。答题宝 targetSdk 35，
 * 无论把什么打包进来都无法在自己进程里跑 proot，因此只能"内置安装包 + 引导安装"。
 *
 * <p>内置两个官方产物（assets/termux_env/，见该目录 README.txt）：
 * · termux-0.118.3-fdroid.apk（F-Droid 官方签名，非 debuggable）
 * · ubuntu-base-24.04.5-base-arm64.tar.gz（Ubuntu 官方根文件系统）
 */
public final class TermuxEnvInstaller {

    public static final String TERMUX_PACKAGE = "com.termux";
    public static final String ASSET_DIR = "termux_env";
    public static final String ASSET_TERMUX_APK = "termux-0.118.3-fdroid.apk";
    /**
     * Ubuntu 根文件系统在 assets 里的名字。
     * 注意后缀故意不是 .gz：**AGP/AAPT 会把 assets 里 .gz 结尾的文件自动解包**
     * （实测打包后条目变成 101.8MB 的 .tar，按原名读取直接失败），所以这里用中性后缀，
     * 导出给用户时再用 {@link #EXPORT_ROOTFS_NAME} 的标准名字。
     */
    public static final String ASSET_ROOTFS = "ubuntu-base-24.04.5-base-arm64.targz.bin";
    /** 导出到公共目录时使用的标准文件名（proot-distro 按扩展名识别归档格式） */
    public static final String EXPORT_ROOTFS_NAME = "ubuntu-base-24.04.5-base-arm64.tar.gz";
    /** 导出的根文件系统所在目录（公共下载目录，Termux 用 termux-setup-storage 后可读） */
    public static final String EXPORT_SUBDIR = "OilQuiz/termux_env";

    /** 清华镜像的 Ubuntu 官方 rootfs（本地安装包读不到时的回退通道） */
    public static final String TUNA_ROOTFS_URL =
            "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/"
                    + "ubuntu-base-24.04.5-base-arm64.tar.gz";
    /** proot-distro 容器名（5.9 的容器目录是 $PREFIX/var/lib/proot-distro/containers/<name>） */
    public static final String CONTAINER_NAME = "ubuntu";
    /** 准备脚本的完整输出日志（Termux 私有目录；排障时直接看它） */
    public static final String SETUP_LOG = ".quiz_env_setup.log";

    public static final String PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";
    private static final String SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION = "com.termux.RUN_COMMAND";
    private static final String E_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String E_ARGS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String E_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String E_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";

    private TermuxEnvInstaller() {
    }

    // ---------- 状态查询 ----------

    /** Termux 已安装则返回版本名（如 0.118.3），否则 null */
    public static String termuxVersion(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return pi.versionName;
        } catch (Exception e) {
            return null;
        }
    }

    /** 是否已授予 Termux 的 RUN_COMMAND 权限（小米上只能由用户在 App 内点"允许"） */
    public static boolean hasRunCommandPermission(Context ctx) {
        return ctx.checkSelfPermission(PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Termux 是否已拿到外部存储权限（决定"用本地 29MB 安装包"还是"联网下 30MB"）。
     *
     * <p>该权限只能由 Termux 自己执行 {@code termux-setup-storage} 后由用户在系统弹窗点「允许」，
     * 答题宝无法代授（MIUI 还禁掉了 pm grant），所以这里只做"预检 + 明确提示"。
     */
    public static boolean termuxHasStoragePermission(Context ctx) {
        if (termuxVersion(ctx) == null) {
            return false;
        }
        String[] perms = {
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE"
        };
        for (String p : perms) {
            if (ctx.getPackageManager().checkPermission(p, TERMUX_PACKAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    /** 资产是否存在（构建时若未内置大包，返回 false → 界面会提示走"下载"通道） */
    public static long assetSize(Context ctx, String name) {
        try (InputStream in = ctx.getAssets().open(ASSET_DIR + "/" + name)) {
            long total = 0;
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
            }
            return total;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 导出的根文件系统（若已导出且大小正常） */
    public static File exportedRootfs(Context ctx) {
        File f = new File(publicDir(ctx), EXPORT_ROOTFS_NAME);
        return (f.isFile() && f.length() > 1024 * 1024) ? f : null;
    }

    private static File publicDir(Context ctx) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                EXPORT_SUBDIR);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    // ---------- 步骤 1：安装 Termux ----------

    /**
     * 把内置的 Termux APK 释放到 cacheDir，并交给系统安装器。
     * 需要用户确认一次（小米/Android 会要求"允许安装未知应用"）。
     */
    public static void installTermux(Activity activity) throws Exception {
        File dir = new File(activity.getCacheDir(), ASSET_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new java.io.IOException("无法创建缓存目录: " + dir);
        }
        File apk = new File(dir, ASSET_TERMUX_APK);
        if (!apk.isFile() || apk.length() <= 0) {
            copyAsset(activity, ASSET_TERMUX_APK, apk);
        }
        Uri uri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".fileprovider", apk);
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(i);
    }

    // ---------- 步骤 2：导出根文件系统 ----------

    /** 把内置的 Ubuntu 根文件系统导出到 Download/OilQuiz/termux_env/，返回导出后的文件 */
    public static File exportRootfs(Context ctx) throws Exception {
        File out = new File(publicDir(ctx), EXPORT_ROOTFS_NAME);
        copyAsset(ctx, ASSET_ROOTFS, out);
        return out;
    }

    private static void copyAsset(Context ctx, String assetName, File dst) throws Exception {
        try (InputStream in = ctx.getAssets().open(ASSET_DIR + "/" + assetName);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[262144];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    // ---------- 步骤 3：在 Termux 里准备容器 ----------

    /**
     * 生成"在 Termux 里执行"的准备脚本。
     *
     * <p>2026-09-27 真机踩坑后重写，硬要求：
     * <ul>
     *   <li><b>幂等</b>：已装过的机器再点一次不能报错。proot-distro 5.9 的 {@code list} 把人类可读
     *       列表打到 <b>stderr</b>（stdout 为空），旧脚本用 {@code list 2>/dev/null | grep -q ubuntu}
     *       必然判成"没装"→ 再 install → {@code Error: container already exists} →
     *       被 {@code set -e} 判死退出 1（用户真机实测所见）。现在用 {@code list -q}（走 stdout）、
     *       {@code list 2>&1} 与容器目录三重判定，命中就跳过。</li>
     *   <li><b>不假装成功</b>：失败打 ❌ 并以 exit 1 结束，全部通过才 ✅ + exit 0。</li>
     *   <li><b>可排障</b>：整段输出 tee 到 {@code ~/.quiz_env_setup.log}，用户说"看日志"就有地方看。</li>
     *   <li><b>说人话</b>：存储权限没给时明确讲清"本地 29MB 包读不到 → 本次联网下 30MB + 怎么授权"。</li>
     * </ul>
     *
     * <p>脚本保持 POSIX 语法（可用 busybox {@code ash -n} 做语法门禁），只依赖 Termux 自带的
     * bash/tee/grep。
     */
    public static String buildSetupScript(String rootfsPath) {
        String root = rootfsPath == null || rootfsPath.isEmpty()
                ? ("/sdcard/Download/" + EXPORT_SUBDIR + "/" + EXPORT_ROOTFS_NAME)
                : rootfsPath;
        return SCRIPT_TEMPLATE
                .replace("__ROOTFS__", root)
                .replace("__TUNA__", TUNA_ROOTFS_URL)
                .replace("__GUI_INNER__", GUI_INNER_COMMAND)
                .replace("__GUI_FILE_B64__", b64(buildGuiFile()));
    }

    // ---------- 图形界面（X11 + VNC）----------

    /** VNC 端口（容器里的 Xvnc 监听 127.0.0.1，仅本机可见） */
    public static final int VNC_PORT = 5900;

    /**
     * 容器内启动图形界面的命令：**用 TigerVNC 的 Xvnc**（X server + VNC 一个进程搞定）。
     *
     * <p>为什么不用 Xvfb + x11vnc（最早那版）：x11vnc 0.9.16 在 proot 下太脆 ——
     * {@code -encodings} 不认、{@code shmget} 被拒（要 -noshm）、{@code -threads} 会空转且不再监听，
     * 而且客户端连上后**时好时坏地不发版本横幅**（真机实测：冷启动时 4 次重连全失败、最后进程直接没了）。
     * 换 Xvnc 后同一个 proot 环境里前台跑满 12 秒毫无问题。
     *
     * <p>三条硬要求：
     * <ol>
     *   <li><b>Xvnc 必须前台常驻</b>（这里用 exec）：proot 会话一退出就会带走所有子进程。</li>
     *   <li><b>启动前必须清残留 socket</b>：{@code :1} 的旧 socket 还在时，Xvnc 会直接
     *       {@code failed to bind socket: Address already in use} 退出（真机踩到过）。</li>
     *   <li>{@code -SecurityTypes None} 免密码、{@code -ac} 免 X 授权、{@code -AlwaysShared} 允许多客户端。</li>
     * </ol>
     */
    public static final String GUI_INNER_COMMAND =
            "pkill -x Xvnc >/dev/null 2>&1; pkill -x x11vnc >/dev/null 2>&1; pkill -x Xvfb >/dev/null 2>&1; "
                    + "pkill -x xclock >/dev/null 2>&1; sleep 1; "
                    + "rm -f /tmp/.X11-unix/X1 /tmp/.X1-lock; "
                    + "exec Xvnc :1 -geometry 1280x720 -depth 24 -rfbport 5900 -localhost "
                    + "-SecurityTypes None -AlwaysShared -ac -desktop OilQuiz";

    /**
     * 演示窗口源码：tkinter 实时时钟 + 一个按钮。
     *
     * <p>为什么要有它：桌面如果完全静止（早先用的 xclock 在 proot 下不走了），
     * VNC 就没有画面变化、也就没有帧更新 —— 用户会以为"只显示两帧/坏了"。
     * 这个窗口每 500ms 刷新一次，顺便还证明了 tkinter（内置 Chaquopy 做不到）真的能用。
     *
     * <p>用 base64 传输写进 Termux 家目录：heredoc 在这种"多层引号 + 非交互 shell"的场景下太容易出岔子。
     */
    public static final String GUI_DEMO_PY = """
            import tkinter as tk
            import tkinter.font as tkfont
            import time, sys
            r = tk.Tk()
            r.title("OilQuiz GUI")
            r.geometry("760x380+40+40")
            r.configure(bg="#0b3d91")
            # 关键：Tk 默认字体是 DejaVu Sans，没有中文字形，中文会显示成方框（tofu）。
            # 把全局默认字体换成容器里装的中文字体，这样所有控件（以及用户自己写的 tkinter 程序，
            # 只要照抄这两行）都有中文。
            default = tkfont.nametofont("TkDefaultFont")
            default.configure(family="WenQuanYi Micro Hei")
            big = tk.Label(r, font=("WenQuanYi Micro Hei", 60, "bold"), fg="white", bg="#0b3d91")
            big.pack(pady=(36, 8))
            tk.Label(r, font=("WenQuanYi Micro Hei", 17), fg="#cfe8ff", bg="#0b3d91",
                     text="答题宝 · Ubuntu 24.04 + tkinter " + sys.version.split()[0]).pack()
            tk.Label(r, font=("WenQuanYi Micro Hei", 15), fg="#9fd0ff", bg="#0b3d91",
                     text="中文字体已装好，不会再显示方框").pack(pady=(4, 0))
            tk.Button(r, text="能点说明输入也通了", font=("WenQuanYi Micro Hei", 15)).pack(pady=12)
            def tick():
                big.config(text=time.strftime("%H:%M:%S"))
                r.after(500, tick)
            tick()
            r.mainloop()
            """;

    /**
     * fontconfig 兜底：把通用族（sans-serif/serif/monospace）优先指向中文字体。
     *
     * <p>只装字体还不够：很多程序（含 Tk 默认字体）会点名 DejaVu Sans，而它没有中文字形 → 方框。
     * 这条规则让"没点名具体字体"的程序都能出中文；点名了 DejaVu 的，见 GUI_DEMO_PY 里的 TkDefaultFont 写法。
     */
    public static final String FONTCONFIG_LOCAL_CONF = """
            <?xml version="1.0"?>
            <!DOCTYPE fontconfig SYSTEM "fonts.dtd">
            <fontconfig>
              <match target="pattern"><test name="family"><string>sans-serif</string></test><edit name="family" mode="prepend" binding="strong"><string>WenQuanYi Micro Hei</string></edit></match>
              <match target="pattern"><test name="family"><string>serif</string></test><edit name="family" mode="prepend" binding="strong"><string>WenQuanYi Micro Hei</string></edit></match>
              <match target="pattern"><test name="family"><string>monospace</string></test><edit name="family" mode="prepend" binding="strong"><string>WenQuanYi Micro Hei</string></edit></match>
            </fontconfig>
            """;

    /**
     * 把若干条 shell 语句拼成一行：普通语句之间用 "; "，而以 {@code &} 结尾的后台语句后面不能再加分号
     * （{@code &; i=0} 是语法错误，真机上被 ash -n 门禁抓到过）。
     */
    private static String joinShell(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            sb.append(p);
            sb.append(p.trim().endsWith("&") ? ' ' : "; ");
        }
        return sb.toString();
    }

    /** 演示脚本 base64 / fontconfig base64（都用 base64 下发，避开引号与 XML 转义） */
    private static String b64(String s) {
        return android.util.Base64.encodeToString(
                s.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
    }

    /** 写演示窗口 + 挂到 :1 上跑（两行 bash，供启动/重启脚本复用；base64 传源码） */
    /** 演示脚本的 base64（脚本里用 base64 -d 写出，彻底避免引号/转义问题） */
    private static String demoBase64() {
        return android.util.Base64.encodeToString(
                GUI_DEMO_PY.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
    }

    private static String[] guiDemoLines() {
        String b64 = android.util.Base64.encodeToString(
                GUI_DEMO_PY.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        return new String[]{
                "echo \"" + b64 + "\" | base64 -d > \"$H/.quiz_gui_demo.py\"",
                "setsid nohup proot-distro login ubuntu -- /bin/bash -lc \"DISPLAY=:1 /usr/bin/python3 /data/data/com.termux/files/home/.quiz_gui_demo.py\" >/dev/null 2>&1 &"
        };
    }

    /**
     * Termux 侧「启动图形界面」脚本（幂等）。
     *
     * <p>就绪判断 = 端口已监听 <b>且</b> 进程还在（两条都在 Termux 侧做，0 成本，不用起 proot 登录）：
     * 早先每秒起一次 {@code proot-distro login} 去判断，实测要等 16 秒才连上；而只按进程名判断又会撞上
     * <b>僵死的 proot 进程</b>（容器里早就没有 x11vnc 了、进程表里还留着）→ 误判"已在运行" → 什么都不启动
     * （真机踩到过：冷启动 30 秒端口都没开）。
     *
     * <p>进程匹配用括号模式 {@code x11vn[c]}，避免匹配到本脚本自己的命令行（里面有 {@code x11vn[c]} 字面量）。
     */
    public static String buildGuiStartScript() {
        // 全部拼成**一行**（分号分隔、不放 # 注释）：
        // 真机实测：多行脚本经 RUN_COMMAND 下发时正文不执行，回包只有 .bashrc 横幅 ——
        // 一行能被第一条 # 之后整段注释掉，所以这里既不用换行也不写注释。
        return joinShell(
                "H=\"${HOME:-/data/data/com.termux/files/home}\"",
                "PREFIX=\"${PREFIX:-/data/data/com.termux/files/usr}\"",
                "export PREFIX",
                "export HOME=\"$H\"",
                "export PATH=\"$PREFIX/bin:/system/bin\"",
                "LOG=\"$H/.quiz_gui.log\"",
                "QUIZ_DEMO_B64=\"" + b64(GUI_DEMO_PY) + "\"",
                "QUIZ_FONTCONF_B64=\"" + b64(FONTCONFIG_LOCAL_CONF) + "\"",
                "UP() { pgrep -x Xvnc >/dev/null 2>&1; }",
                "DEMO_UP() { pgrep -f 'quiz_gui_dem[o]' >/dev/null 2>&1; }",
                "ensure_fonts() { setsid nohup timeout 40 proot-distro login ubuntu -- /bin/bash -lc \"mkdir -p /etc/fonts; echo $QUIZ_FONTCONF_B64 | base64 -d > /etc/fonts/local.conf; command -v fc-cache >/dev/null 2>&1 && fc-cache -f >/dev/null 2>&1\" >/dev/null 2>&1 < /dev/null & }",
                "ensure_demo() { echo \"$QUIZ_DEMO_B64\" | base64 -d > \"$H/.quiz_gui_demo.py\"; DEMO_UP || { setsid nohup proot-distro login ubuntu -- /bin/bash -lc \"DISPLAY=:1 /usr/bin/python3 /data/data/com.termux/files/home/.quiz_gui_demo.py\" >/dev/null 2>&1 < /dev/null & sleep 2; }; }",
                "TRACE=\"$H/.quiz_gui_start_trace.log\"",
                "trace() { echo \"$(date '+%T') $1\" >> \"$TRACE\"; }",
                "trace \"script-start\"",
                "ensure_fonts",
                "trace \"font-scheduled\"",
                "if UP; then ensure_demo; trace \"already-up\"; echo \"GUI_ALREADY_UP\"; exit 0; fi",
                ": > \"$LOG\"",
                "echo \"[$(date '+%T')] start\" >> \"$LOG\"",
                "trace \"starting-xvnc\"",
                "setsid nohup proot-distro login ubuntu -- /bin/bash -lc \"" + GUI_INNER_COMMAND + "\" >> \"$LOG\" 2>&1 < /dev/null &",
                "i=0",
                "while [ $i -lt 20 ]; do sleep 1; i=$((i+1)); if UP; then ensure_demo; trace \"gui-up\"; echo \"GUI_UP\"; exit 0; fi; done",
                "trace \"gui-failed\"",
                "echo \"GUI_FAILED\"",
                "tail -15 \"$LOG\"",
                "echo \"若 socket 被占用，先清掉残留在跑的 Xvnc：pkill -f 'Xvn[c] :1'\"",
                "exit 1");
    }

    /** Termux 侧「停止图形界面」脚本 */
    public static String buildGuiStopScript() {
        return String.join("\n",
                "PREFIX=\"${PREFIX:-/data/data/com.termux/files/usr}\"",
                "export PATH=\"$PREFIX/bin:/system/bin\"",
                "proot-distro login ubuntu -- /bin/bash -lc 'pkill -x Xvnc; pkill -x x11vnc; pkill -x Xvfb; pkill -x xclock' >/dev/null 2>&1",
                "echo GUI_STOPPED");
    }

    /** Termux 侧「重启图形界面」脚本：服务端被反复连断弄脏（banner 变 3.3、安全类型返回 0）时自愈用 */
    /** Termux 侧「重启图形界面」脚本（单行，理由同 buildGuiStartScript）：服务端被弄脏时自愈用 */
    public static String buildGuiRestartScript() {
        return joinShell(
                "H=\"${HOME:-/data/data/com.termux/files/home}\"",
                "PREFIX=\"${PREFIX:-/data/data/com.termux/files/usr}\"",
                "export PREFIX",
                "export HOME=\"$H\"",
                "export PATH=\"$PREFIX/bin:/system/bin\"",
                "LOG=\"$H/.quiz_gui.log\"",
                "QUIZ_DEMO_B64=\"" + b64(GUI_DEMO_PY) + "\"",
                "QUIZ_FONTCONF_B64=\"" + b64(FONTCONFIG_LOCAL_CONF) + "\"",
                "UP() { pgrep -x Xvnc >/dev/null 2>&1; }",
                "DEMO_UP() { pgrep -f 'quiz_gui_dem[o]' >/dev/null 2>&1; }",
                "ensure_fonts() { setsid nohup timeout 40 proot-distro login ubuntu -- /bin/bash -lc \"mkdir -p /etc/fonts; echo $QUIZ_FONTCONF_B64 | base64 -d > /etc/fonts/local.conf; command -v fc-cache >/dev/null 2>&1 && fc-cache -f >/dev/null 2>&1\" >/dev/null 2>&1 < /dev/null & }",
                "ensure_demo() { echo \"$QUIZ_DEMO_B64\" | base64 -d > \"$H/.quiz_gui_demo.py\"; DEMO_UP || { setsid nohup proot-distro login ubuntu -- /bin/bash -lc \"DISPLAY=:1 /usr/bin/python3 /data/data/com.termux/files/home/.quiz_gui_demo.py\" >/dev/null 2>&1 < /dev/null & sleep 2; }; }",
                "pkill -x Xvnc >/dev/null 2>&1",
                "pkill -x xclock >/dev/null 2>&1",
                "pkill -f 'quiz_gui_dem[o]' >/dev/null 2>&1",
                "sleep 1",
                "echo \"[$(date '+%T')] restart\" >> \"$LOG\"",
                "setsid nohup proot-distro login ubuntu -- /bin/bash -lc \"" + GUI_INNER_COMMAND + "\" >> \"$LOG\" 2>&1 < /dev/null &",
                "i=0",
                "while [ $i -lt 20 ]; do sleep 1; i=$((i+1)); if UP; then ensure_fonts; ensure_demo; echo \"GUI_RESTARTED\"; exit 0; fi; done",
                "echo \"GUI_RESTART_FAILED\"; tail -10 \"$LOG\"; exit 1");
    }

    /** 让 Termux 重启图形界面；返回 null 表示已下发 */
    public static String restartGuiInTermux(Context ctx) {
        return runInTermux(ctx, buildGuiRestartScript(), false);
    }

    /** Termux 侧「图形界面状态」脚本：端口通就再列一下容器里的 Xvnc 进程 */
    public static String buildGuiStatusScript() {
        return String.join("\n",
                "PREFIX=\"${PREFIX:-/data/data/com.termux/files/usr}\"",
                "export PATH=\"$PREFIX/bin:/system/bin\"",
                "if pgrep -f 'Xvn[c] :1' >/dev/null 2>&1; then",
                "  echo \"GUI_RUNNING 127.0.0.1:" + VNC_PORT + "\"",
                "  proot-distro login ubuntu -- /bin/bash -lc 'ps -ef | grep -E \"Xvnc|x11vnc|Xvfb\" | grep -v grep | head -3'",
                "else",
                "  echo \"GUI_STOPPED\"",
                "fi");
    }

    /** 把一段文本写到公共下载目录（调试/给用户检查用）；失败返回 null */
    public static File writeTextFile(Context ctx, String name, String content) {
        try {
            File f = new File(publicDir(ctx), name);
            try (OutputStream out = new FileOutputStream(f)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * `~/ubuntu-gui` 文件内容（由「一键准备」写出来，App 之后只用一条短命令去跑它）。
     *
     * <p>为什么绕这一下：长脚本文本经 RUN_COMMAND 直接下发时，真机实测**不执行**（回包只有 .bashrc 横幅），
     * 而以"文件 + bash 文件 start"形式跑完全正常 —— 换行/长度都不再是问题。
     */
    public static String buildGuiFile() {
        return String.join("\n",
                "#!/data/data/com.termux/files/usr/bin/bash",
                "case \"$1\" in",
                "  stop)",
                buildGuiStopScript(),
                "    ;;",
                "  status)",
                buildGuiStatusScript(),
                "    ;;",
                "  *)",
                buildGuiStartScript(),
                "    ;;",
                "esac",
                "");
    }

    /**
     * 让 Termux 起图形界面。
     *
     * <p>先把脚本落盘到 Download/OilQuiz/termux_env/gui_start.sh：
     * ① Termux 有存储权限时只下发一行 {@code bash <路径>}（省得几千字符塞进 Intent）；
     * ② 出问题时这个文件就是"App 到底下发了什么"的字节级证据（真机排查时非常有用）。
     */
    public static String startGuiInTermux(Context ctx) {
        String script = buildGuiStartScript();
        // 落盘只为"出问题时能看 App 到底下发了什么"（真机排查用）。
        // 注意：不要改成让 Termux 执行这个文件 —— /sdcard 上 App 写的脚本 Termux 读不了
        // （实测 bash /sdcard/... → Permission denied, exit 126），只能由 App 直接把脚本文本下发。
        writeTextFile(ctx, "gui_start.sh", script);
        writeTextFile(ctx, "gui_restart.sh", buildGuiRestartScript());
        writeTextFile(ctx, "gui_stop.sh", buildGuiStopScript());
        writeTextFile(ctx, "gui_status.sh", buildGuiStatusScript());
        writeTextFile(ctx, "gui_demo.py", GUI_DEMO_PY);
        // 只下发一条**短命令**去跑 ~/ubuntu-gui（由「一键准备」写出来的文件）。
        // 原因：长脚本文本经 RUN_COMMAND 下发时实测不执行（回包只有 .bashrc 横幅，连 trace 都写不出来），
        // 而以"文件 + 短命令"形式跑就完全正常（同一份内容手动 bash 文件 100% 成功）。
        String shortCmd = "test -x $HOME/ubuntu-gui && bash $HOME/ubuntu-gui start"
                + " || echo NO_UBUNTU_GUI_请先点一次一键准备";
        return runInTermux(ctx, shortCmd, true);
    }

    /** 让 Termux 停图形界面；返回 null 表示已下发 */
    public static String stopGuiInTermux(Context ctx) {
        return runInTermux(ctx, buildGuiStopScript(), true);
    }

    private static final String SCRIPT_TEMPLATE = """
            # 答题宝 · 完整 Python 环境准备（Termux + Ubuntu 容器）
            # 可重复运行：已装的部分会跳过，不会破坏已有环境
            PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
            export PREFIX
            HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
            ROOTFS_ARG="__ROOTFS__"
            TUNA_URL="__TUNA__"
            LOG="$HOME_DIR/.quiz_env_setup.log"
            STATUS="$HOME_DIR/.quiz_env_setup.status"

            step() { echo ""; echo "---- $1 ----"; }
            ok()   { echo "✅ $1"; }
            bad()  { echo "❌ $1"; FAIL=1; }

            container_installed() {
              [ -d "$PREFIX/var/lib/proot-distro/containers/ubuntu" ] && return 0
              [ -d "$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu" ] && return 0
              proot-distro list -q 2>/dev/null | grep -qx ubuntu && return 0
              proot-distro list 2>&1 | grep -q ubuntu && return 0
              return 1
            }

            gui_pkgs_ok() {
              proot-distro login ubuntu -- /bin/bash -lc 'command -v Xvnc >/dev/null 2>&1 && (ls /usr/share/fonts/truetype/wqy/ >/dev/null 2>&1 || fc-list :lang=zh 2>/dev/null | grep -q .)' 2>/dev/null
            }

            quiz_main() {
              FAIL=0

              step "0/6 允许外部应用调用（答题宝后续才能自动下发命令）"
              mkdir -p "$HOME_DIR/.termux"
              touch "$HOME_DIR/.termux/termux.properties"
              grep -q "^allow-external-apps" "$HOME_DIR/.termux/termux.properties" || echo "allow-external-apps=true" >> "$HOME_DIR/.termux/termux.properties"
              if grep -q "^allow-external-apps *= *true" "$HOME_DIR/.termux/termux.properties"; then
                termux-reload-settings >/dev/null 2>&1 || true
                ok "allow-external-apps=true 已生效"
              else
                bad "allow-external-apps 没写进去，请手查 $HOME_DIR/.termux/termux.properties"
              fi

              step "1/6 存储权限（决定用本地 29MB 包还是联网下 30MB）"
              STORAGE_OK=0
              [ -d "$HOME_DIR/storage" ] && STORAGE_OK=1
              if [ "$STORAGE_OK" = 0 ]; then
                echo "Termux 还没有外部存储权限，正在申请：手机上会弹「允许访问文件」框，请点『允许』"
                termux-setup-storage >/dev/null 2>&1 || true
                sleep 3
                [ -d "$HOME_DIR/storage" ] && STORAGE_OK=1
              fi
              if [ "$STORAGE_OK" = 1 ]; then
                ok "存储权限已就绪（可用本地安装包，不联网）"
              else
                echo "⚠️  未授予存储权限：读不到本地包 $ROOTFS_ARG"
                echo "    · 想用本地包：Termux 里执行 termux-setup-storage 并点『允许』，再点一次「一键准备」"
                echo "    · 本次改为联网下载（清华镜像，约 30MB）"
              fi

              step "2/6 proot-distro"
              if command -v proot-distro >/dev/null 2>&1; then
                ok "proot-distro 已安装"
              else
                echo "正在安装 proot-distro（首次约 1~2 分钟）…"
                pkg update -y >/dev/null 2>&1 || true
                pkg install -y proot-distro >/dev/null 2>&1 || bad "proot-distro 安装失败：请检查网络后重跑本页"
              fi

              step "3/6 Ubuntu 容器"
              if ! command -v proot-distro >/dev/null 2>&1; then
                bad "没有 proot-distro，跳过容器步骤"
              elif container_installed; then
                ok "容器 ubuntu 已存在，跳过下载与安装（重复运行不会破坏已有环境）"
              else
                ROOTFS=""
                for p in "$ROOTFS_ARG" "$HOME_DIR/storage/downloads/OilQuiz/termux_env/ubuntu-base-24.04.5-base-arm64.tar.gz" "/sdcard/Download/OilQuiz/termux_env/ubuntu-base-24.04.5-base-arm64.tar.gz" "/storage/emulated/0/Download/OilQuiz/termux_env/ubuntu-base-24.04.5-base-arm64.tar.gz"; do
                  if [ -n "$p" ] && [ -r "$p" ]; then ROOTFS="$p"; break; fi
                done
                if [ -n "$ROOTFS" ]; then
                  echo "用本地安装包建容器（不联网）: $ROOTFS"
                  proot-distro install -n ubuntu "$ROOTFS" || proot-distro install -n ubuntu "file://$ROOTFS" || echo "（本地包安装失败，下面改用在线镜像重试）"
                fi
                if ! container_installed; then
                  echo "联网下载 Ubuntu 根文件系统并建容器（清华镜像，约 30MB）…"
                  proot-distro install -n ubuntu "$TUNA_URL" || true
                fi
                if container_installed; then
                  ok "容器 ubuntu 就绪"
                else
                  bad "容器创建失败（想推倒重来：proot-distro reset ubuntu）"
                fi
              fi

              step "4/6 容器内完整 Python（tkinter/curses/readline/sqlite3/ssl/lzma/venv）"
              PY_CHECK='import tkinter, curses, readline, sqlite3, ssl, lzma, multiprocessing, venv; print("完整 Python", __import__("sys").version.split()[0], "| tkinter Tk", tkinter.TkVersion, "| fork", hasattr(__import__("os"), "fork"))'
              if command -v proot-distro >/dev/null 2>&1 && proot-distro login ubuntu -- /usr/bin/python3 -c "$PY_CHECK" 2>/dev/null; then
                ok "容器内 Python 已完整，跳过 apt（省 2~4 分钟）"
              else
                echo "正在容器内安装 python3-full / python3-tk / pip / venv（约 2~4 分钟）…"
                proot-distro login ubuntu -- /bin/bash -lc 'for f in /etc/apt/sources.list /etc/apt/sources.list.d/ubuntu.sources; do if [ -f "$f" ]; then sed -i "s|http://archive.ubuntu.com/ubuntu|https://mirrors.tuna.tsinghua.edu.cn/ubuntu|g; s|http://security.ubuntu.com/ubuntu|https://mirrors.tuna.tsinghua.edu.cn/ubuntu|g" "$f"; fi; done; apt-get update -y && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends python3-full python3-tk python3-venv python3-pip python3-setuptools ca-certificates' || bad "容器内 apt 安装失败：请检查网络后重跑本页"
                proot-distro login ubuntu -- /usr/bin/python3 -c "$PY_CHECK" 2>/dev/null || bad "容器内 Python 仍不完整"
              fi

              step "5/6 图形界面组件（TigerVNC Xvnc + X 工具，约 70MB，仅首次）"
              if ! command -v proot-distro >/dev/null 2>&1; then
                bad "没有 proot-distro，跳过图形界面组件"
              elif gui_pkgs_ok; then
                ok "Xvnc（TigerVNC）+ 中文字体已安装，跳过"
              else
                echo "正在容器内安装 tigervnc-standalone-server / x11-utils / x11-apps / procps / xdotool / imagemagick（约 70MB，2~4 分钟）…"
                proot-distro login ubuntu -- /bin/bash -lc 'export DEBIAN_FRONTEND=noninteractive; apt-get update -y && apt-get install -y --no-install-recommends tigervnc-standalone-server x11-utils x11-apps procps xdotool imagemagick fonts-wqy-microhei fonts-dejavu fontconfig' || bad "图形界面组件安装失败：请检查网络后重跑本页"
                if gui_pkgs_ok; then ok "X11/VNC 组件就绪"; else bad "X11/VNC 组件没装全"; fi
              fi

              step "6/6 入口（~/ubuntu、~/ubuntu-gui）与最终验证"
              cat > "$HOME_DIR/ubuntu" <<'QUIZ_UBUNTU_EOF'
            #!/data/data/com.termux/files/usr/bin/bash
            exec proot-distro login ubuntu -- "$@"
            QUIZ_UBUNTU_EOF
              chmod +x "$HOME_DIR/ubuntu"
              echo "__GUI_FILE_B64__" | base64 -d > "$HOME_DIR/ubuntu-gui"
            case "$1" in
              stop)
                proot-distro login ubuntu -- /bin/bash -lc 'pkill -x Xvnc; pkill -x x11vnc; pkill -x Xvfb; pkill -x xclock' >/dev/null 2>&1
                echo "图形界面已停止"
                ;;
              status)
                if PORTUP; then echo "图形界面运行中: 127.0.0.1:$PORT"; else echo "图形界面未运行"; fi
                ;;
              *)
                if PORTUP; then echo "图形界面已在运行: 127.0.0.1:$PORT"; exit 0; fi
                : > "$LOG"
                echo "正在启动图形界面（TigerVNC Xvnc 1280x720 :$PORT）..."
                setsid nohup proot-distro login ubuntu -- /bin/bash -lc "$INNER" >> "$LOG" 2>&1 &
                i=0
                while [ $i -lt 20 ]; do
                  sleep 1
                  i=$((i+1))
                  if PORTUP; then echo "✅ 图形界面已就绪: 127.0.0.1:$PORT"; exit 0; fi
                done
                echo "❌ 20 秒内端口未打开；日志 $LOG："
                tail -20 "$LOG"
                exit 1
                ;;
            esac
            QUIZ_GUI_EOF
              chmod +x "$HOME_DIR/ubuntu-gui"
              if "$HOME_DIR/ubuntu" python3 -c 'import tkinter, curses, readline, sqlite3, ssl, lzma, multiprocessing; print("✅ 完整体 Python 验证通过 | Python", __import__("sys").version.split()[0], "| tkinter Tk", tkinter.TkVersion)'; then
                echo ""
                echo "🎉 环境准备完成：Termux 里输入  ~/ubuntu  进入真 Ubuntu"
                echo "   图形界面：答题宝「图形界面（VNC）」页点「启动图形界面」，或 Termux 里 ~/ubuntu-gui start"
              else
                bad "最终验证失败：~/ubuntu 里缺 tkinter 或其它模块"
              fi

              echo "$FAIL" > "$HOME_DIR/.quiz_env_setup.exit"
            }

            # 整段输出同时进日志：以后"看看日志"就是 cat ~/.quiz_env_setup.log
            quiz_main 2>&1 | tee "$LOG"
            FAIL="$(cat "$HOME_DIR/.quiz_env_setup.exit" 2>/dev/null)"
            [ -n "$FAIL" ] || FAIL=1
            echo ""
            if [ "$FAIL" = 0 ]; then
              echo "===== 全部完成 ✅ ====="
            else
              echo "===== 有步骤失败 ❌（看上面的 ❌ 行）====="
            fi
            echo "$(date '+%F %T') fail=$FAIL" >> "$STATUS"
            exit "$FAIL"
            """;

    /**
     * 通过 Termux 的 RUN_COMMAND 在 Termux 里执行脚本（会打开一个可见会话，用户能看到进度）。
     * 需要：① 已授予 RUN_COMMAND 权限；② Termux 侧 allow-external-apps=true（脚本第 0 步会写，首次需用户手动跑一次）。
     *
     * @return null 表示已下发；否则返回失败原因
     */
    public static String runInTermux(Context ctx, String script, boolean background) {
        if (termuxVersion(ctx) == null) {
            return "Termux 还没安装，请先执行第 1 步";
        }
        if (!hasRunCommandPermission(ctx)) {
            return "还没有授予 Termux 的 RUN_COMMAND 权限（点上方按钮授予，或按提示手动粘贴命令）";
        }
        try {
            Intent i = new Intent();
            i.setClassName(TERMUX_PACKAGE, SERVICE);
            i.setAction(ACTION);
            i.putExtra(E_PATH, "/data/data/com.termux/files/usr/bin/bash");
            i.putExtra(E_ARGS, new String[]{"-lc", script});
            i.putExtra(E_WORKDIR, "/data/data/com.termux/files/home");
            i.putExtra(E_BACKGROUND, background);
            ctx.startService(i);
            return null;
        } catch (Exception e) {
            return "下发失败: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** 供"复制命令"用：把脚本包成一条可直接粘进 Termux 的命令 */
    public static String manualCommand(String script) {
        return "bash -lc " + shellQuote(script);
    }

    private static String shellQuote(String s) {
        return "'" + String.valueOf(s).replace("'", "'\\''") + "'";
    }

    // ---------- 通道自检 / 一键修复（Termux 侧配置） ----------

    /**
     * 内置 Termux APK 的签名证书 SHA-256（F-Droid 官方签名，keytool -printcert -jarfile 得出）。
     * 用来判断"已装的 Termux 能否被内置包覆盖安装"：签名不同时系统会直接拒绝更新安装。
     */
    public static final String BUNDLED_TERMUX_SIGNER_SHA256 =
            "22:8F:B2:CF:E9:08:31:C1:49:9E:C3:CC:AF:61:E9:6E:8E:1C:E7:07:66:B9:47:46:72:CE:42:73:34:D4:1C:42";

    private static final String RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    private static final String EXTRA_RC_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String EXTRA_RC_ARGS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String EXTRA_RC_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String EXTRA_RC_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String EXTRA_RC_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT";
    private static final String EXTRA_RESULT_BUNDLE = "result";
    private static final String RESULT_STDOUT = "stdout";
    private static final String RESULT_STDERR = "stderr";
    private static final String RESULT_ERRMSG = "errmsg";
    private static final String RESULT_ACTION = "com.oilquiz.app.TERMUX_EXEC_RESULT";
    private static final java.util.concurrent.atomic.AtomicInteger REQ_CODE =
            new java.util.concurrent.atomic.AtomicInteger(4200);

    /** 一次"真发命令并等 Termux 回执"的结果 */
    public static final class ChannelResult {
        public final boolean ok;
        public final String stdout;
        public final String stderr;
        public final String error;

        ChannelResult(boolean ok, String stdout, String stderr, String error) {
            this.ok = ok;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.error = error;
        }
    }

    /**
     * 真发一条命令并等回执（广播）。**这是"通道到底通不通"的唯一可信判据**，
     * 比"我下发了 intent 没报错"强得多。
     *
     * <p>失败原因会区分：没装 Termux / 没授权 / 系统拒绝 / Termux 没回执
     * （后者通常是 allow-external-apps 没开，或 Termux 被系统冻结/清过数据）。
     */
    public static ChannelResult runInTermuxAndWait(Context ctx, String command, int timeoutSeconds) {
        if (termuxVersion(ctx) == null) {
            return new ChannelResult(false, "", "", "Termux 没装");
        }
        if (!hasRunCommandPermission(ctx)) {
            return new ChannelResult(false, "", "", "还没授予 RUN_COMMAND 权限（点上面的授权按钮）");
        }
        final java.util.concurrent.ArrayBlockingQueue<Intent> queue =
                new java.util.concurrent.ArrayBlockingQueue<>(1);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                queue.offer(intent);
            }
        };
        boolean registered = false;
        try {
            IntentFilter filter = new IntentFilter(RESULT_ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                ctx.registerReceiver(receiver, filter);
            }
            registered = true;

            Intent replyIntent = new Intent(RESULT_ACTION).setPackage(ctx.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                flags |= PendingIntent.FLAG_MUTABLE;   // Termux 要往里面塞结果 Bundle
            }
            PendingIntent reply = PendingIntent.getBroadcast(ctx, REQ_CODE.incrementAndGet(), replyIntent, flags);

            Intent i = new Intent(ACTION_RUN_COMMAND);
            i.setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE);
            i.putExtra(EXTRA_RC_PATH, "/data/data/com.termux/files/usr/bin/bash");
            i.putExtra(EXTRA_RC_ARGS, new String[]{"-lc", command});
            i.putExtra(EXTRA_RC_WORKDIR, "/data/data/com.termux/files/home");
            // 必须 false（与项目里能用的 SystemResourceTool.termuxExec 一致）：
            // background=true 配 PendingIntent 回执时，实测 bash 起来了但**命令行没被执行**
            // （回包只有 .bashrc 横幅）—— 这就是"通道自检看着 ok 其实啥也没跑"的原因。
            i.putExtra(EXTRA_RC_BACKGROUND, false);
            i.putExtra(EXTRA_RC_PENDING_INTENT, reply);
            ctx.startService(i);

            Intent result = queue.poll(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            if (result == null) {
                return new ChannelResult(false, "", "", "Termux " + timeoutSeconds
                        + "s 没回执（最常见原因：Termux 侧 allow-external-apps 没开；也可能 Termux 被系统冻结/清除过数据）");
            }
            Bundle b = result.getBundleExtra(EXTRA_RESULT_BUNDLE);
            if (b == null) {
                return new ChannelResult(false, "", "", "Termux 回执里没有结果 Bundle");
            }
            String out = b.getString(RESULT_STDOUT, "");
            String err = b.getString(RESULT_STDERR, "");
            String errmsg = b.getString(RESULT_ERRMSG, "");
            if (errmsg != null && !errmsg.isEmpty()) {
                return new ChannelResult(false, out, err, "Termux 报错: " + errmsg);
            }
            return new ChannelResult(true, out, err, null);
        } catch (SecurityException se) {
            return new ChannelResult(false, "", "", "系统拒绝了 RUN_COMMAND（权限没生效）: " + se.getMessage());
        } catch (Exception e) {
            return new ChannelResult(false, "", "", e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (registered) {
                try {
                    ctx.unregisterReceiver(receiver);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 一次拿到环境诊断：proot-distro / 容器 / 上次准备结果 / allow-external-apps 是否已写 */
    public static String buildDiagnoseCommand() {
        return String.join("; ",
                "echo \"PREFIX=$PREFIX\"",
                "command -v proot-distro >/dev/null 2>&1 && echo proot-distro=yes || echo proot-distro=no",
                "echo \"containers: $(proot-distro list -q 2>/dev/null | tr '\\n' ' ')\"",
                "echo \"last-setup: $(tail -1 $HOME/.quiz_env_setup.status 2>/dev/null)\"",
                "echo \"allow-external-apps: $(grep -c '^allow-external-apps=true' $HOME/.termux/termux.properties 2>/dev/null)\"",
                "test -x $HOME/ubuntu-gui && echo ubuntu-gui=yes || echo ubuntu-gui=no",
                "echo \"cjk-font-file: $(proot-distro login ubuntu -- ls /usr/share/fonts/truetype/wqy/ 2>/dev/null | wc -l) 个（0=没装中文字体）\"");
    }

    /** 已装 Termux 的签名 SHA-256（大写冒号分隔，与 keytool 输出一致）；取不到返回 null */
    public static String installedTermuxSigner(Context ctx) {
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(
                    TERMUX_PACKAGE, PackageManager.GET_SIGNATURES);
            android.content.pm.Signature[] sigs = pi.signatures;
            if (sigs == null || sigs.length == 0) {
                return null;
            }
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(sigs[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) {
                if (i > 0) {
                    sb.append(':');
                }
                sb.append(String.format(java.util.Locale.US, "%02X", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 已装 Termux 与内置包是否同签名；不同签名时"更新安装"会被系统直接拒绝 */
    public static boolean termuxSignerMatchesBundled(Context ctx) {
        String s = installedTermuxSigner(ctx);
        return s != null && s.equalsIgnoreCase(BUNDLED_TERMUX_SIGNER_SHA256);
    }

    /** 把准备脚本全文写到公共下载目录（App 有权限，Termux 有存储权限后能读）；失败返回 null */
    public static File writeSetupScriptFile(Context ctx) {
        try {
            File r = exportedRootfs(ctx);
            File f = new File(publicDir(ctx), "setup.sh");
            try (OutputStream out = new FileOutputStream(f)) {
                out.write(buildSetupScript(r == null ? null : r.getAbsolutePath())
                        .getBytes(StandardCharsets.UTF_8));
            }
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 手动兜底的"一行命令"：把 5KB 的准备脚本写到公共目录，让用户只需要粘一行
     * {@code bash /sdcard/Download/OilQuiz/termux_env/setup.sh}。
     * 返回 null 表示写不进去（此时只能退回复制整段脚本）。
     */
    public static String shortManualCommand(Context ctx) {
        File f = writeSetupScriptFile(ctx);
        return f == null ? null : "bash " + f.getAbsolutePath();
    }

    /** 在本机打开 Termux（用户去粘贴那一行） */
    public static boolean openTermux(Context ctx) {
        try {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage(TERMUX_PACKAGE);
            if (i == null) {
                return false;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String readAssetText(Context ctx, String assetName) {
        try (InputStream in = ctx.getAssets().open(ASSET_DIR + "/" + assetName)) {
            byte[] buf = new byte[8192];
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}

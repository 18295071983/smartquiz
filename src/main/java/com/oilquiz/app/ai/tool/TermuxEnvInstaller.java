package com.oilquiz.app.ai.tool;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
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
     * 生成"在 Termux 里执行"的准备脚本：装 proot-distro → 用导出的本地根文件系统建容器 →
     * 容器内 apt 装 python3-full + python3-tk → 建 ~/ubuntu 入口并验证。
     * 脚本自己也会写入 allow-external-apps=true（供 App 后续自动化调用）。
     */
    public static String buildSetupScript(String rootfsPath) {
        String root = rootfsPath == null || rootfsPath.isEmpty()
                ? ("/sdcard/Download/" + EXPORT_SUBDIR + "/" + EXPORT_ROOTFS_NAME)
                : rootfsPath;
        StringBuilder sb = new StringBuilder();
        sb.append("set -e\n");
        sb.append("# 0) 允许外部应用调用（供 App 后续自动化；Termux 0.118 默认关闭）\n");
        sb.append("mkdir -p ~/.termux && touch ~/.termux/termux.properties\n");
        sb.append("grep -q '^allow-external-apps' ~/.termux/termux.properties || ");
        sb.append("echo 'allow-external-apps=true' >> ~/.termux/termux.properties\n");
        sb.append("# 1) 存储权限（读取内置包；会弹一次系统授权）\n");
        sb.append("termux-setup-storage || true\n");
        sb.append("# 2) 装 proot-distro（首次约 1~2 分钟）\n");
        sb.append("pkg update -y && pkg install -y proot-distro\n");
        sb.append("# 3) 用导出的本地根文件系统建容器（已存在则跳过；读不到就回退清华镜像下载）\n");
        sb.append("if ! proot-distro list 2>/dev/null | grep -q ubuntu; then\n");
        sb.append("  ROOTFS=\"").append(root).append("\"\n");
        sb.append("  if [ -r \"$ROOTFS\" ]; then\n");
        sb.append("    echo \"用本地根文件系统建容器: $ROOTFS\"\n");
        sb.append("    proot-distro install -n ubuntu \"file://$ROOTFS\"\n");
        sb.append("  else\n");
        sb.append("    echo \"读不到本地包（Termux 可能没有存储权限），改用清华镜像下载…\"\n");
        sb.append("    proot-distro install -n ubuntu \"https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz\"\n");
        sb.append("  fi\n");
        sb.append("fi\n");
        sb.append("# 4) 容器内装完整 Python（换清华源，约 2~4 分钟）\n");
        sb.append("proot-distro login ubuntu -- /bin/bash -lc '");
        sb.append("for f in /etc/apt/sources.list /etc/apt/sources.list.d/ubuntu.sources; do ");
        sb.append("[ -f \"$f\" ] && sed -i \"s|http://archive.ubuntu.com/ubuntu|https://mirrors.tuna.tsinghua.edu.cn/ubuntu|g; ");
        sb.append("s|http://security.ubuntu.com/ubuntu|https://mirrors.tuna.tsinghua.edu.cn/ubuntu|g\" \"$f\"; done; ");
        sb.append("apt-get update -y && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ");
        sb.append("python3-full python3-tk python3-venv python3-pip python3-setuptools ca-certificates'\n");
        sb.append("# 5) 入口脚本 + 验证\n");
        sb.append("printf '%s\\n' '#!/data/data/com.termux/files/usr/bin/bash' ");
        sb.append("'exec proot-distro login ubuntu -- \"$@\"' > ~/ubuntu && chmod +x ~/ubuntu\n");
        sb.append("./ubuntu python3 -c \"import tkinter, curses, readline, sqlite3, ssl, lzma, multiprocessing; ");
        sb.append("print('完整 Python OK | tkinter Tk', tkinter.TkVersion)\"\n");
        sb.append("echo '准备完成：在 Termux 里输入 ~/ubuntu 即可进入真 Ubuntu'\n");
        return sb.toString();
    }

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

package com.oilquiz.app.ai.tool;
import com.oilquiz.app.util.PublicStorageWriter;

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
        /**
     * 淇 allow-external-apps 鐨勪竴琛屽懡浠ゃ€?     * <p>鍏抽敭鍧戯紙鐪熸満瀹炶瘉锛夛細allow-external-apps=true 鍐欒繘 ~/.termux/termux.properties 鍚庯紝
     * Termux 杩涚▼蹇呴』閲嶅惎鎵嶄細閲嶆柊鍔犺浇閰嶇疆锛孯UN_COMMAND 閫氶亾鎵嶆斁琛屻€俿etup.sh 绗?0 姝ュ彧璋冧簡
     * termux-reload-settings锛堥儴鍒嗙増鏈笉鐢熸晥锛夛紝鎵€浠?閰嶇疆宸插啓浣嗛€氶亾浠嶄笉閫?鏋佸父瑙併€?     * 鏈懡浠?= 鍐欏叆閰嶇疆 + reload + exit锛堥€€鍑?Termux 鍗冲己鍒堕噸鍚繘绋嬶紝閲嶆柊鎵撳紑鍗崇敓鏁堬級銆?     */
    public static final String ALLOWEX_FIX_COMMAND =
            "mkdir -p ~/.termux; grep -q '^allow-external-apps' ~/.termux/termux.properties 2>/dev/null"
            + " || echo 'allow-external-apps=true' >> ~/.termux/termux.properties;"
            + " echo 'allow-external-apps:'; grep '^allow-external-apps' ~/.termux/termux.properties;"
            + " termux-reload-settings 2>/dev/null; sleep 1; exit";
    private static final String SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION = "com.termux.RUN_COMMAND";
    private static final String E_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String E_ARGS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String E_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String E_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    /** 在 Termux 新建可见会话执行命令（E_SESSION_ACTION=0 新会话，前台显示，可交互） */
    private static final String E_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION";
    private static final String E_SESSION_NEW = "com.termux.RUN_COMMAND_SESSION_NEW";

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
        String ver = termuxVersion(ctx);
        if (ver == null) {
            return false;
        }
        // Termux 0.118+ 已不再授予 READ/WRITE_EXTERNAL_STORAGE；
        // 标准授权路径是 MANAGE_EXTERNAL_STORAGE（所有文件访问）或 SAF。
        String[] perms = {
                "android.permission.MANAGE_EXTERNAL_STORAGE",
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE"
        };
        for (String p : perms) {
            if (ctx.getPackageManager().checkPermission(p, TERMUX_PACKAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        }
        // 上述传统权限全未授时，老版 Termux 确实没存储能力；
        // 但 0.118+ 在很多设备上通过 sdcard_rw 组 / MediaStore 模式工作（这台真机实测
        // 无任何权限 granted 仍能 cp /sdcard 文件成功），按新版模型判定可用，免得界面误报。
        String[] vp = ver.split("\\.");
        if (vp.length >= 2) {
            try {
                int maj = Integer.parseInt(vp[0]);
                int min = Integer.parseInt(vp[1]);
                if (maj > 0 || (maj == 0 && min >= 118)) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
    }

    /** 资产是否存在（构建时若未内置大包，返回 false → 界面会提示走"下载"通道） */
    /**
     * Termux 鏄惁宸茶幏寰?鏄剧ず鍦ㄥ叾浠栧簲鐢ㄤ笂灞?锛堟偓娴獥锛夋潈闄愩€?     *
     * <p>Android 10+ 涓?Termux 浠庡悗鍙板惎鍔ㄧ粓绔細璇濓紙RUN_COMMAND 鑷姩鎵ц锛夊繀椤讳緷璧栨鏉冮檺锛?     * 缂哄け鏃?Termux 浼氬脊 "Display over other apps" 鎻愮ず骞跺彲鑳芥嫆寮€浼氳瘽銆?     * 鐢?AppOps 鏌?Termux 鐨?SYSTEM_ALERT_WINDOW 鐘舵€侊紙Settings.canDrawOverlays 鍙兘鏌ヨ嚜韬級銆?     * 鏌ヤ笉鍒?鐗堟湰杩囨棫鏃惰繑鍥?true锛屼氦缁欓€氶亾鑷鍏滃簳锛岄伩鍏嶈鎶ュ崱娴佺▼銆?     */
    public static boolean termuxHasOverlayPermission(Context ctx) {
        try {
            android.app.AppOpsManager aom =
                    (android.app.AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            android.content.pm.ApplicationInfo ai =
                    ctx.getPackageManager().getApplicationInfo(TERMUX_PACKAGE, 0);
            int mode = aom.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, ai.uid, TERMUX_PACKAGE);
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return true;
        }
    }
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
        // 只以 tar.gz 实体为准（File/MediaStore 双通道，>1MB 即认为导出成功）。
        // 不再用 .quiz_rootfs_done 隐藏文件做标记：MediaStore 会把点开头文件改名为
        // _.quiz_rootfs_done（防隐藏文件绕过扫描），查询永远查不到 → 误判"未导出"
        // → 向导卡死在第 3 步（真机踩到，残留了 10 个 _ 副本）。
        if (PublicStorageWriter.size(ctx, "termux_env", EXPORT_ROOTFS_NAME) > 1024 * 1024) {
            return new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS),
                    "OilQuiz/termux_env/" + EXPORT_ROOTFS_NAME);
        }
        return null;
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
    // ---------- Optional plugins (Termux:API / Termux:Boot) ----------

    public static final String TERMUX_API_PACKAGE = "com.termux.api";
    public static final String TERMUX_BOOT_PACKAGE = "com.termux.boot";
    public static final String ASSET_API_APK = "com.termux.api.apk";
    public static final String ASSET_BOOT_APK = "com.termux.boot.apk";

    /** Whether the given package is installed */
    public static boolean isAppInstalled(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Release bundled plugin APK (Termux:API / Termux:Boot) to cacheDir and hand to the system installer */
    public static void installPluginApk(Activity activity, String assetName, String outName) throws Exception {
        File dir = new File(activity.getCacheDir(), ASSET_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new java.io.IOException("cannot create cache dir: " + dir);
        }
        File apk = new File(dir, outName);
        if (!apk.isFile() || apk.length() <= 0) {
            copyAsset(activity, assetName, apk);
        }
        Uri uri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".fileprovider", apk);
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(i);
    }

    /** Command that writes the Termux:Boot auto-start script (run inside Termux) */
    public static String buildBootScriptCommand() {
        return """
                mkdir -p "$HOME/.termux/boot" && cat > "$HOME/.termux/boot/start-env.sh" <<'BOOTEOF'
                #!/data/data/com.termux/files/usr/bin/bash
                # quiz app: auto start Ubuntu env (SSH) after device boot
                termux-wake-lock 2>/dev/null
                sleep 25
                [ -f "$HOME/ubuntu" ] && nohup bash "$HOME/ubuntu" -c "service ssh start 2>/dev/null" >/dev/null 2>&1
                BOOTEOF
                chmod 700 "$HOME/.termux/boot/start-env.sh" && echo BOOT_SCRIPT_OK
                """;
    }
    public static File exportRootfs(Context ctx) throws Exception {
        File out = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS),
                "OilQuiz/termux_env/" + EXPORT_ROOTFS_NAME);
        // MediaStore 优先：无"所有文件访问"权限也能写 Download 公共目录
        // （Termux 侧经 sdcard_rw 读公共目录实体，与 App 权限无关）
        // 清理历史残留：旧版写 .quiz_rootfs_done 标记被 MediaStore 改名成 _ 开头且删不掉（每导一次多一个副本）
        for (int i = 0; i < 30; i++) {
            String n = i == 0 ? "_.quiz_rootfs_done" : "_.quiz_rootfs_done (" + i + ")";
            PublicStorageWriter.delete(ctx, "termux_env", n);
        }
        String rel = PublicStorageWriter.writeStream(ctx, "termux_env", EXPORT_ROOTFS_NAME,
                null, ctx.getAssets().open(ASSET_DIR + "/" + ASSET_ROOTFS));
        if (rel == null) {
            // MediaStore 一次失败：部分 ROM 的 MediaProvider 瞬时抖动，重试一次再落 File API 兜底
            rel = PublicStorageWriter.writeStream(ctx, "termux_env", EXPORT_ROOTFS_NAME,
                    null, ctx.getAssets().open(ASSET_DIR + "/" + ASSET_ROOTFS));
        }
        if (rel == null) {
            copyAsset(ctx, ASSET_ROOTFS, out);
        }
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
        // 分阶段：setup.sh 现在是 runner，只负责同步 8 个 step 脚本并顺序执行，
        // 上一步失败立即停止（不再一股脑跑完一路报错），已完成的步骤自动跳过（幂等续跑）。
        return buildRunnerScript(root, TUNA_ROOTFS_URL);
    }

    /**
     * 容器内「中文化 + 北京时间」脚本（可重复运行，只做一次性修复）。
     *
     * <p><b>真机查到的根因</b>：Ubuntu 精简根文件系统自带 {@code /etc/dpkg/dpkg.cfg.d/excludes}，
     * 里面有一行 {@code path-exclude=/usr/share/locale/}{@code *}{@code /LC_MESSAGES/*.mo} ——
     * 装包时<b>所有程序自带的翻译词典都被跳过、根本没落盘</b>。证据：
     * {@code dpkg -V xfce4-panel} 报 65 个 missing（63 个是各语种 .mo），
     * 而 {@code dpkg -L xfce4-panel} 的语种列表里 zh_CN 明明在。
     * 所以 XFCE 面板 / Thunar / 开始菜单一直显示英文，跟设没设 LANG 无关
     * （语言包 language-pack-zh-hans 只覆盖 main 里的组件，XFCE 在 universe，词典只在自己包里）。
     *
     * <p>脚本干四件事：① 注释掉那条排除；② 把已装好的包缺的中文词典用
     * {@code apt-get download + dpkg-deb -x} 手动抠回来（dpkg 不会为已安装包重写 .mo）；
     * ③ 生成 zh_CN.UTF-8 并写 /etc/default/locale、/etc/environment、/etc/profile.d；
     * ④ 时区设为 Asia/Shanghai，并装上现成的中文开始菜单 Whisker Menu。
     */
    public static final String ZH_FIX_SH = """
            #!/bin/bash
            # 答题宝 · 容器中文化与北京时间（可重复运行）
            set +e
            # ① proot 容器 DNS 兜底：ubuntu-base 的 resolv.conf 指向 127.0.0.53（systemd stub），
            #    proot 里没有监听者 → apt/pip 全部报 Failed to fetch / Temporary failure resolving（真机最常见根因）
            printf 'nameserver 8.8.8.8\nnameserver 223.5.5.5\nnameserver 114.114.114.114\n' > /etc/resolv.conf
            EX=/etc/dpkg/dpkg.cfg.d/excludes
            if [ -f "$EX" ] && grep -q '^path-exclude=/usr/share/locale' "$EX"; then
              sed -i 's|^path-exclude=/usr/share/locale.*|# &|' "$EX"
              echo "· 已解除 dpkg 对翻译词典的排除（这是界面永远英文的根因）"
            fi
            export DEBIAN_FRONTEND=noninteractive
            TUNA=0
            for f in /etc/apt/sources.list /etc/apt/sources.list.d/ubuntu.sources; do
              # arm64 的 Ubuntu 根文件系统用的是 ports.ubuntu.com/ubuntu-ports，
              # 只换 archive/security 换不动（真机实测：容器一直是官方 ports 源，装大包很慢）
              if [ -f "$f" ] && grep -qE 'archive.ubuntu.com|security.ubuntu.com|ports.ubuntu.com' "$f"; then
                sed -i 's|http://ports.ubuntu.com/ubuntu-ports|https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports|g' "$f"
                sed -i 's|http://archive.ubuntu.com/ubuntu|https://mirrors.tuna.tsinghua.edu.cn/ubuntu|g' "$f"
                sed -i 's|http://security.ubuntu.com/ubuntu|https://mirrors.tuna.tsinghua.edu.cn/ubuntu|g' "$f"
                TUNA=1
              fi
            done
            if [ "$(cat /etc/timezone 2>/dev/null)" != "Asia/Shanghai" ]; then
              ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime
              echo "Asia/Shanghai" > /etc/timezone
              echo "· 时区已设为 Asia/Shanghai（北京时间）"
            fi
            cat > /etc/default/locale <<'QUIZ_LOCALE_EOF'
            LANG=zh_CN.UTF-8
            LANGUAGE=zh_CN:zh
            LC_ALL=zh_CN.UTF-8
            QUIZ_LOCALE_EOF
            cp /etc/default/locale /etc/environment
            mkdir -p /etc/profile.d
            cat > /etc/profile.d/00-quiz-locale.sh <<'QUIZ_PROFILE_EOF'
            export LANG=zh_CN.UTF-8
            export LANGUAGE=zh_CN:zh
            export LC_ALL=zh_CN.UTF-8
            QUIZ_PROFILE_EOF
            if ! locale -a 2>/dev/null | grep -qi 'zh_CN.utf'; then
              if ! command -v locale-gen >/dev/null 2>&1; then
                [ "$TUNA" = 1 ] && apt-get update -y >/dev/null 2>&1
                apt-get install -y --no-install-recommends locales >/dev/null 2>&1
              fi
              grep -q '^zh_CN.UTF-8' /etc/locale.gen 2>/dev/null || echo 'zh_CN.UTF-8 UTF-8' >> /etc/locale.gen
              locale-gen >/dev/null 2>&1 && echo "· 已生成 zh_CN.UTF-8"
            fi
            if [ ! -f /usr/share/locale/zh_CN/LC_MESSAGES/xfce4-panel.mo ] || [ ! -f /usr/share/locale/zh_CN/LC_MESSAGES/thunar.mo ]; then
              [ "$TUNA" = 1 ] && apt-get update -y >/dev/null 2>&1
              apt-get install -y --no-install-recommends language-pack-zh-hans language-pack-zh-hans-base >/dev/null 2>&1
              T=/tmp/quiz-zh-mo
              rm -rf "$T"
              mkdir -p "$T/deb" "$T/ex"
              cd "$T/deb" || exit 1
              for p in xfce4-panel xfwm4 xfdesktop4 libxfce4ui-2-0 libxfce4util7 xfconf libgarcon-1-0 exo-utils xfce4-appfinder; do
                apt-get download "$p" >/dev/null 2>&1
              done
              for d in *.deb; do
                [ -f "$d" ] && dpkg-deb -x "$d" "$T/ex" >/dev/null 2>&1
              done
              [ -d "$T/ex/usr/share/locale" ] && cp -a "$T/ex/usr/share/locale/." /usr/share/locale/ 2>/dev/null
              rm -rf "$T"
              echo "· 中文词典已补齐：$(ls /usr/share/locale/zh_CN/LC_MESSAGES 2>/dev/null | wc -l) 个"
            fi
            if ! ls /usr/lib/*/xfce4/panel/plugins/libwhiskermenu.so >/dev/null 2>&1; then
              apt-get install -y --no-install-recommends xfce4-whiskermenu-plugin >/dev/null 2>&1
              if ! ls /usr/lib/*/xfce4/panel/plugins/libwhiskermenu.so >/dev/null 2>&1; then
                cd /tmp && apt-get download xfce4-whiskermenu-plugin >/dev/null 2>&1
                mkdir -p /tmp/quiz-wm
                dpkg-deb -x /tmp/xfce4-whiskermenu-plugin*.deb /tmp/quiz-wm >/dev/null 2>&1
                cp -a /tmp/quiz-wm/. / 2>/dev/null
                rm -rf /tmp/quiz-wm /tmp/xfce4-whiskermenu-plugin*.deb
                apt-get install -y --no-install-recommends libgtk-layer-shell0 libgarcon-1-0 libgarcon-gtk3-1-0 >/dev/null 2>&1
              fi
              echo "· 已装现成的中文开始菜单 Whisker Menu"
            fi
            # 把 Whisker Menu 挂到顶部面板最左边当主菜单。
            # 坑一：插件缺 libgtk-layer-shell.so.0 会崩溃（真机：面板弹「插件意外离开」，60 秒内重启多次后
            #       被面板自动从配置里删掉）——所以上面装了依赖，这里再查一次缺库就放弃挂载。
            # 坑二：必须在会话来起来之前改配置，否则运行中的 xfconfd 会把自己的配置写回去覆盖掉。
            if command -v python3 >/dev/null 2>&1 && ls /usr/lib/*/xfce4/panel/plugins/libwhiskermenu.so >/dev/null 2>&1; then
              if ldd /usr/lib/*/xfce4/panel/plugins/libwhiskermenu.so 2>/dev/null | grep -q 'not found'; then
                echo "⚠️  Whisker Menu 仍然缺依赖，跳过挂载（不影响自带的中文菜单）"
              else
                python3 - <<'QUIZ_PANEL_PY'
            import os
            CFG = "/root/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-panel.xml"
            sep = chr(10)
            def minimal():
                lines = ['<?xml version="1.0" encoding="UTF-8"?>',
                         '<channel name="xfce4-panel" version="1.0">',
                         '  <property name="configver" type="int" value="2"/>',
                         '  <property name="panels" type="array">',
                         '    <value type="int" value="1"/>',
                         '    <property name="panel-1" type="empty">',
                         '      <property name="position" type="string" value="p=6;x=0;y=0"/>',
                         '      <property name="length" type="uint" value="100"/>',
                         '      <property name="position-locked" type="bool" value="true"/>',
                         '      <property name="size" type="uint" value="26"/>',
                         '      <property name="plugin-ids" type="array">',
                         '        <value type="int" value="23"/>',
                         '        <value type="int" value="2"/>',
                         '        <value type="int" value="3"/>',
                         '        <value type="int" value="6"/>',
                         '        <value type="int" value="12"/>',
                         '        <value type="int" value="14"/>',
                         '      </property>',
                         '    </property>',
                         '  </property>',
                         '  <property name="plugins" type="empty">',
                         '    <property name="plugin-2" type="string" value="tasklist">',
                         '      <property name="grouping" type="uint" value="1"/>',
                         '    </property>',
                         '    <property name="plugin-3" type="string" value="separator">',
                         '      <property name="expand" type="bool" value="true"/>',
                         '      <property name="style" type="uint" value="0"/>',
                         '    </property>',
                         '    <property name="plugin-6" type="string" value="systray">',
                         '      <property name="square-icons" type="bool" value="true"/>',
                         '    </property>',
                         '    <property name="plugin-12" type="string" value="clock"/>',
                         '    <property name="plugin-14" type="string" value="actions"/>',
                         '    <property name="plugin-23" type="string" value="whiskermenu"/>',
                         '  </property>',
                         '</channel>', '']
                os.makedirs(os.path.dirname(CFG), exist_ok=True)
                open(CFG, "w", encoding="utf-8").write(sep.join(lines))
                print("· 已生成面板配置：开始菜单在最左")
            if not os.path.exists(CFG):
                minimal()
            else:
                s = open(CFG, encoding="utf-8").read()
                if 'value="whiskermenu"' in s:
                    print("· 开始菜单已挂好")
                else:
                    old = '<property name="plugins" type="empty">'
                    s = s.replace(old, old + sep + '    <property name="plugin-23" type="string" value="whiskermenu"/>', 1)
                    i = s.index('<property name="panel-1"')
                    k = s.index('<property name="plugin-ids"', i)
                    k2 = s.index('</property>', k)
                    seg = s[k:k2]
                    line1 = '        <value type="int" value="1"/>' + sep
                    while line1 in seg:
                        seg = seg.replace(line1, '', 1)
                    if '<value type="int" value="23"/>' not in seg:
                        first = seg.index('<value')
                        seg = seg[:first] + '<value type="int" value="23"/>' + sep + '        ' + seg[first:]
                    open(CFG, "w", encoding="utf-8").write(s[:k] + seg + s[k2:])
                    rc = "/root/.config/xfce4/panel/whiskermenu-23.rc"
                    os.makedirs(os.path.dirname(rc), exist_ok=True)
                    open(rc, "w", encoding="utf-8").write('[Configuration]' + sep + 'button-title=应用' + sep)
                    print("· 已把中文开始菜单插到面板最左边（顶替原来的英文菜单）")
            QUIZ_PANEL_PY
              fi
            fi
            echo "ZH_FIX_OK tz=$(cat /etc/timezone 2>/dev/null) 中文词典=$(ls /usr/share/locale/zh_CN/LC_MESSAGES 2>/dev/null | wc -l)"
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

    /** 把一段文本写到公共下载目录（调试/给用户检查用）；MediaStore 优先，无需"所有文件访问" */
    public static File writeTextFile(Context ctx, String name, String content) {
        PublicStorageWriter.writeText(ctx, "termux_env", name, content);
        return new File(publicDir(ctx), name);
    }

    /** 公共准备日志文件名：Termux 的分阶段 runner 会把执行输出 tee 到这里，App 可读 → 页面实时监控 */
    public static final String SETUP_PUBLIC_LOG_NAME = "setup_log.txt";

    /** SSH 开启结果文件名（Termux 侧写入，App 读取后显示连接信息） */
    public static final String SSH_INFO_NAME = "ssh_info.txt";

    /** 读公共目录下任意文本文件尾部（最长 8KB）；读不到返回空串（File 优先 + MediaStore 回退） */
    public static String readPublicText(Context ctx, String name) {
        return PublicStorageWriter.readTail(ctx, "termux_env", name, 8192);
    }

    /** 一键开启 SSH：装 openssh → 设默认密码 → 启动 sshd → 把 IP/用户/端口写入公共文件（App 读取展示）。
     *  2026-10-02 改：① openssh 已随离线包内置（step2 ④ 组），脚本优先直接用；没有才联网 pkg install（先切清华源）。
     *  ② 多行脚本不能经 RUN_COMMAND 直接下发（Termux 不执行长文本），本脚本由 App 先落盘为 enable_ssh.sh，
     *  再下发单行 bash 执行（见 TermuxEnvSetupActivity.doEnableSsh）。③ 路径统一用 $HOME（bash -lc 下 Termux 已设）。 */
    public static String buildSshEnableScript() {
        return "INFO=\"$HOME/storage/downloads/OilQuiz/termux_env/ssh_info.txt\"\n"
                + "rm -f \"$INFO\"\n"
                + "if ! command -v sshd >/dev/null 2>&1; then\n"
                + "  if [ -f \"$PREFIX/etc/apt/sources.list\" ] && ! grep -qE \"tuna\\.tsinghua|ustc\\.edu|aliyun\\.com\" \"$PREFIX/etc/apt/sources.list\" 2>/dev/null; then\n"
                + "    printf 'deb https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main stable main\\n' > \"$PREFIX/etc/apt/sources.list\"\n"
                + "    pkg update -y >/dev/null 2>&1\n"
                + "  fi\n"
                + "  pkg install openssh -y >/dev/null 2>&1\n"
                + "fi\n"
                + "if ! command -v sshd >/dev/null 2>&1; then echo \"SSH_INSTALL_FAIL\" > \"$INFO\"; exit 1; fi\n"
                + "(echo -e \"quiz2026\\nquiz2026\" | passwd) >/dev/null 2>&1\n"
                + "sshd 2>/dev/null\n"
                + "sleep 1\n"
                + "if pgrep -x sshd >/dev/null 2>&1; then\n"
                + "  U=$(whoami)\n"
                + "  IP=$(ip addr show wlan0 2>/dev/null | awk '/inet /{print $2}' | cut -d/ -f1)\n"
                + "  [ -z \"$IP\" ] && IP=$(/system/bin/ip addr show wlan0 2>/dev/null | awk '/inet /{print $2}' | cut -d/ -f1)\n"
                + "  [ -z \"$IP\" ] && IP=$(ifconfig wlan0 2>/dev/null | awk '/inet addr/{print $2}' | cut -d: -f2)\n"
                + "  [ -z \"$IP\" ] && IP=$(hostname -I 2>/dev/null | awk '{print $1}')\n"
                + "  { echo \"SSH_OK\"; echo \"user=$U\"; echo \"ip=$IP\"; echo \"port=8022\"; echo \"pass=quiz2026\"; } > \"$INFO\"\n"
                + "else\n"
                + "  echo \"SSH_START_FAIL\" > \"$INFO\"\n"
                + "fi\n";
    }

    /** 读公共准备日志尾部（最长 8KB）；读不到返回空串（App 侧监控 Termux 执行过程用） */
    public static String readPublicSetupLog(Context ctx) {
        return readPublicText(ctx, SETUP_PUBLIC_LOG_NAME);
    }


    /** step 文件名（按依赖顺序）。已按用户要求移除中文化（step3_zh_fix）与容器内 Python（step4_python，App AI 用内置 Termux python）；step5_gui 已随 VNC 功能删除 */
    private static final String[] STEP_NAMES = {
            "step0_allow_external_apps.sh", "step1_storage.sh", "step2_proot.sh",
            "step3_container.sh",
            "step6_finalize.sh"
    };

    /** 分阶段 runner：同步 5 个 step 脚本到 $HOME/.quiz_env_steps，顺序执行，失败即停（可续跑） */
    private static String buildRunnerScript(String root, String tuna) {
        String names = String.join(" ", STEP_NAMES);
        return """
                #!/data/data/com.termux/files/usr/bin/bash
                # 答题宝 · 分阶段准备：每步独立脚本，上一步失败就停，已完成的步骤重跑时自动跳过
                PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"; export PREFIX
                HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
                export ROOTFS_ARG="__ROOTFS__"
                export TUNA_URL="__TUNA__"
                LOG="$HOME_DIR/.quiz_env_setup.log"
                STATUS="$HOME_DIR/.quiz_env_setup.status"
                PUBDIR="$HOME_DIR/storage/downloads/OilQuiz/termux_env"
                PUBLOG="$PUBDIR/setup_log.txt"
                STEP_DIR="$HOME_DIR/.quiz_env_steps"
                STEPS="__STEPS__"
                # 若 Termux 还没建存储桥（~/storage 不存在），先申请（弹窗点允许）再找脚本；
                # 否则 runner 直读 /sdcard 会被 Android 分区存储拒绝，8 个 step 一个都拷不到（真机踩过 126）
                [ -d "$HOME_DIR/storage" ] || { echo "正在申请存储权限（系统弹窗请点『允许』）…"; termux-setup-storage >/dev/null 2>&1 || true; sleep 3; }
                # 整段输出同时进日志（进程替换需 bash 4.4+，Termux 自带满足）
                # 私有 LOG（~/.quiz_env_setup.log）+ 公共 PUBLOG（App 可读，页面实时监控执行过程）
                mkdir -p "$PUBDIR" 2>/dev/null || true
                exec > >(tee "$LOG" "$PUBLOG" 2>/dev/null) 2>&1
                mkdir -p "$STEP_DIR"
                for f in $STEPS; do
                  SRC=""
                  for c in /sdcard/Download/OilQuiz/termux_env/$f "$HOME_DIR/storage/downloads/OilQuiz/termux_env/$f"; do
                    [ -f "$c" ] && SRC="$c" && break
                  done
                  if [ -n "$SRC" ]; then cp "$SRC" "$STEP_DIR/$f" && chmod 700 "$STEP_DIR/$f"; fi
                  if [ ! -x "$STEP_DIR/$f" ]; then
                    echo "❌ 读不到步骤脚本 $f（Termux 存储权限未授予？）"
                    echo "    · 在 Termux 里执行 termux-setup-storage，弹窗点『允许』（或去 Termux 设置→应用→打开所有文件访问）"
                    echo "    · 然后在答题宝点「智能检测引导」重新生成脚本，再重跑这一行"
                    exit 1
                  fi
                done
                LAST_FAIL=0
                for f in $STEPS; do
                  echo ""
                  echo "===== $f ====="
                  bash "$STEP_DIR/$f" || { LAST_FAIL=1; echo "❌ $f 执行失败，已停止（修好后重跑这一行，已完成的步骤会自动跳过）"; break; }
                done
                echo "$(date '+%F %T') fail=$LAST_FAIL" >> "$STATUS"
                if [ "$LAST_FAIL" = 0 ]; then
                  echo "🎉 环境准备完成：Termux 里输入  ~/ubuntu  进入真 Ubuntu"
                  echo "fail=0"
                else
                  echo "===== 有步骤失败 ❌（看上面的 ❌ 行；重跑会自动跳过已完成的步骤）====="
                  echo "fail=1"
                fi
                exit "$LAST_FAIL"
                """.replace("__ROOTFS__", root).replace("__TUNA__", tuna).replace("__STEPS__", names);
    }

    /** step 脚本公共头：环境变量 + ok/bad（bad 写状态并 exit 1，让 runner 停下） */
    private static String stepHeader(String tag) {
        return "#!/data/data/com.termux/files/usr/bin/bash\n"
                + "PREFIX=\"${PREFIX:-/data/data/com.termux/files/usr}\"; export PREFIX\n"
                + "HOME_DIR=\"${HOME:-/data/data/com.termux/files/home}\"\n"
                + "STATUS=\"$HOME_DIR/.quiz_env_setup.status\"\n"
                + "ok(){ echo \"✅ $1\"; }\n"
                + "bad(){ echo \"❌ $1\"; echo \"" + tag + "=fail $(date '+%F %T')\" >> \"$STATUS\"; exit 1; }\n";
    }

    /** 8 个独立步骤脚本（顺序 = STEP_NAMES；每步幂等，失败写状态并退出，不往下跑） */
    public static java.util.List<String> buildStepScripts(String root, String tuna) {
        java.util.List<String> l = new java.util.ArrayList<>();
        String safeRoot = (root == null || root.isEmpty())
                ? ("/sdcard/Download/" + EXPORT_SUBDIR + "/" + EXPORT_ROOTFS_NAME) : root;

        // step0：allow-external-apps（答题宝自动下发命令的开关）
        l.add(stepHeader("step0")
                + "mkdir -p \"$HOME_DIR/.termux\"\n"
                + "touch \"$HOME_DIR/.termux/termux.properties\"\n"
                + "if grep -q \"^allow-external-apps\" \"$HOME_DIR/.termux/termux.properties\"; then\n"
                + "  sed -i \"s|^allow-external-apps.*|allow-external-apps=true|\" \"$HOME_DIR/.termux/termux.properties\"\n"
                + "else\n"
                + "  echo \"allow-external-apps=true\" >> \"$HOME_DIR/.termux/termux.properties\"\n"
                + "fi\n"
                + "if grep -q \"^allow-external-apps *= *true\" \"$HOME_DIR/.termux/termux.properties\"; then\n"
                + "  termux-reload-settings >/dev/null 2>&1 || true\n"
                + "  ok \"allow-external-apps=true 已生效\"\n"
                + "else\n"
                + "  bad \"allow-external-apps 没写进去，请手查 $HOME_DIR/.termux/termux.properties\"\n"
                + "fi\n"
                + "echo \"step0=ok $(date '+%F %T')\" >> \"$STATUS\"\n");

        // step1：存储权限（软依赖：没授权会联网兜底，不 fail）
        l.add(stepHeader("step1")
                + "STORAGE_OK=0\n"
                + "[ -d \"$HOME_DIR/storage\" ] && STORAGE_OK=1\n"
                + "if [ \"$STORAGE_OK\" = 0 ]; then\n"
                + "  echo \"Termux 还没有外部存储权限，正在申请：手机上会弹「允许访问文件」框，请点『允许』\"\n"
                + "  termux-setup-storage >/dev/null 2>&1 || true\n"
                + "  sleep 3\n"
                + "  [ -d \"$HOME_DIR/storage\" ] && STORAGE_OK=1\n"
                + "fi\n"
                + "if [ \"$STORAGE_OK\" = 1 ]; then\n"
                + "  ok \"存储权限已就绪（可用本地安装包，不联网）\"\n"
                + "else\n"
                + "  echo \"⚠️  未授予存储权限：读不到本地包，本次改为联网下载（清华镜像，约 30MB）\"\n"
                + "  echo \"    · 想用本地包：Termux 里执行 termux-setup-storage 并点『允许』，再重跑这一行\"\n"
                + "fi\n"
                + "echo \"step1=ok $(date '+%F %T')\" >> \"$STATUS\"\n");

        // step2：proot-distro（内置离线包优先，零联网；兜底联网 + 清华镜像；均幂等）
        // 2026-10-02 重构：原"一行 dpkg --force-depends -i 全部 22 包"在 bootstrap 已带同/高版本库时会中途中止
        //   （dpkg -i 一个包报错即中断后续），且错误被 >/dev/null 吞掉看不到原因。
        //   现改为：① 先装基础依赖（容错）→ ② proot + proot-distro 核心单独装（失败再 force-depends 兜底）
        //   → ③ python 全家桶（force-depends 容错，装不上不影响容器，联网兜底）。dpkg 输出全部进日志可查。
        l.add(stepHeader("step2")
                + "if command -v proot-distro >/dev/null 2>&1; then\n"
                + "  ok \"proot-distro 已安装\"\n"
                + "else\n"
                + "  PKGS=\"$HOME/storage/downloads/OilQuiz/termux_env/pkgs\"\n"
                + "  if [ -d \"$PKGS\" ] && ls \"$PKGS\"/*.deb >/dev/null 2>&1; then\n"
                + "    echo \"正在本地离线安装 Termux 组件（proot / python 全家桶，共 22 包，零联网）…\"\n"
                + "    cd \"$PKGS\"\n"
                + "    # ① 基础依赖库：bootstrap 已带同/高版本时 dpkg 会提示『已安装/较新版本』，忽略即可（不中断后续）\n"
                + "    dpkg -i libandroid-posix-semaphore_*.deb libandroid-shmem_*.deb libtalloc_*.deb libandroid-support_*.deb 2>&1 || true\n"
                + "    # ② 核心：proot + proot-distro（容器地基，单独装；失败再用 --force-depends 兜底一次）\n"
                + "    if ! dpkg -i proot_*.deb proot-distro_*.deb 2>&1; then\n"
                + "      dpkg --force-depends -i proot_*.deb proot-distro_*.deb 2>&1 || true\n"
                + "    fi\n"
                + "    # ③ python 全家桶（App AI 用；force-depends 容错，失败由联网兜底；不影响容器）\n"
                + "    dpkg --force-depends -i libbz2_*.deb libexpat_*.deb libffi_*.deb liblzma_*.deb libsqlite_*.deb gdbm_*.deb libcrypt_*.deb zlib_*.deb zstd_*.deb readline_*.deb ncurses_*.deb ncurses-ui-libs_*.deb openssl_*.deb ca-certificates_*.deb python_*.deb python-pip_*.deb 2>&1 || true\n"
                + "    # ④ openssh 全家桶（SSH 功能，2026-10-02 内置；force-depends 容错，libc++ 由 bootstrap 自带）\n"
                + "    dpkg --force-depends -i libandroid-glob_*.deb libresolv-wrapper_*.deb libdb_*.deb ldns_*.deb libedit_*.deb krb5_*.deb termux-auth_*.deb openssh-sftp-server_*.deb openssh_*.deb resolv-conf_*.deb 2>&1 || true\n"
                + "    dpkg --configure -a >/dev/null 2>&1 || true\n"
                + "    if command -v proot-distro >/dev/null 2>&1; then\n"
                + "      ok \"proot-distro 已离线安装（未联网）\"\n"
                + "    else\n"
                + "      echo \"离线安装失败（上面 dpkg 输出可见原因），回退联网安装（清华镜像）…\"\n"
                + "      if [ -f \"$PREFIX/etc/apt/sources.list\" ] && ! grep -qE \"tuna\\.tsinghua|ustc\\.edu|aliyun\\.com\" \"$PREFIX/etc/apt/sources.list\" 2>/dev/null; then\n"
                + "        printf 'deb https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main stable main\\n' > \"$PREFIX/etc/apt/sources.list\"\n"
                + "      fi\n"
                + "      pkg update -y 2>&1 || true\n"
                + "      pkg install -y proot-distro 2>&1 || bad \"proot-distro 安装失败：请检查网络后重跑\"\n"
                + "    fi\n"
                + "  else\n"
                + "    echo \"未找到内置离线包（存储权限未授予？），联网安装（清华镜像）…\"\n"
                + "    if [ -f \"$PREFIX/etc/apt/sources.list\" ] && ! grep -qE \"tuna\\.tsinghua|ustc\\.edu|aliyun\\.com\" \"$PREFIX/etc/apt/sources.list\" 2>/dev/null; then\n"
                + "      printf 'deb https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main stable main\\n' > \"$PREFIX/etc/apt/sources.list\"\n"
                + "    fi\n"
                + "    pkg update -y 2>&1 || true\n"
                + "    pkg install -y proot-distro 2>&1 || bad \"proot-distro 安装失败：请检查网络后重跑\"\n"
                + "  fi\n"
                + "fi\n"
                + "echo \"step2=ok $(date '+%F %T')\" >> \"$STATUS\"\n");

        // step3：Ubuntu 容器（本地包优先，联网兜底；容器目录损坏时提示 reset）
        l.add(stepHeader("step3")
                + "ROOTFS_ARG=\"${ROOTFS_ARG:-" + safeRoot + "}\"\n"
                + "TUNA_URL=\"${TUNA_URL:-" + tuna + "}\"\n"
                + "container_installed() {\n"
                + "  # 只查目录不算数：必须能真正 login 才算装好（防止损坏空壳容器假跳过，真机踩过）\n"
                + "  timeout 60 proot-distro login ubuntu -- true 2>/dev/null && return 0\n"
                + "  return 1\n"
                + "}\n"
                + "if ! command -v proot-distro >/dev/null 2>&1; then\n"
                + "  bad \"没有 proot-distro，跳过容器步骤（先修 step2）\"\n"
                + "elif container_installed; then\n"
                + "  ok \"容器 ubuntu 可用，跳过下载与安装\"\n"
                + "else\n"
                + "  # 容器损坏或不存在：先清残留目录，避免 proot-distro install 报 already exists\n"
                + "  if [ -d \"$PREFIX/var/lib/proot-distro/containers/ubuntu\" ] || [ -d \"$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu\" ] || proot-distro list -q 2>/dev/null | grep -qx ubuntu; then\n"
                + "    echo \"检测到容器残留（可能是损坏的空壳），先清理再重装…\"\n"
                + "    proot-distro remove ubuntu >/dev/null 2>&1 || true\n"
                + "    rm -rf \"$PREFIX/var/lib/proot-distro/containers/ubuntu\" \"$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu\"\n"
                + "  fi\n"
                + "  ROOTFS=\"\"\n"
                + "  for p in \"$ROOTFS_ARG\" \"$HOME_DIR/storage/downloads/OilQuiz/termux_env/ubuntu-base-24.04.5-base-arm64.tar.gz\" \"/sdcard/Download/OilQuiz/termux_env/ubuntu-base-24.04.5-base-arm64.tar.gz\" \"/storage/emulated/0/Download/OilQuiz/termux_env/ubuntu-base-24.04.5-base-arm64.tar.gz\"; do\n"
                + "    if [ -n \"$p\" ] && [ -r \"$p\" ]; then ROOTFS=\"$p\"; break; fi\n"
                + "  done\n"
                + "  if [ -n \"$ROOTFS\" ]; then\n"
                + "    echo \"用本地安装包建容器（不联网）: $ROOTFS\"\n"
                + "    proot-distro install -n ubuntu \"$ROOTFS\" || proot-distro install -n ubuntu \"file://$ROOTFS\" || echo \"（本地包安装失败，下面改用在线镜像重试）\"\n"
                + "  fi\n"
                + "  if ! container_installed; then\n"
                + "    echo \"联网下载 Ubuntu 根文件系统并建容器（清华镜像，约 30MB）…\"\n"
                + "    proot-distro install -n ubuntu \"$TUNA_URL\" || true\n"
                + "  fi\n"
                + "  if container_installed; then\n"
                + "    ok \"容器 ubuntu 就绪\"\n"
                + "  else\n"
                + "    bad \"容器创建失败（想推倒重来：proot-distro reset ubuntu，然后重跑这一行）\"\n"
                + "  fi\n"
                + "fi\n"
                + "echo \"step3=ok $(date '+%F %T')\" >> \"$STATUS\"\n");

        // step3.5：中文界面与北京时间 —— 已按用户要求移除（装 locales/语言包首次 1~2 分钟，非主链路必需）。
        // 需要中文化时：Termux 里手动执行 ~/.quiz_zh_fix.sh（App 已把 zh_fix.sh 落到公共目录）。

        // step4：容器内 Python —— 已按用户要求删除。
        // 原因：App 的 AI 脚本用内置的 Termux python（step2 离线装 22 包里的 python_3.14.6，零联网）；
        // 容器内 python3-full/tkinter/pandas 需要联网 apt+pip（2~4 分钟），且其原用途（tkinter 图形）已随 VNC 删除。
        // 需要时在 ~/ubuntu 里手动执行：apt-get install python3-full && pip3 install requests pandas

        // step5：图形界面组件已随 VNC 功能删除（原 step5_gui.sh 不再生成）

        // step6：入口（~/ubuntu）与最终验证
        l.add(stepHeader("step6")
                + "cat > \"$HOME_DIR/ubuntu\" <<'QUIZ_UBUNTU_EOF'\n"
                + "#!/data/data/com.termux/files/usr/bin/bash\n"
                + "exec proot-distro login ubuntu -- \"$@\"\n"
                + "QUIZ_UBUNTU_EOF\n"
                + "chmod +x \"$HOME_DIR/ubuntu\"\n"
                + "if \"$HOME_DIR/ubuntu\" /bin/true; then\n"
                + "  echo \"✅ 最终验证通过：容器可用（进 Ubuntu 用 ~/ubuntu；容器内 Python 需要时手动 apt 装）\"\n"
                + "else\n"
                + "  bad \"最终验证失败：容器无法登录，请检查 step3\"\n"
                + "fi\n"
                + "echo \"step6=ok $(date '+%F %T')\" >> \"$STATUS\"\n");

        return l;
    }



    /**
     * 通过 Termux 的 RUN_COMMAND 在 Termux 里执行脚本（会打开一个可见会话，用户能看到进度）。
     * 需要：① 已授予 RUN_COMMAND 权限；② Termux 侧 allow-external-apps=true（脚本第 0 步会写，首次需用户手动跑一次）。
     *
     * @return null 表示已下发；否则返回失败原因
     */
    /** Termux 主进程/服务是否在跑（被系统杀了会导致 RUN_COMMAND 发不出去，需先唤起再发） */
    public static boolean termuxProcessAlive(Context ctx) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return false;
            }
            for (android.app.ActivityManager.RunningServiceInfo s : am.getRunningServices(200)) {
                if (s.service != null && TERMUX_PACKAGE.equals(s.service.getPackageName())) {
                    return true;
                }
            }
            for (android.app.ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
                if (p.processName != null && p.processName.startsWith(TERMUX_PACKAGE)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

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
            // Termux 进程没起来等原因导致下发失败：上层会唤起 Termux 后自动重发
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

    /** 把准备脚本全文写到公共下载目录（MediaStore 写入，无需"所有文件访问"）；失败返回 null */
    public static File writeSetupScriptFile(Context ctx) {
        try {
            File r = exportedRootfs(ctx);
            PublicStorageWriter.writeText(ctx, "termux_env", "setup.sh",
                    buildSetupScript(r == null ? null : r.getAbsolutePath()));
            // 分阶段：setup.sh 是 runner；8 个 step 脚本独立落盘，由 runner 同步到 Termux 顺序执行
            java.util.List<String> steps = buildStepScripts(
                    r == null ? null : r.getAbsolutePath(), TUNA_ROOTFS_URL);
            for (int i = 0; i < STEP_NAMES.length; i++) {
                writeTextFile(ctx, STEP_NAMES[i], steps.get(i));
            }
            writeTextFile(ctx, "zh_fix.sh", ZH_FIX_SH);
            // 内置 Termux 离线安装包（22 个 deb：proot / python 全家桶）一并导出，step2 零联网安装
            exportTermuxPkgs(ctx);
            return new File(publicDir(ctx), "setup.sh");
        } catch (Exception e) {
            return null;
        }
    }

    /** 把 assets/termux_pkgs/ 里的 deb 导出到公共目录 termux_env/pkgs/（幂等：已存在且非空则跳过） */
    public static boolean exportTermuxPkgs(Context ctx) {
        try {
            String[] names = ctx.getAssets().list("termux_pkgs");
            if (names == null || names.length == 0) {
                return false;
            }
            boolean all = true;
            for (String n : names) {
                if (!n.endsWith(".deb")) continue;
                if (PublicStorageWriter.size(ctx, "termux_env/pkgs", n) > 0) continue; // 已导出
                byte[] bytes;
                try (java.io.InputStream in = ctx.getAssets().open("termux_pkgs/" + n)) {
                    bytes = readAllBytes(in);
                }
                if (bytes == null || bytes.length == 0) {
                    all = false;
                    continue;
                }
                String r = PublicStorageWriter.writeBytes(ctx, "termux_env/pkgs", n,
                        "application/vnd.debian.binary-package", bytes);
                if (r == null) all = false;
            }
            return all;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] readAllBytes(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[262144];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /**
     * 手动兜底的"一行命令"：把准备脚本写到公共目录（已从 33KB 压到 ~11KB，不再内嵌大段 base64），让用户只需要粘一行。
     *
     * <p><b>注意：不能直接 {@code bash /sdcard/...}。</b>真机实测：/sdcard 是 noexec 且分区存储下
     * Termux 直接读 App 写的文件 → {@code Permission denied, exit 126}（App 写的脚本文件踩过同样坑）。
     * 所以这一行改为：先 {@code termux-setup-storage} 建立 ~/storage 桥（系统弹窗点允许），
     * 再把脚本拷到 $HOME（可执行）后 bash 运行。
     * 返回 null 表示脚本写不进去（此时只能退回复制整段脚本）。
     */
    public static String shortManualCommand(Context ctx) {
        File f = writeSetupScriptFile(ctx);
        if (f == null) {
            return null;
        }
        // setup.sh 由 App 生成到公共目录；Termux 侧先 termux-setup-storage 建桥（弹窗点允许），
        // 再区分两种失败：存储没授权（~/storage 不存在） vs 脚本没生成（App 还没写），给出明确提示。
        return "termux-setup-storage 2>/dev/null; f=\"$HOME/storage/downloads/" + EXPORT_SUBDIR
                + "/setup.sh\"; if [ -d \"$HOME/storage/downloads\" ]; then "
                + "[ -f \"$f\" ] && cp \"$f\" \"$HOME/setup.sh\" && bash \"$HOME/setup.sh\" "
                + "|| echo 'SETUP_SH_MISSING: 先在答题宝点「智能检测引导」生成 setup.sh，再回 Termux 粘这一行'; "
                + "else echo 'STORAGE_NOT_GRANTED: 系统弹「允许访问文件」时请点「允许」，或去 Termux 设置→应用→打开所有文件访问'; fi";
    }

    /** 在本机打开 Termux（用户去粘贴那一行） */
    /** 一键进 Ubuntu 容器：让 Termux 新建一个可见终端会话并自动执行 ~/ubuntu（无需手动粘贴） */
    public static boolean openUbuntuSession(Context ctx) {
        try {
            Intent i = new Intent();
            i.setClassName(TERMUX_PACKAGE, SERVICE);
            i.setAction(ACTION);
            i.putExtra(E_PATH, "/data/data/com.termux/files/usr/bin/bash");
            i.putExtra(E_ARGS, new String[]{"-lc", "~/ubuntu"});
            i.putExtra(E_WORKDIR, "/data/data/com.termux/files/home");
            i.putExtra(E_BACKGROUND, false);
            i.putExtra(E_SESSION_ACTION, 0);   // 0 = 新建会话（前台显示，有 tty，可交互）
            i.putExtra(E_SESSION_NEW, "ubuntu");
            ctx.startService(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

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

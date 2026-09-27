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
     * <p>2026-09-27 真机踩坑后重写，四条硬要求：
     * <ul>
     *   <li><b>幂等</b>：已装过的机器再点一次不能报错。proot-distro 5.9 的 {@code list} 把人类可读
     *       列表打到 <b>stderr</b>（stdout 为空），旧脚本用 {@code list 2>/dev/null | grep -q ubuntu}
     *       必然判成"没装"→ 再 install → {@code Error: container 'ubuntu' already exists} →
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
                .replace("__TUNA__", TUNA_ROOTFS_URL);
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

            quiz_main() {
              FAIL=0

              step "0/5 允许外部应用调用（答题宝后续才能自动下发命令）"
              mkdir -p "$HOME_DIR/.termux"
              touch "$HOME_DIR/.termux/termux.properties"
              grep -q "^allow-external-apps" "$HOME_DIR/.termux/termux.properties" || echo "allow-external-apps=true" >> "$HOME_DIR/.termux/termux.properties"
              if grep -q "^allow-external-apps *= *true" "$HOME_DIR/.termux/termux.properties"; then
                termux-reload-settings >/dev/null 2>&1 || true
                ok "allow-external-apps=true 已生效"
              else
                bad "allow-external-apps 没写进去，请手查 $HOME_DIR/.termux/termux.properties"
              fi

              step "1/5 存储权限（决定用本地 29MB 包还是联网下 30MB）"
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

              step "2/5 proot-distro"
              if command -v proot-distro >/dev/null 2>&1; then
                ok "proot-distro 已安装"
              else
                echo "正在安装 proot-distro（首次约 1~2 分钟）…"
                pkg update -y >/dev/null 2>&1 || true
                pkg install -y proot-distro >/dev/null 2>&1 || bad "proot-distro 安装失败：请检查网络后重跑本页"
              fi

              step "3/5 Ubuntu 容器"
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

              step "4/5 容器内完整 Python（tkinter/curses/readline/sqlite3/ssl/lzma/venv）"
              PY_CHECK='import tkinter, curses, readline, sqlite3, ssl, lzma, multiprocessing, venv; print("完整 Python", __import__("sys").version.split()[0], "| tkinter Tk", tkinter.TkVersion, "| fork", hasattr(__import__("os"), "fork"))'
              if command -v proot-distro >/dev/null 2>&1 && proot-distro login ubuntu -- /usr/bin/python3 -c "$PY_CHECK" 2>/dev/null; then
                ok "容器内 Python 已完整，跳过 apt（省 2~4 分钟）"
              else
                echo "正在容器内安装 python3-full / python3-tk / pip / venv（约 2~4 分钟）…"
                proot-distro login ubuntu -- /bin/bash -lc 'for f in /etc/apt/sources.list /etc/apt/sources.list.d/ubuntu.sources; do if [ -f "$f" ]; then sed -i "s|http://archive.ubuntu.com/ubuntu|https://mirrors.tuna.tsinghua.edu.cn/ubuntu|g; s|http://security.ubuntu.com/ubuntu|https://mirrors.tuna.tsinghua.edu.cn/ubuntu|g" "$f"; fi; done; apt-get update -y && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends python3-full python3-tk python3-venv python3-pip python3-setuptools ca-certificates' || bad "容器内 apt 安装失败：请检查网络后重跑本页"
                proot-distro login ubuntu -- /usr/bin/python3 -c "$PY_CHECK" 2>/dev/null || bad "容器内 Python 仍不完整"
              fi

              step "5/5 入口 ~/ubuntu 与最终验证"
              cat > "$HOME_DIR/ubuntu" <<'QUIZ_UBUNTU_EOF'
            #!/data/data/com.termux/files/usr/bin/bash
            exec proot-distro login ubuntu -- "$@"
            QUIZ_UBUNTU_EOF
              chmod +x "$HOME_DIR/ubuntu"
              if "$HOME_DIR/ubuntu" python3 -c 'import tkinter, curses, readline, sqlite3, ssl, lzma, multiprocessing; print("✅ 完整体 Python 验证通过 | Python", __import__("sys").version.split()[0], "| tkinter Tk", tkinter.TkVersion)'; then
                echo ""
                echo "🎉 环境准备完成：Termux 里输入  ~/ubuntu  进入真 Ubuntu"
                echo "   答题宝以后可直接用这个环境跑 Python（含 tkinter 图形库）"
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

package com.oilquiz.app.ai.tool;

import android.app.ActivityManager;
import android.graphics.Bitmap;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.oilquiz.app.ai.agent.online.AgentWorkspace;
import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.util.AILogger;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Tool(
    value = "system_resource",
    description = "系统资源调用工具，支持打开应用、打开URL、发送短信、拨打电话、控制应用、执行Shell命令(shell_command)、在Termux里执行命令(termux_exec，完整Linux环境)、读写系统设置等。支持模糊匹配应用名，找不到时自动回退系统选择器。" + BaseAITool.TOOLKIT_HINT,
    category = "system",
    aliases = {"open_app", "send_sms", "make_call", "launch_app"},
    actions = {
        @Action(name = "open_app", description = "打开指定应用"),
        @Action(name = "open_url", description = "打开URL"),
        @Action(name = "send_sms", description = "发送短信"),
        @Action(name = "make_call", description = "拨打电话"),
        @Action(name = "list_apps", description = "列出已安装应用"),
        @Action(name = "get_app_info", description = "获取应用信息"),
        @Action(name = "app_control", description = "控制应用：强制停止/清除数据/获取详细信息"),
        @Action(name = "shell_command", description = "执行Shell命令"),
        @Action(name = "ssh_exec", description = "SSH 连接任意远程主机并执行单条命令（内置 ssh/scp 客户端，不依赖本地 Termux；可连电脑、云服务器、路由器、NAS 等任何开 sshd 的机器）。支持密钥免密或密码认证（密码认证走内置 askpass 机制，开箱即用无需安装）。注意：① 非交互，单条命令执行完即断开，25 秒超时；② 默认跳过主机指纹校验（StrictHostKeyChecking=no）方便首次连接；③ 参数 host=目标IP/域名、user=用户名、command=远程命令、port=端口(默认22)、password=密码、key_file=私钥路径"),
        @Action(name = "termux_exec", description = "在 Termux 中执行命令（完整 Linux 环境，可 apt/pip/ssh/git 等；需已装 Termux 并授予 RUN_COMMAND 权限）。注意：① 本工具同一时刻只执行一条命令，多条命令必须串行调用，不要并发（并发会串输出）；② 用 proot-distro login ubuntu 进容器后，容器内裸 git/gcc/make/cmake 等命令可能命中 Termux 的二进制（PATH 被继承），简单容器命令会被自动注入容器优先 PATH，复杂命令请自行用绝对路径 /usr/bin/git）"),
        @Action(name = "http_download", description = "下载 URL 到本地（App 内 okhttp 实现，https 走系统证书校验，比 shell 里的 wget 更可靠）"),
        @Action(name = "shell_mode", description = "切换 shell 拦截模式：full=不拦截(默认，用户自己的设备) / readonly=恢复危险命令与敏感路径拦截 / query=查询当前"),
        @Action(name = "read_setting", description = "读取系统设置"),
        @Action(name = "write_setting", description = "修改系统设置"),
        @Action(name = "get_current_app", description = "获取当前前台应用信息")
    },
    params = {
        @Param(name = "action", type = "string", description = "操作类型", required = true),
        @Param(name = "app", type = "string", description = "应用名称或包名", required = false),
        @Param(name = "url", type = "string", description = "URL地址（http_download 时为要下载的地址）", required = false),
        @Param(name = "mode", type = "string", description = "shell_mode 用: full(默认不拦) / readonly(开启拦截) / query(查询)", required = false),
        @Param(name = "path", type = "string", description = "保存路径（http_download 用；绝对路径或工作区相对路径，缺省存到工作区 files/ 并用 URL 文件名）", required = false),
        @Param(name = "phone", type = "string", description = "电话号码", required = false),
        @Param(name = "message", type = "string", description = "短信内容", required = false),
        @Param(name = "command", type = "string", description = "Shell命令（shell_command 或 termux_exec 均可使用：termux_exec 时命令在 Termux 的 Linux 环境里执行；ssh_exec 时为要在远程主机执行的单条命令）", required = false),
        @Param(name = "host", type = "string", description = "SSH 目标主机 IP/域名（ssh_exec 用）", required = false),
        @Param(name = "port", type = "string", description = "SSH 端口，默认 22（ssh_exec 用）", required = false),
        @Param(name = "user", type = "string", description = "SSH 用户名（ssh_exec 用）", required = false),
        @Param(name = "password", type = "string", description = "SSH 密码（ssh_exec 用；App 内置 askpass 机制开箱即用，无需安装 sshpass）", required = false),
        @Param(name = "key_file", type = "string", description = "SSH 私钥绝对路径（ssh_exec 用；缺省尝试 ~/.ssh/id_ed25519、id_rsa）", required = false),
        @Param(name = "setting_type", type = "string", description = "设置类型: system/secure/global", required = false),
        @Param(name = "setting_key", type = "string", description = "设置键名", required = false),
        @Param(name = "setting_value", type = "string", description = "设置值", required = false),
        @Param(name = "control_action", type = "string", description = "控制操作: force_stop/clear_data/detailed_info", required = false)
    }
)
public class SystemResourceTool implements AITool {
    private static final String TAG = "SystemResourceTool";



    /** Shell 命令执行超时（秒）：超时强制终止，防止命令挂起阻塞 Agent。
     *  取值 25 < 框架工具超时 30s —— 这样超时由我们自己返回明确信息（含已产生的输出），
     *  而不是被框架先杀掉报一个空的 "工具执行失败: null"。 */
    private static final long SHELL_TIMEOUT_SECONDS = 25;
    /** Shell 输出最大字符数：超出截断并终止进程（防 top/cat 大文件撑爆） */
    private static final int MAX_SHELL_OUTPUT_CHARS = 20000;

    /** 危险命令黑名单（词边界匹配，防分段/管道绕过） */
    private static final String[] BANNED_COMMANDS = {
            "rm", "rmdir", "format", "factory_reset", "reboot", "shutdown", "halt", "poweroff",
            "su", "dd", "mkfs", "mkfs.ext", "mount", "umount", "chmod", "chown", "chgrp",
            "wipe", "erase", "mknod", "fdisk", "parted", "resize2fs", "e2fsck",
            "kill", "pkill", "killall", "killall5", "ssh", "scp",
            "iptables", "ip6tables", "setenforce", "chroot", "fastboot", "adb",
            "svc", "sepolicy", "truncate", "tune2fs"
            // 注：wget/curl/mv/ln/xargs/busybox/nc/telnet 已放开 —— 内置 busybox 工具链后它们是正常工具
    };

    /** 匹配 "> /dev/xxx" / ">> /dev/xxx"（含 2> 这类 fd 前缀） */
    private static final java.util.regex.Pattern DEVICE_WRITE =
            java.util.regex.Pattern.compile(">{1,2}\\s*(/dev/[^\\s;|&)]+)");

    /** 允许重定向的设备节点：丢弃输出/标准流，无害且是最常用写法（曾把 find ... 2>/dev/null 误杀） */
    private static final java.util.Set<String> SAFE_DEV_TARGETS = new java.util.HashSet<>(java.util.Arrays.asList(
            "/dev/null", "/dev/stdout", "/dev/stderr", "/dev/tty"));

    // ===== 内置 Linux 命令工具链（Alpine busybox-static，随 APK 解压到 nativeLibraryDir）=====
    // 内置 busybox 取自 Termux 官方包（bionic 编译，天然不撞 seccomp），拆成三件随 jniLibs 分发：
    /** 启动器（有 PT_INTERP，可 exec；argv[0]=applet 名） */
    private static final String BUSYBOX_LAUNCHER_LIB_NAME = "libbusybox_launcher.so";
    /** 真正的多合一二进制（纯 .so，无解释器，只能被启动器按 NEEDED 加载） */
    private static final String BUSYBOX_CORE_LIB_NAME = "libbusybox.so";
    /** busybox 依赖的 Termux 私有库（Android 系统里没有，必须一起带） */
    private static final String BUSYBOX_SELINUX_LIB_NAME = "libandroid-selinux.so";
    /** libandroid-selinux 又依赖 Termux 的 pcre2 */
    private static final String BUSYBOX_PCRE2_LIB_NAME = "libpcre2-8.so";

    // ===== 额外内置的 Termux 工具（bionic 构建，随 jniLibs 解压到 nativeLibraryDir，可执行）=====
    /** openssl CLI：真正带 TLS（busybox 的 wget 没有 TLS） */
    private static final String OPENSSL_BIN_LIB = "libopenssl_bin.so";
    /** openssl 的运行时库（libcrypto/libssl/libz） */
    private static final String[] EXTRA_LIB_NAMES = {"libcrypto.so", "libssl.so", "libz.so"};
    /** CA 包：内容是 PEM，命名成 lib*.so 只是为了能随 jniLibs 打包 */
    private static final String CA_CERT_LIB = "libcacert.pem.so";

    // ===== openssh（Termux bionic 构建）：可执行文件随 jniLibs，依赖库以 tar.gz 单文件分发 =====
    /** ssh 系列可执行文件（bin 目录里做成软链接，指向 nativeLibraryDir/libssh_*.so） */
    private static final String[] SSH_BIN_NAMES = {"ssh", "scp", "sftp", "ssh-keygen", "ssh-keyscan", "ssh-add"};
    /** 通用工具包清单（tools/tests/bundle_termux_bins.py 生成）：JSON，列出工具名 -> lib*.so */
    private static final String TOOLKIT_MANIFEST_LIB = "libtoolkit_manifest.so";

    /** C 启动器：为单个工具设置依赖库路径后再 exec 真正的二进制（避免全局 LD_LIBRARY_PATH 污染系统命令） */
    private static final String LAUNCHER_LIB = "liblauncher.so";

    /** 依赖库包：tar.gz（保留 libcrypto.so.3 这类带版本号的真实名字）—— dlopen 数据目录允许，execve 不允许，
     *  所以库可以解包到 files/lib 用 LD_LIBRARY_PATH 加载，二进制必须留在 nativeLibraryDir。 */
    private static final String SSH_LIBS_BUNDLE = "libssh_libs.so";
    /** applet 软链接目录（filesDir/bin）：只初始化一次 */
    private static final Object BUSYBOX_LOCK = new Object();
    private static volatile String sBusyboxBinDir;

    // ===== Termux 集成（RUN_COMMAND Intent，取值对齐 TermuxConstants）=====
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    private static final String TERMUX_ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    private static final String TERMUX_PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";
    private static final String TERMUX_EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String TERMUX_EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String TERMUX_EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String TERMUX_EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String TERMUX_EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT";
    /** Termux 回传结果的 Bundle 及其键（EXTRA_PLUGIN_RESULT_BUNDLE = "result"） */
    private static final String TERMUX_RESULT_BUNDLE = "result";
    private static final String TERMUX_RESULT_STDOUT = "stdout";
    private static final String TERMUX_RESULT_STDERR = "stderr";
    private static final String TERMUX_RESULT_EXIT_CODE = "exitCode";
    private static final String TERMUX_RESULT_ERRMSG = "errmsg";
    private static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    /** 我们自己的广播 action：承接 Termux 回传的执行结果 */
    private static final String TERMUX_RESULT_ACTION = "com.oilquiz.app.TERMUX_EXEC_RESULT";
    /** Termux 命令等待上限（秒）：Termux app-shell 启动 + 执行，留足余量但低于框架 30s */
    private static final long TERMUX_TIMEOUT_SECONDS = 20;
    private static final AtomicInteger TERMUX_REQUEST_CODE = new AtomicInteger(1000);
    /** termux_exec 全局串行锁：多个并发调用的 receiver 都监听同一 action，广播会被所有 receiver 收到、
     *  谁 poll 到算谁的 → 输出互相串（真机实测三条并发输出串到同一条）。同一时刻只允许一个调用在等结果。 */
    private static final Object TERMUX_EXEC_LOCK = new Object();

    /** 敏感路径黑名单：读取/写入其他应用数据、内核接口、凭据文件一律拒绝 */
    private static final String[] BANNED_PATHS = {
            "/data/data/", "/data/user/", "/data/system/", "/data/local/",
            "/proc/", "/sys/", "/dev/block", "/dev/mem", "/dev/kmem", "/dev/sd",
            "/etc/shadow", "/etc/passwd", "/etc/sudoers", "/data/misc/keychain",
            ".keystore", "id_rsa", "id_dsa", "authorized_keys", "credential", "token"
    };

    private final Context context;
    
    private static final Map<String, String> APP_PACKAGE_MAP = new HashMap<>();
    static {
        APP_PACKAGE_MAP.put("微信", "com.tencent.mm");
        APP_PACKAGE_MAP.put("wechat", "com.tencent.mm");
        APP_PACKAGE_MAP.put("qq", "com.tencent.mobileqq");
        APP_PACKAGE_MAP.put("QQ", "com.tencent.mobileqq");
        APP_PACKAGE_MAP.put("支付宝", "com.eg.android.AlipayGphone");
        APP_PACKAGE_MAP.put("alipay", "com.eg.android.AlipayGphone");
        APP_PACKAGE_MAP.put("淘宝", "com.taobao.taobao");
        APP_PACKAGE_MAP.put("taobao", "com.taobao.taobao");
        APP_PACKAGE_MAP.put("京东", "com.jingdong.app.mall");
        APP_PACKAGE_MAP.put("jd", "com.jingdong.app.mall");
        APP_PACKAGE_MAP.put("微博", "com.sina.weibo");
        APP_PACKAGE_MAP.put("weibo", "com.sina.weibo");
        APP_PACKAGE_MAP.put("抖音", "com.ss.android.ugc.trill");
        APP_PACKAGE_MAP.put("douyin", "com.ss.android.ugc.trill");
        APP_PACKAGE_MAP.put("快手", "com.kuaishou.nebula");
        APP_PACKAGE_MAP.put("kuaishou", "com.kuaishou.nebula");
        APP_PACKAGE_MAP.put("浏览器", "com.android.browser");
        APP_PACKAGE_MAP.put("browser", "com.android.browser");
        APP_PACKAGE_MAP.put("相机", "com.android.camera");
        APP_PACKAGE_MAP.put("camera", "com.android.camera");
        APP_PACKAGE_MAP.put("设置", "com.android.settings");
        APP_PACKAGE_MAP.put("settings", "com.android.settings");
        APP_PACKAGE_MAP.put("地图", "com.autonavi.minimap");
        APP_PACKAGE_MAP.put("高德地图", "com.autonavi.minimap");
        APP_PACKAGE_MAP.put("amap", "com.autonavi.minimap");
        APP_PACKAGE_MAP.put("百度地图", "com.baidu.BaiduMap");
        APP_PACKAGE_MAP.put("baidu map", "com.baidu.BaiduMap");
        APP_PACKAGE_MAP.put("音乐", "com.android.music");
        APP_PACKAGE_MAP.put("music", "com.android.music");
        APP_PACKAGE_MAP.put("视频", "com.android.video");
        APP_PACKAGE_MAP.put("video", "com.android.video");
        APP_PACKAGE_MAP.put("日历", "com.android.calendar");
        APP_PACKAGE_MAP.put("calendar", "com.android.calendar");
        APP_PACKAGE_MAP.put("联系人", "com.android.contacts");
        APP_PACKAGE_MAP.put("contacts", "com.android.contacts");
        APP_PACKAGE_MAP.put("短信", "com.android.mms");
        APP_PACKAGE_MAP.put("sms", "com.android.mms");
        APP_PACKAGE_MAP.put("电话", "com.android.phone");
        APP_PACKAGE_MAP.put("phone", "com.android.phone");
        APP_PACKAGE_MAP.put("邮件", "com.android.email");
        APP_PACKAGE_MAP.put("email", "com.android.email");
        APP_PACKAGE_MAP.put("微信支付", "com.tencent.mm");
        APP_PACKAGE_MAP.put("滴滴", "com.sdu.didi.psnger");
        APP_PACKAGE_MAP.put("didi", "com.sdu.didi.psnger");
        APP_PACKAGE_MAP.put("美团", "com.meituan.meituan");
        APP_PACKAGE_MAP.put("meituan", "com.meituan.meituan");
        APP_PACKAGE_MAP.put("饿了么", "me.ele");
        APP_PACKAGE_MAP.put("eleme", "me.ele");
        APP_PACKAGE_MAP.put("携程", "ctrip.android.view");
        APP_PACKAGE_MAP.put("ctrip", "ctrip.android.view");
        APP_PACKAGE_MAP.put("大众点评", "com.dianping.v1");
        APP_PACKAGE_MAP.put("dianping", "com.dianping.v1");
    }
    
    public SystemResourceTool(Context context) {
        this.context = context.getApplicationContext();
    }
    
    @Override
    public String getName() {
        return "system_resource";
    }
    
    @Override
    public String getDescription() {
        return "系统资源调用工具，支持打开应用、打开URL、发送短信、拨打电话、控制应用、执行Shell命令(shell_command)、在Termux里执行命令(termux_exec，完整Linux环境)、读写系统设置等。支持模糊匹配应用名，找不到时自动回退系统选择器。" + BaseAITool.TOOLKIT_HINT;
    }
    
    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        normalizeParameters(parameters);
        
        try {
            String action = (String) parameters.get("action");
            if (action == null) {
                return new AIToolResult("缺少参数: action", parameters);
            }
            
            switch (action) {
                case "open_app":
                    return openApp(parameters);
                case "open_url":
                    return openUrl(parameters);
                case "send_sms":
                    return sendSms(parameters);
                case "make_call":
                    return makeCall(parameters);
                case "send_email":
                    return sendEmail(parameters);
                case "open_map":
                    return openMap(parameters);
                case "share_text":
                    return shareText(parameters);
                case "open_settings":
                    return openSettings(parameters);
                case "list_apps":
                    return listInstalledApps();
                case "check_app":
                case "get_app_info":
                    return checkAppInstalled(parameters);
                case "app_control":
                    return appControl(parameters);
                case "shell_command":
                    return executeShellCommand(parameters);
                case "ssh_exec":
                    return sshExec(parameters);
                case "termux_exec":
                    return termuxExec(parameters);
                case "http_download":
                    return httpDownload(parameters);
                case "shell_mode":
                    return shellMode(parameters);
                case "read_setting":
                    return readSetting(parameters);
                case "write_setting":
                    return writeSetting(parameters);
                case "get_current_app":
                    return getCurrentApp(parameters);
                default:
                    return new AIToolResult("未知操作: " + action, parameters);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "执行出错: " + e.getMessage(), e);
            return new AIToolResult("错误: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult openApp(Map<String, Object> parameters) {
        String appName = (String) parameters.get("app");
        String packageName = (String) parameters.get("package");
        
        if (appName == null && packageName == null) {
            return new AIToolResult("缺少参数: app或package", parameters);
        }
        
        // 1. 如果直接给了包名，尝试打开
        if (packageName != null) {
            return tryLaunchApp(packageName, appName, parameters);
        }
        
        // 2. 特殊处理：浏览器动态检测
        String lower = appName.toLowerCase();
        if ("浏览器".equals(appName) || "browser".equals(lower) || "chrome".equals(lower)) {
            String browserPkg = findBrowserPackage();
            if (browserPkg != null) {
                return tryLaunchApp(browserPkg, appName, parameters);
            }
        }
        
        // 3. 从硬编码映射查找
        String mappedPkg = APP_PACKAGE_MAP.get(lower);
        if (mappedPkg != null) {
            return tryLaunchApp(mappedPkg, appName, parameters);
        }
        
        // 4. 从已安装应用列表模糊匹配
        String matchedPkg = findAppByFuzzyName(appName);
        if (matchedPkg != null) {
            return tryLaunchApp(matchedPkg, appName, parameters);
        }
        
        // 5. 回退：使用 Intent chooser 让系统选择
        return openAppViaChooser(appName, parameters);
    }
    
    /**
     * 尝试启动指定包名的应用
     */
    private AIToolResult tryLaunchApp(String packageName, String appName, Map<String, Object> parameters) {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                
                Map<String, Object> result = new HashMap<>();
                result.put("status", "success");
                result.put("message", "已打开应用: " + (appName != null ? appName : packageName));
                result.put("package", packageName);
                return new AIToolResult(result, parameters);
            } else {
                return new AIToolResult("应用未安装或无法启动: " + packageName, parameters);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "打开应用失败: " + e.getMessage());
            return new AIToolResult("打开应用失败: " + e.getMessage(), parameters);
        }
    }
    
    /**
     * 模糊匹配已安装应用名称（支持部分匹配、包含匹配）
     */
    private String findAppByFuzzyName(String appName) {
        if (appName == null || appName.isEmpty()) return null;
        String lowerName = appName.toLowerCase();
        PackageManager pm = context.getPackageManager();
        
        // 获取所有有 launcher 的应用
        Intent launchIntent = new Intent(Intent.ACTION_MAIN);
        launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> launcherApps = pm.queryIntentActivities(launchIntent, 0);
        
        String bestMatch = null;
        int bestScore = 0;
        
        for (ResolveInfo ri : launcherApps) {
            if (ri.activityInfo == null || ri.activityInfo.applicationInfo == null) continue;
            String label = pm.getApplicationLabel(ri.activityInfo.applicationInfo).toString();
            String labelLower = label.toLowerCase();
            String pkg = ri.activityInfo.packageName;
            
            int score = 0;
            // 完全匹配
            if (labelLower.equals(lowerName) || pkg.equals(lowerName)) {
                score = 100;
            }
            // 以...开头
            else if (labelLower.startsWith(lowerName) || lowerName.length() >= 2 && labelLower.contains(lowerName)) {
                score = 80;
            }
            // 应用名包含输入或输入包含应用名
            else if (labelLower.contains(lowerName) || lowerName.contains(labelLower)) {
                score = 60;
            }
            // 逐字符匹配（中文按字匹配）
            else {
                int matchCount = 0;
                for (int i = 0; i < lowerName.length(); i++) {
                    if (labelLower.indexOf(lowerName.charAt(i)) >= 0) matchCount++;
                }
                if (lowerName.length() > 0 && matchCount >= lowerName.length() * 0.6) {
                    score = 30;
                }
            }
            
            if (score > bestScore) {
                bestScore = score;
                bestMatch = pkg;
            }
        }
        
        // 至少需要30分才算匹配成功
        return bestScore >= 30 ? bestMatch : null;
    }
    
    /**
     * 回退方案：使用 Intent chooser 让系统选择可处理的应用
     */
    private AIToolResult openAppViaChooser(String appName, Map<String, Object> parameters) {
        try {
            // 尝试用应用市场搜索
            Intent marketIntent = new Intent(Intent.ACTION_VIEW, 
                    Uri.parse("market://search?q=" + Uri.encode(appName)));
            marketIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            
            // 先尝试用应用名作为搜索关键词，让系统选择
            // 如果没有任何应用可以处理，则提示用户
            PackageManager pm = context.getPackageManager();
            if (marketIntent.resolveActivity(pm) != null) {
                context.startActivity(marketIntent);
                Map<String, Object> result = new HashMap<>();
                result.put("status", "fallback");
                result.put("message", "未找到\"" + appName + "\"，已打开应用商店搜索");
                result.put("suggestion", "请从搜索结果中安装或选择应用");
                return new AIToolResult(result, parameters);
            }
            
            // 最终回退：列出可能的匹配应用供用户参考
            List<String> similarApps = findSimilarApps(appName, 5);
            Map<String, Object> result = new HashMap<>();
            result.put("status", "not_found");
            result.put("message", "未找到应用: " + appName);
            if (!similarApps.isEmpty()) {
                result.put("similar_apps", similarApps);
                result.put("suggestion", "您可能想打开以下应用之一，请指定包名重试");
            } else {
                result.put("suggestion", "设备上没有匹配的应用，请先安装");
            }
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "回退打开应用失败: " + e.getMessage());
            return new AIToolResult("未找到应用: " + appName + "，且回退方案失败", parameters);
        }
    }
    
    /**
     * 查找名称相似的应用列表
     */
    private List<String> findSimilarApps(String appName, int maxResults) {
        List<String> results = new ArrayList<>();
        if (appName == null || appName.isEmpty()) return results;
        String lowerName = appName.toLowerCase();
        PackageManager pm = context.getPackageManager();
        
        Intent launchIntent = new Intent(Intent.ACTION_MAIN);
        launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> launcherApps = pm.queryIntentActivities(launchIntent, 0);
        
        for (ResolveInfo ri : launcherApps) {
            if (ri.activityInfo == null || ri.activityInfo.applicationInfo == null) continue;
            String label = pm.getApplicationLabel(ri.activityInfo.applicationInfo).toString();
            String labelLower = label.toLowerCase();
            // 有任何字符匹配就加入候选
            for (int i = 0; i < lowerName.length(); i++) {
                if (labelLower.indexOf(lowerName.charAt(i)) >= 0) {
                    results.add(label + " (" + ri.activityInfo.packageName + ")");
                    break;
                }
            }
            if (results.size() >= maxResults) break;
        }
        return results;
    }
    
    private AIToolResult openUrl(Map<String, Object> parameters) {
        String url = (String) parameters.get("url");
        
        if (url == null || url.isEmpty()) {
            return new AIToolResult("缺少参数: url", parameters);
        }
        
        try {
            // 兜底：打开 pollinations.ai 图片生成链接时，改为下载图片并内联显示（不弹浏览器）
            if (url.contains("image.pollinations.ai")) {
                return downloadAndShowImage(url, parameters);
            }

            // 正确编码 URL 中的非 ASCII 字符，避免浏览器错误 Punycode 编码
            String encodedUrl = encodeInternationalUrl(url);
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(encodedUrl));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", "已打开链接: " + url);
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "打开链接失败: " + e.getMessage());
            return new AIToolResult("打开链接失败: " + e.getMessage(), parameters);
        }
    }

    /**
     * 兜底：下载 pollinations 生成的图片到本地，返回 image_grid 组件（对话内联显示）。
     * 防止模型用 open_url 打开图片链接而不是 image_gen 工具时，图片无法在对话中展示。
     */
    private AIToolResult downloadAndShowImage(String url, Map<String, Object> parameters) {
        try {
            okhttp3.Request request = com.oilquiz.app.ai.util.NetworkUtil.createApiRequestBuilder(url)
                    .get().build();
            try (okhttp3.Response response = com.oilquiz.app.ai.util.NetworkUtil.getClient().newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return new AIToolResult("图片下载失败(HTTP " + response.code() + ")", parameters);
                }
                okhttp3.ResponseBody body = response.body();
                if (body == null) return new AIToolResult("图片下载失败: 空响应", parameters);

                java.io.File dir = com.oilquiz.app.ai.agent.online.AgentWorkspace.getInstance(context).getWorkspaceDir();
                if (!dir.exists()) dir.mkdirs();
                java.io.File imageFile = new java.io.File(dir, "gen_" + System.currentTimeMillis() + ".jpg");
                try (java.io.InputStream input = body.byteStream();
                     java.io.FileOutputStream output = new java.io.FileOutputStream(imageFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                        if (imageFile.length() > 12 * 1024 * 1024) {
                            output.close();
                            imageFile.delete();
                            return new AIToolResult("图片下载失败: 超过大小限制", parameters);
                        }
                    }
                }
                if (!imageFile.exists() || imageFile.length() == 0) {
                    imageFile.delete();
                    return new AIToolResult("图片下载失败: 未生成有效图片", parameters);
                }

                android.net.Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                        context, "com.oilquiz.app.fileprovider", imageFile);

                Map<String, Object> result = new HashMap<>();
                result.put("status", "success");
                result.put("imagePath", imageFile.getAbsolutePath());
                result.put("contentUri", contentUri.toString());
                result.put("message", "图片已下载并在对话中展示");

                AIToolResult toolResult = new AIToolResult(result, parameters);
                try {
                    org.json.JSONObject props = new org.json.JSONObject();
                    props.put("columns", 1);
                    org.json.JSONArray images = new org.json.JSONArray();
                    images.put(contentUri.toString());
                    props.put("images", images);
                    toolResult.withComponent(com.oilquiz.app.ai.chat.component.ComponentData.of("image_grid", props));
                } catch (Exception ignored) {
                }
                return toolResult;
            }
        } catch (Exception e) {
            AILogger.e(TAG, "图片下载失败: " + e.getMessage());
            return new AIToolResult("图片下载失败: " + e.getMessage(), parameters);
        }
    }
    
    /**
     * 编码含非 ASCII 字符的 URL：域名用 IDN.toASCII，路径用百分号编码
     */
    private static String encodeInternationalUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        boolean hasNonAscii = false;
        for (int i = 0; i < url.length(); i++) {
            if (url.charAt(i) > 127) { hasNonAscii = true; break; }
        }
        if (!hasNonAscii) return url;
        
        try {
            int schemeEnd = url.indexOf("://");
            if (schemeEnd < 0) return url;
            String scheme = url.substring(0, schemeEnd);
            String rest = url.substring(schemeEnd + 3);
            
            int pathStart = rest.indexOf('/');
            int queryStart = rest.indexOf('?');
            int fragStart = rest.indexOf('#');
            
            int authorityEnd = rest.length();
            if (pathStart >= 0) authorityEnd = pathStart;
            else if (queryStart >= 0) authorityEnd = queryStart;
            else if (fragStart >= 0) authorityEnd = fragStart;
            
            String authority = rest.substring(0, authorityEnd);
            String remainder = rest.substring(authorityEnd);
            
            // 处理 host
            String host = authority;
            String port = "";
            int colonIdx = authority.lastIndexOf(':');
            if (colonIdx >= 0) {
                String possiblePort = authority.substring(colonIdx + 1);
                boolean isPort = true;
                for (int i = 0; i < possiblePort.length(); i++) {
                    if (!Character.isDigit(possiblePort.charAt(i))) { isPort = false; break; }
                }
                if (isPort && !possiblePort.isEmpty()) {
                    host = authority.substring(0, colonIdx);
                    port = ":" + possiblePort;
                }
            }
            
            String encodedHost;
            try { encodedHost = java.net.IDN.toASCII(host); }
            catch (Exception e) { encodedHost = host; }
            
            // 编码路径中的非 ASCII 字符
            StringBuilder encodedRemainder = new StringBuilder(remainder.length());
            for (int i = 0; i < remainder.length(); i++) {
                char c = remainder.charAt(i);
                if (c > 127) {
                    byte[] bytes = String.valueOf(c).getBytes("UTF-8");
                    for (byte b : bytes) encodedRemainder.append(String.format("%%%.2X", b & 0xFF));
                } else if (c == ' ') {
                    encodedRemainder.append("%20");
                } else {
                    encodedRemainder.append(c);
                }
            }
            
            return scheme + "://" + encodedHost + port + encodedRemainder;
        } catch (Exception e) {
            return url;
        }
    }
    
    private AIToolResult sendSms(Map<String, Object> parameters) {
        String phone = (String) parameters.get("phone");
        String message = (String) parameters.get("message");
        
        if (phone == null || phone.isEmpty()) {
            return new AIToolResult("缺少参数: phone", parameters);
        }
        
        try {
            Uri smsUri = Uri.parse("smsto:" + phone);
            Intent intent = new Intent(Intent.ACTION_SENDTO, smsUri);
            if (message != null && !message.isEmpty()) {
                intent.putExtra("sms_body", message);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", "已打开短信发送界面");
            result.put("phone", phone);
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "发送短信失败: " + e.getMessage());
            return new AIToolResult("发送短信失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult makeCall(Map<String, Object> parameters) {
        String phone = (String) parameters.get("phone");
        
        if (phone == null || phone.isEmpty()) {
            return new AIToolResult("缺少参数: phone", parameters);
        }
        
        try {
            Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", "已打开拨号界面");
            result.put("phone", phone);
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "拨打电话失败: " + e.getMessage());
            return new AIToolResult("拨打电话失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult sendEmail(Map<String, Object> parameters) {
        String to = (String) parameters.get("to");
        String subject = (String) parameters.get("subject");
        String body = (String) parameters.get("body");
        
        if (to == null || to.isEmpty()) {
            return new AIToolResult("缺少参数: to", parameters);
        }
        
        try {
            Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + to));
            if (subject != null) {
                intent.putExtra(Intent.EXTRA_SUBJECT, subject);
            }
            if (body != null) {
                intent.putExtra(Intent.EXTRA_TEXT, body);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", "已打开邮件发送界面");
            result.put("to", to);
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "发送邮件失败: " + e.getMessage());
            return new AIToolResult("发送邮件失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult openMap(Map<String, Object> parameters) {
        String location = (String) parameters.get("location");
        String address = (String) parameters.get("address");
        
        if (location == null && address == null) {
            return new AIToolResult("缺少参数: location或address", parameters);
        }
        
        String query = location != null ? location : address;
        
        try {
            Uri mapUri = Uri.parse("geo:0,0?q=" + Uri.encode(query));
            Intent intent = new Intent(Intent.ACTION_VIEW, mapUri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", "已打开地图: " + query);
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "打开地图失败: " + e.getMessage());
            return new AIToolResult("打开地图失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult shareText(Map<String, Object> parameters) {
        String text = (String) parameters.get("text");
        String title = (String) parameters.get("title");
        
        if (text == null || text.isEmpty()) {
            return new AIToolResult("缺少参数: text", parameters);
        }
        
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, text);
            if (title != null) {
                intent.putExtra(Intent.EXTRA_TITLE, title);
            }
            
            Intent chooser = Intent.createChooser(intent, title != null ? title : "分享");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", "已打开分享界面");
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "分享失败: " + e.getMessage());
            return new AIToolResult("分享失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult openSettings(Map<String, Object> parameters) {
        String setting = (String) parameters.get("setting");
        
        Intent intent;
        if (setting != null && !setting.isEmpty()) {
            switch (setting.toLowerCase()) {
                case "wifi":
                    intent = new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS);
                    break;
                case "bluetooth":
                    intent = new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
                    break;
                case "location":
                    intent = new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                    break;
                case "display":
                    intent = new Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS);
                    break;
                case "sound":
                    intent = new Intent(android.provider.Settings.ACTION_SOUND_SETTINGS);
                    break;
                case "storage":
                    intent = new Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
                    break;
                case "app":
                    intent = new Intent(android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS);
                    break;
                case "battery":
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        intent = new Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS);
                    } else {
                        intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
                    }
                    break;
                default:
                    intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
                    break;
            }
        } else {
            intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
        }
        
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        
        try {
            context.startActivity(intent);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", "已打开设置界面: " + (setting != null ? setting : "系统设置"));
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "打开设置失败: " + e.getMessage());
            return new AIToolResult("打开设置失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult listInstalledApps() {
        List<Map<String, Object>> userApps = new ArrayList<>();
        List<Map<String, Object>> systemApps = new ArrayList<>();
        
        try {
            PackageManager pm = context.getPackageManager();
            
            // 获取所有有 launcher 图标的应用包名集合（用于标记可启动的应用）
            Intent launchIntent = new Intent(Intent.ACTION_MAIN);
            launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> launcherApps = pm.queryIntentActivities(launchIntent, 0);
            java.util.Set<String> launcherPackages = new java.util.HashSet<>();
            for (ResolveInfo ri : launcherApps) {
                if (ri.activityInfo != null) {
                    launcherPackages.add(ri.activityInfo.packageName);
                }
            }
            
            // 返回所有已安装应用
            List<ApplicationInfo> packages = pm.getInstalledApplications(0);
            for (ApplicationInfo packageInfo : packages) {
                boolean isSystem = (packageInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean hasLauncher = launcherPackages.contains(packageInfo.packageName);
                boolean isUpdated = (packageInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                
                // 包含规则：
                // 1. 非系统应用（第三方应用）→ 无条件包含
                // 2. 有 launcher 图标的系统应用 → 包含
                // 3. 用户更新过的系统应用 → 包含
                // 4. 纯系统服务包（无 launcher、未更新）→ 跳过
                if (isSystem && !hasLauncher && !isUpdated) {
                    continue;
                }
                
                Map<String, Object> app = new HashMap<>();
                String label = pm.getApplicationLabel(packageInfo).toString();
                app.put("name", label);
                app.put("package", packageInfo.packageName);
                if (!isSystem) {
                    userApps.add(app);
                } else {
                    systemApps.add(app);
                }
            }
            
            // 按名称排序
            java.util.Comparator<Map<String, Object>> byName = (a, b) -> 
                    ((String) a.get("name")).compareTo((String) b.get("name"));
            java.util.Collections.sort(userApps, byName);
            java.util.Collections.sort(systemApps, byName);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("user_app_count", userApps.size());
            result.put("system_app_count", systemApps.size());
            result.put("total_count", userApps.size() + systemApps.size());
            // 用户应用全量返回
            result.put("user_apps", userApps);
            // 系统应用全量返回（不再截断）
            result.put("system_apps", systemApps);
            return new AIToolResult(result, new HashMap<>());
            
        } catch (Exception e) {
            AILogger.e(TAG, "获取应用列表失败: " + e.getMessage());
            return new AIToolResult("获取应用列表失败: " + e.getMessage(), new HashMap<>());
        }
    }
    
    private AIToolResult checkAppInstalled(Map<String, Object> parameters) {
        String appName = (String) parameters.get("app");
        String packageName = (String) parameters.get("package");
        
        if (appName == null && packageName == null) {
            return new AIToolResult("缺少参数: app或package", parameters);
        }
        
        // 特殊处理：浏览器动态检测
        if (packageName == null && appName != null) {
            String lower = appName.toLowerCase();
            if ("浏览器".equals(appName) || "browser".equals(lower) || "chrome".equals(lower)) {
                return checkBrowserInstalled(appName);
            }
            // 硬编码映射
            packageName = APP_PACKAGE_MAP.get(lower);
        }
        
        // 如果硬编码映射找到了，直接检查
        if (packageName != null) {
            try {
                context.getPackageManager().getApplicationInfo(packageName, 0);
                Map<String, Object> result = new HashMap<>();
                result.put("status", "success");
                result.put("installed", true);
                result.put("app", appName);
                result.put("package", packageName);
                return new AIToolResult(result, parameters);
            } catch (PackageManager.NameNotFoundException e) {
                // 包名存在但没安装
            }
        }
        
        // 模糊匹配已安装应用
        if (appName != null) {
            String matchedPkg = findAppByFuzzyName(appName);
            if (matchedPkg != null) {
                Map<String, Object> result = new HashMap<>();
                result.put("status", "success");
                result.put("installed", true);
                result.put("app", appName);
                result.put("package", matchedPkg);
                result.put("match_type", "fuzzy");
                return new AIToolResult(result, parameters);
            }
        }
        
        // 未找到
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("installed", false);
        result.put("app", appName);
        result.put("package", packageName);
        return new AIToolResult(result, parameters);
    }

    /**
     * 动态检测已安装的浏览器应用（不依赖硬编码包名）
     */
    private AIToolResult checkBrowserInstalled(String appName) {
        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com"));
            List<ResolveInfo> browsers = context.getPackageManager().queryIntentActivities(browserIntent, 0);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("installed", !browsers.isEmpty());
            result.put("app", appName);
            
            if (!browsers.isEmpty()) {
                List<String> browserNames = new ArrayList<>();
                for (ResolveInfo ri : browsers) {
                    String name = ri.loadLabel(context.getPackageManager()).toString();
                    String pkg = ri.activityInfo.packageName;
                    browserNames.add(name + " (" + pkg + ")");
                }
                result.put("browsers", browserNames);
                result.put("count", browsers.size());
                result.put("message", "已安装 " + browsers.size() + " 个浏览器");
            } else {
                result.put("message", "未检测到浏览器应用");
            }
            return new AIToolResult(result, null);
        } catch (Exception e) {
            return new AIToolResult("检测浏览器失败: " + e.getMessage(), null);
        }
    }

    /**
     * 查找已安装的浏览器包名
     */
    private String findBrowserPackage() {
        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com"));
            List<ResolveInfo> browsers = context.getPackageManager().queryIntentActivities(browserIntent, 0);
            if (!browsers.isEmpty()) {
                // 优先返回第一个（通常是默认浏览器）
                return browsers.get(0).activityInfo.packageName;
            }
        } catch (Exception ignored) {}
        return null;
    }
    
    // ==================== 新增：应用控制、Shell命令、系统设置 ====================
    
    /**
     * 应用控制：强制停止、清除数据、获取详细信息
     */
    private AIToolResult appControl(Map<String, Object> parameters) {
        String appName = (String) parameters.get("app");
        String packageName = (String) parameters.get("package");
        String controlAction = (String) parameters.get("control_action");
        
        if (controlAction == null || controlAction.isEmpty()) {
            return new AIToolResult("缺少参数: control_action (force_stop/clear_data/detailed_info)", parameters);
        }
        if (appName == null && packageName == null) {
            return new AIToolResult("缺少参数: app或package", parameters);
        }
        
        // 解析包名
        if (packageName == null) {
            packageName = APP_PACKAGE_MAP.get(appName.toLowerCase());
            if (packageName == null) {
                packageName = findAppByFuzzyName(appName);
            }
            if (packageName == null && appName != null && appName.contains(".")) {
                packageName = appName;
            }
        }
        if (packageName == null) {
            return new AIToolResult("未找到应用: " + appName, parameters);
        }
        
        PackageManager pm = context.getPackageManager();
        
        try {
            switch (controlAction) {
                case "force_stop": {
                    // 使用 ActivityManager 强制停止应用
                    ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                    am.killBackgroundProcesses(packageName);
                    
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", "success");
                    result.put("message", "已强制停止应用: " + packageName);
                    result.put("package", packageName);
                    return new AIToolResult(result, parameters);
                }
                case "clear_data": {
                    // 使用 pm clear 命令清除应用数据
                    String output = executeShell("pm clear " + packageName);
                    Map<String, Object> result = new HashMap<>();
                    if (output != null && output.contains("Success")) {
                        result.put("status", "success");
                        result.put("message", "已清除应用数据: " + packageName);
                    } else {
                        result.put("status", "failed");
                        result.put("message", "清除应用数据失败: " + (output != null ? output : "未知错误"));
                    }
                    result.put("package", packageName);
                    return new AIToolResult(result, parameters);
                }
                case "detailed_info": {
                    PackageInfo pkgInfo = pm.getPackageInfo(packageName, 0);
                    ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
                    
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", "success");
                    result.put("package", packageName);
                    result.put("name", pm.getApplicationLabel(appInfo).toString());
                    result.put("version_name", pkgInfo.versionName != null ? pkgInfo.versionName : "unknown");
                    @SuppressWarnings("deprecation")
                    long vCode = pkgInfo.versionCode;
                    result.put("version_code", String.valueOf(vCode));
                    result.put("target_sdk", String.valueOf(appInfo.targetSdkVersion));
                    result.put("source_dir", appInfo.sourceDir);
                    result.put("data_dir", appInfo.dataDir);
                    result.put("is_system", (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                    result.put("enabled", appInfo.enabled);
                    
                    // 获取安装时间
                    result.put("install_time", String.valueOf(pkgInfo.firstInstallTime));
                    result.put("update_time", String.valueOf(pkgInfo.lastUpdateTime));
                    
                    return new AIToolResult(result, parameters);
                }
                default:
                    return new AIToolResult("未知控制操作: " + controlAction + "，支持: force_stop/clear_data/detailed_info", parameters);
            }
        } catch (PackageManager.NameNotFoundException e) {
            return new AIToolResult("应用未安装: " + packageName, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "应用控制失败: " + e.getMessage());
            return new AIToolResult("应用控制失败: " + e.getMessage(), parameters);
        }
    }
    
    /**
     * 执行 Shell 命令
     */
    private AIToolResult executeShellCommand(Map<String, Object> parameters) {
        String command = (String) parameters.get("command");
        
        if (command == null || command.isEmpty()) {
            return new AIToolResult("缺少参数: command", parameters);
        }
        
        // ===== 护栏已全部移除 =====
        // 这是用户自己的设备，shell_command 不再做任何命令/路径拦截：
        // 变量替换、$( )、反引号、rm/dd/chmod/kill、/proc、/data/data、设备写入等一律放行，
        // 命令原样交给 /system/bin/sh 执行。仅保留两项与安全无关的工程限制：
        //   · 超时 SHELL_TIMEOUT_SECONDS 秒（到点强杀并返回已产生的输出）
        //   · 输出上限 MAX_SHELL_OUTPUT_CHARS 字符（防 cat 大文件把结果撑爆）
        
        // readonly 模式才走拦截（默认 full：用户自己的设备，不拦截）
        if (isGuardEnabled()) {
            String lower = command.toLowerCase().trim();
            String[] parts = lower.split("[;&|$()\\s]+");
            for (String bannedPath : BANNED_PATHS) {
                if (lower.contains(bannedPath)) {
                    return new AIToolResult("[readonly] 不允许访问敏感路径: " + bannedPath, parameters);
                }
                // 带尾斜杠的黑名单条目（/proc/、/sys/）也要拦住 "ls /proc" 这种无斜杠写法
                if (bannedPath.endsWith("/")) {
                    String bare = bannedPath.substring(0, bannedPath.length() - 1);
                    for (String part : parts) {
                        if (part.equals(bare) || part.startsWith(bare + "/")) {
                            return new AIToolResult("[readonly] 不允许访问敏感路径: " + bannedPath, parameters);
                        }
                    }
                }
            }
            for (String part : parts) {
                String p = part.trim();
                if (p.isEmpty()) {
                    continue;
                }
                for (String banned : BANNED_COMMANDS) {
                    if (p.equals(banned) || p.startsWith(banned + " ")) {
                        return new AIToolResult("[readonly] 不允许执行危险命令: " + command, parameters);
                    }
                }
            }
            for (int i = 0; i < parts.length - 1; i++) {
                if (!"sleep".equals(parts[i])) {
                    continue;
                }
                try {
                    if (Double.parseDouble(parts[i + 1]) >= SHELL_TIMEOUT_SECONDS) {
                        return new AIToolResult("[readonly] sleep 超过 " + SHELL_TIMEOUT_SECONDS + " 秒必然超时，已拒绝", parameters);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        try {
            String output = executeShell(command);
            boolean timedOut = output != null && output.startsWith("(命令执行超时");
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", timedOut ? "timeout" : "success");
            result.put("command", command);
            result.put("output", output != null ? output : "(无输出)");
            String binDir = ensureBusyboxBinDir();
            if (binDir != null) {
                result.put("linux_toolkit", "内置 busybox 在 PATH 末尾，另有 " + binDir + "（环境变量 BUSYBOX_BIN_DIR）："
                        + "ash/awk/vi/nc/tar/gzip 等可直接用；wget/curl 已换成包装脚本，"
                        + "https 由 App 内下载服务(127.0.0.1)代理，可直接 wget -O 文件 URL");
            }
            if (timedOut) {
                result.put("hint", "命令未在 " + SHELL_TIMEOUT_SECONDS + " 秒内结束，已被强制终止。"
                        + "请改用能自行结束的写法重试：如加 timeout 5 前缀、限制输出条数（head -n 20）、"
                        + "或改用 dumpsys/pm 等一次性查询命令；不要在 shell 里写 sleep/长轮询。");
            }
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "执行命令失败: " + e.getMessage());
            return new AIToolResult("执行命令失败: " + e.getMessage(), parameters);
        }
    }
    
    /** 供 linux_shell 等其它工具复用：在完整内置环境下执行命令 */
    public static AIToolResult runShellStatic(Context ctx, Map<String, Object> parameters) {
        return new SystemResourceTool(ctx).executeShellCommand(parameters);
    }

    // ===== 命令路由配置的读写（整命令行 linux_shell(action=route) 与设置页 UI 共用） =====

    /** 路由配置文件：<files>/bin/.route（每行 "命令=顺序"，顺序字符 b/s/k/t） */
    public static File routeFile(Context ctx) {
        return new File(new File(ctx.getFilesDir(), "bin"), ".route");
    }

    /** 读出全部路由配置（有序） */
    public static java.util.LinkedHashMap<String, String> readRoutes(Context ctx) {
        java.util.LinkedHashMap<String, String> routes = new java.util.LinkedHashMap<>();
        try {
            File f = routeFile(ctx);
            if (f.exists()) {
                for (String line : new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8").split("\n")) {
                    String s = line.trim();
                    int eq = s.indexOf('=');
                    if (eq > 0) {
                        routes.put(s.substring(0, eq).trim(), s.substring(eq + 1).trim());
                    }
                }
            }
        } catch (Exception e) {
            AILogger.e(TAG, "读路由配置失败: " + BaseAITool.errText(e));
        }
        return routes;
    }

    /** 写入全部路由配置（空则删除文件） */
    public static void writeRoutes(Context ctx, java.util.Map<String, String> routes) {
        try {
            File f = routeFile(ctx);
            if (routes == null || routes.isEmpty()) {
                if (f.exists() && !f.delete()) {
                    AILogger.e(TAG, "删除路由配置失败: " + f);
                }
                return;
            }
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                AILogger.e(TAG, "创建 bin 目录失败: " + dir);
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : routes.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            java.nio.file.Files.write(f.toPath(), sb.toString().getBytes("UTF-8"));
        } catch (Exception e) {
            AILogger.e(TAG, "写路由配置失败: " + BaseAITool.errText(e));
        }
    }

    /** 单个命令改路由：order 为 null/空/reset 表示恢复默认 */
    public static boolean setRoute(Context ctx, String name, String order) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        String n = name.trim();
        java.util.LinkedHashMap<String, String> routes = readRoutes(ctx);
        boolean changed;
        if (order == null || order.trim().isEmpty() || "reset".equalsIgnoreCase(order.trim())
                || "default".equalsIgnoreCase(order.trim())) {
            changed = routes.remove(n) != null;
        } else {
            String cleaned = order.trim().toLowerCase().replaceAll("[^bskt]", "");
            if (cleaned.isEmpty()) {
                return false;
            }
            changed = !cleaned.equals(routes.put(n, cleaned));
        }
        writeRoutes(ctx, routes);
        return changed;
    }

    /** 可路由的命令名：优先读工具包清单，取不到就列 bin 目录下的可执行项 */
    public static java.util.List<String> routableToolNames(Context ctx) {
        java.util.List<String> names = new java.util.ArrayList<>();
        try {
            File manifest = new File(ctx.getApplicationInfo().nativeLibraryDir, TOOLKIT_MANIFEST_LIB);
            if (manifest.exists()) {
                org.json.JSONObject obj = new org.json.JSONObject(
                        new String(java.nio.file.Files.readAllBytes(manifest.toPath()), "UTF-8"));
                org.json.JSONArray arr = obj.optJSONArray("tools");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        names.add(arr.getJSONObject(i).getString("name"));
                    }
                }
            }
        } catch (Exception e) {
            AILogger.e(TAG, "读工具清单失败: " + BaseAITool.errText(e));
        }
        for (String extra : new String[]{"busybox", "openssl", "ssh", "scp", "sftp", "ssh-keygen", "gawk"}) {
            if (!names.contains(extra)) {
                names.add(extra);
            }
        }
        java.util.Collections.sort(names);
        return names;
    }
    /**
     * 供 linux_shell(action=route) 用：查询/修改命令路由顺序。
     * 写在 bin/.route（每行 "命令=顺序"），路由器每次执行都会读它。
     * 顺序字符：b=内置 s=系统 k=busybox t=toybox，例如 bskt / stkb / b / bst。
     */
    public static AIToolResult routeStatic(Context ctx, Map<String, Object> parameters) {
        Map<String, Object> result = new HashMap<>();
        File binDir = new File(ctx.getFilesDir(), "bin");
        File routeFile = new File(binDir, ".route");
        Object rawName = parameters.get("command");
        Object rawOrder = parameters.get("order");
        String name = rawName instanceof String ? ((String) rawName).trim() : null;
        String order = rawOrder instanceof String ? ((String) rawOrder).trim().toLowerCase() : null;
        try {
            java.util.LinkedHashMap<String, String> routes = new java.util.LinkedHashMap<>();
            if (routeFile.exists()) {
                String all = new String(java.nio.file.Files.readAllBytes(routeFile.toPath()), "UTF-8");
                for (String line : all.split("\n")) {
                    String s = line.trim();
                    int eq = s.indexOf('=');
                    if (eq > 0) {
                        routes.put(s.substring(0, eq).trim(), s.substring(eq + 1).trim());
                    }
                }
            }
            boolean changed = false;
            if (name != null && !name.isEmpty() && !"query".equals(name)) {
                if (order == null || order.isEmpty() || "reset".equals(order) || "default".equals(order)) {
                    changed = routes.remove(name) != null;
                } else {
                    String cleaned = order.replaceAll("[^bskt]", "");
                    if (cleaned.isEmpty()) {
                        result.put("status", "error");
                        result.put("error", "order 只能由 b(内置)/s(系统)/k(busybox)/t(toybox) 组成，例如 bskt、stkb");
                        return new AIToolResult(result, parameters);
                    }
                    routes.put(name, cleaned);
                    changed = true;
                }
                if (!binDir.isDirectory() && !binDir.mkdirs()) {
                    result.put("status", "error");
                    result.put("error", "bin 目录不存在，请先执行一次 linux_shell(action=tools)");
                    return new AIToolResult(result, parameters);
                }
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, String> e : routes.entrySet()) {
                    sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
                }
                java.nio.file.Files.write(routeFile.toPath(), sb.toString().getBytes("UTF-8"));
            }
            result.put("status", "success");
            result.put("default_order", "bskt（内置 -> 系统 -> busybox -> toybox）");
            result.put("routes", routes.isEmpty() ? "(全部使用默认顺序)" : routes.toString());
            result.put("changed", changed);
            result.put("hint", "改顺序：linux_shell(action=route, command=curl, order=stkb)；恢复：order=reset");
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("error", BaseAITool.errText(e));
            return new AIToolResult(result, parameters);
        }
    }
    /** 供 linux_shell 复用：下载 URL 到文件 */
    public static AIToolResult httpDownloadStatic(Context ctx, Map<String, Object> parameters) {
        return new SystemResourceTool(ctx).httpDownload(parameters);
    }

    /** 列出内置工具及版本（linux_shell action=tools） */
    public static AIToolResult toolkitStatus(Context ctx, Map<String, Object> parameters) {
        SystemResourceTool t = new SystemResourceTool(ctx);
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        String binDir = t.ensureBusyboxBinDir();
        result.put("bin_dir", binDir != null ? binDir : "(未就绪)");
        // 从工具包清单读名字（加工具后自动出现，不用改代码），busybox 永远放第一个
        java.util.List<String> toolNames = new java.util.ArrayList<>();
        toolNames.add("busybox");
        for (String n : routableToolNames(ctx)) {
            if (!"busybox".equals(n)) {
                toolNames.add(n);
            }
        }
        if (toolNames.size() > 40) {
            toolNames = new java.util.ArrayList<>(toolNames.subList(0, 40));
        }
        String[] names = toolNames.toArray(new String[0]);
        // 注意：逐个跑 --version 最坏情况会超过框架的 30 秒工具超时，
        // 所以单个限 3 秒、整体 12 秒预算，超了就返回已探到的结果。
        StringBuilder sb = new StringBuilder();
        long startAt = System.currentTimeMillis();
        int done = 0;
        for (String n : names) {
            if (System.currentTimeMillis() - startAt > 12000) {
                sb.append("...(剩余 ").append(names.length - done).append(" 个工具跳过：列举超时)").append('\n');
                break;
            }
            String out = t.shellCapture(n + " --version 2>&1 | head -1", 3);
            sb.append(n).append(": ").append(out == null || out.isEmpty() ? "(无)" : out).append('\n');
            done++;
        }
        result.put("tools", sb.toString().trim());
        return new AIToolResult(result, parameters);
    }

    /** 同步跑一条命令并取输出（内部用） */
    private String shellCapture(String command, long timeoutSec) {
        Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start();
            p.getOutputStream().close();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
                if (sb.length() > 200) {
                    break;
                }
            }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            return sb.toString().trim();
        } catch (Exception e) {
            if (p != null) {
                p.destroyForcibly();
            }
            return "";
        }
    }

    /** 按 libtoolkit_manifest.so 清单解包依赖库并把工具软链接进 PATH */
    private void ensureGenericTools(File binDir, boolean rebuild) {
        try {
            File manifest = new File(context.getApplicationInfo().nativeLibraryDir, TOOLKIT_MANIFEST_LIB);
            if (!manifest.exists()) {
                return;
            }
            String json = new String(java.nio.file.Files.readAllBytes(manifest.toPath()), "UTF-8");
            org.json.JSONObject obj = new org.json.JSONObject(json);
            String bundle = obj.optString("libs_bundle", "libtoolkit_libs.so");
            File libDir = new File(context.getFilesDir(), obj.optString("extract_dir", "toolkit_lib"));
            if (rebuild || !libDir.isDirectory() || libDir.list() == null || libDir.list().length == 0) {
                extractBundle(binDir, libDir, bundle);
            }
            org.json.JSONArray arr = obj.optJSONArray("tools");
            if (arr == null) {
                return;
            }
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject t = arr.getJSONObject(i);
                // 走启动器而不是直接指向 lib<name>_bin.so：启动器会设置 LD_LIBRARY_PATH
                linkBin(binDir, t.getString("name"), LAUNCHER_LIB);
            }
            AILogger.i(TAG, "通用工具包就绪: " + arr.length() + " 个工具, 库目录=" + libDir);
        } catch (Exception e) {
            AILogger.e(TAG, "加载通用工具包失败: " + BaseAITool.errText(e));
        }
    }

    /** 通用解包：把 bundle（tar.gz）解到 dir */
    private void extractBundle(File binDir, File dir, String bundleName) {
        try {
            File bundle = new File(context.getApplicationInfo().nativeLibraryDir, bundleName);
            if (!bundle.exists()) {
                AILogger.e(TAG, "缺少依赖库包: " + bundle.getAbsolutePath());
                return;
            }
            if (dir.exists()) {
                deleteRecursively(dir);
            }
            if (!dir.mkdirs() && !dir.isDirectory()) {
                return;
            }
            ProcessBuilder pb = new ProcessBuilder(new File(binDir, "busybox").getAbsolutePath(),
                    "tar", "-xzf", bundle.getAbsolutePath(), "-C", dir.getAbsolutePath());
            pb.redirectErrorStream(true);
            pb.environment().put("LD_LIBRARY_PATH", binDir.getAbsolutePath());
            Process proc = pb.start();
            proc.getOutputStream().close();
            if (!proc.waitFor(40, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                AILogger.e(TAG, "解包依赖库超时: " + bundleName);
                return;
            }
            int n = dir.list() == null ? 0 : dir.list().length;
            AILogger.i(TAG, "解包 " + bundleName + " 完成 exit=" + proc.exitValue() + " 文件数=" + n);
        } catch (Exception e) {
            AILogger.e(TAG, "解包失败 " + bundleName + ": " + BaseAITool.errText(e));
        }
    }

    /** 递归删除（重建 bin 目录用） */
    private void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        if (!f.delete()) {
            AILogger.e(TAG, "删除失败: " + f.getAbsolutePath());
        }
    }

    /** 供 linux_shell 等其它工具复用：在完整内置环境下执行命令 */




    private void installFetchShims(File binDir) {
        try {
            int port = HttpFetchServer.start(context);
            if (port <= 0) {
                AILogger.e(TAG, "本地下载服务未启动，wget/curl 的 https 不可用");
                return;
            }
            // 注意：shim 必须是 nativeLibraryDir 里的文件（随 APK 解压，可 exec）。
            // 之前把脚本写进 bin（App 数据目录）会报 Permission denied —— Android 禁止 exec 数据目录里的文件。
            // 只保留 wget 的 https 包装（内置 bushybox wget 未编译 TLS）；curl 用内置的（自带 TLS）
            linkShim(binDir, "wget", "libwget_shim.so");
        } catch (Exception e) {
            AILogger.e(TAG, "安装 wget/curl 包装脚本失败: " + BaseAITool.errText(e));
        }
    }

    /** 用内置 busybox 的 tar 解包 ssh 依赖库到 files/lib（保留真实文件名，含带版本号的 SONAME） */
    private void extractSshLibs(File binDir, File libDir) {
        Process proc = null;
        try {
            File bundle = new File(context.getApplicationInfo().nativeLibraryDir, SSH_LIBS_BUNDLE);
            if (!bundle.exists()) {
                AILogger.e(TAG, "缺少 ssh 依赖库包: " + bundle.getAbsolutePath());
                return;
            }
            if (libDir.exists()) {
                deleteRecursively(libDir);
            }
            if (!libDir.mkdirs() && !libDir.isDirectory()) {
                AILogger.e(TAG, "无法创建库目录: " + libDir);
                return;
            }
            ProcessBuilder pb = new ProcessBuilder(new File(binDir, "busybox").getAbsolutePath(),
                    "tar", "-xzf", bundle.getAbsolutePath(), "-C", libDir.getAbsolutePath());
            pb.redirectErrorStream(true);
            pb.environment().put("LD_LIBRARY_PATH", binDir.getAbsolutePath());
            proc = pb.start();
            proc.getOutputStream().close();
            if (!proc.waitFor(30, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                AILogger.e(TAG, "解包 ssh 依赖库超时");
                return;
            }
            int n = libDir.list() == null ? 0 : libDir.list().length;
            AILogger.i(TAG, "ssh 依赖库解包完成 exit=" + proc.exitValue() + " 文件数=" + n);
        } catch (Exception e) {
            AILogger.e(TAG, "解包 ssh 依赖库失败: " + BaseAITool.errText(e));
        }
    }

    /** 按 libtoolkit_manifest.so 清单解包依赖库并把工具软链接进 PATH */


    /** 把 busybox --list 的结果写到 bin/.busybox_applets，供路由器判断某命令是否是 busybox applet */
    private void writeBusyboxAppletList(File binDir) {
        try {
            File busybox = new File(binDir, "busybox");
            if (!busybox.exists()) {
                return;
            }
            Process p = new ProcessBuilder(busybox.getAbsolutePath(), "--list")
                    .redirectErrorStream(true).start();
            p.getOutputStream().close();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
            java.nio.file.Files.write(new File(binDir, ".busybox_applets").toPath(),
                    sb.toString().getBytes("UTF-8"));
        } catch (Exception e) {
            AILogger.e(TAG, "写 busybox applet 清单失败: " + BaseAITool.errText(e));
        }
    }

    /** 把 busybox applet 软链接改成指向 liblauncher.so（路由器）：内置不可用时回退系统/toybox */
    private void rerouteApplets(File binDir) {
        try {
            File list = new File(binDir, ".busybox_applets");
            File router = new File(context.getApplicationInfo().nativeLibraryDir, LAUNCHER_LIB);
            if (!list.exists() || !router.exists()) {
                return;
            }
            String all = new String(java.nio.file.Files.readAllBytes(list.toPath()), "UTF-8");
            int n = 0;
            for (String raw : all.split("\n")) {
                String name = raw.trim();
                if (name.isEmpty() || "busybox".equals(name)) {
                    continue;
                }
                File link = new File(binDir, name);
                if (link.exists() && !link.delete()) {
                    continue;
                }
                java.nio.file.Files.createSymbolicLink(link.toPath(), router.toPath());
                n++;
            }
            AILogger.i(TAG, "applet 已改走路由器: " + n + " 个");
        } catch (Exception e) {
            AILogger.e(TAG, "改路由失败: " + BaseAITool.errText(e));
        }
    }
    private void linkBin(File binDir, String name, String libName) {
        linkShim(binDir, name, libName);
    }

    /** 把 bin/<name> 指向 nativeLibraryDir 里的 shim（先删掉 busybox --install 建的 applet 软链接） */
    private void linkShim(File binDir, String name, String shimLibName) {
        try {
            File target = new File(context.getApplicationInfo().nativeLibraryDir, shimLibName);
            if (!target.exists()) {
                AILogger.e(TAG, "缺少 " + shimLibName + "（wget/curl 的 https 不可用）: " + target.getAbsolutePath());
                return;
            }
            File link = new File(binDir, name);
            if (link.exists() && !link.delete()) {
                AILogger.e(TAG, "删除旧 wget/curl 软链接失败: " + link);
            }
            java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath());
        } catch (Exception e) {
            AILogger.e(TAG, "创建 " + name + " 软链接失败: " + BaseAITool.errText(e));
        }
    }

    // ===== shell 拦截模式开关（默认 full：不拦；readonly 恢复旧的命令/路径拦截）=====
    // ===== shell 拦截模式开关（默认 full：不拦；readonly 恢复旧的命令/路径拦截）=====
    private static final String PREF_NAME = "system_resource_prefs";
    private static final String PREF_GUARD = "shell_guard_enabled";

    private boolean isGuardEnabled() {
        try {
            return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getBoolean(PREF_GUARD, false);
        } catch (Exception e) {
            return false;
        }
    }

    private void setGuardEnabled(boolean on) {
        try {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREF_GUARD, on).apply();
        } catch (Exception ignored) {
        }
    }

    /** shell_mode：切换/查询 shell 拦截模式（默认 full 不拦；readonly 恢复拦截） */
    private AIToolResult shellMode(Map<String, Object> parameters) {
        String mode = (String) parameters.get("mode");
        boolean changed = false;
        if (mode != null) {
            String m = mode.trim().toLowerCase();
            if ("readonly".equals(m) || "guard".equals(m) || "on".equals(m)) {
                setGuardEnabled(true);
                changed = true;
            } else if ("full".equals(m) || "off".equals(m) || "open".equals(m)) {
                setGuardEnabled(false);
                changed = true;
            }
        }
        boolean on = isGuardEnabled();
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("shell_guard", on ? "readonly" : "full");
        result.put("description", on ? "readonly：拦截危险命令(rm/dd/chmod/kill/mount/su…)与敏感路径(/data/data、/proc、/sys)，sleep 超限直接拒" : "full：不拦截任何命令（默认，用户自己的设备）");
        result.put("changed", changed);
        return new AIToolResult(result, parameters);
    }
    private AIToolResult httpDownload(Map<String, Object> parameters) {
        String url = (String) parameters.get("url");
        if (url == null || url.trim().isEmpty()) {
            return new AIToolResult("缺少参数: url", parameters);
        }
        url = url.trim();
        String path = (String) parameters.get("path");
        File dest;
        File wsFiles = new File(context.getFilesDir(), "agent_workspace/files");
        if (path != null && !path.trim().isEmpty()) {
            String pt = path.trim();
            dest = pt.startsWith("/") ? new File(pt) : new File(wsFiles, pt);
        } else {
            String name = url.substring(url.lastIndexOf('/') + 1);
            if (name.isEmpty() || name.contains("?") || name.length() > 80) {
                name = "download_" + System.currentTimeMillis();
            }
            dest = new File(wsFiles, name);
        }
        long bytes = HttpFetchServer.downloadToFile(url, dest);
        Map<String, Object> result = new HashMap<>();
        result.put("url", url);
        if (bytes < 0) {
            result.put("status", "error");
            result.put("error", "下载失败（网络不可达 / 证书校验失败 / HTTP 非 2xx）");
            return new AIToolResult(result, parameters);
        }
        result.put("status", "success");
        result.put("file", dest.getAbsolutePath());
        result.put("bytes", bytes);
        return new AIToolResult(result, parameters);
    }

    /** 在 bin 目录为 nativeLibraryDir 里的库建软链接（供 busybox 的 LD_LIBRARY_PATH 查找） */
    private void linkLib(File binDir, String libName) {
        try {
            File target = new File(context.getApplicationInfo().nativeLibraryDir, libName);
            if (!target.exists()) {
                AILogger.e(TAG, "缺少内置原生库: " + target.getAbsolutePath());
                return;
            }
            File link = new File(binDir, libName);
            if (!link.exists()) {
                java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath());
            }
        } catch (Exception e) {
            AILogger.e(TAG, "创建库软链接失败 " + libName + ": " + BaseAITool.errText(e));
        }
    }

    /**
     * 预热内置 Linux 工具箱（App 启动时后台调用）。
     *
     * <p>为什么要预热：bin 目录是懒创建的，而 Python 引擎只在启动时读一次 PATH/LD_LIBRARY_PATH；
     * 不预热的话，从没调过 shell_command 的会话里 python_execute 的 subprocess 用不到 busybox。
     */
    public static void prepareToolkit(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            new SystemResourceTool(ctx).ensureBusyboxBinDir();
        } catch (Exception e) {
            AILogger.e(TAG, "预热内置工具箱失败: " + e.getMessage());
        }
    }

    /** 内置 busybox 启动器的真实路径（系统解压出来的 nativeLibraryDir，可 execve） */
    private String busyboxPath() {
        try {
            return new File(context.getApplicationInfo().nativeLibraryDir, BUSYBOX_LAUNCHER_LIB_NAME).getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 确保内置 Linux 命令工具链可用：用 {@code busybox --install -s} 在 filesDir/bin 下为全部
     * applet 建软链接（sed/awk/grep/find/tar/wget/vi…），只做一次并缓存。
     *
     * @return 软链接目录的绝对路径；不可用时返回 null（shell_command 退回系统 PATH）
     */
    private String ensureBusyboxBinDir() {
        String cached = sBusyboxBinDir;
        if (cached != null) {
            return cached;
        }
        synchronized (BUSYBOX_LOCK) {
            if (sBusyboxBinDir != null) {
                return sBusyboxBinDir;
            }
            try {
                String boxPath = busyboxPath();
                if (boxPath == null) {
                    return null;
                }
                File box = new File(boxPath);
                if (!box.exists()) {
                    AILogger.e(TAG, "内置 busybox 不存在: " + boxPath);
                    return null;
                }
                if (!box.canExecute()) {
                    // 某些 ROM 首次安装后权限位未生效，补一次
                    box.setExecutable(true, false);
                }
                if (!box.canExecute()) {
                    AILogger.e(TAG, "内置 busybox 不可执行（nativeLibraryDir 未解压？）: " + boxPath);
                    return null;
                }
                File binDir = new File(context.getFilesDir(), "bin");
                if (!binDir.exists() && !binDir.mkdirs()) {
                    return null;
                }
                // 重装 App 后 nativeLibraryDir 路径里的哈希会变（/data/app/~~xxx==/com.oilquiz.app-yyy==/lib/arm64），
                // 旧 applet 软链接会全部悬空（实测：所有 applet 报 "inaccessible or not found"）。
                // 所以用 .target 记录"当时链接指向谁"，路径一变就整目录重建。
                File targetFile = new File(binDir, ".target");
                String recordedTarget = null;
                if (targetFile.exists()) {
                    try {
                        recordedTarget = new String(java.nio.file.Files.readAllBytes(targetFile.toPath()), "UTF-8").trim();
                    } catch (Exception ignored) {
                    }
                }
                boolean rebuild = !boxPath.equals(recordedTarget);
                if (rebuild) {
                    // 重建前先保住用户的路由配置（bin/.route），否则每次重装都会被清掉
                    String savedRoute = null;
                    try {
                        File rf = new File(binDir, ".route");
                        if (rf.exists()) {
                            savedRoute = new String(java.nio.file.Files.readAllBytes(rf.toPath()), "UTF-8");
                        }
                    } catch (Exception ignored) {
                    }
                    deleteRecursively(binDir);
                    if (!binDir.mkdirs()) {
                        AILogger.e(TAG, "无法创建 busybox 软链接目录: " + binDir);
                        return null;
                    }
                    if (savedRoute != null && !savedRoute.isEmpty()) {
                        try {
                            java.nio.file.Files.write(new File(binDir, ".route").toPath(), savedRoute.getBytes("UTF-8"));
                        } catch (Exception ignored) {
                        }
                    }
                }
                // busybox 用 argv[0] 判断自己要扮演哪个 applet：文件叫 libbusybox.so 时直接调用会
                // 报 "applet not found"。所以先在 bin 目录建一个名为 busybox 的软链接（软链接指向
                // nativeLibraryDir 里的真实文件，SELinux 检查的是最终目标，exec 合法），再用它调 --install。
                File boxLink = new File(binDir, "busybox");
                if (!boxLink.exists()) {
                    try {
                        java.nio.file.Files.createSymbolicLink(boxLink.toPath(), box.toPath());
                    } catch (Exception e) {
                        AILogger.e(TAG, "创建 busybox 软链接失败: " + BaseAITool.errText(e));
                    }
                }
                String invokePath = boxLink.exists() ? boxLink.getAbsolutePath() : boxPath;
                // 库软链接必须先建好：Termux 的启动器是按 DT_NEEDED 加载 libbusybox.so 的，
                // 而 LD_LIBRARY_PATH 只指向本目录（不暴露整个 nativeLibraryDir —— 那里还有
                // libsqlite3.so / libssl3.so 等同名库，指过去会让系统命令加载到不兼容版本）
                linkLib(binDir, BUSYBOX_CORE_LIB_NAME);
                linkLib(binDir, BUSYBOX_SELINUX_LIB_NAME);
                linkLib(binDir, BUSYBOX_PCRE2_LIB_NAME);
                // 额外工具：openssl（带 TLS）及其运行时库
                for (String lib : EXTRA_LIB_NAMES) {
                    linkLib(binDir, lib);
                }
                linkBin(binDir, "openssl", LAUNCHER_LIB);
                // openssh：依赖库解包到 files/lib（dlopen 允许），可执行文件软链接到 nativeLibraryDir
                File libDir = new File(context.getFilesDir(), "lib");
                if (rebuild || !libDir.isDirectory() || (libDir.list() != null && libDir.list().length == 0)) {
                    extractSshLibs(binDir, libDir);
                }
                // 全部走 C 启动器（它只给自己的进程设置依赖库路径，避免污染系统二进制）
                for (String n : SSH_BIN_NAMES) {
                    linkBin(binDir, n, LAUNCHER_LIB);
                }
                if (rebuild) {
                    ProcessBuilder installPb = new ProcessBuilder(invokePath, "--install", "-s", binDir.getAbsolutePath());
                    installPb.redirectErrorStream(true);
                    // 关键：不设 LD_LIBRARY_PATH 的话启动器加载不到 libbusybox.so，
                    // --install 直接失败 → 一个 applet 软链接都建不出来（已实测）
                    installPb.environment().put("LD_LIBRARY_PATH", binDir.getAbsolutePath());
                    Process proc = installPb.start();
                    try {
                        proc.getOutputStream().close();
                    } catch (Exception ignored) {
                    }
                    final StringBuilder log = new StringBuilder();
                    Thread reader = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                BufferedReader r = new BufferedReader(
                                        new InputStreamReader(proc.getInputStream(), "UTF-8"));
                                String line;
                                while ((line = r.readLine()) != null) {
                                    log.append(line).append('\n');
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }, "busybox-install-reader");
                    reader.setDaemon(true);
                    reader.start();
                    if (!proc.waitFor(15, TimeUnit.SECONDS)) {
                        proc.destroyForcibly();
                        AILogger.e(TAG, "busybox --install 超时");
                        return null;
                    }
                    reader.join(800);
                    if (proc.exitValue() != 0) {
                        AILogger.e(TAG, "busybox --install 失败(" + proc.exitValue() + "): " + snapshot(log));
                        return null;
                    }
                    try {
                        java.nio.file.Files.write(targetFile.toPath(), boxPath.getBytes("UTF-8"));
                    } catch (Exception e) {
                        AILogger.e(TAG, "写入 .target 失败（下次会重建）: " + BaseAITool.errText(e));
                    }
                }

                // 自检：applet 软链接确实建出来了才算成功（否则 PATH 里啥也没有，等于白设）
                File probe = new File(binDir, "sed");
                if (!probe.exists()) {
                    AILogger.e(TAG, "busybox applet 软链接未生成（sed 不存在），放弃注入 PATH: " + binDir);
                    return null;
                }
                // 写 busybox applet 清单并把这些软链接改成走【路由器】：内置不可用时回退系统/toybox
                writeBusyboxAppletList(binDir);
                rerouteApplets(binDir);

                // 让 wget/curl 支持 https（busybox 没编译 TLS）：起本地下载服务 + 覆盖成包装脚本
                installFetchShims(binDir);
                // 通用工具包（bundle_termux_bins.py 生成）：解包依赖库 + 按清单建软链接
                ensureGenericTools(binDir, rebuild);

                sBusyboxBinDir = binDir.getAbsolutePath();
                AILogger.i(TAG, "内置 Linux 工具链就绪: " + sBusyboxBinDir);
                return sBusyboxBinDir;
            } catch (Exception e) {
                AILogger.e(TAG, "初始化内置 busybox 失败: " + BaseAITool.errText(e));
                return null;
            }
        }
    }

    /**
     * 执行 Shell 命令并返回输出（真正带超时，防止命令挂起永久阻塞 Agent）。
     *
     * <p>旧实现有两个致命问题：
     * <ol>
     *   <li>在主线程里 {@code while ((line = reader.readLine()) != null)} 读输出，
     *       一旦命令挂起（如 {@code sleep 30}、等待 stdin 的交互命令），readLine() 永远不返回，
     *       下面的 {@code waitFor(10s)} 根本执行不到 —— 超时形同虚设，最终只能被框架的 30s 超时杀掉，
     *       返回一个空的 "工具执行失败: null"。</li>
     *   <li>把 {@code command + " 2>&1"} 拼进 sh -c 的字符串，命令末尾若带注释或换行会破坏语义，
     *       并且多套了一层 shell 解析。</li>
     * </ol>
     * 现在改为：ProcessBuilder + redirectErrorStream（原生合并 stderr）+ 关闭子进程 stdin
     * （交互命令立即得到 EOF 而不是干等）+ 独立读取线程收割输出 + 主线程 waitFor 限时 +
     * 超时 destroyForcibly，并保留已产生的部分输出。
     */
    private String executeShell(String command) {
        Process process = null;
        Thread readerThread = null;
        final StringBuilder output = new StringBuilder();
        final boolean[] truncated = new boolean[1];
        final BufferedReader[] readerRef = new BufferedReader[1];
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", command);
            // 原生合并 stderr → stdout，避免两路管道缓冲写满导致死锁（不再拼 " 2>&1"）
            pb.redirectErrorStream(true);
            // 把内置 busybox 工具链目录放到 PATH 最前面：sed/awk/grep/find/tar/wget/vi 等直接可用
            try {
                Map<String, String> env = pb.environment();
                String binDir = ensureBusyboxBinDir();
                String basePath = "/system/bin:/system/xbin:/vendor/bin";
                String oldPath = env.get("PATH");
                if (oldPath != null && !oldPath.isEmpty()) {
                    basePath = basePath + ":" + oldPath;
                }
                // 内置目录放【PATH 最前】：所有名字都经路由器（内置 -> 系统 -> busybox -> toybox），
                // 内置实现不可用时自动回退到系统/toybox，所以不会再出现“把好用的命令劫持成会崩的版本”。
                env.put("PATH", binDir != null ? (binDir + ":" + basePath) : basePath);
                if (binDir != null) {
                    env.put("BUSYBOX_BIN_DIR", binDir);
                    env.put("BUSYBOX", busyboxPath() != null ? busyboxPath() : "");
                    // busybox 启动器靠它加载 libbusybox.so / libandroid-selinux.so（只含这两个软链接的目录）
                    // 不再向 shell 注入 LD_LIBRARY_PATH：那会让系统二进制（如 /system/bin/curl）
                    // 加载到我们的 libcrypto.so 而符号不匹配。内置工具由 liblauncher.so 自带环境。
                    // wget/curl 包装脚本靠它找本地下载服务（也写了 .http_port 文件作为兜底）
                    int fetchPort = HttpFetchServer.getPort();
                    if (fetchPort > 0) {
                        env.put("HTTP_FETCH_PORT", String.valueOf(fetchPort));
                        if (HttpFetchServer.getToken() != null) {
                            env.put("HTTP_FETCH_TOKEN", HttpFetchServer.getToken());
                        }
                    }
                    // 内置 CA 包（openssl / curl 用）
                    File ca = new File(context.getApplicationInfo().nativeLibraryDir, CA_CERT_LIB);
                    if (ca.exists()) {
                        env.put("SSL_CERT_FILE", ca.getAbsolutePath());
                        env.put("CURL_CA_BUNDLE", ca.getAbsolutePath());
                    }
                }
                // 让 wget/vi/tar 之类有可写的临时目录与 HOME
                env.put("TMPDIR", context.getCacheDir().getAbsolutePath());
                env.put("HOME", context.getFilesDir().getAbsolutePath());
                // 给 C 路由器精确的应用数据目录（多用户/工作资料下路径不同，不能靠硬编码）
                env.put("APP_FILES", context.getFilesDir().getAbsolutePath());
                env.put("TERM", "dumb");
            } catch (Exception ignored) {
            }
            process = pb.start();

            // 关闭子进程的标准输入：交互式命令（cat、su 提示等）读到 EOF 会立即退出，
            // 否则它们会一直等输入，直到把自己等成超时。
            try {
                process.getOutputStream().close();
            } catch (Exception ignored) {
            }

            final Process proc = process;
            readerThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(proc.getInputStream(), "UTF-8"));
                        readerRef[0] = reader;
                        String line;
                        while ((line = reader.readLine()) != null) {
                            synchronized (output) {
                                output.append(line).append("\n");
                                if (output.length() > MAX_SHELL_OUTPUT_CHARS) {
                                    // 输出超限（如 top、cat 大文件）：停止读并终止进程
                                    truncated[0] = true;
                                    break;
                                }
                            }
                        }
                    } catch (Exception ignored) {
                        // 进程被强杀时 readLine 抛异常，属正常路径
                    }
                }
            }, "shell-output-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            if (!process.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                // 真超时：强杀进程，等待读取线程收尾，返回已产生的部分输出
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                if (readerThread != null) {
                    readerThread.join(1000);
                }
                String partial = snapshot(output);
                StringBuilder sb = new StringBuilder();
                sb.append("(命令执行超时(").append(SHELL_TIMEOUT_SECONDS).append("s)，已终止)");
                if (!partial.isEmpty()) {
                    sb.append("\n--- 超时前已产生的输出 ---\n").append(partial);
                }
                return sb.toString();
            }

            // 进程已退出：给它 1 秒把管道里剩余内容读完，避免丢尾部输出
            if (readerThread != null) {
                readerThread.join(1000);
            }

            String out = snapshot(output);
            if (truncated[0]) {
                out = out + "\n...(输出超过 " + MAX_SHELL_OUTPUT_CHARS + " 字符已截断)";
            }
            return out.isEmpty() ? "(无输出)" : out;
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return "执行失败: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } finally {
            try {
                if (readerRef[0] != null) {
                    readerRef[0].close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** 线程安全地取输出快照 */
    private static String snapshot(StringBuilder output) {
        synchronized (output) {
            return output.toString().trim();
        }
    }

    /**
     * 在 Termux 里执行命令（完整 Linux 环境）。
     *
     * <p>为什么不能直接用 shell_command：我们的进程是 untrusted_app，既读不到
     * /data/data/com.termux（SELinux 拦截），也不能用 {@code am startservice} 调
     * Termux 的 RunCommandService —— 那样调用者是 shell(uid 2000)，不持有
     * com.termux.permission.RUN_COMMAND，系统直接回 "Not found; no service started"。
     * 只有我们 App 自己带权限 startService，Termux 才会执行并把结果回传。
     *
     * <p>两条前置条件（缺一不可）：
     * <ol>
     *   <li>本应用被授予 {@code com.termux.permission.RUN_COMMAND}（dangerous 权限）；</li>
     *   <li>Termux 侧 ~/.termux/termux.properties 里 {@code allow-external-apps=true}。</li>
     * </ol>
     * 结果通过 PendingIntent 广播回传，Bundle 键：stdout/stderr/exitCode/errmsg。
     */

    private AIToolResult termuxExec(Map<String, Object> parameters) {
        String command = (String) parameters.get("command");
        if (command == null || command.trim().isEmpty()) {
            return new AIToolResult("缺少参数: command（termux_exec 需要在 Termux 中执行的命令）", parameters);
        }
        // 容器 PATH 纠正：proot-distro login ubuntu 会继承 Termux 的 PATH，
        // 容器里裸 git/gcc/make 命中的是 Termux 二进制（真机实测）。
        // 只处理“简单容器命令”形态，复杂命令保持原样。
        command = normalizeContainerPath(command);
        parameters.put("command", command);

        // 1. Termux 是否已安装
        try {
            context.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
        } catch (Exception e) {
            return new AIToolResult("未检测到 Termux（" + TERMUX_PACKAGE + "）。termux_exec 需要先安装 Termux 应用。", parameters);
        }

        // 2. 是否已授予 RUN_COMMAND 权限
        if (context.checkSelfPermission(TERMUX_PERMISSION_RUN_COMMAND) != PackageManager.PERMISSION_GRANTED) {
            // 主动拉起授权弹窗（部分 ROM 禁用了 adb pm grant，只能 App 内请求）
            boolean prompted = false;
            try {
                Intent ask = new Intent(context, TermuxPermissionActivity.class);
                ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(ask);
                prompted = true;
            } catch (Exception e) {
                AILogger.e(TAG, "无法拉起 Termux 授权弹窗: " + BaseAITool.errText(e));
            }
            return new AIToolResult("尚未获得 Termux 运行权限 " + TERMUX_PERMISSION_RUN_COMMAND + "。"
                    + (prompted ? "已为你弹出系统授权框，请点「允许」，然后重新执行这条 termux_exec 命令。" : "")
                    + "若没有弹窗，可在系统设置的答题宝权限页里手动开启，或用 adb: pm grant "
                    + context.getPackageName() + " " + TERMUX_PERMISSION_RUN_COMMAND + "（该命令在部分 ROM 会被系统拒绝）"
                    + "；另外 Termux 侧还需要 allow-external-apps=true。", parameters);
        }

        // 全局串行锁：监听同一 action 的多个 receiver 会互相抢广播，
        // 必须等上一个调用完成、receiver 注销后才能再注册新的。
        synchronized (TERMUX_EXEC_LOCK) {
        final ArrayBlockingQueue<Intent> resultQueue = new ArrayBlockingQueue<>(1);
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                resultQueue.offer(intent);
            }
        };
        IntentFilter filter = new IntentFilter(TERMUX_RESULT_ACTION);
        boolean registered = false;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            registered = true;

            Intent replyIntent = new Intent(TERMUX_RESULT_ACTION).setPackage(context.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                flags |= PendingIntent.FLAG_MUTABLE;   // Termux 需要往里面塞结果 Bundle
            }
            PendingIntent reply = PendingIntent.getBroadcast(
                    context, TERMUX_REQUEST_CODE.incrementAndGet(), replyIntent, flags);

            Intent intent = new Intent(TERMUX_ACTION_RUN_COMMAND);
            intent.setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE);
            intent.putExtra(TERMUX_EXTRA_COMMAND_PATH, TERMUX_BASH);
            intent.putExtra(TERMUX_EXTRA_ARGUMENTS, new String[]{"-lc", command});
            intent.putExtra(TERMUX_EXTRA_WORKDIR, TERMUX_HOME);
            intent.putExtra(TERMUX_EXTRA_BACKGROUND, false);
            intent.putExtra(TERMUX_EXTRA_PENDING_INTENT, reply);

            try {
                context.startService(intent);
            } catch (SecurityException se) {
                return new AIToolResult("调用 Termux 被系统拒绝（权限未生效）: " + BaseAITool.errText(se)
                        + "。请重新授予 " + TERMUX_PERMISSION_RUN_COMMAND, parameters);
            } catch (Exception e) {
                return new AIToolResult("启动 Termux RunCommandService 失败: " + BaseAITool.errText(e), parameters);
            }

            Intent result = resultQueue.poll(TERMUX_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (result == null) {
                // 长任务（装包/下载/容器操作）超时提示更明确：不是通道坏了，是任务本身超过 20s
                boolean longTask = isLongRunningCommand(command);
                return new AIToolResult("Termux 命令超时（" + TERMUX_TIMEOUT_SECONDS + "s 未返回结果）。"
                        + (longTask
                        ? "这条命令是装包/下载类长任务（apt/pip/proot 等），20 秒内跑不完属正常。"
                        + "建议：① 在 Termux 里手动执行看完整过程；② 或让 App 用「一键准备」流程（分步骤、可续跑、有日志监控）。"
                        : "")
                        + (longTask ? "" : "常见原因：Termux 侧未开启 allow-external-apps，或命令自身长时间不结束（如进入交互、等待输入）。")
                        + (longTask ? "" : "请在 Termux 里执行：echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings"), parameters);
            }

            Bundle bundle = result.getBundleExtra(TERMUX_RESULT_BUNDLE);
            Map<String, Object> out = new HashMap<>();
            out.put("command", command);
            out.put("environment", "termux");
            if (bundle == null) {
                out.put("status", "error");
                out.put("raw", String.valueOf(result.getExtras()));
                return new AIToolResult(out, parameters);
            }

            String stdout = bundle.getString(TERMUX_RESULT_STDOUT, "");
            String stderr = bundle.getString(TERMUX_RESULT_STDERR, "");
            String errmsg = bundle.getString(TERMUX_RESULT_ERRMSG, "");
            int exitCode = bundle.getInt(TERMUX_RESULT_EXIT_CODE, -1);

            out.put("exit_code", exitCode);
            out.put("stdout", truncateOutput(stdout));
            out.put("stderr", truncateOutput(stderr));
            if (errmsg != null && !errmsg.isEmpty()) {
                out.put("status", "error");
                out.put("error", errmsg);
                out.put("hint", "若提示 allow-external-apps 相关错误，请在 Termux 里设置："
                        + "mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings");
            } else {
                out.put("status", exitCode == 0 ? "success" : "failed");
            }
            return new AIToolResult(out, parameters);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new AIToolResult("Termux 命令被中断", parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "termux_exec 失败: " + e.getMessage(), e);
            return new AIToolResult("termux_exec 失败: " + BaseAITool.errText(e), parameters);
        } finally {
            if (registered) {
                try {
                    context.unregisterReceiver(receiver);
                } catch (Exception ignored) {
                }
            }
        }
        } // synchronized (TERMUX_EXEC_LOCK) 结束
    }

    /**
     * SSH 连接远程主机执行单条命令（内置 ssh 客户端，非交互）。
     *
     * <p>认证路径：
     * <ol>
     *   <li>传了 password → 需要 sshpass（App 未内置）。检测到则 sshpass -p 执行；
     *       没有则返回引导（生成密钥对免密 / 先在 Termux 装 sshpass）；</li>
     *   <li>没传 password → 密钥认证：key_file 指定，缺省依次尝试 ~/.ssh/id_ed25519、id_rsa。</li>
     * </ol>
     * 命令一次性执行完即断开；默认跳过主机指纹校验（StrictHostKeyChecking=no）方便首次连接。
     */
    private AIToolResult sshExec(Map<String, Object> parameters) {
        String host = (String) parameters.get("host");
        String user = (String) parameters.get("user");
        String command = (String) parameters.get("command");
        if (host == null || host.trim().isEmpty()) {
            return new AIToolResult("缺少参数: host（SSH 目标主机 IP/域名）", parameters);
        }
        if (command == null || command.trim().isEmpty()) {
            return new AIToolResult("缺少参数: command（要在远程主机执行的单条命令）", parameters);
        }
        String userAt = (user == null || user.trim().isEmpty()) ? "" : (user.trim() + "@");
        String port = (String) parameters.get("port");
        String portArg = (port == null || port.trim().isEmpty()) ? "" : ("-p " + port.trim() + " ");

        String password = (String) parameters.get("password");
        String keyFile = (String) parameters.get("key_file");

        // 拼接认证段
        String authSeg;
        String methodDesc;
        java.util.List<File> sshTempFiles = new java.util.ArrayList<>();
        if (password != null && !password.isEmpty()) {
            // 密码认证两条路径（都不依赖 Termux）：
            // ① 有 sshpass → 直接用（最兼容）；
            // ② 无 sshpass → OpenSSH 原生 SSH_ASKPASS 机制：写 askpass 脚本 + 密码文件，
            //    ssh 在无 TTY 时自动回调脚本取密码（OpenSSH 8.4+ 配合 SSH_ASKPASS_REQUIRE=force 强制生效）
            if (commandExists("sshpass")) {
                authSeg = "sshpass -p '" + password.replace("'", "'\\''") + "' ";
                methodDesc = "密码认证（sshpass）";
            } else {
                try {
                    File passFile = new File(context.getCacheDir(), "sshpass_" + System.currentTimeMillis());
                    File askScript = new File(context.getCacheDir(), "sshask_" + System.currentTimeMillis());
                    java.nio.file.Files.write(passFile.toPath(), password.getBytes("UTF-8"));
                    java.nio.file.Files.write(askScript.toPath(),
                            ("#!/system/bin/sh\ncat '" + passFile.getAbsolutePath() + "'\n").getBytes("UTF-8"));
                    if (!askScript.setExecutable(true, true)) {
                        throw new IllegalStateException("无法设置 askpass 脚本可执行");
                    }
                    authSeg = "export SSH_ASKPASS='" + askScript.getAbsolutePath()
                            + "' SSH_ASKPASS_REQUIRE=force DISPLAY=:0; ";
                    methodDesc = "密码认证";
                    sshTempFiles.add(passFile);
                    sshTempFiles.add(askScript);
                } catch (Exception e) {
                    AILogger.e(TAG, "SSH_ASKPASS 初始化失败: " + BaseAITool.errText(e));
                    return new AIToolResult("SSH 密码认证初始化失败: " + BaseAITool.errText(e)
                            + "\n建议改用密钥免密（不传 password），或把密钥放到 key_file 指定路径。", parameters);
                }
            }
        } else {
            authSeg = "";
            methodDesc = "密钥认证";
            if (keyFile == null || keyFile.trim().isEmpty()) {
                // 缺省：App 自己 HOME（filesDir）下的 ed25519；不存在则引导一键生成密钥对
                keyFile = new File(context.getFilesDir(), ".ssh/id_ed25519").getAbsolutePath();
            }
            if (password == null || password.isEmpty()) {
                File kf = new File(keyFile);
                if (!kf.exists()) {
                    String keyGen = "mkdir -p $HOME/.ssh && ssh-keygen -t ed25519 -N '' -f $HOME/.ssh/id_ed25519 && cat $HOME/.ssh/id_ed25519.pub";
                    return new AIToolResult(new java.util.HashMap<String, Object>() {{
                        put("status", "need_key");
                        put("host", host);
                        put("reason", "未找到 SSH 私钥（" + kf.getAbsolutePath() + "）。需要先生成密钥对并让目标机信任公钥。");
                        put("guide", "两步完成免密：\n"
                                + "① 在 shell_command 里执行一键生成并显示公钥：\n   " + keyGen + "\n"
                                + "② 把输出的 .pub 内容追加到目标机 ~/.ssh/authorized_keys（或你常用的公钥管理后台），\n"
                                + "   然后重新用 ssh_exec（不传 password 即可，缺省会自动用这个私钥）。\n"
                                + "若目标机仅支持密码认证：直接传 password 即可（App 内置 askpass，无需安装任何东西）。");
                    }}, parameters);
                }
            }
        }

        // 单引号包裹远程命令（防本地 shell 先做变量替换）；命令里若有单引号用 '\'' 转义
        String remoteCmd = "'" + command.replace("'", "'\\''") + "'";
        String keyArg = (password != null && !password.isEmpty())
                ? ""
                : ("-i '" + keyFile.replace("'", "'\\''") + "' ");

        // 附加退出码回显（executeShell 合并输出且不返回退出码，用末尾标记解析）
        String sshCmd = authSeg + "ssh " + keyArg + portArg
                + "-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null "
                + "-o ConnectTimeout=8 -o ServerAliveInterval=5 -o ServerAliveCountMax=2 "
                + userAt + host + " " + remoteCmd
                + "; echo __SSH_EXIT=$?";

        try {
            String output = executeShell(sshCmd);
            // 清理 askpass 临时文件（密码/脚本），避免残留
            for (File f : sshTempFiles) {
                try {
                    if (f != null && f.exists() && !f.delete()) f.deleteOnExit();
                } catch (Exception ignored) {
                }
            }
            if (output == null) {
                return new AIToolResult("SSH 执行无输出（连接失败？）", parameters);
            }
            int exitCode = -1;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("__SSH_EXIT=(-?\\d+)").matcher(output);
            if (m.find()) {
                exitCode = Integer.parseInt(m.group(1));
                // 去掉标记行，避免污染输出
                output = output.replace(m.group(0), "").replaceAll("\\n{2,}", "\n").trim();
            }
            Map<String, Object> result = new HashMap<>();
            result.put("status", exitCode == 0 ? "success" : "failed");
            result.put("method", methodDesc);
            result.put("target", userAt + host);
            result.put("exit_code", exitCode);
            result.put("output", truncateOutput(output));
            if (exitCode == 0) {
                result.put("hint", "SSH 命令执行成功。之后如需免密：生成密钥对并放公钥到目标机 ~/.ssh/authorized_keys，即可不再传密码。");
            } else if (exitCode == 255) {
                result.put("hint", "SSH 连接失败（255=连接层错误）：检查 host/port/user 是否正确、目标机 sshd 是否在跑、网络是否可达；若用密码认证确认 sshpass 可用；密钥认证确认私钥路径正确且公钥已放入目标机 authorized_keys。");
            }
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "ssh_exec 失败: " + e.getMessage(), e);
            return new AIToolResult("ssh_exec 失败: " + BaseAITool.errText(e), parameters);
        }
    }

    /** shell 环境里是否有某命令（如 sshpass） */
    private boolean commandExists(String name) {
        try {
            Process p = new ProcessBuilder("/system/bin/sh", "-c",
                    "command -v " + name + " >/dev/null 2>&1 && echo YES").start();
            String out = new String(p.getInputStream().readAllBytes(), "UTF-8").trim();
            p.waitFor(3, TimeUnit.SECONDS);
            return "YES".equals(out);
        } catch (Exception e) {
            return false;
        }
    }
    
    /** 输出上限：超长输出截断保留头尾，防止撑爆工具结果（与 shell_command 的 MAX_SHELL_OUTPUT_CHARS 同思路） */
    private static String truncateOutput(String s) {
        if (s == null) return "";
        if (s.length() <= 8000) return s;
        return s.substring(0, 4000) + "\n…(输出过长已截断，共 " + s.length() + " 字符)…\n" + s.substring(s.length() - 2000);
    }

    /** 判断是否为装包/下载类长任务（apt/pip/proot/pkg/install/update 等，20 秒跑不完属正常） */
    private static boolean isLongRunningCommand(String command) {
        if (command == null) return false;
        String c = command.trim().toLowerCase();
        String[] hints = {"apt", "pip", "proot", "pkg ", "install", "update", "upgrade", "wget ", "curl ", "git clone", "tar ", "unzip"};
        for (String h : hints) {
            if (c.contains(h)) return true;
        }
        return false;
    }

    /**
     * 对进入 Ubuntu 容器的简单命令做 PATH 纠正。
     * <p>proot-distro login ubuntu 会继承宿主的 PATH（Termux usr/bin 在前），
     * 容器里裸 git/gcc/make 会命中 Termux 的二进制（真机实测）。这里只处理
     * “proot-distro login ubuntu -- <简单命令>”这种形态：命令体不含引号/分号/
     * 反引号/$ 时，改写为 bash -lc 内先 export 容器优先 PATH 再执行。
     * 复杂命令（有引号嵌套、管道、变量）保持原样，由工具描述里的提示兑底。
     */
    private static String normalizeContainerPath(String command) {
        String trimmed = command.trim();
        if (!trimmed.startsWith("proot-distro login ubuntu")) {
            return command;
        }
        String rest = trimmed.substring("proot-distro login ubuntu".length()).trim();
        if (rest.isEmpty() || !rest.startsWith("--")) {
            return command;
        }
        String body = rest.substring(2).trim();
        if (body.isEmpty()) {
            return command;
        }
        // 已显式处理的形态直接放行（用户已经自己管了 PATH 或用了绝对路径/自定义 bash）
        if (body.startsWith("env PATH=") || body.startsWith("/usr/bin/") || body.startsWith("/bin/")
                || body.startsWith("bash -")) {
            return command;
        }
        // 简单命令判定：仅字母数字 + 路径/选项符号，不含引号、分号、反引号、$、管道、重定向
        if (!body.matches("[A-Za-z0-9_./=+\\-:@%^, ]+")) {
            return command;
        }
        return "proot-distro login ubuntu -- /bin/bash -lc \"export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; "
                + body + "\"";
    }

    /**
     * 读取系统设置
     */
    private AIToolResult readSetting(Map<String, Object> parameters) {
        String settingType = (String) parameters.get("setting_type");
        String settingKey = (String) parameters.get("setting_key");
        
        if (settingKey == null || settingKey.isEmpty()) {
            return new AIToolResult("缺少参数: setting_key", parameters);
        }
        if (settingType == null || settingType.isEmpty()) {
            settingType = "system";
        }
        
        try {
            String value;
            switch (settingType.toLowerCase()) {
                case "system":
                    value = Settings.System.getString(context.getContentResolver(), settingKey);
                    break;
                case "secure":
                    value = Settings.Secure.getString(context.getContentResolver(), settingKey);
                    break;
                case "global":
                    value = Settings.Global.getString(context.getContentResolver(), settingKey);
                    break;
                default:
                    return new AIToolResult("未知设置类型: " + settingType + "，支持: system/secure/global", parameters);
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("type", settingType);
            result.put("key", settingKey);
            result.put("value", value != null ? value : "(未设置)");
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("读取设置失败: " + e.getMessage(), parameters);
        }
    }
    
    /**
     * 修改系统设置
     */
    private AIToolResult writeSetting(Map<String, Object> parameters) {
        String settingType = (String) parameters.get("setting_type");
        String settingKey = (String) parameters.get("setting_key");
        String settingValue = (String) parameters.get("setting_value");
        
        if (settingKey == null || settingKey.isEmpty()) {
            return new AIToolResult("缺少参数: setting_key", parameters);
        }
        if (settingValue == null) {
            return new AIToolResult("缺少参数: setting_value", parameters);
        }
        if (settingType == null || settingType.isEmpty()) {
            settingType = "system";
        }
        
        try {
            boolean success;
            switch (settingType.toLowerCase()) {
                case "system":
                    success = Settings.System.putString(context.getContentResolver(), settingKey, settingValue);
                    break;
                case "secure":
                    success = Settings.Secure.putString(context.getContentResolver(), settingKey, settingValue);
                    break;
                case "global":
                    success = Settings.Global.putString(context.getContentResolver(), settingKey, settingValue);
                    break;
                default:
                    return new AIToolResult("未知设置类型: " + settingType + "，支持: system/secure/global", parameters);
            }
            
            Map<String, Object> result = new HashMap<>();
            if (success) {
                result.put("status", "success");
                result.put("message", "已修改设置: " + settingKey + " = " + settingValue);
            } else {
                result.put("status", "failed");
                result.put("message", "修改设置失败，可能缺少 WRITE_SETTINGS 权限");
            }
            result.put("type", settingType);
            result.put("key", settingKey);
            result.put("value", settingValue);
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("修改设置失败: " + e.getMessage(), parameters);
        }
    }
    
    /**
     * 获取当前前台应用信息
     */
    private AIToolResult getCurrentApp(Map<String, Object> parameters) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            
            if (processes != null && !processes.isEmpty()) {
                List<Map<String, Object>> foregroundApps = new ArrayList<>();
                for (ActivityManager.RunningAppProcessInfo proc : processes) {
                    if (proc.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        Map<String, Object> appInfo = new HashMap<>();
                        appInfo.put("package", proc.processName);
                        appInfo.put("importance", "foreground");
                        try {
                            String label = context.getPackageManager().getApplicationLabel(
                                context.getPackageManager().getApplicationInfo(proc.processName, 0)
                            ).toString();
                            appInfo.put("name", label);
                        } catch (Exception ignored) {}
                        foregroundApps.add(appInfo);
                    }
                }
                result.put("foreground_apps", foregroundApps);
                result.put("count", foregroundApps.size());
            } else {
                result.put("message", "无法获取前台应用列表");
            }
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("获取前台应用失败: " + e.getMessage(), parameters);
        }
    }
    
    private void normalizeParameters(Map<String, Object> parameters) {
        if (parameters == null) return;
        
        if (parameters.containsKey("app_name") && !parameters.containsKey("app")) {
            parameters.put("app", parameters.get("app_name"));
        }
        if (parameters.containsKey("phone_number") && !parameters.containsKey("phone")) {
            parameters.put("phone", parameters.get("phone_number"));
        }
    }
    
    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("action", "操作类型: open_app/open_url/send_sms/make_call/send_email/open_map/share_text/open_settings/list_apps/check_app/get_app_info/app_control/shell_command/termux_exec/read_setting/write_setting/get_current_app");
        descriptions.put("app", "应用名称或包名（支持模糊匹配）");
        descriptions.put("package", "应用包名");
        descriptions.put("url", "网址链接");
        descriptions.put("phone", "电话号码");
        descriptions.put("message", "短信内容");
        descriptions.put("command", "Shell命令（如: pm list packages, dumpsys activity top, sed -n 1,20p 文件, grep -rn TODO /sdcard/Download, wget -O /sdcard/a.zip URL）。单条命令 25 秒超时，超时会终止并返回已产生的输出；系统自带 toybox 命令可直接用，另有内置 busybox（ash/wget/awk/vi/telnet 等）在 PATH 末尾");
        descriptions.put("setting_type", "设置类型: system/secure/global");
        descriptions.put("setting_key", "设置键名（如: screen_brightness, wifi_on, airplane_mode_on）");
        descriptions.put("setting_value", "设置值");
        descriptions.put("control_action", "应用控制操作: force_stop(强制停止)/clear_data(清除数据)/detailed_info(详细信息)");
        descriptions.put("to", "收件人邮箱");
        descriptions.put("subject", "邮件主题");
        descriptions.put("body", "邮件正文");
        descriptions.put("location", "位置坐标");
        descriptions.put("address", "地址");
        descriptions.put("text", "分享内容");
        descriptions.put("title", "分享标题");
        descriptions.put("setting", "设置页: wifi/bluetooth/location/display/sound/storage/app/battery");
        return descriptions;
    }
}

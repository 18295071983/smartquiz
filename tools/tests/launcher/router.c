// 内置工具路由器：按 内置 -> 系统 -> busybox -> toybox 的顺序找能用的实现，找不到就报清楚。
//
// 为什么需要：
//  1) Android 只允许执行 nativeLibraryDir（随 APK 解压）里的文件，数据目录一律 Permission denied；
//  2) 把工具库目录塞进全局 LD_LIBRARY_PATH 会让系统二进制（如 /system/bin/curl）加载到我们的
//     libcrypto.so 而符号不匹配 —— 所以由本路由器【只给自己】设置库路径；
//  3) 内置工具万一不可用（缺库/不兼容），自动回退到系统自带实现（toybox 覆盖 200+ 命令），
//     而不是直接把命令搞挂。
//
// 约定：bin/<name> 是指向本路由器的软链接；内置二进制为 nativeLibraryDir/lib<name>_bin.so
//       （'-' 换成 '_'）。可用 bin/.route 覆盖顺序：一行 "名字=顺序"，顺序取值 b,s,k,t
//       （bundled, system, busybox, toybox），例如 curl=bskt、sed=stkb。
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/**
 * 应用数据目录（files/）——多用户 / 工作资料下路径不同，所以不能写死：
 *   1) 优先 $APP_FILES（App 注入的精确路径）
 *   2) 其次 $HOME（App 注入，且系统 shell 也有）
 *   3) 自动推导：Android 的 uid = userId * 100000 + appId，
 *      所以 /data/user/<uid/100000>/<包名>/files —— 不依赖任何环境变量，多用户也对
 *   4) 以上都不可用（目录不存在）时，退回单用户默认路径
 */
static char g_app_files[PATH_MAX];

static const char *app_files(void) {
    if (g_app_files[0] != '\0') {
        return g_app_files;
    }
    const char *env = getenv("APP_FILES");
    if (env == NULL || env[0] == '\0') {
        env = getenv("HOME");
    }
    if (env != NULL && env[0] != '\0' && access(env, R_OK) == 0) {
        snprintf(g_app_files, sizeof(g_app_files), "%s", env);
        return g_app_files;
    }
    snprintf(g_app_files, sizeof(g_app_files), "/data/user/%d/com.oilquiz.app/files",
             (int) (getuid() / 100000));
    if (access(g_app_files, R_OK) == 0) {
        return g_app_files;
    }
    snprintf(g_app_files, sizeof(g_app_files), "%s", "/data/user/0/com.oilquiz.app/files");
    return g_app_files;
}
#define MAX_ARGS 4096

static char g_exe_dir[PATH_MAX];

/** name 是否出现在 bin/.busybox_applets（busybox --list 的结果，每行一个） */
static int is_busybox_applet(const char *name) {
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/bin/.busybox_applets", app_files());
    FILE *f = fopen(path, "r");
    if (f == NULL) {
        return 0;
    }
    char line[256];
    int found = 0;
    while (fgets(line, sizeof(line), f) != NULL) {
        size_t n = strlen(line);
        while (n > 0 && (line[n - 1] == '\n' || line[n - 1] == '\r')) {
            line[--n] = '\0';
        }
        if (strcmp(line, name) == 0) {
            found = 1;
            break;
        }
    }
    fclose(f);
    return found;
}

/** 读 bin/.route 里该命令的顺序（缺省 "bskt"） */
static void route_order(const char *name, char *order, size_t size) {
    snprintf(order, size, "bskt");
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/bin/.route", app_files());
    FILE *f = fopen(path, "r");
    if (f == NULL) {
        return;
    }
    char line[256];
    while (fgets(line, sizeof(line), f) != NULL) {
        char *eq = strchr(line, '=');
        if (eq == NULL) {
            continue;
        }
        *eq = '\0';
        if (strcmp(line, name) != 0) {
            continue;
        }
        char *v = eq + 1;
        size_t n = strlen(v);
        while (n > 0 && (v[n - 1] == '\n' || v[n - 1] == '\r')) {
            v[--n] = '\0';
        }
        if (n > 0) {
            snprintf(order, size, "%s", v);
        }
        break;
    }
    fclose(f);
}

static void set_bundled_env(void) {
    char libs[PATH_MAX * 3];
    // 顺序很重要：toolkit_lib / lib 必须排在 nativeLibraryDir 之前，
    // 否则 App 自带的 libc++_shared.so（不同 NDK 版本）会抢在 Termux 版前面被加载，
    // ffmpeg 这类工具就会出现符号不匹配。
    snprintf(libs, sizeof(libs), "%s/toolkit_lib:%s/lib:%s", app_files(), app_files(), g_exe_dir);
    setenv("LD_LIBRARY_PATH", libs, 1);
}

int main(int argc, char **argv) {
    char exe[PATH_MAX];
    ssize_t n = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    if (n <= 0) {
        return 127;
    }
    exe[n] = '\0';
    char *slash = strrchr(exe, '/');
    if (slash == NULL) {
        return 127;
    }
    *slash = '\0';
    snprintf(g_exe_dir, sizeof(g_exe_dir), "%s", exe);

    const char *arg0 = argv[0];
    const char *base = strrchr(arg0, '/');
    base = (base == NULL) ? arg0 : base + 1;

    char safe[256];
    size_t i = 0;
    for (; base[i] != '\0' && i < sizeof(safe) - 1; i++) {
        safe[i] = (base[i] == '-') ? '_' : base[i];
    }
    safe[i] = '\0';

    char order[16];
    route_order(base, order, sizeof(order));

    /* toybox 调用形式：toybox <applet> args... */
    char *tb_argv[MAX_ARGS];
    tb_argv[0] = (char *) "toybox";
    tb_argv[1] = (char *) base;
    for (int k = 1; k < argc && k + 1 < MAX_ARGS; k++) {
        tb_argv[k + 1] = argv[k];
    }
    tb_argv[argc + 1 < MAX_ARGS ? argc + 1 : MAX_ARGS - 1] = NULL;

    char target[PATH_MAX];
    for (const char *p = order; *p != '\0'; p++) {
        switch (*p) {
            case 'b': /* 内置（随 APK 打包） */
                snprintf(target, sizeof(target), "%s/lib%s_bin.so", g_exe_dir, safe);
                if (access(target, X_OK) == 0) {
                    set_bundled_env();
                    execv(target, argv);
                }
                break;
            case 's': /* 系统自带同名命令 */
                snprintf(target, sizeof(target), "/system/bin/%s", base);
                if (access(target, X_OK) == 0) {
                    execv(target, argv);
                }
                break;
            case 'k': /* busybox applet */
                if (is_busybox_applet(base)) {
                    snprintf(target, sizeof(target), "%s/libbusybox_launcher.so", g_exe_dir);
                    if (access(target, X_OK) == 0) {
                        execv(target, argv);
                    }
                }
                break;
            case 't': /* toybox */
                if (access("/system/bin/toybox", X_OK) == 0) {
                    execv("/system/bin/toybox", tb_argv);
                }
                break;
            default:
                break;
        }
    }

    fprintf(stderr, "%s: 没有可用实现（内置/系统/busybox/toybox 都没有）\n", base);
    return 127;
}

package com.oilquiz.app.ai.importing.v2;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;

/**
 * 导入管线公共目录工具。
 * <p>
 * 公共读写目录（Python 专用）：/storage/emulated/0/OilQuiz/
 * 私有沙箱目录仅 Java 可访问，数据库与模型权重存放其中。
 */
public final class ImportDirs {

    private static final String TAG = "ImportDirs";

    /** 公共根目录名 */
    public static final String PUBLIC_ROOT_NAME = "OilQuiz";
    /** 题库源文件目录 */
    public static final String SOURCE_DIR = "source";
    /** 临时 CSV 输出目录 */
    public static final String TEMP_DIR = "temp";
    /** 字段映射缓存文件 */
    public static final String MAP_CACHE_FILE = "map_cache.json";
    /** 断点状态文件 */
    public static final String BREAKPOINT_FILE = "import_breakpoint.state";
    /** 入库就绪标记文件 */
    public static final String READY_FLAG_FILE = "import_ready.flag";
    /** 错误行导出文件 */
    public static final String ERROR_ROWS_FILE = "error_rows.csv";
    /** 缺字段题目报告文件（引导用户修复后重导） */
    public static final String ISSUES_REPORT_FILE = "import_issues_report.csv";

    private static volatile android.content.Context appCtx;

    private ImportDirs() {
    }

    /** App 启动时注入 context（决定走公共根还是私有根） */
    public static void init(android.content.Context ctx) {
        appCtx = ctx != null ? ctx.getApplicationContext() : null;
        Log.i(TAG, "ImportDirs.init, hasAllFilesAccess=" + hasAllFilesAccess()
                + ", root=" + publicRoot().getAbsolutePath());
    }

    /** 是否持有"所有文件访问"权限（Android 11+；旧版本恒 true） */
    public static boolean hasAllFilesAccess() {
        return android.os.Build.VERSION.SDK_INT < 30
                || android.os.Environment.isExternalStorageManager();
    }

    /**
     * 工作区根目录，双模式自动切换：
     * <ul>
     *   <li>有"所有文件访问" → /storage/emulated/0/OilQuiz/（文件管理器可见、用户习惯）</li>
     *   <li>无权限 → App 私有 files/import_workspace/（Python 同进程可读写，无需任何权限；
     *       私有持久目录不会被系统清理，导入/断点/缓存全链路照常跑）</li>
     * </ul>
     * 因为所有子目录都经本方法派生，切根后整条导入管线零改动自动跟随。
     */
    public static File publicRoot() {
        File root;
        if (hasAllFilesAccess()) {
            root = new File(Environment.getExternalStorageDirectory(), PUBLIC_ROOT_NAME);
        } else {
            android.content.Context c = appCtx;
            root = c != null
                    ? new File(c.getFilesDir(), "import_workspace")
                    : new File(Environment.getExternalStorageDirectory(), PUBLIC_ROOT_NAME);
        }
        if (!root.exists() && !root.mkdirs()) {
            Log.w(TAG, "创建工作区目录失败: " + root.getAbsolutePath());
        }
        return root;
    }

    /** 题库源文件目录（自动创建） */
    public static File sourceDir() {
        File dir = new File(publicRoot(), SOURCE_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 临时 CSV 目录（自动创建） */
    public static File tempDir() {
        File dir = new File(publicRoot(), TEMP_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File mapCacheFile() {
        return new File(publicRoot(), MAP_CACHE_FILE);
    }

    public static File breakpointFile() {
        return new File(publicRoot(), BREAKPOINT_FILE);
    }

    public static File readyFlagFile() {
        return new File(tempDir(), READY_FLAG_FILE);
    }

    public static File errorRowsFile() {
        return new File(tempDir(), ERROR_ROWS_FILE);
    }

    /** 缺字段报告文件（放公共根目录，便于用户查看与修改后重导） */
    public static File issuesReportFile() {
        return new File(publicRoot(), ISSUES_REPORT_FILE);
    }

    /**
     * 获取给定源文件对应的断点目录（临时目录下按文件名哈希隔离，支持多文件先后导入）。
     */
    public static File sessionDir(Context context, String sourceFileName) {
        String safe = sourceFileName == null ? "session"
                : sourceFileName.replaceAll("[^A-Za-z0-9._\\-]", "_");
        File dir = new File(tempDir(), safe + "_"
                + Integer.toHexString(Math.abs((sourceFileName == null ? 0 : sourceFileName.hashCode()))));
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 清理会话目录中的临时产物（CSV、标记文件），并删除已清空的目录本体 */
    public static void cleanSessionDir(File sessionDir) {
        if (sessionDir == null || !sessionDir.exists()) return;
        File[] files = sessionDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    boolean deleted = f.delete();
                    if (!deleted) {
                        Log.w(TAG, "清理临时文件失败: " + f.getName());
                    }
                }
            }
        }
        // 目录已清空则删除本体（避免 temp/ 下残留大量空会话目录）
        if (!sessionDir.delete()) {
            Log.w(TAG, "清理会话目录失败(可能仍非空): " + sessionDir.getName());
        }
    }

    /**
     * 清理 temp/ 下所有历史会话目录。只删除【空目录】：
     * 正在进行的并行导入目录非空（含分片文件）会被安全跳过，不会误删。
     * 用于每次全新导入前打扫上次中断/完成残留的空目录。
     */
    public static void cleanAllEmptySessionDirs() {
        File temp = tempDir();
        File[] dirs = temp.listFiles();
        if (dirs == null) return;
        int removed = 0;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            File[] inside = d.listFiles();
            boolean empty = inside == null || inside.length == 0;
            if (empty && d.delete()) {
                removed++;
            }
        }
        if (removed > 0) {
            Log.i(TAG, "清理历史空会话目录 " + removed + " 个");
        }
    }
}

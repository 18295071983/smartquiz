package com.oilquiz.app.database;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 旧库保护：Room 打开前先看清"这次打开会不会把整库重建掉"。
 *
 * <p><b>为什么需要它</b>：{@code AppDatabase} 用的是
 * {@code addMigrations(MIGRATIONS).fallbackToDestructiveMigration()}，而 MIGRATIONS 数组
 * 第一条是 17→18（{@link #LOWEST_MIGRATABLE_VERSION}）。也就是说库版本低于 17 时 Room
 * 没有迁移路径，走兜底＝<b>drop 所有表再按实体重建</b>：题目、成绩、错题、笔记、对话历史
 * 一起没，界面不会有任何提示，用户只会发现"题库空了"。这是不可逆的数据丢失，而我们现在
 * 既拦不住（不重建就得让这台设备起不来）也不该装作没事。</p>
 *
 * <p>所以这一层做三件事，都不改变"能打开"这个结果：
 * <ol>
 *   <li>用普通只读方式读 {@code PRAGMA user_version}，判断这次打开会不会触发重建；</li>
 *   <li>会触发的，先把库文件（含 {@code -wal}/{@code -shm}）整份复制到
 *       {@code filesDir/db_rebuild_backup/<时间>/}，并顺手报一句题库还剩多少道题；</li>
 *   <li>留 AUDIT 日志 + 一次性 toast，让用户知道数据被搬走了、搬到哪、这不是"没了"。</li>
 * </ol>
 * 事后靠这份备份恢复仍然是一条出路（{@code DatabaseUpgradeManager.restoreFromBackup} 那条路
 * 或直接覆盖回 {@code databases/}），而不会像原来那样静默蒸发。</p>
 *
 * <p>本类只在 Room 之前<b>读</b>，不写、不改版本号；任何异常都只让自己退场，
 * 绝不挡住数据库打开——保护动作失败也不该把用户拦在门外，但要留下 W。</p>
 */
public final class LegacyDatabaseGuard {

    private static final String TAG = "LegacyDatabaseGuard";

    /**
     * Room 能<b>无损</b>迁移的最低版本：MIGRATIONS 数组第一条是 17→18。
     * 低于这个版本号就没有迁移链，fallbackToDestructiveMigration 会整库重建。
     * 改了 MIGRATIONS 的首元素必须同步改这里（改的时候也改
     * {@code DatabaseVersionChecker.MIN_SUPPORTED_VERSION}，两处口径要一致）。
     */
    public static final int LOWEST_MIGRATABLE_VERSION = 17;

    /** 与 AppDatabase 里的库名保持一致 */
    private static final String DB_NAME = "smartquiz_database";

    private static final java.util.concurrent.atomic.AtomicBoolean checked =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicBoolean noticed =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private LegacyDatabaseGuard() {
    }

    /**
     * Room 打开之前调用。每个进程只跑一次（{@code AppDatabase.getDatabase} 会被反复调，
     * 而重建只可能发生在第一次真正打开的时候）。
     */
    public static void protectBeforeOpen(Context context) {
        if (context == null) return;
        if (!checked.compareAndSet(false, true)) return;
        try {
            File dbFile = context.getDatabasePath(DB_NAME);
            if (dbFile == null || !dbFile.exists() || dbFile.length() <= 0) return; // 新装，没有数据可丢

            int version = 0;
            int questions = -1;
            SQLiteDatabase raw = null;
            try {
                raw = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null,
                        SQLiteDatabase.OPEN_READONLY);
                version = raw.getVersion();
                if (version > 0) {
                    questions = countQuestions(raw);
                }
            } finally {
                if (raw != null) {
                    try {
                        raw.close();
                    } catch (Exception closeFailed) {
                        Log.w(TAG, "只读探测的库没关掉: " + closeFailed.getMessage());
                    }
                }
            }
            // version==0 是"这文件还不是我们认得的 Room 库"（全新空文件），没有迁移与重建之分
            if (version <= 0 || version >= LOWEST_MIGRATABLE_VERSION) {
                return;
            }

            String backupDir = backupFiles(context, dbFile);
            String notice = "检测到 v" + version + " 的旧题库（Room 只带了 v"
                    + LOWEST_MIGRATABLE_VERSION + " 起的迁移），将重建表结构；已整库备份到 "
                    + backupDir + (questions >= 0 ? "，里面有 " + questions + " 道题" : "")
                    + "。旧数据不会自动回来，需要时从这份备份恢复。";
            AILogger.audit(TAG, notice);
            showOnce(context, notice);
        } catch (Exception guardFailed) {
            // 保护动作本身失败不能挡住打开：宁可少一份备份，也不能让人连应用都进不去
            Log.w(TAG, "旧库保护没跑成（不阻止数据库打开）: " + guardFailed.getMessage());
        }
    }

    /** COUNT(*) 拿不到就返回 -1：探测失败不该把重建判断一起废掉 */
    private static int countQuestions(SQLiteDatabase raw) {
        Cursor c = null;
        try {
            c = raw.rawQuery("SELECT COUNT(*) FROM question", null);
            if (c != null && c.moveToFirst()) {
                return c.getInt(0);
            }
        } catch (Exception noTable) {
            Log.w(TAG, "旧库里读不到 question 表（可能更早的版本）: " + noTable.getMessage());
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception closeFailed) {
                    Log.w(TAG, "count 游标没关掉: " + closeFailed.getMessage());
                }
            }
        }
        return -1;
    }

    /**
     * 把 {@code .db} 连 {@code -wal}/{@code -shm} 一起复制。
     * WAL 模式下未 checkpoint 的数据只在 {@code -wal} 里，只拷主文件等于拷一份旧的。
     */
    private static String backupFiles(Context context, File dbFile) {
        File base = new File(context.getFilesDir(), "db_rebuild_backup");
        File dest = new File(base, new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date()));
        if (!dest.mkdirs() && !dest.isDirectory()) {
            Log.w(TAG, "备份目录建不出来，这次重建不会有备份: " + dest.getPath());
            return "(备份失败:" + dest.getPath() + ")";
        }
        copyIfPresent(dbFile, new File(dest, dbFile.getName()));
        copyIfPresent(new File(dbFile.getPath() + "-wal"), new File(dest, dbFile.getName() + "-wal"));
        copyIfPresent(new File(dbFile.getPath() + "-shm"), new File(dest, dbFile.getName() + "-shm"));
        return dest.getAbsolutePath();
    }

    private static void copyIfPresent(File from, File to) {
        if (from == null || !from.exists()) return;
        try (FileInputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        } catch (IOException copyFailed) {
            // 少一份文件＝这份备份可能不完整，必须单独说出来（-wal 没拷到尤其致命）
            Log.w(TAG, "备份 " + from.getName() + " 没拷全: " + copyFailed.getMessage());
        }
    }

    private static void showOnce(final Context context, final String message) {
        if (!noticed.compareAndSet(false, true)) return;
        final Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(app, message, Toast.LENGTH_LONG).show();
            } catch (Exception toastFailed) {
                Log.w(TAG, "重建提示的 toast 没弹出来（AUDIT 日志仍在）: " + toastFailed.getMessage());
            }
        });
    }
}

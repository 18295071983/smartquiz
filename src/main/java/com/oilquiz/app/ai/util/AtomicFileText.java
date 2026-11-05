package com.oilquiz.app.ai.util;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 文本文件的<b>原子写入 + 备份回读</b>。
 *
 * <p><b>为什么需要它</b>：Agent 的会话历史此前是 {@code new FileWriter(file)} 直接覆盖写。
 * 写到一半进程被杀（切后台被回收、用户强停、OOM），文件就停在半截 JSON 上；下次启动时解析失败，
 * 现有代码只打一行告警，于是<b>整段对话静默丢失</b>——用户看到的是"历史没了"，日志里却只有
 * 一句"restore failed"。</p>
 *
 * <p>做法与工作区内置指南文件一致（那边早就在用 tmp+rename）：先写 {@code <file>.tmp} 并 fsync，
 * 把旧文件备份成 {@code <file>.bak}，再 rename 覆盖主文件（同目录 rename 在 POSIX 上原子）。
 * 读的时候主文件解析失败就回落 {@code .bak}——最坏情况是丢掉"最后一次保存"，而不是全部。</p>
 *
 * <p>两处如实说明，别把上面那句承诺读过头：
 * <ol>
 *   <li>备份 rename 与主文件 rename 之间，主文件<b>是短暂不存在的</b>（内容在 {@code .bak} 里）。
 *       这期间被杀，{@link #readWithBackup} 仍能从备份读到旧内容，所以"不丢全部"成立，
 *       但"旧文件保持原样"不成立。</li>
 *   <li>备份拿不到时（rename 和退化的复制都失败）下面照样会覆盖主文件——拒绝写入等于存不了，
 *       更糟。这种情况会留一条 W，说明这次写入没有回退点。</li>
 * </ol></p>
 *
 * <p>纯 java.io + {@code android.util.Log}，不依赖 Context，可直接单测（见 {@code AtomicFileTextTest}）。
 * 故意不用 {@code AILogger}：它是全局单文件日志、要 Context 初始化，而这个原语被离线单测直接调用；
 * 要进 ai_logs 的失败由调用方（ChatHistoryManager 等）升级上报。</p>
 */
public final class AtomicFileText {

    private static final String TAG = "AtomicFileText";

    private AtomicFileText() {
    }

    /** 备份后缀与临时后缀（与主文件同目录，保证 rename 不跨文件系统） */
    public static final String TMP_SUFFIX = ".tmp";
    public static final String BAK_SUFFIX = ".bak";

    /**
     * 原子写入：{@code content} 要么完整落盘、要么旧文件保持原样，不会留下半截。
     *
     * @return 成功与否；失败时旧文件不受影响（临时文件会被清掉）
     */
    public static boolean write(File file, String content) {
        if (file == null || content == null) return false;
        File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) return false;

        File tmp = new File(file.getPath() + TMP_SUFFIX);
        File bak = new File(file.getPath() + BAK_SUFFIX);

        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
                out.flush();
                try {
                    out.getFD().sync(); // 尽量把数据刷到磁盘，减小"rename 成功但内容没落盘"的窗口
                } catch (IOException ignored) {
                    // 某些文件系统不支持 sync：不影响语义，失败不阻断
                }
            }
        } catch (IOException e) {
            // 磁盘满 / 没权限 / 路径被占用都走这里。返回 false 之外必须留下原因：
            // 调用方只会说"没保存成功"，而"没保存成功"最常见的根因（ENOSPC）得有个出处。
            Log.w(TAG, "临时文件写失败，已放弃这次写入（主文件未动）: " + file.getPath() + " —— " + e);
            deleteQuietly(tmp);
            return false;
        }

        // 旧文件先备份（覆盖更早的备份）。备份彻底拿不到时不能装作没事：
        // 紧接着的 rename 会覆盖主文件，那份旧内容就再没有退路了——
        // "最坏丢最后一次保存"会退化成"最坏整段丢"。
        if (file.exists()) {
            deleteQuietly(bak);
            boolean backedUp = file.renameTo(bak) || copyTo(file, bak);
            if (!backedUp) {
                Log.w(TAG, "备份没写成，这次覆盖没有回退点: " + file.getPath() + " -> " + bak.getPath());
            }
        }

        if (tmp.renameTo(file)) {
            return true;
        }
        // rename 失败（目标被占用等）：退化为"删除 + rename"，仍比直接覆盖安全（备份还在）
        deleteQuietly(file);
        if (tmp.renameTo(file)) {
            return true;
        }
        deleteQuietly(tmp);
        return false;
    }

    /** 读主文件；主文件不存在时回落 {@code .bak}。都不存在返回 null */
    public static String readWithBackup(File file) {
        String main = readText(file);
        if (main != null) return main;
        return readText(backupOf(file));
    }

    /** 只读备份（主文件解析失败时由调用方回落，保留"先记日志再回落"的空间） */
    public static String readBackup(File file) {
        return readText(backupOf(file));
    }

    public static File backupOf(File file) {
        return file == null ? null : new File(file.getPath() + BAK_SUFFIX);
    }

    /**
     * 读文本；不存在/读失败返回 null。
     *
     * <p>"读失败"和"文件不存在"塌成同一个 null 是本类最容易被误读的地方：调用方
     * （ChatHistoryManager 那类）看到 null 就回落备份或当成"没有历史"，于是 EACCES、
     * 拔了存储、读一半断掉这些真故障都长得像"这用户没历史"。返回值形状不能改（改了
     * 就是另一件事），但异常必须留下：路径 + 原因，一条 W。</p>
     */
    public static String readText(File file) {
        if (file == null || !file.isFile()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.w(TAG, "读文件失败（不是\"文件不存在\"，别按空数据处理）: " + file.getPath()
                    + " —— " + e);
            return null;
        }
    }

    /** 复制兜底（rename 不可用时）。返回是否真写出了备份 */
    private static boolean copyTo(File from, File to) {
        String text = readText(from);
        if (text == null) {
            Log.w(TAG, "备份前的复制没读到源内容，放弃备份: " + from.getPath());
            return false;
        }
        try (FileOutputStream out = new FileOutputStream(to)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return true;
        } catch (IOException e) {
            Log.w(TAG, "备份写不出: " + to.getPath() + " —— " + e);
            return false;
        }
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}

package com.oilquiz.app.ai.util;

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
 * <p>纯 java.io，不依赖 Context，可直接单测（见 {@code AtomicFileTextTest}）。</p>
 */
public final class AtomicFileText {

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
            deleteQuietly(tmp);
            return false;
        }

        // 旧文件先备份（覆盖更早的备份）；备份失败不阻断写入
        if (file.exists()) {
            deleteQuietly(bak);
            if (!file.renameTo(bak)) {
                // rename 失败（少见）：退化为复制
                copyTo(file, bak);
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

    /** 读文本；不存在/读失败返回 null */
    public static String readText(File file) {
        if (file == null || !file.isFile()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static void copyTo(File from, File to) {
        String text = readText(from);
        if (text == null) return;
        try (FileOutputStream out = new FileOutputStream(to)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
            // 备份失败不影响主流程（主文件还在）
        }
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}

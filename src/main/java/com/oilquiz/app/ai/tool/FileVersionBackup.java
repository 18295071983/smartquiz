package com.oilquiz.app.ai.tool;

import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件历史版本备份（单一实现，2026-10-29 从 {@link FileGeneratorTool} 抽出）。
 *
 * <p>为什么要有这个类：备份这件事原先只有 {@code file_generator} 做（覆盖写前轮转
 * {@code .ver1..ver5}），而真正会写坏用户数据的 {@code excel_tool} 是
 * "不传 output_path 就原地保存"，一次覆盖就把台账改掉了，连回退的对象都不存在。
 * 两处共用同一份轮转逻辑，命名与保留数才能对得上，
 * {@code workspace(action=versions/restore)} 也才知道去哪儿找。</p>
 *
 * <p>命名约定：{@code 原名.ext.verN}（N=1 最新）。列版本、恢复都按这个名字规则，
 * 换实现时不要改名字——旧工作区里已经有一批 .verN。</p>
 */
public final class FileVersionBackup {

    private static final String TAG = "FileVersionBackup";

    /** 保留最近 5 版（沿用 file_generator 原口径） */
    public static final int MAX_VERSIONS = 5;
    public static final String SUFFIX = ".ver";

    private FileVersionBackup() {
    }

    /** 某个文件的第 n 版备份路径（n 从 1 开始） */
    public static File versionFile(File target, int n) {
        return new File(target.getParentFile(), target.getName() + SUFFIX + n);
    }

    /**
     * 覆盖写前备份：当前文件 → {@code .ver1}，旧版本依次轮转，超上限的最旧版本删除。
     *
     * <p>只在"文件存在且非空"时备份；备份失败<b>不阻断主流程</b>——写坏之前没留底才可怕，
     * 留底失败顶少一次回退机会，不该因此让用户的一次正常写入报错。</p>
     *
     * @return 是否真的留了一份备份
     */
    public static boolean backup(File target) {
        try {
            if (target == null || !target.exists() || target.length() == 0) return false;
            File dir = target.getParentFile();
            String base = target.getName();
            // 1) 轮转旧版本（从旧到新，先腾出最末位）
            for (int v = MAX_VERSIONS - 1; v >= 1; v--) {
                File oldV = new File(dir, base + SUFFIX + v);
                File newV = new File(dir, base + SUFFIX + (v + 1));
                if (newV.exists() && !newV.delete()) {
                    AILogger.w(TAG, "删除旧版本失败: " + newV.getName());
                }
                if (oldV.exists() && !oldV.renameTo(newV)) {
                    AILogger.w(TAG, "轮转版本失败: " + oldV.getName());
                }
            }
            // 2) 当前文件 → ver1
            Files.copy(target.toPath(), versionFile(target, 1).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            AILogger.i(TAG, "已备份文件版本: " + base + " → " + base + SUFFIX + "1");
            return true;
        } catch (Exception e) {
            AILogger.w(TAG, "备份文件版本失败: " + (target != null ? target.getName() : "?")
                    + " - " + e.getMessage());
            return false;
        }
    }

    /** 一个历史版本的元信息 */
    public static final class Version {
        public final int index;
        public final File file;
        public final long size;
        public final long lastModified;

        Version(int index, File file, long size, long lastModified) {
            this.index = index;
            this.file = file;
            this.size = size;
            this.lastModified = lastModified;
        }
    }

    /** 该文件现存的历史版本，按 N 升序（1 = 最近一版） */
    public static List<Version> versions(File target) {
        List<Version> out = new ArrayList<>();
        if (target == null) return out;
        for (int n = 1; n <= MAX_VERSIONS; n++) {
            File v = versionFile(target, n);
            if (v.exists() && v.length() > 0) {
                out.add(new Version(n, v, v.length(), v.lastModified()));
            }
        }
        return out;
    }

    /**
     * 恢复到第 {@code n} 版（1=最近一版）。
     *
     * <p>恢复本身可撤销：先把"当前内容"备份成新的一版再去覆盖它。所以"restore 错版本"
     * 还能再 restore 回来，不会造成单向损失。目标不存在（被删了）时不需要备份当前。</p>
     *
     * @return null 表示成功；非 null 是给调用方的错误文案
     */
    public static String restore(File target, int n) {
        if (target == null) return "缺少目标文件";
        if (n < 1 || n > MAX_VERSIONS) {
            return "version 越界: " + n + "（只支持 1.." + MAX_VERSIONS + "，1=最近一版）";
        }
        File src = versionFile(target, n);
        if (!src.exists()) {
            return "没有 " + target.getName() + SUFFIX + n + " 这份备份"
                    + "（现有版本可先 workspace(action=versions, fileName=" + target.getName() + "）查看）";
        }
        File tmp = new File(target.getParentFile(), target.getName() + ".restoring");
        try {
            // 先固定要用的那份内容，再轮转——否则 backup 会把 verN 改名成 ver(N+1)，
            // 我们手上那个 File 就指向空气了
            Files.copy(src.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            backup(target);
            Files.copy(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            AILogger.i(TAG, "已恢复到第 " + n + " 版: " + target.getName());
            return null;
        } catch (Exception e) {
            return "恢复失败: " + e.getMessage();
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }
}

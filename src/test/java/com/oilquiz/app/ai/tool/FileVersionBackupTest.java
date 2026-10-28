package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * {@link FileVersionBackup} 的轮转与恢复（2026-10-29）。
 *
 * <p>这段逻辑原先埋在 {@code file_generator} 私有方法里，只有覆盖写会触发；现在
 * {@code excel_tool} 原地保存也接上了，同一套 {@code 原名.verN} 命名变成两个写者的共同约定，
 * 所以抽成独立类并单独测——名字或轮转方向一改，{@code workspace(action=versions/restore)}
 * 就会去找一个根本不存在的版本，用户看到的将是"没有备份"。</p>
 *
 * <p>重点钉三件事：①第 1 版永远是"最近一次被覆盖掉的内容"（方向反了，"恢复第 1 版"会把刚写的
 * 换成最旧的）；②只保留 5 版，不能无限堆文件；③恢复本身可撤销——退错了还能退回来。</p>
 *
 * <p>下面的 {@code overwrite()} 就是真实调用顺序：<b>先 backup 再写</b>（{@code file_generator}
 * 覆盖写、{@code excel_tool} 保存都是这个次序）。反过来测会得到完全不同的版本序列。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class FileVersionBackupTest {

    private Context context;
    private File dir;
    private File target;

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        dir = new File(context.getCacheDir(), "verbackup-" + System.nanoTime());
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        target = new File(dir, "ledger.csv");
        write(target, "v0");
    }

    @After
    public void tearDown() {
        deleteRecursive(dir);
    }

    private void write(File f, String content) throws Exception {
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** 模拟一次覆盖写：备份当前内容，然后写入新内容 */
    private void overwrite(String content) throws Exception {
        FileVersionBackup.backup(target);
        write(target, content);
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ---------- 备份 ----------

    @Test
    public void backupCreatesVer1HoldingPreviousContent() throws Exception {
        overwrite("v1");

        File ver1 = FileVersionBackup.versionFile(target, 1);
        assertTrue("覆盖写前必须留下 原名.ver1", ver1.exists());
        assertEquals("备份里是上一版内容", "v0", read(ver1));
        assertEquals("v1", read(target));
    }

    @Test
    public void ver1IsAlwaysTheMostRecentPreviousContent() throws Exception {
        overwrite("v1");   // ver1=v0
        overwrite("v2");   // ver1=v1 ver2=v0
        overwrite("v3");   // ver1=v2 ver2=v1 ver3=v0

        List<FileVersionBackup.Version> vs = FileVersionBackup.versions(target);
        assertEquals(3, vs.size());
        assertEquals(1, vs.get(0).index);
        assertEquals("v2", read(vs.get(0).file));
        assertEquals("v1", read(vs.get(1).file));
        assertEquals("v0", read(vs.get(2).file));
        // 版本号升序 = 时间降序，restore(version=N) 才能按这个口径解释
        assertTrue(vs.get(0).lastModified >= vs.get(2).lastModified);
    }

    @Test
    public void rotationKeepsAtMostFiveVersions() throws Exception {
        for (int i = 1; i <= 8; i++) {
            overwrite("v" + i);
        }
        List<FileVersionBackup.Version> vs = FileVersionBackup.versions(target);
        assertEquals("只保留最近 " + FileVersionBackup.MAX_VERSIONS + " 版",
                FileVersionBackup.MAX_VERSIONS, vs.size());
        assertEquals("v7", read(vs.get(0).file));            // 当前是 v8，最近一次被覆盖的是 v7
        assertEquals("v3", read(vs.get(vs.size() - 1).file));
        assertFalse("第 6 版不该存在", FileVersionBackup.versionFile(target, 6).exists());
    }

    @Test
    public void missingOrEmptyFileIsNotBackedUp() throws Exception {
        assertFalse(FileVersionBackup.backup(new File(dir, "does_not_exist.csv")));
        File empty = new File(dir, "empty.csv");
        write(empty, "");
        assertFalse("空文件没必要留底", FileVersionBackup.backup(empty));
        assertTrue(FileVersionBackup.versions(empty).isEmpty());
    }

    // ---------- 恢复 ----------

    @Test
    public void restoreReplacesContentAndKeepsCurrentAsNewVersion() throws Exception {
        overwrite("v1");   // ver1=v0
        overwrite("v2");   // ver1=v1 ver2=v0

        assertNull(FileVersionBackup.restore(target, 1));
        assertEquals("v1", read(target));
        // 恢复前的 v2 也被留底，退错了还能回来
        assertEquals("v2", read(FileVersionBackup.versionFile(target, 1)));
    }

    @Test
    public void restoreIsReversible() throws Exception {
        overwrite("v1");
        overwrite("v2");
        assertNull(FileVersionBackup.restore(target, 1));   // → v1，ver1=v2
        assertNull(FileVersionBackup.restore(target, 1));   // → v2，ver1=v1
        assertEquals("v2", read(target));
    }

    @Test
    public void restoreRejectsOutOfRangeAndMissingVersions() throws Exception {
        overwrite("v1");

        assertNotNull(FileVersionBackup.restore(target, 0));
        assertNotNull(FileVersionBackup.restore(target, FileVersionBackup.MAX_VERSIONS + 1));
        String missing = FileVersionBackup.restore(target, 4);
        assertNotNull(missing);
        assertTrue("缺版本要给可行动文案（指路 versions）: " + missing, missing.contains("versions"));
        assertEquals("被拒绝之后内容不能被改动", "v1", read(target));
    }

    @Test
    public void restoreOfDeletedTargetRecreatesIt() throws Exception {
        overwrite("v1");   // ver1=v0
        //noinspection ResultOfMethodCallIgnored
        target.delete();

        assertNull(FileVersionBackup.restore(target, 1));
        assertTrue("主文件被删后仍能靠备份找回", target.exists());
        assertEquals("v0", read(target));
    }

    // ---------- 命名约定（workspace 靠这个找版本） ----------

    @Test
    public void versionNameKeepsOriginalNameAsPrefix() throws Exception {
        File source = new File(dir, "台账.xlsx");
        File ver = FileVersionBackup.versionFile(source, 2);
        assertEquals("台账.xlsx.ver2", ver.getName());
        // 备份必须与主文件同目录（workspace 靠"在主文件旁边找 .verN"定位，规则一改就找不到）。
        // 不断言分隔符字面量：Windows 本地单测会把 /sdcard/x 规范化成 \sdcard\x，真机不是
        assertEquals(dir.getCanonicalPath(), ver.getParentFile().getCanonicalPath());
    }
}

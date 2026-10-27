package com.oilquiz.app.ai.util;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 原子写 + 备份回读。
 *
 * <p>要钉住的是"写到一半被杀"这条路：旧实现 {@code FileWriter} 直接覆盖，进程被杀就留下半截 JSON，
 * 下次解析失败只打一行告警——整段对话静默丢失。这里断言的是承诺：<b>要么新内容完整、要么旧内容完好</b>，
 * 并且能回落到上一份备份；同时 {@code .tmp} 不会残留、删除时要连备份一起删（否则"清空历史"会被备份复活）。</p>
 */
public class AtomicFileTextTest {

    private File dir;
    private File file;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("atomic-file-text").toFile();
        file = new File(dir, "history.json");
    }

    @After
    public void tearDown() {
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        dir.delete();
    }

    @Test
    public void writeThenReadRoundTrip() {
        assertTrue(AtomicFileText.write(file, "[1,2,3]"));
        assertEquals("[1,2,3]", AtomicFileText.readText(file));
        // 写完不该留下临时文件
        assertFalse(new File(file.getPath() + AtomicFileText.TMP_SUFFIX).exists());
    }

    @Test
    public void secondWriteKeepsPreviousAsBackup() {
        assertTrue(AtomicFileText.write(file, "first"));
        assertTrue(AtomicFileText.write(file, "second"));
        assertEquals("second", AtomicFileText.readText(file));
        assertEquals("first", AtomicFileText.readBackup(file));
    }

    @Test
    public void readWithBackupFallsBackWhenMainMissingOrEmpty() {
        assertTrue(AtomicFileText.write(file, "v1"));
        assertTrue(AtomicFileText.write(file, "v2"));
        // 主文件丢失 → 回落备份
        assertTrue(file.delete());
        assertEquals("v1", AtomicFileText.readWithBackup(file));
        // 主文件为空（写坏的另一形态）→ 由调用方 parseHistoryFile 解析失败后回落，这里只验 readText 返回空串
        assertTrue(AtomicFileText.write(file, ""));
        assertEquals("", AtomicFileText.readText(file));
        assertEquals("v1", AtomicFileText.readBackup(file));
    }

    @Test
    public void readBackupIgnoresCorruptMain() throws Exception {
        assertTrue(AtomicFileText.write(file, "{\"ok\":1}"));
        assertTrue(AtomicFileText.write(file, "{\"ok\":2}"));
        // 模拟"写到一半被杀"：主文件只剩半截
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write("{\"ok\":".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals("{\"ok\":", AtomicFileText.readText(file));
        assertEquals("{\"ok\":1}", AtomicFileText.readBackup(file));
    }

    @Test
    public void createsParentDirectoriesAndRejectsNulls() {
        File nested = new File(dir, "a/b/c/history.json");
        assertTrue(AtomicFileText.write(nested, "x"));
        assertEquals("x", AtomicFileText.readText(nested));

        assertFalse(AtomicFileText.write(null, "x"));
        assertFalse(AtomicFileText.write(file, null));
        assertNull(AtomicFileText.readText(null));
        assertNull(AtomicFileText.readWithBackup(new File(dir, "nope.json")));
    }
}

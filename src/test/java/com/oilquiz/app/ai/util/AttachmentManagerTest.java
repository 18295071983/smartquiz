package com.oilquiz.app.ai.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;

/**
 * 附件落盘文件名清洗（2026-11-10）。
 *
 * <p>背景：旧实现用 {@code [^a-zA-Z0-9._-]} 白名单，中文名会被整条洗成 {@code ____.jpg}，
 * 附件目录和日志里无法对应回用户选的是哪张图；而清洗的真正目的只是"别把目录段带进
 * 文件名、别写出隐藏文件、别用系统保留字符"。这组用例钉住四件事：</p>
 * <ol>
 *   <li>中文/空格/括号等可读字符原样保留；</li>
 *   <li>路径分隔符清洗后 {@code new File(dir, name)} 的父目录必须仍是 dir（不能逃逸）；</li>
 *   <li>"." / ".." / ".jpg" 这类名字补前缀，不产出隐藏文件或目录语义；</li>
 *   <li>超长名截断时保留扩展名，长度不超上限。</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
public class AttachmentManagerTest {

    private static final int MAX_FILE_NAME_LEN = 120;

    @Test
    public void chineseFileNamesSurviveSanitizing() {
        String out = AttachmentManager.sanitizeFileName("期末复习 资料（第1章）.png");

        assertTrue("中文名必须保留，实际: " + out, out.contains("期末复习"));
        assertTrue("扩展名必须保留，实际: " + out, out.endsWith(".png"));
        assertFalse("不应整串被洗成下划线，实际: " + out, out.startsWith("____"));
    }

    @Test
    public void pathSeparatorsCannotEscapeAttachmentDir() {
        File dir = new File("/data/data/com.oilquiz.app/files/attachments");

        for (String raw : new String[]{ "../evil.png", "a/b/c.jpg", "..\\..\\windows.png" }) {
            String out = AttachmentManager.sanitizeFileName(raw);
            assertFalse("不得残留正斜杠: " + out, out.contains("/"));
            assertFalse("不得残留反斜杠: " + out, out.contains("\\"));

            File resolved = new File(dir, out);
            assertEquals("清洗后必须仍落在 attachments 目录内: " + raw + " -> " + out,
                    dir.getPath(), resolved.getParentFile().getPath());
        }
    }

    @Test
    public void controlCharsAndWindowsReservedAreReplaced() {
        String out = AttachmentManager.sanitizeFileName("bad\u0000name:a*b?c\"d<e>f|g.txt");

        assertFalse("不得含 NUL / 冒号 / 通配符等保留字符，实际: " + out,
                out.matches(".*[\\p{Cntrl}:*?\"<>|].*"));
        assertTrue("扩展名必须保留，实际: " + out, out.endsWith(".txt"));
    }

    @Test
    public void dotOnlyAndHiddenNamesGetPrefix() {
        assertEquals("attachment.", AttachmentManager.sanitizeFileName("."));
        assertEquals("attachment..", AttachmentManager.sanitizeFileName(".."));
        String hidden = AttachmentManager.sanitizeFileName(".jpg");
        assertEquals("attachment.jpg", hidden);
        assertFalse("不得产出隐藏文件", hidden.startsWith("."));
    }

    @Test
    public void overlongNamesKeepExtensionWithinLimit() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append('长');
        String raw = sb + ".jpeg";

        String out = AttachmentManager.sanitizeFileName(raw);

        assertTrue("长度必须收敛到上限，实际: " + out.length(), out.length() <= MAX_FILE_NAME_LEN);
        assertTrue("扩展名必须保留，实际: " + out, out.endsWith(".jpeg"));
    }

    @Test
    public void nullOrEmptyFallsBackToAttachment() {
        assertEquals("attachment", AttachmentManager.sanitizeFileName(null));
        assertEquals("attachment", AttachmentManager.sanitizeFileName(""));
        assertEquals("attachment", AttachmentManager.sanitizeFileName("   "));
    }
}

package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.oilquiz.app.testsupport.ThemedAppContext;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 手机 CLI（2026-11-18，P1 只读子集）。
 *
 * <p>钉住：argv 解析、退出码、help/version、tools list/show、未知命令。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class PhoneCliTest {

    private Context ctx() {
        return ThemedAppContext.get();
    }

    @Test
    public void argvSplitHonorsQuotes() {
        String[] a = PhoneCli.split("ui find --text=\"保存 成功\" --json");
        assertEquals(4, a.length);
        assertEquals("ui", a[0]);
        assertEquals("--text=保存 成功", a[2]);
        assertEquals("--json", a[3]);
    }

    @Test
    public void emptyCommandIsUsageError() {
        PhoneCli.Result r = PhoneCli.exec(ctx(), "   ");
        assertEquals(2, r.code);
        assertTrue(r.stdout.contains("phone-cli"));
    }

    @Test
    public void helpAndVersion() {
        PhoneCli.Result h = PhoneCli.exec(ctx(), "help");
        assertEquals(0, h.code);
        assertTrue(h.stdout.contains("ui dump"));

        PhoneCli.Result v = PhoneCli.exec(ctx(), "version");
        assertEquals(0, v.code);
        assertTrue(v.stdout.contains("phone-cli"));
    }

    @Test
    public void toolsListAndShow() {
        PhoneCli.Result list = PhoneCli.exec(ctx(), "tools list");
        assertEquals(0, list.code);
        assertTrue("工具列表应包含 ui_component", list.stdout.contains("ui_component"));

        PhoneCli.Result show = PhoneCli.exec(ctx(), "tools show ui_component");
        assertEquals(0, show.code);
        assertTrue(show.stdout.contains("name: ui_component"));

        PhoneCli.Result missing = PhoneCli.exec(ctx(), "tools show 不存在的工具");
        assertEquals(1, missing.code);
    }

    @Test
    public void unknownCommandIsUsageError() {
        PhoneCli.Result r = PhoneCli.exec(ctx(), "rm -rf /");
        assertEquals(2, r.code);
        assertTrue(r.stderr.contains("未知命令"));
    }

    @Test
    public void logcatIsExplicitlyUnsupported() {
        PhoneCli.Result r = PhoneCli.exec(ctx(), "logcat --lines 10");
        assertEquals(1, r.code);
        assertTrue(r.stderr.contains("READ_LOGS"));
    }
}

package com.oilquiz.app.ai.chat.parts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** 终端 ANSI SGR（2026-11-18，第三批）：颜色/加粗/重置，其它 CSI 丢弃。 */
@RunWith(RobolectricTestRunner.class)
public class AnsiSgrTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = com.oilquiz.app.testsupport.ThemedAppContext.get();
    }

    @Test
    public void stripsSgrAndKeepsText() {
        CharSequence out = AnsiSgr.render(ctx, "\u001b[31merror\u001b[0m ok");
        assertEquals("error ok", out.toString());
        assertTrue("红色要有前景 Span",
                ((Spanned) out).getSpans(0, out.length(), ForegroundColorSpan.class).length > 0);
    }

    @Test
    public void boldIsKept() {
        CharSequence out = AnsiSgr.render(ctx, "\u001b[1mimportant\u001b[0m");
        assertEquals("important", out.toString());
        assertTrue(((Spanned) out).getSpans(0, out.length(), StyleSpan.class).length > 0);
    }

    @Test
    public void nonSgrCsiIsDropped() {
        assertEquals("abc", AnsiSgr.render(ctx, "a\u001b[2Kb\u001b[?25lc").toString());
    }

    @Test
    public void plainTextUntouched() {
        assertEquals("plain", AnsiSgr.render(ctx, "plain").toString());
    }
}

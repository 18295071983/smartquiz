package com.oilquiz.app.ai.chat.parts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** 代码着色（2026-11-18）：四类 token + HTML/XML 标签名。 */
@RunWith(RobolectricTestRunner.class)
public class CodeHighlighterTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = com.oilquiz.app.testsupport.ThemedAppContext.get();
    }

    @Test
    public void htmlTagsGetSpanAndTextIsIntact() {
        CharSequence out = CodeHighlighter.highlight(ctx, "<h1 class=\"x\">Hi</h1>", "html");
        assertEquals("<h1 class=\"x\">Hi</h1>", out.toString());
        assertTrue("标签名要有前景 Span",
                ((Spanned) out).getSpans(0, out.length(), ForegroundColorSpan.class).length > 0);
    }

    @Test
    public void plainTextHasNoSpans() {
        CharSequence out = CodeHighlighter.highlight(ctx, "hello world", "text");
        assertEquals("hello world", out.toString());
        assertEquals(0, ((Spanned) out).getSpans(0, out.length(), ForegroundColorSpan.class).length);
    }
}

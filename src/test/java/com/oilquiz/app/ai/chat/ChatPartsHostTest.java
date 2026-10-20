package com.oilquiz.app.ai.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * ChatPartsHost（P3a 消费端环境映射）：只验纯访问器与回调路由，不触发 renderMarkdown
 * （那条走 ChatAdapter 后台渲染线程 + Markwon，属 P3b 真机验证范畴）。
 */
@RunWith(RobolectricTestRunner.class)
public class ChatPartsHostTest {

    private Context ctx;
    private RecordingCallback cb;
    private ChatPartsHost host;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        cb = new RecordingCallback();
        host = new ChatPartsHost(ctx, cb);
    }

    @Test
    public void accessorsReflectSetters() {
        host.setAvailableWidth(900);
        host.setWebMarkdownEnabled(true);
        ChatMessage msg = ChatMessage.createAIMessage("a", "x", 1L);
        host.setMessage(msg);

        assertSame(ctx, host.context());
        assertEquals(900, host.availableWidth());
        assertTrue(host.webMarkdownEnabled());
        assertSame(msg, host.message());
        assertFalse("裸 Application 默认非夜间", host.dark());
        assertNotNull(host.markdownThemeVars());
    }

    @Test
    public void decisionsAndInvalidationRouteToCallback() {
        host.onApprovalDecision("p1", "c1", true);
        host.invalidateSize();
        assertEquals("p1|c1|true", cb.lastDecision);
        assertTrue(cb.invalidated);
    }

    private static final class RecordingCallback implements ChatPartsHost.Callback {
        String lastDecision;
        boolean invalidated;
        @Override
        public void onApprovalDecision(String partId, String toolCallId, boolean approved) {
            lastDecision = partId + "|" + toolCallId + "|" + approved;
        }
        @Override
        public void onPartsContentChanged() {
            invalidated = true;
        }
    }
}

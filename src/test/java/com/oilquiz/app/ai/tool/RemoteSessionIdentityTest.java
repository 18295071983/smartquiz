package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 会话身份（2026-11-12 v2）：跟随会话与直连会话分开。
 *
 * <p>{@code attached_session}（跟随，聊天/续写/事件流）与 {@code direct_session}
 * （{@code @direct}，手机专属直连）互不覆盖；旧键自动迁移，但不会再被"选择会话"污染。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class RemoteSessionIdentityTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences("remote_dsh_config", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void legacySyncTargetIsMigratedToFollow() {
        ctx.getSharedPreferences("remote_dsh_config", Context.MODE_PRIVATE)
                .edit().putString("sync_target", "session-old").commit();

        assertEquals("session-old", RemoteDshTool.canonicalSessionId(ctx));
        assertEquals("session-old", RemoteDshTool.getSyncTarget(ctx));
        // 旧 sync_target 只算跟随会话，不再被当成直连会话
        assertEquals("", RemoteDshTool.getDirectSessionId(ctx));
    }

    @Test
    public void followAndDirectAreIndependent() {
        RemoteDshTool.setCanonicalSession(ctx, "session-follow", "跟随标题");
        RemoteDshTool.setDirectSession(ctx, "session-direct", "直连标题");

        assertEquals("session-follow", RemoteDshTool.getSyncTarget(ctx));
        assertEquals("跟随标题", RemoteDshTool.getSyncTitle(ctx));
        assertEquals("session-direct", RemoteDshTool.getDirectSessionId(ctx));
        assertEquals("直连标题", RemoteDshTool.getDirectTitle(ctx));
    }

    @Test
    public void switchingFollowDoesNotTouchDirect() {
        RemoteDshTool.setDirectSession(ctx, "session-direct", "直连");
        RemoteDshTool.setCanonicalSession(ctx, "session-a", "A");
        RemoteDshTool.setCanonicalSession(ctx, "session-b", "B");

        assertEquals("session-b", RemoteDshTool.getSyncTarget(ctx));
        assertEquals("session-direct", RemoteDshTool.getDirectSessionId(ctx));
    }

    @Test
    public void legacyDshSessionCountsAsDirectOnlyWhenDifferent() {
        // 老数据：dsh_session_id 曾经被选择会话覆盖，跟跟随会话相同 -> 不算直连
        RemoteDshTool.setCanonicalSession(ctx, "session-follow", "跟随");
        ctx.getSharedPreferences("remote_dsh_config", Context.MODE_PRIVATE)
                .edit().putString("dsh_session_id", "session-follow").commit();
        assertEquals("", RemoteDshTool.getDirectSessionId(ctx));

        // 与跟随不同 -> 认作直连（迁移）
        ctx.getSharedPreferences("remote_dsh_config", Context.MODE_PRIVATE)
                .edit().putString("dsh_session_id", "session-direct-old").commit();
        assertEquals("session-direct-old", RemoteDshTool.getDirectSessionId(ctx));
    }

    @Test
    public void changingDirectCodeKeepsFollow() {
        RemoteDshTool.setCanonicalSession(ctx, "session-follow", "跟随");
        RemoteDshTool.setDirectSession(ctx, "session-direct", "直连");

        RemoteDshTool.setDirectCode(ctx, "work");

        assertEquals("session-follow", RemoteDshTool.getSyncTarget(ctx));
        assertEquals("", RemoteDshTool.getDirectSessionId(ctx));
    }

    @Test
    public void transportCursorIsPerSession() {
        RemoteDshTool.setTransportSeq(ctx, "session-a", 12);
        assertEquals(12, RemoteDshTool.getTransportSeq(ctx, "session-a"));
        assertEquals(-1, RemoteDshTool.getTransportSeq(ctx, "session-b"));
    }
}

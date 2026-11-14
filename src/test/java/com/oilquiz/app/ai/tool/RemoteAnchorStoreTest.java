package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 锚点账本（A3，2026-11-14）：{@code hello} 帧下发的 anchor 入账后，
 * "建本地会话"要能按（设备 × 电脑会话）把它**查**回来。
 *
 * <p>锚点是二维键：同一台手机上 A 会话和 B 会话各记各的，切会话不能串；
 * 清历史后整本账要能清干净，等着服务端重签。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class RemoteAnchorStoreTest {

    private static final String PREFS = "remote_anchors";

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
    }

    /** 造一条插件格式的锚点（local_session_id 是 randomBytes(8).toString('hex')）。 */
    private static JSONObject anchor(String local, String remote) throws Exception {
        JSONObject a = new JSONObject();
        a.put("local_session_id", local);
        a.put("remote_session_id", remote);
        a.put("acked_seq", 9774);
        return a;
    }

    @Test
    public void helloAnchorIsQueryableByRemoteSession() throws Exception {
        RemoteAnchorStore.save(ctx, anchor("3ae6d7fa9c1b2d4e", "session-e570"));

        assertEquals("当前跟随的锚点要能直接取到本机会话 id",
                "3ae6d7fa9c1b2d4e", RemoteAnchorStore.currentLocalSessionId(ctx));
        assertEquals("按电脑会话查 → 本机会话 id",
                "3ae6d7fa9c1b2d4e",
                RemoteAnchorStore.localSessionIdForRemote(ctx, "session-e570"));
        assertNull("别的电脑会话没有锚点", RemoteAnchorStore.byRemote(ctx, "session-other"));
        assertEquals("查不到返回空串（调用方才好退回新建）",
                "", RemoteAnchorStore.localSessionIdForRemote(ctx, "session-other"));
    }

    /** 一台手机多条电脑会话：各自一条锚点，互不参合。 */
    @Test
    public void twoRemoteSessionsKeepTheirOwnLocalIds() throws Exception {
        RemoteAnchorStore.save(ctx, anchor("aaaaaaaaaaaaaaaa", "session-a"));
        RemoteAnchorStore.save(ctx, anchor("bbbbbbbbbbbbbbbb", "session-b"));

        assertEquals("aaaaaaaaaaaaaaaa",
                RemoteAnchorStore.localSessionIdForRemote(ctx, "session-a"));
        assertEquals("bbbbbbbbbbbbbbbb",
                RemoteAnchorStore.localSessionIdForRemote(ctx, "session-b"));
        assertEquals("后一条是当前跟随", "bbbbbbbbbbbbbbbb",
                RemoteAnchorStore.currentLocalSessionId(ctx));
    }

    /** 残帧不能污染账本：缺电脑会话 id 的不入账；缺本机 id 的查出来是空串（调用方当"没有"处理）。 */
    @Test
    public void malformedAnchorIsIgnored() throws Exception {
        RemoteAnchorStore.save(ctx, null);                              // 空帧不能崩
        RemoteAnchorStore.save(ctx, anchor("dddddddddddddddd", ""));    // 缺 remote_session_id
        assertNull("缺电脑会话 id 的锚点不入账", RemoteAnchorStore.current(ctx));

        RemoteAnchorStore.save(ctx, anchor("", "session-x"));           // 缺 local_session_id
        assertEquals("缺本机 id 时调用方按「没有锚点」处理", "",
                RemoteAnchorStore.localSessionIdForRemote(ctx, "session-x"));
    }

    /** 清历史（A4 的前置）：整本账清干净，服务端重签后按新 id 建会话。 */
    @Test
    public void clearWipesLedgerSoServerCanReSign() throws Exception {
        RemoteAnchorStore.save(ctx, anchor("cccccccccccccccc", "session-c"));

        RemoteAnchorStore.clear(ctx);

        assertNull("当前锚点应清空", RemoteAnchorStore.current(ctx));
        assertNull(RemoteAnchorStore.byRemote(ctx, "session-c"));
        assertEquals("", RemoteAnchorStore.localSessionIdForRemote(ctx, "session-c"));

        RemoteAnchorStore.save(ctx, anchor("eeeeeeeeeeeeeeee", "session-c"));
        assertNotNull("重签后应能重新入账", RemoteAnchorStore.current(ctx));
        assertEquals("eeeeeeeeeeeeeeee",
                RemoteAnchorStore.localSessionIdForRemote(ctx, "session-c"));
    }
}

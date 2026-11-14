package com.oilquiz.app.ai.chat.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.ai.tool.RemoteDshTool;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 远程三态判定（2026-11-14，M8-P0-1）。
 *
 * <p>为什么要钉这个类：外壳所有远程化点都从它分叉 —— 判错了会出现"配对过但没连，
 * 顶栏却写电脑在跑"。边界最容易踩的有两条：</p>
 * <ul>
 *   <li>{@code isConnected} 在"没有 connected 标记的老配置"上默认 true（升级不突然断），
 *       所以"已配对未连接"必须靠显式 false 造；</li>
 *   <li>只有地址没有令牌不算配对（令牌是鉴权的一半）。</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class ChatRemoteModeTest {

    /** 与 RemoteDshTool 的存储契约一致：文件名与键名都写死在这里，改了测试就报 */
    private static final String PREF = "remote_dsh_config";
    private static final String KEY_URL = "base_url";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_CONNECTED = "connected";

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        prefs().edit().clear().commit();
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private void pair(String url, String token) {
        SharedPreferences.Editor e = prefs().edit();
        if (url == null) e.remove(KEY_URL); else e.putString(KEY_URL, url);
        if (token == null) e.remove(KEY_TOKEN); else e.putString(KEY_TOKEN, token);
        e.commit();
    }

    @Test
    public void freshInstallIsLocal() {
        assertFalse(ChatRemoteMode.isConfigured(ctx));
        assertFalse(ChatRemoteMode.isConnected(ctx));
        assertFalse(ChatRemoteMode.isActive(ctx));
        assertFalse(ChatRemoteMode.isDisconnected(ctx));
    }

    @Test
    public void pairedAndConnectedIsRemote() {
        pair("http://192.168.1.2:8218", "tok");
        prefs().edit().putBoolean(KEY_CONNECTED, true).commit();
        assertTrue(ChatRemoteMode.isConfigured(ctx));
        assertTrue(ChatRemoteMode.isActive(ctx));
        assertFalse("连上了就不该算断开", ChatRemoteMode.isDisconnected(ctx));
    }

    @Test
    public void pairedButDisconnectedIsNotRemote() {
        pair("http://192.168.1.2:8218", "tok");
        prefs().edit().putBoolean(KEY_CONNECTED, false).commit();
        assertTrue(ChatRemoteMode.isConfigured(ctx));
        assertFalse("断开了不能按远程渲染", ChatRemoteMode.isActive(ctx));
        assertTrue(ChatRemoteMode.isDisconnected(ctx));
    }

    @Test
    public void legacyConfigWithoutConnectedFlagCountsAsConnected() {
        // 老配置没有 connected 标记：升级上来的用户不该突然"用不了"
        pair("http://192.168.1.2:8218", "tok");
        assertTrue(ChatRemoteMode.isActive(ctx));
    }

    @Test
    public void urlWithoutTokenIsNotPaired() {
        pair("http://192.168.1.2:8218", null);
        assertFalse(ChatRemoteMode.isConfigured(ctx));
        assertFalse(ChatRemoteMode.isActive(ctx));
        assertFalse(ChatRemoteMode.isDisconnected(ctx));
    }

    @Test
    public void blankTokenIsNotPaired() {
        pair("http://192.168.1.2:8218", "   ");
        assertFalse("空令牌不算配对", ChatRemoteMode.isConfigured(ctx));
    }

    @Test
    public void disconnectApiFlipsToDisconnected() {
        pair("http://192.168.1.2:8218", "tok");
        RemoteDshTool.disconnect(ctx);
        assertTrue(ChatRemoteMode.isConfigured(ctx));
        assertFalse(ChatRemoteMode.isActive(ctx));
        assertTrue(ChatRemoteMode.isDisconnected(ctx));
    }
}

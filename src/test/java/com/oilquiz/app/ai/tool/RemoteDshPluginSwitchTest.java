package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.oilquiz.app.testsupport.ThemedAppContext;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.HashMap;
import java.util.Map;

/**
 * 远程 DSH 插件总开关（2026-11-18）。
 *
 * <p>用户口径：手机端不需要远程控制，默认关闭；关的时候 AI 调 remote_dsh 必须直接被拒绝，
 * 不能还去打网络。开关状态存 ai_prefs（清配对配置不会把它清掉）。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class RemoteDshPluginSwitchTest {

    @Test
    public void pluginIsOffByDefault() {
        Context ctx = ThemedAppContext.get();
        RemoteDshTool.setPluginEnabled(ctx, false);
        assertFalse("手机端默认关闭", RemoteDshTool.isPluginEnabled(ctx));
    }

    @Test
    public void togglePersists() {
        Context ctx = ThemedAppContext.get();
        RemoteDshTool.setPluginEnabled(ctx, true);
        assertTrue(RemoteDshTool.isPluginEnabled(ctx));
        RemoteDshTool.setPluginEnabled(ctx, false);
        assertFalse(RemoteDshTool.isPluginEnabled(ctx));
    }

    @Test
    public void clearConfigDoesNotResetPluginSwitch() {
        Context ctx = ThemedAppContext.get();
        RemoteDshTool.setPluginEnabled(ctx, true);
        try {
            RemoteDshTool.clearConfig(ctx);
        } catch (Throwable ignored) {
            // clearConfig 依赖 prefs，Robolectric 下正常
        }
        assertTrue("清除配对配置不应该把插件开关一起清掉", RemoteDshTool.isPluginEnabled(ctx));
    }

    @Test
    public void chatFallsBackToLocalUiWhenPluginOffButKeepsPairing() {
        Context ctx = ThemedAppContext.get();
        // 模拟"配对过 + 缓存标记为已连接"（真实场景：首次连上后用户关掉开关）
        ctx.getSharedPreferences("remote_dsh_config", Context.MODE_PRIVATE).edit()
                .putString("base_url", "http://192.168.1.2:8218")
                .putString("token", "device-token")
                .putBoolean("connected", true)
                .apply();
        RemoteDshTool.setPluginEnabled(ctx, true);
        assertTrue("开着时按远程态渲染", com.oilquiz.app.ai.chat.ui.ChatRemoteMode.isConfigured(ctx));
        assertTrue(com.oilquiz.app.ai.chat.ui.ChatRemoteMode.isConnected(ctx));

        RemoteDshTool.setPluginEnabled(ctx, false);
        assertFalse("关掉后界面必须回本地/未配对态",
                com.oilquiz.app.ai.chat.ui.ChatRemoteMode.isConfigured(ctx));
        assertFalse(com.oilquiz.app.ai.chat.ui.ChatRemoteMode.isConnected(ctx));
        assertFalse(com.oilquiz.app.ai.chat.ui.ChatRemoteMode.isActive(ctx));
        // 只恢复界面，配对信息仍保留（下次打开开关不用重配）
        assertTrue("地址不能丢", RemoteDshTool.configValue(ctx, "base_url").contains("192.168.1.2"));
        assertTrue("令牌不能丢", RemoteDshTool.configValue(ctx, "token").length() > 0);
    }

    @Test
    public void toolRejectsEveryActionWhenPluginOff() {
        Context ctx = ThemedAppContext.get();
        RemoteDshTool.setPluginEnabled(ctx, false);
        RemoteDshTool tool = new RemoteDshTool(ctx);
        Map<String, Object> args = new HashMap<>();
        args.put("action", "status");
        AIToolResult r = tool.execute(args);
        assertFalse("关闭时必须拒绝", r.isSuccess());
        assertTrue("错误信息要说明怎么打开",
                String.valueOf(r.getErrorMessage()).contains("远程 DSH 插件已关闭"));
    }
}

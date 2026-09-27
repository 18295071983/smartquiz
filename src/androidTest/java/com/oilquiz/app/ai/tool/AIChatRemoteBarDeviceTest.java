package com.oilquiz.app.ai.tool;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.oilquiz.app.R;
import com.oilquiz.app.ui.activity.AIChatActivity;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 真机验证：**聊天页顶部有「电脑连接」常驻状态条**（用户反馈 2026-09-27：
 * "AI 对话界面没有任何 UI 提示，只能在对话流中显示"）。
 *
 * <p>三种状态都要在界面上看得出来：未配对（红）/ 已连接（绿）/ 已断开（黄），点一下进连接界面。
 * 聊天页 exported=false，只能由应用内启动，所以这里用插桩拉起真实页面读控件文本。
 *
 * 运行：
 *   adb shell am instrument -w -e class com.oilquiz.app.ai.tool.AIChatRemoteBarDeviceTest \
 *       -e publicUrl https://<花生壳域名> -e token <令牌> \
 *       com.oilquiz.app.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4.class)
public class AIChatRemoteBarDeviceTest {

    private static Context ctx() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static void pairFromArgs(Context c) throws Exception {
        android.os.Bundle args = InstrumentationRegistry.getArguments();
        String publicUrl = args.getString("publicUrl");
        String token = args.getString("token");
        if (publicUrl != null && token != null) {
            String host = new java.net.URL(publicUrl).getHost();
            RemoteDshPairBridge.parseAndSave(c,
                    "dshpair://" + host + "?scheme=https&port=443&token=" + token);
        }
    }

    /** 拉起聊天页并返回状态条文本；启动被 MIUI 拦截时返回 null（调用方跳过） */
    private static String openChatAndReadBar(Instrumentation inst) throws Exception {
        try {
            Intent main = new Intent(ctx(), com.oilquiz.app.MainActivity.class);
            main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            inst.startActivitySync(main);
            Thread.sleep(1200);
        } catch (Exception ignored) {
        }
        Intent i = new Intent(ctx(), AIChatActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity a;
        try {
            a = inst.startActivitySync(i);
        } catch (RuntimeException e) {
            System.out.println("[EXP] 聊天页启动被拦截: " + e.getMessage());
            return null;
        }
        TextView bar = a.findViewById(R.id.remote_dsh_text);
        TextView action = a.findViewById(R.id.remote_dsh_action);
        assertNotNull("聊天页应有「电脑连接」状态条 remote_dsh_text", bar);
        assertNotNull("状态条应有隐藏按钮 remote_dsh_hide", a.findViewById(R.id.remote_dsh_hide));
        String text = String.valueOf(bar.getText());
        System.out.println("[EXP] 聊天页状态条: " + text + "  |  右侧按钮=" + (action != null ? action.getText() : "?"));
        android.os.Bundle args = InstrumentationRegistry.getArguments();
        if ("1".equals(args.getString("uiShot"))) {
            System.out.println("[EXP] uiShot=1，聊天页停留 12 秒供截图");
            Thread.sleep(12000);
        }
        return text;
    }

    /**
     * 开关：关掉状态条后聊天页不应再显示（用户要求「不要一直显示」）；打开后恢复。
     */
    @Test
    public void chatBarFollowsSwitch() throws Exception {
        Context c = ctx();
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        pairFromArgs(c);

        RemoteDshTool.setBarEnabled(c, false);
        String off = openChatAndReadBarVisibility(inst);
        Assume.assumeTrue("聊天页无法启动（MIUI 限制），跳过", off != null);
        System.out.println("[EXP] 开关关闭时状态条可见性 = " + off);
        assertTrue("关掉开关后状态条应隐藏（GONE）: " + off, off.contains("GONE"));

        RemoteDshTool.setBarEnabled(c, true);
        String on = openChatAndReadBarVisibility(inst);
        System.out.println("[EXP] 开关打开时状态条可见性 = " + on);
        assertTrue("打开开关后状态条应可见: " + on, on.contains("VISIBLE"));
    }

    /** 拉起聊天页，返回状态条 View 的可见性字符串（启动失败返回 null） */
    private static String openChatAndReadBarVisibility(Instrumentation inst) throws Exception {
        try {
            Intent main = new Intent(ctx(), com.oilquiz.app.MainActivity.class);
            main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            inst.startActivitySync(main);
            Thread.sleep(1200);
        } catch (Exception ignored) {
        }
        Intent i = new Intent(ctx(), AIChatActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity a;
        try {
            a = inst.startActivitySync(i);
        } catch (RuntimeException e) {
            System.out.println("[EXP] 聊天页启动被拦截: " + e.getMessage());
            return null;
        }
        android.view.View bar = a.findViewById(R.id.remote_dsh_bar);
        assertNotNull("聊天页应有状态条容器 remote_dsh_bar", bar);
        return bar.getVisibility() == android.view.View.GONE ? "GONE"
                : (bar.getVisibility() == android.view.View.VISIBLE ? "VISIBLE" : "其他");
    }

    @Test
    public void chatPageShowsRemoteConnectionBar() throws Exception {
        Context c = ctx();
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        RemoteDshTool.setBarEnabled(c, true);   // 与开关用例解耦：本用例只验内容，不验开关

        // 1) 未配对状态：应显示"电脑未配对"（红色点 + 去配对）
        RemoteDshTool.clearConfig(c);
        String unpaired = openChatAndReadBar(inst);
        Assume.assumeTrue("聊天页无法启动（MIUI 限制），跳过", unpaired != null);
        assertTrue("未配对时状态条应提示未配对: " + unpaired, unpaired.contains("未配对"));

        // 2) 配对/连接后：应显示"电脑已连接 + 地址"
        pairFromArgs(c);
        assertTrue("配对后应为已连接", RemoteDshTool.isConnected(c));
        String connected = openChatAndReadBar(inst);
        assertTrue("已连接时状态条应显示已连接: " + connected, connected.contains("已连接"));
        assertTrue("已连接时应带出电脑地址: " + connected, connected.contains("http"));

        // 3) 断开后：应显示"电脑已断开"
        RemoteDshTool.disconnect(c);
        String disconnected = openChatAndReadBar(inst);
        assertTrue("断开时状态条应显示已断开: " + disconnected, disconnected.contains("已断开"));
    }
}

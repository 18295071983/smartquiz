package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 真机验证"手机 App 能不能连上电脑端 dsh 桥接"。
 *
 * 为什么要专门有这个测试：用 adb shell 里的 nc 能连通，并不代表 **App 自己的 uid/网络栈** 能连通
 * （Android 有 per-uid 路由与默认网络选择，历史上"Wi-Fi 无外网时 App 流量走蜂窝"就会连不上局域网设备）。
 * 所以这里直接用 App 的进程、App 保存的 base_url/token 去打 /health 与真实工具路径 get_status。
 *
 * 运行：
 *   adb shell am instrument -w -e class com.oilquiz.app.ai.tool.RemoteDshBridgeDeviceTest com.oilquiz.app.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4.class)
public class RemoteDshBridgeDeviceTest {

    @Test
    public void bridgeReachableFromAppUid() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences sp = ctx.getSharedPreferences("remote_dsh_config", Context.MODE_PRIVATE);
        String baseUrl = sp.getString("base_url", "");
        String token = sp.getString("token", "");
        System.out.println("[EXP] 已保存配对配置 => base_url=" + baseUrl
                + "  token=" + (token.isEmpty() ? "(空)" : token.substring(0, Math.min(6, token.length())) + "..."));
        assertFalse("App 里还没有配对配置（base_url 为空）", baseUrl.isEmpty());

        // 1) App 的 uid / App 的网络栈直接访问桥接 /health
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + "/health").openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        int code = c.getResponseCode();
        String body = readAll(c);
        long ms = System.currentTimeMillis() - t0;
        System.out.println("[EXP] app-uid GET /health => HTTP " + code + "  " + ms + "ms  " + body);
        assertEquals("App 进程应能连上桥接 /health", 200, code);

        // 2) 走工具真实代码路径（get_status -> /status + Bearer token）
        Map<String, Object> p = new HashMap<>();
        p.put("action", "get_status");
        t0 = System.currentTimeMillis();
        AIToolResult r = new RemoteDshTool(ctx).execute(p);
        ms = System.currentTimeMillis() - t0;
        System.out.println("[EXP] remote_dsh get_status => success=" + r.isSuccess() + "  " + ms + "ms");
        System.out.println("[EXP]   result=" + r.getResult());
        System.out.println("[EXP]   error=" + r.getErrorMessage());
        assertTrue("get_status 应成功（连不上会报 10s 连接超时）: " + r.getErrorMessage(), r.isSuccess());
        assertTrue("get_status 结果里应显示桥接在线: " + r.getResult(),
                String.valueOf(r.getResult()).contains("桥接服务在线"));

        // ACP 通道单独核对：探测偶发抖动时重试几次再判定（bridge 侧也已加了重试）
        boolean acpOk = false;
        for (int i = 0; i < 3 && !acpOk; i++) {
            HttpURLConnection s = (HttpURLConnection) new URL(baseUrl + "/status").openConnection();
            s.setRequestProperty("Authorization", "Bearer " + token);
            s.setConnectTimeout(10000);
            s.setReadTimeout(20000);
            String stBody = s.getResponseCode() == 200 ? readAll(s) : "";
            acpOk = stBody.contains("\"session_acp\": true") || stBody.contains("\"session_acp\":true");
            System.out.println("[EXP] /status 第 " + (i + 1) + " 次 => " + stBody);
            if (!acpOk) Thread.sleep(800);
        }
        assertTrue("ACP 通道应可用（bridge 的 /acp/healthz 探测）", acpOk);
    }

    /**
     * 公网/隧道形态：既验证新的配对码解析（dshpair://host?scheme=https&port=443&token=…），
     * 也验证 App 的 uid 能真的走隧道打到电脑（用 -e publicUrl/-e token 传参）。
     */
    @Test
    public void publicTunnelParsingAndReachability() throws Exception {
        android.os.Bundle args = InstrumentationRegistry.getArguments();
        String publicUrl = args.getString("publicUrl");
        String token = args.getString("token");
        org.junit.Assume.assumeTrue("需要 -e publicUrl=… -e token=… 参数", publicUrl != null && token != null);

        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();

        // 1) 配对码解析：隧道地址是 https 默认端口（URL 里没有端口），旧解析会因 port<=0 失败
        String host = new java.net.URL(publicUrl).getHost();
        String qr = "dshpair://" + host + "?scheme=https&port=443&token=" + token;
        String err = RemoteDshPairBridge.parseAndSave(ctx, qr);
        System.out.println("[EXP] 隧道配对码解析 => err=" + err + "  qr=" + qr);
        assertNull("隧道形态配对码应能解析: " + err, err);
        String saved = ctx.getSharedPreferences("remote_dsh_config", Context.MODE_PRIVATE)
                .getString("base_url", "");
        System.out.println("[EXP] 解析后保存的 base_url => " + saved);
        assertEquals("https 默认端口不应拼 :443", publicUrl, saved);

        // 2) App uid 直接打公网隧道 /health
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new java.net.URL(publicUrl + "/health").openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        int code = c.getResponseCode();
        String body = readAll(c);
        System.out.println("[EXP] 公网隧道 /health => HTTP " + code + "  " + (System.currentTimeMillis() - t0) + "ms  " + body);
        assertEquals("App 进程应能通过公网隧道访问桥接", 200, code);

        // 3) 真实工具路径（get_status）
        Map<String, Object> p = new HashMap<>();
        p.put("action", "get_status");
        t0 = System.currentTimeMillis();
        AIToolResult r = new RemoteDshTool(ctx).execute(p);
        System.out.println("[EXP] 公网隧道 get_status => success=" + r.isSuccess() + "  "
                + (System.currentTimeMillis() - t0) + "ms");
        System.out.println("[EXP]   result=" + r.getResult());
        System.out.println("[EXP]   error=" + r.getErrorMessage());
        assertTrue("公网隧道下 get_status 应成功: " + r.getErrorMessage(), r.isSuccess());
        assertTrue("公网隧道下桥接应在线: " + r.getResult(), String.valueOf(r.getResult()).contains("桥接服务在线"));
    }

    private static String readAll(HttpURLConnection c) throws Exception {
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        }
    }
}

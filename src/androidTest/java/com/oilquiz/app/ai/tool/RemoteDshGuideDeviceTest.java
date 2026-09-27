package com.oilquiz.app.ai.tool;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 真机验证「怎么用 / 电脑端怎么配」教程页与**电脑端程序导出**（2026-09-27 用户要求：
 * 新用户不会配置，要教用户怎么用、电脑端怎么配）。
 *
 * <p>关键点：光有文档不够——桥接脚本用户根本拿不到。所以 App 必须能把
 * start_dsh_bridge.bat / .sh、dsh_bridge_server.py、qrcodegen.js、README.md 导出到手机，
 * 用户拷到电脑上双击即可。
 */
@RunWith(AndroidJUnit4.class)
public class RemoteDshGuideDeviceTest {

    private static Context ctx() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    /** 教程页布局能按主题 inflate，关键控件齐全 */
    @Test
    public void guideLayoutInflates() {
        Context base = ctx();
        Context themed = new android.view.ContextThemeWrapper(base, base.getApplicationInfo().theme);
        android.view.View root = android.view.LayoutInflater.from(themed)
                .inflate(com.oilquiz.app.R.layout.activity_remote_dsh_guide, null);
        assertNotNull("教程页布局应能 inflate", root);
        int[] ids = {
                com.oilquiz.app.R.id.btn_export,
                com.oilquiz.app.R.id.export_result,
                com.oilquiz.app.R.id.btn_goto_connect
        };
        for (int id : ids) {
            assertNotNull("教程页缺少控件: " + base.getResources().getResourceEntryName(id),
                    root.findViewById(id));
        }
        System.out.println("[EXP] 教程页布局 inflate 成功，控件齐全");
    }

    /** 导出电脑端程序：5 个文件都要真的写到公共下载目录，且非空 */
    @Test
    public void exportBridgeFilesWritesAllFiles() throws Exception {
        Context c = ctx();
        String dir = RemoteDshTool.exportBridgeFiles(c);
        System.out.println("[EXP] 导出目录 = " + dir);
        assertTrue("导出目录应存在: " + dir, new File(dir).isDirectory());
        String[] names = {"start_dsh_bridge.bat", "start_dsh_bridge.sh",
                "dsh_bridge_server.py", "pair_page.html", "qrcodegen.js", "README.md"};
        StringBuilder bad = new StringBuilder();
        for (String n : names) {
            File f = new File(dir, n);
            long len = f.isFile() ? f.length() : -1;
            System.out.println("[EXP]   " + n + " => " + (len < 0 ? "缺失" : len + " 字节"));
            if (len <= 0) {
                bad.append(bad.length() == 0 ? "" : ", ").append(n);
            }
        }
        assertTrue("以下文件导出失败: " + bad, bad.length() == 0);
        // 桥接脚本要够大（防止只导出了空壳）
        assertTrue("dsh_bridge_server.py 应 >10KB",
                new File(dir, "dsh_bridge_server.py").length() > 10_000);

        // 回归（实测踩到的坑）：启动脚本必须纯 ASCII。
        // Windows cmd 按"字节偏移"重读 .bat，脚本里 chcp 65001 之后若还有多字节字符，
        // 解析会撕裂——REM 注释的中文碎片会被当命令执行，脚本直接崩。
        byte[] bat = readAll(new File(dir, "start_dsh_bridge.bat"));
        int nonAscii = 0;
        for (byte x : bat) {
            if ((x & 0xFF) > 127) nonAscii++;
        }
        System.out.println("[EXP] start_dsh_bridge.bat 字节数=" + bat.length + "，非 ASCII 字节=" + nonAscii);
        assertEquals("启动脚本必须纯 ASCII（否则 Windows 下 cmd 解析会撕裂）", 0, nonAscii);
        // 同时确认它真的会拉起两个服务
        String batText = new String(bat, java.nio.charset.StandardCharsets.US_ASCII);
        assertTrue("启动脚本应启动 ACP serve", batText.contains("acp serve"));
        assertTrue("启动脚本应启动桥接", batText.contains("dsh_bridge_server.py"));
    }

    private static byte[] readAll(File f) throws Exception {
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    /** 聊天页状态条默认关闭（大多用户不用这个功能，不该常驻占位） */
    @Test
    public void statusBarOffByDefault() {
        Context c = ctx();
        c.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)
                .edit().remove("remote_dsh_bar").apply();
        System.out.println("[EXP] 状态条默认值 => isBarEnabled=" + RemoteDshTool.isBarEnabled(c));
        assertFalse("状态条应默认关闭", RemoteDshTool.isBarEnabled(c));
    }
}

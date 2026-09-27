package com.oilquiz.app.vnc;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.oilquiz.app.ai.tool.TermuxEnvInstaller;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 真机验证「图形界面（VNC）」：容器里的 X11 + x11vnc 能不能起、自研 RFB 客户端能不能连上并拿到帧。
 *
 * <p>这是整条链路唯一有说服力的验收方式：不是"我觉得能连"，而是真的在设备上
 * 让 Termux 起服务端 → 用 {@link VncClient} 完成 RFB 3.8 握手 → 断言收到 ServerInit 与
 * 至少一帧 FramebufferUpdate。
 */
@RunWith(AndroidJUnit4.class)
public class VncClientDeviceTest {

    private static Context ctx() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static boolean portOpen(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 关键用例：起服务端 → 真连 127.0.0.1:5900 → 断言握手与首帧 */
    @Test
    public void rfbHandshakeAndFirstFrame() throws Exception {
        Context c = ctx();
        if (TermuxEnvInstaller.termuxVersion(c) == null) {
            System.out.println("[VNC] 没装 Termux，跳过");
            return;
        }
        if (!TermuxEnvInstaller.hasRunCommandPermission(c)) {
            System.out.println("[VNC] 没授予 RUN_COMMAND 权限，跳过");
            return;
        }
        String err = TermuxEnvInstaller.startGuiInTermux(c);
        System.out.println("[VNC] 下发启动图形界面: " + (err == null ? "已接受" : err));

        boolean up = false;
        int waited = 0;
        for (int i = 0; i < 30 && !up; i++) {
            Thread.sleep(1000);
            waited++;
            up = portOpen("127.0.0.1", TermuxEnvInstaller.VNC_PORT, 1500);
        }
        System.out.println("[VNC] 127.0.0.1:" + TermuxEnvInstaller.VNC_PORT + " 就绪=" + up + "（等了 " + waited + "s）");
        assertTrue("127.0.0.1:" + TermuxEnvInstaller.VNC_PORT + " 应在 30 秒内就绪"
                + "（没起来就看 Termux 的 ~/.quiz_gui.log；多半是容器里没装 xvfb/x11vnc）", up);

        // 每次尝试都用新的 latch：onDisconnected 也会放行 latch，
        // 所以必须用独立的成功标志判断，不能把"断开"当"握手成功"（第一版就栽在这）。
        final CountDownLatch frameLatch = new CountDownLatch(1);
        VncClient client = null;
        boolean ok = false;
        int[] size = new int[2];
        String[] name = new String[1];
        String[] failure = new String[1];

        for (int attempt = 1; attempt <= 5 && !ok; attempt++) {
            final CountDownLatch done = new CountDownLatch(1);
            final boolean[] good = new boolean[1];
            final int[] sz = new int[2];
            final String[] nm = new String[1];
            final String[] why = new String[1];
            final VncClient vc = new VncClient(new VncClient.Listener() {
                @Override
                public void onConnected(int w, int h, String serverName) {
                    good[0] = true;
                    sz[0] = w;
                    sz[1] = h;
                    nm[0] = serverName;
                    done.countDown();
                }

                @Override
                public void onFrameReady() {
                    frameLatch.countDown();
                }

                @Override
                public void onClipboard(String text) {
                    // 用不到
                }

                @Override
                public void onDisconnected(String reason) {
                    why[0] = reason;
                    done.countDown();
                }
            });
            client = vc;
            vc.start("127.0.0.1", TermuxEnvInstaller.VNC_PORT, 5000);
            done.await(20, TimeUnit.SECONDS);
            ok = good[0];
            size = sz;
            name = nm;
            failure = why;
            System.out.println("[VNC] 第 " + attempt + " 次握手=" + ok + " 尺寸=" + sz[0] + "x" + sz[1]
                    + " name=" + nm[0] + " 断开原因=" + why[0]);
            if (!ok) {
                vc.stop();
                Thread.sleep(1500);
            }
        }
        assertTrue("RFB 握手应完成（最后一次失败原因: " + failure[0] + "）", ok);
        assertTrue("ServerInit 尺寸应为正（实际 " + size[0] + "x" + size[1] + "）", size[0] > 0 && size[1] > 0);
        assertTrue("应收到至少一帧 FramebufferUpdate", frameLatch.await(20, TimeUnit.SECONDS));
        assertNotNull("帧位图应已分配", client.getBitmap());
        assertEquals("位图宽度应与 ServerInit 一致", size[0], client.getBitmap().getWidth());
        // 帧内容不能全黑：X 桌面上有窗口（环境准备里会起 xclock / 演示窗口），
        // 全黑说明要么没真收到画面、要么像素解码/编码协商错了。
        android.graphics.Bitmap bmp = client.getBitmap();
        java.util.HashSet<Integer> distinct = new java.util.HashSet<>();
        for (int yy = 0; yy < bmp.getHeight(); yy += 13) {
            for (int xx = 0; xx < bmp.getWidth(); xx += 13) {
                distinct.add(bmp.getPixel(xx, yy));
            }
        }
        System.out.println("[VNC] 帧内不同颜色数=" + distinct.size());
        assertTrue("帧内容不应全黑（实际颜色数 " + distinct.size() + "）", distinct.size() > 2);
        System.out.println("[VNC] RFB 客户端验收通过（" + size[0] + "x" + size[1] + " name=" + name[0]
                + " 颜色数=" + distinct.size() + "）");
        client.stop();
    }

    /**
     * 把图形界面页真的拉到前台并保持 15 秒，供外部 adb 截图取证。
     *
     * <p>Instrumentation 以自家 UID 启动自家 Activity，不受 exported=false 限制
     * （从 adb shell am start 是起不来的）；页面 onCreate 会自动连 127.0.0.1:5900，
     * 所以截到的就是"答题宝里显示 Linux 桌面"的真实画面。
     */
    @Test
    public void showVncActivityForScreenshot() throws Exception {
        android.content.Intent i = new android.content.Intent(ctx(), VncActivity.class);
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        // 不能用 startActivitySync：VNC 页面在持续收帧重绘，事件队列一直不空闲，
        // startActivitySync 会等 45 秒后超时（实测就是这么失败的）。直接 startActivity 即可。
        ctx().startActivity(i);
        System.out.println("[VNC] 图形界面页已拉起，保持 18 秒供截图");
        Thread.sleep(18000);
    }

    /** 准备脚本与 App 侧启动脚本必须一致（同一份容器内命令），且带上真机踩坑必需的参数 */
    @Test
    public void guiScriptsAreConsistent() {
        String setup = TermuxEnvInstaller.buildSetupScript("/sdcard/Download/OilQuiz/termux_env/ubuntu-base-24.04.5-base-arm64.tar.gz");
        assertTrue("准备脚本应含 GUI 启动命令", setup.contains(TermuxEnvInstaller.GUI_INNER_COMMAND));
        assertTrue("准备脚本应写 ~/ubuntu-gui", setup.contains("ubuntu-gui"));
        assertTrue("应装 tigervnc-standalone-server（Xvnc）", setup.contains("tigervnc-standalone-server"));
        assertTrue("应装 xdotool（GUI 自动化）", setup.contains("xdotool"));
        assertTrue("应装 imagemagick（截图）", setup.contains("imagemagick"));
        assertTrue("Xvnc 必须前台常驻（exec）", setup.contains("exec Xvnc :1"));
        assertTrue("Xvnc 要免密码/免 X 授权", setup.contains("-SecurityTypes None") && setup.contains("-ac"));
        assertTrue("启动前必须清 :1 的残留 socket（否则 bind 失败直接退出）",
                setup.contains("rm -f /tmp/.X11-unix/X1"));
        assertFalse("不要再带 x11vnc 那套参数", setup.contains("-noshm") || setup.contains("-threads"));

        String start = TermuxEnvInstaller.buildGuiStartScript();
        assertTrue("App 侧启动脚本应 setsid nohup 常驻", start.contains("setsid nohup"));
        assertTrue("App 侧启动脚本应与准备脚本用同一份容器命令", start.contains(TermuxEnvInstaller.GUI_INNER_COMMAND));
        assertTrue("App 侧启动脚本应幂等（已在跑就退出）", start.contains("GUI_ALREADY_UP"));
        assertTrue("就绪判断应在 Termux 侧 0 成本做（端口+进程双条件）",
                start.contains("pgrep -f") && start.contains("Xvn[c] :1")
                        && start.contains("/dev/tcp/127.0.0.1/5900"));
        assertTrue("失败时要提示清残留 Xvnc", start.contains("pkill -f 'Xvn[c] :1'"));
        assertTrue("启动后要预热服务端（x11vnc 首个客户端约 5 秒才发横幅）", start.contains("s.recv(12)"));
        assertFalse("不要再回到 x11vnc 那套（-threads 实测空转且不再监听）", setup.contains("-threads"));

        String status = TermuxEnvInstaller.buildGuiStatusScript();
        assertTrue("状态脚本应报 GUI_RUNNING/GUI_STOPPED",
                status.contains("GUI_RUNNING") && status.contains("GUI_STOPPED"));
        System.out.println("[VNC] 脚本一致性断言通过");
    }

    /** 两个 Termux 侧脚本必须是合法 bash（App 自带 busybox 做真语法检查） */
    @Test
    public void guiScriptsAreValidBash() throws Exception {
        Context c = ctx();
        File bb = new File(c.getFilesDir(), "bin/busybox");
        if (!bb.isFile()) {
            System.out.println("[VNC] 没找到 " + bb + "，跳过 ash -n");
            return;
        }
        String[] scripts = {
                TermuxEnvInstaller.buildGuiStartScript(),
                TermuxEnvInstaller.buildGuiStopScript(),
                TermuxEnvInstaller.buildGuiStatusScript()
        };
        String[] names = {"buildGuiStartScript", "buildGuiStopScript", "buildGuiStatusScript"};
        for (int i = 0; i < scripts.length; i++) {
            File f = new File(c.getCacheDir(), "vnc_scripts/" + names[i] + ".sh");
            assertTrue("缓存目录应可用", f.getParentFile().isDirectory() || f.getParentFile().mkdirs());
            Files.write(f.toPath(), scripts[i].getBytes(StandardCharsets.UTF_8));
            Process p = new ProcessBuilder(bb.getAbsolutePath(), "ash", "-n", f.getAbsolutePath())
                    .redirectErrorStream(true).start();
            p.getOutputStream().close();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = p.getInputStream().read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            boolean done = p.waitFor(20, TimeUnit.SECONDS);
            String out = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            System.out.println("[VNC] ash -n " + names[i] + " exit=" + (done ? p.exitValue() : -1) + " " + out.trim());
            assertTrue(names[i] + " 应是合法 bash: " + out, done && p.exitValue() == 0);
        }
    }

    /** 图形界面页布局能 inflate，关键控件齐全 */
    @Test
    public void vncLayoutInflates() {
        Context base = ctx();
        Context themed = new android.view.ContextThemeWrapper(base, base.getApplicationInfo().theme);
        android.view.View root = android.view.LayoutInflater.from(themed)
                .inflate(com.oilquiz.app.R.layout.activity_vnc, null);
        int[] ids = {
                com.oilquiz.app.R.id.vnc_view, com.oilquiz.app.R.id.vnc_status,
                com.oilquiz.app.R.id.vnc_hint, com.oilquiz.app.R.id.vnc_port,
                com.oilquiz.app.R.id.btn_vnc_gui, com.oilquiz.app.R.id.btn_vnc_connect,
                com.oilquiz.app.R.id.btn_vnc_disconnect, com.oilquiz.app.R.id.btn_vnc_keyboard,
                com.oilquiz.app.R.id.btn_vnc_right, com.oilquiz.app.R.id.btn_vnc_fit,
                com.oilquiz.app.R.id.btn_vnc_zoom_in, com.oilquiz.app.R.id.btn_vnc_zoom_out,
                com.oilquiz.app.R.id.btn_vnc_paste
        };
        StringBuilder missing = new StringBuilder();
        for (int id : ids) {
            if (root.findViewById(id) == null) {
                missing.append(missing.length() == 0 ? "" : ", ")
                        .append(base.getResources().getResourceEntryName(id));
            }
        }
        System.out.println("[VNC] 图形界面页 inflate 成功，缺失: " + (missing.length() == 0 ? "(无)" : missing));
        assertFalse("布局缺控件: " + missing, missing.length() > 0);
    }
}

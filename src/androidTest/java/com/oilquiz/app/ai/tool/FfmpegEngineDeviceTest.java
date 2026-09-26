package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.graphics.BitmapFactory;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 真机验证 ffmpeg 引擎（media_toolkit 的 engine=auto/ffmpeg 通道）。
 *
 * 为什么必须有这个测试：ffmpeg 在这个项目里失败过两次（Termux 版库在加载阶段就炸），
 * 所以"能加载 + 能解系统框架解不了的容器"必须真机跑通才算数，不能只看编译过。
 *
 * 素材来源（两条都走，谁通用谁）：
 *   1) 真实素材：从 filesamples.com 下载 avi/flv/wmv（下载失败则跳过，不误报）
 *   2) 自造素材：用 ffmpeg 的 lavfi 测试源 + 原生编码器现场生成 avi/flv/rm
 *
 * 运行：
 *   adb shell am instrument -w -e class com.oilquiz.app.ai.tool.FfmpegEngineDeviceTest com.oilquiz.app.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4.class)
public class FfmpegEngineDeviceTest {

    private Context ctx;
    private MediaToolkitTool tool;
    private File dir;
    /** ffmpeg 自造的 mp4（lavfi 测试源，纯本地，不依赖网络） */
    private File mp4;

    @Before
    public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        tool = new MediaToolkitTool(ctx);
        dir = new File(ctx.getCacheDir(), "ff_test");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建 " + dir);
        assertTrue("ffmpeg 引擎应可用（libav* 加载成功）: " + FfmpegEngine.version(), FfmpegEngine.available());
        mp4 = new File(dir, "gen_base.mp4");
        if (!mp4.exists() || mp4.length() == 0) {
            FfmpegEngine.Result r = FfmpegEngine.run(new String[]{"-y", "-hide_banner",
                    "-f", "lavfi", "-i", "testsrc=size=320x240:rate=10",
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                    "-t", "3", "-c:v", "mpeg4", "-q:v", "5", "-c:a", "aac", "-shortest",
                    mp4.getAbsolutePath()}, 120000);
            System.out.println("[SETUP] 生成 base.mp4 => " + r.describe() + " size=" + mp4.length());
        }
        System.out.println("[SETUP] engine=" + FfmpegEngine.describeVersion() + " base=" + mp4.length() + "B");
    }

    // ==================== 1. 引擎本身 ====================

    @Test
    public void engineIsLoadedAndUsable() {
        AIToolResult r = tool.execute(params("probe", "path", mp4.getAbsolutePath(), "engine", "ffmpeg"));
        System.out.println("[EXP] version probe => " + r.getResult());
        assertTrue("ffmpeg 引擎应能读自己生成的 mp4", r.isSuccess());

        // 注：-version/-encoders 之类写的是 stdout，ffmpeg-kit 只收集 stderr，拿不到（实测空串）。
        // 所以版本号走进程内 API，编解码能力一律用"真的跑一遍"来证明。
        String v = FfmpegEngine.version();
        System.out.println("[EXP] 进程内 ffmpeg 版本 => " + v);
        assertTrue("版本号应可读且看着像 ffmpeg 版本: " + v, v != null && v.matches("(?s).*[0-9]+\\.[0-9]+.*"));

        // 行为验证 1：硬件 H.264 编码器（h264_mediacodec）真的能编
        File hw = new File(dir, "hw_h264.mp4");
        FfmpegEngine.Result hwRun = FfmpegEngine.run(new String[]{"-y", "-hide_banner", "-i", mp4.getAbsolutePath(),
                "-c:v", "h264_mediacodec", "-c:a", "aac", hw.getAbsolutePath()}, 120000);
        System.out.println("[EXP] h264_mediacodec 编码 => " + hwRun.describe() + " size=" + hw.length());
        assertTrue("h264_mediacodec（MediaCodec 硬件桥）应可用: " + hwRun.describe(), hwRun.success && hw.length() > 0);

        // 行为验证 2：老容器/老编码的解码器（rv20/rm 已在 readsContainers 里验过，这里补 msmpeg4v3=wmv）
        File wmv = new File(dir, "gen.wmv");
        FfmpegEngine.Result gen = FfmpegEngine.run(new String[]{"-y", "-hide_banner", "-i", mp4.getAbsolutePath(),
                "-c:v", "msmpeg4", "-c:a", "wmav2", wmv.getAbsolutePath()}, 120000);
        System.out.println("[EXP] 生成 wmv(msmpeg4v3) => " + gen.describe() + " size=" + wmv.length());
        if (gen.success && wmv.length() > 0) {
            AIToolResult p = tool.execute(params("probe", "path", wmv.getAbsolutePath()));
            System.out.println("[EXP] 读 wmv => " + String.valueOf(p.getResult()).replace('\n', ' '));
            assertTrue("应能读 wmv（系统框架读不了）", p.isSuccess());
        }
        // stderr 通道要能拿到：故意用不存在的输入，错误信息应出现在 output 里
        FfmpegEngine.Result bad = FfmpegEngine.run(new String[]{"-hide_banner", "-i", new File(dir, "nope.xyz").getAbsolutePath(), "-f", "null", "-"}, 30000);
        System.out.println("[EXP] 错误通道 => success=" + bad.success + " output=" + bad.output.replace('\n', '|'));
        assertTrue("失败时 stderr 内容应能拿到（用于给用户解释原因）", !bad.success && !bad.output.trim().isEmpty());
    }

    // ==================== 2. 系统框架解不了的容器 ====================

    /** 用 ffmpeg 现场造 avi/flv/rm 三种"系统框架打不开"的容器，再用 media_toolkit 读它们 */
    @Test
    public void readsContainersSystemFrameworkCannot() throws Exception {
        String[][] cases = {
                {"avi", "-c:v", "mpeg4", "-q:v", "5", "-c:a", "pcm_s16le"},
                {"flv", "-c:v", "flv", "-c:a", "aac"},
                {"rm", "-f", "rm", "-c:v", "rv20", "-c:a", "ac3"},
        };
        for (String[] c : cases) {
            File sample = new File(dir, "gen." + c[0]);
            java.util.List<String> args = new java.util.ArrayList<>();
            args.add("-y");
            args.add("-hide_banner");
            args.add("-i");
            args.add(mp4.getAbsolutePath());
            for (int i = 1; i < c.length; i++) args.add(c[i]);
            args.add(sample.getAbsolutePath());
            FfmpegEngine.Result gen = FfmpegEngine.run(args.toArray(new String[0]), 120000);
            System.out.println("[EXP] 生成 " + c[0] + " => " + gen.describe() + " size=" + sample.length());
            assertTrue("应能生成 " + c[0], gen.success && sample.length() > 0);

            // 系统引擎必须打不开（设备解封装器只有 aac/amr/flac/midi/mkv/mp3/mp4/mpeg2/ogg/wav）
            AIToolResult sys = tool.execute(params("probe", "path", sample.getAbsolutePath(), "engine", "system"));
            System.out.println("[EXP] 系统引擎读 " + c[0] + " => success=" + sys.isSuccess() + " err=" + sys.getErrorMessage());
            assertTrue("系统引擎本就不该能读 " + c[0] + "（否则这个用例失去意义）", !sys.isSuccess());

            // engine=auto：应自动回退 ffmpeg 并读出轨道
            AIToolResult auto = tool.execute(params("probe", "path", sample.getAbsolutePath()));
            JSONObject o = new JSONObject(String.valueOf(auto.getResult()));
            System.out.println("[EXP] auto 回退读 " + c[0] + " => engine=" + o.optString("engine")
                    + " tracks=" + o.optInt("track_count") + " dur=" + o.optJSONObject("metadata").optDouble("duration_sec")
                    + " " + o.optJSONObject("metadata").optInt("width") + "x" + o.optJSONObject("metadata").optInt("height"));
            assertTrue(c[0] + " 应被 ffmpeg 引擎读出: " + auto.getErrorMessage(),
                    auto.isSuccess() && o.optInt("track_count") >= 1 && o.optString("engine").startsWith("ffmpeg"));
            assertTrue(c[0] + " 应有时长", o.optJSONObject("metadata").optDouble("duration_sec") > 1.0);

            // 截帧 + 转 WAV 也应能走 ffmpeg
            AIToolResult frame = tool.execute(params("frame", "path", sample.getAbsolutePath(), "time", "1.0"));
            JSONObject fj = new JSONObject(String.valueOf(frame.getResult()));
            File img = new File(fj.getString("first_file"));
            BitmapFactory.Options bo = new BitmapFactory.Options();
            bo.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(img.getAbsolutePath(), bo);
            System.out.println("[EXP] " + c[0] + " 截帧 => " + img.getName() + " " + bo.outWidth + "x" + bo.outHeight);
            assertTrue(c[0] + " 应能截帧: " + frame.getErrorMessage(), frame.isSuccess() && bo.outWidth > 0);

            AIToolResult wav = tool.execute(params("to_wav", "path", sample.getAbsolutePath()));
            System.out.println("[EXP] " + c[0] + " 转 WAV => " + String.valueOf(wav.getResult()).replace('\n', ' '));
            assertTrue(c[0] + " 应能转 WAV（有音轨时）: " + wav.getErrorMessage(), wav.isSuccess());
        }
    }

    /** ffmpeg 把系统读不了的容器转成系统能读的 mp4 —— 端到端"打通"验证 */
    @Test
    public void convertsUnreadableContainerIntoPlayableMp4() throws Exception {
        File avi = new File(dir, "gen.avi");
        assertTrue("前置素材应在（由上一个用例生成）", avi.exists() || generateAvi(avi));

        AIToolResult sys = tool.execute(params("probe", "path", avi.getAbsolutePath(), "engine", "system"));
        assertTrue("系统引擎不该能读 avi", !sys.isSuccess());

        AIToolResult out = tool.execute(params("transcode", "path", avi.getAbsolutePath(), "engine", "ffmpeg",
                "video_mime", "h264", "audio_mime", "aac", "output", "from_avi.mp4", "timeout", "180"));
        System.out.println("[EXP] avi→mp4 => " + String.valueOf(out.getResult()).replace('\n', ' '));
        assertTrue("avi→mp4 应成功: " + out.getErrorMessage(), out.isSuccess());
        String file = new JSONObject(String.valueOf(out.getResult())).getString("file");

        // 关键：产物要用【系统引擎】也能读（说明真成了通用 mp4）
        AIToolResult sysOut = tool.execute(params("probe", "path", file, "engine", "system"));
        System.out.println("[EXP] 系统引擎读产物 => success=" + sysOut.isSuccess() + " " + String.valueOf(sysOut.getResult()).replace('\n', ' '));
        assertTrue("ffmpeg 产物应能被系统引擎读: " + sysOut.getErrorMessage(), sysOut.isSuccess());
    }

    // ==================== 3. 滤镜链（系统引擎完全没有的能力） ====================

    @Test
    public void filterChainWorks() throws Exception {
        AIToolResult r = tool.execute(params("filter", "path", mp4.getAbsolutePath(),
                "vfilter", "scale=160:120,fps=5", "afilter", "volume=0.5",
                "video_mime", "h264", "audio_mime", "aac", "output", "filtered.mp4", "timeout", "180"));
        System.out.println("[EXP] filter => " + String.valueOf(r.getResult()).replace('\n', ' '));
        assertTrue("滤镜链应成功: " + r.getErrorMessage(), r.isSuccess());
        JSONObject o = new JSONObject(String.valueOf(r.getResult()));
        String file = o.getString("file");
        System.out.println("[EXP] filter 输出尺寸 => " + o.optInt("output_width") + "x" + o.optInt("output_height")
                + " codec=" + o.optString("video_codec"));

        AIToolResult sys = tool.execute(params("probe", "path", file, "engine", "system"));
        assertTrue("滤镜产物应能被系统引擎读", sys.isSuccess());
        JSONObject meta = new JSONObject(String.valueOf(sys.getResult())).getJSONObject("metadata");
        System.out.println("[EXP] 滤镜产物系统元数据 => " + meta);
        assertTrue("滤镜后宽度应为 160，实际 " + meta.optInt("width"), meta.optInt("width") == 160);
    }

    // ==================== 4. 真实素材（下载得到才跑） ====================

    @Test
    public void realWorldSamplesDecode() throws Exception {
        String[][] samples = {
                {"real.avi", "https://filesamples.com/samples/video/avi/sample_640x360.avi"},
                {"real.flv", "https://filesamples.com/samples/video/flv/sample_640x360.flv"},
                {"real.wmv", "https://filesamples.com/samples/video/wmv/sample_640x360.wmv"},
        };
        int downloaded = 0;
        for (String[] s : samples) {
            File f = new File(dir, s[0]);
            if (!f.exists() || f.length() == 0) {
                boolean ok = download(s[1], f, 60000);
                System.out.println("[EXP] 下载 " + s[0] + " => " + (ok ? (f.length() + "B") : "失败（跳过真实素材）"));
            }
            if (!f.exists() || f.length() == 0) continue;
            downloaded++;
            AIToolResult sys = tool.execute(params("probe", "path", f.getAbsolutePath(), "engine", "system"));
            AIToolResult auto = tool.execute(params("probe", "path", f.getAbsolutePath()));
            JSONObject o = new JSONObject(String.valueOf(auto.getResult()));
            System.out.println("[EXP] 真实 " + s[0] + " => 系统引擎=" + (sys.isSuccess() ? "能读" : "读不了")
                    + " | ffmpeg tracks=" + o.optInt("track_count")
                    + " " + o.optJSONObject("metadata").optInt("width") + "x" + o.optJSONObject("metadata").optInt("height")
                    + " codec=" + firstVideoCodec(o) + " dur=" + o.optJSONObject("metadata").optDouble("duration_sec"));
            assertTrue("真实素材应能被 ffmpeg 引擎读出", auto.isSuccess() && o.optInt("track_count") >= 1);

            AIToolResult th = tool.execute(params("thumbnail", "path", f.getAbsolutePath(), "max", "160"));
            assertTrue("真实素材应能出缩略图: " + th.getErrorMessage(), th.isSuccess());
            System.out.println("[EXP] 真实 " + s[0] + " 缩略图 => "
                    + new JSONObject(String.valueOf(th.getResult())).getString("first_file"));
        }
        System.out.println("[EXP] 真实素材下载成功数 = " + downloaded + "/3");
        if (downloaded == 0) {
            System.out.println("[WARN] 网络不可达，真实素材没下到；本次只验证了 ffmpeg 自造素材（gen.avi/flv/rm）");
        }
    }

    // ==================== 5. 文档随包与保护（LGPL 合规） ====================

    /** ffmpeg 是 LGPL，许可声明必须随包分发、并在工作区受保护（删除后自动恢复） */
    @Test
    public void licenceNoticeIsShippedAndProtected() throws Exception {
        for (String name : new String[]{"THIRD_PARTY_NOTICES.md", "MEDIA_TOOLKIT_GUIDE.md"}) {
            assertTrue(name + " 应被登记为内置文档（删除受保护 + 自动恢复）",
                    com.oilquiz.app.ai.agent.online.AgentWorkspace.isBuiltinGuideFile(name));
            String asset = "apk_shell/guides/" + name;
            String content;
            try (InputStream in = ctx.getAssets().open(asset)) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                content = new String(bos.toByteArray(), "UTF-8");
            }
            System.out.println("[EXP] 随包文档 " + name + " => " + content.length() + " 字符");
            assertTrue(name + " 应真的打进 APK 的 assets", content.length() > 200);
            File inWorkspace = new File(ctx.getFilesDir(),
                    "agent_workspace/files/" + name);
            System.out.println("[EXP] 工作区副本 " + name + " => exists=" + inWorkspace.exists()
                    + " size=" + inWorkspace.length());
            if ("THIRD_PARTY_NOTICES.md".equals(name)) {
                assertTrue("许可声明里应写清 ffmpeg 的 LGPL 与源码地址",
                        content.contains("LGPL") && content.contains("ffmpegkit-maintained"));
                assertTrue("许可声明应已恢复到工作区（应用启动时自动恢复）", inWorkspace.exists() && inWorkspace.length() > 200);
            }
        }
    }

    // ==================== 辅助 ====================

    private boolean generateAvi(File out) {
        FfmpegEngine.Result r = FfmpegEngine.run(new String[]{"-y", "-hide_banner", "-i", mp4.getAbsolutePath(),
                "-c:v", "mpeg4", "-q:v", "5", "-c:a", "pcm_s16le", out.getAbsolutePath()}, 120000);
        return r.success && out.length() > 0;
    }

    private static String firstVideoCodec(JSONObject o) {
        JSONArray t = o.optJSONArray("tracks");
        if (t == null) return "?";
        for (int i = 0; i < t.length(); i++) {
            JSONObject s = t.optJSONObject(i);
            if (s != null && "video".equals(s.optString("type"))) return s.optString("codec");
        }
        return "?";
    }

    private static boolean download(String url, File target, int timeoutMs) {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)");
            int code = conn.getResponseCode();
            if (code != 200) return false;
            in = conn.getInputStream();
            out = new FileOutputStream(target);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return target.length() > 0;
        } catch (Throwable t) {
            System.out.println("[WARN] 下载失败 " + url + " :: " + t);
            if (target.exists()) target.delete();
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
        }
    }

    private Map<String, Object> params(String action, String... kv) {
        Map<String, Object> p = new HashMap<>();
        p.put("action", action);
        for (int i = 0; i + 1 < kv.length; i += 2) p.put(kv[i], kv[i + 1]);
        return p;
    }
}

package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import com.oilquiz.app.ai.tool.openai.ToolDefinition;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 真机(instrumented)验证 media_toolkit：本地媒体工具箱（系统硬解硬编，无 ffmpeg）。
 *
 * 测试素材由本类现场用 MediaCodec 生成（H.264 视频 + AAC 音频的 mp4、一张 jpg、一段 wav），
 * 不依赖设备上已有的文件，也不依赖任何存储权限；同时顺带证明设备编码器可用。
 *
 * 运行：
 *   gradlew :assembleDebug :assembleDebugAndroidTest
 *   adb install -r build\outputs\apk\debug\答题宝-debug-2.0.apk
 *   adb install -r build\outputs\apk\androidTest\debug\*.apk
 *   adb shell am instrument -w -e class com.oilquiz.app.ai.tool.MediaToolkitDeviceTest com.oilquiz.app.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4.class)
public class MediaToolkitDeviceTest {

    private Context ctx;
    private MediaToolkitTool tool;
    private File dir;
    private File video;
    private File rotated;
    private File image;

    @Before
    public void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        tool = new MediaToolkitTool(ctx);
        dir = new File(ctx.getCacheDir(), "mt_test");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建测试目录 " + dir);
        video = new File(dir, "mt_source.mp4");
        if (!video.exists() || video.length() == 0) makeTestMp4(video, 0);
        rotated = new File(dir, "mt_rot.mp4");
        if (!rotated.exists() || rotated.length() == 0) makeTestMp4(rotated, 90);
        image = new File(dir, "mt_source.jpg");
        if (!image.exists() || image.length() == 0) makeTestJpg(image);
        System.out.println("[SETUP] dir=" + dir.getAbsolutePath()
                + " video=" + video.length() + "B image=" + image.length() + "B");
    }

    // ==================== 用例 ====================

    /** 工具注册 + probe 能读出轨道/时长/编码器 */
    @Test
    public void probeReadsTracks() throws Exception {
        ToolDefinition def = AIToolManager.getInstance(ctx).getToolDefinition("media_toolkit");
        assertNotNull("media_toolkit 应已注册工具定义", def);
        System.out.println("[TEST] definition params=" + def.getParameters().size());
        AITool viaFactory = AIToolManager.getInstance(ctx).getTool("media_toolkit");
        assertTrue("工厂注册应能实例化 media_toolkit: " + viaFactory,
                viaFactory != null && "media_toolkit".equals(viaFactory.getName()));

        JSONObject o = call("probe", "path", video.getAbsolutePath());
        System.out.println("[EXP] probe video => " + o);
        assertTrue("应至少有 2 条轨道（视频+音频）: " + o, o.getInt("track_count") >= 2);
        assertTrue("时长应约 3 秒: " + o, o.getJSONObject("metadata").getDouble("duration_sec") > 2.0);
        assertTrue("应识别为 video: " + o, "video".equals(o.getString("type")));
        String codec = "";
        for (int i = 0; i < o.getJSONArray("tracks").length(); i++) {
            JSONObject t = o.getJSONArray("tracks").getJSONObject(i);
            if ("video".equals(t.optString("type"))) codec = t.optString("codec");
        }
        System.out.println("[EXP] video codec => " + codec);
        assertTrue("视频轨应给出编码器名: " + codec,
                codec.toLowerCase().contains("avc") || codec.toLowerCase().contains("h264"));
    }

    /** probe 认图片 */
    @Test
    public void probeReadsImage() throws Exception {
        JSONObject o = call("probe", "path", image.getAbsolutePath());
        System.out.println("[EXP] probe image => " + o);
        assertTrue("应识别为 image: " + o, "image".equals(o.getString("type")));
        assertTrue("应读出宽高: " + o, o.getJSONObject("image").getInt("width") == 640);
    }

    /** 截帧：按时间取一帧，能解码回图片且尺寸正确 */
    @Test
    public void frameExtractsPicture() throws Exception {
        JSONObject o = call("frame", "path", video.getAbsolutePath(), "time", "1.0", "width", "320");
        System.out.println("[EXP] frame => " + o);
        File f = new File(o.getString("first_file"));
        assertTrue("截帧文件应存在: " + f, f.exists() && f.length() > 0);
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), b);
        System.out.println("[EXP] frame size => " + b.outWidth + "x" + b.outHeight);
        assertTrue("帧宽应为 320（源就是 320 宽）: " + b.outWidth, b.outWidth == 320);
    }

    /** 多帧：count=N 均匀抽帧 */
    @Test
    public void frameCountExtractsMany() throws Exception {
        JSONObject o = call("frame", "path", video.getAbsolutePath(), "count", "4", "width", "160");
        System.out.println("[EXP] frame count=4 => " + o);
        assertTrue("应抽出 4 张: " + o, o.getInt("count") == 4);
        for (int i = 0; i < 4; i++) {
            File f = new File(o.getJSONArray("files").getJSONObject(i).getString("file"));
            assertTrue("第 " + i + " 张应存在: " + f, f.exists() && f.length() > 0);
        }
    }

    /** 缩略图：最长边受 max 限制 */
    @Test
    public void thumbnailScalesToMax() throws Exception {
        JSONObject o = call("thumbnail", "path", video.getAbsolutePath(), "max", "160");
        System.out.println("[EXP] thumbnail => " + o);
        File f = new File(o.getString("first_file"));
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), b);
        System.out.println("[EXP] thumb size => " + b.outWidth + "x" + b.outHeight);
        assertTrue("最长边应 <=160: " + b.outWidth + "x" + b.outHeight,
                b.outWidth <= 160 && b.outHeight <= 160 && b.outWidth > 0);
    }

    /**
     * 旋转元数据探查：手机竖拍视频存的是横向像素 + rotation=90。
     * 这里量一下 getFrameAtTime 出来的帧到底是横向(320x240)还是已经转正(240x320)，
     * 以决定工具要不要自己补旋转（不同 ROM/版本行为不一致，必须实测）。
     */
    @Test
    public void frameRotationProbe() throws Exception {
        JSONObject p = call("probe", "path", rotated.getAbsolutePath());
        int rot = p.getJSONObject("metadata").optInt("rotation");
        JSONObject f = call("frame", "path", rotated.getAbsolutePath(), "time", "1.0");
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getString("first_file"), b);
        System.out.println("[EXP] rotation=" + rot + " frame=" + b.outWidth + "x" + b.outHeight
                + " (存储像素 320x240) => " + (b.outWidth > b.outHeight ? "框架未转正(需自行旋转)" : "框架已转正"));
    }

    /** 无损抽音轨：aac → m4a，且输出只有音轨 */
    @Test
    public void extractAudioLossless() throws Exception {
        JSONObject o = call("extract_audio", "path", video.getAbsolutePath());
        System.out.println("[EXP] extract_audio => " + o);
        File f = new File(o.getString("file"));
        assertTrue("m4a 应存在且 >1KB: " + f.length(), f.exists() && f.length() > 1024);
        JSONObject p = call("probe", "path", f.getAbsolutePath());
        System.out.println("[EXP] probe m4a => " + p);
        assertTrue("抽出的音轨应能解码: " + p, p.getInt("track_count") >= 1);
        assertTrue("应只有音轨: " + p, "audio".equals(p.getString("type")));
    }

    /** to_wav：16kHz 单声道 PCM16 WAV（校验头部字节） */
    @Test
    public void toWavDecodes16kMono() throws Exception {
        JSONObject o = call("to_wav", "path", video.getAbsolutePath());
        System.out.println("[EXP] to_wav => " + o);
        File f = new File(o.getString("file"));
        assertTrue("wav 应存在: " + f, f.exists() && f.length() > 10000);
        byte[] head = new byte[44];
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            int n = in.read(head);
            assertTrue("应读到 wav 头", n == 44);
        }
        String riff = new String(head, 0, 4, "US-ASCII");
        String wave = new String(head, 8, 4, "US-ASCII");
        int channels = (head[22] & 0xFF) | ((head[23] & 0xFF) << 8);
        int rate = (head[24] & 0xFF) | ((head[25] & 0xFF) << 8) | ((head[26] & 0xFF) << 16) | ((head[27] & 0xFF) << 24);
        int bits = (head[34] & 0xFF) | ((head[35] & 0xFF) << 8);
        System.out.println("[EXP] wav head => " + riff + "/" + wave + " ch=" + channels + " rate=" + rate + " bits=" + bits);
        assertTrue("应是 RIFF/WAVE", "RIFF".equals(riff) && "WAVE".equals(wave));
        assertTrue("应 16k 单声道 16bit，实际 ch=" + channels + " rate=" + rate + " bits=" + bits,
                channels == 1 && rate == 16000 && bits == 16);
    }

    /** 无损剪切：结果能播放（能 probe 出时长）且比源短 */
    @Test
    public void trimRemuxKeepsPlayable() throws Exception {
        JSONObject o = call("trim", "path", video.getAbsolutePath(), "start", "0.5", "end", "1.5");
        System.out.println("[EXP] trim => " + o);
        File f = new File(o.getString("file"));
        assertTrue("剪切结果应存在: " + f, f.exists() && f.length() > 1024);
        JSONObject p = call("probe", "path", f.getAbsolutePath());
        double dur = p.getJSONObject("metadata").getDouble("duration_sec");
        System.out.println("[EXP] trim duration => " + dur + " actual_start=" + o.optDouble("actual_start_sec"));
        assertTrue("时长应在 0.5~1.8 秒之间（关键帧对齐会有偏差）: " + dur, dur > 0.5 && dur < 1.8);
    }

    /** 转码：缩放 + 限码率 + H.264 重编码，能出片且分辨率减半 */
    @Test
    public void transcodeScalesAndReencodes() throws Exception {
        JSONObject o = call("transcode", "path", video.getAbsolutePath(), "video_mime", "h264",
                "audio_mime", "aac", "scale", "0.5", "bitrate", "400", "timeout", "120");
        System.out.println("[EXP] transcode => " + o);
        File f = new File(o.getString("file"));
        assertTrue("转码结果应存在: " + f, f.exists() && f.length() > 1024);
        JSONObject p = call("probe", "path", f.getAbsolutePath());
        int w = p.getJSONObject("metadata").getInt("width");
        int h = p.getJSONObject("metadata").getInt("height");
        System.out.println("[EXP] transcode probe => " + p.getJSONObject("metadata"));
        // 真机实测：设备编码器按宏块对齐向上取整，请求 160x120 会得到 170x128。
        // 这里校验"确实缩到了半尺寸附近"，并且返回的 output_width 与实际文件一致（不能虚报）。
        assertTrue("分辨率应缩到半尺寸附近（请求 160x120，实际 " + w + "x" + h + "）",
                w >= 140 && w <= 180 && h >= 110 && h <= 145);
        assertTrue("返回的 output_width/height 必须与实际文件一致: "
                        + o.optInt("output_width") + "x" + o.optInt("output_height") + " vs " + w + "x" + h,
                o.optInt("output_width") == w && o.optInt("output_height") == h);
        assertTrue("应保留音轨: " + p, p.getInt("track_count") >= 2);
    }

    /**
     * 编码器分辨率对齐行为探查（记录真实规则，不当成"必须精确"的断言）。
     * 320x240 源：0.8 → 请求 256x192；0.5 → 请求 160x120。
     */
    @Test
    public void transcodeAlignmentProbe() throws Exception {
        for (String scale : new String[]{"0.8", "0.5"}) {
            JSONObject o = call("transcode", "path", video.getAbsolutePath(), "video_mime", "h264",
                    "scale", scale, "timeout", "120", "output", "align_" + scale.replace(".", "_") + ".mp4");
            System.out.println("[EXP] alignment scale=" + scale + " requested=" + o.optString("resolution")
                    + " actual=" + o.optInt("output_width") + "x" + o.optInt("output_height")
                    + " note=" + o.optString("resolution_note"));
            assertTrue("应产出文件", new File(o.getString("file")).length() > 0);
        }
    }

    /** 换容器：video/audio 都 keep = 纯重封装（不重编码），秒级完成 */
    @Test
    public void transcodeTransmuxOnly() throws Exception {
        long t0 = System.currentTimeMillis();
        JSONObject o = call("transcode", "path", video.getAbsolutePath(),
                "video_mime", "keep", "audio_mime", "keep", "timeout", "120");
        long ms = System.currentTimeMillis() - t0;
        System.out.println("[EXP] transmux => " + o + " in " + ms + "ms");
        File f = new File(o.getString("file"));
        assertTrue("重封装结果应存在: " + f, f.exists() && f.length() > 1024);
        JSONObject p = call("probe", "path", f.getAbsolutePath());
        assertTrue("重封装应保留视频+音频: " + p, p.getInt("track_count") >= 2 && "video".equals(p.getString("type")));
    }

    /** 去音轨 */
    @Test
    public void transcodeRemovesAudio() throws Exception {
        JSONObject o = call("transcode", "path", video.getAbsolutePath(), "remove_audio", "true", "timeout", "120");
        System.out.println("[EXP] transcode remove_audio => " + o);
        JSONObject p = call("probe", "path", new File(o.getString("file")).getAbsolutePath());
        System.out.println("[EXP] probe no-audio => " + p);
        assertTrue("应只剩视频轨: " + p, p.getInt("track_count") == 1);
    }

    /** 图片处理：裁剪 + 缩放 + 灰度 + 转 PNG */
    @Test
    public void imageOpsWorks() throws Exception {
        JSONObject o = call("image_ops", "path", image.getAbsolutePath(), "crop", "0,0,320,240",
                "max", "80", "gray", "true", "format", "png");
        System.out.println("[EXP] image_ops => " + o);
        File f = new File(o.getString("file"));
        assertTrue("图片应存在: " + f, f.exists() && f.length() > 0);
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), b);
        System.out.println("[EXP] image_ops out => " + b.outWidth + "x" + b.outHeight + " mime=" + b.outMimeType);
        assertTrue("最长边应 <=80: " + b.outWidth + "x" + b.outHeight, b.outWidth <= 80 && b.outHeight <= 80);
        assertTrue("应是 PNG: " + b.outMimeType, "image/png".equals(b.outMimeType));
    }

    /**
     * 裁剪必须按"原图像素"取区域（回归：曾经因为解码时 inSampleSize 降采样，
     * 裁剪坐标被当成降采样后的坐标，结果裁到了错误区域 —— 真机实测踩到）。
     * 测试图 640x480：整体蓝底(30,60,120)，中心(320,240)半径150 的黄色圆(240,200,40)，左上角白字。
     */
    @Test
    public void imageOpsCropTakesRequestedRegion() throws Exception {
        // 左下角 0,400,150,80 完全在圆外 → 必须是纯蓝；裁错区域就会带入中间的黄色
        JSONObject blue = call("image_ops", "path", image.getAbsolutePath(),
                "crop", "0,400,150,80", "max", "80", "format", "png");
        Bitmap b1 = BitmapFactory.decodeFile(blue.getString("file"));
        assertNotNull("应能解码裁剪结果", b1);
        int c1 = b1.getPixel(b1.getWidth() / 2, b1.getHeight() / 2);
        System.out.println("[EXP] crop corner => " + b1.getWidth() + "x" + b1.getHeight()
                + " centerPixel=" + Integer.toHexString(c1));
        assertTrue("左下角裁剪应只剩蓝色背景，实际 " + Integer.toHexString(c1),
                Color.blue(c1) > 100 && Color.blue(c1) > Color.red(c1));
        b1.recycle();

        // 170,90,300,300 正好是黄圆的外接矩形 → 中心像素必须是黄
        JSONObject yellow = call("image_ops", "path", image.getAbsolutePath(),
                "crop", "170,90,300,300", "format", "png");
        Bitmap b2 = BitmapFactory.decodeFile(yellow.getString("file"));
        assertNotNull("应能解码裁剪结果", b2);
        int c2 = b2.getPixel(b2.getWidth() / 2, b2.getHeight() / 2);
        System.out.println("[EXP] crop center => " + b2.getWidth() + "x" + b2.getHeight()
                + " centerPixel=" + Integer.toHexString(c2));
        assertTrue("中心裁剪的中心像素应是黄色圆，实际 " + Integer.toHexString(c2),
                Color.red(c2) > 150 && Color.green(c2) > 120 && Color.blue(c2) < 120);
        b2.recycle();
    }

    /** 错误路径要给出人话，而不是崩 */
    @Test
    public void errorsAreReadable() throws Exception {
        AIToolResult missing = tool.execute(params("probe", "path", new File(dir, "nope.mp4").getAbsolutePath()));
        System.out.println("[EXP] missing file => success=" + missing.isSuccess() + " err=" + missing.getErrorMessage());
        assertTrue("不存在文件应失败并给出『不存在』: " + missing.getErrorMessage(),
                !missing.isSuccess() && String.valueOf(missing.getErrorMessage()).contains("不存在"));
        AIToolResult bad = tool.execute(params("action_only"));
        System.out.println("[EXP] no path => success=" + bad.isSuccess() + " err=" + bad.getErrorMessage());
        assertTrue("缺 path 应失败", !bad.isSuccess());

        // 描述里承诺"做不到会明确报错"：不支持的容器必须失败并说清原因，而不是"成功但空壳"
        File fakeAvi = new File(dir, "fake.avi");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(fakeAvi)) {
            fos.write(new byte[4096]);
        }
        AIToolResult unknown = tool.execute(params("probe", "path", fakeAvi.getAbsolutePath()));
        System.out.println("[EXP] unsupported container => success=" + unknown.isSuccess() + " err=" + unknown.getErrorMessage());
        assertTrue("不支持的容器应失败并说明原因: " + unknown.getErrorMessage(),
                !unknown.isSuccess() && String.valueOf(unknown.getErrorMessage()).contains("无法识别"));
    }

    /** Python 侧 android_media 必须能用（Chaquopy 里 jclass 调 Java 实现） */
    @Test
    public void pythonAndroidMediaWorks() {
        String code = "import android_media, json\n"
                + "info = android_media.probe('" + video.getAbsolutePath() + "')\n"
                + "print('TYPE=' + info['type'])\n"
                + "print('DUR=' + str(info['metadata']['duration_sec']))\n"
                + "fr = android_media.frame('" + video.getAbsolutePath() + "', time=1.0, width=160)\n"
                + "print('FRAME=' + fr['first_file'])\n"
                + "w = android_media.to_wav('" + video.getAbsolutePath() + "')\n"
                + "print('WAV=' + str(w['sample_rate']) + '/' + str(w['channels']) + '/' + str(w['size_bytes']))\n";
        com.oilquiz.app.ai.python.PythonToolManager.ExecutionResult r =
                com.oilquiz.app.ai.python.PythonToolManager.getInstance(ctx).executeCode(code, null);
        String flat = String.valueOf(r.stdout).replace('\n', '|').replace('\r', ' ');
        System.out.println("[EXP] python android_media success=" + r.success + " stdout=" + flat + " err=" + r.error);
        assertTrue("python 应能 probe: " + flat, flat.contains("TYPE=video"));
        assertTrue("python 应能截帧: " + flat, flat.contains("FRAME=") && flat.contains(".jpg"));
        assertTrue("python 应能转 wav: " + flat, flat.contains("WAV=16000/1/"));
    }

    // ==================== 辅助 ====================

    private Map<String, Object> params(String action, String... kv) {
        Map<String, Object> p = new HashMap<>();
        p.put("action", action);
        for (int i = 0; i + 1 < kv.length; i += 2) p.put(kv[i], kv[i + 1]);
        return p;
    }

    /** 执行工具并把 JSON 结果解析出来（失败直接抛，让用例红） */
    private JSONObject call(String action, String... kv) throws Exception {
        AIToolResult r = tool.execute(params(action, kv));
        if (!r.isSuccess()) throw new AssertionError(action + " 执行失败: " + r.getErrorMessage());
        JSONObject o = new JSONObject(String.valueOf(r.getResult()));
        System.out.println("[JSON] " + action + " => " + o.toString().replace('\n', ' '));
        return o;
    }

    /** 现场生成一张 640x480 的测试图 */
    private void makeTestJpg(File out) throws Exception {
        Bitmap bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.rgb(30, 60, 120));
        Paint p = new Paint();
        p.setColor(Color.rgb(240, 200, 40));
        c.drawCircle(320, 240, 150, p);
        p.setColor(Color.WHITE);
        p.setTextSize(64);
        c.drawText("MEDIA 640x480", 20, 80, p);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, fos);
        }
        bmp.recycle();
    }

    /**
     * 现场生成测试 mp4：H.264 视频（320x240，15fps，3 秒，画面里有移动竖条便于区分帧）+ AAC 静音音轨。
     * 顺带验证设备编码器可用（transcode 依赖它）。
     */
    private void makeTestMp4(File out, int orientationHint) throws Exception {
        final int w = 320, h = 240, fps = 15, frames = 45;
        MediaCodec venc = MediaCodec.createEncoderByType("video/avc");
        int colorFormat = pickColorFormat(venc, "video/avc");
        MediaFormat vf = MediaFormat.createVideoFormat("video/avc", w, h);
        vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
        vf.setInteger(MediaFormat.KEY_BIT_RATE, 800_000);
        vf.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        venc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        venc.start();

        MediaCodec aenc = MediaCodec.createEncoderByType("audio/mp4a-latm");
        MediaFormat af = MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 1);
        af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        af.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
        af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        aenc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        aenc.start();

        MediaMuxer mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        if (orientationHint != 0) mux.setOrientationHint(orientationHint);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int vTrack = -1, aTrack = -1;
        boolean muxStarted = false, vInDone = false, aInDone = false, vOutDone = false, aOutDone = false;
        int frameIdx = 0, silenceSamples = 1024;
        long vPtsUs = 0, aPtsUs = 0;
        long durationUs = 3_000_000L;
        byte[] silence = new byte[silenceSamples * 2];

        while (!(vOutDone && aOutDone)) {
            if (!vInDone) {
                int in = venc.dequeueInputBuffer(5000);
                if (in >= 0) {
                    if (frameIdx >= frames) {
                        venc.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        vInDone = true;
                    } else {
                        int size = fillFrame(venc, in, colorFormat, w, h, frameIdx);
                        venc.queueInputBuffer(in, 0, size, vPtsUs, 0);
                        frameIdx++;
                        vPtsUs += 1_000_000L / fps;
                    }
                }
            }
            if (!aInDone) {
                int in = aenc.dequeueInputBuffer(0);
                if (in >= 0) {
                    if (aPtsUs >= durationUs) {
                        aenc.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        aInDone = true;
                    } else {
                        ByteBuffer b = aenc.getInputBuffer(in);
                        b.clear();
                        b.put(silence);
                        aenc.queueInputBuffer(in, 0, silence.length, aPtsUs, 0);
                        aPtsUs += silenceSamples * 1_000_000L / 44100L;
                    }
                }
            }
            int oi = venc.dequeueOutputBuffer(info, 0);
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                vTrack = mux.addTrack(venc.getOutputFormat());
            } else if (oi >= 0) {
                boolean cfg = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (cfg) info.size = 0;
                if (info.size > 0 && muxStarted) {
                    ByteBuffer ob = venc.getOutputBuffer(oi);
                    mux.writeSampleData(vTrack, ob, info);
                }
                venc.releaseOutputBuffer(oi, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) vOutDone = true;
            }
            oi = aenc.dequeueOutputBuffer(info, 0);
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                aTrack = mux.addTrack(aenc.getOutputFormat());
            } else if (oi >= 0) {
                boolean cfg = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (cfg) info.size = 0;
                if (info.size > 0 && muxStarted) {
                    ByteBuffer ob = aenc.getOutputBuffer(oi);
                    mux.writeSampleData(aTrack, ob, info);
                }
                aenc.releaseOutputBuffer(oi, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) aOutDone = true;
            }
            if (!muxStarted && vTrack >= 0 && aTrack >= 0) {
                mux.start();
                muxStarted = true;
            }
        }
        if (muxStarted) {
            try {
                mux.stop();
            } catch (Throwable t) {
                System.out.println("[SETUP] mux.stop failed: " + t);
            }
        }
        mux.release();
        try {
            venc.stop();
        } catch (Throwable ignored) {
        }
        venc.release();
        try {
            aenc.stop();
        } catch (Throwable ignored) {
        }
        aenc.release();
        System.out.println("[SETUP] mp4 generated: " + out.length() + "B colorFormat=0x" + Integer.toHexString(colorFormat));
    }

    private static int pickColorFormat(MediaCodec codec, String mime) {
        try {
            MediaCodecInfo.CodecCapabilities caps = codec.getCodecInfo().getCapabilitiesForType(mime);
            boolean semi = false, planar = false;
            for (int f : caps.colorFormats) {
                if (f == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) return f;
                if (f == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) semi = true;
                if (f == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) planar = true;
            }
            if (semi) return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
            if (planar) return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
        } catch (Throwable t) {
            System.out.println("[SETUP] pickColorFormat failed: " + t);
        }
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
    }

    /** 往输入缓冲里填一帧灰阶图（带移动竖条）；返回要 queue 的字节数 */
    private static int fillFrame(MediaCodec codec, int index, int colorFormat, int w, int h, int frameIdx) {
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
            try {
                Image img = codec.getInputImage(index);
                if (img != null) {
                    Image.Plane[] planes = img.getPlanes();
                    for (int pi = 0; pi < planes.length; pi++) {
                        Image.Plane pl = planes[pi];
                        ByteBuffer buf = pl.getBuffer();
                        int rowStride = pl.getRowStride();
                        int pixStride = pl.getPixelStride();
                        int pw = pi == 0 ? w : (w + 1) / 2;
                        int ph = pi == 0 ? h : (h + 1) / 2;
                        for (int r = 0; r < ph; r++) {
                            for (int c = 0; c < pw; c++) {
                                byte v = pi > 0 ? (byte) 128 : luma(c, frameIdx, w);
                                buf.put(r * rowStride + c * pixStride, v);
                            }
                        }
                    }
                    return w * h * 3 / 2;
                }
            } catch (Throwable t) {
                System.out.println("[SETUP] fillFrame via Image failed: " + t);
            }
        }
        ByteBuffer buf = codec.getInputBuffer(index);
        if (buf == null) return 0;
        buf.clear();
        int ySize = w * h;
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                buf.put(r * w + c, luma(c, frameIdx, w));
            }
        }
        for (int i = 0; i < ySize / 2; i++) buf.put(ySize + i, (byte) 128);
        return ySize + ySize / 2;
    }

    private static byte luma(int x, int frameIdx, int w) {
        int bar = (frameIdx * 8) % w;
        return (byte) (Math.abs(x - bar) < 12 ? 220 : 70);
    }
}

package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.FFprobeKit;
import com.arthenica.ffmpegkit.Level;
import com.arthenica.ffmpegkit.MediaInformation;
import com.arthenica.ffmpegkit.MediaInformationSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.StreamInformation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ffmpeg 引擎封装（ffmpeg-kit-min，进程内调用）。
 *
 * <p>它存在的意义：系统媒体框架（MediaExtractor/MediaCodec）的设备解封装器只有 10 种
 * （aac/amr/flac/midi/mkv/mp3/mp4/mpeg2/ogg/wav），**avi/flv/rmvb/wmv 一律打不开**，
 * 也没有复杂滤镜。ffmpeg 引擎补的正是这一块。
 *
 * <p>与之前 ffmpeg 两次失败的区别：这里是 NDK/bionic 构建的 libav*，通过 jniLibs 随包分发、
 * 由 System.loadLibrary 正常加载（Android 只禁 execve，不禁 dlopen），不需要 exec、不需要权限；
 * Termux 版是动态链接到 Termux 自己的 stub 库与同名不同 ABI 的 libc++，加载阶段就失败。
 */
final class FfmpegEngine {

    private static final String TAG = "FfmpegEngine";
    /** 回传给模型的输出上限（工具结果总体还有 20k 限制，别把上下文撑爆） */
    private static final int OUTPUT_LIMIT = 6000;

    private static volatile Boolean sAvailable;
    private static volatile String sVersion = "";

    private FfmpegEngine() {
    }

    /** 引擎是否可用（加载 libav* 成功）；失败会被记忆，不反复重试 */
    static boolean available() {
        Boolean cached = sAvailable;
        if (cached != null) return cached;
        synchronized (FfmpegEngine.class) {
            if (sAvailable != null) return sAvailable;
            try {
                FFmpegKitConfig.setLogLevel(Level.AV_LOG_ERROR);
                sVersion = String.valueOf(FFmpegKitConfig.getFFmpegVersion());
                sAvailable = Boolean.TRUE;
            } catch (Throwable t) {
                Log.w(TAG, "ffmpeg 引擎不可用: " + t);
                sAvailable = Boolean.FALSE;
            }
            return sAvailable;
        }
    }

    static String version() {
        if (!available()) return "(不可用)";
        return sVersion;
    }

    /** 一次 ffmpeg 调用的结果 */
    static final class Result {
        boolean success;
        boolean timedOut;
        int returnCode = -1;
        String output = "";
        String error;
        String command = "";
        long ms;

        String describe() {
            if (success) return "成功";
            if (timedOut) return "超时已取消";
            if (error != null) return error;
            return "exit=" + returnCode + (output.isEmpty() ? "" : (" | " + lastLine(output)));
        }

        private static String lastLine(String s) {
            String[] lines = s.trim().split("\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String l = lines[i].trim();
                if (!l.isEmpty()) return l.length() > 200 ? l.substring(l.length() - 200) : l;
            }
            return "";
        }
    }

    /**
     * 执行 ffmpeg（参数数组，不经 shell）。同步等待，超时后 cancel。
     *
     * <p>注意：ffmpeg-kit 只收集 stderr（av_log）里的内容，所以 {@code -version}/{@code -encoders}
     * 这类**写 stdout** 的输出拿不到（真机实测为空串），要拿进程内版本号用 {@link #version()}；
     * 错误原因、进度信息都在 stderr，能正常拿到。
     *
     * @param args      ffmpeg 参数（不含 "ffmpeg" 本身），如 {-y, -i, in.mp4, out.wav}
     * @param timeoutMs 最长等待
     */
    static Result run(String[] args, long timeoutMs) {
        Result r = new Result();
        if (!available()) {
            r.error = "ffmpeg 引擎不可用（libav* 未加载成功）";
            return r;
        }
        StringBuilder cmd = new StringBuilder("ffmpeg");
        for (String a : args) {
            if (a == null) {
                // 实测：argv 里混进 null 指针会在 ffmpeg 的 locate_option 里 strcmp(NULL) 直接 SIGSEGV
                // （整个 App 崩，不是 Java 异常）。这里必须挡在 native 调用之前。
                r.error = "ffmpeg 参数里含 null（内部错误，已阻止调用以避免进程崩溃）";
                Log.w(TAG, "拒绝执行含 null 的 ffmpeg 参数");
                return r;
            }
            cmd.append(' ').append(a);
        }
        r.command = cmd.toString();

        final CountDownLatch latch = new CountDownLatch(1);
        final long t0 = System.currentTimeMillis();
        FFmpegSession session = null;
        try {
            session = FFmpegKit.executeWithArgumentsAsync(args, s -> latch.countDown(), null, null);
            boolean done = latch.await(Math.max(1000L, timeoutMs), TimeUnit.MILLISECONDS);
            if (!done) {
                r.timedOut = true;
                try {
                    FFmpegKit.cancel(session.getSessionId());
                } catch (Throwable ignored) {
                }
                latch.await(5, TimeUnit.SECONDS);
            }
            r.ms = System.currentTimeMillis() - t0;
            ReturnCode rc = session.getReturnCode();
            r.returnCode = rc == null ? -1 : rc.getValue();
            r.success = !r.timedOut && ReturnCode.isSuccess(rc);
            String out = session.getOutput();
            if (out == null || out.trim().isEmpty()) out = session.getAllLogsAsString();
            if (out == null) out = "";
            r.output = out.length() > OUTPUT_LIMIT ? out.substring(out.length() - OUTPUT_LIMIT) : out;
            if (!r.success && !r.timedOut && r.output.trim().isEmpty()) {
                String st = session.getFailStackTrace();
                r.error = "ffmpeg 失败 (exit=" + r.returnCode + ")" + (st == null ? "" : (": " + st));
            }
        } catch (Throwable t) {
            r.ms = System.currentTimeMillis() - t0;
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.w(TAG, "ffmpeg 执行异常 args=" + r.command, t);
        }
        return r;
    }

    /**
     * ffprobe 读媒体信息（能读系统框架读不了的东西，比如 avi/flv/rmvb）。
     * 返回结构见 {@link #mediaInfoJson}。
     */
    static JSONObject probe(Context ctx, String path) throws Exception {
        File local = materialize(ctx, path, null);
        MediaInformationSession session = FFprobeKit.getMediaInformation(local.getAbsolutePath());
        MediaInformation mi = session == null ? null : session.getMediaInformation();
        if (mi == null) {
            String out = session == null ? "" : String.valueOf(session.getOutput());
            throw new IllegalStateException("ffprobe 读不出媒体信息: " + truncate(out, 400));
        }
        return mediaInfoJson(mi);
    }

    /** MediaInformation → JSON（字段名与系统 probe 对齐，便于模型统一理解） */
    static JSONObject mediaInfoJson(MediaInformation mi) throws Exception {
        JSONObject o = new JSONObject();
        o.put("format", orEmpty(mi.getFormat()));
        o.put("format_long", orEmpty(mi.getLongFormat()));
        o.put("duration_sec", num(mi.getDuration()));
        o.put("size_bytes", (long) num(mi.getSize()));
        o.put("bitrate_kbps", (long) num(mi.getBitrate()) / 1000);
        JSONArray tracks = new JSONArray();
        List<StreamInformation> streams = mi.getStreams();
        if (streams != null) {
            for (StreamInformation st : streams) {
                JSONObject t = new JSONObject();
                Long index = st.getIndex();
                t.put("index", index == null ? tracks.length() : index);
                String type = orEmpty(st.getType());
                t.put("type", type);
                t.put("codec", orEmpty(st.getCodec()));
                t.put("codec_long", orEmpty(st.getCodecLong()));
                Long w = st.getWidth(), h = st.getHeight();
                if (w != null && w > 0) t.put("width", w);
                if (h != null && h > 0) t.put("height", h);
                String fps = st.getAverageFrameRate();
                if (fps != null && !fps.startsWith("0/0") && !fps.isEmpty()) t.put("frame_rate", fps);
                if (st.getSampleRate() != null) t.put("sample_rate", st.getSampleRate());
                if (st.getChannelLayout() != null) t.put("channel_layout", st.getChannelLayout());
                if (st.getBitrate() != null) t.put("bitrate_kbps", (long) num(st.getBitrate()) / 1000);
                JSONObject tags = st.getTags();
                if (tags != null && tags.has("language")) t.put("language", tags.optString("language"));
                tracks.put(t);
            }
        }
        o.put("track_count", tracks.length());
        o.put("tracks", tracks);
        return o;
    }

    /**
     * 把输入变成"ffmpeg 能用的本地绝对路径"：本地路径原样返回；
     * content:// 复制到 cacheDir 下的临时文件（{@code cacheName} 为 null 时自动命名）。
     *
     * @param keepTo 需要保留到该文件（content:// 用）；本地文件忽略
     */
    static File materialize(Context ctx, String path, File keepTo) throws Exception {
        if (path == null) throw new IllegalArgumentException("输入路径为空");
        if (path.startsWith("content://")) {
            File target = keepTo != null ? keepTo
                    : new File(ctx.getCacheDir(), "ff_in_" + Integer.toHexString(path.hashCode()) + guessExt(path));
            try (InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(path))) {
                if (in == null) throw new IllegalArgumentException("无法打开 content URI: " + path);
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IllegalStateException("无法创建临时目录: " + parent);
                }
                try (FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
            return target;
        }
        String p = path.startsWith("file://") ? Uri.parse(path).getPath() : path;
        return new File(p);
    }

    private static String guessExt(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) return ".bin";
        String ext = path.substring(dot).replaceAll("[^A-Za-z0-9.]", "");
        return ext.length() > 6 ? ".bin" : ext;
    }

    static String truncate(String s, int limit) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= limit ? t : (t.substring(0, limit) + "...(截断)");
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static double num(String s) {
        if (s == null) return 0;
        try {
            return Double.parseDouble(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 供 /version 之类的简单查询用 */
    static String describeVersion() {
        return String.format(Locale.US, "ffmpeg-kit-min %s (ffmpeg %s)", "8.1.9", version());
    }
}

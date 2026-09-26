package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import com.google.common.collect.ImmutableList;
import com.oilquiz.app.ai.agent.online.AgentWorkspace;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 本地媒体工具箱（media_toolkit）：用系统自带多媒体框架（MediaExtractor / MediaCodec /
 * MediaMuxer / MediaMetadataRetriever）+ Media3 Transformer 完成常见音视频处理，
 * 不依赖 ffmpeg、不联网、不需要任何权限。
 *
 * <p>为什么需要它：内置 Linux 工具箱里没有 ffmpeg（Termux 版依赖大量 stub 库、且 libc++/系统库
 * 名字与 ABI 冲突，已在 56a8821 记录并回退）。而"看视频信息 / 截帧 / 抽音频 / 压一压再发"
 * 这些高频需求，系统框架本身就能做，而且是硬编解码、速度快、体积零成本。
 *
 * <p>能力边界（务必如实告知用户）：
 * <ul>
 *   <li>能解/能的容器取决于设备硬件与系统解码器（mp4/mkv/webm/3gp/mp3/m4a/aac/flac/wav/ogg 等通常可用）；</li>
 *   <li>trim 是"关键帧对齐的无损重封装"，起点会吸附到前一个关键帧，不做逐帧精确剪切；</li>
 *   <li>transcode 依托 MediaCodec 硬件编解码，只支持设备编码器支持的编码（H.264/H.265/AAC 基本都有）；</li>
 *   <li>任意复杂滤镜链（水印时间轴、多路混流、字幕烧录）做不了，那需要 ffmpeg。</li>
 * </ul>
 */
@Tool(
    value = "media_toolkit",
    description = "本地媒体工具箱（系统自带硬解硬编，不依赖 ffmpeg/外部二进制，处理过程不需要权限、不联网）："
        + "probe 看媒体信息（时长/分辨率/码率/帧率/音视频轨/编码器）；frame/thumbnail 截帧出图（可按时间/百分比/帧序号/多帧）；"
        + "extract_audio 无损抽取音轨（m4a/mp3）；to_wav 解码成 WAV（默认 16k 单声道，可喂语音识别）；"
        + "trim 无损剪切（关键帧对齐，秒）；transcode 转码/压缩/改分辨率/换容器（H.264/H.265/AAC，可去音轨）；"
        + "image_ops 图片处理（缩放/裁剪/旋转/翻转/灰度/转格式/压缩）。"
        + "输入支持绝对路径、工作区相对路径、content:// URI（读取外部文件仍受 App 已有存储访问限制）；输出默认落工作区 files/media/。"
        + "能力边界：能处理的格式/编码取决于设备解码器与编码器（avi/flv/rmvb 等冷门容器、时间轴水印/多路混流/字幕烧录等复杂滤镜链不支持）；做不到时明确报错并如实回复用户，不要承诺。",
    category = "media",
    actions = {
        @Action(name = "probe", description = "读取媒体信息：时长、分辨率、帧率、码率、旋转、音视频轨与编码器（视频/音频/图片都可以）"),
        @Action(name = "frame", description = "按时间截帧存为图片（time=秒 或 percent=0-100 或 index=帧序号；count=N 抽 N 张均匀帧）"),
        @Action(name = "thumbnail", description = "取缩略图（默认取 10% 处、最长边 512，适合列表/预览）"),
        @Action(name = "extract_audio", description = "无损抽取音轨（不重新编码）：aac→m4a、mp3→mp3"),
        @Action(name = "to_wav", description = "解码音轨为 WAV（默认 16kHz 单声道 16bit；rate/channels 可调），适合喂语音识别或剪辑"),
        @Action(name = "trim", description = "无损剪切时间区间（start/end 秒，关键帧对齐，不重新编码，MP4 输出）"),
        @Action(name = "transcode", description = "转码/压缩/改分辨率/换容器（video_mime/audio_mime/width/height/scale/bitrate/remove_audio）；实际分辨率由设备编码器对齐决定，返回 output_width/output_height 是真实值"),
        @Action(name = "image_ops", description = "图片处理：resize(width/height/max)、crop=x,y,w,h、rotate=度、flip=h/v、gray、format=png/jpeg/webp、quality")
    },
    params = {
        @Param(name = "action", type = "string", description = "操作: probe/frame/thumbnail/extract_audio/to_wav/trim/transcode/image_ops", required = true),
        @Param(name = "path", type = "string", description = "输入文件：绝对路径、工作区相对路径或 content:// URI", required = true),
        @Param(name = "output", type = "string", description = "输出文件（缺省存工作区 files/media/ 并按动作自动命名）", required = false),
        @Param(name = "time", type = "string", description = "frame 用：截帧时间（秒，可小数，如 3.5）", required = false),
        @Param(name = "percent", type = "string", description = "frame 用：按视频时长百分比取帧（0-100）", required = false),
        @Param(name = "index", type = "string", description = "frame 用：按帧序号取帧（0 开始）", required = false),
        @Param(name = "count", type = "string", description = "frame 用：均匀抽取 N 张（如 9 张做九宫格预览）", required = false),
        @Param(name = "exact", type = "string", description = "frame 用：true=精确帧（慢），默认取最近关键帧", required = false),
        @Param(name = "width", type = "string", description = "输出/缩放宽度（frame/thumbnail/image_ops/transcode）", required = false),
        @Param(name = "height", type = "string", description = "输出/缩放高度（frame/thumbnail/image_ops/transcode）", required = false),
        @Param(name = "max", type = "string", description = "image_ops/thumbnail 用：最长边不超过该像素（等比缩放）", required = false),
        @Param(name = "format", type = "string", description = "输出图片格式: png/jpeg/webp（默认 jpg）", required = false),
        @Param(name = "quality", type = "string", description = "jpeg/webp 质量 1-100（默认 90）", required = false),
        @Param(name = "crop", type = "string", description = "image_ops 用：裁剪区域 x,y,w,h（像素）", required = false),
        @Param(name = "rotate", type = "string", description = "image_ops 用：旋转角度（90/180/270 或任意度数）", required = false),
        @Param(name = "flip", type = "string", description = "image_ops 用：翻转 h=水平 v=垂直", required = false),
        @Param(name = "gray", type = "string", description = "image_ops 用：true=转灰度", required = false),
        @Param(name = "start", type = "string", description = "trim/transcode 用：起始时间（秒，默认 0）", required = false),
        @Param(name = "end", type = "string", description = "trim/transcode 用：结束时间（秒，默认到结尾）", required = false),
        @Param(name = "rate", type = "string", description = "to_wav 用：采样率（默认 16000）", required = false),
        @Param(name = "channels", type = "string", description = "to_wav 用：声道数 1/2（默认 1）", required = false),
        @Param(name = "video_mime", type = "string", description = "transcode 用：h264/h265/av1/keep（默认 keep=不重编视频，仅换容器/重封装）", required = false),
        @Param(name = "audio_mime", type = "string", description = "transcode 用：aac/none/keep（默认 aac；none 等同 remove_audio）", required = false),
        @Param(name = "bitrate", type = "string", description = "transcode 用：视频码率 kbps（如 2000；会强制重编视频）", required = false),
        @Param(name = "scale", type = "string", description = "transcode 用：等比缩放倍数（如 0.5 表示宽高减半）", required = false),
        @Param(name = "remove_audio", type = "string", description = "transcode 用：true=去掉音轨", required = false),
        @Param(name = "timeout", type = "string", description = "transcode 用：最长等待秒数（默认 180，最大 900）", required = false)
    }
)
public class MediaToolkitTool implements AITool {

    private static final String TAG = "MediaToolkitTool";
    private static final long TRANSCODE_DEFAULT_TIMEOUT_SEC = 180;

    private final Context context;

    public MediaToolkitTool(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String getName() {
        return "media_toolkit";
    }

    @Override
    public String getDescription() {
        return "本地媒体工具箱（系统硬解硬编，不依赖 ffmpeg/外部二进制，处理过程不需要权限、不联网）："
                + "probe 媒体信息；frame/thumbnail 截帧出图；extract_audio 无损抽音轨；to_wav 转 WAV（默认16k单声道）；"
                + "trim 无损剪切；transcode 转码/压缩/改分辨率/换容器；image_ops 图片缩放裁剪旋转转格式。"
                + "能否处理取决于设备解码/编码器，不支持的容器或滤镜会明确报错";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> m = new HashMap<>();
        m.put("action", "操作: probe(媒体信息)/frame(截帧)/thumbnail(缩略图)/extract_audio(无损抽音轨)/to_wav(转WAV)/trim(无损剪切)/transcode(转码压缩)/image_ops(图片处理)");
        m.put("path", "输入文件：绝对路径、工作区相对路径或 content:// URI");
        m.put("output", "输出文件；缺省存工作区 files/media/ 并按动作自动命名");
        m.put("time", "frame 用：截帧时间（秒，可小数，如 3.5）");
        m.put("percent", "frame 用：按视频时长百分比取帧（0-100）");
        m.put("index", "frame 用：按帧序号取帧（0 开始）");
        m.put("count", "frame 用：均匀抽 N 张");
        m.put("exact", "frame 用：true=精确帧（慢），默认最近关键帧");
        m.put("width", "输出宽度（frame/thumbnail/image_ops/transcode）");
        m.put("height", "输出高度（frame/thumbnail/image_ops/transcode）");
        m.put("max", "最长边上限（image_ops/thumbnail，等比缩放）");
        m.put("format", "输出图片格式: png/jpeg/webp（默认 jpg）");
        m.put("quality", "jpeg/webp 质量 1-100（默认 90）");
        m.put("crop", "image_ops 用：裁剪区域 x,y,w,h（原图像素，坐标系=已按 EXIF 转正后的方向）");
        m.put("rotate", "image_ops 用：旋转角度（90/180/270 或任意度数）");
        m.put("flip", "image_ops 用：翻转 h=水平 v=垂直");
        m.put("gray", "image_ops 用：true=转灰度");
        m.put("start", "trim/transcode 用：起始时间（秒，默认 0）");
        m.put("end", "trim/transcode 用：结束时间（秒，默认到结尾）");
        m.put("rate", "to_wav 用：采样率（默认 16000）");
        m.put("channels", "to_wav 用：声道数 1/2（默认 1）");
        m.put("video_mime", "transcode 用：h264/h265/av1/keep（默认 keep）");
        m.put("audio_mime", "transcode 用：aac/none/keep（默认 aac）");
        m.put("bitrate", "transcode 用：视频码率 kbps");
        m.put("scale", "transcode 用：等比缩放倍数（0.5=宽高减半）；实际分辨率由设备编码器对齐决定，以返回的 output_width/height 为准");
        m.put("remove_audio", "transcode 用：true=去掉音轨");
        m.put("timeout", "transcode 用：最长等待秒数（默认 180，最大 900）");
        return m;
    }

    // ==================================================================
    // 入口
    // ==================================================================

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        String action = str(parameters, "action", "probe").toLowerCase(Locale.ROOT).trim();
        try {
            switch (action) {
                case "probe":
                case "info":
                case "ffprobe":
                    return probe(parameters);
                case "frame":
                case "screenshot":
                case "grab":
                    return frames(parameters, false);
                case "thumbnail":
                case "thumb":
                    return frames(parameters, true);
                case "extract_audio":
                case "extractaudio":
                case "audio":
                    return extractAudio(parameters);
                case "to_wav":
                case "wav":
                    return toWav(parameters);
                case "trim":
                case "cut":
                case "clip":
                    return trim(parameters);
                case "transcode":
                case "convert":
                case "compress":
                    return transcode(parameters);
                case "image_ops":
                case "image":
                case "photo":
                    return imageOps(parameters);
                default:
                    return AIToolResult.fail("未知 action: " + action
                            + "（支持 probe/frame/thumbnail/extract_audio/to_wav/trim/transcode/image_ops）");
            }
        } catch (Throwable t) {
            Log.w(TAG, "media_toolkit 执行失败 action=" + action, t);
            return AIToolResult.fail("media_toolkit 执行失败（" + action + "）: " + describe(t));
        }
    }

    /** Python 侧（android_media 模块）调用入口：返回 JSON 字符串 {success, result|error} */
    public static String runJson(Context ctx, Map<String, Object> params) {
        JSONObject o = new JSONObject();
        try {
            AIToolResult r = new MediaToolkitTool(ctx).execute(params);
            o.put("success", r.isSuccess());
            if (r.isSuccess()) {
                o.put("result", String.valueOf(r.getResult()));
            } else {
                o.put("error", String.valueOf(r.getErrorMessage()));
            }
        } catch (Throwable t) {
            try {
                o.put("success", false);
                o.put("error", describe(t));
            } catch (Exception ignored) {
            }
        }
        return o.toString();
    }

    // ==================================================================
    // probe：媒体信息
    // ==================================================================

    private AIToolResult probe(Map<String, Object> p) throws Exception {
        String path = requirePath(p);
        JSONObject out = new JSONObject();
        out.put("path", path);
        File f = localFile(path);
        if (f != null && f.exists()) {
            out.put("file", f.getAbsolutePath());
            out.put("size_bytes", f.length());
            out.put("size_text", humanSize(f.length()));
        }
        out.put("container", guessContainer(path));

        // 图片：走 BitmapFactory 拿尺寸，再补 EXIF
        if (isImagePath(path) || !hasAnyTrack(path)) {
            JSONObject img = new JSONObject();
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            if (f != null) {
                BitmapFactory.decodeFile(f.getAbsolutePath(), opt);
            } else {
                try (java.io.InputStream is = openInput(path)) {
                    BitmapFactory.decodeStream(is, null, opt);
                }
            }
            if (opt.outWidth > 0 && opt.outHeight > 0) {
                img.put("width", opt.outWidth);
                img.put("height", opt.outHeight);
                img.put("mime", opt.outMimeType);
                if (f != null) {
                    try {
                        ExifInterface exif = new ExifInterface(f.getAbsolutePath());
                        img.put("orientation", exif.getAttribute(ExifInterface.TAG_ORIENTATION));
                        String dt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
                        if (dt != null) img.put("datetime", dt);
                    } catch (Throwable ignored) {
                    }
                }
                out.put("type", "image");
                out.put("image", img);
                return AIToolResult.success(out.toString(2));
            }
            // 不是图片也不是能解码的媒体 → 明确报错（描述里承诺的"做不到会明确报错"必须兑现）
            return AIToolResult.fail("系统媒体框架无法识别该文件（container=" + guessContainer(path)
                    + "）：设备解码器读不到轨道，可能是不支持的容器/编码（avi/flv/rmvb/wmv 等）。"
                    + "请如实告诉用户\"设备媒体框架不支持这个格式\"，不要硬凑一条能跑但结果是错的路子。");
        }

        // 通用元数据（MediaMetadataRetriever）
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            setSource(mmr, path);
            JSONObject meta = new JSONObject();
            putIf(meta, "duration_sec", round2(metaLong(mmr, MediaMetadataRetriever.METADATA_KEY_DURATION) / 1000.0));
            putIf(meta, "width", metaInt(mmr, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            putIf(meta, "height", metaInt(mmr, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            putIf(meta, "rotation", metaInt(mmr, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            putIf(meta, "bitrate_kbps", metaInt(mmr, MediaMetadataRetriever.METADATA_KEY_BITRATE) / 1000);
            putIf(meta, "capture_framerate", metaStr(mmr, MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE));
            putIf(meta, "frame_count", metaStr(mmr, MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT));
            putIf(meta, "mime", mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE));
            putIf(meta, "date", mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE));
            meta.put("has_video", "yes".equals(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)));
            meta.put("has_audio", "yes".equals(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)));
            out.put("type", meta.optBoolean("has_video") ? "video" : "audio");
            out.put("metadata", meta);
        } catch (Throwable t) {
            out.put("metadata_error", describe(t));
        } finally {
            releaseQuietly(mmr);
        }

        // 轨道明细（MediaExtractor）
        MediaExtractor ex = new MediaExtractor();
        try {
            setSource(ex, path);
            JSONArray tracks = new JSONArray();
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat fmt = ex.getTrackFormat(i);
                JSONObject t = new JSONObject();
                String mime = fmt.containsKey(MediaFormat.KEY_MIME) ? fmt.getString(MediaFormat.KEY_MIME) : null;
                t.put("index", i);
                t.put("mime", mime == null ? "" : mime);
                t.put("type", mime == null ? "?" : (mime.startsWith("video/") ? "video" : (mime.startsWith("audio/") ? "audio" : "other")));
                if (mime != null) t.put("codec", decoderName(mime));
                putIf(t, "width", fmtInt(fmt, MediaFormat.KEY_WIDTH));
                putIf(t, "height", fmtInt(fmt, MediaFormat.KEY_HEIGHT));
                putIf(t, "frame_rate", fmtFloat(fmt, MediaFormat.KEY_FRAME_RATE));
                putIf(t, "sample_rate", fmtInt(fmt, MediaFormat.KEY_SAMPLE_RATE));
                putIf(t, "channel_count", fmtInt(fmt, MediaFormat.KEY_CHANNEL_COUNT));
                putIf(t, "bitrate_kbps", fmtInt(fmt, MediaFormat.KEY_BIT_RATE) / 1000);
                putIf(t, "duration_sec", round2(fmtLong(fmt, MediaFormat.KEY_DURATION) / 1000000.0));
                putIf(t, "language", saneLanguage(fmtStr(fmt, MediaFormat.KEY_LANGUAGE)));
                putIf(t, "profile", fmtInt(fmt, MediaFormat.KEY_PROFILE));
                putIf(t, "level", fmtInt(fmt, MediaFormat.KEY_LEVEL));
                putIf(t, "max_input_size", fmtInt(fmt, MediaFormat.KEY_MAX_INPUT_SIZE));
                tracks.put(t);
            }
            out.put("track_count", ex.getTrackCount());
            out.put("tracks", tracks);
        } catch (Throwable t) {
            out.put("tracks_error", describe(t));
        } finally {
            releaseQuietly(ex);
        }
        return AIToolResult.success(out.toString(2));
    }

    private boolean hasAnyTrack(String path) {
        MediaExtractor ex = new MediaExtractor();
        try {
            setSource(ex, path);
            return ex.getTrackCount() > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            releaseQuietly(ex);
        }
    }

    // ==================================================================
    // frame / thumbnail：截帧
    // ==================================================================

    private AIToolResult frames(Map<String, Object> p, boolean thumb) throws Exception {
        String path = requirePath(p);
        boolean exact = boolParam(p, "exact", false);
        String fmt = str(p, "format", thumb ? "jpg" : "jpg");
        int quality = clamp(intParam(p, "quality", 90), 1, 100);

        int srcW = intParam(p, "width", 0);
        int srcH = intParam(p, "height", 0);
        if (thumb) {
            int max = intParam(p, "max", 512);
            if (srcW <= 0 && srcH <= 0) srcW = max;
        }

        // 图片输入：直接当图片处理（缩放/转格式）
        File inFile = localFile(path);
        if (isImagePath(path) || !hasVideoTrack(path)) {
            Bitmap bmp = loadBitmap(path, inFile, Math.max(srcW, srcH));
            if (bmp == null) return AIToolResult.fail("无法解码该文件为图片/视频帧: " + path);
            try {
                bmp = scaleBitmap(bmp, srcW, srcH, intParam(p, "max", 0), true);
                File out = resolveOutput(p, baseName(path) + (thumb ? "_thumb." : "_img.") + extFor(fmt));
                int n = writeBitmap(bmp, out, fmt, quality);
                return AIToolResult.success(simple("image", out, n, "宽高=" + bmp.getWidth() + "x" + bmp.getHeight()));
            } finally {
                bmp.recycle();
            }
        }

        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        List<Double> times = new ArrayList<>();
        double duration = 0;
        try {
            setSource(mmr, path);
            duration = metaLong(mmr, MediaMetadataRetriever.METADATA_KEY_DURATION) / 1000.0;
            int count = intParam(p, "count", 0);
            int index = intParam(p, "index", -1);
            if (count > 1) {
                double d = duration > 0 ? duration : 1;
                for (int i = 0; i < count; i++) times.add(d * i / count);
            } else if (index >= 0) {
                Bitmap bmp;
                try {
                    bmp = mmr.getFrameAtIndex(index);
                } catch (Throwable t) {
                    bmp = null;
                }
                if (bmp == null) return AIToolResult.fail("取第 " + index + " 帧失败（该文件可能不支持按帧序号取帧，可改用 time= 秒）");
                try {
                    bmp = scaleBitmap(bmp, srcW, srcH, intParam(p, "max", 0), true);
                    File out = resolveOutput(p, baseName(path) + "_f" + index + "." + extFor(fmt));
                    int n = writeBitmap(bmp, out, fmt, quality);
                    return AIToolResult.success(simple("frame", out, n, "帧序号=" + index));
                } finally {
                    bmp.recycle();
                }
            } else {
                double t = 0;
                if (p.get("percent") != null) {
                    double pct = doubleParam(p, "percent", 10);
                    t = duration * pct / 100.0;
                } else if (p.get("time") != null) {
                    t = doubleParam(p, "time", 0);
                } else if (thumb) {
                    t = duration * 0.1;
                }
                times.add(Math.max(0, t));
            }

            JSONArray saved = new JSONArray();
            for (int i = 0; i < times.size(); i++) {
                double t = times.get(i);
                long us = (long) (t * 1000000L);
                Bitmap bmp = grabFrame(mmr, us, exact ? MediaMetadataRetriever.OPTION_CLOSEST
                        : MediaMetadataRetriever.OPTION_CLOSEST_SYNC, srcW, srcH,
                        intParam(p, "max", 0));
                if (bmp == null) continue;
                try {
                    String name = times.size() > 1
                            ? baseName(path) + "_" + (i + 1) + "." + extFor(fmt)
                            : (thumb ? baseName(path) + "_thumb." : baseName(path) + "_" + fmtTime(t) + ".") + extFor(fmt);
                    File out = times.size() > 1 ? uniqueOut(p, name, i) : resolveOutput(p, name);
                    int n = writeBitmap(bmp, out, fmt, quality);
                    JSONObject item = new JSONObject();
                    item.put("file", out.getAbsolutePath());
                    item.put("time_sec", round2(t));
                    item.put("size_bytes", n);
                    item.put("width", bmp.getWidth());
                    item.put("height", bmp.getHeight());
                    saved.put(item);
                } finally {
                    bmp.recycle();
                }
            }
            if (saved.length() == 0) {
                return AIToolResult.fail("截帧失败：没有取到任何帧（时间点可能超出时长 " + round2(duration) + "s）");
            }
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("count", saved.length());
            out.put("duration_sec", round2(duration));
            out.put("files", saved);
            out.put("first_file", saved.getJSONObject(0).getString("file"));
            return AIToolResult.success(out.toString(2));
        } catch (Throwable t) {
            return AIToolResult.fail("截帧失败: " + describe(t));
        } finally {
            releaseQuietly(mmr);
        }
    }

    private Bitmap grabFrame(MediaMetadataRetriever mmr, long timeUs, int option,
                             int wantW, int wantH, int maxSide) {
        // 只给了一边时按原视频比例补另一边，避免拉伸变形
        int w = wantW, h = wantH;
        if (maxSide > 0) {
            int vw = metaInt(mmr, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            int vh = metaInt(mmr, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (vw > 0 && vh > 0) {
                double scale = maxSide / (double) Math.max(vw, vh);
                if (scale < 1) {
                    w = (int) Math.round(vw * scale);
                    h = (int) Math.round(vh * scale);
                }
            }
        }
        if (w > 0 && h <= 0) {
            int vw = metaInt(mmr, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            int vh = metaInt(mmr, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (vw > 0 && vh > 0) h = (int) Math.round(w * (double) vh / vw);
        } else if (h > 0 && w <= 0) {
            int vw = metaInt(mmr, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            int vh = metaInt(mmr, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (vw > 0 && vh > 0) w = (int) Math.round(h * (double) vw / vh);
        }
        try {
            if (w > 0 && h > 0) {
                return mmr.getScaledFrameAtTime(timeUs, option, w, h);
            }
            return mmr.getFrameAtTime(timeUs, option);
        } catch (Throwable t) {
            Log.w(TAG, "取帧失败 t=" + timeUs, t);
            return null;
        }
    }

    // ==================================================================
    // extract_audio：无损抽取音轨
    // ==================================================================

    private AIToolResult extractAudio(Map<String, Object> p) throws Exception {
        String path = requirePath(p);
        MediaExtractor ex = new MediaExtractor();
        try {
            setSource(ex, path);
            int idx = findTrack(ex, "audio/");
            if (idx < 0) return AIToolResult.fail("该文件没有音轨: " + path);
            MediaFormat fmt = ex.getTrackFormat(idx);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            boolean raw = MimeTypes.AUDIO_MPEG.equals(mime) || "audio/mpeg".equals(mime)
                    || "audio/3gpp".equals(mime) || "audio/amr".equals(mime);
            String defaultExt = raw ? ("audio/3gpp".equals(mime) || "audio/amr".equals(mime) ? ".amr" : ".mp3") : ".m4a";
            File out = resolveOutputExt(p, baseName(path) + "_audio", defaultExt);
            ex.selectTrack(idx);

            if (raw) {
                long size = rawCopy(ex, out);
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("mode", "raw-copy(无损原样导出)");
                o.put("mime", mime);
                o.put("file", out.getAbsolutePath());
                o.put("size_bytes", size);
                return AIToolResult.success(o.toString(2));
            }

            int outFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
            if (mime != null && (mime.startsWith("audio/opus") || mime.startsWith("audio/vorbis"))) {
                outFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM;
            }
            MediaMuxer muxer = new MediaMuxer(out.getAbsolutePath(), outFormat);
            long size;
            try {
                int track = muxer.addTrack(fmt);
                muxer.start();
                size = copySamples(ex, muxer, track, 0, -1, true);
                muxer.stop();
            } finally {
                releaseQuietly(muxer);
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("mode", "remux(无损重封装,未重新编码)");
            o.put("mime", mime == null ? "" : mime);
            o.put("file", out.getAbsolutePath());
            o.put("size_bytes", out.length());
            o.put("samples_bytes", size);
            return AIToolResult.success(o.toString(2));
        } finally {
            releaseQuietly(ex);
        }
    }

    private long rawCopy(MediaExtractor ex, File out) throws IOException {
        long total = 0;
        ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
        try (BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
            while (true) {
                int size = ex.readSampleData(buf, 0);
                if (size < 0) break;
                byte[] data = new byte[size];
                buf.position(0);
                buf.limit(size);
                buf.get(data);
                os.write(data);
                total += size;
                ex.advance();
            }
        }
        return total;
    }

    /** 把样本拷进 muxer（无损，不解码）。startUs/endUs 为时间过滤（endUs<0 表示到结尾），rebase=时间戳从 0 起。 */
    private long copySamples(MediaExtractor ex, MediaMuxer muxer, int trackIndex,
                             long startUs, long endUs, boolean rebase) {
        ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long total = 0;
        long offset = Long.MIN_VALUE;
        while (true) {
            long pts = ex.getSampleTime();
            if (pts < 0) break;
            if (endUs > 0 && pts > endUs) break;
            int size = ex.readSampleData(buf, 0);
            if (size < 0) break;
            if (pts >= startUs) {
                if (offset == Long.MIN_VALUE) offset = pts;
                info.set(0, size, rebase ? pts - offset : pts, ex.getSampleFlags());
                muxer.writeSampleData(trackIndex, buf, info);
                total += size;
            }
            ex.advance();
        }
        return total;
    }

    // ==================================================================
    // to_wav：解码为 WAV
    // ==================================================================

    private AIToolResult toWav(Map<String, Object> p) throws Exception {
        String path = requirePath(p);
        int rate = clamp(intParam(p, "rate", 16000), 8000, 48000);
        int channels = clamp(intParam(p, "channels", 1), 1, 2);
        File out = resolveOutputExt(p, baseName(path) + "_" + (rate / 1000) + "k", ".wav");

        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            setSource(ex, path);
            int idx = findTrack(ex, "audio/");
            if (idx < 0) return AIToolResult.fail("该文件没有音轨，无法转 WAV: " + path);
            MediaFormat fmt = ex.getTrackFormat(idx);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            int srcRate = fmtInt(fmt, MediaFormat.KEY_SAMPLE_RATE);
            int srcCh = fmtInt(fmt, MediaFormat.KEY_CHANNEL_COUNT);
            if (srcRate <= 0) srcRate = 44100;
            if (srcCh <= 0) srcCh = 1;
            ex.selectTrack(idx);

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();

            WavWriter writer = new WavWriter(out, rate, channels, srcRate, srcCh);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            int pcmEncoding = fmtInt(fmt, MediaFormat.KEY_PCM_ENCODING);
            long timeoutAt = System.currentTimeMillis() + 10 * 60 * 1000L;

            while (!outputDone) {
                if (System.currentTimeMillis() > timeoutAt) {
                    throw new IOException("解码超时（文件过大或解码器卡住）");
                }
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                        int size = ex.readSampleData(inBuf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        short[] pcm = toShorts(outBuf, info.size, pcmEncoding);
                        writer.feed(pcm, srcRate, srcCh);
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat nf = codec.getOutputFormat();
                    int nr = fmtInt(nf, MediaFormat.KEY_SAMPLE_RATE);
                    int nc = fmtInt(nf, MediaFormat.KEY_CHANNEL_COUNT);
                    int pe = fmtInt(nf, MediaFormat.KEY_PCM_ENCODING);
                    if (pe != 0) pcmEncoding = pe;
                    writer.updateSource(nr > 0 ? nr : srcRate, nc > 0 ? nc : srcCh);
                }
            }
            long bytes = writer.finish();
            if (bytes <= 0L) {
                return AIToolResult.fail("解码结果为空（文件可能损坏或没有可解码音频）");
            }
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("file", out.getAbsolutePath());
            o.put("size_bytes", out.length());
            o.put("sample_rate", rate);
            o.put("channels", channels);
            o.put("duration_sec", round2(bytes / (double) (rate * channels * 2)));
            o.put("format", "WAV PCM 16bit");
            return AIToolResult.success(o.toString(2));
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignored) {
                }
                releaseQuietly(codec);
            }
            releaseQuietly(ex);
        }
    }

    private static short[] toShorts(ByteBuffer buf, int size, int pcmEncoding) {
        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            int n = size / 4;
            float[] f = new float[n];
            buf.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(f, 0, n);
            short[] s = new short[n];
            for (int i = 0; i < n; i++) {
                float v = Math.max(-1f, Math.min(1f, f[i]));
                s[i] = (short) (v * 32767);
            }
            return s;
        }
        int n = size / 2;
        short[] s = new short[n];
        buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s, 0, n);
        return s;
    }

    /** 流式写 WAV：边解码边混音/重采样落盘，内存占用与文件长度无关。 */
    private static final class WavWriter {
        private final File file;
        private final DataOutputStream out;
        private final int outRate;
        private final int outChannels;
        private int srcRate;
        private int srcChannels;
        private double phase = 0;
        private long baseFrames = 0;
        private long dataBytes = 0;

        WavWriter(File file, int outRate, int outChannels, int srcRate, int srcChannels) throws IOException {
            this.file = file;
            this.out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file), 1 << 16));
            this.outRate = outRate;
            this.outChannels = outChannels;
            this.srcRate = srcRate;
            this.srcChannels = srcChannels;
            writeHeader();
        }

        void updateSource(int rate, int channels) {
            if (rate > 0) this.srcRate = rate;
            if (channels > 0) this.srcChannels = channels;
        }

        private double step() {
            return srcRate / (double) outRate;
        }

        private void writeHeader() throws IOException {
            out.writeBytes("RIFF");
            writeIntLE(0);
            out.writeBytes("WAVEfmt ");
            writeIntLE(16);
            writeShortLE((short) 1);
            writeShortLE((short) outChannels);
            writeIntLE(outRate);
            writeIntLE(outRate * outChannels * 2);
            writeShortLE((short) (outChannels * 2));
            writeShortLE((short) 16);
            out.writeBytes("data");
            writeIntLE(0);
        }

        /** 喂入一段交错 PCM16；frames = 每声道采样数 */
        void feed(short[] pcm, int srcRate, int srcChannels) throws IOException {
            updateSource(srcRate, srcChannels);
            if (pcm.length == 0) return;
            int n = pcm.length / Math.max(1, this.srcChannels);
            if (n <= 0) return;
            if (this.srcChannels == 1) {
                resampleMono(pcm, n);
            } else {
                short[] mono = new short[n];
                for (int i = 0, j = 0; i + this.srcChannels <= pcm.length; i += this.srcChannels, j++) {
                    long sum = 0;
                    for (int c = 0; c < this.srcChannels; c++) sum += pcm[i + c];
                    mono[j] = (short) (sum / this.srcChannels);
                }
                resampleMono(mono, n);
            }
            baseFrames += n;
        }

        private void resampleMono(short[] mono, int n) throws IOException {
            double step = step();
            while (phase < baseFrames + n - 1) {
                int i0 = (int) (phase - baseFrames);
                double frac = phase - baseFrames - i0;
                double v = mono[i0] * (1 - frac) + mono[i0 + 1] * frac;
                writeSample((short) Math.max(-32768, Math.min(32767, Math.round(v))));
                phase += step;
            }
        }

        private void writeSample(short s) throws IOException {
            for (int c = 0; c < outChannels; c++) {
                out.writeByte(s & 0xFF);
                out.writeByte((s >> 8) & 0xFF);
            }
            dataBytes += 2L * outChannels;
        }

        private void writeIntLE(int v) throws IOException {
            out.writeByte(v & 0xFF);
            out.writeByte((v >> 8) & 0xFF);
            out.writeByte((v >> 16) & 0xFF);
            out.writeByte((v >> 24) & 0xFF);
        }

        private void writeShortLE(short v) throws IOException {
            out.writeByte(v & 0xFF);
            out.writeByte((v >> 8) & 0xFF);
        }

        /** 收尾：回填 RIFF/data 长度（不补长度的话播放器会当成损坏文件），返回数据字节数 */
        long finish() throws IOException {
            out.flush();
            out.close();
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.seek(4);
                raf.write(intLE((int) (36 + dataBytes)));
                raf.seek(40);
                raf.write(intLE((int) dataBytes));
            }
            return dataBytes;
        }

        private static byte[] intLE(int v) {
            return new byte[]{(byte) (v & 0xFF), (byte) ((v >> 8) & 0xFF), (byte) ((v >> 16) & 0xFF), (byte) ((v >> 24) & 0xFF)};
        }
    }

    // ==================================================================
    // trim：无损剪切（关键帧对齐重封装）
    // ==================================================================

    private AIToolResult trim(Map<String, Object> p) throws Exception {
        String path = requirePath(p);
        double start = doubleParam(p, "start", 0);
        double end = doubleParam(p, "end", -1);
        String ext = str(p, "format", "");
        File out = resolveOutputExt(p, baseName(path) + "_trim", ext.isEmpty() ? ".mp4" : ("." + ext.replace(".", "")));

        MediaExtractor ex = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            setSource(ex, path);
            int trackCount = ex.getTrackCount();
            if (trackCount == 0) return AIToolResult.fail("文件没有可读取的轨道: " + path);
            muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int[] trackMap = new int[trackCount];
            for (int i = 0; i < trackCount; i++) {
                MediaFormat fmt = ex.getTrackFormat(i);
                trackMap[i] = muxer.addTrack(fmt);
                ex.selectTrack(i);
            }
            int rotation = readRotation(path);
            if (rotation != 0) {
                try {
                    muxer.setOrientationHint(rotation);
                } catch (Throwable ignored) {
                }
            }
            long startUs = (long) (start * 1000000L);
            long endUs = end > 0 ? (long) (end * 1000000L) : -1;
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long offsetPts = Long.MIN_VALUE;
            long firstPts = -1;
            long total = 0;
            muxer.start();
            while (true) {
                long pts = ex.getSampleTime();
                if (pts < 0) break;
                if (endUs > 0 && pts > endUs) break;
                int size = ex.readSampleData(buf, 0);
                if (size < 0) break;
                if (offsetPts == Long.MIN_VALUE) offsetPts = pts;
                if (firstPts < 0) firstPts = pts;
                int trackIndex = ex.getSampleTrackIndex();
                if (trackIndex >= 0) {
                    info.set(0, size, pts - offsetPts, ex.getSampleFlags());
                    muxer.writeSampleData(trackMap[trackIndex], buf, info);
                    total += size;
                }
                ex.advance();
            }
            muxer.stop();
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("mode", "remux(无损重封装,未重新编码)");
            o.put("file", out.getAbsolutePath());
            o.put("size_bytes", out.length());
            o.put("requested_start_sec", round2(start));
            o.put("requested_end_sec", end > 0 ? round2(end) : -1);
            o.put("actual_start_sec", round2(firstPts / 1000000.0));
            o.put("note", "起点已吸附到前一个关键帧（无损剪切的必然结果）");
            return AIToolResult.success(o.toString(2));
        } finally {
            releaseQuietly(muxer);
            releaseQuietly(ex);
        }
    }

    // ==================================================================
    // transcode：Media3 Transformer 转码/压缩
    // ==================================================================

    private AIToolResult transcode(Map<String, Object> p) throws Exception {
        final String path = requirePath(p);
        File inFile = localFile(path);
        if (inFile == null || !inFile.isFile()) {
            return AIToolResult.fail("transcode 需要本地文件（不支持 content://，可先用 workspace 复制到本地）: " + path);
        }
        File out = resolveOutputExt(p, baseName(path) + "_out", ".mp4");
        if (out.exists() && !out.delete()) {
            return AIToolResult.fail("输出文件已存在且无法覆盖: " + out.getAbsolutePath());
        }

        String vm = str(p, "video_mime", "keep");
        String am = str(p, "audio_mime", "aac");
        boolean removeAudio = boolParam(p, "remove_audio", false) || "none".equalsIgnoreCase(am);
        int bitrateKbps = intParam(p, "bitrate", 0);
        double scale = doubleParam(p, "scale", 0);
        int wantW = intParam(p, "width", 0);
        int wantH = intParam(p, "height", 0);
        double start = doubleParam(p, "start", 0);
        double end = doubleParam(p, "end", -1);
        long timeoutSec = clamp(intParam(p, "timeout", (int) TRANSCODE_DEFAULT_TIMEOUT_SEC), 10, 900);

        // 目标分辨率
        if (scale > 0 && scale != 1.0) {
            int vw = 0, vh = 0;
            try (TrackProbe tp = new TrackProbe(path, context)) {
                vw = tp.width;
                vh = tp.height;
            }
            if (vw > 0 && vh > 0) {
                wantW = even((int) Math.round(vw * scale));
                wantH = even((int) Math.round(vh * scale));
            }
        }
        if (wantW > 0 && wantH <= 0) {
            try (TrackProbe tp = new TrackProbe(path, context)) {
                if (tp.width > 0 && tp.height > 0) wantH = even((int) Math.round(wantW * (double) tp.height / tp.width));
            }
        } else if (wantH > 0 && wantW <= 0) {
            try (TrackProbe tp = new TrackProbe(path, context)) {
                if (tp.width > 0 && tp.height > 0) wantW = even((int) Math.round(wantH * (double) tp.width / tp.height));
            }
        }

        final int fWantW = wantW, fWantH = wantH, fBitrate = bitrateKbps;
        final boolean fRemoveAudio = removeAudio;
        final String fVideoMime = vm, fAudioMime = am;
        final double fStart = start, fEnd = end;

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> error = new AtomicReference<>();
        final AtomicReference<ExportResult> result = new AtomicReference<>();
        final AtomicBoolean finished = new AtomicBoolean(false);
        final AtomicReference<Transformer> transRef = new AtomicReference<>();

        HandlerThread ht = new HandlerThread("media-transcode");
        ht.start();
        final Looper looper = ht.getLooper();
        new Handler(looper).post(() -> {
            try {
                Transformer.Builder builder = new Transformer.Builder(context).setLooper(looper);
                if (!"keep".equalsIgnoreCase(fVideoMime) && !fVideoMime.isEmpty()) {
                    String mime = mimeOf(fVideoMime, true);
                    if (mime == null) {
                        error.set("不支持的 video_mime: " + fVideoMime + "（可用 h264/h265/av1/keep）");
                        latch.countDown();
                        return;
                    }
                    builder.setVideoMimeType(mime);
                }
                if (!fRemoveAudio) {
                    if ("keep".equalsIgnoreCase(fAudioMime) || fAudioMime.isEmpty()) {
                        // keep：不设置即保持原样（配合无视频效果时是纯重封装）
                    } else {
                        String mime = mimeOf(fAudioMime, false);
                        if (mime == null) {
                            error.set("不支持的 audio_mime: " + fAudioMime + "（可用 aac/mp3/opus/keep/none）");
                            latch.countDown();
                            return;
                        }
                        builder.setAudioMimeType(mime);
                    }
                } else {
                    builder.setRemoveAudio(true);
                }
                if (fBitrate > 0) {
                    builder.setEncoderFactory(new DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder()
                                    .setBitrate(fBitrate * 1000)
                                    .build())
                            .build());
                }
                builder.addListener(new Transformer.Listener() {
                    private void done(ExportResult r) {
                        if (finished.compareAndSet(false, true)) {
                            result.set(r);
                            latch.countDown();
                        }
                    }

                    private void fail(Exception e) {
                        if (finished.compareAndSet(false, true)) {
                            error.set(describe(e));
                            latch.countDown();
                        }
                    }

                    @Override
                    public void onCompleted(androidx.media3.transformer.Composition composition, ExportResult exportResult) {
                        done(exportResult);
                    }

                    @Override
                    public void onTransformationCompleted(MediaItem mediaItem) {
                        done(null);
                    }

                    @Override
                    public void onError(androidx.media3.transformer.Composition composition, ExportResult exportResult, ExportException exportException) {
                        fail(exportException);
                    }

                    @Override
                    public void onTransformationError(MediaItem mediaItem, Exception exception) {
                        fail(exception);
                    }
                });

                MediaItem.Builder mb = new MediaItem.Builder().setUri(Uri.fromFile(inFile));
                if (fStart > 0 || fEnd > 0) {
                    MediaItem.ClippingConfiguration.Builder cb = new MediaItem.ClippingConfiguration.Builder();
                    if (fStart > 0) cb.setStartPositionMs((long) (fStart * 1000));
                    if (fEnd > 0) cb.setEndPositionMs((long) (fEnd * 1000));
                    mb.setClippingConfiguration(cb.build());
                }
                EditedMediaItem.Builder ib = new EditedMediaItem.Builder(mb.build());
                if (fWantW > 0 && fWantH > 0) {
                    Presentation presentation = Presentation.createForWidthAndHeight(
                            fWantW, fWantH, Presentation.LAYOUT_SCALE_TO_FIT);
                    ib.setEffects(new Effects(ImmutableList.<AudioProcessor>of(),
                            ImmutableList.<Effect>of(presentation)));
                }
                if (fRemoveAudio) ib.setRemoveAudio(true);

                Transformer transformer = builder.build();
                transRef.set(transformer);
                transformer.start(ib.build(), out.getAbsolutePath());
            } catch (Throwable t) {
                if (finished.compareAndSet(false, true)) {
                    error.set(describe(t));
                    latch.countDown();
                }
            }
        });

        boolean ok;
        try {
            ok = latch.await(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            ok = false;
        }
        if (!ok) {
            Transformer t = transRef.get();
            if (t != null) {
                try {
                    t.cancel();
                } catch (Throwable ignored) {
                }
            }
            ht.quitSafely();
            return AIToolResult.fail("转码超时（>" + timeoutSec + "s）已取消；可提高 timeout 或改用 trim/更低分辨率");
        }
        ht.quitSafely();
        if (error.get() != null) {
            return AIToolResult.fail("转码失败: " + error.get());
        }
        if (!out.exists() || out.length() <= 0) {
            return AIToolResult.fail("转码没有产生输出文件（可能源文件没有可转换的轨道）");
        }
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("file", out.getAbsolutePath());
        o.put("size_bytes", out.length());
        o.put("source_size_bytes", inFile.length());
        o.put("video_mime", fVideoMime);
        o.put("audio_mime", fRemoveAudio ? "none" : fAudioMime);
        if (fWantW > 0 && fWantH > 0) o.put("resolution", fWantW + "x" + fWantH);
        if (fBitrate > 0) o.put("bitrate_kbps", fBitrate);
        ExportResult r = result.get();
        if (r != null) {
            if (r.width > 0) o.put("output_width", r.width);
            if (r.height > 0) o.put("output_height", r.height);
            if (r.durationMs > 0) o.put("output_duration_sec", round2(r.durationMs / 1000.0));
            if (r.videoEncoderName != null) o.put("video_encoder", r.videoEncoderName);
            if (r.audioEncoderName != null) o.put("audio_encoder", r.audioEncoderName);
            if (r.averageVideoBitrate > 0) o.put("average_video_bitrate_kbps", r.averageVideoBitrate / 1000);
            if (fWantW > 0 && fWantH > 0 && (r.width != fWantW || r.height != fWantH)) {
                // 设备编码器会把分辨率按宏块对齐向上取整（真机实测：要 160x120 实得 170x128），
                // 这里把"请求值 vs 实际值"都摊开，避免用户拿着请求值去核对成品
                o.put("resolution_note", "实际分辨率由设备编码器对齐决定（请求 " + fWantW + "x" + fWantH
                        + "，实得 " + r.width + "x" + r.height + "）");
            }
        }
        return AIToolResult.success(o.toString(2));
    }

    private static String mimeOf(String key, boolean video) {
        String k = key.toLowerCase(Locale.ROOT);
        if (video) {
            if (k.equals("h264") || k.equals("avc") || k.equals("avc1")) return MimeTypes.VIDEO_H264;
            if (k.equals("h265") || k.equals("hevc")) return MimeTypes.VIDEO_H265;
            if (k.equals("av1")) return MimeTypes.VIDEO_AV1;
            if (k.equals("vp9")) return MimeTypes.VIDEO_VP9;
            return null;
        }
        if (k.equals("aac")) return MimeTypes.AUDIO_AAC;
        if (k.equals("mp3")) return MimeTypes.AUDIO_MPEG;
        if (k.equals("opus")) return MimeTypes.AUDIO_OPUS;
        if (k.equals("vorbis")) return MimeTypes.AUDIO_VORBIS;
        if (k.equals("flac")) return MimeTypes.AUDIO_FLAC;
        return null;
    }

    private static int even(int v) {
        return v <= 0 ? 0 : (v % 2 == 0 ? v : v - 1);
    }

    /** 读轨道尺寸的小工具（AutoCloseable，保证 extractor 释放） */
    private static final class TrackProbe implements AutoCloseable {
        int width, height;

        TrackProbe(String path, Context ctx) {
            MediaExtractor ex = new MediaExtractor();
            try {
                if (path.startsWith("content://") || path.startsWith("file://")) {
                    ex.setDataSource(ctx, Uri.parse(path), null);
                } else {
                    ex.setDataSource(path);
                }
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat f = ex.getTrackFormat(i);
                    String mime = f.containsKey(MediaFormat.KEY_MIME) ? f.getString(MediaFormat.KEY_MIME) : "";
                    if (mime != null && mime.startsWith("video/")) {
                        width = fmtInt(f, MediaFormat.KEY_WIDTH);
                        height = fmtInt(f, MediaFormat.KEY_HEIGHT);
                        int rot = fmtInt(f, MediaFormat.KEY_ROTATION);
                        if (rot == 90 || rot == 270) {
                            int t = width;
                            width = height;
                            height = t;
                        }
                        break;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                releaseQuietly(ex);
            }
        }

        @Override
        public void close() {
        }
    }

    // ==================================================================
    // image_ops：图片处理
    // ==================================================================

    private AIToolResult imageOps(Map<String, Object> p) throws Exception {
        String path = requirePath(p);
        File inFile = localFile(path);
        String cropSpec = str(p, "crop", "");
        int relW = intParam(p, "width", 0);
        int relH = intParam(p, "height", 0);
        int relMax = intParam(p, "max", 0);
        int loadTarget = Math.max(relW, relMax);
        // 裁剪坐标是"原图像素"：只要不是超大图就按原分辨率解码，保证裁剪区域精确。
        // >30MP 的图仍按目标尺寸降采样（避免 OOM），并把裁剪坐标按 inSampleSize 折算。
        if (!cropSpec.isEmpty()) {
            int[] dims = new int[2];
            readImageBounds(path, inFile, dims);
            if ((long) dims[0] * dims[1] <= 30_000_000L) loadTarget = 0;
        }
        int[] sampleOut = new int[1];
        Bitmap bmp = loadBitmap(path, inFile, loadTarget, sampleOut);
        if (bmp == null) return AIToolResult.fail("无法解码图片: " + path);
        String fmt = str(p, "format", isImagePath(path) ? extension(path) : "jpg");
        int quality = clamp(intParam(p, "quality", 90), 1, 100);
        JSONArray applied = new JSONArray();
        try {
            // EXIF 方向
            if (inFile != null && boolParam(p, "auto_rotate", true)) {
                int deg = exifRotation(inFile);
                if (deg != 0) {
                    bmp = rotateBitmap(bmp, deg);
                    applied.put("auto_rotate=" + deg);
                }
            }
            String rotate = str(p, "rotate", "");
            if (!rotate.isEmpty()) {
                int deg = (int) Math.round(Double.parseDouble(rotate));
                bmp = rotateBitmap(bmp, deg);
                applied.put("rotate=" + deg);
            }
            String flip = str(p, "flip", "");
            if (!flip.isEmpty()) {
                bmp = flipBitmap(bmp, flip.toLowerCase(Locale.ROOT).startsWith("v"));
                applied.put("flip=" + flip);
            }
            String crop = cropSpec;
            if (!crop.isEmpty()) {
                String[] parts = crop.split("[,x ]+");
                if (parts.length < 4) return AIToolResult.fail("crop 需要 4 个数字: x,y,w,h（如 crop=10,20,300,400）");
                int s = Math.max(1, sampleOut[0]);
                int fullW = bmp.getWidth() * s;      // 换算回原图像素（旋转已发生，坐标系=当前显示方向）
                int fullH = bmp.getHeight() * s;
                int x = clamp((int) Double.parseDouble(parts[0]), 0, Math.max(0, fullW - 1));
                int y = clamp((int) Double.parseDouble(parts[1]), 0, Math.max(0, fullH - 1));
                int w = clamp((int) Double.parseDouble(parts[2]), 1, fullW - x);
                int h = clamp((int) Double.parseDouble(parts[3]), 1, fullH - y);
                int cx = x / s, cy = y / s;
                int cw = clamp(w / s, 1, bmp.getWidth() - cx);
                int ch = clamp(h / s, 1, bmp.getHeight() - cy);
                Bitmap cropped = Bitmap.createBitmap(bmp, cx, cy, cw, ch);
                if (cropped != bmp) bmp.recycle();
                bmp = cropped;
                applied.put("crop=" + x + "," + y + "," + w + "," + h);
                if (s > 1) applied.put("crop_decode_downscale=" + s);
            }
            if (relW > 0 || relH > 0 || relMax > 0) {
                Bitmap scaled = scaleBitmap(bmp, relW, relH, relMax, true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
                applied.put("resize=" + bmp.getWidth() + "x" + bmp.getHeight());
            }
            if (boolParam(p, "gray", false)) {
                Bitmap gray = toGray(bmp);
                bmp.recycle();
                bmp = gray;
                applied.put("gray=true");
            }
            File out = resolveOutputExt(p, baseName(path) + "_out", "." + extFor(fmt));
            int size = writeBitmap(bmp, out, fmt, quality);
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("file", out.getAbsolutePath());
            o.put("size_bytes", size);
            o.put("width", bmp.getWidth());
            o.put("height", bmp.getHeight());
            o.put("format", extFor(fmt));
            o.put("ops", applied);
            return AIToolResult.success(o.toString(2));
        } finally {
            if (!bmp.isRecycled()) bmp.recycle();
        }
    }

    private Bitmap loadBitmap(String path, File inFile, int targetWidth) {
        return loadBitmap(path, inFile, targetWidth, null);
    }

    /**
     * 解码图片。sampleOut[0] 回传实际用的 inSampleSize —— 裁剪坐标是"原图像素"，
     * 必须知道换算比例，否则降采样过的位图会把裁剪区域取错（真机实测踩到）。
     */
    private Bitmap loadBitmap(String path, File inFile, int targetWidth, int[] sampleOut) {
        if (sampleOut != null) sampleOut[0] = 1;
        try {
            int[] bounds = new int[2];
            readImageBounds(path, inFile, bounds);
            int outW = bounds[0];
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
            if (targetWidth > 0 && outW > targetWidth * 2) {
                int sample = 1;
                while (outW / (sample * 2) >= targetWidth) sample *= 2;
                opt.inSampleSize = sample;
                if (sampleOut != null) sampleOut[0] = sample;
            }
            if (inFile != null) {
                return BitmapFactory.decodeFile(inFile.getAbsolutePath(), opt);
            }
            try (java.io.InputStream is = openInput(path)) {
                return BitmapFactory.decodeStream(is, null, opt);
            }
        } catch (Throwable t) {
            Log.w(TAG, "解码图片失败: " + path, t);
            return null;
        }
    }

    /** 只读图片尺寸（bounds 解码，不占内存）；dims[0]/[1] 回传宽高，失败为 0 */
    private void readImageBounds(String path, File inFile, int[] dims) {
        try {
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            if (inFile != null) {
                BitmapFactory.decodeFile(inFile.getAbsolutePath(), b);
            } else {
                try (java.io.InputStream is = openInput(path)) {
                    BitmapFactory.decodeStream(is, null, b);
                }
            }
            dims[0] = Math.max(0, b.outWidth);
            dims[1] = Math.max(0, b.outHeight);
        } catch (Throwable t) {
            dims[0] = 0;
            dims[1] = 0;
        }
    }

    private int exifRotation(File file) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (o) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    private Bitmap rotateBitmap(Bitmap src, int degrees) {
        if (degrees % 360 == 0) return src;
        Matrix m = new Matrix();
        m.postRotate(degrees);
        Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        return out == null ? src : out;
    }

    private Bitmap flipBitmap(Bitmap src, boolean vertical) {
        Matrix m = new Matrix();
        if (vertical) {
            m.postScale(1, -1);
        } else {
            m.postScale(-1, 1);
        }
        Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        return out == null ? src : out;
    }

    private Bitmap scaleBitmap(Bitmap src, int width, int height, int maxSide, boolean keepRatio) {
        int sw = src.getWidth(), sh = src.getHeight();
        int tw = width, th = height;
        if (maxSide > 0 && Math.max(sw, sh) > maxSide) {
            double s = maxSide / (double) Math.max(sw, sh);
            tw = (int) Math.round(sw * s);
            th = (int) Math.round(sh * s);
        } else if (tw > 0 && th <= 0) {
            th = (int) Math.round(tw * (double) sh / sw);
        } else if (th > 0 && tw <= 0) {
            tw = (int) Math.round(th * (double) sw / sh);
        }
        if (tw <= 0 || th <= 0 || (tw == sw && th == sh)) return src;
        return Bitmap.createScaledBitmap(src, tw, th, true);
    }

    private Bitmap toGray(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint();
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0);
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(src, 0, 0, paint);
        return out;
    }

    private int writeBitmap(Bitmap bmp, File out, String format, int quality) throws IOException {
        Bitmap.CompressFormat cf;
        String f = extFor(format).toLowerCase(Locale.ROOT);
        if ("png".equals(f)) {
            cf = Bitmap.CompressFormat.PNG;
        } else if ("webp".equals(f)) {
            cf = android.os.Build.VERSION.SDK_INT >= 30
                    ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
        } else {
            cf = Bitmap.CompressFormat.JPEG;
        }
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建输出目录: " + parent);
        }
        try (FileOutputStream fos = new FileOutputStream(out)) {
            if (!bmp.compress(cf, quality, fos)) {
                throw new IOException("图片编码失败（格式 " + f + "）");
            }
        }
        return (int) out.length();
    }

    // ==================================================================
    // 工具方法
    // ==================================================================

    private String requirePath(Map<String, Object> p) {
        String path = str(p, "path", "");
        if (path.trim().isEmpty()) path = str(p, "file", "");
        if (path.trim().isEmpty()) path = str(p, "url", "");
        if (path.trim().isEmpty()) throw new IllegalArgumentException("缺少 path 参数（输入文件路径或 content:// URI）");
        String resolved = path.trim();
        // 本地路径先确认存在：否则 MediaExtractor/MMR 会各自抛不同的底层异常，
        // 甚至 probe 会"成功"返回一个 type=unknown 的空壳（2026-09-26 真机实测踩到）
        if (!resolved.startsWith("content://") && !resolved.startsWith("file://")) {
            File f = localFile(resolved);
            if (f == null || !f.exists()) {
                throw new IllegalArgumentException("输入文件不存在: " + resolved
                        + (f != null && !resolved.equals(f.getAbsolutePath()) ? "（解析为 " + f.getAbsolutePath() + "）" : ""));
            }
        }
        return resolved;
    }

    private File workspaceMediaDir() {
        File ws = AgentWorkspace.getInstance(context).getWorkspaceDir();
        File dir = new File(ws, "files/media");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "无法创建工作区 media 目录: " + dir);
        }
        return dir;
    }

    /** 工作区相对路径 → 工作区内的文件；绝对路径原样返回；content:// 返回 null */
    private File localFile(String path) {
        if (path == null || path.startsWith("content://")) return null;
        if (path.startsWith("file://")) {
            try {
                return new File(Uri.parse(path).getPath());
            } catch (Throwable t) {
                return null;
            }
        }
        File f = new File(path);
        if (f.isAbsolute()) return f;
        File ws = AgentWorkspace.getInstance(context).getWorkspaceDir();
        File a = new File(ws, path);
        if (a.exists()) return a;
        File b = new File(new File(ws, "files"), path);
        return b.exists() ? b : a;
    }

    private java.io.InputStream openInput(String path) throws IOException {
        if (path.startsWith("content://") || path.startsWith("file://")) {
            java.io.InputStream is = context.getContentResolver().openInputStream(Uri.parse(path));
            if (is == null) throw new IOException("无法打开输入: " + path);
            return is;
        }
        return new java.io.FileInputStream(path);
    }

    private File resolveOutput(Map<String, Object> p, String defaultName) throws IOException {
        return resolveOutputExt(p, stripExt(defaultName), "." + extFromName(defaultName));
    }

    private File resolveOutputExt(Map<String, Object> p, String defaultBase, String ext) throws IOException {
        String output = str(p, "output", "");
        File out;
        if (output.trim().isEmpty()) {
            out = new File(workspaceMediaDir(), sanitize(defaultBase) + ext);
        } else {
            File f = new File(output.trim());
            out = f.isAbsolute() ? f : new File(workspaceMediaDir(), output.trim());
            if (out.getName().indexOf('.') < 0) out = new File(out.getAbsolutePath() + ext);
        }
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建输出目录: " + parent);
        }
        return out;
    }

    private File uniqueOut(Map<String, Object> p, String name, int i) throws IOException {
        // name 里已带序号与扩展名时不能再用整个 name 当 base，否则会得到 xxx.jpg.jpg（真机踩到）
        if (str(p, "output", "").trim().isEmpty()) return resolveOutputExt(p, stripExt(name), "." + extFromName(name));
        // 指定了 output：多帧时按 _1.._N 派生
        String output = str(p, "output", "");
        int dot = output.lastIndexOf('.');
        String base = dot > 0 ? output.substring(0, dot) : output;
        String ext = dot > 0 ? output.substring(dot) : "";
        File f = new File(base + "_" + (i + 1) + ext);
        return f.isAbsolute() ? f : new File(workspaceMediaDir(), f.getPath());
    }

    private void setSource(MediaExtractor ex, String path) throws IOException {
        if (path.startsWith("content://") || path.startsWith("file://")) {
            ex.setDataSource(context, Uri.parse(path), null);
        } else {
            ex.setDataSource(path);
        }
    }

    private void setSource(MediaMetadataRetriever mmr, String path) {
        if (path.startsWith("content://") || path.startsWith("file://")) {
            mmr.setDataSource(context, Uri.parse(path));
        } else {
            mmr.setDataSource(path);
        }
    }

    private boolean hasVideoTrack(String path) {
        MediaExtractor ex = new MediaExtractor();
        try {
            setSource(ex, path);
            return findTrack(ex, "video/") >= 0;
        } catch (Throwable t) {
            return false;
        } finally {
            releaseQuietly(ex);
        }
    }

    private static int findTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = null;
            try {
                MediaFormat f = ex.getTrackFormat(i);
                mime = f.containsKey(MediaFormat.KEY_MIME) ? f.getString(MediaFormat.KEY_MIME) : null;
            } catch (Throwable ignored) {
            }
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static String decoderName(String mime) {
        MediaCodec codec = null;
        try {
            codec = MediaCodec.createDecoderByType(mime);
            return codec.getName();
        } catch (Throwable t) {
            return "(无可用解码器)";
        } finally {
            releaseQuietly(codec);
        }
    }

    private int readRotation(String path) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            setSource(mmr, path);
            return metaInt(mmr, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
        } catch (Throwable t) {
            return 0;
        } finally {
            releaseQuietly(mmr);
        }
    }

    private static void releaseQuietly(Object o) {
        if (o == null) return;
        try {
            if (o instanceof MediaExtractor) ((MediaExtractor) o).release();
            else if (o instanceof MediaMetadataRetriever) ((MediaMetadataRetriever) o).release();
            else if (o instanceof MediaMuxer) ((MediaMuxer) o).release();
            else if (o instanceof MediaCodec) ((MediaCodec) o).release();
        } catch (Throwable ignored) {
        }
    }

    private static long metaLong(MediaMetadataRetriever mmr, int key) {
        try {
            String v = mmr.extractMetadata(key);
            return v == null ? 0 : Long.parseLong(v.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int metaInt(MediaMetadataRetriever mmr, int key) {
        return (int) metaLong(mmr, key);
    }

    private static String metaStr(MediaMetadataRetriever mmr, int key) {
        try {
            return mmr.extractMetadata(key);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int fmtInt(MediaFormat f, String key) {
        try {
            return f.containsKey(key) ? f.getInteger(key) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long fmtLong(MediaFormat f, String key) {
        try {
            return f.containsKey(key) ? f.getLong(key) : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static float fmtFloat(MediaFormat f, String key) {
        try {
            return f.containsKey(key) ? f.getFloat(key) : 0f;
        } catch (Throwable t) {
            return 0f;
        }
    }

    private static String fmtStr(MediaFormat f, String key) {
        try {
            return f.containsKey(key) ? f.getString(key) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** MediaExtractor 给的 language 可能是 NUL 填充的怪串（真机实测打印成乱码），只保留合法语言码 */
    private static String saneLanguage(String lang) {
        if (lang == null) return null;
        String s = lang.trim();
        return s.matches("[A-Za-z]{2,3}") ? s : null;
    }

    private static void putIf(JSONObject o, String key, Object v) throws Exception {
        if (v == null) return;
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == 0d) return;
        }
        if (v instanceof String && ((String) v).isEmpty()) return;
        o.put(key, v);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String fmtTime(double t) {
        return String.format(Locale.US, "%.1fs", t);
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1fKB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1fMB", bytes / 1048576.0);
        return String.format(Locale.US, "%.2fGB", bytes / 1073741824.0);
    }

    private static String describe(Throwable t) {
        if (t == null) return "未知错误";
        if (t instanceof ExportException) {
            ExportException e = (ExportException) t;
            return e.getErrorCodeName() + "(" + e.errorCode + "): " + String.valueOf(e.getMessage());
        }
        if (t instanceof androidx.media3.transformer.TransformationException) {
            androidx.media3.transformer.TransformationException e = (androidx.media3.transformer.TransformationException) t;
            return e.getErrorCodeName() + "(" + e.errorCode + "): " + String.valueOf(e.getMessage());
        }
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null ? "" : (": " + msg));
    }

    private static String simple(String kind, File out, long size, String extra) throws Exception {
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("kind", kind);
        o.put("file", out.getAbsolutePath());
        o.put("size_bytes", size);
        o.put("size_text", humanSize(size));
        if (extra != null && !extra.isEmpty()) o.put("detail", extra);
        return o.toString(2);
    }

    private static boolean isImagePath(String path) {
        String e = extension(path).toLowerCase(Locale.ROOT);
        return e.equals("jpg") || e.equals("jpeg") || e.equals("png") || e.equals("webp")
                || e.equals("bmp") || e.equals("gif") || e.equals("heic") || e.equals("heif");
    }

    private static String guessContainer(String path) {
        String e = extension(path).toLowerCase(Locale.ROOT);
        return e.isEmpty() ? "?" : e.toUpperCase(Locale.ROOT);
    }

    private static String extension(String path) {
        if (path == null) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(dot + 1) : "";
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String extFromName(String name) {
        String e = extension(name);
        return e.isEmpty() ? "bin" : e;
    }

    private static String extFor(String format) {
        String f = format == null ? "" : format.toLowerCase(Locale.ROOT).replace(".", "").trim();
        if (f.equals("jpeg") || f.equals("jpg")) return "jpg";
        if (f.equals("png")) return "png";
        if (f.equals("webp")) return "webp";
        if (f.equals("bmp")) return "bmp";
        return f.isEmpty() ? "jpg" : f;
    }

    private static String baseName(String path) {
        String p = path;
        int q = p.indexOf('?');
        if (q > 0) p = p.substring(0, q);
        int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        String name = slash >= 0 ? p.substring(slash + 1) : p;
        name = stripExt(name);
        if (name.isEmpty()) name = "media";
        return sanitize(name);
    }

    private static String sanitize(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c > 127) sb.append(c);
            else sb.append('_');
        }
        String s = sb.toString();
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    private static String str(Map<String, Object> p, String key, String def) {
        Object v = p.get(key);
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    private static int intParam(Map<String, Object> p, String key, int def) {
        Object v = p.get(key);
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) return def;
            if (s.contains(".")) return (int) Math.round(Double.parseDouble(s));
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return def;
        }
    }

    private static double doubleParam(Map<String, Object> p, String key, double def) {
        Object v = p.get(key);
        if (v == null) return def;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? def : Double.parseDouble(s);
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean boolParam(Map<String, Object> p, String key, boolean def) {
        Object v = p.get(key);
        if (v == null) return def;
        if (v instanceof Boolean) return (Boolean) v;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return def;
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

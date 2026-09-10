package com.oilquiz.app.ai.speech;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 音频文件解码器：MediaExtractor + MediaCodec 把任意音频（m4a/mp3/wav/amr/aac 等）
 * 解码并重采样为 16kHz 单声道 float PCM（[-1,1]），供端侧 SenseVoice 本地识别。
 * 与 LocalAsrRecognizer 的 AudioRecord 采集链路互补：本类处理"已有音频文件"场景
 * （Agent 录音落盘后的识别），不依赖在线 ASR。
 */
public final class AudioPcmDecoder {

    private static final String TAG = "AudioPcmDecoder";

    /** 本地 SenseVoice 输入要求：16kHz 单声道 */
    public static final int TARGET_SAMPLE_RATE = 16000;

    private AudioPcmDecoder() {
    }

    /** 解码音频文件为 16kHz 单声道 float PCM；失败抛异常（文件无效/无音轨/解码失败） */
    public static float[] decodeFile(Context context, File file) throws Exception {
        if (file == null || !file.exists()) {
            throw new IllegalArgumentException("音频文件不存在: " + (file != null ? file.getAbsolutePath() : "null"));
        }
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            return decode(extractor);
        } finally {
            safeReleaseExtractor(extractor);
        }
    }

    /** 解码 content:// URI 音频为 16kHz 单声道 float PCM */
    public static float[] decodeUri(Context context, Uri uri) throws Exception {
        if (uri == null) {
            throw new IllegalArgumentException("音频 URI 不能为空");
        }
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            return decode(extractor);
        } finally {
            safeReleaseExtractor(extractor);
        }
    }

    private static float[] decode(MediaExtractor extractor) throws Exception {
        // 1. 选择音频轨
        int trackIndex = -1;
        MediaFormat format = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                trackIndex = i;
                format = f;
                break;
            }
        }
        if (trackIndex < 0 || format == null) {
            throw new Exception("音频文件不包含音轨");
        }
        extractor.selectTrack(trackIndex);

        int srcSampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        int srcChannels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
        if (srcSampleRate <= 0) srcSampleRate = 44100;
        if (srcChannels <= 0) srcChannels = 1;

        String mime = format.getString(MediaFormat.KEY_MIME);
        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        try {
            codec.configure(format, null, null, 0);
            codec.start();

            List<short[]> chunkList = new ArrayList<>();
            long totalSamples = 0;
            ByteBuffer infoBuf = ByteBuffer.allocate(4);
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;

            while (!outputDone) {
                // 喂输入
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                        int sampleSize = extractor.readSampleData(inBuf, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                // 取输出
                int outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000);
                if (outIndex >= 0) {
                    if (bufferInfo.size > 0) {
                        ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                        outBuf.position(bufferInfo.offset);
                        outBuf.limit(bufferInfo.offset + bufferInfo.size);
                        int samples = bufferInfo.size / 2;
                        short[] shorts = new short[samples];
                        outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts, 0, samples);
                        chunkList.add(shorts);
                        totalSamples += samples;
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = codec.getOutputFormat();
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        int sr = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        if (sr > 0) srcSampleRate = sr;
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        int ch = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if (ch > 0) srcChannels = ch;
                    }
                }
                // 输入结束且输出耗尽 → 结束
                if (inputDone && outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // 继续等（解码器缓冲可能还有数据）；连续等待由 dequeue 超时兜底
                }
            }

            // 2. 合并 → 混 mono → 重采样 → float
            short[] all = new short[(int) totalSamples];
            int off = 0;
            for (short[] c : chunkList) {
                System.arraycopy(c, 0, all, off, c.length);
                off += c.length;
            }
            if (off == 0) {
                throw new Exception("音频解码结果为空（文件可能过短或损坏）");
            }
            return toPcm16kMono(all, off, srcSampleRate, srcChannels);
        } finally {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            try {
                codec.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** 混单声道 + 线性重采样到 16kHz + 转 float[-1,1] */
    private static float[] toPcm16kMono(short[] samples, int length, int srcRate, int srcChannels) {
        // 1. 混 mono（多声道取平均）
        int monoLen = srcChannels > 1 ? length / srcChannels : length;
        short[] mono = new short[monoLen];
        if (srcChannels > 1) {
            for (int i = 0, j = 0; i + srcChannels <= length; i += srcChannels, j++) {
                long sum = 0;
                for (int c = 0; c < srcChannels; c++) sum += samples[i + c];
                mono[j] = (short) (sum / srcChannels);
            }
        } else {
            System.arraycopy(samples, 0, mono, 0, monoLen);
        }

        // 2. 重采样到 TARGET_SAMPLE_RATE（线性插值）
        float[] result;
        if (srcRate == TARGET_SAMPLE_RATE) {
            result = new float[monoLen];
            for (int i = 0; i < monoLen; i++) result[i] = mono[i] / 32768.0f;
        } else {
            int outLen = (int) ((long) monoLen * TARGET_SAMPLE_RATE / srcRate);
            if (outLen < 1) outLen = 1;
            result = new float[outLen];
            double ratio = (double) srcRate / TARGET_SAMPLE_RATE;
            for (int i = 0; i < outLen; i++) {
                double pos = i * ratio;
                int i0 = (int) pos;
                int i1 = Math.min(i0 + 1, monoLen - 1);
                double frac = pos - i0;
                result[i] = (float) ((mono[i0] * (1 - frac) + mono[i1] * frac) / 32768.0);
            }
        }
        return result;
    }

    private static void safeReleaseExtractor(MediaExtractor extractor) {
        try {
            extractor.release();
        } catch (Exception ignored) {
        }
    }
}

package com.oilquiz.app.ai.speech.asr;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationOutput;
import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig;
import com.oilquiz.app.ai.speech.core.SpeechModelSelector;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 百炼 / DashScope 语音识别引擎（基于官方 dashscope-sdk-java）
 *
 * <h3>双路由设计</h3>
 * 百炼的 ASR 模型分属两套接口，本引擎按模型名自动路由，用户可自由切换模型：
 *
 * <ul>
 *   <li><b>非实时模型</b>（qwen3-asr-flash / fun-asr-flash / qwen-audio-3.0-asr-flash / paraformer-v2 等）
 *       → {@code MultiModalConversation}（multimodal-generation 端点），
 *       本地音频以 file:// URI 传入，SDK 自动鉴权与 OSS 上传，
 *       结果从 {@code output.choices[0].message.content[].text} 提取。</li>
 *   <li><b>实时模型</b>（paraformer-realtime / sensevoice / gummy 等）
 *       → {@code Recognition}（实时双工识别服务），
 *       {@code Recognition.call(param, file)} 返回 JSON {@code {"sentences":[...]}},
 *       按 sampleRate / format 参数传入。</li>
 * </ul>
 *
 * <p>路由依据：模型名含 "realtime" / "sensevoice" / "gummy" 走 Recognition；
 * 其余（含 qwen*-asr / fun-asr / paraformer-v2 等非实时模型）走 MultiModalConversation。</p>
 *
 * <h3>文件体积</h3>
 * SDK 直接上传原始音频，护栏设为 100MB。
 */
public class DashScopeAsrEngine implements AsrEngine {

    private static final String TAG = "DashScopeAsrEngine";
    private static final long MAX_RAW_BYTES = 100L * 1024 * 1024;

    /** wav 采样率读取失败时的兜底值 */
    private static final int DEFAULT_WAV_SAMPLE_RATE = 16000;

    private final Gson gson = new Gson();

    @Override
    public boolean handles(String apiUrl) {
        return SpeechModelSelector.isDashScopeEndpoint(apiUrl);
    }

    @Override
    public String transcribe(OnlineModelConfig config, String modelName, File audioFile, String language)
            throws Exception {
        long size = audioFile.length();
        if (size > MAX_RAW_BYTES) {
            throw new Exception("音频文件过大（" + (size / 1024 / 1024) + "MB），请缩短录音时长或降低采样率");
        }

        if (isRealtimeModel(modelName)) {
            return transcribeViaRecognition(config, modelName, audioFile, language);
        } else {
            return transcribeViaMultiModal(config, modelName, audioFile, language);
        }
    }

    // ==================== 路由判断 ====================

    /** 实时双工模型走 Recognition；其余走 MultiModalConversation */
    private static boolean isRealtimeModel(String modelName) {
        if (modelName == null) return false;
        String lower = modelName.toLowerCase();
        return lower.contains("realtime") || lower.contains("sensevoice") || lower.contains("gummy");
    }

    // ==================== 路径 A：MultiModalConversation（非实时模型） ====================

    private String transcribeViaMultiModal(OnlineModelConfig config, String modelName,
                                            File audioFile, String language) throws Exception {
        String fileUri = toFileUri(audioFile);
        AILogger.i(TAG, "百炼ASR(MultiModal): model=" + modelName + " uri=" + fileUri
                + " size=" + audioFile.length() + "B");

        Map<String, Object> audioPart = new HashMap<>();
        audioPart.put("audio", fileUri);
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(audioPart);
        MultiModalMessage userMessage = MultiModalMessage.builder()
                .role("user")
                .content(content)
                .build();

        Map<String, Object> asrOptions = new HashMap<>();
        asrOptions.put("enable_itn", true);
        if (language != null && !language.isEmpty()) {
            asrOptions.put("language", language);
        }

        MultiModalConversationParam param = MultiModalConversationParam.builder()
                .apiKey(config.apiKey)
                .model(modelName)
                .message(userMessage)
                .parameter("asr_options", asrOptions)
                .build();

        try {
            MultiModalConversationResult result = new MultiModalConversation().call(param);
            return parseMultiModalResult(result);
        } catch (ApiException | NoApiKeyException | UploadFileException e) {
            throw new Exception("百炼语音识别失败: " + e.getMessage(), e);
        }
    }

    private String parseMultiModalResult(MultiModalConversationResult result) throws Exception {
        if (result == null) {
            throw new Exception("语音识别返回为空");
        }
        if (result.getCode() != null && !result.getCode().isEmpty()) {
            throw new Exception("语音识别失败: [" + result.getCode() + "] " + result.getMessage());
        }
        MultiModalConversationOutput output = result.getOutput();
        if (output == null) {
            throw new Exception("语音识别返回为空");
        }

        // 主路径：标准多模态结构 output.choices[0].message.content[].text
        if (output.getChoices() != null && !output.getChoices().isEmpty()) {
            MultiModalMessage msg = output.getChoices().get(0).getMessage();
            if (msg != null && msg.getContent() != null) {
                StringBuilder sb = new StringBuilder();
                for (Map<String, Object> part : msg.getContent()) {
                    if (part == null) continue;
                    Object text = part.get("text");
                    if (text != null) sb.append(text);
                }
                if (sb.length() > 0) return sb.toString();
            }
        }

        // 兜底：个别 qwen-asr 变体直接把文本放在 output.text / output.output.sentence.text
        String fallback = extractTextFromJson(gson.toJson(result));
        if (fallback != null && !fallback.isEmpty()) return fallback;
        throw new Exception("语音识别结果为空");
    }

    // ==================== 路径 B：Recognition（实时双工模型） ====================

    private String transcribeViaRecognition(OnlineModelConfig config, String modelName,
                                             File audioFile, String language) throws Exception {
        AudioFormat fmt = resolveFormat(audioFile.getName());
        if ("wav".equals(fmt.format)) {
            int sr = readWavSampleRate(audioFile);
            if (sr > 0) fmt = new AudioFormat("wav", sr);
        }
        AILogger.i(TAG, "百炼ASR(Recognition): model=" + modelName
                + " format=" + fmt.format + " sampleRate=" + fmt.sampleRate
                + " size=" + audioFile.length() + "B");

        RecognitionParam param = RecognitionParam.builder()
                .apiKey(config.apiKey)
                .model(modelName)
                .sampleRate(fmt.sampleRate)
                .format(fmt.format)
                .build();

        try {
            String resultJson = new Recognition().call(param, audioFile);
            return parseRecognitionResult(resultJson);
        } catch (Exception e) {
            throw new Exception("百炼语音识别失败: " + e.getMessage(), e);
        }
    }

    private String parseRecognitionResult(String json) throws Exception {
        if (json == null || json.trim().isEmpty()) {
            throw new Exception("语音识别返回空响应");
        }
        JsonObject root;
        try {
            root = gson.fromJson(json, JsonObject.class);
        } catch (Exception e) {
            throw new Exception("语音识别返回非 JSON: " + truncate(json));
        }
        if (root == null) {
            throw new Exception("语音识别返回为空");
        }

        String status = optString(root, "status");
        if ("error".equals(status)) {
            String msg = optString(root, "message");
            throw new Exception("语音识别失败: " + (msg != null ? msg : truncate(json)));
        }

        JsonArray sentences = optArray(root, "sentences");
        if (sentences != null && sentences.size() > 0) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement el : sentences) {
                if (!el.isJsonObject()) continue;
                String text = optString(el.getAsJsonObject(), "text");
                if (text != null) sb.append(text);
            }
            if (sb.length() > 0) return sb.toString();
        }

        // 兜底：直接 {"text":"..."}
        String direct = optString(root, "text");
        if (direct != null && !direct.isEmpty()) return direct;
        throw new Exception("语音识别结果为空: " + truncate(json));
    }

    // ==================== 音频格式推断（Recognition 路径） ====================

    /** 按扩展名推断 SDK 识别所需的 format 与 sampleRate */
    private static AudioFormat resolveFormat(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        if (lower.endsWith(".wav")) {
            return new AudioFormat("wav", DEFAULT_WAV_SAMPLE_RATE);
        }
        if (lower.endsWith(".mp3")) {
            return new AudioFormat("mp3", 16000);
        }
        // m4a / mp4 / aac / 默认：与录音格式（AAC in MP4）一致
        return new AudioFormat("m4a", 44100);
    }

    /** 读取 WAV 文件头中的采样率（offset 24，4 字节小端 int） */
    private static int readWavSampleRate(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[28];
            int read = 0;
            while (read < header.length) {
                int n = fis.read(header, read, header.length - read);
                if (n < 0) break;
                read += n;
            }
            if (read >= 28
                    && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F') {
                return ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            }
        } catch (Exception e) {
            AILogger.w(TAG, "读取 wav 采样率失败，回落默认: " + e.getMessage());
        }
        return 0;
    }

    // ==================== 通用工具 ====================

    /** 生成本地文件的 file:// URI（Android 绝对路径以 / 开头；Windows 含盘符需三斜杠） */
    private static String toFileUri(File file) {
        String abs = file.getAbsolutePath().replace('\\', '/');
        if (abs.matches("^[a-zA-Z]:/.*")) {
            return "file:///" + abs;       // file:///D:/path
        }
        return "file://" + abs;            // file:///data/... (abs 以 / 开头)
    }

    /** 从序列化的结果 JSON 中尽力提取文本（兜底路径） */
    private String extractTextFromJson(String json) {
        JsonObject root = gson.fromJson(json, JsonObject.class);
        if (root == null) return null;
        JsonObject output = optObject(root, "output");
        if (output == null) return null;
        String direct = optString(output, "text");
        if (direct != null && !direct.isEmpty()) return direct;
        JsonObject inner = optObject(output, "output");
        if (inner != null) {
            JsonObject sentence = optObject(inner, "sentence");
            if (sentence != null) {
                String t = optString(sentence, "text");
                if (t != null && !t.isEmpty()) return t;
            }
        }
        return null;
    }

    private static final class AudioFormat {
        final String format;
        final int sampleRate;
        AudioFormat(String format, int sampleRate) {
            this.format = format;
            this.sampleRate = sampleRate;
        }
    }

    private static JsonObject optObject(JsonObject obj, String key) {
        if (obj == null) return null;
        JsonElement el = obj.get(key);
        return (el != null && el.isJsonObject()) ? el.getAsJsonObject() : null;
    }

    private static JsonArray optArray(JsonObject obj, String key) {
        if (obj == null) return null;
        JsonElement el = obj.get(key);
        return (el != null && el.isJsonArray()) ? el.getAsJsonArray() : null;
    }

    private static String optString(JsonObject obj, String key) {
        if (obj == null) return null;
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) return null;
        return el.getAsString();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}

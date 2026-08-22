package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.speech.SpeechManager;
import com.oilquiz.app.ai.speech.TTSService;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 语音合成工具（TTS，Text-to-Speech）：
 * 将文字合成为语音并播放，或保存为音频文件，供 Agent 实现"语音合成/朗读"能力。
 *
 * 支持的操作：
 * - synthesize（默认）：合成并播放；
 * - save：仅合成，保存为音频文件（可指定输出路径）；
 * - play：播放已有音频文件；
 * - stop：停止当前播放；
 * - check：检查 TTS 可用性、当前模型与音色；
 * - voices：获取可用音色列表；
 * - set_voice：设置音色（可持久化）。
 *
 * 底层复用 {@link SpeechManager}：在线 TTS 不可用时自动回退系统 TTS。
 */
@Tool(
    value = "speech_synthesis",
    description = "语音合成工具：将文字合成为语音并播放，或保存为音频文件（TTS）",
    category = "speech",
    aliases = {"语音合成", "tts", "朗读", "语音播报", "text_to_speech", "语音朗读"},
    actions = {
        @Action(name = "synthesize", description = "合成并播放语音(阻塞)"),
        @Action(name = "speak", description = "带播放组件朗读(非阻塞,返回component_id,用户可停止)"),
        @Action(name = "save", description = "合成保存为音频文件"),
        @Action(name = "play", description = "播放指定音频文件"),
        @Action(name = "stop", description = "停止当前语音播放"),
        @Action(name = "check", description = "检查TTS可用性/当前模型/音色"),
        @Action(name = "voices", description = "获取可用音色列表"),
        @Action(name = "set_voice", description = "设置音色")
    },
    params = {
        @Param(name = "action", type = "string", description = "操作类型: synthesize(默认)/speak/save/play/stop/check/voices/set_voice", required = false),
        @Param(name = "text", type = "string", description = "要合成/朗读的文字", required = false),
        @Param(name = "voice", type = "string", description = "音色ID(alloy/echo/nova/shimmer等，或sys:系统音色)", required = false),
        @Param(name = "title", type = "string", description = "播放组件标题(speak用，默认🔊正在朗读)", required = false),
        @Param(name = "output_path", type = "string", description = "输出音频文件路径(save用)", required = false),
        @Param(name = "audio_path", type = "string", description = "音频文件路径(play用)", required = false),
        @Param(name = "save_voice", type = "boolean", description = "是否持久化音色(set_voice用)", required = false),
        @Param(name = "duration_seconds", type = "integer", description = "最长播放时长(speak用，0=不限)", required = false),
        @Param(name = "timeout_seconds", type = "integer", description = "合成/播放超时(秒)", required = false)
    }
)
public class SpeechSynthesisTool implements AITool {

    private static final String TAG = "SpeechSynthesisTool";

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MIN_TIMEOUT_SECONDS = 5;
    private static final int MAX_TIMEOUT_SECONDS = 300;
    private static final int VOICES_TIMEOUT_SECONDS = 15;

    private final Context context;

    public SpeechSynthesisTool(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String getName() {
        return "speech_synthesis";
    }

    @Override
    public String getDescription() {
        return "语音合成工具：将文字合成为语音并播放，或保存为音频文件（TTS）。"
                + "在线TTS不可用时自动回退系统TTS。支持音色选择(voices查看列表)、停止播放、设置音色。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作类型: synthesize(默认,合成并播放)/speak(带播放组件,非阻塞)/save(合成保存为文件)/play(播放音频文件)/stop(停止播放)/check(检查可用性)/voices(获取音色列表)/set_voice(设置音色)");
        params.put("text", "要合成/朗读的文字(synthesize/speak/save用)");
        params.put("voice", "音色ID(如alloy/echo/nova/shimmer，或sys:系统音色；voices可查列表)");
        params.put("title", "播放组件标题(speak用，默认🔊正在朗读)");
        params.put("output_path", "输出音频文件路径(save用，不传自动保存到缓存目录)");
        params.put("audio_path", "音频文件路径(play用)");
        params.put("save_voice", "是否持久化音色(set_voice用，默认false)");
        params.put("duration_seconds", "最长播放时长(speak用，0=不限)");
        params.put("timeout_seconds", "合成/播放超时(秒)，默认30，播放最长300");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            String action = strParam(parameters, "action", "synthesize");
            switch (action) {
                case "save":
                    return save(parameters);
                case "play":
                    return playAudioFile(parameters);
                case "stop":
                    return stop();
                case "check":
                    return check();
                case "voices":
                    return voices();
                case "set_voice":
                    return setVoice(parameters);
                case "speak":
                    // 带播放组件的朗读：弹出"🔊正在朗读"对话框（可见可停止），非阻塞返回 component_id
                    return speakWithComponent(parameters);
                case "synthesize":
                default:
                    return synthesizeAndPlay(parameters);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "语音合成工具执行失败: " + e.getMessage(), e);
            return AIToolResult.fail("语音合成失败: " + e.getMessage());
        }
    }

    // ==================== 操作实现 ====================

    /**
     * 带播放组件的朗读（speak）：创建"🔊正在朗读"原生组件（可见可停止），
     * 立即返回 component_id，Agent 可用 ui_component(get_result) 等待播放完成/被停止。
     * 播放器与应用层共享 SpeechManager 单例：应用层"停止朗读"按钮同样可中断。
     */
    private AIToolResult speakWithComponent(Map<String, Object> parameters) {
        String text = strParam(parameters, "text", null);
        if (text == null || text.isEmpty()) {
            return AIToolResult.fail("缺少参数: text（要朗读的文字）");
        }
        String voice = strParam(parameters, "voice", null);
        String title = strParam(parameters, "title", "🔊 正在朗读");
        // 最长播放时长（0=不限制，默认 0；一般 agent 朗读让用户自然听完）
        int maxSeconds = intParam(parameters, "duration_seconds", 0, 0, 600);

        try {
            SpeechManager speech = SpeechManager.getInstance(context);
            if (voice != null && !voice.isEmpty()) {
                speech.setTtsVoice(voice);
            }

            com.oilquiz.app.ai.python.PythonToolManager ptm =
                    com.oilquiz.app.ai.python.PythonToolManager.getInstance(context);
            String componentId = "speech_" + System.currentTimeMillis() + "_"
                    + (int) (Math.random() * 10000);
            Map<String, Object> createReply = ptm.createSpeechPlayerComponent(
                    componentId, title, text, maxSeconds);
            Object cid = createReply != null ? createReply.get("component_id") : null;
            if (cid == null) {
                return AIToolResult.fail("朗读组件创建失败: "
                        + (createReply != null ? createReply.get("message") : "无响应"));
            }

            Map<String, Object> info = new HashMap<>();
            info.put("component_id", String.valueOf(cid));
            info.put("status", "speaking");
            info.put("message", "正在朗读（component_id=" + cid
                    + "），可用 ui_component(get_result, component_id=" + cid
                    + ") 等待播放完成(completed)或停止(stopped)");
            return AIToolResult.success(info);
        } catch (Exception e) {
            AILogger.e(TAG, "带组件朗读失败: " + e.getMessage(), e);
            return AIToolResult.fail("带组件朗读失败: " + e.getMessage());
        }
    }

    /** 合成并播放（阻塞至播放完成或超时） */
    private AIToolResult synthesizeAndPlay(Map<String, Object> parameters) {
        String text = strParam(parameters, "text", null);
        if (text == null || text.isEmpty()) {
            return AIToolResult.fail("缺少参数: text（要朗读的文字）");
        }
        String voice = strParam(parameters, "voice", null);
        int timeout = intParam(parameters, "timeout_seconds", 60, MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS);

        SpeechManager speech = SpeechManager.getInstance(context);
        if (voice != null && !voice.isEmpty()) {
            speech.setTtsVoice(voice);
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> error = new AtomicReference<>(null);
        speech.speak(text, new TTSService.PlaybackCallback() {
            @Override
            public void onStart() {
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }

            @Override
            public void onError(String message) {
                error.set(message);
                latch.countDown();
            }
        });

        boolean finished;
        try {
            finished = latch.await(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            speech.stopSpeaking();
            return AIToolResult.fail("语音播放被中断");
        }
        if (!finished) {
            speech.stopSpeaking();
            return AIToolResult.fail("语音播放超时（" + timeout + "秒），已停止播放");
        }
        if (error.get() != null) {
            return AIToolResult.fail("语音合成失败: " + error.get());
        }

        Map<String, Object> info = new HashMap<>();
        info.put("status", "completed");
        info.put("voice", speech.getCurrentTtsVoice());
        return AIToolResult.success("语音播放完成", info);
    }

    /** 仅合成，保存为音频文件 */
    private AIToolResult save(Map<String, Object> parameters) {
        String text = strParam(parameters, "text", null);
        if (text == null || text.isEmpty()) {
            return AIToolResult.fail("缺少参数: text（要合成的文字）");
        }
        String voice = strParam(parameters, "voice", null);
        String outputPath = strParam(parameters, "output_path", null);
        int timeout = intParam(parameters, "timeout_seconds", DEFAULT_TIMEOUT_SECONDS,
                MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS);

        SpeechManager speech = SpeechManager.getInstance(context);
        if (voice != null && !voice.isEmpty()) {
            speech.setTtsVoice(voice);
        }

        try {
            TTSService.SynthesisResult result = speech.synthesizeSpeech(text)
                    .get(timeout, TimeUnit.SECONDS);
            if (result == null || result.audioFile == null || !result.audioFile.exists()) {
                return AIToolResult.fail("语音合成失败：未生成音频文件");
            }

            File finalFile = result.audioFile;
            if (outputPath != null && !outputPath.isEmpty()) {
                File target = new File(outputPath);
                if (copyFile(result.audioFile, target)) {
                    finalFile = target;
                } else {
                    return AIToolResult.fail("语音合成成功但保存到指定路径失败: " + outputPath
                            + "（原始文件: " + result.audioFile.getAbsolutePath() + "）");
                }
            }

            Map<String, Object> info = new HashMap<>();
            info.put("file_path", finalFile.getAbsolutePath());
            info.put("model", result.modelName);
            info.put("from_system_tts", result.fromSystemTts);
            return AIToolResult.success("语音合成完成，音频文件: " + finalFile.getAbsolutePath(), info);
        } catch (TimeoutException e) {
            return AIToolResult.fail("语音合成超时（" + timeout + "秒），请检查网络后重试");
        } catch (Exception e) {
            AILogger.e(TAG, "语音合成保存失败: " + e.getMessage(), e);
            return AIToolResult.fail("语音合成失败: " + e.getMessage());
        }
    }

    /** 播放已有音频文件（阻塞至播放完成或超时） */
    private AIToolResult playAudioFile(Map<String, Object> parameters) {
        String audioPath = strParam(parameters, "audio_path", null);
        if (audioPath == null) {
            audioPath = strParam(parameters, "file_path", null);
        }
        if (audioPath == null || audioPath.isEmpty()) {
            return AIToolResult.fail("缺少参数: audio_path（要播放的音频文件路径）");
        }
        File file = new File(audioPath);
        if (!file.exists()) {
            return AIToolResult.fail("音频文件不存在: " + audioPath);
        }
        int timeout = intParam(parameters, "timeout_seconds", 60, MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS);

        SpeechManager speech = SpeechManager.getInstance(context);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> error = new AtomicReference<>(null);
        speech.playAudioFile(file, new TTSService.PlaybackCallback() {
            @Override
            public void onStart() {
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }

            @Override
            public void onError(String message) {
                error.set(message);
                latch.countDown();
            }
        });

        boolean finished;
        try {
            finished = latch.await(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            speech.stopSpeaking();
            return AIToolResult.fail("播放被中断");
        }
        if (!finished) {
            speech.stopSpeaking();
            return AIToolResult.fail("播放超时（" + timeout + "秒），已停止");
        }
        if (error.get() != null) {
            return AIToolResult.fail("播放失败: " + error.get());
        }
        return AIToolResult.success("播放完成: " + file.getAbsolutePath());
    }

    /** 停止当前播放 */
    private AIToolResult stop() {
        SpeechManager speech = SpeechManager.getInstance(context);
        speech.stopSpeaking();
        return AIToolResult.success("已停止语音播放");
    }

    /** 检查 TTS 可用性 */
    private AIToolResult check() {
        SpeechManager speech = SpeechManager.getInstance(context);
        boolean online = speech.isOnlineTtsAvailable();
        String model = speech.getCurrentTtsModelDisplay();
        String currentVoice = speech.getCurrentTtsVoice();
        String savedVoice = speech.getSavedTtsVoice();
        boolean systemVoice = speech.isCurrentTtsVoiceSystem();

        Map<String, Object> info = new HashMap<>();
        info.put("online_tts_available", online);
        info.put("model", model);
        info.put("current_voice", currentVoice);
        info.put("saved_voice", savedVoice);
        info.put("is_system_voice", systemVoice);
        return AIToolResult.success("在线TTS" + (online ? "可用" : "不可用")
                + "，当前模型: " + model
                + "，当前音色: " + currentVoice
                + (systemVoice ? "（系统音色）" : ""), info);
    }

    /** 获取可用音色列表 */
    private AIToolResult voices() {
        SpeechManager speech = SpeechManager.getInstance(context);
        try {
            List<TTSService.Voice> voices = speech.fetchVoicesAsync()
                    .get(VOICES_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (voices == null || voices.isEmpty()) {
                return AIToolResult.fail("未获取到可用音色");
            }
            StringBuilder sb = new StringBuilder("可用音色（" + voices.size() + "个）:\n");
            for (TTSService.Voice voice : voices) {
                sb.append("- ").append(voice.id);
                if (voice.name != null && !voice.name.equals(voice.id)) {
                    sb.append(" (").append(voice.name).append(")");
                }
                sb.append("\n");
            }
            Map<String, Object> info = new HashMap<>();
            info.put("voice_count", voices.size());
            info.put("voices", voices);
            return AIToolResult.success(sb.toString().trim(), info);
        } catch (TimeoutException e) {
            return AIToolResult.fail("获取音色列表超时（" + VOICES_TIMEOUT_SECONDS + "秒）");
        } catch (Exception e) {
            AILogger.e(TAG, "获取音色列表失败: " + e.getMessage(), e);
            return AIToolResult.fail("获取音色列表失败: " + e.getMessage());
        }
    }

    /** 设置音色 */
    private AIToolResult setVoice(Map<String, Object> parameters) {
        String voice = strParam(parameters, "voice", null);
        if (voice == null || voice.isEmpty()) {
            return AIToolResult.fail("缺少参数: voice（音色ID，可用 voices 操作查看列表）");
        }
        boolean persist = boolParam(parameters, "save_voice", false);

        SpeechManager speech = SpeechManager.getInstance(context);
        speech.setTtsVoice(voice);
        if (persist) {
            speech.saveTtsVoice(voice);
        }
        Map<String, Object> info = new HashMap<>();
        info.put("voice", voice);
        info.put("persisted", persist);
        return AIToolResult.success("音色已设置为: " + voice
                + (persist ? "（已保存，后续默认使用）" : "（仅本次会话生效）"), info);
    }

    // ==================== 工具方法 ====================

    private boolean copyFile(File src, File dst) {
        try {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = new FileOutputStream(dst)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            }
            return dst.exists() && dst.length() > 0;
        } catch (Exception e) {
            AILogger.e(TAG, "复制音频文件失败: " + e.getMessage(), e);
            return false;
        }
    }

    private static String strParam(Map<String, Object> parameters, String key, String defaultValue) {
        Object value = parameters.get(key);
        if (value == null) return defaultValue;
        String str = String.valueOf(value).trim();
        return str.isEmpty() ? defaultValue : str;
    }

    private static int intParam(Map<String, Object> parameters, String key, int defaultValue,
                                int min, int max) {
        Object value = parameters.get(key);
        if (value == null) return defaultValue;
        int parsed;
        try {
            parsed = Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
        return Math.max(min, Math.min(max, parsed));
    }

    private static boolean boolParam(Map<String, Object> parameters, String key, boolean defaultValue) {
        Object value = parameters.get(key);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return (Boolean) value;
        String str = String.valueOf(value).trim();
        if (str.equalsIgnoreCase("true") || str.equals("1")) return true;
        if (str.equalsIgnoreCase("false") || str.equals("0")) return false;
        return defaultValue;
    }
}

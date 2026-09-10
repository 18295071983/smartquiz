package com.oilquiz.app.ai.tool;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.net.Uri;

import com.oilquiz.app.ai.speech.SpeechManager;
import com.oilquiz.app.ai.speech.SpeechRecognitionService;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 语音输入工具（ASR，Speech-to-Text）：
 * 将语音/音频转换为文字，供 Agent 实现"语音输入"能力。
 *
 * 支持三种操作：
 * - recognize（默认）：识别已有音频文件（文件路径或 content:// URI）为文字；
 * - record_and_recognize：麦克风录音若干秒后自动识别为文字；
 * - check：检查语音识别可用性与当前生效的模型。
 *
 * 底层复用 {@link SpeechManager}（在线 ASR 模型，如 qwen3-asr-flash / whisper-1）。
 * record_and_recognize 需要录音权限，未授予时提示先调用 permission_manager 工具请求。
 */
@Tool(
    value = "voice_input",
    description = "语音输入工具：将语音/音频转换为文字（语音识别ASR）",
    category = "speech",
    aliases = {"语音输入", "语音识别", "asr", "听写", "voice_to_text", "stt", "录音识别"},
    actions = {
        @Action(name = "recognize", description = "识别音频文件为文字"),
        @Action(name = "record", description = "交互式录音识别（弹出录音组件，用户点完成结束）"),
        @Action(name = "record_and_recognize", description = "固定时长录音并识别"),
        @Action(name = "check", description = "检查语音识别可用性与当前模型")
    },
    params = {
        @Param(name = "action", type = "string", description = "操作类型: recognize(默认)/record(交互式录音)/record_and_recognize/check", required = false),
        @Param(name = "audio_path", type = "string", description = "音频文件路径(mp3/m4a/wav/amr等，与audio_uri二选一)", required = false),
        @Param(name = "audio_uri", type = "string", description = "音频content:// URI(与audio_path二选一)", required = false),
        @Param(name = "language", type = "string", description = "语言提示(zh/en)，默认自动检测", required = false),
        @Param(name = "duration_seconds", type = "integer", description = "录音时长/上限(秒)：record_and_recognize固定录音默认15，record交互式默认30上限60", required = false),
        @Param(name = "title", type = "string", description = "录音组件标题(record用，默认🎤请说话)", required = false),
        @Param(name = "hint", type = "string", description = "录音组件提示文字(record用)", required = false),
        @Param(name = "timeout_seconds", type = "integer", description = "识别超时(秒)，默认30；record等待用户操作超时默认90", required = false)
    }
)
public class VoiceInputTool implements AITool {

    private static final String TAG = "VoiceInputTool";

    private static final int DEFAULT_RECORD_SECONDS = 15;
    private static final int MAX_RECORD_SECONDS = 60;
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MIN_TIMEOUT_SECONDS = 5;
    private static final int MAX_TIMEOUT_SECONDS = 120;

    private final Context context;

    public VoiceInputTool(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String getName() {
        return "voice_input";
    }

    @Override
    public String getDescription() {
        return "语音输入工具：将语音/音频转换为文字（语音识别ASR）。"
                + "支持识别音频文件(recognize)、麦克风录音后识别(record_and_recognize)、检查可用性(check)。"
                + "未配置语音识别模型时提示先配置（如 qwen3-asr-flash / whisper-1）。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("action", "操作类型: recognize(默认,识别音频文件)/record(交互式录音组件,用户点完成结束)/record_and_recognize(固定时长录音)/check(检查可用性)");
        params.put("audio_path", "音频文件路径(mp3/m4a/wav/amr等，与audio_uri二选一)");
        params.put("audio_uri", "音频content:// URI(与audio_path二选一)");
        params.put("language", "语言提示(zh/en)，默认自动检测");
        params.put("duration_seconds", "录音时长/上限(秒)：record_and_recognize固定录音默认15，record交互式默认30上限60");
        params.put("title", "录音组件标题(record用，默认🎤请说话)");
        params.put("hint", "录音组件提示文字(record用)");
        params.put("timeout_seconds", "识别超时(秒)，默认30；record等待用户操作超时默认90");
        return params;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            String action = strParam(parameters, "action", "recognize");
            switch (action) {
                case "check":
                    return check();
                case "record":
                    // 交互式录音：弹出语音识别专用组件（录音对话框），用户点"完成"结束并识别
                    return recordInteractive(parameters);
                case "record_and_recognize":
                    return recordAndRecognize(parameters);
                case "recognize":
                case "asr":
                default:
                    return recognize(parameters);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "语音输入工具执行失败: " + e.getMessage(), e);
            return AIToolResult.fail("语音输入失败: " + e.getMessage());
        }
    }

    // ==================== 操作实现 ====================

    /** 识别已有音频文件/URI 为文字 */
    private AIToolResult recognize(Map<String, Object> parameters) {
        String audioPath = strParam(parameters, "audio_path", null);
        if (audioPath == null) {
            audioPath = strParam(parameters, "file_path", null);
        }
        String audioUri = strParam(parameters, "audio_uri", null);
        if ((audioPath == null || audioPath.isEmpty()) && (audioUri == null || audioUri.isEmpty())) {
            return AIToolResult.fail("缺少参数: audio_path（或 audio_uri），请提供要识别的音频文件路径或URI");
        }

        String language = strParam(parameters, "language", null);
        if (language != null && language.isEmpty()) language = null;
        int timeout = intParam(parameters, "timeout_seconds", DEFAULT_TIMEOUT_SECONDS,
                MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS);

        SpeechManager speech = SpeechManager.getInstance(context);
        // 在线模型 OR 本地 SenseVoice OR 系统识别，任一可用即可识别（本地语音识别已启用时无需在线配置）
        if (!speech.isAnyAsrAvailable()) {
            return AIToolResult.fail("语音识别服务不可用：未配置在线语音识别模型，且本地语音识别不可用。"
                    + "请先在模型设置中为'语音识别'选择模型（如 qwen3-asr-flash / whisper-1），"
                    + "或确认本地语音模型已加载");
        }

        // 用户显式选择"本地 SenseVoice"作为语音识别专用模型 → 本地优先（不再走在线）
        boolean preferLocal = isLocalAsrSelected();
        boolean usedOnline = false;
        CompletableFuture<SpeechRecognitionService.RecognitionResult> future = null;

        try {
            if (audioUri != null && !audioUri.isEmpty()) {
                // 在线 ASR 可用时优先在线（除非用户显式选本地专用模型）；否则本地 SenseVoice 解码识别
                if (!preferLocal && speech.isAsrAvailable()) {
                    usedOnline = true;
                    future = speech.recognizeSpeech(Uri.parse(audioUri), language);
                } else {
                    future = speech.recognizeSpeechLocal(Uri.parse(audioUri), language);
                }
            } else {
                File file = new File(audioPath);
                if (!file.exists()) {
                    return AIToolResult.fail("音频文件不存在: " + audioPath);
                }
                if (!preferLocal && speech.isAsrAvailable()) {
                    usedOnline = true;
                    future = speech.recognizeSpeech(file, language);
                } else {
                    future = speech.recognizeSpeechLocal(file, language);
                }
            }

            SpeechRecognitionService.RecognitionResult result = future.get(timeout, TimeUnit.SECONDS);
            if (result == null || result.text == null || result.text.trim().isEmpty()) {
                return AIToolResult.fail("未能识别出语音内容，请重试（音频过短、无人声或格式不支持）");
            }

            Map<String, Object> info = new HashMap<>();
            info.put("text", result.text);
            info.put("model", result.modelName);
            return AIToolResult.success("识别结果: " + result.text, info);
        } catch (TimeoutException e) {
            // 通知底层不再等待结果（HTTP 请求由 HttpURLConnection 自身超时兜底回收）
            if (future != null) {
                future.cancel(true);
            }
            return AIToolResult.fail("语音识别超时（" + timeout + "秒），请检查网络后重试"
                    + (usedOnline && speech.isOfflineAsrAvailable()
                        ? "，或切换本地 SenseVoice 后重试（完全离线，不受网络影响）" : ""));
        } catch (Exception e) {
            AILogger.e(TAG, "语音识别失败: " + e.getMessage(), e);
            return AIToolResult.fail("语音识别失败: " + e.getMessage()
                    + (usedOnline && speech.isOfflineAsrAvailable()
                        ? "（可切换本地 SenseVoice 重试，完全离线）" : ""));
        }
    }

    /** 麦克风录音并识别（固定时长录音） */
    private AIToolResult recordAndRecognize(Map<String, Object> parameters) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return AIToolResult.fail("缺少录音权限：请先调用 permission_manager 工具"
                    + "（action=request_and_wait, permission=录音）请求麦克风权限，再重新调用本工具");
        }

        // 停止当前 TTS 播放：避免合成语音被录入麦克风（录音前必须先停语音）
        com.oilquiz.app.ai.speech.SpeechManager.getInstance(context).stopSpeaking();

        int duration = intParam(parameters, "duration_seconds", DEFAULT_RECORD_SECONDS,
                1, MAX_RECORD_SECONDS);

        File audioFile = new File(context.getCacheDir(),
                "agent_voice_input_" + System.currentTimeMillis() + ".m4a");
        AIToolResult recordResult = recordToFile(audioFile, duration);
        if (!recordResult.isSuccess()) {
            return recordResult;
        }

        Map<String, Object> recognizeParams = new HashMap<>(parameters);
        recognizeParams.put("audio_path", audioFile.getAbsolutePath());
        AIToolResult asrResult = recognize(recognizeParams);

        // 识别结束后清理临时录音文件
        try {
            if (audioFile.exists()) {
                audioFile.delete();
            }
        } catch (Exception ignored) {
        }
        return asrResult;
    }

    /**
     * 交互式录音识别（专用语音识别 UI 组件）：
     * 弹出原生录音对话框（自动开始录音、显示计时），用户点"完成"停止，
     * 返回音频文件路径后自动识别为文字。解决"agent 需要用户说话时"的交互引导，
     * 并在录音前自动停止 TTS 播放（防止合成语音被录入）。
     */
    private AIToolResult recordInteractive(Map<String, Object> parameters) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return AIToolResult.fail("缺少录音权限：请先调用 permission_manager 工具"
                    + "（action=request_and_wait, permission=录音）请求麦克风权限，再重新调用本工具");
        }

        // 停止当前 TTS 播放：避免合成语音被录入麦克风
        com.oilquiz.app.ai.speech.SpeechManager.getInstance(context).stopSpeaking();

        String title = strParam(parameters, "title", "🎤 请说话");
        String hint = strParam(parameters, "hint",
                "正在录音，说完请点「完成」（最长 " + MAX_RECORD_SECONDS + " 秒）");
        // 录音上限（auto_close 语义=最长录音时长）
        int maxSeconds = intParam(parameters, "duration_seconds", 30, 5, MAX_RECORD_SECONDS);
        // 等待用户操作超时（秒）
        int waitTimeout = intParam(parameters, "timeout_seconds", 90, 10, 180);

        try {
            com.oilquiz.app.ai.python.PythonToolManager ptm =
                    com.oilquiz.app.ai.python.PythonToolManager.getInstance(context);
            String componentId = "voice_" + System.currentTimeMillis() + "_"
                    + (int) (Math.random() * 10000);

            // 1. 创建语音识别组件（录音对话框）
            Map<String, Object> createReply = ptm.createVoiceRecorderComponent(
                    componentId, title, hint, maxSeconds);
            Object cid = createReply != null ? createReply.get("component_id") : null;
            if (cid == null) {
                return AIToolResult.fail("语音识别组件创建失败: "
                        + (createReply != null ? createReply.get("message") : "无响应"));
            }

            // 2. 阻塞等待用户录音完成（返回音频路径或 cancelled）
            Map<String, Object> result = ptm.getUiComponentResult(String.valueOf(cid), waitTimeout);
            Object resObj = result != null ? result.get("result") : null;
            String audioPath = resObj != null ? String.valueOf(resObj) : "";

            if (audioPath == null || audioPath.isEmpty() || audioPath.startsWith("cancelled")
                    || "pending".equals(audioPath)) {
                return AIToolResult.fail("录音已取消或未完成"
                        + (audioPath.startsWith("cancelled") ? "（" + audioPath + "）" : "")
                        + "，可重新调用本工具再试");
            }
            File audioFile = new File(audioPath);
            if (!audioFile.exists()) {
                return AIToolResult.fail("录音文件不存在: " + audioPath);
            }

            // 3. 识别录音
            Map<String, Object> recognizeParams = new HashMap<>(parameters);
            recognizeParams.put("audio_path", audioPath);
            AIToolResult asrResult = recognize(recognizeParams);
            if (asrResult.isSuccess()) {
                asrResult.getAdditionalInfo().put("recorded_audio", audioPath);
            }
            return asrResult;
        } catch (Exception e) {
            AILogger.e(TAG, "交互式录音失败: " + e.getMessage(), e);
            return AIToolResult.fail("交互式录音失败: " + e.getMessage());
        }
    }

    /** 录音到指定文件（阻塞 durationSeconds 秒） */
    private AIToolResult recordToFile(File outFile, int durationSeconds) {
        MediaRecorder recorder = null;
        try {
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(128000);
            recorder.setOutputFile(outFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
        } catch (Exception e) {
            safeRelease(recorder);
            AILogger.e(TAG, "录音启动失败: " + e.getMessage(), e);
            return AIToolResult.fail("录音启动失败: " + e.getMessage());
        }

        try {
            Thread.sleep(durationSeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            safeRelease(recorder);
            return AIToolResult.fail("录音被中断");
        }

        try {
            recorder.stop();
        } catch (RuntimeException e) {
            // 录音过短(<1s)或未真正开始会抛异常
            safeRelease(recorder);
            AILogger.e(TAG, "录音停止失败(可能时长过短): " + e.getMessage(), e);
            return AIToolResult.fail("录音失败（录音时长过短或设备异常），请增加 duration_seconds 后重试");
        } finally {
            safeRelease(recorder);
        }

        if (!outFile.exists() || outFile.length() == 0) {
            return AIToolResult.fail("录音失败：未生成有效音频文件");
        }
        return AIToolResult.success(outFile.getAbsolutePath());
    }

    private void safeRelease(MediaRecorder recorder) {
        if (recorder != null) {
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** 用户是否在"功能专用模型"中显式选择了本地 SenseVoice（→ 识别本地优先） */
    private boolean isLocalAsrSelected() {
        try {
            return com.oilquiz.app.ai.speech.SpeechManager.LOCAL_ASR_ID.equals(
                    com.oilquiz.app.ai.model.OnlineModelManager.getInstance(context)
                            .getFeatureModelId(com.oilquiz.app.ai.model.OnlineModelManager.FEATURE_ASR));
        } catch (Exception e) {
            return false;
        }
    }

    /** 检查语音识别可用性 */
    private AIToolResult check() {
        SpeechManager speech = SpeechManager.getInstance(context);
        boolean online = speech.isAsrAvailable();
        boolean offline = speech.isOfflineAsrAvailable();
        boolean available = online || offline;
        String model = speech.getCurrentAsrModelDisplay();
        Map<String, Object> info = new HashMap<>();
        info.put("available", available);
        info.put("online_model", online);
        info.put("local_available", offline);
        info.put("model", model);
        info.put("mode", online ? "在线" : (offline ? "本地(SenseVoice/系统识别)" : "无"));
        info.put("hint", "record_and_recognize 操作需要录音权限（可用 permission_manager 请求）");
        return AIToolResult.success("语音识别" + (available ? "可用" : "不可用")
                + "（在线" + (online ? "可用" : "未配置") + " / 本地"
                + (offline ? "可用" : "不可用") + "），当前模型: " + model, info);
    }

    // ==================== 参数工具方法 ====================

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
}

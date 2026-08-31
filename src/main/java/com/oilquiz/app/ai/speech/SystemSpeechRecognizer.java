package com.oilquiz.app.ai.speech;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;

/**
 * 系统语音识别器（离线/本地兜底 ASR）
 *
 * 当在线语音识别模型不可用或调用失败时，回退到 Android 系统自带的语音识别服务
 * （如 Google 语音、手机厂商内置引擎；部分引擎支持离线识别包）。
 *
 * 注意：
 * 1. SpeechRecognizer 必须在主线程创建和调用，本类内部已做线程切换
 * 2. 与文件式识别不同，系统识别器是"实时监听麦克风"模式，需要用户重新说话
 * 3. 使用前应调用 {@link #isAvailable(Context)} 检查设备是否有识别服务
 */
public class SystemSpeechRecognizer {

    private static final String TAG = "SystemSpeechRecognizer";

    /** 识别回调（均在主线程触发） */
    public interface RecognitionCallback {
        /** 最终识别结果 */
        void onResult(String text);

        /** 识别过程中的部分结果（可用于实时展示，可为空实现） */
        default void onPartialResult(String text) {
        }

        /** 识别失败 */
        void onError(String error);

        /** 识别器就绪，开始聆听 */
        default void onReadyForSpeech() {
        }

        /** 识别结束（无论成功失败） */
        default void onEnd() {
        }
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private volatile boolean listening = false;
    /** 已重试次数：首次失败（如 ERROR_CLIENT）后用更宽松的参数自动重试一次 */
    private int retryCount = 0;
    private RecognitionCallback currentCallback;

    public SystemSpeechRecognizer(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 设备是否有可用的系统语音识别服务 */
    public static boolean isAvailable(Context context) {
        try {
            return SpeechRecognizer.isRecognitionAvailable(context.getApplicationContext());
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isListening() {
        return listening;
    }

    /**
     * 开始实时语音识别（监听麦克风）
     * 可在任意线程调用，内部切换到主线程执行
     */
    public void startListening(RecognitionCallback callback) {
        currentCallback = callback;
        retryCount = 0;
        mainHandler.post(this::doStartListening);
    }

    /** 实际启动识别 */
    private void doStartListening() {
        try {
            destroyInternal();
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                if (currentCallback != null) {
                    currentCallback.onError("设备没有可用的语音识别服务，请配置在线语音识别模型");
                }
                return;
            }

            // 优先直接绑定系统已注册的识别服务组件（如小米小爱 AsrService），
            // 避免 voice_recognition_service 未选中时 createSpeechRecognizer 报 "no selected voice recognition service"
            android.content.ComponentName svc = findRecognitionServiceComponent(context);
            if (svc != null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context, svc);
                AILogger.d(TAG, "绑定语音识别服务: " + svc.flattenToString());
            } else {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context);
            }
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {
                    listening = true;
                    if (currentCallback != null) currentCallback.onReadyForSpeech();
                }

                @Override
                public void onBeginningOfSpeech() {
                }

                @Override
                public void onRmsChanged(float rmsdB) {
                }

                @Override
                public void onBufferReceived(byte[] buffer) {
                }

                @Override
                public void onEndOfSpeech() {
                }

                @Override
                public void onError(int error) {
                    listening = false;
                    AILogger.w(TAG, "系统语音识别错误: " + error);
                    // ERROR_CLIENT/ERROR_SERVER 常见于引擎不支持当前参数，重试一次
                    if ((error == SpeechRecognizer.ERROR_CLIENT
                            || error == SpeechRecognizer.ERROR_SERVER)
                            && retryCount == 0) {
                        retryCount++;
                        AILogger.i(TAG, "重试系统语音识别");
                        final int retryDelay = 300;
                        mainHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                doStartListening();
                            }
                        }, retryDelay);
                        return;
                    }
                    if (currentCallback != null) {
                        currentCallback.onError(mapErrorCode(error));
                        currentCallback.onEnd();
                    }
                }

                @Override
                public void onResults(Bundle results) {
                    listening = false;
                    String text = extractTopResult(results);
                    if (currentCallback != null) {
                        if (text != null && !text.isEmpty()) {
                            currentCallback.onResult(text);
                        } else {
                            currentCallback.onError("未识别到语音内容");
                        }
                        currentCallback.onEnd();
                    }
                }

                @Override
                public void onPartialResults(Bundle partialResults) {
                    String text = extractTopResult(partialResults);
                    if (text != null && !text.isEmpty() && currentCallback != null) {
                        currentCallback.onPartialResult(text);
                    }
                }

                @Override
                public void onEvent(int eventType, Bundle params) {
                }
            });

            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            // 显式指定自由文本模型与中文，兼容小米小爱等国内引擎（避免引擎按默认英文模型拒绝请求）
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            AILogger.d(TAG, "启动系统语音识别，调用包: " + context.getPackageName());
            recognizer.startListening(intent);
        } catch (Exception e) {
            listening = false;
            AILogger.e(TAG, "启动系统语音识别失败: " + e.getMessage(), e);
            if (currentCallback != null) {
                currentCallback.onError("启动系统语音识别失败: " + e.getMessage());
            }
        }
    }

    /** 停止识别（触发最终结果回调） */
    public void stopListening() {
        mainHandler.post(() -> {
            try {
                if (recognizer != null) {
                    recognizer.stopListening();
                }
            } catch (Exception ignored) {
            }
        });
    }

    /** 取消并释放资源 */
    public void cancel() {
        mainHandler.post(this::destroyInternal);
    }


    /** 查找系统已注册的第一个语音识别服务组件（如 com.xiaomi.mibrain.speech/.asr.AsrService） */
    private static android.content.ComponentName findRecognitionServiceComponent(Context ctx) {
        try {
            android.content.Intent intent = new android.content.Intent("android.speech.RecognitionService");
            java.util.List<android.content.pm.ResolveInfo> list =
                    ctx.getPackageManager().queryIntentServices(intent,
                            android.content.pm.PackageManager.MATCH_ALL);
            if (list != null) {
                for (android.content.pm.ResolveInfo ri : list) {
                    if (ri.serviceInfo != null && ri.serviceInfo.packageName != null
                            && ri.serviceInfo.name != null && ri.serviceInfo.enabled) {
                        return new android.content.ComponentName(
                                ri.serviceInfo.packageName, ri.serviceInfo.name);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }


    private void destroyInternal() {
        listening = false;
        if (recognizer != null) {
            try {
                recognizer.cancel();
                recognizer.destroy();
            } catch (Exception ignored) {
            }
            recognizer = null;
        }
    }

    private String extractTopResult(Bundle results) {
        if (results == null) return null;
        try {
            ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches != null && !matches.isEmpty()) {
                return matches.get(0);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String mapErrorCode(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "网络不可用，若需离线识别请在系统设置中下载离线语音包";
            case SpeechRecognizer.ERROR_AUDIO:
                return "录音出错，请重试";
            case SpeechRecognizer.ERROR_CLIENT:
                return "系统语音识别不可用（可能是小爱/语音引擎不支持当前参数）。" +
                        "建议：1) 在「模型管理」中配置在线语音识别模型（如 qwen3-asr-flash）；" +
                        "2) 或检查「系统设置→应用管理→小爱语音」是否已启用";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "缺少录音权限";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "未识别到语音内容";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "没有检测到说话";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "识别服务忙，请稍后重试";
            case SpeechRecognizer.ERROR_SERVER:
                return "识别服务返回错误";
            default:
                return "识别失败（错误码 " + error + "）";
        }
    }
}

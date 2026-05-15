package com.oilquiz.app.manager;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;

public class SpeechRecognizerManager {
    private static final String TAG = "SpeechRecognizerManager";

    public static final int RESULT_SUCCESS = 0;
    public static final int RESULT_NO_MATCH = 1;
    public static final int RESULT_ERROR = 2;
    public static final int RESULT_SERVER_ERROR = 3;
    public static final int RESULT_SPEECH_TIMEOUT = 4;

    private Context context;
    private SpeechRecognizer speechRecognizer;
    private Handler mainHandler;
    private boolean isListening = false;
    private SpeechCallback callback;

    public interface SpeechCallback {
        void onReadyForSpeech();
        void onBeginningOfSpeech();
        void onEndOfSpeech();
        void onPartialResult(String text);
        void onResult(String text, int resultCode);
        void onError(int errorCode, String errorMessage);
    }

    public SpeechRecognizerManager(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        initSpeechRecognizer();
    }

    private void initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {
                    Log.d(TAG, "准备识别");
                    isListening = true;
                    if (callback != null) {
                        callback.onReadyForSpeech();
                    }
                }

                @Override
                public void onBeginningOfSpeech() {
                    Log.d(TAG, "开始说话");
                    if (callback != null) {
                        callback.onBeginningOfSpeech();
                    }
                }

                @Override
                public void onEndOfSpeech() {
                    Log.d(TAG, "结束说话");
                    isListening = false;
                    if (callback != null) {
                        callback.onEndOfSpeech();
                    }
                }

                @Override
                public void onBufferReceived(byte[] buffer) {
                }

                @Override
                public void onRmsChanged(float rmsdB) {
                }

                @Override
                public void onPartialResults(Bundle partialResults) {
                    ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty() && callback != null) {
                        callback.onPartialResult(matches.get(0));
                    }
                }

                @Override
                public void onEvent(int eventType, Bundle params) {
                }

                @Override
                public void onResults(Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    isListening = false;
                    if (matches != null && !matches.isEmpty()) {
                        String result = matches.get(0);
                        Log.d(TAG, "识别结果: " + result);
                        if (callback != null) {
                            callback.onResult(result, RESULT_SUCCESS);
                        }
                    } else {
                        if (callback != null) {
                            callback.onResult("", RESULT_NO_MATCH);
                        }
                    }
                }

                @Override
                public void onError(int error) {
                    isListening = false;
                    String errorMessage = getErrorMessage(error);
                    int resultCode;
                    switch (error) {
                        case SpeechRecognizer.ERROR_NO_MATCH:
                        case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                            resultCode = RESULT_NO_MATCH;
                            break;
                        case SpeechRecognizer.ERROR_SERVER:
                            resultCode = RESULT_SERVER_ERROR;
                            break;
                        default:
                            resultCode = RESULT_ERROR;
                            break;
                    }
                    Log.e(TAG, "识别错误: " + error + " - " + errorMessage);
                    if (callback != null) {
                        callback.onError(error, errorMessage);
                        callback.onResult("", resultCode);
                    }
                }
            });
            Log.d(TAG, "SpeechRecognizer 初始化成功");
        } else {
            Log.e(TAG, "当前设备不支持语音识别");
        }
    }

    private String getErrorMessage(int errorCode) {
        switch (errorCode) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "音频录制错误";
            case SpeechRecognizer.ERROR_CLIENT:
                return "客户端错误";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "权限不足";
            case SpeechRecognizer.ERROR_NETWORK:
                return "网络错误";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "网络超时";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "没有识别到匹配内容";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "识别服务忙";
            case SpeechRecognizer.ERROR_SERVER:
                return "服务器错误";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "没有语音输入";
            default:
                return "未知错误";
        }
    }

    public void setCallback(SpeechCallback callback) {
        this.callback = callback;
    }

    public void startListening() {
        if (speechRecognizer == null) {
            Log.e(TAG, "SpeechRecognizer 未初始化");
            return;
        }

        if (isListening) {
            Log.w(TAG, "已经在监听中");
            return;
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);

        mainHandler.post(() -> {
            speechRecognizer.startListening(intent);
            Log.d(TAG, "开始监听...");
        });
    }

    public void stopListening() {
        if (speechRecognizer != null && isListening) {
            mainHandler.post(() -> {
                speechRecognizer.stopListening();
                isListening = false;
                Log.d(TAG, "停止监听");
            });
        }
    }

    public boolean isListening() {
        return isListening;
    }

    public void release() {
        if (speechRecognizer != null) {
            mainHandler.post(() -> {
                speechRecognizer.destroy();
                speechRecognizer = null;
                Log.d(TAG, "释放 SpeechRecognizer");
            });
        }
        isListening = false;
        callback = null;
    }
}

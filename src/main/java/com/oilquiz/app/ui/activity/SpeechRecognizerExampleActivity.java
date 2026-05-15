package com.oilquiz.app.ui.activity;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.oilquiz.app.R;
import com.oilquiz.app.manager.SpeechRecognizerManager;
import com.oilquiz.app.resource.AppResourceManager;
import com.oilquiz.app.resource.PermissionResourceProvider;
import java.util.List;

public class SpeechRecognizerExampleActivity extends AppCompatActivity {
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 100;

    private SpeechRecognizerManager speechManager;
    private TextView textResult;
    private TextView textStatus;
    private Button btnStart;
    private Button btnStop;

    private String[] permissions = {Manifest.permission.RECORD_AUDIO};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_speech_example);

        textResult = findViewById(R.id.text_result);
        textStatus = findViewById(R.id.text_status);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);

        btnStart.setOnClickListener(v -> startSpeechRecognition());
        btnStop.setOnClickListener(v -> stopSpeechRecognition());

        speechManager = new SpeechRecognizerManager(this);
        speechManager.setCallback(new SpeechRecognizerManager.SpeechCallback() {
            @Override
            public void onReadyForSpeech() {
                runOnUiThread(() -> textStatus.setText("请说话..."));
            }

            @Override
            public void onBeginningOfSpeech() {
                runOnUiThread(() -> textStatus.setText("正在识别中..."));
            }

            @Override
            public void onEndOfSpeech() {
                runOnUiThread(() -> textStatus.setText("识别结束"));
            }

            @Override
            public void onPartialResult(String text) {
                runOnUiThread(() -> textResult.setText("临时结果: " + text));
            }

            @Override
            public void onResult(String text, int resultCode) {
                runOnUiThread(() -> {
                    if (resultCode == SpeechRecognizerManager.RESULT_SUCCESS) {
                        textResult.setText("识别结果: " + text);
                    } else if (resultCode == SpeechRecognizerManager.RESULT_NO_MATCH) {
                        textResult.setText("未能识别到内容，请重试");
                    } else {
                        textResult.setText("识别失败，请重试");
                    }
                });
            }

            @Override
            public void onError(int errorCode, String errorMessage) {
                runOnUiThread(() -> textStatus.setText("错误: " + errorMessage));
            }
        });
    }

    private void startSpeechRecognition() {
        if (checkPermission()) {
            speechManager.startListening();
        } else {
            requestPermission();
        }
    }

    private void stopSpeechRecognition() {
        speechManager.stopListening();
    }

    private boolean checkPermission() {
        return AppResourceManager.getInstance(this).hasMicrophonePermission();
    }

    private void requestPermission() {
        AppResourceManager.getInstance(this).permissions().requestMicrophonePermission(this, new PermissionResourceProvider.PermissionCallback() {
            @Override
            public void onGranted() {
                startSpeechRecognition();
            }

            @Override
            public void onDenied(List<String> deniedPermissions) {
                textStatus.setText("需要录音权限才能使用语音识别");
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        AppResourceManager.getInstance(this).permissions().onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (speechManager != null) {
            speechManager.release();
        }
    }
}

package com.oilquiz.app.ui.dialog;

import android.app.ProgressDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.oilquiz.app.ai.speech.SpeechManager;
import com.oilquiz.app.ai.speech.TTSService;

import java.util.List;

/**
 * TTS 音色选择对话框
 *
 * 流程：
 * 1. 自动从当前 TTS 在线端点拉取可用音色列表（GET /audio/speech/voices），
 *    拉取失败/超时则回退内置常用音色预设
 * 2. 单选列表展示，支持"试听"按钮播放所选音色效果
 * 3. "确定"保存为默认音色（持久化），后续朗读/合成都使用该音色
 */
public class TTSVoiceSelectorDialog {

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SpeechManager speechManager;

    private List<TTSService.Voice> voices;
    private int selectedIndex = -1;
    private AlertDialog voiceDialog;

    public TTSVoiceSelectorDialog(Context context) {
        this.context = context;
        this.speechManager = SpeechManager.getInstance(context);
    }

    /**
     * 显示对话框：先拉取音色列表（带进度提示），完成后弹出选择列表
     */
    @SuppressWarnings("deprecation")
    public void show() {
        // 进度提示（拉取音色通常很快，最多等待 15s）
        ProgressDialog progress = new ProgressDialog(context);
        progress.setMessage("正在获取音色列表...");
        progress.setCancelable(true);
        progress.show();

        speechManager.fetchVoicesAsync().whenComplete((result, error) -> mainHandler.post(() -> {
            try {
                if (progress.isShowing()) progress.dismiss();
            } catch (Exception ignored) {
            }

            List<TTSService.Voice> voiceList = result;
            if (voiceList == null || voiceList.isEmpty()) {
                voiceList = TTSService.getPresetVoices();
                Toast.makeText(context, "使用内置音色列表", Toast.LENGTH_SHORT).show();
            }
            voices = voiceList;
            showVoiceListDialog();
        }));
    }

    /**
     * 弹出单选列表对话框（含试听按钮）
     */
    private void showVoiceListDialog() {
        // 仅当用户保存过音色时才预选，未设置时不预选任何项（避免误导）
        String currentVoice = speechManager.getSavedTtsVoice();
        String[] displayNames = new String[voices.size()];
        selectedIndex = -1;
        for (int i = 0; i < voices.size(); i++) {
            TTSService.Voice v = voices.get(i);
            displayNames[i] = v.name.equals(v.id) ? v.id : (v.name + "（" + v.id + "）");
            if (currentVoice != null && v.id.equals(currentVoice)) {
                selectedIndex = i;
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle("选择语音合成音色")
                .setSingleChoiceItems(displayNames, selectedIndex, (dialog, which) -> selectedIndex = which)
                .setNeutralButton("▶ 试听", null) // 点击后拦截关闭行为
                .setPositiveButton("确定", (dialog, which) -> {
                    if (selectedIndex >= 0 && selectedIndex < voices.size()) {
                        TTSService.Voice chosen = voices.get(selectedIndex);
                        speechManager.saveTtsVoice(chosen.id);
                        Toast.makeText(context, "音色已设置：" + chosen.name, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .setOnDismissListener(d -> speechManager.stopSpeaking());

        voiceDialog = builder.create();
        voiceDialog.show();

        // 试听按钮：播放当前选中音色，不关闭对话框
        android.widget.Button previewBtn = voiceDialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (previewBtn != null) {
            previewBtn.setOnClickListener(v -> {
                if (selectedIndex < 0 || selectedIndex >= voices.size()) {
                    Toast.makeText(context, "请先选择一个音色", Toast.LENGTH_SHORT).show();
                    return;
                }
                TTSService.Voice chosen = voices.get(selectedIndex);
                Toast.makeText(context, "试听音色：" + chosen.name, Toast.LENGTH_SHORT).show();
                speechManager.previewVoice(chosen.id, new TTSService.PlaybackCallback() {
                    @Override
                    public void onStart() {
                    }

                    @Override
                    public void onComplete() {
                    }

                    @Override
                    public void onError(String error) {
                        mainHandler.post(() -> Toast.makeText(context,
                                "试听失败: " + error, Toast.LENGTH_SHORT).show());
                    }
                });
            });
        }
    }
}

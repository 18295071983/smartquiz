package com.oilquiz.app.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.speech.SpeechManager;
import com.oilquiz.app.ai.speech.SpeechModelRegistry;
import com.oilquiz.app.ai.speech.TTSService;
import com.oilquiz.app.ai.speech.core.SpeechModelSelector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * TTS 音色选择（底部弹窗，2026-11-04 重做）。
 *
 * <p>为什么重做：原来是 {@code AlertDialog} 的单选列表，和全局那套底部弹窗（圆角、标题栏、
 * 副标题、搜索、底部按钮）完全不搭；48 个音色平铺在一个系统弹窗里也没法搜索、看不出当前模型。
 * 现在套用 {@code dialog_speech_model_selector} 的同一副骨架，行为和取数逻辑保持不变：</p>
 *
 * <ol>
 *   <li>音色列表跟随「当前端点 + 当前 TTS 模型」：自动来源优先，预置表兜底；</li>
 *   <li>本地/系统 TTS 下只列系统音色，不再拿在线预设充数；</li>
 *   <li>行内试听，确定后持久化到该端点作用域。</li>
 * </ol>
 */
@SuppressWarnings("deprecation") // 弹窗窗口仍靠 ADJUST_RESIZE 跟输入法收缩
public class TTSVoiceSelectorDialog {

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SpeechManager speechManager;

    private Dialog dialog;
    private VoiceAdapter adapter;
    private TextView tvEmpty;
    private final List<TTSService.Voice> allVoices = new ArrayList<>();
    private final List<TTSService.Voice> shownVoices = new ArrayList<>();
    private String selectedId;
    private String query = "";

    public TTSVoiceSelectorDialog(Context context) {
        this.context = context;
        this.speechManager = SpeechManager.getInstance(context);
    }

    public void show() {
        dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        View view = LayoutInflater.from(context).inflate(R.layout.dialog_tts_voice_selector, null);
        dialog.setContentView(view);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, (int) (screenHeight * 0.72f));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        bindViews(view);
        loadVoices();
        com.oilquiz.app.theme.EdgeToEdgeHelper.applyDialog(dialog);
        dialog.show();
    }

    public void dismiss() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    // ==================== 视图 ====================

    private void bindViews(View root) {
        ImageView close = root.findViewById(R.id.btn_voice_close);
        if (close != null) close.setOnClickListener(v -> dismiss());

        TextView subtitle = root.findViewById(R.id.tv_voice_subtitle);
        if (subtitle != null) {
            String model = speechManager.getCurrentTtsModelDisplay();
            subtitle.setText("当前模型：" + (model != null && !model.isEmpty() ? model : "—")
                    + " · 试听后再点确定");
        }

        tvEmpty = root.findViewById(R.id.tv_voice_empty);

        RecyclerView recycler = root.findViewById(R.id.voice_recycler);
        recycler.setLayoutManager(new LinearLayoutManager(context));
        adapter = new VoiceAdapter();
        recycler.setAdapter(adapter);

        EditText search = root.findViewById(R.id.et_voice_search);
        if (search != null) {
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }

                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                    query = s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
                    applyFilter();
                }

                @Override public void afterTextChanged(Editable s) { }
            });
        }

        View cancel = root.findViewById(R.id.btn_voice_cancel);
        if (cancel != null) cancel.setOnClickListener(v -> dismiss());

        View confirm = root.findViewById(R.id.btn_voice_confirm);
        if (confirm != null) confirm.setOnClickListener(v -> onConfirm());
    }

    private void onConfirm() {
        if (selectedId == null || selectedId.isEmpty()) {
            Toast.makeText(context, "请先选择一个音色", Toast.LENGTH_SHORT).show();
            return;
        }
        String name = selectedId;
        for (TTSService.Voice v : allVoices) {
            if (v.id.equals(selectedId)) { name = v.name; break; }
        }
        speechManager.saveTtsVoice(selectedId);
        Toast.makeText(context, "音色已设置：" + name, Toast.LENGTH_SHORT).show();
        dismiss();
    }

    // ==================== 数据 ====================

    private void loadVoices() {
        speechManager.fetchVoicesAsync().whenComplete((result, error) -> mainHandler.post(() -> {
            if (dialog == null || !dialog.isShowing()) return;

            List<TTSService.Voice> voiceList = result;

            // API 拉取失败且拿回全量预设（>100）时，收敛到当前模型的音色
            if ((voiceList == null || voiceList.size() > 100) && context != null) {
                try {
                    OnlineModelManager.OnlineModelConfig config =
                            SpeechModelSelector.select(context, SpeechModelSelector.Capability.TTS);
                    String modelName = null;
                    if (config != null) {
                        modelName = SpeechModelSelector.resolveModelName(config,
                                OnlineModelManager.getInstance(context).getFeatureModelName(
                                        OnlineModelManager.FEATURE_TTS),
                                SpeechModelSelector.Capability.TTS);
                    }
                    List<TTSService.Voice> scoped = SpeechModelRegistry.getVoicesForModel(
                            modelName, config != null ? config.apiUrl : null);
                    if (!scoped.isEmpty()) {
                        voiceList = scoped;
                    }
                } catch (Exception ignored) {
                }
            }

            // 本地/系统 TTS 下不兜底在线预设（用户选的是系统合成，列 qwen3 音色没有意义）
            if (voiceList == null || voiceList.isEmpty()) {
                if (speechManager.isLocalTtsConfigured()) {
                    Toast.makeText(context, "当前为系统合成，系统未提供可选音色", Toast.LENGTH_SHORT).show();
                    dismiss();
                    return;
                }
                voiceList = TTSService.getPresetVoices();
            }

            allVoices.clear();
            allVoices.addAll(voiceList);

            String saved = speechManager.getSavedTtsVoice();
            selectedId = saved;
            if (selectedId == null && !allVoices.isEmpty()) {
                selectedId = allVoices.get(0).id;
            }

            applyFilter();
        }));
    }

    private void applyFilter() {
        shownVoices.clear();
        for (TTSService.Voice v : allVoices) {
            if (query.isEmpty()
                    || (v.name != null && v.name.toLowerCase(Locale.ROOT).contains(query))
                    || (v.id != null && v.id.toLowerCase(Locale.ROOT).contains(query))) {
                shownVoices.add(v);
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (tvEmpty != null) {
            tvEmpty.setVisibility(shownVoices.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    // ==================== 列表 ====================

    private class VoiceAdapter extends RecyclerView.Adapter<VoiceAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View item = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_tts_voice, parent, false);
            return new VH(item);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            TTSService.Voice voice = shownVoices.get(position);
            holder.name.setText(voice.name != null && !voice.name.isEmpty() ? voice.name : voice.id);
            holder.voiceId.setText(voice.id);
            holder.radio.setChecked(voice.id.equals(selectedId));

            holder.itemView.setOnClickListener(v -> {
                selectedId = voice.id;
                notifyDataSetChanged();
            });

            holder.preview.setOnClickListener(v -> preview(voice));
            holder.preview.setEnabled(true);
        }

        @Override
        public int getItemCount() {
            return shownVoices.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final RadioButton radio;
            final TextView name;
            final TextView voiceId;
            final ImageButton preview;

            VH(@NonNull View itemView) {
                super(itemView);
                radio = itemView.findViewById(R.id.rb_voice_selected);
                name = itemView.findViewById(R.id.tv_voice_name);
                voiceId = itemView.findViewById(R.id.tv_voice_id);
                preview = itemView.findViewById(R.id.btn_voice_preview);
            }
        }
    }

    private void preview(TTSService.Voice voice) {
        Toast.makeText(context, "试听：" + voice.name, Toast.LENGTH_SHORT).show();
        speechManager.previewVoice(voice.id, new TTSService.PlaybackCallback() {
            @Override public void onStart() { }

            @Override public void onComplete() { }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> Toast.makeText(context,
                        "试听失败: " + error, Toast.LENGTH_SHORT).show());
            }
        });
    }
}

package com.oilquiz.app.ai.chat.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.config.UserModelParamsManager;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ui.activity.ModelCenterActivity;

import java.util.List;
import java.util.Locale;

/**
 * 聊天页「模型与参数」面板（2026-10-21，demo #sheet-model）。
 *
 * <p>此前点顶栏标题直接跳 {@link ModelCenterActivity} 整页；这里按 demo 改成 BottomSheet：
 * 在线/本地分段、模型列表、温度、上下文长度、应用。在线模型走
 * {@link OnlineModelManager#setActiveModel}；温度与上下文走
 * {@link UserModelParamsManager}（与既有参数面板同源，不另存一份）。</p>
 *
 * <p>本地模型的切换要重新加载 native 模型，不在这个轻面板里做，列出名字并给
 * "打开模型中心"入口，避免出现"选了但没生效"的假切换。</p>
 */
public final class ModelSheet {

    private ModelSheet() {
    }

    public static void show(final Activity activity, final Runnable onApplied) {
        if (activity == null) return;
        try {
            final BottomSheetDialog sheet = new BottomSheetDialog(activity);
            View root = LayoutInflater.from(activity)
                    .inflate(R.layout.view_chat_v2_model_sheet, null);
            View close = root.findViewById(R.id.chat_v2_model_sheet_close);
            if (close != null) close.setOnClickListener(v -> sheet.dismiss());

            final OnlineModelManager manager = OnlineModelManager.getInstance(activity);
            final UserModelParamsManager params = UserModelParamsManager.getInstance(activity);
            final LinearLayout list = root.findViewById(R.id.chat_v2_model_list);
            final TextView tabOnline = root.findViewById(R.id.chat_v2_model_tab_online);
            final TextView tabLocal = root.findViewById(R.id.chat_v2_model_tab_local);
            final String activeId = manager.getActiveModel() != null
                    ? manager.getActiveModel().id : null;
            final String[] selected = {activeId};
            final boolean[] onlineTab = {true};

            final Runnable render = () -> {
                if (list == null) return;
                list.removeAllViews();
                if (onlineTab[0]) {
                    renderOnline(activity, list, manager, selected, () -> {
                        renderOnline(activity, list, manager, selected, null, sheet);
                    }, sheet);
                } else {
                    renderLocal(activity, list, sheet);
                }
            };

            if (tabOnline != null) tabOnline.setOnClickListener(v -> {
                onlineTab[0] = true;
                styleTabs(tabOnline, tabLocal, true);
                render.run();
            });
            if (tabLocal != null) tabLocal.setOnClickListener(v -> {
                onlineTab[0] = false;
                styleTabs(tabOnline, tabLocal, false);
                render.run();
            });

            final SeekBar tempBar = root.findViewById(R.id.chat_v2_model_temp_bar);
            final TextView tempValue = root.findViewById(R.id.chat_v2_model_temp_value);
            final float currentTemp = params.getAnswerTemperature();
            int progress = Math.round((currentTemp - UserModelParamsManager.MIN_TEMPERATURE) * 100f);
            progress = Math.max(0, Math.min(140, progress));
            if (tempBar != null) {
                tempBar.setProgress(progress);
                tempBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                        float t = UserModelParamsManager.MIN_TEMPERATURE + p / 100f;
                        if (tempValue != null) {
                            tempValue.setText(String.format(Locale.getDefault(), "%.1f", t));
                        }
                    }
                    @Override public void onStartTrackingTouch(SeekBar seekBar) { }
                    @Override public void onStopTrackingTouch(SeekBar seekBar) { }
                });
            }
            if (tempValue != null) {
                tempValue.setText(String.format(Locale.getDefault(), "%.1f", currentTemp));
            }

            TextView context = root.findViewById(R.id.chat_v2_model_context);
            if (context != null) {
                int ctx = params.getContextSize();
                context.setText(ctx <= 0
                        ? activity.getString(R.string.chat_v2_model_context_auto)
                        : activity.getString(R.string.chat_v2_model_context_manual, ctx));
            }

            View apply = root.findViewById(R.id.chat_v2_model_apply);
            if (apply != null) {
                apply.setOnClickListener(v -> {
                    try {
                        String picked = selected[0];
                        String now = manager.getActiveModel() != null
                                ? manager.getActiveModel().id : null;
                        if (onlineTab[0] && picked != null && !picked.equals(now)) {
                            manager.setActiveModel(picked);
                        }
                        if (tempBar != null) {
                            float t = UserModelParamsManager.MIN_TEMPERATURE
                                    + tempBar.getProgress() / 100f;
                            params.setTemperature(t);
                        }
                        Toast.makeText(activity, R.string.chat_v2_model_apply,
                                Toast.LENGTH_SHORT).show();
                        sheet.dismiss();
                        if (onApplied != null) onApplied.run();
                    } catch (Throwable t) {
                        Toast.makeText(activity, "应用失败：" + t.getMessage(),
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }

            styleTabs(tabOnline, tabLocal, true);
            render.run();
            sheet.setContentView(root);
            sheet.show();
        } catch (Throwable t) {
            Toast.makeText(activity, "模型面板打开失败：" + t.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private interface Runnable2 {
        void run();
    }

    private static void renderOnline(Activity activity, LinearLayout list,
                                     OnlineModelManager manager, String[] selected,
                                     Runnable2 onPicked, BottomSheetDialog sheet) {
        List<OnlineModelManager.OnlineModelConfig> models = manager.getModelList();
        if (models == null || models.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText(R.string.chat_v2_model_no_online);
            empty.setTextSize(12f);
            empty.setTextColor(ContextCompat.getColor(activity, R.color.chat_v2_text_3));
            empty.setPadding(0, dp(activity, 10), 0, dp(activity, 10));
            list.addView(empty);
            addCenterLink(activity, list, sheet);
            return;
        }
        for (OnlineModelManager.OnlineModelConfig cfg : models) {
            if (cfg == null) continue;
            boolean picked = cfg.id != null && cfg.id.equals(selected[0]);
            list.addView(modelRow(activity, cfg.name != null ? cfg.name : cfg.modelName,
                    cfg.modelName != null ? cfg.modelName : cfg.apiUrl, picked, v -> {
                        selected[0] = cfg.id;
                        if (onPicked != null) onPicked.run();
                    }));
        }
        addCenterLink(activity, list, sheet);
    }

    private static void renderLocal(Activity activity, LinearLayout list,
                                    BottomSheetDialog sheet) {
        TextView hint = new TextView(activity);
        hint.setText(R.string.chat_v2_model_local_hint);
        hint.setTextSize(12f);
        hint.setTextColor(ContextCompat.getColor(activity, R.color.chat_v2_text_3));
        hint.setPadding(0, dp(activity, 8), 0, dp(activity, 8));
        list.addView(hint);
        String[] names = null;
        String current = null;
        try {
            AIService ai = AIService.getInstance(activity);
            names = ai.getDownloadedModelList();
            current = ai.getCurrentModelName();
        } catch (Throwable ignored) {
        }
        if (names != null && names.length > 0) {
            for (String name : names) {
                if (name == null || name.isEmpty()) continue;
                boolean picked = name.equals(current);
                list.addView(modelRow(activity, name,
                        picked ? activity.getString(R.string.chat_v2_model) : "", picked, null));
            }
        }
        addCenterLink(activity, list, sheet);
    }

    private static View modelRow(Activity activity, String title, String subtitle,
                                 boolean selected, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10));
        row.setBackgroundResource(selected
                ? R.drawable.chat_v2_segment_selected : android.R.color.transparent);
        row.setClickable(true);
        row.setFocusable(true);
        if (onClick != null) row.setOnClickListener(onClick);

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(activity);
        t.setText(title != null ? title : "");
        t.setTextSize(14f);
        t.setTextColor(ContextCompat.getColor(activity, R.color.chat_v2_text));
        texts.addView(t);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(activity);
            s.setText(subtitle);
            s.setTextSize(11f);
            s.setTextColor(ContextCompat.getColor(activity, R.color.chat_v2_text_3));
            texts.addView(s);
        }
        row.addView(texts);

        ImageView check = new ImageView(activity);
        int size = dp(activity, 18);
        check.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        check.setImageResource(R.drawable.ic_check);
        check.setImageTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(activity, R.color.chat_v2_brand)));
        check.setVisibility(selected ? View.VISIBLE : View.GONE);
        row.addView(check);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(activity, 2);
        lp.bottomMargin = dp(activity, 2);
        row.setLayoutParams(lp);
        return row;
    }

    private static void addCenterLink(Activity activity, LinearLayout list,
                                      BottomSheetDialog sheet) {
        TextView link = new TextView(activity);
        link.setText(R.string.chat_v2_model_open_center);
        link.setTextSize(13f);
        link.setTypeface(Typeface.DEFAULT_BOLD);
        link.setTextColor(ContextCompat.getColor(activity, R.color.chat_v2_brand));
        link.setGravity(Gravity.CENTER);
        link.setPadding(0, dp(activity, 12), 0, dp(activity, 4));
        link.setOnClickListener(v -> {
            sheet.dismiss();
            activity.startActivity(new Intent(activity, ModelCenterActivity.class));
        });
        list.addView(link);
    }

    private static void styleTabs(TextView online, TextView local, boolean onlineSelected) {
        if (online != null) {
            online.setBackgroundResource(onlineSelected
                    ? R.drawable.chat_v2_segment_selected : android.R.color.transparent);
            online.setTextColor(ContextCompat.getColor(online.getContext(),
                    onlineSelected ? R.color.chat_v2_text : R.color.chat_v2_text_3));
            online.setTypeface(null, onlineSelected ? Typeface.BOLD : Typeface.NORMAL);
        }
        if (local != null) {
            local.setBackgroundResource(onlineSelected
                    ? android.R.color.transparent : R.drawable.chat_v2_segment_selected);
            local.setTextColor(ContextCompat.getColor(local.getContext(),
                    onlineSelected ? R.color.chat_v2_text_3 : R.color.chat_v2_text));
            local.setTypeface(null, onlineSelected ? Typeface.NORMAL : Typeface.BOLD);
        }
    }

    private static int dp(Activity activity, float value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}

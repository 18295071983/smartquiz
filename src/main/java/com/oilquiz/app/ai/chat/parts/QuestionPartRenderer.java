package com.oilquiz.app.ai.chat.parts;

import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.turn.AssistantTurn.Part;
import com.oilquiz.app.theme.ThemeColors;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 人工问答 part 渲染器（2026-11-12）。
 *
 * <p>与审批分开：问题可能有多条、每条带选项/多选/自由文本，答案是一整批
 * {@code {answers:[{id,selected,custom?}]}}（DSH 的 {@code AskUserQuestionAnswer}），
 * 不能复用"允许/拒绝"两个按钮。</p>
 *
 * <p>一个 part = 一次 ask（可能含多个问题），提交时一次交齐整批 —— 服务端要求
 * "每个问题恰好一条答案"，拆开提交会被 {@code BAD_ANSWER} 拒绝。</p>
 *
 * <p>数据口径：{@code Part.text}=第一个问题（供指纹/可读标题），{@code Part.args}=JSON
 * {@code {callId, questions:[{id,question,detail,header,options:[{label,description}],multiSelect,answer}]}}，
 * {@code Part.status}=pending/answered/expired。</p>
 */
public final class QuestionPartRenderer implements PartRenderer {

    @Override
    public boolean canRender(Part part) {
        return part != null && Part.TYPE_QUESTION.equals(part.type);
    }

    @Override
    public View createView(PartHost host, ViewGroup parent) {
        LinearLayout root = new LinearLayout(host.context());
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(host.context(), 10);
        root.setPadding(pad, pad, pad, pad);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    @Override
    public void bindView(View view, Part part, PartHost host) {
        if (!(view instanceof LinearLayout)) return;
        LinearLayout root = (LinearLayout) view;
        root.removeAllViews();
        Context ctx = host.context();
        boolean pending = part.isQuestionPending();

        TextView title = new TextView(ctx);
        title.setText(pending ? "电脑端在等你回答" : "问答");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        try {
            title.setTextColor(ThemeColors.get(ctx, R.color.text_secondary));
        } catch (Throwable ignored) {
            // 取色失败用默认
        }
        root.addView(title);

        JSONObject args = parse(part.args);
        JSONArray questions = args == null ? null : args.optJSONArray("questions");
        List<QuestionUi> uis = new ArrayList<>();
        if (questions != null) {
            for (int i = 0; i < questions.length(); i += 1) {
                JSONObject q = questions.optJSONObject(i);
                if (q == null) continue;
                uis.add(buildQuestion(ctx, root, q, pending));
            }
        } else if (part.text != null && !part.text.isEmpty()) {
            // 旧数据/极简事件：只有正文，退化成自由文本
            JSONObject q = new JSONObject();
            try {
                q.put("id", part.toolCallId != null ? part.toolCallId : part.id);
                q.put("question", part.text);
            } catch (Exception ignored) {
                // ignore
            }
            uis.add(buildQuestion(ctx, root, q, pending));
        }

        if (pending) {
            Button submit = new Button(ctx);
            submit.setText("提交回答");
            submit.setOnClickListener(v -> submit(host, part, uis));
            root.addView(submit);
        } else {
            TextView done = new TextView(ctx);
            done.setText("expired".equals(part.status) ? "已过期（电脑端已处理）" : "已回答");
            done.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            try {
                done.setTextColor(ThemeColors.get(ctx, R.color.success));
            } catch (Throwable ignored) {
                // ignore
            }
            root.addView(done);
        }
    }

    @Override
    public void releaseView(View view) {
        // 纯 View，无重资源。
    }

    // ---------------- 单个问题 ----------------

    private static final class QuestionUi {
        String id;
        boolean multiSelect;
        final List<String> optionLabels = new ArrayList<>();
        final List<CheckBox> boxes = new ArrayList<>();
        EditText freeText;
    }

    private static QuestionUi buildQuestion(Context ctx, LinearLayout root, JSONObject q, boolean pending) {
        QuestionUi ui = new QuestionUi();
        ui.id = q.optString("id", "");
        ui.multiSelect = q.optBoolean("multiSelect", false);

        String header = q.optString("header", "");
        if (!header.isEmpty()) {
            TextView hv = new TextView(ctx);
            hv.setText(header);
            hv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            try {
                hv.setTextColor(ThemeColors.get(ctx, R.color.text_secondary));
            } catch (Throwable ignored) {
                // ignore
            }
            root.addView(hv);
        }
        TextView qv = new TextView(ctx);
        qv.setText(q.optString("question", ""));
        qv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        qv.setPadding(0, dp(ctx, 4), 0, dp(ctx, 2));
        root.addView(qv);

        String detail = q.optString("detail", "");
        if (!detail.isEmpty()) {
            TextView dv = new TextView(ctx);
            dv.setText(detail);
            dv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            try {
                dv.setTextColor(ThemeColors.get(ctx, R.color.text_secondary));
            } catch (Throwable ignored) {
                // ignore
            }
            root.addView(dv);
        }

        JSONArray options = q.optJSONArray("options");
        if (options != null && options.length() > 0) {
            for (int i = 0; i < options.length(); i += 1) {
                JSONObject o = options.optJSONObject(i);
                if (o == null) continue;
                String label = o.optString("label", "");
                if (label.isEmpty()) continue;
                ui.optionLabels.add(label);
                CheckBox cb = new CheckBox(ctx);
                cb.setText(label);
                cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                cb.setEnabled(pending);
                String desc = o.optString("description", "");
                if (!desc.isEmpty()) cb.setContentDescription(label + "：" + desc);
                final QuestionUi owner = ui;
                cb.setOnCheckedChangeListener((b, checked) -> {
                    if (!checked || owner.multiSelect) return;
                    // 单选：勾一个就取消同组其它勾选
                    for (CheckBox other : owner.boxes) {
                        if (other != b && other.isChecked()) other.setChecked(false);
                    }
                });
                ui.boxes.add(cb);
                root.addView(cb);
            }
        }

        EditText et = new EditText(ctx);
        et.setHint("其他回答（可选）");
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setEnabled(pending);
        root.addView(et);
        ui.freeText = et;
        return ui;
    }

    private static void submit(PartHost host, Part part, List<QuestionUi> uis) {
        JSONArray answers = new JSONArray();
        for (QuestionUi ui : uis) {
            List<String> selected = new ArrayList<>();
            for (int i = 0; i < ui.boxes.size(); i += 1) {
                if (ui.boxes.get(i).isChecked() && i < ui.optionLabels.size()) {
                    selected.add(ui.optionLabels.get(i));
                }
            }
            String custom = ui.freeText != null ? ui.freeText.getText().toString().trim() : "";
            JSONObject a = new JSONObject();
            try {
                a.put("id", ui.id);
                a.put("selected", new JSONArray(selected));
                if (!custom.isEmpty()) a.put("custom", custom);
            } catch (Exception ignored) {
                // 坏字段跳过
            }
            answers.put(a);
        }
        JSONObject root = new JSONObject();
        try {
            root.put("answers", answers);
        } catch (Exception ignored) {
            // ignore
        }
        host.onQuestionAnswer(part.id, root.toString());
    }

    private static JSONObject parse(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            return new JSONObject(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static int dp(Context ctx, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }
}

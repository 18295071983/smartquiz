package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.R;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 题目卡片组件：题干 + 选项 + 答案/解析（答题宝核心场景，Track B0-3 已接 {@link CardShell}）。
 *
 * <p>本步只统一外壳；选项可点、判题、回传答题结果属于 Track B1
 * （{@code quiz_answer}），当前仍是"题目预览"形态。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "type": "single" | "multiple" | "judge",
 *   "question": "题干",
 *   "options": ["A. 选项1", "B. 选项2"],
 *   "answer": "A",
 *   "analysis": "解析文本（可选）"
 * }
 * </pre>
 */
public class QuizCardView implements ChatComponent {

    @Override
    public String getType() {
        return "quiz_card";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null && !TextUtils.isEmpty(data.props.optString("question", ""));
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        String question = p.optString("question", "");
        String answer = p.optString("answer", "");
        String analysis = p.optString("analysis", "");
        JSONArray options = p.optJSONArray("options");

        CardShell shell = CardShell.create(context)
                .setHeaderIcon(R.drawable.ic_quiz)
                .setHeader(typeLabel(context, p.optString("type", "")),
                        options != null && options.length() > 0
                                ? context.getString(R.string.card_shell_option_count, options.length())
                                : null)
                .setSubtitle(question);

        if (options == null || options.length() == 0) {
            shell.setState(CardShell.State.EMPTY, context.getString(R.string.card_shell_no_options));
        } else {
            for (int i = 0; i < options.length(); i++) {
                String option = options.optString(i, "");
                if (TextUtils.isEmpty(option)) continue;
                shell.addBody(optionView(context, option));
            }
        }

        if (!TextUtils.isEmpty(answer)) {
            shell.addBodyText(context.getString(R.string.card_quiz_answer, answer),
                    ComponentTheme.bodyTextSize(), ComponentTheme.brand(context), true);
        }
        if (!TextUtils.isEmpty(analysis)) {
            shell.addBodyText(context.getString(R.string.card_quiz_analysis, analysis),
                    ComponentTheme.metaTextSize(), ComponentTheme.textSecondary(context), false);
        }
        return shell;
    }

    /** 题型标签：不再用【单选】这类硬拼字符串，走 strings.xml */
    private static String typeLabel(Context context, String type) {
        if ("multiple".equals(type)) {
            return context.getString(R.string.card_quiz_type_multiple);
        }
        if ("judge".equals(type)) {
            return context.getString(R.string.card_quiz_type_judge);
        }
        return context.getString(R.string.card_quiz_type_single);
    }

    /**
     * 选项行（静态展示）。
     *
     * <p>刻意保留为独立方法：B1 要在这里换成可点击控件 + 选中/判题态，
     * 外壳其余部分不用动。
     */
    private TextView optionView(Context context, String option) {
        TextView tv = new TextView(context);
        tv.setText(option);
        tv.setTextSize(ComponentTheme.bodyTextSize());
        tv.setTextColor(ComponentTheme.textPrimary(context));
        tv.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        tv.setMinHeight(ComponentTheme.dp(context, 32));
        tv.setPadding(ComponentTheme.dp(context, 4), ComponentTheme.dp(context, 3),
                ComponentTheme.dp(context, 4), ComponentTheme.dp(context, 3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tv.setLayoutParams(lp);
        return tv;
    }
}

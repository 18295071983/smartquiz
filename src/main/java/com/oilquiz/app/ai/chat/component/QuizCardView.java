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

import java.util.ArrayList;
import java.util.List;

/**
 * 题目卡片组件：题干 + 可点选项 + 判题 + 解析（答题宝核心场景，B1 起可作答）。
 *
 * <p>交互口径：
 * <ul>
 *   <li>选项可点：单选/判断互斥选中，多选可累加；</li>
 *   <li>答案与解析<b>不在作答前出现</b>（此前一进来就把答案摊在卡片上，等于没法练）；</li>
 *   <li>「提交」本地判题 → 正确/错误态 + 展开解析；「重做」清空重来；</li>
 *   <li>判题在本地做（即时反馈，不等模型），结果通过
 *       {@link ComponentActions#dispatchResult} 以 {@code quiz_answer} 回传给 Agent，
 *       模型要基于答题结果继续讲评时走 {@code get_result} 取。</li>
 * </ul>
 *
 * <p>作答状态是运行期的（选项选中态、判题结果），不写回 {@code props}：
 * 会话持久化结构不动，历史恢复后题目回到"未作答"，符合预期。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "type": "single" | "multiple" | "judge",
 *   "question": "题干",
 *   "options": ["A. 选项1", "B. 选项2"],
 *   "answer": "A" | "A,C" | "对",
 *   "analysis": "解析文本（可选）"
 * }
 * </pre>
 */
public class QuizCardView implements ChatComponent {

    /** 没有 component_id 时（模型直接在正文里出题）答题结果走的回传通道 */
    private static final String QUIZ_CHANNEL = "quiz_card";

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
        JSONArray options = p.optJSONArray("items") != null ? p.optJSONArray("items")
                : p.optJSONArray("options");
        String type = p.optString("type", "single");
        boolean multiple = "multiple".equals(type);

        List<String> choices = normalizeChoices(options, type);
        List<Integer> correctIndexes = resolveAnswerIndexes(answer, choices);

        CardShell shell = CardShell.create(context)
                .setHeaderIcon(R.drawable.ic_quiz)
                .setHeader(typeLabel(context, type),
                        choices.isEmpty() ? null
                                : context.getString(R.string.card_shell_option_count, choices.size()))
                .setSubtitle(question);

        if (choices.isEmpty()) {
            shell.setState(CardShell.State.EMPTY, context.getString(R.string.card_shell_no_options));
            return shell;
        }
        if (correctIndexes.isEmpty()) {
            // 模型没给答案：只展示题目，不给"提交"，避免把无解题判成"答错"
            for (int i = 0; i < choices.size(); i++) {
                shell.addBody(readonlyOptionRow(context, choices.get(i), i));
            }
            shell.setState(CardShell.State.EMPTY, context.getString(R.string.card_quiz_no_answer));
            appendAnalysis(shell, context, analysis);
            return shell;
        }

        bindInteractive(shell, context, data, type, choices, correctIndexes, analysis, multiple);
        return shell;
    }

    // ---------------------------------------------------------------- 交互

    private void bindInteractive(final CardShell shell, final Context context, final ComponentData data,
                                 final String type, final List<String> choices,
                                 final List<Integer> correctIndexes, final String analysis,
                                 final boolean multiple) {
        final LinearLayout optionGroup = new LinearLayout(context);
        optionGroup.setOrientation(LinearLayout.VERTICAL);
        shell.addBody(optionGroup);

        final List<TextView> marks = new ArrayList<>();
        final List<Integer> selected = new ArrayList<>();
        final long startedAt = System.currentTimeMillis();
        final boolean[] answered = {false};

        for (int i = 0; i < choices.size(); i++) {
            final int index = i;
            TextView mark = new TextView(context);
            mark.setText(optionLabel(context, choices.get(i), i));
            mark.setTextSize(ComponentTheme.bodyTextSize());
            mark.setTextColor(ComponentTheme.textPrimary(context));
            mark.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            mark.setMinHeight(ComponentTheme.dp(context, 40));
            mark.setPadding(ComponentTheme.dp(context, 8), ComponentTheme.dp(context, 6),
                    ComponentTheme.dp(context, 8), ComponentTheme.dp(context, 6));
            mark.setBackground(ComponentTheme.chipBackground(context, false));
            mark.setClickable(true);
            mark.setFocusable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = ComponentTheme.dp(context, 6);
            mark.setLayoutParams(lp);
            mark.setOnClickListener(v -> {
                if (answered[0]) return;   // 已提交就锁住，改答案必须走「重做」
                if (multiple) {
                    if (selected.contains(index)) {
                        selected.remove(Integer.valueOf(index));
                        mark.setBackground(ComponentTheme.chipBackground(context, false));
                        mark.setTextColor(ComponentTheme.textPrimary(context));
                    } else {
                        selected.add(index);
                        mark.setBackground(ComponentTheme.chipBackground(context, true));
                        mark.setTextColor(ComponentTheme.brand(context));
                    }
                } else {
                    selected.clear();
                    selected.add(index);
                    for (TextView other : marks) {
                        other.setBackground(ComponentTheme.chipBackground(context, false));
                        other.setTextColor(ComponentTheme.textPrimary(context));
                    }
                    mark.setBackground(ComponentTheme.chipBackground(context, true));
                    mark.setTextColor(ComponentTheme.brand(context));
                }
                shell.getBody().invalidate();
            });
            marks.add(mark);
            optionGroup.addView(mark);
        }

        final TextView feedback = new TextView(context);
        feedback.setTextSize(ComponentTheme.bodyTextSize());
        feedback.setVisibility(View.GONE);
        LinearLayout.LayoutParams fbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fbLp.topMargin = ComponentTheme.dp(context, 10);
        shell.addBody(feedback);

        // 提交/重做互为回调，用数组持有避免 lambda 自引用（局部变量在自己的初始化式里不可见）
        final Runnable[] submitRef = new Runnable[1];
        final TextView[] analysisView = new TextView[1];

        submitRef[0] = () -> {
            if (answered[0] || selected.isEmpty()) return;
            answered[0] = true;
            boolean correct = isSameSet(selected, correctIndexes);
            for (int i = 0; i < marks.size(); i++) {
                TextView mark = marks.get(i);
                boolean isCorrectOption = correctIndexes.contains(i);
                boolean isPicked = selected.contains(i);
                mark.setClickable(false);
                if (isCorrectOption) {
                    mark.setTextColor(ComponentTheme.success(context));
                    mark.setBackground(optionBackground(context,
                            ComponentTheme.success(context), 0x1A));
                } else if (isPicked) {
                    mark.setTextColor(ComponentTheme.danger(context));
                    mark.setBackground(optionBackground(context,
                            ComponentTheme.danger(context), 0x1A));
                } else {
                    mark.setTextColor(ComponentTheme.textSecondary(context));
                    mark.setBackground(optionBackground(context,
                            ComponentTheme.outline(context), 0x00));
                }
            }
            feedback.setText(context.getString(correct
                    ? R.string.card_quiz_correct : R.string.card_quiz_wrong,
                    optionLetters(correctIndexes)));
            feedback.setTextColor(correct ? ComponentTheme.success(context)
                    : ComponentTheme.danger(context));
            feedback.setVisibility(View.VISIBLE);
            // 解析只在作答后出现；重做时收起而不是删除，避免重复插入
            if (analysisView[0] == null && !TextUtils.isEmpty(analysis)) {
                analysisView[0] = shell.addBodyText(
                        context.getString(R.string.card_quiz_analysis, analysis),
                        ComponentTheme.metaTextSize(),
                        ComponentTheme.textSecondary(context), false);
            }
            if (analysisView[0] != null) analysisView[0].setVisibility(View.VISIBLE);
            shell.clearActions().addAction(context.getString(R.string.card_quiz_redo), false, () -> {
                answered[0] = false;
                selected.clear();
                feedback.setVisibility(View.GONE);
                if (analysisView[0] != null) analysisView[0].setVisibility(View.GONE);
                for (TextView mark : marks) {
                    mark.setClickable(true);
                    mark.setTextColor(ComponentTheme.textPrimary(context));
                    mark.setBackground(ComponentTheme.chipBackground(context, false));
                }
                shell.clearActions().addAction(
                        context.getString(R.string.card_quiz_submit), true, submitRef[0]);
            });
            dispatchAnswer(context, data, type, selected, correct,
                    System.currentTimeMillis() - startedAt, choices.size());
        };
        shell.addAction(context.getString(R.string.card_quiz_submit), true, submitRef[0]);
    }

    /**
     * 回传答题结果：{@code quiz_answer} + 紧凑 JSON。
     * 有 component_id 时按 id 回传（Agent 用 get_result 取），否则走聊天 UI 通道。
     */
    private static void dispatchAnswer(Context context, ComponentData data, String type,
                                       List<Integer> selected, boolean correct,
                                       long costMs, int optionCount) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("event", "quiz_answer");
            payload.put("type", type);
            payload.put("selected", optionLetters(selected));
            payload.put("correct", correct);
            payload.put("costMs", costMs);
            payload.put("optionCount", optionCount);
            String channel = data != null && !TextUtils.isEmpty(data.id)
                    ? data.id : QUIZ_CHANNEL;
            ComponentActions.dispatchResult(context, channel, payload.toString());
        } catch (Throwable ignored) {
            // 回传失败不影响本地判题与解析展示
        }
    }

    // ---------------------------------------------------------------- 数据口径

    /** 判断题没给选项时补"正确/错误"两项，模型只填 answer 也能作答 */
    private static List<String> normalizeChoices(JSONArray options, String type) {
        List<String> out = new ArrayList<>();
        if (options != null) {
            for (int i = 0; i < options.length(); i++) {
                String v = options.optString(i, "");
                if (!TextUtils.isEmpty(v)) out.add(v);
            }
        }
        if (out.isEmpty() && "judge".equals(type)) {
            out.add("正确");
            out.add("错误");
        }
        return out;
    }

    /**
     * answer → 选项下标。兼容三种写法：字母（"A" / "A,C"）、选项原文、判断题的"对/错/正确/错误"。
     */
    private static List<Integer> resolveAnswerIndexes(String answer, List<String> choices) {
        List<Integer> out = new ArrayList<>();
        if (TextUtils.isEmpty(answer)) return out;
        String normalized = answer.toUpperCase(java.util.Locale.US)
                .replace("、", ",").replace(";", ",").replace(" ", ",");
        for (String token : normalized.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            Integer idx = matchToken(t, answer, choices);
            if (idx != null && !out.contains(idx)) out.add(idx);
        }
        return out;
    }

    private static Integer matchToken(String upperToken, String rawAnswer, List<String> choices) {
        if (upperToken.length() == 1) {
            char c = upperToken.charAt(0);
            if (c >= 'A' && c - 'A' < choices.size()) return c - 'A';
        }
        // 判断题语义
        String raw = rawAnswer.trim();
        if (isTrueWord(raw)) return 0;
        if (isFalseWord(raw)) return choices.size() > 1 ? 1 : null;
        for (int i = 0; i < choices.size(); i++) {
            String label = stripOptionPrefix(choices.get(i)).trim();
            if (label.equalsIgnoreCase(upperToken)
                    || stripOptionPrefix(choices.get(i)).trim().equals(rawAnswer.trim())) {
                return i;
            }
        }
        return null;
    }

    private static boolean isTrueWord(String s) {
        return "对".equals(s) || "正确".equals(s) || "TRUE".equalsIgnoreCase(s) || "T".equals(s);
    }

    private static boolean isFalseWord(String s) {
        return "错".equals(s) || "错误".equals(s) || "FALSE".equalsIgnoreCase(s) || "F".equals(s);
    }

    /** 选项文案自带 "A. xxx" 时不重复编号，缺编号才补字母 */
    private static String optionLabel(Context context, String raw, int index) {
        String letter = letterOf(index);
        String trimmed = raw.trim();
        if (trimmed.startsWith(letter + ".")) return trimmed;
        return letter + ". " + trimmed;
    }

    private static String stripOptionPrefix(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.length() >= 3 && Character.isLetter(t.charAt(0)) && t.charAt(1) == '.') {
            return t.substring(2);
        }
        return t;
    }

    private static String letterOf(int index) {
        return String.valueOf((char) ('A' + index));
    }

    private static String optionLetters(List<Integer> indexes) {
        List<Integer> sorted = new ArrayList<>(indexes);
        java.util.Collections.sort(sorted);
        StringBuilder sb = new StringBuilder();
        for (int i : sorted) sb.append(i > 0 ? "," : "").append(letterOf(i));
        return sb.toString();
    }

    private static boolean isSameSet(List<Integer> a, List<Integer> b) {
        if (a.size() != b.size()) return false;
        for (int v : a) if (!b.contains(v)) return false;
        return true;
    }

    private static String typeLabel(Context context, String type) {
        if ("multiple".equals(type)) {
            return context.getString(R.string.card_quiz_type_multiple);
        }
        if ("judge".equals(type)) {
            return context.getString(R.string.card_quiz_type_judge);
        }
        return context.getString(R.string.card_quiz_type_single);
    }

    /** 解析：作答前不显示，作答后作为副标题补上（避免"一上来给答案"） */
    private static void appendAnalysis(CardShell shell, Context context, String analysis) {
        if (TextUtils.isEmpty(analysis)) return;
        shell.addBodyText(context.getString(R.string.card_quiz_analysis, analysis),
                ComponentTheme.metaTextSize(), ComponentTheme.textSecondary(context), false);
    }

    /** 无答案时的只读选项行（不参与判题） */
    private static View readonlyOptionRow(Context context, String raw, int index) {
        TextView tv = new TextView(context);
        tv.setText(optionLabel(context, raw, index));
        tv.setTextSize(ComponentTheme.bodyTextSize());
        tv.setTextColor(ComponentTheme.textPrimary(context));
        tv.setMinHeight(ComponentTheme.dp(context, 32));
        tv.setPadding(ComponentTheme.dp(context, 4), ComponentTheme.dp(context, 3),
                ComponentTheme.dp(context, 4), ComponentTheme.dp(context, 3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tv.setLayoutParams(lp);
        return tv;
    }

    private static android.graphics.drawable.Drawable optionBackground(Context context,
                                                                      int accent, int alpha) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(ComponentTheme.dp(context, 10));
        gd.setColor(ComponentTheme.withAlpha(accent, alpha));
        gd.setStroke(ComponentTheme.dp(context, 1), ComponentTheme.withAlpha(accent, 0x66));
        return gd;
    }
}

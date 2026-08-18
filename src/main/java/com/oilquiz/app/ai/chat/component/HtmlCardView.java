package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML 富内容组件（原生渲染，无 WebView）。
 *
 * Agent/Python 生成的 HTML 渲染为原生 View 卡片，与图表/表格等其他组件风格统一、
 * 无滚动冲突、随消息列表滚动：
 * - 标题/段落/列表/粗斜体/代码/引用/链接等 → TextView + Html.fromHtml
 * - 表格 → 原生表格行渲染（参考 table_card 风格）
 * - 图片 → 转换为可点击链接（点击打开）
 * - 复杂 CSS/JS 不支持（降级为文本），需完整交互时请使用其他方式
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "html": "&lt;h3&gt;标题&lt;/h3&gt;&lt;p&gt;内容&lt;/p&gt;&lt;table&gt;...&lt;/table&gt;",
 *   "title": "可选卡片标题"
 * }
 * </pre>
 */
public class HtmlCardView implements ChatComponent {

    /** 表格块（非贪婪，容忍属性与多行） */
    private static final Pattern TABLE = Pattern.compile(
            "(?is)<table[^>]*>(.*?)</table>");
    /** 表格行 */
    private static final Pattern TR = Pattern.compile(
            "(?is)<tr[^>]*>(.*?)</tr>");
    /** 单元格 th/td */
    private static final Pattern TD = Pattern.compile(
            "(?is)<t[dh][^>]*>(.*?)</t[dh]>");
    /** 图片标签：<img src="url"> → 可点击链接 */
    private static final Pattern IMG = Pattern.compile(
            "(?is)<img[^>]*src\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>");

    @Override
    public String getType() {
        return "html";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null;
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        String html = p.optString("html", "");
        if (html.isEmpty()) html = p.optString("content", "");
        if (html.isEmpty()) html = p.optString("text", "");
        String title = p.optString("title", "");

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        card.setBackground(cardBackground(context));

        if (!TextUtils.isEmpty(title)) {
            TextView titleTv = new TextView(context);
            titleTv.setText(title);
            titleTv.setTextSize(14);
            titleTv.setTextColor(ComponentColors.textPrimary(context));
            titleTv.setTypeface(Typeface.DEFAULT_BOLD);
            titleTv.setPadding(0, 0, 0, dp(context, 6));
            card.addView(titleTv);
        }

        if (html.trim().isEmpty()) {
            TextView emptyTv = new TextView(context);
            emptyTv.setText("⚠ 未获取到组件内容（html 数据解析为空）");
            emptyTv.setTextSize(12);
            emptyTv.setTextColor(ComponentColors.textTertiary(context));
            card.addView(emptyTv);
            Log.w("HtmlCardView", "html component: empty content, props=" + p.toString());
            return card;
        }

        // 1. 表格块单独渲染为原生表格
        List<String> tables = new ArrayList<>();
        Matcher tableMatcher = TABLE.matcher(html);
        while (tableMatcher.find()) {
            tables.add(tableMatcher.group(1));
        }
        String rest = tableMatcher.reset(html).replaceAll("");
        for (String tableInner : tables) {
            View table = buildTable(context, tableInner);
            if (table != null) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(context, 8);
                card.addView(table, lp);
            }
        }

        // 2. 图片标签 → 可点击链接（点击打开，诚实降级不做假图）
        rest = IMG.matcher(rest).replaceAll(m -> {
            String src = m.group(1).trim();
            if (src.startsWith("http://") || src.startsWith("https://")) {
                return "<a href=\"" + src + "\">🖼 查看图片</a>";
            }
            return "🖼 [图片]";
        });

        // 3. 剩余 HTML → fromHtml 原生渲染（标题/段落/列表/粗斜体/链接/代码等）
        if (!rest.trim().isEmpty()) {
            TextView contentTv = new TextView(context);
            contentTv.setTextSize(14);
            contentTv.setTextColor(ComponentColors.textPrimary(context));
            contentTv.setLinkTextColor(ComponentColors.accent(context));
            contentTv.setText(makeClickableText(context, rest));
            contentTv.setMovementMethod(LinkMovementMethod.getInstance());
            card.addView(contentTv);
        }

        Log.i("HtmlCardView", "html component rendered natively, htmlLen=" + html.length()
                + ", tables=" + tables.size());
        return card;
    }

    /**
     * fromHtml 渲染并替换链接为可点击的 ClickableSpan（点击打开浏览器，带异常保护）。
     */
    private static SpannableStringBuilder makeClickableText(Context context, String html) {
        Spanned spanned = android.text.Html.fromHtml(html,
                android.text.Html.FROM_HTML_MODE_COMPACT, null, null);
        SpannableStringBuilder ssb = new SpannableStringBuilder(spanned);
        URLSpan[] spans = ssb.getSpans(0, ssb.length(), URLSpan.class);
        for (URLSpan span : spans) {
            int start = ssb.getSpanStart(span);
            int end = ssb.getSpanEnd(span);
            int flags = ssb.getSpanFlags(span);
            String url = span.getURL();
            ssb.removeSpan(span);
            ssb.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    openUrl(context, url);
                }

                @Override
                public void updateDrawState(android.text.TextPaint ds) {
                    ds.setColor(ComponentColors.accent(context));
                    ds.setUnderlineText(true);
                }
            }, start, end, flags);
        }
        return ssb;
    }

    /** 打开 http/https 链接（系统浏览器），其他协议忽略 */
    private static void openUrl(Context context, String url) {
        if (url == null || url.isEmpty()) return;
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return;
        try {
            android.content.Intent intent = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
            if (!(context instanceof android.app.Activity)) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        } catch (Exception e) {
            Log.w("HtmlCardView", "打开链接失败: " + url + " - " + e.getMessage());
        }
    }

    /**
     * 原生渲染表格：首行含 th 视为表头行，其余为数据行；列宽均分、边框网格。
     * 解析失败返回 null（调用方跳过该表格）。
     */
    private View buildTable(Context context, String tableInner) {
        try {
            Matcher trMatcher = TR.matcher(tableInner);
            List<List<String>> rows = new ArrayList<>();
            boolean hasHeader = tableInner.toLowerCase().contains("<th");
            while (trMatcher.find()) {
                String trInner = trMatcher.group(1);
                Matcher tdMatcher = TD.matcher(trInner);
                List<String> cells = new ArrayList<>();
                while (tdMatcher.find()) {
                    cells.add(stripTags(tdMatcher.group(1)));
                }
                if (!cells.isEmpty()) {
                    rows.add(cells);
                }
            }
            if (rows.isEmpty()) return null;

            int colCount = 0;
            for (List<String> row : rows) {
                colCount = Math.max(colCount, row.size());
            }
            if (colCount == 0) return null;

            LinearLayout table = new LinearLayout(context);
            table.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < rows.size(); i++) {
                List<String> row = rows.get(i);
                boolean header = hasHeader && i == 0;
                LinearLayout rowView = new LinearLayout(context);
                rowView.setOrientation(LinearLayout.HORIZONTAL);
                rowView.setBackgroundColor(header ? 0x1A4C8DFF : ComponentColors.background(context));
                for (int c = 0; c < colCount; c++) {
                    String text = c < row.size() ? row.get(c) : "";
                    TextView cell = new TextView(context);
                    cell.setText(text);
                    cell.setTextSize(12);
                    cell.setTextColor(header ? 0xFF1F2937 : ComponentColors.textPrimary(context));
                    if (header) cell.setTypeface(Typeface.DEFAULT_BOLD);
                    cell.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    cell.setPadding(dp(context, 6), dp(context, 5), dp(context, 6), dp(context, 5));
                    cell.setBackground(borderBackground(context));
                    rowView.addView(cell, new LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                }
                table.addView(rowView, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            return table;
        } catch (Exception e) {
            Log.w("HtmlCardView", "table render failed: " + e.getMessage());
            return null;
        }
    }

    /** 剥 HTML 标签 + 实体解码（转纯文本） */
    private static String stripTags(String html) {
        if (html == null) return "";
        String cleaned = html.replaceAll("(?is)<br\\s*/?>", "\n")
                .replaceAll("(?is)</p>|</div>|</h[1-6]>|</li>|</tr>", "\n")
                .replaceAll("(?is)<[^>]+>", "");
        Spanned spanned = android.text.Html.fromHtml(cleaned,
                android.text.Html.FROM_HTML_MODE_COMPACT, null, null);
        return spanned.toString().trim();
    }

    private static android.graphics.drawable.Drawable borderBackground(Context context) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setStroke(dp(context, 1), ComponentColors.border(context));
        return gd;
    }

    private static android.graphics.drawable.Drawable cardBackground(Context context) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(ComponentColors.background(context));
        gd.setCornerRadius(dp(context, 10));
        gd.setStroke(dp(context, 1), ComponentColors.border(context));
        return gd;
    }

    private static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}

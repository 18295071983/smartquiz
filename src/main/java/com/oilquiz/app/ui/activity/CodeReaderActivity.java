package com.oilquiz.app.ui.activity;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.oilquiz.app.ai.chat.component.ComponentColors;
import com.oilquiz.app.ai.chat.parts.CodeHighlighter;
import com.oilquiz.app.theme.Tokens;
import com.oilquiz.app.util.render.FileEncodingUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 代码阅读器（2026-11-18，持续完善）。
 *
 * <p>源码态：行号 + 语法着色 + 搜索（高亮/上一个/下一个）+ 跳行 + 字号 + 长文件分批加载；
 * HTML/Markdown/SVG/JSON 顶部可切换渲染态（WebView / 原生 md 卡 / JsonViewerCard）。</p>
 */
public class CodeReaderActivity extends Activity {

    private static final String EXTRA_FILE_PATH = "file_path";
    private static final int MAX_LINES = 20_000;
    private static final int PAGE = 3_000;

    private File file;
    private String lang;
    private String raw;
    private String[] lines = new String[0];
    private int visible;
    private int fontSp = 13;

    private FrameLayout content;
    private TextView toggle;
    private ScrollView vScroll;
    private TextView codeTv;
    private TextView searchInfo;
    private EditText searchInput;
    private View searchRow;
    private View moreRow;

    private int[] codeStarts = new int[0];
    private final List<Integer> matchLines = new ArrayList<>();
    private int matchIdx;
    private String query = "";
    private boolean rendered;

    public static void start(Context context, String filePath) {
        Intent i = new Intent(context, CodeReaderActivity.class);
        i.putExtra(EXTRA_FILE_PATH, filePath);
        if (!(context instanceof Activity)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(i);
    }

    public static boolean isCodeFile(String fileName) {
        String l = langOf(fileName);
        return l != null && !"text".equals(l);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String path = getIntent().getStringExtra(EXTRA_FILE_PATH);
        file = path == null ? null : new File(path);
        setContentView(build());
    }

    private View build() {
        if (file == null || !file.isFile()) {
            return message("文件不存在或未下载完成");
        }
        lang = langOf(file.getName());
        raw = safeRead(file);
        lines = raw.split("\n", -1);
        visible = Math.min(lines.length, PAGE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ComponentColors.background(this));
        int pad = Tokens.spaceMd(this);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(pad, pad, pad, Tokens.spaceXs(this));

        TextView title = new TextView(this);
        title.setText(file.getName());
        title.setTextSize(Tokens.textSizeSp(this, Tokens.TEXT_SUBTITLE));
        title.setTextColor(ComponentColors.textPrimary(this));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title);

        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView meta = new TextView(this);
        meta.setText(langLabel(lang) + " · " + lines.length + " 行");
        meta.setTextSize(Tokens.textSizeSp(this, Tokens.TEXT_CAPTION_SM));
        meta.setTextColor(ComponentColors.textTertiary(this));
        metaRow.addView(meta, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        toggle = actionText("预览");
        boolean canRender = canRender();
        toggle.setVisibility(canRender ? View.VISIBLE : View.GONE);
        toggle.setOnClickListener(v -> switchMode());
        metaRow.addView(toggle);
        head.addView(metaRow);
        root.addView(head);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(pad, Tokens.spaceXs(this), pad, Tokens.spaceXs(this));
        actions.addView(action("搜索", v -> toggleSearch()));
        actions.addView(action("跳行", v -> showJumpDialog()));
        actions.addView(action("A−", v -> setFont(fontSp - 1)));
        actions.addView(action("A+", v -> setFont(fontSp + 1)));
        root.addView(actions);

        searchRow = buildSearchRow(pad);
        root.addView(searchRow);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setPadding(pad, Tokens.spaceXs(this), pad, pad);
        moreRow = action("加载更多（还有 " + (lines.length - visible) + " 行）",
                v -> loadMore());
        moreRow.setVisibility(visible < lines.length ? View.VISIBLE : View.GONE);
        footer.addView(moreRow, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView copy = action("复制全文", v -> copyAll());
        footer.addView(copy);
        root.addView(footer);

        showCode();
        return root;
    }

    private View buildSearchRow(int pad) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad, 0, pad, Tokens.spaceXs(this));
        row.setVisibility(View.GONE);

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("搜索…");
        searchInput.setTextSize(Tokens.textSizeSp(this, Tokens.TEXT_CAPTION_SM));
        searchInput.setBackgroundColor(ComponentColors.fieldBg(this));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                query = s == null ? "" : s.toString().trim();
                search();
            }
        });
        row.addView(searchInput, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        searchInfo = new TextView(this);
        searchInfo.setTextSize(Tokens.textSizeSp(this, Tokens.TEXT_CAPTION_SM));
        searchInfo.setTextColor(ComponentColors.textTertiary(this));
        searchInfo.setPadding(Tokens.spaceSm(this), 0, Tokens.spaceSm(this), 0);
        row.addView(searchInfo);

        row.addView(action("↑", v -> step(-1)));
        row.addView(action("↓", v -> step(1)));
        row.addView(action("×", v -> toggleSearch()));
        return row;
    }

    private TextView actionText(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(Tokens.textSizeSp(this, Tokens.TEXT_CAPTION_SM));
        tv.setTextColor(ComponentColors.accent(this));
        tv.setPadding(Tokens.spaceSm(this), Tokens.spaceXs(this),
                Tokens.spaceSm(this), Tokens.spaceXs(this));
        return tv;
    }

    private TextView action(String text, View.OnClickListener click) {
        TextView tv = actionText(text);
        tv.setOnClickListener(click);
        return tv;
    }

    // ---------------- 源码态 ----------------

    private void showCode() {
        if (content == null) return;
        content.removeAllViews();
        rendered = false;
        toggle.setText("预览");

        codeTv = new TextView(this);
        codeTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fontSp);
        codeTv.setTextIsSelectable(true);
        codeTv.setTypeface(Typeface.MONOSPACE);
        codeTv.setLineSpacing(0f, 1.25f);
        int pad = Tokens.spaceMd(this);
        codeTv.setPadding(pad, Tokens.spaceSm(this), pad, Tokens.spaceXl(this));
        codeTv.setText(numbered());

        HorizontalScrollView h = new HorizontalScrollView(this);
        h.addView(codeTv, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.WRAP_CONTENT));
        vScroll = new ScrollView(this);
        vScroll.addView(h, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        content.addView(vScroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        if (!query.isEmpty()) {
            applySearchHighlight();
        }
        updateMoreRow();
    }

    private SpannableStringBuilder numbered() {
        int shown = Math.min(visible, lines.length);
        codeStarts = new int[shown];
        SpannableStringBuilder out = new SpannableStringBuilder();
        int width = String.valueOf(shown).length();
        for (int i = 0; i < shown; i++) {
            String no = String.valueOf(i + 1);
            for (int k = no.length(); k < width; k++) out.append(' ');
            int start = out.length();
            out.append(no);
            out.setSpan(new ForegroundColorSpan(ComponentColors.textTertiary(this)),
                    start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.append("  ");
            codeStarts[i] = out.length();
            out.append(CodeHighlighter.highlight(this, lines[i], lang));
            out.append('\n');
        }
        return out;
    }

    private void setFont(int sp) {
        int next = Math.max(10, Math.min(20, sp));
        if (next == fontSp) return;
        fontSp = next;
        if (rendered) {
            switchMode(); // 回源码态应用字号
        } else {
            int line = currentTopLine();
            showCode();
            scrollToLine(line);
        }
    }

    private void loadMore() {
        if (visible >= lines.length) return;
        visible = Math.min(Math.min(visible + PAGE, lines.length), MAX_LINES);
        int line = currentTopLine();
        showCode();
        scrollToLine(line);
    }

    private void updateMoreRow() {
        if (moreRow == null) return;
        int rest = lines.length - visible;
        moreRow.setVisibility(rest > 0 ? View.VISIBLE : View.GONE);
        if (moreRow instanceof TextView) {
            ((TextView) moreRow).setText("加载更多（还有 " + rest + " 行）");
        }
    }

    // ---------------- 搜索 / 跳行 ----------------

    private void toggleSearch() {
        boolean open = searchRow.getVisibility() != View.VISIBLE;
        searchRow.setVisibility(open ? View.VISIBLE : View.GONE);
        if (open) {
            searchInput.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
        } else {
            query = "";
            searchInput.setText("");
            if (!rendered) applySearchHighlight();
        }
    }

    private void search() {
        matchLines.clear();
        matchIdx = 0;
        if (query.isEmpty()) {
            searchInfo.setText("");
            if (!rendered) applySearchHighlight();
            return;
        }
        for (int i = 0; i < Math.min(visible, lines.length); i++) {
            if (lines[i].toLowerCase(Locale.US).contains(query.toLowerCase(Locale.US))) {
                matchLines.add(i);
            }
        }
        searchInfo.setText(matchLines.isEmpty() ? "无匹配"
                : (matchIdx + 1) + "/" + matchLines.size());
        if (!rendered) {
            applySearchHighlight();
            if (!matchLines.isEmpty()) scrollToLine(matchLines.get(0));
        }
    }

    private void step(int dir) {
        if (matchLines.isEmpty()) return;
        matchIdx = (matchIdx + dir + matchLines.size()) % matchLines.size();
        searchInfo.setText((matchIdx + 1) + "/" + matchLines.size());
        if (!rendered) {
            applySearchHighlight();
            scrollToLine(matchLines.get(matchIdx));
        }
    }

    /** 在已渲染文本上给命中行加底色（不重建文本，避免丢滚动位置）。 */
    private void applySearchHighlight() {
        if (codeTv == null) return;
        CharSequence cs = codeTv.getText();
        if (!(cs instanceof SpannableStringBuilder)) return;
        SpannableStringBuilder sb = (SpannableStringBuilder) cs;
        BackgroundColorSpan[] old = sb.getSpans(0, sb.length(), BackgroundColorSpan.class);
        for (BackgroundColorSpan s : old) sb.removeSpan(s);
        if (query.isEmpty()) return;
        int bg = withAlpha(ComponentColors.warning(this), 0.30f);
        String q = query.toLowerCase(Locale.US);
        int shown = Math.min(visible, lines.length);
        for (int i = 0; i < shown && i < codeStarts.length; i++) {
            String lower = lines[i].toLowerCase(Locale.US);
            int from = 0;
            while (true) {
                int at = lower.indexOf(q, from);
                if (at < 0) break;
                int s = codeStarts[i] + at;
                sb.setSpan(new BackgroundColorSpan(bg), s, s + q.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                from = at + Math.max(1, q.length());
            }
        }
    }

    private void showJumpDialog() {
        EditText input = new EditText(this);
        input.setHint("行号（1-" + lines.length + "）");
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        // 2026-11-18：走标准层 AppDialog（DialogStandardRatchetTest 基线 0，禁止新增手写 AlertDialog）
        com.oilquiz.app.ui.modal.AppDialog.builder(this)
                .setTitle("跳到行")
                .setView(input)
                .setPositiveButton("跳转", (d, w) -> {
                    try {
                        int line = Integer.parseInt(input.getText().toString().trim());
                        if (line >= 1 && line <= lines.length) {
                            if (line > visible) {
                                visible = Math.min(line + PAGE, MAX_LINES);
                                showCode();
                            }
                            scrollToLine(line - 1);
                        }
                    } catch (Throwable ignored) {
                        // 非法输入忽略
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void scrollToLine(int line) {
        if (vScroll == null || codeTv == null) return;
        final int target = Math.max(0, line);
        vScroll.post(() -> {
            android.text.Layout layout = codeTv.getLayout();
            int y = layout != null && target < layout.getLineCount()
                    ? layout.getLineTop(target) + codeTv.getPaddingTop()
                    : target * codeTv.getLineHeight();
            vScroll.scrollTo(0, y);
        });
    }

    private int currentTopLine() {
        if (vScroll == null || codeTv == null || codeTv.getLayout() == null) return 0;
        int y = vScroll.getScrollY() - codeTv.getPaddingTop();
        return Math.max(0, codeTv.getLayout().getLineForVertical(Math.max(0, y)));
    }

    // ---------------- 渲染态 ----------------

    private void switchMode() {
        if (!canRender()) return;
        if (rendered) {
            showCode();
            return;
        }
        rendered = true;
        toggle.setText("源码");
        content.removeAllViews();
        if ("json".equals(lang)) {
            showJson();
        } else if ("markdown".equals(lang)) {
            showMarkdown();
        } else {
            showWeb();
        }
    }

    private void showJson() {
        try {
            org.json.JSONObject props = new org.json.JSONObject();
            props.put("title", file.getName());
            try {
                props.put("json", new org.json.JSONObject(raw));
            } catch (Throwable notObject) {
                props.put("json", raw);
            }
            View view = new com.oilquiz.app.ai.chat.component.JsonViewerCard()
                    .createView(this, com.oilquiz.app.ai.chat.component.ComponentData.of(
                            "json_viewer", props));
            if (view != null) {
                ScrollView scroll = new ScrollView(this);
                scroll.addView(view);
                content.addView(scroll);
                return;
            }
        } catch (Throwable ignored) {
            // fallthrough
        }
        showCode();
    }

    private void showMarkdown() {
        try {
            org.json.JSONObject props = new org.json.JSONObject();
            props.put("path", file.getAbsolutePath());
            props.put("title", file.getName());
            View md = new com.oilquiz.app.ai.chat.component.MarkdownDocCardView()
                    .createView(this, com.oilquiz.app.ai.chat.component.ComponentData.of(
                            "markdown_doc", props));
            if (md != null) {
                ScrollView scroll = new ScrollView(this);
                scroll.addView(md);
                content.addView(scroll);
                return;
            }
        } catch (Throwable ignored) {
            // fallthrough
        }
        showCode();
    }

    private void showWeb() {
        WebView web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(false);
        s.setAllowFileAccess(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        web.setWebViewClient(new WebViewClient());
        web.loadUrl("file://" + file.getAbsolutePath());
        content.addView(web);
    }

    private boolean canRender() {
        return "html".equals(lang) || "markdown".equals(lang) || "svg".equals(lang)
                || "json".equals(lang);
    }

    // ---------------- 杂项 ----------------

    private void copyAll() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("code", raw));
        }
    }

    private String safeRead(File f) {
        try {
            String text = FileEncodingUtil.readText(f, 2_000_000);
            return text == null ? "" : text;
        } catch (Throwable t) {
            return "";
        }
    }

    private View message(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(Tokens.textSizeSp(this, Tokens.TEXT_BODY));
        tv.setTextColor(ComponentColors.textSecondary(this));
        tv.setGravity(Gravity.CENTER);
        int pad = Tokens.spaceXl(this);
        tv.setPadding(pad, pad, pad, pad);
        return tv;
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private static String langLabel(String l) {
        if (l == null) return "文本";
        switch (l) {
            case "html": return "HTML";
            case "markdown": return "Markdown";
            case "json": return "JSON";
            case "xml": return "XML";
            case "svg": return "SVG";
            case "java": return "Java";
            case "kotlin": return "Kotlin";
            case "python": return "Python";
            case "js": case "ts": case "jsx": return "JavaScript";
            case "css": return "CSS";
            default: return l.toUpperCase(Locale.US);
        }
    }

    private static String langOf(String name) {
        if (name == null) return null;
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".java")) return "java";
        if (n.endsWith(".kt") || n.endsWith(".kts")) return "kotlin";
        if (n.endsWith(".py")) return "python";
        if (n.endsWith(".js") || n.endsWith(".mjs")) return "js";
        if (n.endsWith(".ts")) return "ts";
        if (n.endsWith(".tsx") || n.endsWith(".jsx")) return "jsx";
        if (n.endsWith(".json")) return "json";
        if (n.endsWith(".xml")) return "xml";
        if (n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".xhtml")) return "html";
        if (n.endsWith(".svg")) return "svg";
        if (n.endsWith(".css")) return "css";
        if (n.endsWith(".c") || n.endsWith(".h") || n.endsWith(".cpp")) return "c";
        if (n.endsWith(".cs")) return "csharp";
        if (n.endsWith(".go")) return "go";
        if (n.endsWith(".rs")) return "rust";
        if (n.endsWith(".sh") || n.endsWith(".ps1")) return "shell";
        if (n.endsWith(".md")) return "markdown";
        if (n.endsWith(".txt") || n.endsWith(".log")) return "text";
        return null;
    }
}

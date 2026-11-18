package com.oilquiz.app.infra;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.oilquiz.app.ai.chat.parts.ToolDetailCardView;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;

/**
 * 工具详情卡调试页（2026-11-18）：用真实 {@link ToolDetailCardView} 渲染三种样例，
 * 验证"图片 → file_get → 缩略图"整条链路，不用在聊天流里翻历史。
 *
 * <p>不导出；从 DebugLayoutInjectActivity 以 open_activity 方式打开。</p>
 */
public class ToolAssetDemoActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        col.setPadding(pad, pad, pad, pad);
        scroll.addView(col, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        col.addView(title("1. read_image（真实路径，走 /ws/file）"));
        col.addView(card("read_image", "{}",
                "<path>D:\\qzq\\smartquiz\\build\\now.png</path>"
                        + "<type>image</type><content>image/webp image, 1220x2656 px</content>"));

        col.addView(title("2. read_file HTML（代码着色）"));
        col.addView(card("read_file", "{\"file_path\":\"demo/index.html\"}",
                "<!doctype html>\n<html>\n<body>\n<h1>Hello</h1>\n<!-- comment -->\n</body>\n</html>"));

        col.addView(title("3. markdown 文件（原生 md 卡）"));
        col.addView(card("read_file", "{}",
                "<path>D:\\\\qzq\\\\smartquiz\\\\CHANGELOG.md</path>"));

        col.addView(title("4. PDF（点击走专用查看器）"));
        col.addView(card("read_file", "{}",
                "<path>D:\\\\qzq\\\\smartquiz\\\\.workbuddy\\\\tmp\\\\kb_text.pdf</path>"));

        col.addView(title("5. 代码文件（代码阅读器）"));
        col.addView(card("read_file", "{}",
                "<path>D:\\\\qzq\\\\smartquiz\\\\src\\\\main\\\\java\\\\com\\\\oilquiz\\\\app\\\\ai\\\\chat\\\\parts\\\\AssetRenderer.java</path>"));

        col.addView(title("6. edit（diff）"));
        col.addView(card("edit",
                "{\"file_path\":\"a.java\",\"old_string\":\"alpha\\nbeta\",\"new_string\":\"alpha\\ngamma\"}",
                "ok"));
    }

    private View title(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tv.setTextColor(Color.GRAY);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        lp.bottomMargin = dp(8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private View card(String tool, String args, String result) {
        AssistantTurn.Part p = AssistantTurn.Part.toolCall(
                "demo_" + tool, "c_" + tool, tool, args, "success", 1L);
        p.result = result;
        View v = ToolDetailCardView.build(this, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        v.setLayoutParams(lp);
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

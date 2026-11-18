package com.oilquiz.app.infra;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.parts.AgentPanelPayload;
import com.oilquiz.app.ai.chat.parts.AgentPanelWebFlag;
import com.oilquiz.app.ai.chat.ui.AgentPanelView;
import com.oilquiz.app.theme.ThemeColors;
import com.oilquiz.app.theme.Tokens;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 执行活动区 WebView 组件 PoC（2026-11-18）。
 *
 * <p>目的不是"多一个页面"，而是**能真机量三件事**：</p>
 * <ol>
 *   <li>首帧：从建 WebView 到页面首次回传高度的耗时（{@code loadStartMs}）；</li>
 *   <li>更新成本：页内 {@code qzAgent.update()} 的平均/最大耗时、FPS（面板右下角 + 这里同步一份）；</li>
 *   <li>高度回传稳定性：每帧 {@code onHeight} 的抖动次数。</li>
 * </ol>
 *
 * <p>数据是本地模拟的流（60 帧 × 80ms），与真实 part 走同一条
 * {@code AgentPanelView.update(JSONObject)} 通路，所以量出来的注入成本就是现网成本。</p>
 */
public class AgentPanelPoCActivity extends AppCompatActivity {

    private static final long TICK_MS = 80L;
    private static final int TOTAL_TICKS = 60;

    private final Handler main = new Handler(Looper.getMainLooper());
    private AgentPanelView panel;
    private TextView statsView;
    private TextView hintView;
    private boolean running;
    private int tick;
    private long loadStartMs;
    private long firstFrameMs = -1L;
    private int heightChanges;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            tick++;
            emit(tick);
            if (tick >= TOTAL_TICKS) {
                running = false;
                hintView.setText("已结束：点「重新播放」再看一遍；打开右侧统计看注入耗时。");
                return;
            }
            main.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Tokens.space(this, Tokens.SPACE_MD);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(ThemeColors.get(this, R.color.background));

        TextView title = new TextView(this);
        title.setText("Agent 执行区 · WebView 组件 PoC");
        Tokens.applyText(title, Tokens.TEXT_TITLE, Tokens.Weight.SEMIBOLD,
                ThemeColors.get(this, R.color.text_primary));
        root.addView(title);

        hintView = new TextView(this);
        hintView.setText("下面的面板是一个 WebView（assets/agent_panel/agent_panel.html），"
                + "数据由本地模拟流驱动，走和真实消息同一条 update(JSONObject) 通路。");
        Tokens.applyText(hintView, Tokens.TEXT_FOOTNOTE, Tokens.Weight.REGULAR,
                ThemeColors.get(this, R.color.text_secondary));
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = Tokens.space(this, Tokens.SPACE_XS);
        root.addView(hintView, hintLp);

        panel = new AgentPanelView(this);
        panel.setOnHeightChanged(px -> {
            heightChanges++;
            if (firstFrameMs < 0) {
                firstFrameMs = SystemClock.uptimeMillis() - loadStartMs;
                updateHint();
            }
        });
        panel.setOnStats(stats -> {
            if (statsView == null) {
                return;
            }
            statsView.setText("updates " + stats.optInt("updates")
                    + " · 注入 avg " + stats.optDouble("avgMs", 0) + "ms"
                    + " · max " + stats.optDouble("maxMs", 0) + "ms"
                    + " · 页内 " + stats.optInt("fps") + "fps"
                    + " · 高度回传 " + heightChanges + " 次");
        });
        LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLp.topMargin = Tokens.space(this, Tokens.SPACE_MD);
        root.addView(panel, panelLp);

        statsView = new TextView(this);
        statsView.setText("等待页面就绪…");
        Tokens.applyText(statsView, Tokens.TEXT_FOOTNOTE, Tokens.Weight.REGULAR,
                ThemeColors.get(this, R.color.text_secondary));
        LinearLayout.LayoutParams statsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statsLp.topMargin = Tokens.space(this, Tokens.SPACE_SM);
        root.addView(statsView, statsLp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        Button replay = new Button(this);
        replay.setText("重新播放");
        replay.setOnClickListener(v -> restart());
        buttons.addView(replay);
        Button stop = new Button(this);
        stop.setText("停止");
        stop.setOnClickListener(v -> {
            running = false;
            main.removeCallbacks(ticker);
            hintView.setText("已停止。");
        });
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stopLp.leftMargin = Tokens.space(this, Tokens.SPACE_SM);
        buttons.addView(stop, stopLp);
        Button toggle = new Button(this);
        toggle.setText(AgentPanelWebFlag.enabled(this) ? "聊天页：WebView 已开" : "聊天页：用原生");
        toggle.setOnClickListener(v -> {
            boolean next = !AgentPanelWebFlag.enabled(this);
            AgentPanelWebFlag.setEnabled(this, next);
            toggle.setText(next ? "聊天页：WebView 已开" : "聊天页：用原生");
        });
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toggleLp.leftMargin = Tokens.space(this, Tokens.SPACE_SM);
        buttons.addView(toggle, toggleLp);
        root.addView(buttons);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        restart();
    }

    private void restart() {
        tick = 0;
        running = true;
        heightChanges = 0;
        firstFrameMs = -1L;
        loadStartMs = SystemClock.uptimeMillis();
        updateHint();
        main.removeCallbacks(ticker);
        main.postDelayed(ticker, 120L);
    }

    private void updateHint() {
        if (hintView == null) {
            return;
        }
        String frame = firstFrameMs >= 0 ? ("首帧 " + firstFrameMs + "ms") : "首帧计时中…";
        hintView.setText("模拟流：" + TICK_MS + "ms/帧 × " + TOTAL_TICKS + " 帧；" + frame);
    }

    /** 构造与真实 part 同形状的一帧（label/steps/theme）。 */
    private void emit(int i) {
        try {
            JSONObject state = new JSONObject();
            boolean active = i < TOTAL_TICKS;
            state.put("active", active);
            state.put("label", active
                    ? "正在核对第 " + i + " 处调用点…"
                    : "深度思考 6 秒 · 调用 3 个工具");

            JSONArray steps = new JSONArray();
            StringBuilder think = new StringBuilder();
            for (int k = Math.max(1, i - 4); k <= i; k++) {
                think.append("第 ").append(k).append(" 步：检查调用点、比对参数、确认返回值。");
            }
            steps.put(new JSONObject()
                    .put("id", "r1")
                    .put("kind", "thinking")
                    .put("text", think.toString()));

            int tools = Math.min(3, Math.max(1, i / 8));
            for (int t = 0; t < tools; t++) {
                boolean runningTool = active && t == tools - 1;
                steps.put(new JSONObject()
                        .put("id", "t" + t)
                        .put("kind", "tool")
                        .put("title", t == 0 ? "pwsh" : (t == 1 ? "grep" : "read_file"))
                        .put("detail", t == 0
                                ? "Get-ChildItem -Recurse | Select-String \"onDraw\""
                                : "src/main/java/com/oilquiz/app/ai/chat/…")
                        .put("status", runningTool ? "running" : "success")
                        .put("result", t == 0
                                ? "src/main/java/com/oilquiz/app/ui/widget/LiveDotsView.java:120  onDraw(Canvas)"
                                : ""));
            }
            state.put("steps", steps);
            state.put("stats", true); // PoC 页：打开页内 FPS 统计（生产聊天页不跑常驻 rAF）
            state.put("theme", AgentPanelPayload.theme(this,
                    (getResources().getConfiguration().uiMode
                            & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                            == android.content.res.Configuration.UI_MODE_NIGHT_YES));
            panel.update(state);
        } catch (Throwable ignored) {
            // 模拟流失败不影响 PoC 页面本身
        }
    }

    @Override
    protected void onDestroy() {
        running = false;
        main.removeCallbacks(ticker);
        if (panel != null) {
            panel.release();
        }
        super.onDestroy();
    }
}

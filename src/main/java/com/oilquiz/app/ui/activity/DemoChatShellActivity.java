package com.oilquiz.app.ui.activity;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.input.ChatInputManager;
import com.oilquiz.app.ai.chat.ui.ChatShellView;
import com.oilquiz.app.ai.chat.ui.GenerationStatusBar;
import com.oilquiz.app.ai.chat.ui.TokenStatsBar;
import com.oilquiz.app.ai.stats.TokenStatsManager;

import java.util.ArrayList;
import java.util.List;

/**
 * ChatShellView 验证页（demo，不入主流程）。
 *
 * 验证整页壳装配可用性：状态条（思考/空闲切换）、统计条（模拟流式）、
 * 消息流（输入栏发送追加）、输入栏接线。全部用假数据源，不触真实模型。
 */
public class DemoChatShellActivity extends Activity {

    private ChatShellView shell;
    private final List<String> messages = new ArrayList<>();
    private DemoAdapter adapter;

    // ---- 假数据源 ----
    private String fakePhaseJson = "{\"phase\":\"IDLE\",\"running\":false}";
    private boolean fakeGenerating;
    private int fakeTokens;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_demo_chat_shell);

        shell = findViewById(R.id.chat_shell);
        adapter = new DemoAdapter();
        shell.bindSources(new FakeNativeSource(), new FakeStatsSource());
        shell.setAdapter(adapter);
        messages.add("👋 这是 ChatShellView 验证页（不接真实对话）");
        messages.add("💬 底部输入栏发送消息，可验证消息流滚动");
        messages.add("🔘 右上角按钮验证状态条与统计条");
        adapter.notifyDataSetChanged();

        // 输入栏接线
        shell.attachInput(this, new ChatInputManager.Callback() {
            @Override public void onSendMessage(String text) {
                messages.add("🙋 " + text);
                adapter.notifyDataSetChanged();
                shell.getMessagesView().scrollToBottomImmediate();
            }
            @Override public void onAttachFile() {
                // demo 不接附件
            }
            @Override public void onShowToast(String message) {
                android.widget.Toast.makeText(DemoChatShellActivity.this, message, android.widget.Toast.LENGTH_SHORT).show();
            }
        }, null);

        // 模拟按钮
        Button btnThink = findViewById(R.id.btn_demo_think);
        Button btnIdle = findViewById(R.id.btn_demo_idle);
        Button btnStream = findViewById(R.id.btn_demo_stream);

        btnThink.setOnClickListener(v -> {
            fakePhaseJson = "{\"phase\":\"THINKING\",\"running\":true}";
            fakeGenerating = true;
            shell.onThinkingSegment("💭 正在分析题目并规划步骤...", 1);
            shell.onThinkingSegment("💭 正在分析题目并规划步骤...\n第二步：提取关键条件", 2);
            shell.refreshStatusBar();
        });

        btnIdle.setOnClickListener(v -> {
            fakePhaseJson = "{\"phase\":\"IDLE\",\"running\":false}";
            fakeGenerating = false;
            shell.refreshStatusBar();
            shell.setInferring(false);
        });

        btnStream.setOnClickListener(v -> {
            fakeGenerating = true;
            fakeTokens += 8;
            shell.onStreamingToken(fakeTokens, 15.3f);
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (shell != null) shell.onResume();
    }

    @Override protected void onDestroy() {
        if (shell != null) shell.onDestroy();
        super.onDestroy();
    }

    // ==================== 假数据源 ====================

    private class FakeNativeSource implements GenerationStatusBar.NativeSource {
        @Override public boolean isUsingOnlineModel() { return false; }
        @Override public String getGenPhase() { return fakePhaseJson; }
        @Override public float getDecodeSpeed() { return 15.3f; }
        @Override public String getPrefillProgress() { return null; }
        @Override public float getPhaseSpeed() { return 12.5f; }
        @Override public String getKvCacheStats() { return null; }
    }

    private class FakeStatsSource implements TokenStatsBar.StatsSource {
        @Override public boolean isUsingOnlineModel() { return false; }
        @Override public boolean isGenerating() { return fakeGenerating; }
        @Override public int getOnlineCompletionTokens() { return 0; }
        @Override public int getOnlinePromptTokens() { return 0; }
        @Override public float getPhaseSpeed() { return 12.5f; }
        @Override public float getNativeInferenceSpeed() { return 10.0f; }
        @Override public int getNativeTokenCount() { return fakeTokens; }
        @Override public String getGenPhase() { return fakePhaseJson; }
        @Override public long getStreamingTokenCount() { return fakeTokens; }
        @Override public int getLastReasoningTokens() { return 0; }
    @Override public int getLastCacheHitTokens() { return 0; }
        @Override public int getLastPromptTokens() { return 0; }
        @Override public int[] getContextWindowInfo() { return null; }
    }

    // ==================== 最简消息流 Adapter ====================

    private class DemoAdapter extends RecyclerView.Adapter<DemoAdapter.VH> {
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = (TextView) LayoutInflater.from(parent.getContext())
                    .inflate(android.R.layout.simple_list_item_1, parent, false);
            tv.setTextSize(14f);
            tv.setPadding(dp(16), dp(10), dp(16), dp(10));
            return new VH(tv);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int position) {
            h.text.setText(messages.get(position));
        }
        @Override public int getItemCount() { return messages.size(); }
        class VH extends RecyclerView.ViewHolder {
            final TextView text;
            VH(TextView tv) { super(tv); text = tv; }
        }
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}

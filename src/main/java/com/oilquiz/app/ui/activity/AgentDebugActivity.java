package com.oilquiz.app.ui.activity;

/*
 * AgentDebugActivity —— Agent 调试控制台。
 *
 * 功能：实时显示 AgentDebugBridge 外部注入通道的连通状态与执行全过程
 *      （步骤 / 思考流 / token 流 / 工具调用 / 完成 / 错误），支持通道开关与
 *      token 复制。外部注入触发时由 AgentDebugBridge 自动跳转到本页。
 *
 * 联动：PC 端可同时用 adb logcat -s AgentDebugBridge 与
 *      /sdcard/Android/data/com.oilquiz.app/files/agent_bridge/result_*.txt 观测。
 */
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.oilquiz.app.R;
import com.oilquiz.app.infra.AgentDebugBridge;

public class AgentDebugActivity extends AppCompatActivity {

    private SwitchMaterial switchBridge;
    private TextView tvBridgeState;
    private TextView tvToken;
    private TextView tvConnState;
    private TextView tvLog;
    private ScrollView scrollLog;

    private final StringBuilder logBuf = new StringBuilder();

    private final AgentDebugBridge.BridgeListener listener = new AgentDebugBridge.BridgeListener() {
        @Override
        public void onEvent(final String line) {
            runOnUiThread(() -> appendLog(line));
        }

        @Override
        public void onState(final String key, final String value) {
            runOnUiThread(() -> {
                if ("connection".equals(key)) {
                    tvConnState.setText(value);
                } else if ("status".equals(key)) {
                    tvConnState.setText(value);
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_agent_debug);

        switchBridge = findViewById(R.id.switch_bridge);
        tvBridgeState = findViewById(R.id.tv_bridge_state);
        tvToken = findViewById(R.id.tv_token);
        tvConnState = findViewById(R.id.tv_conn_state);
        tvLog = findViewById(R.id.tv_log);
        scrollLog = findViewById(R.id.scroll_log);
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        tvLog.setHorizontallyScrolling(false);

        refreshBridgeState();

        switchBridge.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AgentDebugBridge.setEnabled(AgentDebugActivity.this, isChecked);
            refreshBridgeState();
            Toast.makeText(this, isChecked ? "外部注入通道已开启" : "外部注入通道已关闭", Toast.LENGTH_SHORT).show();
        });

        MaterialButton btnCopy = findViewById(R.id.btn_copy_token);
        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("AgentBridgeToken", tvToken.getText()));
                Toast.makeText(this, "Token 已复制", Toast.LENGTH_SHORT).show();
            }
        });

        MaterialButton btnClear = findViewById(R.id.btn_clear_log);
        btnClear.setOnClickListener(v -> {
            logBuf.setLength(0);
            tvLog.setText("");
        });

        appendLog("—— Agent 调试控制台已打开 ——");
    }

    private void refreshBridgeState() {
        boolean on = AgentDebugBridge.isEnabled(this);
        switchBridge.setChecked(on);
        tvBridgeState.setText(on ? "已开启" : "已关闭");
        tvToken.setText(AgentDebugBridge.getToken(this));
    }

    private void appendLog(String line) {
        logBuf.append(line).append('\n');
        // 控制日志缓冲区大小（避免长任务撑爆内存）
        if (logBuf.length() > 100_000) {
            logBuf.delete(0, logBuf.length() - 60_000);
        }
        tvLog.setText(logBuf.toString());
        scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    protected void onResume() {
        super.onResume();
        AgentDebugBridge.setListener(listener);
        refreshBridgeState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        AgentDebugBridge.setListener(null);
    }
}

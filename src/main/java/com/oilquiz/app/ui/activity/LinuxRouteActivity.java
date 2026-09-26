package com.oilquiz.app.ui.activity;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.oilquiz.app.ai.tool.SystemResourceTool;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * 命令路由设置页（内置 Linux 工具箱）。
 *
 * <p>每个命令按顺序找可用实现，execv 失败自动换下一个：
 * 内置(lib&lt;name&gt;_bin.so) → /system/bin/&lt;name&gt; → busybox → toybox。
 * 这里让用户按命令选择顺序，配置写到 files/bin/.route，立即生效（路由器每次执行都会读）。
 *
 * <p>入口：工具集 → 设置与数据 → 命令路由。
 */
public class LinuxRouteActivity extends AppCompatActivity {

    /** 预设顺序：null = 默认(bskt) */
    private static final String[] PRESET_LABELS = {
            "默认（内置→系统→busybox→toybox）",
            "只用内置",
            "系统优先",
            "只用系统",
            "只用 busybox",
            "只用 toybox"
    };
    private static final String[] PRESET_ORDERS = {null, "b", "stkb", "s", "k", "t"};

    private LinearLayout container;
    private TextView summary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("命令路由");
        }

        ScrollView scroll = new ScrollView(this);
        container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        container.setPadding(pad, pad, pad, pad);
        scroll.addView(container);

        TextView header = new TextView(this);
        header.setText("内置 Linux 工具箱的命令路由\n\n"
                + "每个命令按顺序找可用实现，某个实现不可用会自动换下一个（不会把命令搞挂）：\n"
                + "内置 → 系统 /system/bin → busybox → toybox\n\n"
                + "改完立即生效，无需重启；配置保存在 files/bin/.route");
        header.setTextSize(13);
        header.setTextColor(Color.DKGRAY);
        header.setPadding(0, 0, 0, dp(12));
        container.addView(header);

        summary = new TextView(this);
        summary.setTextSize(12);
        summary.setTextColor(Color.GRAY);
        summary.setPadding(0, 0, 0, dp(12));
        container.addView(summary);

        List<String> tools = SystemResourceTool.routableToolNames(this);
        LinkedHashMap<String, String> routes = SystemResourceTool.readRoutes(this);
        for (String name : tools) {
            container.addView(buildRow(name, routes.get(name)));
        }

        Button resetAll = new Button(this);
        resetAll.setText("全部恢复默认");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        resetAll.setLayoutParams(lp);
        resetAll.setOnClickListener(v -> {
            SystemResourceTool.writeRoutes(LinuxRouteActivity.this, new LinkedHashMap<>());
            Toast.makeText(this, "已全部恢复默认路由", Toast.LENGTH_SHORT).show();
            recreate();
        });
        container.addView(resetAll);

        setContentView(scroll);
        refreshSummary();
    }

    private View buildRow(String name, String currentOrder) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(15);
        row.addView(label);

        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, PRESET_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(presetIndex(currentOrder));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == presetIndex(currentOrder)) {
                    return;   // 用户没改
                }
                boolean ok = SystemResourceTool.setRoute(LinuxRouteActivity.this, name, PRESET_ORDERS[position]);
                Toast.makeText(LinuxRouteActivity.this,
                        ok ? (name + " → " + PRESET_LABELS[position]) : (name + " 配置未变"),
                        Toast.LENGTH_SHORT).show();
                refreshSummary();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        row.addView(spinner);
        return row;
    }

    private void refreshSummary() {
        LinkedHashMap<String, String> routes = SystemResourceTool.readRoutes(this);
        if (routes.isEmpty()) {
            summary.setText("当前：全部使用默认顺序");
        } else {
            summary.setText("当前自定义：" + routes.toString());
        }
    }

    private static int presetIndex(String order) {
        if (order == null || order.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < PRESET_ORDERS.length; i++) {
            if (order.equals(PRESET_ORDERS[i])) {
                return i;
            }
        }
        return 0;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}

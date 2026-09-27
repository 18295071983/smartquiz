package com.oilquiz.app.ai.tool;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;

/**
 * remote_dsh 使用教程：「怎么用 + 电脑端怎么配」。
 *
 * <p>为什么要有（2026-09-27 用户指出）：这功能只在电脑上配好才可用，而大多数用户根本不知道
 * 电脑端要装什么、要跑什么脚本；而且桥接脚本本身新用户也拿不到。所以这里既讲步骤，
 * 又能把电脑端程序（启动脚本 + 桥接 + 二维码库 + README）**一键导出到手机**，再拷到电脑上双击启动。
 *
 * <p>入口：「远程连接（电脑）」顶部「怎么用 / 电脑端怎么配」。
 */
public class RemoteDshGuideActivity extends AppCompatActivity {

    private MaterialButton exportBtn;
    private TextView exportResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("怎么用（电脑端怎么配）");
        }
        setContentView(R.layout.activity_remote_dsh_guide);

        exportBtn = findViewById(R.id.btn_export);
        exportResult = findViewById(R.id.export_result);
        MaterialButton gotoConnect = findViewById(R.id.btn_goto_connect);

        if (exportBtn != null) {
            exportBtn.setOnClickListener(v -> doExport());
        }
        if (gotoConnect != null) {
            gotoConnect.setOnClickListener(v -> {
                startActivity(new Intent(this, RemoteDshConnectActivity.class));
                finish();
            });
        }
    }

    /** 导出电脑端程序（读写磁盘，放后台线程） */
    private void doExport() {
        if (exportBtn == null) {
            return;
        }
        exportBtn.setEnabled(false);
        exportBtn.setText("正在导出…");
        new Thread(() -> {
            String msg;
            boolean ok;
            try {
                String dir = RemoteDshTool.exportBridgeFiles(getApplicationContext());
                ok = true;
                msg = "已导出到：\n" + dir
                        + "\n\n里面 6 个文件（start_dsh_bridge.bat / .sh、dsh_bridge_server.py、pair_page.html、"
                        + "qrcodegen.js、README.md）\n"
                        + "请整个文件夹拷到电脑上，双击 start_dsh_bridge.bat 启动（文件缺一不可）。";
            } catch (Exception e) {
                ok = false;
                msg = "导出失败：" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                        + "\n可以在系统设置里给本应用「所有文件访问」权限后重试。";
            }
            final String fMsg = msg;
            final boolean fOk = ok;
            runOnUiThread(() -> {
                exportBtn.setEnabled(true);
                exportBtn.setText(fOk ? "重新导出电脑端程序" : "导出电脑端程序到手机");
                exportResult.setText(fMsg);
                Toast.makeText(this, fOk ? "已导出，去文件管理器 Download/OilQuiz/remote_dsh" : "导出失败",
                        Toast.LENGTH_LONG).show();
            });
        }, "remote-dsh-export").start();
    }
}

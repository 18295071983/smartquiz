package com.oilquiz.app.ai.tool;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.zxing.Result;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

/**
 * remote_dsh 扫码配对 Activity：相机扫描电脑端配对页二维码（dshpair://...），
 * 自动保存 base_url/token 到 remote_dsh_config，结果通过 RemoteDshPairBridge.lastResult 回传。
 */
public class RemoteDshPairScanActivity extends AppCompatActivity {

    private static final String TAG = "RemoteDshPairScan";
    private static final int CAMERA_REQ = 1001;

    private DecoratedBarcodeView barcodeView;
    private boolean paused = true;

    private final BarcodeCallback callback = new BarcodeCallback() {
        @Override
        public void barcodeResult(BarcodeResult result) {
            if (result == null || result.getResult() == null) return;
            final String text = result.getText();
            final String error = RemoteDshPairBridge.parseAndSave(getApplicationContext(), text);
            if (error != null) {
                RemoteDshPairBridge.lastResult = null;
                Log.w(TAG, "无效配对码: " + text);
                Toast.makeText(RemoteDshPairScanActivity.this, error, Toast.LENGTH_LONG).show();
                return;
            }
            // ★ 在这里（而不是只在 AI 的 action=pair 里）登记设备：
            //   2026-10-06 实测踩坑——手机连着、对话正常，但电脑端「已配对设备」恒为 0。
            //   原因是从界面「扫码配对」按钮进来的这条路只写配置就 return，从不 call /pair/claim；
            //   只有 AI 调 action=pair 时才会登记。把登记放在扫码结果回调里，任何入口都覆盖。
            barcodeView.setStatusText("配对成功，正在登记设备…");
            final android.content.Context app = getApplicationContext();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final String claimErr = RemoteDshPairBridge.claimDeviceToken(
                            app, RemoteDshTool.configValue(app, "base_url"),
                            RemoteDshTool.configValue(app, RemoteDshTool.KEY_MAIN_TOKEN));
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (claimErr == null) {
                                RemoteDshPairBridge.lastResult = RemoteDshPairBridge.RESULT_OK;
                                Toast.makeText(RemoteDshPairScanActivity.this,
                                        "配对成功：已登记为设备（可在电脑端单独吊销）", Toast.LENGTH_LONG).show();
                            } else {
                                RemoteDshPairBridge.lastResult = null;
                                Log.w(TAG, "登记设备失败: " + claimErr);
                                Toast.makeText(RemoteDshPairScanActivity.this,
                                        "配对成功，但设备登记失败：" + claimErr
                                                + "\n仍可使用（走主令牌）", Toast.LENGTH_LONG).show();
                            }
                            finish();
                        }
                    });
                }
            }, "remote-dsh-claim-scan").start();
        }

    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("扫描电脑配对二维码");
        barcodeView = new DecoratedBarcodeView(this);
        // 识别失败的反馈（2026-10-06 实测踩过）：相机对焦要一两秒才出预览帧，用户容易以为坏了就按返回，
        // 而识别不到时页面毫无提示。这里把"要停住几秒""该扫哪张码"直接写在扫码页上。
        barcodeView.setStatusText("对准电脑屏幕上的二维码，停住 2-3 秒\n"
                + "（配对页有两张码：手机与电脑不在同一网段时，请扫「公网/隧道」那张）");
        setContentView(barcodeView);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAMERA_REQ);
        } else {
            startScan();
        }
    }

    private void startScan() {
        if (barcodeView != null) {
            barcodeView.decodeContinuous(callback);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_REQ) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startScan();
            } else {
                Toast.makeText(this, "需要相机权限才能扫码配对", Toast.LENGTH_LONG).show();
                RemoteDshPairBridge.lastResult = null;
                finish();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (barcodeView != null && paused) {
            barcodeView.resume();
            paused = false;
        }
    }

    @Override
    protected void onPause() {
        if (barcodeView != null && !paused) {
            barcodeView.pause();
            paused = true;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        barcodeView = null;
        super.onDestroy();
    }
}

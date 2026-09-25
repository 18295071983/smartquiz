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
            String error = RemoteDshPairBridge.parseAndSave(getApplicationContext(), result.getText());
            if (error == null) {
                RemoteDshPairBridge.lastResult = "OK";
                finish();
            } else {
                RemoteDshPairBridge.lastResult = null;
                Log.w(TAG, "无效配对码: " + result.getText());
                Toast.makeText(RemoteDshPairScanActivity.this, error, Toast.LENGTH_LONG).show();
            }
        }

    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("扫描电脑配对二维码");
        barcodeView = new DecoratedBarcodeView(this);
        barcodeView.setStatusText("对准电脑屏幕上的配对二维码");
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

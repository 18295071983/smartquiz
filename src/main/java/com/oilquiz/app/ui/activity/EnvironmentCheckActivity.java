package com.oilquiz.app.ui.activity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.text.format.Formatter;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import com.google.android.material.button.MaterialButton;
import android.widget.TextView;
import android.widget.Toast;

import com.oilquiz.app.R;
import com.oilquiz.app.ui.base.BaseActivity;
import com.oilquiz.app.util.NativeEnvironmentChecker;

import java.io.File;

public class EnvironmentCheckActivity extends BaseActivity {

    private TextView tvManufacturer;
    private TextView tvBrand;
    private TextView tvModel;
    private TextView tvHardware;
    private TextView tvAndroidVersion;
    private TextView tvSdkInt;
    private TextView tvSecurityPatch;
    private TextView tvLanguage;
    private TextView tvCpuAbi;
    private TextView tvProcessors;
    private TextView tvCamera;
    private TextView tvGps;
    private TextView tvTotalMemory;
    private TextView tvAvailableMemory;
    private TextView tvInternalStorage;
    private TextView tvExternalStorage;

    private MaterialButton btnCopy;
    private MaterialButton btnShare;

    private NativeEnvironmentChecker environmentChecker;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_environment_check;
    }

    @Override
    protected void initView() {
        setupToolbar("原生环境检测");

        // 设备信息
        tvManufacturer = findViewById(R.id.tv_manufacturer);
        tvBrand = findViewById(R.id.tv_brand);
        tvModel = findViewById(R.id.tv_model);
        tvHardware = findViewById(R.id.tv_hardware);

        // 系统信息
        tvAndroidVersion = findViewById(R.id.tv_android_version);
        tvSdkInt = findViewById(R.id.tv_sdk_int);
        tvSecurityPatch = findViewById(R.id.tv_security_patch);
        tvLanguage = findViewById(R.id.tv_language);

        // 硬件信息
        tvCpuAbi = findViewById(R.id.tv_cpu_abi);
        tvProcessors = findViewById(R.id.tv_processors);
        tvCamera = findViewById(R.id.tv_camera);
        tvGps = findViewById(R.id.tv_gps);

        // 内存信息
        tvTotalMemory = findViewById(R.id.tv_total_memory);
        tvAvailableMemory = findViewById(R.id.tv_available_memory);

        // 存储信息
        tvInternalStorage = findViewById(R.id.tv_internal_storage);
        tvExternalStorage = findViewById(R.id.tv_external_storage);

        // 按钮
        btnCopy = findViewById(R.id.btn_copy);
        btnShare = findViewById(R.id.btn_share);
    }

    @Override
    protected void initData() {
        environmentChecker = new NativeEnvironmentChecker(this);
        updateEnvironmentInfo();
    }

    @Override
    protected void initListener() {
        btnCopy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyEnvironmentInfo();
            }
        });

        btnShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareEnvironmentInfo();
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.history_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_refresh) {
            updateEnvironmentInfo();
            Toast.makeText(this, "信息已刷新", Toast.LENGTH_SHORT).show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateEnvironmentInfo() {
        // 设备信息
        tvManufacturer.setText(environmentChecker.getDeviceInfo().manufacturer);
        tvBrand.setText(environmentChecker.getDeviceInfo().brand);
        tvModel.setText(environmentChecker.getDeviceInfo().model);
        tvHardware.setText(environmentChecker.getDeviceInfo().hardware);

        // 系统信息
        tvAndroidVersion.setText(environmentChecker.getSystemInfo().androidVersion);
        tvSdkInt.setText(String.valueOf(environmentChecker.getSystemInfo().sdkInt));
        tvSecurityPatch.setText(environmentChecker.getSystemInfo().securityPatch);
        tvLanguage.setText(environmentChecker.getSystemInfo().language);

        // 硬件信息
        tvCpuAbi.setText(environmentChecker.getHardwareInfo().cpuAbi);
        tvProcessors.setText(String.valueOf(environmentChecker.getRuntimeInfo().availableProcessors));
        tvCamera.setText(environmentChecker.getHardwareInfo().hasCamera ? "支持" : "不支持");
        tvGps.setText(environmentChecker.getHardwareInfo().hasGPS ? "支持" : "不支持");

        // 内存信息
        tvTotalMemory.setText(Formatter.formatFileSize(this, environmentChecker.getMemoryInfo().totalMemory));
        tvAvailableMemory.setText(Formatter.formatFileSize(this, environmentChecker.getMemoryInfo().availableMemory));

        // 存储信息
        tvInternalStorage.setText(getStorageInfo(Environment.getDataDirectory()));
        tvExternalStorage.setText(getStorageInfo(Environment.getExternalStorageDirectory()));
    }

    private String getStorageInfo(File path) {
        try {
            StatFs stat = new StatFs(path.getPath());
            long blockSize = stat.getBlockSizeLong();
            long totalBlocks = stat.getBlockCountLong();
            long availableBlocks = stat.getAvailableBlocksLong();

            long totalSize = totalBlocks * blockSize;
            long availableSize = availableBlocks * blockSize;

            return Formatter.formatFileSize(this, availableSize) + " / " + Formatter.formatFileSize(this, totalSize);
        } catch (Exception e) {
            return "-";
        }
    }

    private void copyEnvironmentInfo() {
        StringBuilder info = new StringBuilder();
        info.append("=== 设备环境检测报告 ===\n\n");
        info.append("【设备信息】\n");
        info.append("制造商: " + environmentChecker.getDeviceInfo().manufacturer + "\n");
        info.append("品牌: " + environmentChecker.getDeviceInfo().brand + "\n");
        info.append("型号: " + environmentChecker.getDeviceInfo().model + "\n");
        info.append("硬件: " + environmentChecker.getDeviceInfo().hardware + "\n\n");

        info.append("【系统信息】\n");
        info.append("Android版本: " + environmentChecker.getSystemInfo().androidVersion + "\n");
        info.append("SDK级别: " + environmentChecker.getSystemInfo().sdkInt + "\n");
        info.append("安全补丁: " + environmentChecker.getSystemInfo().securityPatch + "\n");
        info.append("语言: " + environmentChecker.getSystemInfo().language + "\n\n");

        info.append("【硬件信息】\n");
        info.append("CPU架构: " + environmentChecker.getHardwareInfo().cpuAbi + "\n");
        info.append("处理器数量: " + environmentChecker.getRuntimeInfo().availableProcessors + "\n");
        info.append("相机: " + (environmentChecker.getHardwareInfo().hasCamera ? "支持" : "不支持") + "\n");
        info.append("GPS: " + (environmentChecker.getHardwareInfo().hasGPS ? "支持" : "不支持") + "\n\n");

        info.append("【内存信息】\n");
        info.append("总内存: " + Formatter.formatFileSize(this, environmentChecker.getMemoryInfo().totalMemory) + "\n");
        info.append("可用内存: " + Formatter.formatFileSize(this, environmentChecker.getMemoryInfo().availableMemory) + "\n\n");

        info.append("【存储信息】\n");
        info.append("内部存储: " + getStorageInfo(Environment.getDataDirectory()) + "\n");
        info.append("外部存储: " + getStorageInfo(Environment.getExternalStorageDirectory()) + "\n");

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("环境检测报告", info.toString());
        clipboard.setPrimaryClip(clip);

        Toast.makeText(this, "信息已复制到剪贴板", Toast.LENGTH_SHORT).show();
    }

    private void shareEnvironmentInfo() {
        StringBuilder info = new StringBuilder();
        info.append("=== 设备环境检测报告 ===\n\n");
        info.append("【设备信息】\n");
        info.append("制造商: " + environmentChecker.getDeviceInfo().manufacturer + "\n");
        info.append("品牌: " + environmentChecker.getDeviceInfo().brand + "\n");
        info.append("型号: " + environmentChecker.getDeviceInfo().model + "\n");
        info.append("硬件: " + environmentChecker.getDeviceInfo().hardware + "\n\n");

        info.append("【系统信息】\n");
        info.append("Android版本: " + environmentChecker.getSystemInfo().androidVersion + "\n");
        info.append("SDK级别: " + environmentChecker.getSystemInfo().sdkInt + "\n");
        info.append("安全补丁: " + environmentChecker.getSystemInfo().securityPatch + "\n");
        info.append("语言: " + environmentChecker.getSystemInfo().language + "\n\n");

        info.append("【硬件信息】\n");
        info.append("CPU架构: " + environmentChecker.getHardwareInfo().cpuAbi + "\n");
        info.append("处理器数量: " + environmentChecker.getRuntimeInfo().availableProcessors + "\n");
        info.append("相机: " + (environmentChecker.getHardwareInfo().hasCamera ? "支持" : "不支持") + "\n");
        info.append("GPS: " + (environmentChecker.getHardwareInfo().hasGPS ? "支持" : "不支持") + "\n\n");

        info.append("【内存信息】\n");
        info.append("总内存: " + Formatter.formatFileSize(this, environmentChecker.getMemoryInfo().totalMemory) + "\n");
        info.append("可用内存: " + Formatter.formatFileSize(this, environmentChecker.getMemoryInfo().availableMemory) + "\n\n");

        info.append("【存储信息】\n");
        info.append("内部存储: " + getStorageInfo(Environment.getDataDirectory()) + "\n");
        info.append("外部存储: " + getStorageInfo(Environment.getExternalStorageDirectory()) + "\n");

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "设备环境检测报告");
        intent.putExtra(Intent.EXTRA_TEXT, info.toString());
        startActivity(Intent.createChooser(intent, "分享环境检测报告"));
    }
}

package com.oilquiz.app.ui.activity;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.config.DeviceCapabilityDetector;
import com.oilquiz.app.ai.config.DeviceCapabilityDetector.DeviceInfo;

public class DeviceInfoActivity extends AppCompatActivity {

    private TextView deviceInfoTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_info);

        // 初始化 UI 组件
        deviceInfoTextView = findViewById(R.id.device_info_text_view);

        // 获取设备信息
        DeviceInfo deviceInfo = DeviceCapabilityDetector.getDetailedDeviceInfo(this);

        // 显示设备信息
        displayDeviceInfo(deviceInfo);
    }

    private void displayDeviceInfo(DeviceInfo deviceInfo) {
        StringBuilder infoBuilder = new StringBuilder();
        infoBuilder.append("设备信息\n\n");
        infoBuilder.append("设备层级: ").append(getTierDisplayName(deviceInfo.tier)).append("\n");
        infoBuilder.append("设备型号: ").append(deviceInfo.model).append("\n");
        infoBuilder.append("Android 版本: ").append(deviceInfo.androidVersion).append(" (API ").append(deviceInfo.apiLevel).append(")\n");
        infoBuilder.append("内存: ").append(deviceInfo.totalMemoryMB).append("MB 总内存, ").append(deviceInfo.availableMemoryMB).append("MB 可用\n");
        infoBuilder.append("CPU: ").append(deviceInfo.cpuCores).append(" 核心, 最高频率: ").append(deviceInfo.maxCpuFrequency).append(" MHz\n");
        infoBuilder.append("是否有高频核心: ").append(deviceInfo.hasBigCores ? "是" : "否").append("\n");

        deviceInfoTextView.setText(infoBuilder.toString());
    }

    private String getTierDisplayName(DeviceCapabilityDetector.DeviceTier tier) {
        switch (tier) {
            case FLAGSHIP:
                return "旗舰级";
            case HIGH_END:
                return "高端";
            case MID_RANGE:
                return "中端";
            case BUDGET:
                return "低端";
            default:
                return "未知";
        }
    }
}

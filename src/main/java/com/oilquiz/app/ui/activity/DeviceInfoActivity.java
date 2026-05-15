package com.oilquiz.app.ui.activity;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.optimization.DeviceDetector;

public class DeviceInfoActivity extends AppCompatActivity {

    private TextView deviceInfoTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_info);

        deviceInfoTextView = findViewById(R.id.device_info_text_view);
        String deviceInfo = DeviceDetector.getDeviceInfo();
        deviceInfoTextView.setText(deviceInfo);
    }
}

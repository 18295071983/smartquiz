package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.oilquiz.app.R;

public class WeatherTestActivity extends AppCompatActivity {

    private EditText urlInput;
    private Button testButton;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);

        TextView title = new TextView(this);
        title.setText("天气界面测试");
        title.setTextSize(24);
        title.setPadding(0, 0, 0, 16);
        layout.addView(title);

        urlInput = new EditText(this);
        urlInput.setHint("输入 fxLink URL (可选)");
        urlInput.setText("https://www.qweather.com/weather/beijing-101010100.html");
        layout.addView(urlInput);

        testButton = new Button(this);
        testButton.setText("打开天气详情界面");
        testButton.setOnClickListener(v -> openWeatherDetail());
        layout.addView(testButton);

        statusText = new TextView(this);
        statusText.setPadding(0, 16, 0, 0);
        layout.addView(statusText);

        setContentView(layout);
    }

    private void openWeatherDetail() {
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) {
            statusText.setText("请输入 URL 或直接测试默认 URL");
            return;
        }

        Intent intent = new Intent(this, WeatherWebViewActivity.class);
        intent.putExtra("url", url);
        intent.putExtra("title", "天气测试 - 北京");
        startActivity(intent);
        statusText.setText("正在打开天气详情界面...");
    }
}

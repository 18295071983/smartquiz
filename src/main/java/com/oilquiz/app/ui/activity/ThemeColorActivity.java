package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;

import com.oilquiz.app.R;
import com.oilquiz.app.model.ThemeColor;
import com.oilquiz.app.repository.ThemeColorRepository;
import com.oilquiz.app.ui.ThemeColorAdapter;
import com.oilquiz.app.manager.ThemeColorManager;
import com.oilquiz.app.MainActivity;

import java.util.List;

public class ThemeColorActivity extends AppCompatActivity {

    private ThemeColorRepository themeColorRepository;
    private ListView themeColorListView;
    private ThemeColorAdapter themeColorAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_theme_color);

        themeColorRepository = new ThemeColorRepository(this);

        themeColorListView = findViewById(R.id.lv_theme_colors);

        List<ThemeColor> themeColors = themeColorRepository.getThemeColors();
        themeColorAdapter = new ThemeColorAdapter(this, themeColors);
        themeColorListView.setAdapter(themeColorAdapter);

        themeColorListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                ThemeColor selectedColor = themeColors.get(position);
                if ("自定义颜色".equals(selectedColor.getName())) {
                    // 启用自定义颜色
                    android.content.SharedPreferences preferences = getSharedPreferences("theme_preferences", MODE_PRIVATE);
                    preferences.edit()
                            .putBoolean("use_custom_color", true)
                            .apply();
                    themeColorAdapter.setSelectedPosition(position);
                    themeColorAdapter.notifyDataSetChanged();
                    // 重启应用以应用新主题色
                    Intent intent = new Intent(ThemeColorActivity.this, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    finish();
                } else {
                    // 使用预设主题色
                    themeColorRepository.setCurrentThemeColor(selectedColor.getColorRes());
                    themeColorAdapter.setSelectedPosition(position);
                    themeColorAdapter.notifyDataSetChanged();
                    // 重启应用以应用新主题色
                    Intent intent = new Intent(ThemeColorActivity.this, MainActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    finish();
                }
            }
        });

        // 自定义颜色按钮点击事件
        findViewById(R.id.btn_custom_color).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showColorPickerDialog();
            }
        });
    }

    // 显示颜色选择对话框
    private void showColorPickerDialog() {
        // 创建颜色选择对话框
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle("选择颜色");

        // 创建颜色选择器视图
        final android.widget.SeekBar redSeekBar = new android.widget.SeekBar(this);
        final android.widget.SeekBar greenSeekBar = new android.widget.SeekBar(this);
        final android.widget.SeekBar blueSeekBar = new android.widget.SeekBar(this);

        redSeekBar.setMax(255);
        greenSeekBar.setMax(255);
        blueSeekBar.setMax(255);

        // 设置当前主题色作为默认值
        int currentColor = getResources().getColor(themeColorRepository.getCurrentThemeColor());
        int red = (currentColor >> 16) & 0xFF;
        int green = (currentColor >> 8) & 0xFF;
        int blue = currentColor & 0xFF;

        redSeekBar.setProgress(red);
        greenSeekBar.setProgress(green);
        blueSeekBar.setProgress(blue);

        // 创建颜色预览视图
        final android.view.View colorPreview = new android.view.View(this);
        colorPreview.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                100
        ));
        colorPreview.setBackgroundColor(android.graphics.Color.rgb(red, green, blue));

        // 更新颜色预览
        android.widget.SeekBar.OnSeekBarChangeListener listener = new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                int r = redSeekBar.getProgress();
                int g = greenSeekBar.getProgress();
                int b = blueSeekBar.getProgress();
                colorPreview.setBackgroundColor(android.graphics.Color.rgb(r, g, b));
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        };

        redSeekBar.setOnSeekBarChangeListener(listener);
        greenSeekBar.setOnSeekBarChangeListener(listener);
        blueSeekBar.setOnSeekBarChangeListener(listener);

        // 构建布局
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(20, 20, 20, 20);

        layout.addView(colorPreview);
        layout.addView(new android.widget.TextView(this) {{ setText("红色"); setPadding(0, 10, 0, 5); }});
        layout.addView(redSeekBar);
        layout.addView(new android.widget.TextView(this) {{ setText("绿色"); setPadding(0, 10, 0, 5); }});
        layout.addView(greenSeekBar);
        layout.addView(new android.widget.TextView(this) {{ setText("蓝色"); setPadding(0, 10, 0, 5); }});
        layout.addView(blueSeekBar);

        builder.setView(layout);

        // 设置按钮
        builder.setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                int r = redSeekBar.getProgress();
                int g = greenSeekBar.getProgress();
                int b = blueSeekBar.getProgress();
                int customColor = android.graphics.Color.rgb(r, g, b);
                
                // 保存自定义颜色
                saveCustomColor(customColor);
            }
        });

        builder.setNegativeButton("取消", null);
        builder.show();
    }

    // 保存自定义颜色
    private void saveCustomColor(int color) {
        // 使用SharedPreferences来保存自定义颜色值和标记
        android.content.SharedPreferences preferences = getSharedPreferences("theme_preferences", MODE_PRIVATE);
        preferences.edit()
                .putInt("custom_theme_color", color)
                .putBoolean("use_custom_color", true)
                .apply();
        
        // 重启应用以应用新主题色
        Intent intent = new Intent(ThemeColorActivity.this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }
}

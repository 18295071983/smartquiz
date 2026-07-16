package com.oilquiz.app.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.GridView;
import android.widget.RadioGroup;

import androidx.appcompat.app.AppCompatDelegate;

import com.oilquiz.app.R;
import com.oilquiz.app.model.ThemeColor;
import com.oilquiz.app.ui.base.BaseActivity;

import java.util.ArrayList;
import java.util.List;

public class ThemeSwitcherActivity extends BaseActivity {

    private GridView gvThemeColors;
    private RadioGroup rgThemeMode;
    private ThemeColorAdapter themeColorAdapter;
    private List<ThemeColor> themeColors;
    private int selectedColorPosition = 0;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_theme_switcher;
    }

    @Override
    protected void initView() {
        gvThemeColors = findViewById(R.id.gv_theme_colors);
        rgThemeMode = findViewById(R.id.rg_theme_mode);
    }

    @Override
    protected void initData() {
        themeColors = new ArrayList<>();
        themeColors.add(new ThemeColor(1, "蓝色", R.color.theme_blue, true));
        themeColors.add(new ThemeColor(2, "绿色", R.color.theme_green, false));
        themeColors.add(new ThemeColor(3, "紫色", R.color.theme_purple, false));
        themeColors.add(new ThemeColor(4, "橙色", R.color.theme_orange, false));
        themeColors.add(new ThemeColor(5, "粉色", R.color.theme_pink, false));
        themeColors.add(new ThemeColor(6, "青色", R.color.theme_teal, false));
        themeColors.add(new ThemeColor(7, "靛蓝", R.color.theme_indigo, false));
        themeColors.add(new ThemeColor(8, "AI紫", R.color.aiPrimary, false));

        themeColorAdapter = new ThemeColorAdapter(this, themeColors, selectedColorPosition);
        gvThemeColors.setAdapter(themeColorAdapter);
    }

    @Override
    protected void initListener() {
        gvThemeColors.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                selectedColorPosition = position;
                themeColorAdapter.setSelectedPosition(position);
                themeColorAdapter.notifyDataSetChanged();
                // 这里可以添加应用主题色的逻辑
            }
        });

        rgThemeMode.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                if (checkedId == R.id.rb_light) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                } else if (checkedId == R.id.rb_dark) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                }
            }
        });
    }
}

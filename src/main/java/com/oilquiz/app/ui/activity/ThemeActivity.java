package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.cardview.widget.CardView;

import com.oilquiz.app.R;
import com.oilquiz.app.MainActivity;
import com.oilquiz.app.manager.ThemeColorManager;
import com.oilquiz.app.resource.AppResourceManager;

public class ThemeActivity extends AppCompatActivity {

    private static final String PREF_NAME = "theme_preferences";
    private static final String KEY_THEME = "current_theme";

    private RadioGroup radioGroupThemeMode;
    private RadioButton radioLight;
    private RadioButton radioDark;
    private RadioButton radioSystem;
    private LinearLayout linearLayoutThemeColors;
    private AppResourceManager resources;
    private ThemeColorManager themeColorManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_theme);

        resources = AppResourceManager.getInstance(this);
        themeColorManager = new ThemeColorManager();

        radioGroupThemeMode = findViewById(R.id.radioGroupThemeMode);
        radioLight = findViewById(R.id.radio_light);
        radioDark = findViewById(R.id.radio_dark);
        radioSystem = findViewById(R.id.radio_system);
        linearLayoutThemeColors = findViewById(R.id.linearLayoutThemeColors);

        loadCurrentThemeMode();
        setupThemeColors();

        radioGroupThemeMode.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                int themeMode;
                int nightMode;

                if (checkedId == R.id.radio_dark) {
                    themeMode = 1;
                    nightMode = AppCompatDelegate.MODE_NIGHT_YES;
                } else if (checkedId == R.id.radio_system) {
                    themeMode = 2;
                    nightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                } else {
                    themeMode = 0;
                    nightMode = AppCompatDelegate.MODE_NIGHT_NO;
                }

                SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
                prefs.edit().putInt(KEY_THEME, themeMode).apply();

                AppCompatDelegate.setDefaultNightMode(nightMode);
            }
        });

        findViewById(R.id.btn_save_theme).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    private void loadCurrentThemeMode() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        int themeMode = prefs.getInt(KEY_THEME, 2);
        if (themeMode == 1) {
            radioDark.setChecked(true);
        } else if (themeMode == 2) {
            radioSystem.setChecked(true);
        } else {
            radioLight.setChecked(true);
        }
    }

    private void setupThemeColors() {
        String[] themeColors = {"蓝色", "绿色", "紫色", "橙色", "粉色", "青色", "靛蓝"};
        int[] colorValues = {
            R.color.theme_blue,
            R.color.theme_green,
            R.color.theme_purple,
            R.color.theme_orange,
            R.color.theme_pink,
            R.color.theme_teal,
            R.color.theme_indigo
        };

        int currentThemeColor = themeColorManager.getCurrentThemeColor(this);

        for (int i = 0; i < themeColors.length; i++) {
            final String colorName = themeColors[i];
            final int colorRes = colorValues[i];

            CardView cardView = new CardView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, 0, 0, 8);
            cardView.setLayoutParams(params);
            cardView.setCardElevation(1);
            cardView.setRadius(8);
            cardView.setCardBackgroundColor(getResources().getColor(R.color.surface_variant));

            LinearLayout cardLayout = new LinearLayout(this);
            LinearLayout.LayoutParams cardLayoutParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            cardLayout.setLayoutParams(cardLayoutParams);
            cardLayout.setOrientation(LinearLayout.HORIZONTAL);
            cardLayout.setPadding(12, 12, 12, 12);

            View colorView = new View(this);
            LinearLayout.LayoutParams colorParams = new LinearLayout.LayoutParams(40, 40);
            colorParams.setMargins(0, 0, 12, 0);
            colorView.setLayoutParams(colorParams);
            colorView.setBackgroundColor(getResources().getColor(colorRes));
            colorView.setClipToOutline(true);

            final RadioButton radioButton = new RadioButton(this);
            LinearLayout.LayoutParams radioParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            radioParams.setMargins(0, 0, 12, 0);
            radioButton.setLayoutParams(radioParams);

            if (colorRes == currentThemeColor) {
                radioButton.setChecked(true);
            }

            android.widget.TextView textView = new android.widget.TextView(this);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            textParams.weight = 1;
            textView.setLayoutParams(textParams);
            textView.setText(colorName);
            textView.setTextSize(14);
            textView.setTextColor(getResources().getColor(R.color.on_surface));

            cardLayout.addView(colorView);
            cardLayout.addView(radioButton);
            cardLayout.addView(textView);
            cardView.addView(cardLayout);
            linearLayoutThemeColors.addView(cardView);

            final int finalI = i;
            cardView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Toast.makeText(ThemeActivity.this, "已选择" + colorName + "主题", Toast.LENGTH_SHORT).show();
                    themeColorManager.setThemeColor(ThemeActivity.this, colorRes);
                    resources.config().setConfig("theme_color", finalI);

                    for (int j = 0; j < linearLayoutThemeColors.getChildCount(); j++) {
                        CardView childCard = (CardView) linearLayoutThemeColors.getChildAt(j);
                        LinearLayout childLayout = (LinearLayout) childCard.getChildAt(0);
                        RadioButton childRadio = (RadioButton) childLayout.getChildAt(1);
                        childRadio.setChecked(j == finalI);
                    }
                }
            });
        }
    }
}

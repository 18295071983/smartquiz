package com.oilquiz.app.ui.activity;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import androidx.appcompat.app.AppCompatActivity;

import com.oilquiz.app.R;
import com.oilquiz.app.manager.LanguageManager;

import java.util.ArrayList;
import java.util.List;

public class LanguageActivity extends AppCompatActivity {

    private ListView languageListView;
    private ArrayAdapter<String> languageAdapter;
    private List<String> languageNames;
    private List<String> languageCodes;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_language);

        languageListView = findViewById(R.id.lv_languages);
        languageListView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        // 初始化语言列表
        languageNames = new ArrayList<>();
        languageCodes = new ArrayList<>();

        languageNames.add("简体中文");
        languageCodes.add("zh");

        languageNames.add("English");
        languageCodes.add("en");

        languageNames.add("繁體中文");
        languageCodes.add("zh-rTW");

        // 创建适配器（单选列表，标记当前语言）
        languageAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice, languageNames);
        languageListView.setAdapter(languageAdapter);

        // 预选当前语言
        String current = LanguageManager.getLanguage(this);
        int checked = languageCodes.indexOf(current);
        if (checked >= 0) {
            languageListView.setItemChecked(checked, true);
        }

        // 设置点击事件
        languageListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String languageCode = languageCodes.get(position);
                if (languageCode.equals(LanguageManager.getLanguage(LanguageActivity.this))) {
                    return; // 选择未变化，不重复触发
                }
                // setApplicationLocales 生效后由 AppCompat/系统自动重建 Activity，无需手动重启
                LanguageManager.setLanguage(LanguageActivity.this, languageCode);
                finish();
            }
        });
    }
}

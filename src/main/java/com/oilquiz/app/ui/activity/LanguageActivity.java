package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import androidx.appcompat.app.AppCompatActivity;

import com.oilquiz.app.MainActivity;
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

        // 初始化语言列表
        languageNames = new ArrayList<>();
        languageCodes = new ArrayList<>();

        languageNames.add("简体中文");
        languageCodes.add("zh");

        languageNames.add("English");
        languageCodes.add("en");

        languageNames.add("繁體中文");
        languageCodes.add("zh-rTW");

        // 创建适配器
        languageAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, languageNames);
        languageListView.setAdapter(languageAdapter);

        // 设置点击事件
        languageListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String languageCode = languageCodes.get(position);
                LanguageManager.setLanguage(LanguageActivity.this, languageCode);

                // 重启应用以应用新语言
                Intent intent = new Intent(LanguageActivity.this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                finish();
            }
        });
    }
}

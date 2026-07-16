package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.ui.base.BaseActivity;
import androidx.cardview.widget.CardView;

import com.oilquiz.app.R;
import com.oilquiz.app.ui.export.TemplateSelectionActivity;

public class ImportGuideActivity extends BaseActivity {

    private static final int REQUEST_CODE_IMPORT = 1001;
    private static final int REQUEST_CODE_TEMPLATE = 1002;

    private CardView cardDirectImport;
    private CardView cardTemplateImport;
    private CardView cardHistory;
    private MaterialButton btnDirectImport;
    private MaterialButton btnTemplateImport;
    private MaterialButton btnViewHistory;
    private LinearLayout layoutSupportedFormats;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_import_guide;
    }

    @Override
    protected void initView() {
        // 设置Toolbar
        setupToolbar("题目导入");

        cardDirectImport = findViewById(R.id.cardDirectImport);
        cardTemplateImport = findViewById(R.id.cardTemplateImport);
        cardHistory = findViewById(R.id.cardHistory);
        btnDirectImport = findViewById(R.id.btnDirectImport);
        btnTemplateImport = findViewById(R.id.btnTemplateImport);
        btnViewHistory = findViewById(R.id.btnViewHistory);
        layoutSupportedFormats = findViewById(R.id.layoutSupportedFormats);

        // 设置支持格式说明
        setupSupportedFormats();
    }

    @Override
    protected void initData() {
        // 无需初始化数据
    }

    @Override
    protected void initListener() {
        // 直接导入
        btnDirectImport.setOnClickListener(v -> startDirectImport());
        cardDirectImport.setOnClickListener(v -> startDirectImport());

        // 使用模板导入
        btnTemplateImport.setOnClickListener(v -> startTemplateImport());
        cardTemplateImport.setOnClickListener(v -> startTemplateImport());

        // 查看历史
        btnViewHistory.setOnClickListener(v -> viewImportHistory());
        cardHistory.setOnClickListener(v -> viewImportHistory());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void setupSupportedFormats() {
        String[] formats = {"Excel (.xls, .xlsx)", "CSV (.csv)", "JSON (.json)"};
        int[] icons = {R.drawable.ic_excel, R.drawable.ic_csv, R.drawable.ic_json};

        for (int i = 0; i < formats.length; i++) {
            View formatView = getLayoutInflater().inflate(R.layout.item_format_badge, layoutSupportedFormats, false);
            TextView tvFormat = formatView.findViewById(R.id.tvFormat);
            tvFormat.setText(formats[i]);
            layoutSupportedFormats.addView(formatView);
        }
    }

    private void startDirectImport() {
        Intent intent = new Intent(this, ImportActivity.class);
        startActivityForResult(intent, REQUEST_CODE_IMPORT);
    }

    private void startTemplateImport() {
        Intent intent = new Intent(this, TemplateSelectionActivity.class);
        startActivityForResult(intent, REQUEST_CODE_TEMPLATE);
    }

    private void viewImportHistory() {
        Intent intent = new Intent(this, HistoryActivity.class);
        startActivity(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == RESULT_OK) {
            if (requestCode == REQUEST_CODE_IMPORT || requestCode == REQUEST_CODE_TEMPLATE) {
                // 导入成功，可以显示提示或刷新界面
                Toast.makeText(this, "导入完成", Toast.LENGTH_SHORT).show();
            }
        }
    }
}

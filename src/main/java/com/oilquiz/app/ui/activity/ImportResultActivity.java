package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import com.google.android.material.button.MaterialButton;
import android.widget.TextView;
import android.widget.Toast;

import com.oilquiz.app.ui.base.BaseActivity;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.oilquiz.app.R;
import com.oilquiz.app.ui.adapter.ImportErrorAdapter;

import java.util.ArrayList;
import java.util.List;

public class ImportResultActivity extends BaseActivity {

    public static final String EXTRA_TOTAL_QUESTIONS = "total_questions";
    public static final String EXTRA_SUCCESS_COUNT = "success_count";
    public static final String EXTRA_FAILED_COUNT = "failed_count";
    public static final String EXTRA_SKIPPED_COUNT = "skipped_count";
    public static final String EXTRA_IMPORT_TIME = "import_time";
    public static final String EXTRA_ERROR_MESSAGES = "error_messages";
    public static final String EXTRA_FILE_NAME = "file_name";

    private TextView tvTotalCount;
    private TextView tvSuccessCount;
    private TextView tvFailedCount;
    private TextView tvSkippedCount;
    private TextView tvImportTime;
    private TextView tvFileName;
    private TextView tvSuccessRate;
    private CardView cardErrors;
    private RecyclerView rvErrors;
    private MaterialButton btnDone;
    private MaterialButton btnImportAgain;
    private MaterialButton btnViewQuestions;

    private ImportErrorAdapter errorAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_import_result;
    }

    @Override
    protected void initView() {
        // 设置Toolbar
        setupToolbar("导入结果");

        tvTotalCount = findViewById(R.id.tvTotalCount);
        tvSuccessCount = findViewById(R.id.tvSuccessCount);
        tvFailedCount = findViewById(R.id.tvFailedCount);
        tvSkippedCount = findViewById(R.id.tvSkippedCount);
        tvImportTime = findViewById(R.id.tvImportTime);
        tvFileName = findViewById(R.id.tvFileName);
        tvSuccessRate = findViewById(R.id.tvSuccessRate);
        cardErrors = findViewById(R.id.cardErrors);
        rvErrors = findViewById(R.id.rvErrors);
        btnDone = findViewById(R.id.btnDone);
        btnImportAgain = findViewById(R.id.btnImportAgain);
        btnViewQuestions = findViewById(R.id.btnViewQuestions);

        // 设置错误列表
        rvErrors.setLayoutManager(new LinearLayoutManager(this));
        errorAdapter = new ImportErrorAdapter();
        rvErrors.setAdapter(errorAdapter);
    }

    @Override
    protected void initData() {
        Intent intent = getIntent();
        int totalQuestions = intent.getIntExtra(EXTRA_TOTAL_QUESTIONS, 0);
        int successCount = intent.getIntExtra(EXTRA_SUCCESS_COUNT, 0);
        int failedCount = intent.getIntExtra(EXTRA_FAILED_COUNT, 0);
        int skippedCount = intent.getIntExtra(EXTRA_SKIPPED_COUNT, 0);
        long importTime = intent.getLongExtra(EXTRA_IMPORT_TIME, 0);
        String fileName = intent.getStringExtra(EXTRA_FILE_NAME);
        List<String> errorMessages = intent.getStringArrayListExtra(EXTRA_ERROR_MESSAGES);

        // 显示数据
        tvTotalCount.setText(String.valueOf(totalQuestions));
        tvSuccessCount.setText(String.valueOf(successCount));
        tvFailedCount.setText(String.valueOf(failedCount));
        tvSkippedCount.setText(String.valueOf(skippedCount));
        tvImportTime.setText(String.format("%.2f秒", importTime / 1000.0));
        tvFileName.setText(fileName != null ? fileName : "未知文件");

        // 计算成功率
        double successRate = totalQuestions > 0 ? (successCount * 100.0 / totalQuestions) : 0;
        tvSuccessRate.setText(String.format("%.1f%%", successRate));

        // 显示错误信息
        if (errorMessages != null && !errorMessages.isEmpty()) {
            cardErrors.setVisibility(View.VISIBLE);
            errorAdapter.setErrors(errorMessages);
        } else {
            cardErrors.setVisibility(View.GONE);
        }
    }

    @Override
    protected void initListener() {
        btnDone.setOnClickListener(v -> {
            setResult(RESULT_OK);
            finish();
        });

        btnImportAgain.setOnClickListener(v -> {
            // 返回导入引导页
            Intent intent = new Intent(this, ImportGuideActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
        });

        btnViewQuestions.setOnClickListener(v -> {
            // 打开题库管理查看导入的题目
            Intent intent = new Intent(this, QuestionBankActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            setResult(RESULT_OK);
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        setResult(RESULT_OK);
        super.onBackPressed();
    }
}

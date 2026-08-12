package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import com.oilquiz.app.ui.base.BaseActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputLayout;
import com.oilquiz.app.R;
import com.oilquiz.app.resource.SystemUIResourceAdapter;
import com.oilquiz.app.util.render.ExcelUtil;

import java.util.ArrayList;
import java.util.List;

public class DataIssueFixActivity extends BaseActivity {

    public static final String EXTRA_DATA_ISSUE_REPORT = "data_issue_report";
    public static final String EXTRA_RESULT_DATA_ISSUE_REPORT = "result_data_issue_report";
    public static final String EXTRA_SKIP_AND_CONTINUE = "skip_and_continue";

    private ExcelUtil.DataIssueReport issueReport;

    private TextView totalIssuesCount;
    private TextView resolvedIssuesCount;
    private TextView unresolvedIssuesCount;
    private LinearLayout issuesContainer;
    private TextView tvNoIssues;
    private MaterialButton buttonBatchFix;
    private MaterialButton buttonSkipAndContinue;
    private MaterialButton buttonCompleteFix;
    private MaterialButton btnScanIssues;
    private MaterialButton btnFixAll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUIResourceAdapter uiAdapter = SystemUIResourceAdapter.getInstance(this);
        uiAdapter.applySystemTheme(this);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_data_issue_fix;
    }

    @Override
    protected void initView() {
        setupToolbar("数据问题修复");

        totalIssuesCount = findViewById(R.id.totalIssuesCount);
        resolvedIssuesCount = findViewById(R.id.resolvedIssuesCount);
        unresolvedIssuesCount = findViewById(R.id.unresolvedIssuesCount);
        issuesContainer = findViewById(R.id.issuesContainer);
        tvNoIssues = findViewById(R.id.tvNoIssues);
        buttonBatchFix = findViewById(R.id.buttonBatchFix);
        buttonSkipAndContinue = findViewById(R.id.buttonSkipAndContinue);
        buttonCompleteFix = findViewById(R.id.buttonCompleteFix);
        btnScanIssues = findViewById(R.id.btnScanIssues);
        btnFixAll = findViewById(R.id.btnFixAll);
    }

    @Override
    protected void initData() {
        issueReport = (ExcelUtil.DataIssueReport) getIntent()
                .getSerializableExtra(EXTRA_DATA_ISSUE_REPORT);

        if (issueReport == null) {
            Toast.makeText(this, "没有数据问题需要修复", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        updateStats();
        renderIssueList();
    }

    @Override
    protected void initListener() {
        btnScanIssues.setOnClickListener(v ->
                Toast.makeText(this, "请在导入流程中重新扫描", Toast.LENGTH_SHORT).show());

        btnFixAll.setOnClickListener(v -> {
            int fixed = 0;
            for (ExcelUtil.DataIssueItem issue : issueReport.issues) {
                if (!issue.isResolved) {
                    if (issue.issueType == ExcelUtil.DataIssueType.MISSING_QUESTION_TYPE) {
                        issue.userCorrectedValue = "单选题";
                    } else if (issue.suggestedValue != null && !issue.suggestedValue.isEmpty()) {
                        issue.userCorrectedValue = issue.suggestedValue;
                    } else {
                        continue;
                    }
                    issue.isResolved = true;
                    fixed++;
                }
            }
            updateStats();
            renderIssueList();
            Toast.makeText(this, "已自动修复 " + fixed + " 个问题", Toast.LENGTH_SHORT).show();
        });

        buttonBatchFix.setOnClickListener(v -> showBatchFixDialog());
        buttonSkipAndContinue.setOnClickListener(v -> skipAndContinue());
        buttonCompleteFix.setOnClickListener(v -> completeFixAndContinue());
    }

    private void updateStats() {
        issueReport.updateResolvedCount();
        totalIssuesCount.setText(String.valueOf(issueReport.totalIssues));
        resolvedIssuesCount.setText(String.valueOf(issueReport.resolvedIssues));
        unresolvedIssuesCount.setText(String.valueOf(issueReport.totalIssues - issueReport.resolvedIssues));
    }

    /**
     * 重新渲染整个问题列表（用 LinearLayout 替代 RecyclerView，避免嵌套滚动问题）
     */
    private void renderIssueList() {
        issuesContainer.removeAllViews();

        if (issueReport.issues == null || issueReport.issues.isEmpty()) {
            tvNoIssues.setVisibility(View.VISIBLE);
            return;
        }
        tvNoIssues.setVisibility(View.GONE);

        for (int i = 0; i < issueReport.issues.size(); i++) {
            ExcelUtil.DataIssueItem issue = issueReport.issues.get(i);
            View itemView = createIssueItemView(issue, i);
            issuesContainer.addView(itemView);
        }
    }

    /**
     * 创建单个问题的 item 视图
     */
    private View createIssueItemView(ExcelUtil.DataIssueItem issue, int index) {
        View view = LayoutInflater.from(this).inflate(R.layout.item_data_issue, issuesContainer, false);

        TextView issueRowNumber = view.findViewById(R.id.issueRowNumber);
        TextView issueDescription = view.findViewById(R.id.issueDescription);
        ImageView issueStatusIcon = view.findViewById(R.id.issueStatusIcon);
        View inputArea = view.findViewById(R.id.inputArea);
        TextView inputLabel = view.findViewById(R.id.inputLabel);
        TextInputLayout spinnerInputLayout = view.findViewById(R.id.spinnerInputLayout);
        AutoCompleteTextView spinnerAutoComplete = view.findViewById(R.id.spinnerAutoComplete);
        MaterialButton buttonSkip = view.findViewById(R.id.buttonSkip);
        MaterialButton buttonApply = view.findViewById(R.id.buttonApply);

        issueRowNumber.setText("第" + issue.rowNumber + "行");
        issueDescription.setText(issue.getIssueDescription());
        inputLabel.setText(issue.fieldName);

        if (issue.isResolved) {
            // 已解决：显示解决状态和修正值
            issueStatusIcon.setVisibility(View.VISIBLE);
            inputArea.setVisibility(View.VISIBLE);
            spinnerInputLayout.setVisibility(View.VISIBLE);
            spinnerAutoComplete.setText(issue.userCorrectedValue != null ? issue.userCorrectedValue : "");
            spinnerAutoComplete.setEnabled(false);
            buttonSkip.setVisibility(View.GONE);
            buttonApply.setText("撤销");
            buttonApply.setOnClickListener(v -> {
                issue.isResolved = false;
                issue.userCorrectedValue = null;
                updateStats();
                renderIssueList();
            });
        } else {
            // 未解决：显示输入区域
            issueStatusIcon.setVisibility(View.GONE);
            inputArea.setVisibility(View.VISIBLE);
            spinnerAutoComplete.setEnabled(true);
            buttonSkip.setVisibility(View.VISIBLE);
            buttonApply.setText("应用");

            // 构建建议值列表
            List<String> suggestions = new ArrayList<>();
            if (issue.suggestedValuesList != null) {
                for (String s : issue.suggestedValuesList) {
                    if (!suggestions.contains(s)) suggestions.add(s);
                }
            }
            if (issue.issueType == ExcelUtil.DataIssueType.MISSING_QUESTION_TYPE) {
                String[] defaults = {"单选题", "多选题", "判断题", "填空题", "简答题"};
                for (String d : defaults) {
                    if (!suggestions.contains(d)) suggestions.add(d);
                }
            }
            if (issue.suggestedValue != null && !issue.suggestedValue.isEmpty()
                    && !suggestions.contains(issue.suggestedValue)) {
                suggestions.add(issue.suggestedValue);
            }

            if (!suggestions.isEmpty()) {
                ArrayAdapter<String> adapter = new ArrayAdapter<>(
                        this, android.R.layout.simple_dropdown_item_1line, suggestions);
                spinnerAutoComplete.setAdapter(adapter);
                spinnerAutoComplete.setOnItemClickListener((parent, v, position, id) -> {
                    spinnerAutoComplete.setText(suggestions.get(position));
                    spinnerAutoComplete.dismissDropDown();
                });
                spinnerAutoComplete.setOnClickListener(v -> {
                    spinnerAutoComplete.showDropDown();
                });
                spinnerAutoComplete.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus) spinnerAutoComplete.showDropDown();
                });
                spinnerInputLayout.setVisibility(View.VISIBLE);
            } else {
                spinnerInputLayout.setVisibility(View.VISIBLE);
            }

            buttonSkip.setOnClickListener(v -> {
                issue.isResolved = false;
                issue.userCorrectedValue = null;
                updateStats();
                renderIssueList();
            });

            buttonApply.setOnClickListener(v -> {
                String value = spinnerAutoComplete.getText().toString().trim();
                if (value.isEmpty()) {
                    Toast.makeText(this, "请输入或从下拉选择值", Toast.LENGTH_SHORT).show();
                    return;
                }
                issue.userCorrectedValue = value;
                issue.isResolved = true;
                updateStats();
                renderIssueList();
            });
        }

        return view;
    }

    private void showBatchFixDialog() {
        String[] options = {"统一设置题型为'单选题'",
                "统一设置题型为'多选题'",
                "统一设置题型为'判断题'"};

        new AlertDialog.Builder(this)
                .setTitle("批量修复")
                .setItems(options, (dialog, which) -> {
                    String value = "";
                    switch (which) {
                        case 0: value = "单选题"; break;
                        case 1: value = "多选题"; break;
                        case 2: value = "判断题"; break;
                    }
                    applyBatchFix(ExcelUtil.DataIssueType.MISSING_QUESTION_TYPE, value);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void applyBatchFix(ExcelUtil.DataIssueType issueType, String value) {
        for (ExcelUtil.DataIssueItem issue : issueReport.issues) {
            if (issue.issueType == issueType && !issue.isResolved) {
                issue.userCorrectedValue = value;
                issue.isResolved = true;
            }
        }
        updateStats();
        renderIssueList();
        Toast.makeText(this, "批量修复完成", Toast.LENGTH_SHORT).show();
    }

    private void skipAndContinue() {
        Intent resultIntent = new Intent();
        resultIntent.putExtra(EXTRA_RESULT_DATA_ISSUE_REPORT, issueReport);
        resultIntent.putExtra(EXTRA_SKIP_AND_CONTINUE, true);
        setResult(RESULT_OK, resultIntent);
        finish();
    }

    private void completeFixAndContinue() {
        Intent resultIntent = new Intent();
        resultIntent.putExtra(EXTRA_RESULT_DATA_ISSUE_REPORT, issueReport);
        resultIntent.putExtra(EXTRA_SKIP_AND_CONTINUE, false);
        setResult(RESULT_OK, resultIntent);
        finish();
    }
}

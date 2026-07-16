package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import com.oilquiz.app.ui.base.BaseActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputLayout;
import android.widget.EditText;
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
    private IssuesAdapter issuesAdapter;

    private TextView totalIssuesCount;
    private TextView resolvedIssuesCount;
    private TextView unresolvedIssuesCount;
    private RecyclerView issuesRecyclerView;
    private MaterialButton buttonBatchFix;
    private MaterialButton buttonSkipAndContinue;
    private MaterialButton buttonCompleteFix;

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
        // 设置Toolbar
        setupToolbar("数据问题修复");

        totalIssuesCount = findViewById(R.id.totalIssuesCount);
        resolvedIssuesCount = findViewById(R.id.resolvedIssuesCount);
        unresolvedIssuesCount = findViewById(R.id.unresolvedIssuesCount);
        issuesRecyclerView = findViewById(R.id.rvIssuesList);
        buttonBatchFix = findViewById(R.id.buttonBatchFix);
        buttonSkipAndContinue = findViewById(R.id.buttonSkipAndContinue);
        buttonCompleteFix = findViewById(R.id.buttonCompleteFix);

        issuesRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        issuesAdapter = new IssuesAdapter();
        issuesRecyclerView.setAdapter(issuesAdapter);
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
        issuesAdapter.setIssues(issueReport.issues);
    }

    @Override
    protected void initListener() {
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
        issuesAdapter.notifyDataSetChanged();
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

    private class IssuesAdapter extends RecyclerView.Adapter<IssueViewHolder> {
        private List<ExcelUtil.DataIssueItem> issues = new ArrayList<>();

        public void setIssues(List<ExcelUtil.DataIssueItem> issues) {
            this.issues = issues;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public IssueViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_data_issue, parent, false);
            return new IssueViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull IssueViewHolder holder, int position) {
            holder.bind(issues.get(position));
        }

        @Override
        public int getItemCount() {
            return issues.size();
        }
    }

    private class IssueViewHolder extends RecyclerView.ViewHolder {
        private ImageView issueTypeIcon;
        private TextView issueRowNumber;
        private TextView issueDescription;
        private ImageView issueStatusIcon;
        private View inputArea;
        private TextView inputLabel;
        private TextInputLayout spinnerInputLayout;
        private AutoCompleteTextView spinnerAutoComplete;
        private MaterialButton buttonSkip;
        private MaterialButton buttonApply;

        public IssueViewHolder(@NonNull View itemView) {
            super(itemView);
            issueTypeIcon = itemView.findViewById(R.id.issueTypeIcon);
            issueRowNumber = itemView.findViewById(R.id.issueRowNumber);
            issueDescription = itemView.findViewById(R.id.issueDescription);
            issueStatusIcon = itemView.findViewById(R.id.issueStatusIcon);
            inputArea = itemView.findViewById(R.id.inputArea);
            inputLabel = itemView.findViewById(R.id.inputLabel);
            spinnerInputLayout = itemView.findViewById(R.id.spinnerInputLayout);
            spinnerAutoComplete = itemView.findViewById(R.id.spinnerAutoComplete);
            buttonSkip = itemView.findViewById(R.id.buttonSkip);
            buttonApply = itemView.findViewById(R.id.buttonApply);
        }

        public void bind(ExcelUtil.DataIssueItem issue) {
            issueRowNumber.setText("第" + issue.rowNumber + "行");
            issueDescription.setText(issue.getIssueDescription());
            inputLabel.setText(issue.fieldName);

            if (issue.isResolved) {
                issueStatusIcon.setVisibility(View.VISIBLE);
                inputArea.setVisibility(View.GONE);
                spinnerAutoComplete.setText(issue.userCorrectedValue);
            } else {
                issueStatusIcon.setVisibility(View.GONE);
                inputArea.setVisibility(View.VISIBLE);
                spinnerAutoComplete.setText("");

                // 设置建议值下拉选择框
                if (issue.suggestedValuesList != null && !issue.suggestedValuesList.isEmpty()) {
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(
                            itemView.getContext(),
                            android.R.layout.simple_dropdown_item_1line,
                            issue.suggestedValuesList
                    );
                    spinnerAutoComplete.setAdapter(adapter);
                    spinnerAutoComplete.setOnItemClickListener((parent, view, position, id) -> {
                        String selectedValue = issue.suggestedValuesList.get(position);
                        spinnerAutoComplete.setText(selectedValue, false);
                    });
                    spinnerInputLayout.setVisibility(View.VISIBLE);
                } else {
                    spinnerInputLayout.setVisibility(View.GONE);
                }
            }

            buttonSkip.setOnClickListener(v -> {
                issue.isResolved = false;
                updateStats();
                issuesAdapter.notifyItemChanged(getAdapterPosition());
            });

            buttonApply.setOnClickListener(v -> {
                String value = spinnerAutoComplete.getText().toString();
                if (value.trim().isEmpty()) {
                    Toast.makeText(itemView.getContext(), "请从下拉选项中选择值", Toast.LENGTH_SHORT).show();
                    return;
                }
                issue.userCorrectedValue = value;
                issue.isResolved = true;
                updateStats();
                issuesAdapter.notifyItemChanged(getAdapterPosition());
            });
        }
    }
}

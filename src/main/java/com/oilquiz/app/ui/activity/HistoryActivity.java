package com.oilquiz.app.ui.activity;

import androidx.appcompat.app.AlertDialog;
import com.oilquiz.app.ui.base.BaseActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.oilquiz.app.R;
import com.oilquiz.app.model.ImportHistory;
import com.oilquiz.app.repository.ImportHistoryRepository;
import com.oilquiz.app.ui.adapter.ImportHistoryAdapter;

import java.util.List;

public class HistoryActivity extends BaseActivity implements ImportHistoryAdapter.OnHistoryItemClickListener, ImportHistoryAdapter.OnHistoryItemDeleteListener {

    private TextView textViewTotalImports;
    private TextView textViewTotalQuestions;
    private RecyclerView recyclerViewHistory;
    private LinearLayout layoutEmptyState;
    private ImportHistoryAdapter historyAdapter;

    private ImportHistoryRepository importHistoryRepository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_history;
    }

    @Override
    protected void initView() {
        // 设置Toolbar
        setupToolbar("导入历史");

        textViewTotalImports = findViewById(R.id.textViewTotalImports);
        textViewTotalQuestions = findViewById(R.id.textViewTotalQuestions);
        recyclerViewHistory = findViewById(R.id.recyclerViewHistory);
        layoutEmptyState = findViewById(R.id.layoutEmptyState);

        // 设置RecyclerView
        recyclerViewHistory.setLayoutManager(new LinearLayoutManager(this));
        historyAdapter = new ImportHistoryAdapter(null, this);
        historyAdapter.setOnHistoryItemDeleteListener(this);
        recyclerViewHistory.setAdapter(historyAdapter);
    }

    @Override
    protected void initData() {
        // 初始化Repository
        importHistoryRepository = new ImportHistoryRepository(getApplication());

        // 加载历史数据
        loadHistoryData();
    }

    @Override
    protected void initListener() {
        // 无需额外监听器，因为点击事件已在Adapter中处理
    }

    private void loadHistoryData() {
        List<ImportHistory> importHistoryList = importHistoryRepository.getImportHistory();
        
        if (importHistoryList != null && !importHistoryList.isEmpty()) {
            // 显示列表，隐藏空状态
            recyclerViewHistory.setVisibility(View.VISIBLE);
            layoutEmptyState.setVisibility(View.GONE);
            
            // 更新统计信息
            updateStatistics(importHistoryList);
            historyAdapter.updateData(importHistoryList);
        } else {
            // 显示空状态，隐藏列表
            recyclerViewHistory.setVisibility(View.GONE);
            layoutEmptyState.setVisibility(View.VISIBLE);
            updateEmptyState();
        }
    }

    private void updateStatistics(List<ImportHistory> importHistoryList) {
        if (importHistoryList == null || importHistoryList.isEmpty()) {
            updateEmptyState();
            return;
        }

        // 计算总导入次数
        int totalImports = importHistoryList.size();
        textViewTotalImports.setText(String.valueOf(totalImports));

        // 计算总导入题目数
        int totalQuestions = 0;
        for (ImportHistory history : importHistoryList) {
            totalQuestions += history.getImportedCount();
        }
        textViewTotalQuestions.setText(String.valueOf(totalQuestions));
    }

    private void updateEmptyState() {
        textViewTotalImports.setText("0");
        textViewTotalQuestions.setText("0");
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.history_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_clear_history) {
            clearHistory();
            return true;
        } else if (id == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void clearHistory() {
        new AlertDialog.Builder(this)
                .setTitle("清空历史记录")
                .setMessage("确定要清空所有导入历史记录吗？此操作不可恢复。")
                .setPositiveButton("确定", (dialog, which) -> {
                    importHistoryRepository.clearImportHistory();
                    loadHistoryData();
                    Toast.makeText(this, "历史记录已清空", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void onHistoryItemClick(ImportHistory importHistory) {
        // 查看导入详情
        Intent intent = new Intent(this, ImportResultActivity.class);
        intent.putExtra(ImportResultActivity.EXTRA_TOTAL_QUESTIONS, importHistory.getImportedCount() + importHistory.getFailedCount());
        intent.putExtra(ImportResultActivity.EXTRA_SUCCESS_COUNT, importHistory.getImportedCount());
        intent.putExtra(ImportResultActivity.EXTRA_FAILED_COUNT, importHistory.getFailedCount());
        intent.putExtra(ImportResultActivity.EXTRA_SKIPPED_COUNT, 0);
        intent.putExtra(ImportResultActivity.EXTRA_IMPORT_TIME, 0);
        intent.putExtra(ImportResultActivity.EXTRA_FILE_NAME, importHistory.getFileName());
        startActivity(intent);
    }

    @Override
    public void onHistoryItemDelete(ImportHistory importHistory, int position) {
        new AlertDialog.Builder(this)
                .setTitle("删除记录")
                .setMessage("确定要删除这条导入记录吗？")
                .setPositiveButton("删除", (dialog, which) -> {
                    importHistoryRepository.deleteImportHistory(importHistory.getId());
                    historyAdapter.removeItem(position);
                    loadHistoryData(); // 重新加载以更新统计
                    Toast.makeText(this, "记录已删除", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadHistoryData();
    }
}

package com.oilquiz.app.ui.activity;

import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.repair.QuestionRepairEngine;
import com.oilquiz.app.database.DatabaseManager;
import com.oilquiz.app.model.Question;
import com.oilquiz.app.ui.base.BaseActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 题库智能修复界面。
 * <p>
 * 扫描已入库题目的数据问题（乱码/题干为空/答案缺失/答案与选项不匹配/题型缺失/重复），
 * 支持一键规则修复与 AI 逐题修复。
 */
public class QuestionRepairActivity extends BaseActivity {

    private TextView tvStats;
    private TextView tvStatus;
    private ProgressBar pbRepair;
    private MaterialButton btnScan;
    private MaterialButton btnRuleFix;
    private MaterialButton btnAiFix;
    private ListView lvIssues;

    private final QuestionRepairEngine engine = new QuestionRepairEngine();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<QuestionRepairEngine.RepairItem> items = new ArrayList<>();
    private volatile boolean busy = false;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_question_repair;
    }

    @Override
    protected void initView() {
        setupToolbar("题库智能修复");
        tvStats = findViewById(R.id.tvRepairStats);
        tvStatus = findViewById(R.id.tvRepairStatus);
        pbRepair = findViewById(R.id.pbRepair);
        btnScan = findViewById(R.id.btnScanRepair);
        btnRuleFix = findViewById(R.id.btnRuleFix);
        btnAiFix = findViewById(R.id.btnAiFix);
        lvIssues = findViewById(R.id.lvRepairIssues);
    }

    @Override
    protected void initData() {
        // 无可用模型（本地+在线都没有）时提前提示
        if (!engine.isAiRepairAvailable(this)) {
            btnAiFix.setEnabled(false);
            btnAiFix.setText("AI修复(无模型)");
        }
    }

    @Override
    protected void initListener() {
        btnScan.setOnClickListener(v -> startScan());
        btnRuleFix.setOnClickListener(v -> startRuleFix());
        btnAiFix.setOnClickListener(v -> startAiFix());
    }

    // ==================== 扫描 ====================

    private void startScan() {
        if (busy) return;
        busy = true;
        setButtonsEnabled(false);
        showStatus("正在扫描题库...");
        executor.execute(() -> {
            try {
                List<Question> all = DatabaseManager.getInstance(this).getAllQuestions().get();
                List<QuestionRepairEngine.RepairItem> result =
                        engine.scan(all != null ? all : new ArrayList<>());
                runOnUiThread(() -> {
                    items.clear();
                    items.addAll(result);
                    renderItems(all != null ? all.size() : 0);
                    busy = false;
                    hideStatus();
                    setButtonsEnabled(true);
                    btnRuleFix.setEnabled(countAutoFixable() > 0);
                    boolean aiOk = engine.isAiRepairAvailable(this);
                    btnAiFix.setEnabled(aiOk && countAiCandidates() > 0);
                    if (!aiOk && countAiCandidates() > 0) {
                        Toast.makeText(this, "存在需AI修复的题目，但本地与在线模型均不可用", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    busy = false;
                    hideStatus();
                    setButtonsEnabled(true);
                    Toast.makeText(this, "扫描失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    // ==================== 规则修复 ====================

    private void startRuleFix() {
        if (busy || countAutoFixable() == 0) return;
        busy = true;
        setButtonsEnabled(false);
        showStatus("正在应用规则修复...");
        executor.execute(() -> {
            final int applied = engine.applyAutoFixes(this, items);
            runOnUiThread(() -> {
                busy = false;
                hideStatus();
                Toast.makeText(this, "规则修复完成，已写库 " + applied + " 题", Toast.LENGTH_SHORT).show();
                startScan(); // 重新扫描刷新
            });
        });
    }

    // ==================== AI 修复 ====================

    private void startAiFix() {
        List<QuestionRepairEngine.RepairItem> candidates = new ArrayList<>();
        for (QuestionRepairEngine.RepairItem item : items) {
            if (item.aiCandidate) candidates.add(item);
        }
        if (busy || candidates.isEmpty()) return;
        busy = true;
        setButtonsEnabled(false);
        pbRepair.setVisibility(android.view.View.VISIBLE);
        pbRepair.setMax(candidates.size());
        pbRepair.setProgress(0);

        executor.execute(() -> {
            // 引擎内部：在线模型批量（每批5题一次推理）/ 本地模型逐题，修复并写库
            int[] counts = engine.aiRepairAll(this, candidates, (done, total) ->
                    runOnUiThread(() -> {
                        pbRepair.setProgress(done);
                        showStatus("AI修复中 " + done + "/" + total);
                    }));
            final int fixedFinal = counts[0];
            final int failedFinal = counts[1];
            runOnUiThread(() -> {
                busy = false;
                hideStatus();
                pbRepair.setVisibility(android.view.View.GONE);
                Toast.makeText(this, "AI修复完成：成功 " + fixedFinal + " 题，未能修复 "
                        + failedFinal + " 题", Toast.LENGTH_LONG).show();
                startScan(); // 重新扫描刷新
            });
        });
    }

    // ==================== 渲染 ====================

    private void renderItems(int totalScanned) {
        int autoCnt = countAutoFixable();
        int aiCnt = countAiCandidates();
        tvStats.setText("共扫描 " + totalScanned + " 题，发现问题 " + items.size() + " 题"
                + "\n可规则自动修复: " + autoCnt + " 题 | 需AI修复: " + aiCnt + " 题");

        List<String> lines = new ArrayList<>();
        for (QuestionRepairEngine.RepairItem item : items) {
            String qt = item.question.getQuestionText();
            String preview = (qt == null || qt.trim().isEmpty()) ? "(题干为空)"
                    : qt.trim().substring(0, Math.min(30, qt.trim().length()));
            String tag = item.autoFixable ? "[可自动修复] "
                    : (item.aiCandidate ? "[需AI修复] " : "[需人工确认] ");
            lines.add("#" + item.question.getId() + " " + preview
                    + "\n" + tag + item.issueSummary());
        }
        if (lines.isEmpty()) {
            lines.add("✅ 未发现问题题目");
        }
        lvIssues.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, lines));
    }

    private int countAutoFixable() {
        int n = 0;
        for (QuestionRepairEngine.RepairItem item : items) {
            if (item.autoFixable) n++;
        }
        return n;
    }

    private int countAiCandidates() {
        int n = 0;
        for (QuestionRepairEngine.RepairItem item : items) {
            if (item.aiCandidate) n++;
        }
        return n;
    }

    private void setButtonsEnabled(boolean enabled) {
        btnScan.setEnabled(enabled);
        if (!enabled) {
            btnRuleFix.setEnabled(false);
            btnAiFix.setEnabled(false);
        }
    }

    private void showStatus(String text) {
        tvStatus.setVisibility(android.view.View.VISIBLE);
        tvStatus.setText(text);
    }

    private void hideStatus() {
        tvStatus.setVisibility(android.view.View.GONE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}

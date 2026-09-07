package com.oilquiz.app.ui.activity;

import com.oilquiz.app.SmartQuizApplication;
import com.oilquiz.app.theme.ThemeColors;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.importing.v2.ImportMain;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 导入数据预览页：展示解析结果（全部行分页）、错误/缺失标注，
 * 并让用户选择"全部导入 / 仅导入完整题目 / 取消导入"。
 *
 * 启动参数：
 * - EXTRA_CHUNK_FILES: 解析分片文件路径数组
 * - EXTRA_PREVIEW: 质量预览统计
 * 返回：
 * - RESULT_OK + EXTRA_DECISION_SKIP_INCOMPLETE(boolean)：是否仅导入完整题目
 * - RESULT_CANCELED：用户取消导入
 */
public class ImportPreviewActivity extends AppCompatActivity {

    public static final String EXTRA_CHUNK_FILES = "chunk_files";
    public static final String EXTRA_PREVIEW = "preview";
    public static final String EXTRA_MAPPED_FIELDS = "mapped_fields";
    public static final String EXTRA_DECISION_SKIP_INCOMPLETE = "decision_skip_incomplete";

    private RecyclerView recyclerView;
    private PreviewAdapter adapter;
    private List<File> chunkFiles;
    private ImportMain.QualityPreview preview;
    private Chip chipAll;
    private Chip chipSkip;
    /** 已映射字段集合（标准字段名）：缺失判断只针对这些字段 */
    private java.util.Set<String> mappedFields = new java.util.HashSet<>();
    /** 被去重的重复行（数据题序号，1-based）：行内标注"重复" */
    private java.util.Set<Integer> duplicateRows = new java.util.HashSet<>();

    // 全部数据行缓存（懒加载，最多缓存全部行——预览需完整翻页）
    private List<String[]> allRows = new ArrayList<>();
    private String[] headers;
    private boolean loaded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_import_preview);

        // 接收参数
        String[] paths = getIntent().getStringArrayExtra(EXTRA_CHUNK_FILES);
        chunkFiles = new ArrayList<>();
        if (paths != null) {
            for (String p : paths) {
                File f = new File(p);
                if (f.exists()) chunkFiles.add(f);
            }
        }
        preview = (ImportMain.QualityPreview) getIntent().getSerializableExtra(EXTRA_PREVIEW);
        java.util.ArrayList<String> mappedList =
                getIntent().getStringArrayListExtra(EXTRA_MAPPED_FIELDS);
        if (mappedList != null) {
            mappedFields.addAll(mappedList);
        } else if (preview != null && preview.mappedFields != null) {
            mappedFields.addAll(preview.mappedFields);
        }

        TextView tvTotal = findViewById(R.id.tv_total);
        TextView tvComplete = findViewById(R.id.tv_complete);
        TextView tvIncomplete = findViewById(R.id.tv_incomplete);
        TextView tvSkipped = findViewById(R.id.tv_skipped);
        TextView tvMissingDetail = findViewById(R.id.tv_missing_detail);
        TextView tvDupDetail = findViewById(R.id.tv_dup_detail);

        if (preview != null) {
            tvTotal.setText("共 " + preview.totalRows + " 行");
            tvComplete.setText(getString(R.string.h_aae41a4f) + preview.completeCount());
            tvIncomplete.setText(getString(R.string.h_5c91b7d9) + preview.incompleteCount);
            tvSkipped.setText(getString(R.string.h_a3e17914) + preview.getDuplicateCount());
            StringBuilder detail = new StringBuilder();
            for (Map.Entry<String, Long> e : preview.missingByField.entrySet()) {
                if (detail.length() > 0) detail.append("，");
                detail.append(fieldLabel(e.getKey())).append("缺 ").append(e.getValue());
            }
            if (preview.emptyQuestionCount > 0) {
                if (detail.length() > 0) detail.append("；");
                detail.append(getString(R.string.h_9fba2017)).append(preview.emptyQuestionCount).append(getString(R.string.h_2ce6e3c3));
            }
            long dup = preview.getDuplicateCount();
            if (dup > 0) {
                if (detail.length() > 0) detail.append("；");
                detail.append(getString(R.string.h_8dd291a0)).append(dup).append(" 行");
            }
            tvMissingDetail.setText(detail.length() > 0 ? getString(R.string.h_8c6f7416) + detail : getString(R.string.h_f066e272));

            // 重复/近似重复明细：说明"哪道题重复、为何重复"
            StringBuilder dupText = new StringBuilder();
            for (com.oilquiz.app.ai.importing.v2.ImportMain.QualityPreview.DuplicateDetail d
                    : preview.duplicateDetails) {
                if (dupText.length() > 0) dupText.append('\n');
                dupText.append(getString(R.string.h_d545c2a4)).append(joinRows(d.rows)).append(getString(R.string.h_5c6640af))
                        .append(truncate(d.question, 56)).append("」\n")
                        .append(getString(R.string.h_71279df9)).append(d.reason);
            }
            for (com.oilquiz.app.ai.importing.v2.ImportMain.QualityPreview.DuplicateDetail d
                    : preview.stemVariantDetails) {
                if (dupText.length() > 0) dupText.append('\n');
                dupText.append(getString(R.string.h_539d7ddc)).append(joinRows(d.rows)).append(getString(R.string.h_5c6640af))
                        .append(truncate(d.question, 56)).append("」\n")
                        .append(getString(R.string.h_71279df9)).append(d.reason);
            }
            if (dupText.length() > 0) {
                tvDupDetail.setText(dupText.toString());
                tvDupDetail.setVisibility(View.VISIBLE);
            }

            // 行内"重复"标注：重复组中除首现外的行（首现保留，其余被去重）
            for (com.oilquiz.app.ai.importing.v2.ImportMain.QualityPreview.DuplicateDetail d
                    : preview.duplicateDetails) {
                for (int i = 1; i < d.rows.size(); i++) {
                    duplicateRows.add(d.rows.get(i));
                }
            }
        }

        recyclerView = findViewById(R.id.preview_recycler);
        adapter = new PreviewAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
        // 数据加载放到首帧布局完成后执行（post）：getItemCount 会被 RecyclerView 在
        // 布局计算中调用，若在其中同步读文件并 notifyDataSetChanged 会抛
        // "Cannot call this method while RecyclerView is computing a layout" 崩溃。
        // 读文件在后台线程执行，避免大题库分片卡住主线程（UI 无响应）。
        recyclerView.post(() -> new Thread(this::ensureLoaded, "import-preview-load").start());

        chipAll = findViewById(R.id.chip_all);
        chipSkip = findViewById(R.id.chip_skip);
        chipAll.setOnClickListener(v -> selectMode(true));
        chipSkip.setOnClickListener(v -> selectMode(false));
        selectMode(true);

        MaterialButton btnCancel = findViewById(R.id.btn_cancel);
        MaterialButton btnContinue = findViewById(R.id.btn_continue);
        btnCancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        btnContinue.setOnClickListener(v -> {
            Intent result = new Intent();
            result.putExtra(EXTRA_DECISION_SKIP_INCOMPLETE, chipSkip.isChecked());
            setResult(RESULT_OK, result);
            finish();
        });

        findViewById(R.id.toolbar).setOnClickListener(v -> finish());
    }

    private void selectMode(boolean all) {
        chipAll.setChecked(all);
        chipSkip.setChecked(!all);
    }

    private static String fieldLabel(String field) {
        switch (field == null ? "" : field) {
            case "questionType": return "题型";
            case "difficulty": return "难度";
            case "category": return "分类";
            case "explanation": return "解析";
            case "correctAnswer": return "答案";
            case "answerText": return "答案";
            case "knowledgePoint": return "知识点";
            case "subCategory": return "子分类";
            case "tags": return "标签";
            case "hint": return SmartQuizApplication.getAppContext().getString(R.string.h_02d9819d);
            case "points": return "分值";
            case "timeLimit": return "时限";
            case "author": return "作者";
            case "comment": return "备注";
            case "source": return "来源";
            default: return field;
        }
    }

    /** 懒加载全部数据行（后台线程执行，完成后主线程刷新；之后翻页纯内存） */
    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        allRows.clear();
        for (File f : chunkFiles) {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String headerLine = r.readLine();
                if (headerLine != null && headers == null) {
                    headers = parseCsvLine(headerLine);
                }
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    allRows.add(parseCsvLine(line));
                }
            } catch (Exception e) {
                android.util.Log.w("ImportPreview", "读取分片失败 " + f.getName() + ": " + e.getMessage());
            }
        }
        // 回到主线程刷新：此刻不在 RecyclerView 布局/滚动回调中，notify 安全
        runOnUiThread(() -> adapter.notifyDataSetChanged());
    }

    /** 简易 CSV 行解析（与 ImportCsvIngestor 一致：支持引号包裹） */
    private static String[] parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    cells.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
        }
        cells.add(cur.toString());
        return cells.toArray(new String[0]);
    }

    /** 数据行列表适配器：显示 题干/选项/答案 摘要 + 缺失标注 */
    class PreviewAdapter extends RecyclerView.Adapter<PreviewAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(ImportPreviewActivity.this)
                    .inflate(R.layout.item_import_preview_row, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            String[] row = allRows.get(position);
            Map<String, String> kv = new HashMap<>();
            if (headers != null) {
                for (int i = 0; i < headers.length && i < row.length; i++) {
                    kv.put(headers[i], row[i]);
                }
            }

            String question = kv.getOrDefault("questionText", "");
            h.tvIndex.setText(String.valueOf(position + 1));
            h.tvQuestion.setText(question.isEmpty() ? getString(R.string.h_e1322390) : truncate(question, 60));

            // 动态缺失判断：遍历全部已映射字段（mappedFields），字段值为空即标注缺失。
            // 不硬编码字段名单——文件映射了哪些字段，哪些缺失就会显示。
            StringBuilder miss = new StringBuilder();
            StringBuilder answerPreview = new StringBuilder();
            for (String field : mappedFields) {
                if (field == null || field.isEmpty()) continue;
                // 排除虚拟字段（合并选项等，非 CSV 列）
                if ("optionsCombined".equals(field)) continue;
                String val = kv.getOrDefault(field, "");
                if (val == null || val.trim().isEmpty()) {
                    if (miss.length() > 0) miss.append("、");
                    miss.append(fieldLabel(field));
                }
                // 答案预览：题干/答案类字段显示到右侧
                if ("correctAnswer".equals(field) || "answerText".equals(field)) {
                    answerPreview.setLength(0);
                    answerPreview.append(getString(R.string.h_2bac46d1)).append(val.isEmpty() ? "空" : truncate(val, 30));
                }
            }
            h.tvAnswer.setText(answerPreview.length() > 0 ? answerPreview.toString()
                    : "答案: " + (kv.getOrDefault("correctAnswer", "").isEmpty() ? "空" : "…"));

            // 选项预览：可选项列拆分后的 optionA~L 拼成 "A. 对  B. 错"，
            // 让用户直观确认"可选项"内容已被拆分为选项
            StringBuilder opt = new StringBuilder();
            char[] letters = "ABCDEFGHIJKL".toCharArray();
            for (int li = 0; li < letters.length; li++) {
                String val = kv.getOrDefault("option" + letters[li], "");
                if (val == null || val.trim().isEmpty()) continue;
                if (opt.length() > 0) opt.append("　");
                opt.append(letters[li]).append(". ").append(truncate(val.trim(), 18));
            }
            if (opt.length() > 0) {
                h.tvOptions.setText(opt.toString());
                h.tvOptions.setVisibility(View.VISIBLE);
            } else {
                h.tvOptions.setVisibility(View.GONE);
            }

            if (miss.length() > 0) {
                h.tvMissing.setText(getString(R.string.h_95d20549) + miss.toString());
                h.tvMissing.setVisibility(View.VISIBLE);
                h.itemView.setBackgroundResource(R.color.error_container);
            } else if (duplicateRows.contains(position + 1)) {
                h.tvMissing.setText(getString(R.string.h_2492d1b6));
                h.tvMissing.setVisibility(View.VISIBLE);
                h.itemView.setBackgroundResource(R.color.warning_container);
            } else {
                h.tvMissing.setVisibility(View.GONE);
                h.itemView.setBackgroundColor(ThemeColors.get(h.itemView.getContext(), R.color.surface));
            }
        }

        @Override
        public int getItemCount() {
            // 注意：此方法会被 RecyclerView 在布局计算中调用，绝不能在其中加载数据或 notify
            return allRows.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvIndex, tvQuestion, tvOptions, tvAnswer, tvMissing;

            VH(@NonNull View itemView) {
                super(itemView);
                tvIndex = itemView.findViewById(R.id.tv_row_index);
                tvQuestion = itemView.findViewById(R.id.tv_row_question);
                tvOptions = itemView.findViewById(R.id.tv_row_options);
                tvAnswer = itemView.findViewById(R.id.tv_row_answer);
                tvMissing = itemView.findViewById(R.id.tv_row_missing);
            }
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /** 行号列表 → "112、130"（中文顿号分隔） */
    private static String joinRows(java.util.List<Integer> rows) {
        if (rows == null || rows.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sb.append('、');
            sb.append(rows.get(i));
        }
        return sb.toString();
    }
}

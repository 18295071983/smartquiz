package com.oilquiz.app.ui.activity;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.oilquiz.app.R;
import com.oilquiz.app.database.DatabaseFieldManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 字段管理 Activity
 * 提供数据库字段的查看和管理功能
 */
public class FieldManagementActivity extends AppCompatActivity {

    private Spinner spinnerTable;
    private RecyclerView rvFields;
    private TextView tvNoFields;
    private MaterialButton btnLoadFields;
    private MaterialButton btnAddField;

    private DatabaseFieldManager fieldManager;
    private List<String> tableList = new ArrayList<>();
    private String selectedTable = null;

    // 常用字段类型
    private static final String[] FIELD_TYPES = {
        "INTEGER", "TEXT", "REAL", "BLOB", "BOOLEAN",
        "VARCHAR(255)", "VARCHAR(512)", "VARCHAR(1024)",
        "DATE", "DATETIME", "TIMESTAMP"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_field_management);

        fieldManager = DatabaseFieldManager.getInstance(this);

        initViews();
        setupListeners();
        loadTables();
    }

    private void initViews() {
        spinnerTable = findViewById(R.id.spinnerTable);
        rvFields = findViewById(R.id.rvFields);
        tvNoFields = findViewById(R.id.tvNoFields);
        btnLoadFields = findViewById(R.id.btnLoadFields);
        btnAddField = findViewById(R.id.btnAddField);

        rvFields.setLayoutManager(new LinearLayoutManager(this));
    }

    private void setupListeners() {
        findViewById(R.id.toolbar).setOnClickListener(v -> finish());

        btnLoadFields.setOnClickListener(v -> {
            if (selectedTable != null) {
                loadFields(selectedTable);
            } else {
                Toast.makeText(this, "请先选择一个表", Toast.LENGTH_SHORT).show();
            }
        });

        spinnerTable.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < tableList.size()) {
                    selectedTable = tableList.get(position);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedTable = null;
            }
        });

        btnAddField.setOnClickListener(v -> {
            if (selectedTable != null) {
                showAddFieldDialog(selectedTable);
            } else {
                Toast.makeText(this, "请先选择一个表", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadTables() {
        new Thread(() -> {
            try {
                tableList.clear();
                tableList.add("请选择表...");

                // 直接使用同步方法，避免死锁
                List<String> tables = fieldManager.getAllTablesSync();
                for (String table : tables) {
                    tableList.add(table);
                }

                runOnUiThread(() -> {
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(FieldManagementActivity.this,
                        android.R.layout.simple_spinner_item, tableList);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    spinnerTable.setAdapter(adapter);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "加载表列表失败: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void loadFields(String tableName) {
        ProgressDialog progressDialog = ProgressDialog.show(
            this, "加载字段", "正在加载字段信息...", true, false);

        new Thread(() -> {
            try {
                // 直接使用同步方法，避免死锁
                List<DatabaseFieldManager.FieldInfo> fields = fieldManager.getTableFieldsSync(tableName);

                runOnUiThread(() -> {
                    progressDialog.dismiss();

                    if (fields.isEmpty()) {
                        tvNoFields.setVisibility(View.VISIBLE);
                        rvFields.setVisibility(View.GONE);
                    } else {
                        tvNoFields.setVisibility(View.GONE);
                        rvFields.setVisibility(View.VISIBLE);

                        FieldAdapter adapter = new FieldAdapter(fields, tableName, fieldManager);
                        rvFields.setAdapter(adapter);
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(this, "加载字段失败: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    /**
     * 字段列表适配器
     */
    private static class FieldAdapter extends RecyclerView.Adapter<FieldAdapter.FieldViewHolder> {

        private List<DatabaseFieldManager.FieldInfo> fields;
        private String tableName;
        private DatabaseFieldManager fieldManager;

        FieldAdapter(List<DatabaseFieldManager.FieldInfo> fields, String tableName,
                    DatabaseFieldManager fieldManager) {
            this.fields = fields;
            this.tableName = tableName;
            this.fieldManager = fieldManager;
        }

        @Override
        public FieldViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_field_info, parent, false);
            return new FieldViewHolder(view);
        }

        @Override
        public void onBindViewHolder(FieldViewHolder holder, int position) {
            DatabaseFieldManager.FieldInfo field = fields.get(position);
            holder.bind(field);
        }

        @Override
        public int getItemCount() {
            return fields.size();
        }

        class FieldViewHolder extends RecyclerView.ViewHolder {
            private TextView tvFieldName;
            private TextView tvFieldType;
            private TextView tvFieldConstraints;
            private MaterialButton btnDelete;

            FieldViewHolder(View itemView) {
                super(itemView);
                tvFieldName = itemView.findViewById(R.id.tvFieldName);
                tvFieldType = itemView.findViewById(R.id.tvFieldType);
                tvFieldConstraints = itemView.findViewById(R.id.tvFieldConstraints);
                btnDelete = itemView.findViewById(R.id.btnDelete);
            }

            void bind(DatabaseFieldManager.FieldInfo field) {
                tvFieldName.setText(field.fieldName);
                tvFieldType.setText("类型: " + field.fieldType);

                StringBuilder constraints = new StringBuilder();
                if (field.isPrimaryKey) {
                    constraints.append("主键 ");
                }
                if (!field.isNullable) {
                    constraints.append("NOT NULL ");
                }
                if (field.defaultValue != null) {
                    constraints.append("默认值: ").append(field.defaultValue);
                }
                tvFieldConstraints.setText(constraints.toString().trim());

                btnDelete.setVisibility(View.GONE);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (fieldManager != null) {
            fieldManager.shutdown();
        }
    }

    /**
     * 显示添加字段对话框
     */
    private void showAddFieldDialog(String tableName) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("添加字段");

        // 创建对话框布局
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

        // 字段名称输入
        EditText etFieldName = new EditText(this);
        etFieldName.setHint("字段名称");
        etFieldName.setSingleLine(true);
        etFieldName.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        layout.addView(etFieldName);

        // 字段类型选择
        Spinner spinnerFieldType = new Spinner(this);
        ArrayAdapter<String> typeAdapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, FIELD_TYPES);
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerFieldType.setAdapter(typeAdapter);
        spinnerFieldType.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        layout.addView(spinnerFieldType);

        // 允许为空选项
        MaterialCheckBox cbNullable = new MaterialCheckBox(this);
        cbNullable.setText("允许为空");
        cbNullable.setChecked(true);
        cbNullable.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        layout.addView(cbNullable);

        // 默认值输入
        EditText etDefaultValue = new EditText(this);
        etDefaultValue.setHint("默认值（可选）");
        etDefaultValue.setSingleLine(true);
        etDefaultValue.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        layout.addView(etDefaultValue);

        builder.setView(layout);

        builder.setPositiveButton("添加", (dialog, which) -> {
            String fieldName = etFieldName.getText().toString().trim();
            String fieldType = (String) spinnerFieldType.getSelectedItem();
            boolean nullable = cbNullable.isChecked();
            String defaultValue = etDefaultValue.getText().toString().trim();

            if (fieldName.isEmpty()) {
                Toast.makeText(this, "请输入字段名称", Toast.LENGTH_SHORT).show();
                return;
            }

            if (fieldManager.isFieldProtected(fieldName)) {
                Toast.makeText(this, "不能添加受保护的字段名称", Toast.LENGTH_SHORT).show();
                return;
            }

            addField(tableName, fieldName, fieldType, nullable, defaultValue);
        });

        builder.setNegativeButton("取消", null);
        builder.show();
    }

    /**
     * 添加字段
     */
    private void addField(String tableName, String fieldName, String fieldType, boolean nullable, String defaultValue) {
        ProgressDialog progressDialog = ProgressDialog.show(
            this, "添加字段", "正在添加字段...", true, false);

        new Thread(() -> {
            try {
                DatabaseFieldManager.OperationResult result = fieldManager.addField(
                    tableName, fieldName, fieldType, nullable, 
                    defaultValue.isEmpty() ? null : defaultValue
                ).get();

                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    if (result.success) {
                        Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show();
                        // 重新加载字段列表
                        loadFields(tableName);
                    } else {
                        Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(this, "添加字段失败: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }
}

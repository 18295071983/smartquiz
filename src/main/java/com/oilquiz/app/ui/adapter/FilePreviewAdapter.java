package com.oilquiz.app.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.oilquiz.app.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FilePreviewAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_DATA = 1;

    private Context context;
    private List<String> columnHeaders;
    private List<List<String>> dataRows;
    private Map<String, Integer> fieldMapping;
    private Map<Integer, String> columnToFieldMap;

    private String[] fieldOptions = {
        "不映射", "题目", "选项A", "选项B", "选项C", "选项D", 
        "正确答案", "解析", "难度", "分类", "题型"
    };

    public FilePreviewAdapter(Context context, List<String> columnHeaders, List<List<String>> dataRows, Map<String, Integer> fieldMapping) {
        this.context = context;
        this.columnHeaders = columnHeaders;
        this.dataRows = dataRows;
        this.fieldMapping = fieldMapping != null ? fieldMapping : new HashMap<>();
        this.columnToFieldMap = new HashMap<>();

        // 初始化列到字段的映射
        for (Map.Entry<String, Integer> entry : this.fieldMapping.entrySet()) {
            columnToFieldMap.put(entry.getValue(), entry.getKey());
        }
    }

    @Override
    public int getItemViewType(int position) {
        return position == 0 ? TYPE_HEADER : TYPE_DATA;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View view = LayoutInflater.from(context).inflate(R.layout.item_file_preview_header, parent, false);
            return new HeaderViewHolder(view);
        } else {
            View view = LayoutInflater.from(context).inflate(R.layout.item_file_preview_row, parent, false);
            return new DataViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof HeaderViewHolder) {
            bindHeaderViewHolder((HeaderViewHolder) holder);
        } else if (holder instanceof DataViewHolder) {
            bindDataViewHolder((DataViewHolder) holder, position - 1);
        }
    }

    private void bindHeaderViewHolder(HeaderViewHolder holder) {
        LinearLayout headerContainer = holder.headerContainer;
        headerContainer.removeAllViews();

        for (int i = 0; i < columnHeaders.size(); i++) {
            final int columnIndex = i;
            View headerItem = LayoutInflater.from(context).inflate(R.layout.item_column_header, headerContainer, false);
            TextView columnName = headerItem.findViewById(R.id.columnName);
            Spinner fieldSpinner = headerItem.findViewById(R.id.fieldSpinner);

            columnName.setText(columnHeaders.get(i));

            // 设置字段选择器
            ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, fieldOptions);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            fieldSpinner.setAdapter(adapter);

            // 设置默认选择
            String mappedField = columnToFieldMap.get(columnIndex);
            if (mappedField != null) {
                for (int j = 0; j < fieldOptions.length; j++) {
                    if (fieldOptions[j].equals(mappedField)) {
                        fieldSpinner.setSelection(j);
                        break;
                    }
                }
            }

            // 设置选择监听器
            fieldSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    String selectedField = fieldOptions[position];
                    if (!selectedField.equals("不映射")) {
                        // 移除旧的映射
                        for (Map.Entry<String, Integer> entry : fieldMapping.entrySet()) {
                            if (entry.getValue() == columnIndex) {
                                fieldMapping.remove(entry.getKey());
                                break;
                            }
                        }
                        // 移除旧的列映射
                        columnToFieldMap.remove(columnIndex);

                        // 添加新的映射
                        fieldMapping.put(selectedField, columnIndex);
                        columnToFieldMap.put(columnIndex, selectedField);
                        
                        // 添加高亮效果
                        headerItem.setBackgroundColor(context.getResources().getColor(R.color.primary_light));
                    } else {
                        // 移除映射
                        for (Map.Entry<String, Integer> entry : fieldMapping.entrySet()) {
                            if (entry.getValue() == columnIndex) {
                                fieldMapping.remove(entry.getKey());
                                columnToFieldMap.remove(columnIndex);
                                break;
                            }
                        }
                        
                        // 移除高亮效果
                        headerItem.setBackgroundColor(context.getResources().getColor(R.color.transparent));
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
            
            // 初始状态的高亮
            if (mappedField != null) {
                headerItem.setBackgroundColor(context.getResources().getColor(R.color.primary_light));
            }

            headerContainer.addView(headerItem);
        }
    }

    private void bindDataViewHolder(DataViewHolder holder, int dataPosition) {
        if (dataPosition >= 0 && dataPosition < dataRows.size()) {
            List<String> rowData = dataRows.get(dataPosition);
            LinearLayout rowContainer = holder.rowContainer;
            rowContainer.removeAllViews();

            for (int i = 0; i < columnHeaders.size(); i++) {
                View cellItem = LayoutInflater.from(context).inflate(R.layout.item_data_cell, rowContainer, false);
                TextView cellText = cellItem.findViewById(R.id.cellText);

                if (i < rowData.size()) {
                    cellText.setText(rowData.get(i));
                } else {
                    cellText.setText("");
                }

                rowContainer.addView(cellItem);
            }
        }
    }

    @Override
    public int getItemCount() {
        return dataRows.size() + 1; // +1 for header
    }

    public Map<String, Integer> getFieldMapping() {
        return fieldMapping;
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        LinearLayout headerContainer;

        HeaderViewHolder(View itemView) {
            super(itemView);
            headerContainer = itemView.findViewById(R.id.headerContainer);
        }
    }

    static class DataViewHolder extends RecyclerView.ViewHolder {
        LinearLayout rowContainer;

        DataViewHolder(View itemView) {
            super(itemView);
            rowContainer = itemView.findViewById(R.id.rowContainer);
        }
    }
}

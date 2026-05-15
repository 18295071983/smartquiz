package com.oilquiz.app.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.oilquiz.app.R;

import java.util.List;
import java.util.Map;

public class SmartMappingAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int SHOW_ALL = -1;
    public static final int SHOW_QUESTION_TYPE = 0;
    public static final int SHOW_DIFFICULTY = 1;
    public static final int SHOW_CATEGORY = 2;

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_MAPPING_ROW = 1;

    private Context context;
    private List<String> detectedQuestionTypes;
    private List<String> detectedDifficulties;
    private List<String> detectedCategories;
    private List<String> standardQuestionTypes;
    private List<String> standardDifficulties;
    private List<String> standardCategories;
    private Map<String, String> questionTypeMapping;
    private Map<String, String> difficultyMapping;
    private Map<String, String> categoryMapping;

    private int showType = SHOW_ALL;

    public SmartMappingAdapter(Context context, List<String> detectedQuestionTypes, List<String> detectedDifficulties, List<String> detectedCategories,
                              List<String> standardQuestionTypes, List<String> standardDifficulties, List<String> standardCategories,
                              Map<String, String> questionTypeMapping, Map<String, String> difficultyMapping, Map<String, String> categoryMapping) {
        this.context = context;
        this.detectedQuestionTypes = detectedQuestionTypes;
        this.detectedDifficulties = detectedDifficulties;
        this.detectedCategories = detectedCategories;
        this.standardQuestionTypes = standardQuestionTypes;
        this.standardDifficulties = standardDifficulties;
        this.standardCategories = standardCategories;
        this.questionTypeMapping = questionTypeMapping;
        this.difficultyMapping = difficultyMapping;
        this.categoryMapping = categoryMapping;
    }

    public void setShowType(int type) {
        this.showType = type;
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        if (showType == SHOW_ALL) {
            if (position == 0) return TYPE_HEADER;
            if (isSectionHeaderPosition(position)) return TYPE_HEADER;
            return TYPE_MAPPING_ROW;
        }
        return TYPE_MAPPING_ROW;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View view = LayoutInflater.from(context).inflate(R.layout.item_mapping_header, parent, false);
            return new HeaderViewHolder(view);
        } else {
            View view = LayoutInflater.from(context).inflate(R.layout.item_mapping_row, parent, false);
            return new MappingViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        if (showType == SHOW_ALL) {
            bindAllMode(holder, position);
        } else {
            if (holder instanceof MappingViewHolder) {
                bindTabMode((MappingViewHolder) holder, position);
            }
        }
    }

    private void bindAllMode(RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof HeaderViewHolder) {
            HeaderViewHolder headerHolder = (HeaderViewHolder) holder;
            if (position == 0) {
                headerHolder.questionTypeSection.setVisibility(View.VISIBLE);
                headerHolder.difficultySection.setVisibility(View.VISIBLE);
                headerHolder.categorySection.setVisibility(View.VISIBLE);
                headerHolder.questionTypeSection.setText("题型映射 (" + detectedQuestionTypes.size() + ")");
                headerHolder.difficultySection.setText("难度映射 (" + detectedDifficulties.size() + ")");
                headerHolder.categorySection.setText("分类映射 (" + detectedCategories.size() + ")");
            } else {
                SectionInfo info = getSectionInfo(position);
                headerHolder.questionTypeSection.setVisibility(info.section == SHOW_QUESTION_TYPE ? View.VISIBLE : View.GONE);
                headerHolder.difficultySection.setVisibility(info.section == SHOW_DIFFICULTY ? View.VISIBLE : View.GONE);
                headerHolder.categorySection.setVisibility(info.section == SHOW_CATEGORY ? View.VISIBLE : View.GONE);

                switch (info.section) {
                    case SHOW_QUESTION_TYPE:
                        headerHolder.questionTypeSection.setText("题型映射 (" + detectedQuestionTypes.size() + ")");
                        break;
                    case SHOW_DIFFICULTY:
                        headerHolder.difficultySection.setText("难度映射 (" + detectedDifficulties.size() + ")");
                        break;
                    case SHOW_CATEGORY:
                        headerHolder.categorySection.setText("分类映射 (" + detectedCategories.size() + ")");
                        break;
                }
            }
        } else if (holder instanceof MappingViewHolder) {
            MappingViewHolder mappingHolder = (MappingViewHolder) holder;
            SectionInfo info = getSectionInfo(position);
            int index = info.index;

            switch (info.section) {
                case SHOW_QUESTION_TYPE:
                    if (index < detectedQuestionTypes.size()) {
                        String detectedType = detectedQuestionTypes.get(index);
                        mappingHolder.detectedValue.setText(detectedType);
                        setupSpinner(mappingHolder.mappingSpinner, standardQuestionTypes, questionTypeMapping, detectedType);
                    }
                    break;
                case SHOW_DIFFICULTY:
                    if (index < detectedDifficulties.size()) {
                        String detectedDifficulty = detectedDifficulties.get(index);
                        mappingHolder.detectedValue.setText(detectedDifficulty);
                        setupSpinner(mappingHolder.mappingSpinner, standardDifficulties, difficultyMapping, detectedDifficulty);
                    }
                    break;
                case SHOW_CATEGORY:
                    if (index < detectedCategories.size()) {
                        String detectedCategory = detectedCategories.get(index);
                        mappingHolder.detectedValue.setText(detectedCategory);
                        setupSpinner(mappingHolder.mappingSpinner, standardCategories, categoryMapping, detectedCategory);
                    }
                    break;
            }
        }
    }

    private void bindTabMode(MappingViewHolder holder, int position) {
        int index = position;
        if (showType == SHOW_QUESTION_TYPE) {
            if (index < detectedQuestionTypes.size()) {
                String detectedType = detectedQuestionTypes.get(index);
                holder.detectedValue.setText(detectedType);
                setupSpinner(holder.mappingSpinner, standardQuestionTypes, questionTypeMapping, detectedType);
            }
        } else if (showType == SHOW_DIFFICULTY) {
            if (index < detectedDifficulties.size()) {
                String detectedDifficulty = detectedDifficulties.get(index);
                holder.detectedValue.setText(detectedDifficulty);
                setupSpinner(holder.mappingSpinner, standardDifficulties, difficultyMapping, detectedDifficulty);
            }
        } else if (showType == SHOW_CATEGORY) {
            if (index < detectedCategories.size()) {
                String detectedCategory = detectedCategories.get(index);
                holder.detectedValue.setText(detectedCategory);
                setupSpinner(holder.mappingSpinner, standardCategories, categoryMapping, detectedCategory);
            }
        }
    }

    private boolean isSectionHeaderPosition(int position) {
        if (position == 0) return true;
        if (position == 1) return false;
        int offset = 1 + detectedQuestionTypes.size();
        if (position == offset) return true;
        offset += 1 + detectedDifficulties.size();
        if (position == offset) return true;
        return false;
    }

    private SectionInfo getSectionInfo(int position) {
        int questionTypeStart = 1;
        int questionTypeEnd = questionTypeStart + detectedQuestionTypes.size();

        int difficultyHeaderPos = questionTypeEnd;
        int difficultyStart = difficultyHeaderPos + 1;
        int difficultyEnd = difficultyStart + detectedDifficulties.size();

        int categoryHeaderPos = difficultyEnd;
        int categoryStart = categoryHeaderPos + 1;

        if (position < questionTypeEnd) {
            return new SectionInfo(SHOW_QUESTION_TYPE, position - questionTypeStart);
        } else if (position < difficultyStart) {
            return new SectionInfo(SHOW_DIFFICULTY, -1);
        } else if (position < difficultyEnd) {
            return new SectionInfo(SHOW_DIFFICULTY, position - difficultyStart);
        } else if (position < categoryStart) {
            return new SectionInfo(SHOW_CATEGORY, -1);
        } else {
            return new SectionInfo(SHOW_CATEGORY, position - categoryStart);
        }
    }

    private static class SectionInfo {
        int section;
        int index;

        SectionInfo(int section, int index) {
            this.section = section;
            this.index = index;
        }
    }

    private void setupSpinner(Spinner spinner, List<String> options, Map<String, String> mapping, String detectedValue) {
        List<String> spinnerOptions = new java.util.ArrayList<>();
        spinnerOptions.add("不映射");
        spinnerOptions.addAll(options);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, spinnerOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        String mappedValue = mapping.get(detectedValue);
        if (mappedValue != null) {
            for (int i = 0; i < spinnerOptions.size(); i++) {
                if (spinnerOptions.get(i).equals(mappedValue)) {
                    spinner.setSelection(i);
                    break;
                }
            }
        }

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                String selectedValue = spinnerOptions.get(pos);
                if (!selectedValue.equals("不映射")) {
                    mapping.put(detectedValue, selectedValue);
                } else {
                    mapping.remove(detectedValue);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    @Override
    public int getItemCount() {
        if (showType == SHOW_ALL) {
            return 1 + detectedQuestionTypes.size()
                 + 1 + detectedDifficulties.size()
                 + 1 + detectedCategories.size();
        } else if (showType == SHOW_QUESTION_TYPE) {
            return detectedQuestionTypes.size();
        } else if (showType == SHOW_DIFFICULTY) {
            return detectedDifficulties.size();
        } else {
            return detectedCategories.size();
        }
    }

    public Map<String, String> getQuestionTypeMapping() {
        return questionTypeMapping;
    }

    public Map<String, String> getDifficultyMapping() {
        return difficultyMapping;
    }

    public Map<String, String> getCategoryMapping() {
        return categoryMapping;
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView questionTypeSection;
        TextView difficultySection;
        TextView categorySection;

        HeaderViewHolder(View itemView) {
            super(itemView);
            questionTypeSection = itemView.findViewById(R.id.questionTypeSection);
            difficultySection = itemView.findViewById(R.id.difficultySection);
            categorySection = itemView.findViewById(R.id.categorySection);
        }
    }

    static class MappingViewHolder extends RecyclerView.ViewHolder {
        TextView detectedValue;
        Spinner mappingSpinner;

        MappingViewHolder(View itemView) {
            super(itemView);
            detectedValue = itemView.findViewById(R.id.detectedValue);
            mappingSpinner = itemView.findViewById(R.id.mappingSpinner);
        }
    }
}

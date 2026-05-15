package com.oilquiz.app.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.oilquiz.app.R;
import com.oilquiz.app.model.Template;

import java.util.List;

public class TemplateAdapter extends BaseAdapter {

    private Context context;
    private List<Template> templates;
    private boolean isSelectionMode;
    private List<Template> selectedTemplates;
    private OnTemplateClickListener listener;

    public interface OnTemplateClickListener {
        void onTemplateClick(Template template);
        void onTemplateLongClick(Template template);
    }

    public TemplateAdapter(Context context, List<Template> templates, boolean isSelectionMode, List<Template> selectedTemplates, OnTemplateClickListener listener) {
        this.context = context;
        this.templates = templates;
        this.isSelectionMode = isSelectionMode;
        this.selectedTemplates = selectedTemplates;
        this.listener = listener;
    }

    @Override
    public int getCount() {
        return templates.size();
    }

    @Override
    public Object getItem(int position) {
        return templates.get(position);
    }

    @Override
    public long getItemId(int position) {
        return templates.get(position).getId();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_template, parent, false);
            holder = new ViewHolder();
            holder.nameTextView = convertView.findViewById(R.id.nameTextView);
            holder.descriptionTextView = convertView.findViewById(R.id.descriptionTextView);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        Template template = templates.get(position);
        holder.nameTextView.setText(template.getName());
        holder.descriptionTextView.setText(template.getDescription());

        // 设置点击事件
        convertView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onTemplateClick(template);
            }
        });

        convertView.setOnLongClickListener(v -> {
            if (listener != null) {
                listener.onTemplateLongClick(template);
                return true;
            }
            return false;
        });

        // 处理选择模式的视觉效果
        if (isSelectionMode && selectedTemplates.contains(template)) {
            convertView.setBackgroundColor(context.getResources().getColor(R.color.colorPrimary));
        } else {
            convertView.setBackgroundColor(context.getResources().getColor(android.R.color.transparent));
        }

        return convertView;
    }

    private static class ViewHolder {
        TextView nameTextView;
        TextView descriptionTextView;
    }
}
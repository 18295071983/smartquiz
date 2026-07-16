package com.oilquiz.app.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;

import com.oilquiz.app.R;
import com.oilquiz.app.model.ThemeColor;

import java.util.List;

public class ThemeColorAdapter extends BaseAdapter {

    private Context context;
    private List<ThemeColor> themeColors;
    private int selectedPosition;

    // 兼容现有代码的构造函数
    public ThemeColorAdapter(Context context, List<ThemeColor> themeColors) {
        this(context, themeColors, -1);
    }

    // 新的构造函数，用于主题切换示例
    public ThemeColorAdapter(Context context, List<ThemeColor> themeColors, int selectedPosition) {
        this.context = context;
        this.themeColors = themeColors;
        this.selectedPosition = selectedPosition;
    }

    @Override
    public int getCount() {
        return themeColors.size();
    }

    @Override
    public Object getItem(int position) {
        return themeColors.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;

        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_theme_color, parent, false);
            holder = new ViewHolder();
            holder.cvColor = convertView.findViewById(R.id.cv_color);
            holder.vColor = convertView.findViewById(R.id.v_color);
            holder.tvColorName = convertView.findViewById(R.id.tv_color_name);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        ThemeColor themeColor = themeColors.get(position);
        holder.vColor.setBackgroundResource(themeColor.getColorRes());
        holder.tvColorName.setText(themeColor.getName());

        // 处理选中状态
        if (position == selectedPosition) {
            holder.cvColor.setStrokeColor(ContextCompat.getColor(context, R.color.colorPrimary));
            holder.cvColor.setStrokeWidth(4);
        } else {
            holder.cvColor.setStrokeColor(ContextCompat.getColor(context, R.color.transparent));
            holder.cvColor.setStrokeWidth(2);
        }

        return convertView;
    }

    public void setSelectedPosition(int selectedPosition) {
        this.selectedPosition = selectedPosition;
    }

    private static class ViewHolder {
        MaterialCardView cvColor;
        View vColor;
        TextView tvColorName;
    }
}

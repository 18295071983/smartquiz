package com.oilquiz.app.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.RadioButton;
import android.widget.TextView;

import com.oilquiz.app.R;
import com.oilquiz.app.model.Theme;

import java.util.List;

public class ThemeAdapter extends BaseAdapter {

    private Context context;
    private List<Theme> themes;
    private int selectedPosition = -1;

    public ThemeAdapter(Context context, List<Theme> themes) {
        this.context = context;
        this.themes = themes;
        // 设置默认选中的位置
        for (int i = 0; i < themes.size(); i++) {
            if (themes.get(i).isSelected()) {
                selectedPosition = i;
                break;
            }
        }
    }

    @Override
    public int getCount() {
        return themes.size();
    }

    @Override
    public Object getItem(int position) {
        return themes.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_theme, parent, false);
            holder = new ViewHolder();
            holder.themeName = convertView.findViewById(R.id.tv_theme_name);
            holder.radioButton = convertView.findViewById(R.id.rb_theme);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        Theme theme = themes.get(position);
        holder.themeName.setText(theme.getName());
        holder.radioButton.setChecked(position == selectedPosition);

        return convertView;
    }

    public void setSelectedPosition(int position) {
        selectedPosition = position;
    }

    static class ViewHolder {
        TextView themeName;
        RadioButton radioButton;
    }
}

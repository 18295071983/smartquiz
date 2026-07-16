package com.oilquiz.app.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.oilquiz.app.R;

import java.util.ArrayList;
import java.util.List;

public class ImportErrorAdapter extends RecyclerView.Adapter<ImportErrorAdapter.ErrorViewHolder> {

    private List<String> errors = new ArrayList<>();

    public void setErrors(List<String> errors) {
        this.errors = errors != null ? errors : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ErrorViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_import_error, parent, false);
        return new ErrorViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ErrorViewHolder holder, int position) {
        holder.bind(errors.get(position), position + 1);
    }

    @Override
    public int getItemCount() {
        return errors.size();
    }

    static class ErrorViewHolder extends RecyclerView.ViewHolder {
        private TextView tvErrorNumber;
        private TextView tvErrorMessage;

        public ErrorViewHolder(@NonNull View itemView) {
            super(itemView);
            tvErrorNumber = itemView.findViewById(R.id.tvErrorNumber);
            tvErrorMessage = itemView.findViewById(R.id.tvErrorMessage);
        }

        public void bind(String error, int number) {
            tvErrorNumber.setText(String.valueOf(number));
            tvErrorMessage.setText(error);
        }
    }
}

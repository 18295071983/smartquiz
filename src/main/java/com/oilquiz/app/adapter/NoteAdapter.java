package com.oilquiz.app.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.TextView;

import com.oilquiz.app.R;
import com.oilquiz.app.model.Note;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class NoteAdapter extends BaseAdapter {

    private Context context;
    private List<Note> notes;
    private OnNoteClickListener listener;

    public interface OnNoteClickListener {
        void onNoteClick(Note note);
        void onEditClick(Note note);
        void onDeleteClick(Note note);
    }

    public NoteAdapter(Context context, List<Note> notes, OnNoteClickListener listener) {
        this.context = context;
        this.notes = notes;
        this.listener = listener;
    }

    @Override
    public int getCount() {
        return notes != null ? notes.size() : 0;
    }

    @Override
    public Object getItem(int position) {
        return notes != null ? notes.get(position) : null;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(R.layout.item_note, parent, false);
            holder = new ViewHolder();
            holder.tvNoteTitle = convertView.findViewById(R.id.tv_note_title);
            holder.tvNoteContent = convertView.findViewById(R.id.tv_note_content);
            holder.tvNoteDate = convertView.findViewById(R.id.tv_note_date);
            holder.btnEdit = convertView.findViewById(R.id.btn_edit);
            holder.btnDelete = convertView.findViewById(R.id.btn_delete);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        Note note = notes.get(position);
        if (note != null) {
            holder.tvNoteTitle.setText(note.getTitle());
            holder.tvNoteContent.setText(note.getContent());
            holder.tvNoteDate.setText(formatDate(note.getCreatedAt()));

            holder.btnEdit.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onEditClick(note);
                }
            });

            holder.btnDelete.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDeleteClick(note);
                }
            });

            convertView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onNoteClick(note);
                }
            });
        }

        return convertView;
    }

    private String formatDate(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        return sdf.format(new Date(timestamp));
    }

    static class ViewHolder {
        TextView tvNoteTitle;
        TextView tvNoteContent;
        TextView tvNoteDate;
        Button btnEdit;
        Button btnDelete;
    }

    public void updateData(List<Note> notes) {
        this.notes = notes != null ? notes : new java.util.ArrayList<>();
        notifyDataSetChanged();
    }
}

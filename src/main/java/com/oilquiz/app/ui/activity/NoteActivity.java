package com.oilquiz.app.ui.activity;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;
import java.util.List;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;
import com.oilquiz.app.adapter.NoteAdapter;
import com.oilquiz.app.model.Note;
import com.oilquiz.app.viewmodel.NoteViewModel;

public class NoteActivity extends AppCompatActivity implements NoteAdapter.OnNoteClickListener {

    private NoteViewModel noteViewModel;
    private ListView noteListView;
    private EditText noteTitleEditText;
    private EditText noteContentEditText;
    private MaterialButton addNoteButton;
    private NoteAdapter noteAdapter;
    private Note editingNote;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_note);

        noteViewModel = new ViewModelProvider(this).get(NoteViewModel.class);
        noteViewModel.init(this);

        noteListView = findViewById(R.id.lv_notes);
        noteTitleEditText = findViewById(R.id.et_note_title);
        noteContentEditText = findViewById(R.id.et_note_content);
        addNoteButton = findViewById(R.id.btn_add_note);

        setupListeners();
        loadNotes();
    }

    private void setupListeners() {
        addNoteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    final String title = noteTitleEditText.getText().toString().trim();
                    final String content = noteContentEditText.getText().toString().trim();
                    
                    if (title.isEmpty()) {
                        Toast.makeText(NoteActivity.this, getString(R.string.h_554a28dd), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    if (editingNote != null) {
                        // 编辑现有笔记
                        editingNote.setTitle(title);
                        editingNote.setContent(content);
                        noteViewModel.updateNote(editingNote, new com.oilquiz.app.repository.NoteRepository.RepositoryCallback<Void>() {
                            @Override
                            public void onSuccess(final Void result) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            Toast.makeText(NoteActivity.this, getString(R.string.h_62e6eeb2), Toast.LENGTH_SHORT).show();
                                            editingNote = null;
                                            addNoteButton.setText(getString(R.string.h_81cbd86e));
                                            noteTitleEditText.setText("");
                                            noteContentEditText.setText("");
                                            loadNotes();
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                            Toast.makeText(NoteActivity.this, getString(R.string.h_7354c70f) + e.getMessage(), Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                });
                            }

                            @Override
                            public void onError(final String error) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(NoteActivity.this, getString(R.string.h_7354c70f) + error, Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }
                        });
                    } else {
                        // 添加新笔记
                        noteViewModel.addNote(title, content, new com.oilquiz.app.repository.NoteRepository.RepositoryCallback<Long>() {
                            @Override
                            public void onSuccess(final Long result) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            Toast.makeText(NoteActivity.this, getString(R.string.h_2d22d1d7), Toast.LENGTH_SHORT).show();
                                            noteTitleEditText.setText("");
                                            noteContentEditText.setText("");
                                            loadNotes();
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                            Toast.makeText(NoteActivity.this, getString(R.string.h_0d741dec) + e.getMessage(), Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                });
                            }

                            @Override
                            public void onError(final String error) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(NoteActivity.this, getString(R.string.h_0d741dec) + error, Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }
                        });
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    Toast.makeText(NoteActivity.this, getString(R.string.h_be4a600c) + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void loadNotes() {
        noteViewModel.getNotes(new com.oilquiz.app.repository.NoteRepository.RepositoryCallback<List<Note>>() {
            @Override
            public void onSuccess(final List<Note> notes) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (noteAdapter == null) {
                                noteAdapter = new NoteAdapter(NoteActivity.this, notes != null ? notes : new java.util.ArrayList<Note>(), NoteActivity.this);
                                noteListView.setAdapter(noteAdapter);
                            } else {
                                noteAdapter.updateData(notes);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                            Toast.makeText(NoteActivity.this, getString(R.string.h_bfaee9de) + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }

            @Override
            public void onError(final String error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(NoteActivity.this, getString(R.string.h_bfaee9de) + error, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    @Override
    public void onNoteClick(Note note) {
        // 点击笔记项，显示笔记详情
        new AlertDialog.Builder(this)
                .setTitle(note.getTitle())
                .setMessage(note.getContent())
                .setPositiveButton(getString(R.string.h_38cf16f2), null)
                .show();
    }

    @Override
    public void onEditClick(Note note) {
        // 点击编辑按钮，将笔记内容填充到编辑框
        editingNote = note;
        noteTitleEditText.setText(note.getTitle());
        noteContentEditText.setText(note.getContent());
        addNoteButton.setText(getString(R.string.h_281e2b43));
    }

    @Override
    public void onDeleteClick(Note note) {
        try {
            if (note == null) {
                Toast.makeText(this, getString(R.string.h_65d0074b), Toast.LENGTH_SHORT).show();
                return;
            }
            // 显示删除确认对话框
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.h_d8b87379))
                    .setMessage(getString(R.string.h_92d8c70b))
                    .setPositiveButton(getString(R.string.h_38cf16f2), (dialog, which) -> {
                        noteViewModel.deleteNote(note.getId(), new com.oilquiz.app.repository.NoteRepository.RepositoryCallback<Void>() {
                            @Override
                            public void onSuccess(final Void result) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            Toast.makeText(NoteActivity.this, getString(R.string.h_0007d170), Toast.LENGTH_SHORT).show();
                                            loadNotes(); // 重新加载笔记列表
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                            Toast.makeText(NoteActivity.this, getString(R.string.h_e0f24bbc) + e.getMessage(), Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                });
                            }

                            @Override
                            public void onError(final String error) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(NoteActivity.this, getString(R.string.h_e0f24bbc) + error, Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }
                        });
                    })
                    .setNegativeButton(getString(R.string.h_625fb26b), null)
                    .show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, getString(R.string.h_be4a600c) + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadNotes(); // 重新加载笔记列表
    }
}

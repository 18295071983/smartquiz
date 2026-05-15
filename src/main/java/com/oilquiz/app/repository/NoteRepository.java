package com.oilquiz.app.repository;

import android.content.Context;

import com.oilquiz.app.database.AppDatabase;
import com.oilquiz.app.database.NoteDao;
import com.oilquiz.app.model.Note;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NoteRepository {
    private NoteDao noteDao;
    private ExecutorService executorService;

    public interface RepositoryCallback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    public NoteRepository(Context context) {
        AppDatabase db = AppDatabase.getDatabase(context);
        noteDao = db.noteDao();
        executorService = Executors.newSingleThreadExecutor();
    }

    public void addNote(final Note note, final RepositoryCallback<Long> callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    long id = noteDao.insert(note);
                    callback.onSuccess(id);
                } catch (Exception e) {
                    callback.onError(e.getMessage());
                }
            }
        });
    }

    public void updateNote(final Note note, final RepositoryCallback<Void> callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    noteDao.update(note);
                    callback.onSuccess(null);
                } catch (Exception e) {
                    callback.onError(e.getMessage());
                }
            }
        });
    }

    public void deleteNote(final long id, final RepositoryCallback<Void> callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    noteDao.deleteNote(id);
                    callback.onSuccess(null);
                } catch (Exception e) {
                    callback.onError(e.getMessage());
                }
            }
        });
    }

    public void getNoteById(final long id, final RepositoryCallback<Note> callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Note note = noteDao.getNoteById(id);
                    callback.onSuccess(note);
                } catch (Exception e) {
                    callback.onError(e.getMessage());
                }
            }
        });
    }

    public void getNotesByUserId(final long userId, final RepositoryCallback<List<Note>> callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    List<Note> notes = noteDao.getNotesByUserId(userId);
                    callback.onSuccess(notes);
                } catch (Exception e) {
                    callback.onError(e.getMessage());
                }
            }
        });
    }

    public void getNotesByRelatedQuestionId(final long questionId, final RepositoryCallback<List<Note>> callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    List<Note> notes = noteDao.getNotesByRelatedQuestionId(questionId);
                    callback.onSuccess(notes);
                } catch (Exception e) {
                    callback.onError(e.getMessage());
                }
            }
        });
    }

    public void getNotes(final RepositoryCallback<List<Note>> callback) {
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    List<Note> notes = noteDao.getNotes();
                    callback.onSuccess(notes);
                } catch (Exception e) {
                    callback.onError(e.getMessage());
                }
            }
        });
    }
}

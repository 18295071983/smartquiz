package com.oilquiz.app.viewmodel;

import android.content.Context;

import androidx.lifecycle.ViewModel;

import com.oilquiz.app.model.Note;
import com.oilquiz.app.repository.NoteRepository;

import java.util.List;

public class NoteViewModel extends ViewModel {
    private NoteRepository noteRepository;

    public void init(Context context) {
        noteRepository = new NoteRepository(context);
    }

    public void addNote(Note note, NoteRepository.RepositoryCallback<Long> callback) {
        noteRepository.addNote(note, callback);
    }

    public void updateNote(Note note, NoteRepository.RepositoryCallback<Void> callback) {
        noteRepository.updateNote(note, callback);
    }

    public void deleteNote(long id, NoteRepository.RepositoryCallback<Void> callback) {
        noteRepository.deleteNote(id, callback);
    }

    public void getNoteById(long id, NoteRepository.RepositoryCallback<Note> callback) {
        noteRepository.getNoteById(id, callback);
    }

    public void getNotesByUserId(long userId, NoteRepository.RepositoryCallback<List<Note>> callback) {
        noteRepository.getNotesByUserId(userId, callback);
    }

    public void getNotesByRelatedQuestionId(long questionId, NoteRepository.RepositoryCallback<List<Note>> callback) {
        noteRepository.getNotesByRelatedQuestionId(questionId, callback);
    }

    public void addNote(String title, String content, NoteRepository.RepositoryCallback<Long> callback) {
        Note note = new Note();
        note.setTitle(title);
        note.setContent(content);
        note.setCreateTime(System.currentTimeMillis());
        note.setUpdateTime(System.currentTimeMillis());
        note.setUserId(1); // 默认用户ID
        noteRepository.addNote(note, callback);
    }

    public void getNotes(NoteRepository.RepositoryCallback<List<Note>> callback) {
        noteRepository.getNotes(callback);
    }
}

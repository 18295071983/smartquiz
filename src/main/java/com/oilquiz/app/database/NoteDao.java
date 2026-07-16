package com.oilquiz.app.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.oilquiz.app.model.Note;

import java.util.List;

@Dao
public interface NoteDao {
    @Insert
    long insert(Note note);

    @Update
    void update(Note note);

    @Query("DELETE FROM note WHERE id = :id")
    void deleteNote(long id);

    @Query("SELECT * FROM note WHERE id = :id")
    Note getNoteById(long id);

    @Query("SELECT * FROM note WHERE userId = :userId")
    List<Note> getNotesByUserId(long userId);

    @Query("SELECT * FROM note WHERE relatedQuestionId = :questionId")
    List<Note> getNotesByRelatedQuestionId(long questionId);

    @Query("SELECT * FROM note")
    List<Note> getNotes();

    @Query("SELECT COUNT(*) FROM note WHERE userId = :userId")
    int getNoteCountByUserId(long userId);
}

package com.oilquiz.app.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.oilquiz.app.model.ChatHistory;

import java.util.List;

@Dao
public interface ChatHistoryDao {
    @Insert
    long insert(ChatHistory chatHistory);

    @Query("DELETE FROM chat_history WHERE id = :id")
    void deleteChatHistory(long id);

    @Query("DELETE FROM chat_history WHERE userId = :userId")
    void deleteChatHistoryByUserId(long userId);

    @Query("SELECT * FROM chat_history WHERE userId = :userId ORDER BY timestamp ASC")
    List<ChatHistory> getChatHistoryByUserId(long userId);
}

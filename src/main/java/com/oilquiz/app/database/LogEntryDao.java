package com.oilquiz.app.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.oilquiz.app.model.LogEntry;

import java.util.List;

@Dao
public interface LogEntryDao {
    @Insert
    long insert(LogEntry logEntry);

    @Query("SELECT * FROM log_entry WHERE userId = :userId ORDER BY timestamp DESC")
    List<LogEntry> getLogEntriesByUserId(long userId);

    @Query("SELECT * FROM log_entry WHERE level = :level ORDER BY timestamp DESC")
    List<LogEntry> getLogEntriesByLevel(String level);

    @Query("SELECT * FROM log_entry ORDER BY timestamp DESC LIMIT :limit")
    List<LogEntry> getRecentLogEntries(int limit);
}

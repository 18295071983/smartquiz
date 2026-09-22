package com.oilquiz.app.ai.sessionlog;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

/**
 * 会话事件 DAO —— append-only 写入 + 按会话顺序读取。
 */
@Dao
public interface SessionEventDao {

    /** 追加一条事件，返回自增主键（行 id）。 */
    @Insert
    long insert(SessionEvent event);

    /** 按会话读取全部事件（seq 升序 = 追加顺序）。 */
    @Query("SELECT * FROM session_events WHERE sessionId = :sessionId ORDER BY seq ASC")
    List<SessionEvent> events(String sessionId);

    /** 某会话当前最大 seq（无记录返回 0）。 */
    @Query("SELECT COALESCE(MAX(seq), 0) FROM session_events WHERE sessionId = :sessionId")
    long maxSeq(String sessionId);

    /** 某会话事件总数。 */
    @Query("SELECT COUNT(*) FROM session_events WHERE sessionId = :sessionId")
    int count(String sessionId);

    /** 按会话删除（会话清理/重置用；正常流程不做局部删除，保持 append-only）。 */
    @Query("DELETE FROM session_events WHERE sessionId = :sessionId")
    int deleteBySession(String sessionId);
}

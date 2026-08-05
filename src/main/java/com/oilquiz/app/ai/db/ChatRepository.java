package com.oilquiz.app.ai.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.oilquiz.app.ai.ChatScene;
import com.oilquiz.app.ai.util.PromptBuilder;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ChatRepository {
    private static final String TAG = "ChatRepository";
    private ChatDatabaseHelper dbHelper;

    public ChatRepository(Context context) {
        dbHelper = new ChatDatabaseHelper(context);
    }

    // 创建新会话（带场景隔离）
    public long createConversation(String title, String scene) {
        String safeScene = ChatScene.normalize(scene);
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        long timestamp = System.currentTimeMillis();
        values.put(ChatDatabaseHelper.COLUMN_CONV_TITLE, title);
        values.put(ChatDatabaseHelper.COLUMN_CONV_CREATED_AT, timestamp);
        values.put(ChatDatabaseHelper.COLUMN_CONV_UPDATED_AT, timestamp);
        values.put(ChatDatabaseHelper.COLUMN_CONV_SCENE, safeScene);
        long conversationId = db.insert(ChatDatabaseHelper.TABLE_CONVERSATIONS, null, values);
        db.close();
        Log.d(TAG, "Created conversation with id: " + conversationId + ", scene: " + safeScene);
        return conversationId;
    }

    // 向后兼容：默认 ai_chat 场景
    public long createConversation(String title) {
        return createConversation(title, ChatScene.AI_CHAT);
    }

    // 保存聊天消息
    public long saveMessage(long conversationId, String role, String content, boolean isCompressed) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(ChatDatabaseHelper.COLUMN_CONVERSATION_ID, conversationId);
        values.put(ChatDatabaseHelper.COLUMN_ROLE, role);
        values.put(ChatDatabaseHelper.COLUMN_CONTENT, content);
        values.put(ChatDatabaseHelper.COLUMN_TIMESTAMP, System.currentTimeMillis());
        values.put(ChatDatabaseHelper.COLUMN_IS_COMPRESSED, isCompressed ? 1 : 0);
        long messageId = db.insert(ChatDatabaseHelper.TABLE_CHAT_MESSAGES, null, values);
        
        // 更新会话的更新时间
        ContentValues convValues = new ContentValues();
        convValues.put(ChatDatabaseHelper.COLUMN_CONV_UPDATED_AT, System.currentTimeMillis());
        db.update(ChatDatabaseHelper.TABLE_CONVERSATIONS, convValues, 
                ChatDatabaseHelper.COLUMN_CONV_ID + " = ?", new String[]{String.valueOf(conversationId)});
        
        db.close();
        Log.d(TAG, "Saved message with id: " + messageId);
        return messageId;
    }

    // 获取会话的所有消息
    public List<PromptBuilder.Message> getMessages(long conversationId) {
        List<PromptBuilder.Message> messages = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.query(
                ChatDatabaseHelper.TABLE_CHAT_MESSAGES,
                new String[]{ChatDatabaseHelper.COLUMN_ROLE, ChatDatabaseHelper.COLUMN_CONTENT},
                ChatDatabaseHelper.COLUMN_CONVERSATION_ID + " = ?",
                new String[]{String.valueOf(conversationId)},
                null,
                null,
                ChatDatabaseHelper.COLUMN_TIMESTAMP + " ASC"
        );

        if (cursor != null) {
            while (cursor.moveToNext()) {
                String role = cursor.getString(cursor.getColumnIndexOrThrow(ChatDatabaseHelper.COLUMN_ROLE));
                String content = cursor.getString(cursor.getColumnIndexOrThrow(ChatDatabaseHelper.COLUMN_CONTENT));
                messages.add(new PromptBuilder.Message(role, content));
            }
            cursor.close();
        }
        db.close();
        Log.d(TAG, "Retrieved " + messages.size() + " messages for conversation: " + conversationId);
        return messages;
    }

    // 获取所有会话
    public List<Conversation> getConversations() {
        List<Conversation> conversations = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.query(
                ChatDatabaseHelper.TABLE_CONVERSATIONS,
                new String[]{ChatDatabaseHelper.COLUMN_CONV_ID, ChatDatabaseHelper.COLUMN_CONV_TITLE, 
                        ChatDatabaseHelper.COLUMN_CONV_CREATED_AT, ChatDatabaseHelper.COLUMN_CONV_UPDATED_AT},
                null,
                null,
                null,
                null,
                ChatDatabaseHelper.COLUMN_CONV_UPDATED_AT + " DESC"
        );

        if (cursor != null) {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(ChatDatabaseHelper.COLUMN_CONV_ID));
                String title = cursor.getString(cursor.getColumnIndexOrThrow(ChatDatabaseHelper.COLUMN_CONV_TITLE));
                long createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(ChatDatabaseHelper.COLUMN_CONV_CREATED_AT));
                long updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(ChatDatabaseHelper.COLUMN_CONV_UPDATED_AT));
                conversations.add(new Conversation(id, title, createdAt, updatedAt));
            }
            cursor.close();
        }
        db.close();
        Log.d(TAG, "Retrieved " + conversations.size() + " conversations");
        return conversations;
    }

    // 删除会话
    public void deleteConversation(long conversationId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        // 先删除会话的所有消息
        db.delete(ChatDatabaseHelper.TABLE_CHAT_MESSAGES, 
                ChatDatabaseHelper.COLUMN_CONVERSATION_ID + " = ?", 
                new String[]{String.valueOf(conversationId)});
        // 再删除会话
        db.delete(ChatDatabaseHelper.TABLE_CONVERSATIONS, 
                ChatDatabaseHelper.COLUMN_CONV_ID + " = ?", 
                new String[]{String.valueOf(conversationId)});
        db.close();
        Log.d(TAG, "Deleted conversation with id: " + conversationId);
    }

    // 清空所有聊天记录
    public void clearAllChats() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete(ChatDatabaseHelper.TABLE_CHAT_MESSAGES, null, null);
        db.delete(ChatDatabaseHelper.TABLE_CONVERSATIONS, null, null);
        db.close();
        Log.d(TAG, "Cleared all chats");
    }

    // ========== 组2：场景隔离 & 软裁剪 ==========

    /**
     * 清空指定场景的所有会话和消息。
     */
    public void clearScene(String scene) {
        String safeScene = ChatScene.normalize(scene);
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        // 先查出该场景的所有 conversationId
        Cursor cursor = db.query(
                ChatDatabaseHelper.TABLE_CONVERSATIONS,
                new String[]{ChatDatabaseHelper.COLUMN_CONV_ID},
                ChatDatabaseHelper.COLUMN_CONV_SCENE + " = ?",
                new String[]{safeScene},
                null, null, null
        );
        List<Long> ids = new ArrayList<>();
        if (cursor != null) {
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(0));
            }
            cursor.close();
        }
        // 删除这些会话的消息
        for (Long id : ids) {
            db.delete(ChatDatabaseHelper.TABLE_CHAT_MESSAGES,
                    ChatDatabaseHelper.COLUMN_CONVERSATION_ID + " = ?",
                    new String[]{String.valueOf(id)});
        }
        // 删除会话本身
        db.delete(ChatDatabaseHelper.TABLE_CONVERSATIONS,
                ChatDatabaseHelper.COLUMN_CONV_SCENE + " = ?",
                new String[]{safeScene});
        db.close();
        Log.d(TAG, "Cleared scene: " + safeScene + " (" + ids.size() + " conversations)");
    }

    /**
     * SQL 层直接截断取最近 N 对消息（ORDER BY DESC LIMIT 2*N），Java Heap 不膨胀。
     * 返回的列表已按时间正序排列（最旧→最新），适合直接拼入 prompt。
     *
     * @param scene          场景过滤
     * @param conversationId 当前会话ID
     * @param maxPairs       最大对话对数（1对 = user + assistant）
     */
    public List<PromptBuilder.Message> getRecentForContext(String scene, long conversationId, int maxPairs) {
        String safeScene = ChatScene.normalize(scene);
        int limit = Math.max(2, maxPairs * 2);
        List<PromptBuilder.Message> reverse = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        // JOIN conversations 校验 scene，确保不会跨场景读到消息
        String query = "SELECT m." + ChatDatabaseHelper.COLUMN_ROLE + ", m." + ChatDatabaseHelper.COLUMN_CONTENT +
                " FROM " + ChatDatabaseHelper.TABLE_CHAT_MESSAGES + " m" +
                " INNER JOIN " + ChatDatabaseHelper.TABLE_CONVERSATIONS + " c" +
                " ON m." + ChatDatabaseHelper.COLUMN_CONVERSATION_ID + " = c." + ChatDatabaseHelper.COLUMN_CONV_ID +
                " WHERE m." + ChatDatabaseHelper.COLUMN_CONVERSATION_ID + " = ?" +
                " AND c." + ChatDatabaseHelper.COLUMN_CONV_SCENE + " = ?" +
                " ORDER BY m." + ChatDatabaseHelper.COLUMN_TIMESTAMP + " DESC" +
                " LIMIT " + limit;
        Cursor cursor = db.rawQuery(query, new String[]{String.valueOf(conversationId), safeScene});
        if (cursor != null) {
            while (cursor.moveToNext()) {
                String role = cursor.getString(0);
                String content = cursor.getString(1);
                reverse.add(new PromptBuilder.Message(role, content));
            }
            cursor.close();
        }
        db.close();
        // 反转为正序（最旧→最新）
        Collections.reverse(reverse);
        // 配对对齐：如果第一条是 assistant（缺少对应 user），丢弃它
        while (!reverse.isEmpty() && !"user".equals(reverse.get(0).role())) {
            reverse.remove(0);
        }
        Log.d(TAG, "getRecentForContext: scene=" + safeScene + ", convId=" + conversationId +
                ", maxPairs=" + maxPairs + ", returned=" + reverse.size() + " messages");
        return reverse;
    }

    /**
     * 获取模型上下文消息（三步安全裁剪）：
     * 1) SQL LIMIT 取最近 maxPairs 对消息
     * 2) 配对对齐（丢弃孤立的 assistant 消息）
     * 3) tokens 预算精裁（通过 PromptBuilder.truncateHistory，整条整加不斩半条）
     *
     * @param scene          场景过滤
     * @param conversationId 当前会话ID
     * @param maxPairs       最大对话对数
     * @return 安全的消息列表，可直接拼入 prompt
     */
    public List<PromptBuilder.Message> getContextMessages(String scene, long conversationId, int maxPairs) {
        List<PromptBuilder.Message> recent = getRecentForContext(scene, conversationId, maxPairs);
        if (recent.isEmpty()) return recent;
        // 第三步：tokens 预算精裁（PromptBuilder.truncateHistory 内部会按内存档位调整 maxPairs）
        return PromptBuilder.truncateHistory(recent, maxPairs);
    }

    /**
     * 获取指定场景的会话列表。
     */
    public List<Conversation> getConversationsByScene(String scene) {
        String safeScene = ChatScene.normalize(scene);
        List<Conversation> conversations = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.query(
                ChatDatabaseHelper.TABLE_CONVERSATIONS,
                new String[]{ChatDatabaseHelper.COLUMN_CONV_ID, ChatDatabaseHelper.COLUMN_CONV_TITLE,
                        ChatDatabaseHelper.COLUMN_CONV_CREATED_AT, ChatDatabaseHelper.COLUMN_CONV_UPDATED_AT},
                ChatDatabaseHelper.COLUMN_CONV_SCENE + " = ?",
                new String[]{safeScene},
                null, null,
                ChatDatabaseHelper.COLUMN_CONV_UPDATED_AT + " DESC"
        );
        if (cursor != null) {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String title = cursor.getString(1);
                long createdAt = cursor.getLong(2);
                long updatedAt = cursor.getLong(3);
                conversations.add(new Conversation(id, title, createdAt, updatedAt));
            }
            cursor.close();
        }
        db.close();
        return conversations;
    }

    // 会话模型类
    public static class Conversation {
        private long id;
        private String title;
        private long createdAt;
        private long updatedAt;

        public Conversation(long id, String title, long createdAt, long updatedAt) {
            this.id = id;
            this.title = title;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        public long getId() { return id; }
        public String getTitle() { return title; }
        public long getCreatedAt() { return createdAt; }
        public long getUpdatedAt() { return updatedAt; }
    }
}

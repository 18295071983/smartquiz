package com.oilquiz.app.ai.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.oilquiz.app.ai.util.PromptBuilder;

import java.util.ArrayList;
import java.util.List;

public class ChatRepository {
    private static final String TAG = "ChatRepository";
    private ChatDatabaseHelper dbHelper;

    public ChatRepository(Context context) {
        dbHelper = new ChatDatabaseHelper(context);
    }

    // 创建新会话
    public long createConversation(String title) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        long timestamp = System.currentTimeMillis();
        values.put(ChatDatabaseHelper.COLUMN_CONV_TITLE, title);
        values.put(ChatDatabaseHelper.COLUMN_CONV_CREATED_AT, timestamp);
        values.put(ChatDatabaseHelper.COLUMN_CONV_UPDATED_AT, timestamp);
        long conversationId = db.insert(ChatDatabaseHelper.TABLE_CONVERSATIONS, null, values);
        db.close();
        Log.d(TAG, "Created conversation with id: " + conversationId);
        return conversationId;
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

package com.oilquiz.app.ui.activity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.Context;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.ChatModeManager;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ui.adapter.ChatAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28})
public class AIChatActivityTest {
    
    private AIChatActivity activity;
    
    @Mock
    private AIService mockAIService;
    
    @Mock
    private ChatModeManager mockModeManager;
    
    @Mock
    private ChatAdapter mockChatAdapter;
    
    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        Context context = RuntimeEnvironment.application;
        activity = new AIChatActivity();
    }
    
    @Test
    public void testMessageCreation() {
        String testContent = "测试消息";
        long timestamp = System.currentTimeMillis();
        
        ChatMessage userMsg = ChatMessage.createUserMessage(
            UUID.randomUUID().toString(),
            testContent,
            timestamp
        );
        
        assertNotNull(userMsg);
        assertTrue(userMsg.isUserMessage());
        assertEquals(testContent, userMsg.content);
        assertEquals(timestamp, userMsg.timestamp);
        assertNull(userMsg.thinkingContent);
    }
    
    @Test
    public void testAIMessageCreation() {
        String messageId = UUID.randomUUID().toString();
        
        ChatMessage aiMsg = new ChatMessage.Builder(ChatMessage.MessageType.AI)
            .id(messageId)
            .content("")
            .timestamp(System.currentTimeMillis())
            .status(ChatMessage.MessageStatus.GENERATING)
            .build();
        
        assertNotNull(aiMsg);
        assertTrue(aiMsg.isAIMessage());
        assertEquals(ChatMessage.MessageStatus.GENERATING, aiMsg.status);
        assertTrue(aiMsg.content.isEmpty());
    }
    
    @Test
    public void testSystemMessageCreation() {
        String content = "系统提示消息";
        
        ChatMessage sysMsg = ChatMessage.createSystemMessage(
            content,
            ChatMessage.SystemMessageType.INFO
        );
        
        assertNotNull(sysMsg);
        assertTrue(sysMsg.isSystemMessage());
        assertEquals(content, sysMsg.content);
    }
    
    @Test
    public void testMessageClone() {
        String originalContent = "原始内容";
        String thinkingContent = "思考过程";
        
        ChatMessage original = new ChatMessage.Builder(ChatMessage.MessageType.AI)
            .id(UUID.randomUUID().toString())
            .content(originalContent)
            .timestamp(System.currentTimeMillis())
            .thinkingContent(thinkingContent)
            .status(ChatMessage.MessageStatus.COMPLETED)
            .build();
        
        ChatMessage cloned = original.clone();
        
        assertNotNull(cloned);
        assertNotSame(original, cloned);
        assertEquals(original.id, cloned.id);
        assertEquals(originalContent, cloned.content);
        assertEquals(thinkingContent, cloned.thinkingContent);
        assertEquals(original.status, cloned.status);
    }
    
    @Test
    public void testAttachmentCreation() {
        String uri = "content://test/image.jpg";
        String fileName = "test.jpg";
        long fileSize = 1024;
        
        ChatMessage.Attachment attachment = new ChatMessage.Attachment(
            "image",
            uri,
            fileName,
            fileSize
        );
        
        assertNotNull(attachment);
        assertEquals("image", attachment.type);
        assertEquals(uri, attachment.uri);
        assertEquals(fileName, attachment.name);
        assertEquals(fileSize, attachment.size);
    }
    
    @Test
    public void testUserMessageWithAttachments() {
        List<ChatMessage.Attachment> attachments = new ArrayList<>();
        attachments.add(new ChatMessage.Attachment("image", "uri1", "img1.jpg", 100));
        attachments.add(new ChatMessage.Attachment("file", "uri2", "doc.pdf", 2048));
        
        ChatMessage msg = ChatMessage.createUserMessage(
            UUID.randomUUID().toString(),
            "带附件的消息",
            System.currentTimeMillis(),
            attachments
        );
        
        assertNotNull(msg);
        assertNotNull(msg.attachments);
        assertEquals(2, msg.attachments.size());
        assertTrue(msg.hasAttachments());
    }
    
    @Test
    public void testEmptyMessageValidation() {
        ChatMessage emptyMsg = ChatMessage.createUserMessage(
            UUID.randomUUID().toString(),
            "",
            System.currentTimeMillis()
        );
        
        assertNotNull(emptyMsg);
        assertTrue(emptyMsg.content.isEmpty());
    }
    
    @Test
    public void testLongMessageContent() {
        StringBuilder longContent = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            longContent.append("这是一个很长的消息内容。");
        }
        
        ChatMessage longMsg = ChatMessage.createUserMessage(
            UUID.randomUUID().toString(),
            longContent.toString(),
            System.currentTimeMillis()
        );
        
        assertNotNull(longMsg);
        assertEquals(longContent.length(), longMsg.content.length());
    }
    
    @Test
    public void testThinkingStepCreation() {
        ChatMessage.ThinkingStep step = new ChatMessage.ThinkingStep(
            "分析问题",
            "正在分析用户的问题...",
            ChatMessage.ThinkingStep.ThinkingStepType.UNDERSTAND
        );
        
        assertNotNull(step);
        assertEquals("分析问题", step.type);
        assertEquals("正在分析用户的问题...", step.description);
        assertEquals(ChatMessage.ThinkingStep.ThinkingStepType.UNDERSTAND, step.stepType);
        assertEquals(ChatMessage.ThinkingStepStatus.PENDING, step.status);
    }
    
    @Test
    public void testMessageStatusTransitions() {
        ChatMessage msg = new ChatMessage.Builder(ChatMessage.MessageType.AI)
            .id(UUID.randomUUID().toString())
            .content("")
            .timestamp(System.currentTimeMillis())
            .status(ChatMessage.MessageStatus.GENERATING)
            .build();
        
        assertEquals(ChatMessage.MessageStatus.GENERATING, msg.status);
        
        msg.status = ChatMessage.MessageStatus.IN_PROGRESS;
        assertEquals(ChatMessage.MessageStatus.IN_PROGRESS, msg.status);
        
        msg.status = ChatMessage.MessageStatus.COMPLETED;
        assertEquals(ChatMessage.MessageStatus.COMPLETED, msg.status);
        
        msg.status = ChatMessage.MessageStatus.FAILED;
        assertEquals(ChatMessage.MessageStatus.FAILED, msg.status);
    }
    
    @Test
    public void testConversationHistoryFiltering() {
        List<ChatMessage> messages = new ArrayList<>();
        
        messages.add(ChatMessage.createUserMessage(UUID.randomUUID().toString(), "问题1", System.currentTimeMillis()));
        messages.add(new ChatMessage.Builder(ChatMessage.MessageType.AI).id(UUID.randomUUID()).content("回答1").timestamp(System.currentTimeMillis()).status(ChatMessage.MessageStatus.COMPLETED).build());
        messages.add(ChatMessage.createUserMessage(UUID.randomUUID().toString(), "问题2", System.currentTimeMillis()));
        messages.add(new ChatMessage.Builder(ChatMessage.MessageType.AI).id(UUID.randomUUID()).content("").timestamp(System.currentTimeMillis()).status(ChatMessage.MessageStatus.GENERATING).build());
        messages.add(ChatMessage.createSystemMessage("系统消息", ChatMessage.SystemMessageType.INFO));
        
        List<ChatMessage> filteredHistory = new ArrayList<>();
        for (ChatMessage msg : messages) {
            if ((msg.isUserMessage() || msg.isAIMessage()) && 
                !(msg.isAIMessage() && msg.status == ChatMessage.MessageStatus.GENERATING)) {
                filteredHistory.add(msg);
            }
        }
        
        assertEquals(3, filteredHistory.size());
        assertTrue(filteredHistory.get(0).isUserMessage());
        assertTrue(filteredHistory.get(1).isAIMessage());
        assertTrue(filteredHistory.get(2).isUserMessage());
    }
    
    @Test
    public void testUUIDGenerationUniqueness() {
        String id1 = UUID.randomUUID().toString();
        String id2 = UUID.randomUUID().toString();
        
        assertNotEquals(id1, id2);
        assertNotNull(id1);
        assertNotNull(id2);
        assertEquals(36, id1.length()); // UUID格式长度
    }
    
    @Test
    public void testTimestampAccuracy() {
        long beforeCreate = System.currentTimeMillis();
        ChatMessage msg = ChatMessage.createUserMessage(
            UUID.randomUUID().toString(),
            "时间戳测试",
            System.currentTimeMillis()
        );
        long afterCreate = System.currentTimeMillis();
        
        assertTrue(msg.timestamp >= beforeCreate);
        assertTrue(msg.timestamp <= afterCreate);
    }
    
    @Test
    public void testNullSafetyForOptionalFields() {
        ChatMessage msg = new ChatMessage.Builder(ChatMessage.MessageType.AI)
            .id(UUID.randomUUID().toString())
            .content(null)
            .timestamp(System.currentTimeMillis())
            .build();
        
        assertNull(msg.content);
        assertNull(msg.thinkingContent);
        assertNull(msg.attachments);
        assertNull(msg.thinkingSteps);
    }
}

package com.oilquiz.app.ai.chat.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.oilquiz.app.ai.chat.ChatMessage;

import org.junit.Test;

/**
 * 摘要缓存键必须按文件内容指纹，而不是 attachment.id。
 * attachment.id 是每次构造附件时随机生成的 UUID —— 用它做键，持久化缓存永远不命中，
 * 同一个文件每次重新提问都会再走一遍在线模型。
 */
public class SummaryCacheKeyTest {

    @Test
    public void sameFileYieldsSameKeyAcrossAttachmentInstances() {
        ChatMessage.Attachment first = new ChatMessage.Attachment(
                "image", "file:///sd-cache/notes.pdf", "notes.pdf", 2048);
        first.localFilePath = "/sd-cache/notes.pdf";

        ChatMessage.Attachment second = new ChatMessage.Attachment(
                "image", "file:///sd-cache/notes.pdf", "notes.pdf", 2048);
        second.localFilePath = "/sd-cache/notes.pdf";

        assertNotEquals("两个实例的随机 id 本就不同（这正是旧键规则失效的原因）", first.id, second.id);
        assertEquals("同一文件必须命中同一份摘要缓存",
                AttachmentProcessor.summaryCacheKey(first), AttachmentProcessor.summaryCacheKey(second));
    }

    @Test
    public void differentFileYieldsDifferentKey() {
        ChatMessage.Attachment a = new ChatMessage.Attachment(
                "image", "file:///sd-cache/a.pdf", "a.pdf", 2048);
        ChatMessage.Attachment b = new ChatMessage.Attachment(
                "image", "file:///sd-cache/b.pdf", "b.pdf", 2048);
        assertNotEquals(AttachmentProcessor.summaryCacheKey(a), AttachmentProcessor.summaryCacheKey(b));
    }

    @Test
    public void sameNameDifferentSizeIsNotConfused() {
        ChatMessage.Attachment small = new ChatMessage.Attachment(
                "image", "file:///sd-cache/shot.png", "shot.png", 1024);
        ChatMessage.Attachment big = new ChatMessage.Attachment(
                "image", "file:///sd-cache/shot.png", "shot.png", 4096);
        assertNotEquals(AttachmentProcessor.summaryCacheKey(small),
                AttachmentProcessor.summaryCacheKey(big));
    }
}

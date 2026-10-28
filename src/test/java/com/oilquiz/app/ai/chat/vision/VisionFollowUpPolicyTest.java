package com.oilquiz.app.ai.chat.vision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.chat.ChatMessage;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 视觉追问闸门（2026-10-28）：决定"这一轮要不要把历史图片重新编码上传给模型"。
 *
 * <p>回归的是真机日志里的一类事故：发过一次图之后，之后每一轮纯文字消息都被视觉通道接管
 * （"你有什么功能""继续""git clone …"全都在重新上传那张图），模型拿到无关旧图、Agent
 * 工具链被提前 return 短路。</p>
 */
public class VisionFollowUpPolicyTest {

    private static ChatMessage user(String text) {
        ChatMessage m = ChatMessage.createUserMessage(text);
        m.type = ChatMessage.MessageType.USER;
        return m;
    }

    private static ChatMessage ai(String text) {
        ChatMessage m = ChatMessage.createAIMessage(text);
        m.type = ChatMessage.MessageType.AI;
        return m;
    }

    private static ChatMessage imageUser(String text) {
        ChatMessage m = user(text);
        m.attachments = new ArrayList<>();
        ChatMessage.Attachment att = new ChatMessage.Attachment(
                "image", "content://x/1.jpg", "1.jpg", 1024);
        m.attachments.add(att);
        return m;
    }

    // ==================== 轮次距离 ====================

    @Test
    public void countsUserTurnsAfterImageNotMessages() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(user("旧话题"));
        history.add(ai("旧回答"));
        history.add(imageUser("看这张图"));
        history.add(ai("图里是 P-226 预填模板"));
        history.add(user("继续"));           // 第 1 个用户回合
        history.add(ai("继续说明"));
        history.add(user("再问一次"));        // 第 2 个用户回合（当前）
        assertEquals(2, AttachmentVisionPipeline.userTurnsSinceImage(history, 2));
    }

    @Test
    public void invalidIndexIsZeroTurns() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(user("hi"));
        assertEquals(0, AttachmentVisionPipeline.userTurnsSinceImage(history, -1));
        assertEquals(0, AttachmentVisionPipeline.userTurnsSinceImage(history, 9));
        assertEquals(0, AttachmentVisionPipeline.userTurnsSinceImage(null, 0));
    }

    // ==================== 闸门判定 ====================

    @Test
    public void immediateNextTurnReusesEvenWithoutCue() {
        // 发完图直接追问"继续"——这是正常的看图续聊，仍然要带图
        assertTrue(AttachmentVisionPipeline.shouldReuseImageForFollowUp(1, "继续"));
        assertTrue(AttachmentVisionPipeline.shouldReuseImageForFollowUp(2, "那第三题呢"));
        assertNull(AttachmentVisionPipeline.explainFollowUpReuse(1, "继续"));
    }

    @Test
    public void distantUnrelatedMessageNoLongerCarriesImage() {
        // 真机日志现场："你有什么功能""继续""git clone …" 都曾经在重新上传旧图
        assertFalse(AttachmentVisionPipeline.shouldReuseImageForFollowUp(3, "你有什么功能"));
        assertFalse(AttachmentVisionPipeline.shouldReuseImageForFollowUp(3, "继续"));
        assertFalse(AttachmentVisionPipeline.shouldReuseImageForFollowUp(3,
                "git clone https://github.com/18295071983/smartquiz.git"));
        assertTrue(AttachmentVisionPipeline.explainFollowUpReuse(3, "你有什么功能").contains("没有图片指代线索"));
    }

    @Test
    public void distantMessageWithExplicitReferenceStillReusesImage() {
        assertTrue(AttachmentVisionPipeline.shouldReuseImageForFollowUp(3, "这张图里第三个是什么"));
        assertTrue(AttachmentVisionPipeline.shouldReuseImageForFollowUp(4, "帮我把截图里的表填完"));
    }

    @Test
    public void hardCapStopsAncientImagesEvenWithReference() {
        assertFalse(AttachmentVisionPipeline.shouldReuseImageForFollowUp(
                AttachmentVisionPipeline.VISION_FOLLOW_UP_MAX_TURNS + 1, "这张图再看一下"));
        assertTrue(AttachmentVisionPipeline.explainFollowUpReuse(
                AttachmentVisionPipeline.VISION_FOLLOW_UP_MAX_TURNS + 1, "这张图再看一下").contains("上限"));
    }

    @Test
    public void currentImageMessageIsNotAFollowUp() {
        assertFalse(AttachmentVisionPipeline.shouldReuseImageForFollowUp(0, "这张图里有什么"));
        assertFalse(AttachmentVisionPipeline.shouldReuseImageForFollowUp(-1, ""));
    }

    // ==================== 指代线索词 ====================

    @Test
    public void detectsImageReferences() {
        assertTrue(AttachmentVisionPipeline.refersToImage("这张图里的第三题"));
        assertTrue(AttachmentVisionPipeline.refersToImage("图中那行字是什么"));
        assertTrue(AttachmentVisionPipeline.refersToImage("帮我看下截图"));
        assertTrue(AttachmentVisionPipeline.refersToImage("照片右下角"));
        assertTrue(AttachmentVisionPipeline.refersToImage("Screenshot 里第二列"));
        assertTrue(AttachmentVisionPipeline.refersToImage("check the IMAGE again"));
    }

    @Test
    public void doesNotMistakeOrdinaryWordsForImageReferences() {
        // "这张表""地图"不能算"在说那张图"，否则闸门等于没装
        assertFalse(AttachmentVisionPipeline.refersToImage("这张表怎么填"));
        assertFalse(AttachmentVisionPipeline.refersToImage("导航到最近的地图位置"));
        assertFalse(AttachmentVisionPipeline.refersToImage("图表里的数据不对"));
        assertFalse(AttachmentVisionPipeline.refersToImage("你有什么功能"));
        assertFalse(AttachmentVisionPipeline.refersToImage(""));
        assertFalse(AttachmentVisionPipeline.refersToImage(null));
    }
}

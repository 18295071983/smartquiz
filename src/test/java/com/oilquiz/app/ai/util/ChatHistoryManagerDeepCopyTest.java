package com.oilquiz.app.ai.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.oilquiz.app.ai.chat.ChatMessage;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 保存副本必须保留"非 transient 但 clone 未覆盖"的三个字段（2026-11-13，B11/B9）。
 *
 * <p>背景：`approvals` / `questions` / `remoteCardView` 都声明为非 transient，
 * 但 `ChatMessage.clone()` 与 `deepCopyForSave()` 都没有补拷 → 保存副本里恒为空：
 * pending 审批重启后消失、远程卡重进会话变空壳。这两条用例把它们钉住。</p>
 */
public class ChatHistoryManagerDeepCopyTest {

    @Test
    public void keepsApprovalsAndQuestions() {
        ChatMessage m = ChatMessage.createAIMessage("T1-A1", "hi", 1000L, null, 0, 0);
        m.approvals = new ArrayList<>();
        m.approvals.add(new ChatMessage.ApprovalRequest(
                "ap-1", "call-1", "write_file", "写文件", "pending", 1, 1000L, "dsh"));
        m.questions = new ArrayList<>();
        m.questions.add(new ChatMessage.QuestionRequest());

        List<ChatMessage> copy = ChatHistoryManager.deepCopyForSave(
                java.util.Collections.singletonList(m));

        assertEquals(1, copy.size());
        assertNotNull("审批必须随保存副本保留（B11）", copy.get(0).approvals);
        assertEquals(1, copy.get(0).approvals.size());
        assertEquals("ap-1", copy.get(0).approvals.get(0).id);
        assertEquals("dsh", copy.get(0).approvals.get(0).source);
        assertNotNull("问答必须随保存副本保留（B11）", copy.get(0).questions);
        assertEquals(1, copy.get(0).questions.size());
    }

    @Test
    public void keepsRemoteCardView() {
        ChatMessage m = ChatMessage.createAIMessage("T1-A2", "hi", 1000L, null, 0, 0);
        Map<String, Object> view = new HashMap<>();
        view.put("header", "自检");
        view.put("cards", new ArrayList<>());
        m.remoteCardView = view;

        List<ChatMessage> copy = ChatHistoryManager.deepCopyForSave(
                java.util.Collections.singletonList(m));

        assertNotNull("远程卡必须随保存副本保留（B9）", copy.get(0).remoteCardView);
        assertEquals("自检", copy.get(0).remoteCardView.get("header"));
    }
}

package com.oilquiz.app.ai.python;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@code document_parser} 退化为只读后的防误用契约（2026-10-28）。
 *
 * <p>最关键的一条：老会话的缓存 schema 里可能还带着 {@code action=write}。如果这种调用被静默降级成
 * read，模型会拿到文件内容并以为"写成功了"，而文件一个字节都没变——这种静默错误比直接报错难查得多，
 * 所以这里把"必须显式失败"钉死。</p>
 */
public class DocumentParserReadOnlyTest {

    @Test
    public void removedWriteActionsFailLoudlyAndPointToFileGenerator() {
        for (String action : new String[]{"write", "append", "edit", "replace", "diff"}) {
            String message = DocumentParserTool.removedActionMessage(action);
            assertTrue("已移除的 action=" + action + " 必须报错，不能静默降级", message != null);
            assertTrue("报错要说清迁移去向: " + message, message.contains("file_generator"));
            assertTrue("报错要带上原 action: " + message, message.contains("action=" + action));
        }
    }

    @Test
    public void readOnlyActionsAreStillAllowed() {
        assertNull(DocumentParserTool.removedActionMessage("read"));
        assertNull(DocumentParserTool.removedActionMessage("parse"));
        assertNull("缺省 action 走 read，不算已移除", DocumentParserTool.removedActionMessage(null));
        assertNull(DocumentParserTool.removedActionMessage(""));
    }
}

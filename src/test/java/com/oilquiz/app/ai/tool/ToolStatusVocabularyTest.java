package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link ToolStatusVocabulary} 的规则钉桩。
 *
 * <p>最关键的一条是"不认识的 status 不许当成成功"：旧实现是一条 if-else 链，默认
 * {@code isSuccess = true}，任何新状态词或大写 FAILED 都会被读成成功 —— 并行词表最坏的
 * 失效方式不是崩溃，而是安静地说谎。
 */
public class ToolStatusVocabularyTest {

    @Test
    public void knownStatusesGetLabels() {
        assertEquals("[工具执行成功]", ToolStatusVocabulary.modelLabel(ToolStatusVocabulary.SUCCESS));
        assertEquals("[工具执行失败]", ToolStatusVocabulary.modelLabel(ToolStatusVocabulary.FAILED));
        assertEquals("[工具执行失败]", ToolStatusVocabulary.modelLabel(ToolStatusVocabulary.ERROR));
        assertNotNull(ToolStatusVocabulary.modelLabel(ToolStatusVocabulary.TIMEOUT));
        assertNotNull(ToolStatusVocabulary.modelLabel(ToolStatusVocabulary.TRUNCATED));
        assertNotNull(ToolStatusVocabulary.modelLabel(ToolStatusVocabulary.FALLBACK));
    }

    @Test
    public void caseInsensitiveSoUppercaseFailedIsNotSuccess() {
        assertEquals("[工具执行失败]", ToolStatusVocabulary.modelLabel("FAILED"));
        assertEquals("[工具执行失败]", ToolStatusVocabulary.modelLabel("Error"));
        assertTrue(ToolStatusVocabulary.isFailure("FAILED"));
    }

    /** 核心防线：未知值返回 null，由调用方如实标注，绝不回落成"成功" */
    @Test
    public void unknownStatusIsNotSilentlySuccess() {
        assertNull(ToolStatusVocabulary.modelLabel("partial"));
        assertNull(ToolStatusVocabulary.modelLabel("who_knows"));
        assertNull(ToolStatusVocabulary.modelLabel(""));
        assertNull(ToolStatusVocabulary.modelLabel(null));
        assertFalse(ToolStatusVocabulary.isFailure("partial"));
    }

    /** 完成同义词来自全仓库实际写入的 status 字面值，不是猜的：
     *  把它们当"未识别"会是噪声，把它们当失败更是错 */
    @Test
    public void successSynonymsAreRecognised() {
        for (String word : new String[]{"completed", "added", "deleted", "saved", "updated",
                "cleared", "reverted", "set", "success_with_detail"}) {
            assertEquals(word + " 应算成功", "[工具执行成功]", ToolStatusVocabulary.modelLabel(word));
            assertFalse(word + " 不该算失败", ToolStatusVocabulary.isFailure(word));
        }
    }

    /** 没做成但不属于"error"的常见终态：必须给非成功的抬头，否则模型以为拿到了正常结果 */
    @Test
    public void nonErrorFailuresAreLabelled() {
        for (String word : new String[]{"not_found", "connect_failed", "restricted",
                "unsupported", "cancelled"}) {
            assertNotNull(word + " 必须有抬头", ToolStatusVocabulary.modelLabel(word));
            assertFalse("抬头不能是成功", "[工具执行成功]".equals(ToolStatusVocabulary.modelLabel(word)));
            assertTrue(word + " 应算失败", ToolStatusVocabulary.isFailure(word));
        }
    }

    /** 要用户先做动作 / 还在跑，既不是成功也不该被说成失败 */
    @Test
    public void pendingKindsAreNeitherSuccessNorFailure() {
        assertTrue(ToolStatusVocabulary.modelLabel("need_permission").contains("需要用户先完成动作"));
        assertTrue(ToolStatusVocabulary.modelLabel("in_progress").contains("仍在进行中"));
        assertFalse(ToolStatusVocabulary.isFailure("need_permission"));
        assertFalse(ToolStatusVocabulary.isFailure("in_progress"));
    }

    @Test
    public void timeoutCountsAsFailureForNextStepPurposes() {
        // 超时对模型是"别照结果往下推"的信号：命令可能已生效但没回传
        assertTrue(ToolStatusVocabulary.isFailure(ToolStatusVocabulary.TIMEOUT));
        assertTrue(ToolStatusVocabulary.modelLabel(ToolStatusVocabulary.TIMEOUT)
                .contains("可能已经生效"));
    }

    @Test
    public void booleanStatusMapsBothWays() {
        assertEquals("[工具执行成功]", ToolStatusVocabulary.modelLabel(Boolean.TRUE));
        assertEquals("[工具执行失败]", ToolStatusVocabulary.modelLabel(Boolean.FALSE));
    }
}

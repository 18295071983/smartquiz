package com.oilquiz.app.ai.prompt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * PromptAssembler 语义测试 —— 对齐 dsh SystemPrompt 的核心契约：
 * 1) order 升序 + 同名按 name 字典序（确定性排序）
 * 2) scoped 段覆盖 global 同名段（shadow）
 * 3) 重复注册（同层）抛错；disposer 幂等卸载
 * 4) 严格变量插值：{{name}} 未知/畸形/无值抛错；interpolate=false 保留原文
 * 5) complete 段：多于一个 active 抛错；存在时恢复为唯一 prompt 段
 * 6) 动态上下文渲染："当前运行时上下文"前缀 + 空段丢弃
 */
public class PromptAssemblerTest {

    // ===== 1. order 排序 + name 兜底 =====

    @Test
    public void sectionsOrderByOrderThenName() {
        PromptAssembler a = new PromptAssembler();
        a.registerSection(PromptSection.of("zeta", 10, "Z"));
        a.registerSection(PromptSection.of("alpha", 10, "A"));
        a.registerSection(PromptSection.of("mid", 5, "M"));
        PromptAssembly as = a.assemble(AssembleContext.global());
        assertEquals("M\n\nA\n\nZ", as.render());
    }

    // ===== 2. scoped shadow =====

    @Test
    public void scopedSectionShadowsGlobal() {
        PromptAssembler a = new PromptAssembler();
        a.registerSection(PromptSection.of("persona", 0, "global persona"));
        a.registerSection(PromptSection.of("persona", 0, "agent persona"), "agent");
        assertEquals("global persona", a.assemble(AssembleContext.global()).render());
        assertEquals("agent persona", a.assemble(AssembleContext.of("agent")).render());
    }

    @Test
    public void scopedVariableShadowsGlobal() {
        PromptAssembler a = new PromptAssembler();
        a.registerVariable("name", ctx -> "global-name");
        a.registerVariable("name", ctx -> "agent-name", "agent");
        a.registerSection(PromptSection.of("id", 0, "你好 {{name}}"));
        assertEquals("你好 global-name", a.assemble(AssembleContext.global()).render());
        assertEquals("你好 agent-name", a.assemble(AssembleContext.of("agent")).render());
    }

    // ===== 3. 重复注册 + disposer =====

    @Test
    public void duplicateSectionInSameLayerThrows() {
        PromptAssembler a = new PromptAssembler();
        a.registerSection(PromptSection.of("dup", 0, "x"));
        assertThrows(IllegalArgumentException.class,
                () -> a.registerSection(PromptSection.of("dup", 1, "y")));
    }

    @Test
    public void disposerRemovesSectionIdempotently() {
        PromptAssembler a = new PromptAssembler();
        a.registerSection(PromptSection.of("s", 0, "S"));
        Runnable d = a.registerSection(PromptSection.of("t", 1, "T"));
        assertEquals("S\n\nT", a.assemble(AssembleContext.global()).render());
        d.run();
        d.run(); // 幂等
        assertEquals("S", a.assemble(AssembleContext.global()).render());
    }

    @Test
    public void scopedUnregisterRestoresGlobal() {
        PromptAssembler a = new PromptAssembler();
        a.registerSection(PromptSection.of("persona", 0, "global persona"));
        Runnable d = a.registerSection(PromptSection.of("persona", 0, "agent persona"), "agent");
        assertEquals("agent persona", a.assemble(AssembleContext.of("agent")).render());
        d.run();
        assertEquals("global persona", a.assemble(AssembleContext.of("agent")).render());
    }

    // ===== 4. 严格变量插值 =====

    @Test
    public void unknownVariableThrows() {
        PromptAssembler a = new PromptAssembler();
        a.registerSection(PromptSection.of("s", 0, "你好 {{missing}}"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> a.assemble(AssembleContext.global()).render());
        assertTrue(ex.getMessage().contains("unknown prompt variable"));
    }

    @Test
    public void malformedVariableThrows() {
        PromptAssembler a = new PromptAssembler();
        a.registerVariable("ok", ctx -> "1");
        a.registerSection(PromptSection.of("s", 0, "值：{{OK}}"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> a.assemble(AssembleContext.global()).render());
        assertTrue(ex.getMessage().contains("malformed"));
    }

    @Test
    public void undefinedVariableValueThrows() {
        PromptAssembler a = new PromptAssembler();
        a.registerVariable("maybe", ctx -> null);
        a.registerSection(PromptSection.of("s", 0, "值：{{maybe}}"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> a.assemble(AssembleContext.global()).render());
        assertTrue(ex.getMessage().contains("no value"));
    }

    @Test
    public void interpolateFalseKeepsLiteral() {
        PromptAssembler a = new PromptAssembler();
        a.registerSection(PromptSection.of("s", 0, "字面 {{name}}").interpolate(false));
        assertEquals("字面 {{name}}", a.assemble(AssembleContext.global()).render());
    }

    @Test
    public void interpolatedValueNotRescanned() {
        PromptAssembler a = new PromptAssembler();
        a.registerVariable("a", ctx -> "{{b}}");
        a.registerVariable("b", ctx -> "B");
        a.registerSection(PromptSection.of("s", 0, "值 {{a}}"));
        assertEquals("值 {{b}}", a.assemble(AssembleContext.global()).render());
    }

    // ===== 5. complete 段 =====

    @Test
    public void singleCompleteBecomesSoleSection() {
        PromptAssembler a = new PromptAssembler();
        a.registerSection(PromptSection.of("identity", -1000, "你是答题宝助手"));
        a.registerSection(PromptSection.of("persona", 0, "普通 persona"));
        a.registerSection(PromptSection.of("override", 0, "本地模式完整提示").complete(true), "local");
        assertEquals("你是答题宝助手\n\n普通 persona",
                a.assemble(AssembleContext.global()).render());
        assertEquals("本地模式完整提示",
                a.assemble(AssembleContext.of("local")).render());
    }

    @Test
    public void multipleCompleteThrows() {
        PromptAssembler a = new PromptAssembler();
        a.registerSection(PromptSection.of("c1", 0, "x").complete(true));
        a.registerSection(PromptSection.of("c2", 0, "y").complete(true));
        assertThrows(IllegalStateException.class, () -> a.assemble(AssembleContext.global()));
    }

    // ===== 6. 动态上下文 =====

    @Test
    public void contextRenderedAsRuntimeSnapshot() {
        PromptAssembler a = new PromptAssembler();
        a.registerContext(PromptContext.of("session", 200, "当前会话：答题模式"));
        a.registerSection(PromptSection.of("s", 0, "S"));
        PromptAssembly as = a.assemble(AssembleContext.global());
        assertEquals("S", as.render());
        String snap = as.renderContextSnapshot();
        assertTrue(snap.startsWith("当前运行时上下文。此快照取代更早的运行时上下文快照。"));
        assertTrue(snap.contains("当前会话：答题模式"));
    }

    @Test
    public void emptyContextsRenderEmptySnapshot() {
        PromptAssembler a = new PromptAssembler();
        a.registerContext(PromptContext.of("empty", 0, "   "));
        assertEquals("", a.assemble(AssembleContext.global()).renderContextSnapshot());
    }

    @Test
    public void contextsJoinInOrder() {
        PromptAssembler a = new PromptAssembler();
        a.registerContext(PromptContext.of("later", 200, "B"));
        a.registerContext(PromptContext.of("early", 100, "A"));
        String snap = a.assemble(AssembleContext.global()).renderContextSnapshot();
        assertTrue(snap.contains("\n\nA\n\nB"));
        assertFalse(snap.contains("\n\nB\n\nA"));
    }
}

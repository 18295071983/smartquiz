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
 *
 * <p>CLEANUP(2026-11-13)：dsh 的 PromptContext（运行时上下文快照）与 complete 段语义已从
 * PromptAssembler 删除，对应用例一并移除——它们在生产代码里没有任何调用方，
 * 留着测试只会让"这个能力还在用"看起来像真的。
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

    // ===== 5. 空段与排版 =====

    @Test
    public void emptySectionsDroppedAndJoinedByBlankLine() {
        PromptAssembler a = new PromptAssembler();
        a.registerSection(PromptSection.of("blank", 0, "   "));
        a.registerSection(PromptSection.of("one", 1, "A"));
        a.registerSection(PromptSection.of("two", 2, "B"));
        assertEquals("A\n\nB", a.assemble(AssembleContext.global()).render());
    }

    @Test
    public void allEmptyRendersEmptyString() {
        PromptAssembler a = new PromptAssembler();
        a.registerSection(PromptSection.of("blank", 0, "   "));
        assertEquals("", a.assemble(AssembleContext.global()).render());
    }
}

package com.oilquiz.app.ai.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一次 prompt 组装的结果 —— 对应 dsh 的 {@code PromptAssembly}。
 * sections/contexts 均已求值并按 order 排序（变量尚未插值，渲染时才插值）。
 */
public final class PromptAssembly {

    /** 一个已求值的命名段 */
    public static final class Section {
        public final String name;
        public final String text;
        public final boolean interpolate;

        Section(String name, String text, boolean interpolate) {
            this.name = name;
            this.text = text;
            this.interpolate = interpolate;
        }
    }

    /** 一个已求值的动态上下文段 */
    public static final class Ctx {
        public final String name;
        public final String text;

        Ctx(String name, String text) {
            this.name = name;
            this.text = text;
        }
    }

    public final List<Section> sections;
    public final List<Ctx> contexts;
    /** 本次组装解析出的变量（name -> value，scoped 已覆盖 global）；渲染插值使用 */
    public final Map<String, String> variables;

    PromptAssembly(List<Section> sections, List<Ctx> contexts, Map<String, String> variables) {
        this.sections = sections;
        this.contexts = contexts;
        this.variables = variables == null ? java.util.Collections.emptyMap() : variables;
    }

    /** 渲染系统提示正文：插值后按空行连接，空段丢弃 */
    public String render() {
        return PromptAssembler.render(this);
    }

    /** 渲染动态上下文快照（"当前运行时上下文"开头），无内容返回空串 */
    public String renderContextSnapshot() {
        return PromptAssembler.renderContextSnapshot(this);
    }

    /** 所有已求值段（含 contexts）的名称，便于诊断/日志 */
    public List<String> sectionNames() {
        List<String> names = new ArrayList<>();
        for (Section s : sections) names.add(s.name);
        for (Ctx c : contexts) names.add("ctx:" + c.name);
        return names;
    }
}

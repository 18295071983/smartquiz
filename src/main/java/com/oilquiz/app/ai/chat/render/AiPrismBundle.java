package com.oilquiz.app.ai.chat.render;

import io.noties.prism4j.annotations.PrismBundle;

/**
 * Prism4j 语法包注册（prism4j-bundler 注解处理器据此生成 GrammarLocatorDef，
 * 供 MarkdownRenderer 的 Prism4j 代码语法高亮使用）。
 *
 * include 仅限 bundler 2.0.0 已 ported 的语言（见 bundler jar 内 languages/ 目录）；
 * 不支持的（bash/php/ruby/typescript/rust 等）会导致 "Unable to read language" 编译错误。
 * grammarLocatorClassName 带点 → 生成到本类同包（com.oilquiz.app.ai.chat.render）。
 */
@PrismBundle(
        include = {"markup", "css", "clike", "javascript", "json", "java", "kotlin", "python",
                "sql", "yaml", "c", "cpp", "csharp", "go", "swift", "markdown"},
        grammarLocatorClassName = ".GrammarLocatorDef"
)
public class AiPrismBundle {

    private AiPrismBundle() {
    }
}

package com.oilquiz.app.ai.agent.online;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 文档解析工具的注入条件（2026-10-28）。
 *
 * <p>背景：{@code python_file_ops} 是唯一能取 PDF/Word/PPT 正文的工具，但它不是常驻核心工具。
 * 用户说"看看这份 pdf"时如果没被注入，模型手里只有读不了 docx/pptx/pdf 的 {@code file_reader}，
 * 于是转去手写 Python 解析——工具在、模型看不见，等于没有。这里钉住"该出现时必须出现"。</p>
 */
public class OnlineToolManagerIntentTest {

    @Test
    public void documentFormatsBringTheParserIntoContext() {
        for (String msg : new String[]{
                "帮我看看这个 pdf 讲了什么",
                "读一下 report.docx 的正文",
                "这份 ppt 有几页",
                "把那个 excel 表头念一下",
                "简历里写了什么",
                "总结这篇论文",
                "解析文档 /sdcard/a.pdf"}) {
            assertTrue("该注入文档解析工具：" + msg, OnlineToolManager.wantsDocumentParsing(msg));
        }
    }

    @Test
    public void ordinaryRequestsDoNotPullInTheParser() {
        for (String msg : new String[]{
                "今天天气怎么样",
                "帮我写个文件",
                "算一下 1+1",
                "把这张图里的字取出来"}) {
            assertFalse("不该注入文档解析工具：" + msg, OnlineToolManager.wantsDocumentParsing(msg));
        }
        assertFalse(OnlineToolManager.wantsDocumentParsing(null));
        assertFalse(OnlineToolManager.wantsDocumentParsing(""));
    }
}

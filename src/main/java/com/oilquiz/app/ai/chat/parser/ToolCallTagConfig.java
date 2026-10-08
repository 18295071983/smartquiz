package com.oilquiz.app.ai.chat.parser;

import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 工具调用标签配置 —— 标签来自 chat template，由 native 通过 meta 事件下发，不硬编码。
 *
 * <p>背景：流式阶段需要判断"当前 token 是正文还是工具调用语法"。此前
 * {@code AgentLoopEngine.filterStreamToken} 只硬编码识别 Qwen 的 {@code <tool_call>}，
 * 而 MiniCPM5 用 {@code <function name=".."><param name="..">}，
 * 于是 MiniCPM5 的标签被判为普通正文推给 UI 并被 TTS 朗读（实测 TTS 朗读
 * {@code <function name="location"<param name=...}）。</p>
 *
 * <p>标签来源：native 从 {@code common_chat_params.preserved_tokens} 推导后随 meta 事件下发
 * （{@code tool_call_open_tags} / {@code tool_call_close_tags}）。换模型时模板变化，
 * 标签自动跟着变，Java 侧无需改动。</p>
 *
 * <p><b>注意</b>：本配置为空（模板未声明工具语法）时，调用方必须**不做任何吞除**，
 * 按纯正文处理 —— 否则会把正常回复吃掉。</p>
 */
public final class ToolCallTagConfig {

    private static final String TAG = "ToolCallTagConfig";

    /** 模板未提供工具调用标签时的空配置（纯正文模型 / 无工具语法） */
    private static final ToolCallTagConfig EMPTY =
            new ToolCallTagConfig(Collections.<String>emptyList(), Collections.<String>emptyList());

    /** 开标签，如 {@code "<function"} / {@code "<param"} / {@code "<tool_call"} */
    private final List<String> openTags;
    /** 闭标签，如 {@code "</function>"} / {@code "</param>"} / {@code "</tool_call>"} */
    private final List<String> closeTags;

    private ToolCallTagConfig(List<String> openTags, List<String> closeTags) {
        List<String> o = new ArrayList<>();
        if (openTags != null) {
            for (String t : openTags) {
                if (t != null && !t.isEmpty()) o.add(t);
            }
        }
        List<String> c = new ArrayList<>();
        if (closeTags != null) {
            for (String t : closeTags) {
                if (t != null && !t.isEmpty()) c.add(t);
            }
        }
        this.openTags = Collections.unmodifiableList(o);
        this.closeTags = Collections.unmodifiableList(c);
    }

    /** 模板是否声明了工具调用语法；false 表示不应做任何吞除 */
    public boolean isAvailable() {
        return !openTags.isEmpty();
    }

    public List<String> getOpenTags() {
        return openTags;
    }

    public List<String> getCloseTags() {
        return closeTags;
    }

    public static ToolCallTagConfig empty() {
        return EMPTY;
    }

    /**
     * 文本中是否出现任一工具调用开标签。
     */
    public boolean containsOpenTag(String text) {
        if (text == null || text.isEmpty()) return false;
        for (String t : openTags) {
            if (text.contains(t)) return true;
        }
        return false;
    }

    /**
     * 文本中是否出现任一工具调用闭标签。
     */
    public boolean containsCloseTag(String text) {
        if (text == null || text.isEmpty()) return false;
        for (String t : closeTags) {
            if (text.contains(t)) return true;
        }
        return false;
    }

    /**
     * 从 native meta 事件解析。任何异常都退化为 empty（不做吞除），不抛给调用方。
     */
    public static ToolCallTagConfig fromJson(JSONObject meta) {
        if (meta == null) return EMPTY;
        try {
            List<String> open = readStringArray(meta.optJSONArray("tool_call_open_tags"));
            List<String> close = readStringArray(meta.optJSONArray("tool_call_close_tags"));
            ToolCallTagConfig cfg = new ToolCallTagConfig(open, close);
            if (cfg.isAvailable()) {
                AILogger.i(TAG, "Tool-call tags from template: " + open.size() + " open "
                        + open + ", " + close.size() + " close " + close);
            }
            return cfg;
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to parse tool-call tags from meta, disabled: " + e.getMessage());
            return EMPTY;
        }
    }

    public static ToolCallTagConfig fromJson(String json) {
        if (json == null || json.isEmpty()) return EMPTY;
        try {
            return fromJson(new JSONObject(json));
        } catch (Exception e) {
            AILogger.w(TAG, "Invalid meta JSON: " + e.getMessage());
            return EMPTY;
        }
    }

    /**
     * 当前引擎的工具调用标签。
     *
     * <p>规则与 {@link ThinkingTagConfig#forCurrentEngine()} 一致：</p>
     * <ol>
     *   <li>NPU：GenieX 模板在 SDK 侧，llama.cpp native 上下文不存在 → 取不到模板标签，
     *       用 Qwen 系默认（{@code <tool_call>}），与 {@code NpuEngineRouter} 的 meta 事件一致。</li>
     *   <li>其他引擎：用 native 模板下发的标签；取不到时返回 empty（不做吞除）。</li>
     * </ol>
     */
    public static ToolCallTagConfig forCurrentEngine() {
        try {
            if (com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                return fromJson("{\"tool_call_open_tags\":[\"<tool_call\"],"
                        + "\"tool_call_close_tags\":[\"</tool_call>\"]}");
            }
        } catch (Throwable ignored) {
            // NpuLlmChat 不可用时按 llama.cpp 处理
        }
        try {
            return com.oilquiz.app.ai.jni.LlamaHelper.getToolCallTags();
        } catch (Throwable t) {
            return EMPTY;
        }
    }

    private static List<String> readStringArray(JSONArray arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            String t = arr.optString(i, "");
            if (t != null && !t.isEmpty()) out.add(t);
        }
        return out;
    }

    @Override
    public String toString() {
        return "ToolCallTagConfig{open=" + openTags + ", close=" + closeTags + "}";
    }
}

package com.oilquiz.app.ai.chat.parser;

import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 思考标签配置 —— 标签来自 chat template，由 native 通过 meta 事件下发，不硬编码。
 *
 * <p>背景：不同模型的思考标签各不相同（Qwen3 用 {@code <think>}、DeepSeek 用
 * {@code [THINK]}、GPT-OSS 用 {@code <|channel|>analysis<|message|>}、MiniMax 用
 * {@code <|channel|>thought}、Llama3 用 {@code <mm:think>}）。Java 层如果写死
 * {@code <think>}，换模型就会漏检或误判。llama.cpp 的
 * {@code common_chat_templates_apply()} 已经从 GGUF 内置模板推导出这些标签，
 * native 层通过 {@code {"type":"meta"}} 事件下发，本类负责承接。</p>
 *
 * <p>用法：</p>
 * <pre>
 *   case "meta":
 *       tagConfig = ThinkingTagConfig.fromJson(event);
 *       break;
 *   case "reasoning":
 *       ...
 * </pre>
 */
public final class ThinkingTagConfig {

    private static final String TAG = "ThinkingTagConfig";

    /** 模板未提供标签时使用的空配置（非思考模型 / 模板未声明思考段） */
    private static final ThinkingTagConfig EMPTY = new ThinkingTagConfig("", Collections.<String>emptyList());

    private final String startTag;
    private final List<String> endTags;

    private ThinkingTagConfig(String startTag, List<String> endTags) {
        this.startTag = startTag == null ? "" : startTag;
        List<String> copy = new ArrayList<>();
        if (endTags != null) {
            for (String t : endTags) {
                if (t != null && !t.isEmpty()) copy.add(t);
            }
        }
        this.endTags = Collections.unmodifiableList(copy);
    }

    /** 模板是否提供了可用的思考标签；false 表示不应做思考段识别/剥离 */
    public boolean isAvailable() {
        return !startTag.isEmpty() && !endTags.isEmpty();
    }

    public String getStartTag() {
        return startTag;
    }

    public List<String> getEndTags() {
        return endTags;
    }

    public static ThinkingTagConfig empty() {
        return EMPTY;
    }

    /**
     * 从 native meta 事件解析。任何异常都退化为 empty（不做剥离），不抛给调用方。
     */
    public static ThinkingTagConfig fromJson(JSONObject meta) {
        if (meta == null) return EMPTY;
        try {
            String start = meta.optString("thinking_start_tag", "");
            List<String> ends = new ArrayList<>();
            JSONArray arr = meta.optJSONArray("thinking_end_tags");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String t = arr.optString(i, "");
                    if (t != null && !t.isEmpty()) ends.add(t);
                }
            }
            ThinkingTagConfig cfg = new ThinkingTagConfig(start, ends);
            AILogger.i(TAG, "Thinking tags from template: start='" + start + "', "
                    + ends.size() + " end tag(s), available=" + cfg.isAvailable());
            return cfg;
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to parse meta event, thinking tags disabled: " + e.getMessage());
            return EMPTY;
        }
    }

    public static ThinkingTagConfig fromJson(String json) {
        if (json == null || json.isEmpty()) return EMPTY;
        try {
            return fromJson(new JSONObject(json));
        } catch (Exception e) {
            AILogger.w(TAG, "Invalid meta JSON: " + e.getMessage());
            return EMPTY;
        }
    }

    /**
     * 当前引擎的思考标签 —— **全局唯一来源**。
     *
     * <p>为什么需要统一（2026-10-07 审计定位）：本项目三条路径（llama.cpp / NPU / 在线）的思考标签
     * 原先各取各的 —— llama.cpp 走 {@code LlamaHelper.getThinkingTags()}（native 模板），
     * NPU 侧则在不同调用点分别写 {@code null} 或硬编码默认值，于是"思考/正文分流"的行为
     * 因入口而异，出现"有时分开、有时混在一起"。</p>
     *
     * <p>规则：</p>
     * <ol>
     *   <li>NPU：GenieX 的模板在 SDK 侧，llama.cpp 的 native 上下文不存在 → 取不到模板标签，
     *       使用与 {@code NpuEngineRouter} 发给 UI 的 meta 事件**一致**的默认标签，
     *       保证"上游分流用的标签"和"下游兜底用的标签"是同一套。</li>
     *   <li>其他引擎：用 native 模板提供的标签（{@code LlamaHelper.getThinkingTags()}），
     *       标签不可用时返回 empty（不做剥离），与既有行为一致。</li>
     * </ol>
     */
    public static ThinkingTagConfig forCurrentEngine() {
        try {
            if (com.oilquiz.app.ai.engine.NpuLlmChat.isEngineEnabled()) {
                // 与 NpuEngineRouter 的 meta 事件保持一致（Qwen 系默认）
                return fromJson("{\"thinking_start_tag\":\"<think>\","
                        + "\"thinking_end_tags\":[\"</think>\"]}");
            }
        } catch (Throwable ignored) {
            // NpuLlmChat 不可用时按 llama.cpp 处理
        }
        try {
            return com.oilquiz.app.ai.jni.LlamaHelper.getThinkingTags();
        } catch (Throwable t) {
            return EMPTY;
        }
    }

    /**
     * 剥离思考段（含标签），剩余内容作为正文。
     * 规则与 native {@code stripThinkTags()} 保持一致：
     * 多个结束标签取最早出现者；未闭合的思考块从开始标签剥到结尾。
     *
     * @return 剥离后的正文；模板无标签或输入为空时原样返回
     */
    public String stripThinking(String input) {
        if (input == null || input.isEmpty() || !isAvailable()) return input;
        StringBuilder out = new StringBuilder(input.length());
        int pos = 0;
        final int len = input.length();
        while (pos < len) {
            int start = input.indexOf(startTag, pos);
            if (start < 0) {
                out.append(input, pos, len);
                break;
            }
            // 开始标签之前的内容保留
            out.append(input, pos, start);
            // 找最早出现的任一结束标签
            int close = -1;
            int closeLen = 0;
            for (String tag : endTags) {
                int p = input.indexOf(tag, start + startTag.length());
                if (p >= 0 && (close < 0 || p < close)) {
                    close = p;
                    closeLen = tag.length();
                }
            }
            if (close < 0) {
                break;   // 未闭合：剥掉开始标签到结尾，视为思考残留
            }
            pos = close + closeLen;
        }
        return out.toString();
    }

    /**
     * 移除思考标签“标记符号”但保留标签之间的内容；用于渲染层兜底清理思考气泡里的残留标签。
     * 与 {@link #stripThinking(String)} 不同：stripThinking 删除整个思考段，本方法只去掉标记本身。
     * 模板不可用时返回原串（调用方可用旧的 &lt;think&gt; 正则兜底）。
     */
    public String removeTagMarkers(String input) {
        if (input == null || input.isEmpty() || !isAvailable()) return input;
        String s = input;
        s = s.replace(startTag, "");
        for (String tag : endTags) {
            s = s.replace(tag, "");
        }
        return s;
    }

    @Override
    public String toString() {
        return "ThinkingTagConfig{start='" + startTag + "', ends=" + endTags + "}";
    }
}

package com.oilquiz.app.ai.chat;

import com.oilquiz.app.ai.chat.component.ComponentData;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ChatMessage#contentRoundBounds} 的归一/修复工具（2026-10-21）。
 *
 * <p>把"轮次边界"从"一个没人校验的 List&lt;Integer&gt;"收成三条不变量：</p>
 * <ul>
 *   <li>数量 = tool_call 数（一个工具调用一条边界）；</li>
 *   <li>值单调不减、且落在 [0, content.length()]；</li>
 *   <li>与 {@link ChatMessage#contentRoundToolIds} 一一对应（旧数据没有 id 时按
 *       tool_call 顺序补齐）。</li>
 * </ul>
 *
 * <p>另外提供 {@link #remapAfterRemoval}：正文在完成时剥离 thinking 标签 / trim 后，
 * 用旧偏移算新偏移，避免"bound 还在旧坐标系里"。</p>
 */
public final class RoundBounds {

    private RoundBounds() {
    }

    public static void normalizeAll(List<ChatMessage> messages) {
        if (messages == null) return;
        for (ChatMessage m : messages) normalize(m);
    }

    /**
     * 归一一条消息的轮次边界。会就地把修复后的列表写回 message；
     * 旧历史（bounds 缺失/去重/越界）在加载或渲染前调用一次即可。
     */
    public static void normalize(ChatMessage message) {
        if (message == null) return;
        List<String> toolIds = toolCallIds(message);
        int toolCount = toolIds.size();

        List<Integer> bounds = message.contentRoundBounds;
        if (bounds == null) bounds = new ArrayList<>();
        List<String> ids = message.contentRoundToolIds;
        if (ids == null) ids = new ArrayList<>();

        int contentLen = message.content != null ? message.content.length() : 0;
        int count = toolCount > 0 ? toolCount : bounds.size();
        List<Integer> outBounds = new ArrayList<>(count);
        List<String> outIds = new ArrayList<>(count);
        int prev = 0;
        for (int i = 0; i < count; i++) {
            Integer raw = i < bounds.size() ? bounds.get(i) : null;
            int offset = raw == null ? prev : raw;
            offset = Math.max(prev, Math.max(0, offset));
            if (message.content != null) offset = Math.min(offset, contentLen);
            prev = offset;
            outBounds.add(offset);

            String id = i < ids.size() ? ids.get(i) : null;
            if ((id == null || id.isEmpty()) && i < toolIds.size()) id = toolIds.get(i);
            outIds.add(id != null ? id : "");
        }
        message.contentRoundBounds = outBounds;
        message.contentRoundToolIds = outIds;
    }

    /**
     * 正文发生一次区间删除 + 前导 trim 后，把旧坐标系的 bounds 映射到新 content。
     *
     * @param removedStart 删除区间起点（旧内容坐标）
     * @param removedEnd   删除区间终点（旧内容坐标，exclusive）
     * @param leadingTrim  删除后整段内容被 trim 掉的前导空白字符数
     * @param newLength    改写后的 content 长度
     */
    public static List<Integer> remapAfterRemoval(List<Integer> bounds,
                                                  int removedStart, int removedEnd,
                                                  int leadingTrim, int newLength) {
        if (bounds == null) return null;
        int removeLen = Math.max(0, removedEnd - removedStart);
        int lead = Math.max(0, leadingTrim);
        int max = Math.max(0, newLength);
        List<Integer> out = new ArrayList<>(bounds.size());
        for (Integer b : bounds) {
            int old = b == null ? 0 : b;
            int mapped;
            if (old <= removedStart) {
                mapped = old - lead;
            } else if (old >= removedEnd) {
                mapped = old - lead - removeLen;
            } else {
                mapped = removedStart - lead;
            }
            out.add(Math.max(0, Math.min(mapped, max)));
        }
        return out;
    }

    private static List<String> toolCallIds(ChatMessage message) {
        List<String> ids = new ArrayList<>();
        if (message.components == null) return ids;
        for (ComponentData c : message.components) {
            if (c == null || !"tool_call".equals(c.type)) continue;
            String id = c.props != null ? c.props.optString("toolCallId", "") : "";
            ids.add(id != null ? id : "");
        }
        return ids;
    }
}

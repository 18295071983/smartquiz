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
        // id 两遍解析：先保留仍然对得上组件的 id（顺序真值优先，绝不重排），
        // 再把空槽与**失效槽**按组件顺序补一个未认领的 id。
        // 失效槽是坏数据留下的（工具卡组件被替换/丢失后 id 还写在历史里）：
        // 留着它，恢复出的 tool_call part 既匹配不到组件又没有名字，会渲一张空白工具行，
        // 而真正存在的组件因为没人认领被补到正文末尾——顺序就错了。
        java.util.Set<String> known = new java.util.HashSet<>(toolIds);
        java.util.Set<Integer> claimed = new java.util.HashSet<>();
        List<String> original = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String stored = i < ids.size() && ids.get(i) != null ? ids.get(i) : "";
            original.add(stored);
            // 只在"确实有工具组件可比对"时才判定失效：toolCount==0 时历史里的 id 是
            // legacy 手工路径留的槽位记号（消息可能压根没有 tool_call 组件），照旧原样保留。
            boolean usable = !stored.isEmpty() && (toolCount == 0 || known.contains(stored));
            if (usable) {
                outIds.add(stored);
                if (known.contains(stored)) claimed.add(toolIds.indexOf(stored));
            } else {
                outIds.add(null);
            }
        }
        int cursor = 0;
        for (int i = 0; i < count; i++) {
            if (outIds.get(i) != null) continue;
            while (cursor < toolIds.size() && claimed.contains(cursor)) cursor++;
            if (cursor >= toolIds.size()) {
                // 没有未认领的组件可顶：维持历史原值（可能是空串，也可能就是那个失效 id），
                // 不凭空造信息，也不抹掉 legacy 写过的东西。
                outIds.set(i, original.get(i));
                continue;
            }
            String cand = toolIds.get(cursor);
            claimed.add(cursor);
            cursor++;
            // 组件自己也没有 id：留给 AssistantTurn 的"按序就地归位"配对
            outIds.set(i, cand == null ? original.get(i) : cand);
        }

        int prev = 0;
        for (int i = 0; i < count; i++) {
            Integer raw = i < bounds.size() ? bounds.get(i) : null;
            int offset = raw == null ? prev : raw;
            offset = Math.max(prev, Math.max(0, offset));
            if (message.content != null) offset = Math.min(offset, contentLen);
            prev = offset;
            outBounds.add(offset);
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

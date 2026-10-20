package com.oilquiz.app.ai.chat.parts;

import com.oilquiz.app.ai.chat.turn.AssistantTurn;

import java.util.ArrayList;
import java.util.List;

/**
 * 按 part 类型解析渲染器的注册表（parts 架构升级 P2，2026-10-21）。
 *
 * <p>解析按注册顺序匹配第一个 {@link PartRenderer#canRender} 为真的 renderer，允许
 * 特定 renderer 覆盖通用 renderer（例如未来的 data/产物卡）。P2 只注册 text/reasoning；
 * tool_call/tool_result/data 留到 P3。</p>
 */
public final class PartRendererRegistry {

    private final List<PartRenderer> renderers = new ArrayList<>();

    /** 注册一个 renderer；null 忽略，返回自身便于链式调用。 */
    public PartRendererRegistry register(PartRenderer renderer) {
        if (renderer != null) {
            renderers.add(renderer);
        }
        return this;
    }

    /** 解析某个 part 的渲染器；无匹配返回 null（引擎据此跳过该 part）。 */
    public PartRenderer resolve(AssistantTurn.Part part) {
        if (part == null) {
            return null;
        }
        for (PartRenderer r : renderers) {
            if (r != null && r.canRender(part)) {
                return r;
            }
        }
        return null;
    }

    public int size() {
        return renderers.size();
    }

    /** P2 默认注册表：text + reasoning + error。tool/data 留 P3。 */
    public static PartRendererRegistry createDefault() {
        return new PartRendererRegistry()
                .register(new TextPartRenderer())
                .register(new ReasoningPartRenderer())
                .register(new ErrorPartRenderer());
    }
}

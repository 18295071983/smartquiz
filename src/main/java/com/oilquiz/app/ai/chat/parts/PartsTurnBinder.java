package com.oilquiz.app.ai.chat.parts;

import android.view.ViewGroup;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;

/**
 * 一条 AI 消息的 parts → 视图 绑定器（parts 架构升级 P3a，2026-10-21）。
 *
 * <p>把 {@link TurnPartsHost} 增量引擎封成一个"给一条消息、一个容器、一个宿主"就能渲染的
 * 消费端入口：ChatAdapter 接线时（P3b）每 holder 持一个本类实例，绑定/复用/回收都只调这里，
 * 引擎的 viewById+fingerprint 复用随之保留。</p>
 *
 * <p>parts 真值来源：优先 {@code message.assistantTurn}（canonical），缺则
 * {@link AssistantTurn#fromMessage} 现场从 content/roundSnapshots/components 重建。</p>
 *
 * <p>本类只依赖抽象（registry/host/ViewGroup），不引用 ChatAdapter，可在 JVM/Robolectric
 * 用假 renderer 独立验证；flag=false 时 ChatAdapter 根本不会构造它。</p>
 */
public final class PartsTurnBinder {

    private final TurnPartsHost engine;

    public PartsTurnBinder(ViewGroup container, PartRendererRegistry registry, PartHost host) {
        this.engine = new TurnPartsHost(container, registry, host);
    }

    /** 绑定一条消息的 parts 到容器（增量复用）。msg 为 null 等价于清空。 */
    public void bind(ChatMessage msg) {
        engine.bind(partsOf(msg));
    }

    /** holder 回收/消息切换时清空（释放所有视图）。 */
    public void clear() {
        engine.bind(null);
    }

    public int renderedCount() {
        return engine.renderedCount();
    }

    private static java.util.List<AssistantTurn.Part> partsOf(ChatMessage msg) {
        if (msg == null) {
            return null;
        }
        // 复用现网 canonical（含引擎写入的 state），但操作其副本，避免污染 msg.assistantTurn；
        // 再把内联 component 标记展开成 text+data 交错 part。
        AssistantTurn base = msg.assistantTurn != null
                ? msg.assistantTurn.copy()
                : AssistantTurn.fromMessage(msg);
        AssistantTurn.expandInline(base);
        return base != null ? base.parts : null;
    }
}

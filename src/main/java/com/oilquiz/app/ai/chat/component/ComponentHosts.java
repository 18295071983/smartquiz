package com.oilquiz.app.ai.chat.component;

/**
 * 组件可用的"页面能力"（2026-11-14）。
 *
 * <p>背景：卡片渲染器只拿得到 {@code Context}，想"点一下跳到那条消息""展开某个子代理的轨迹"
 * 这类动作就做不到 —— 它们需要聊天页的数据（消息列表、RecyclerView）。</p>
 *
 * <p>为什么用静态挂载而不是改 {@link ChatComponent#createView} 签名：组件实例是
 * {@link ComponentRegistry} 里的**单例**，给实例塞 host 会在多条消息之间串；
 * 而改 {@code createView(context, data)} 的签名要动 40 个实现类和 6 处渲染工厂，
 * 收益不成比例。这里的 host 是"当前前台聊天页"，切页时由页面自己
 * {@link #set} / {@link #clear}，语义与 {@code ComponentActions} 的静态回调一致。</p>
 */
public final class ComponentHosts {

    /** 聊天页提供给卡片的动作（都可为空实现，历史恢复/弹窗预览时没有宿主） */
    public interface Host {
        /** 滚动到指定本地消息（按 {@code ChatMessage.id}） */
        void scrollToMessage(String messageId);

        /** 滚动到最后一条消息（"正在电脑上执行/排队中"的气泡通常就在末尾） */
        void scrollToLast();

        /** 滚动到某个子代理的轨迹卡（按 {@code subagent_card.props.childId}） */
        void openSubagent(String childId);
    }

    /** 无宿主时的空实现：所有动作静默忽略，卡片退化成纯展示 */
    public static final Host NONE = new Host() {
        @Override public void scrollToMessage(String messageId) { }

        @Override public void scrollToLast() { }

        @Override public void openSubagent(String childId) { }
    };

    private static volatile Host current = NONE;

    private ComponentHosts() {
    }

    public static void set(Host host) {
        current = host != null ? host : NONE;
    }

    public static void clear() {
        current = NONE;
    }

    public static Host get() {
        return current;
    }
}

package com.oilquiz.app.ai.agent.software.engine;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 本地 Agent「停止链路」不变量（2026-10-25）。
 *
 * <p>背景：这条链路曾经整条是断的，而且每一段单独看都"像是对的"——
 * <ol>
 *   <li>{@code AgentRouter.isGenerating()} 只看在线引擎，而 {@code AIChatActivity}
 *       里 6 处 {@code if (agentChatHandler.isGenerating()) agentChatHandler.cancel();}
 *       全挂在这个门控上 → 本地 Agent 运行时取消入口根本不被调用；</li>
 *   <li>{@code AgentSoftwareLayer.cancel()} 只打断 native 生成、并把 {@code isProcessing}
 *       立即置回 false → 循环线程还在收尾却被当成空闲，再按一次停止反而什么都不做；</li>
 *   <li>{@code AgentLoopEngine} 内部没有取消标志，取消只能靠 native 让本次生成返回 null
 *       间接感知；break 之后统一退出路径只要 {@code toolCallCount > 0} 就仍然
 *       {@code generateFinalAnswer()} 再解码一轮 → "点了停止，手机反而更卖力地跑"；</li>
 *   <li>重入时软件层静默 {@code return}，不给任何终态回调 → 界面永久停在"⏳ 模型处理中…"。</li>
 * </ol>
 *
 * <p>这里用「反射核 API 形状 + 源码文本钉住关键分支」两层保护，风格同
 * {@link LocalThinToolsTest}：真正的行为验证要真机点停止按钮，但**结构不能回退**——
 * 一旦有人把 {@code generateFinalAnswer} 挪回无条件执行、或把 {@code isGenerating()}
 * 改回只看在线引擎，这里就该红。
 */
public class LocalAgentCancelPathTest {

    private static final String ENGINE_PATH =
            "src/main/java/com/oilquiz/app/ai/agent/software/engine/AgentLoopEngine.java";
    private static final String LAYER_PATH =
            "src/main/java/com/oilquiz/app/ai/agent/software/AgentSoftwareLayer.java";
    private static final String HANDLER_PATH =
            "src/main/java/com/oilquiz/app/ai/chat/AgentChatHandler.java";
    private static final String ROUTER_PATH =
            "src/main/java/com/oilquiz/app/ai/agent/AgentRouter.java";
    private static final String ACTIVITY_PATH =
            "src/main/java/com/oilquiz/app/ui/activity/AIChatActivity.java";

    /**
     * 读源码并统一换行。
     *
     * <p>仓库里 Java 源文件是 CRLF，跨行锚点若直接用 {@code \n} 拼就永远匹配不上
     * ——测试会在"看起来完全正确"的断言上假红，所以读入即归一化。
     */
    private static String readSrc(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)
                .replace("\r\n", "\n").replace("\r", "\n");
    }

    /** 读源码并把连续空白压成单个空格，避免缩进/换行改动把断言变成误红 */
    private static String flat(String path) throws Exception {
        return readSrc(path).replaceAll("\\s+", " ");
    }

    /** 取某个方法的源码体（从签名到下一个同级/外层方法签名），用于"只在该方法内断言" */
    private static String methodBody(String src, String signature) {
        int start = src.indexOf(signature);
        assertTrue("找不到方法签名: " + signature, start >= 0);
        int from = start + signature.length();
        int end = src.length();
        // 4 空格（顶层类成员）与 8 空格（嵌套类成员，如 AIChatActivity.AgentCallbackImpl）
        // 都要当作边界，否则体一直延伸到文件尾，断言会误吃别处的同名字符串
        for (String marker : new String[]{"\n    public ", "\n    private ",
                "\n        public ", "\n        private ", "\n        @Override"}) {
            int i = src.indexOf(marker, from);
            if (i >= 0) end = Math.min(end, i);
        }
        return src.substring(start, end);
    }

    // ===== 1) 引擎：取消 API 必须存在且是公开可调用 =====

    @Test
    public void engineExposesPublicCancelApi() throws Exception {
        Field f = AgentLoopEngine.class.getDeclaredField("cancelRequested");
        assertNotNull(f);
        assertTrue("取消标志必须是 AtomicBoolean（跨线程置位/读取）",
                java.util.concurrent.atomic.AtomicBoolean.class.equals(f.getType()));
        Method cancel = AgentLoopEngine.class.getMethod("cancel");
        Method isCancelled = AgentLoopEngine.class.getMethod("isCancelled");
        assertTrue(cancel.getReturnType() == void.class);
        assertTrue(isCancelled.getReturnType() == boolean.class);
        assertTrue("cancel() 必须 public：AgentSoftwareLayer 要从另一层调用它",
                Modifier.isPublic(cancel.getModifiers()));
    }

    @Test
    public void runResetsCancelFlagBeforeTheLoop() throws Exception {
        String src = flat(ENGINE_PATH);
        int reset = src.indexOf("cancelRequested.set(false);");
        int firstRead = src.indexOf("if (cancelRequested.get()) {");
        assertTrue("run() 入口必须复位取消标志（引擎实例常驻，不复位会让上一轮的停止粘住下一轮）", reset >= 0);
        assertTrue("取消标志必须在每轮循环开头被检查", firstRead >= 0);
        assertTrue("复位必须发生在循环检查之前", reset < firstRead);
    }

    // ===== 2) 引擎：取消后不得再发起任何解码（核心那条"停止反而多跑一轮"） =====

    @Test
    public void cancelledRunSkipsFinalAnswerGeneration() throws Exception {
        String src = flat(ENGINE_PATH);
        assertTrue("统一退出路径必须先算出 cancelled",
                src.contains("final boolean cancelled = cancelRequested.get();"));
        // generateFinalAnswer 必须挂在 else 分支上，而不是无条件的 if (toolCallCount > 0)
        assertTrue("最终答必须只在未取消时生成（必须挂在 cancelled 的 else 分支）",
                src.contains("} else if (toolCallCount > 0) { GenerateResult finalGen = generateFinalAnswer"));
        // 反向钉：旧的无条件写法是"补合成结果那句之后直接 if (toolCallCount > 0)"。
        // 注意不能只断言 "if (toolCallCount > 0) {…" 不存在——它作为 "} else if (…" 的子串永远存在。
        assertFalse("不得再有未受取消门控的 generateFinalAnswer 调用",
                src.contains("; if (toolCallCount > 0) { GenerateResult finalGen = generateFinalAnswer"));
        // 取消分支不注入兜底话术
        assertTrue("取消分支必须先于兜底 return",
                src.contains("cancelledResponse.cancelled = true;"));
    }

    @Test
    public void toolWaitsAlsoHonorCancel() throws Exception {
        String src = flat(ENGINE_PATH);
        // 并行等待 + 单工具超时等待都要看取消标志，否则点停止最坏要白等一个 TOOL_TIMEOUT_MS
        int waits = 0;
        for (int i = src.indexOf("&& !cancelRequested.get()"); i >= 0;
                 i = src.indexOf("&& !cancelRequested.get()", i + 1)) {
            waits++;
        }
        assertTrue("并行与串行两处工具等待都要响应取消（当前 " + waits + " 处）", waits >= 2);
        assertTrue("取消时不能把未跑完的工具报成超时（用户会以为工具坏了）",
                src.contains("已取消，该工具未执行完成"));
    }

    // ===== 3) 软件层：cancel 必须打到引擎，并且不许提前谎报空闲 =====

    @Test
    public void softwareLayerCancelReachesEngineAndKeepsBusyFlag() throws Exception {
        String src = readSrc(LAYER_PATH);
        String cancel = methodBody(src, "public void cancel()");
        assertTrue("cancel() 必须置引擎取消标志", cancel.contains("loopEngine.cancel()"));
        assertFalse("cancel() 不得再把 isProcessing 置回 false："
                        + "循环还在收尾却被当成空闲，会让上层再按一次停止什么也不做，"
                        + "并允许新消息与上一轮残留回调混用同一个 callback",
                cancel.contains("isProcessing.set(false)"));
        assertTrue("NPU 与 llama.cpp 两个停止入口都要保留",
                cancel.contains("NpuLlmChat.stopGenerate()") && cancel.contains("LlamaHelper.stopGeneration()"));
    }

    @Test
    public void reentrantSendNotifiesBusyInsteadOfSilentDrop() throws Exception {
        String src = readSrc(LAYER_PATH);
        String process = methodBody(src,
                "public void processMessage(String userMessage, boolean enableThinking,");
        assertTrue("重入必须回调 onBusy（旧实现静默 return → UI 永久挂『处理中』）",
                process.contains("cb.onBusy(userMessage)"));
        assertTrue("重入分支不得直接 return 而不给终态",
                process.contains("return;") && process.contains("onBusy"));
        assertTrue("AgentCallback 需声明 onBusy 默认方法",
                src.contains("default void onBusy(String userMessage) {}"));
        // 取消轮仍要作为完成事件交付（UI 沿用既有的"已停止"标注，不另造终态）
        assertTrue("取消轮仍需透出，便于日志核对",
                src.contains("response.cancelled"));
    }

    // ===== 4) 上层门控：isGenerating 必须覆盖本地软件层（这条是断链的根因） =====

    @Test
    public void handlerIsGeneratingCoversLocalLayer() throws Exception {
        String src = readSrc(HANDLER_PATH);
        String body = methodBody(src, "public boolean isGenerating()");
        assertTrue("isGenerating() 必须包含在线引擎判断", body.contains("engine.isGenerating()"));
        assertTrue("isGenerating() 必须包含本地软件层判断："
                        + "Activity 里 6 处 cancel() 都挂在这个门上，漏掉本地层就等于"
                        + "『本地 Agent 一跑就停不下来』",
                body.contains("softwareLayer.isProcessing()"));
    }

    @Test
    public void routerIsGeneratingStaysOnlineOnlyAndHandlerCompensates() throws Exception {
        // AgentRouter 只懂在线引擎——这是事实，不是 bug；补偿必须发生在 AgentChatHandler 层。
        // 若哪天有人把 router 也改成覆盖本地，至少这里会提醒核对两边是否重复取消。
        String router = flat(ROUTER_PATH);
        assertTrue("AgentRouter.isGenerating 仍只反映在线引擎",
                router.contains("public boolean isGenerating() { return onlineEngine != null && onlineEngine.isGenerating(); }"));
        String handler = readSrc(HANDLER_PATH);
        assertTrue("补偿必须留在 AgentChatHandler（不要在两处各改一半）",
                handler.contains("softwareLayer.isProcessing()"));
    }

    @Test
    public void handlerForwardsBusyToUi() throws Exception {
        String src = readSrc(HANDLER_PATH);
        assertTrue("AgentChatCallback 需声明 onAgentBusy 默认方法",
                src.contains("default void onAgentBusy(String userMessage) {}"));
        assertTrue("软件层 onBusy 必须转发给 UI", src.contains("callback.onAgentBusy(userMessage)"));
    }

    // ===== 5) UI：停止不得被标成"执行完成"，空正文不得写进历史 =====

    /**
     * AIChatActivity 里有 7 处 {@code public void onComplete(String fullText)}
     * （不同匿名回调），必须用"方法名 + 首行注释"定位到 AgentCallbackImpl 那一个，
     * 否则断言会打在别的 onComplete 上（假绿）。
     */
    private static final String AGENT_ON_COMPLETE =
            "public void onComplete(String fullText) {\n"
                    + "            // 幂等：只处理第一次完成事件（重复/迟到回调直接忽略）";

    @Test
    public void cancelledRunIsLabelledStoppedNotCompleted() throws Exception {
        String src = readSrc(ACTIVITY_PATH);
        String onComplete = methodBody(src, AGENT_ON_COMPLETE);
        assertTrue("完成文案必须按 userStoppedCurrentGeneration 分叉",
                onComplete.contains("stoppedByUser ? \"⏹ 已停止\" : \"✅ 执行完成\""));
        assertFalse("不得再无条件写『✅ 执行完成』（用户报的『上下对不上』）",
                onComplete.contains("updateAgentStatusBar(\"✅ 执行完成\", false)"));
    }

    @Test
    public void blankCancelledAnswerIsNotWrittenIntoLocalHistory() throws Exception {
        String src = readSrc(ACTIVITY_PATH);
        String onComplete = methodBody(src, AGENT_ON_COMPLETE);
        assertTrue("空正文不得 appendExternalChatTurn（否则本地历史多出一条空 assistant 轮）",
                onComplete.contains("fullText != null && !fullText.trim().isEmpty()"));
    }

    @Test
    public void busyPathRollsBackPendingAgentMessage() throws Exception {
        String src = readSrc(ACTIVITY_PATH);
        assertTrue("发送前必须暂存上一条待回写消息",
                src.contains("lastAgentUserMessagePrev = lastAgentUserMessage;"));
        String onBusy = methodBody(src, "public void onAgentBusy(String userMessage)");
        assertTrue("被拒的发送必须回滚待回写标记，否则上一轮完成时会把这条被丢弃的消息当成问题写进历史",
                onBusy.contains("lastAgentUserMessage = lastAgentUserMessagePrev;"));
        assertTrue("被拒的发送必须给用户明确提示（旧行为是静默丢弃 → UI 永久挂『处理中』）",
                onBusy.contains("showToast("));
        // 这里刻意不动消息列表：上一条的回答仍会流进当前气泡并落地，此时若去收口
        // 会连带把尚未定稿的气泡一起带掉（本文件有 RecyclerView 一致性崩溃的历史）
        assertFalse("被拒路径不应做消息列表增删",
                onBusy.contains("handleGenerationError(") || onBusy.contains("chatHistory.remove("));
    }
}

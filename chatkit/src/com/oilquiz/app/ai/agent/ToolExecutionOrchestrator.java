package com.oilquiz.app.ai.agent;

import android.content.Context;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.tool.AIToolResult;

import java.util.List;
import java.util.Map;

/**
 * 工具执行编排器（全局可复用，逻辑层）。
 *
 * 从 AIChatActivity 工具执行链路抽取：预检（ToolPreChecker）→ 执行（AIToolManager）
 * → 成功解读（ToolResultInterpreter）/ 失败智能恢复（ToolErrorRecovery）→ 重试。
 * 渲染与提示通过 {@link Host} 回调，不依赖页面。
 */
public class ToolExecutionOrchestrator {

    /** 宿主回调（UI 渲染） */
    public interface Host {
        void runOnUi(Runnable r);
        void onAddSystemMessage(String text);
        void onScrollToBottom();
        void onUpdateToolCallResult(int msgPos, boolean success, String resultStr);
        void onAddAiMessage(String content);
        void onWriteInterpretedToMessage(int msgPos, String content);
        void onInjectInterpretToLocalContext(String content);
        void onUpdateSystemMessageText(int pos, String text);
        /** 宿主弹用户补参输入框（缺失参数需用户输入时） */
        void onPromptUserForMissingParams(String toolName, Map<String, Object> params, int msgPos,
                                          List<ToolErrorRecovery.MissingParam> missing,
                                          int retryCount, Runnable onComplete);
    }

    private final Context context;
    private final Host host;

    public ToolExecutionOrchestrator(Context context, Host host) {
        this.context = context.getApplicationContext();
        this.host = host;
    }

    /** 引导执行入口：dismiss 弹窗 + 工具卡片占位 + 预检执行 */
    public void runGuideToolExecution(Object dialog,
                                      String toolName, Map<String, Object> execParams) {
        if (dialog instanceof android.app.Dialog) ((android.app.Dialog) dialog).dismiss();
        String paramsStr = execParams == null || execParams.isEmpty()
                ? "{}" : new com.google.gson.Gson().toJson(execParams);
        int msgPos = hostOnAddToolCallMessage(toolName, paramsStr);
        preCheckThenExecute(toolName, execParams, msgPos, 0, null);
    }

    /** 宿主提供工具卡片插入（默认由消息列表实现） */
    protected int hostOnAddToolCallMessage(String toolName, String paramsStr) {
        return -1;
    }

    /** 预检 → 执行（预知性补充缺失参数） */
    public void preCheckThenExecute(final String toolName,
                                    final Map<String, Object> params,
                                    final int msgPos,
                                    final int retryCount,
                                    final Runnable onComplete) {
        ToolPreChecker.preCheck(context, toolName, params, new ToolPreChecker.PreCheckCallback() {
            @Override
            public void onReady(Map<String, Object> p, String autoFilledInfo) {
                host.runOnUi(() -> {
                    if (autoFilledInfo != null && !autoFilledInfo.isEmpty()) {
                        host.onAddSystemMessage("🔧 已预检补充: " + autoFilledInfo);
                        host.onScrollToBottom();
                    }
                    executeToolWithRecovery(toolName, p, msgPos, retryCount, onComplete);
                });
            }

            @Override
            public void onNeedUserInput(List<ToolErrorRecovery.MissingParam> missing) {
                host.runOnUi(() -> host.onPromptUserForMissingParams(
                        toolName, params, msgPos, missing, retryCount, onComplete));
            }
        });
    }

    /** 执行工具，失败时智能恢复重试（最多 2 次：自动补参 → 用户输入） */
    public void executeToolWithRecovery(final String toolName,
                                        final Map<String, Object> params,
                                        final int msgPos,
                                        final int retryCount,
                                        final Runnable onComplete) {
        if (retryCount > 0) {
            host.onAddSystemMessage("🔧 检测到问题，正在自动修复并重试(" + retryCount + "/2)...");
            host.onScrollToBottom();
        }
        new Thread(() -> {
            final AIToolResult result = AIToolManager.getInstance(context).executeTool(toolName, params);
            host.runOnUi(() -> {
                final boolean success = result != null && result.isSuccess();
                if (success) {
                    Object rawResult = result.getResult();
                    String resultStr = ToolResultInterpreter.formatForUi(toolName, rawResult);
                    host.onUpdateToolCallResult(msgPos, true, resultStr);
                    host.onAddSystemMessage("✅ 工具执行完成");
                    host.onScrollToBottom();
                    try {
                        ToolResultStore.save(toolName, params, rawResult, 0);
                    } catch (Exception ignore) { }
                    host.onAddSystemMessage("💡 正在AI解读结果...");
                    host.onScrollToBottom();
                    final int progressMsgPos = -1; // 宿主可在 onAddSystemMessage 回调里记录
                    final Runnable doContinue = onComplete;
                    final String fallback = resultStr;
                    ToolResultInterpreter.interpret(context, toolName, rawResult,
                            new ToolResultInterpreter.InterpretCallback() {
                                @Override
                                public void onInterpreted(String summary) {
                                    boolean hasInterpret = summary != null && !summary.isEmpty();
                                    String content = hasInterpret ? summary : fallback;
                                    host.onWriteInterpretedToMessage(msgPos, content);
                                    if (hasInterpret) {
                                        host.onAddAiMessage(content);
                                        host.onInjectInterpretToLocalContext(content);
                                    } else {
                                        host.onUpdateSystemMessageText(-1, "ℹ️ 无可用AI模型解读，已展示模板结果");
                                    }
                                    host.onScrollToBottom();
                                    if (doContinue != null) doContinue.run();
                                }

                                @Override
                                public void onError(String error) {
                                    host.onWriteInterpretedToMessage(msgPos, fallback);
                                    host.onUpdateSystemMessageText(-1, "ℹ️ AI解读失败，已展示模板结果");
                                    host.onScrollToBottom();
                                    if (doContinue != null) doContinue.run();
                                }
                            });
                } else if (retryCount < 2) {
                    String error = result != null ? result.getErrorMessage() : "未知错误";
                    attemptToolRecovery(toolName, params, msgPos, error, retryCount, onComplete);
                } else {
                    String error = result != null ? result.getErrorMessage() : "未知错误";
                    host.onUpdateToolCallResult(msgPos, false, error);
                    host.onAddSystemMessage("❌ 工具执行失败: " + error);
                    host.onScrollToBottom();
                    if (onComplete != null) onComplete.run();
                }
            });
        }).start();
    }

    /** 智能恢复：分析错误原因，自动补参或弹用户输入 */
    public void attemptToolRecovery(final String toolName,
                                    final Map<String, Object> params,
                                    final int msgPos,
                                    final String errorMsg,
                                    final int retryCount,
                                    final Runnable onComplete) {
        List<ToolErrorRecovery.MissingParam> missing =
                ToolErrorRecovery.analyzeMissingParams(toolName, errorMsg, params);
        if (missing.isEmpty()) {
            host.onUpdateToolCallResult(msgPos, false, errorMsg);
            host.onAddSystemMessage("❌ 工具执行失败: " + errorMsg);
            host.onScrollToBottom();
            if (onComplete != null) onComplete.run();
            return;
        }
        StringBuilder descSb = new StringBuilder("🔧 检测到缺失参数: ");
        for (int i = 0; i < missing.size(); i++) {
            if (i > 0) descSb.append("、");
            descSb.append(missing.get(i).description);
        }
        host.onAddSystemMessage(descSb.toString());
        host.onScrollToBottom();

        if (ToolErrorRecovery.allAutoFillable(missing)) {
            host.onAddSystemMessage("📍 正在自动获取...");
            host.onScrollToBottom();
            autoFillMissingParams(missing, params, () ->
                    executeToolWithRecovery(toolName, params, msgPos, retryCount + 1, onComplete));
        } else {
            host.onPromptUserForMissingParams(toolName, params, msgPos, missing, retryCount, onComplete);
        }
    }

    /** 自动获取缺失参数（定位/时间），补充到 params */
    public void autoFillMissingParams(final List<ToolErrorRecovery.MissingParam> missing,
                                      final Map<String, Object> params,
                                      final Runnable onComplete) {
        if (ToolErrorRecovery.needsLocation(missing)) {
            ToolContextProvider.getCurrentLocation(context, new ToolContextProvider.LocationCallback() {
                @Override
                public void onLocationReady(String city, double lat, double lon) {
                    if (!ToolParamResolver.hasParamValue(params, "city")) params.put("city", city);
                    if (!ToolParamResolver.hasParamValue(params, "lat")) params.put("lat", lat);
                    if (!ToolParamResolver.hasParamValue(params, "lon")) params.put("lon", lon);
                    host.onAddSystemMessage("📍 已自动获取位置: " + city);
                    host.onScrollToBottom();
                    ToolParamResolver.fillTimeParams(missing, params);
                    onComplete.run();
                }

                @Override
                public void onLocationFailed(String error) {
                    host.onAddSystemMessage("⚠️ 自动定位失败: " + error + "，使用默认城市北京");
                    if (!ToolParamResolver.hasParamValue(params, "city")) params.put("city", "北京");
                    if (!ToolParamResolver.hasParamValue(params, "lat")) params.put("lat", 39.9042);
                    if (!ToolParamResolver.hasParamValue(params, "lon")) params.put("lon", 116.4074);
                    host.onScrollToBottom();
                    ToolParamResolver.fillTimeParams(missing, params);
                    onComplete.run();
                }
            });
        } else {
            ToolParamResolver.fillTimeParams(missing, params);
            onComplete.run();
        }
    }
}

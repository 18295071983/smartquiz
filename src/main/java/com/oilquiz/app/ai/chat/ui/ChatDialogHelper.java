package com.oilquiz.app.ai.chat.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.util.Log;
import android.widget.EditText;

import com.oilquiz.app.ai.chat.ChatMessage;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 管理 AI 聊天页面的所有对话框和导出逻辑。
 * 从 AIChatActivity 中提取的独立模块。
 */
public class ChatDialogHelper {

    private static final String TAG = "ChatDialogHelper";

    public interface Callback {
        void onShowToast(String message);
        void onAddSystemMessage(String message);
        void onAddAIMessage(String content);
        void onClearChat();
    }

    private final Activity activity;
    private final Callback callback;

    public ChatDialogHelper(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
    }

    /**
     * 处理消息动作（复制、重新生成、新对话等）
     */
    public void handleAction(ChatMessage.Action action, List<ChatMessage> chatHistory) {
        switch (action.type) {
            case COPY:
                if (action.content != null) {
                    copyToClipboard("AI Message", action.content);
                    callback.onShowToast("已复制");
                }
                break;
            case NEW_CHAT:
                callback.onClearChat();
                callback.onAddSystemMessage("已开始新对话");
                break;
            case LIKE:
                callback.onShowToast("感谢您的喜欢！");
                break;
            case DISLIKE:
                callback.onShowToast("我们会努力改进！");
                break;
            case SHOW_HELP:
                handleHelpCommand();
                break;
            case VIEW_TOOL_DETAILS:
                showToolDetailsDialog(action.messageId, chatHistory);
                break;
            case EXPORT_SUMMARY:
                exportSummary(action.messageId, chatHistory);
                break;
            case SHOW_GUIDE:
                showGuideDialog();
                break;
            case REPORT_ERROR:
                String errorMsg = action.content != null ? action.content : "未知错误";
                callback.onAddSystemMessage("已收到错误报告: " + errorMsg);
                callback.onShowToast("错误已报告，感谢您的反馈！");
                break;
        }
    }

    /**
     * 显示工具调用详情对话框
     */
    public void showToolDetailsDialog(String messageId, List<ChatMessage> chatHistory) {
        if (messageId == null) {
            callback.onShowToast("未找到工具调用信息");
            return;
        }

        ChatMessage toolMessage = null;
        for (ChatMessage msg : chatHistory) {
            if (messageId.equals(msg.id)) {
                toolMessage = msg;
                break;
            }
        }

        if (toolMessage == null || toolMessage.toolCallInfo == null) {
            callback.onShowToast("未找到工具调用详情");
            return;
        }

        ChatMessage.ToolCallInfo info = toolMessage.toolCallInfo;
        StringBuilder details = new StringBuilder();
        details.append("\uD83D\uDCCB **工具调用详情**\n\n");
        details.append("**工具名称**: ").append(info.toolDisplayName != null ? info.toolDisplayName : info.toolName).append("\n");
        details.append("**工具标识**: ").append(info.toolName).append("\n");
        details.append("**执行状态**: ").append(info.getStatusText()).append("\n");
        if (info.executionTimeMs > 0) {
            details.append("**执行耗时**: ").append(info.executionTimeMs).append("ms\n");
        }
        if (info.parameters != null && !info.parameters.isEmpty()) {
            details.append("\n\uD83D\uDCDD **输入参数**:\n```json\n").append(formatJson(info.parameters)).append("\n```\n");
        }
        if (info.result != null && !info.result.isEmpty()) {
            String resultPreview = info.result;
            if (resultPreview.length() > 500) {
                resultPreview = resultPreview.substring(0, 500) + "\n...(内容已截断)";
            }
            details.append("\n\uD83D\uDCCA **执行结果**:\n").append(resultPreview).append("\n");
        }

        new AlertDialog.Builder(activity)
            .setTitle("工具详情: " + (info.toolDisplayName != null ? info.toolDisplayName : info.toolName))
            .setMessage(details.toString())
            .setPositiveButton("复制结果", (dialog, which) -> {
                if (info.result != null) {
                    copyToClipboard("Tool Result", info.result);
                    callback.onShowToast("已复制结果");
                }
            })
            .setNeutralButton("复制全部", (dialog, which) -> {
                copyToClipboard("Tool Details", details.toString());
                callback.onShowToast("已复制全部信息");
            })
            .setNegativeButton("关闭", null)
            .show();
    }

    /**
     * 导出总结内容
     */
    public void exportSummary(String messageId, List<ChatMessage> chatHistory) {
        if (messageId == null) {
            callback.onShowToast("未找到总结信息");
            return;
        }

        ChatMessage summaryMessage = null;
        for (ChatMessage msg : chatHistory) {
            if (messageId.equals(msg.id)) {
                summaryMessage = msg;
                break;
            }
        }

        if (summaryMessage == null) {
            callback.onShowToast("未找到总结内容");
            return;
        }

        StringBuilder exportContent = new StringBuilder();
        exportContent.append("# AI 对话总结\n\n");
        exportContent.append("生成时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("\n");

        if (summaryMessage.summaryInfo != null) {
            ChatMessage.SummaryInfo summaryInfo = summaryMessage.summaryInfo;
            exportContent.append("步骤数量: ").append(summaryInfo.stepsCount).append("\n");
            exportContent.append("总耗时: ").append(summaryInfo.totalTimeMs).append("ms\n");
            if (summaryInfo.keyPoints != null && !summaryInfo.keyPoints.isEmpty()) {
                exportContent.append("\n## 关键要点\n").append(summaryInfo.keyPoints).append("\n");
            }
            if (summaryInfo.nextSteps != null && !summaryInfo.nextSteps.isEmpty()) {
                exportContent.append("\n## 后续建议\n").append(summaryInfo.nextSteps).append("\n");
            }
        }
        exportContent.append("\n## 总结内容\n\n").append(summaryMessage.content != null ? summaryMessage.content : "");

        final String finalContent = exportContent.toString();

        new AlertDialog.Builder(activity)
            .setTitle("导出总结")
            .setMessage("选择导出方式：\n\n" +
                "\uD83D\uDCCB 复制到剪贴板\n" +
                "\uD83D\uDCE4 分享到其他应用\n" +
                "\uD83D\uDCDD 预览内容")
            .setPositiveButton("复制", (dialog, which) -> {
                copyToClipboard("AI Summary", finalContent);
                callback.onShowToast("已复制到剪贴板");
            })
            .setNeutralButton("分享", (dialog, which) -> {
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_TITLE, "AI对话总结");
                shareIntent.putExtra(Intent.EXTRA_TEXT, finalContent);
                activity.startActivity(Intent.createChooser(shareIntent, "分享总结"));
            })
            .setNegativeButton("预览", (dialog, which) -> {
                showPreviewDialog("总结预览", finalContent);
            })
            .show();
    }

    /**
     * 显示使用指南对话框
     */
    public void showGuideDialog() {
        StringBuilder guide = new StringBuilder();
        guide.append("\uD83E\uDD16 **AI助手使用指南**\n\n");
        guide.append("### \uD83D\uDCDA 基础功能\n");
        guide.append("• **提问**: 在输入框输入问题，点击发送\n");
        guide.append("• **连续对话**: AI会记住上下文，支持多轮对话\n");
        guide.append("• **停止生成**: 点击停止按钮中断当前回复\n\n");
        guide.append("### \uD83D\uDD27 快捷操作\n");
        guide.append("• **复制**: 点击消息下方的复制按钮\n");
        guide.append("• **重新生成**: 点击重新生成获取不同回复\n");
        guide.append("• **新对话**: 清除历史，开始新的对话\n\n");
        guide.append("### \uD83D\uDEE0\uFE0F 智能工具\n");
        guide.append("• **天气查询**: 询问天气信息\n");
        guide.append("• **位置定位**: 获取当前位置\n");
        guide.append("• **文件操作**: 读取、分析文件\n");
        guide.append("• **网络搜索**: 搜索网络信息\n\n");
        guide.append("### \uD83D\uDCA1 使用技巧\n");
        guide.append("• 描述问题时尽量详细\n");
        guide.append("• 可以要求AI解释某个概念\n");
        guide.append("• 可以让AI总结之前的对话\n");
        guide.append("• 使用工具帮助完成复杂任务\n");

        new AlertDialog.Builder(activity)
            .setTitle("AI助手使用指南")
            .setMessage(guide.toString())
            .setPositiveButton("我知道了", null)
            .setNeutralButton("复制指南", (dialog, which) -> {
                copyToClipboard("AI Guide", guide.toString());
                callback.onShowToast("已复制使用指南");
            })
            .show();
    }

    /**
     * 显示预览对话框
     */
    public void showPreviewDialog(String title, String content) {
        new AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(content)
            .setPositiveButton("复制", (dialog, which) -> {
                copyToClipboard(title, content);
                callback.onShowToast("已复制");
            })
            .setNegativeButton("关闭", null)
            .show();
    }

    /**
     * 导出完整对话
     */
    public void exportChat(List<ChatMessage> chatHistory) {
        if (chatHistory == null || chatHistory.isEmpty()) {
            callback.onShowToast("没有可导出的对话内容");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("AI对话导出\n");
        sb.append("时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date())).append("\n");
        sb.append("===================\n\n");
        for (ChatMessage msg : chatHistory) {
            String role = "未知";
            if (msg.type == ChatMessage.MessageType.USER) role = "用户";
            else if (msg.type == ChatMessage.MessageType.AI) role = "AI";
            else if (msg.type == ChatMessage.MessageType.SYSTEM) role = "系统";
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(msg.timestamp));
            sb.append("[").append(time).append("] ").append(role).append(":\n");
            sb.append(msg.content != null ? msg.content : "").append("\n\n");
        }
        copyToClipboard("AI对话", sb.toString());
        callback.onShowToast("对话已复制到剪贴板");
    }

    /**
     * 显示输入选项菜单
     */
    public void showInputOptions(EditText inputMessage) {
        String[] options = {"粘贴", "清空输入", "导出对话"};
        new AlertDialog.Builder(activity)
            .setTitle("选项")
            .setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0:
                        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Activity.CLIPBOARD_SERVICE);
                        if (clipboard.hasPrimaryClip() && clipboard.getPrimaryClip() != null) {
                            ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
                            String text = item.getText() != null ? item.getText().toString() : "";
                            inputMessage.append(text);
                        }
                        break;
                    case 1:
                        inputMessage.setText("");
                        break;
                    case 2:
                        // exportChat needs chatHistory, handled by caller
                        break;
                }
            })
            .show();
    }

    private void handleHelpCommand() {
        callback.onAddAIMessage("可用功能：\n**应用功能：**生成题目、分析题目、翻译、学习计划、统计、搜索题目、天气、导入/导出题目、数据库操作\n**娱乐功能：**讲笑话、猜谜语、写诗、讲故事、知识问答、名言、游戏");
    }

    private void copyToClipboard(String label, String content) {
        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Activity.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, content));
    }

    private String formatJson(String jsonStr) {
        if (jsonStr == null) return "";
        try {
            org.json.JSONObject json = new org.json.JSONObject(jsonStr);
            return json.toString(2);
        } catch (Exception e) {
            return jsonStr;
        }
    }
}

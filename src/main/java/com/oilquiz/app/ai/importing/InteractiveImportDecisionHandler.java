package com.oilquiz.app.ai.importing;

import android.app.Activity;
import android.util.Log;

import com.oilquiz.app.SmartQuizApplication;
import com.oilquiz.app.ai.importing.v2.ImportMain;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 交互式导入决策处理器 —— 智能体导入时四个关键决策点都与用户确认（弹窗），
 * 判断与导入动作不静默执行。
 * <p>
 * 决策点（与 ImportMain 四决策点一一对应）：
 * <ol>
 *   <li>字段映射确认：展示映射方案（源列名→标准字段）与来源，用户确认或取消</li>
 *   <li>数据预览：展示质量统计，用户选择"全部导入/仅导入完整/取消"</li>
 *   <li>智能填充：展示缺字段行数，用户选择"AI 填充/跳过缺字段行/取消"</li>
 *   <li>最终入库：展示预计导入统计，用户确认入库或取消</li>
 * </ol>
 * 弹窗经主线程 Handler + {@link CountDownLatch} 阻塞等待用户操作；
 * 等待超时（3 分钟）或当前 Activity 不可用时按默认继续放行（不卡死导入线程）。
 */
public class InteractiveImportDecisionHandler implements ImportMain.InteractionHandler {

    private static final String TAG = "InteractiveImportHandler";
    /** 单次决策等待超时（毫秒）：超过后按默认继续，避免后台无人值守时永久阻塞导入 */
    private static final long WAIT_TIMEOUT_MS = 3 * 60 * 1000L;

    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    public ImportMain.InteractionHandler.Decision onMappingReady(
            Map<String, String> mapping, List<String> headers,
            String sourceName, String docHint, String mappingSource) {
        StringBuilder msg = new StringBuilder();
        msg.append("文件：").append(sourceName == null ? "" : sourceName).append('\n');
        msg.append("映射来源：").append(sourceNameOf(mappingSource)).append('\n');
        if (docHint != null && !docHint.isEmpty()) {
            msg.append("题库说明：").append(docHint).append('\n');
        }
        if (mapping != null && !mapping.isEmpty()) {
            msg.append("\n字段映射：\n");
            for (Map.Entry<String, String> e : mapping.entrySet()) {
                msg.append("  ").append(e.getKey()).append(" ← ").append(e.getValue()).append('\n');
            }
        }
        DecisionResult r = ask("确认字段映射", msg.toString(),
                new String[]{"确认继续", "取消导入"});
        if (r.cancelled) {
            return cancel();
        }
        return new ImportMain.InteractionHandler.Decision();
    }

    @Override
    public ImportMain.InteractionHandler.Decision onPreviewReady(
            ImportMain.QualityPreview preview, List<File> chunks) {
        String msg = "数据预览（入库前）：\n"
                + "  总行数：" + n(preview == null ? 0 : preview.totalRows) + "\n"
                + "  有效写入：" + n(preview == null ? 0 : preview.writtenRows) + "\n"
                + "  无效/重复跳过：" + n(preview == null ? 0 : preview.skippedCount) + "\n"
                + "  文件内重复题：" + n(preview == null ? 0 : preview.duplicateCount) + "\n\n"
                + "如何处理缺字段/不完整的行？";
        DecisionResult r = ask("确认数据预览", msg,
                new String[]{"全部导入", "仅导入完整题目", "取消导入"});
        if (r.cancelled) {
            return cancel();
        }
        ImportMain.InteractionHandler.Decision d = new ImportMain.InteractionHandler.Decision();
        d.skipIncomplete = r.choice == 1; // 仅导入完整题目 → 跳过缺字段行
        d.previewConsumed = true;
        return d;
    }

    @Override
    public ImportMain.InteractionHandler.Decision onFillReady(
            ImportMain.QualityPreview preview, int missingCount) {
        String msg = "检测到 " + missingCount + " 行缺字段。\n\n是否用 AI 智能填充缺失字段？";
        DecisionResult r = ask("确认智能填充", msg,
                new String[]{"AI 填充缺失字段", "跳过缺字段行", "取消导入"});
        if (r.cancelled) {
            return cancel();
        }
        ImportMain.InteractionHandler.Decision d = new ImportMain.InteractionHandler.Decision();
        d.fillEnabled = r.choice == 0; // AI 填充 / 跳过
        if (r.choice == 1) {
            d.skipIncomplete = true;
        }
        return d;
    }

    @Override
    public ImportMain.InteractionHandler.Decision onFinalConfirm(
            ImportMain.QualityPreview preview, ImportMain.ImportSummary summary) {
        long imported = summary == null ? 0 : summary.imported;
        long dup = summary == null ? 0 : summary.duplicated;
        long failed = summary == null ? 0 : summary.failed;
        String msg = "解析完成，即将入库：\n"
                + "  预计导入：" + n(imported) + " 题\n"
                + "  重复：" + n(dup) + " 题\n"
                + "  失败：" + n(failed) + " 题\n\n"
                + "确认写入题库？";
        DecisionResult r = ask("确认入库", msg,
                new String[]{"确认入库", "取消导入"});
        if (r.cancelled) {
            return cancel();
        }
        return new ImportMain.InteractionHandler.Decision();
    }

    // ==================== 弹窗工具 ====================

    private static class DecisionResult {
        boolean cancelled;
        int choice; // 选中按钮索引
    }

    /** 主线程弹 AlertDialog 并阻塞等待用户选择；异常/超时/无 Activity 时按"确认继续"兜底 */
    private DecisionResult ask(String title, String message, String[] buttons) {
        DecisionResult result = new DecisionResult();
        CountDownLatch latch = new CountDownLatch(1);
        try {
            final Activity activity = SmartQuizApplication.getCurrentActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                Log.w(TAG, "当前无可用 Activity，决策点自动放行: " + title);
                return result; // 默认继续
            }
            mainHandler.post(() -> {
                try {
                    // 两按钮 → 正/负；三按钮 → 正/中/负；其余 → 仅确认
                    android.app.AlertDialog.Builder b2 = new android.app.AlertDialog.Builder(activity);
                    b2.setTitle(title);
                    b2.setMessage(message);
                    b2.setCancelable(false);
                    if (buttons != null && buttons.length == 2) {
                        b2.setNegativeButton(buttons[1], (d, w) -> { result.cancelled = true; latch.countDown(); });
                        b2.setPositiveButton(buttons[0], (d, w) -> { result.choice = 0; latch.countDown(); });
                    } else if (buttons != null && buttons.length >= 3) {
                        b2.setNegativeButton(buttons[2], (d, w) -> { result.cancelled = true; latch.countDown(); });
                        b2.setNeutralButton(buttons[1], (d, w) -> { result.choice = 1; latch.countDown(); });
                        b2.setPositiveButton(buttons[0], (d, w) -> { result.choice = 0; latch.countDown(); });
                    } else {
                        b2.setPositiveButton("确认", (d, w) -> latch.countDown());
                    }
                    b2.show();
                } catch (Exception e) {
                    Log.w(TAG, "弹窗失败，决策点自动放行: " + e.getMessage());
                    latch.countDown();
                }
            });
            if (!latch.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "决策等待超时，自动放行: " + title);
            }
        } catch (Exception e) {
            Log.w(TAG, "决策交互异常，自动放行: " + e.getMessage());
        }
        return result;
    }

    private ImportMain.InteractionHandler.Decision cancel() {
        ImportMain.InteractionHandler.Decision d = new ImportMain.InteractionHandler.Decision();
        d.action = ImportMain.InteractionHandler.Decision.CANCEL;
        return d;
    }

    private static String n(long v) {
        return String.valueOf(v);
    }

    private static String sourceNameOf(String src) {
        if (src == null) return "AI 推理";
        switch (src) {
            case "cache": return "缓存命中";
            case "rules": return "规则识别";
            case "ai": return "AI 推理";
            case "fallback": return "兜底映射";
            default: return src;
        }
    }
}

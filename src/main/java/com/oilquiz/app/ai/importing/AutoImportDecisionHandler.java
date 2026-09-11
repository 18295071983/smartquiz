package com.oilquiz.app.ai.importing;

import com.oilquiz.app.ai.importing.v2.ImportMain;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 自动决策交互处理器 —— 智能体驱动导入时使用（无人值守，不弹窗）。
 * <p>
 * 四个决策点全部自动放行，决策参数由构造注入：
 * <ul>
 *   <li>字段映射确认：直接采用 AI 映射结果继续</li>
 *   <li>数据预览：直接通过（按 skipIncomplete 决定是否跳过缺字段行）</li>
 *   <li>智能填充确认：按 fillEnabled 参数（默认开启）</li>
 *   <li>最终入库确认：直接确认入库</li>
 * </ul>
 * 与 {@code AIImportActivity.createInteractionHandler()}（人工确认版）互斥，
 * 智能体工具侧统一使用本实现，保证工具调用不会被 UI 弹窗阻塞。
 */
public class AutoImportDecisionHandler implements ImportMain.InteractionHandler {

    private final boolean fillMissing;
    private final boolean skipIncomplete;

    public AutoImportDecisionHandler() {
        this(true, false);
    }

    public AutoImportDecisionHandler(boolean fillMissing, boolean skipIncomplete) {
        this.fillMissing = fillMissing;
        this.skipIncomplete = skipIncomplete;
    }

    @Override
    public ImportMain.InteractionHandler.Decision onMappingReady(
            Map<String, String> mapping, List<String> headers,
            String sourceName, String docHint, String mappingSource) {
        return new ImportMain.InteractionHandler.Decision(); // 采用 AI 映射，继续
    }

    @Override
    public ImportMain.InteractionHandler.Decision onPreviewReady(
            ImportMain.QualityPreview preview, List<File> chunks) {
        ImportMain.InteractionHandler.Decision d = new ImportMain.InteractionHandler.Decision();
        d.skipIncomplete = skipIncomplete;
        d.previewConsumed = true; // 预览自动通过，不再弹出
        return d;
    }

    @Override
    public ImportMain.InteractionHandler.Decision onFillReady(
            ImportMain.QualityPreview preview, int missingCount) {
        ImportMain.InteractionHandler.Decision d = new ImportMain.InteractionHandler.Decision();
        d.fillEnabled = fillMissing; // 按参数决定是否 AI 补缺失字段
        return d;
    }

    @Override
    public ImportMain.InteractionHandler.Decision onFinalConfirm(
            ImportMain.QualityPreview preview, ImportMain.ImportSummary summary) {
        return new ImportMain.InteractionHandler.Decision(); // 自动确认入库
    }
}

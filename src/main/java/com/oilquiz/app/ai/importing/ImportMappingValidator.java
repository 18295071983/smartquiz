package com.oilquiz.app.ai.importing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 导入字段映射防错校验器（全通道共用）。
 * <p>
 * 在 SQL 导入、AI 导入规则快速通道、CSV/Excel 直接导入等通道中，
 * 于数据批量提取【之前】校验"表头 → 标准字段"映射的可靠性，
 * 防止列错位/必填缺失/表头冲突导致错误数据入库。
 * </p>
 * <p>纯 Java 工具类，无 Android 依赖。</p>
 */
public final class ImportMappingValidator {

    /** 样例行中题干非空占比低于该值 → 判定映射错误 */
    private static final float MIN_QUESTION_FILL_RATIO = 0.4f;
    /** 样例行中答案非空占比低于该值 → 仅警告 */
    private static final float MIN_ANSWER_FILL_RATIO = 0.4f;

    private ImportMappingValidator() {
    }

    /** 校验报告 */
    public static class Report {
        /** 是否允许按当前映射继续导入 */
        public boolean ok = true;
        /** 阻断性错误（ok=false 时非空） */
        public final List<String> errors = new ArrayList<>();
        /** 非阻断警告 */
        public final List<String> warnings = new ArrayList<>();

        public String summary() {
            StringBuilder sb = new StringBuilder();
            for (String e : errors) sb.append("❌ ").append(e).append('\n');
            for (String w : warnings) sb.append("⚠ ").append(w).append('\n');
            return sb.toString();
        }
    }

    /**
     * 校验字段映射。
     *
     * @param headers     表头列表（列名原文，可为 null）
     * @param mapping     标准字段名 → 列索引（FieldMappingRegistry 产物）
     * @param sampleRows  数据行样本（不含表头，取前若干行即可，可为 null）
     */
    public static Report validate(List<String> headers,
                                  Map<String, Integer> mapping,
                                  List<List<String>> sampleRows) {
        Report report = new Report();
        if (mapping == null || mapping.isEmpty()) {
            report.ok = false;
            report.errors.add("字段映射为空，无法识别任何题目列");
            return report;
        }

        // 1. 必填字段检查：题干 + 答案
        if (!mapping.containsKey("questionText")) {
            report.ok = false;
            report.errors.add("未识别到题干列（questionText），映射不可用");
        }
        if (!mapping.containsKey("correctAnswer")) {
            report.ok = false;
            report.errors.add("未识别到答案列（correctAnswer），映射不可用");
        }

        // 2. 表头冲突检查（两个表头映射到同一标准字段 → 列错位风险）
        //    核心字段（题干/答案）冲突为阻断性错误；其他冲突仅警告
        List<String> conflicts = FieldMappingRegistry.detectMappingConflicts(headers);
        if (conflicts != null) {
            for (String c : conflicts) {
                boolean core = c.startsWith("questionText(") || c.startsWith("correctAnswer(");
                if (core) {
                    report.ok = false;
                    report.errors.add("表头冲突：" + c);
                } else {
                    report.warnings.add("表头冲突：" + c);
                }
            }
        }

        // 3. 样例行质量检查（列错位防护）
        if (sampleRows != null && !sampleRows.isEmpty()) {
            int qIdx = mapping.containsKey("questionText") ? mapping.get("questionText") : -1;
            int aIdx = mapping.containsKey("correctAnswer") ? mapping.get("correctAnswer") : -1;
            int n = 0;
            int qFilled = 0;
            int aFilled = 0;
            int qNumericOnly = 0;
            for (List<String> row : sampleRows) {
                if (row == null || row.isEmpty()) continue;
                n++;
                String qt = qIdx >= 0 && qIdx < row.size() ? nz(row.get(qIdx)) : "";
                String ans = aIdx >= 0 && aIdx < row.size() ? nz(row.get(aIdx)) : "";
                if (!qt.isEmpty()) {
                    qFilled++;
                    if (qt.matches("[0-9.\\-]+")) qNumericOnly++;
                }
                if (!ans.isEmpty()) aFilled++;
            }
            if (n > 0) {
                float qRatio = qFilled / (float) n;
                float aRatio = aFilled / (float) n;
                if (qIdx >= 0 && qRatio < MIN_QUESTION_FILL_RATIO) {
                    report.ok = false;
                    report.errors.add(String.format(
                            "样例行题干列填充率过低（%d/%d），疑似列映射错误", qFilled, n));
                }
                if (qIdx >= 0 && qFilled > 0 && qNumericOnly * 2 >= qFilled) {
                    report.warnings.add("题干列内容多为纯数字，疑似映射到了序号/编号列");
                }
                if (aIdx >= 0 && aRatio < MIN_ANSWER_FILL_RATIO) {
                    report.warnings.add(String.format(
                            "样例行答案列填充率较低（%d/%d），请确认答案列映射正确", aFilled, n));
                }
            }
        }
        return report;
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }
}

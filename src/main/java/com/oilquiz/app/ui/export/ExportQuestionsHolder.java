package com.oilquiz.app.ui.export;

import com.oilquiz.app.model.Question;

import java.util.List;

/**
 * 导出题目数据内存持有器。
 * <p>
 * 题库数量大时（上千题），通过 Intent Serializable 传递 questions 列表
 * 会超过 Binder 事务上限（1MB）抛 TransactionTooLargeException 导致崩溃。
 * 导出流水线（ExportActivity → TemplateSelectionActivity → FieldConfigActivity
 * → ExportProgressActivity）均在同一进程内，改用静态内存持有传递。
 * <p>
 * ExportProgressActivity 导出结束后应调用 {@link #clear()} 释放引用。
 */
public final class ExportQuestionsHolder {

    private static volatile List<Question> questions;

    private ExportQuestionsHolder() {
    }

    /** 设置待导出的题目列表 */
    public static void set(List<Question> list) {
        questions = list;
    }

    /** 获取待导出的题目列表（可能为 null） */
    public static List<Question> get() {
        return questions;
    }

    /** 释放引用，避免长期持有大列表造成内存压力 */
    public static void clear() {
        questions = null;
    }
}

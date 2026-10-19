package com.oilquiz.app.ai.tool;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 原生弹窗组件类型的唯一名单（2026-10-20 收口）。
 *
 * <p>问题：这份"哪些 component_type 走原生弹窗"的表在 {@code SystemUIComponentTool} 里
 * 被抄了两遍（{@code isNativeComponentType} 与 {@code isBuiltinComponentType} 各一份同样的
 * 数组），旁边还躺着第三份早已零引用的死名单 {@code BUILTIN_COMPONENT_TYPES}。
 * 更糟的是名字撞车：表里写着 {@code list} 与 {@code progress}，而
 * {@link com.oilquiz.app.ai.chat.component.ComponentTypes} 的 canonical 目录把
 * {@code list→list_card}、{@code progress→progress_card} 当成**聊天流卡片**教给模型——
 * 模型照目录用 {@code list} 拿到的是一个弹窗，卡片实现永远进不去。
 *
 * <p>口径：<b>短名让给卡片，弹窗改用带语义的名字</b>。
 * <ul>
 *   <li>内部实现（{@code PythonToolManager.createUiComponent} 与各原生 handler）仍认
 *       {@code list}/{@code progress} 这些老名字——内部调用方一个都不动，零风险；</li>
 *   <li>模型侧可见的原生类型名是 {@link #MODEL_NAMES}，其中 {@code list_picker}/{@code progress_dialog}
 *       经 {@link #internalName(String)} 翻译成内部名再下发；</li>
 *   <li>于是 {@code list}/{@code progress} 不再被弹窗拦截，落到内置卡片链路
 *       （{@code list_card}/{@code progress_card}）。历史数据里已存在的弹窗实例按 component_id
 *       更新/关闭，不走类型名解析，不受影响。</li>
 * </ul>
 *
 * <p>不变量由 {@code ComponentNameIntegrityTest} 钉住：
 * canonical 卡片名不得出现在 {@link #MODEL_NAMES} 里，且模型可见名与内部名之外不再有第二份表。
 */
public final class NativeComponentTypes {

    private NativeComponentTypes() {
    }

    /** 内部实现认识的原生类型名（PythonToolManager / 原生 handler 的分支依据，勿随意改名） */
    public static final Set<String> INTERNAL_NAMES;

    /**
     * 模型可见名 → 内部实现名。只放"因为让出短名给卡片而需要翻译"的两项；
     * 其余模型可见名与内部名同名。
     */
    public static final Map<String, String> MODEL_TO_INTERNAL;

    /** 模型可见的原生弹窗类型名 = 内部名去掉被卡片占用的 list/progress，加上替代名 */
    public static final Set<String> MODEL_NAMES;

    /** 被卡片名字占用、因此对模型改用替代名的内部类型 */
    private static final Set<String> RESERVED_FOR_CARDS;

    static {
        Set<String> internal = new LinkedHashSet<>(java.util.Arrays.asList(
                "dialog", "progress", "input", "choice", "multi_choice",
                "date", "time", "snackbar", "list", "notification", "toast", "voice_recorder",
                "speech_player", "custom", "layout_canvas", "file_picker", "image_picker",
                "contact_picker", "rating", "color", "otp", "number", "media_task", "marquee"));
        INTERNAL_NAMES = Collections.unmodifiableSet(internal);

        Set<String> reserved = new LinkedHashSet<>();
        reserved.add("list");        // → list_card（聊天流列表卡）
        reserved.add("progress");    // → progress_card（聊天流进度卡）
        RESERVED_FOR_CARDS = Collections.unmodifiableSet(reserved);

        Map<String, String> alias = new HashMap<>();
        alias.put("list_picker", "list");
        alias.put("progress_dialog", "progress");
        MODEL_TO_INTERNAL = Collections.unmodifiableMap(alias);

        Set<String> model = new LinkedHashSet<>();
        for (String name : internal) {
            if (!reserved.contains(name)) model.add(name);
        }
        model.addAll(alias.keySet());
        MODEL_NAMES = Collections.unmodifiableSet(model);
    }

    /** 该内部类型名是否因为要让给聊天流卡片而对模型改用替代名 */
    public static boolean isReservedForCards(String internalName) {
        return internalName != null && RESERVED_FOR_CARDS.contains(internalName);
    }

    /** 模型传来的 component_type 是否是原生弹窗类型（走弹窗，不进聊天流卡片链路） */
    public static boolean isModelNative(String componentType) {
        return componentType != null && MODEL_NAMES.contains(componentType);
    }

    /** 模型可见名翻译成内部实现名；无需翻译的原样返回 */
    public static String internalName(String modelTypeName) {
        if (modelTypeName == null) return null;
        String mapped = MODEL_TO_INTERNAL.get(modelTypeName);
        return mapped != null ? mapped : modelTypeName;
    }

    /** 给模型看的替代名（用于错误提示："list 现在是卡片，弹窗请用 list_picker"） */
    public static String modelAliasFor(String internalName) {
        if (internalName == null) return null;
        for (Map.Entry<String, String> e : MODEL_TO_INTERNAL.entrySet()) {
            if (e.getValue().equals(internalName)) return e.getKey();
        }
        return internalName;
    }
}

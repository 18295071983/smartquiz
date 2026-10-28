package com.oilquiz.app.ai.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 两个运行时 {@link ToolGuideFlow} 的逐字段 diff（测试辅助）。
 *
 * <p>PoC 的核心承诺是"配置产物 == 硬编码 builder 产物"，两侧同为
 * {@link ToolGuideFlow}，所以这里可以比"数据形状"更细的东西：把每个字段都列出来比，
 * 差异以可读字符串返回，让失败信息直接指出是第几步哪个字段漂了。</p>
 *
 * <p>只比 {@code getActiveSteps} 与渲染层真正读到的字段；
 * {@code GuideStep.paramValue} 是运行时槽位，两侧都必须是 null（也一起验）。</p>
 */
final class GuideFlowDiff {

    private GuideFlowDiff() {
    }

    /** 返回差异列表；两侧等价时为空。 */
    static List<String> diff(ToolGuideFlow expected, ToolGuideFlow actual) {
        List<String> diffs = new ArrayList<>();
        if (expected == null || actual == null) {
            diffs.add("一侧为 null expected=" + (expected != null) + " actual=" + (actual != null));
            return diffs;
        }
        compare(diffs, "toolName", expected.toolName, actual.toolName);
        compare(diffs, "toolDisplayName", expected.toolDisplayName, actual.toolDisplayName);
        compare(diffs, "toolDescription", expected.toolDescription, actual.toolDescription);
        compare(diffs, "contextDependencies", expected.contextDependencies, actual.contextDependencies);
        if (size(expected.steps) != size(actual.steps)) {
            diffs.add("steps.size expected=" + size(expected.steps) + " actual=" + size(actual.steps));
            return diffs;
        }
        for (int i = 0; i < size(expected.steps); i++) {
            diffStep(diffs, "steps[" + i + "]", expected.steps.get(i), actual.steps.get(i));
        }
        return diffs;
    }

    private static void diffStep(List<String> diffs, String loc,
                                 ToolGuideFlow.GuideStep expected, ToolGuideFlow.GuideStep actual) {
        if (expected == null || actual == null) {
            diffs.add(loc + " 一侧为 null");
            return;
        }
        compare(diffs, loc + ".type", expected.type, actual.type);
        compare(diffs, loc + ".title", expected.title, actual.title);
        compare(diffs, loc + ".description", expected.description, actual.description);
        compare(diffs, loc + ".paramKey", expected.paramKey, actual.paramKey);
        compare(diffs, loc + ".hint", expected.hint, actual.hint);
        compare(diffs, loc + ".required", expected.required, actual.required);
        compare(diffs, loc + ".multiline", expected.multiline, actual.multiline);
        compare(diffs, loc + ".allowMultiple", expected.allowMultiple, actual.allowMultiple);
        if (!Arrays.equals(expected.mimeTypes, actual.mimeTypes)) {
            diffs.add(loc + ".mimeTypes expected=" + Arrays.toString(expected.mimeTypes)
                    + " actual=" + Arrays.toString(actual.mimeTypes));
        }
        // 条件分支按 "|" 串逐字比：这是配置结构化 values[] 回填后的形状，顺序也要一致
        compare(diffs, loc + ".conditionKey", expected.conditionKey, actual.conditionKey);
        compare(diffs, loc + ".conditionValue", expected.conditionValue, actual.conditionValue);
        compare(diffs, loc + ".paramValue(运行时槽位两侧都该是 null)", expected.paramValue, actual.paramValue);

        if (size(expected.options) != size(actual.options)) {
            diffs.add(loc + ".options.size expected=" + size(expected.options)
                    + " actual=" + size(actual.options));
        } else {
            for (int i = 0; i < size(expected.options); i++) {
                ToolGuideFlow.GuideStep.Option e = expected.options.get(i);
                ToolGuideFlow.GuideStep.Option a = actual.options.get(i);
                compare(diffs, loc + ".options[" + i + "].label", e.label, a.label);
                compare(diffs, loc + ".options[" + i + "].value", e.value, a.value);
            }
        }

        if (expected.dynamicOptions == null || actual.dynamicOptions == null) {
            if (expected.dynamicOptions != null || actual.dynamicOptions != null) {
                diffs.add(loc + ".dynamicOptions 一侧缺失 expected=" + (expected.dynamicOptions != null)
                        + " actual=" + (actual.dynamicOptions != null));
            }
        } else {
            compare(diffs, loc + ".dynamicOptions.toolName",
                    expected.dynamicOptions.toolName, actual.dynamicOptions.toolName);
            compare(diffs, loc + ".dynamicOptions.action",
                    expected.dynamicOptions.action, actual.dynamicOptions.action);
            compare(diffs, loc + ".dynamicOptions.listKey",
                    expected.dynamicOptions.listKey, actual.dynamicOptions.listKey);
            compare(diffs, loc + ".dynamicOptions.itemField",
                    expected.dynamicOptions.itemField, actual.dynamicOptions.itemField);
            compare(diffs, loc + ".dynamicOptions.allOptionLabel",
                    expected.dynamicOptions.allOptionLabel, actual.dynamicOptions.allOptionLabel);
        }
    }

    private static int size(List<?> list) {
        return list == null ? 0 : list.size();
    }

    private static void compare(List<String> diffs, String field, Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            diffs.add(field + " expected=" + expected + " actual=" + actual);
        }
    }

    /** 把差异列表拼成一条断言消息。 */
    static String render(List<String> diffs) {
        StringBuilder sb = new StringBuilder();
        for (String diff : diffs) {
            if (sb.length() > 0) {
                sb.append("\n  ");
            }
            sb.append(diff);
        }
        return sb.toString();
    }
}

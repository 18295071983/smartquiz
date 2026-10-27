package com.oilquiz.app.ai.tool.openai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具参数的<b>schema 级轻校验</b>：只查"定义里写了必填却没给"和"给了定义里没有的参数"。
 *
 * <p>为什么只做这两条：类型归一（数字串→int、字符串→bool）{@code AIToolManager} 已经在做了，
 * 再做一遍类型校验只会把"模型传了字符串数字"这种正常情况判死；而本项目的 schema 刻意宽松
 * （不输出 default、required 大多为空），能查的本来就不多。</p>
 *
 * <p>缺必填时由调用方<b>拒绝执行</b>并回一条精确提示——比让工具自己抛
 * NullPointerException/返回一句"参数错误"更容易让模型自纠。未知参数只记日志，
 * <b>不拒绝</b>：模型多带几个字段是常态，硬拒会把本来能跑通的调用卡死。</p>
 */
public final class ToolArgsValidator {

    /** 校验结果 */
    public static final class Result {
        /** 定义里声明必填、但本次没给的参数名 */
        public final List<String> missingRequired = new ArrayList<>();
        /** 定义里没有、但本次给了的参数名（仅提示，不拒绝） */
        public final List<String> unknown = new ArrayList<>();

        public boolean ok() {
            return missingRequired.isEmpty();
        }

        /** 给人/模型看的一句话；没有缺失时返回 null */
        public String missingMessage() {
            if (missingRequired.isEmpty()) return null;
            return "缺少必填参数: " + String.join("、", missingRequired)
                    + "（该工具的参数定义要求；请补齐后重试）";
        }
    }

    private ToolArgsValidator() {
    }

    /**
     * @param definition 工具定义；为 null（未注册/只有 Map 兜底）时直接放行
     * @param args       已解析的参数（可为 null）
     */
    public static Result validate(ToolDefinition definition, Map<String, Object> args) {
        Result r = new Result();
        if (definition == null || definition.getParameters() == null) return r;
        for (ParamDefinition p : definition.getParameters()) {
            if (p == null || p.getName() == null) continue;
            boolean present = args != null && args.get(p.getName()) != null
                    && !(args.get(p.getName()) instanceof String
                            && ((String) args.get(p.getName())).trim().isEmpty());
            if (p.isRequired() && !present) {
                r.missingRequired.add(p.getName());
            }
            // 收集未知参数（只提示）
        }
        if (args != null) {
            java.util.Set<String> declared = new java.util.HashSet<>();
            for (ParamDefinition p : definition.getParameters()) {
                if (p != null && p.getName() != null) declared.add(p.getName());
            }
            for (String key : args.keySet()) {
                if (key != null && !declared.contains(key)) r.unknown.add(key);
            }
        }
        return r;
    }
}

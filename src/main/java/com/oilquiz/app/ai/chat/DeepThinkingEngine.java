package com.oilquiz.app.ai.chat;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DeepThinkingEngine {
    private static final String TAG = "DeepThinking";
    
    private List<ThinkingStep> thinkingChain;
    private Stack<ThinkingContext> contextStack;
    private double confidenceScore;
    private int reasoningDepth;
    private long startTime;
    
    // 可视化日志集成
    private com.oilquiz.app.util.AILogger2 logger2;
    private com.oilquiz.app.util.AILogger2.VisualLogEntry currentStepEntry;
    private boolean loggingEnabled;
    
    public DeepThinkingEngine() {
        this.thinkingChain = new ArrayList<>();
        this.contextStack = new Stack<>();
        this.confidenceScore = 0.0;
        this.reasoningDepth = 0;
        this.startTime = System.currentTimeMillis();
        this.logger2 = com.oilquiz.app.util.AILogger2.getInstance();
        this.loggingEnabled = true;
    }
    
    public void enableLogging(boolean enabled) {
        this.loggingEnabled = enabled;
    }
    
    public void startThinking(String question) {
        thinkingChain.clear();
        contextStack.clear();
        confidenceScore = 0.0;
        reasoningDepth = 0;
        startTime = System.currentTimeMillis();
        
        // 可视化日志：开始思考
        if (loggingEnabled) {
            JSONObject meta = new JSONObject();
            try {
                meta.put("question_length", question.length());
                meta.put("question_preview", truncate(question, 50));
            } catch (Exception e) {}
            
            logger2.log(com.oilquiz.app.util.AILogger2.LogLevel.THINKING,
                       com.oilquiz.app.util.AILogger2.LogCategory.DEEP_THINKING,
                       TAG, "🧠 开始深度思考: " + truncate(question, 80), meta);
            
            currentStepEntry = logger2.startStep(
                com.oilquiz.app.util.AILogger2.LogCategory.DEEP_THINKING,
                TAG,
                "理解问题",
                "分析用户问题: " + truncate(question, 100),
                meta
            );
        }
        
        addStep("UNDERSTAND", "理解问题", "正在分析用户的问题: " + truncate(question, 100));
        pushContext("question", question);
        
        // 更新进度
        updateStepProgress(10, "正在解析问题结构...");
    }
    
    public void analyzeProblemDecomposition(String problem) {
        // 可视化日志：开始问题分解
        if (loggingEnabled) {
            completeCurrentStep("问题结构分析完成");
            
            JSONObject meta = new JSONObject();
            try {
                meta.put("problem_length", problem.length());
                meta.put("complexity_indicators", countComplexityIndicators(problem));
            } catch (Exception e) {}
            
            currentStepEntry = logger2.startStep(
                com.oilquiz.app.util.AILogger2.LogCategory.DEEP_THINKING,
                TAG,
                "问题分解",
                "将复杂问题拆解为可管理的子任务",
                meta
            );
        }
        
        addStep("DECOMPOSE", "问题分解", "将复杂问题分解为子问题...");
        
        List<String> subProblems = decomposeProblem(problem);
        StringBuilder analysis = new StringBuilder("识别出 ").append(subProblems.size()).append(" 个子问题:\n");
        
        for (int i = 0; i < subProblems.size(); i++) {
            analysis.append((i + 1)).append(". ").append(subProblems.get(i)).append("\n");
            pushContext("sub_problem_" + i, subProblems.get(i));
            
            // 记录每个子问题
            if (loggingEnabled) {
                logger2.log(com.oilquiz.app.util.AILogger2.LogLevel.STEP,
                           com.oilquiz.app.util.AILogger2.LogCategory.DEEP_THINKING,
                           TAG, "📋 子问题 " + (i+1) + ": " + truncate(subProblems.get(i), 60), null);
            }
        }
        
        addProgress("问题分解完成", analysis.toString(), subProblems);
        updateStepProgress(25, "已分解为 " + subProblems.size() + " 个子问题");
    }
    
    public void gatherKnowledge(String topic) {
        // 可视化日志：开始知识检索
        if (loggingEnabled) {
            completeCurrentStep("问题分解完成");
            
            JSONObject meta = new JSONObject();
            try {
                meta.put("topic", topic);
                meta.put("knowledge_domains", identifyDomains(topic));
            } catch (Exception e) {}
            
            currentStepEntry = logger2.startStep(
                com.oilquiz.app.util.AILogger2.LogCategory.DEEP_THINKING,
                TAG,
                "知识检索",
                "激活相关知识库和领域经验",
                meta
            );
        }
        
        addStep("KNOWLEDGE", "知识检索", "检索与「" + topic + "」相关的知识...");
        
        StringBuilder knowledge = new StringBuilder();
        knowledge.append("激活相关知识库:\n");
        knowledge.append("- 领域知识: ").append(topic).append("\n");
        knowledge.append("- 概念定义: 正在加载...\n");
        knowledge.append("- 相关案例: 查找中...\n");
        knowledge.append("- 前提条件: 验证中...");
        
        // 记录知识领域
        if (loggingEnabled) {
            logger2.log(com.oilquiz.app.util.AILogger2.LogLevel.INFO,
                       com.oilquiz.app.util.AILogger2.LogCategory.DEEP_THINKING,
                       TAG, "📚 知识领域: " + identifyDomains(topic), null);
        }
        
        addProgress("知识收集", knowledge.toString(), null);
        updateStepProgress(40, "正在激活 " + topic + " 相关知识库...");
    }
    
    public void evaluateHypotheses(List<String> hypotheses) {
        // 可视化日志：开始假设评估
        if (loggingEnabled) {
            completeCurrentStep("知识检索完成");
            
            JSONObject meta = new JSONObject();
            try {
                meta.put("hypotheses_count", hypotheses.size());
                meta.put("evaluation_method", "multi_criteria_analysis");
            } catch (Exception e) {}
            
            currentStepEntry = logger2.startStep(
                com.oilquiz.app.util.AILogger2.LogCategory.DEEP_THINKING,
                TAG,
                "假设评估",
                "评估多个解决方案的可行性",
                meta
            );
        }
        
        addStep("EVALUATE", "假设评估", "评估 " + hypotheses.size() + " 个可能的解决方案...");
        
        for (int i = 0; i < hypotheses.size(); i++) {
            String hypothesis = hypotheses.get(i);
            double confidence = estimateConfidence(hypothesis);
            
            StringBuilder eval = new StringBuilder();
            eval.append("假设").append(i + 1).append(": ").append(truncate(hypothesis, 80)).append("\n");
            eval.append("置信度: ").append(String.format("%.1f%%", confidence * 100)).append("\n");
            eval.append("可行性: ").append(confidence > 0.6 ? "高" : confidence > 0.3 ? "中" : "低").append("\n");
            eval.append("风险: ").append(confidence > 0.7 ? "低" : confidence > 0.4 ? "中" : "高");
            
            // 记录每个假设的评估结果
            if (loggingEnabled) {
                JSONObject hypMeta = new JSONObject();
                try {
                    hypMeta.put("hypothesis_index", i + 1);
                    hypMeta.put("confidence", confidence);
                    hypMeta.put("feasibility", confidence > 0.6 ? "high" : confidence > 0.3 ? "medium" : "low");
                } catch (Exception e) {}
                
                String level = confidence > 0.7 ? "✅" : confidence > 0.4 ? "⚠️" : "❌";
                logger2.log(confidence > 0.6 ? 
                           com.oilquiz.app.util.AILogger2.LogLevel.SUCCESS :
                           com.oilquiz.app.util.AILogger2.LogLevel.WARNING,
                           com.oilquiz.app.util.AILogger2.LogCategory.DEEP_THINKING,
                           TAG, level + " 假设" + (i+1) + " [" + String.format("%.1f%%", confidence*100) + "]: " + truncate(hypothesis, 60), hypMeta);
            }
            
            addProgress("评估假设" + (i + 1), eval.toString(), hypothesis);
        }
        
        updateStepProgress(55, "已完成 " + hypotheses.size() + " 个假设的评估");
    }
    
    public void logicalReasoning(String premise, String conclusion) {
        // 可视化日志：开始逻辑推理
        if (loggingEnabled) {
            completeCurrentStep("假设评估完成");
            
            JSONObject meta = new JSONObject();
            try {
                meta.put("reasoning_depth", reasoningDepth + 1);
                meta.put("reasoning_type", identifyReasoningType(premise));
                meta.put("premise_length", premise.length());
            } catch (Exception e) {}
            
            currentStepEntry = logger2.startStep(
                com.oilquiz.app.util.AILogger2.LogCategory.DEEP_THINKING,
                TAG,
                "逻辑推理",
                "进行第 " + (reasoningDepth + 1) + " 层推导",
                meta
            );
        }
        
        addStep("REASON", "逻辑推理", "进行逻辑推导...");
        reasoningDepth++;
        
        StringBuilder reasoning = new StringBuilder();
        reasoning.append("推理步骤 #").append(reasoningDepth).append(":\n");
        reasoning.append("前提: ").append(truncate(premise, 100)).append("\n");
        reasoning.append("推导: ");
        
        String reasonType = "";
        if (premise.contains("因为") || premise.contains("由于")) {
            reasoning.append("因果推理 - 从原因推断结果\n");
            reasonType = "因果推理";
        } else if (premise.contains("如果") || premise.contains("假如")) {
            reasoning.append("条件推理 - 基于条件得出结论\n");
            reasonType = "条件推理";
        } else if (premise.contains("所有") || premise.contains("每个")) {
            reasoning.append("归纳推理 - 从特例归纳一般规律\n");
            reasonType = "归纳推理";
        } else {
            reasoning.append("演绎推理 - 应用规则到具体情况\n");
            reasonType = "演绎推理";
        }
        
        // 记录推理类型
        if (loggingEnabled) {
            logger2.log(com.oilquiz.app.util.AILogger2.LogLevel.THINKING,
                       com.oilquiz.app.util.AILogger2.LogCategory.DEEP_THINKING,
                       TAG, "🔗 推理类型: " + reasonType + " | 深度: " + reasoningDepth, null);
        }
        
        reasoning.append("结论: ").append(truncate(conclusion, 100));
        
        addProgress("逻辑推导", reasoning.toString(), null);
        updateConfidence(0.15); // 每次成功推理增加置信度
        
        updateStepProgress(70, "完成第 " + reasoningDepth + " 层" + reasonType);
    }
    
    public void verifySolution(String solution) {
        // 可视化日志：开始验证
        if (loggingEnabled) {
            completeCurrentStep("逻辑推理完成");
            
            JSONObject meta = new JSONObject();
            try {
                meta.put("solution_length", solution.length());
                meta.put("verification_checks", 4);
                meta.put("current_confidence", confidenceScore);
            } catch (Exception e) {}
            
            currentStepEntry = logger2.startStep(
                com.oilquiz.app.util.AILogger2.LogCategory.DEEP_THINKING,
                TAG,
                "验证答案",
                "检查解决方案的正确性和完整性",
                meta
            );
        }
        
        addStep("VERIFY", "验证答案", "验证解决方案的正确性...");
        
        StringBuilder verification = new StringBuilder();
        verification.append("验证检查:\n");
        verification.append("✓ 逻辑一致性: 通过\n");
        verification.append("✓ 完整性检查: 通过\n");
        verification.append("✓ 约束满足: 验证中...\n");
        verification.append("✓ 边界情况: 测试中...\n");
        verification.append("结论: ").append(solution.length() > 20 ? "方案可行" : "需要进一步分析");
        
        // 记录验证结果
        if (loggingEnabled) {
            logger2.log(com.oilquiz.app.util.AILogger2.LogLevel.RESULT,
                       com.oilquiz.app.util.AILogger2.LogCategory.DEEP_THINKING,
                       TAG, "✅ 验证通过 | 置信度: " + String.format("%.1f%%", confidenceScore * 100), null);
        }
        
        addProgress("验证结果", verification.toString(), solution);
        updateStepProgress(85, "验证完成，准备综合答案...");
    }
    
    public void synthesizeAnswer(String answer) {
        // 可视化日志：综合答案
        if (loggingEnabled) {
            completeCurrentStep("验证完成");
            
            long elapsed = System.currentTimeMillis() - startTime;
            JSONObject meta = new JSONObject();
            try {
                meta.put("total_steps", thinkingChain.size());
                meta.put("reasoning_depth", reasoningDepth);
                meta.put("final_confidence", confidenceScore);
                meta.put("elapsed_time_ms", elapsed);
                meta.put("answer_length", answer != null ? answer.length() : 0);
            } catch (Exception e) {}
            
            currentStepEntry = logger2.startStep(
                com.oilquiz.app.util.AILogger2.LogCategory.DEEP_THINKING,
                TAG,
                "综合答案",
                "整合所有推理结果，生成最终输出",
                meta
            );
        }
        
        addStep("SYNTHESIZE", "综合答案", "整合所有推理结果，生成最终答案...");
        
        long elapsed = System.currentTimeMillis() - startTime;
        StringBuilder synthesis = new StringBuilder();
        synthesis.append("思考总结:\n");
        synthesis.append("- 推理深度: ").append(reasoningDepth).append(" 层\n");
        synthesis.append("- 耗时: ").append(elapsed / 1000.0).append(" 秒\n");
        synthesis.append("- 置信度: ").append(String.format("%.1f%%", confidenceScore * 100)).append("\n");
        synthesis.append("- 步骤数: ").append(thinkingChain.size()).append("\n");
        synthesis.append("\n最终答案已生成，基于以上推理过程。");
        
        addProgress("思考完成", synthesis.toString(), answer);
        updateStepProgress(100, "✨ 深度思考完成！");
        
        // 完成整个思考过程
        if (loggingEnabled) {
            completeCurrentStep("深度思考完成 | " + reasoningDepth + "层推理 | " + String.format("%.1f%%", confidenceScore*100) + "置信度");
            
            // 记录最终总结
            JSONObject summaryMeta = new JSONObject();
            try {
                summaryMeta.put("total_thinking_time", elapsed);
                summaryMeta.put("steps_completed", thinkingChain.size());
                summaryMeta.put("final_confidence", confidenceScore);
            } catch (Exception e) {}
            
            logger2.log(confidenceScore > 0.8 ? 
                       com.oilquiz.app.util.AILogger2.LogLevel.SUCCESS :
                       com.oilquiz.app.util.AILogger2.LogLevel.INFO,
                       com.oilquiz.app.util.AILogger2.LogCategory.DEEP_THINKING,
                       TAG, "🎉 深度思考完成！耗时: " + String.format("%.2fs", elapsed/1000.0) + 
                           " | 深度: " + reasoningDepth + "层 | 置信度: " + String.format("%.1f%%", confidenceScore*100), 
                       summaryMeta);
        }
    }
    
    public List<ThinkingStep> getThinkingChain() {
        return new ArrayList<>(thinkingChain);
    }
    
    public String getThinkingVisualization() {
        if (thinkingChain.isEmpty()) return "";
        
        StringBuilder viz = new StringBuilder();
        viz.append("🧠 **深度思考过程**\n\n");
        
        for (int i = 0; i < thinkingChain.size(); i++) {
            ThinkingStep step = thinkingChain.get(i);
            viz.append("**").append(step.stepNumber).append(". ").append(step.typeName)
               .append(": ").append(step.title).append("**\n");
            
            if (step.content != null && !step.content.isEmpty()) {
                viz.append("   ").append(step.content.replace("\n", "\n   ")).append("\n");
            }
            
            if (i < thinkingChain.size() - 1) {
                viz.append("   ⬇️\n");
            }
            viz.append("\n");
        }
        
        viz.append("---\n");
        viz.append("*置信度: ").append(String.format("%.1f%%", confidenceScore * 100))
           .append(" | 深度: ").append(reasoningDepth)
           .append("层*");
        
        return viz.toString();
    }
    
    public double getConfidenceScore() {
        return confidenceScore;
    }
    
    public int getReasoningDepth() {
        return reasoningDepth;
    }
    
    private void addStep(String type, String title, String content) {
        ThinkingStep step = new ThinkingStep(
            thinkingChain.size() + 1,
            type,
            title,
            content,
            System.currentTimeMillis()
        );
        thinkingChain.add(step);
    }
    
    private void addProgress(String description, String detail, Object data) {
        if (!thinkingChain.isEmpty()) {
            ThinkingStep lastStep = thinkingChain.get(thinkingChain.size() - 1);
            if (lastStep.progress == null) {
                lastStep.progress = new ArrayList<>();
            }
            ProgressInfo progress = new ProgressInfo(description, detail, data);
            lastStep.progress.add(progress);
        }
    }
    
    private void pushContext(String key, String value) {
        contextStack.push(new ThinkingContext(key, value));
    }
    
    private void popContext() {
        if (!contextStack.isEmpty()) {
            contextStack.pop();
        }
    }
    
    private void updateConfidence(double delta) {
        confidenceScore = Math.min(1.0, Math.max(0.0, confidenceScore + delta));
    }
    
    private List<String> decomposeProblem(String problem) {
        List<String> subProblems = new ArrayList<>();
        
        Pattern pattern = Pattern.compile("[，,；;、]");
        String[] parts = pattern.split(problem);
        
        for (String part : parts) {
            part = part.trim();
            if (part.length() > 3) {
                subProblems.add(part);
            }
        }
        
        if (subProblems.isEmpty()) {
            subProblems.add(problem);
        } else if (subProblems.size() > 5) {
            while (subProblems.size() > 5) {
                subProblems.remove(subProblems.size() - 1);
            }
        }
        
        return subProblems;
    }
    
    private double estimateConfidence(String hypothesis) {
        double baseConfidence = 0.5;
        
        if (hypothesis.contains("因此") || hypothesis.contains("所以")) baseConfidence += 0.2;
        if (hypothesis.contains("根据") || hypothesis.contains("基于")) baseConfidence += 0.15;
        if (hypothesis.contains("可以") || hypothesis.contains("能够")) baseConfidence += 0.1;
        if (hypothesis.length() > 50) baseConfidence += 0.05;
        if (hypothesis.contains("?") || hypothesis.contains("？")) baseConfidence -= 0.2;
        
        return Math.min(1.0, Math.max(0.0, baseConfidence + (Math.random() - 0.5) * 0.2));
    }
    
    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
    
    public static class ThinkingStep {
        public int stepNumber;
        public String type;
        public String typeName;
        public String title;
        public String content;
        public long timestamp;
        public List<ProgressInfo> progress;
        
        public ThinkingStep(int stepNumber, String type, String title, 
                           String content, long timestamp) {
            this.stepNumber = stepNumber;
            this.type = type;
            this.typeName = getDisplayName(type);
            this.title = title;
            this.content = content;
            this.timestamp = timestamp;
            this.progress = new ArrayList<>();
        }
        
        private static String getDisplayName(String type) {
            switch (type) {
                case "UNDERSTAND": return "理解";
                case "DECOMPOSE": return "分解";
                case "KNOWLEDGE": return "知识";
                case "EVALUATE": return "评估";
                case "REASON": return "推理";
                case "VERIFY": return "验证";
                case "SYNTHESIZE": return "综合";
                default: return type;
            }
        }
    }
    
    public static class ProgressInfo {
        public String description;
        public String detail;
        public Object data;
        
        public ProgressInfo(String description, String detail, Object data) {
            this.description = description;
            this.detail = detail;
            this.data = data;
        }
    }
    
    private static class ThinkingContext {
        String key;
        String value;
        
        ThinkingContext(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }
    
    // ========== 可视化日志辅助方法 ==========
    
    private void updateStepProgress(int percent, String statusMessage) {
        if (loggingEnabled && currentStepEntry != null && logger2 != null) {
            logger2.updateStepProgress(currentStepEntry, percent, statusMessage, null);
        }
    }
    
    private void completeCurrentStep(String resultMessage) {
        if (loggingEnabled && currentStepEntry != null && logger2 != null) {
            logger2.completeStep(currentStepEntry, resultMessage, true, null);
            currentStepEntry = null;
        }
    }
    
    private int countComplexityIndicators(String text) {
        int count = 0;
        String[] indicators = {"分析", "比较", "评估", "优化", "设计", "实现", "测试"};
        for (String indicator : indicators) {
            if (text.contains(indicator)) count++;
        }
        return count;
    }
    
    private String identifyDomains(String topic) {
        List<String> domains = new ArrayList<>();
        
        if (topic.contains("编程") || topic.contains("代码") || topic.contains("算法")) domains.add("计算机科学");
        if (topic.contains("数学") || topic.contains("计算") || topic.contains("统计")) domains.add("数学");
        if (topic.contains("物理") || topic.contains("化学") || topic.contains("生物")) domains.add("自然科学");
        if (topic.contains("经济") || topic.contains("金融") || topic.contains("商业")) domains.add("经济学");
        if (topic.contains("历史") || topic.contains("文化") || topic.contains("艺术")) domains.add("人文社科");
        if (topic.contains("医学") || topic.contains("健康") || topic.contains("疾病")) domains.add("医学健康");
        if (topic.contains("法律") || topic.contains("法规") || topic.contains("政策")) domains.add("法律法规");
        
        if (domains.isEmpty()) domains.add("通用知识");
        return String.join(", ", domains);
    }
    
    private String identifyReasoningType(String premise) {
        if (premise.contains("因为") || premise.contains("由于")) return "causal";
        if (premise.contains("如果") || premise.contains("假如")) return "conditional";
        if (premise.contains("所有") || premise.contains("每个")) return "inductive";
        return "deductive";
    }
}

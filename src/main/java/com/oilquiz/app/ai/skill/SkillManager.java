package com.oilquiz.app.ai.skill;

import android.content.Context;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SkillManager {
    private static final String TAG = "SkillManager";

    private final Context context;
    private final Map<String, Skill> skills;

    public SkillManager(Context context) {
        this.context = context.getApplicationContext();
        this.skills = new HashMap<>();
        registerBuiltinSkills();
    }

    private void registerBuiltinSkills() {
        registerSkill(new Skill("math_solver", "数学求解", "解答数学问题，包括代数、几何、微积分等", "数学|计算|方程|函数|积分|微分"));
        registerSkill(new Skill("code_assistant", "编程助手", "帮助编写、调试和解释代码", "代码|编程|bug|调试|程序|开发"));
        registerSkill(new Skill("essay_writer", "写作助手", "帮助撰写各类文章、作文", "写作|作文|文章|论文|报告"));
        registerSkill(new Skill("translator", "翻译助手", "多语言翻译服务", "翻译|translate|英文|中文|日文"));
        registerSkill(new Skill("study_planner", "学习规划", "制定个性化学习计划", "学习计划|复习|备考|安排"));
        registerSkill(new Skill("question_analyzer", "题目分析", "分析题目并提供解题思路", "分析|解题|思路|答案|解析"));
        registerSkill(new Skill("summarizer", "内容总结", "对长文本进行摘要总结", "总结|摘要|概括|提炼"));
        registerSkill(new Skill("brainstorm", "头脑风暴", "创意思维和想法生成", "创意|想法|头脑风暴|建议|方案"));
    }

    public void registerSkill(Skill skill) {
        skills.put(skill.id, skill);
    }

    public Skill getSkill(String skillId) {
        return skills.get(skillId);
    }

    public List<Skill> getAllSkills() {
        return new ArrayList<>(skills.values());
    }

    public List<Skill> matchSkills(String message) {
        List<Skill> matched = new ArrayList<>();
        if (message == null) return matched;
        String lower = message.toLowerCase();
        for (Skill skill : skills.values()) {
            String[] keywords = skill.keywords.split("\\|");
            for (String keyword : keywords) {
                if (lower.contains(keyword)) {
                    matched.add(skill);
                    break;
                }
            }
        }
        return matched;
    }

    public String buildSkillPrompt(String skillId, String userMessage) {
        Skill skill = skills.get(skillId);
        if (skill == null) return userMessage;
        return "你是一个" + skill.name + "。" + skill.description + "。\n\n用户问题: " + userMessage;
    }

    public static class Skill {
        public final String id;
        public final String name;
        public final String description;
        public final String keywords;

        public Skill(String id, String name, String description, String keywords) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.keywords = keywords;
        }
    }
}

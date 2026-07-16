package com.oilquiz.app.ai.skill;

import android.content.Context;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.tool.AIToolsManager;
import com.oilquiz.app.util.AILogger;

public class DynamicSkillExecutor {
    private static final String TAG = "DynamicSkillExecutor";

    private final Context context;
    private final SkillManager skillManager;
    private final AIToolsManager toolsManager;

    public DynamicSkillExecutor(Context context, SkillManager skillManager, AIToolsManager toolsManager) {
        this.context = context.getApplicationContext();
        this.skillManager = skillManager;
        this.toolsManager = toolsManager;
    }

    public String executeWithSkill(String skillId, String message) {
        SkillManager.Skill skill = skillManager.getSkill(skillId);
        if (skill == null) {
            AILogger.w(TAG, "Skill not found: " + skillId);
            return "未找到技能: " + skillId;
        }

        try {
            String prompt = skillManager.buildSkillPrompt(skillId, message);
            AIService aiService = AIService.getInstance(context);
            return aiService.generateSync(prompt, 512);
        } catch (Exception e) {
            AILogger.e(TAG, "Skill execution failed: " + e.getMessage());
            return "技能执行失败: " + e.getMessage();
        }
    }
}

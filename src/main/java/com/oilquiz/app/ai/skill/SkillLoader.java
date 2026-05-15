package com.oilquiz.app.ai.skill;

import android.content.Context;
import com.oilquiz.app.util.AILogger;

import java.util.List;

public class SkillLoader {
    private static final String TAG = "SkillLoader";

    private final Context context;
    private final SkillManager skillManager;

    public SkillLoader(Context context, SkillManager skillManager) {
        this.context = context.getApplicationContext();
        this.skillManager = skillManager;
    }

    public void loadDefaultSkills() {
        AILogger.i(TAG, "Default skills already registered by SkillManager");
    }

    public List<SkillManager.Skill> getLoadedSkills() {
        return skillManager.getAllSkills();
    }

    public boolean isSkillAvailable(String skillId) {
        return skillManager.getSkill(skillId) != null;
    }
}

package com.oilquiz.app.ai.agent.software.model;

import java.util.ArrayList;
import java.util.List;

/**
 * TaskPlan - 任务计划
 */
public class TaskPlan {
    private final String originalMessage;
    private final IntentResult intent;
    private final List<Task> tasks;
    
    public TaskPlan(String originalMessage, IntentResult intent) {
        this.originalMessage = originalMessage;
        this.intent = intent;
        this.tasks = new ArrayList<>();
    }
    
    /**
     * 创建简单任务计划
     */
    public static TaskPlan simple(String userMessage, IntentResult intent) {
        TaskPlan plan = new TaskPlan(userMessage, intent);
        Task task = new Task(java.util.UUID.randomUUID().toString());
        task.setDescription(userMessage);
        task.setIntentType(intent.type);
        task.setEntity(intent.entity);
        task.setNeedsTool(intent.needsTool());
        plan.addTask(task);
        return plan;
    }
    
    public void addTask(Task task) {
        tasks.add(task);
    }
    
    public String getOriginalMessage() { return originalMessage; }
    public IntentResult getIntent() { return intent; }
    public List<Task> getTasks() { return tasks; }
    public int getTaskCount() { return tasks.size(); }
}

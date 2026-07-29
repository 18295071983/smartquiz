package com.oilquiz.app.ai.agent.software;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.oilquiz.app.ai.agent.software.model.ExecutionResult;
import com.oilquiz.app.ai.agent.software.model.ThinkingChain;
import com.oilquiz.app.ai.agent.software.model.ThinkingStep;
import com.oilquiz.app.ai.agent.software.model.TaskResult;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

public class AgentCheckpointManager {
    private static final String TAG = "AgentCheckpointManager";
    private static final String CHECKPOINT_FILE = "agent_checkpoint.json";
    
    private final Context context;
    private final Gson gson;
    
    public AgentCheckpointManager(Context context) {
        this.context = context.getApplicationContext();
        this.gson = new Gson();
    }
    
    public static class Checkpoint {
        public String messageId;
        public String userMessage;
        public int currentStep;
        public int totalSteps;
        public Map<String, TaskResult> completedTasks;
        public String lastThought;
        public String lastAction;
        public String lastObservation;
        public long timestamp;
        public boolean isRecovering;
        
        public Checkpoint() {
            completedTasks = new HashMap<>();
            isRecovering = false;
        }
    }
    
    public void saveCheckpoint(String messageId, String userMessage, int currentStep, 
                               int totalSteps, ExecutionResult executionResult,
                               String thought, String action, String observation) {
        try {
            Checkpoint checkpoint = new Checkpoint();
            checkpoint.messageId = messageId;
            checkpoint.userMessage = userMessage;
            checkpoint.currentStep = currentStep;
            checkpoint.totalSteps = totalSteps;
            checkpoint.lastThought = thought;
            checkpoint.lastAction = action;
            checkpoint.lastObservation = observation;
            checkpoint.timestamp = System.currentTimeMillis();
            
            if (executionResult != null && executionResult.getTaskResults() != null) {
                checkpoint.completedTasks = new HashMap<>(executionResult.getTaskResults());
            }
            
            File file = new File(context.getFilesDir(), CHECKPOINT_FILE);
            FileWriter writer = new FileWriter(file);
            gson.toJson(checkpoint, writer);
            writer.close();
            
            Log.i(TAG, "Checkpoint saved for message: " + messageId + ", step: " + currentStep);
        } catch (IOException e) {
            Log.e(TAG, "Failed to save checkpoint", e);
        }
    }
    
    public Checkpoint loadCheckpoint() {
        try {
            File file = new File(context.getFilesDir(), CHECKPOINT_FILE);
            if (!file.exists()) {
                return null;
            }
            
            FileReader reader = new FileReader(file);
            Checkpoint checkpoint = gson.fromJson(reader, Checkpoint.class);
            reader.close();
            
            Log.i(TAG, "Checkpoint loaded: " + checkpoint.messageId + ", step: " + checkpoint.currentStep);
            return checkpoint;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load checkpoint", e);
            deleteCheckpoint();
            return null;
        }
    }
    
    public void deleteCheckpoint() {
        File file = new File(context.getFilesDir(), CHECKPOINT_FILE);
        if (file.exists()) {
            file.delete();
            Log.i(TAG, "Checkpoint deleted");
        }
    }
    
    public boolean hasCheckpoint() {
        File file = new File(context.getFilesDir(), CHECKPOINT_FILE);
        return file.exists() && file.length() > 0;
    }
    
    public boolean isCheckpointExpired(long maxAgeMs) {
        Checkpoint checkpoint = loadCheckpoint();
        if (checkpoint == null) return true;
        return System.currentTimeMillis() - checkpoint.timestamp > maxAgeMs;
    }
    
    public ExecutionResult createExecutionResultFromCheckpoint(Checkpoint checkpoint) {
        if (checkpoint == null || checkpoint.completedTasks == null) {
            return null;
        }
        ExecutionResult result = new ExecutionResult();
        for (Map.Entry<String, TaskResult> entry : checkpoint.completedTasks.entrySet()) {
            result.addTaskResult(entry.getKey(), entry.getValue());
        }
        return result;
    }
}
package com.oilquiz.app.ai.tool.openai;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class ToolCall {
    
    private final String id;
    private final String toolName;
    private final Map<String, Object> arguments;
    
    public ToolCall(String id, String toolName, Map<String, Object> arguments) {
        this.id = id;
        this.toolName = toolName;
        this.arguments = arguments != null ? arguments : new HashMap<>();
    }
    
    public String getId() {
        return id;
    }
    
    public String getToolName() {
        return toolName;
    }
    
    public Map<String, Object> getArguments() {
        return arguments;
    }
    
    public Object getArgument(String key) {
        return arguments.get(key);
    }
    
    public void addArgument(String key, Object value) {
        arguments.put(key, value);
    }
    
    public JSONObject toOpenAIFormat() throws JSONException {
        JSONObject toolCall = new JSONObject();
        toolCall.put("id", id != null ? id : "call_" + System.currentTimeMillis());
        toolCall.put("type", "function");
        
        JSONObject function = new JSONObject();
        function.put("name", toolName);
        
        JSONObject args = new JSONObject();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            args.put(entry.getKey(), entry.getValue());
        }
        function.put("arguments", args);
        
        toolCall.put("function", function);
        return toolCall;
    }
    
    public static ToolCall fromJSON(String jsonStr) throws JSONException {
        JSONObject json = new JSONObject(jsonStr);
        
        String id = json.optString("id", null);
        String type = json.optString("type", "function");
        
        if (!"function".equals(type)) {
            throw new JSONException("Unsupported tool call type: " + type);
        }
        
        JSONObject function = json.getJSONObject("function");
        String name = function.getString("name");
        
        JSONObject args = function.getJSONObject("arguments");
        Map<String, Object> arguments = new HashMap<>();
        Iterator<String> keys = args.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            arguments.put(key, args.get(key));
        }
        
        return new ToolCall(id, name, arguments);
    }
    
    public static ToolCall fromJSONObject(JSONObject json) throws JSONException {
        String id = json.optString("id", null);
        String type = json.optString("type", "function");
        
        if (!"function".equals(type)) {
            throw new JSONException("Unsupported tool call type: " + type);
        }
        
        JSONObject function = json.getJSONObject("function");
        String name = function.getString("name");
        
        JSONObject args = function.getJSONObject("arguments");
        Map<String, Object> arguments = new HashMap<>();
        Iterator<String> keys = args.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            arguments.put(key, args.get(key));
        }
        
        return new ToolCall(id, name, arguments);
    }
}
package com.oilquiz.app.ai.usage.tracker;

import java.util.ArrayList;
import java.util.List;

/**
 * 全链路追踪管理器
 * 生成 traceId, 记录各阶段 Span
 */
public class TraceManager {
    
    /**
     * Span (阶段)
     */
    public static class TraceSpan {
        public final String spanId;
        public final String name;
        public final long startTime;
        public final long endTime;
        public final long duration;
        public final String status;
        public final String error;
        
        public TraceSpan(String spanId, String name, long startTime, long endTime,
                         String status, String error) {
            this.spanId = spanId;
            this.name = name;
            this.startTime = startTime;
            this.endTime = endTime;
            this.duration = endTime - startTime;
            this.status = status;
            this.error = error;
        }
    }
    
    /**
     * 追踪记录
     */
    public static class TraceRecord {
        private final String traceId;
        private final String sessionId;
        private final String userId;
        private final List<TraceSpan> spans;
        
        public TraceRecord(String traceId, String sessionId, String userId) {
            this.traceId = traceId;
            this.sessionId = sessionId;
            this.userId = userId;
            this.spans = new ArrayList<>();
        }
        
        public void addSpan(TraceSpan span) {
            spans.add(span);
        }
        
        public String getTraceId() { return traceId; }
        public String getSessionId() { return sessionId; }
        public String getUserId() { return userId; }
        public List<TraceSpan> getSpans() { return spans; }
    }
    
    /**
     * 生成唯一 traceId
     */
    public static String generateTraceId() {
        return java.util.UUID.randomUUID().toString();
    }
    
    /**
     * 创建追踪记录
     */
    public static TraceRecord createTrace(String sessionId, String userId) {
        return new TraceRecord(generateTraceId(), sessionId, userId);
    }
}

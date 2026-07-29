package com.oilquiz.app.ai.agent.software.thinking;

import com.oilquiz.app.ai.agent.software.thinking.model.*;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.util.AILogger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ThinkingAssistantEngine {
    private static final String TAG = "ThinkingAssistantEngine";
    private static final long SESSION_TIMEOUT_MS = 30 * 60 * 1000L;

    private final ThinkingAnalyzer analyzer;
    private final QuestionGenerator questionGenerator;
    private final PerspectiveEngine perspectiveEngine;
    private final ReflectionEngine reflectionEngine;
    private final Summarizer summarizer;

    private final Map<String, ThinkingSession> activeSessions;

    public ThinkingAssistantEngine(AIService aiService) {
        this.analyzer = new ThinkingAnalyzer(aiService);
        this.questionGenerator = new QuestionGenerator(aiService);
        this.perspectiveEngine = new PerspectiveEngine(aiService);
        this.reflectionEngine = new ReflectionEngine(aiService);
        this.summarizer = new Summarizer(aiService);
        this.activeSessions = new ConcurrentHashMap<>();
    }

    public ThinkingSession startSession(String userQuestion) {
        ThinkingSession session = new ThinkingSession();
        session.setUserQuestion(userQuestion);
        activeSessions.put(session.getSessionId(), session);
        AILogger.i(TAG, "Started thinking session: " + session.getSessionId() +
                " for: " + truncate(userQuestion, 50));
        return session;
    }

    public void endSession(String sessionId) {
        activeSessions.remove(sessionId);
        AILogger.i(TAG, "Ended thinking session: " + sessionId);
    }

    public ThinkingSession getSession(String sessionId) {
        ThinkingSession session = activeSessions.get(sessionId);
        if (session != null && session.isExpired(SESSION_TIMEOUT_MS)) {
            AILogger.w(TAG, "Session expired: " + sessionId);
            return null;
        }
        return session;
    }

    public void cleanupExpiredSessions() {
        int expired = 0;
        for (Map.Entry<String, ThinkingSession> entry : activeSessions.entrySet()) {
            if (entry.getValue().isExpired(SESSION_TIMEOUT_MS)) {
                activeSessions.remove(entry.getKey());
                expired++;
            }
        }
        if (expired > 0) {
            AILogger.i(TAG, "Cleaned up " + expired + " expired sessions");
        }
    }

    public int getActiveSessionCount() {
        cleanupExpiredSessions();
        return activeSessions.size();
    }

    public ProcessResult processMessage(ThinkingSession session, String userMessage) {
        if (session == null || userMessage == null || userMessage.trim().isEmpty()) {
            return ProcessResult.error("思考会话不可用或消息为空");
        }

        try {
            session.addMessage(ThinkingMessage.userMessage(userMessage));

            ThinkingAnalyzer.AnalysisResult analysis = analyzer.analyze(userMessage, session);
            updateSessionState(session, analysis);

            String contextStr = buildContextString(session);
            QuestionGenerator.GeneratedQuestion question = questionGenerator.generate(session, analysis);

            session.addMessage(ThinkingMessage.aiQuestion(
                    question.getQuestion(), question.getQuestionType(), question.getReason()));
            session.incrementDepth();

            return new ProcessResult(
                    question.getQuestion(),
                    question.getQuestionType(),
                    question.getReason(),
                    analysis,
                    session.getState());

        } catch (Exception e) {
            AILogger.e(TAG, "Error processing message: " + e.getMessage(), e);
            return ProcessResult.error("思考引擎处理出错，请稍后再试");
        }
    }

    public ProcessResult processWithPerspective(ThinkingSession session, String userMessage) {
        if (session == null || userMessage == null) {
            return ProcessResult.error("思考会话不可用");
        }

        try {
            ThinkingAnalyzer.AnalysisResult analysis = analyzer.analyze(userMessage, session);
            PerspectiveEngine.PerspectiveResult perspective = perspectiveEngine.suggestPerspective(session, userMessage);

            if (session.getContext() != null) {
                session.getContext().addExploredAspect(perspective.getAspect());
            }

            StringBuilder response = new StringBuilder();
            response.append("从「").append(perspective.getAspect()).append("」的角度来思考：\n");
            response.append(perspective.getContent());

            if (perspective.getSuggestion() != null && !perspective.getSuggestion().isEmpty()) {
                response.append("\n\n建议：").append(perspective.getSuggestion());
            }

            return new ProcessResult(
                    response.toString(),
                    "PERSPECTIVE",
                    "多角度思考",
                    analysis,
                    session.getState());

        } catch (Exception e) {
            AILogger.e(TAG, "Error processing perspective: " + e.getMessage(), e);
            return ProcessResult.error("视角生成出错");
        }
    }

    public ProcessResult processWithReflection(ThinkingSession session, String userMessage) {
        if (session == null || userMessage == null) {
            return ProcessResult.error("思考会话不可用");
        }

        try {
            ThinkingAnalyzer.AnalysisResult analysis = analyzer.analyze(userMessage, session);
            ReflectionEngine.ReflectionResult reflection = reflectionEngine.reflect(session, userMessage);

            StringBuilder response = new StringBuilder();
            response.append("【反思】\n").append(reflection.getReflection());

            if (reflection.getBlindSpot() != null && !reflection.getBlindSpot().isEmpty()) {
                response.append("\n\n【可能的盲点】\n").append(reflection.getBlindSpot());
            }
            if (reflection.getAssumption() != null && !reflection.getAssumption().isEmpty()) {
                response.append("\n\n【隐含假设】\n").append(reflection.getAssumption());
            }

            session.addMessage(ThinkingMessage.aiReflection(response.toString()));

            return new ProcessResult(
                    response.toString(),
                    "REFLECTION",
                    "深度反思",
                    analysis,
                    session.getState());

        } catch (Exception e) {
            AILogger.e(TAG, "Error processing reflection: " + e.getMessage(), e);
            return ProcessResult.error("反思处理出错");
        }
    }

    public Summarizer.SummaryResult summarizeSession(ThinkingSession session) {
        if (session == null) return null;
        return summarizer.summarize(session);
    }

    private void updateSessionState(ThinkingSession session, ThinkingAnalyzer.AnalysisResult analysis) {
        if (session.getContext() != null) {
            session.getContext().setClarity(analysis.clarity);
            session.getContext().setDepth(analysis.depth);
            session.getContext().setInformation(analysis.information);
            session.getContext().setEmotion(analysis.emotion);
        }

        if (analysis.clarity <= 2) {
            session.setState(ThinkingState.EXPLORING);
        } else if (analysis.depth >= 4) {
            session.setState(ThinkingState.DECIDING);
        } else if (analysis.depth >= 2) {
            session.setState(ThinkingState.ANALYZING);
        } else {
            session.setState(ThinkingState.EXPLORING);
        }
    }

    private String buildContextString(ThinkingSession session) {
        StringBuilder sb = new StringBuilder();
        ThinkingContext ctx = session.getContext();
        if (ctx.getClarity() > 0) {
            sb.append("[清晰度:").append(ctx.getClarity()).append("/5] ");
        }
        if (!ctx.getKeyPoints().isEmpty()) {
            sb.append("[关键点:").append(String.join(",", ctx.getKeyPoints())).append("] ");
        }
        return sb.toString();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }

    public static class ProcessResult {
        private String response;
        private String responseType;
        private String reason;
        private ThinkingAnalyzer.AnalysisResult analysis;
        private ThinkingState state;
        private boolean success;
        private String error;

        public ProcessResult(String response, String responseType, String reason,
                            ThinkingAnalyzer.AnalysisResult analysis, ThinkingState state) {
            this.response = response;
            this.responseType = responseType;
            this.reason = reason;
            this.analysis = analysis;
            this.state = state;
            this.success = true;
        }

        public static ProcessResult error(String error) {
            ProcessResult result = new ProcessResult("", "", "", null, ThinkingState.EXPLORING);
            result.success = false;
            result.error = error;
            return result;
        }

        public boolean isSuccess() { return success; }
        public String getResponse() { return response; }
        public String getResponseType() { return responseType; }
        public String getReason() { return reason; }
        public ThinkingAnalyzer.AnalysisResult getAnalysis() { return analysis; }
        public ThinkingState getState() { return state; }
        public String getError() { return error; }
    }
}

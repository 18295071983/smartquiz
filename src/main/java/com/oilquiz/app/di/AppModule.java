package com.oilquiz.app.di;

import android.content.Context;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.live.MutableChatList;
import com.oilquiz.app.ai.inference.InferenceRouter;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.refactor.AIConfig;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.service.ModelListFetcher;
import com.oilquiz.app.ai.service.UsageTracker;
import com.oilquiz.app.ai.util.APIKeyManager;
import com.oilquiz.app.ai.util.ChatHistoryManager;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;

/**
 * AppModule - Hilt 依赖注入模块
 * 
 * 提供应用级别的单例依赖
 */
@Module
@InstallIn(SingletonComponent.class)
public class AppModule {

    @Provides
    @Singleton
    public AIService provideAIService(@ApplicationContext Context context) {
        return AIService.getInstance(context);
    }

    @Provides
    @Singleton
    public InferenceRouter provideInferenceRouter(@ApplicationContext Context context) {
        return InferenceRouter.getInstance(context);
    }

    @Provides
    @Singleton
    public OnlineModelManager provideOnlineModelManager(@ApplicationContext Context context) {
        return OnlineModelManager.getInstance(context);
    }

    @Provides
    @Singleton
    public AIConfig provideAIConfig(@ApplicationContext Context context) {
        return new AIConfig(context);
    }

    @Provides
    @Singleton
    public APIKeyManager provideAPIKeyManager(@ApplicationContext Context context) {
        return APIKeyManager.getInstance(context);
    }

    @Provides
    @Singleton
    public ChatHistoryManager provideChatHistoryManager(@ApplicationContext Context context) {
        return new ChatHistoryManager(context);
    }

    @Provides
    @Singleton
    public ModelListFetcher provideModelListFetcher(@ApplicationContext Context context) {
        return ModelListFetcher.getInstance(context);
    }

    @Provides
    @Singleton
    public UsageTracker provideUsageTracker(@ApplicationContext Context context) {
        return UsageTracker.getInstance(context);
    }

    @Provides
    @Singleton
    public ExecutorService provideExecutorService() {
        return Executors.newFixedThreadPool(4);
    }

    @Provides
    @Singleton
    public MutableChatList provideMutableChatList() {
        return new MutableChatList();
    }

    @Provides
    @Singleton
    public List<ChatMessage> provideChatHistory() {
        return new ArrayList<>();
    }

    @Provides
    @Singleton
    public com.oilquiz.app.viewmodel.QuestionViewModel provideQuestionViewModel(@ApplicationContext Context context) {
        return new com.oilquiz.app.viewmodel.QuestionViewModel((android.app.Application) context.getApplicationContext());
    }

    @Provides
    @Singleton
    public com.oilquiz.app.util.export.ExportManager provideExportManager(@ApplicationContext Context context) {
        return com.oilquiz.app.util.export.ExportManager.getInstance();
    }
}

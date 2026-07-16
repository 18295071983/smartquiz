package com.oilquiz.app.di;

import android.app.Application;

import com.oilquiz.app.repository.QuestionRepository;
import com.oilquiz.app.repository.UserRepository;
import com.oilquiz.app.util.export.ExportManager;
import com.oilquiz.app.viewmodel.QuestionViewModel;

import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;

/**
 * Hilt 应用模块
 * 提供应用级别的依赖
 */
@Module
@InstallIn(SingletonComponent.class)
public class AppModule {

    /**
     * 提供题目仓库
     */
    @Provides
    @Singleton
    public QuestionRepository provideQuestionRepository(Application application) {
        return new QuestionRepository(application);
    }

    /**
     * 提供用户仓库
     */
    @Provides
    @Singleton
    public UserRepository provideUserRepository(Application application) {
        return new UserRepository(application);
    }

    /**
     * 提供题目 ViewModel
     */
    @Provides
    @Singleton
    public QuestionViewModel provideQuestionViewModel(Application application) {
        return new QuestionViewModel(application);
    }

    /**
     * 提供导出管理器
     */
    @Provides
    @Singleton
    public ExportManager provideExportManager() {
        return ExportManager.getInstance();
    }
}

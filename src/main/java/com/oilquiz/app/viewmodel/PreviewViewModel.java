package com.oilquiz.app.viewmodel;

import android.app.Application;
import android.util.Log;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;

import com.oilquiz.app.util.PreviewRenderBridge;

import java.io.File;

/**
 * 预览和渲染功能的ViewModel，用于管理预览和渲染状态
 */
public class PreviewViewModel extends AndroidViewModel {
    private static final String TAG = "PreviewViewModel";
    private PreviewRenderBridge previewRenderBridge;
    private MutableLiveData<PreviewState> previewState;
    private MutableLiveData<Integer> progress;

    public PreviewViewModel(Application application) {
        super(application);
        previewRenderBridge = new PreviewRenderBridge(application);
        previewState = new MutableLiveData<>(new PreviewState());
        progress = new MutableLiveData<>(0);
    }

    /**
     * 预览文件
     * @param file 要预览的文件
     */
    public void previewFile(File file) {
        // 更新状态为加载中
        PreviewState state = previewState.getValue();
        if (state != null) {
            state.setLoading(true);
            state.setError(null);
            state.setContent(null);
            previewState.setValue(state);
        }

        // 开始预览
        previewRenderBridge.previewFile(file, new PreviewRenderBridge.PreviewCallback() {
            @Override
            public void onSuccess(Object previewContent) {
                // 更新状态为成功
                PreviewState successState = previewState.getValue();
                if (successState != null) {
                    successState.setLoading(false);
                    successState.setError(null);
                    successState.setContent(previewContent);
                    previewState.setValue(successState);
                }
                progress.setValue(100);
            }

            @Override
            public void onError(String error) {
                // 更新状态为错误
                PreviewState errorState = previewState.getValue();
                if (errorState != null) {
                    errorState.setLoading(false);
                    errorState.setError(error);
                    errorState.setContent(null);
                    previewState.setValue(errorState);
                }
                Log.e(TAG, "预览失败: " + error);
            }

            @Override
            public void onProgress(int progressValue) {
                // 更新进度
                progress.setValue(progressValue);
            }
        });
    }

    /**
     * 检查文件是否可预览
     * @param file 要检查的文件
     * @return 是否可预览
     */
    public boolean canPreview(File file) {
        return previewRenderBridge.canPreview(file);
    }

    /**
     * 清理预览缓存
     */
    public void clearPreviewCache() {
        previewRenderBridge.clearPreviewCache();
        Log.i(TAG, "预览缓存已清理");
    }

    /**
     * 获取预览缓存大小
     * @return 缓存大小（字节）
     */
    public long getPreviewCacheSize() {
        return previewRenderBridge.getPreviewCacheSize();
    }

    /**
     * 获取预览状态
     * @return 预览状态LiveData
     */
    public MutableLiveData<PreviewState> getPreviewState() {
        return previewState;
    }

    /**
     * 获取预览进度
     * @return 进度LiveData
     */
    public MutableLiveData<Integer> getProgress() {
        return progress;
    }

    /**
     * 预览状态类
     */
    public static class PreviewState {
        private boolean loading;
        private String error;
        private Object content;

        public PreviewState() {
            this.loading = false;
            this.error = null;
            this.content = null;
        }

        public boolean isLoading() {
            return loading;
        }

        public void setLoading(boolean loading) {
            this.loading = loading;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }

        public Object getContent() {
            return content;
        }

        public void setContent(Object content) {
            this.content = content;
        }
    }
}
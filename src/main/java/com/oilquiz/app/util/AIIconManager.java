package com.oilquiz.app.util;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.widget.ImageView;

import com.oilquiz.app.R;
import com.oilquiz.app.util.AIIconMapper.AIIconCategory;
import com.oilquiz.app.util.AIIconMapper.AIIconInfo;

import java.util.HashMap;
import java.util.Map;

public class AIIconManager {

    private static final String TAG = "AIIconManager";
    private static AIIconManager instance;
    private final Context context;
    private final Map<String, Drawable> cachedDrawables = new HashMap<>();

    private AIIconManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized AIIconManager getInstance(Context context) {
        if (instance == null) {
            instance = new AIIconManager(context);
        }
        return instance;
    }

    public int getIconResource(String iconKey) {
        return AIIconMapper.getIconResource(iconKey);
    }

    public Drawable getIconDrawable(String iconKey) {
        if (iconKey == null || iconKey.isEmpty()) {
            return getDefaultIconDrawable();
        }

        if (cachedDrawables.containsKey(iconKey)) {
            return cachedDrawables.get(iconKey);
        }

        try {
            int resId = AIIconMapper.getIconResource(iconKey);
            Drawable drawable = context.getDrawable(resId);
            if (drawable != null) {
                cachedDrawables.put(iconKey, drawable);
                return drawable;
            }
        } catch (Resources.NotFoundException e) {
            Log.e(TAG, "Icon not found: " + iconKey, e);
        }

        return getDefaultIconDrawable();
    }

    public Drawable getIconDrawableForIntent(String intentType) {
        int resId = AIIconMapper.getIconForIntent(intentType);
        try {
            return context.getDrawable(resId);
        } catch (Resources.NotFoundException e) {
            Log.e(TAG, "Icon not found for intent: " + intentType, e);
            return getDefaultIconDrawable();
        }
    }

    public Drawable getIconDrawableByCategory(AIIconCategory category, String action) {
        int resId = AIIconMapper.getIconByCategoryAndAction(category, action);
        try {
            return context.getDrawable(resId);
        } catch (Resources.NotFoundException e) {
            Log.e(TAG, "Icon not found for category/action: " + category + "/" + action, e);
            return getDefaultIconDrawable();
        }
    }

    public void setIconToImageView(String iconKey, ImageView imageView) {
        if (imageView == null) {
            return;
        }

        Drawable drawable = getIconDrawable(iconKey);
        if (drawable != null) {
            imageView.setImageDrawable(drawable);
        } else {
            imageView.setImageResource(AIIconMapper.UNKNOWN_ICON);
        }
    }

    public void setIconForIntentToImageView(String intentType, ImageView imageView) {
        if (imageView == null) {
            return;
        }

        Drawable drawable = getIconDrawableForIntent(intentType);
        if (drawable != null) {
            imageView.setImageDrawable(drawable);
        } else {
            imageView.setImageResource(AIIconMapper.UNKNOWN_ICON);
        }
    }

    public AIIconInfo getIconInfo(String iconKey) {
        return AIIconMapper.getIconInfo(iconKey);
    }

    public Map<String, Integer> getAllIconResources() {
        return AIIconMapper.getAllIcons();
    }

    public Map<String, AIIconInfo> getAllIconInfo() {
        return AIIconMapper.getAllIconInfo();
    }

    private Drawable getDefaultIconDrawable() {
        return context.getDrawable(AIIconMapper.UNKNOWN_ICON);
    }

    public void clearCache() {
        cachedDrawables.clear();
    }

    public int getCacheSize() {
        return cachedDrawables.size();
    }

    public static class IconResource {
        public static final int AI_BRAIN = R.drawable.ic_ai_brain;
        public static final int AI_ROBOT = R.drawable.ic_ai_robot;
        public static final int AI_THINKING = R.drawable.ic_ai_thinking;
        public static final int AI_RESPONSE = R.drawable.ic_ai_response;
        public static final int AI_ANALYZE = R.drawable.ic_ai_analyze;
        public static final int AI_TRANSLATE = R.drawable.ic_ai_translate;
        public static final int AI_GENERATE = R.drawable.ic_ai_generate;
        public static final int AI_SUMMARIZE = R.drawable.ic_ai_summarize;
        public static final int AI_QUESTION = R.drawable.ic_ai_question;
        public static final int AI_TOOL = R.drawable.ic_ai_tool;
        public static final int AI_TOOL_CALL = R.drawable.ic_ai_tool_call;
        public static final int AI_OCR = R.drawable.ic_ai_ocr;
        public static final int AI_MODEL = R.drawable.ic_ai_model;
        public static final int AI_DOWNLOAD = R.drawable.ic_ai_download;
        public static final int AI_UPLOAD = R.drawable.ic_ai_upload;
        public static final int AI_LOADING = R.drawable.ic_ai_loading;
        public static final int AI_SUCCESS = R.drawable.ic_ai_success;
        public static final int AI_ERROR = R.drawable.ic_ai_error;
        public static final int AI_WARNING = R.drawable.ic_ai_warning;
        public static final int AI_LEARNING = R.drawable.ic_ai_learning;
        public static final int AI_AGENT = R.drawable.ic_ai_agent;
        public static final int AI_CHAT_BUBBLE = R.drawable.ic_ai_chat_bubble;
        public static final int AI_MIC = R.drawable.ic_ai_mic;
        public static final int AI_CODE = R.drawable.ic_ai_code;
        public static final int AI_IMAGE = R.drawable.ic_ai_image;
        public static final int AI_MEMORY = R.drawable.ic_ai_memory;
        public static final int AI_CONNECT = R.drawable.ic_ai_connect;
        public static final int AI_CHAT = R.drawable.ic_ai_chat;

        public static int getByKey(String key) {
            switch (key) {
                case "ai_brain": return AI_BRAIN;
                case "ai_robot": return AI_ROBOT;
                case "ai_thinking": return AI_THINKING;
                case "ai_response": return AI_RESPONSE;
                case "ai_analyze": return AI_ANALYZE;
                case "ai_translate": return AI_TRANSLATE;
                case "ai_generate": return AI_GENERATE;
                case "ai_summarize": return AI_SUMMARIZE;
                case "ai_question": return AI_QUESTION;
                case "ai_tool": return AI_TOOL;
                case "ai_tool_call": return AI_TOOL_CALL;
                case "ai_ocr": return AI_OCR;
                case "ai_model": return AI_MODEL;
                case "ai_download": return AI_DOWNLOAD;
                case "ai_upload": return AI_UPLOAD;
                case "ai_loading": return AI_LOADING;
                case "ai_success": return AI_SUCCESS;
                case "ai_error": return AI_ERROR;
                case "ai_warning": return AI_WARNING;
                case "ai_learning": return AI_LEARNING;
                case "ai_agent": return AI_AGENT;
                case "ai_chat_bubble": return AI_CHAT_BUBBLE;
                case "ai_mic": return AI_MIC;
                case "ai_code": return AI_CODE;
                case "ai_image": return AI_IMAGE;
                case "ai_memory": return AI_MEMORY;
                case "ai_connect": return AI_CONNECT;
                case "ai_chat": return AI_CHAT;
                default: return AIIconMapper.UNKNOWN_ICON;
            }
        }
    }
}

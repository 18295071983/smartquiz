package com.oilquiz.app.ui.accessibility;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.oilquiz.app.R;

import java.util.HashSet;
import java.util.Set;

/**
 * 无障碍（Accessibility）辅助工具类
 * 提供 TalkBack 兼容、内容描述自动生成等功能
 */
public class AccessibilityHelper {
    private static final String TAG = "AccessibilityHelper";
    private static volatile AccessibilityHelper instance;
    
    private final Context context;
    private final AccessibilityManager accessibilityManager;
    private final boolean isScreenReaderEnabled;

    private AccessibilityHelper(Context context) {
        this.context = context.getApplicationContext();
        this.accessibilityManager = (AccessibilityManager) 
            context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        this.isScreenReaderEnabled = accessibilityManager != null && 
            accessibilityManager.isEnabled();
    }
    
    public static AccessibilityHelper getInstance(Context context) {
        if (instance == null) {
            synchronized (AccessibilityHelper.class) {
                if (instance == null) {
                    instance = new AccessibilityHelper(context);
                }
            }
        }
        return instance;
    }

    /**
     * 检查 TalkBack 是否启用
     */
    public boolean isScreenReaderEnabled() {
        return isScreenReaderEnabled;
    }

    /**
     * 检查辅助功能服务是否启用
     */
    public boolean isAccessibilityServiceEnabled() {
        if (accessibilityManager == null) return false;
        
        for (android.accessibilityservice.AccessibilityServiceInfo service : 
                accessibilityManager.getEnabledAccessibilityServiceList(
                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (service.getResolveInfo().serviceInfo.packageName.equals(context.getPackageName())) {
                return true;
            }
        }
        return false;
    }

    // ==================== ContentDescription 生成 ====================
    
    /**
     * 为题目卡片生成完整的无障碍描述
     */
    public static String generateQuestionAccessibilityDescription(
            String questionText,
            String questionType,
            String category,
            int difficulty,
            boolean isFavorite,
            boolean hasAnswer) {
        
        StringBuilder description = new StringBuilder();
        
        // 题号
        description.append("题目: ").append(questionText);
        
        // 类型
        if (questionType != null && !questionType.isEmpty()) {
            description.append(", 类型: ").append(questionType);
        }
        
        // 分类
        if (category != null && !category.isEmpty()) {
            description.append(", 分类: ").append(category);
        }
        
        // 难度
        String difficultyStr;
        switch (difficulty) {
            case 1:
                difficultyStr = "简单";
                break;
            case 2:
                difficultyStr = "中等";
                break;
            case 3:
                difficultyStr = "困难";
                break;
            default:
                difficultyStr = "未知";
        }
        description.append(", 难度: ").append(difficultyStr);
        
        // 收藏状态
        if (isFavorite) {
            description.append(", 已收藏");
        }
        
        // 答案状态
        if (hasAnswer) {
            description.append(", 已显示答案");
        }
        
        // 操作提示
        description.append(", 双击查看详情");
        
        return description.toString();
    }

    /**
     * 为日志条目生成无障碍描述
     */
    public static String generateLogAccessibilityDescription(
            String level,
            String tag,
            String message,
            String timestamp) {
        
        StringBuilder description = new StringBuilder();
        
        // 级别
        description.append(level).append("级别日志, ");
        
        // 标签
        if (tag != null && !tag.isEmpty()) {
            description.append("标签: ").append(tag).append(", ");
        }
        
        // 消息
        if (message != null && !message.isEmpty()) {
            // 截断长消息
            String shortMessage = message.length() > 100 
                ? message.substring(0, 100) + "..." 
                : message;
            description.append("内容: ").append(shortMessage);
        }
        
        // 时间
        if (timestamp != null && !timestamp.isEmpty()) {
            description.append(", 时间: ").append(timestamp);
        }
        
        return description.toString();
    }

    /**
     * 为测验模式生成无障碍描述
     */
    public static String generateQuizModeAccessibilityDescription(
            String modeName,
            String description,
            int questionCount,
            String timeLimit) {
        
        StringBuilder sb = new StringBuilder();
        sb.append(modeName).append(": ").append(description);
        sb.append(", 题目数量: ").append(questionCount).append("道");
        
        if (timeLimit != null && !timeLimit.isEmpty()) {
            sb.append(", 时间限制: ").append(timeLimit);
        }
        
        return sb.toString();
    }

    // ==================== View 无障碍设置 ====================
    
    /**
     * 为 ImageButton 设置无障碍属性
     */
    public static void setupImageButton(View view, String contentDescription) {
        if (view instanceof ImageButton) {
            ImageButton button = (ImageButton) view;
            button.setContentDescription(contentDescription);
            button.setFocusable(true);
            button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        } else if (view instanceof ImageView) {
            ImageView imageView = (ImageView) view;
            imageView.setContentDescription(contentDescription);
            imageView.setFocusable(true);
            imageView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
    }

    /**
     * 为题目卡片设置无障碍属性
     */
    public static void setupQuestionCard(
            View cardView,
            String questionText,
            String questionType,
            String category,
            int difficulty,
            boolean isFavorite,
            boolean hasAnswer) {
        
        String description = generateQuestionAccessibilityDescription(
            questionText, questionType, category, difficulty, isFavorite, hasAnswer);
        
        cardView.setContentDescription(description);
        cardView.setFocusable(true);
        cardView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        
        // 添加点击事件说明
        cardView.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(
                    View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_CLICK, "查看详情"));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                    AccessibilityNodeInfo.ACTION_LONG_CLICK, "更多选项"));
            }
        });
    }

    /**
     * 为日志条目设置无障碍属性
     */
    public static void setupLogEntry(
            View entryView,
            String level,
            String tag,
            String message,
            String timestamp) {
        
        String description = generateLogAccessibilityDescription(level, tag, message, timestamp);
        
        entryView.setContentDescription(description);
        entryView.setFocusable(true);
        entryView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    /**
     * 为难度标签设置颜色和描述
     */
    public static void setupDifficultyTag(TextView textView, int difficulty) {
        Context context = textView.getContext();
        
        String difficultyText;
        int backgroundColor;
        int textColor;
        
        switch (difficulty) {
            case 1: // 简单
                difficultyText = "简单";
                backgroundColor = ContextCompat.getColor(context, R.color.difficulty_easy_bg);
                textColor = ContextCompat.getColor(context, R.color.difficulty_easy);
                break;
            case 2: // 中等
                difficultyText = "中等";
                backgroundColor = ContextCompat.getColor(context, R.color.difficulty_medium_bg);
                textColor = ContextCompat.getColor(context, R.color.difficulty_medium);
                break;
            case 3: // 困难
                difficultyText = "困难";
                backgroundColor = ContextCompat.getColor(context, R.color.difficulty_hard_bg);
                textColor = ContextCompat.getColor(context, R.color.difficulty_hard);
                break;
            default:
                difficultyText = "未知";
                backgroundColor = ContextCompat.getColor(context, R.color.gray_700);
                textColor = ContextCompat.getColor(context, R.color.gray_300);
        }
        
        textView.setText(difficultyText);
        textView.setBackgroundColor(backgroundColor);
        textView.setTextColor(textColor);
        
        // 无障碍描述
        textView.setContentDescription("难度: " + difficultyText);
    }

    /**
     * 为按钮设置无障碍描述
     */
    public static void setupButton(Button button, String customDescription) {
        String description = customDescription != null ? customDescription : button.getText().toString();
        button.setContentDescription(description);
        button.setFocusable(true);
        button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    /**
     * 为列表项设置无障碍属性
     */
    public static void setupListItem(View itemView, String primaryText, String secondaryText) {
        StringBuilder description = new StringBuilder();
        description.append(primaryText);
        if (secondaryText != null && !secondaryText.isEmpty()) {
            description.append(", ").append(secondaryText);
        }
        
        itemView.setContentDescription(description.toString());
        itemView.setFocusable(true);
        itemView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    // ==================== RecyclerView 无障碍 ====================
    
    /**
     * 为 RecyclerView 设置无障碍标签
     */
    public static void setupRecyclerView(RecyclerView recyclerView, String listDescription) {
        recyclerView.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(
                    View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                
                AccessibilityNodeInfo.CollectionInfo collectionInfo = 
                    AccessibilityNodeInfo.CollectionInfo.obtain(
                        1, // 行数（动态）
                        1, // 列数
                        false, // 是否层次结构
                        0 // 是否多选 (0 for false, 1 for true)
                    );
                info.setCollectionInfo(collectionInfo);
                
                // 设置列表描述
                if (listDescription != null && !listDescription.isEmpty()) {
                    info.setPackageName(recyclerView.getContext().getPackageName());
                    // 检查 API 级别，setRoleDescription 是在 API 22 中添加的
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
                        // 使用反射来调用 setRoleDescription 方法，避免 API 级别问题
                        try {
                            java.lang.reflect.Method method = info.getClass().getMethod("setRoleDescription", String.class);
                            method.invoke(info, "列表");
                        } catch (Exception e) {
                            // 忽略异常
                        }
                    }
                }
            }
        });
        
        // 设置内容Description
        recyclerView.setContentDescription(listDescription);
    }

    /**
     * 为 RecyclerView.ViewHolder 设置无障碍属性
     */
    public static void setupViewHolder(
            RecyclerView.ViewHolder holder,
            int position,
            int totalCount,
            String itemDescription) {
        
        holder.itemView.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(
                    View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                
                // 设置位置信息
                // 检查 API 级别，setRoleDescription 是在 API 22 中添加的
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) {
                    // 使用反射来调用 setRoleDescription 方法，避免 API 级别问题
                    try {
                        java.lang.reflect.Method method = info.getClass().getMethod("setRoleDescription", String.class);
                        method.invoke(info, "列表项");
                    } catch (Exception e) {
                        // 忽略异常
                    }
                }
                info.setCollectionItemInfo(
                    AccessibilityNodeInfo.CollectionItemInfo.obtain(
                        position,     // 行
                        1,            // 行跨度
                        0,            // 列
                        1,            // 列跨度
                        false         // 是否选中
                    )
                );
            }
        });
        
        // 设置项目描述
        holder.itemView.setContentDescription(
            String.format("第 %d 项，共 %d 项: %s", 
                position + 1, totalCount, itemDescription)
        );
    }

    // ==================== 无障碍事件 ====================
    
    /**
     * 发送无障碍事件
     */
    public void announceForAccessibility(String message) {
        if (!isScreenReaderEnabled) return;
        
        AccessibilityEvent event = AccessibilityEvent.obtain(
            AccessibilityEvent.TYPE_ANNOUNCEMENT);
        event.setClassName(getClass().getName());
        event.setPackageName(context.getPackageName());
        event.setContentDescription(message);
        
        accessibilityManager.sendAccessibilityEvent(event);
    }

    /**
     * 发送无障碍焦点事件
     */
    public void announceFocus(View view, String message) {
        if (!isScreenReaderEnabled) return;
        
        view.announceForAccessibility(message);
    }

    /**
     * 通知选择变化
     */
    public void notifySelectionChanged(View view, String selection) {
        if (!isScreenReaderEnabled) return;
        
        AccessibilityEvent event = AccessibilityEvent.obtain(
            AccessibilityEvent.TYPE_VIEW_SELECTED);
        event.setContentDescription(selection);
        view.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
    }

    /**
     * 通知文本变化
     */
    public void notifyTextChanged(View view, String newText) {
        if (!isScreenReaderEnabled) return;
        
        AccessibilityEvent event = AccessibilityEvent.obtain(
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED);
        event.setContentDescription(newText);
        view.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED);
    }
}

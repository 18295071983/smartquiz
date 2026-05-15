package com.oilquiz.app.ui.activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.oilquiz.app.resource.AppResourceManager;
import com.oilquiz.app.resource.PermissionResourceProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.tabs.TabLayout;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.service.AIProcessingService;
import com.oilquiz.app.util.AILogger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LogViewerActivity extends AppCompatActivity {

    private static final String TAG = "LogViewerActivity";
    private static final int MAX_LOG_LINES = 1000;
    private static final int MAX_LOG_LENGTH = 100000;
    private static final int REQUEST_FILE_PICKER = 1;
    private static final int REQUEST_WRITE_EXTERNAL_STORAGE = 2;
    
    // 日志类型常量
    public static final int LOG_TYPE_ALL = 0;
    public static final int LOG_TYPE_MODEL = 1;
    public static final int LOG_TYPE_ERROR = 2;
    public static final int LOG_TYPE_SUCCESS = 3;
    public static final int LOG_TYPE_AI_SERVICE = 4; // AI服务日志
    
    // UI组件
    private ListView logListView;
    private ScrollView logScrollView;
    private EditText inputMessage;
    private EditText searchInput;
    private MaterialButton btnSend;
    private MaterialButton btnClearLog;
    private MaterialButton btnCopyLog;
    private MaterialButton btnShareLog;
    private MaterialButton btnExportLog;
    private MaterialButton btnCloseDetail;
    private TextView detailedTitle;
    private TextView detailedContent;
    private View detailedInfoArea;
    private TabLayout tabLayout;
    private Chip chipAll;
    private Chip chipModel;
    private Chip chipError;
    private Chip chipSuccess;
    private Chip chipAIService;
    
    // 数据和适配器
    private List<LogItem> logItems;
    private List<LogItem> filteredLogItems;
    private LogAdapter logAdapter;
    private LogReceiver logReceiver;
    private Handler mainHandler;
    private boolean isDestroyed = false;
    private int currentTabPosition = 0;
    private String currentSearchQuery = "";
    private int currentFilterType = LOG_TYPE_ALL;
    
    // 日志项模型类
    public class LogItem {
        public String timestamp;
        public String message;
        public int type;
        public String level;
        public String details;
        public long time;
        
        public LogItem(String timestamp, String message, int type, String level, String details) {
            this.timestamp = timestamp;
            this.message = message;
            this.type = type;
            this.level = level;
            this.details = details;
            this.time = System.currentTimeMillis();
        }
    }
    
    // 日志适配器
    public class LogAdapter extends BaseAdapter {
        private List<LogItem> items;
        private LayoutInflater inflater;
        
        public LogAdapter(List<LogItem> items) {
            this.items = items;
            this.inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }
        
        @Override
        public int getCount() {
            return items.size();
        }
        
        @Override
        public Object getItem(int position) {
            return items.get(position);
        }
        
        @Override
        public long getItemId(int position) {
            return position;
        }
        
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.log_item_layout, parent, false);
                holder = new ViewHolder();
                holder.timestamp = convertView.findViewById(R.id.log_item_timestamp);
                holder.message = convertView.findViewById(R.id.log_item_message);
                holder.typeIndicator = convertView.findViewById(R.id.log_item_type_indicator);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }
            
            LogItem item = items.get(position);
            holder.timestamp.setText(item.timestamp);
            holder.message.setText(item.message);
            
            // 根据日志类型设置指示器颜色
            switch (item.type) {
                case LOG_TYPE_MODEL:
                    holder.typeIndicator.setBackgroundColor(ContextCompat.getColor(LogViewerActivity.this, R.color.log_model));
                    break;
                case LOG_TYPE_ERROR:
                    holder.typeIndicator.setBackgroundColor(ContextCompat.getColor(LogViewerActivity.this, R.color.log_error));
                    break;
                case LOG_TYPE_SUCCESS:
                    holder.typeIndicator.setBackgroundColor(ContextCompat.getColor(LogViewerActivity.this, R.color.log_success));
                    break;
                default:
                    holder.typeIndicator.setBackgroundColor(ContextCompat.getColor(LogViewerActivity.this, R.color.log_info));
            }
            
            return convertView;
        }
        
        private class ViewHolder {
            TextView timestamp;
            TextView message;
            View typeIndicator;
        }
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_viewer);
        
        try {
            mainHandler = new Handler(Looper.getMainLooper());
            
            // 初始化数据
            logItems = new ArrayList<>();
            filteredLogItems = new ArrayList<>();
            
            // 初始化UI组件
            initUIComponents();
            
            // 初始化适配器
            logAdapter = new LogAdapter(filteredLogItems);
            logListView.setAdapter(logAdapter);
            
            setTitle("AI 服务日志" + " - " + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
            
            loadHistoricalLogs();
            
            logReceiver = new LogReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction(AIProcessingService.ACTION_AI_LOG_UPDATE);
            LocalBroadcastManager.getInstance(this).registerReceiver(logReceiver, filter);
            
            addLogItem("日志查看器已启动", LOG_TYPE_SUCCESS, "INFO", "日志查看器初始化完成，准备接收日志");
            addLogItem("等待接收日志...", LOG_TYPE_SUCCESS, "INFO", "系统已准备就绪，等待AI服务日志");
            
            android.util.Log.d(TAG, "LogViewerActivity onCreate completed");
        } catch (Exception e) {
            android.util.Log.e(TAG, "Error in onCreate: " + e.getMessage());
            showToast("初始化失败: " + e.getMessage());
            finish();
        }
    }
    
    private void initUIComponents() {
        // 日志相关组件
        logListView = findViewById(R.id.log_list_view);
        logScrollView = findViewById(R.id.log_scroll_view);
        searchInput = findViewById(R.id.search_input);
        detailedInfoArea = findViewById(R.id.detailed_info_area);
        detailedTitle = findViewById(R.id.detailed_title);
        detailedContent = findViewById(R.id.detailed_content);
        
        // 标签页
        tabLayout = findViewById(R.id.tab_layout);
        
        // 筛选芯片
        chipAll = findViewById(R.id.chip_all);
        chipModel = findViewById(R.id.chip_model);
        chipError = findViewById(R.id.chip_error);
        chipSuccess = findViewById(R.id.chip_success);
        chipAIService = findViewById(R.id.chip_ai_service); // AI服务筛选芯片
        
        // 按钮
        btnSend = findViewById(R.id.btn_send);
        btnClearLog = findViewById(R.id.btn_clear_log);
        btnCopyLog = findViewById(R.id.btn_copy_log);
        btnShareLog = findViewById(R.id.btn_share_log);
        btnExportLog = findViewById(R.id.btn_export_log);
        btnCloseDetail = findViewById(R.id.btn_close_detail);
        
        // 输入框
        inputMessage = findViewById(R.id.input_message);
        
        // 设置按钮点击监听器
        setButtonListeners();
        
        // 设置列表项点击监听器
        logListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                LogItem item = filteredLogItems.get(position);
                showDetailedInfo(item);
            }
        });
        
        // 设置标签页切换监听器
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                currentTabPosition = tab.getPosition();
                filterLogs();
            }
            
            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}
            
            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });
        
        // 设置搜索输入监听器
        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                currentSearchQuery = s.toString();
                filterLogs();
            }
            
            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });
    }
    
    private void setButtonListeners() {
        // 发送按钮
        if (btnSend != null) {
            btnSend.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String message = inputMessage.getText().toString().trim();
                    if (!TextUtils.isEmpty(message)) {
                        sendMessage(message);
                    }
                }
            });
        }
        
        // 清空日志按钮
        if (btnClearLog != null) {
            btnClearLog.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clearLog();
                }
            });
        }
        
        // 复制日志按钮
        if (btnCopyLog != null) {
            btnCopyLog.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    copyLog();
                }
            });
        }
        
        // 分享日志按钮
        if (btnShareLog != null) {
            btnShareLog.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    shareLog();
                }
            });
        }
        
        // 导出日志按钮
        if (btnExportLog != null) {
            btnExportLog.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    exportLog();
                }
            });
        }
        
        // 关闭详细信息按钮
        if (btnCloseDetail != null) {
            btnCloseDetail.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    detailedInfoArea.setVisibility(View.GONE);
                }
            });
        }
        
        // 筛选芯片
        if (chipAll != null) {
            chipAll.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    currentFilterType = LOG_TYPE_ALL;
                    filterLogs();
                }
            });
        }
        
        if (chipModel != null) {
            chipModel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    currentFilterType = LOG_TYPE_MODEL;
                    filterLogs();
                }
            });
        }
        
        if (chipError != null) {
            chipError.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    currentFilterType = LOG_TYPE_ERROR;
                    filterLogs();
                }
            });
        }
        
        if (chipSuccess != null) {
            chipSuccess.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    currentFilterType = LOG_TYPE_SUCCESS;
                    filterLogs();
                }
            });
        }
        
        if (chipAIService != null) {
            chipAIService.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    currentFilterType = LOG_TYPE_AI_SERVICE;
                    filterLogs();
                }
            });
        }
    }
    
    private void sendMessage(String message) {
        addLogItem("用户输入: " + message, LOG_TYPE_SUCCESS, "INFO", "用户提交了新消息: " + message);
        if (inputMessage != null) {
            inputMessage.setText("");
        }
        
        // 发送消息到AI服务
        Intent intent = new Intent(this, AIProcessingService.class);
        intent.putExtra(AIProcessingService.EXTRA_TASK_TYPE, AIProcessingService.TASK_TYPE_CHAT);
        intent.putExtra(AIProcessingService.EXTRA_PROMPT, message);
        intent.putExtra(AIProcessingService.EXTRA_MAX_TOKENS, 1000);
        
        // 适配 Android 14 的前台服务启动方式
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        
        addLogItem("发送消息到AI服务", LOG_TYPE_SUCCESS, "INFO", "消息已发送到AI处理服务，等待处理结果");
    }
    
    private void clearLog() {
        logItems.clear();
        filteredLogItems.clear();
        logAdapter.notifyDataSetChanged();
        addLogItem("日志已清空", LOG_TYPE_SUCCESS, "INFO", "所有日志已清空，等待接收新日志");
    }
    
    private void copyLog() {
        if (logItems.size() > 0) {
            StringBuilder sb = new StringBuilder();
            for (LogItem item : logItems) {
                sb.append("[").append(item.timestamp).append("] [").append(item.level).append("] ").append(item.message).append("\n");
            }
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("AI Service Log", sb.toString());
            clipboard.setPrimaryClip(clip);
            showToast("日志已复制到剪贴板");
        } else {
            showToast("日志为空，无法复制");
        }
    }
    
    private void shareLog() {
        if (logItems.size() > 0) {
            StringBuilder sb = new StringBuilder();
            for (LogItem item : logItems) {
                sb.append("[").append(item.timestamp).append("] [").append(item.level).append("] ").append(item.message).append("\n");
            }
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, sb.toString());
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "AI Service Log");
            startActivity(Intent.createChooser(shareIntent, "分享日志"));
        } else {
            showToast("日志为空，无法分享");
        }
    }
    
    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "选择文件"), REQUEST_FILE_PICKER);
    }
    
    private void exportLog() {
        if (logItems.size() > 0) {
            AppResourceManager resources = AppResourceManager.getInstance(this);
            if (!resources.hasStoragePermission()) {
                resources.permissions().requestStoragePermission(this, new PermissionResourceProvider.PermissionCallback() {
                    @Override
                    public void onGranted() {
                        exportLog();
                    }

                    @Override
                    public void onDenied(List<String> deniedPermissions) {
                        showToast("需要存储权限才能导出日志");
                    }
                });
                return;
            }
            
            try {
                File logFile = new File(getExternalFilesDir(null), "ai_service_log_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt");
                BufferedWriter writer = new BufferedWriter(new FileWriter(logFile));
                
                writer.write("AI Service Log Export\n");
                writer.write("Export Time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + "\n");
                writer.write("Total Logs: " + logItems.size() + "\n");
                writer.write("====================================\n\n");
                
                for (LogItem item : logItems) {
                    writer.write("[" + item.timestamp + "] [" + item.level + "] [Type: " + getLogTypeString(item.type) + "] " + item.message + "\n");
                    if (!TextUtils.isEmpty(item.details)) {
                        writer.write("Details: " + item.details + "\n\n");
                    } else {
                        writer.write("\n");
                    }
                }
                
                writer.close();
                showToast("日志已导出到: " + logFile.getAbsolutePath());
                
                // 显示分享选项
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                Uri fileUri = FileProvider.getUriForFile(LogViewerActivity.this,
                        getPackageName() + ".fileprovider", logFile);
                shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
                shareIntent.putExtra(Intent.EXTRA_SUBJECT, "AI Service Log Export");
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(shareIntent, "分享导出的日志"));
                
            } catch (IOException e) {
                android.util.Log.e(TAG, "Error exporting log: " + e.getMessage());
                showToast("导出日志失败: " + e.getMessage());
            }
        } else {
            showToast("日志为空，无法导出");
        }
    }
    
    private void loadHistoricalLogs() {
        try {
            String logContent = AILogger.getLogContent();
            if (!TextUtils.isEmpty(logContent)) {
                addLogItem("=== 历史日志 ===", LOG_TYPE_SUCCESS, "INFO", "开始加载历史日志");
                
                String[] lines = logContent.split("\n");
                int startLine = Math.max(0, lines.length - MAX_LOG_LINES);
                int count = 0;
                for (int i = startLine; i < lines.length; i++) {
                    if (!TextUtils.isEmpty(lines[i]) && count < MAX_LOG_LINES) {
                        int logType = LOG_TYPE_ALL;
                        String level = "INFO";
                        String details = "历史日志记录";
                        
                        // 判断是否是 AI 服务相关日志
                        boolean isAIService = false;
                        String[] aiKeywords = {"AIChatActivity", "AIProcessingService", "AIService", "LlamaJNI", 
                                                 "User input", "Starting AI", "Task Type", "STREAM GENERATE", 
                                                 "LOAD MODEL", "Prompt token", "Generated token", "用户输入", "发送消息"};
                        for (String keyword : aiKeywords) {
                            if (lines[i].contains(keyword)) {
                                isAIService = true;
                                break;
                            }
                        }
                        
                        if (isAIService) {
                            logType = LOG_TYPE_AI_SERVICE;
                        }
                        
                        // 简单的日志类型判断
                        if (lines[i].contains("Error") || lines[i].contains("Exception")) {
                            logType = LOG_TYPE_ERROR;
                            level = "ERROR";
                        } else if (lines[i].contains("Model") || lines[i].contains("model")) {
                            logType = LOG_TYPE_MODEL;
                        } else if (lines[i].contains("Success") || lines[i].contains("success")) {
                            logType = LOG_TYPE_SUCCESS;
                        }
                        
                        addLogItem(lines[i], logType, level, details);
                        count++;
                    }
                }
                
                if (lines.length > MAX_LOG_LINES) {
                    addLogItem("... 共 " + lines.length + " 行，已截断显示最近 " + MAX_LOG_LINES + " 行 ...", LOG_TYPE_SUCCESS, "INFO", "历史日志已截断");
                }
                
                addLogItem("=== 历史日志结束 ===", LOG_TYPE_SUCCESS, "INFO", "历史日志加载完成");
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "Error loading historical logs: " + e.getMessage());
            addLogItem("加载历史日志失败: " + e.getMessage(), LOG_TYPE_ERROR, "ERROR", "加载历史日志时发生错误: " + e.getMessage());
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        isDestroyed = true;
        if (logReceiver != null) {
            try {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(logReceiver);
            } catch (Exception e) {
                android.util.Log.e(TAG, "Error unregistering receiver: " + e.getMessage());
            }
        }
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
    }
    
    private void addLogItem(String message, int type, String level, String details) {
        if (TextUtils.isEmpty(message)) {
            return;
        }
        
        if (isDestroyed) {
            return;
        }
        
        if (mainHandler == null || mainHandler.getLooper() != Looper.getMainLooper()) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        
        mainHandler.post(() -> {
            try {
                if (isDestroyed) {
                    return;
                }
                
                String timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
                LogItem logItem = new LogItem(timestamp, message, type, level, details);
                
                logItems.add(logItem);
                
                // 限制日志数量
                if (logItems.size() > MAX_LOG_LINES) {
                    logItems.remove(0);
                }
                
                filterLogs();
            } catch (Exception e) {
                android.util.Log.e(TAG, "Error in addLogItem: " + e.getMessage());
            }
        });
    }
    
    private void filterLogs() {
        if (isDestroyed) {
            return;
        }
        
        mainHandler.post(() -> {
            try {
                filteredLogItems.clear();
                
                for (LogItem item : logItems) {
                    // 根据标签页筛选
                    boolean matchTab = false;
                    switch (currentTabPosition) {
                        case 0: // 所有日志
                            matchTab = true;
                            break;
                        case 1: // 模型信息
                            matchTab = (item.type == LOG_TYPE_MODEL);
                            break;
                        case 2: // 错误信息
                            matchTab = (item.type == LOG_TYPE_ERROR);
                            break;
                        case 3: // 成功信息
                            matchTab = (item.type == LOG_TYPE_SUCCESS);
                            break;
                        case 4: // 详细信息
                            matchTab = true;
                            break;
                    }
                    
                    // 根据筛选芯片筛选
                    boolean matchFilter = true;
                    if (currentFilterType == LOG_TYPE_AI_SERVICE) {
                        // AI 服务智能筛选 - 只显示与 AI 服务相关的关键日志
                        matchFilter = isAIServiceLog(item);
                    } else {
                        // 普通筛选
                        matchFilter = (currentFilterType == LOG_TYPE_ALL) || (item.type == currentFilterType);
                    }
                    
                    // 根据搜索查询筛选
                    boolean matchSearch = TextUtils.isEmpty(currentSearchQuery) || 
                            item.message.toLowerCase().contains(currentSearchQuery.toLowerCase()) ||
                            item.details.toLowerCase().contains(currentSearchQuery.toLowerCase());
                    
                    if (matchTab && matchFilter && matchSearch) {
                        filteredLogItems.add(item);
                    }
                }
                
                // 确保日志按时间排列（最新的在前面）
                // 由于日志是按添加顺序保存的，已经是时间顺序，不需要额外排序
                
                logAdapter.notifyDataSetChanged();
            } catch (Exception e) {
                android.util.Log.e(TAG, "Error in filterLogs: " + e.getMessage());
            }
        });
    }
    
    // 判断是否是 AI 服务相关的日志
    private boolean isAIServiceLog(LogItem item) {
        String msg = item.message;
        
        // 关键日志关键词
        String[] keywords = {
            "[AIChatActivity]",
            "[AIProcessingService]", 
            "[AIService]",
            "[LlamaJNI]",
            "User input message",
            "Starting AI service",
            "Task Type",
            "=== STREAM GENERATE START ===",
            "=== LOAD MODEL START ===",
            "=== LOAD MODEL COMPLETE ===",
            "Calling processAITask",
            "Prompt token",
            "Generated token",
            "Pre-checks passed",
            "formatting prompt",
            "Native memory",
            "Java memory",
            "Device info",
            "Model loaded successfully",
            "Vocabulary loaded",
            "Context created",
            "model in memory",
            "用户输入",
            "发送消息"
        };
        
        // 检查是否包含关键词
        for (String keyword : keywords) {
            if (msg.contains(keyword)) {
                return true;
            }
        }
        
        // 检查是否是这些关键类的日志
        if (msg.startsWith("[")) {
            String[] logClasses = {"AIChatActivity", "AIProcessingService", "AIService", "LlamaJNI"};
            for (String cls : logClasses) {
                if (msg.contains(cls)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    private void showDetailedInfo(LogItem item) {
        if (isDestroyed) {
            return;
        }
        
        mainHandler.post(() -> {
            try {
                if (isDestroyed) {
                    return;
                }
                
                detailedTitle.setText("详细信息 - " + getLogTypeString(item.type));
                StringBuilder details = new StringBuilder();
                details.append("时间: " + item.timestamp + "\n");
                details.append("级别: " + item.level + "\n");
                details.append("类型: " + getLogTypeString(item.type) + "\n");
                details.append("消息: " + item.message + "\n\n");
                details.append("详细信息:\n" + (TextUtils.isEmpty(item.details) ? "无详细信息" : item.details));
                detailedContent.setText(details.toString());
                detailedInfoArea.setVisibility(View.VISIBLE);
            } catch (Exception e) {
                android.util.Log.e(TAG, "Error showing detailed info: " + e.getMessage());
            }
        });
    }
    
    private String getLogTypeString(int type) {
        switch (type) {
            case LOG_TYPE_MODEL:
                return "模型信息";
            case LOG_TYPE_ERROR:
                return "错误信息";
            case LOG_TYPE_SUCCESS:
                return "成功信息";
            case LOG_TYPE_AI_SERVICE:
                return "AI服务信息";
            default:
                return "其他信息";
        }
    }
    
    private void showToast(final String message) {
        if (isDestroyed) {
            return;
        }
        
        if (mainHandler == null || mainHandler.getLooper() != Looper.getMainLooper()) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        
        mainHandler.post(() -> {
            if (!isDestroyed) {
                android.widget.Toast.makeText(LogViewerActivity.this, message, android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    private class LogReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isDestroyed) {
                return;
            }
            
            if (intent != null && AIProcessingService.ACTION_AI_LOG_UPDATE.equals(intent.getAction())) {
                String logMessage = intent.getStringExtra(AIProcessingService.EXTRA_LOG_MESSAGE);
                String logLevel = intent.getStringExtra(AIProcessingService.EXTRA_LOG_LEVEL);
                if (!TextUtils.isEmpty(logMessage)) {
                    int logType = LOG_TYPE_AI_SERVICE; // 默认标记为 AI 服务类型
                    String details = "AI服务日志";
                    
                    // 分析日志类型
                    if (logMessage.contains("Error") || logMessage.contains("Exception") || logMessage.contains("error")) {
                        logType = LOG_TYPE_ERROR;
                    } else if (logMessage.contains("Model") || logMessage.contains("model") || logMessage.contains("Loading model")) {
                        logType = LOG_TYPE_MODEL;
                    } else if (logMessage.contains("Success") || logMessage.contains("success") || logMessage.contains("completed")) {
                        logType = LOG_TYPE_SUCCESS;
                    }
                    
                    // 分析详细信息
                    if (logMessage.contains("Loading model")) {
                        details = "模型加载过程日志";
                    } else if (logMessage.contains("onToken called")) {
                        details = "模型生成token的过程";
                    } else if (logMessage.contains("onSuccess called")) {
                        details = "模型生成完成";
                    } else if (logMessage.contains("Task Type: ")) {
                        details = "AI任务类型信息";
                    } else if (logMessage.contains("User input")) {
                        details = "用户输入消息";
                    } else if (logMessage.contains("Prompt token")) {
                        details = "提示词token化";
                    } else if (logMessage.contains("STREAM GENERATE")) {
                        details = "流式生成开始";
                    }
                    
                    addLogItem(logMessage, logType, TextUtils.isEmpty(logLevel) ? "INFO" : logLevel, details);
                }
            }
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_FILE_PICKER && resultCode == RESULT_OK) {
            if (data != null) {
                Uri selectedUri = data.getData();
                if (selectedUri != null) {
                    String fileName = selectedUri.getLastPathSegment();
                    addLogItem("附件已选择: " + fileName, LOG_TYPE_SUCCESS, "INFO", "已选择文件: " + fileName);
                }
            }
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        AppResourceManager.getInstance(this).permissions().onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}

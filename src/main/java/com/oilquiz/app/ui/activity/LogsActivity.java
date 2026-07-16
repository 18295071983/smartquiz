package com.oilquiz.app.ui.activity;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;

import com.oilquiz.app.ui.base.BaseActivity;

import com.oilquiz.app.R;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class LogsActivity extends BaseActivity {

    // UI组件
    private RecyclerView rvLogs;
    private MaterialButton btnRefreshLogs;
    private MaterialButton btnClearLogs;
    private MaterialButton btnCopyLogs;
    private MaterialButton btnExportLogs;
    private MaterialButton btnShareLogs;
    private TabLayout tabLayout;
    
    // 分页组件
    private LinearLayout llPagination;
    private MaterialButton btnPrevPage;
    private MaterialButton btnNextPage;
    private TextView tvPageInfo;
    
    // 分页参数
    private static final int PAGE_SIZE = 20;
    private int currentPage = 1;
    private int totalPages = 1;
    private int totalLogs = 0;
    
    // 日志数据
    private List<LogEntry> allLogs = new ArrayList<>();
    private LogAdapter logAdapter;
    
    // 日志类型
    private static final String LOG_TYPE_AI = "ai";
    private static final String LOG_TYPE_CRASH = "crash";
    private static final String LOG_TYPE_AI_LOGGER = "ai_logger";
    private String currentLogType = LOG_TYPE_AI_LOGGER;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_logs;
    }

    @Override
    protected void initView() {
        // 初始化UI组件
        rvLogs = findViewById(R.id.rv_logs);
        btnRefreshLogs = findViewById(R.id.btn_refresh_logs);
        btnClearLogs = findViewById(R.id.btn_clear_logs);
        btnCopyLogs = findViewById(R.id.btn_copy_logs);
        btnExportLogs = findViewById(R.id.btn_export_logs);
        btnShareLogs = findViewById(R.id.btn_share_logs);
        tabLayout = findViewById(R.id.tab_layout);
        
        // 初始化分页组件
        llPagination = findViewById(R.id.ll_pagination);
        btnPrevPage = findViewById(R.id.btn_prev_page);
        tvPageInfo = findViewById(R.id.tv_page_info);
        btnNextPage = findViewById(R.id.btn_next_page);
        
        // 初始化适配器
        logAdapter = new LogAdapter();
        rvLogs.setAdapter(logAdapter);
        // 设置LayoutManager
        rvLogs.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        
        // 初始化选项卡
        initTabs();
    }
    
    // 初始化选项卡
    private void initTabs() {
        if (tabLayout != null) {
            // 清除现有选项卡
            tabLayout.removeAllTabs();
            
            // 添加AI Logger选项卡（默认）
            tabLayout.addTab(tabLayout.newTab().setText("AI服务日志").setTag(LOG_TYPE_AI_LOGGER));
            // 添加AI日志选项卡
            tabLayout.addTab(tabLayout.newTab().setText("系统AI日志").setTag(LOG_TYPE_AI));
            // 添加崩溃日志选项卡
            tabLayout.addTab(tabLayout.newTab().setText("崩溃").setTag(LOG_TYPE_CRASH));
            
            // 设置选项卡点击监听器
            tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                @Override
                public void onTabSelected(TabLayout.Tab tab) {
                    currentLogType = (String) tab.getTag();
                    loadLogData();
                }
                
                @Override
                public void onTabUnselected(TabLayout.Tab tab) {}
                
                @Override
                public void onTabReselected(TabLayout.Tab tab) {}
            });
        }
    }

    @Override
    protected void initData() {
        try {
            // 初始化AppLogger
            com.oilquiz.app.infra.AppLogger.init(this);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "初始化失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        
        // 加载日志数据
        loadLogData();
    }

    @Override
    protected void initListener() {
        // 设置点击事件
        if (btnRefreshLogs != null) {
            btnRefreshLogs.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    loadLogData();
                }
            });
        }

        if (btnClearLogs != null) {
            btnClearLogs.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clearLogs();
                }
            });
        }

        if (btnCopyLogs != null) {
            btnCopyLogs.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    copyLogs();
                }
            });
        }

        if (btnExportLogs != null) {
            btnExportLogs.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    exportLogs();
                }
            });
        }

        if (btnShareLogs != null) {
            btnShareLogs.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    shareLogs();
                }
            });
        }
        
        // 分页按钮
        if (btnPrevPage != null) {
            btnPrevPage.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (currentPage > 1) {
                        currentPage--;
                        updatePagination();
                        updateLogList();
                    }
                }
            });
        }
        
        if (btnNextPage != null) {
            btnNextPage.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (currentPage < totalPages) {
                        currentPage++;
                        updatePagination();
                        updateLogList();
                    }
                }
            });
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.logs_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        
        if (id == R.id.action_clear_logs) {
            clearLogs();
            return true;
        } else if (id == R.id.action_copy_logs) {
            copyLogs();
            return true;
        } else if (id == R.id.action_export_logs) {
            exportLogs();
            return true;
        } else if (id == R.id.action_share_logs) {
            shareLogs();
            return true;
        }
        
        return super.onOptionsItemSelected(item);
    }
    
    // 加载日志数据
    private void loadLogData() {
        try {
            // 显示加载中提示
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(LogsActivity.this, "正在加载日志...", Toast.LENGTH_SHORT).show();
                }
            });
            
            // 检查AppLogger是否初始化
            checkAppLoggerInitialized();
            
            // 在后台线程中加载日志
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        // 限制日志加载时间，避免长时间阻塞
                        long startTime = System.currentTimeMillis();
                        
                        // 创建一个新的列表，避免并发访问问题
                        List<LogEntry> newLogs = new ArrayList<>();
                        
                        // 根据当前日志类型加载不同的日志
                        if (LOG_TYPE_AI.equals(currentLogType)) {
                            loadAILogsToList(newLogs);
                        } else if (LOG_TYPE_CRASH.equals(currentLogType)) {
                            loadCrashLogsToList(newLogs);
                        } else if (LOG_TYPE_AI_LOGGER.equals(currentLogType)) {
                            loadAILoggerLogsToList(newLogs);
                        }
                        
                        // 过滤掉null日志条目
                        final List<LogEntry> validLogs = new ArrayList<>();
                        for (LogEntry entry : newLogs) {
                            if (entry != null) {
                                validLogs.add(entry);
                            }
                        }
                        
                        // 限制日志条目数量，避免内存溢出
                        final int MAX_LOG_ENTRIES = 1000;
                        if (validLogs.size() > MAX_LOG_ENTRIES) {
                            // 创建新的列表来存储截断后的日志
                            final List<LogEntry> truncatedLogs = new ArrayList<>(validLogs.subList(0, MAX_LOG_ENTRIES));
                            // 清空原列表并添加截断后的日志
                            validLogs.clear();
                            validLogs.addAll(truncatedLogs);
                        }
                        
                        // 按时间戳降序排序，新日志优先显示
                        validLogs.sort((log1, log2) -> {
                            try {
                                if (log1 == null || log2 == null) {
                                    return 0;
                                }
                                return Long.compare(log2.getTimestamp(), log1.getTimestamp());
                            } catch (Exception e) {
                                e.printStackTrace();
                                return 0;
                            }
                        });
                        
                        // 限制处理时间，避免ANR
                        if (System.currentTimeMillis() - startTime > 10000) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(LogsActivity.this, "日志加载超时，部分日志可能未显示", Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                        
                        // 在主线程中更新UI
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    // 更新allLogs的引用
                                    allLogs.clear();
                                    allLogs.addAll(validLogs);
                                    
                                    // 更新分页信息
                                    totalLogs = validLogs.size();
                                    totalPages = (totalLogs + PAGE_SIZE - 1) / PAGE_SIZE;
                                    if (totalPages < 1) totalPages = 1;
                                    currentPage = 1;
                                    
                                    // 更新UI
                                    updatePagination();
                                    updateLogList();
                                } catch (Exception e) {
                                    e.printStackTrace();
                                    Toast.makeText(LogsActivity.this, "更新UI失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(LogsActivity.this, "加载日志失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
            }).start();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "加载日志失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    // 加载AI Logger日志到指定列表
    private void loadAILoggerLogsToList(List<LogEntry> targetList) {
        if (targetList == null) {
            return;
        }
        
        try {
            // 确保AILogger已初始化
            if (com.oilquiz.app.util.AILogger.getLogFilePath().equals("Log file not initialized")) {
                com.oilquiz.app.util.AILogger.init(this);
            }
            
            List<com.oilquiz.app.util.AILogger.LogEntry> aiLoggerEntries = com.oilquiz.app.util.AILogger.getAllLogs();
            
            if (aiLoggerEntries != null && !aiLoggerEntries.isEmpty()) {
                for (com.oilquiz.app.util.AILogger.LogEntry aiEntry : aiLoggerEntries) {
                    LogEntry entry = new LogEntry();
                    
                    // 解析时间戳
                    try {
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
                        java.util.Date date = sdf.parse(aiEntry.timestamp);
                        entry.setTimestamp(date != null ? date.getTime() : System.currentTimeMillis());
                    } catch (Exception e) {
                        entry.setTimestamp(System.currentTimeMillis());
                    }
                    
                    // 设置日志级别
                    String level = aiEntry.level;
                    if ("INFO".equals(level)) {
                        entry.setLevel("I");
                    } else if ("WARN".equals(level)) {
                        entry.setLevel("W");
                    } else if ("ERROR".equals(level)) {
                        entry.setLevel("E");
                    } else {
                        entry.setLevel("I");
                    }
                    
                    // 设置标签和详情
                    entry.setAction(aiEntry.tag);
                    entry.setDetail(aiEntry.message);
                    
                    targetList.add(entry);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            // 添加错误信息条目
            LogEntry errorEntry = new LogEntry();
            errorEntry.setTimestamp(System.currentTimeMillis());
            errorEntry.setLevel("E");
            errorEntry.setAction("LogsActivity");
            errorEntry.setDetail("加载AILogger日志失败: " + e.getMessage());
            targetList.add(errorEntry);
        }
    }
    
    // 加载AI日志到指定列表
    private void loadAILogsToList(List<LogEntry> targetList) {
        if (targetList == null) {
            return;
        }
        
        String aiLogs = com.oilquiz.app.infra.AppLogger.getAILogs();
        if (aiLogs == null || aiLogs.isEmpty()) {
            return;
        }
        
        // 检查是否是特殊信息（如日志文件不存在等）
        if (aiLogs.startsWith("[")) {
            // 创建一个特殊的日志条目来显示这些信息
            LogEntry entry = new LogEntry();
            entry.setTimestamp(System.currentTimeMillis());
            entry.setLevel("I");
            entry.setAction("AILogger");
            entry.setDetail(aiLogs);
            targetList.add(entry);
            return;
        }
        
        String[] lines = aiLogs.split("\n");
        
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) continue;
            
            // 检查是否是特殊信息行
            if (line.startsWith("[")) {
                // 创建一个特殊的日志条目来显示这些信息
                LogEntry entry = new LogEntry();
                entry.setTimestamp(System.currentTimeMillis());
                entry.setLevel("I");
                entry.setAction("AILogger");
                entry.setDetail(line);
                targetList.add(entry);
                continue;
            }
            
            // 解析标准日志格式：2026-04-07 11:47:17.835 | I | AppLogger            | [AI] 消息内容
            try {
                String[] parts = line.split(Pattern.quote(" | "));
                if (parts.length >= 4) {
                    String timestampStr = parts[0].trim();
                    String level = parts[1].trim();
                    String action = parts[2].trim();
                    String detail = parts[3].trim();
                    
                    // 移除AI标签
                    if (detail.startsWith("[AI] ")) {
                        detail = detail.substring(5);
                    }
                    
                    // 解析时间戳
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
                    Date date = sdf.parse(timestampStr);
                    long timestamp = date != null ? date.getTime() : System.currentTimeMillis();
                    
                    LogEntry entry = new LogEntry();
                    entry.setTimestamp(timestamp);
                    entry.setLevel(level);
                    entry.setAction(action);
                    entry.setDetail(detail);
                    
                    targetList.add(entry);
                } else {
                    // 对于格式不正确的日志行，跳过处理
                    LogEntry entry = new LogEntry();
                    entry.setTimestamp(System.currentTimeMillis());
                    entry.setLevel("I");
                    entry.setAction("AILogger");
                    entry.setDetail(line);
                    targetList.add(entry);
                }
            } catch (Exception e) {
                // 解析失败时跳过
            }
        }
    }
    
    // 加载崩溃日志到指定列表
    private void loadCrashLogsToList(List<LogEntry> targetList) {
        if (targetList == null) {
            return;
        }
        
        String crashLogs = com.oilquiz.app.infra.AppLogger.getCrashLogs();
        if (crashLogs == null || crashLogs.isEmpty()) {
            return;
        }
        
        // 检查是否是特殊信息（如日志文件不存在等）
        if (crashLogs.startsWith("[")) {
            // 创建一个特殊的日志条目来显示这些信息
            LogEntry entry = new LogEntry();
            entry.setTimestamp(System.currentTimeMillis());
            entry.setLevel("I");
            entry.setAction("AppLogger");
            entry.setDetail(crashLogs);
            targetList.add(entry);
            return;
        }
        
        String[] lines = crashLogs.split("\n");
        
        // 处理崩溃报告格式
        boolean inCrashReport = false;
        StringBuilder crashReportBuilder = new StringBuilder();
        
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) continue;
            
            // 检查是否是特殊信息行
            if (line.startsWith("[")) {
                // 创建一个特殊的日志条目来显示这些信息
                LogEntry entry = new LogEntry();
                entry.setTimestamp(System.currentTimeMillis());
                entry.setLevel("I");
                entry.setAction("AppLogger");
                entry.setDetail(line);
                targetList.add(entry);
                continue;
            }
            
            // 检查是否是崩溃报告的开始
            if (line.contains("══════════════════════════════════════════") && !inCrashReport) {
                inCrashReport = true;
                crashReportBuilder = new StringBuilder();
                crashReportBuilder.append(line).append("\n");
                continue;
            }
            
            // 检查是否是崩溃报告的结束
            if (inCrashReport && line.contains("══════════════════════════════════════════")) {
                crashReportBuilder.append(line).append("\n");
                
                // 创建崩溃报告日志条目
                LogEntry entry = new LogEntry();
                entry.setTimestamp(System.currentTimeMillis());
                entry.setLevel("E");
                entry.setAction("崩溃报告");
                entry.setDetail(crashReportBuilder.toString());
                targetList.add(entry);
                
                inCrashReport = false;
                continue;
            }
            
            // 如果在崩溃报告中，继续收集内容
            if (inCrashReport) {
                crashReportBuilder.append(line).append("\n");
                continue;
            }
            
            // 解析标准日志格式：2026-04-07 11:47:17.835 | I | AppLogger            | 所有日志已清空
            try {
                String[] parts = line.split(Pattern.quote(" | "));
                if (parts.length >= 4) {
                    String timestampStr = parts[0].trim();
                    String level = parts[1].trim();
                    String action = parts[2].trim();
                    String detail = parts[3].trim();
                    
                    // 解析时间戳
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
                    Date date = sdf.parse(timestampStr);
                    long timestamp = date != null ? date.getTime() : System.currentTimeMillis();
                    
                    LogEntry entry = new LogEntry();
                    entry.setTimestamp(timestamp);
                    entry.setLevel(level);
                    entry.setAction(action);
                    entry.setDetail(detail);
                    
                    targetList.add(entry);
                } else {
                    // 对于格式不正确的日志行，跳过处理，避免生成过多的警告
                    // 只处理有意义的崩溃相关内容
                    if (line.contains("崩溃") || line.contains("Crash") || line.contains("Exception") || line.contains("Error")) {
                        LogEntry entry = new LogEntry();
                        entry.setTimestamp(System.currentTimeMillis());
                        entry.setLevel("E");
                        entry.setAction("崩溃信息");
                        entry.setDetail(line);
                        targetList.add(entry);
                    }
                }
            } catch (Exception e) {
                // 解析失败时跳过，避免生成过多的错误信息
            }
        }
    }
    
    /**
     * 检查AppLogger是否初始化，如果未初始化则尝试初始化
     */
    private void checkAppLoggerInitialized() {
        try {
            // 检查AppLogger是否已初始化
            if (!com.oilquiz.app.infra.AppLogger.isInitialized()) {
                // 如果未初始化，尝试初始化
                com.oilquiz.app.infra.AppLogger.init(this);
                com.oilquiz.app.infra.AppLogger.i("AppLogger", "日志系统重新初始化成功");
            }
        } catch (Exception e) {
            com.oilquiz.app.infra.AppLogger.e("AppLogger", "日志系统初始化失败: " + e.getMessage());
        }
    }
    
    // 更新分页信息
    private void updatePagination() {
        if (llPagination != null && tvPageInfo != null) {
            if (totalPages > 1) {
                llPagination.setVisibility(View.VISIBLE);
                tvPageInfo.setText("第 " + currentPage + " 页，共 " + totalPages + " 页");
                
                if (btnPrevPage != null) {
                    btnPrevPage.setEnabled(currentPage > 1);
                }
                if (btnNextPage != null) {
                    btnNextPage.setEnabled(currentPage < totalPages);
                }
            } else {
                llPagination.setVisibility(View.GONE);
            }
        }
    }
    
    // 更新日志列表
    private void updateLogList() {
        try {
            // 计算当前页的日志
            int startIndex = (currentPage - 1) * PAGE_SIZE;
            int endIndex = Math.min(startIndex + PAGE_SIZE, allLogs.size());
            List<LogEntry> pageLogs = new ArrayList<>();
            
            for (int i = startIndex; i < endIndex; i++) {
                if (i < allLogs.size()) {
                    LogEntry entry = allLogs.get(i);
                    if (entry != null) {
                        pageLogs.add(entry);
                    }
                }
            }
            
            if (logAdapter != null) {
                logAdapter.setLogs(pageLogs);
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "更新日志列表失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    

    
    // 清空日志
    private void clearLogs() {
        try {
            // 显示操作中提示
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(LogsActivity.this, "正在清空日志...", Toast.LENGTH_SHORT).show();
                }
            });
            
            new Thread(new Runnable() {
                @Override
                public void run() {
                    boolean logCleared = false;
                    String logTypeName = "";
                    
                    if (LOG_TYPE_AI.equals(currentLogType)) {
                        logCleared = com.oilquiz.app.infra.AppLogger.clearAILogs();
                        logTypeName = "AI日志";
                    } else if (LOG_TYPE_CRASH.equals(currentLogType)) {
                        logCleared = com.oilquiz.app.infra.AppLogger.clearCrashLogs();
                        logTypeName = "崩溃日志";
                    } else if (LOG_TYPE_AI_LOGGER.equals(currentLogType)) {
                        logCleared = com.oilquiz.app.util.AILogger.clearLogs();
                        logTypeName = "AI服务日志";
                    }
                    
                    final boolean finalLogCleared = logCleared;
                    final String finalLogTypeName = logTypeName;
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (finalLogCleared) {
                                    loadLogData();
                                    Toast.makeText(LogsActivity.this, finalLogTypeName + "已清空", Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(LogsActivity.this, "清空" + finalLogTypeName + "失败", Toast.LENGTH_SHORT).show();
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                                Toast.makeText(LogsActivity.this, "清空日志时出错: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                }
            }).start();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "清空日志时出错: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // 复制日志
    private void copyLogs() {
        if (allLogs.isEmpty()) {
            Toast.makeText(this, "没有可复制的日志", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            // 显示操作中提示
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(LogsActivity.this, "正在复制日志...", Toast.LENGTH_SHORT).show();
                }
            });
            
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        StringBuilder logs = new StringBuilder();
                        for (LogEntry entry : allLogs) {
                            if (entry != null) {
                                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
                                String timestampStr = sdf.format(new Date(entry.getTimestamp()));
                                logs.append(timestampStr).append(" | ").append(entry.getLevel()).append(" | ")
                                    .append(entry.getAction()).append(" | ").append(entry.getDetail()).append("\n");
                            }
                        }

                        // 复制到剪贴板
                        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        android.content.ClipData clip = android.content.ClipData.newPlainText("日志", logs.toString());
                        clipboard.setPrimaryClip(clip);

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(LogsActivity.this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show();
                            }
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(LogsActivity.this, "复制日志失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
            }).start();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "复制日志失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // 导出日志到文件
    private void exportLogs() {
        if (allLogs.isEmpty()) {
            Toast.makeText(this, "没有可导出的日志", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            // 显示操作中提示
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(LogsActivity.this, "正在导出日志...", Toast.LENGTH_SHORT).show();
                }
            });
            
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        // 创建导出目录
                        File exportDir = new File(getExternalFilesDir(null), "exported_logs");
                        if (!exportDir.exists()) {
                            exportDir.mkdirs();
                        }

                        // 创建合并的日志文件
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
                        String timestamp = sdf.format(new Date());
                        File exportFile = new File(exportDir, "logs_" + timestamp + ".txt");

                        StringBuilder logs = new StringBuilder();
                        for (LogEntry entry : allLogs) {
                            if (entry != null) {
                                SimpleDateFormat timestampFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
                                String timestampStr = timestampFormat.format(new Date(entry.getTimestamp()));
                                logs.append(timestampStr).append(" | ").append(entry.getLevel()).append(" | ")
                                    .append(entry.getAction()).append(" | ").append(entry.getDetail()).append("\n");
                            }
                        }

                        java.io.FileWriter writer = new java.io.FileWriter(exportFile);
                        writer.write(logs.toString());
                        writer.close();

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(LogsActivity.this, "日志已导出到: " + exportFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                            }
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(LogsActivity.this, "导出日志失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
            }).start();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "导出日志失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // 分享日志
    private void shareLogs() {
        if (allLogs.isEmpty()) {
            Toast.makeText(this, "没有可分享的日志", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            // 显示操作中提示
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(LogsActivity.this, "正在准备分享...", Toast.LENGTH_SHORT).show();
                }
            });
            
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        // 创建导出文件
                        File exportDir = new File(getExternalFilesDir(null), "exported_logs");
                        if (!exportDir.exists()) {
                            exportDir.mkdirs();
                        }

                        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
                        String timestamp = sdf.format(new Date());
                        File exportFile = new File(exportDir, "logs_" + timestamp + ".txt");

                        StringBuilder logs = new StringBuilder();
                        for (LogEntry entry : allLogs) {
                            if (entry != null) {
                                SimpleDateFormat timestampFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
                                String timestampStr = timestampFormat.format(new Date(entry.getTimestamp()));
                                logs.append(timestampStr).append(" | ").append(entry.getLevel()).append(" | ")
                                    .append(entry.getAction()).append(" | ").append(entry.getDetail()).append("\n");
                            }
                        }

                        java.io.FileWriter writer = new java.io.FileWriter(exportFile);
                        writer.write(logs.toString());
                        writer.close();

                        // 分享文件
                        android.net.Uri fileUri = androidx.core.content.FileProvider.getUriForFile(LogsActivity.this, 
                                getPackageName() + ".fileprovider", exportFile);

                        android.content.Intent shareIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
                        shareIntent.setType("text/plain");
                        shareIntent.putExtra(android.content.Intent.EXTRA_STREAM, fileUri);
                        shareIntent.putExtra(android.content.Intent.EXTRA_SUBJECT, "应用日志");
                        shareIntent.putExtra(android.content.Intent.EXTRA_TEXT, "请查看附件中的应用日志");
                        shareIntent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                android.content.Intent chooser = android.content.Intent.createChooser(shareIntent, "分享日志");
                                startActivity(chooser);
                            }
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(LogsActivity.this, "分享日志失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
            }).start();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "分享日志失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    // 日志条目类
    private class LogEntry {
        private long timestamp;
        private String level;
        private String action;
        private String detail;
        
        public long getTimestamp() {
            return timestamp;
        }
        
        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }
        
        public String getLevel() {
            return level;
        }
        
        public void setLevel(String level) {
            this.level = level;
        }
        
        public String getAction() {
            return action;
        }
        
        public void setAction(String action) {
            this.action = action;
        }
        
        public String getDetail() {
            return detail;
        }
        
        public void setDetail(String detail) {
            this.detail = detail;
        }
    }
    
    // 日志适配器
    private class LogAdapter extends RecyclerView.Adapter<LogAdapter.LogViewHolder> {
        private List<LogEntry> logs = new ArrayList<>();
        
        public void setLogs(List<LogEntry> logs) {
            this.logs = logs;
            notifyDataSetChanged();
        }
        
        @Override
        public LogViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View view = getLayoutInflater().inflate(R.layout.item_log_entry, parent, false);
            return new LogViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(LogViewHolder holder, int position) {
            try {
                LogEntry entry = logs.get(position);
                if (entry == null) return;
                
                // 格式化时间戳
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
                long timestamp = entry.getTimestamp();
                String timestampStr = sdf.format(new Date(timestamp));
                if (holder.tvTimestamp != null) {
                    holder.tvTimestamp.setText(timestampStr);
                }
                
                // 设置级别
                String levelText = entry.getLevel();
                if (holder.tvLevel != null) {
                    holder.tvLevel.setText(levelText != null ? levelText : "");
                    
                    // 设置级别颜色
                    if (levelText != null) {
                        if (levelText.equals("E")) {
                            try {
                                holder.tvLevel.setTextColor(getResources().getColor(R.color.error_color, null));
                                holder.tvLevel.setBackgroundResource(R.drawable.rounded_tag_error);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        } else if (levelText.equals("W")) {
                            try {
                                holder.tvLevel.setTextColor(getResources().getColor(R.color.warning_color, null));
                                holder.tvLevel.setBackgroundResource(R.drawable.rounded_tag_warning);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        } else {
                            try {
                                holder.tvLevel.setTextColor(getResources().getColor(R.color.primary_color, null));
                                holder.tvLevel.setBackgroundResource(R.drawable.rounded_tag);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
                
                // 设置操作和详情
                if (holder.tvAction != null) {
                    String action = entry.getAction();
                    holder.tvAction.setText(action != null ? action : "");
                }
                if (holder.tvDetail != null) {
                    String detail = entry.getDetail();
                    // 限制详情长度，避免UI崩溃
                    if (detail != null && detail.length() > 1000) {
                        detail = detail.substring(0, 1000) + "... [内容过长，已截断]";
                    }
                    holder.tvDetail.setText(detail != null ? detail : "");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        @Override
        public int getItemCount() {
            return logs.size();
        }
        
        class LogViewHolder extends RecyclerView.ViewHolder {
            TextView tvTimestamp;
            TextView tvLevel;
            TextView tvAction;
            TextView tvDetail;
            
            public LogViewHolder(View itemView) {
                super(itemView);
                tvTimestamp = itemView.findViewById(R.id.tv_log_timestamp);
                tvLevel = itemView.findViewById(R.id.tv_log_level);
                tvAction = itemView.findViewById(R.id.tv_log_action);
                tvDetail = itemView.findViewById(R.id.tv_log_detail);
            }
        }
    }
}

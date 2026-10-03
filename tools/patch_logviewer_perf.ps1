# LogViewerActivity 性能优化补丁：增量过滤 + 节流批量刷新
$ErrorActionPreference = 'Stop'
$p = "D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ui\activity\LogViewerActivity.java"
$t = [IO.File]::ReadAllText($p)

# 1. 字段区：加节流/增量队列字段
$f_anchor = '    private String currentSearchQuery = "";' + "`n" + '    private int currentFilterType = LOG_TYPE_ALL;'
$f_add = $f_anchor + "`n" + @"
`n    // 性能优化：增量日志队列 + 节流刷新（广播风暴合并，避免每次全量重筛）
    private final List<LogItem> pendingLogItems = new ArrayList<>();
    private static final long FLUSH_DELAY_MS = 150L;
    private final Runnable flushPendingRunnable = new Runnable() {
        @Override
        public void run() {
            flushPendingLogs();
        }
    };
"@
if ($t.Contains($f_anchor)) { $t = $t.Replace($f_anchor, $f_add); Write-Output "字段: OK" } else { Write-Output "字段: MISS" }

# 2. addLogItem：全量 filterLogs -> 入队 + 节流
$a_anchor = @"
                logItems.add(logItem);
                
                // 限制日志数量
                if (logItems.size() > MAX_LOG_LINES) {
                    logItems.remove(0);
                }
                
                filterLogs();
"@
$a_anchor = $a_anchor.Replace("`r`n", "`n")
$a_new = @"
                logItems.add(logItem);
                
                // 限制日志数量
                if (logItems.size() > MAX_LOG_LINES) {
                    logItems.remove(0);
                }
                
                // 性能优化：入队 + 节流，批量增量刷新（不再每次全量重筛）
                pendingLogItems.add(logItem);
                mainHandler.removeCallbacks(flushPendingRunnable);
                mainHandler.postDelayed(flushPendingRunnable, FLUSH_DELAY_MS);
"@
$a_new = $a_new.Replace("`r`n", "`n")
if ($t.Contains($a_anchor)) { $t = $t.Replace($a_anchor, $a_new); Write-Output "addLogItem: OK" } else { Write-Output "addLogItem: MISS" }

# 3. 在 isAIServiceLog 前插入：flushPendingLogs / isItemMatchFilter / autoScrollToBottom
$i_anchor = '    // 判断是否是 AI 服务相关的日志'
$i_new = @"
    private void flushPendingLogs() {
        if (isDestroyed || pendingLogItems.isEmpty()) {
            return;
        }
        try {
            boolean added = false;
            for (LogItem item : pendingLogItems) {
                if (isItemMatchFilter(item)) {
                    filteredLogItems.add(item);
                    added = true;
                }
            }
            pendingLogItems.clear();
            if (added) {
                logAdapter.notifyDataSetChanged();
                autoScrollToBottom();
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "Error in flushPendingLogs: " + e.getMessage());
        }
    }
    
    // 单条日志是否匹配当前筛选（tab/筛选芯片/搜索）
    private boolean isItemMatchFilter(LogItem item) {
        boolean matchTab;
        switch (currentTabPosition) {
            case 1: // 模型信息
                matchTab = (item.type == LOG_TYPE_MODEL);
                break;
            case 2: // 错误信息
                matchTab = (item.type == LOG_TYPE_ERROR);
                break;
            case 3: // 成功信息
                matchTab = (item.type == LOG_TYPE_SUCCESS);
                break;
            default: // 所有日志 / 详细信息
                matchTab = true;
                break;
        }
        
        boolean matchFilter;
        if (currentFilterType == LOG_TYPE_AI_SERVICE) {
            matchFilter = isAIServiceLog(item);
        } else {
            matchFilter = (currentFilterType == LOG_TYPE_ALL) || (item.type == currentFilterType);
        }
        
        boolean matchSearch = TextUtils.isEmpty(currentSearchQuery)
                || item.message.toLowerCase().contains(currentSearchQuery.toLowerCase())
                || item.details.toLowerCase().contains(currentSearchQuery.toLowerCase());
        
        return matchTab && matchFilter && matchSearch;
    }
    
    // 新日志到达且用户在底部附近时，自动平滑滚到底部
    private void autoScrollToBottom() {
        if (logListView == null || isDestroyed) {
            return;
        }
        int count = filteredLogItems.size();
        if (count == 0) {
            return;
        }
        int lastVisible = logListView.getLastVisiblePosition();
        if (lastVisible >= count - 4) {
            logListView.post(() -> {
                if (logListView != null) {
                    logListView.smoothScrollToPosition(count - 1);
                }
            });
        }
    }
    
    // 判断是否是 AI 服务相关的日志
"@
$i_new = $i_new.Replace("`r`n", "`n")
if ($t.Contains($i_anchor)) { $t = $t.Replace($i_anchor, $i_new); Write-Output "新增方法: OK" } else { Write-Output "新增方法: MISS" }

[IO.File]::WriteAllText($p, $t, (New-Object Text.UTF8Encoding $false))
Write-Output "已写回"

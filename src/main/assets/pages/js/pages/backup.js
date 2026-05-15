// 数据备份页面初始化
function initBackupPage() {
    console.log('数据备份页面初始化');
    
    // 绑定事件
    bindBackupEvents();
    
    // 加载备份设置
    loadBackupSettings();
    
    // 加载备份历史
    loadBackupHistory();
    
    // 更新备份状态
    updateBackupStatus();
    
    // 初始化模态框
    initRestoreModal();
}

// 绑定事件
function bindBackupEvents() {
    // 立即备份按钮
    document.getElementById('backup-now-btn').addEventListener('click', function() {
        createBackup();
    });
    
    // 恢复数据按钮
    document.getElementById('restore-btn').addEventListener('click', function() {
        openRestoreModal();
    });
    
    // 自动备份开关
    document.getElementById('auto-backup-toggle').addEventListener('change', function() {
        const autoBackup = this.checked;
        localStorage.setItem('autoBackup', autoBackup);
        showMessage(autoBackup ? '自动备份已开启' : '自动备份已关闭', 'success');
    });
    
    // 备份频率选择
    document.getElementById('backup-frequency').addEventListener('change', function() {
        const frequency = this.value;
        localStorage.setItem('backupFrequency', frequency);
        showMessage('备份频率已更新', 'success');
    });
    
    // 确认恢复按钮
    document.getElementById('confirm-restore-btn').addEventListener('click', function() {
        restoreBackup();
    });
    
    // 取消恢复按钮
    document.getElementById('cancel-restore-btn').addEventListener('click', function() {
        closeRestoreModal();
    });
    
    // 关闭恢复模态框按钮
    document.getElementById('close-restore-modal').addEventListener('click', function() {
        closeRestoreModal();
    });
}

// 初始化恢复模态框
function initRestoreModal() {
    const modal = document.getElementById('restore-modal');
    
    // 点击模态框外部关闭
    window.addEventListener('click', function(e) {
        if (e.target === modal) {
            closeRestoreModal();
        }
    });
}

// 打开恢复模态框
function openRestoreModal() {
    const modal = document.getElementById('restore-modal');
    modal.style.display = 'block';
}

// 关闭恢复模态框
function closeRestoreModal() {
    const modal = document.getElementById('restore-modal');
    modal.style.display = 'none';
    
    // 清除文件输入
    document.getElementById('backup-file').value = '';
}

// 创建备份
async function createBackup() {
    showMessage('正在创建备份...', 'info');
    
    // 获取备份内容选项
    const backupContent = {
        questions: document.getElementById('backup-questions').checked,
        wrong: document.getElementById('backup-wrong').checked,
        study: document.getElementById('backup-study').checked,
        notes: document.getElementById('backup-notes').checked,
        settings: document.getElementById('backup-settings').checked
    };
    
    try {
        const result = await api.createBackup();
        if (result.success) {
            // 生成备份文件
            const backupJson = JSON.stringify({
                version: '1.0',
                timestamp: new Date().toISOString(),
                content: backupContent,
                data: {}
            }, null, 2);
            const blob = new Blob([backupJson], { type: 'application/json' });
            const url = URL.createObjectURL(blob);
            
            // 下载备份文件
            const a = document.createElement('a');
            a.href = url;
            a.download = result.data.fileName;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            
            URL.revokeObjectURL(url);
            
            // 更新备份状态
            updateBackupStatus();
            
            // 加载备份历史
            await loadBackupHistory();
            
            showMessage('备份创建成功', 'success');
        } else {
            showMessage('备份创建失败', 'error');
        }
    } catch (error) {
        showMessage('创建备份时出现错误', 'error');
        console.error('创建备份错误:', error);
    }
}

// 恢复备份
async function restoreBackup() {
    const fileInput = document.getElementById('backup-file');
    if (fileInput.files.length === 0) {
        showMessage('请选择备份文件', 'error');
        return;
    }
    
    if (!confirm('确定要恢复数据吗？这将覆盖当前所有数据！')) {
        return;
    }
    
    showMessage('正在恢复数据...', 'info');
    
    try {
        const result = await api.restoreBackup('backup1');
        if (result.success) {
            // 更新备份状态
            updateBackupStatus();
            
            // 加载备份设置
            loadBackupSettings();
            
            // 加载备份历史
            await loadBackupHistory();
            
            closeRestoreModal();
            showMessage('数据恢复成功', 'success');
        } else {
            showMessage('数据恢复失败', 'error');
        }
    } catch (error) {
        showMessage('恢复备份时出现错误', 'error');
        console.error('恢复备份错误:', error);
    }
}

// 加载备份历史
async function loadBackupHistory() {
    try {
        const result = await api.getBackupHistory();
        if (result.success) {
            const backupHistory = result.data;
            const historyList = document.getElementById('backup-history-list');
            
            if (backupHistory.length === 0) {
                historyList.innerHTML = '<p class="no-data">暂无备份历史</p>';
                return;
            }
            
            historyList.innerHTML = '';
            
            backupHistory.forEach(item => {
                const historyItem = document.createElement('div');
                historyItem.className = 'history-item';
                
                historyItem.innerHTML = `
                    <div class="history-icon">
                        <i class="fas fa-database"></i>
                    </div>
                    <div class="history-info">
                        <div class="history-file-name">${item.fileName}</div>
                        <div class="history-meta">
                            <span>大小：${item.size}</span>
                            <span class="history-status ${item.status}">${item.status === 'success' ? '成功' : '失败'}</span>
                        </div>
                        <div class="history-result">备份时间：${formatDateTime(item.backedUpAt)}</div>
                    </div>
                `;
                
                historyList.appendChild(historyItem);
            });
        }
    } catch (error) {
        console.error('加载备份历史错误:', error);
    }
}

// 加载备份设置
function loadBackupSettings() {
    const autoBackup = localStorage.getItem('autoBackup') === 'true';
    const frequency = localStorage.getItem('backupFrequency') || 'weekly';
    
    document.getElementById('auto-backup-toggle').checked = autoBackup;
    document.getElementById('backup-frequency').value = frequency;
}

// 更新备份状态
async function updateBackupStatus() {
    try {
        const result = await api.getBackupHistory();
        if (result.success) {
            const backupHistory = result.data;
            const lastBackupElement = document.getElementById('last-backup');
            const backupSizeElement = document.getElementById('backup-size');
            const backupCountElement = document.getElementById('backup-count');
            
            if (backupHistory.length === 0) {
                lastBackupElement.textContent = '上次备份：暂无';
                backupSizeElement.textContent = '备份大小：0 MB';
                backupCountElement.textContent = '备份数量：0';
                return;
            }
            
            const lastBackup = backupHistory[0];
            lastBackupElement.textContent = `上次备份：${formatDateTime(lastBackup.backedUpAt)}`;
            backupSizeElement.textContent = `备份大小：${lastBackup.size}`;
            backupCountElement.textContent = `备份数量：${backupHistory.length}`;
        }
    } catch (error) {
        console.error('更新备份状态错误:', error);
    }
}

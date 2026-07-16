// 导入文件页面初始化
function initImportPage() {
    console.log('导入文件页面初始化');
    
    // 绑定导入按钮事件
    bindImportEvents();
    
    // 加载导入历史
    loadImportHistory();
}

// 绑定导入按钮事件
function bindImportEvents() {
    // CSV文件导入
    document.getElementById('import-csv-btn').addEventListener('click', function() {
        const fileInput = document.getElementById('csv-file');
        if (fileInput.files.length > 0) {
            importFile(fileInput.files[0]);
        } else {
            showMessage('请选择CSV文件', 'error');
        }
    });
    
    // Excel文件导入
    document.getElementById('import-excel-btn').addEventListener('click', function() {
        const fileInput = document.getElementById('excel-file');
        if (fileInput.files.length > 0) {
            importFile(fileInput.files[0]);
        } else {
            showMessage('请选择Excel文件', 'error');
        }
    });
    
    // JSON文件导入
    document.getElementById('import-json-btn').addEventListener('click', function() {
        const fileInput = document.getElementById('json-file');
        if (fileInput.files.length > 0) {
            importFile(fileInput.files[0]);
        } else {
            showMessage('请选择JSON文件', 'error');
        }
    });
}

// 导入文件
async function importFile(file) {
    showMessage('正在导入文件...', 'info');
    
    try {
        const result = await api.importFile(file);
        if (result.success) {
            // 更新导入历史列表
            await loadImportHistory();
            
            showMessage(`成功导入 ${result.data.recordCount} 条数据`, 'success');
        } else {
            showMessage('导入失败', 'error');
        }
    } catch (error) {
        showMessage('导入过程中出现错误', 'error');
        console.error('导入错误:', error);
    }
}

// 加载导入历史
async function loadImportHistory() {
    try {
        const result = await api.getImportHistory();
        if (result.success) {
            const importHistory = result.data;
            const historyList = document.getElementById('import-history-list');
            
            if (importHistory.length === 0) {
                historyList.innerHTML = '<p class="no-data">暂无导入历史</p>';
                return;
            }
            
            historyList.innerHTML = '';
            
            importHistory.forEach(item => {
                const historyItem = document.createElement('div');
                historyItem.className = 'history-item';
                
                const fileIcon = getFileIcon(item.format);
                
                historyItem.innerHTML = `
                    <div class="history-icon">${fileIcon}</div>
                    <div class="history-info">
                        <div class="history-file-name">${item.fileName}</div>
                        <div class="history-meta">
                            <span>${formatDateTime(item.importedAt)}</span>
                            <span class="history-status ${item.status}">${item.status === 'success' ? '成功' : '失败'}</span>
                        </div>
                        <div class="history-result">导入 ${item.recordCount} 条数据</div>
                    </div>
                `;
                
                historyList.appendChild(historyItem);
            });
        }
    } catch (error) {
        console.error('加载导入历史错误:', error);
    }
}

// 获取文件图标
function getFileIcon(fileType) {
    switch(fileType) {
        case 'csv':
            return '<i class="fas fa-file-csv"></i>';
        case 'excel':
            return '<i class="fas fa-file-excel"></i>';
        case 'json':
            return '<i class="fas fa-file-json"></i>';
        default:
            return '<i class="fas fa-file"></i>';
    }
}
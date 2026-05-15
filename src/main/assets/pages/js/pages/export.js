// 数据导出页面初始化
function initExportPage() {
    console.log('数据导出页面初始化');
    
    // 绑定导出按钮事件
    bindExportEvents();
    
    // 加载导出历史
    loadExportHistory();
}

// 绑定导出按钮事件
function bindExportEvents() {
    // CSV格式导出
    document.getElementById('export-csv-btn').addEventListener('click', function() {
        const options = {
            questions: document.getElementById('export-questions-csv').checked,
            wrong: document.getElementById('export-wrong-csv').checked,
            study: document.getElementById('export-study-csv').checked
        };
        exportData('CSV', options);
    });
    
    // Excel格式导出
    document.getElementById('export-excel-btn').addEventListener('click', function() {
        const options = {
            questions: document.getElementById('export-questions-excel').checked,
            wrong: document.getElementById('export-wrong-excel').checked,
            study: document.getElementById('export-study-excel').checked
        };
        exportData('Excel', options);
    });
    
    // JSON格式导出
    document.getElementById('export-json-btn').addEventListener('click', function() {
        const options = {
            questions: document.getElementById('export-questions-json').checked,
            wrong: document.getElementById('export-wrong-json').checked,
            study: document.getElementById('export-study-json').checked,
            settings: document.getElementById('export-settings-json').checked
        };
        exportData('JSON', options);
    });
}

// 导出数据
async function exportData(format, options) {
    showMessage('正在导出数据...', 'info');
    
    try {
        // 确定导出的数据类型
        let dataType = 'questions';
        if (options.wrong) dataType = 'wrong_questions';
        if (options.study) dataType = 'study_plans';
        if (options.settings) dataType = 'settings';
        
        const result = await api.exportData(format, dataType);
        if (result.success) {
            // 更新导出历史列表
            await loadExportHistory();
            
            // 模拟文件下载
            simulateFileDownload(format, result.data.fileName);
            
            showMessage(`成功导出 ${result.data.recordCount} 条数据`, 'success');
        } else {
            showMessage('导出失败', 'error');
        }
    } catch (error) {
        showMessage('导出过程中出现错误', 'error');
        console.error('导出错误:', error);
    }
}

// 模拟文件下载
function simulateFileDownload(format, fileName) {
    const content = `这是${format}格式的导出文件，包含SmartQuiz的数据。`;
    
    const blob = new Blob([content], { type: getMimeType(format.toLowerCase()) });
    const url = URL.createObjectURL(blob);
    
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    
    URL.revokeObjectURL(url);
}

// 获取文件MIME类型
function getMimeType(format) {
    switch(format) {
        case 'csv':
            return 'text/csv';
        case 'excel':
            return 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
        case 'json':
            return 'application/json';
        default:
            return 'application/octet-stream';
    }
}

// 加载导出历史
async function loadExportHistory() {
    try {
        const result = await api.getExportHistory();
        if (result.success) {
            const exportHistory = result.data;
            const historyList = document.getElementById('export-history-list');
            
            if (exportHistory.length === 0) {
                historyList.innerHTML = '<p class="no-data">暂无导出历史</p>';
                return;
            }
            
            historyList.innerHTML = '';
            
            exportHistory.forEach(item => {
                const historyItem = document.createElement('div');
                historyItem.className = 'history-item';
                
                const fileIcon = getFileIcon(item.format.toLowerCase());
                
                historyItem.innerHTML = `
                    <div class="history-icon">${fileIcon}</div>
                    <div class="history-info">
                        <div class="history-file-name">${item.fileName}</div>
                        <div class="history-meta">
                            <span>${formatDateTime(item.exportedAt)}</span>
                            <span class="history-status success">成功</span>
                        </div>
                        <div class="history-result">导出 ${item.recordCount} 条数据</div>
                    </div>
                `;
                
                historyList.appendChild(historyItem);
            });
        }
    } catch (error) {
        console.error('加载导出历史错误:', error);
    }
}

// 获取文件图标
function getFileIcon(format) {
    switch(format) {
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

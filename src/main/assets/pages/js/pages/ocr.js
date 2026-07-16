// OCR识别页面JavaScript模块

function initOCRPage() {
    console.log('初始化OCR识别页面');
    
    // 加载OCR历史
    loadOCRHistory();
    
    // 为拍照按钮添加点击事件
    const cameraButton = document.querySelector('.ocr-actions .btn-primary');
    if (cameraButton) {
        cameraButton.addEventListener('click', function() {
            showMessage('拍照功能开发中', 'info');
        });
    }
    
    // 为上传图片按钮添加点击事件
    const uploadButton = document.querySelector('.ocr-actions .btn-secondary');
    if (uploadButton) {
        uploadButton.addEventListener('click', function() {
            // 创建隐藏的文件输入
            const fileInput = document.createElement('input');
            fileInput.type = 'file';
            fileInput.accept = 'image/*';
            fileInput.onchange = function(e) {
                const file = e.target.files[0];
                if (file) {
                    handleImageUpload(file);
                }
            };
            fileInput.click();
        });
    }
    
    // 为复制文本按钮添加点击事件
    const copyButton = document.querySelector('.result-actions .btn-primary');
    if (copyButton) {
        copyButton.addEventListener('click', function() {
            const resultText = document.querySelector('.result-text');
            if (resultText) {
                const text = resultText.textContent;
                navigator.clipboard.writeText(text).then(function() {
                    showMessage('文本已复制到剪贴板', 'success');
                }, function() {
                    showMessage('复制失败', 'error');
                });
            }
        });
    }
    
    // 为保存结果按钮添加点击事件
    const saveButton = document.querySelector('.result-actions .btn-secondary:nth-child(2)');
    if (saveButton) {
        saveButton.addEventListener('click', function() {
            showMessage('保存结果功能开发中', 'info');
        });
    }
    
    // 为导出为文件按钮添加点击事件
    const exportButton = document.querySelector('.result-actions .btn-secondary:nth-child(3)');
    if (exportButton) {
        exportButton.addEventListener('click', function() {
            const resultText = document.querySelector('.result-text');
            if (resultText) {
                const text = resultText.textContent;
                downloadTextFile(text, 'ocr_result.txt');
                showMessage('文件已导出', 'success');
            }
        });
    }
    
    // 为查看和删除按钮添加点击事件
    document.addEventListener('click', function(e) {
        if (e.target.closest('.action-btn.view')) {
            const historyItem = e.target.closest('.history-item');
            const historyContent = historyItem.querySelector('.history-content p').textContent;
            const resultText = document.querySelector('.result-text');
            if (resultText) {
                resultText.textContent = historyContent;
                showMessage('已加载历史记录', 'info');
            }
        }
        if (e.target.closest('.action-btn.delete')) {
            const historyItem = e.target.closest('.history-item');
            if (confirm('确定要删除这条历史记录吗？')) {
                showMessage('历史记录已删除', 'success');
                historyItem.remove();
            }
        }
    });
}

// 处理图片上传
function handleImageUpload(file) {
    // 创建图片预览
    const previewPlaceholder = document.querySelector('.preview-placeholder');
    if (previewPlaceholder) {
        const reader = new FileReader();
        reader.onload = function(e) {
            previewPlaceholder.innerHTML = `<img src="${e.target.result}" style="max-width: 100%; max-height: 100%; object-fit: contain;">`;
        };
        reader.readAsDataURL(file);
    }
    
    // 执行OCR识别
    performOCR(file);
}

// 执行OCR识别
async function performOCR(image) {
    try {
        showMessage('正在识别...', 'info');
        const response = await api.performOCR(image);
        if (response.success) {
            const resultText = document.querySelector('.result-text');
            if (resultText) {
                resultText.textContent = response.data.text;
            }
            showMessage('识别完成', 'success');
            // 重新加载OCR历史
            loadOCRHistory();
        } else {
            showMessage('识别失败', 'error');
        }
    } catch (error) {
        console.error('OCR识别失败:', error);
        showMessage('识别失败', 'error');
    }
}

// 加载OCR历史
async function loadOCRHistory() {
    try {
        const response = await api.getOCRHistory();
        if (response.success) {
            console.log('OCR历史加载成功:', response.data);
            // 这里可以根据需要更新页面上的OCR历史列表
        } else {
            showMessage('加载OCR历史失败', 'error');
        }
    } catch (error) {
        console.error('加载OCR历史失败:', error);
        showMessage('加载OCR历史失败', 'error');
    }
}

// 下载文本文件
function downloadTextFile(text, filename) {
    const element = document.createElement('a');
    const file = new Blob([text], {type: 'text/plain'});
    element.href = URL.createObjectURL(file);
    element.download = filename;
    document.body.appendChild(element);
    element.click();
    document.body.removeChild(element);
}

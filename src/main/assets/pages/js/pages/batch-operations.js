// 批量操作模块
const batchModule = {
    currentFilter: 'questions',
    selectedItems: new Set(),
    
    // 获取数据
    getData(filter) {
        switch (filter) {
            case 'questions':
                return JSON.parse(localStorage.getItem('smartquiz_questions') || '[]');
            case 'quizzes':
                return JSON.parse(localStorage.getItem('smartquiz_quizzes') || '[]');
            case 'notes':
                return JSON.parse(localStorage.getItem('smartquiz_notes') || '[]');
            default:
                return [];
        }
    },
    
    // 保存数据
    saveData(filter, data) {
        switch (filter) {
            case 'questions':
                localStorage.setItem('smartquiz_questions', JSON.stringify(data));
                break;
            case 'quizzes':
                localStorage.setItem('smartquiz_quizzes', JSON.stringify(data));
                break;
            case 'notes':
                localStorage.setItem('smartquiz_notes', JSON.stringify(data));
                break;
        }
    },
    
    // 渲染项目列表
    renderItems(filter) {
        this.currentFilter = filter;
        this.selectedItems.clear();
        
        const itemList = document.getElementById('itemList');
        const emptyState = document.getElementById('emptyState');
        const data = this.getData(filter);
        
        if (data.length === 0) {
            itemList.style.display = 'none';
            emptyState.style.display = 'block';
            this.updateSelectedCount();
            return;
        }
        
        itemList.style.display = 'block';
        emptyState.style.display = 'none';
        
        // 清空列表
        itemList.innerHTML = `
            <div class="list-header">
                <input type="checkbox" class="select-all" id="selectAll">
                <label for="selectAll">全选</label>
            </div>
        `;
        
        // 添加项目
        data.forEach(item => {
            const itemElement = document.createElement('div');
            itemElement.className = 'item';
            itemElement.innerHTML = `
                <input type="checkbox" class="item-checkbox" data-id="${item.id}">
                <div class="item-info">
                    <div class="item-title">${item.title || item.name || '无标题'}</div>
                    <div class="item-details">
                        ${filter === 'questions' ? `类型: ${item.type || '未知'} | 难度: ${item.difficulty || '未知'}` : 
                          filter === 'quizzes' ? `题目数量: ${item.questions ? item.questions.length : 0} | 时间: ${new Date(item.timestamp).toLocaleString()}` : 
                          `创建时间: ${new Date(item.createdAt || Date.now()).toLocaleString()}`}
                    </div>
                </div>
            `;
            itemList.appendChild(itemElement);
        });
        
        // 绑定全选事件
        document.getElementById('selectAll').addEventListener('change', (e) => {
            const checkboxes = document.querySelectorAll('.item-checkbox');
            checkboxes.forEach(checkbox => {
                checkbox.checked = e.target.checked;
                if (e.target.checked) {
                    this.selectedItems.add(checkbox.dataset.id);
                } else {
                    this.selectedItems.delete(checkbox.dataset.id);
                }
            });
            this.updateSelectedCount();
        });
        
        // 绑定单个选择事件
        document.querySelectorAll('.item-checkbox').forEach(checkbox => {
            checkbox.addEventListener('change', (e) => {
                if (e.target.checked) {
                    this.selectedItems.add(e.target.dataset.id);
                } else {
                    this.selectedItems.delete(e.target.dataset.id);
                }
                this.updateSelectedCount();
                
                // 更新全选状态
                const allChecked = document.querySelectorAll('.item-checkbox:checked').length === document.querySelectorAll('.item-checkbox').length;
                document.getElementById('selectAll').checked = allChecked;
            });
        });
        
        this.updateSelectedCount();
    },
    
    // 更新选择计数
    updateSelectedCount() {
        document.getElementById('countValue').textContent = this.selectedItems.size;
    },
    
    // 批量删除
    batchDelete() {
        if (this.selectedItems.size === 0) {
            alert('请先选择要删除的项目');
            return;
        }
        
        if (confirm(`确定要删除选中的 ${this.selectedItems.size} 个项目吗？`)) {
            const data = this.getData(this.currentFilter);
            const updatedData = data.filter(item => !this.selectedItems.has(item.id.toString()));
            this.saveData(this.currentFilter, updatedData);
            this.renderItems(this.currentFilter);
            alert('删除成功');
        }
    },
    
    // 批量导出
    batchExport() {
        if (this.selectedItems.size === 0) {
            alert('请先选择要导出的项目');
            return;
        }
        
        const data = this.getData(this.currentFilter);
        const selectedData = data.filter(item => this.selectedItems.has(item.id.toString()));
        
        // 创建导出内容
        const exportContent = JSON.stringify(selectedData, null, 2);
        
        // 创建下载链接
        const blob = new Blob([exportContent], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `batch-export-${this.currentFilter}-${Date.now()}.json`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
        
        alert('导出成功');
    },
    
    // 批量移动
    batchMove() {
        if (this.selectedItems.size === 0) {
            alert('请先选择要移动的项目');
            return;
        }
        
        // 这里可以实现移动功能，例如移动到不同的分类
        alert('移动功能开发中');
    },
    
    // 批量复制
    batchCopy() {
        if (this.selectedItems.size === 0) {
            alert('请先选择要复制的项目');
            return;
        }
        
        const data = this.getData(this.currentFilter);
        const selectedData = data.filter(item => this.selectedItems.has(item.id.toString()));
        
        // 复制项目
        const copiedItems = selectedData.map(item => {
            const copied = { ...item };
            copied.id = Date.now().toString() + Math.random().toString(36).substr(2, 9);
            copied.title = `${copied.title || copied.name} (副本)`;
            return copied;
        });
        
        const updatedData = [...data, ...copiedItems];
        this.saveData(this.currentFilter, updatedData);
        this.renderItems(this.currentFilter);
        alert('复制成功');
    },
    
    // 初始化
    init() {
        // 初始渲染
        this.renderItems('questions');
        
        // 绑定过滤按钮事件
        document.querySelectorAll('.filter-btn').forEach(btn => {
            btn.addEventListener('click', (e) => {
                document.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
                e.target.classList.add('active');
                const filter = e.target.dataset.filter;
                this.renderItems(filter);
            });
        });
    }
};

// 页面加载完成后初始化
document.addEventListener('DOMContentLoaded', () => {
    batchModule.init();
});
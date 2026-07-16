// 历史记录管理
const historyModule = {
    // 获取历史记录
    getHistory() {
        const history = localStorage.getItem('smartquiz_history');
        return history ? JSON.parse(history) : [];
    },
    
    // 添加历史记录
    addHistory(type, title, details) {
        const history = this.getHistory();
        const newItem = {
            id: Date.now(),
            type,
            title,
            details,
            timestamp: new Date().toISOString()
        };
        history.unshift(newItem);
        // 只保留最近100条记录
        if (history.length > 100) {
            history.splice(100);
        }
        localStorage.setItem('smartquiz_history', JSON.stringify(history));
    },
    
    // 清空历史记录
    clearHistory() {
        if (confirm('确定要清空所有历史记录吗？')) {
            localStorage.removeItem('smartquiz_history');
            this.renderHistory();
        }
    },
    
    // 过滤历史记录
    filterHistory(filter, timeRange) {
        let history = this.getHistory();
        
        // 按时间范围过滤
        if (timeRange === 'week') {
            const weekAgo = new Date();
            weekAgo.setDate(weekAgo.getDate() - 7);
            history = history.filter(item => new Date(item.timestamp) >= weekAgo);
        } else if (timeRange === 'month') {
            const monthAgo = new Date();
            monthAgo.setMonth(monthAgo.getMonth() - 1);
            history = history.filter(item => new Date(item.timestamp) >= monthAgo);
        }
        
        // 按类型过滤
        if (filter !== 'all') {
            history = history.filter(item => item.type === filter);
        }
        
        return history;
    },
    
    // 渲染历史记录
    renderHistory(filter = 'all', timeRange = 'recent') {
        const historyList = document.getElementById('historyList');
        const emptyState = document.getElementById('emptyState');
        const filteredHistory = this.filterHistory(filter, timeRange);
        
        if (filteredHistory.length === 0) {
            historyList.style.display = 'none';
            emptyState.style.display = 'block';
            return;
        }
        
        historyList.style.display = 'block';
        emptyState.style.display = 'none';
        
        historyList.innerHTML = filteredHistory.map(item => {
            const date = new Date(item.timestamp);
            const formattedDate = date.toLocaleString('zh-CN');
            
            return `
                <li class="history-item">
                    <div class="history-item-header">
                        <span class="history-item-title">${item.title}</span>
                        <span class="history-item-time">${formattedDate}</span>
                    </div>
                    <div class="history-item-details">${item.details}</div>
                </li>
            `;
        }).join('');
    },
    
    // 初始化
    init() {
        // 渲染初始历史记录
        this.renderHistory();
        
        // 绑定标签切换事件
        document.querySelectorAll('.tab').forEach(tab => {
            tab.addEventListener('click', () => {
                document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
                tab.classList.add('active');
                const timeRange = tab.dataset.tab;
                const filter = document.querySelector('.filter-btn.active').dataset.filter;
                this.renderHistory(filter, timeRange);
            });
        });
        
        // 绑定过滤按钮事件
        document.querySelectorAll('.filter-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                document.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                const filter = btn.dataset.filter;
                const timeRange = document.querySelector('.tab.active').dataset.tab;
                this.renderHistory(filter, timeRange);
            });
        });
        
        // 绑定清空按钮事件
        window.clearHistory = () => this.clearHistory();
    }
};

// 页面加载完成后初始化
document.addEventListener('DOMContentLoaded', () => {
    historyModule.init();
});
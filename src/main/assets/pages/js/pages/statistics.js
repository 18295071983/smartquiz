// 统计分析模块
const statisticsModule = {
    // 获取统计数据
    getStatistics(dateRange) {
        const quizzes = JSON.parse(localStorage.getItem('smartquiz_quizzes') || '[]');
        const history = JSON.parse(localStorage.getItem('smartquiz_history') || '[]');
        
        // 计算时间范围
        const now = new Date();
        let startTime;
        
        switch (dateRange) {
            case 'week':
                startTime = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000);
                break;
            case 'month':
                startTime = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000);
                break;
            case 'year':
                startTime = new Date(now.getTime() - 365 * 24 * 60 * 60 * 1000);
                break;
            default: // all
                startTime = new Date(0);
        }
        
        // 过滤数据
        const filteredQuizzes = quizzes.filter(quiz => {
            const quizDate = new Date(quiz.timestamp);
            return quizDate >= startTime;
        });
        
        const filteredHistory = history.filter(item => {
            const itemDate = new Date(item.timestamp);
            return itemDate >= startTime;
        });
        
        // 计算统计数据
        const totalQuizzes = filteredQuizzes.length;
        const totalQuestions = filteredQuizzes.reduce((sum, quiz) => sum + quiz.questions.length, 0);
        const totalCorrect = filteredQuizzes.reduce((sum, quiz) => {
            const correctCount = quiz.questions.filter(q => q.userAnswer === q.correctAnswer).length;
            return sum + correctCount;
        }, 0);
        
        // 计算学习时长（假设每次测验平均10分钟）
        const totalTime = Math.round(totalQuizzes * 10);
        
        // 计算正确率
        const accuracyRate = totalQuestions > 0 ? Math.round((totalCorrect / totalQuestions) * 100) : 0;
        
        // 计算学习进度
        const knowledgeProgress = Math.min(100, Math.round((totalCorrect / 100) * 100));
        const planProgress = Math.min(100, Math.round((totalQuizzes / 20) * 100));
        const reviewProgress = Math.min(100, Math.round((filteredHistory.filter(item => item.type === 'review').length / 10) * 100));
        
        return {
            totalQuizzes,
            totalCorrect,
            totalTime,
            accuracyRate,
            knowledgeProgress,
            planProgress,
            reviewProgress,
            quizzes: filteredQuizzes
        };
    },
    
    // 渲染统计卡片
    renderStatsCards(stats) {
        document.getElementById('totalQuizzes').textContent = stats.totalQuizzes;
        document.getElementById('totalCorrect').textContent = stats.totalCorrect;
        document.getElementById('totalTime').textContent = stats.totalTime;
        document.getElementById('accuracyRate').textContent = `${stats.accuracyRate}%`;
        
        // 渲染进度条
        document.getElementById('knowledgeProgress').textContent = `${stats.knowledgeProgress}%`;
        document.getElementById('knowledgeFill').style.width = `${stats.knowledgeProgress}%`;
        
        document.getElementById('planProgress').textContent = `${stats.planProgress}%`;
        document.getElementById('planFill').style.width = `${stats.planProgress}%`;
        
        document.getElementById('reviewProgress').textContent = `${stats.reviewProgress}%`;
        document.getElementById('reviewFill').style.width = `${stats.reviewProgress}%`;
    },
    
    // 渲染图表
    renderChart(stats, chartType) {
        const chartContainer = document.getElementById('trendChart');
        
        // 生成模拟数据
        const labels = [];
        const data = [];
        
        // 生成最近7天的数据
        for (let i = 6; i >= 0; i--) {
            const date = new Date();
            date.setDate(date.getDate() - i);
            labels.push(`${date.getMonth() + 1}/${date.getDate()}`);
            
            // 模拟每天的测验次数
            data.push(Math.floor(Math.random() * 5) + 1);
        }
        
        // 清空图表容器
        chartContainer.innerHTML = '';
        
        // 创建简单的图表
        if (chartType === 'line') {
            // 折线图
            let svg = `<svg width="100%" height="100%" viewBox="0 0 800 250">`;
            
            // 绘制网格
            svg += `<g stroke="#e0e0e0" stroke-width="1">`;
            for (let i = 0; i <= 5; i++) {
                const y = 20 + (i * 40);
                svg += `<line x1="50" y1="${y}" x2="750" y2="${y}" />`;
            }
            for (let i = 0; i <= 7; i++) {
                const x = 50 + (i * 100);
                svg += `<line x1="${x}" y1="20" x2="${x}" y2="220" />`;
            }
            svg += `</g>`;
            
            // 绘制折线
            svg += `<g stroke="${getComputedStyle(document.documentElement).getPropertyValue('--primary')}" stroke-width="2" fill="none">`;
            svg += `<path d="`;
            for (let i = 0; i < data.length; i++) {
                const x = 50 + (i * 100);
                const y = 220 - (data[i] * 40);
                if (i === 0) {
                    svg += `M ${x} ${y}`;
                } else {
                    svg += ` L ${x} ${y}`;
                }
            }
            svg += `" />`;
            svg += `</g>`;
            
            // 绘制数据点
            svg += `<g fill="${getComputedStyle(document.documentElement).getPropertyValue('--primary')}">`;
            for (let i = 0; i < data.length; i++) {
                const x = 50 + (i * 100);
                const y = 220 - (data[i] * 40);
                svg += `<circle cx="${x}" cy="${y}" r="4" />`;
            }
            svg += `</g>`;
            
            // 绘制标签
            svg += `<g font-size="12" fill="${getComputedStyle(document.documentElement).getPropertyValue('--text-medium')}" text-anchor="middle">`;
            for (let i = 0; i < labels.length; i++) {
                const x = 50 + (i * 100);
                svg += `<text x="${x}" y="240">${labels[i]}</text>`;
            }
            svg += `</g>`;
            
            svg += `</svg>`;
            chartContainer.innerHTML = svg;
        } else {
            // 柱状图
            let svg = `<svg width="100%" height="100%" viewBox="0 0 800 250">`;
            
            // 绘制网格
            svg += `<g stroke="#e0e0e0" stroke-width="1">`;
            for (let i = 0; i <= 5; i++) {
                const y = 20 + (i * 40);
                svg += `<line x1="50" y1="${y}" x2="750" y2="${y}" />`;
            }
            for (let i = 0; i <= 7; i++) {
                const x = 50 + (i * 100);
                svg += `<line x1="${x}" y1="20" x2="${x}" y2="220" />`;
            }
            svg += `</g>`;
            
            // 绘制柱状图
            svg += `<g fill="${getComputedStyle(document.documentElement).getPropertyValue('--primary')}">`;
            for (let i = 0; i < data.length; i++) {
                const x = 70 + (i * 100);
                const width = 60;
                const height = data[i] * 40;
                const y = 220 - height;
                svg += `<rect x="${x}" y="${y}" width="${width}" height="${height}" rx="4" />`;
            }
            svg += `</g>`;
            
            // 绘制标签
            svg += `<g font-size="12" fill="${getComputedStyle(document.documentElement).getPropertyValue('--text-medium')}" text-anchor="middle">`;
            for (let i = 0; i < labels.length; i++) {
                const x = 100 + (i * 100);
                svg += `<text x="${x}" y="240">${labels[i]}</text>`;
            }
            svg += `</g>`;
            
            svg += `</svg>`;
            chartContainer.innerHTML = svg;
        }
    },
    
    // 初始化
    init() {
        // 初始渲染
        const stats = this.getStatistics('month');
        this.renderStatsCards(stats);
        this.renderChart(stats, 'line');
        
        // 绑定日期范围选择事件
        document.getElementById('dateRange').addEventListener('change', (e) => {
            const dateRange = e.target.value;
            const stats = this.getStatistics(dateRange);
            this.renderStatsCards(stats);
            const chartType = document.getElementById('chartType').value;
            this.renderChart(stats, chartType);
        });
        
        // 绑定图表类型选择事件
        document.getElementById('chartType').addEventListener('change', (e) => {
            const chartType = e.target.value;
            const dateRange = document.getElementById('dateRange').value;
            const stats = this.getStatistics(dateRange);
            this.renderChart(stats, chartType);
        });
    }
};

// 页面加载完成后初始化
document.addEventListener('DOMContentLoaded', () => {
    statisticsModule.init();
});
// 错题集页面JavaScript模块

function initWrongQuestionPage() {
    console.log('初始化错题集页面');
    
    // 加载错题数据
    loadWrongQuestions();
    
    // 为清空错题按钮添加点击事件
    const clearButton = document.querySelector('.btn-primary');
    if (clearButton) {
        clearButton.addEventListener('click', function() {
            if (confirm('确定要清空所有错题吗？')) {
                clearWrongQuestions();
            }
        });
    }
    
    // 为筛选按钮添加点击事件
    const filterButton = document.querySelector('.btn-secondary');
    if (filterButton) {
        filterButton.addEventListener('click', function() {
            showMessage('筛选功能开发中', 'info');
        });
    }
    
    // 为搜索按钮添加点击事件
    const searchButton = document.querySelector('.search-button');
    if (searchButton) {
        searchButton.addEventListener('click', searchWrongQuestions);
    }
    
    // 为筛选下拉框添加 change 事件
    const filters = document.querySelectorAll('.filter-bar select');
    filters.forEach(filter => {
        filter.addEventListener('change', filterWrongQuestions);
    });
    
    // 为复习和移除按钮添加点击事件
    document.addEventListener('click', function(e) {
        if (e.target.closest('.action-btn.review')) {
            const wrongQuestionItem = e.target.closest('.wrong-question-item');
            showMessage('复习错题功能开发中', 'info');
        }
        if (e.target.closest('.action-btn.remove')) {
            const wrongQuestionItem = e.target.closest('.wrong-question-item');
            if (confirm('确定要移除这道错题吗？')) {
                showMessage('错题移除成功', 'success');
                wrongQuestionItem.remove();
            }
        }
    });
    
    // 为分页按钮添加点击事件
    const pageButtons = document.querySelectorAll('.page-btn');
    pageButtons.forEach(button => {
        button.addEventListener('click', function() {
            if (!this.classList.contains('disabled')) {
                // 移除所有按钮的 active 类
                pageButtons.forEach(btn => btn.classList.remove('active'));
                // 添加 active 类到当前按钮
                this.classList.add('active');
                // 模拟加载对应页的数据
                loadWrongQuestions();
            }
        });
    });
}

// 加载错题数据
async function loadWrongQuestions() {
    try {
        const response = await api.getWrongQuestions();
        if (response.success) {
            console.log('错题数据加载成功:', response.data);
            // 这里可以根据需要更新页面上的错题列表
        } else {
            showMessage('加载错题失败', 'error');
        }
    } catch (error) {
        console.error('加载错题失败:', error);
        showMessage('加载错题失败', 'error');
    }
}

// 清空错题集
async function clearWrongQuestions() {
    try {
        const response = await api.clearWrongQuestions();
        if (response.success) {
            showMessage('错题集已清空', 'success');
            // 清空页面上的错题列表
            const wrongQuestionList = document.querySelector('.wrong-question-list');
            if (wrongQuestionList) {
                wrongQuestionList.innerHTML = '<p style="text-align: center; color: #6B7280; padding: 20px;">暂无错题</p>';
            }
        } else {
            showMessage('清空错题集失败', 'error');
        }
    } catch (error) {
        console.error('清空错题集失败:', error);
        showMessage('清空错题集失败', 'error');
    }
}

// 搜索错题
function searchWrongQuestions() {
    const searchInput = document.querySelector('.search-bar input');
    const searchTerm = searchInput.value.trim();
    if (searchTerm) {
        showMessage(`搜索错题: ${searchTerm}`, 'info');
        // 这里可以根据搜索词过滤错题
    }
}

// 筛选错题
function filterWrongQuestions() {
    const typeFilter = document.querySelector('.filter-bar select:first-child');
    const difficultyFilter = document.querySelector('.filter-bar select:last-child');
    
    const type = typeFilter.value;
    const difficulty = difficultyFilter.value;
    
    showMessage(`筛选错题: 类型=${type}, 难度=${difficulty}`, 'info');
    // 这里可以根据筛选条件过滤错题
}

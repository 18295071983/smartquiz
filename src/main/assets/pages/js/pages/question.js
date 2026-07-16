// 题目管理页面JavaScript模块

function initQuestionPage() {
    console.log('初始化题目管理页面');
    
    // 加载题目数据
    loadQuestions();
    
    // 为搜索按钮添加点击事件
    const searchButton = document.querySelector('.search-button');
    if (searchButton) {
        searchButton.addEventListener('click', searchQuestions);
    }
    
    // 为筛选下拉框添加 change 事件
    const filters = document.querySelectorAll('.filter-bar select');
    filters.forEach(filter => {
        filter.addEventListener('change', filterQuestions);
    });
    
    // 为添加题目按钮添加点击事件
    const addQuestionButton = document.querySelector('.btn-primary');
    if (addQuestionButton) {
        addQuestionButton.addEventListener('click', function() {
            showMessage('添加题目功能开发中', 'info');
        });
    }
    
    // 为批量导入按钮添加点击事件
    const batchImportButton = document.querySelector('.btn-secondary');
    if (batchImportButton) {
        batchImportButton.addEventListener('click', function() {
            showMessage('批量导入功能开发中', 'info');
        });
    }
    
    // 为编辑和删除按钮添加点击事件
    document.addEventListener('click', function(e) {
        if (e.target.closest('.action-btn.edit')) {
            const questionItem = e.target.closest('.question-item');
            showMessage('编辑题目功能开发中', 'info');
        }
        if (e.target.closest('.action-btn.delete')) {
            const questionItem = e.target.closest('.question-item');
            if (confirm('确定要删除这道题目吗？')) {
                showMessage('题目删除成功', 'success');
                questionItem.remove();
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
                loadQuestions();
            }
        });
    });
}

// 加载题目数据
async function loadQuestions() {
    try {
        const response = await api.getQuestions();
        if (response.success) {
            // 这里可以根据需要更新页面上的题目列表
            console.log('题目数据加载成功:', response.data);
        }
    } catch (error) {
        console.error('加载题目失败:', error);
        showMessage('加载题目失败', 'error');
    }
}

// 搜索题目
function searchQuestions() {
    const searchInput = document.querySelector('.search-bar input');
    const searchTerm = searchInput.value.trim();
    if (searchTerm) {
        showMessage(`搜索: ${searchTerm}`, 'info');
        // 这里可以根据搜索词过滤题目
    }
}

// 筛选题目
function filterQuestions() {
    const typeFilter = document.querySelector('.filter-bar select:first-child');
    const difficultyFilter = document.querySelector('.filter-bar select:last-child');
    
    const type = typeFilter.value;
    const difficulty = difficultyFilter.value;
    
    showMessage(`筛选: 类型=${type}, 难度=${difficulty}`, 'info');
    // 这里可以根据筛选条件过滤题目
}

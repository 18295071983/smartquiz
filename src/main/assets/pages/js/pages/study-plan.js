// 学习计划页面JavaScript模块

function initStudyPlanPage() {
    console.log('初始化学习计划页面');
    
    // 加载学习计划数据
    loadStudyPlans();
    
    // 为添加计划按钮添加点击事件
    const addPlanButton = document.querySelector('.btn-primary');
    if (addPlanButton) {
        addPlanButton.addEventListener('click', function() {
            showMessage('添加计划功能开发中', 'info');
        });
    }
    
    // 为日历视图按钮添加点击事件
    const calendarButton = document.querySelector('.btn-secondary');
    if (calendarButton) {
        calendarButton.addEventListener('click', function() {
            showMessage('日历视图功能开发中', 'info');
        });
    }
    
    // 为编辑、删除和查看按钮添加点击事件
    document.addEventListener('click', function(e) {
        if (e.target.closest('.action-btn.edit')) {
            const planItem = e.target.closest('.plan-item');
            showMessage('编辑计划功能开发中', 'info');
        }
        if (e.target.closest('.action-btn.delete')) {
            const planItem = e.target.closest('.plan-item');
            if (confirm('确定要删除这个学习计划吗？')) {
                showMessage('计划删除成功', 'success');
                planItem.remove();
            }
        }
        if (e.target.closest('.action-btn.view')) {
            const planItem = e.target.closest('.plan-item');
            showMessage('查看计划详情功能开发中', 'info');
        }
    });
}

// 加载学习计划数据
async function loadStudyPlans() {
    try {
        const response = await api.getStudyPlans();
        if (response.success) {
            console.log('学习计划数据加载成功:', response.data);
            // 这里可以根据需要更新页面上的学习计划列表
        } else {
            showMessage('加载学习计划失败', 'error');
        }
    } catch (error) {
        console.error('加载学习计划失败:', error);
        showMessage('加载学习计划失败', 'error');
    }
}

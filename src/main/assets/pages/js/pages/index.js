// 主页面JavaScript模块

function initIndexPage() {
    console.log('初始化主页面');
    
    // 为所有卡片添加点击事件
    const cards = document.querySelectorAll('.card');
    cards.forEach(card => {
        card.addEventListener('click', function() {
            // 获取卡片对应的页面
            const page = this.getAttribute('onclick').match(/navigateTo\('([^']+)'\)/)[1];
            if (page) {
                navigateTo(page);
            }
        });
    });
    
    // 添加页面加载动画
    document.body.classList.add('loaded');
    
    // 初始化主题切换
    initThemeToggle();
}

// 初始化主题切换
function initThemeToggle() {
    // 检查是否已有主题切换按钮
    let themeToggle = document.querySelector('.theme-toggle');
    if (!themeToggle) {
        // 创建主题切换按钮
        themeToggle = document.createElement('button');
        themeToggle.className = 'theme-toggle';
        themeToggle.innerHTML = '<i class="fas fa-moon"></i>';
        themeToggle.style.position = 'fixed';
        themeToggle.style.bottom = '20px';
        themeToggle.style.right = '20px';
        themeToggle.style.width = '50px';
        themeToggle.style.height = '50px';
        themeToggle.style.borderRadius = '50%';
        themeToggle.style.backgroundColor = '#3B82F6';
        themeToggle.style.color = 'white';
        themeToggle.style.border = 'none';
        themeToggle.style.boxShadow = '0 4px 6px rgba(0, 0, 0, 0.1)';
        themeToggle.style.cursor = 'pointer';
        themeToggle.style.fontSize = '20px';
        themeToggle.style.display = 'flex';
        themeToggle.style.alignItems = 'center';
        themeToggle.style.justifyContent = 'center';
        themeToggle.style.zIndex = '1000';
        
        document.body.appendChild(themeToggle);
    }
    
    // 更新主题图标
    updateThemeIcon();
    
    // 添加点击事件
    themeToggle.addEventListener('click', function() {
        toggleTheme();
        updateThemeIcon();
    });
}

// 更新主题图标
function updateThemeIcon() {
    const themeToggle = document.querySelector('.theme-toggle');
    if (themeToggle) {
        if (document.body.classList.contains('dark-theme')) {
            themeToggle.innerHTML = '<i class="fas fa-sun"></i>';
        } else {
            themeToggle.innerHTML = '<i class="fas fa-moon"></i>';
        }
    }
}

// 页面加载动画
window.addEventListener('load', function() {
    document.body.classList.add('loaded');
});

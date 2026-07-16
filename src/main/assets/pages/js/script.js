// 模块化JavaScript入口文件

// 导航功能
function navigateTo(page) {
    window.location.href = page;
}

// 页面加载完成后执行
window.addEventListener('DOMContentLoaded', function() {
    // 初始化页面
    initializePage();
    
    // 检查主题设置
    checkTheme();
});

// 初始化页面函数
function initializePage() {
    console.log('页面初始化完成');
    
    // 为卡片添加点击效果
    const cards = document.querySelectorAll('.card');
    cards.forEach(card => {
        card.addEventListener('click', function() {
            this.style.transform = 'scale(0.95)';
            setTimeout(() => {
                this.style.transform = '';
            }, 150);
        });
    });
    
    // 根据当前页面加载对应模块
    const currentPage = window.location.pathname.split('/').pop() || 'index.html';
    loadPageModule(currentPage);
}

// 加载页面模块
function loadPageModule(page) {
    switch(page) {
        case 'index.html':
            if (typeof initIndexPage === 'function') {
                initIndexPage();
            }
            break;
        case 'question.html':
            if (typeof initQuestionPage === 'function') {
                initQuestionPage();
            }
            break;
        case 'quiz.html':
            if (typeof initQuizPage === 'function') {
                initQuizPage();
            }
            break;
        case 'study-plan.html':
            if (typeof initStudyPlanPage === 'function') {
                initStudyPlanPage();
            }
            break;
        case 'ai-chat.html':
            if (typeof initAIChatPage === 'function') {
                initAIChatPage();
            }
            break;
        case 'wrong-question.html':
            if (typeof initWrongQuestionPage === 'function') {
                initWrongQuestionPage();
            }
            break;
        case 'ocr.html':
            if (typeof initOCRPage === 'function') {
                initOCRPage();
            }
            break;
        case 'import.html':
            if (typeof initImportPage === 'function') {
                initImportPage();
            }
            break;
        case 'export.html':
            if (typeof initExportPage === 'function') {
                initExportPage();
            }
            break;
        case 'note.html':
            if (typeof initNotePage === 'function') {
                initNotePage();
            }
            break;
        case 'backup.html':
            if (typeof initBackupPage === 'function') {
                initBackupPage();
            }
            break;
        case 'user.html':
            if (typeof initUserPage === 'function') {
                initUserPage();
            }
            break;
        case 'theme.html':
            if (typeof initThemePage === 'function') {
                initThemePage();
            }
            break;
        case 'language.html':
            if (typeof initLanguagePage === 'function') {
                initLanguagePage();
            }
            break;
        case 'environment.html':
            if (typeof initEnvironmentPage === 'function') {
                initEnvironmentPage();
            }
            break;
        case 'test.html':
            if (typeof initTestPage === 'function') {
                initTestPage();
            }
            break;
    }
}

// 主题切换功能
function toggleTheme() {
    document.body.classList.toggle('dark-theme');
    localStorage.setItem('theme', document.body.classList.contains('dark-theme') ? 'dark' : 'light');
}

// 检查本地存储中的主题设置
function checkTheme() {
    const savedTheme = localStorage.getItem('theme');
    if (savedTheme === 'dark') {
        document.body.classList.add('dark-theme');
    }
}

// 导入页面模块
const scripts = [
    'js/utils.js',
    'js/api.js',
    'js/pages/index.js',
    'js/pages/question.js',
    'js/pages/quiz.js',
    'js/pages/study-plan.js',
    'js/pages/ai-chat.js',
    'js/pages/wrong-question.js',
    'js/pages/ocr.js',
    'js/pages/import.js',
    'js/pages/export.js',
    'js/pages/note.js',
    'js/pages/backup.js',
    'js/pages/user.js',
    'js/pages/theme.js',
    'js/pages/language.js',
    'js/pages/environment.js',
    'js/pages/test.js'
];

// 动态加载脚本
scripts.forEach(src => {
    const script = document.createElement('script');
    script.src = src;
    document.head.appendChild(script);
});

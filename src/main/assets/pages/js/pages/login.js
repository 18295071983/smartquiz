// 登录模块
const loginModule = {
    // 显示错误信息
    showError(message) {
        const errorElement = document.getElementById('errorMessage');
        errorElement.textContent = message;
        errorElement.style.display = 'block';
    },
    
    // 隐藏错误信息
    hideError() {
        const errorElement = document.getElementById('errorMessage');
        errorElement.style.display = 'none';
    },
    
    // 获取用户数据
    getUsers() {
        return JSON.parse(localStorage.getItem('smartquiz_users') || '[]');
    },
    
    // 验证用户
    validateUser(username, password) {
        const users = this.getUsers();
        return users.find(user => user.username === username && user.password === password);
    },
    
    // 设置登录状态
    setLoggedInUser(user) {
        localStorage.setItem('smartquiz_loggedInUser', JSON.stringify(user));
    },
    
    // 处理登录
    handleLogin(e) {
        e.preventDefault();
        this.hideError();
        
        const username = document.getElementById('username').value;
        const password = document.getElementById('password').value;
        const remember = document.getElementById('remember').checked;
        
        // 验证输入
        if (!username || !password) {
            this.showError('请输入用户名和密码');
            return;
        }
        
        // 验证用户
        const user = this.validateUser(username, password);
        if (!user) {
            this.showError('用户名或密码错误');
            return;
        }
        
        // 设置登录状态
        this.setLoggedInUser(user);
        
        // 如果勾选了记住我，存储用户信息
        if (remember) {
            localStorage.setItem('smartquiz_rememberedUser', JSON.stringify({
                username: user.username,
                password: user.password
            }));
        } else {
            localStorage.removeItem('smartquiz_rememberedUser');
        }
        
        // 登录成功，跳转到首页
        alert('登录成功');
        window.location.href = 'index.html';
    },
    
    // 初始化
    init() {
        // 检查是否有记住的用户
        const rememberedUser = localStorage.getItem('smartquiz_rememberedUser');
        if (rememberedUser) {
            const user = JSON.parse(rememberedUser);
            document.getElementById('username').value = user.username;
            document.getElementById('password').value = user.password;
            document.getElementById('remember').checked = true;
        }
        
        // 绑定表单提交事件
        document.getElementById('loginForm').addEventListener('submit', (e) => this.handleLogin(e));
        
        // 绑定忘记密码链接
        document.querySelector('.forgot-password').addEventListener('click', (e) => {
            e.preventDefault();
            alert('忘记密码功能开发中');
        });
        
        // 绑定社交登录按钮
        document.querySelectorAll('.social-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                alert('社交登录功能开发中');
            });
        });
    }
};

// 页面加载完成后初始化
document.addEventListener('DOMContentLoaded', () => {
    loginModule.init();
});
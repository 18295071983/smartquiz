// 注册模块
const registerModule = {
    // 显示错误信息
    showError(message) {
        const errorElement = document.getElementById('errorMessage');
        const successElement = document.getElementById('successMessage');
        errorElement.textContent = message;
        errorElement.style.display = 'block';
        successElement.style.display = 'none';
    },
    
    // 显示成功信息
    showSuccess(message) {
        const errorElement = document.getElementById('errorMessage');
        const successElement = document.getElementById('successMessage');
        successElement.textContent = message;
        successElement.style.display = 'block';
        errorElement.style.display = 'none';
    },
    
    // 隐藏所有消息
    hideMessages() {
        document.getElementById('errorMessage').style.display = 'none';
        document.getElementById('successMessage').style.display = 'none';
    },
    
    // 获取用户数据
    getUsers() {
        return JSON.parse(localStorage.getItem('smartquiz_users') || '[]');
    },
    
    // 保存用户数据
    saveUsers(users) {
        localStorage.setItem('smartquiz_users', JSON.stringify(users));
    },
    
    // 检查用户名是否已存在
    isUsernameExists(username) {
        const users = this.getUsers();
        return users.some(user => user.username === username);
    },
    
    // 处理注册
    handleRegister(e) {
        e.preventDefault();
        this.hideMessages();
        
        const username = document.getElementById('username').value;
        const password = document.getElementById('password').value;
        const confirmPassword = document.getElementById('confirmPassword').value;
        const agree = document.getElementById('agree').checked;
        
        // 验证输入
        if (!username || !password || !confirmPassword) {
            this.showError('请填写所有必填字段');
            return;
        }
        
        if (password !== confirmPassword) {
            this.showError('两次输入的密码不一致');
            return;
        }
        
        if (!agree) {
            this.showError('请同意服务条款和隐私政策');
            return;
        }
        
        // 检查用户名是否已存在
        if (this.isUsernameExists(username)) {
            this.showError('用户名已存在');
            return;
        }
        
        // 创建新用户
        const newUser = {
            id: Date.now().toString(),
            username,
            password,
            createdAt: new Date().toISOString(),
            role: 'user'
        };
        
        // 保存用户
        const users = this.getUsers();
        users.push(newUser);
        this.saveUsers(users);
        
        // 显示成功信息
        this.showSuccess('注册成功，请登录');
        
        // 清空表单
        document.getElementById('registerForm').reset();
        
        // 3秒后跳转到登录页
        setTimeout(() => {
            window.location.href = 'login.html';
        }, 3000);
    },
    
    // 初始化
    init() {
        // 绑定表单提交事件
        document.getElementById('registerForm').addEventListener('submit', (e) => this.handleRegister(e));
        
        // 绑定服务条款和隐私政策链接
        document.querySelectorAll('.form-options a').forEach(link => {
            link.addEventListener('click', (e) => {
                e.preventDefault();
                alert('功能开发中');
            });
        });
    }
};

// 页面加载完成后初始化
document.addEventListener('DOMContentLoaded', () => {
    registerModule.init();
});
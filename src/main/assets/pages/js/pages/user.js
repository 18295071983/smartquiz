// 用户管理页面初始化
function initUserPage() {
    console.log('用户管理页面初始化');
    
    // 绑定事件
    bindUserEvents();
    
    // 加载用户信息
    loadUserInfo();
    
    // 加载通知设置
    loadNotificationSettings();
}

// 绑定事件
function bindUserEvents() {
    // 保存个人信息按钮
    document.getElementById('save-profile-btn').addEventListener('click', function() {
        saveUserProfile();
    });
    
    // 更换头像按钮
    document.getElementById('change-avatar-btn').addEventListener('click', function() {
        document.getElementById('avatar-upload').click();
    });
    
    // 头像上传
    document.getElementById('avatar-upload').addEventListener('change', function(e) {
        if (e.target.files.length > 0) {
            changeAvatar(e.target.files[0]);
        }
    });
    
    // 修改密码按钮
    document.getElementById('change-password-btn').addEventListener('click', function() {
        changePassword();
    });
    
    // 保存通知设置按钮
    document.getElementById('save-notification-btn').addEventListener('click', function() {
        saveNotificationSettings();
    });
    
    // 退出登录按钮
    document.getElementById('logout-btn').addEventListener('click', function() {
        logout();
    });
    
    // 删除账户按钮
    document.getElementById('delete-account-btn').addEventListener('click', function() {
        deleteAccount();
    });
}

// 加载用户信息
async function loadUserInfo() {
    try {
        const result = await api.getUserInfo();
        if (result.success) {
            const userInfo = result.data;
            
            document.getElementById('username').value = userInfo.username || '';
            document.getElementById('email').value = userInfo.email || '';
            document.getElementById('phone').value = userInfo.phone || '';
            document.getElementById('bio').value = userInfo.bio || '';
            
            // 加载头像
            if (userInfo.avatar) {
                const avatarContainer = document.getElementById('avatar-container');
                avatarContainer.innerHTML = `<img src="${userInfo.avatar}" alt="头像">`;
            }
        }
    } catch (error) {
        console.error('加载用户信息错误:', error);
    }
    
    // 更新最后登录信息
    const lastLogin = localStorage.getItem('lastLogin') || '2026-03-15 12:34:56';
    document.getElementById('last-login').textContent = lastLogin;
    
    // 更新登录设备信息
    const loginDevice = getDeviceInfo();
    document.getElementById('login-device').textContent = loginDevice;
}

// 保存个人信息
async function saveUserProfile() {
    const userInfo = {
        username: document.getElementById('username').value,
        email: document.getElementById('email').value,
        phone: document.getElementById('phone').value,
        bio: document.getElementById('bio').value
    };
    
    if (!userInfo.username) {
        showMessage('请输入用户名', 'error');
        return;
    }
    
    if (!userInfo.email) {
        showMessage('请输入邮箱', 'error');
        return;
    }
    
    try {
        const result = await api.updateUserInfo(userInfo);
        if (result.success) {
            showMessage('个人信息保存成功', 'success');
        } else {
            showMessage('个人信息保存失败', 'error');
        }
    } catch (error) {
        showMessage('保存个人信息时出现错误', 'error');
        console.error('保存个人信息错误:', error);
    }
}

// 更换头像
function changeAvatar(file) {
    const reader = new FileReader();
    
    reader.onload = async function(e) {
        const avatarUrl = e.target.result;
        
        // 更新头像显示
        const avatarContainer = document.getElementById('avatar-container');
        avatarContainer.innerHTML = `<img src="${avatarUrl}" alt="头像">`;
        
        try {
            const result = await api.updateUserInfo({ avatar: avatarUrl });
            if (result.success) {
                showMessage('头像更换成功', 'success');
            } else {
                showMessage('头像更换失败', 'error');
            }
        } catch (error) {
            showMessage('更换头像时出现错误', 'error');
            console.error('更换头像错误:', error);
        }
    };
    
    reader.readAsDataURL(file);
}

// 修改密码
function changePassword() {
    const oldPassword = document.getElementById('old-password').value;
    const newPassword = document.getElementById('new-password').value;
    const confirmPassword = document.getElementById('confirm-password').value;
    
    if (!oldPassword) {
        showMessage('请输入旧密码', 'error');
        return;
    }
    
    if (!newPassword) {
        showMessage('请输入新密码', 'error');
        return;
    }
    
    if (newPassword !== confirmPassword) {
        showMessage('两次输入的密码不一致', 'error');
        return;
    }
    
    if (newPassword.length < 6) {
        showMessage('新密码长度不能少于6位', 'error');
        return;
    }
    
    // 模拟密码修改
    setTimeout(() => {
        // 保存密码（实际应用中应该加密存储）
        localStorage.setItem('password', newPassword);
        
        // 清空表单
        document.getElementById('old-password').value = '';
        document.getElementById('new-password').value = '';
        document.getElementById('confirm-password').value = '';
        
        showMessage('密码修改成功', 'success');
    }, 1000);
}

// 加载通知设置
function loadNotificationSettings() {
    const notificationSettings = JSON.parse(localStorage.getItem('notificationSettings') || '{}');
    
    document.getElementById('notification-email').checked = notificationSettings.email || false;
    document.getElementById('notification-app').checked = notificationSettings.app || true;
    document.getElementById('notification-reminder').checked = notificationSettings.reminder || true;
}

// 保存通知设置
async function saveNotificationSettings() {
    const notificationSettings = {
        email: document.getElementById('notification-email').checked,
        app: document.getElementById('notification-app').checked,
        reminder: document.getElementById('notification-reminder').checked
    };
    
    try {
        const result = await api.updateUserSettings({ notifications: notificationSettings });
        if (result.success) {
            localStorage.setItem('notificationSettings', JSON.stringify(notificationSettings));
            showMessage('通知设置保存成功', 'success');
        } else {
            showMessage('通知设置保存失败', 'error');
        }
    } catch (error) {
        showMessage('保存通知设置时出现错误', 'error');
        console.error('保存通知设置错误:', error);
    }
}

// 退出登录
function logout() {
    if (confirm('确定要退出登录吗？')) {
        // 清除登录状态
        localStorage.removeItem('isLoggedIn');
        showMessage('已退出登录', 'success');
        
        // 跳转到首页
        setTimeout(() => {
            navigateTo('index.html');
        }, 1000);
    }
}

// 删除账户
function deleteAccount() {
    if (confirm('确定要删除账户吗？此操作不可恢复！')) {
        if (confirm('再次确认要删除账户吗？所有数据将被清除！')) {
            // 清除所有本地存储数据
            localStorage.clear();
            showMessage('账户已删除', 'success');
            
            // 跳转到首页
            setTimeout(() => {
                navigateTo('index.html');
            }, 1000);
        }
    }
}

// 获取设备信息
function getDeviceInfo() {
    let deviceInfo = '';
    
    // 获取操作系统信息
    const userAgent = navigator.userAgent;
    if (userAgent.includes('Windows')) {
        deviceInfo += 'Windows';
        if (userAgent.includes('Windows NT 10.0')) deviceInfo += ' 10';
        else if (userAgent.includes('Windows NT 6.3')) deviceInfo += ' 8.1';
        else if (userAgent.includes('Windows NT 6.2')) deviceInfo += ' 8';
        else if (userAgent.includes('Windows NT 6.1')) deviceInfo += ' 7';
    } else if (userAgent.includes('Macintosh')) {
        deviceInfo += 'macOS';
    } else if (userAgent.includes('Linux')) {
        deviceInfo += 'Linux';
    } else if (userAgent.includes('Android')) {
        deviceInfo += 'Android';
    } else if (userAgent.includes('iPhone') || userAgent.includes('iPad')) {
        deviceInfo += 'iOS';
    }
    
    deviceInfo += ', ';
    
    // 获取浏览器信息
    if (userAgent.includes('Chrome')) {
        deviceInfo += 'Chrome';
    } else if (userAgent.includes('Firefox')) {
        deviceInfo += 'Firefox';
    } else if (userAgent.includes('Safari')) {
        deviceInfo += 'Safari';
    } else if (userAgent.includes('Edge')) {
        deviceInfo += 'Edge';
    } else if (userAgent.includes('Opera') || userAgent.includes('OPR')) {
        deviceInfo += 'Opera';
    } else {
        deviceInfo += 'Unknown Browser';
    }
    
    return deviceInfo;
}

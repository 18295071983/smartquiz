// 主题设置页面初始化
function initThemePage() {
    console.log('主题设置页面初始化');
    
    // 绑定事件
    bindThemeEvents();
    
    // 加载主题列表
    loadThemes();
    
    // 加载主题设置
    loadThemeSettings();
    
    // 初始化主题预览
    initThemePreviews();
}

// 绑定事件
function bindThemeEvents() {
    // 主题卡片点击事件
    document.querySelectorAll('.theme-card').forEach(card => {
        card.addEventListener('click', function() {
            // 移除所有卡片的active类
            document.querySelectorAll('.theme-card').forEach(c => c.classList.remove('active'));
            // 添加当前卡片的active类
            this.classList.add('active');
        });
    });
    
    // 字体大小滑块事件
    document.getElementById('font-size').addEventListener('input', function() {
        const fontSize = this.value;
        document.getElementById('font-size-value').textContent = `${fontSize}px`;
    });
    
    // 主题模式单选按钮事件
    document.querySelectorAll('input[name="theme-mode"]').forEach(radio => {
        radio.addEventListener('change', function() {
            const mode = this.value;
            if (mode === 'schedule') {
                document.querySelector('.schedule-settings').style.display = 'block';
            } else {
                document.querySelector('.schedule-settings').style.display = 'none';
            }
        });
    });
    
    // 保存主题设置按钮
    document.getElementById('save-theme-btn').addEventListener('click', function() {
        saveThemeSettings();
    });
    
    // 恢复默认按钮
    document.getElementById('reset-theme-btn').addEventListener('click', function() {
        resetThemeSettings();
    });
}

// 加载主题列表
async function loadThemes() {
    try {
        const result = await api.getThemes();
        if (result.success) {
            const themes = result.data;
            const themeContainer = document.querySelector('.themes-container');
            
            // 清空现有主题卡片
            themeContainer.innerHTML = '';
            
            // 添加主题卡片
            themes.forEach(theme => {
                const themeCard = document.createElement('div');
                themeCard.className = 'theme-card';
                themeCard.dataset.theme = theme.id;
                
                themeCard.innerHTML = `
                    <div class="theme-preview" data-theme="${theme.id}"></div>
                    <h3>${theme.name}</h3>
                `;
                
                themeContainer.appendChild(themeCard);
            });
            
            // 重新绑定主题卡片点击事件
            bindThemeCardEvents();
        }
    } catch (error) {
        console.error('加载主题列表错误:', error);
    }
}

// 绑定主题卡片事件
function bindThemeCardEvents() {
    document.querySelectorAll('.theme-card').forEach(card => {
        card.addEventListener('click', function() {
            // 移除所有卡片的active类
            document.querySelectorAll('.theme-card').forEach(c => c.classList.remove('active'));
            // 添加当前卡片的active类
            this.classList.add('active');
        });
    });
}

// 初始化主题预览
function initThemePreviews() {
    // 这里可以添加主题预览的交互效果
    console.log('主题预览初始化');
}

// 加载主题设置
function loadThemeSettings() {
    const themeSettings = JSON.parse(localStorage.getItem('themeSettings') || '{}');
    
    // 加载主题
    const theme = themeSettings.theme || 'light';
    document.querySelectorAll('.theme-card').forEach(card => {
        if (card.dataset.theme === theme) {
            card.classList.add('active');
        } else {
            card.classList.remove('active');
        }
    });
    
    // 加载字体设置
    document.getElementById('font-family').value = themeSettings.fontFamily || 'system';
    document.getElementById('font-size').value = themeSettings.fontSize || 16;
    document.getElementById('font-size-value').textContent = `${themeSettings.fontSize || 16}px`;
    
    // 加载界面设置
    document.getElementById('enable-animations').checked = themeSettings.enableAnimations !== false;
    document.getElementById('enable-shadows').checked = themeSettings.enableShadows !== false;
    document.getElementById('enable-rounded-corners').checked = themeSettings.enableRoundedCorners !== false;
    
    // 加载主题模式
    document.querySelector(`input[name="theme-mode"][value="${themeSettings.themeMode || 'manual'}"]`).checked = true;
    
    // 加载定时设置
    if (themeSettings.themeMode === 'schedule') {
        document.querySelector('.schedule-settings').style.display = 'block';
        document.getElementById('light-time').value = themeSettings.lightTime || '06:00';
        document.getElementById('dark-time').value = themeSettings.darkTime || '18:00';
    }
}

// 保存主题设置
async function saveThemeSettings() {
    const themeSettings = {
        theme: document.querySelector('.theme-card.active').dataset.theme,
        fontFamily: document.getElementById('font-family').value,
        fontSize: parseInt(document.getElementById('font-size').value),
        enableAnimations: document.getElementById('enable-animations').checked,
        enableShadows: document.getElementById('enable-shadows').checked,
        enableRoundedCorners: document.getElementById('enable-rounded-corners').checked,
        themeMode: document.querySelector('input[name="theme-mode"]:checked').value,
        lightTime: document.getElementById('light-time').value,
        darkTime: document.getElementById('dark-time').value
    };
    
    try {
        const result = await api.updateUserSettings({ theme: themeSettings.theme });
        if (result.success) {
            localStorage.setItem('themeSettings', JSON.stringify(themeSettings));
            
            // 应用主题
            applyTheme(themeSettings.theme);
            
            // 应用字体设置
            applyFontSettings(themeSettings);
            
            showMessage('主题设置保存成功', 'success');
        } else {
            showMessage('主题设置保存失败', 'error');
        }
    } catch (error) {
        showMessage('保存主题设置时出现错误', 'error');
        console.error('保存主题设置错误:', error);
    }
}

// 应用主题
function applyTheme(theme) {
    // 移除所有主题类
    document.body.classList.remove('dark-theme', 'blue-theme', 'green-theme');
    
    // 添加当前主题类
    if (theme === 'dark') {
        document.body.classList.add('dark-theme');
    } else if (theme === 'blue') {
        document.body.classList.add('blue-theme');
    } else if (theme === 'green') {
        document.body.classList.add('green-theme');
    }
    
    // 保存主题到localStorage
    localStorage.setItem('theme', theme);
}

// 应用字体设置
function applyFontSettings(settings) {
    let fontFamily;
    switch(settings.fontFamily) {
        case 'sans-serif':
            fontFamily = 'Arial, sans-serif';
            break;
        case 'serif':
            fontFamily = 'Georgia, serif';
            break;
        case 'monospace':
            fontFamily = 'Courier New, monospace';
            break;
        default:
            fontFamily = 'system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif';
    }
    
    document.body.style.fontFamily = fontFamily;
    document.body.style.fontSize = `${settings.fontSize}px`;
    
    // 应用界面设置
    if (settings.enableAnimations) {
        document.body.classList.remove('no-animations');
    } else {
        document.body.classList.add('no-animations');
    }
    
    if (settings.enableShadows) {
        document.body.classList.remove('no-shadows');
    } else {
        document.body.classList.add('no-shadows');
    }
    
    if (settings.enableRoundedCorners) {
        document.body.classList.remove('no-rounded-corners');
    } else {
        document.body.classList.add('no-rounded-corners');
    }
}

// 恢复默认主题设置
async function resetThemeSettings() {
    const defaultSettings = {
        theme: 'light',
        fontFamily: 'system',
        fontSize: 16,
        enableAnimations: true,
        enableShadows: true,
        enableRoundedCorners: true,
        themeMode: 'manual',
        lightTime: '06:00',
        darkTime: '18:00'
    };
    
    try {
        const result = await api.updateUserSettings({ theme: defaultSettings.theme });
        if (result.success) {
            localStorage.setItem('themeSettings', JSON.stringify(defaultSettings));
            loadThemeSettings();
            applyTheme(defaultSettings.theme);
            applyFontSettings(defaultSettings);
            
            showMessage('主题设置已恢复默认', 'success');
        } else {
            showMessage('恢复默认设置失败', 'error');
        }
    } catch (error) {
        showMessage('恢复默认设置时出现错误', 'error');
        console.error('恢复默认设置错误:', error);
    }
}

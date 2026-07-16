// 语言设置页面初始化
function initLanguagePage() {
    console.log('语言设置页面初始化');
    
    // 绑定事件
    bindLanguageEvents();
    
    // 加载语言列表
    loadLanguages();
    
    // 加载语言设置
    loadLanguageSettings();
}

// 绑定事件
function bindLanguageEvents() {
    // 语言卡片点击事件
    document.querySelectorAll('.language-card').forEach(card => {
        card.addEventListener('click', function() {
            // 移除所有卡片的active类
            document.querySelectorAll('.language-card').forEach(c => c.classList.remove('active'));
            // 添加当前卡片的active类
            this.classList.add('active');
            // 更新界面语言选择
            const language = this.dataset.language;
            document.getElementById('interface-language').value = language;
        });
    });
    
    // 保存语言设置按钮
    document.getElementById('save-language-btn').addEventListener('click', function() {
        saveLanguageSettings();
    });
    
    // 恢复默认按钮
    document.getElementById('reset-language-btn').addEventListener('click', function() {
        resetLanguageSettings();
    });
}

// 加载语言列表
async function loadLanguages() {
    try {
        const result = await api.getLanguages();
        if (result.success) {
            const languages = result.data;
            const languageContainer = document.querySelector('.languages-container');
            
            // 清空现有语言卡片
            languageContainer.innerHTML = '';
            
            // 添加语言卡片
            languages.forEach(language => {
                const languageCard = document.createElement('div');
                languageCard.className = 'language-card';
                languageCard.dataset.language = language.id;
                
                languageCard.innerHTML = `
                    <div class="language-icon"></div>
                    <h3>${language.name}</h3>
                    <p>${language.id}</p>
                `;
                
                languageContainer.appendChild(languageCard);
            });
            
            // 重新绑定语言卡片点击事件
            bindLanguageCardEvents();
        }
    } catch (error) {
        console.error('加载语言列表错误:', error);
    }
}

// 绑定语言卡片事件
function bindLanguageCardEvents() {
    document.querySelectorAll('.language-card').forEach(card => {
        card.addEventListener('click', function() {
            // 移除所有卡片的active类
            document.querySelectorAll('.language-card').forEach(c => c.classList.remove('active'));
            // 添加当前卡片的active类
            this.classList.add('active');
            // 更新界面语言选择
            const language = this.dataset.language;
            document.getElementById('interface-language').value = language;
        });
    });
}

// 加载语言设置
function loadLanguageSettings() {
    const languageSettings = JSON.parse(localStorage.getItem('languageSettings') || '{}');
    
    // 加载界面语言
    const interfaceLanguage = languageSettings.interfaceLanguage || 'zh-CN';
    document.getElementById('interface-language').value = interfaceLanguage;
    
    // 更新语言卡片的active状态
    document.querySelectorAll('.language-card').forEach(card => {
        if (card.dataset.language === interfaceLanguage) {
            card.classList.add('active');
        } else {
            card.classList.remove('active');
        }
    });
    
    // 加载输入语言
    document.getElementById('input-language').value = languageSettings.inputLanguage || 'auto';
    
    // 加载翻译设置
    document.getElementById('enable-translation').checked = languageSettings.enableTranslation || false;
    document.getElementById('enable-voice').checked = languageSettings.enableVoice || false;
    document.getElementById('translation-language').value = languageSettings.translationLanguage || 'zh-CN';
}

// 保存语言设置
async function saveLanguageSettings() {
    const languageSettings = {
        interfaceLanguage: document.getElementById('interface-language').value,
        inputLanguage: document.getElementById('input-language').value,
        enableTranslation: document.getElementById('enable-translation').checked,
        enableVoice: document.getElementById('enable-voice').checked,
        translationLanguage: document.getElementById('translation-language').value
    };
    
    try {
        const result = await api.updateUserSettings({ language: languageSettings.interfaceLanguage });
        if (result.success) {
            localStorage.setItem('languageSettings', JSON.stringify(languageSettings));
            
            // 应用语言设置
            applyLanguageSettings(languageSettings);
            
            showMessage('语言设置保存成功', 'success');
        } else {
            showMessage('语言设置保存失败', 'error');
        }
    } catch (error) {
        showMessage('保存语言设置时出现错误', 'error');
        console.error('保存语言设置错误:', error);
    }
}

// 应用语言设置
function applyLanguageSettings(settings) {
    // 设置页面语言
    document.documentElement.lang = settings.interfaceLanguage;
    
    // 这里可以添加更多语言应用逻辑
    console.log('语言设置已应用:', settings);
}

// 恢复默认语言设置
async function resetLanguageSettings() {
    const defaultSettings = {
        interfaceLanguage: 'zh-CN',
        inputLanguage: 'auto',
        enableTranslation: false,
        enableVoice: false,
        translationLanguage: 'zh-CN'
    };
    
    try {
        const result = await api.updateUserSettings({ language: defaultSettings.interfaceLanguage });
        if (result.success) {
            localStorage.setItem('languageSettings', JSON.stringify(defaultSettings));
            loadLanguageSettings();
            applyLanguageSettings(defaultSettings);
            
            showMessage('语言设置已恢复默认', 'success');
        } else {
            showMessage('恢复默认设置失败', 'error');
        }
    } catch (error) {
        showMessage('恢复默认设置时出现错误', 'error');
        console.error('恢复默认设置错误:', error);
    }
}

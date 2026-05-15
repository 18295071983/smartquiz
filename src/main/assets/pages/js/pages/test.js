// 测试按钮页面初始化
function initTestPage() {
    console.log('测试按钮页面初始化');
    
    // 绑定测试按钮事件
    bindTestEvents();
}

// 绑定测试按钮事件
function bindTestEvents() {
    // 功能测试
    document.getElementById('test-localStorage').addEventListener('click', testLocalStorage);
    document.getElementById('test-sessionStorage').addEventListener('click', testSessionStorage);
    document.getElementById('test-api').addEventListener('click', testAPI);
    document.getElementById('test-notification').addEventListener('click', testNotification);
    
    // UI测试
    document.getElementById('test-theme').addEventListener('click', testTheme);
    document.getElementById('test-animation').addEventListener('click', testAnimation);
    document.getElementById('test-responsive').addEventListener('click', testResponsive);
    document.getElementById('test-toast').addEventListener('click', testToast);
    
    // 性能测试
    document.getElementById('test-memory').addEventListener('click', testMemory);
    document.getElementById('test-speed').addEventListener('click', testSpeed);
    document.getElementById('test-load').addEventListener('click', testLoad);
    
    // 错误测试
    document.getElementById('test-error').addEventListener('click', testError);
    document.getElementById('test-exception').addEventListener('click', testException);
    document.getElementById('test-network-error').addEventListener('click', testNetworkError);
}

// 显示测试结果
function showTestResult(testName, result, details) {
    const resultContainer = document.getElementById('test-result-container');
    
    // 清除无结果提示
    if (resultContainer.querySelector('.no-results')) {
        resultContainer.innerHTML = '';
    }
    
    // 创建结果元素
    const resultElement = document.createElement('div');
    resultElement.className = `test-result ${result}`;
    
    resultElement.innerHTML = `
        <div class="result-header">
            <h4>${testName}</h4>
            <span class="result-status ${result}">${result === 'success' ? '成功' : '失败'}</span>
        </div>
        <div class="result-details">${details}</div>
    `;
    
    // 添加到结果容器
    resultContainer.appendChild(resultElement);
    
    // 滚动到最新结果
    resultElement.scrollIntoView({ behavior: 'smooth', block: 'end' });
}

// 测试LocalStorage
function testLocalStorage() {
    try {
        // 测试存储
        localStorage.setItem('test-key', 'test-value');
        const value = localStorage.getItem('test-key');
        localStorage.removeItem('test-key');
        
        if (value === 'test-value') {
            showTestResult('LocalStorage测试', 'success', 'LocalStorage存储和读取正常');
        } else {
            showTestResult('LocalStorage测试', 'error', 'LocalStorage读取失败');
        }
    } catch (error) {
        showTestResult('LocalStorage测试', 'error', `LocalStorage测试失败: ${error.message}`);
    }
}

// 测试SessionStorage
function testSessionStorage() {
    try {
        // 测试存储
        sessionStorage.setItem('test-key', 'test-value');
        const value = sessionStorage.getItem('test-key');
        sessionStorage.removeItem('test-key');
        
        if (value === 'test-value') {
            showTestResult('SessionStorage测试', 'success', 'SessionStorage存储和读取正常');
        } else {
            showTestResult('SessionStorage测试', 'error', 'SessionStorage读取失败');
        }
    } catch (error) {
        showTestResult('SessionStorage测试', 'error', `SessionStorage测试失败: ${error.message}`);
    }
}

// 测试API
function testAPI() {
    try {
        // 测试模拟API
        const questions = getQuestions();
        if (questions && questions.length > 0) {
            showTestResult('API测试', 'success', `API调用成功，返回 ${questions.length} 条数据`);
        } else {
            showTestResult('API测试', 'error', 'API返回数据为空');
        }
    } catch (error) {
        showTestResult('API测试', 'error', `API测试失败: ${error.message}`);
    }
}

// 测试通知
function testNotification() {
    if ('Notification' in window) {
        if (Notification.permission === 'granted') {
            // 发送测试通知
            new Notification('测试通知', {
                body: '这是一个测试通知',
                icon: 'https://via.placeholder.com/100'
            });
            showTestResult('通知测试', 'success', '通知发送成功');
        } else if (Notification.permission !== 'denied') {
            // 请求通知权限
            Notification.requestPermission().then(permission => {
                if (permission === 'granted') {
                    new Notification('测试通知', {
                        body: '这是一个测试通知',
                        icon: 'https://via.placeholder.com/100'
                    });
                    showTestResult('通知测试', 'success', '通知权限获取成功并发送通知');
                } else {
                    showTestResult('通知测试', 'error', '通知权限被拒绝');
                }
            });
        } else {
            showTestResult('通知测试', 'error', '通知权限已被拒绝');
        }
    } else {
        showTestResult('通知测试', 'error', '浏览器不支持通知');
    }
}

// 测试主题切换
function testTheme() {
    try {
        // 切换主题
        document.body.classList.toggle('dark-theme');
        const isDark = document.body.classList.contains('dark-theme');
        showTestResult('主题切换测试', 'success', `主题已切换到${isDark ? '深色' : '浅色'}模式`);
    } catch (error) {
        showTestResult('主题切换测试', 'error', `主题切换失败: ${error.message}`);
    }
}

// 测试动画效果
function testAnimation() {
    try {
        // 创建动画元素
        const animationElement = document.createElement('div');
        animationElement.className = 'test-animation';
        animationElement.innerHTML = '<i class="fas fa-spinner fa-spin"></i> 动画测试';
        document.body.appendChild(animationElement);
        
        // 显示结果
        showTestResult('动画效果测试', 'success', '动画效果正常显示');
        
        // 3秒后移除动画元素
        setTimeout(() => {
            document.body.removeChild(animationElement);
        }, 3000);
    } catch (error) {
        showTestResult('动画效果测试', 'error', `动画测试失败: ${error.message}`);
    }
}

// 测试响应式布局
function testResponsive() {
    try {
        const viewportWidth = window.innerWidth;
        let deviceType = '桌面';
        if (viewportWidth <= 768) {
            deviceType = '移动设备';
        } else if (viewportWidth <= 1024) {
            deviceType = '平板';
        }
        
        showTestResult('响应式布局测试', 'success', `当前视口宽度: ${viewportWidth}px，设备类型: ${deviceType}`);
    } catch (error) {
        showTestResult('响应式布局测试', 'error', `响应式测试失败: ${error.message}`);
    }
}

// 测试消息提示
function testToast() {
    try {
        showMessage('这是一条测试消息', 'success');
        showTestResult('消息提示测试', 'success', '消息提示正常显示');
    } catch (error) {
        showTestResult('消息提示测试', 'error', `消息提示测试失败: ${error.message}`);
    }
}

// 测试内存使用
function testMemory() {
    try {
        // 测试内存使用
        const startMemory = performance.memory ? performance.memory.usedJSHeapSize : 0;
        
        // 创建一些对象来测试内存
        const testArray = [];
        for (let i = 0; i < 100000; i++) {
            testArray.push({ id: i, name: `test-${i}` });
        }
        
        const endMemory = performance.memory ? performance.memory.usedJSHeapSize : 0;
        const memoryUsed = (endMemory - startMemory) / 1024 / 1024;
        
        showTestResult('内存使用测试', 'success', `内存使用: ${memoryUsed.toFixed(2)} MB`);
    } catch (error) {
        showTestResult('内存使用测试', 'error', `内存测试失败: ${error.message}`);
    }
}

// 测试执行速度
function testSpeed() {
    try {
        const startTime = performance.now();
        
        // 执行一些计算密集型操作
        let sum = 0;
        for (let i = 0; i < 10000000; i++) {
            sum += i;
        }
        
        const endTime = performance.now();
        const executionTime = endTime - startTime;
        
        showTestResult('执行速度测试', 'success', `执行时间: ${executionTime.toFixed(2)} 毫秒`);
    } catch (error) {
        showTestResult('执行速度测试', 'error', `执行速度测试失败: ${error.message}`);
    }
}

// 测试加载性能
function testLoad() {
    try {
        const navigationTiming = performance.timing;
        const loadTime = navigationTiming.loadEventEnd - navigationTiming.navigationStart;
        
        showTestResult('加载性能测试', 'success', `页面加载时间: ${loadTime.toFixed(2)} 毫秒`);
    } catch (error) {
        showTestResult('加载性能测试', 'error', `加载性能测试失败: ${error.message}`);
    }
}

// 测试错误处理
function testError() {
    try {
        // 故意触发一个错误
        const undefinedVariable = undefined;
        undefinedVariable.toString();
    } catch (error) {
        showTestResult('错误处理测试', 'success', `错误捕获成功: ${error.message}`);
    }
}

// 测试异常捕获
function testException() {
    try {
        // 抛出一个自定义异常
        throw new Error('测试异常');
    } catch (error) {
        showTestResult('异常捕获测试', 'success', `异常捕获成功: ${error.message}`);
    }
}

// 测试网络错误
function testNetworkError() {
    fetch('https://nonexistent-domain-12345.com/test')
        .then(response => response.json())
        .catch(error => {
            showTestResult('网络错误测试', 'success', `网络错误捕获成功: ${error.message}`);
        });
}
// 环境检测页面初始化
function initEnvironmentPage() {
    console.log('环境检测页面初始化');
    
    // 绑定事件
    bindEnvironmentEvents();
    
    // 开始检测环境
    detectEnvironment();
}

// 绑定事件
function bindEnvironmentEvents() {
    // 刷新检测按钮
    document.getElementById('refresh-env-btn').addEventListener('click', function() {
        detectEnvironment();
    });
    
    // 导出环境信息按钮
    document.getElementById('export-env-btn').addEventListener('click', function() {
        exportEnvironmentInfo();
    });
}

// 检测环境
function detectEnvironment() {
    showMessage('正在检测环境...', 'info');
    
    // 检测浏览器信息
    detectBrowserInfo();
    
    // 检测设备信息
    detectDeviceInfo();
    
    // 检测网络信息
    detectNetworkInfo();
    
    // 检测功能支持
    detectFeatures();
    
    showMessage('环境检测完成', 'success');
}

// 检测浏览器信息
function detectBrowserInfo() {
    const userAgent = navigator.userAgent;
    let browserName = 'Unknown';
    let browserVersion = 'Unknown';
    
    // 检测浏览器名称和版本
    if (userAgent.includes('Chrome')) {
        browserName = 'Chrome';
        browserVersion = userAgent.match(/Chrome\/([0-9.]+)/)[1];
    } else if (userAgent.includes('Firefox')) {
        browserName = 'Firefox';
        browserVersion = userAgent.match(/Firefox\/([0-9.]+)/)[1];
    } else if (userAgent.includes('Safari')) {
        browserName = 'Safari';
        browserVersion = userAgent.match(/Version\/([0-9.]+)/)[1];
    } else if (userAgent.includes('Edge')) {
        browserName = 'Edge';
        browserVersion = userAgent.match(/Edge\/([0-9.]+)/)[1];
    } else if (userAgent.includes('Opera') || userAgent.includes('OPR')) {
        browserName = 'Opera';
        browserVersion = userAgent.match(/(?:Opera|OPR)\/([0-9.]+)/)[1];
    } else if (userAgent.includes('MSIE') || userAgent.includes('Trident')) {
        browserName = 'Internet Explorer';
        browserVersion = userAgent.match(/(?:MSIE|rv:)\s*([0-9.]+)/)[1];
    }
    
    document.getElementById('browser-name').textContent = browserName;
    document.getElementById('browser-version').textContent = browserVersion;
    document.getElementById('user-agent').textContent = userAgent;
}

// 检测设备信息
function detectDeviceInfo() {
    const userAgent = navigator.userAgent;
    let os = 'Unknown';
    let deviceType = 'Unknown';
    
    // 检测操作系统
    if (userAgent.includes('Windows')) {
        os = 'Windows';
        if (userAgent.includes('Windows NT 10.0')) os += ' 10';
        else if (userAgent.includes('Windows NT 6.3')) os += ' 8.1';
        else if (userAgent.includes('Windows NT 6.2')) os += ' 8';
        else if (userAgent.includes('Windows NT 6.1')) os += ' 7';
        else if (userAgent.includes('Windows NT 6.0')) os += ' Vista';
        else if (userAgent.includes('Windows NT 5.1')) os += ' XP';
    } else if (userAgent.includes('Macintosh')) {
        os = 'macOS';
    } else if (userAgent.includes('Linux')) {
        os = 'Linux';
    } else if (userAgent.includes('Android')) {
        os = 'Android';
        deviceType = 'Mobile';
    } else if (userAgent.includes('iPhone') || userAgent.includes('iPad')) {
        os = 'iOS';
        deviceType = 'Mobile';
    }
    
    // 检测设备类型
    if (deviceType === 'Unknown') {
        if (window.innerWidth <= 768) {
            deviceType = 'Mobile';
        } else if (window.innerWidth <= 1024) {
            deviceType = 'Tablet';
        } else {
            deviceType = 'Desktop';
        }
    }
    
    // 屏幕分辨率
    const screenResolution = `${screen.width}x${screen.height}`;
    
    // 视口大小
    const viewportSize = `${window.innerWidth}x${window.innerHeight}`;
    
    document.getElementById('os').textContent = os;
    document.getElementById('device-type').textContent = deviceType;
    document.getElementById('screen-resolution').textContent = screenResolution;
    document.getElementById('viewport-size').textContent = viewportSize;
}

// 检测网络信息
function detectNetworkInfo() {
    // 网络状态
    const online = navigator.onLine;
    document.getElementById('network-status').textContent = online ? '在线' : '离线';
    
    // 连接类型
    let connectionType = 'Unknown';
    if (navigator.connection) {
        connectionType = navigator.connection.type || 'Unknown';
        switch (connectionType) {
            case 'wifi':
                connectionType = 'WiFi';
                break;
            case 'cellular':
                connectionType = '蜂窝网络';
                break;
            case 'ethernet':
                connectionType = '以太网';
                break;
            case 'bluetooth':
                connectionType = '蓝牙';
                break;
            case 'none':
                connectionType = '无网络';
                break;
        }
    }
    document.getElementById('connection-type').textContent = connectionType;
    
    // 模拟下载速度测试
    testDownloadSpeed();
}

// 测试下载速度
function testDownloadSpeed() {
    const startTime = new Date().getTime();
    const fileSize = 1024 * 1024; // 1MB
    const testImage = new Image();
    
    testImage.onload = function() {
        const endTime = new Date().getTime();
        const duration = (endTime - startTime) / 1000;
        const speed = (fileSize / duration / 1024).toFixed(2); // KB/s
        document.getElementById('download-speed').textContent = `${speed} KB/s`;
    };
    
    testImage.onerror = function() {
        document.getElementById('download-speed').textContent = '无法测试';
    };
    
    // 使用随机参数避免缓存
    testImage.src = `https://via.placeholder.com/1x1?${Math.random()}`;
}

// 检测功能支持
function detectFeatures() {
    // LocalStorage
    let localStorageSupport = '不支持';
    try {
        localStorage.setItem('test', 'test');
        localStorage.removeItem('test');
        localStorageSupport = '支持';
    } catch (e) {
        localStorageSupport = '不支持';
    }
    document.getElementById('local-storage').textContent = localStorageSupport;
    
    // SessionStorage
    let sessionStorageSupport = '不支持';
    try {
        sessionStorage.setItem('test', 'test');
        sessionStorage.removeItem('test');
        sessionStorageSupport = '支持';
    } catch (e) {
        sessionStorageSupport = '不支持';
    }
    document.getElementById('session-storage').textContent = sessionStorageSupport;
    
    // IndexedDB
    const indexedDBSupport = 'indexedDB' in window ? '支持' : '不支持';
    document.getElementById('indexed-db').textContent = indexedDBSupport;
    
    // Canvas
    const canvasSupport = 'HTMLCanvasElement' in window ? '支持' : '不支持';
    document.getElementById('canvas').textContent = canvasSupport;
    
    // WebGL
    let webglSupport = '不支持';
    try {
        const canvas = document.createElement('canvas');
        webglSupport = !!(window.WebGLRenderingContext && (canvas.getContext('webgl') || canvas.getContext('experimental-webgl')));
        webglSupport = webglSupport ? '支持' : '不支持';
    } catch (e) {
        webglSupport = '不支持';
    }
    document.getElementById('webgl').textContent = webglSupport;
    
    // Audio
    const audioSupport = 'Audio' in window ? '支持' : '不支持';
    document.getElementById('audio').textContent = audioSupport;
    
    // Video
    const videoSupport = 'HTMLVideoElement' in window ? '支持' : '不支持';
    document.getElementById('video').textContent = videoSupport;
}

// 导出环境信息
function exportEnvironmentInfo() {
    const environmentInfo = {
        browser: {
            name: document.getElementById('browser-name').textContent,
            version: document.getElementById('browser-version').textContent,
            userAgent: document.getElementById('user-agent').textContent
        },
        device: {
            os: document.getElementById('os').textContent,
            type: document.getElementById('device-type').textContent,
            screenResolution: document.getElementById('screen-resolution').textContent,
            viewportSize: document.getElementById('viewport-size').textContent
        },
        network: {
            status: document.getElementById('network-status').textContent,
            type: document.getElementById('connection-type').textContent,
            downloadSpeed: document.getElementById('download-speed').textContent
        },
        features: {
            localStorage: document.getElementById('local-storage').textContent,
            sessionStorage: document.getElementById('session-storage').textContent,
            indexedDB: document.getElementById('indexed-db').textContent,
            canvas: document.getElementById('canvas').textContent,
            webgl: document.getElementById('webgl').textContent,
            audio: document.getElementById('audio').textContent,
            video: document.getElementById('video').textContent
        },
        timestamp: new Date().toISOString()
    };
    
    const jsonContent = JSON.stringify(environmentInfo, null, 2);
    const blob = new Blob([jsonContent], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    
    const a = document.createElement('a');
    a.href = url;
    a.download = `environment_info_${new Date().toISOString().slice(0, 10)}.json`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    
    URL.revokeObjectURL(url);
    showMessage('环境信息导出成功', 'success');
}
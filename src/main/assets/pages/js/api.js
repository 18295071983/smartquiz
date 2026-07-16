// 浏览器数据操作模块

// 存储键名常量
const STORAGE_KEYS = {
    QUESTIONS: 'smartquiz_questions',
    QUIZZES: 'smartquiz_quizzes',
    STUDY_PLANS: 'smartquiz_study_plans',
    WRONG_QUESTIONS: 'smartquiz_wrong_questions',
    OCR_HISTORY: 'smartquiz_ocr_history',
    CHAT_HISTORY: 'smartquiz_chat_history',
    IMPORT_HISTORY: 'smartquiz_import_history',
    EXPORT_HISTORY: 'smartquiz_export_history',
    NOTES: 'smartquiz_notes',
    BACKUP_HISTORY: 'smartquiz_backup_history',
    USER: 'smartquiz_user',
    THEMES: 'smartquiz_themes',
    LANGUAGES: 'smartquiz_languages'
};

// 初始化默认数据
function initDefaultData() {
    // 初始化题目数据
    if (!localStorage.getItem(STORAGE_KEYS.QUESTIONS)) {
        const defaultQuestions = [
            {
                id: 'q1',
                type: '选择题',
                difficulty: '中等',
                content: '以下哪种CSS选择器的优先级最高？',
                options: [
                    'A. 类选择器 (.class)',
                    'B. ID选择器 (#id)',
                    'C. 标签选择器 (tag)',
                    'D. 伪类选择器 (:hover)'
                ],
                answer: 'B',
                createdAt: '2026-03-15T10:00:00'
            },
            {
                id: 'q2',
                type: '判断题',
                difficulty: '简单',
                content: 'CSS中的flexbox布局可以实现响应式设计。',
                options: [],
                answer: '√',
                createdAt: '2026-03-14T14:30:00'
            },
            {
                id: 'q3',
                type: '填空题',
                difficulty: '困难',
                content: 'JavaScript中，______方法用于向数组末尾添加元素。',
                options: [],
                answer: 'push',
                createdAt: '2026-03-13T09:15:00'
            }
        ];
        localStorage.setItem(STORAGE_KEYS.QUESTIONS, JSON.stringify(defaultQuestions));
    }
    
    // 初始化测验数据
    if (!localStorage.getItem(STORAGE_KEYS.QUIZZES)) {
        const defaultQuizzes = [
            {
                id: 'quiz1',
                title: 'HTML基础测试',
                questions: ['q1', 'q2'],
                duration: 30,
                createdAt: '2026-03-15T08:00:00'
            },
            {
                id: 'quiz2',
                title: 'JavaScript基础测试',
                questions: ['q3'],
                duration: 20,
                createdAt: '2026-03-14T10:30:00'
            }
        ];
        localStorage.setItem(STORAGE_KEYS.QUIZZES, JSON.stringify(defaultQuizzes));
    }
    
    // 初始化学习计划数据
    if (!localStorage.getItem(STORAGE_KEYS.STUDY_PLANS)) {
        const defaultStudyPlans = [
            {
                id: 'plan1',
                title: '每日英语学习',
                status: 'active',
                startDate: '2026-03-15',
                endDate: '2026-04-15',
                dailyTime: 30,
                progress: 45,
                createdAt: '2026-03-15T07:00:00'
            },
            {
                id: 'plan2',
                title: '数学基础强化',
                status: 'completed',
                startDate: '2026-02-01',
                endDate: '2026-03-10',
                dailyTime: 60,
                progress: 100,
                createdAt: '2026-02-01T08:00:00'
            },
            {
                id: 'plan3',
                title: '编程技能提升',
                status: 'upcoming',
                startDate: '2026-04-20',
                endDate: '2026-06-20',
                dailyTime: 120,
                progress: 0,
                createdAt: '2026-03-10T10:00:00'
            }
        ];
        localStorage.setItem(STORAGE_KEYS.STUDY_PLANS, JSON.stringify(defaultStudyPlans));
    }
    
    // 初始化错题集
    if (!localStorage.getItem(STORAGE_KEYS.WRONG_QUESTIONS)) {
        const defaultWrongQuestions = [
            {
                id: 'wq1',
                questionId: 'q1',
                wrongAnswer: 'A',
                correctAnswer: 'B',
                reason: '对CSS选择器优先级规则理解不清晰',
                wrongTime: '2026-03-15T14:30:00'
            },
            {
                id: 'wq2',
                questionId: 'q2',
                wrongAnswer: '×',
                correctAnswer: '√',
                reason: '对flexbox布局的理解不够',
                wrongTime: '2026-03-14T10:20:00'
            },
            {
                id: 'wq3',
                questionId: 'q3',
                wrongAnswer: 'add',
                correctAnswer: 'push',
                reason: '对JavaScript数组方法记忆不牢',
                wrongTime: '2026-03-13T16:45:00'
            }
        ];
        localStorage.setItem(STORAGE_KEYS.WRONG_QUESTIONS, JSON.stringify(defaultWrongQuestions));
    }
    
    // 初始化OCR历史
    if (!localStorage.getItem(STORAGE_KEYS.OCR_HISTORY)) {
        const defaultOCRHistory = [
            {
                id: 'ocr1',
                text: '这是一段OCR识别的示例文本，用于测试识别功能。',
                status: 'success',
                createdAt: '2026-03-15T14:30:00'
            },
            {
                id: 'ocr2',
                text: 'Another example of OCR recognized text. This demonstrates the capability of the OCR system.',
                status: 'success',
                createdAt: '2026-03-14T10:20:00'
            }
        ];
        localStorage.setItem(STORAGE_KEYS.OCR_HISTORY, JSON.stringify(defaultOCRHistory));
    }
    
    // 初始化聊天历史
    if (!localStorage.getItem(STORAGE_KEYS.CHAT_HISTORY)) {
        const defaultChatHistory = [
            {
                id: 'chat1',
                role: 'ai',
                content: '你好！我是SmartQuiz的AI助手，有什么可以帮助你的吗？',
                timestamp: '2026-03-15T14:30:00'
            },
            {
                id: 'chat2',
                role: 'user',
                content: '如何提高英语听力水平？',
                timestamp: '2026-03-15T14:31:00'
            },
            {
                id: 'chat3',
                role: 'ai',
                content: '提高英语听力水平的方法有：\n1. 每天坚持听英语，至少30分钟\n2. 选择适合自己水平的材料\n3. 边听边做笔记\n4. 尝试跟读，模仿发音\n5. 使用英语播客、电影、新闻等多种资源',
                timestamp: '2026-03-15T14:32:00'
            }
        ];
        localStorage.setItem(STORAGE_KEYS.CHAT_HISTORY, JSON.stringify(defaultChatHistory));
    }
    
    // 初始化导入历史
    if (!localStorage.getItem(STORAGE_KEYS.IMPORT_HISTORY)) {
        const defaultImportHistory = [
            {
                id: 'import1',
                fileName: 'questions.csv',
                format: 'CSV',
                recordCount: 10,
                status: 'success',
                importedAt: '2026-03-15T14:30:00'
            },
            {
                id: 'import2',
                fileName: 'study_plans.xlsx',
                format: 'Excel',
                recordCount: 5,
                status: 'success',
                importedAt: '2026-03-14T10:20:00'
            }
        ];
        localStorage.setItem(STORAGE_KEYS.IMPORT_HISTORY, JSON.stringify(defaultImportHistory));
    }
    
    // 初始化导出历史
    if (!localStorage.getItem(STORAGE_KEYS.EXPORT_HISTORY)) {
        const defaultExportHistory = [
            {
                id: 'export1',
                fileName: 'questions_export_20260315.csv',
                format: 'CSV',
                recordCount: 15,
                exportedAt: '2026-03-15T15:30:00'
            },
            {
                id: 'export2',
                fileName: 'study_plans_export_20260314.json',
                format: 'JSON',
                recordCount: 8,
                exportedAt: '2026-03-14T11:20:00'
            }
        ];
        localStorage.setItem(STORAGE_KEYS.EXPORT_HISTORY, JSON.stringify(defaultExportHistory));
    }
    
    // 初始化笔记数据
    if (!localStorage.getItem(STORAGE_KEYS.NOTES)) {
        const defaultNotes = [
            {
                id: 'note1',
                title: 'HTML基础笔记',
                content: 'HTML是超文本标记语言，用于创建网页结构。',
                tags: ['HTML', '前端'],
                createdAt: '2026-03-15T14:30:00',
                updatedAt: '2026-03-15T14:30:00'
            },
            {
                id: 'note2',
                title: 'JavaScript数组方法',
                content: 'push() - 向数组末尾添加元素\npop() - 移除数组末尾元素\nshift() - 移除数组第一个元素\nunshift() - 向数组开头添加元素',
                tags: ['JavaScript', '前端'],
                createdAt: '2026-03-14T10:20:00',
                updatedAt: '2026-03-14T10:20:00'
            }
        ];
        localStorage.setItem(STORAGE_KEYS.NOTES, JSON.stringify(defaultNotes));
    }
    
    // 初始化备份历史
    if (!localStorage.getItem(STORAGE_KEYS.BACKUP_HISTORY)) {
        const defaultBackupHistory = [
            {
                id: 'backup1',
                fileName: 'backup_20260315.zip',
                size: '2.5MB',
                status: 'success',
                backedUpAt: '2026-03-15T14:30:00'
            },
            {
                id: 'backup2',
                fileName: 'backup_20260310.zip',
                size: '2.3MB',
                status: 'success',
                backedUpAt: '2026-03-10T10:20:00'
            }
        ];
        localStorage.setItem(STORAGE_KEYS.BACKUP_HISTORY, JSON.stringify(defaultBackupHistory));
    }
    
    // 初始化用户数据
    if (!localStorage.getItem(STORAGE_KEYS.USER)) {
        const defaultUser = {
            id: 'user1',
            username: 'admin',
            email: 'admin@example.com',
            fullName: '管理员',
            avatar: '',
            settings: {
                theme: 'light',
                language: 'zh-CN',
                notifications: true,
                autoBackup: true
            }
        };
        localStorage.setItem(STORAGE_KEYS.USER, JSON.stringify(defaultUser));
    }
    
    // 初始化主题数据
    if (!localStorage.getItem(STORAGE_KEYS.THEMES)) {
        const defaultThemes = [
            { id: 'light', name: '浅色主题' },
            { id: 'dark', name: '深色主题' },
            { id: 'blue', name: '蓝色主题' },
            { id: 'green', name: '绿色主题' }
        ];
        localStorage.setItem(STORAGE_KEYS.THEMES, JSON.stringify(defaultThemes));
    }
    
    // 初始化语言数据
    if (!localStorage.getItem(STORAGE_KEYS.LANGUAGES)) {
        const defaultLanguages = [
            { id: 'zh-CN', name: '简体中文' },
            { id: 'en-US', name: 'English' },
            { id: 'zh-TW', name: '繁體中文' }
        ];
        localStorage.setItem(STORAGE_KEYS.LANGUAGES, JSON.stringify(defaultLanguages));
    }
}

// 模拟延迟
function simulateDelay(ms = 500) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

// API模拟函数
const api = {
    // 获取题目列表
    getQuestions: async (params = {}) => {
        initDefaultData();
        await simulateDelay();
        let questions = JSON.parse(localStorage.getItem(STORAGE_KEYS.QUESTIONS) || '[]');
        
        // 模拟筛选
        if (params.type) {
            questions = questions.filter(q => q.type === params.type);
        }
        
        if (params.difficulty) {
            questions = questions.filter(q => q.difficulty === params.difficulty);
        }
        
        return {
            success: true,
            data: questions,
            total: questions.length
        };
    },
    
    // 获取题目详情
    getQuestion: async (id) => {
        initDefaultData();
        await simulateDelay();
        const questions = JSON.parse(localStorage.getItem(STORAGE_KEYS.QUESTIONS) || '[]');
        const question = questions.find(q => q.id === id);
        return {
            success: true,
            data: question
        };
    },
    
    // 添加题目
    addQuestion: async (question) => {
        initDefaultData();
        await simulateDelay();
        const questions = JSON.parse(localStorage.getItem(STORAGE_KEYS.QUESTIONS) || '[]');
        const newQuestion = {
            id: `q${questions.length + 1}`,
            ...question,
            createdAt: new Date().toISOString()
        };
        questions.push(newQuestion);
        localStorage.setItem(STORAGE_KEYS.QUESTIONS, JSON.stringify(questions));
        return {
            success: true,
            data: newQuestion
        };
    },
    
    // 更新题目
    updateQuestion: async (id, updates) => {
        initDefaultData();
        await simulateDelay();
        const questions = JSON.parse(localStorage.getItem(STORAGE_KEYS.QUESTIONS) || '[]');
        const index = questions.findIndex(q => q.id === id);
        if (index !== -1) {
            questions[index] = { ...questions[index], ...updates };
            localStorage.setItem(STORAGE_KEYS.QUESTIONS, JSON.stringify(questions));
            return {
                success: true,
                data: questions[index]
            };
        }
        return {
            success: false,
            message: '题目不存在'
        };
    },
    
    // 删除题目
    deleteQuestion: async (id) => {
        initDefaultData();
        await simulateDelay();
        let questions = JSON.parse(localStorage.getItem(STORAGE_KEYS.QUESTIONS) || '[]');
        questions = questions.filter(q => q.id !== id);
        localStorage.setItem(STORAGE_KEYS.QUESTIONS, JSON.stringify(questions));
        return {
            success: true
        };
    },
    
    // 获取测验列表
    getQuizzes: async () => {
        initDefaultData();
        await simulateDelay();
        const quizzes = JSON.parse(localStorage.getItem(STORAGE_KEYS.QUIZZES) || '[]');
        return {
            success: true,
            data: quizzes
        };
    },
    
    // 获取学习计划列表
    getStudyPlans: async () => {
        initDefaultData();
        await simulateDelay();
        const studyPlans = JSON.parse(localStorage.getItem(STORAGE_KEYS.STUDY_PLANS) || '[]');
        return {
            success: true,
            data: studyPlans
        };
    },
    
    // 添加学习计划
    addStudyPlan: async (plan) => {
        initDefaultData();
        await simulateDelay();
        const studyPlans = JSON.parse(localStorage.getItem(STORAGE_KEYS.STUDY_PLANS) || '[]');
        const newPlan = {
            id: `plan${studyPlans.length + 1}`,
            ...plan,
            progress: 0,
            createdAt: new Date().toISOString()
        };
        studyPlans.push(newPlan);
        localStorage.setItem(STORAGE_KEYS.STUDY_PLANS, JSON.stringify(studyPlans));
        return {
            success: true,
            data: newPlan
        };
    },
    
    // 更新学习计划
    updateStudyPlan: async (id, updates) => {
        initDefaultData();
        await simulateDelay();
        const studyPlans = JSON.parse(localStorage.getItem(STORAGE_KEYS.STUDY_PLANS) || '[]');
        const index = studyPlans.findIndex(p => p.id === id);
        if (index !== -1) {
            studyPlans[index] = { ...studyPlans[index], ...updates };
            localStorage.setItem(STORAGE_KEYS.STUDY_PLANS, JSON.stringify(studyPlans));
            return {
                success: true,
                data: studyPlans[index]
            };
        }
        return {
            success: false,
            message: '学习计划不存在'
        };
    },
    
    // 删除学习计划
    deleteStudyPlan: async (id) => {
        initDefaultData();
        await simulateDelay();
        let studyPlans = JSON.parse(localStorage.getItem(STORAGE_KEYS.STUDY_PLANS) || '[]');
        studyPlans = studyPlans.filter(p => p.id !== id);
        localStorage.setItem(STORAGE_KEYS.STUDY_PLANS, JSON.stringify(studyPlans));
        return {
            success: true
        };
    },
    
    // 获取错题集
    getWrongQuestions: async () => {
        initDefaultData();
        await simulateDelay();
        const wrongQuestions = JSON.parse(localStorage.getItem(STORAGE_KEYS.WRONG_QUESTIONS) || '[]');
        return {
            success: true,
            data: wrongQuestions
        };
    },
    
    // 移除错题
    removeWrongQuestion: async (id) => {
        initDefaultData();
        await simulateDelay();
        let wrongQuestions = JSON.parse(localStorage.getItem(STORAGE_KEYS.WRONG_QUESTIONS) || '[]');
        wrongQuestions = wrongQuestions.filter(wq => wq.id !== id);
        localStorage.setItem(STORAGE_KEYS.WRONG_QUESTIONS, JSON.stringify(wrongQuestions));
        return {
            success: true
        };
    },
    
    // 清空错题集
    clearWrongQuestions: async () => {
        initDefaultData();
        await simulateDelay();
        localStorage.setItem(STORAGE_KEYS.WRONG_QUESTIONS, JSON.stringify([]));
        return {
            success: true
        };
    },
    
    // 获取OCR历史
    getOCRHistory: async () => {
        initDefaultData();
        await simulateDelay();
        const ocrHistory = JSON.parse(localStorage.getItem(STORAGE_KEYS.OCR_HISTORY) || '[]');
        return {
            success: true,
            data: ocrHistory
        };
    },
    
    // 执行OCR识别
    performOCR: async (image) => {
        initDefaultData();
        await simulateDelay(1500);
        const ocrHistory = JSON.parse(localStorage.getItem(STORAGE_KEYS.OCR_HISTORY) || '[]');
        const newOCR = {
            id: `ocr${ocrHistory.length + 1}`,
            text: '这是OCR识别的结果示例。实际应用中，这里会返回真实的识别文本。',
            status: 'success',
            createdAt: new Date().toISOString()
        };
        ocrHistory.push(newOCR);
        localStorage.setItem(STORAGE_KEYS.OCR_HISTORY, JSON.stringify(ocrHistory));
        return {
            success: true,
            data: newOCR
        };
    },
    
    // 删除OCR历史
    deleteOCRHistory: async (id) => {
        initDefaultData();
        await simulateDelay();
        let ocrHistory = JSON.parse(localStorage.getItem(STORAGE_KEYS.OCR_HISTORY) || '[]');
        ocrHistory = ocrHistory.filter(ocr => ocr.id !== id);
        localStorage.setItem(STORAGE_KEYS.OCR_HISTORY, JSON.stringify(ocrHistory));
        return {
            success: true
        };
    },
    
    // 获取聊天历史
    getChatHistory: async () => {
        initDefaultData();
        await simulateDelay();
        const chatHistory = JSON.parse(localStorage.getItem(STORAGE_KEYS.CHAT_HISTORY) || '[]');
        return {
            success: true,
            data: chatHistory
        };
    },
    
    // 发送聊天消息
    sendChatMessage: async (message) => {
        initDefaultData();
        await simulateDelay(1000);
        const chatHistory = JSON.parse(localStorage.getItem(STORAGE_KEYS.CHAT_HISTORY) || '[]');
        const userMessage = {
            id: `chat${chatHistory.length + 1}`,
            role: 'user',
            content: message,
            timestamp: new Date().toISOString()
        };
        chatHistory.push(userMessage);
        
        // 模拟AI回复
        const aiMessage = {
            id: `chat${chatHistory.length + 1}`,
            role: 'ai',
            content: '这是AI的回复。实际应用中，这里会调用真实的AI API获取回复。',
            timestamp: new Date().toISOString()
        };
        chatHistory.push(aiMessage);
        localStorage.setItem(STORAGE_KEYS.CHAT_HISTORY, JSON.stringify(chatHistory));
        
        return {
            success: true,
            data: [userMessage, aiMessage]
        };
    },
    
    // 清空聊天历史
    clearChatHistory: async () => {
        initDefaultData();
        await simulateDelay();
        localStorage.setItem(STORAGE_KEYS.CHAT_HISTORY, JSON.stringify([]));
        return {
            success: true
        };
    },
    
    // 获取导入历史
    getImportHistory: async () => {
        initDefaultData();
        await simulateDelay();
        const importHistory = JSON.parse(localStorage.getItem(STORAGE_KEYS.IMPORT_HISTORY) || '[]');
        return {
            success: true,
            data: importHistory
        };
    },
    
    // 导入文件
    importFile: async (file) => {
        initDefaultData();
        await simulateDelay(2000);
        const importHistory = JSON.parse(localStorage.getItem(STORAGE_KEYS.IMPORT_HISTORY) || '[]');
        const newImport = {
            id: `import${importHistory.length + 1}`,
            fileName: file.name,
            format: file.type.includes('csv') ? 'CSV' : file.type.includes('excel') || file.type.includes('xlsx') ? 'Excel' : 'JSON',
            recordCount: Math.floor(Math.random() * 20) + 1,
            status: 'success',
            importedAt: new Date().toISOString()
        };
        importHistory.push(newImport);
        localStorage.setItem(STORAGE_KEYS.IMPORT_HISTORY, JSON.stringify(importHistory));
        return {
            success: true,
            data: newImport
        };
    },
    
    // 获取导出历史
    getExportHistory: async () => {
        initDefaultData();
        await simulateDelay();
        const exportHistory = JSON.parse(localStorage.getItem(STORAGE_KEYS.EXPORT_HISTORY) || '[]');
        return {
            success: true,
            data: exportHistory
        };
    },
    
    // 导出数据
    exportData: async (format, dataType) => {
        initDefaultData();
        await simulateDelay(1500);
        const exportHistory = JSON.parse(localStorage.getItem(STORAGE_KEYS.EXPORT_HISTORY) || '[]');
        const newExport = {
            id: `export${exportHistory.length + 1}`,
            fileName: `${dataType}_export_${new Date().toISOString().slice(0, 10).replace(/-/g, '')}.${format.toLowerCase()}`,
            format: format,
            recordCount: Math.floor(Math.random() * 20) + 1,
            exportedAt: new Date().toISOString()
        };
        exportHistory.push(newExport);
        localStorage.setItem(STORAGE_KEYS.EXPORT_HISTORY, JSON.stringify(exportHistory));
        return {
            success: true,
            data: newExport
        };
    },
    
    // 获取笔记列表
    getNotes: async () => {
        initDefaultData();
        await simulateDelay();
        const notes = JSON.parse(localStorage.getItem(STORAGE_KEYS.NOTES) || '[]');
        return {
            success: true,
            data: notes
        };
    },
    
    // 添加笔记
    addNote: async (note) => {
        initDefaultData();
        await simulateDelay();
        const notes = JSON.parse(localStorage.getItem(STORAGE_KEYS.NOTES) || '[]');
        const newNote = {
            id: `note${notes.length + 1}`,
            ...note,
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString()
        };
        notes.push(newNote);
        localStorage.setItem(STORAGE_KEYS.NOTES, JSON.stringify(notes));
        return {
            success: true,
            data: newNote
        };
    },
    
    // 更新笔记
    updateNote: async (id, updates) => {
        initDefaultData();
        await simulateDelay();
        const notes = JSON.parse(localStorage.getItem(STORAGE_KEYS.NOTES) || '[]');
        const index = notes.findIndex(n => n.id === id);
        if (index !== -1) {
            notes[index] = { ...notes[index], ...updates, updatedAt: new Date().toISOString() };
            localStorage.setItem(STORAGE_KEYS.NOTES, JSON.stringify(notes));
            return {
                success: true,
                data: notes[index]
            };
        }
        return {
            success: false,
            message: '笔记不存在'
        };
    },
    
    // 删除笔记
    deleteNote: async (id) => {
        initDefaultData();
        await simulateDelay();
        let notes = JSON.parse(localStorage.getItem(STORAGE_KEYS.NOTES) || '[]');
        notes = notes.filter(n => n.id !== id);
        localStorage.setItem(STORAGE_KEYS.NOTES, JSON.stringify(notes));
        return {
            success: true
        };
    },
    
    // 获取备份历史
    getBackupHistory: async () => {
        initDefaultData();
        await simulateDelay();
        const backupHistory = JSON.parse(localStorage.getItem(STORAGE_KEYS.BACKUP_HISTORY) || '[]');
        return {
            success: true,
            data: backupHistory
        };
    },
    
    // 创建备份
    createBackup: async () => {
        initDefaultData();
        await simulateDelay(2000);
        const backupHistory = JSON.parse(localStorage.getItem(STORAGE_KEYS.BACKUP_HISTORY) || '[]');
        const newBackup = {
            id: `backup${backupHistory.length + 1}`,
            fileName: `backup_${new Date().toISOString().slice(0, 10).replace(/-/g, '')}.zip`,
            size: `${(Math.random() * 3 + 1).toFixed(1)}MB`,
            status: 'success',
            backedUpAt: new Date().toISOString()
        };
        backupHistory.push(newBackup);
        localStorage.setItem(STORAGE_KEYS.BACKUP_HISTORY, JSON.stringify(backupHistory));
        return {
            success: true,
            data: newBackup
        };
    },
    
    // 恢复备份
    restoreBackup: async (backupId) => {
        initDefaultData();
        await simulateDelay(2000);
        return {
            success: true,
            message: '备份恢复成功'
        };
    },
    
    // 获取用户信息
    getUserInfo: async () => {
        initDefaultData();
        await simulateDelay();
        const user = JSON.parse(localStorage.getItem(STORAGE_KEYS.USER) || '{}');
        return {
            success: true,
            data: user
        };
    },
    
    // 更新用户信息
    updateUserInfo: async (updates) => {
        initDefaultData();
        await simulateDelay();
        const user = JSON.parse(localStorage.getItem(STORAGE_KEYS.USER) || '{}');
        const updatedUser = { ...user, ...updates };
        localStorage.setItem(STORAGE_KEYS.USER, JSON.stringify(updatedUser));
        return {
            success: true,
            data: updatedUser
        };
    },
    
    // 更新用户设置
    updateUserSettings: async (settings) => {
        initDefaultData();
        await simulateDelay();
        const user = JSON.parse(localStorage.getItem(STORAGE_KEYS.USER) || '{}');
        user.settings = { ...user.settings, ...settings };
        localStorage.setItem(STORAGE_KEYS.USER, JSON.stringify(user));
        return {
            success: true,
            data: user.settings
        };
    },
    
    // 获取主题列表
    getThemes: async () => {
        initDefaultData();
        await simulateDelay();
        const themes = JSON.parse(localStorage.getItem(STORAGE_KEYS.THEMES) || '[]');
        return {
            success: true,
            data: themes
        };
    },
    
    // 获取语言列表
    getLanguages: async () => {
        initDefaultData();
        await simulateDelay();
        const languages = JSON.parse(localStorage.getItem(STORAGE_KEYS.LANGUAGES) || '[]');
        return {
            success: true,
            data: languages
        };
    }
};

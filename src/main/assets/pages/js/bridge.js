// Android WebView 数据桥接脚本
// 提供与 Android 原生数据的交互接口

(function() {
    'use strict';

    // 桥接API对象
    window.AndroidBridge = {
        version: '1.0',
        debug: false
    };

    // 内部缓存
    var cache = {
        questions: null,
        notes: null,
        studyPlans: null,
        wrongQuestions: null,
        questionTypes: null,
        categories: null
    };

    // 日志函数
    function log(message, data) {
        if (window.AndroidBridge.debug) {
            console.log('[AndroidBridge] ' + message, data || '');
        }
    }

    // 解析JSON安全函数
    function safeParse(jsonString, defaultValue) {
        try {
            if (!jsonString || jsonString === '[]' || jsonString === '{}') {
                return defaultValue || [];
            }
            return JSON.parse(jsonString);
        } catch (e) {
            console.error('JSON解析失败:', e);
            return defaultValue || [];
        }
    }

    // ==================== 题目数据接口 ====================

    window.AndroidBridge.questions = {
        getAll: function() {
            try {
                if (typeof AndroidData !== 'undefined' && typeof AndroidData.getQuestions === 'function') {
                    cache.questions = safeParse(AndroidData.getQuestions(), []);
                } else {
                    cache.questions = [];
                }
                return cache.questions;
            } catch (e) {
                log('获取题目失败', e);
                return [];
            }
        },

        get: function(id) {
            var questions = this.getAll();
            for (var i = 0; i < questions.length; i++) {
                if (questions[i].id == id || questions[i].id === id) {
                    return questions[i];
                }
            }
            return null;
        },

        refresh: function() {
            cache.questions = null;
            try {
                if (typeof AndroidData !== 'undefined' && typeof AndroidData.refreshNotes === 'function') {
                    AndroidData.refreshNotes();
                }
            } catch (e) {}
            return this.getAll();
        },

        save: function(question) {
            if (!question.id || question.id === 0) {
                question.id = Date.now();
                question.createdAt = new Date().toISOString();
            }
            question.updatedAt = new Date().toISOString();
            try {
                if (typeof AndroidData !== 'undefined' && typeof AndroidData.saveQuestion === 'function') {
                    AndroidData.saveQuestion(JSON.stringify(question));
                }
            } catch (e) {}
            this.refresh();
            return question;
        },

        delete: function(id) {
            try {
                if (typeof AndroidData !== 'undefined' && typeof AndroidData.deleteQuestion === 'function') {
                    AndroidData.deleteQuestion(id);
                }
            } catch (e) {}
            this.refresh();
        },

        filter: function(criteria) {
            var questions = this.getAll();
            if (!criteria) return questions;

            return questions.filter(function(q) {
                if (criteria.type && q.type !== criteria.type) return false;
                if (criteria.difficulty && q.difficulty !== criteria.difficulty) return false;
                if (criteria.category && q.category !== criteria.category) return false;
                if (criteria.keyword) {
                    var kw = criteria.keyword.toLowerCase();
                    if (!q.content || !q.content.toLowerCase().includes(kw)) return false;
                }
                return true;
            });
        }
    };

    // ==================== 笔记数据接口 ====================

    window.AndroidBridge.notes = {
        getAll: function() {
            try {
                if (typeof AndroidData !== 'undefined' && typeof AndroidData.getNotes === 'function') {
                    cache.notes = safeParse(AndroidData.getNotes(), []);
                } else {
                    cache.notes = [];
                }
                return cache.notes;
            } catch (e) {
                log('获取笔记失败', e);
                return [];
            }
        },

        get: function(id) {
            var notes = this.getAll();
            for (var i = 0; i < notes.length; i++) {
                if (notes[i].id == id || notes[i].id === id) {
                    return notes[i];
                }
            }
            return null;
        },

        refresh: function() {
            cache.notes = null;
            try {
                if (typeof AndroidData !== 'undefined' && typeof AndroidData.refreshNotes === 'function') {
                    AndroidData.refreshNotes();
                }
            } catch (e) {}
            return this.getAll();
        },

        save: function(note) {
            if (!note.id || note.id === 0) {
                note.id = Date.now();
                note.createdAt = new Date().toISOString();
            }
            note.updatedAt = new Date().toISOString();
            try {
                if (typeof AndroidData !== 'undefined' && typeof AndroidData.saveNote === 'function') {
                    AndroidData.saveNote(JSON.stringify(note));
                }
            } catch (e) {}
            this.refresh();
            return note;
        },

        delete: function(id) {
            try {
                if (typeof AndroidData !== 'undefined' && typeof AndroidData.deleteNote === 'function') {
                    AndroidData.deleteNote(id);
                }
            } catch (e) {}
            this.refresh();
        },

        search: function(keyword) {
            var notes = this.getAll();
            if (!keyword) return notes;

            var kw = keyword.toLowerCase();
            return notes.filter(function(n) {
                return (n.title && n.title.toLowerCase().includes(kw)) ||
                       (n.content && n.content.toLowerCase().includes(kw));
            });
        }
    };

    // ==================== 学习计划数据接口 ====================

    window.AndroidBridge.studyPlans = {
        getAll: function() {
            try {
                if (typeof AndroidData !== 'undefined' && typeof AndroidData.getStudyPlans === 'function') {
                    cache.studyPlans = safeParse(AndroidData.getStudyPlans(), []);
                } else {
                    cache.studyPlans = [];
                }
                return cache.studyPlans;
            } catch (e) {
                log('获取学习计划失败', e);
                return [];
            }
        },

        get: function(id) {
            var plans = this.getAll();
            for (var i = 0; i < plans.length; i++) {
                if (plans[i].id == id || plans[i].id === id) {
                    return plans[i];
                }
            }
            return null;
        },

        refresh: function() {
            cache.studyPlans = null;
            try {
                if (typeof AndroidData !== 'undefined' && typeof AndroidData.refreshStudyPlans === 'function') {
                    AndroidData.refreshStudyPlans();
                }
            } catch (e) {}
            return this.getAll();
        },

        save: function(plan) {
            if (!plan.id || plan.id === 0) {
                plan.id = Date.now();
                plan.createdAt = new Date().toISOString();
            }
            plan.updatedAt = new Date().toISOString();
            try {
                if (typeof AndroidData !== 'undefined' && typeof AndroidData.saveStudyPlan === 'function') {
                    AndroidData.saveStudyPlan(JSON.stringify(plan));
                }
            } catch (e) {}
            this.refresh();
            return plan;
        },

        delete: function(id) {
            try {
                if (typeof AndroidData !== 'undefined' && typeof AndroidData.deleteStudyPlan === 'function') {
                    AndroidData.deleteStudyPlan(id);
                }
            } catch (e) {}
            this.refresh();
        },

        getActive: function() {
            return this.getAll().filter(function(p) {
                return p.status === 'active';
            });
        }
    };

    // ==================== 错题数据接口 ====================

    window.AndroidBridge.wrongQuestions = {
        getAll: function() {
            try {
                if (typeof AndroidData !== 'undefined' && typeof AndroidData.getWrongQuestions === 'function') {
                    cache.wrongQuestions = safeParse(AndroidData.getWrongQuestions(), []);
                } else {
                    cache.wrongQuestions = [];
                }
                return cache.wrongQuestions;
            } catch (e) {
                log('获取错题失败', e);
                return [];
            }
        },

        refresh: function() {
            cache.wrongQuestions = null;
            try {
                if (typeof AndroidData !== 'undefined' && typeof AndroidData.refreshWrongQuestions === 'function') {
                    AndroidData.refreshWrongQuestions();
                }
            } catch (e) {}
            return this.getAll();
        },

        remove: function(questionId) {
            try {
                if (typeof AndroidData !== 'undefined' && typeof AndroidData.removeWrongQuestion === 'function') {
                    AndroidData.removeWrongQuestion(questionId);
                }
            } catch (e) {}
            this.refresh();
        },

        clearAll: function() {
            try {
                if (typeof AndroidData !== 'undefined' && typeof AndroidData.clearAllWrongQuestions === 'function') {
                    AndroidData.clearAllWrongQuestions();
                }
            } catch (e) {}
            this.refresh();
        },

        add: function(wrongQuestion) {
            wrongQuestion.wrongTime = new Date().toISOString();
            try {
                if (typeof AndroidData !== 'undefined' && typeof AndroidData.addWrongQuestion === 'function') {
                    AndroidData.addWrongQuestion(JSON.stringify(wrongQuestion));
                }
            } catch (e) {}
            this.refresh();
        }
    };

    // ==================== 系统工具接口 ====================

    window.AndroidBridge.utils = {
        showToast: function(message) {
            try {
                if (typeof AndroidUtil !== 'undefined' && typeof AndroidUtil.showToast === 'function') {
                    AndroidUtil.showToast(message);
                }
            } catch (e) {
                log('显示Toast失败', e);
            }
        },

        showToastLong: function(message) {
            try {
                if (typeof AndroidUtil !== 'undefined' && typeof AndroidUtil.showToastLong === 'function') {
                    AndroidUtil.showToastLong(message);
                }
            } catch (e) {
                log('显示长Toast失败', e);
            }
        },

        vibrate: function(milliseconds) {
            try {
                if (typeof AndroidUtil !== 'undefined' && typeof AndroidUtil.vibrate === 'function') {
                    AndroidUtil.vibrate(milliseconds || 100);
                }
            } catch (e) {
                log('振动失败', e);
            }
        },

        getDeviceInfo: function() {
            try {
                if (typeof AndroidUtil !== 'undefined' && typeof AndroidUtil.getDeviceInfo === 'function') {
                    return JSON.parse(AndroidUtil.getDeviceInfo());
                }
            } catch (e) {
                log('获取设备信息失败', e);
            }
            return {};
        },

        getAppVersion: function() {
            try {
                if (typeof AndroidUtil !== 'undefined' && typeof AndroidUtil.getAppVersion === 'function') {
                    return AndroidUtil.getAppVersion();
                }
            } catch (e) {
                return '1.0.0';
            }
        },

        copyToClipboard: function(text) {
            try {
                if (typeof AndroidClipboard !== 'undefined' && typeof AndroidClipboard.copy === 'function') {
                    AndroidClipboard.copy(text);
                    return true;
                } else if (typeof AndroidUtil !== 'undefined' && typeof AndroidUtil.setClipboard === 'function') {
                    AndroidUtil.setClipboard(text);
                    return true;
                }
            } catch (e) {
                log('复制到剪贴板失败', e);
            }
            return false;
        },

        share: function(title, text) {
            try {
                if (typeof AndroidUtil !== 'undefined' && typeof AndroidUtil.shareText === 'function') {
                    AndroidUtil.shareText(title, text);
                    return true;
                }
            } catch (e) {
                log('分享失败', e);
            }
            return false;
        },

        openUrl: function(url) {
            try {
                if (typeof AndroidUtil !== 'undefined' && typeof AndroidUtil.openUrlInBrowser === 'function') {
                    AndroidUtil.openUrlInBrowser(url);
                    return true;
                }
            } catch (e) {
                log('打开链接失败', e);
            }
            return false;
        },

        exit: function() {
            try {
                if (typeof Android !== 'undefined' && typeof Android.finishActivity === 'function') {
                    Android.finishActivity();
                }
            } catch (e) {
                log('关闭页面失败', e);
            }
        }
    };

    // ==================== 数据刷新接口 ====================

    window.AndroidBridge.refresh = function() {
        cache = {
            questions: null,
            notes: null,
            studyPlans: null,
            wrongQuestions: null,
            questionTypes: null,
            categories: null
        };
        try {
            if (typeof AndroidData !== 'undefined' && typeof AndroidData.refreshAllData === 'function') {
                AndroidData.refreshAllData();
            }
        } catch (e) {
            log('刷新数据失败', e);
        }
    };

    // ==================== 初始化完成通知 ====================

    window.AndroidBridge.ready = function(callback) {
        if (typeof callback === 'function') {
            if (document.readyState === 'complete') {
                setTimeout(callback, 0);
            } else {
                window.addEventListener('load', callback);
            }
        }
    };

    log('AndroidBridge initialized');

})();

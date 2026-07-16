// 模板管理模块
const templateModule = {
    // 获取模板列表
    getTemplates() {
        const templates = localStorage.getItem('smartquiz_templates');
        return templates ? JSON.parse(templates) : [];
    },
    
    // 保存模板列表
    saveTemplates(templates) {
        localStorage.setItem('smartquiz_templates', JSON.stringify(templates));
    },
    
    // 添加模板
    addTemplate(template) {
        const templates = this.getTemplates();
        const newTemplate = {
            id: Date.now().toString(),
            ...template,
            createdAt: new Date().toISOString()
        };
        templates.push(newTemplate);
        this.saveTemplates(templates);
        return newTemplate;
    },
    
    // 更新模板
    updateTemplate(id, updatedTemplate) {
        const templates = this.getTemplates();
        const index = templates.findIndex(t => t.id === id);
        if (index !== -1) {
            templates[index] = {
                ...templates[index],
                ...updatedTemplate,
                updatedAt: new Date().toISOString()
            };
            this.saveTemplates(templates);
            return templates[index];
        }
        return null;
    },
    
    // 删除模板
    deleteTemplate(id) {
        const templates = this.getTemplates();
        const updatedTemplates = templates.filter(t => t.id !== id);
        this.saveTemplates(updatedTemplates);
        return updatedTemplates;
    },
    
    // 渲染模板列表
    renderTemplates() {
        const templateList = document.getElementById('templateList');
        const emptyState = document.getElementById('emptyState');
        const templates = this.getTemplates();
        
        if (templates.length === 0) {
            templateList.style.display = 'none';
            emptyState.style.display = 'block';
            return;
        }
        
        templateList.style.display = 'grid';
        emptyState.style.display = 'none';
        
        templateList.innerHTML = templates.map(template => {
            // 根据模板类型选择图标
            let iconClass;
            switch (template.type) {
                case 'excel':
                    iconClass = 'fas fa-file-excel';
                    break;
                case 'json':
                    iconClass = 'fas fa-file-code';
                    break;
                case 'csv':
                    iconClass = 'fas fa-file-csv';
                    break;
                case 'word':
                    iconClass = 'fas fa-file-word';
                    break;
                default:
                    iconClass = 'fas fa-file-alt';
            }
            
            return `
                <div class="template-card">
                    <div class="template-icon">
                        <i class="${iconClass}"></i>
                    </div>
                    <h3 class="template-title">${template.name}</h3>
                    <p class="template-description">${template.description || '无描述'}</p>
                    <div class="template-actions">
                        <button class="template-btn" onclick="templateModule.editTemplate('${template.id}')">
                            <i class="fas fa-edit"></i> 编辑
                        </button>
                        <button class="template-btn delete" onclick="templateModule.deleteTemplateConfirm('${template.id}')">
                            <i class="fas fa-trash"></i> 删除
                        </button>
                    </div>
                </div>
            `;
        }).join('');
    },
    
    // 打开添加模板模态框
    openAddTemplateModal() {
        document.getElementById('modalTitle').textContent = '添加模板';
        document.getElementById('templateId').value = '';
        document.getElementById('templateName').value = '';
        document.getElementById('templateDescription').value = '';
        document.getElementById('templateType').value = 'excel';
        document.getElementById('templateModal').classList.add('active');
    },
    
    // 打开编辑模板模态框
    editTemplate(id) {
        const templates = this.getTemplates();
        const template = templates.find(t => t.id === id);
        if (template) {
            document.getElementById('modalTitle').textContent = '编辑模板';
            document.getElementById('templateId').value = template.id;
            document.getElementById('templateName').value = template.name;
            document.getElementById('templateDescription').value = template.description || '';
            document.getElementById('templateType').value = template.type;
            document.getElementById('templateModal').classList.add('active');
        }
    },
    
    // 关闭模板模态框
    closeTemplateModal() {
        document.getElementById('templateModal').classList.remove('active');
    },
    
    // 确认删除模板
    deleteTemplateConfirm(id) {
        if (confirm('确定要删除这个模板吗？')) {
            this.deleteTemplate(id);
            this.renderTemplates();
        }
    },
    
    // 处理表单提交
    handleFormSubmit(e) {
        e.preventDefault();
        const id = document.getElementById('templateId').value;
        const name = document.getElementById('templateName').value;
        const description = document.getElementById('templateDescription').value;
        const type = document.getElementById('templateType').value;
        
        const templateData = {
            name,
            description,
            type
        };
        
        if (id) {
            // 更新模板
            this.updateTemplate(id, templateData);
        } else {
            // 添加模板
            this.addTemplate(templateData);
        }
        
        this.closeTemplateModal();
        this.renderTemplates();
    },
    
    // 初始化
    init() {
        // 渲染初始模板列表
        this.renderTemplates();
        
        // 绑定表单提交事件
        document.getElementById('templateForm').addEventListener('submit', (e) => this.handleFormSubmit(e));
        
        // 绑定全局函数
        window.openAddTemplateModal = () => this.openAddTemplateModal();
        window.closeTemplateModal = () => this.closeTemplateModal();
    }
};

// 页面加载完成后初始化
document.addEventListener('DOMContentLoaded', () => {
    templateModule.init();
});
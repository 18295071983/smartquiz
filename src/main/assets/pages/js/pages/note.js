// 学习笔记页面初始化
function initNotePage() {
    console.log('学习笔记页面初始化');
    
    // 绑定事件
    bindNoteEvents();
    
    // 加载笔记列表
    loadNotes();
    
    // 初始化模态框
    initModal();
}

// 绑定事件
function bindNoteEvents() {
    // 新建笔记按钮
    document.getElementById('add-note-btn').addEventListener('click', function() {
        openModal();
    });
    
    // 搜索按钮
    document.getElementById('search-note-btn').addEventListener('click', function() {
        searchNotes();
    });
    
    // 搜索框回车事件
    document.getElementById('note-search').addEventListener('keypress', function(e) {
        if (e.key === 'Enter') {
            searchNotes();
        }
    });
    
    // 保存笔记按钮
    document.getElementById('save-note-btn').addEventListener('click', function() {
        saveNote();
    });
    
    // 取消按钮
    document.getElementById('cancel-note-btn').addEventListener('click', function() {
        closeModal();
    });
    
    // 关闭模态框按钮
    document.getElementById('close-modal').addEventListener('click', function() {
        closeModal();
    });
}

// 初始化模态框
function initModal() {
    const modal = document.getElementById('note-modal');
    
    // 点击模态框外部关闭
    window.addEventListener('click', function(e) {
        if (e.target === modal) {
            closeModal();
        }
    });
}

// 打开模态框
function openModal(note = null) {
    const modal = document.getElementById('note-modal');
    const modalTitle = document.getElementById('modal-title');
    const noteTitle = document.getElementById('note-title');
    const noteContent = document.getElementById('note-content');
    const noteDate = document.getElementById('note-date');
    
    if (note) {
        modalTitle.textContent = '编辑笔记';
        noteTitle.value = note.title;
        noteContent.value = note.content;
        noteDate.value = note.date || new Date().toISOString().split('T')[0];
        modal.dataset.noteId = note.id;
    } else {
        modalTitle.textContent = '新建笔记';
        noteTitle.value = '';
        noteContent.value = '';
        noteDate.value = new Date().toISOString().split('T')[0];
        delete modal.dataset.noteId;
    }
    
    modal.style.display = 'block';
}

// 关闭模态框
function closeModal() {
    const modal = document.getElementById('note-modal');
    modal.style.display = 'none';
    
    // 清除表单
    document.getElementById('note-title').value = '';
    document.getElementById('note-content').value = '';
    document.getElementById('note-date').value = new Date().toISOString().split('T')[0];
    delete modal.dataset.noteId;
}

// 保存笔记
async function saveNote() {
    const title = document.getElementById('note-title').value;
    const content = document.getElementById('note-content').value;
    const date = document.getElementById('note-date').value;
    const noteId = document.getElementById('note-modal').dataset.noteId;
    
    if (!title) {
        showMessage('请输入笔记标题', 'error');
        return;
    }
    
    if (!content) {
        showMessage('请输入笔记内容', 'error');
        return;
    }
    
    try {
        if (noteId) {
            // 编辑现有笔记
            const result = await api.updateNote(noteId, {
                title,
                content,
                date
            });
            if (result.success) {
                showMessage('笔记更新成功', 'success');
            } else {
                showMessage('笔记更新失败', 'error');
            }
        } else {
            // 新建笔记
            const result = await api.addNote({
                title,
                content,
                date,
                tags: []
            });
            if (result.success) {
                showMessage('笔记创建成功', 'success');
            } else {
                showMessage('笔记创建失败', 'error');
            }
        }
        
        await loadNotes();
        closeModal();
    } catch (error) {
        showMessage('保存笔记时出现错误', 'error');
        console.error('保存笔记错误:', error);
    }
}

// 加载笔记列表
async function loadNotes() {
    try {
        const result = await api.getNotes();
        if (result.success) {
            const notes = result.data;
            const noteList = document.getElementById('note-list');
            
            if (notes.length === 0) {
                noteList.innerHTML = '<p class="no-data">暂无笔记</p>';
                return;
            }
            
            noteList.innerHTML = '';
            
            notes.forEach(note => {
                const noteItem = document.createElement('div');
                noteItem.className = 'note-item';
                
                noteItem.innerHTML = `
                    <div class="note-header">
                        <h3 class="note-item-title">${note.title}</h3>
                        <div class="note-actions">
                            <button class="edit-note-btn" data-id="${note.id}">
                                <i class="fas fa-edit"></i>
                            </button>
                            <button class="delete-note-btn" data-id="${note.id}">
                                <i class="fas fa-trash"></i>
                            </button>
                        </div>
                    </div>
                    <p class="note-item-content">${truncateContent(note.content, 100)}</p>
                    <div class="note-footer">
                        <span class="note-date">${note.date || formatDate(note.createdAt)}</span>
                        <span class="note-updated">${formatDateTime(note.updatedAt)}</span>
                    </div>
                `;
                
                noteList.appendChild(noteItem);
            });
            
            // 绑定编辑和删除按钮事件
            bindNoteItemEvents();
        }
    } catch (error) {
        console.error('加载笔记错误:', error);
        showMessage('加载笔记时出现错误', 'error');
    }
}

// 绑定笔记项事件
function bindNoteItemEvents() {
    // 编辑按钮
    document.querySelectorAll('.edit-note-btn').forEach(btn => {
        btn.addEventListener('click', async function() {
            const noteId = this.dataset.id;
            try {
                const notes = (await api.getNotes()).data;
                const note = notes.find(note => note.id === noteId);
                if (note) {
                    openModal(note);
                }
            } catch (error) {
                console.error('获取笔记详情错误:', error);
            }
        });
    });
    
    // 删除按钮
    document.querySelectorAll('.delete-note-btn').forEach(btn => {
        btn.addEventListener('click', function() {
            const noteId = this.dataset.id;
            if (confirm('确定要删除这条笔记吗？')) {
                deleteNote(noteId);
            }
        });
    });
}

// 删除笔记
async function deleteNote(noteId) {
    try {
        const result = await api.deleteNote(noteId);
        if (result.success) {
            await loadNotes();
            showMessage('笔记删除成功', 'success');
        } else {
            showMessage('笔记删除失败', 'error');
        }
    } catch (error) {
        showMessage('删除笔记时出现错误', 'error');
        console.error('删除笔记错误:', error);
    }
}

// 搜索笔记
async function searchNotes() {
    const searchTerm = document.getElementById('note-search').value.toLowerCase();
    try {
        const result = await api.getNotes();
        if (result.success) {
            const notes = result.data;
            const filteredNotes = notes.filter(note => 
                note.title.toLowerCase().includes(searchTerm) || 
                note.content.toLowerCase().includes(searchTerm)
            );
            
            const noteList = document.getElementById('note-list');
            
            if (filteredNotes.length === 0) {
                noteList.innerHTML = '<p class="no-data">没有找到匹配的笔记</p>';
                return;
            }
            
            noteList.innerHTML = '';
            
            filteredNotes.forEach(note => {
                const noteItem = document.createElement('div');
                noteItem.className = 'note-item';
                
                noteItem.innerHTML = `
                    <div class="note-header">
                        <h3 class="note-item-title">${note.title}</h3>
                        <div class="note-actions">
                            <button class="edit-note-btn" data-id="${note.id}">
                                <i class="fas fa-edit"></i>
                            </button>
                            <button class="delete-note-btn" data-id="${note.id}">
                                <i class="fas fa-trash"></i>
                            </button>
                        </div>
                    </div>
                    <p class="note-item-content">${truncateContent(note.content, 100)}</p>
                    <div class="note-footer">
                        <span class="note-date">${note.date || formatDate(note.createdAt)}</span>
                        <span class="note-updated">${formatDateTime(note.updatedAt)}</span>
                    </div>
                `;
                
                noteList.appendChild(noteItem);
            });
            
            // 绑定编辑和删除按钮事件
            bindNoteItemEvents();
        }
    } catch (error) {
        console.error('搜索笔记错误:', error);
    }
}

// 截断内容
function truncateContent(content, maxLength) {
    if (content.length <= maxLength) {
        return content;
    }
    return content.substring(0, maxLength) + '...';
}

// AI助手页面JavaScript模块

function initAIChatPage() {
    console.log('初始化AI助手页面');
    
    // 加载聊天历史
    loadChatHistory();
    
    // 为发送按钮添加点击事件
    const sendButton = document.querySelector('.send-button');
    if (sendButton) {
        sendButton.addEventListener('click', sendMessage);
    }
    
    // 为输入框添加回车键事件
    const chatInput = document.querySelector('.chat-input-area input');
    if (chatInput) {
        chatInput.addEventListener('keypress', function(e) {
            if (e.key === 'Enter') {
                sendMessage();
            }
        });
    }
    
    // 为快速问题按钮添加点击事件
    const quickQuestionButtons = document.querySelectorAll('.quick-question-btn');
    quickQuestionButtons.forEach(button => {
        button.addEventListener('click', function() {
            const question = this.textContent;
            const chatInput = document.querySelector('.chat-input-area input');
            if (chatInput) {
                chatInput.value = question;
                sendMessage();
            }
        });
    });
    
    // 滚动到底部
    scrollToBottom();
}

// 加载聊天历史
async function loadChatHistory() {
    try {
        const response = await api.getChatHistory();
        if (response.success) {
            console.log('聊天历史加载成功:', response.data);
            // 这里可以根据需要更新页面上的聊天历史
        } else {
            showMessage('加载聊天历史失败', 'error');
        }
    } catch (error) {
        console.error('加载聊天历史失败:', error);
        showMessage('加载聊天历史失败', 'error');
    }
}

// 发送消息
async function sendMessage() {
    const chatInput = document.querySelector('.chat-input-area input');
    const message = chatInput.value.trim();
    
    if (message) {
        // 清空输入框
        chatInput.value = '';
        
        // 添加用户消息到聊天界面
        addMessage('user', message);
        
        try {
            // 发送消息到API
            const response = await api.sendChatMessage(message);
            if (response.success) {
                // 添加AI回复到聊天界面
                const aiMessage = response.data[1]; // 第二个消息是AI的回复
                addMessage('ai', aiMessage.content);
            } else {
                showMessage('发送消息失败', 'error');
            }
        } catch (error) {
            console.error('发送消息失败:', error);
            showMessage('发送消息失败', 'error');
        }
    }
}

// 添加消息到聊天界面
function addMessage(role, content) {
    const chatMessages = document.querySelector('.chat-messages');
    const messageDiv = document.createElement('div');
    messageDiv.className = `message ${role}-message`;
    
    const avatarDiv = document.createElement('div');
    avatarDiv.className = 'message-avatar';
    avatarDiv.innerHTML = role === 'user' ? '<i class="fas fa-user"></i>' : '<i class="fas fa-robot"></i>';
    
    const contentDiv = document.createElement('div');
    contentDiv.className = 'message-content';
    
    const messageText = document.createElement('p');
    messageText.textContent = content;
    
    const messageTime = document.createElement('span');
    messageTime.className = 'message-time';
    messageTime.textContent = formatTime(new Date());
    
    contentDiv.appendChild(messageText);
    contentDiv.appendChild(messageTime);
    messageDiv.appendChild(avatarDiv);
    messageDiv.appendChild(contentDiv);
    
    chatMessages.appendChild(messageDiv);
    
    // 滚动到底部
    scrollToBottom();
}

// 滚动到底部
function scrollToBottom() {
    const chatMessages = document.querySelector('.chat-messages');
    chatMessages.scrollTop = chatMessages.scrollHeight;
}

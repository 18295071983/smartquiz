// 测验页面JavaScript模块

let timerInterval;
let seconds = 0;
let currentQuestionIndex = 0;
const totalQuestions = 3;

function initQuizPage() {
    console.log('初始化测验页面');
    
    // 开始计时器
    startTimer();
    
    // 为选项添加点击事件
    const options = document.querySelectorAll('.options input[type="radio"]');
    options.forEach(option => {
        option.addEventListener('change', function() {
            // 可以在这里添加答题逻辑
            console.log('选择了选项:', this.value);
        });
    });
    
    // 为上一题按钮添加点击事件
    const prevButton = document.querySelector('.quiz-navigation button:first-child');
    if (prevButton) {
        prevButton.addEventListener('click', function() {
            if (currentQuestionIndex > 0) {
                currentQuestionIndex--;
                updateQuestion();
            }
        });
    }
    
    // 为下一题按钮添加点击事件
    const nextButton = document.querySelector('.quiz-navigation button:last-child');
    if (nextButton) {
        nextButton.addEventListener('click', function() {
            if (currentQuestionIndex < totalQuestions - 1) {
                currentQuestionIndex++;
                updateQuestion();
            } else {
                // 完成测验
                finishQuiz();
            }
        });
    }
    
    // 为标记按钮添加点击事件
    const markButton = document.querySelector('.quiz-controls button:first-child');
    if (markButton) {
        markButton.addEventListener('click', function() {
            showMessage('题目已标记', 'success');
        });
    }
    
    // 为题目列表按钮添加点击事件
    const listButton = document.querySelector('.quiz-controls button:nth-child(2)');
    if (listButton) {
        listButton.addEventListener('click', function() {
            showMessage('题目列表功能开发中', 'info');
        });
    }
    
    // 为暂停按钮添加点击事件
    const pauseButton = document.querySelector('.quiz-controls button:last-child');
    if (pauseButton) {
        pauseButton.addEventListener('click', function() {
            if (this.innerHTML.includes('暂停')) {
                clearInterval(timerInterval);
                this.innerHTML = '<i class="fas fa-play"></i> 继续';
                showMessage('测验已暂停', 'info');
            } else {
                startTimer();
                this.innerHTML = '<i class="fas fa-pause"></i> 暂停';
                showMessage('测验已继续', 'info');
            }
        });
    }
}

// 开始计时器
function startTimer() {
    timerInterval = setInterval(function() {
        seconds++;
        updateTimerDisplay();
    }, 1000);
}

// 更新计时器显示
function updateTimerDisplay() {
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = seconds % 60;
    const timerElement = document.querySelector('.quiz-timer span');
    if (timerElement) {
        timerElement.textContent = `${String(minutes).padStart(2, '0')}:${String(remainingSeconds).padStart(2, '0')}`;
    }
}

// 更新题目
function updateQuestion() {
    // 更新进度条
    const progressFill = document.querySelector('.progress-fill');
    if (progressFill) {
        const progress = ((currentQuestionIndex + 1) / totalQuestions) * 100;
        progressFill.style.width = `${progress}%`;
    }
    
    // 更新题目计数
    const questionCount = document.querySelector('.quiz-progress p');
    if (questionCount) {
        questionCount.textContent = `第 ${currentQuestionIndex + 1} 题 / 共 ${totalQuestions} 题`;
    }
    
    // 更新上一题按钮状态
    const prevButton = document.querySelector('.quiz-navigation button:first-child');
    if (prevButton) {
        if (currentQuestionIndex === 0) {
            prevButton.disabled = true;
            prevButton.classList.add('disabled');
        } else {
            prevButton.disabled = false;
            prevButton.classList.remove('disabled');
        }
    }
    
    // 更新下一题按钮文本
    const nextButton = document.querySelector('.quiz-navigation button:last-child');
    if (nextButton) {
        if (currentQuestionIndex === totalQuestions - 1) {
            nextButton.textContent = '完成测验';
        } else {
            nextButton.textContent = '下一题';
        }
    }
    
    // 这里可以根据currentQuestionIndex加载对应题目的数据
    console.log('当前题目:', currentQuestionIndex + 1);
}

// 完成测验
function finishQuiz() {
    clearInterval(timerInterval);
    showMessage('测验完成！', 'success');
    // 这里可以添加测验结果逻辑
    setTimeout(() => {
        navigateTo('index.html');
    }, 2000);
}

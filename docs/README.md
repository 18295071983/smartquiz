# 答题宝 开发文档

> 设计文档体系（`docs/development`）已按 feature/agent-local 真实代码重写。

## 文档结构

```
docs/
├── ai_rules.md                            # AI 编码助手规则
├── QWeather_SDK_Guide.md                  # 和风天气 SDK 集成指南
├── README.md                              # 本索引
├── smartquiz-wiki.html                    # 项目 Wiki（单文件 HTML，可视化导航）
├── AI工具功能清单.md                       # AI 工具功能描述清单（agent 工具全集）
├── development/                           # 设计文档体系（重写）
│   ├── 01-project-overview.md             # 项目架构总览
│   ├── 02-ai-agent-architecture.md        # AI Agent 架构设计
│   ├── 03-ai-chat-ui.md                   # AI 对话界面设计
│   ├── 04-ai-service-inference.md         # AI 服务与推理设计（含四后端/NPU）
│   ├── 05-tool-system.md                  # 工具系统设计（含 ssh_exec）
│   ├── 06-data-layer.md                   # 数据层设计
│   ├── 07-hardware-performance.md         # 硬件与性能（NPU/OpenCL/Vulkan）
│   ├── 08-module-inventory.md             # 模块清单
│   ├── 09-development-guide.md            # 开发规范
│   ├── 10-database-design.md              # 数据库设计
│   ├── 11-edge-model-deployment.md        # 端侧大模型部署设计（四后端）
│   ├── 12-llama-cpp.md                    # llama.cpp 功能设计（四后端）
│   ├── 13-inference-engine.md             # 推理库与推理引擎设计
│   ├── 14-cmake-build.md                  # CMake 构建设计（四后端 / NPU）
│   ├── 15-question-import.md              # 题库文件导入功能设计
│   ├── 15-question-import-review.md       # 题库导入复评
│   └── 16-ai-coding-conventions.md        # AI 工具编码约定
├── database/
│   └── database_structure.md              # 数据库结构（保留参考）
├── research/
│   └── README.md                          # 调研笔记去向说明
└── system/
    ├── api_design.md                      # API 设计
    ├── deployment_guide.md                # 部署指南
    └── system_architecture.md             # 系统架构
```

根目录另有：

- `CHANGELOG.md`（变更日志，最新：2026-10-09）
- `SETUP_GUIDE.md` / `DEVELOPMENT_GUIDE.md`（环境搭建 / 开发指南）
- `CosyVoice-TTS-测试指南.md`（CosyVoice TTS 专项测试记录）
- `TERMUX一键准备_用户配合设计.md`（一键准备交互设计）
- `git-ops-guide.md`（Git 操作规范，含 `src/main/cpp/llama.cpp` 嵌套仓库注意事项）
- `DEBUG_AGENT_BRIDGE.md`（`AgentDebugBridge` 外部注入调试通道）
- `README.md` / `ROADMAP.md` / `使用速查表.md` / `工具创建指南.md` / `HTML_DESIGN_RULES.md` / `APK_SOURCE_GUIDE.md`（壳 HTML/APK 导出）
- `AI_FIX_ROADMAP.md` / `OilQuiz_综合迭代文档.md` / `AI_USAGE_CONFIG_README.md` / `douyin_downloader_工具文档.md`（**历史快照**，文首已标注日期与现状差异）
- `js_execute_*.md` / `fullscreen_playbook.md` / `patch_fullscreen_bridge_*.md` / `mock_v8_test_checklist.md` / `apk_payload_verify_0915.md`（**非本项目代码/架构**，一次性导出产出物的历史记录，文首已标注）

## 文档导航

### 新入开发者

1. **项目 Wiki** → [smartquiz-wiki.html](smartquiz-wiki.html)（浏览器打开）
2. **项目概览** → [development/01-project-overview.md](development/01-project-overview.md)
3. **系统架构** → [system/system_architecture.md](system/system_architecture.md)
4. **开发规范** → [development/09-development-guide.md](development/09-development-guide.md)
5. **数据库设计** → [development/10-database-design.md](development/10-database-design.md)

### 最近变更与工具

1. **变更日志** → [../CHANGELOG.md](../CHANGELOG.md)（最新：2026-10-09）
2. **AI 工具清单** → [AI工具功能清单.md](AI工具功能清单.md)
3. **Termux 一键准备设计** → [../TERMUX一键准备_用户配合设计.md](../TERMUX一键准备_用户配合设计.md)
4. **Git 操作规范** → [../git-ops-guide.md](../git-ops-guide.md)
5. **Agent 调试通道** → [../DEBUG_AGENT_BRIDGE.md](../DEBUG_AGENT_BRIDGE.md)

### AI 功能开发

1. **Agent 架构** → [development/02-ai-agent-architecture.md](development/02-ai-agent-architecture.md)
2. **AI 对话界面** → [development/03-ai-chat-ui.md](development/03-ai-chat-ui.md)
3. **AI 服务与推理** → [development/04-ai-service-inference.md](development/04-ai-service-inference.md)
4. **工具系统** → [development/05-tool-system.md](development/05-tool-system.md)
5. **模块清单** → [development/08-module-inventory.md](development/08-module-inventory.md)
6. **端侧大模型部署** → [development/11-edge-model-deployment.md](development/11-edge-model-deployment.md)
7. **llama.cpp 功能** → [development/12-llama-cpp.md](development/12-llama-cpp.md)

### 功能模块

1. **数据层** → [development/06-data-layer.md](development/06-data-layer.md)
2. **硬件与性能** → [development/07-hardware-performance.md](development/07-hardware-performance.md)

### 语音与天气

1. **天气 SDK** → [QWeather_SDK_Guide.md](QWeather_SDK_Guide.md)
2. **AI 编码规则** → [ai_rules.md](ai_rules.md)

## 文档维护

- 文档与 `feature/agent-local` 分支的源码同步，确保准确性。
- 设计文档集中在 `docs/development`，按编号组织（01~16）。
- 重大功能变更时同步更新对应文档。
- `docs/AGENT_ARCHITECTURE.md`、`AGENT_LOCAL_MODEL_ONLINE_ROUTING.md`、`ONLINE_*` 等早期文档已删除（内容过时或与新文档重复）。

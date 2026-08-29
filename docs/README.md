# 答题宝 开发文档

> 设计文档体系（`docs/development`）已按 feature/agent-local 真实代码重写。

## 文档结构

```
docs/
├── ai_rules.md                            # AI 编码助手规则
├── QWeather_SDK_Guide.md                  # 和风天气 SDK 集成指南
├── README.md                              # 本索引
├── development/                           # 设计文档体系（重写）
│   ├── 01-project-overview.md             # 项目架构总览
│   ├── 02-ai-agent-architecture.md        # AI Agent 架构设计
│   ├── 03-ai-chat-ui.md                   # AI 对话界面设计
│   ├── 04-ai-service-inference.md         # AI 服务与推理设计
│   ├── 05-tool-system.md                  # 工具系统设计
│   ├── 06-data-layer.md                   # 数据层设计
│   ├── 07-hardware-performance.md         # 硬件与性能
│   ├── 08-module-inventory.md             # 模块清单
│   ├── 09-development-guide.md            # 开发规范
│   ├── 10-database-design.md              # 数据库设计
│   ├── 11-edge-model-deployment.md        # 端侧大模型部署设计
│   ├── 12-llama-cpp.md                    # llama.cpp 功能设计
│   ├── 13-inference-engine.md             # 推理库与推理引擎设计
│   ├── 14-cmake-build.md                  # CMake 构建设计
│   ├── 15-question-import.md              # 题库文件导入功能设计
│   └── 16-ai-coding-conventions.md        # AI 工具编码约定
├── database/
│   └── database_structure.md              # 数据库结构（保留参考）
└── system/
    ├── api_design.md                      # API 设计
    ├── deployment_guide.md                # 部署指南
    └── system_architecture.md             # 系统架构
```

## 文档导航

### 新入开发者

1. **项目概览** → [development/01-project-overview.md](development/01-project-overview.md)
2. **系统架构** → [system/system_architecture.md](system/system_architecture.md)
3. **开发规范** → [development/09-development-guide.md](development/09-development-guide.md)
4. **数据库设计** → [development/10-database-design.md](development/10-database-design.md)

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

# 答题宝 开发文档

## 文档结构

```
docs/
├── AGENT_ARCHITECTURE.md                  # Agent 智能代理架构设计
├── ai_rules.md                            # AI 开发规则与规范
├── database/
│   └── database_structure.md              # 数据库结构设计
├── development/
│   ├── ai_feature_design.md               # AI 功能总体设计
│   ├── ai_ui_interaction_design.md        # AI-UI 交互设计
│   ├── android_adaptation.md              # Android 原生适配开发标准化
│   ├── comprehensive_analysis_report.md   # 综合分析报告
│   ├── development_standards.md           # 开发标准规范
│   ├── enhanced_local_library_design.md   # 增强本地库设计
│   ├── excel_import_feature.md            # Excel 导入功能设计
│   ├── module_function_design.md          # 模块功能设计
│   ├── project_redesign_summary.md        # 项目重设计总结
│   ├── tech_stack.md                      # 技术栈文档
│   ├── testing_strategy.md               # 测试策略文档
│   ├── ui_resources_design.md             # UI 资源设计
│   ├── ui_ux_redesign.md                  # UI/UX 重设计文档
│   ├── user_stories.md                    # 用户故事
│   ├── AGENT_OPTIMIZATION_REPORT.md       # Agent 优化报告
│   ├── RESOURCE_REFACTORING_GUIDE.md      # 资源重构指南
│   ├── TBS_SDK_INTEGRATION_GUIDE.md       # TBS SDK 集成指南
│   ├── 应用重构设计文档.md                # 应用重构设计
│   └── ai_modules/
│       ├── ai_service_design.md           # AI 服务层设计
│       ├── learning_assistant_design.md   # 学习助手设计
│       ├── llm_service_design.md          # LLM 服务设计
│       ├── model_manager_design.md        # 模型管理设计
│       ├── question_analyzer_design.md    # 题目分析器设计
│       ├── question_generator_design.md   # 题目生成器设计
│       └── translator_design.md           # 翻译器设计
└── system/
    ├── api_design.md                      # API 设计文档
    ├── deployment_guide.md                # 部署指南
    └── system_architecture.md             # 系统架构设计
```

## 文档导航

### 新入开发者

1. **系统架构** → [system_architecture.md](system/system_architecture.md) - 了解整体系统设计
2. **技术栈** → [tech_stack.md](development/tech_stack.md) - 了解使用的技术栈
3. **开发规范** → [development_standards.md](development/development_standards.md) - 了解编码规范
4. **数据库设计** → [database_structure.md](database/database_structure.md) - 了解数据模型

### AI 功能开发

1. **AI 总体设计** → [ai_feature_design.md](development/ai_feature_design.md)
2. **Agent 架构** → [AGENT_ARCHITECTURE.md](AGENT_ARCHITECTURE.md)
3. **LLM 服务** → [llm_service_design.md](development/ai_modules/llm_service_design.md)
4. **AI-UI 交互** → [ai_ui_interaction_design.md](development/ai_ui_interaction_design.md)

### 部署与运维

1. **部署指南** → [deployment_guide.md](system/deployment_guide.md)
2. **测试策略** → [testing_strategy.md](development/testing_strategy.md)
3. **API 设计** → [api_design.md](system/api_design.md)

## 文档维护

- 文档随代码同步更新，确保准确性
- 所有文档纳入版本控制管理
- 重大功能变更时同步更新相关文档
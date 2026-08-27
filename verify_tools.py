import re, os

base = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app"

# 速查表中的工具清单
tools = ["ai_weather","network_search","smart_research","webpage_reader",
         "python_calculate","python_execute","python_analyze_data","python_web_reader",
         "python_file_ops","python_chart","location","file_reader","file_analyzer",
         "file_generator","database","excel_tool","system_resource","app_operation",
         "image_gen","dashscope_media","speech_synthesis","voice_input","ocr_recognize",
         "memory","workspace","ui_component","ui_component_plugin","create_dynamic_tool",
         "ai_create_tool","tool_registry","permission_manager","app_toolkit"]

# 读取 AIToolManager 全文
tm_path = os.path.join(base, "ai", "tool", "AIToolManager.java")
with open(tm_path, encoding="utf-8") as f:
    tm = f.read()

# 1) AIToolManager 中 registerToolFactory
factory_matches = set(re.findall(r'registerToolFactory\("([^"]+)"', tm))
# 2) AIToolManager 中 case "xxx" (ToolDefinition)
case_matches = set(re.findall(r'case "([^"]+)":', tm))

# 3) AgentService 中 registerToolSchema
as_path = os.path.join(base, "ai", "service", "AgentService.java")
with open(as_path, encoding="utf-8") as f:
    asrc = f.read()
schema_matches = set(re.findall(r'registerToolSchema\("([^"]+)"', asrc))

# 4) 工具类文件（实现类）
tool_dir = os.path.join(base, "ai", "tool")
impl_files = set(os.path.splitext(f)[0] for f in os.listdir(tool_dir) if f.endswith(".java"))

print(f"{'TOOL':<25}{'factory':<10}{'case':<10}{'schema':<10}{'CLASS'}")
print("-" * 70)
for t in tools:
    in_factory = t in factory_matches
    in_case = t in case_matches
    in_schema = t in schema_matches
    # 查找类文件（按工具名匹配驼峰）
    cls = "?"
    for f in impl_files:
        if f.lower().replace("tool","") in t or t in f.lower().replace("tool",""):
            cls = f
            break
    flag = "OK" if (in_factory or in_schema) else "MISSING"
    print(f"{t:<25}{('Y' if in_factory else '-'):<10}{('Y' if in_case else '-'):<10}{('Y' if in_schema else '-'):<10}{cls} {flag}")

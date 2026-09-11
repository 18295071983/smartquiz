import json, io

path = r"D:\qzq\smartquiz\src\main\assets\providers.json"
with io.open(path, encoding="utf-8") as f:
    data = json.load(f)

# 已知的差异化能力（按厂商官方能力，保守声明；组织者最终核验）
# agent: 有独立 Agent/Responses 类接口；webSearch: 有联网搜索功能
known = {
    "openai":        {"agent": True, "webSearch": True, "vision": True},
    "dashscope":     {"agent": False, "webSearch": True, "vision": True},
    "zhipu":         {"agent": True, "webSearch": True, "vision": True},   # GLM Agent / web_search 工具 / glm-4v
    "hunyuan":       {"agent": False, "webSearch": True, "vision": True},  # 混元有联网搜索
    "xfyun":         {"agent": False, "webSearch": True, "vision": True},   # 星火联网搜索 / spark-vision
    "volcengine":    {"agent": False, "webSearch": True, "vision": True},   # 豆包联网搜索 / 视觉
    "baidu":         {"agent": False, "webSearch": True, "vision": True},   # 千帆搜索 / ernie 视觉
    "minimax":       {"agent": False, "webSearch": False, "vision": True},  # M2 多模态；搜索无
    "xiaomimimo":    {"agent": False, "webSearch": False, "vision": True},  # MiMo-VL
    "moonshot":      {"agent": False, "webSearch": True, "vision": True},   # Kimi 内置搜索 / 视觉
    "siliconflow":   {"agent": False, "webSearch": False, "vision": True},  # 平台有视觉模型
    "baichuan":      {"agent": False, "webSearch": False, "vision": False},
    "deepseek":      {"agent": False, "webSearch": False, "vision": False}, # 纯文本，无搜索
    "anthropic":     {"agent": True, "webSearch": False, "vision": True},   # Messages + tool use（Agent 模式）
    "gemini":        {"agent": False, "webSearch": True, "vision": True},   # google search grounding
    "groq":          {"agent": False, "webSearch": False, "vision": True},  # llama vision
    "together":      {"agent": False, "webSearch": False, "vision": True},  # 平台有视觉模型
    "ollama":        {"agent": False, "webSearch": False, "vision": True},  # 本地 llava/qwen2.5vl 等
    "stepfun":       {"agent": False, "webSearch": True, "vision": True},   # 阶跃有搜索与视觉
    "yi":            {"agent": False, "webSearch": False, "vision": False},
    "sensenova":     {"agent": False, "webSearch": False, "vision": True},
    "infini":        {"agent": False, "webSearch": False, "vision": False},
    "yanxi":         {"agent": False, "webSearch": False, "vision": False},
    "zhinao":        {"agent": False, "webSearch": True, "vision": False},  # 360 搜索增强
    "huawei":        {"agent": False, "webSearch": False, "vision": True},  # openPangu 多模态
    "bailian-workspace": {"agent": False, "webSearch": True, "vision": True},
    "302ai":         {"agent": False, "webSearch": False, "vision": True},  # 聚合平台按需
}

def svc(enabled, endpoint=None, param=None):
    o = {"enabled": bool(enabled)}
    if endpoint: o["endpoint"] = endpoint
    if param: o["param"] = param
    return o

for p in data["providers"]:
    pid = p["id"]
    k = known.get(pid, {})
    services = {"chat": {"enabled": True}}
    # functionCalling: OpenAI 兼容端点默认支持工具调用
    services["functionCalling"] = {"enabled": True}
    # agent
    services["agent"] = svc(k.get("agent", False), "/v1/responses" if pid == "openai" else None)
    # vision
    services["vision"] = svc(k.get("vision", False))
    # webSearch
    services["webSearch"] = svc(k.get("webSearch", False), None, "web_search")
    # embedding / rerank / imageGen / tts / asr：按现有非空模型字段
    services["embedding"] = svc(bool(p.get("embeddingModel")))
    services["rerank"] = svc(bool(p.get("rerankModel")))
    services["imageGen"] = svc(bool(p.get("imageModel")))
    services["tts"] = svc(bool(p.get("ttsModel")))
    services["asr"] = svc(bool(p.get("asrModel")))
    p["services"] = services

# 更新 note
data["note"] = "在线模型服务商配置表 v3：在 v2 基础上为每家服务商新增 services 服务能力声明（chat/agent/vision/webSearch/embedding/rerank/imageGen/functionCalling/tts/asr），支持 agent 接口、多模态、网络搜索等差异化服务的配置与 UI 设置。agent/webSearch/vision 按已知官方能力声明（最终以调研核验为准，未确认项 enabled=false 可在 note 标注）。外部更新：覆盖 filesDir/providers.json 后 reload 即生效。"

with io.open(path, "w", encoding="utf-8") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)

print("OK providers:", len(data["providers"]))

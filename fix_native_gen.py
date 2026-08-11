#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import os, sys

filepath = os.path.join(os.path.dirname(__file__), 'src', 'main', 'java', 'com', 'oilquiz', 'app', 'ai', 'agent', 'UnifiedAgentEngine.java')

with open(filepath, 'r', encoding='utf-8', errors='replace') as f:
    lines = f.readlines()

# Lines 1008-1024 (1-indexed) have old code with parsedReasoning/parsedToolCalls
# We need to replace them with new code that uses reasoningHolder/toolCallsHolder

new_block = [
    "        // \u5982\u679c C++ \u5c42\u6ca1\u6709\u89e3\u6790\u5230\u63a8\u7406\u5185\u5bb9\uff0c\u68c0\u67e5\u6a21\u578b\u662f\u5426\u81ea\u5df1\u8f93\u51fa\u4e86 \u003Cthink\u003E...\u003C/think\u003E \u6807\u7b7e\n",
    "        if (thinking.isEmpty()) {\n",
    "            String[] split = splitThinkingAndContent(content);\n",
    "            content = split[0];\n",
    "            thinking = split[1];\n",
    "        } else {\n",
    "            // \u6709 C++ \u5c42\u89e3\u6790\u7684\u63a8\u7406\u5185\u5bb9\uff0c\u4e5f\u4ece content \u4e2d\u79fb\u9664 \u003Cthink\u003E \u6807\u7b7e\n",
    "            String[] split = splitThinkingAndContent(content);\n",
    "            content = split[0];\n",
    "        }\n",
    "\n",
    "        // \u5c06 ToolCallInfo \u8f6c\u6362\u4e3a NativeToolCall\uff08\u4fdd\u6301\u5185\u90e8\u63a5\u53e3\u517c\u5bb9\uff09\n",
    "        List<NativeToolCall> nativeToolCalls = new ArrayList<>();\n",
    "        if (!toolCallsHolder.isEmpty()) {\n",
    "            for (OnlineInferenceService.ToolCallInfo tc : toolCallsHolder) {\n",
    "                String argsStr = tc.arguments != null ? tc.arguments : \"{}\";\n",
    "                JSONObject argsJson;\n",
    "                try {\n",
    "                    argsJson = new JSONObject(argsStr);\n",
    "                } catch (Exception e) {\n",
    "                    argsJson = new JSONObject();\n",
    "                    argsStr = \"{}\";\n",
    "                }\n",
    "                nativeToolCalls.add(new NativeToolCall(tc.id, tc.name, argsStr, argsJson));\n",
    "            }\n",
    "        }\n",
    "\n",
    "        AILogger.i(TAG, \"nativeGenerateSyncWithThinking result: contentLen=\" + content.length()\n",
    "                + \" thinkingLen=\" + thinking.length()\n",
    "                + \" toolCalls=\" + nativeToolCalls.size());\n",
    "\n",
    "        return new NativeGenerateResult(content, thinking, nativeToolCalls);\n",
    "    }\n",
]

# Replace lines 1008-1025 (1-indexed) -> indices 1007-1024
lines[1007:1025] = new_block

with open(filepath, 'w', encoding='utf-8') as f:
    f.writelines(lines)

print("SUCCESS: Fixed nativeGenerateSyncWithThinking method")

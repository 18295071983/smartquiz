#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""修改提示词，明确action选择，按使用场景分组"""

file_path = r"D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\agent\software\engine\AgentLoopEngine.java"

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# 修改1：网络搜索工具，按使用场景分组
old1 = '''        sb.append("1. 网络搜索(network_search)：\\n");
        sb.append("• action=search(query,limit=5)：关键词搜索，返回标题/链接/摘要列表\\n");
        sb.append("• action=ask(question,model=concise)：智能问答，直接返回答案+引用来源\\n");
        sb.append("• action=read_url(url)：读取指定网页正文内容\\n");
        sb.append("• action=get_webpage(url)：获取网页原始HTML内容\\n");
        sb.append("• action=extract_info(url)：从网页提取关键信息\\n");
        sb.append("• action=summarize(url)：生成网页摘要\\n");
        sb.append("• action=search_and_read(query)：搜索后自动读取第一条结果正文\\n");
        sb.append("• action=get_dynamic_content(url)：获取JS渲染的动态网页内容\\n");
        sb.append("• action=smart_search(query,maxResults=5)：智能搜索，自动读取详情生成摘要\\n");
        sb.append("• action=smart_read(results)：对已有搜索结果逐条读正文生成摘要\\n");
        sb.append("简单查询用search/ask；需要详情用read_url/smart_search；动态网页用get_dynamic_content。\\n\\n");'''

new1 = '''        sb.append("1. 网络搜索(network_search)：\\n");
        sb.append("【搜索信息】\\n");
        sb.append("• action=search(query,limit=5)：关键词搜索，返回标题/链接/摘要列表（要找资料用这个）\\n");
        sb.append("• action=ask(question,model=concise)：智能问答，直接返回答案+引用来源（要直接问答案用这个）\\n");
        sb.append("【读取网页】\\n");
        sb.append("• action=read_url(url)：读取指定网页正文内容（普通网页用这个）\\n");
        sb.append("• action=get_dynamic_content(url)：获取JS渲染的动态网页内容（网页内容是动态加载的用这个）\\n");
        sb.append("【搜索+读取一体化】\\n");
        sb.append("• action=smart_search(query,maxResults=5)：智能搜索，自动读取详情生成摘要（要搜索并自动整理结果用这个）\\n");
        sb.append("• action=search_and_read(query)：搜索后自动读取第一条结果正文\\n");
        sb.append("【网页处理高级功能】\\n");
        sb.append("• action=get_webpage(url)：获取网页原始HTML内容\\n");
        sb.append("• action=extract_info(url)：从网页提取关键信息\\n");
        sb.append("• action=summarize(url)：生成网页摘要\\n");
        sb.append("• action=smart_read(results)：对已有搜索结果逐条读正文生成摘要\\n");
        sb.append("选择建议：要搜索→search；要直接问答案→ask；要读网页→read_url；要读动态网页→get_dynamic_content；要搜索并自动整理→smart_search。\\n\\n");'''

if old1 in content:
    content = content.replace(old1, new1, 1)
    print("Fixed issue 1: network search grouped by scenario")
else:
    print("WARNING: Could not find issue 1 pattern")

# 修改2：天气工具，明确all和one_call的区别
old2 = '''        sb.append("• action=all：全部天气信息\\n");
        sb.append("• action=one_call：详细天气（当前+逐时+逐日+警报+日出日落，必须传lat+lon，不支持city）\\n");'''

new2 = '''        sb.append("• action=all：全部基础天气信息（一次返回current+forecast+hourly+air_quality+alerts+indices，支持city或经纬度）\\n");
        sb.append("• action=one_call：和风官方详细天气接口（当前+逐时+逐日+警报+日出日落，数据更详细，必须传lat+lon，不支持city）\\n");'''

if old2 in content:
    content = content.replace(old2, new2, 1)
    print("Fixed issue 2: weather all vs one_call clarified")
else:
    print("WARNING: Could not find issue 2 pattern")

# 修改3：时间日期工具，明确now和date/time的区别
old3 = '''        sb.append("3. 时间日期(time_date)：\\n");
        sb.append("• action=now：当前日期时间\\n");
        sb.append("• action=date：仅日期\\n");
        sb.append("• action=time：仅时间\\n");
        sb.append("• action=weekday：星期几\\n");
        sb.append("参数：action按需求选择，无其他参数。\\n\\n");'''

new3 = '''        sb.append("3. 时间日期(time_date)：\\n");
        sb.append("• action=now：当前完整日期时间（大部分情况用这个就行）\\n");
        sb.append("• action=date：仅日期（只要年月日时用）\\n");
        sb.append("• action=time：仅时间（只要时分秒时用）\\n");
        sb.append("• action=weekday：星期几（只要星期时用）\\n");
        sb.append("参数：action按需求选择，无其他参数。不确定用哪个就用now。\\n\\n");'''

if old3 in content:
    content = content.replace(old3, new3, 1)
    print("Fixed issue 3: time_date now vs date/time clarified")
else:
    print("WARNING: Could not find issue 3 pattern")

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Done!")

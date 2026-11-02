package com.oilquiz.app.ui.activity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 模型相关页面的入口架构不变量（2026-11-02 MODEL-HUB-ONE-PLACE）。
 *
 * <p><b>WHY</b>：仓库里一度有四个页面各挂一份"当前模型 + 模型列表"——
 * {@code AICenterActivity}（在线模型卡 + 导入模型卡，两张卡头部还各有一个开关）、
 * {@code ModelSelectorActivity}（本地 + 在线列表）、{@code ModelCenterActivity}（在线注册 + 用量）、
 * {@code ApiConfigActivity}（服务商密钥）。结果是同一个动作有 3~4 个表面，其中最好看的模型中心
 * <b>选不了本地模型</b>（它只有在线注册 Tab），而聊天页的模型入口当时正指着它；
 * AI 中心那两个开关的监听只做"互相取消勾选"，既不写偏好也不调路由（路由只看哪个模型激活，
 * 见 ROUTE-UNIFIED），属于"看着能换引擎、其实什么都换不了"的假控件。</p>
 *
 * <p><b>收敛后的口径</b>（本文件逐条钉住）：
 * ① 唯一的模型切换页 = {@code ModelSelectorActivity}（本地热切换 + 在线点选 + 添加 + 导入 +
 * 一键初始化 + 语音模型），聊天页与 AI 中心的模型入口都指它；
 * ② {@code ModelCenterActivity} 只当"在线注册 + 用量"，并必须一跳回到①；①也一跳得到用量；
 * ③ {@code AICenterActivity} 不再挂模型列表、不再有模式开关，只显示当前模型 + 一跳；
 * ④ 界面与报错文案里不许再出现不存在的页面名（「模型管理页」「模型设置」），
 *    也不许再用会被读成"第三种聊天模型"的「专用模型」字样（语音那条已改名「语音模型」）。</p>
 */
public class ModelEntryArchitectureTest {

    private static String read(String rel) throws IOException {
        Path p = Paths.get(rel);
        assertTrue("文件不存在：" + rel, Files.exists(p));
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    /** ③ AI 中心不得再挂模型列表与假开关 */
    @Test
    public void aiCenterHasNoModelListsOrFakeSwitches() throws Exception {
        String layout = read("src/main/res/layout/activity_ai_center.xml");
        String java = read("src/main/java/com/oilquiz/app/ui/activity/AICenterActivity.java");
        for (String dead : new String[]{"switch_online_mode", "switch_local_mode",
                "online_models_recycler", "models_recycler"}) {
            assertFalse("AI 中心布局又挂回了 " + dead + "（列表与假开关已删，切换只去「模型」页）",
                    layout.contains("@+id/" + dead));
        }
        for (String cls : new String[]{"OnlineModelAdapter", "ModelAdapter", "SwitchMaterial"}) {
            assertFalse("AI 中心又自己实例化了 " + cls, java.contains(cls + "("));
        }
        assertFalse("AI 中心不得再写 setOnCheckedChangeListener 造互斥开关",
                java.contains("setOnCheckedChangeListener((buttonView, isChecked)"));
        assertTrue("AI 中心应保留「去模型页切换」这一跳",
                java.contains("R.id.btn_go_model_page") && java.contains("ModelSelectorActivity.class"));
    }

    /** ①+④ 模型中心必须一跳能到"能选本地模型"的那页，且不再有「专用模型」这种叫法 */
    @Test
    public void modelCenterHopsToThePageThatCanSwitchLocalModels() throws Exception {
        String layout = read("src/main/res/layout/activity_model_center.xml");
        String java = read("src/main/java/com/oilquiz/app/ui/activity/ModelCenterActivity.java");
        assertTrue("模型中心缺「🔀 切换模型」一跳（它自己选不了本地模型，用户必须能一步走到能选的页）",
                layout.contains("@+id/btn_goto_model_page"));
        assertTrue("那一跳没接到「模型」页", java.contains("btn_goto_model_page"));
        assertTrue("语音那条入口应叫「语音模型」，别再写「专用模型」",
                layout.contains("语音模型") && !layout.contains("android:text=\"专用模型\""));
    }

    /** ①+② 模型页：正名为「模型」、有反向一跳看用量、语音区改名 */
    @Test
    public void modelPageIsNamedAndLinksBackToUsage() throws Exception {
        String layout = read("src/main/res/layout/activity_model_selector.xml");
        String java = read("src/main/java/com/oilquiz/app/ui/activity/ModelSelectorActivity.java");
        assertTrue("页面标题应为「模型」（它同时管本地与在线，叫「模型选择」容易让人以为只有在线）",
                layout.contains("app:title=\"模型\""));
        assertTrue("缺反向一跳：从模型页应能一步去「用量与注册」", layout.contains("@+id/btn_goto_model_center"));
        assertTrue("那一跳没接到模型中心", java.contains("btn_goto_model_center"));
        assertFalse("语音区不得再叫「功能专用模型」（会被读成第三种聊天模型）",
                layout.contains("功能专用模型"));
    }

    /** ① 聊天页的模型入口只能指"能换本地模型"的那页 */
    @Test
    public void chatEntriesPointAtTheSwitchablePage() throws Exception {
        String chat = read("src/main/java/com/oilquiz/app/ui/activity/AIChatActivity.java");
        int toSelector = count(chat, "ModelSelectorActivity.class");
        assertTrue("聊天页应有两处模型入口（空状态首屏 + 工具抽屉）都跳「模型」页，实际 " + toSelector,
                toSelector >= 2);
        assertEquals("聊天页不得再把模型入口指回模型中心（它选不了本地模型）",
                0, count(chat, "ModelCenterActivity"));
    }

    /** ④ 报错与提示里不许出现不存在的页面名 */
    @Test
    public void copyNeverNamesPagesThatDoNotExist() throws Exception {
        String[] files = {
                "src/main/java/com/oilquiz/app/ui/activity/AIChatActivity.java",
                "src/main/java/com/oilquiz/app/ai/knowledge/KnowledgeBaseManager.java",
                "src/main/java/com/oilquiz/app/ai/tool/DashscopeMediaTool.java",
                "src/main/res/layout/dialog_ocr_model_selector.xml",
                "src/main/res/layout/dialog_speech_model_selector.xml",
                "src/main/res/layout/dialog_voice_feature_info.xml",
        };
        for (String f : files) {
            String t = read(f);
            assertFalse(f + " 还指向不存在的「模型管理页」", t.contains("模型管理页"));
            assertFalse(f + " 还指向不存在的「模型设置」页", t.contains("「模型设置」"));
        }
    }

    /** 那个零引用的旧布局（标题还叫「AI模型管理」）不该被重新发明回来 */
    @Test
    public void orphanAiModelManagerLayoutStaysGone() {
        assertFalse("activity_ai_model_manager.xml 是零引用死布局（无代码、无清单引用），已删；"
                        + "若要恢复必须先给出真实入口，别再留一份没人用的模型页",
                Files.exists(Paths.get("src/main/res/layout/activity_ai_model_manager.xml")));
    }

    private static int count(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    private static void assertEquals(String msg, int expected, int actual) {
        assertTrue(msg + "（期望 " + expected + "，实际 " + actual + "）", expected == actual);
    }
}

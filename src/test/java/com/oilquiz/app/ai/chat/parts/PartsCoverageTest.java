package com.oilquiz.app.ai.chat.parts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * parts 覆盖补齐（P2.5）：错误 part 独立渲染 + §4.6 工具卡归属所依赖的 "attached part"
 * 引擎能力。用假 renderer 在真实 ViewGroup 上钉死：companion part 不建自己的视图，
 * 写进其 anchor（按 id 关联，模拟 tool_result→tool_call）视图；内容变化才重绑；
 * anchor 不在则跳过；anchor 移除则其视图被释放、companion 残留指纹被清理。
 */
@RunWith(RobolectricTestRunner.class)
public class PartsCoverageTest {

    private Context ctx;
    private LinearLayout container;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        container = new LinearLayout(ctx);
    }

    // ---- 错误渲染器：独立视图，走原生 markdown TextView ----

    @Test
    public void errorRendererOwnsViewAndMatchesType() {
        ErrorPartRenderer r = new ErrorPartRenderer();
        assertTrue(r.canRender(error("e1", "工具执行失败")));
        assertFalse(r.canRender(text("t1", "正文")));

        View v = r.createView(new TestHost(ctx), container);
        assertTrue(v instanceof TextView);
        assertNotNull(PartRendererRegistry.createDefault().resolve(error("e1", "boom")));
    }

    // ---- attached part：§4.6 工具卡归属 ----

    @Test
    public void attachedPartBindsIntoAnchorAndOwnsNoView() {
        TurnPartsHost host = hostWith(new AnchorRenderer(), new CompanionRenderer());
        host.bind(Arrays.asList(text("t1", "正文1"), companion("r1", "t1", "结果A")));

        assertEquals("companion 不建独立视图，只剩 anchor", 1, container.getChildCount());
        assertEquals(1, host.renderedCount());
        assertFalse("companion 不应出现在视图表", host.views().containsKey("r1"));
        assertEquals("结果写进了 anchor 视图", "comp:结果A", host.views().get("t1").getTag());
    }

    @Test
    public void attachedPartRebindsOnlyWhenItsContentChanges() {
        TurnPartsHost host = hostWith(new AnchorRenderer(), new CompanionRenderer());
        host.bind(Arrays.asList(text("t1", "正文1"), companion("r1", "t1", "结果A")));
        int bindsBefore = host.bindCount;

        host.bind(Arrays.asList(text("t1", "正文1"), companion("r1", "t1", "结果A")));
        assertEquals("同指纹不重复写 anchor", bindsBefore, host.bindCount);

        host.bind(Arrays.asList(text("t1", "正文1"), companion("r1", "t1", "结果B")));
        assertEquals(bindsBefore + 1, host.bindCount);
        assertEquals("comp:结果B", host.views().get("t1").getTag());
    }

    @Test
    public void multipleCompanionsShareOneAnchorView() {
        TurnPartsHost host = hostWith(new AnchorRenderer(), new CompanionRenderer());
        host.bind(Arrays.asList(
                text("t1", "正文"), companion("r1", "t1", "A"), companion("r2", "t1", "B")));

        assertEquals("两个 companion 仍只有一个 anchor 视图", 1, container.getChildCount());
        List<View> views = new ArrayList<>();
        for (int i = 0; i < container.getChildCount(); i++) views.add(container.getChildAt(i));
        assertSame(host.views().get("t1"), views.get(0));
    }

    @Test
    public void anchorMissingSkipsCompanion_anchorRemovedReleasesView() {
        TurnPartsHost host = hostWith(new AnchorRenderer(), new CompanionRenderer());
        host.bind(Arrays.asList(text("t1", "正文"), companion("r1", "t1", "结果A")));

        // anchor 没了：companion 因找不到 anchor 被跳过，不建视图；anchor 视图释放。
        host.bind(Arrays.asList(companion("r1", "t1", "结果A")));
        assertEquals(0, container.getChildCount());
        assertEquals(0, host.renderedCount());
        assertEquals("anchor 视图释放一次", 1, host.releaseCount);

        // 换一个新 anchor，companion 改挂它：残留指纹已清理，能正常首次写入。
        host.bind(Arrays.asList(text("t2", "新正文"), companion("r1", "t2", "结果A")));
        assertEquals("comp:结果A", host.views().get("t2").getTag());
    }

    @Test
    public void anchorRecreatedForcesCompanionRebind() {
        TurnPartsHost host = hostWith(new AnchorRenderer(), new CompanionRenderer());
        host.bind(Arrays.asList(text("t1", "正文"), companion("r1", "t1", "结果A")));
        assertTrue(host.views().containsKey("t1"));

        // 用同 id 换成 reasoning：AnchorRenderer 不认 → t1 文本视图被释放，companion 因 anchor 缺失跳过。
        host.bind(Arrays.asList(reasoning("t1", "思考"), companion("r1", "t1", "结果A")));
        assertFalse("t1 文本视图已释放", host.views().containsKey("t1"));
        assertEquals(0, container.getChildCount());
        assertTrue(host.releaseCount >= 1);

        // 同 id 再换回 text：anchor 全新创建 → 强制把 companion 结果写回新视图。
        host.bind(Arrays.asList(text("t1", "正文"), companion("r1", "t1", "结果A")));
        assertEquals("comp:结果A", host.views().get("t1").getTag());
    }

    @Test
    public void toolResultAttachesIntoToolCard_noSecondCard() {
        FakeFactory factory = new FakeFactory();
        ToolCallPartRenderer call = new ToolCallPartRenderer(factory);
        TurnPartsHost host = hostWith(call, new ToolResultPartRenderer(call.componentFactory()));

        AssistantTurn.Part tc = AssistantTurn.Part.toolCall(
                "R1:tool:c1", "c1", "weather", "{\"city\":\"北京\"}", "running", 1L);
        AssistantTurn.Part tr = new AssistantTurn.Part();
        tr.id = "R1:tool:c1:result";
        tr.type = AssistantTurn.Part.TYPE_TOOL_RESULT;
        tr.toolCallId = "c1";
        tr.result = "晴天 25度";

        host.bind(Arrays.asList(tc, tr));

        // §4.6：只有一张工具卡，result 不建第二张卡。
        assertEquals("工具卡只建一个宿主视图", 1, container.getChildCount());
        assertTrue(host.views().containsKey("R1:tool:c1"));
        assertFalse("tool_result 不应有独立视图", host.views().containsKey("R1:tool:c1:result"));

        // 工厂被调用两次：先建卡（result 空），再合并 result 原地重渲染同一张卡。
        assertEquals(2, factory.created.size());
        assertEquals("", factory.created.get(0).optString("result"));
        assertEquals("晴天 25度", factory.created.get(1).optString("result"));
        assertEquals("weather", factory.created.get(1).optString("toolName"));
    }

    @Test
    public void identicalToolPartsDoNotRebind() {
        FakeFactory factory = new FakeFactory();
        ToolCallPartRenderer call = new ToolCallPartRenderer(factory);
        TurnPartsHost host = hostWith(call, new ToolResultPartRenderer(call.componentFactory()));

        AssistantTurn.Part tc = AssistantTurn.Part.toolCall(
                "c1", "c1", "weather", "{}", "done", 1L);
        AssistantTurn.Part tr = new AssistantTurn.Part();
        tr.id = "c1-result";
        tr.type = AssistantTurn.Part.TYPE_TOOL_RESULT;
        tr.toolCallId = "c1";
        tr.result = "ok";

        host.bind(Arrays.asList(tc, tr));
        int created = factory.created.size();
        host.bind(Arrays.asList(tc, tr)); // 完全相同
        assertEquals("同内容不重复渲染卡片", created, factory.created.size());
    }

    /** 假组件工厂：在每次 create 时快照 props，返回空壳视图，不触真实 ToolCallCardView。 */
    private static final class FakeFactory implements ToolCallPartRenderer.ComponentFactory {
        final java.util.List<org.json.JSONObject> created = new ArrayList<>();
        @Override
        public View create(Context context, ComponentData data) {
            try {
                created.add(new org.json.JSONObject(data.props.toString())); // 快照，避免共享引用被后续合并改写
            } catch (org.json.JSONException e) {
                created.add(new org.json.JSONObject());
            }
            View v = new View(context);
            v.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return v;
        }
    }

    private TurnPartsHost hostWith(PartRenderer... renderers) {
        PartRendererRegistry reg = new PartRendererRegistry();
        for (PartRenderer r : renderers) reg.register(r);
        return new TurnPartsHost(container, reg, new TestHost(ctx));
    }

    private static AssistantTurn.Part text(String id, String t) {
        return AssistantTurn.Part.text(id, t, 1L);
    }

    private static AssistantTurn.Part reasoning(String id, String t) {
        return AssistantTurn.Part.reasoning(id, t, 1L);
    }

    private static AssistantTurn.Part error(String id, String t) {
        AssistantTurn.Part p = new AssistantTurn.Part();
        p.id = id;
        p.type = AssistantTurn.Part.TYPE_ERROR;
        p.text = t;
        return p;
    }

    /** companion：type=companion，args=其 anchor part id，result=内容。 */
    private static AssistantTurn.Part companion(String id, String anchorId, String result) {
        AssistantTurn.Part p = new AssistantTurn.Part();
        p.id = id;
        p.type = "companion";
        p.args = anchorId;
        p.result = result;
        return p;
    }

    /** 拥有独立视图的 anchor：text 类型。 */
    private static final class AnchorRenderer implements PartRenderer {
        @Override
        public boolean canRender(AssistantTurn.Part part) {
            return part != null && AssistantTurn.Part.TYPE_TEXT.equals(part.type);
        }
        @Override
        public View createView(PartHost host, ViewGroup parent) {
            View v = new View(host.context());
            v.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return v;
        }
        @Override
        public void bindView(View view, AssistantTurn.Part part, PartHost host) {
            // anchor 自身只在 companion 尚未写入时打个初始 tag。
            if (view.getTag() == null) view.setTag("anchor:" + part.text);
        }
        @Override
        public void releaseView(View view) {
        }
    }

    /** attached：按 args 里的 anchor id 写进对应视图，不建自己的视图。 */
    private static final class CompanionRenderer implements PartRenderer {
        @Override
        public boolean canRender(AssistantTurn.Part part) {
            return part != null && "companion".equals(part.type);
        }
        @Override
        public String anchorIdFor(AssistantTurn.Part part, List<AssistantTurn.Part> ordered) {
            return part.args != null && !part.args.isEmpty() ? part.args : null;
        }
        @Override
        public View createView(PartHost host, ViewGroup parent) {
            throw new AssertionError("attached part 不应被建视图");
        }
        @Override
        public void bindView(View view, AssistantTurn.Part part, PartHost host) {
            view.setTag("comp:" + part.result); // 写进 anchor 视图
        }
        @Override
        public void releaseView(View view) {
        }
    }

    private static final class TestHost implements PartHost {
        private final Context ctx;
        TestHost(Context ctx) {
            this.ctx = ctx;
        }
        @Override
        public Context context() {
            return ctx;
        }
        @Override
        public int availableWidth() {
            return 1080;
        }
        @Override
        public ChatMessage message() {
            return null;
        }
        @Override
        public boolean webMarkdownEnabled() {
            return false;
        }
        @Override
        public boolean dark() {
            return false;
        }
        @Override
        public String markdownThemeVars() {
            return "";
        }
        @Override
        public void renderMarkdown(android.widget.TextView target, String markdown) {
            target.setText(markdown);
        }
        @Override
        public void onApprovalDecision(String partId, String toolCallId, boolean approved) {
        }
        @Override
        public void invalidateSize() {
        }
    }
}

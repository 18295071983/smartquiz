package com.oilquiz.app.infra;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.tool.GitCredentials;
import com.oilquiz.app.ai.tool.ToolPermissions;
import com.oilquiz.app.theme.ThemeColors;
import com.oilquiz.app.theme.Tokens;
import com.oilquiz.app.ui.UiActions;
import com.oilquiz.app.ui.UiFx;
import com.oilquiz.app.ui.UiWidgets;

import java.util.Map;
import java.util.Set;

/**
 * Agent 设置页（2026-11-16）：把"只差最后一公里"的两块能力接上界面。
 *
 * <ol>
 *   <li><b>Git 凭据</b>：保存/删除凭据别名（用户名 + token），落盘走
 *       {@link GitCredentials} 的加密存储；模型 push 时只传别名，令牌不进对话；</li>
 *   <li><b>工具权限分级</b>：展示 57 个工具的五个级别（只读/本地写/执行/网络/危险），
 *       让用户看清"哪些会问、哪些不会"。</li>
 * </ol>
 *
 * <p>注意：本页只做"展示 + 保存"，<b>不改执行链路</b> —— 权限拦截的接入点仍留在
 * {@code AIToolManager.executeTool} 的 TODO 里（按约定先不接线）。</p>
 */
public class AgentSettingsActivity extends AppCompatActivity {

    private LinearLayout credentialBox;
    private LinearLayout permissionBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(ThemeColors.get(this, R.color.background));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Tokens.space(this, Tokens.SPACE_XL);
        root.setPadding(pad, pad, pad, Tokens.space(this, Tokens.SPACE_3XL));
        root.setBackgroundColor(ThemeColors.get(this, R.color.background));

        TextView title = new TextView(this);
        title.setText("Agent 设置");
        Tokens.applyText(title, Tokens.TEXT_TITLE, Tokens.Weight.SEMIBOLD,
                ThemeColors.get(this, R.color.text_primary));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Git 凭据（加密保存）· 工具权限分级");
        Tokens.applyText(sub, Tokens.TEXT_FOOTNOTE, Tokens.Weight.REGULAR,
                ThemeColors.get(this, R.color.text_secondary));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.setMargins(0, Tokens.space(this, Tokens.SPACE_XS), 0,
                Tokens.space(this, Tokens.SPACE_XL));
        sub.setLayoutParams(subLp);
        root.addView(sub);

        // ---------- Git 凭据 ----------
        root.addView(section("Git 凭据"));
        TextView credHint = new TextView(this);
        credHint.setText("保存后，模型调用 git push 只需传别名（如 github），"
                + "令牌不会出现在对话或日志里。");
        Tokens.applyText(credHint, Tokens.TEXT_FOOTNOTE, Tokens.Weight.REGULAR,
                ThemeColors.get(this, R.color.text_secondary));
        LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chLp.setMargins(0, 0, 0, Tokens.space(this, Tokens.SPACE_MD));
        credHint.setLayoutParams(chLp);
        root.addView(credHint);

        credentialBox = new LinearLayout(this);
        credentialBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(credentialBox);

        TextView addBtn = pill("＋ 添加凭据");
        UiFx.click(addBtn, this::showAddCredentialDialog);
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.setMargins(0, 0, 0, Tokens.space(this, Tokens.SPACE_XL));
        addBtn.setLayoutParams(addLp);
        root.addView(addBtn);

        // ---------- 工具权限 ----------
        root.addView(section("工具权限分级"));
        TextView permHint = new TextView(this);
        permHint.setText("分级表已就绪，但**执行链路的拦截尚未接线**（按约定先不接）："
                + "本页只展示归类，模型调用时仍一律放行。");
        Tokens.applyText(permHint, Tokens.TEXT_FOOTNOTE, Tokens.Weight.REGULAR,
                ThemeColors.get(this, R.color.text_secondary));
        LinearLayout.LayoutParams phLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        phLp.setMargins(0, 0, 0, Tokens.space(this, Tokens.SPACE_MD));
        permHint.setLayoutParams(phLp);
        root.addView(permHint);

        permissionBox = new LinearLayout(this);
        permissionBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(permissionBox);

        scroll.addView(root);
        android.widget.FrameLayout holder = new android.widget.FrameLayout(this);
        holder.setBackgroundColor(ThemeColors.get(this, R.color.background));
        holder.addView(scroll, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(holder);

        renderCredentials();
        renderPermissions();
    }

    // ------------------------------------------------------------------
    // Git 凭据
    // ------------------------------------------------------------------

    private void renderCredentials() {
        credentialBox.removeAllViews();
        Map<String, String> aliases = GitCredentials.listAliases(this);
        if (aliases.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有保存凭据（push/pull 前需要先添加一个）");
            Tokens.applyText(empty, Tokens.TEXT_BODY, Tokens.Weight.REGULAR,
                    ThemeColors.get(this, R.color.text_secondary));
            credentialBox.addView(empty);
            return;
        }
        for (Map.Entry<String, String> e : aliases.entrySet()) {
            final String alias = e.getKey();
            final String user = e.getValue();
            TextView row = cardRow(alias + "  ·  " + (user.isEmpty() ? "(无用户名)" : user));
            UiFx.longPress(row, () -> {
                com.oilquiz.app.ui.modal.AppDialog.builder(this)
                        .setTitle("删除凭据")
                        .setMessage("确定删除别名「" + alias + "」吗？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除", (d, w) -> {
                            GitCredentials.remove(this, alias);
                            renderCredentials();
                            Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                        })
                        .show();
            });
            UiWidgets.tooltip(row, "长按删除该凭据");
            credentialBox.addView(row);
        }
    }

    private void showAddCredentialDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Tokens.space(this, Tokens.SPACE_XL);
        box.setPadding(p, Tokens.space(this, Tokens.SPACE_LG), p, 0);

        final EditText aliasInput = simpleInput("别名（如 github）");
        final EditText userInput = simpleInput("用户名（HTTPS 一般填账号）");
        final EditText tokenInput = simpleInput("Token / 密码");
        box.addView(aliasInput);
        box.addView(userInput);
        box.addView(tokenInput);

        com.oilquiz.app.ui.modal.AppDialog.builder(this)
                .setTitle("添加 Git 凭据")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    String alias = aliasInput.getText().toString().trim();
                    String user = userInput.getText().toString().trim();
                    String token = tokenInput.getText().toString().trim();
                    if (alias.isEmpty() || token.isEmpty()) {
                        Toast.makeText(this, "别名和 Token 不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    GitCredentials.save(this, alias, user, token);
                    renderCredentials();
                    Toast.makeText(this, "已加密保存：" + alias, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    // ------------------------------------------------------------------
    // 工具权限
    // ------------------------------------------------------------------

    private void renderPermissions() {
        permissionBox.removeAllViews();
        String[] labels = {"只读（免确认）", "本地可逆写（首次确认）", "执行（每次确认）",
                "网络外发（每次确认）", "危险（每次确认 + 审计）"};
        ToolPermissions.Level[] levels = {ToolPermissions.Level.READ_ONLY,
                ToolPermissions.Level.WRITE_LOCAL, ToolPermissions.Level.EXEC,
                ToolPermissions.Level.NETWORK, ToolPermissions.Level.DANGEROUS};
        // 动态工具（create_dynamic_tool 创建）不进静态分级表，ToolPermissions.levelOf 对它们兜底按 EXEC；
        // 而设置页只遍历 LEVELS 这张静态表，于是模型自建的工具在这一页永远看不到 ——
        // R3 的验收点恰恰是"动态工具在 Agent 设置页的权限分级里可见"。这里交给
        // ToolPermissions.toolsOf(level, dynamic) 合并（纯 Java，可单测）。
        java.util.List<String> dynamicTools = java.util.Collections.emptyList();
        try {
            dynamicTools = AIToolManager.getInstance(this).getDynamicTools();
        } catch (Throwable ignored) {
            // 设置页不该因为注册表还没初始化就崩：拿不到就只显示静态分级
        }
        for (int i = 0; i < levels.length; i++) {
            Set<String> tools = ToolPermissions.toolsOf(levels[i], dynamicTools);
            String extra = (levels[i] == ToolPermissions.Level.EXEC && !dynamicTools.isEmpty())
                    ? "（含动态工具 " + dynamicTools.size() + " 个，未单独分级）" : "";
            TextView head = new TextView(this);
            head.setText(labels[i] + " · " + tools.size() + " 个" + extra);
            Tokens.applyText(head, Tokens.TEXT_BODY_LG, Tokens.Weight.SEMIBOLD,
                    ThemeColors.get(this, R.color.text_primary));
            LinearLayout.LayoutParams hlLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hlLp.setMargins(0, Tokens.space(this, Tokens.SPACE_MD), 0,
                    Tokens.space(this, Tokens.SPACE_XS));
            head.setLayoutParams(hlLp);
            permissionBox.addView(head);

            TextView list = new TextView(this);
            list.setText(String.join("、", tools));
            Tokens.applyText(list, Tokens.TEXT_LABEL, Tokens.Weight.REGULAR,
                    ThemeColors.get(this, R.color.text_secondary));
            LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            listLp.setMargins(0, 0, 0, Tokens.space(this, Tokens.SPACE_SM));
            list.setLayoutParams(listLp);
            permissionBox.addView(list);
        }
    }

    // ------------------------------------------------------------------
    // 构件
    // ------------------------------------------------------------------

    private TextView section(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        Tokens.applyText(tv, Tokens.TEXT_SUBTITLE, Tokens.Weight.SEMIBOLD,
                ThemeColors.get(this, R.color.text_primary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, Tokens.space(this, Tokens.SPACE_MD), 0,
                Tokens.space(this, Tokens.SPACE_SM));
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView cardRow(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        Tokens.applyText(tv, Tokens.TEXT_BODY_LG, Tokens.Weight.REGULAR,
                ThemeColors.get(this, R.color.text_primary));
        int p = Tokens.space(this, Tokens.SPACE_LG);
        tv.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Tokens.radius(this, Tokens.RADIUS_MD));
        bg.setColor(ThemeColors.get(this, R.color.card_background));
        bg.setStroke(1, ThemeColors.get(this, R.color.border));
        tv.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, Tokens.space(this, Tokens.SPACE_SM));
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView pill(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        Tokens.applyText(tv, Tokens.TEXT_BODY, Tokens.Weight.MEDIUM,
                ThemeColors.get(this, R.color.text_primary));
        int p = Tokens.space(this, Tokens.SPACE_MD);
        tv.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Tokens.radius(this, Tokens.RADIUS_PILL));
        bg.setColor(ThemeColors.get(this, R.color.primary));
        tv.setBackground(bg);
        tv.setTextColor(ThemeColors.get(this, R.color.on_primary));
        return tv;
    }

    private EditText simpleInput(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                Tokens.textSizePx(this, Tokens.TEXT_BODY_LG));
        et.setTextColor(ThemeColors.get(this, R.color.text_primary));
        et.setHintTextColor(ThemeColors.get(this, R.color.text_secondary));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, Tokens.space(this, Tokens.SPACE_SM));
        et.setLayoutParams(lp);
        return et;
    }
}

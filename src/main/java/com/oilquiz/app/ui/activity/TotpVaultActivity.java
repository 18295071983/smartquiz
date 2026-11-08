package com.oilquiz.app.ui.activity;

import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import com.oilquiz.app.R;
import com.oilquiz.app.security.totp.TotpAccount;
import com.oilquiz.app.security.totp.TotpGenerator;
import com.oilquiz.app.security.totp.TotpUri;
import com.oilquiz.app.security.totp.TotpVault;
import com.oilquiz.app.theme.ThemeColors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置通用 TOTP 保险库。
 *
 * <p>安全约束：</p>
 * <ul>
 *   <li>仅本地；密钥经 Android Keystore AES-GCM 加密，失败不回退明文；</li>
 *   <li>不接 AI/Agent、不写日志、不云同步；</li>
 *   <li>FLAG_SECURE 禁止截屏；打开时验证设备锁屏；</li>
 *   <li>不自动填充/不把 secret 暴露到 UI。</li>
 * </ul>
 */
public class TotpVaultActivity extends AppCompatActivity {

    private static final int REQ_UNLOCK = 0x7A11;
    private static final long TICK_MS = 1000L;

    private TotpVault vault;
    private LinearLayout listContainer;
    private TextView statusText;
    private boolean unlocked = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, CodeHolder> holders = new LinkedHashMap<>();

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!unlocked) return;
            refreshCodes();
            handler.postDelayed(this, TICK_MS);
        }
    };

    private final ActivityResultLauncher<ScanOptions> scanLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (result.getContents() == null) return;
                try {
                    TotpAccount account = TotpUri.parse(result.getContents());
                    addAccount(account);
                } catch (IllegalArgumentException e) {
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // TOTP 验证码不允许截屏/录屏。
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        vault = TotpVault.getInstance(this);
        setContentView(wrapInScrollView(buildPage()));
        if (vault.hasStoredData()) {
            requestUnlock();
        } else {
            unlocked = true;
            render();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(tick);
        if (unlocked) handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_UNLOCK) {
            if (resultCode == RESULT_OK) {
                unlocked = true;
                render();
                handler.removeCallbacks(tick);
                handler.post(tick);
            } else {
                Toast.makeText(this, "未验证设备锁屏，已关闭保险库", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void requestUnlock() {
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (km == null || !km.isDeviceSecure()) {
            // 设备没设锁屏：仍受 App 沙盒 + Keystore 保护，但明确告知风险。
            unlocked = true;
            render();
            Toast.makeText(this, "设备未设置锁屏，保险库仅受 App 沙盒/Keystore 保护",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = km.createConfirmDeviceCredentialIntent(
                "解锁 TOTP 保险库", "验证设备锁屏后查看验证码");
        if (intent == null) {
            unlocked = true;
            render();
            return;
        }
        try {
            startActivityForResult(intent, REQ_UNLOCK);
        } catch (Exception e) {
            unlocked = true;
            render();
            Toast.makeText(this, "无法调起设备锁屏验证，保险库仅受 App 沙盒保护",
                    Toast.LENGTH_LONG).show();
        }
    }

    private View wrapInScrollView(View page) {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(page);
        return sv;
    }

    private View buildPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(12), dp(16), dp(16));
        page.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(18);
        back.setGravity(Gravity.CENTER);
        back.setTextColor(color(R.color.text_primary));
        back.setBackground(buttonBackground(R.color.secondary_container));
        topRow.addView(back, new LinearLayout.LayoutParams(dp(40), dp(36)));
        back.setOnClickListener(v -> finish());

        TextView title = new TextView(this);
        title.setText("TOTP 验证器");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(color(R.color.text_primary));
        title.setPadding(dp(10), 0, 0, 0);
        topRow.addView(title);
        page.addView(topRow);

        TextView sub = new TextView(this);
        sub.setText("本地加密保险库 · 不参与 AI/云同步 · 不支持截屏\n"
                + "只用于通用 TOTP；GitHub 2FA 建议同时保留 recovery codes 或硬件密钥。");
        sub.setTextSize(12);
        sub.setTextColor(color(R.color.text_tertiary));
        sub.setPadding(0, dp(8), 0, dp(8));
        page.addView(sub);

        if (!vault.isAvailable()) {
            TextView warn = new TextView(this);
            warn.setText("⚠ 系统 Keystore 不可用：为避免明文落盘，本页禁止保存新账号。");
            warn.setTextSize(12);
            warn.setTextColor(color(R.color.error));
            warn.setPadding(0, dp(4), 0, dp(8));
            page.addView(warn);
        }

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setPadding(0, dp(4), 0, dp(8));

        TextView addBtn = pill("＋ 添加账号", R.color.primary_container);
        addBtn.setOnClickListener(v -> showAddChoice());
        actionRow.addView(addBtn);

        TextView resetBtn = pill("重置保险库", R.color.error_container);
        resetBtn.setOnClickListener(v -> confirmReset());
        actionRow.addView(resetBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));
        page.addView(actionRow);

        statusText = new TextView(this);
        statusText.setTextSize(12);
        statusText.setTextColor(color(R.color.text_tertiary));
        statusText.setPadding(0, dp(4), 0, dp(4));
        page.addView(statusText);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        page.addView(listContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return page;
    }

    private void render() {
        if (listContainer == null) return;
        listContainer.removeAllViews();
        holders.clear();
        if (!unlocked) {
            statusText.setText("已锁定");
            return;
        }
        List<TotpAccount> accounts;
        try {
            accounts = vault.list();
        } catch (TotpVault.VaultException e) {
            statusText.setText("⚠ " + e.getMessage());
            statusText.setTextColor(color(R.color.error));
            return;
        }
        statusText.setTextColor(color(R.color.text_tertiary));
        if (accounts.isEmpty()) {
            statusText.setText("还没有账号。点「＋ 添加账号」扫码或手动输入。");
            return;
        }
        statusText.setText("共 " + accounts.size() + " 个账号 · 验证码每 30 秒更新");
        for (TotpAccount account : accounts) {
            listContainer.addView(buildCard(account));
        }
        refreshCodes();
    }

    private View buildCard(final TotpAccount account) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(5), 0, dp(5));
        card.setLayoutParams(lp);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(R.color.surface_variant));
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), color(R.color.outline));
        card.setBackground(bg);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText(account.displayLabel());
        label.setTextSize(14);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(color(R.color.text_primary));
        head.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView delete = new TextView(this);
        delete.setText("🗑");
        delete.setTextSize(16);
        delete.setGravity(Gravity.CENTER);
        delete.setPadding(dp(8), 0, 0, 0);
        delete.setTextColor(color(R.color.error));
        delete.setOnClickListener(v -> confirmDelete(account));
        head.addView(delete);
        card.addView(head);

        TextView meta = new TextView(this);
        meta.setText(account.algorithm + " · " + account.digits + " 位 · "
                + account.period + " 秒");
        meta.setTextSize(11);
        meta.setTextColor(color(R.color.text_tertiary));
        meta.setPadding(0, dp(1), 0, dp(4));
        card.addView(meta);

        LinearLayout codeRow = new LinearLayout(this);
        codeRow.setOrientation(LinearLayout.HORIZONTAL);
        codeRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView code = new TextView(this);
        code.setTextSize(30);
        code.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        code.setLetterSpacing(0.12f);
        code.setTextColor(color(R.color.primary));
        codeRow.addView(code, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView countdown = new TextView(this);
        countdown.setTextSize(12);
        countdown.setTextColor(color(R.color.text_secondary));
        codeRow.addView(countdown);
        card.addView(codeRow);

        ProgressBar progress = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        progress.setMax(account.period);
        progress.setProgress(account.period);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        progressLp.setMargins(0, dp(4), 0, dp(6));
        card.addView(progress, progressLp);

        TextView copy = pill("复制验证码", R.color.secondary_container);
        copy.setOnClickListener(v -> copyCode(currentHolderCode(account.id)));
        card.addView(copy);

        CodeHolder holder = new CodeHolder(account, code, countdown, progress);
        holders.put(account.id, holder);
        return card;
    }

    private String currentHolderCode(String id) {
        CodeHolder h = holders.get(id);
        return h == null ? "" : h.lastCode;
    }

    private void refreshCodes() {
        long now = System.currentTimeMillis() / 1000L;
        for (CodeHolder h : holders.values()) {
            String code = TotpGenerator.generate(h.account, now);
            if (!code.equals(h.lastCode)) {
                h.lastCode = code;
                h.codeView.setText(formatCode(code));
            }
            int remaining = TotpGenerator.remainingSeconds(h.account.period, now);
            h.countdownView.setText("剩余 " + remaining + "s");
            h.progress.setProgress(remaining);
        }
    }

    private String formatCode(String code) {
        if (code.length() == 6) return code.substring(0, 3) + " " + code.substring(3);
        if (code.length() == 8) return code.substring(0, 4) + " " + code.substring(4);
        return code;
    }

    private void showAddChoice() {
        if (!unlocked) return;
        if (!vault.isAvailable()) {
            Toast.makeText(this, "系统 Keystore 不可用，不能安全保存", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("添加 TOTP 账号")
                .setItems(new String[]{"扫码添加（推荐）", "手动输入密钥"}, (d, which) -> {
                    if (which == 0) startScan();
                    else showManualDialog();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void startScan() {
        ScanOptions options = new ScanOptions();
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setPrompt("扫描 otpauth:// 二维码");
        options.setBeepEnabled(false);
        options.setOrientationLocked(false);
        scanLauncher.launch(options);
    }

    private void showManualDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), dp(4), dp(8), 0);

        EditText issuer = new EditText(this);
        issuer.setHint("服务名（如 GitHub）");
        issuer.setSingleLine(true);
        form.addView(issuer);

        EditText account = new EditText(this);
        account.setHint("账号（如 alice@example.com）");
        account.setSingleLine(true);
        form.addView(account);

        EditText secret = new EditText(this);
        secret.setHint("Base32 密钥（setup key）");
        secret.setSingleLine(true);
        secret.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        form.addView(secret);

        Spinner algorithm = new Spinner(this);
        String[] algorithms = {"SHA1", "SHA256", "SHA512"};
        algorithm.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, algorithms));
        form.addView(algorithm);

        EditText digits = new EditText(this);
        digits.setHint("位数（6/8）");
        digits.setInputType(InputType.TYPE_CLASS_NUMBER);
        digits.setText("6");
        form.addView(digits);

        EditText period = new EditText(this);
        period.setHint("周期秒数（默认 30）");
        period.setInputType(InputType.TYPE_CLASS_NUMBER);
        period.setText("30");
        form.addView(period);

        new AlertDialog.Builder(this)
                .setTitle("手动输入 TOTP")
                .setView(form)
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        String s = secret.getText().toString();
                        if (TotpGenerator.decodeBase32(s).length == 0) {
                            Toast.makeText(this, "密钥不是有效 Base32", Toast.LENGTH_LONG).show();
                            return;
                        }
                        int dig = parseInt(digits.getText().toString(), 6);
                        int per = parseInt(period.getText().toString(), 30);
                        TotpAccount a = new TotpAccount(null,
                                issuer.getText().toString(),
                                account.getText().toString(),
                                s,
                                algorithms[algorithm.getSelectedItemPosition()],
                                dig, per);
                        addAccount(a);
                    } catch (IllegalArgumentException e) {
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private void addAccount(TotpAccount account) {
        try {
            vault.add(account);
            Toast.makeText(this, "已添加 " + account.displayLabel(), Toast.LENGTH_SHORT).show();
            render();
        } catch (TotpVault.VaultException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void confirmDelete(final TotpAccount account) {
        new AlertDialog.Builder(this)
                .setTitle("删除 " + account.displayLabel() + "？")
                .setMessage("删除后该账号的 TOTP 密钥会从本机保险库移除，无法恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    try {
                        vault.remove(account.id);
                        render();
                    } catch (TotpVault.VaultException e) {
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("重置 TOTP 保险库？")
                .setMessage("会清空本机全部 TOTP 密钥，不能撤销。"
                        + "只有在确认无法解密、且已有外部备份时才继续。")
                .setPositiveButton("清空", (d, w) -> {
                    try {
                        vault.clearAll();
                        render();
                    } catch (TotpVault.VaultException e) {
                        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void copyCode(String code) {
        if (code == null || code.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        ClipData clip = ClipData.newPlainText("TOTP 验证码", code);
        if (Build.VERSION.SDK_INT >= 33) {
            android.os.PersistableBundle extras = new android.os.PersistableBundle();
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
        }
        cm.setPrimaryClip(clip);
        Toast.makeText(this, "验证码已复制，30 秒后自动清除", Toast.LENGTH_SHORT).show();
        handler.postDelayed(() -> {
            try {
                ClipData cur = cm.getPrimaryClip();
                if (cur != null && cur.getItemCount() > 0
                        && code.contentEquals(cur.getItemAt(0).coerceToText(this))) {
                    cm.setPrimaryClip(ClipData.newPlainText("", ""));
                }
            } catch (Throwable ignored) {
            }
        }, 30_000L);
    }

    private TextView pill(String text, int bgColorRes) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(12);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(color(R.color.text_primary));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(bgColorRes));
        bg.setCornerRadius(dp(16));
        v.setBackground(bg);
        v.setPadding(dp(14), dp(7), dp(14), dp(7));
        return v;
    }

    private GradientDrawable buttonBackground(int colorRes) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(colorRes));
        bg.setCornerRadius(dp(9));
        return bg;
    }

    private int color(int resId) {
        return ThemeColors.get(this, resId);
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private static final class CodeHolder {
        final TotpAccount account;
        final TextView codeView;
        final TextView countdownView;
        final ProgressBar progress;
        String lastCode = "";

        CodeHolder(TotpAccount account, TextView codeView, TextView countdownView,
                   ProgressBar progress) {
            this.account = account;
            this.codeView = codeView;
            this.countdownView = countdownView;
            this.progress = progress;
        }
    }
}

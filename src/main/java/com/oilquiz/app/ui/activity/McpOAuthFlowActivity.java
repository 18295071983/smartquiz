package com.oilquiz.app.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import com.oilquiz.app.ai.agent.online.mcp.McpOAuthPending;
import com.oilquiz.app.util.AILogger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自定义 scheme 回调接收页（{@code smartquiz-mcp-oauth://callback?code=…&state=…}）。
 *
 * <p>浏览器完成授权后按服务商登记的那个回调地址把系统拉回本 App；这个 Activity 不画界面，
 * 只做三件事：取出 code/state/error → 按 state 交给正在等待的授权线程 → 提示一句并关掉自己。</p>
 *
 * <p>为什么要单独一个 Activity 而不是广播：回传带着用户凭证，走 {@code exported} 的
 * intent-filter 是最小暴露面——不匹配 state 的回调（别的 App 或旧的一次）当场丢弃，
 * 不写进任何状态。环回那支由 {@code McpLoopbackReceiver} 收，两条路最后都汇到
 * {@link McpOAuthPending#deliver}，等的那一方不需要关心自己是被哪条喂醒的。</p>
 */
public class McpOAuthFlowActivity extends Activity {

    private static final String TAG = "McpOAuthCallback";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handle(getIntent());
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null) {
            Toast.makeText(this, "授权回传是空的，已忽略", Toast.LENGTH_SHORT).show();
            return;
        }
        Map<String, String> q = queryOf(data);
        String state = q.get("state");
        String code = q.get("code");
        String error = q.get("error");
        if (state == null || (code == null && error == null)) {
            AILogger.w(TAG, "回传缺少 state/code，丢弃");
            Toast.makeText(this, "授权回传不完整，已忽略", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean matched = McpOAuthPending.deliver(state, new McpOAuthPending.Result(
                code, error, q.get("error_description")));
        AILogger.i(TAG, "授权回传" + (matched ? "已交给等待中的授权" : "与当前等待不匹配，已丢弃"));
        Toast.makeText(this, matched
                ? (error == null ? "授权已回传，回到答题宝即可" : "服务商回传了错误，回到答题宝看原因")
                : "这次回传不属于当前授权，已忽略", Toast.LENGTH_LONG).show();
    }

    /** Uri 不支持 query 时（少见）也要能取，逐个键试。 */
    private static Map<String, String> queryOf(Uri data) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (String key : data.getQueryParameterNames()) {
                String v = data.getQueryParameter(key);
                if (v != null) out.put(key, v);
            }
        } catch (Exception e) {
            String s = data.getEncodedSchemeSpecificPart();
            if (s != null) {
                for (String pair : s.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        out.put(pair.substring(0, eq), pair.substring(eq + 1));
                    }
                }
            }
        }
        return out;
    }
}

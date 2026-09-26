package com.oilquiz.app.ai.tool;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;

/**
 * 只做一件事：弹出并请求 Termux 的 {@code com.termux.permission.RUN_COMMAND} 授权，然后结束自己。
 *
 * <p>为什么需要它：该权限是 Termux 声明的 dangerous 权限，必须在运行时由用户确认。
 * 而部分 ROM（实测 HyperOS / MIUI）禁止普通 adb 会话执行 {@code pm grant}
 * （Neither user 2000 nor current process has android.permission.GRANT_RUNTIME_PERMISSIONS），
 * 所以只能由 App 自己发起请求。
 *
 * <p>由 {@link SystemResourceTool#termuxExec} 在权限缺失时以 NEW_TASK 方式拉起，
 * 用户点“允许”后 agent 重新调用 termux_exec 即可。
 */
public class TermuxPermissionActivity extends Activity {

    /** 与 Termux 侧声明保持一致 */
    static final String PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final int REQUEST_CODE = 0x7E31;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            finish();
            return;
        }
        requestPermissions(new String[]{PERMISSION}, REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        finish();
    }
}

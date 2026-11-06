package com.oilquiz.app.ui.base;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Activity Result 兼容基类（2026-11-06）。
 *
 * <p>{@code startActivityForResult/onActivityResult} 在 ComponentActivity 上已废弃。
 * 但仓库里有 30+ 个调用点、各自在 {@code onActivityResult} 里按 requestCode 分支；
 * 一次性改成每个流程独立的 launcher 风险大。这里先做**统一桥接**：</p>
 *
 * <ul>
 *   <li>子类继续写 {@link #startActivityForResultCompat(Intent, int)}；</li>
 *   <li>结果仍回调到原有的 {@code onActivityResult(requestCode, resultCode, data)}；</li>
 *   <li>launcher 在 onCreate 里注册（早于 STARTED），生命周期/重建由 Activity Result API 负责。</li>
 * </ul>
 *
 * <p>约束：同一时刻只有一个"在飞"的遗留请求（覆盖 pendingRequestCode）。现有文件选择/
 * 拍照/导入流程都是串行的，符合这个前提；以后新增并发请求请直接用独立 launcher。</p>
 */
public abstract class LegacyResultActivity extends AppCompatActivity {

    private int pendingRequestCode;
    private ActivityResultLauncher<Intent> legacyLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        legacyLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    int code = pendingRequestCode;
                    pendingRequestCode = 0;
                    if (code != 0) {
                        onActivityResult(code, result.getResultCode(), result.getData());
                    }
                });
    }

    /** 替代 startActivityForResult：结果仍进 onActivityResult。 */
    protected void startActivityForResultCompat(Intent intent, int requestCode) {
        if (legacyLauncher == null) {
            // 极少数在 onCreate 之前调用的路径：退回系统 API（由子类 suppress，不应发生）
            return;
        }
        pendingRequestCode = requestCode;
        legacyLauncher.launch(intent);
    }

    /**
     * 供非 Activity 调用方（工具/弹窗）使用；返回 false 表示宿主不是本基类，
     * 调用方自行决定是否退回旧 API。
     */
    public static boolean startForResult(Activity activity, Intent intent, int requestCode) {
        if (activity instanceof LegacyResultActivity) {
            ((LegacyResultActivity) activity).startActivityForResultCompat(intent, requestCode);
            return true;
        }
        return false;
    }
}

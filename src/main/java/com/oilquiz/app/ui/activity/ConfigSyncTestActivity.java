package com.oilquiz.app.ui.activity;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.oilquiz.app.ui.base.BaseActivity;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.usage.ConfigSyncManager;
import com.oilquiz.app.ai.usage.db.DatabaseManager;
import com.oilquiz.app.ai.usage.db.UsageDatabase;
import com.oilquiz.app.ai.usage.db.entity.ApiProviderConfig;
import com.oilquiz.app.ai.usage.db.entity.ApiPriceConfig;
import com.oilquiz.app.ai.usage.db.entity.ModelEntity;

import java.util.List;

public class ConfigSyncTestActivity extends BaseActivity {

    private static final String TAG = "ConfigSyncTest";

    @Override
    protected int getLayoutId() {
        return R.layout.activity_config_sync_test;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupToolbarWithoutBack("配置同步测试");
    }

    @Override
    protected void initData() {
    }

    @Override
    protected void initListener() {
    }

    @Override
    protected void initView() {
        findViewById(R.id.btn_sync_local).setOnClickListener(v -> testLocalSync());
        findViewById(R.id.btn_sync_remote).setOnClickListener(v -> testRemoteSync());
        findViewById(R.id.btn_check_db).setOnClickListener(v -> checkDatabase());
        findViewById(R.id.btn_check_config).setOnClickListener(v -> checkSyncResult());
    }

    private void testLocalSync() {
        Toast.makeText(this, "正在执行 Assets 同步...", Toast.LENGTH_SHORT).show();
        
        new Thread(() -> {
            try {
                ConfigSyncManager syncManager = new ConfigSyncManager(ConfigSyncTestActivity.this);
                ConfigSyncManager.SyncResult result = syncManager.syncOnStartup();
                
                runOnUiThread(() -> {
                    showResult("Assets 同步", result);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    showError("Assets 同步", e.getMessage());
                });
            }
        }).start();
    }

    private void testRemoteSync() {
        Toast.makeText(this, "正在执行远程同步...", Toast.LENGTH_SHORT).show();
        
        new Thread(() -> {
            try {
                ConfigSyncManager syncManager = new ConfigSyncManager(ConfigSyncTestActivity.this);
                ConfigSyncManager.SyncResult result = syncManager.syncOnStartup();
                
                runOnUiThread(() -> {
                    showResult("远程同步", result);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    showError("远程同步", e.getMessage());
                });
            }
        }).start();
    }

    private void checkDatabase() {
        new Thread(() -> {
            try {
                UsageDatabase db = DatabaseManager.getInstance(ConfigSyncTestActivity.this);
                
                int providerCount = db.providerDao().findAll().size();
                int modelCount = db.modelDao().count();
                int priceCount = db.priceDao().findAll().size();
                
                StringBuilder sb = new StringBuilder();
                sb.append("📊 数据库统计\n\n");
                sb.append("服务商数量: ").append(providerCount).append("\n");
                sb.append("模型数量: ").append(modelCount).append("\n");
                sb.append("价格配置数量: ").append(priceCount).append("\n\n");
                
                if (providerCount > 0) {
                    sb.append("📋 服务商列表:\n");
                    List<ApiProviderConfig> providers = db.providerDao().findAll();
                    for (ApiProviderConfig p : providers) {
                        sb.append("  • ").append(p.getDisplayName())
                          .append(" (").append(p.getProviderId()).append(")\n");
                    }
                }
                
                if (modelCount > 0) {
                    sb.append("\n📋 模型列表:\n");
                    List<ModelEntity> models = db.modelDao().getAll();
                    for (ModelEntity m : models) {
                        sb.append("  • ").append(m.getDisplayName())
                          .append(" (").append(m.getModelId())
                          .append(") [").append(m.getFamily()).append("]\n");
                    }
                }
                
                if (priceCount > 0) {
                    sb.append("\n💰 价格配置:\n");
                    List<ApiPriceConfig> prices = db.priceDao().findAll();
                    for (ApiPriceConfig pr : prices) {
                        sb.append("  • ").append(pr.getModelId())
                          .append(" - ").append(pr.getPriceType())
                          .append(": ¥").append(pr.getPricePer1M()).append("/1M\n");
                    }
                }
                
                runOnUiThread(() -> new AlertDialog.Builder(ConfigSyncTestActivity.this)
                        .setTitle("数据库检查")
                        .setMessage(sb.toString())
                        .setPositiveButton("确定", null)
                        .show());
            } catch (Exception e) {
                runOnUiThread(() -> showError("数据库检查", e.getMessage()));
            }
        }).start();
    }

    private void checkSyncResult() {
        Toast.makeText(this, "正在检查同步结果...", Toast.LENGTH_SHORT).show();
        
        new Thread(() -> {
            try {
                ConfigSyncManager syncManager = new ConfigSyncManager(ConfigSyncTestActivity.this);
                ConfigSyncManager.SyncResult result = syncManager.syncOnStartup();
                
                runOnUiThread(() -> {
                    if (result != null && result.isSuccess()) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("🔄 最近同步结果\n\n");
                        sb.append("状态: 成功\n");
                        sb.append("来源: ").append(result.getSource()).append("\n");
                        sb.append("版本: ").append(result.getVersion()).append("\n");
                        
                        new AlertDialog.Builder(ConfigSyncTestActivity.this)
                                .setTitle("同步结果")
                                .setMessage(sb.toString())
                                .setPositiveButton("确定", null)
                                .show();
                    } else {
                        Toast.makeText(ConfigSyncTestActivity.this, "尚未执行过同步", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> showError("同步结果", e.getMessage()));
            }
        }).start();
    }

    private void showResult(String title, ConfigSyncManager.SyncResult result) {
        String msg = String.format(
                "状态: %s\n来源: %s\n版本: %d",
                result.isSuccess() ? "成功" : "失败",
                result.getSource(),
                result.getVersion()
        );
        
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("确定", null)
                .show();
    }

    private void showError(String title, String error) {
        new AlertDialog.Builder(this)
                .setTitle(title + "失败")
                .setMessage(error)
                .setPositiveButton("确定", null)
                .show();
    }
}

package com.oilquiz.app.ui.activity;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;
import com.oilquiz.app.database.AppDatabase;
import com.oilquiz.app.database.DatabaseManager;
import com.oilquiz.app.database.DatabaseUpgradeManager;
import com.oilquiz.app.database.DatabaseVersionChecker;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 数据库管理 Activity
 * 提供版本查看、升级、备份、恢复、重置功能
 */
public class DatabaseManagementActivity extends AppCompatActivity {

    private TextView tvCurrentVersion;
    private TextView tvLatestVersion;
    private TextView tvUpdateStatus;
    private TextView tvDatabaseSize;
    private TextView tvUpgradeProgress;
    private TextView tvLatestVersionNote;
    private TextView tvNoBackups;
    private TextView tvVersionHistoryToggle;

    private LinearLayout layoutUpgradeProgress;
    private ProgressBar progressUpgrade;

    private MaterialButton btnUpgrade;
    private MaterialButton btnBackup;
    private MaterialButton btnRestore;
    private MaterialButton btnVerify;
    private MaterialButton btnReset;
    private MaterialButton btnClearBackups;
    private MaterialButton btnDatabaseDetail;
    private MaterialButton btnFieldManagement;
    private MaterialButton btnInitializeDatabase;

    private RecyclerView rvVersionHistory;
    private RecyclerView rvBackupList;

    private DatabaseManager dbManager;
    private AtomicBoolean isUpgrading = new AtomicBoolean(false);
    private volatile boolean isActivityDestroyed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_database_management);

        dbManager = DatabaseManager.getInstance(this);

        initViews();
        setupListeners();
        loadDatabaseInfo();
        loadVersionHistory();
        loadBackupList();
    }

    private void initViews() {
        tvCurrentVersion = findViewById(R.id.tvCurrentVersion);
        tvLatestVersion = findViewById(R.id.tvLatestVersion);
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus);
        tvDatabaseSize = findViewById(R.id.tvDatabaseSize);
        tvUpgradeProgress = findViewById(R.id.tvUpgradeProgress);
        tvLatestVersionNote = findViewById(R.id.tvLatestVersionNote);

        layoutUpgradeProgress = findViewById(R.id.layoutUpgradeProgress);
        progressUpgrade = findViewById(R.id.progressUpgrade);

        btnUpgrade = findViewById(R.id.btnUpgrade);
        btnBackup = findViewById(R.id.btnBackup);
        btnRestore = findViewById(R.id.btnRestore);
        btnVerify = findViewById(R.id.btnVerify);
        btnReset = findViewById(R.id.btnReset);
        btnClearBackups = findViewById(R.id.btnClearBackups);
        btnDatabaseDetail = findViewById(R.id.btnDatabaseDetail);
        btnFieldManagement = findViewById(R.id.btnFieldManagement);
        btnInitializeDatabase = findViewById(R.id.btnInitializeDatabase);

        rvVersionHistory = findViewById(R.id.rvVersionHistory);
        rvBackupList = findViewById(R.id.rvBackupList);
        tvNoBackups = findViewById(R.id.tvNoBackups);
        tvVersionHistoryToggle = findViewById(R.id.tvVersionHistoryToggle);

        rvVersionHistory.setLayoutManager(new LinearLayoutManager(this));
        rvBackupList.setLayoutManager(new LinearLayoutManager(this));
    }

    private void setupListeners() {
        findViewById(R.id.toolbar).setOnClickListener(v -> finish());

        btnUpgrade.setOnClickListener(v -> checkForUpdate());
        btnBackup.setOnClickListener(v -> performBackup());
        btnRestore.setOnClickListener(v -> showRestoreDialog());
        btnVerify.setOnClickListener(v -> verifyDatabase());
        btnReset.setOnClickListener(v -> showResetConfirmDialog());
        btnClearBackups.setOnClickListener(v -> clearOldBackups());

        btnDatabaseDetail.setOnClickListener(v -> {
            startActivity(new android.content.Intent(DatabaseManagementActivity.this, BackupActivity.class));
            finish();
        });

        btnFieldManagement.setOnClickListener(v -> {
            startActivity(new android.content.Intent(DatabaseManagementActivity.this, FieldManagementActivity.class));
        });

        btnInitializeDatabase.setOnClickListener(v -> initializeDatabase());

        tvVersionHistoryToggle.setOnClickListener(v -> toggleVersionHistory());
    }

    private void loadDatabaseInfo() {
        new Thread(() -> {
            try {
                int currentVersion = dbManager.getDatabaseVersion();
                long dbSize = dbManager.getDatabaseSize();
                DatabaseVersionChecker.UpgradeInfo upgradeInfo = dbManager.getVersionInfo();

                String versionName = "";
                String versionNote = "";

                DatabaseVersionChecker.VersionInfo vi =
                    DatabaseVersionChecker.getVersionInfo(currentVersion);
                if (vi != null) {
                    versionName = "v" + currentVersion + " (" + vi.name + ")";
                    versionNote = vi.description;
                } else {
                    versionName = "v" + currentVersion;
                }

                String latestVersionName = "v" + DatabaseVersionChecker.LATEST_VERSION + " ";
                String latestVersionNote = "";
                DatabaseVersionChecker.VersionInfo latestVi =
                    DatabaseVersionChecker.getVersionInfo(DatabaseVersionChecker.LATEST_VERSION);
                if (latestVi != null) {
                    latestVersionName += "(" + latestVi.name + ")";
                    latestVersionNote = "v" + DatabaseVersionChecker.LATEST_VERSION + ": " + latestVi.description;
                }
                final String finalLatestVersionName = latestVersionName;
                final String finalLatestVersionNote = latestVersionNote;

                String sizeStr;
                if (dbSize > 1024 * 1024) {
                    sizeStr = String.format("%.2f MB", dbSize / (1024.0 * 1024.0));
                } else {
                    sizeStr = String.format("%.2f KB", dbSize / 1024.0);
                }

                String statusText;
                int statusColor;

                if (!upgradeInfo.needUpgrade) {
                    statusText = "已是最新";
                    statusColor = getColor(R.color.success_color);
                } else if (upgradeInfo.forceUpgrade) {
                    statusText = "需要升级";
                    statusColor = getColor(R.color.error_color);
                } else {
                    statusText = "有更新";
                    statusColor = getColor(R.color.warning_color);
                }

                String finalVersionName = versionName;
                if (isValid()) {
                    runOnUiThread(() -> {
                        if (isValid()) {
                            tvCurrentVersion.setText(finalVersionName);
                            tvLatestVersion.setText(finalLatestVersionName);
                            tvLatestVersionNote.setText(finalLatestVersionNote);
                            tvUpdateStatus.setText(statusText);
                            tvUpdateStatus.setTextColor(statusColor);
                            tvDatabaseSize.setText(sizeStr);

                            btnUpgrade.setEnabled(!isUpgrading.get());
                            if (!upgradeInfo.needUpgrade) {
                                btnUpgrade.setText("已是最新");
                            } else {
                                btnUpgrade.setText("立即升级");
                            }
                        }
                    });
                }

            } catch (Exception e) {
                if (isValid()) {
                    runOnUiThread(() -> {
                        if (isValid()) {
                            tvCurrentVersion.setText("获取失败");
                            Toast.makeText(this, "加载数据库信息失败", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void loadVersionHistory() {
        new Thread(() -> {
            List<DatabaseVersionChecker.VersionInfo> history =
                DatabaseVersionChecker.getAllVersionInfo();

            if (isValid()) {
                runOnUiThread(() -> {
                    if (isValid() && !history.isEmpty()) {
                        DatabaseVersionChecker.VersionInfo latest =
                            history.get(history.size() - 1);
                        tvLatestVersionNote.setText(
                            "v" + latest.version + " (" + latest.name + "): " + latest.description);
                    }
                });
            }
        }).start();
    }

    private void loadBackupList() {
        new Thread(() -> {
            List<String> backups = dbManager.getBackupList();

            if (isValid()) {
                runOnUiThread(() -> {
                    if (isValid()) {
                        if (backups.isEmpty()) {
                            tvNoBackups.setVisibility(View.VISIBLE);
                            rvBackupList.setVisibility(View.GONE);
                            btnClearBackups.setEnabled(false);
                        } else {
                            tvNoBackups.setVisibility(View.GONE);
                            rvBackupList.setVisibility(View.VISIBLE);
                            btnClearBackups.setEnabled(true);

                            StringBuilder sb = new StringBuilder();
                            for (String backup : backups) {
                                sb.append("• ").append(backup).append("\n");
                            }

                            TextView tv = new TextView(this);
                            tv.setText(sb.toString());
                            tv.setTextAppearance(R.style.TextAppearance_SmartQuiz_Body2);
                            rvBackupList.removeAllViews();
                            rvBackupList.addView(tv);
                        }
                    }
                });
            }
        }).start();
    }

    private void checkForUpdate() {
        DatabaseVersionChecker.UpgradeInfo info = dbManager.getVersionInfo();

        if (!info.needUpgrade) {
            Toast.makeText(this, "数据库已是最新版本", Toast.LENGTH_SHORT).show();
            return;
        }

        String message = String.format(
            "当前版本: v%d\n最新版本: v%d\n需要升级 %d 个版本\n\n",
            info.currentVersion, info.latestVersion, info.versionsBehind);

        if (info.forceUpgrade) {
            message += "⚠️ 版本过低，建议立即升级\n\n";
        }

        message += "升级内容:\n";
        List<String> changes = DatabaseVersionChecker.getChangeSummary(
            info.currentVersion, info.latestVersion);
        for (String change : changes) {
            message += "• " + change + "\n";
        }

        new AlertDialog.Builder(this)
            .setTitle("数据库更新")
            .setMessage(message)
            .setPositiveButton("立即升级", (dialog, which) -> performUpgrade())
            .setNegativeButton("稍后再说", null)
            .show();
    }

    private void performUpgrade() {
        if (isUpgrading.get()) {
            Toast.makeText(this, "升级正在进行中", Toast.LENGTH_SHORT).show();
            return;
        }

        ProgressDialog progressDialog = new ProgressDialog(this);
        progressDialog.setTitle("正在升级数据库");
        progressDialog.setMessage("请稍候...");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(100);
        progressDialog.setCancelable(false);
        progressDialog.show();

        isUpgrading.set(true);
        btnUpgrade.setEnabled(false);
        layoutUpgradeProgress.setVisibility(View.VISIBLE);

        DatabaseUpgradeManager upgradeManager =
            DatabaseUpgradeManager.getInstance(this);
        upgradeManager.setUpgradeCallback(new DatabaseUpgradeManager.UpgradeCallback() {
            @Override
            public void onUpgradeStart(int fromVersion, int toVersion) {
                runOnUiThread(() -> {
                    progressDialog.setMessage(String.format("准备升级 v%d → v%d",
                        fromVersion, toVersion));
                });
            }

            @Override
            public void onProgressUpdate(DatabaseUpgradeManager.UpgradeProgress progress) {
                runOnUiThread(() -> {
                    progressUpgrade.setProgress((int) progress.progressPercent);
                    tvUpgradeProgress.setText(progress.toDisplayString());
                    progressDialog.setProgress((int) progress.progressPercent);
                    progressDialog.setMessage(progress.currentStep);
                });
            }

            @Override
            public void onUpgradeSuccess(int newVersion) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    layoutUpgradeProgress.setVisibility(View.GONE);
                    isUpgrading.set(false);

                    Toast.makeText(DatabaseManagementActivity.this, "升级成功！", Toast.LENGTH_SHORT).show();
                    loadDatabaseInfo();
                });
            }

            @Override
            public void onUpgradeFailed(String error, Throwable throwable) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    layoutUpgradeProgress.setVisibility(View.GONE);
                    isUpgrading.set(false);
                    btnUpgrade.setEnabled(true);

                    new AlertDialog.Builder(DatabaseManagementActivity.this)
                        .setTitle("升级失败")
                        .setMessage("错误: " + error + "\n\n是否尝试回滚到备份？")
                        .setPositiveButton("回滚", (d, w) -> performRestore())
                        .setNegativeButton("取消", null)
                        .show();
                });
            }
        });

        dbManager.upgradeDatabase();
    }

    private void performBackup() {
        ProgressDialog progressDialog = ProgressDialog.show(
            this, "备份数据库", "正在备份...", true, false);

        new Thread(() -> {
            try {
                String backupPath = dbManager.backupDatabase().get();

                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    if (backupPath != null) {
                        Toast.makeText(this, "备份成功！\n" + backupPath,
                            Toast.LENGTH_LONG).show();
                        loadBackupList();
                    } else {
                        Toast.makeText(this, "备份失败", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(this, "备份失败: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void showRestoreDialog() {
        List<String> backups = dbManager.getBackupList();

        if (backups.isEmpty()) {
            Toast.makeText(this, "没有可用的备份", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] backupNames = backups.toArray(new String[0]);

        new AlertDialog.Builder(this)
            .setTitle("选择要恢复的备份")
            .setItems(backupNames, (dialog, which) -> {
                new AlertDialog.Builder(this)
                    .setTitle("确认恢复")
                    .setMessage("恢复备份将覆盖当前数据！\n\n建议先进行备份。")
                    .setPositiveButton("恢复", (d, w) -> performRestore())
                    .setNegativeButton("取消", null)
                    .show();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void performRestore() {
        ProgressDialog progressDialog = ProgressDialog.show(
            this, "恢复数据库", "正在恢复...", true, false);

        new Thread(() -> {
            try {
                boolean success = dbManager.rollbackDatabase().get();

                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    if (success) {
                        Toast.makeText(this, "恢复成功！", Toast.LENGTH_SHORT).show();
                        loadDatabaseInfo();
                    } else {
                        Toast.makeText(this, "恢复失败", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(this, "恢复失败: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void verifyDatabase() {
        ProgressDialog progressDialog = ProgressDialog.show(
            this, "验证数据库", "正在验证...", true, false);

        new Thread(() -> {
            try {
                boolean valid = dbManager.verifyDatabaseIntegrity().get();

                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    if (valid) {
                        Toast.makeText(this, "数据库完整，无异常",
                            Toast.LENGTH_SHORT).show();
                    } else {
                        new AlertDialog.Builder(this)
                            .setTitle("验证失败")
                            .setMessage("数据库可能存在损坏，建议备份后重置。")
                            .setPositiveButton("备份", (d, w) -> performBackup())
                            .setNegativeButton("稍后", null)
                            .show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(this, "验证失败: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void showResetConfirmDialog() {
        new AlertDialog.Builder(this)
            .setTitle("⚠️ 危险操作")
            .setMessage("重置将清空所有数据！\n\n此操作不可恢复。\n\n建议先进行完整备份。")
            .setPositiveButton("我已备份，继续", (dialog, which) -> {
                new AlertDialog.Builder(this)
                    .setTitle("再次确认")
                    .setMessage("确定要重置数据库吗？\n\n所有题目、笔记、学习记录都将被删除！")
                    .setPositiveButton("确定重置", (d, w) -> performReset())
                    .setNegativeButton("取消", null)
                    .show();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void performReset() {
        ProgressDialog progressDialog = ProgressDialog.show(
            this, "重置数据库", "正在重置...", true, false);

        new Thread(() -> {
            try {
                boolean success = dbManager.resetDatabase().get();

                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    if (success) {
                        Toast.makeText(this, "重置成功！", Toast.LENGTH_SHORT).show();
                        loadDatabaseInfo();
                    } else {
                        Toast.makeText(this, "重置失败", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(this, "重置失败: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void clearOldBackups() {
        new AlertDialog.Builder(this)
            .setTitle("清理备份")
            .setMessage("清理后将保留最近 5 个备份。\n\n确定要清理吗？")
            .setPositiveButton("清理", (d, w) -> {
                Toast.makeText(this, "旧备份已清理", Toast.LENGTH_SHORT).show();
                loadBackupList();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void initializeDatabase() {
        new AlertDialog.Builder(this)
            .setTitle("初始化数据库")
            .setMessage("初始化数据库将重新创建当前数据库结构。\n\n这将：\n• 保留现有数据库版本\n• 重新初始化所有表结构\n• 清除所有数据\n\n是否继续？\n\n建议先进行备份。")
            .setPositiveButton("初始化", (d, w) -> {
                ProgressDialog progressDialog = ProgressDialog.show(
                    this, "初始化数据库", "正在初始化...", true, false);

                new Thread(() -> {
                    try {
                        boolean success = reinitializeDatabase(progressDialog);

                        runOnUiThread(() -> {
                            progressDialog.dismiss();
                            if (success) {
                                Toast.makeText(this, "数据库初始化完成", Toast.LENGTH_SHORT).show();
                                loadDatabaseInfo();
                            } else {
                                Toast.makeText(this, "数据库初始化失败", Toast.LENGTH_SHORT).show();
                            }
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            progressDialog.dismiss();
                            Toast.makeText(this, "初始化失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        });
                    }
                }).start();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /**
     * 重新初始化数据库
     * 关闭现有连接，删除数据库文件，重新创建数据库
     */
    private boolean reinitializeDatabase(ProgressDialog progressDialog) {
        try {
            runOnUiThread(() -> {
                progressDialog.setMessage("关闭数据库连接...");
            });

            // 1. 关闭现有数据库连接
            AppDatabase.destroyInstance();

            // 等待一下确保连接关闭
            Thread.sleep(500);

            runOnUiThread(() -> {
                progressDialog.setMessage("删除旧数据库文件...");
            });

            // 2. 删除现有数据库文件
            File dbFile = getDatabasePath("smartquiz_database");
            File dbJournalFile = new File(dbFile.getParent(), dbFile.getName() + "-journal");
            File dbShmFile = new File(dbFile.getParent(), dbFile.getName() + "-shm");
            File dbWalFile = new File(dbFile.getParent(), dbFile.getName() + "-wal");

            if (dbFile.exists()) {
                dbFile.delete();
            }
            if (dbJournalFile.exists()) {
                dbJournalFile.delete();
            }
            if (dbShmFile.exists()) {
                dbShmFile.delete();
            }
            if (dbWalFile.exists()) {
                dbWalFile.delete();
            }

            runOnUiThread(() -> {
                progressDialog.setMessage("创建新数据库...");
            });

            // 3. 重新创建数据库
            AppDatabase newDb = AppDatabase.getDatabase(this);
            if (newDb == null) {
                return false;
            }

            runOnUiThread(() -> {
                progressDialog.setMessage("初始化完成");
            });

            // 等待一下确保数据库创建完成
            Thread.sleep(500);

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private void toggleVersionHistory() {
        if (rvVersionHistory.getVisibility() == View.VISIBLE) {
            rvVersionHistory.setVisibility(View.GONE);
            tvVersionHistoryToggle.setText("展开");
        } else {
            rvVersionHistory.setVisibility(View.VISIBLE);
            tvVersionHistoryToggle.setText("收起");
        }
    }

    private boolean isValid() {
        return !isActivityDestroyed && !isFinishing();
    }

    @Override
    protected void onDestroy() {
        isActivityDestroyed = true;
        super.onDestroy();
        if (dbManager != null) {
            dbManager.shutdown();
        }
    }
}

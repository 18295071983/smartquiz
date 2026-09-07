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
                                btnUpgrade.setText(getString(R.string.h_72ac9a72));
                            } else {
                                btnUpgrade.setText(getString(R.string.h_6b4a7fd3));
                            }
                        }
                    });
                }

            } catch (Exception e) {
                if (isValid()) {
                    runOnUiThread(() -> {
                        if (isValid()) {
                            tvCurrentVersion.setText(getString(R.string.h_56f0a1c0));
                            Toast.makeText(this, getString(R.string.h_82e4ce0b), Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, getString(R.string.h_0931bdea), Toast.LENGTH_SHORT).show();
            return;
        }

        String message = String.format(
            "当前版本: v%d\n最新版本: v%d\n需要升级 %d 个版本\n\n",
            info.currentVersion, info.latestVersion, info.versionsBehind);

        if (info.forceUpgrade) {
            message += getString(R.string.h_b2395c13);
        }

        message += getString(R.string.h_f6360a4e);
        List<String> changes = DatabaseVersionChecker.getChangeSummary(
            info.currentVersion, info.latestVersion);
        for (String change : changes) {
            message += "• " + change + "\n";
        }

        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.h_e1255531))
            .setMessage(message)
            .setPositiveButton(getString(R.string.h_6b4a7fd3), (dialog, which) -> performUpgrade())
            .setNegativeButton(getString(R.string.h_87e4d9ef), null)
            .show();
    }

    private void performUpgrade() {
        if (isUpgrading.get()) {
            Toast.makeText(this, getString(R.string.h_55c30e82), Toast.LENGTH_SHORT).show();
            return;
        }

        ProgressDialog progressDialog = new ProgressDialog(this);
        progressDialog.setTitle(getString(R.string.h_f263e97e));
        progressDialog.setMessage(getString(R.string.h_885cd96f));
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
                    progressDialog.setMessage(String.format(getString(R.string.h_4ffa7e4b),
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

                    Toast.makeText(DatabaseManagementActivity.this, getString(R.string.h_c854b481), Toast.LENGTH_SHORT).show();
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
                        .setTitle(getString(R.string.h_4ae2f0a2))
                        .setMessage(getString(R.string.h_7449367f) + error + getString(R.string.h_0402c89c))
                        .setPositiveButton(getString(R.string.h_d00b485b), (d, w) -> performRestore())
                        .setNegativeButton(getString(R.string.h_625fb26b), null)
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
                        Toast.makeText(this, getString(R.string.h_57f444fd) + backupPath,
                            Toast.LENGTH_LONG).show();
                        loadBackupList();
                    } else {
                        Toast.makeText(this, getString(R.string.h_6af91784), Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(this, getString(R.string.h_30e37f96) + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void showRestoreDialog() {
        List<String> backups = dbManager.getBackupList();

        if (backups.isEmpty()) {
            Toast.makeText(this, getString(R.string.h_24d4178e), Toast.LENGTH_SHORT).show();
            return;
        }

        String[] backupNames = backups.toArray(new String[0]);

        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.h_3b5af6de))
            .setItems(backupNames, (dialog, which) -> {
                new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.h_841e5e89))
                    .setMessage(getString(R.string.h_df56fb63))
                    .setPositiveButton(getString(R.string.h_c7db6d4f), (d, w) -> performRestore())
                    .setNegativeButton(getString(R.string.h_625fb26b), null)
                    .show();
            })
            .setNegativeButton(getString(R.string.h_625fb26b), null)
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
                        Toast.makeText(this, getString(R.string.h_b0a72d0a), Toast.LENGTH_SHORT).show();
                        loadDatabaseInfo();
                    } else {
                        Toast.makeText(this, getString(R.string.h_2e827d23), Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(this, getString(R.string.h_1c65e1df) + e.getMessage(),
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
                        Toast.makeText(this, getString(R.string.h_7439efc2),
                            Toast.LENGTH_SHORT).show();
                    } else {
                        new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.h_e441b11e))
                            .setMessage(getString(R.string.h_283b00a6))
                            .setPositiveButton(getString(R.string.h_664b37da), (d, w) -> performBackup())
                            .setNegativeButton(getString(R.string.h_bb86dd5c), null)
                            .show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(this, getString(R.string.h_9b42e577) + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void showResetConfirmDialog() {
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.h_019ff44b))
            .setMessage(getString(R.string.h_f08e9aaf))
            .setPositiveButton(getString(R.string.h_4ae53e6e), (dialog, which) -> {
                new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.h_b44aad55))
                    .setMessage(getString(R.string.h_069893f5))
                    .setPositiveButton(getString(R.string.h_bcaeb727), (d, w) -> performReset())
                    .setNegativeButton(getString(R.string.h_625fb26b), null)
                    .show();
            })
            .setNegativeButton(getString(R.string.h_625fb26b), null)
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
                        Toast.makeText(this, getString(R.string.h_eb0c7067), Toast.LENGTH_SHORT).show();
                        loadDatabaseInfo();
                    } else {
                        Toast.makeText(this, getString(R.string.h_4d713822), Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(this, getString(R.string.h_9601d5fc) + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void clearOldBackups() {
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.h_fc336172))
            .setMessage(getString(R.string.h_fe2a6a6e))
            .setPositiveButton(getString(R.string.h_e47bb1cd), (d, w) -> {
                Toast.makeText(this, getString(R.string.h_f6b61a3a), Toast.LENGTH_SHORT).show();
                loadBackupList();
            })
            .setNegativeButton(getString(R.string.h_625fb26b), null)
            .show();
    }

    private void initializeDatabase() {
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.h_063127ae))
            .setMessage(getString(R.string.h_df4ec7d3))
            .setPositiveButton(getString(R.string.h_2cb472ff), (d, w) -> {
                ProgressDialog progressDialog = ProgressDialog.show(
                    this, "初始化数据库", "正在初始化...", true, false);

                new Thread(() -> {
                    try {
                        boolean success = reinitializeDatabase(progressDialog);

                        runOnUiThread(() -> {
                            progressDialog.dismiss();
                            if (success) {
                                Toast.makeText(this, getString(R.string.h_37d8dc3f), Toast.LENGTH_SHORT).show();
                                loadDatabaseInfo();
                            } else {
                                Toast.makeText(this, getString(R.string.h_7052f598), Toast.LENGTH_SHORT).show();
                            }
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            progressDialog.dismiss();
                            Toast.makeText(this, getString(R.string.h_58c10e4c) + e.getMessage(), Toast.LENGTH_SHORT).show();
                        });
                    }
                }).start();
            })
            .setNegativeButton(getString(R.string.h_625fb26b), null)
            .show();
    }

    /**
     * 重新初始化数据库
     * 关闭现有连接，删除数据库文件，重新创建数据库
     */
    private boolean reinitializeDatabase(ProgressDialog progressDialog) {
        try {
            runOnUiThread(() -> {
                progressDialog.setMessage(getString(R.string.h_948b0db8));
            });

            // 1. 关闭现有数据库连接
            AppDatabase.destroyInstance();

            // 等待一下确保连接关闭
            Thread.sleep(500);

            runOnUiThread(() -> {
                progressDialog.setMessage(getString(R.string.h_ea9abdb9));
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
                progressDialog.setMessage(getString(R.string.h_ede419fa));
            });

            // 3. 重新创建数据库
            AppDatabase newDb = AppDatabase.getDatabase(this);
            if (newDb == null) {
                return false;
            }

            runOnUiThread(() -> {
                progressDialog.setMessage(getString(R.string.h_741e41f5));
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
            tvVersionHistoryToggle.setText(getString(R.string.h_e2edde5a));
        } else {
            rvVersionHistory.setVisibility(View.VISIBLE);
            tvVersionHistoryToggle.setText(getString(R.string.h_def9e98b));
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

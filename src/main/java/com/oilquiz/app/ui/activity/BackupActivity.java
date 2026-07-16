package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import com.oilquiz.app.ui.base.BaseActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.oilquiz.app.resource.AppResourceManager;
import com.oilquiz.app.resource.PermissionResourceProvider;
import java.util.List;

import com.oilquiz.app.R;
import com.oilquiz.app.manager.AutoBackupManager;
import com.oilquiz.app.viewmodel.BackupViewModel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class BackupActivity extends BaseActivity {

    private static final int REQUEST_CODE_PICK_BACKUP = 1001;
    private static final int REQUEST_CODE_STORAGE_PERMISSION = 1002;

    private BackupViewModel backupViewModel;
    private TextView tvBackupStatus;
    private AutoBackupManager autoBackupManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_backup;
    }

    @Override
    protected void initView() {
        setupToolbar("数据备份");
        tvBackupStatus = findViewById(R.id.tv_backup_status);
    }

    @Override
    protected void initData() {
        backupViewModel = new BackupViewModel();
        autoBackupManager = new AutoBackupManager(this);
    }

    @Override
    protected void initListener() {
        MaterialButton btnBackup = findViewById(R.id.btn_backup);
        MaterialButton btnRestore = findViewById(R.id.btn_restore);
        MaterialButton btnDatabaseDetail = findViewById(R.id.btn_database_detail);
        MaterialButton btnCreateBackupEmpty = findViewById(R.id.btn_create_backup_empty);
        SwitchMaterial switchAutoBackup = findViewById(R.id.switch_auto_backup);

        switchAutoBackup.setChecked(autoBackupManager.isAutoBackupEnabled());

        btnBackup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkStoragePermissionAndBackup();
            }
        });

        btnCreateBackupEmpty.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkStoragePermissionAndBackup();
            }
        });

        btnRestore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkStoragePermissionAndRestore();
            }
        });

        switchAutoBackup.setOnCheckedChangeListener((buttonView, isChecked) -> {
            autoBackupManager.setAutoBackupEnabled(BackupActivity.this, isChecked);
            Toast.makeText(BackupActivity.this, isChecked ? "自动备份已开启" : "自动备份已关闭", Toast.LENGTH_SHORT).show();
        });

        btnDatabaseDetail.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(BackupActivity.this, DatabaseDetailActivity.class);
                startActivity(intent);
            }
        });
    }

    private enum OperationType {
        BACKUP,
        RESTORE
    }

    private OperationType currentOperation = null;

    private void checkStoragePermissionAndBackup() {
        currentOperation = OperationType.BACKUP;
        AppResourceManager resources = AppResourceManager.getInstance(this);
        if (!resources.hasStoragePermission()) {
            resources.permissions().requestStoragePermission(this, new PermissionResourceProvider.PermissionCallback() {
                @Override
                public void onGranted() {
                    Toast.makeText(BackupActivity.this, "存储权限已授予", Toast.LENGTH_SHORT).show();
                    backupDatabase();
                }

                @Override
                public void onDenied(List<String> deniedPermissions) {
                    Toast.makeText(BackupActivity.this, "存储权限被拒绝，无法执行备份/恢复操作", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            backupDatabase();
        }
    }

    private void checkStoragePermissionAndRestore() {
        currentOperation = OperationType.RESTORE;
        AppResourceManager resources = AppResourceManager.getInstance(this);
        if (!resources.hasStoragePermission()) {
            resources.permissions().requestStoragePermission(this, new PermissionResourceProvider.PermissionCallback() {
                @Override
                public void onGranted() {
                    Toast.makeText(BackupActivity.this, "存储权限已授予", Toast.LENGTH_SHORT).show();
                    pickBackupFile();
                }

                @Override
                public void onDenied(List<String> deniedPermissions) {
                    Toast.makeText(BackupActivity.this, "存储权限被拒绝，无法执行备份/恢复操作", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            pickBackupFile();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        AppResourceManager.getInstance(this).permissions().onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void backupDatabase() {
        tvBackupStatus.setText("备份状态：正在备份...");
        MaterialButton btnBackup = findViewById(R.id.btn_backup);
        MaterialButton btnRestore = findViewById(R.id.btn_restore);
        btnBackup.setEnabled(false);
        btnRestore.setEnabled(false);
        
        backupViewModel.backupDatabase(this, new BackupViewModel.BackupCallback() {
            @Override
            public void onSuccess(File backupFile) {
                tvBackupStatus.setText("备份状态：备份成功");
                Toast.makeText(BackupActivity.this, "备份成功：" + backupFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                btnBackup.setEnabled(true);
                btnRestore.setEnabled(true);
            }

            @Override
            public void onFailure(String error) {
                tvBackupStatus.setText("备份状态：备份失败");
                Toast.makeText(BackupActivity.this, "备份失败：" + error, Toast.LENGTH_SHORT).show();
                btnBackup.setEnabled(true);
                btnRestore.setEnabled(true);
            }
        });
    }

    private void pickBackupFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_CODE_PICK_BACKUP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_BACKUP && resultCode == RESULT_OK) {
            if (data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    restoreDatabase(uri);
                }
            }
        }
    }

    private void restoreDatabase(Uri uri) {
        tvBackupStatus.setText("备份状态：正在恢复...");
        MaterialButton btnBackup = findViewById(R.id.btn_backup);
        MaterialButton btnRestore = findViewById(R.id.btn_restore);
        btnBackup.setEnabled(false);
        btnRestore.setEnabled(false);
        
        File backupFile = uriToFile(uri);
        if (backupFile == null) {
            tvBackupStatus.setText("备份状态：恢复失败");
            Toast.makeText(this, "无法读取备份文件", Toast.LENGTH_SHORT).show();
            btnBackup.setEnabled(true);
            btnRestore.setEnabled(true);
            return;
        }
        
        backupViewModel.restoreDatabase(this, backupFile, new BackupViewModel.RestoreCallback() {
            @Override
            public void onSuccess() {
                tvBackupStatus.setText("备份状态：恢复成功");
                Toast.makeText(BackupActivity.this, "恢复成功，应用将重启", Toast.LENGTH_SHORT).show();
                btnBackup.setEnabled(true);
                btnRestore.setEnabled(true);
                restartApp();
            }

            @Override
            public void onFailure(String error) {
                tvBackupStatus.setText("备份状态：恢复失败");
                Toast.makeText(BackupActivity.this, "恢复失败：" + error, Toast.LENGTH_SHORT).show();
                btnBackup.setEnabled(true);
                btnRestore.setEnabled(true);
            }
        });
    }
    
    private void restartApp() {
        Intent intent = getBaseContext().getPackageManager().getLaunchIntentForPackage(getBaseContext().getPackageName());
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
        finish();
    }

    private File uriToFile(Uri uri) {
        try {
            File tempFile = new File(getCacheDir(), "temp_backup.db");
            tempFile.createNewFile();
            
            InputStream inputStream = getContentResolver().openInputStream(uri);
            OutputStream outputStream = new FileOutputStream(tempFile);
            
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }
            
            inputStream.close();
            outputStream.close();
            
            return tempFile;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
}
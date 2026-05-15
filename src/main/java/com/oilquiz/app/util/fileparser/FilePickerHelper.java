package com.oilquiz.app.util.fileparser;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.oilquiz.app.resource.AppResourceManager;
import com.oilquiz.app.resource.PermissionResourceProvider;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class FilePickerHelper {

    public static final int REQUEST_PICK_FILE = 1001;
    public static final int REQUEST_CAMERA = 1002;
    public static final int REQUEST_STORAGE_PERMISSION = 1003;

    private final Activity activity;

    public FilePickerHelper(Activity activity) {
        this.activity = activity;
    }

    public void pickFile() {
        if (checkStoragePermission()) {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
            activity.startActivityForResult(Intent.createChooser(intent, "选择文件"), REQUEST_PICK_FILE);
        } else {
            requestStoragePermission();
        }
    }

    public void takePhoto() {
        if (checkCameraPermission()) {
            openCameraInternal();
        } else {
            requestCameraPermission();
        }
    }

    private void openCameraInternal() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(activity.getPackageManager()) != null) {
            File photoFile = null;
            try {
                photoFile = createImageFile();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            if (photoFile != null) {
                Uri photoURI = FileProvider.getUriForFile(activity, "com.oilquiz.app.fileprovider", photoFile);
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
                activity.startActivityForResult(takePictureIntent, REQUEST_CAMERA);
            }
        }
    }

    private boolean checkStoragePermission() {
        return AppResourceManager.getInstance(activity).hasStoragePermission();
    }

    private boolean checkCameraPermission() {
        AppResourceManager resources = AppResourceManager.getInstance(activity);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            return resources.hasCameraPermission();
        } else {
            return resources.hasCameraPermission() && resources.hasStoragePermission();
        }
    }

    private void requestStoragePermission() {
        AppResourceManager.getInstance(activity).permissions().requestStoragePermission(activity, new PermissionResourceProvider.PermissionCallback() {
            @Override
            public void onGranted() {
                pickFile();
            }

            @Override
            public void onDenied(List<String> deniedPermissions) {
            }
        });
    }

    private void requestCameraPermission() {
        AppResourceManager resources = AppResourceManager.getInstance(activity);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            resources.permissions().requestCameraPermission(activity, new PermissionResourceProvider.PermissionCallback() {
                @Override
                public void onGranted() {
                    openCameraInternal();
                }

                @Override
                public void onDenied(List<String> deniedPermissions) {
                }
            });
        } else {
            resources.permissions().requestPermissions(activity,
                new String[]{android.Manifest.permission.CAMERA, android.Manifest.permission.READ_EXTERNAL_STORAGE},
                new PermissionResourceProvider.PermissionCallback() {
                    @Override
                    public void onGranted() {
                        openCameraInternal();
                    }

                    @Override
                    public void onDenied(List<String> deniedPermissions) {
                    }
                });
        }
    }

    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = activity.getExternalFilesDir(null);
        File image = File.createTempFile(
                imageFileName,
                ".jpg",
                storageDir
        );
        return image;
    }

    public boolean handlePermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        AppResourceManager.getInstance(activity).permissions().onRequestPermissionsResult(requestCode, permissions, grantResults);
        return true;
    }

    public static class FileResult {
        private final Uri fileUri;
        private final String fileName;
        private final String mimeType;

        public FileResult(Uri fileUri, String fileName, String mimeType) {
            this.fileUri = fileUri;
            this.fileName = fileName;
            this.mimeType = mimeType;
        }

        public Uri getFileUri() {
            return fileUri;
        }

        public String getFileName() {
            return fileName;
        }

        public String getMimeType() {
            return mimeType;
        }
    }
    
    /**
     * 释放资源
     */
    public void release() {
        // 目前没有需要释放的资源
    }
}

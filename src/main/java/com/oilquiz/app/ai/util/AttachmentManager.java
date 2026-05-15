package com.oilquiz.app.ai.util;

import android.content.Context;
import android.net.Uri;
import com.oilquiz.app.infra.AppLogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class AttachmentManager {

    private static final String TAG = "AttachmentManager";
    private static final String ATTACHMENT_DIR = "attachments";

    public interface AttachmentCallback {
        void onSuccess(List<AttachmentFile> attachments);
        void onError(String error);
    }

    public static class AttachmentFile {
        public String id;
        public String name;
        public String path;
        public String mimeType;
        public long size;
        public Uri uri;
        public AttachmentType type;

        public enum AttachmentType {
            IMAGE,
            VIDEO,
            AUDIO,
            DOCUMENT,
            OTHER
        }

        public AttachmentFile(String id, String name, String path, String mimeType, long size, Uri uri) {
            this.id = id;
            this.name = name;
            this.path = path;
            this.mimeType = mimeType;
            this.size = size;
            this.uri = uri;
            this.type = determineType(mimeType);
        }

        private AttachmentType determineType(String mimeType) {
            if (mimeType == null) return AttachmentType.OTHER;

            if (mimeType.startsWith("image/")) {
                return AttachmentType.IMAGE;
            } else if (mimeType.startsWith("video/")) {
                return AttachmentType.VIDEO;
            } else if (mimeType.startsWith("audio/")) {
                return AttachmentType.AUDIO;
            } else if (mimeType.startsWith("application/pdf") ||
                       mimeType.startsWith("application/msword") ||
                       mimeType.startsWith("application/vnd.") ||
                       mimeType.startsWith("text/")) {
                return AttachmentType.DOCUMENT;
            }

            return AttachmentType.OTHER;
        }

        public String getDisplaySize() {
            if (size < 1024) {
                return size + " B";
            } else if (size < 1024 * 1024) {
                return String.format(Locale.getDefault(), "%.1f KB", size / 1024.0);
            } else if (size < 1024 * 1024 * 1024) {
                return String.format(Locale.getDefault(), "%.1f MB", size / (1024.0 * 1024.0));
            } else {
                return String.format(Locale.getDefault(), "%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
            }
        }

        public String getEmoji() {
            switch (type) {
                case IMAGE:
                    return "\uD83D\uDDBC\uFE0F";
                case VIDEO:
                    return "\uD83C\uDFA5";
                case AUDIO:
                    return "\uD83C\uDFB5";
                case DOCUMENT:
                    return "\uD83D\uDCC4";
                default:
                    return "\uD83D\uDCCE";
            }
        }
    }

    private Context context;
    private File attachmentDir;
    private static int savedFilesCount = 0;

    public AttachmentManager(Context context) {
        this.context = context;
        initAttachmentDirectory();
    }

    private void initAttachmentDirectory() {
        if (context == null) {
            AppLogger.e(TAG, "Context is null");
            return;
        }

        File filesDir = context.getFilesDir();
        if (filesDir != null) {
            attachmentDir = new File(filesDir, ATTACHMENT_DIR);
            if (!attachmentDir.exists()) {
                attachmentDir.mkdirs();
            }
        }
    }

    public void saveAttachments(Context context, List<Uri> uris, AttachmentCallback callback) {
        if (uris == null || uris.isEmpty()) {
            if (callback != null) callback.onSuccess(new ArrayList<>());
            return;
        }

        new Thread(() -> {
            List<AttachmentFile> savedFiles = new ArrayList<>();

            for (Uri uri : uris) {
                try {
                    AttachmentFile file = saveSingleFile(context, uri);
                    if (file != null) {
                        savedFiles.add(file);
                    }
                } catch (Exception e) {
                    AppLogger.e(TAG, "save attachment fail: " + e.getMessage());
                }
            }

            if (callback != null) {
                callback.onSuccess(savedFiles);
            }
        }).start();
    }

    private AttachmentFile saveSingleFile(Context context, Uri uri) throws IOException {
        if (uri == null || context == null) {
            return null;
        }

        String fileName = getFileNameFromUri(context, uri);
        String mimeType = context.getContentResolver().getType(uri);

        if (fileName == null) {
            fileName = generateFileName(mimeType);
        }

        File outputFile = new File(attachmentDir, fileName);

        try (InputStream inputStream = context.getContentResolver().openInputStream(uri);
             FileOutputStream outputStream = new FileOutputStream(outputFile)) {

            if (inputStream == null) {
                AppLogger.e(TAG, "unable to open input stream: " + uri);
                return null;
            }

            byte[] buffer = new byte[4096];
            int bytesRead;
            long totalSize = 0;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                totalSize += bytesRead;
            }

            outputStream.flush();

            String id = "attach_" + System.currentTimeMillis() + "_" + savedFilesCount++;
            return new AttachmentFile(id, fileName, outputFile.getAbsolutePath(),
                                     mimeType, totalSize, uri);
        }
    }

    private String getFileNameFromUri(Context context, Uri uri) {
        String result = null;

        if (uri.getScheme() != null && uri.getScheme().equals("content")) {
            try (android.database.Cursor cursor = context.getContentResolver()
                    .query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        result = cursor.getString(index);
                    }
                }
            } catch (Exception e) {
                AppLogger.e(TAG, "get file name fail: " + e.getMessage());
            }
        }

        if (result == null) {
            result = uri.getPath();
            if (result != null) {
                int cut = result.lastIndexOf('/');
                if (cut != -1) {
                    result = result.substring(cut + 1);
                }
            }
        }

        return result;
    }

    private String generateFileName(String mimeType) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        String timestamp = sdf.format(new Date());
        String extension = getExtensionFromMimeType(mimeType);
        return "attachment_" + timestamp + "." + extension;
    }

    private String getExtensionFromMimeType(String mimeType) {
        if (mimeType == null) return "bin";

        switch (mimeType) {
            case "image/jpeg": return "jpg";
            case "image/png": return "png";
            case "image/gif": return "gif";
            case "image/webp": return "webp";
            case "video/mp4": return "mp4";
            case "audio/mpeg": return "mp3";
            case "audio/wav": return "wav";
            case "application/pdf": return "pdf";
            case "text/plain": return "txt";
            case "application/json": return "json";
            default: return "bin";
        }
    }

    public boolean deleteAttachment(String attachmentId) {
        if (attachmentDir == null || attachmentId == null) {
            return false;
        }

        File[] files = attachmentDir.listFiles();
        if (files == null) {
            return false;
        }

        for (File file : files) {
            if (file.getName().startsWith(attachmentId.replace("attach_", ""))) {
                return file.delete();
            }
        }

        return false;
    }

    public void clearAllAttachments() {
        if (attachmentDir == null) {
            return;
        }

        File[] files = attachmentDir.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            file.delete();
        }
    }

    public File getAttachmentDirectory() {
        return attachmentDir;
    }
}

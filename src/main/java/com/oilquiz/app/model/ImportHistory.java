package com.oilquiz.app.model;

public class ImportHistory {
    private long id;
    private String fileName;
    private String filePath;
    private int importedCount;
    private int failedCount;
    private long importTime;
    private String status;

    public ImportHistory() {
    }

    public ImportHistory(String fileName, String filePath, int importedCount, int failedCount, long importTime, String status) {
        this.fileName = fileName;
        this.filePath = filePath;
        this.importedCount = importedCount;
        this.failedCount = failedCount;
        this.importTime = importTime;
        this.status = status;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public int getImportedCount() {
        return importedCount;
    }

    public void setImportedCount(int importedCount) {
        this.importedCount = importedCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(int failedCount) {
        this.failedCount = failedCount;
    }

    public long getImportTime() {
        return importTime;
    }

    public void setImportTime(long importTime) {
        this.importTime = importTime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}

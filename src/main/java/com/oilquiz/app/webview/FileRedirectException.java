package com.oilquiz.app.webview;

/**
 * 文件重定向异常
 * 当文件请求未配置重定向规则时抛出
 */
public class FileRedirectException extends RuntimeException {

    private final String requestedPath;

    public FileRedirectException(String requestedPath) {
        super("未配置文件重定向规则: " + requestedPath + "。必须在 FileRedirectManager 中显式配置此路径的重定向规则。");
        this.requestedPath = requestedPath;
    }

    public FileRedirectException(String requestedPath, String message) {
        super("文件重定向错误 [" + requestedPath + "]: " + message);
        this.requestedPath = requestedPath;
    }

    public String getRequestedPath() {
        return requestedPath;
    }
}

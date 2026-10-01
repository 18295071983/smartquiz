package com.oilquiz.app.ui.activity;

import android.util.Log;
import android.view.MotionEvent;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

/**
 * SSH 终端用到的两个回调实现：
 * - SshSessionClient：TerminalSession 回调（内容变更→重绘、会话结束→提示）
 * - SshViewClient：TerminalView 渲染回调（全部默认行为）
 */
public class SshTerminalClients {

    static final String TAG = "SshTerminal";

    public static class SshSessionClient implements TerminalSessionClient {
        private final TerminalView view;
        private final SshTerminalActivity activity;

        public SshSessionClient(TerminalView view, SshTerminalActivity activity) {
            this.view = view;
            this.activity = activity;
        }

        @Override
        public void onTextChanged(TerminalSession changedSession) {
            if (view != null) view.postInvalidate();
        }

        @Override
        public void onTitleChanged(TerminalSession changedSession) {
        }

        @Override
        public void onSessionFinished(TerminalSession finishedSession) {
            if (activity != null) activity.onSshDisconnected();
        }

        @Override
        public void onCopyTextToClipboard(TerminalSession session, String text) {
            if (activity != null) activity.copyText(text);
        }

        @Override
        public void onPasteTextFromClipboard(TerminalSession session) {
            if (activity != null) activity.pasteText(session);
        }

        @Override
        public void onBell(TerminalSession session) {
        }

        @Override
        public void onColorsChanged(TerminalSession session) {
        }

        @Override
        public void onTerminalCursorStateChange(boolean state) {
        }

        @Override
        public void setTerminalShellPid(TerminalSession session, int pid) {
        }

        @Override
        public Integer getTerminalCursorStyle() {
            return null;
        }

        @Override
        public void logError(String tag, String message) {
            Log.e(TAG, message);
        }

        @Override
        public void logWarn(String tag, String message) {
            Log.w(TAG, message);
        }

        @Override
        public void logInfo(String tag, String message) {
            Log.i(TAG, message);
        }

        @Override
        public void logDebug(String tag, String message) {
            Log.d(TAG, message);
        }

        @Override
        public void logVerbose(String tag, String message) {
            Log.v(TAG, message);
        }

        @Override
        public void logStackTraceWithMessage(String tag, String message, Exception e) {
            Log.e(TAG, message, e);
        }

        @Override
        public void logStackTrace(String tag, Exception e) {
            Log.e(TAG, "stacktrace", e);
        }
    }

    public static class SshViewClient implements TerminalViewClient {
        private final SshTerminalActivity activity;

        public SshViewClient(SshTerminalActivity activity) {
            this.activity = activity;
        }

        @Override
        public float onScale(float scale) {
            return scale;
        }

        @Override
        public void onSingleTapUp(MotionEvent e) {
            // 点击终端区弹出软键盘，让用户能直接输入
            if (activity != null) activity.showKeyboard();
        }

        @Override
        public boolean shouldBackButtonBeMappedToEscape() {
            return false;
        }

        @Override
        public boolean shouldEnforceCharBasedInput() {
            return false;
        }

        @Override
        public boolean shouldUseCtrlSpaceWorkaround() {
            return false;
        }

        @Override
        public boolean isTerminalViewSelected() {
            return true;
        }

        @Override
        public void copyModeChanged(boolean copyMode) {
        }

        @Override
        public boolean onKeyDown(int keyCode, android.view.KeyEvent e, TerminalSession session) {
            return false;
        }

        @Override
        public boolean onKeyUp(int keyCode, android.view.KeyEvent e) {
            return false;
        }

        @Override
        public boolean onLongPress(MotionEvent event) {
            return false;
        }

        @Override
        public boolean readControlKey() {
            return false;
        }

        @Override
        public boolean readAltKey() {
            return false;
        }

        @Override
        public boolean readShiftKey() {
            return false;
        }

        @Override
        public boolean readFnKey() {
            return false;
        }

        @Override
        public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
            return false;
        }

        @Override
        public void onEmulatorSet() {
        }

        @Override
        public void logError(String tag, String message) {
            Log.e(TAG, message);
        }

        @Override
        public void logWarn(String tag, String message) {
            Log.w(TAG, message);
        }

        @Override
        public void logInfo(String tag, String message) {
            Log.i(TAG, message);
        }

        @Override
        public void logDebug(String tag, String message) {
            Log.d(TAG, message);
        }

        @Override
        public void logVerbose(String tag, String message) {
            Log.v(TAG, message);
        }

        @Override
        public void logStackTraceWithMessage(String tag, String message, Exception e) {
            Log.e(TAG, message, e);
        }

        @Override
        public void logStackTrace(String tag, Exception e) {
            Log.e(TAG, "stacktrace", e);
        }
    }
}

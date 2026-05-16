package com.oilquiz.app.infra;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.oilquiz.app.ai.util.APIKeyManager;

public class DebugKeyReceiver extends BroadcastReceiver {

    private static final String TAG = "DebugKeyReceiver";
    private static final String ACTION_INJECT_KEY = "com.oilquiz.app.DEBUG_INJECT_KEY";
    private static final String ACTION_INJECT_HOST = "com.oilquiz.app.DEBUG_INJECT_HOST";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        try {
            APIKeyManager manager = APIKeyManager.getInstance(context);

            if (ACTION_INJECT_KEY.equals(action)) {
                String service = intent.getStringExtra("service");
                String key = intent.getStringExtra("key");
                if (service != null && key != null) {
                    manager.saveAPIKey(service, key);
                    Log.i(TAG, "Injected API key for: " + service);
                }
            } else if (ACTION_INJECT_HOST.equals(action)) {
                String service = intent.getStringExtra("service");
                String host = intent.getStringExtra("host");
                if (service != null && host != null) {
                    manager.saveAPIHost(service, host);
                    Log.i(TAG, "Injected API host for: " + service);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to inject: " + e.getMessage(), e);
        }
    }
}

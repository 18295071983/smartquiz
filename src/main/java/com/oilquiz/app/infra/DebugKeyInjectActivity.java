package com.oilquiz.app.infra;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.oilquiz.app.ai.util.APIKeyManager;

public class DebugKeyInjectActivity extends Activity {

    private static final String TAG = "DebugKeyInject";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String action = intent.getAction();
        if (action != null && "com.oilquiz.app.DEBUG_INJECT".equals(action)) {
            try {
                APIKeyManager manager = APIKeyManager.getInstance(this);

                String keyService = intent.getStringExtra("key_service");
                String keyValue = intent.getStringExtra("key_value");
                String hostService = intent.getStringExtra("host_service");
                String hostValue = intent.getStringExtra("host_value");

                if (keyService != null && keyValue != null) {
                    manager.saveAPIKey(keyService, keyValue);
                    Log.i(TAG, "Injected API key for: " + keyService);
                }

                if (hostService != null && hostValue != null) {
                    manager.saveAPIHost(hostService, hostValue);
                    Log.i(TAG, "Injected API host for: " + hostService);
                }

                Log.i(TAG, "Injection completed successfully");
            } catch (Exception e) {
                Log.e(TAG, "Injection failed: " + e.getMessage(), e);
            }
        }

        finish();
    }
}

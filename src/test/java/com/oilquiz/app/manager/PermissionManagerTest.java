package com.oilquiz.app.manager;

import android.Manifest;
import android.content.pm.PackageManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import android.app.Activity;

@RunWith(AndroidJUnit4.class)
public class PermissionManagerTest {

    @Mock
    private Activity mockActivity;

    private PermissionManager permissionManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        permissionManager = new PermissionManager();
    }

    @Test
    public void testIsGranted_WhenPermissionGranted() {
        when(mockActivity.checkSelfPermission(Manifest.permission.CAMERA))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        
        assertTrue("Camera should be granted",
                PermissionManager.isGranted(mockActivity, Manifest.permission.CAMERA));
    }

    @Test
    public void testIsGranted_WhenPermissionDenied() {
        when(mockActivity.checkSelfPermission(Manifest.permission.CAMERA))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        
        assertFalse("Camera should not be granted",
                PermissionManager.isGranted(mockActivity, Manifest.permission.CAMERA));
    }

    @Test
    public void testIsAllGranted_AllPermissionsGranted() {
        String[] permissions = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET
        };
        
        for (String perm : permissions) {
            when(mockActivity.checkSelfPermission(perm))
                    .thenReturn(PackageManager.PERMISSION_GRANTED);
        }
        
        assertTrue("All permissions should be granted",
                PermissionManager.isAllGranted(mockActivity, permissions));
    }

    @Test
    public void testIsAllGranted_SomePermissionsDenied() {
        String[] permissions = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        };
        
        when(mockActivity.checkSelfPermission(Manifest.permission.CAMERA))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mockActivity.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        
        assertFalse("Should return false if any permission denied",
                PermissionManager.isAllGranted(mockActivity, permissions));
    }

    @Test
    public void testGetPermissionState_Granted() {
        when(mockActivity.checkSelfPermission(Manifest.permission.CAMERA))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        
        assertEquals(PermissionManager.PermissionState.GRANTED,
                PermissionManager.getPermissionState(mockActivity, Manifest.permission.CAMERA));
    }

    @Test
    public void testGetPermissionState_Denied() {
        when(mockActivity.checkSelfPermission(Manifest.permission.CAMERA))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(ActivityCompat.shouldShowRequestPermissionRationale(mockActivity, 
                Manifest.permission.CAMERA)).thenReturn(true);
        
        assertEquals(PermissionManager.PermissionState.DENIED,
                PermissionManager.getPermissionState(mockActivity, Manifest.permission.CAMERA));
    }

    @Test
    public void testGetPermissionState_PermanentlyDenied() {
        when(mockActivity.checkSelfPermission(Manifest.permission.CAMERA))
                .thenReturn(PackageManager.PERMISSION_DENIED);
        when(ActivityCompat.shouldShowRequestPermissionRationale(mockActivity, 
                Manifest.permission.CAMERA)).thenReturn(false);
        
        assertEquals(PermissionManager.PermissionState.PERMANENTLY_DENIED,
                PermissionManager.getPermissionState(mockActivity, Manifest.permission.CAMERA));
    }
}

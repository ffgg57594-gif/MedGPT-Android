package com.medgpt.app;

import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * Multi-layer security manager for MedGPT app.
 * Combines native C++ checks (root, Frida, debug) with Java-level checks.
 */
public class SecurityManager {

    private static final String TAG = "SecurityManager";
    private static boolean nativeLoaded = false;

    static {
        try {
            System.loadLibrary("native_security");
            nativeLoaded = true;
            Log.i(TAG, "Native security library loaded");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Native security library not available, using Java fallback: " + e.getMessage());
        }
    }

    private final Context context;

    public SecurityManager(Context context) {
        this.context = context;
    }

    // ========== Native methods (from C++ layer) ==========

    private static native boolean nativeCheckRoot();
    private static native boolean nativeCheckFrida();
    private static native boolean nativeCheckDebug();
    private static native boolean nativeCheckAll();
    private static native String nativeGetStatus();

    // ========== Java-level fallback checks ==========

    /**
     * Check if the device is rooted (Java fallback).
     */
    private boolean javaCheckRoot() {
        // Check for common root paths
        String[] paths = {
            "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su",
            "/data/local/su", "/su/bin/su",
            "/data/adb/magisk.db", "/data/adb/magisk.img"
        };
        for (String path : paths) {
            if (new File(path).exists()) {
                Log.w(TAG, "Root detected (Java): " + path);
                return true;
            }
        }

        // Check for root via which command
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"which", "su"});
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            if (line != null && !line.isEmpty()) {
                Log.w(TAG, "Root detected (Java): su in PATH");
                return true;
            }
            process.destroy();
        } catch (Exception ignored) {}

        // Check for Magisk via settings
        try {
            Process process = Runtime.getRuntime().exec(
                    new String[]{"settings", "get", "global", "magisk"});
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            if (line != null && !line.isEmpty()) {
                Log.w(TAG, "Root detected (Java): Magisk setting");
                return true;
            }
            process.destroy();
        } catch (Exception ignored) {}

        return false;
    }

    /**
     * Check if debugger is connected (Java fallback).
     */
    private boolean javaCheckDebug() {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger();
    }

    // ========== Public API ==========

    /**
     * Run all security checks. Returns true if any tampering is detected.
     */
    public boolean isTampered() {
        // Native checks (more stealthy)
        if (nativeLoaded) {
            if (nativeCheckAll()) {
                Log.w(TAG, "Tampering detected by native layer");
                return true;
            }
        }

        // Java fallback checks
        if (javaCheckRoot()) {
            Log.w(TAG, "Root detected by Java fallback");
            return true;
        }
        if (javaCheckDebug()) {
            Log.w(TAG, "Debugger detected by Java fallback");
            return true;
        }

        // Build check
        if (Build.TAGS != null && Build.TAGS.contains("test-keys")) {
            Log.w(TAG, "Test-keys build detected");
            return true;
        }

        return false;
    }

    /**
     * Check root status specifically.
     */
    public boolean isRooted() {
        if (nativeLoaded) {
            if (nativeCheckRoot()) return true;
        }
        return javaCheckRoot();
    }

    /**
     * Check Frida status specifically.
     */
    public boolean hasFrida() {
        if (nativeLoaded) {
            return nativeCheckFrida();
        }
        return false;
    }

    /**
     * Check debugger status specifically.
     */
    public boolean isDebugged() {
        if (nativeLoaded) {
            if (nativeCheckDebug()) return true;
        }
        return javaCheckDebug();
    }

    /**
     * Get status string for debugging.
     */
    public String getStatus() {
        if (nativeLoaded) {
            try {
                return nativeGetStatus();
            } catch (Exception e) {
                return "native_error:" + e.getMessage();
            }
        }
        return "native_not_loaded";
    }
}

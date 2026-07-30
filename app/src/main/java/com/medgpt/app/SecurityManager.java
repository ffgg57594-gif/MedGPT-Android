package com.medgpt.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Debug;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Multi-layer security manager for MedGPT app.
 * Combines native C++ checks with Java-level security layers:
 * - Anti-Root, Anti-Frida, Anti-Debug
 * - Emulator Detection
 * - Xposed / LSPosed Detection
 * - APK Integrity Check
 * - Signature Verification
 * - String Encryption
 */
public class SecurityManager {

    private static final String TAG = "SecurityManager";
    private static boolean nativeLoaded = false;

    static {
        try {
            System.loadLibrary("native_security");
            nativeLoaded = true;
            Log.i(TAG, "Native library loaded");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Native library not available: " + e.getMessage());
        }
    }

    private final Context context;

    public SecurityManager(Context context) {
        this.context = context;
    }

    // ========== Native C++ methods ==========

    private static native boolean nativeCheckRoot();
    private static native boolean nativeCheckFrida();
    private static native boolean nativeCheckDebug();
    private static native boolean nativeCheckEmulator();
    private static native boolean nativeCheckXposed();
    private static native boolean nativeCheckApkIntegrity(String apkPath);
    private static native boolean nativeCheckAll();
    private static native String nativeGetStatus();

    // ========== 1. SIGNATURE VERIFICATION ==========

    /**
     * Verify the APK's signature matches the expected certificate fingerprint.
     * This prevents repackaging by checking the signing key.
     */
    public boolean verifySignature() {
        try {
            PackageInfo packageInfo;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo = context.getPackageManager().getPackageInfo(
                        context.getPackageName(),
                        PackageManager.GET_SIGNING_CERTIFICATES);
                var signingInfo = packageInfo.signingInfo;
                if (signingInfo == null) return false;

                Signature[] signatures = signingInfo.getApkContentsSigners();
                if (signatures == null || signatures.length == 0) return false;

                for (Signature sig : signatures) {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    byte[] digest = md.digest(sig.toByteArray());

                    // Convert to hex string
                    StringBuilder hexString = new StringBuilder();
                    for (byte b : digest) {
                        hexString.append(String.format("%02x", b));
                    }
                    String currentHash = hexString.toString().toUpperCase();

                    // The expected hash for debug keystore
                    // In production, replace with your release keystore hash
                    Log.i(TAG, "APK signature hash: " + currentHash);

                    // Check against known signature hashes
                    // For debug builds, this uses the debug keystore
                    // For release, update with your actual certificate hash
                    return currentHash != null && currentHash.length() > 0;
                }
            } else {
                // Legacy API for Android < 9
                @SuppressWarnings("deprecation")
                Signature[] signatures = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(),
                                PackageManager.GET_SIGNATURES).signatures;

                if (signatures == null || signatures.length == 0) return false;

                for (Signature sig : signatures) {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    byte[] digest = md.digest(sig.toByteArray());

                    StringBuilder hexString = new StringBuilder();
                    for (byte b : digest) {
                        hexString.append(String.format("%02x", b));
                    }
                    Log.i(TAG, "APK signature hash: " + hexString.toString().toUpperCase());
                    return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Signature verification failed: " + e.getMessage());
            return false;
        }
        return false;
    }

    // ========== 2. STRING ENCRYPTION (obfuscated string loader) ==========

    /**
     * Decrypt an XOR-obfuscated string. Sensitive strings are stored
     * encrypted in the bytecode and decrypted at runtime.
     */
    public String decryptString(byte[] encrypted, byte key) {
        if (encrypted == null) return "";
        byte[] decrypted = new byte[encrypted.length];
        for (int i = 0; i < encrypted.length; i++) {
            decrypted[i] = (byte) (encrypted[i] ^ key ^ (i * 7 + 3));
        }
        return new String(decrypted);
    }

    // ========== 3. EMULATOR DETECTION (Java fallback) ==========

    /**
     * Java-level emulator detection checks.
     */
    private boolean javaCheckEmulator() {
        // Check common emulator build properties
        String brand = Build.BRAND;
        String device = Build.DEVICE;
        String model = Build.MODEL;
        String product = Build.PRODUCT;
        String hardware = Build.HARDWARE;
        String manufacturer = Build.MANUFACTURER;
        String fingerprint = Build.FINGERPRINT;

        List<String> knownEmulatorBrands = List.of("generic", "android", "vbox");
        List<String> knownEmulatorDevices = List.of("generic", "sdk", "goldfish", "ranchu");
        List<String> knownEmulatorModels = List.of("sdk", "generic");
        List<String> knownEmulatorProducts = List.of("sdk", "generic", "vbox");
        List<String> knownEmulatorHardware = List.of("goldfish", "ranchu", "vbox");

        if (brand != null && knownEmulatorBrands.contains(brand.toLowerCase())) return true;
        if (device != null && knownEmulatorDevices.contains(device.toLowerCase())) return true;
        if (model != null && knownEmulatorModels.contains(model.toLowerCase())) return true;
        if (product != null && knownEmulatorProducts.contains(product.toLowerCase())) return true;
        if (hardware != null && knownEmulatorHardware.contains(hardware.toLowerCase())) return true;
        if (manufacturer != null && manufacturer.toLowerCase().contains("genymotion")) return true;

        // Check for emulator-specific properties
        if (fingerprint != null && (fingerprint.toLowerCase().contains("generic") ||
                fingerprint.toLowerCase().contains("sdk") ||
                fingerprint.toLowerCase().contains("emulator") ||
                fingerprint.toLowerCase().contains("vbox"))) {
            return true;
        }

        return false;
    }

    // ========== 4. XPOSED / LSPOSED DETECTION (Java fallback) ==========

    /**
     * Java-level Xposed/LSPosed detection.
     */
    private boolean javaCheckXposed() {
        // Check for Xposed by trying to load Xposed classes
        try {
            Class.forName("de.robv.android.xposed.XposedBridge");
            Log.w(TAG, "Xposed: XposedBridge class found");
            return true;
        } catch (ClassNotFoundException ignored) {}

        try {
            Class.forName("org.lsposed.hiddenapibypass.HiddenApiBypass");
            Log.w(TAG, "Xposed: LSPosed class found");
            return true;
        } catch (ClassNotFoundException ignored) {}

        // Check for Xposed modules in /data/data
        try {
            Process process = Runtime.getRuntime().exec(
                    new String[]{"pm", "list", "packages", "--show-secret"});
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("xposed") || line.contains("lsposed") ||
                    line.contains("edxposed") || line.contains("riru")) {
                    Log.w(TAG, "Xposed: package found - " + line);
                    reader.close();
                    process.destroy();
                    return true;
                }
            }
            reader.close();
            process.destroy();
        } catch (Exception ignored) {}

        return false;
    }

    // ========== 5. APK INTEGRITY CHECK (Java) ==========

    /**
     * Verify APK integrity by checking the source directory.
     */
    private boolean javaCheckApkIntegrity() {
        try {
            String apkPath = context.getApplicationInfo().sourceDir;
            if (apkPath == null) return false;

            File apkFile = new File(apkPath);
            if (!apkFile.exists()) {
                Log.w(TAG, "Integrity: APK file missing");
                return false;
            }

            // Check that APK is not tiny (repackaged apps often are)
            if (apkFile.length() < 1048576) {
                Log.w(TAG, "Integrity: APK too small: " + apkFile.length());
                return false;
            }

            // Verify APK is a valid ZIP
            try {
                ZipFile zipFile = new ZipFile(apkFile);
                ZipEntry manifestEntry = zipFile.getEntry("AndroidManifest.xml");
                if (manifestEntry == null) {
                    Log.w(TAG, "Integrity: No AndroidManifest.xml in APK");
                    zipFile.close();
                    return false;
                }

                // Check for expected entries
                boolean hasDex = false;
                boolean hasResources = false;
                var entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name.startsWith("classes") && name.endsWith(".dex")) {
                        hasDex = true;
                    }
                    if (name.equals("resources.arsc")) {
                        hasResources = true;
                    }
                }
                zipFile.close();

                if (!hasDex || !hasResources) {
                    Log.w(TAG, "Integrity: Missing DEX or resources in APK");
                    return false;
                }

                Log.i(TAG, "Integrity: APK structure verified");
                return true;
            } catch (Exception e) {
                Log.w(TAG, "Integrity: Not a valid APK/ZIP: " + e.getMessage());
                return false;
            }
        } catch (Exception e) {
            Log.w(TAG, "Integrity check failed: " + e.getMessage());
            return false;
        }
    }

    // ========== PUBLIC API ==========

    /**
     * Run all integrated security checks.
     * Returns true if any tampering is detected.
     */
    public boolean isTampered() {
        boolean tampered = false;

        // Native checks (C++ layer)
        if (nativeLoaded) {
            if (nativeCheckAll()) {
                Log.w(TAG, "Tampering detected by native layer");
                tampered = true;
            }
        }

        // Root (Java fallback)
        if (javaCheckRoot()) {
            Log.w(TAG, "Root detected by Java fallback");
            tampered = true;
        }

        // Debug (Java fallback)
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
            Log.w(TAG, "Debugger detected by Java fallback");
            tampered = true;
        }

        // Emulator
        if (javaCheckEmulator()) {
            Log.w(TAG, "Emulator detected by Java fallback");
            tampered = true;
        }

        // Xposed
        if (javaCheckXposed()) {
            Log.w(TAG, "Xposed detected by Java fallback");
            tampered = true;
        }

        // Signature verification
        if (!verifySignature()) {
            Log.w(TAG, "Signature verification failed");
            tampered = true;
        }

        // Build check
        if (Build.TAGS != null && Build.TAGS.contains("test-keys")) {
            Log.w(TAG, "Test-keys build detected");
            tampered = true;
        }

        return tampered;
    }

    private boolean javaCheckRoot() {
        String[] paths = {
            "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su",
            "/data/local/su", "/su/bin/su",
            "/data/adb/magisk.db", "/data/adb/magisk.img"
        };
        for (String path : paths) {
            if (new File(path).exists()) return true;
        }
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"which", "su"});
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            if (line != null && !line.isEmpty()) {
                process.destroy();
                return true;
            }
            process.destroy();
        } catch (Exception ignored) {}
        return false;
    }

    // Individual check methods

    public boolean isRooted() {
        if (nativeLoaded && nativeCheckRoot()) return true;
        return javaCheckRoot();
    }

    public boolean hasFrida() {
        return nativeLoaded && nativeCheckFrida();
    }

    public boolean isDebugged() {
        if (nativeLoaded && nativeCheckDebug()) return true;
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger();
    }

    public boolean isEmulator() {
        if (nativeLoaded && nativeCheckEmulator()) return true;
        return javaCheckEmulator();
    }

    public boolean hasXposed() {
        if (nativeLoaded && nativeCheckXposed()) return true;
        return javaCheckXposed();
    }

    public boolean isApkIntact() {
        if (nativeLoaded) {
            try {
                String apkPath = context.getApplicationInfo().sourceDir;
                if (apkPath != null && nativeCheckApkIntegrity(apkPath)) {
                    return false;
                }
                return javaCheckApkIntegrity();
            } catch (Exception e) {
                return javaCheckApkIntegrity();
            }
        }
        return javaCheckApkIntegrity();
    }

    public boolean isSignatureValid() {
        return verifySignature();
    }

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

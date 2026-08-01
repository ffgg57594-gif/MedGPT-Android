package com.drarabi.medvision;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CAMERA_PERM = 100;
    private static final int REQUEST_STORAGE_PERM = 101;
    private static final int REQUEST_GALLERY = 102;
    private static final int REQUEST_CAMERA = 103;

    private WebView webView;
    private ApiBridge apiBridge;
    private SecurityManager securityManager;
    private BillingManager billingManager;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraPhotoUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        applySystemBarInsets();

        webView = findViewById(R.id.webView);
        apiBridge = new ApiBridge(webView);
        securityManager = new SecurityManager(this);
        billingManager = new BillingManager(this, webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setAllowFileAccessFromFileURLs(true);

        webView.addJavascriptInterface(apiBridge, "AndroidBridge");
        webView.addJavascriptInterface(billingManager, "BillingBridge");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePath,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = filePath;

                if (params.isCaptureEnabled()) {
                    // Camera button clicked → request camera permission only
                    if (ContextCompat.checkSelfPermission(MainActivity.this,
                            Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        openCameraOnly();
                    } else {
                        ActivityCompat.requestPermissions(MainActivity.this,
                                new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERM);
                    }
                } else {
                    // Upload images button clicked → request storage permission only
                    String storagePerm = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                            ? Manifest.permission.READ_MEDIA_IMAGES
                            : Manifest.permission.READ_EXTERNAL_STORAGE;
                    if (ContextCompat.checkSelfPermission(MainActivity.this, storagePerm)
                            == PackageManager.PERMISSION_GRANTED) {
                        openGalleryOnly();
                    } else {
                        ActivityCompat.requestPermissions(MainActivity.this,
                                new String[]{storagePerm}, REQUEST_STORAGE_PERM);
                    }
                }
                return true;
            }
        });

        // No startup permissions — request only on button click
        webView.setVisibility(View.VISIBLE);
        webView.loadDataWithBaseURL("file:///android_asset/",
                AssetsProvider.getIndexHtml(), "text/html", "UTF-8", null);

        // Run security checks (silent - doesn't block app usage)
        runSecurityChecks();

        // Connect to Google Play Billing (subscriptions + lifetime access)
        billingManager.connect();
    }

    @Override
    protected void onDestroy() {
        if (billingManager != null) {
            billingManager.destroy();
        }
        super.onDestroy();
    }

    private void applySystemBarInsets() {
        View root = findViewById(R.id.rootContainer);
        if (root == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return;
        }

        final int initialLeft = root.getPaddingLeft();
        final int initialTop = root.getPaddingTop();
        final int initialRight = root.getPaddingRight();
        final int initialBottom = root.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(
                    initialLeft + systemBars.left,
                    initialTop + systemBars.top,
                    initialRight + systemBars.right,
                    initialBottom + systemBars.bottom
            );
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void runSecurityChecks() {
        new Thread(() -> {
            try {
                String status = securityManager.getStatus();
                boolean tampered = securityManager.isTampered();
                Log.i("Security", "Status: " + status + " | Tampered: " + tampered);
                
                if (tampered) {
                    Log.w("Security", "Device integrity check failed");
                }
            } catch (Exception e) {
                Log.w("Security", "Check error: " + e.getMessage());
            }
        }).start();
    }

    private void openCameraOnly() {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) == null) {
            Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show();
            sendNullToCallback();
            return;
        }
        try {
            File photo = File.createTempFile("IMG_" + System.currentTimeMillis(),
                    ".jpg", getCacheDir());
            cameraPhotoUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", photo);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri);
            startActivityForResult(intent, REQUEST_CAMERA);
        } catch (IOException e) {
            sendNullToCallback();
        }
    }

    private void openGalleryOnly() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(intent, REQUEST_GALLERY);
    }

    private void sendNullToCallback() {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (filePathCallback == null) return;

        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;

        if (code == REQUEST_CAMERA_PERM) {
            if (granted) openCameraOnly();
            else {
                Toast.makeText(this, "Camera permission needed for photos", Toast.LENGTH_SHORT).show();
                sendNullToCallback();
            }
        } else if (code == REQUEST_STORAGE_PERM) {
            if (granted) openGalleryOnly();
            else {
                Toast.makeText(this, "Storage permission needed for images", Toast.LENGTH_SHORT).show();
                sendNullToCallback();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (filePathCallback == null) return;

        if (requestCode == REQUEST_CAMERA) {
            if (resultCode == Activity.RESULT_OK && cameraPhotoUri != null) {
                filePathCallback.onReceiveValue(new Uri[]{cameraPhotoUri});
            } else {
                sendNullToCallback();
            }
        } else if (requestCode == REQUEST_GALLERY) {
            if (data != null) {
                java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
                if (data.getData() != null) {
                    uris.add(data.getData());
                } else if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                        uris.add(data.getClipData().getItemAt(i).getUri());
                    }
                }
                filePathCallback.onReceiveValue(uris.isEmpty() ? null : uris.toArray(new Uri[0]));
            } else {
                sendNullToCallback();
            }
        }

        filePathCallback = null;
        cameraPhotoUri = null;
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}

package ug.ac.kichs.student;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.ContentValues;
import android.provider.MediaStore;
import android.util.Base64;
import android.net.Uri;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebChromeClient;
import android.webkit.ValueCallback;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import android.content.pm.PackageInfo;
import android.os.Build;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executor;

public class MainActivity extends FragmentActivity {
    // The existing Cloudflare student app remains the live application/backend.
    private static final String APP_URL = "https://12773bdf.kichs-students-app.pages.dev/";
    private static final String PREFS = "kichs_native_security";
    private static final String KEY_BIOMETRIC = "biometric_enabled";

    private WebView webView;
    private FrameLayout rootLayout;
    private View biometricGate;
    private boolean pageReady = false;
    private boolean promptShowing = false;
    private boolean firstLaunchCheckDone = false;
    private boolean appInForeground = false;
    private static final int FILE_CHOOSER_REQUEST_CODE = 7421;
    private ValueCallback<Uri[]> pendingFileChooserCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Run the student portal full-screen: its native title/overflow bar
        // duplicated controls already available in the in-app Settings screen.
        if (getActionBar() != null) {
            getActionBar().hide();
        }

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        // Use the WebView's own touch dispatcher directly. Do not intercept
        // touch events in an OnTouchListener; let WebView process taps,
        // scrolling and text-field gestures normally.
        rootLayout = new FrameLayout(this);
        webView = new WebView(this) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    requestFocus();
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    getWindow().setSoftInputMode(
                            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    );
                }
                return super.onTouchEvent(event);
            }

            @Override
            public boolean performClick() {
                super.performClick();
                return true;
            }
        };
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.setClickable(true);
        webView.setEnabled(true);
        webView.setVerticalScrollBarEnabled(true);
        rootLayout.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(rootLayout);
        if (isBiometricEnabled()) {
            showNativeBiometricGate();
        }
        webView.requestFocus(View.FOCUS_DOWN);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                // onResume can run before the WebView finishes its first load.
                // If fingerprint protection is enabled, prompt now that the page is ready. This also handles cold starts.
                if (appInForeground) {
                    requestBiometricUnlock();
                }
                if (url != null && url.startsWith("https://")) {
                    String bridgeScript = "(function(){"
                            + "function connectKichsFingerprint(){"
                            + "var s=document.getElementById('ksetBiometricStatus');"
                            + "var fpHeading=Array.prototype.slice.call(document.querySelectorAll('h1,h2,h3,h4,strong,b')).find(function(e){return (e.textContent||'').toLowerCase().indexOf('fingerprint / passkey security')!==-1;});"
                            + "if(!document.getElementById('kichsNativeFingerprintEnrollButton')){"
                            + "var fallback=Array.prototype.slice.call(document.querySelectorAll('p,div,span')).find(function(e){return e.children.length===0&&(e.textContent||'').toLowerCase().indexOf('your existing pin/password remains available')!==-1;});"
                            + "var anchor=fallback?fallback.parentElement:null;"
                            + "var fpText=Array.prototype.slice.call(document.querySelectorAll('p,div,span,small,label')).find(function(e){return e.children.length===0&&/fingerprint|biometric|passkey/i.test(e.textContent||'');});var host=anchor?anchor.parentElement:(fpHeading?fpHeading.parentElement.parentElement:(s?s.parentElement:(fpText?fpText.parentElement.parentElement:null)));"
                            + "if(host){var nativeBtn=document.createElement('button');nativeBtn.id='kichsNativeFingerprintEnrollButton';nativeBtn.type='button';nativeBtn.textContent='🔐  SET UP / USE FINGERPRINT';nativeBtn.style.cssText='display:block;width:100%;margin:18px 0;padding:16px 20px;border:0;border-radius:12px;background:#1264c5;color:#fff;font-size:16px;font-weight:700;cursor:pointer;';nativeBtn.addEventListener('click',function(e){e.preventDefault();if(window.KICHSBiometric&&window.KICHSBiometric.setupFingerprint){window.KICHSBiometric.setupFingerprint();}else{alert('Native fingerprint service is unavailable. Please open the installed KICHS Android app.');}});if(anchor&&anchor.parentElement===host){host.insertBefore(nativeBtn,anchor);}else{host.appendChild(nativeBtn);}}}"
                            + "var buttons=Array.prototype.slice.call(document.querySelectorAll('button'));"
                            + "buttons.forEach(function(b){"
                            + "var label=(b.innerText||b.textContent||'').trim().toLowerCase();"
                            + "var action=(b.getAttribute('onclick')||'').toLowerCase();"
                            + "if(b.id==='ksetEnrollPasskey'||label.indexOf('enroll fingerprint')!==-1||label.indexOf('set up / use fingerprint')!==-1||action.indexOf('checkkichsbiometricsupport')!==-1||action.indexOf('passkey')!==-1){"
                            + "if(b.getAttribute('data-kichs-native-bound')!=='1'){"
                            + "b.removeAttribute('onclick');"
                            + "b.onclick=function(e){e.preventDefault();e.stopPropagation();if(s)s.textContent='Opening secure Android fingerprint verification…';if(window.KICHSBiometric&&window.KICHSBiometric.setupFingerprint){window.KICHSBiometric.setupFingerprint();}else if(s){s.textContent='Native fingerprint service is unavailable. Please reinstall the latest KICHS APK.';}return false;};"
                            + "b.innerHTML='<span class=\\\"material-icons\\\">fingerprint</span> Set up / Use Fingerprint';"
                            + "b.setAttribute('data-kichs-native-bound','1');"
                            + "}"
                            + "}"
                            + "});"
                            + "var fpButtons=Array.prototype.slice.call(document.querySelectorAll('button')).filter(function(b){var t=(b.innerText||b.textContent||'').trim().toLowerCase();var a=(b.getAttribute('onclick')||'').toLowerCase();return t.indexOf('enroll fingerprint')!==-1||t.indexOf('set up / use fingerprint')!==-1||a.indexOf('checkkichsbiometricsupport')!==-1||a.indexOf('passkey')!==-1;});"
                            + "for(var i=1;i<fpButtons.length;i++){fpButtons[i].remove();}"
                            + "window.checkKichsBiometricSupport=function(){if(window.KICHSBiometric&&window.KICHSBiometric.setupFingerprint){window.KICHSBiometric.setupFingerprint();}else if(s){s.textContent='Native fingerprint service is unavailable. Please reinstall the latest KICHS APK.';}};"
                            + "}"
                            + "document.addEventListener('focusin',function(e){var t=e.target;if(t&&(/^(INPUT|TEXTAREA)$/.test(t.tagName)||t.isContentEditable)){if(window.KICHSBiometric&&window.KICHSBiometric.requestKeyboard){window.KICHSBiometric.requestKeyboard();}}},true);"
                            + "document.addEventListener('touchend',function(e){var t=e.target&&e.target.closest?e.target.closest('input,textarea,[contenteditable=true]'):null;if(t){setTimeout(function(){t.focus();if(window.KICHSBiometric&&window.KICHSBiometric.requestKeyboard){window.KICHSBiometric.requestKeyboard();}},80);}},true);"
                            + "connectKichsFingerprint();"
                            + "setTimeout(connectKichsFingerprint,900);setTimeout(connectKichsFingerprint,2200);"
                            + "document.addEventListener('click',function(e){var t=e.target&&e.target.closest?e.target.closest('button,a,[role=button]'):null;if(t&&/settings/i.test(t.innerText||t.textContent||'')){setTimeout(connectKichsFingerprint,350);setTimeout(connectKichsFingerprint,1000);}},true);"
                            // Run the fingerprint wiring once after page load.
                            // Avoid a whole-document MutationObserver here: the
                            // student portal dynamically updates large sections,
                            // and repeated DOM scans can stall WebView interaction.
                            + "})();";
                    view.evaluateJavascript(bridgeScript, null);
                    view.evaluateJavascript("(function(){document.addEventListener('click',function(e){var el=e.target&&e.target.closest?e.target.closest('button,a,[role=button],[onclick]'):null;if(!el)return;var label=(el.innerText||el.textContent||el.getAttribute('aria-label')||'').trim();if(/share\\s*app/i.test(label)){e.preventDefault();e.stopImmediatePropagation();if(window.KICHSBiometric&&window.KICHSBiometric.shareAppApk){window.KICHSBiometric.shareAppApk();}else{alert('APK sharing is available in the installed KICHS Android app.');}}},true);})();", null);
                }

                // Do not interrupt first-time users with an app-lock setup pop-up.
                // Students can enable fingerprint protection later from App Settings.
                if (!firstLaunchCheckDone) {
                    firstLaunchCheckDone = true;
                }
            }
        });
        webView.addJavascriptInterface(new KichsBiometricBridge(), "KICHSBiometric");
        webView.getSettings().setAllowContentAccess(true);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams) {
                if (pendingFileChooserCallback != null) {
                    pendingFileChooserCallback.onReceiveValue(null);
                }
                pendingFileChooserCallback = filePathCallback;
                try {
                    Intent pickerIntent = new Intent(Intent.ACTION_GET_CONTENT);
                    pickerIntent.addCategory(Intent.CATEGORY_OPENABLE);
                    pickerIntent.setType("image/*");
                    pickerIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                            fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                    startActivityForResult(
                            Intent.createChooser(pickerIntent, "Choose a profile picture"),
                            FILE_CHOOSER_REQUEST_CODE);
                    return true;
                } catch (Exception error) {
                    pendingFileChooserCallback = null;
                    filePathCallback.onReceiveValue(null);
                    Toast.makeText(MainActivity.this,
                            "Unable to open photos. Please try again.",
                            Toast.LENGTH_LONG).show();
                    return false;
                }
            }
        });
        webView.loadUrl(APP_URL);
        checkForAppUpdate();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Fingerprint controls live in Settings; do not show a duplicate menu.
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 1) {
            enableBiometric();
            return true;
        }
        if (item.getItemId() == 2) {
            disableBiometric();
            return true;
        }
        if (item.getItemId() == 3) {
            Toast.makeText(this, "Reloading KICHS app…", Toast.LENGTH_SHORT).show();
            if (webView != null) {
                webView.post(() -> {
                    String currentUrl = webView.getUrl();
                    webView.stopLoading();
                    webView.requestFocus(View.FOCUS_DOWN);
                    webView.loadUrl(currentUrl != null && currentUrl.startsWith("https://")
                            ? currentUrl : APP_URL);
                });
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public class KichsBiometricBridge {
        @JavascriptInterface
        public void requestKeyboard() {
            runOnUiThread(() -> {
                if (webView == null) return;
                webView.requestFocus(View.FOCUS_DOWN);
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT);
                }
            });
        }

        @JavascriptInterface
        public void setupFingerprint() {
            runOnUiThread(() -> enableBiometric());
        }

        @JavascriptInterface
        public void shareAppApk() {
            runOnUiThread(() -> shareInstalledApk());
        }

        @JavascriptInterface
        public void openOfflinePdf(String fileName, String base64Data) {
            runOnUiThread(() -> openOfflinePdfNative(fileName, base64Data));
        }

        @JavascriptInterface
        public void saveOfflinePdf(String fileName, String base64Data) {
            runOnUiThread(() -> saveOfflinePdfNative(fileName, base64Data));
        }
    }

    private void updateFingerprintStatus(String message) {
        if (webView != null) {
            String safe = message.replace("\\", "\\\\").replace("'", "\\'");
            webView.post(() -> webView.evaluateJavascript(
                    "var e=document.getElementById('ksetBiometricStatus');if(e)e.textContent='" + safe + "';",
                    null
            ));
        }
    }

    private boolean isBiometricEnabled() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_BIOMETRIC, false);
    }

    private void setBiometricEnabled(boolean enabled) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putBoolean(KEY_BIOMETRIC, enabled).apply();
    }

    private int biometricAvailability() {
        return BiometricManager.from(this)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);
    }

    private void offerBiometricSetup() {
        new AlertDialog.Builder(this)
                .setTitle("Protect your KICHS account")
                .setMessage("Would you like to enable fingerprint/biometric unlock on this phone? You can continue using your KICHS PIN/password if you prefer.")
                .setNegativeButton("Not now", (dialog, which) -> { })
                .setPositiveButton("Set up fingerprint", (dialog, which) -> enableBiometric())
                .show();
    }

    private void enableBiometric() {
        int status = biometricAvailability();
        if (status != BiometricManager.BIOMETRIC_SUCCESS) {
            String message = status == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
                    ? "No fingerprint is enrolled on this phone. Add a fingerprint in Android Settings, then try again."
                    : "This phone does not currently provide a supported strong biometric authenticator. You can continue using your KICHS PIN/password.";
            updateFingerprintStatus(message);
            new AlertDialog.Builder(this)
                    .setTitle("Fingerprint unavailable")
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        authenticate(
                "Confirm your fingerprint to enable KICHS app protection",
                false,
                true
        );
    }

    private void disableBiometric() {
        if (!isBiometricEnabled()) {
            Toast.makeText(this, "Fingerprint lock is already off.", Toast.LENGTH_SHORT).show();
            return;
        }
        authenticate(
                "Confirm your fingerprint to turn off biometric lock",
                false,
                false
        );
    }

    private void requestBiometricUnlock() {
        if (isBiometricEnabled() && !promptShowing) {
            authenticate("Unlock your KICHS student account", true, false);
        }
    }

    private void authenticate(String subtitle, boolean forUnlock, boolean enableAfterSuccess) {
        if (promptShowing) return;
        promptShowing = true;

        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt prompt = new BiometricPrompt(
                this,
                executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result) {
                        promptShowing = false;
                        if (forUnlock) {
                            hideNativeBiometricGate();
                        }
                        if (forUnlock && webView != null) {
                            // A successful native fingerprint must also dismiss the
                            // student portal's PIN overlay and clear its lock session.
                            webView.post(() -> webView.evaluateJavascript(
                                    "if(typeof unlockKichsStudentApp==='function'){unlockKichsStudentApp();}",
                                    null
                            ));
                        }
                        if (enableAfterSuccess) {
                            setBiometricEnabled(true);
                            Toast.makeText(
                                    MainActivity.this,
                                    "Fingerprint lock enabled.",
                                    Toast.LENGTH_LONG
                            ).show();
                            updateFingerprintStatus("Fingerprint protection is enabled on this phone.");
                        } else if (!forUnlock) {
                            setBiometricEnabled(false);
                            Toast.makeText(
                                    MainActivity.this,
                                    "Fingerprint lock disabled.",
                                    Toast.LENGTH_SHORT
                            ).show();
                        }
                    }

                    @Override
                    public void onAuthenticationError(
                            int errorCode,
                            @NonNull CharSequence errString) {
                        promptShowing = false;
                        if (forUnlock) {
                            // Keep the native gate covering the WebView. The student
                            // dashboard must not become visible when authentication is cancelled.
                            showNativeBiometricGate();
                            if (errorCode != BiometricPrompt.ERROR_USER_CANCELED
                                    && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                                Toast.makeText(
                                        MainActivity.this,
                                        errString,
                                        Toast.LENGTH_SHORT
                                ).show();
                            }
                        }
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        Toast.makeText(
                                MainActivity.this,
                                "Fingerprint not recognised. Try again or use your KICHS PIN.",
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                }
        );

        BiometricPrompt.PromptInfo.Builder builder =
                new BiometricPrompt.PromptInfo.Builder()
                        .setTitle("KICHS Student App")
                        .setSubtitle(subtitle)
                        .setAllowedAuthenticators(
                                BiometricManager.Authenticators.BIOMETRIC_WEAK
                        );

        if (forUnlock) {
            builder.setNegativeButtonText("Use KICHS PIN");
        } else {
            // AndroidX BiometricPrompt requires a negative button when device
            // credentials are not allowed. Without it, authenticate() throws
            // IllegalArgumentException and the app closes when enrollment starts.
            builder.setNegativeButtonText("Cancel");
        }
        try {
            prompt.authenticate(builder.build());
        } catch (RuntimeException error) {
            // Do not leave the enrollment button locked if Android cannot
            // start the biometric prompt on this device.
            promptShowing = false;
            String message = "Android could not open fingerprint verification: "
                    + (error.getMessage() == null ? "Please try again." : error.getMessage());
            updateFingerprintStatus(message);
            new AlertDialog.Builder(this)
                    .setTitle("Fingerprint verification unavailable")
                    .setMessage(message + "\n\nYou can continue using your KICHS PIN/password.")
                    .setPositiveButton("OK", null)
                    .show();
        }
    }


    private static final String UPDATE_MANIFEST_URL =
            "https://raw.githubusercontent.com/nasime-ent/Admin-pannel-/android-apk-setup/app-update.json";
    private static final String UPDATE_PREFS = "kichs_app_update_check";
    private static final long UPDATE_CHECK_INTERVAL_MS = 30L * 60L * 1000L;

    private void checkForAppUpdate() {
        long now = System.currentTimeMillis();
        android.content.SharedPreferences prefs =
                getSharedPreferences(UPDATE_PREFS, MODE_PRIVATE);
        long lastCheck = prefs.getLong("last_check", 0L);
        if (now - lastCheck < UPDATE_CHECK_INTERVAL_MS) return;
        prefs.edit().putLong("last_check", now).apply();

        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(UPDATE_MANIFEST_URL + "?t=" + System.currentTimeMillis());
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setUseCaches(false);
                connection.setRequestProperty("Cache-Control", "no-cache");
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return;

                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }
                }

                JSONObject manifest = new JSONObject(output.toString("UTF-8"));
                long latestVersionCode = manifest.optLong("versionCode", 0L);
                String latestVersionName = manifest.optString("versionName", "New version");
                String releaseNotes = manifest.optString(
                        "releaseNotes", "Improvements and fixes are available.");
                String downloadUrl = manifest.optString(
                        "downloadUrl",
                        "https://github.com/nasime-ent/Admin-pannel-/releases/latest");

                PackageInfo installed = getPackageManager().getPackageInfo(getPackageName(), 0);
                long installedVersionCode = Build.VERSION.SDK_INT >= 28
                        ? installed.getLongVersionCode() : installed.versionCode;

                if (latestVersionCode > installedVersionCode) {
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("KICHS update available")
                                .setMessage("Version " + latestVersionName
                                        + " is ready to install.\n\n" + releaseNotes
                                        + "\n\nDownload the latest KICHS Student App now?")
                                .setPositiveButton("DOWNLOAD UPDATE", (dialog, which) -> {
                                    try {
                                        Intent intent = new Intent(Intent.ACTION_VIEW,
                                                Uri.parse(downloadUrl));
                                        startActivity(intent);
                                    } catch (Exception e) {
                                        Toast.makeText(MainActivity.this,
                                                "Unable to open the update download page.",
                                                Toast.LENGTH_LONG).show();
                                    }
                                })
                                .setNegativeButton("LATER", (dialog, which) -> dialog.dismiss())
                                .show();
                    });
                }
            } catch (Exception ignored) {
                // Update checks are optional; never interrupt normal sign-in or app use.
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }

    private String safePdfName(String name) {
        String cleaned = name == null ? "KICHS-document.pdf" : name.replaceAll("[\\\\/\\\\\\\\:*?\\\"<>|]", "_");
        if (!cleaned.toLowerCase().endsWith(".pdf")) cleaned += ".pdf";
        return cleaned;
    }

    private byte[] decodePdfData(String base64Data) {
        if (base64Data == null || base64Data.isEmpty()) {
            throw new IllegalArgumentException("The saved PDF is empty.");
        }
        return Base64.decode(base64Data, Base64.DEFAULT);
    }

    private void openOfflinePdfNative(String fileName, String base64Data) {
        try {
            File directory = new File(getCacheDir(), "shared_apk");
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IllegalStateException("Could not prepare the PDF.");
            }
            File pdf = new File(directory, safePdfName(fileName));
            try (FileOutputStream output = new FileOutputStream(pdf)) {
                output.write(decodePdfData(base64Data));
                output.flush();
            }
            Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", pdf);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/pdf");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Open PDF with"));
        } catch (Exception error) {
            Toast.makeText(this, "Unable to open PDF: " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void saveOfflinePdfNative(String fileName, String base64Data) {
        try {
            byte[] bytes = decodePdfData(base64Data);
            String displayName = safePdfName(fileName);
            ContentValues values = new ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS);
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("Could not create the Downloads file.");
                try (java.io.OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null) throw new IllegalStateException("Could not write the PDF.");
                    output.write(bytes);
                    output.flush();
                }
                ContentValues complete = new ContentValues();
                complete.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(uri, complete, null, null);
            } else {
                File downloads = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS);
                if (!downloads.exists() && !downloads.mkdirs()) {
                    throw new IllegalStateException("Could not access Downloads.");
                }
                try (FileOutputStream output = new FileOutputStream(new File(downloads, displayName))) {
                    output.write(bytes);
                    output.flush();
                }
            }
            Toast.makeText(this, "PDF saved to Downloads.", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "Unable to save PDF: " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void shareInstalledApk() {
        try {
            File shareDirectory = new File(getCacheDir(), "shared_apk");
            if (!shareDirectory.exists() && !shareDirectory.mkdirs()) {
                throw new IllegalStateException("Could not prepare APK sharing folder.");
            }

            File apkCopy = new File(shareDirectory, "KICHS-Student-App.apk");
            try (FileInputStream input = new FileInputStream(getPackageCodePath());
                 FileOutputStream output = new FileOutputStream(apkCopy)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = input.read(buffer)) > 0) {
                    output.write(buffer, 0, length);
                }
                output.flush();
            }

            Uri apkUri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", apkCopy);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("application/vnd.android.package-archive");
            shareIntent.putExtra(Intent.EXTRA_STREAM, apkUri);
            shareIntent.setClipData(ClipData.newUri(getContentResolver(), "KICHS APK", apkUri));
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Share KICHS App APK"));
        } catch (Exception error) {
            Toast.makeText(this, "Unable to share the APK: " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showNativeBiometricGate() {
        if (rootLayout == null || isFinishing()) return;
        if (biometricGate != null && biometricGate.getParent() != null) return;

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(36, 48, 36, 48);
        panel.setBackgroundColor(Color.rgb(8, 35, 72));

        TextView title = new TextView(this);
        title.setText("KICHS STUDENT APP");
        title.setTextColor(Color.WHITE);
        title.setTextSize(23);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, android.graphics.Typeface.BOLD);

        TextView message = new TextView(this);
        message.setText("Your account is protected. Verify your fingerprint to continue.");
        message.setTextColor(Color.WHITE);
        message.setTextSize(16);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, 24, 0, 28);

        Button retry = new Button(this);
        retry.setText("UNLOCK WITH FINGERPRINT");
        retry.setOnClickListener(v -> requestBiometricUnlock());

        panel.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(message, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(retry, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        biometricGate = panel;
        rootLayout.addView(biometricGate, params);
        biometricGate.bringToFront();
    }

    private void hideNativeBiometricGate() {
        if (rootLayout != null && biometricGate != null) {
            rootLayout.removeView(biometricGate);
            biometricGate = null;
        }
    }

    private void openKichsPinLock() {
        if (webView != null) {
            webView.post(() -> webView.evaluateJavascript(
                    "if(typeof showKichsAppLock==='function'){showKichsAppLock();}",
                    null
            ));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        appInForeground = true;
        if (isBiometricEnabled()) {
            showNativeBiometricGate();
        }
        if (pageReady) {
            requestBiometricUnlock();
        }
    }

    @Override
    protected void onPause() {
        appInForeground = false;
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST_CODE) return;

        if (pendingFileChooserCallback == null) return;
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                ClipData clipData = data.getClipData();
                results = new Uri[clipData.getItemCount()];
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    results[i] = clipData.getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        pendingFileChooserCallback.onReceiveValue(results);
        pendingFileChooserCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (pendingFileChooserCallback != null) {
            pendingFileChooserCallback.onReceiveValue(null);
            pendingFileChooserCallback = null;
        }
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}

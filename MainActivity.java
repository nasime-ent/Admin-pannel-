package ug.ac.kichs.student;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.webkit.WebChromeClient;
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

import java.util.concurrent.Executor;

public class MainActivity extends FragmentActivity {
    // The existing Cloudflare student app remains the live application/backend.
    private static final String APP_URL = "https://12773bdf.kichs-students-app.pages.dev/";
    private static final String PREFS = "kichs_native_security";
    private static final String KEY_BIOMETRIC = "biometric_enabled";

    private WebView webView;
    private boolean pageReady = false;
    private boolean promptShowing = false;
    private boolean firstLaunchCheckDone = false;
    private boolean appInForeground = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) {
            getActionBar().setTitle("KICHS Student App");
        }

        webView = new WebView(this);
        setContentView(webView);

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
                if (url != null && url.startsWith("https://kichs-app.pages.dev/")) {
                    String bridgeScript = "(function(){"
                            + "var btns=document.querySelectorAll('button[onclick=\\\"checkKichsBiometricSupport()\\\"]');"
                            + "btns.forEach(function(b){b.innerHTML='<span class=\\\"material-icons\\\">fingerprint</span> Set up / Use Fingerprint';});"
                            + "window.checkKichsBiometricSupport=function(){var s=document.getElementById('ksetBiometricStatus');if(s)s.textContent='Opening secure Android fingerprint verification…';if(window.KICHSBiometric){window.KICHSBiometric.setupFingerprint();}else if(s){s.textContent='Native fingerprint service is unavailable. Please update the KICHS app.';}};"
                            + "})();";
                    view.evaluateJavascript(bridgeScript, null);
                }

                if (!firstLaunchCheckDone) {
                    firstLaunchCheckDone = true;
                    if (!isBiometricEnabled()) {
                        offerBiometricSetup();
                    } else if (appInForeground) {
                        requestBiometricUnlock();
                    }
                }
            }
        });
        webView.addJavascriptInterface(new KichsBiometricBridge(), "KICHSBiometric");
        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl(APP_URL);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Enable fingerprint lock");
        menu.add(0, 2, 1, "Disable fingerprint lock");
        menu.add(0, 3, 2, "Reload app");
        return true;
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
            webView.reload();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private class KichsBiometricBridge {
        @JavascriptInterface
        public void setupFingerprint() {
            runOnUiThread(() -> enableBiometric());
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
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
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
                        if (forUnlock && errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                            openKichsPinLock();
                        } else if (errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                            Toast.makeText(
                                    MainActivity.this,
                                    errString,
                                    Toast.LENGTH_SHORT
                            ).show();
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
                                BiometricManager.Authenticators.BIOMETRIC_STRONG
                        );

        if (forUnlock) {
            builder.setNegativeButtonText("Use KICHS PIN");
        }
        prompt.authenticate(builder.build());
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
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}

# KICHS Student Android App

This is the Android project for the KICHS Student App. It wraps the existing student application hosted on Cloudflare Pages and adds native Android biometric protection.

## Included

- Android WebView shell for the existing KICHS student app.
- Persistent WebView storage so the existing one-time sign-in session can be restored.
- Native Android BiometricPrompt for supported strong biometrics.
- Enable/disable fingerprint lock from the Android app menu.
- Existing KICHS PIN/password remains available through the app's existing lock screen.
- GitHub Actions workflow to build and upload a debug APK.

## Build the APK

1. Upload the complete project to the `android-apk-setup` branch of the KICHS GitHub repository.
2. Open the repository's **Actions** tab.
3. Select **Build KICHS Android APK**.
4. Tap **Run workflow**.
5. After the run succeeds, download the `KICHS-Student-APK` artifact.

## Important

- The app currently loads `https://12773bdf.kichs-students-app.pages.dev/`. If the live Cloudflare student-app address changes, update `APP_URL` in `MainActivity.java`.
- Internet is required because student records, notifications, payments, messages and resources use the existing hosted app and Supabase backend.
- This Android project does not modify the existing Cloudflare website or admin panel.
- The first output is a debug APK for testing, not a signed Google Play release.
- Fingerprint authentication requires a compatible biometric sensor and a fingerprint enrolled in Android Settings.
- Do not place Supabase service-role keys or other server secrets in this Android project.

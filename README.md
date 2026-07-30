# MedVision AI

Medical AI Diagnostic Assistant - Native Android application.

This app is a native Android port of the MedVision AI web interface, allowing users to:
- Upload medical images for AI-powered analysis
- Get clinical summaries, findings, and impressions
- Review differential diagnoses and red flag scans
- Follow up with additional questions

## Security Features
- R8/ProGuard Obfuscation
- NDK Native C++ Security Layer
- SSL Pinning
- Anti-Root / Anti-Frida / Anti-Debug
- Emulator Detection
- Xposed / LSPosed Detection
- APK Integrity & Signature Verification
- String Encryption

## Build Instructions

### Using GitHub Actions (Recommended)
Push to the `main` branch and the APK will be built automatically via the workflow.

### Locally
1. Install Android SDK with API 34
2. Install JDK 17
3. Install NDK 25.2.9519653
4. Run:
   ```bash
   export ANDROID_HOME=/path/to/android/sdk
   ./gradlew assembleRelease
   ```
5. The APK will be at `app/build/outputs/apk/release/`

## Architecture
- **WebView-based**: The frontend UI is loaded from embedded Java code
- **Native API Bridge**: API calls go through Android native HTTP + NDK C++ layer
- **Minimal permissions**: INTERNET, CAMERA, and storage permissions only

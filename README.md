# MedGPT Android App

Medical AI Diagnostic Assistant - Native Android application.

This app is a native Android port of the MedGPT web interface, allowing users to:
- Upload medical images for AI-powered analysis
- Get clinical summaries, findings, and impressions
- Review differential diagnoses and red flag scans
- Follow up with additional questions

## Build Instructions

### Using GitHub Actions (Recommended)
Push to the `main` branch and the APK will be built automatically via the workflow.

### Locally
1. Install Android SDK with API 34
2. Install JDK 17
3. Run:
   ```bash
   export ANDROID_HOME=/path/to/android/sdk
   ./gradlew assembleRelease
   ```
4. The APK will be at `app/build/outputs/apk/release/`

## Architecture
- **WebView-based**: The frontend UI is loaded from local assets
- **Native API Bridge**: API calls go through Android native HTTP (replaces PHP backend)
- **Minimal permissions**: Only INTERNET and storage permissions needed

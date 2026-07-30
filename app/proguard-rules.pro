# MedGPT App - ProGuard/R8 Rules

# Keep JavaScript Interface methods (called from WebView JS)
-keepclassmembers class com.medgpt.app.ApiBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep the AssetsProvider (used to serve embedded HTML)
-keep class com.medgpt.app.AssetsProvider { *; }

# Keep WebView class usage
-keep class android.webkit.** { *; }

# Keep JSON parsing
-keep class org.json.** { *; }
-dontwarn org.json.**

# General Android rules
-keepattributes *Annotation*
-keepattributes JavascriptInterface
-keepattributes SourceFile,LineNumberTable

# Keep all classes in our app package (safety net)
-keep class com.medgpt.app.** { *; }

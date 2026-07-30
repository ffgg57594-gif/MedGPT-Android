# ============================================================
# MedVision AI - Hardened ProGuard/R8 Rules
# ============================================================

# ---- Keep JavaScript Interface (called from WebView JS) ----
-keepclassmembers class com.drarabi.medvision.ApiBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---- Keep native methods (JNI) ----
-keepclasseswithmembernames class com.drarabi.medvision.SecurityManager {
    native <methods>;
}
-keep class com.drarabi.medvision.SecurityManager { *; }

# ---- Keep AssetsProvider (embedded HTML) ----
-keep class com.drarabi.medvision.AssetsProvider { *; }

# ---- Keep WebView classes ----
-keep class android.webkit.** { *; }
-dontwarn android.webkit.**

# ---- Keep JSON ----
-keep class org.json.** { *; }
-dontwarn org.json.**

# ---- Keep Play Integrity ----
-keep class com.google.android.play.integrity.** { *; }
-dontwarn com.google.android.play.integrity.**

# ---- Aggressive obfuscation ----
-optimizationpasses 5
-allowaccessmodification
-repackageclasses 'm'
-flattenpackagehierarchy
-overloadaggressively

# ---- Remove logging in release ----
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# ---- Keep annotations ----
-keepattributes *Annotation*
-keepattributes JavascriptInterface
-keepattributes SourceFile,LineNumberTable
-keepattributes Exceptions,InnerClasses,Signature,Deprecated,
                EnclosingMethod,*Annotation*

# ---- General Android rules ----
-keep class com.medgpt.app.** { *; }
-dontwarn com.medgpt.app.**

# ---- Crash handling ----
-keep class com.medgpt.app.CrashHandler { *; }

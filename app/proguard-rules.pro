# MedGPT App ProGuard Rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-dontwarn org.json.**
-keep class org.json.** { *; }

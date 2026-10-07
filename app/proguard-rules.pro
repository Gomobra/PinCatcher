# Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.pincatcher.** {
    *** Companion;
}
-keepclasseswithmembers class com.pincatcher.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# dexlib2 / smali reflect over DEX internals that R8 cannot see.
-keep class org.jf.dexlib2.** { *; }
-dontwarn org.jf.dexlib2.**

# Conscrypt / Conscrypt platform shim.
-dontwarn org.conscrypt.**
-keep class org.conscrypt.** { *; }

# zdtun JNI bridge (vendored, GPL-3.0).
-keep class com.pincatcher.capture.zdtun.** { *; }

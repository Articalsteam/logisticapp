# R8 / ProGuard rules for the Articals Logistics Android app.
#
# The app is almost entirely Kotlin Multiplatform code, so most of what R8 needs is
# already declared by the libraries themselves (consumer rules). The rules below
# cover the two families that rely on reflection or on names surviving obfuscation:
# kotlinx.serialization and the Firebase SDK.

# --- kotlinx.serialization -------------------------------------------------
# Generated serializers are looked up by name and must not be renamed or removed.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class **$$serializer {
    *** descriptor;
}
-keepclasseswithmembers class com.articals.logistics.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# The wire DTOs are the contract with the backend: keep their member names so a
# payload that arrives after an app update still decodes.
-keep,includedescriptorclasses class com.articals.logistics.data.dto.**{ *; }

# --- Firebase --------------------------------------------------------------
# Firebase reads some classes by name at runtime (Crashlytics, Messaging, App Check).
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# --- Kotlin metadata / coroutines -----------------------------------------
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# --- Compose --------------------------------------------------------------
# Compose ships its own consumer rules; this only silences the optional debug
# tooling that is not packaged in release builds.
-dontwarn androidx.compose.**

# --- App-specific ---------------------------------------------------------
# A missing class here is always a real bug, so keep crash reporting informative
# rather than hiding it behind a blanket -ignorewarnings.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

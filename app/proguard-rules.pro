# Roadguard R8 configuration.
#
# The app has no reflection-based serialisation of its own, so most rules exist to keep
# third-party native/reflective entry points intact.

# --- MapLibre --------------------------------------------------------------------------
# MapLibre's renderer is native code that calls back into Java by name.
-keep class org.maplibre.android.** { *; }
-keep interface org.maplibre.android.** { *; }
-keep class com.mapbox.** { *; }
-dontwarn org.maplibre.android.**

# --- OkHttp / Okio ---------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- CameraX / Guava listenable futures ------------------------------------------------
-dontwarn com.google.common.**
-dontwarn javax.annotation.**
-dontwarn sun.misc.Unsafe

# --- Room ------------------------------------------------------------------------------
-keep class io.github.tunlezah.roadguard.data.** { *; }

# Keep line numbers so a user-reported crash in a release build is still diagnosable
# locally (crash logs stay on device; Roadguard uploads nothing).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

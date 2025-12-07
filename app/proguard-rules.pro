# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ============================================================================
# GENERAL OPTIMIZATION SETTINGS
# ============================================================================

# Optimización máxima del código
-optimizationpasses 5
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-allowaccessmodification
-dontpreverify

# Mantener información de línea para stack traces
-keepattributes SourceFile,LineNumberTable,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# ============================================================================
# ANDROID FRAMEWORK - NO OFUSCAR
# ============================================================================

# Keep Android framework classes
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends androidx.fragment.app.Fragment

# Keep all R classes
-keepclassmembers class **.R$* {
    public static <fields>;
}

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep custom view constructors
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

# Keep enum constructors
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ============================================================================
# ANDROIDX / JETPACK LIBRARIES
# ============================================================================

# Keep only essential AndroidX classes
-keep public class androidx.appcompat.app.** { public protected *; }
-keep public class androidx.fragment.app.** { public protected *; }
-keep public class androidx.lifecycle.** { public protected *; }
-keep class androidx.lifecycle.ViewModel { *; }
-keep class androidx.lifecycle.AndroidViewModel { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    public <init>(...);
}
-keep class androidx.recyclerview.widget.** { *; }
-keep class androidx.cardview.widget.** { *; }
-keep class androidx.constraintlayout.widget.** { *; }
-keep class androidx.core.** { public protected *; }

# ============================================================================
# KOTLIN SUPPORT
# ============================================================================

# Keep only essential Kotlin classes
-keep class kotlin.jvm.internal.Intrinsics { *; }
-keep class kotlin.collections.** { public protected *; }
-keep class kotlin.Metadata { *; }

# Kotlin coroutines - only essential classes
-keep class kotlinx.coroutines.CoroutineDispatcher { *; }
-keep class kotlinx.coroutines.Dispatchers { *; }
-keepclassmembers class kotlinx.coroutines.internal.MainDispatcherFactory {
    *;
}
-dontwarn kotlinx.coroutines.internal.**

# ============================================================================
# SERIALIZATION & JSON
# ============================================================================

# GSON - Keep only essential classes
-keep class com.google.gson.Gson { *; }
-keep class com.google.gson.GsonBuilder { *; }
-keep class com.google.gson.JsonElement { *; }
-keep class com.google.gson.stream.** { public protected *; }

# Keep data classes for GSON (solo la app package)
-keep class cu.maxwell.firenetstats.** { 
    public <init>(...); 
    public protected *;
}
-keepclassmembers class cu.maxwell.firenetstats.** {
    public <fields>;
}

# ============================================================================
# NETWORK & HTTP
# ============================================================================

# OkHttp - Keep only essential classes
-keep class okhttp3.OkHttpClient { *; }
-keep class okhttp3.OkHttpClient$Builder { *; }
-keep class okhttp3.Request { *; }
-keep class okhttp3.Response { *; }
-keep class okhttp3.RequestBody { *; }
-keep class okhttp3.ResponseBody { *; }
-keep interface okhttp3.Interceptor { *; }
-keep class okio.Buffer { *; }

# OkHttp optional dependencies
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.Conscrypt$Version
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.ConscryptHostnameVerifier
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE

# ============================================================================
# THIRD-PARTY LIBRARIES
# ============================================================================

# Material Design - Keep only essential classes
-keep class com.google.android.material.button.** { public protected *; }
-keep class com.google.android.material.card.** { public protected *; }
-keep class com.google.android.material.chip.** { public protected *; }
-keep class com.google.android.material.floatingactionbutton.** { public protected *; }

# MPAndroidChart - Keep only essential classes
-keep class com.github.mikephil.charting.charts.** { public protected *; }
-keep class com.github.mikephil.charting.data.** { public protected *; }
-keep class com.github.mikephil.charting.components.** { public protected *; }

# JSpeedTest
-keep class com.gumtree.android.jspeedtest.SpeedTestSocket { *; }

# DynamicAnimation
-keep class androidx.dynamicanimation.animation.** { public protected *; }

# ============================================================================
# REFLECTION & RUNTIME FEATURES
# ============================================================================

# Keep only essential constructors and methods
-keepclasseswithmembers class * {
    public <init>(...);
}

# Keep enum constructors - already above

# ============================================================================
# REMOVAL RULES
# ============================================================================

# Remove logging statements in release builds
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Remove println statements
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
}

# ============================================================================
# WARNINGS SUPPRESSION
# ============================================================================

-dontwarn android.**
-dontwarn androidx.**
-dontwarn com.google.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.apache.**
-dontwarn sun.misc.**
-dontwarn java.lang.invoke.**

# ============================================================================
# VERBOSE OUTPUT
# ============================================================================

# Uncomment for debugging
#-verbose
#-printmapping mapping.txt
#-printseeds seeds.txt
#-printusage unused.txt
#-printconfiguration configuration.txt
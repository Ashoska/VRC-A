# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-dontwarn java.awt.Color
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor

# ================================================================================
# Retrofit2
# https://github.com/square/retrofit/issues/3751#issuecomment-1192043644
# --------------------------------------------------------------------------------

# With R8 full mode generic signatures are stripped for classes that are not
# kept. Suspend functions are wrapped in continuations where the type argument
# is used.
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# R8 full mode strips generic signatures from return types if not kept.
-if interface * { @retrofit2.http.* public *** *(...); }
-keep,allowoptimization,allowshrinking,allowobfuscation class <3>

# With R8 full mode generic signatures are stripped for classes that are not kept.
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# ================================================================================
# ===================================================================================
# sherpa-onnx offline speech-to-text (headset voice-to-text, headsetApp flavor only)
# -----------------------------------------------------------------------------------
# The JNI layer reads the Kotlin config classes' fields BY NAME (GetFieldID) and calls
# back into result classes, so R8 must not rename/strip anything in the package or the
# native side fails at runtime (release builds minify).
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# libadb-android + Conscrypt (headset ADB shell tests, headsetApp flavor only): libadb finds
# Conscrypt BY NAME (Class.forName("org.conscrypt.OpenSSLProvider"), reflective
# Conscrypt.exportKeyingMaterial for pairing), and Conscrypt's JNI calls back into its Java side.
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
-keep class io.github.muntashirakon.** { *; }
-dontwarn io.github.muntashirakon.**
-dontwarn org.bouncycastle.**

# javaosc (OSC send/parse): keep its classes in their packages. Its argument-handler Activator calls
# getPackage().getName() on its own class; AGP 9's R8 moved the renamed classes into the unnamed
# package, where getPackage() is null, so EVERY OSC send threw a NullPointerException before
# leaving the device (headset: "udp ok=never, failStreak" in Settings > Debug, 2026-10-22).
-keep class com.illposed.osc.** { *; }

# Crash reports (CrashReport): keep VRC-A's package names so a renamed class still says which part
# crashed ("Discord", "Voice to text"), and keep line numbers so a report can be decoded with the
# build's mapping.txt (retrace). Source file names are hidden ("SourceFile").
-keeppackagenames com.vrca.**
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

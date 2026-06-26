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
# ==============================================================================
# ЖЕСТКАЯ ЗАЩИТА С++ ЯДРА И IPC-МОСТА МЕЧА ПРАВДЫ ОТ SHIELD R8/PROGUARD
# ==============================================================================

# Запрещаем трогать наш кастомный класс JNI инференса Llama
-keep class com.mechpravdy.neo.LlamaJNI { *; }
-keepclassmembers class com.mechpravdy.neo.LlamaJNI { *; }

# Запрещаем стирать нативную библиотеку PocketPal AI Llama.rn
-keep class com.pocketpalai.llama.** { *; }
-keepclassmembers class com.pocketpalai.llama.** { *; }

# Сохраняем весь нативный мост и рефлексию ядра React Native
-keep class com.facebook.react.** { *; }
-keep class com.facebook.react.bridge.** { *; }
-keepclassmembers class com.facebook.react.** { *; }
-dontwarn com.facebook.react.**

# Сохраняем системные аннотации, необходимые для работы JNI и JS-мостов
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

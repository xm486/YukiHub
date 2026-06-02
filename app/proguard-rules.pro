# Preserve JNI/SDL engine entry points used by embedded native engines.
-keep class org.tvp.kirikiri2.** { *; }
-keep class com.yuri.onscripter.** { *; }
-keep class org.libsdl.app.** { *; }
-keep class org.cocos2dx.lib.** { *; }
-keep class bridge.NativeBridge { *; }
-keep class T3.** { *; }
-keep class com.akira.tyranoemu.remote.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}


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
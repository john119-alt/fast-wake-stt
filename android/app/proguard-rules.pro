# App-specific R8 rules. The Claude SDK, OkHttp and Kotlin ship their own
# rules inside their jars, which R8 applies automatically.

# JNI: libfastwake.so looks up these methods by name.
-keep class com.fastwake.app.NativeEngine { native <methods>; }

# Keep readable stack traces in crash logs.
-keepattributes SourceFile,LineNumberTable

# OkHttp's optional TLS providers aren't present on Android.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Compile-time annotation used by OpenTelemetry (an SDK dependency); not needed at runtime.
-dontwarn com.google.auto.value.AutoValue

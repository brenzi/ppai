# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class ch.brenzi.prettyprivateai.data.model.** { *; }
-keep class ch.brenzi.prettyprivateai.data.remote.** { *; }

# Native proxy (JNI)
-keep class ch.brenzi.prettyprivateai.proxy.** { *; }

# Jsoup
-keep class org.jsoup.** { *; }
-dontwarn org.jspecify.annotations.**

# Markwon
-keep class io.noties.markwon.** { *; }
-keep class io.noties.prism4j.** { *; }

# Whisper JNI
-keep class ch.brenzi.prettyprivateai.whisper.WhisperNative { native <methods>; }

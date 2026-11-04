# NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# Zip4j
-keep class net.lingala.zip4j.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

# FFmpeg-kit
-keep class com.arthenica.ffmpegkit.** { *; }

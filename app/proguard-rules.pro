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

# CodeLibs JCIFS & Bouncy Castle & SLF4J
-keep class org.codelibs.jcifs.** { *; }
-dontwarn org.codelibs.jcifs.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keepclassmembers class * extends java.security.Provider {
    <init>();
}
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

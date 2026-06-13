# Rules NewPipeExtractor (moteur JS Rhino pour déchiffrement YouTube)
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn okhttp3.**
-dontwarn okio.**

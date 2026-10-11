# Room, Hilt and kotlinx.serialization keep what they need through their own rules.
-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn org.conscrypt.**
# SQLCipher's native code finds these classes by name through JNI; R8 must not rename or remove them.
-keep class net.zetetic.database.** { *; }
# ONNX Runtime's native code calls back into these classes by name through JNI.
-keep class ai.onnxruntime.** { *; }

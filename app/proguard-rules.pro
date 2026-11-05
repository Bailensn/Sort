# JNI 方法名在 release 构建里被 R8 重命名后，C++ 侧就找不到符号了。
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ONNX Runtime 通过反射/JNI 访问这些类，名字不能被裁掉。
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# OpenCV 的 Java 层是 native 绑定，同样保留。
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# 这些是三方库的可选依赖，运行时用不到。
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.eclipse.jetty.**

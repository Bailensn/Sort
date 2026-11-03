# JNI 方法名在 release 构建里被 R8 重命名后，C++ 侧就找不到符号了。
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# JNI 按全名找这个接口，重命名后进度回调就静默失效了。
-keep interface com.bailensn.sort.TableProgress { *; }

# 这些是三方库的可选依赖，运行时用不到。
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.eclipse.jetty.**

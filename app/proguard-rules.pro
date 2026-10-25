# JNI 方法名在 release 构建里被 R8 重命名后，C++ 侧就找不到符号了。
# 当前 release 没有开 minify，保留这条规则是为了以后开启 R8 时仍然安全。
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

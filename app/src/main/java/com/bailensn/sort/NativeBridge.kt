package com.bailensn.sort

/**
 * JNI 桥：native 侧实现在 `src/main/cpp/native-lib.cpp`。
 *
 * JNI 符号名由「包名 + 类名 + 方法名」拼出来，修改包名或类名后，
 * 必须同步修改 native-lib.cpp 里的函数名。
 */
object NativeBridge {

    private const val LIBRARY_NAME = "main"

    private val loadFailure: Throwable? = runCatching {
        System.loadLibrary(LIBRARY_NAME)
    }.exceptionOrNull()

    /** native 库是否加载成功。 */
    val isLoaded: Boolean
        get() = loadFailure == null

    /** 加载失败原因，仅在 [isLoaded] 为 false 时非空。 */
    val loadFailureMessage: String?
        get() = loadFailure?.message

    /** 返回一句来自 C++ 的问候，带当前 ABI。 */
    external fun greeting(): String

    /** 返回 native 库的 ABI、编译器、构建时间等信息。 */
    external fun buildInfo(): String

    /** 用 C++ `std::sort` 升序排序，返回新数组，不修改入参。 */
    external fun sort(values: IntArray): IntArray
}

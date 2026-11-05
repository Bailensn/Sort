package com.bailensn.sort

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

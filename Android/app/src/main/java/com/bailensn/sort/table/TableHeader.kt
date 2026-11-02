package com.bailensn.sort.table

import com.bailensn.sort.NativeTable

/** 一列在业务上的身份：求和校验、单科排序都靠它。 */
enum class ColumnRole {
    SEQUENCE,
    ID,
    SUBJECT,
    TOTAL,
    OTHER,
}

data class ColumnSpec(
    val index: Int,
    /** 图片里认出来的表头文字；没有表头时是空的。 */
    val name: String = "",
    val role: ColumnRole = ColumnRole.OTHER,
)

/**
 * 表格结构：每列叫什么、是什么身份。
 * fromImage 表示第一行确实是图片里的表头，已经从数据行里摘出来了。
 */
data class TableHeader(
    val columns: List<ColumnSpec> = emptyList(),
    val fromImage: Boolean = false,
) {

    val subjectColumns: List<Int>
        get() = columns.filter { it.role == ColumnRole.SUBJECT }.map { it.index }.sorted()

    val totalColumn: Int?
        get() = columns.firstOrNull { it.role == ColumnRole.TOTAL }?.index

    val hasNames: Boolean get() = columns.any { it.name.isNotBlank() }

    fun nameOf(index: Int): String = columns.firstOrNull { it.index == index }?.name.orEmpty()

    fun withName(index: Int, name: String): TableHeader = copy(
        columns = columns.map { if (it.index == index) it.copy(name = name.trim()) else it },
    )

    fun withRole(index: Int, role: ColumnRole): TableHeader = copy(
        columns = columns.map {
            when {
                it.index != index || it.role == role -> it
                // 总分只能有一个：改别的列之前先把原来的降级
                role == ColumnRole.TOTAL -> it.copy(role = ColumnRole.SUBJECT)
                else -> it.copy(role = role)
            }
        },
    )

    companion object {
        fun of(columnCount: Int): TableHeader =
            TableHeader((0 until columnCount).map { ColumnSpec(it) })
    }
}

object TableHeaderDetector {

    /** 认表头：认不出就返回一份空名字的结构，交给用户填。实现在 Native。 */
    fun detect(rows: List<List<String>>): TableHeader = NativeTable.detectHeader(rows)
}

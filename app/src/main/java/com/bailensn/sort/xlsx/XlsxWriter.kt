package com.bailensn.sort.xlsx

import com.bailensn.sort.table.TableGrid
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 手写一个最小但合法的 .xlsx（OOXML）。
 *
 * 不引 Apache POI：POI 在 Android 上方法数、体积和 dex 都不友好。
 * 这里只用到 `java.util.zip` + 字符串拼 XML，产物在 Excel / WPS / Google Sheets
 * 里都能正常打开，单元格带细边框、自动换行、按内容估宽。
 */
object XlsxWriter {

    fun write(grid: TableGrid, output: OutputStream) {
        require(grid.rowCount > 0 && grid.columnCount > 0) { "表格为空" }

        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            putEntry(zip, "[Content_Types].xml", CONTENT_TYPES)
            putEntry(zip, "_rels/.rels", ROOT_RELS)
            putEntry(zip, "xl/workbook.xml", WORKBOOK)
            putEntry(zip, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            putEntry(zip, "xl/styles.xml", STYLES)
            putEntry(zip, "xl/worksheets/sheet1.xml", sheetXml(grid))
        }
    }

    private fun putEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun sheetXml(grid: TableGrid): String {
        val columnCount = grid.columnCount
        val widths = IntArray(columnCount)
        for (row in grid.rows) {
            for (column in 0 until columnCount) {
                val width = displayWidth(row.getOrElse(column) { "" })
                if (width > widths[column]) widths[column] = width
            }
        }

        val lastCell = "${columnName(columnCount - 1)}${grid.rowCount}"
        val xml = StringBuilder(4096)
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        xml.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        xml.append("<dimension ref=\"A1:$lastCell\"/>")
        xml.append("<sheetViews><sheetView workbookViewId=\"0\"/></sheetViews>")
        xml.append("<sheetFormatPr defaultRowHeight=\"16\"/>")

        xml.append("<cols>")
        widths.forEachIndexed { index, width ->
            val excelWidth = (width + 2).coerceIn(8, 60)
            xml.append("<col min=\"${index + 1}\" max=\"${index + 1}\" width=\"$excelWidth\" customWidth=\"1\"/>")
        }
        xml.append("</cols>")

        xml.append("<sheetData>")
        grid.rows.forEachIndexed { rowIndex, row ->
            xml.append("<row r=\"${rowIndex + 1}\">")
            for (column in 0 until columnCount) {
                val reference = "${columnName(column)}${rowIndex + 1}"
                val value = row.getOrElse(column) { "" }
                when {
                    value.isBlank() -> xml.append("<c r=\"$reference\" s=\"1\"/>")

                    isNumeric(value) ->
                        xml.append("<c r=\"$reference\" s=\"1\"><v>${value.trim()}</v></c>")

                    else -> xml.append(
                        "<c r=\"$reference\" s=\"1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">" +
                            escape(value) + "</t></is></c>",
                    )
                }
            }
            xml.append("</row>")
        }
        xml.append("</sheetData>")

        // 合并单元格：只写左上角的 ref，其余格子在 sheetData 里保持空。
        if (grid.merges.isNotEmpty()) {
            xml.append("<mergeCells count=\"${grid.merges.size}\">")
            grid.merges.forEach { merge ->
                val from = "${columnName(merge.column)}${merge.row + 1}"
                val to = "${columnName(merge.column + merge.columnSpan - 1)}${merge.row + merge.rowSpan}"
                xml.append("<mergeCell ref=\"$from:$to\"/>")
            }
            xml.append("</mergeCells>")
        }

        xml.append("</worksheet>")
        return xml.toString()
    }

    private fun isNumeric(value: String): Boolean =
        value.length <= 20 && NUMBER_PATTERN.matches(value.trim())

    private fun displayWidth(value: String): Int =
        value.lineSequence().maxOfOrNull { line ->
            line.sumOf { character -> if (character.code > 0x2E80) 2 else 1 }
        } ?: 0

    private fun columnName(index: Int): String {
        var value = index
        val builder = StringBuilder()
        while (value >= 0) {
            builder.append('A' + value % 26)
            value = value / 26 - 1
        }
        return builder.reverse().toString()
    }

    private fun escape(value: String): String = buildString(value.length + 8) {
        for (character in value) {
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                '\n' -> append("&#10;")
                '\r' -> Unit
                '\t' -> append("&#9;")
                else -> if (character.code >= 0x20) append(character)
            }
        }
    }

    // 整数最多 9 位、可带小数；11 位手机号、带前导 0 的单号会保持文本。
    private val NUMBER_PATTERN = Regex("-?(0|[1-9]\\d{0,8})(\\.\\d+)?")

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private const val WORKBOOK = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets>
</workbook>"""

    private const val WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<fonts count="1"><font><sz val="11"/><name val="Calibri"/><family val="2"/></font></fonts>
<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
<borders count="2">
<border><left/><right/><top/><bottom/><diagonal/></border>
<border><left style="thin"><color auto="1"/></left><right style="thin"><color auto="1"/></right><top style="thin"><color auto="1"/></top><bottom style="thin"><color auto="1"/></bottom><diagonal/></border>
</borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="2">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""
}

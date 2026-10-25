package com.bailensn.sort.xlsx

import com.bailensn.sort.table.TableGrid
import com.bailensn.sort.table.TableMerge
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class XlsxWriterTest {

    @Test
    fun writesRequiredPackageParts() {
        val entries = unzip(write(gridWithValues()))

        assertTrue(entries.containsKey("[Content_Types].xml"))
        assertTrue(entries.containsKey("_rels/.rels"))
        assertTrue(entries.containsKey("xl/workbook.xml"))
        assertTrue(entries.containsKey("xl/styles.xml"))
        assertTrue(entries.containsKey("xl/worksheets/sheet1.xml"))
    }

    @Test
    fun writesStringsNumbersAndDimensions() {
        val sheet = sheetXml(write(gridWithValues()))

        assertTrue(sheet.contains("<dimension ref=\"A1:B2\"/>"))
        assertTrue(sheet.contains("姓名"))
        assertTrue(sheet.contains("<v>1200</v>"))
        assertTrue(sheet.contains("t=\"inlineStr\""))
    }

    @Test
    fun keepsLeadingZeroValuesAsText() {
        val grid = TableGrid(listOf(listOf("007")), TableGrid.Mode.TEXT_CLUSTER)
        val sheet = sheetXml(write(grid))

        assertTrue(sheet.contains("007"))
        assertTrue(!sheet.contains("<v>007</v>"))
    }

    @Test
    fun keepsBlankCellsAsEmptyBorderedCells() {
        // 高中选科 / 缺考：一行里本来就可能有空单元格，位置不能塌陷。
        val grid = TableGrid(
            rows = listOf(
                listOf("姓名", "语文", "数学", "物理", "化学"),
                listOf("张三", "缺考", "95", "", ""),
                listOf("李四", "88", "", "缺考", ""),
            ),
            mode = TableGrid.Mode.GRID_LINES,
        )
        val sheet = sheetXml(write(grid))

        assertTrue(sheet.contains("<c r=\"E2\" s=\"1\"/>"))
        assertTrue(sheet.contains("<c r=\"C3\" s=\"1\"/>"))
        assertTrue(sheet.contains("<c r=\"E3\" s=\"1\"/>"))
        assertTrue(sheet.contains("<dimension ref=\"A1:E3\"/>"))
        // 空白格不写值，也不会把后面的文字往前挪。
        assertTrue(sheet.contains("<c r=\"D2\" s=\"1\"/>"))
        assertTrue(sheet.contains("缺考"))
    }

    @Test
    fun writesMergedCellsForSpannedHeaders() {
        val grid = TableGrid(
            rows = listOf(
                listOf("高二（3）班成绩单", "", ""),
                listOf("姓名", "语文", "数学"),
                listOf("张三", "92", "88"),
            ),
            mode = TableGrid.Mode.GRID_LINES,
            merges = listOf(TableMerge(0, 0, 1, 3)),
        )
        val sheet = sheetXml(write(grid))

        assertTrue(sheet.contains("<mergeCells count=\"1\">"))
        assertTrue(sheet.contains("<mergeCell ref=\"A1:C1\"/>"))
        // 合并区域只保留左上角文字，另外两格是空的。
        assertTrue(sheet.contains("高二（3）班成绩单"))
        assertTrue(sheet.contains("<c r=\"B1\" s=\"1\"/>"))
        assertTrue(sheet.contains("<c r=\"C1\" s=\"1\"/>"))
    }

    @Test
    fun escapesXmlSpecialCharacters() {
        val grid = TableGrid(listOf(listOf("A&B <C>")), TableGrid.Mode.TEXT_CLUSTER)
        val sheet = sheetXml(write(grid))

        assertTrue(sheet.contains("A&amp;B &lt;C&gt;"))
    }

    @Test
    fun sheetXmlIsWellFormed() {
        val sheet = unzip(write(gridWithValues())).getValue("xl/worksheets/sheet1.xml")
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(sheet))
    }

    private fun gridWithValues() = TableGrid(
        rows = listOf(listOf("姓名", "金额"), listOf("张三", "1200")),
        mode = TableGrid.Mode.GRID_LINES,
    )

    private fun write(grid: TableGrid): ByteArray {
        val output = ByteArrayOutputStream()
        XlsxWriter.write(grid, output)
        return output.toByteArray()
    }

    private fun sheetXml(bytes: ByteArray): String =
        unzip(bytes).getValue("xl/worksheets/sheet1.xml").toString(Charsets.UTF_8)

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        return entries
    }
}

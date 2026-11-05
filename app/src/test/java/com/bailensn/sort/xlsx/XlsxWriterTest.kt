package com.bailensn.sort.xlsx

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class XlsxWriterTest {

    @Test
    fun writesRequiredPackageParts() {
        val entries = unzip(write(rowsWithValues()))

        assertTrue(entries.containsKey("[Content_Types].xml"))
        assertTrue(entries.containsKey("_rels/.rels"))
        assertTrue(entries.containsKey("xl/workbook.xml"))
        assertTrue(entries.containsKey("xl/styles.xml"))
        assertTrue(entries.containsKey("xl/worksheets/sheet1.xml"))
    }

    @Test
    fun writesStringsNumbersAndDimensions() {
        val sheet = sheetXml(write(rowsWithValues()))

        assertTrue(sheet.contains("<dimension ref=\"A1:B2\"/>"))
        assertTrue(sheet.contains("姓名"))
        assertTrue(sheet.contains("<v>1200</v>"))
        assertTrue(sheet.contains("t=\"inlineStr\""))
    }

    @Test
    fun keepsLeadingZeroValuesAsText() {
        val sheet = sheetXml(write(listOf(listOf("007"))))

        assertTrue(sheet.contains("007"))
        assertTrue(!sheet.contains("<v>007</v>"))
    }

    @Test
    fun keepsBlankCellsAsEmptyBorderedCells() {
        // 高中选科 / 缺考：一行里本来就可能有空单元格，位置不能塌陷。
        val rows = listOf(
            listOf("姓名", "语文", "数学", "物理", "化学"),
            listOf("张三", "缺考", "95", "", ""),
            listOf("李四", "88", "", "缺考", ""),
        )
        val sheet = sheetXml(write(rows))

        assertTrue(sheet.contains("<c r=\"E2\" s=\"1\"/>"))
        assertTrue(sheet.contains("<c r=\"C3\" s=\"1\"/>"))
        assertTrue(sheet.contains("<c r=\"E3\" s=\"1\"/>"))
        assertTrue(sheet.contains("<dimension ref=\"A1:E3\"/>"))
        // 空白格不写值，也不会把后面的文字往前挪。
        assertTrue(sheet.contains("<c r=\"D2\" s=\"1\"/>"))
        assertTrue(sheet.contains("缺考"))
    }

    @Test
    fun writesRowsVerbatimWithoutInventingMerges() {
        // 合并区域在 rows.tsv 里就是「左上角有字、其余为空」，导出照抄，不自己加合并。
        val rows = listOf(
            listOf("高二（3）班成绩单", "", ""),
            listOf("姓名", "语文", "数学"),
            listOf("张三", "92", "88"),
        )
        val sheet = sheetXml(write(rows))

        assertTrue(!sheet.contains("<mergeCells"))
        assertTrue(sheet.contains("高二（3）班成绩单"))
        assertTrue(sheet.contains("<c r=\"B1\" s=\"1\"/>"))
        assertTrue(sheet.contains("<c r=\"C1\" s=\"1\"/>"))
    }

    @Test
    fun escapesXmlSpecialCharacters() {
        val sheet = sheetXml(write(listOf(listOf("A&B <C>"))))

        assertTrue(sheet.contains("A&amp;B &lt;C&gt;"))
    }

    @Test
    fun sheetXmlIsWellFormed() {
        val sheet = unzip(write(rowsWithValues())).getValue("xl/worksheets/sheet1.xml")
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(sheet))
    }

    private fun rowsWithValues() = listOf(listOf("姓名", "金额"), listOf("张三", "1200"))

    private fun write(rows: List<List<String>>): ByteArray {
        val output = ByteArrayOutputStream()
        XlsxWriter.write(rows, output)
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

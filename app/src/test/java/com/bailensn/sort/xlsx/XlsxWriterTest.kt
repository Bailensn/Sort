package com.bailensn.sort.xlsx

import com.bailensn.sort.table.TableGrid
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

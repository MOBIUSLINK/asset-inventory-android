package cn.assetinventory.app

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

data class ImportedAsset(
    val code: String, val name: String, val warehouse: String, val category: String,
    val status: String, val user: String, val department: String, val source: String,
    val serialNumber: String, val assetType: String, val amount: String, val specification: String,
    val location: String, val purchaseDate: String, val supplier: String, val note: String
)

data class ImportIssue(val row: Int, val rawCode: String, val reason: String, val asset: ImportedAsset)
data class ParsedLedger(val fileName: String, val assets: List<ImportedAsset>, val issues: List<ImportIssue>)

object XlsxImporter {
    private val codePattern = Regex("^[A-Z]{2,6}-?\\d{3,8}(?:-\\d{1,3})?$")

    fun normalizedCode(value: String): String = value.trim().uppercase()
    fun isValidCode(value: String): Boolean = codePattern.matches(normalizedCode(value))

    fun parse(context: Context, uri: Uri): ParsedLedger {
        val entries = mutableMapOf<String, ByteArray>()
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法打开文件" }
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "xl/sharedStrings.xml" || entry.name.startsWith("xl/worksheets/sheet")) {
                        entries[entry.name] = zip.readBytes()
                    }
                    zip.closeEntry()
                }
            }
        }
        val shared = entries["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        var selectedRows: List<List<String>>? = null
        entries.filterKeys { it.startsWith("xl/worksheets/sheet") }.toSortedMap().values.forEach { bytes ->
            val rows = parseSheet(bytes, shared)
            if (rows.any { row -> row.any { it.trim() == "资产编码" } }) selectedRows = rows
        }
        val rows = selectedRows ?: error("没有找到包含“资产编码”表头的工作表")
        val headerIndex = rows.indexOfFirst { row -> row.any { it.trim() == "资产编码" } }
        val headers = rows[headerIndex].map { it.trim() }
        fun col(name: String) = headers.indexOf(name)
        val codeCol = col("资产编码")
        require(codeCol >= 0) { "缺少资产编码列" }
        val assets = mutableListOf<ImportedAsset>()
        val issues = mutableListOf<ImportIssue>()
        val seen = mutableSetOf<String>()
        fun value(row: List<String>, name: String): String = col(name).let {
            val v=if (it >= 0) row.getOrNull(it).orEmpty().trim() else ""
            if(v.equals("nan",true)||v.equals("null",true)||v=="-") "" else v
        }
        rows.drop(headerIndex + 1).forEachIndexed { index, row ->
            val excelRow = headerIndex + index + 2
            val rawCode = row.getOrNull(codeCol).orEmpty().trim()
            if (rawCode.isBlank()) return@forEachIndexed
            val code = normalizedCode(rawCode)
            val asset = ImportedAsset(
                code, value(row, "资产名称"), value(row, "所属仓库"), value(row, "资产分类"),
                value(row, "资产状态"), value(row, "使用人"), value(row, "使用部门"), value(row, "来源"),
                value(row, "SN编码"), value(row, "资产类型"), value(row, "金额"), value(row, "规格型号"),
                value(row, "存放地点"), value(row, "购入时间"), value(row, "供应商"), value(row, "备注说明")
            )
            when {
                !codePattern.matches(code) -> issues += ImportIssue(excelRow, rawCode, "资产编号格式异常", asset)
                !seen.add(code) -> issues += ImportIssue(excelRow, rawCode, "文件内资产编号重复", asset)
                else -> assets += asset
            }
        }
        val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "资产台账.xlsx"
        return ParsedLedger(name, assets, issues)
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val parser = Xml.newPullParser().apply { setInput(ByteArrayInputStream(bytes), "UTF-8") }
        val result = mutableListOf<String>()
        var inSi = false
        val current = StringBuilder()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (parser.name == "si") { inSi = true; current.clear() }
                XmlPullParser.TEXT -> if (inSi && parser.name == null) current.append(parser.text)
                XmlPullParser.END_TAG -> if (parser.name == "si") { result += current.toString(); inSi = false }
            }
            parser.next()
        }
        return result
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val parser = Xml.newPullParser().apply { setInput(ByteArrayInputStream(bytes), "UTF-8") }
        val rows = mutableListOf<List<String>>()
        var cells = mutableMapOf<Int, String>()
        var cellRef = "A1"; var cellType = ""; var value = ""; var inValue = false; var inText = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> cells = mutableMapOf()
                    "c" -> { cellRef = parser.getAttributeValue(null, "r") ?: "A1"; cellType = parser.getAttributeValue(null, "t") ?: ""; value = "" }
                    "v" -> inValue = true
                    "t" -> inText = true
                }
                XmlPullParser.TEXT -> if (inValue || inText) value += parser.text
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v" -> inValue = false
                    "t" -> inText = false
                    "c" -> {
                        val resolved = if (cellType == "s") shared.getOrNull(value.toIntOrNull() ?: -1).orEmpty() else value
                        cells[columnIndex(cellRef)] = resolved
                    }
                    "row" -> if (cells.isNotEmpty()) rows += (0..cells.keys.max()).map { cells[it].orEmpty() }
                }
            }
            parser.next()
        }
        return rows
    }

    private fun columnIndex(ref: String): Int {
        var result = 0
        ref.takeWhile { it.isLetter() }.uppercase().forEach { result = result * 26 + (it - 'A' + 1) }
        return result - 1
    }
}

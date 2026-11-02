package com.example.novacustomerdialer

import android.content.Context
import android.net.Uri
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.FilterInputStream
import java.io.InputStream
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.util.Locale
import java.util.zip.ZipInputStream

/** 单次导入最多读取的行数，防止超大文件耗尽内存。 */
internal const val MAX_IMPORT_ROWS = 200_000

/** 严格按 UTF-8 解码；失败时按 GB18030（兼容 GBK）解码，并去掉 BOM。 */
internal fun decodeTextAuto(bytes: ByteArray): String {
    val utf8Text = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
    val text = utf8Text ?: String(bytes, Charset.forName("GB18030"))
    return text.removePrefix("\uFEFF")
}

/** 完整的 CSV 解析：支持引号内的逗号、换行、双引号转义，自动识别逗号/制表符/分号分隔。 */
internal fun parseCsv(text: String): List<List<String>> {
    val firstLine = text.lineSequence().firstOrNull().orEmpty()
    val delimiter = listOf(',', '\t', ';')
        .maxByOrNull { d -> firstLine.count { it == d } }
        ?.takeIf { firstLine.contains(it) }
        ?: ','
    val rows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    val cell = StringBuilder()
    var quoted = false
    var i = 0
    val n = text.length
    while (i < n) {
        val c = text[i]
        if (quoted) {
            if (c == '"') {
                if (i + 1 < n && text[i + 1] == '"') {
                    cell.append('"')
                    i++
                } else {
                    quoted = false
                }
            } else {
                cell.append(c)
            }
        } else if (c == '"') {
            quoted = true
        } else if (c == delimiter) {
            row.add(cell.toString().trim())
            cell.setLength(0)
        } else if (c == '\n') {
            row.add(cell.toString().trim())
            cell.setLength(0)
            rows.add(row)
            row = ArrayList()
            if (rows.size >= MAX_IMPORT_ROWS) return rows
        } else if (c != '\r') {
            cell.append(c)
        }
        i++
    }
    if (cell.isNotEmpty() || row.isNotEmpty()) {
        row.add(cell.toString().trim())
        rows.add(row)
    }
    return rows
}

/** 把 Excel 的科学计数法/小数还原成普通数字字符串，例如 1.38001E10 -> 13800100000。 */
internal fun plainNumber(value: String): String {
    val v = value.trim()
    if (v.isEmpty() || !(v.contains('E') || v.contains('e') || v.contains('.'))) return v
    return try {
        BigDecimal(v).stripTrailingZeros().toPlainString()
    } catch (_: Exception) {
        v
    }
}

/** 统一回访日期：Excel 序列号、2026/1/5、2026.1.5、2026年1月5日 都转成 yyyy-MM-dd。 */
internal fun normalizeDateText(raw: String): String {
    val s = raw.trim()
    if (s.isEmpty()) return s
    val ymd = Regex("^(\\d{4})[/.\\-年](\\d{1,2})[/.\\-月](\\d{1,2})日?.*$").matchEntire(s)
    if (ymd != null) {
        val y = ymd.groupValues[1].toInt()
        val m = ymd.groupValues[2].toInt()
        val d = ymd.groupValues[3].toInt()
        if (m in 1..12 && d in 1..31) return String.format(Locale.ROOT, "%04d-%02d-%02d", y, m, d)
        return s
    }
    val serial = Regex("^(\\d{5})(\\.\\d+)?$").matchEntire(s)
    if (serial != null) {
        val days = serial.groupValues[1].toInt()
        if (days in 20000..80000) return LocalDate.of(1899, 12, 30).plusDays(days.toLong()).toString()
    }
    return s
}

/** 手动录入号码时的规范化：保留开头的 +，其余只留数字；长度不合理或含字母时保持原样。 */
internal fun normalizeDialNumber(raw: String): String {
    val s = raw.trim()
    val d = s.filter { it.isDigit() }
    if (d.length !in 7..15 || s.any { it.isLetter() }) return s
    return (if (s.startsWith("+")) "+" else "") + d
}

private val sheetEntryRegex = Regex("xl/worksheets/sheet(\\d+)\\.xml")

private fun nonClosing(stream: InputStream): InputStream = object : FilterInputStream(stream) {
    override fun close() {
        // 解析器结束时不要关闭外层 ZipInputStream，否则无法继续读取下一个条目。
    }
}

private fun columnIndex(ref: String): Int {
    val letters = ref.takeWhile { it.isLetter() }
    if (letters.isEmpty()) return -1
    return letters.fold(0) { acc, ch -> acc * 26 + (ch.uppercaseChar() - 'A' + 1) } - 1
}

private fun readSharedStrings(context: Context, uri: Uri): List<String> {
    val out = ArrayList<String>()
    val input = context.contentResolver.openInputStream(uri) ?: return out
    ZipInputStream(input).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && entry.name == "xl/sharedStrings.xml") {
                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(nonClosing(zip), "UTF-8")
                var inSi = false
                var inT = false
                var phoneticDepth = 0
                val sb = StringBuilder()
                var ev = parser.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "si" -> { inSi = true; sb.setLength(0) }
                            "rPh" -> phoneticDepth++
                            "t" -> inT = true
                        }
                    } else if (ev == XmlPullParser.TEXT) {
                        if (inSi && inT && phoneticDepth == 0) sb.append(parser.text)
                    } else if (ev == XmlPullParser.END_TAG) {
                        when (parser.name) {
                            "t" -> inT = false
                            "rPh" -> phoneticDepth--
                            "si" -> { out.add(sb.toString()); inSi = false }
                        }
                    }
                    ev = parser.next()
                }
                break
            }
            entry = zip.nextEntry
        }
    }
    return out
}

private fun parseSheet(stream: InputStream, shared: List<String>, maxRows: Int): List<List<String>> {
    val parser = XmlPullParserFactory.newInstance().newPullParser()
    parser.setInput(nonClosing(stream), "UTF-8")
    val rows = ArrayList<List<String>>()
    var row = HashMap<Int, String>()
    var nextCol = 0
    var col = -1
    var type = ""
    var value = ""
    var ev = parser.eventType
    while (ev != XmlPullParser.END_DOCUMENT && rows.size < maxRows) {
        if (ev == XmlPullParser.START_TAG) {
            when (parser.name) {
                "row" -> { row = HashMap(); nextCol = 0 }
                "c" -> {
                    val ref = parser.getAttributeValue(null, "r").orEmpty()
                    col = columnIndex(ref).let { if (it >= 0) it else nextCol }
                    nextCol = col + 1
                    type = parser.getAttributeValue(null, "t").orEmpty()
                    value = ""
                }
                "v" -> value = parser.nextText()
                "t" -> if (type == "inlineStr") value += parser.nextText()
            }
        } else if (ev == XmlPullParser.END_TAG) {
            when (parser.name) {
                "c" -> if (col in 0..300) {
                    row[col] = when (type) {
                        "s" -> shared.getOrNull(value.trim().toIntOrNull() ?: -1).orEmpty()
                        "inlineStr", "str", "e", "b", "d" -> value
                        else -> plainNumber(value)
                    }
                }
                "row" -> if (row.isNotEmpty()) {
                    val last = row.keys.maxOrNull() ?: -1
                    rows.add((0..last).map { row[it].orEmpty().trim() })
                }
            }
        }
        ev = parser.next()
    }
    return rows
}

/** 读取 xlsx 的所有工作表（按 sheetN 编号顺序），每个工作表是一个行列表。 */
internal fun readXlsxSheets(context: Context, uri: Uri): List<List<List<String>>> {
    val shared = readSharedStrings(context, uri)
    val sheets = ArrayList<Pair<Int, List<List<String>>>>()
    var total = 0
    val input = context.contentResolver.openInputStream(uri) ?: error("无法打开 Excel 文件")
    ZipInputStream(input).use { zip ->
        var entry = zip.nextEntry
        while (entry != null && total < MAX_IMPORT_ROWS) {
            val match = if (entry.isDirectory) null else sheetEntryRegex.matchEntire(entry.name)
            if (match != null) {
                val rows = parseSheet(zip, shared, MAX_IMPORT_ROWS - total)
                total += rows.size
                sheets.add(match.groupValues[1].toInt() to rows)
            }
            entry = zip.nextEntry
        }
    }
    if (sheets.isEmpty()) error("Excel 中没有可读取的工作表")
    return sheets.sortedBy { it.first }.map { it.second }
}


/** 识别中国大陆常见固定电话格式；国际号码及 11 位手机号不按长度误删。 */
internal fun isMainlandLandlineNumber(raw: String): Boolean {
    var digits = raw.filter { it.isDigit() }
    if (digits.startsWith("0086")) digits = digits.removePrefix("0086")
    else if (digits.startsWith("86") && digits.length >= 11) digits = digits.removePrefix("86")
    if (digits.length in 7..8) return true
    if (digits.length in 10..12 && digits.startsWith("0")) return true
    // 国际写法 +86 21 12345678：仅在明确带中国国家码时按区号识别。
    val hasChinaCountryCode = raw.trim().startsWith("+86") || raw.trim().startsWith("0086")
    return hasChinaCountryCode && digits.matches(Regex("^(10|2[0-9]|[3-9]\\d{2})\\d{7,8}$"))
}

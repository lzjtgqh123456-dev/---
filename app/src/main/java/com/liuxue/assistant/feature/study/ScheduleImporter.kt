package com.liuxue.assistant.feature.study

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.liuxue.assistant.data.study.ClassType
import com.liuxue.assistant.data.study.CourseSchedule
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 课表导入：支持
 *  1) 通用「课程行」表（.csv / .xlsx，带表头或按固定列顺序）；
 *  2) 俄罗斯大学「按教室排的矩阵课表」（.xlsx，ВШРФК 样式）—— 见 [ScheduleMatrix]。
 *
 * 为什么不用 Apache POI：POI 在 Android 上体积大、方法数爆炸；
 * .xlsx 本质是 zip + xml，用系统自带的 ZipInputStream + XmlPullParser 完全够用，且零依赖。
 *
 * 通用表列顺序（有表头时按表头名匹配）：
 *   课程名称 | 班级 | 教师 | 类型 | 学分 | 内容 | 星期 | 开始 | 结束 | 地点 | 起始周 | 结束周 | 单双周
 */
object ScheduleImporter {

    /** UTF-8 BOM；Excel 导出的 CSV 常带，解析前剥掉 */
    private const val BOM_CODE = 0xFEFF

    /** xlsx 解压后只读 xml，总大小上限，防止恶意大文件 */
    private const val MAX_ZIP_BYTES = 96L * 1024 * 1024

    data class MergeRange(val r1: Int, val c1: Int, val r2: Int, val c2: Int)

    /** 一张工作表：稠密网格 + 合并区域（0 基） */
    data class SheetGrid(val rows: List<List<String>>, val merges: List<MergeRange>)

    data class Row(
        val courseName: String,
        val className: String = "",
        val teacher: String = "",
        val classType: String = ClassType.LARGE,
        val credits: Double = 0.0,
        val content: String = "",
        val weekday: Int = 1,
        val startTime: String = "08:00",
        val endTime: String = "09:40",
        val location: String = "",
        val weekFrom: Int = 1,
        val weekTo: Int = 18,
        val parity: String = CourseSchedule.PARITY_ALL,
        /** 学时（矩阵课表用） */
        val hours: Int = 0,
        /** 专业 / 工作表名 */
        val major: String = "",
        /** 原始备注（未解析信息） */
        val note: String = ""
    )

    data class Result(
        val rows: List<Row> = emptyList(),
        val error: String? = null,
        val skipped: Int = 0,
        /** 未能识别的原始片段，便于用户核对 */
        val unparsed: List<String> = emptyList(),
        /** 格式名，用于提示 */
        val format: String = "通用表格",
        val major: String = "",
        /** 班级 → 出现次数（矩阵课表用，供导入时勾选班级） */
        val classCounts: Map<String, Int> = emptyMap()
    ) {
        val classes: List<String> get() = classCounts.keys.sorted()
    }

    val HEADERS = listOf(
        "课程名称", "班级", "教师", "类型", "学分", "内容",
        "星期", "开始", "结束", "地点", "起始周", "结束周", "单双周"
    )

    private val HEADER_ALIASES = mapOf(
        0 to listOf("课程名称", "课程", "名称", "course"),
        1 to listOf("班级", "教学班", "class"),
        2 to listOf("教师", "老师", "任课教师", "teacher"),
        3 to listOf("类型", "课程类型", "type"),
        4 to listOf("学分", "credits"),
        5 to listOf("内容", "课程内容", "content"),
        6 to listOf("星期", "周几", "weekday"),
        7 to listOf("开始", "开始时间", "start"),
        8 to listOf("结束", "结束时间", "end"),
        9 to listOf("地点", "上课地点", "教室", "location"),
        10 to listOf("起始周", "开始周", "weekfrom"),
        11 to listOf("结束周", "weekto"),
        12 to listOf("单双周", "周次类型", "parity")
    )

    // ==================== 入口 ====================

    fun import(context: Context, uri: Uri, displayName: String): Result {
        val stream = runCatching { context.contentResolver.openInputStream(uri) }
            .getOrNull() ?: return Result(error = "无法读取所选文件")
        val lower = displayName.lowercase()
        return stream.use { input ->
            when {
                lower.endsWith(".csv") || lower.endsWith(".txt") -> parseCsv(input)
                lower.endsWith(".xlsx") -> parseXlsxWorkbook(input)
                else -> {
                    val buffered = input.buffered()
                    buffered.mark(4)
                    val magic = ByteArray(4)
                    val n = buffered.read(magic)
                    buffered.reset()
                    val isZip = n >= 2 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()
                    if (isZip) parseXlsxWorkbook(buffered) else parseCsv(buffered)
                }
            }
        }
    }

    /** 生成 CSV 模板（带 BOM，Excel 打开中文不乱码） */
    fun templateCsv(): String = buildString {
        append(BOM_CODE.toChar())
        append(HEADERS.joinToString(",")).append('\n')
        append("俄语精读,俄语2101班,伊万诺夫,大班,4,第1-8章,1,08:00,09:40,主楼302,1,18,每周\n")
        append("俄语口语,俄语2101班,彼得洛娃,小班,2,日常会话,3,10:00,11:40,语音室,1,16,每周\n")
        append("俄罗斯文化,全校,斯米尔诺夫,讲座,2,,5,14:00,15:40,大讲堂,3,12,双周\n")
    }

    // ==================== XLSX ====================

    private fun parseXlsxWorkbook(input: InputStream): Result {
        val entries = readZipEntries(input) ?: return Result(error = "无法解压这个 xlsx（文件可能已损坏）")
        val shared = entries["xl/sharedStrings.xml"]
            ?.let { parseSharedStrings(it.inputStream()) } ?: emptyList()
        val sheets = orderedSheets(entries)
        if (sheets.isEmpty()) return Result(error = "这个 xlsx 里没有找到工作表")

        var flatGrid: List<List<String>>? = null
        for ((name, bytes) in sheets) {
            val grid = parseSheetData(String(bytes, Charsets.UTF_8), shared)
            if (grid.rows.isEmpty()) continue
            if (ScheduleMatrix.looksLikeMatrix(grid)) {
                return ScheduleMatrix.parse(grid, name)
            }
            if (flatGrid == null) flatGrid = grid.rows
        }
        val g = flatGrid ?: return Result(error = "表格是空的")
        return rowsFromGrid(g)
    }

    /** 把 zip 里的 xml 全部读进内存（xlsx 很小，顺序无关，便于按需取工作表） */
    private fun readZipEntries(input: InputStream): Map<String, ByteArray>? {
        val out = HashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name.endsWith(".xml") || name.endsWith(".rels")) {
                        val bytes = readAllBytes(zip)
                        total += bytes.size
                        if (total > MAX_ZIP_BYTES) return null
                        out[name] = bytes
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (e: Exception) {
            return null
        }
        return out
    }

    private fun readAllBytes(input: InputStream): ByteArray {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            bos.write(buf, 0, n)
        }
        return bos.toByteArray()
    }

    private data class SheetRef(val name: String, val relId: String, val hidden: Boolean)

    /** 按 workbook 顺序返回 (工作表名, xml 字节)，可见表排前面 */
    private fun orderedSheets(entries: Map<String, ByteArray>): List<Pair<String, ByteArray>> {
        val wbXml = entries["xl/workbook.xml"]?.let { String(it, Charsets.UTF_8) }
        val relXml = entries["xl/_rels/workbook.xml.rels"]?.let { String(it, Charsets.UTF_8) }
        val refs = if (wbXml != null) parseWorkbookSheets(wbXml) else emptyList()
        val rels = if (relXml != null) parseRels(relXml) else emptyMap()
        val result = ArrayList<Triple<String, ByteArray, Boolean>>()
        for (ref in refs) {
            val target = rels[ref.relId] ?: continue
            val bytes = entries[normalizeTarget(target)] ?: continue
            result.add(Triple(ref.name, bytes, !ref.hidden))
        }
        if (result.isEmpty()) {
            entries.keys.filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
                .sorted().forEach { k -> result.add(Triple("Sheet", entries.getValue(k), true)) }
        }
        return result.sortedBy { if (it.third) 0 else 1 }.map { it.first to it.second }
    }

    private fun normalizeTarget(target: String): String {
        var t = target.removePrefix("/").removePrefix("../")
        if (!t.startsWith("xl/")) t = "xl/" + t
        return t
    }

    private fun parseWorkbookSheets(xml: String): List<SheetRef> {
        val out = ArrayList<SheetRef>()
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "sheet") {
                val name = parser.getAttributeValue(null, "name") ?: ""
                val rid = parser.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
                    ?: parser.getAttributeValue(null, "id") ?: ""
                val state = parser.getAttributeValue(null, "state") ?: ""
                if (rid.isNotEmpty()) out.add(SheetRef(name, rid, state.equals("hidden", true)))
            }
            event = parser.next()
        }
        return out
    }

    private fun parseRels(xml: String): Map<String, String> {
        val out = HashMap<String, String>()
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id = parser.getAttributeValue(null, "Id")
                val target = parser.getAttributeValue(null, "Target")
                if (id != null && target != null) out[id] = target
            }
            event = parser.next()
        }
        return out
    }

    private fun parseSharedStrings(input: InputStream): List<String> {
        val out = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        var sb: StringBuilder? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> if (parser.name == "si") sb = StringBuilder()
                XmlPullParser.TEXT -> sb?.append(parser.text)
                XmlPullParser.END_TAG -> if (parser.name == "si") {
                    out.add(sb?.toString() ?: "")
                    sb = null
                }
            }
            event = parser.next()
        }
        return out
    }

    /** 解析工作表为稠密网格 + 合并区域 */
    private fun parseSheetData(xml: String, shared: List<String>): SheetGrid {
        val rows = ArrayList<MutableList<String>>()
        val merges = ArrayList<MergeRange>()
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        var curRow = -1
        var curRowList: MutableList<String>? = null
        var cellRef = ""
        var cellType = ""
        var sb: StringBuilder? = null
        var inV = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> {
                        val r = parser.getAttributeValue(null, "r")?.toIntOrNull()
                        curRow = if (r != null) r - 1 else rows.size
                        curRowList = ArrayList()
                    }
                    "c" -> {
                        cellRef = parser.getAttributeValue(null, "r") ?: ""
                        cellType = parser.getAttributeValue(null, "t") ?: ""
                        sb = StringBuilder()
                    }
                    "v", "t" -> inV = true
                    "mergeCell" -> parser.getAttributeValue(null, "ref")?.let { ref ->
                        parseRange(ref)?.let { merges.add(it) }
                    }
                }
                XmlPullParser.TEXT -> if (inV) sb?.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v", "t" -> inV = false
                    "c" -> {
                        val raw = sb?.toString()?.trim() ?: ""
                        val value = if (cellType == "s") {
                            raw.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                        } else raw
                        val col = colIndex(cellRef)
                        putCell(curRowList, col, value)
                        sb = null
                    }
                    "row" -> {
                        if (curRowList != null) {
                            while (rows.size <= curRow) rows.add(ArrayList())
                            rows[curRow] = curRowList
                        }
                        curRowList = null
                    }
                }
            }
            event = parser.next()
        }
        return SheetGrid(rows, merges)
    }

    /** "C8:T8" -> MergeRange(r1=7,c1=2,r2=7,c2=19) */
    private fun parseRange(ref: String): MergeRange? {
        val parts = ref.split(':')
        if (parts.size != 2) return null
        val a = parseRef(parts[0]) ?: return null
        val b = parseRef(parts[1]) ?: return null
        return MergeRange(a.first, a.second, b.first, b.second)
    }

    /** "C8" -> (row=7, col=2)，均 0 基 */
    private fun parseRef(ref: String): Pair<Int, Int>? {
        var col = -1
        var i = 0
        while (i < ref.length && ref[i].isLetter()) {
            col = (col + 1) * 26 + (ref[i].uppercaseChar() - 'A')
            i++
        }
        if (col < 0) return null
        val row = ref.substring(i).toIntOrNull() ?: return null
        return (row - 1) to col
    }

    private fun putCell(row: MutableList<String>?, col: Int, value: String) {
        if (row == null || col < 0) return
        while (row.size < col) row.add("")
        if (row.size == col) row.add(value) else row[col] = value
    }

    private fun colIndex(ref: String): Int {
        var n = -1
        for (ch in ref) {
            if (ch in 'A'..'Z') n = (n + 1) * 26 + (ch - 'A')
            else if (ch in 'a'..'z') n = (n + 1) * 26 + (ch - 'a')
            else break
        }
        return n
    }

    private fun readAll(input: InputStream): String {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            bos.write(buf, 0, n)
        }
        return String(bos.toByteArray(), Charsets.UTF_8)
    }

    // ==================== CSV ====================

    private fun parseCsv(input: InputStream): Result {
        val raw = readAll(input)
        val text = if (raw.isNotEmpty() && raw[0].code == BOM_CODE) raw.substring(1) else raw
        val rows = text.lines()
            .filter { it.isNotBlank() }
            .map { line -> splitCsvLine(line).map { it.trim() } }
        if (rows.isEmpty()) return Result(error = "CSV 是空的")
        return rowsFromGrid(rows)
    }

    /** 简易 CSV 切分：支持双引号包裹与内嵌逗号 */
    private fun splitCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuote = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuote && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"')
                    i++
                }
                c == '"' -> inQuote = !inQuote
                c == ',' && !inQuote -> {
                    out.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    // ==================== 通用「课程行」网格 -> 结构化 ====================

    private fun rowsFromGrid(grid: List<List<String>>): Result {
        if (grid.isEmpty()) return Result(error = "没有内容")

        val first = grid.first().map { it.trim() }
        val hasHeader = first.any { f ->
            f.contains("课程") || f.contains("名称") || f.lowercase().contains("course")
        }
        val mapping: Map<Int, Int> =
            if (hasHeader) buildMapping(first) else HEADER_ALIASES.keys.associateWith { it }
        val dataRows = if (hasHeader) grid.drop(1) else grid

        val out = mutableListOf<Row>()
        var skipped = 0
        for (r in dataRows) {
            fun cell(idx: Int): String {
                val col = mapping[idx] ?: idx
                return r.getOrNull(col)?.trim() ?: ""
            }
            val name = cell(0)
            if (name.isBlank()) {
                if (r.any { it.isNotBlank() }) skipped++
                continue
            }
            out.add(
                Row(
                    courseName = name,
                    className = cell(1),
                    teacher = cell(2),
                    classType = parseClassType(cell(3)),
                    credits = cell(4).toDoubleOrNull() ?: 0.0,
                    content = cell(5),
                    weekday = parseWeekday(cell(6)),
                    startTime = cell(7).ifBlank { "08:00" },
                    endTime = cell(8).ifBlank { "09:40" },
                    location = cell(9),
                    weekFrom = cell(10).toIntOrNull() ?: 1,
                    weekTo = cell(11).toIntOrNull() ?: 18,
                    parity = parseParity(cell(12))
                )
            )
        }
        return if (out.isEmpty()) Result(error = "没有解析出有效的课程行", skipped = skipped)
        else Result(rows = out, skipped = skipped)
    }

    private fun buildMapping(header: List<String>): Map<Int, Int> {
        val map = mutableMapOf<Int, Int>()
        header.forEachIndexed { col, raw ->
            val h = raw.trim().lowercase()
            HEADER_ALIASES.forEach { (field, aliases) ->
                if (!map.containsKey(field)) {
                    if (aliases.any { a -> h == a.lowercase() || h.contains(a.lowercase()) }) {
                        map[field] = col
                    }
                }
            }
        }
        return map
    }

    private fun parseWeekday(s: String): Int {
        val t = s.trim()
        t.toIntOrNull()?.let { if (it in 1..7) return it }
        val names = listOf("一", "二", "三", "四", "五", "六", "日", "天")
        names.forEachIndexed { i, ch -> if (t.contains(ch)) return i + 1 }
        return 1
    }

    private fun parseParity(s: String): String {
        val t = s.trim()
        return when {
            t.contains("单") -> CourseSchedule.PARITY_ODD
            t.contains("双") -> CourseSchedule.PARITY_EVEN
            t.contains("odd", true) -> CourseSchedule.PARITY_ODD
            t.contains("even", true) -> CourseSchedule.PARITY_EVEN
            else -> CourseSchedule.PARITY_ALL
        }
    }

    private fun parseClassType(s: String): String {
        val t = s.trim()
        return when {
            t.contains("讲座") || t.contains("大课") -> ClassType.LECTURE
            t.contains("小班") -> ClassType.SMALL
            t.contains("大班") -> ClassType.LARGE
            t.contains("实验") || t.contains("实践") -> ClassType.LAB
            t.contains("研讨") || t.contains("讨论") -> ClassType.SEMINAR
            t.isBlank() -> ClassType.LARGE
            else -> ClassType.OTHER
        }
    }
}

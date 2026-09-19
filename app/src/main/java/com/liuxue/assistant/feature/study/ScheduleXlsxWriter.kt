package com.liuxue.assistant.feature.study

import com.liuxue.assistant.data.study.ClassType
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把课程表反向生成「按教室排的矩阵」xlsx（列 = 教室，行 = 星期×时间），
 * 与 [ScheduleMatrix] 的解析规则完全对称，可被同一解析器再读回来。
 *
 * 纯手写 OOXML（zip + xml），不依赖 Apache POI：
 *   - 单元格文本用 inlineStr，不建 sharedStrings
 *   - 时间写 Excel 小数并用内置 numFmtId=21（h:mm）显示
 *   - 课程行横向合并 18 列（第 1..18 周）；下面一行写该房间空闲的周次
 */
object ScheduleXlsxWriter {

    private const val TOTAL_WEEKS = 18
    private val DAY_NAMES = listOf(
        "понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"
    )

    data class ExportLesson(
        val className: String,
        val courseName: String,
        val teacher: String = "",
        val typeLabel: String = "",
        val weekday: Int,
        val startTime: String,
        val endTime: String = "",
        val location: String = "",
        val weekFrom: Int,
        val weekTo: Int,
        val hours: Int = 0
    )

    fun typeLabelOf(classType: String): String = when (classType) {
        ClassType.LECTURE -> "Л."
        ClassType.LAB -> "Лаб."
        ClassType.SEMINAR -> "Пр."
        else -> ""
    }

    fun writeFile(file: File, lessons: List<ExportLesson>, sheetName: String = "课表") {
        file.parentFile?.mkdirs()
        file.writeBytes(toXlsx(lessons, sheetName))
    }

    fun toXlsx(lessons: List<ExportLesson>, sheetName: String = "课表"): ByteArray {
        val sheetXml = buildSheetXml(lessons)
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", CONTENT_TYPES)
            put("_rels/.rels", ROOT_RELS)
            put("xl/workbook.xml", workbookXml(sheetName))
            put("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            put("xl/styles.xml", STYLES)
            put("xl/worksheets/sheet1.xml", sheetXml)
        }
        return bos.toByteArray()
    }

    // ==================== 表格构建 ====================

    private data class CellEntry(
        val className: String,
        val courseName: String,
        val teacher: String,
        val typeLabel: String,
        var hours: Int,
        val ranges: MutableList<Pair<Int, Int>> = ArrayList()
    )

    private fun buildSheetXml(input: List<ExportLesson>): String {
        val lessons = input.filter { it.weekday in 1..7 }
            .map { it.copy(location = it.location.trim().ifBlank { "未指定教室" }) }
        val rooms = lessons.map { it.location }.distinct().sortedWith(roomComparator())
        val slots = lessons.map { it.startTime }.distinct().sortedBy { minutesOf(it) }
        val days = (lessons.map { it.weekday }.distinct() + listOf(1, 2, 3, 4, 5, 6)).distinct().sorted()
        val blockStart = rooms.indices.map { 3 + it * TOTAL_WEEKS }

        // 同一「星期+时段+教室+班级+课程+教师+类型」合并周次
        val bucket = HashMap<String, MutableList<CellEntry>>()
        for (l in lessons) {
            val wf = minOf(l.weekFrom, l.weekTo).coerceIn(1, TOTAL_WEEKS)
            val wt = maxOf(l.weekFrom, l.weekTo).coerceIn(1, TOTAL_WEEKS)
            val key = cellKey(l.weekday, l.startTime, l.location)
            val list = bucket.getOrPut(key) { ArrayList() }
            val hit = list.firstOrNull {
                it.className == l.className && it.courseName == l.courseName &&
                    it.teacher == l.teacher && it.typeLabel == l.typeLabel
            }
            if (hit != null) {
                hit.ranges.add(wf to wt)
                if (l.hours > hit.hours) hit.hours = l.hours
            } else {
                list.add(CellEntry(l.className, l.courseName, l.teacher, l.typeLabel, l.hours, mutableListOf(wf to wt)))
            }
        }

        val merges = ArrayList<String>()
        val data = StringBuilder()

        fun appendRow(r: Int, cellXml: List<String>) {
            if (cellXml.isEmpty()) {
                data.append("<row r=\"").append(r).append("\"/>")
            } else {
                data.append("<row r=\"").append(r).append("\">")
                cellXml.forEach { data.append(it) }
                data.append("</row>")
            }
        }

        appendRow(3, listOf(inlineCell(ref(1, 3), "Оснащение")))
        appendRow(4, listOf(inlineCell(ref(1, 4), "Кол. мест")))
        val row5 = ArrayList<String>().apply { add(inlineCell(ref(1, 5), "Описание")) }
        val row6 = ArrayList<String>().apply { add(inlineCell(ref(1, 6), "День")); add(inlineCell(ref(2, 6), "Время")) }
        val row7 = ArrayList<String>().apply { add(inlineCell(ref(1, 7), "Нед")) }
        for ((i, start) in blockStart.withIndex()) {
            row5.add(inlineCell(ref(start, 5), rooms[i]))
            row6.add(inlineCell(ref(start, 6), rooms[i]))
            merges.add(ref(start, 5) + ":" + ref(start + TOTAL_WEEKS - 1, 5))
            merges.add(ref(start, 6) + ":" + ref(start + TOTAL_WEEKS - 1, 6))
            for (w in 1..TOTAL_WEEKS) row7.add(intCell(ref(start + w - 1, 7), w))
        }
        appendRow(5, row5)
        appendRow(6, row6)
        appendRow(7, row7)

        var r = 8
        for (day in days) {
            val dayStart = r
            for (slot in slots) {
                val lessonRow = ArrayList<String>()
                val freeRow = ArrayList<String>()
                lessonRow.add(inlineCell(ref(1, r), dayName(day)))
                lessonRow.add(timeCell(ref(2, r), slot))
                for ((i, start) in blockStart.withIndex()) {
                    val list = bucket[cellKey(day, slot, rooms[i])] ?: continue
                    if (list.isEmpty()) continue
                    lessonRow.add(inlineCell(ref(start, r), list.joinToString(" // ") { renderEntry(it) }))
                    merges.add(ref(start, r) + ":" + ref(start + TOTAL_WEEKS - 1, r))
                    val occupied = HashSet<Int>()
                    for (e in list) for ((a, b) in e.ranges) for (w in a..b) if (w in 1..TOTAL_WEEKS) occupied.add(w)
                    for (w in 1..TOTAL_WEEKS) {
                        if (w !in occupied) freeRow.add(intCell(ref(start + w - 1, r + 1), w))
                    }
                }
                appendRow(r, lessonRow)
                appendRow(r + 1, freeRow)
                merges.add(ref(2, r) + ":" + ref(2, r + 1))
                r += 2
            }
            if (r > dayStart) merges.add(ref(1, dayStart) + ":" + ref(1, r - 1))
        }

        val mergeXml = if (merges.isEmpty()) "" else
            "<mergeCells count=\"" + merges.size + "\">" +
                merges.joinToString("") { "<mergeCell ref=\"" + it + "\"/>" } + "</mergeCells>"
        return XML_HEAD + "<worksheet xmlns=\"" + MAIN_NS + "\"><sheetData>" + data +
            "</sheetData>" + mergeXml + "</worksheet>"
    }

    // ==================== 文本与 XML 工具 ====================

    private fun cellKey(day: Int, slot: String, room: String): String = day.toString() + "|" + slot + "|" + room

    private fun dayName(day: Int): String = DAY_NAMES.getOrElse(day - 1) { DAY_NAMES[0] }

    private fun renderEntry(e: CellEntry): String {
        val sb = StringBuilder()
        sb.append(e.className).append(' ').append(e.courseName)
        val spec = ArrayList<String>()
        val head = if (e.typeLabel.isBlank()) "" else e.typeLabel + " "
        spec.add(head + weekText(e.ranges))
        if (e.hours > 0) spec.add(e.hours.toString() + " ч.")
        sb.append(" (").append(spec.joinToString(", ")).append(")")
        if (e.teacher.isNotBlank() && !e.courseName.contains(e.teacher)) {
            sb.append(", ").append(e.teacher)
        }
        return sb.toString()
    }

    /** `[2-11,16-17]` -> `2-11, 16-17 нед.`（合并相邻/重叠段） */
    private fun weekText(ranges: List<Pair<Int, Int>>): String {
        val norm = ranges.map { minOf(it.first, it.second).coerceIn(1, TOTAL_WEEKS) to maxOf(it.first, it.second).coerceIn(1, TOTAL_WEEKS) }
            .sortedBy { it.first }
        val merged = ArrayList<Pair<Int, Int>>()
        for (r0 in norm) {
            val last = merged.lastOrNull()
            if (last != null && r0.first <= last.second + 1) {
                merged[merged.size - 1] = last.first to maxOf(last.second, r0.second)
            } else merged.add(r0)
        }
        val sb = StringBuilder()
        merged.forEachIndexed { i, r0 ->
            if (i > 0) sb.append(", ")
            if (r0.first == r0.second) sb.append(r0.first) else sb.append(r0.first).append('-').append(r0.second)
        }
        sb.append(" нед.")
        return sb.toString()
    }

    private fun ref(col: Int, row: Int): String = colName(col) + row

    private fun colName(col: Int): String {
        var c = col
        val sb = StringBuilder()
        while (c > 0) {
            c--
            sb.append(('A'.code + c % 26).toChar())
            c /= 26
        }
        return sb.reverse().toString()
    }

    private fun inlineCell(ref: String, text: String): String =
        "<c r=\"" + ref + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">" + xmlEscape(text) + "</t></is></c>"

    private fun intCell(ref: String, value: Int): String =
        "<c r=\"" + ref + "\"><v>" + value + "</v></c>"

    /** Excel 时间小数（x/1440） */
    private fun timeCell(ref: String, start: String): String {
        val f = minutesOf(start).toDouble() / (24.0 * 60.0)
        return "<c r=\"" + ref + "\" s=\"1\"><v>" + String.format(Locale.US, "%.10f", f) + "</v></c>"
    }

    private fun minutesOf(hhmm: String): Int {
        val parts = hhmm.trim().split(':')
        if (parts.size < 2) return 0
        val h = parts[0].toIntOrNull() ?: 0
        val m = parts[1].toIntOrNull() ?: 0
        return (h * 60 + m).coerceIn(0, 24 * 60 - 1)
    }

    private fun xmlEscape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> if (ch.code >= 0x20 || ch == '\t') sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 数字教室在前（按数值），其余按名称 */
    private fun roomComparator(): Comparator<String> = Comparator { a, b ->
        val na = leadingNumber(a)
        val nb = leadingNumber(b)
        if (na != nb) na.compareTo(nb) else a.compareTo(b)
    }

    private fun leadingNumber(s: String): Int =
        Regex("""^\s*(\d+)""").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

    // ==================== OOXML 骨架 ====================

    private const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"

    private val CONTENT_TYPES = XML_HEAD +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
        "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
        "</Types>"

    private val ROOT_RELS = XML_HEAD +
        "<Relationships xmlns=\"" + PKG_REL_NS + "\">" +
        "<Relationship Id=\"rId1\" Type=\"" + REL_NS + "/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"

    private val WORKBOOK_RELS = XML_HEAD +
        "<Relationships xmlns=\"" + PKG_REL_NS + "\">" +
        "<Relationship Id=\"rId1\" Type=\"" + REL_NS + "/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"" + REL_NS + "/styles\" Target=\"styles.xml\"/>" +
        "</Relationships>"

    private fun workbookXml(sheetName: String): String = XML_HEAD +
        "<workbook xmlns=\"" + MAIN_NS + "\" xmlns:r=\"" + REL_NS + "\">" +
        "<sheets><sheet name=\"" + xmlEscape(sheetName.ifBlank { "课表" }) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets>" +
        "</workbook>"

    /** 最小样式表：xf#1 用内置 numFmtId=21（h:mm）显示时间 */
    private val STYLES = XML_HEAD +
        "<styleSheet xmlns=\"" + MAIN_NS + "\">" +
        "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
        "<fills count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills>" +
        "<borders count=\"1\"><border/></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"2\">" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
        "<xf numFmtId=\"21\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>" +
        "</cellXfs>" +
        "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
        "</styleSheet>"
}

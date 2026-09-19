package com.liuxue.assistant.feature.study

import com.liuxue.assistant.data.study.ClassType
import com.liuxue.assistant.data.study.CourseSchedule
import kotlin.math.roundToInt

/**
 * 俄罗斯大学「按教室排的矩阵课表」解析器（列 = 教室，行 = 星期×时间）。
 *
 * 样例 `课表样式.xlsx`（ВШРФК 专业）结构：
 *   - r3 Оснащение / r4 Кол. мест / r5 Описание（教室描述，列 → 教室号来源）
 *   - r6 `День | Время | <教室号…>`，r7 `Нед` 行 = 每个教室占 18 列（第 1..N 周）
 *   - r8+ 交替出现「课程行」（横向合并 18 列）与「空闲周行」（写该房间空闲的周次）
 *
 * 单元格文本形如：
 *   `10.3-256 ДВ Методы и принципы … (Пр. 2-11, 16-17 нед., 24 ч.), Усманова Л.А.`
 *   多门课用 `//`（或 `\`）分隔；多段周次拆成多条上课时段。
 *
 * 解析刻意宽松：无法识别的片段收进 unparsed，绝不因单条异常而整体失败。
 */
object ScheduleMatrix {

    private val DAYS = mapOf(
        "понедельник" to 1, "вторник" to 2, "среда" to 3,
        "четверг" to 4, "пятница" to 5, "суббота" to 6, "воскресенье" to 7
    )

    /** 分隔符：`//`（但不是 URL 里的）、单独的反斜杠、换行 */
    private val SEP = Regex("""(?<![:/])\s*//+\s*|\s*\\+\s*|\n""")

    /** 周次：`2-11, 16-17 нед.` / `12-13,15 нед.` / `16 нед.` */
    private val WEEKS_RE = Regex(
        """(\d+(?:\s*-\s*\d+)?(?:\s*,\s*\d+(?:\s*-\s*\d+)?)*)\s*нед""",
        RegexOption.IGNORE_CASE
    )

    /** 类型标记：Пр. / Лек. / Лаб. / Сем. / ФТД / ДВ，单字母 Л 必须带点 */
    private val TYPE_RE = Regex(
        """(?<![А-Яа-яЁёA-Za-z])(?:пр|лекц|лек|лаб|сем|фтд|дв)\.?(?![А-Яа-яЁёA-Za-z])""" +
            """|(?<![А-Яа-яЁёA-Za-z])л\.(?![А-Яа-яЁёA-Za-z])""",
        RegexOption.IGNORE_CASE
    )

    private val CLASS_RE = Regex("""10\s*\.\s*3\s*-\s*(\d+)([АаБбABab])?""")
    private val HOURS_RE = Regex("""(\d+)\s*ч\.""")
    private val SUB_RE = Regex("""[Пп]одгруппа\s*([АаБбABab])""")
    private val AUD_RE = Regex("""ауд\.[^,;)]*\)?""", RegexOption.IGNORE_CASE)
    private val CLOCK_RE = Regex("""\b\d{1,2}:\d{2}\b""")
    private val PERENOS_RE = Regex("""-\s*\d+\s*[нn]\.?\s*перенос|\bперенос\b""", RegexOption.IGNORE_CASE)
    private val DV_RE = Regex("""(?<![А-Яа-яЁёA-Za-z])(дв|фтд)\.?(?![А-Яа-яЁёA-Za-z])""", RegexOption.IGNORE_CASE)
    private val LINK_RE = Regex("""https?://\S+""")
    private val LINK2_RE = Regex("""ссылка\s*:?""", RegexOption.IGNORE_CASE)
    private val DOT_RE = Regex("""(?<![А-Яа-яЁёA-Za-z])(дот|ссылка)\s*:?""", RegexOption.IGNORE_CASE)
    /** 文本里的"ауд. 219"房间提示 */
    private val INLINE_ROOM_RE = Regex("""ауд\.[\s]*([0-9][0-9А-Яа-я/\-]*)""", RegexOption.IGNORE_CASE)

    private const val INIT = """[А-ЯЁ]{1,2}\.?\s*[А-ЯЁ]?\.?"""
    private val NAME_RE = Regex("""^[А-ЯЁ][а-яё\-]{1,}(?:\s+[А-ЯЁ][а-яё\-]+){0,2}(?:\s+$INIT)?${'$'}""")
    private val TAIL_RE = Regex("""([А-ЯЁ][а-яё\-]+(?:\s+[А-ЯЁ][а-яё\-]+){0,2}\s+$INIT)\s*${'$'}""")
    private val LASTNAME_RE =
        Regex("""(?:^|\s\s)\s*([А-ЯЁ][а-яё\-]{2,}(?:\s+$INIT)?)\s*(?:,\s*\d+)?\s*${'$'}""")
    private val TOKEN_RE = Regex("""[А-ЯЁа-яё]{2,}""")
    private val BAD_NAME_RE = Regex("""(допка|зачет|экзамен|перенос|^\d|\d\.\d+-\d)""", RegexOption.IGNORE_CASE)

    private const val MAX_UNPARSED = 200
    private const val DEFAULT_WEEKS = 18

    // ==================== 判定 & 入口 ====================

    /** 判断一张表是否是「按教室排的矩阵课表」 */
    fun looksLikeMatrix(sheet: ScheduleImporter.SheetGrid): Boolean {
        val rows = sheet.rows
        var weekRow = -1
        for (r in rows.indices) {
            if (cellAt(rows, r, 0).trim().equals("Нед", true)) { weekRow = r; break }
            if (r > 24) break
        }
        if (weekRow <= 1) return false
        var hasHeader = false
        for (r in 0 until weekRow) {
            if (cellAt(rows, r, 0).contains("День") || cellAt(rows, r, 1).contains("Время")) hasHeader = true
        }
        if (!hasHeader) return false
        for (c in 2 until (rows.getOrNull(weekRow)?.size ?: 0)) {
            if (cellAt(rows, weekRow, c).trim().toDoubleOrNull() == 1.0) return true
        }
        return false
    }

    fun parse(sheet: ScheduleImporter.SheetGrid, sheetName: String): ScheduleImporter.Result {
        val grid = expandMerges(sheet)
        val rows = grid.rows
        val weekRow = rows.withIndex().firstOrNull { cellAt(rows, it.index, 0).trim().equals("Нед", true) }?.index ?: -1
        if (weekRow < 0) return ScheduleImporter.Result(error = "没找到「Нед」表头行")

        // 教室列块：周次行出现 1 的位置，每块 18 列
        val blockStarts = ArrayList<Int>()
        var c = 2
        val width = rows[weekRow].size
        while (c < width) {
            if (cellAt(rows, weekRow, c).trim().toDoubleOrNull() == 1.0) {
                blockStarts.add(c); c += DEFAULT_WEEKS
            } else c++
        }
        if (blockStarts.isEmpty()) return ScheduleImporter.Result(error = "没找到教室列块")

        val locations = blockStarts.map { locationLabel(rows, weekRow, it) }
        val out = ArrayList<ScheduleImporter.Row>()
        val unparsed = ArrayList<String>()
        val classCounts = HashMap<String, Int>()

        var day = 0
        var time: String? = null
        for (r in (weekRow + 1) until rows.size) {
            val dayCell = cellAt(rows, r, 0).trim()
            if (dayCell.isNotEmpty()) DAYS[dayCell.lowercase()]?.let { day = it }
            parseTime(cellAt(rows, r, 1))?.let { time = it }
            if (day == 0 || time == null) continue

            for ((i, start) in blockStarts.withIndex()) {
                val text = cellAt(rows, r, start).trim()
                if (text.isEmpty() || !text.any { it.isLetter() }) continue
                val segments = SEP.split(text).map { it.trim() }.filter { it.isNotEmpty() }
                // 同一格内若只有一个班级代码，则无代码的片段继承它
                val cellClasses = HashSet<String>()
                for (seg in segments) for (m in CLASS_RE.findAll(seg)) cellClasses.add(classCode(m))
                val fallback = cellClasses.singleOrNull()

                for (seg in segments) {
                    val p = parseSegment(seg)
                    if (!validName(p.name)) { addUnparsed(unparsed, seg); continue }
                    val classes = if (p.classes.isNotEmpty()) p.classes else listOfNotNull(fallback)
                    if (classes.isEmpty()) { addUnparsed(unparsed, seg); continue }
                    val withSub = if (p.sub == null) classes
                    else classes.map { if (it.last() in "АБабABab") it else it + p.sub }
                    val ranges = p.ranges.ifEmpty { listOf(1 to DEFAULT_WEEKS) }
                    for (cls in withSub) {
                        classCounts[cls] = (classCounts[cls] ?: 0) + 1
                        for ((wf, wt) in ranges) {
                            out.add(
                                ScheduleImporter.Row(
                                    courseName = p.name,
                                    className = cls,
                                    teacher = p.teacher,
                                    classType = typeOf(p.primaryType),
                                    weekday = day,
                                    startTime = time!!,
                                    endTime = endTime(time!!),
                                    location = withInlineRoom(locations[i], text),
                                    weekFrom = wf,
                                    weekTo = wt,
                                    parity = CourseSchedule.PARITY_ALL,
                                    hours = p.hours,
                                    major = sheetName,
                                    note = ""
                                )
                            )
                        }
                    }
                }
            }
        }
        if (out.isEmpty()) return ScheduleImporter.Result(error = "这张表里没解析出任何课程")
        return ScheduleImporter.Result(
            rows = out,
            skipped = unparsed.size,
            unparsed = unparsed,
            format = "教室矩阵（ВШРФК 样式）",
            major = sheetName,
            classCounts = classCounts
        )
    }

    // ==================== 单元格文本解析 ====================

    private data class Segment(
        val name: String,
        val teacher: String,
        val classes: List<String>,
        val sub: String?,
        val primaryType: String?,
        val ranges: List<Pair<Int, Int>>,
        val hours: Int
    )

    private fun parseSegment(raw: String): Segment {
        var s = raw.trim()
        s = LINK_RE.replace(s, " ")
        s = LINK2_RE.replace(s, " ")
        s = DOT_RE.replace(s, " ")

        val hours = HOURS_RE.findAll(s).sumOf { it.groupValues[1].toIntOrNull() ?: 0 }
        val sub = SUB_RE.find(s)?.groupValues?.get(1)?.uppercase()
        val classes = CLASS_RE.findAll(s).map { classCode(it) }.toList()
        val dv = DV_RE.find(s)?.groupValues?.get(1)?.uppercase()

        val typeMatches = TYPE_RE.findAll(s).toList()
        val specs = WEEKS_RE.findAll(s).map { m ->
            val prior = typeMatches.lastOrNull { it.range.first < m.range.first }
            prior?.value?.lowercase()?.trimEnd('.') to rangesFrom(m.groupValues[1])
        }.toList()
        val ranges = specs.flatMap { it.second }

        val types = typeMatches.map { it.value.lowercase().trimEnd('.') }
        val primary = types.firstOrNull { it != "дв" && it != "фтд" }
            ?: types.firstOrNull { it == "дв" || it == "фтд" }

        var work = s
        work = WEEKS_RE.replace(work, " ")
        work = CLASS_RE.replace(work, " ")
        work = HOURS_RE.replace(work, " ")
        work = SUB_RE.replace(work, " ")
        work = AUD_RE.replace(work, " ")
        work = CLOCK_RE.replace(work, " ")
        work = PERENOS_RE.replace(work, " ")
        work = TYPE_RE.replace(work, " ")
        if (dv != null) {
            DV_RE.find(work)?.let {
                work = work.substring(0, it.range.first) + " " + work.substring(it.range.last + 1)
            }
        }
        work = collapse(work).trim(' ', ',', ';', '.', '(', ')', '-')

        var teacher = ""
        for (piece in work.split(',', ';').asReversed()) {
            val p = piece.trim().trim(' ', ',', ';', '.', '(', ')', '-')
            if (p.isNotEmpty() && p.length <= 45 && NAME_RE.matches(p)) { teacher = p; break }
            // `Шейнина Д.П. (Мифтахова Л.Б.)`：取括号前的姓名
            val head = p.substringBefore('(').trim(' ', ',', ';', '.', '(', ')', '-')
            if (head.isNotEmpty() && head.length <= 45 && NAME_RE.matches(head)) { teacher = head; break }
        }
        if (teacher.isBlank()) {
            val afterParen = work.substringAfterLast(')').trim(' ', ',', ';', '.', '(', ')', '-')
            if (afterParen.isNotEmpty() && afterParen.length <= 45 && NAME_RE.matches(afterParen)) {
                teacher = afterParen
            }
        }
        if (teacher.isBlank()) TAIL_RE.find(work)?.let { teacher = it.groupValues[1].trim() }
        if (teacher.isBlank()) LASTNAME_RE.find(work)?.let { teacher = it.groupValues[1].trim() }

        var name = work
        if (teacher.isNotBlank()) {
            // 从姓名出现处截断（后面可能还跟着 `(另一位教师)` 之类的尾巴）
            val idx = name.lastIndexOf(teacher)
            if (idx >= 0) name = name.substring(0, idx)
        }
        name = collapse(name).trim(' ', ',', ';', '.', '-')
        if (name.count { it == '(' } > name.count { it == ')' }) name += ")"
        if (name.count { it == ')' } > name.count { it == '(' }) name = name.trimEnd(')')
        if (dv != null && name.isNotBlank() && !name.startsWith(dv)) name = dv + " " + name

        return Segment(name, teacher, classes, sub, primary, ranges, hours)
    }

    private fun classCode(m: MatchResult): String =
        "10.3-" + m.groupValues[1] + m.groupValues[2].uppercase()

    private fun rangesFrom(spec: String): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (part in spec.split(',')) {
            val t = part.trim()
            val m = Regex("""^(\d+)\s*-\s*(\d+)${'$'}""").find(t)
            if (m != null) {
                addRange(out, m.groupValues[1].toInt(), m.groupValues[2].toInt())
            } else t.toIntOrNull()?.let { addRange(out, it, it) }
        }
        return out
    }

    private fun addRange(out: MutableList<Pair<Int, Int>>, a: Int, b: Int) {
        if (a > DEFAULT_WEEKS || b < 1) return
        out.add(a.coerceAtLeast(1) to b.coerceAtMost(DEFAULT_WEEKS))
    }

    private fun validName(n: String): Boolean {
        val t = n.trim()
        if (t.length < 3) return false
        if (BAD_NAME_RE.containsMatchIn(t)) return false
        val toks = TOKEN_RE.findAll(t).count()
        if (toks >= 2) return true
        return t.length >= 8 && toks == 1
    }

    private fun typeOf(raw: String?): String = when (raw) {
        "сем", "пр" -> ClassType.SEMINAR
        "лаб" -> ClassType.LAB
        "л", "лек", "лекц" -> ClassType.LECTURE
        else -> ClassType.OTHER
    }

    /** 收敛空白 + 去掉残留的 `( , )` 之类空壳 */
    private fun collapse(input: String): String {
        var w = Regex("""\s+""").replace(input, " ")
        var prev: String
        do {
            prev = w
            w = Regex("""\(\s*[,;.\s]*\)""").replace(w, " ")
            w = Regex("""\s*[,;]\s*(?=[,;)])""").replace(w, " ")
        } while (w != prev)
        return w.trim()
    }

    private fun addUnparsed(list: MutableList<String>, seg: String) {
        if (list.size < MAX_UNPARSED) list.add(seg.take(160))
    }

    // ==================== 网格工具 ====================

    /**
     * 上课地点 = 房间号（row6）+ 地点说明（row5 的 Описание）。
     * row5 形如 `216 (25) РЛиМП` / `219/220 (30) ПЭЛ` / `Онлайн занятия` / `ВОГ (ул. Кул Гали, д. 24)`。
     */
    private fun locationLabel(rows: List<List<String>>, weekRow: Int, col: Int): String {
        val room = cleanRoom(cellAt(rows, weekRow - 1, col).trim())
        val desc = cleanDesc(cellAt(rows, weekRow - 2, col).trim())
        return when {
            room.isNotEmpty() && desc.isNotEmpty() && !desc.contains(room, true) -> room + " · " + desc
            room.isNotEmpty() -> if (desc.isNotEmpty()) desc else room
            desc.isNotEmpty() -> desc
            else -> "教室" + (col + 1)
        }
    }

    /** `216.0` -> `216`（Excel 数值单元格会带 .0） */
    private fun cleanRoom(s: String): String {
        if (s.isBlank()) return ""
        var t = s
        while (t.length > 1 && t.endsWith(".0")) t = t.dropLast(2)
        return t.trim()
    }

    /** 去掉座位数 `(25)`，过滤过长/编辑备注，得到可读的地点名 */
    private fun cleanDesc(s: String): String {
        if (s.isBlank()) return ""
        var t = Regex("""\(\s*\d+\s*\)""").replace(s, " ")
        t = Regex("""\s+""").replace(t, " ").trim()
        if (t.length > 40) return ""
        if (t.contains("редактир") || t.contains("замена")) return ""
        return t
    }

    /** 若单元格里写了 `ауд. 219` 而列块房间不同，补上这个提示 */
    private fun withInlineRoom(base: String, cellText: String): String {
        val inline = INLINE_ROOM_RE.find(cellText)?.groupValues?.get(1)?.trim().orEmpty()
        if (inline.isEmpty()) return base
        if (base.isBlank()) return "ауд. " + inline
        if (base.contains(inline)) return base
        return base + " · ауд. " + inline
    }

    private fun parseTime(v: String): String? {
        val t = v.trim()
        if (t.isEmpty()) return null
        Regex("""^(\d{1,2}):(\d{2})""").find(t)?.let {
            return two(it.groupValues[1].toInt()) + ":" + two(it.groupValues[2].toInt())
        }
        val f = t.replace(',', '.').toDoubleOrNull() ?: return null
        if (f < 0.0 || f >= 1.0) return null
        val mins = (f * 24 * 60).roundToInt()
        return two(mins / 60) + ":" + two(mins % 60)
    }

    /** 默认一节 90 分钟（样例的 7 个时段均为 90 分钟课） */
    private fun endTime(start: String, minutes: Int = 90): String {
        val parts = start.split(':')
        val total = parts[0].toInt() * 60 + parts[1].toInt() + minutes
        return two(total / 60) + ":" + two(total % 60)
    }

    private fun two(n: Int): String = if (n < 10) "0" + n else n.toString()

    private fun cellAt(rows: List<List<String>>, r: Int, c: Int): String =
        rows.getOrNull(r)?.getOrNull(c) ?: ""

    /** 把合并区域展开：区域内每个格子都填上左上角的值 */
    private fun expandMerges(sheet: ScheduleImporter.SheetGrid): ScheduleImporter.SheetGrid {
        val grid = sheet.rows.map { ArrayList(it) }
        for (m in sheet.merges) {
            val v = grid.getOrNull(m.r1)?.getOrNull(m.c1) ?: continue
            for (r in m.r1..m.r2) {
                val row = grid.getOrNull(r) ?: continue
                while (row.size <= m.c2) row.add("")
                for (cc in m.c1..m.c2) row[cc] = v
            }
        }
        return ScheduleImporter.SheetGrid(grid, sheet.merges)
    }
}

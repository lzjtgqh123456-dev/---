package com.liuxue.assistant

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.liuxue.assistant.data.study.ClassType
import com.liuxue.assistant.feature.study.ScheduleImporter
import com.liuxue.assistant.feature.study.ScheduleXlsxWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 「教室矩阵」课表（ВШРФК 样式）真机测试：
 * 解析真实样例、多段周次拆分、合并单元格（教室/星期/时间）、а/б 分组，
 * 并验证「导出 xlsx → 再解析」往返一致。
 */
@RunWith(AndroidJUnit4::class)
class ScheduleMatrixTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /** 测试 APK 的 assets 必须用 InstrumentationRegistry 的 context 才能读到 */
    private fun assetUri(name: String): Uri {
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val out = File(context.cacheDir, name)
        testCtx.assets.open(name).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        return Uri.fromFile(out)
    }

    private fun real(): ScheduleImporter.Result =
        ScheduleImporter.import(context, assetUri("schedule_real.xlsx"), "schedule_real.xlsx")

    @Test
    fun 真实课表能解析出课程与班级() {
        val r = real()
        assertNull("不应有错误：" + r.error, r.error)
        println("格式=${r.format} 专业=${r.major} 行数=${r.rows.size} 班级数=${r.classCounts.size} 未解析=${r.unparsed.size}")
        assertTrue("应解析出大量上课时段，实际 " + r.rows.size, r.rows.size > 2500)
        for (cls in listOf("10.3-354", "10.3-622Б", "10.3-622А", "10.3-256", "10.3-601А")) {
            assertTrue("班级列表应包含 $cls", r.classCounts.containsKey(cls))
        }
        assertTrue("行都应带班级", r.rows.all { it.className.isNotBlank() })
        assertTrue("行都应有课程名", r.rows.all { it.courseName.isNotBlank() })
        assertTrue("行都应有教室", r.rows.all { it.location.isNotBlank() })
    }

    @Test
    fun 多段周次被拆成多条时段() {
        val r = real()
        val rows = r.rows.filter {
            it.className == "10.3-256" && it.courseName.contains("Методы и принципы")
        }
        println("Методы 时段数=" + rows.size + " 周次=" + rows.map { it.weekFrom.toString() + "-" + it.weekTo })
        assertTrue("应拆出多个时段", rows.size >= 2)
        assertTrue("应含 2-11", rows.any { it.weekFrom == 2 && it.weekTo == 11 })
        assertTrue("应含 16-17", rows.any { it.weekFrom == 16 && it.weekTo == 17 })
    }

    @Test
    fun 合并单元格的教室星期时间正确() {
        val r = real()
        val row = r.rows.firstOrNull {
            it.className == "10.3-354" &&
                it.courseName.contains("Практика по профилю") && it.weekFrom == 2
        }
        assertNotNull("应找到 10.3-354 的「Практика по профилю」", row)
        println("命中：" + row!!.courseName + " 周" + row.weekFrom + "-" + row.weekTo +
            " 星期" + row.weekday + " " + row.startTime + "-" + row.endTime + " @" + row.location)
        assertEquals(1, row.weekday)
        assertEquals("08:30", row.startTime)
        assertEquals("10:00", row.endTime)
        assertTrue("教室应由列块决定，实际 " + row.location, row.location.startsWith("216"))
        assertTrue("应识别出地点说明（РЛиМП），实际 " + row.location, row.location.contains("РЛиМП"))
        assertEquals(2, row.weekFrom)
        assertEquals(13, row.weekTo)
        assertEquals(ClassType.SEMINAR, row.classType)
    }

    @Test
    fun аb分组是不同的班级() {
        val r = real()
        assertTrue("应有 622А", r.classCounts.containsKey("10.3-622А"))
        assertTrue("应有 622Б", r.classCounts.containsKey("10.3-622Б"))
        val a = r.rows.filter { it.className == "10.3-622А" && it.courseName.contains("Коммуникативный") }
        val b = r.rows.filter { it.className == "10.3-622Б" && it.courseName.contains("Коммуникативный") }
        println("622А 条数=" + a.size + "，622Б 条数=" + b.size)
        assertTrue("622А 应有该课程", a.isNotEmpty())
        assertTrue("622Б 应有该课程", b.isNotEmpty())
    }

    @Test
    fun подгруппа按АБ拆成不同班级() {
        val r = real()
        assertTrue("应有 601А", r.classCounts.containsKey("10.3-601А"))
        assertTrue("应有 601Б", r.classCounts.containsKey("10.3-601Б"))
        val sp = r.rows.filter { it.className == "10.3-601А" && it.courseName.contains("Практическая грамматика") }
        assertTrue("601А 应有「Практическая грамматика」", sp.isNotEmpty())
    }

    @Test
    fun 导出xlsx能被再解析且关键信息保留() {
        val r = real()
        assertNull(r.error)
        val f = writeRoundTrip(r)
        val back = ScheduleImporter.import(context, Uri.fromFile(f), "roundtrip.xlsx")
        assertNull("回读不应报错：" + back.error, back.error)
        println("导出大小=" + f.length() + " 原始行=" + r.rows.size + " 回读行=" + back.rows.size)
        assertTrue("回读应保留绝大多数行，实际 " + back.rows.size, back.rows.size >= r.rows.size * 90 / 100)

        val a = weekKeys(r.rows)
        val b = weekKeys(back.rows)
        val common = a.intersect(b).size
        val ratio = common.toDouble() / a.size.toDouble()
        println("周次键重合率=" + "%.4f".format(ratio) + " 原始=" + a.size + " 回读=" + b.size)
        assertTrue("往返重合率应 >= 0.97，实际 " + ratio, ratio >= 0.97)

        val anchors = listOf(
            "10.3-354" to "Практика по профилю",
            "10.3-256" to "Методы и принципы",
            "10.3-622Б" to "Практическая грамматика",
            "10.3-622А" to "Коммуникативный практикум",
            "10.3-601А" to "Практическая грамматика"
        )
        for ((cls, part) in anchors) {
            assertTrue(
                "回读应保留 " + cls + " / " + part,
                back.rows.any { it.className == cls && it.courseName.contains(part) }
            )
        }

        val src = r.rows.first {
            it.className == "10.3-354" && it.courseName.contains("Практика по профилю") && it.weekFrom == 2
        }
        val dst = back.rows.firstOrNull {
            it.className == src.className && it.courseName == src.courseName &&
                it.weekday == src.weekday && it.startTime == src.startTime &&
                it.location == src.location && it.weekFrom == src.weekFrom
        }
        assertNotNull("回读应保留 10.3-354 周一 08:30 @216 第2周", dst)
        assertEquals(src.weekTo, dst!!.weekTo)
    }

    @Test
    fun 导出格式对干净数据是精确往返() {
        val lessons = listOf(
            ScheduleXlsxWriter.ExportLesson("10.3-354", "Практика по профилю подготовки (в области русского языка)", "Карасева А.И.", "Пр.", 1, "08:30", "10:00", "216", 2, 13, 24),
            ScheduleXlsxWriter.ExportLesson("10.3-256", "ДВ Методы и принципы современной лингвистики", "Усманова Л.А.", "Пр.", 1, "10:10", "11:40", "216", 2, 11, 24),
            ScheduleXlsxWriter.ExportLesson("10.3-256", "ДВ Методы и принципы современной лингвистики", "Усманова Л.А.", "Пр.", 1, "10:10", "11:40", "216", 16, 17, 24),
            ScheduleXlsxWriter.ExportLesson("10.3-622А", "Коммуникативный практикум", "Языкова А.Ю.", "Пр.", 3, "12:10", "13:40", "219/220", 12, 17, 12),
            ScheduleXlsxWriter.ExportLesson("10.3-999", "Онлайн курс", "Петров П.П.", "Л.", 5, "15:50", "17:20", "Онлайн занятия", 2, 4, 8),
            ScheduleXlsxWriter.ExportLesson("10.3-999", "Онлайн курс", "Петров П.П.", "Л.", 4, "15:50", "17:20", "Онлайн занятия", 5, 5, 8)
        )
        val f = File(context.cacheDir, "roundtrip_clean.xlsx")
        ScheduleXlsxWriter.writeFile(f, lessons, "课表")
        val back = ScheduleImporter.import(context, Uri.fromFile(f), "roundtrip_clean.xlsx")
        assertNull("回读不应报错：" + back.error, back.error)
        println("干净往返：写入=" + lessons.size + " 回读=" + back.rows.size)
        assertEquals("干净数据应逐条往返", lessons.size, back.rows.size)
        for (l in lessons) {
            val hit = back.rows.any {
                it.className == l.className && it.courseName == l.courseName &&
                    it.teacher.trimEnd('.') == l.teacher.trimEnd('.') && it.weekday == l.weekday &&
                    it.startTime == l.startTime && it.location == l.location &&
                    it.weekFrom == l.weekFrom && it.weekTo == l.weekTo
            }
            assertTrue(
                "应往返：" + l.className + " / " + l.courseName + " / " + l.weekFrom + "-" + l.weekTo,
                hit
            )
        }
    }

    /** 把解析结果原样导出，并检查 zip 结构 */
    private fun writeRoundTrip(r: ScheduleImporter.Result): File {
        val lessons = r.rows.map {
            ScheduleXlsxWriter.ExportLesson(
                className = it.className,
                courseName = it.courseName,
                teacher = it.teacher,
                typeLabel = ScheduleXlsxWriter.typeLabelOf(it.classType),
                weekday = it.weekday,
                startTime = it.startTime,
                endTime = it.endTime,
                location = it.location,
                weekFrom = it.weekFrom,
                weekTo = it.weekTo,
                hours = it.hours
            )
        }
        val f = File(context.cacheDir, "roundtrip.xlsx")
        ScheduleXlsxWriter.writeFile(f, lessons, "课表")
        assertTrue("导出文件应存在且非空", f.exists() && f.length() > 1000)
        val zipNames = java.util.Collections.list(java.util.zip.ZipFile(f).entries()).map { it.name }
        for (n in listOf(
            "[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
            "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml"
        )) {
            assertTrue("zip 应包含 " + n, zipNames.contains(n))
        }
        return f
    }

    /** 把每个上课时段展开成「班级|课程|星期|开始|教室|第N周」的周次键，便于比对往返一致性 */
    private fun weekKeys(rows: List<ScheduleImporter.Row>): Set<String> {
        val out = HashSet<String>()
        for (r in rows) {
            if (r.className.isBlank() || r.courseName.isBlank()) continue
            val from = minOf(r.weekFrom, r.weekTo)
            val to = maxOf(r.weekFrom, r.weekTo)
            for (w in from..to) {
                out.add(r.className + "|" + r.courseName + "|" + r.weekday + "|" + r.startTime + "|" + r.location + "|" + w)
            }
        }
        return out
    }
}

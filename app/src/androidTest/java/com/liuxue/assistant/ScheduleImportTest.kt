package com.liuxue.assistant

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.liuxue.assistant.data.study.ClassType
import com.liuxue.assistant.data.study.CourseSchedule
import com.liuxue.assistant.feature.study.ScheduleImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 课表导入解析器真机测试。
 * 验证 .xlsx（zip+xml）与 .csv 的解析、类型/星期/单双周/周次识别。
 */
@RunWith(AndroidJUnit4::class)
class ScheduleImportTest {

    private lateinit var context: Context

    @Before
    fun setUp() { context = ApplicationProvider.getApplicationContext() }

    /**
     * 把测试 APK 的 assets 复制出来。
     * 注意：必须用 InstrumentationRegistry 的 context（测试 APK），
     * 用 app context 读不到 androidTest/assets。
     */
    private fun assetFileUri(name: String): Uri {
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val out = File(context.cacheDir, name)
        testCtx.assets.open(name).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        return Uri.fromFile(out)
    }

    @Test
    fun xlsx能被正确解析() {
        val r = ScheduleImporter.import(context, assetFileUri("schedule_test.xlsx"), "schedule_test.xlsx")
        assertNull("不应有错误：" + r.error, r.error)
        assertEquals(4, r.rows.size)
        val f = r.rows[0]
        println("第1行: ${f.courseName} / ${f.className} / ${f.teacher} / ${f.startTime}")
        assertEquals("俄语精读", f.courseName)
        assertEquals("俄语2101班", f.className)
        assertEquals("伊万诺夫", f.teacher)
        assertEquals(ClassType.LARGE, f.classType)
        assertEquals("08:00", f.startTime)
        assertEquals("主楼302", f.location)
    }

    @Test
    fun 课程类型识别正确() {
        val r = ScheduleImporter.import(context, assetFileUri("schedule_test.xlsx"), "x.xlsx")
        val m = r.rows.associateBy { it.courseName }
        assertEquals(ClassType.SMALL, m["俄语口语"]?.classType)
        assertEquals(ClassType.LECTURE, m["俄罗斯文化"]?.classType)
    }

    @Test
    fun 单双周与周次识别正确() {
        val r = ScheduleImporter.import(context, assetFileUri("schedule_test.xlsx"), "x.xlsx")
        val m = r.rows.associateBy { it.courseName }
        assertEquals(CourseSchedule.PARITY_ALL, m["俄语精读"]!!.parity)
        assertEquals(CourseSchedule.PARITY_EVEN, m["俄罗斯文化"]!!.parity)
        assertEquals(CourseSchedule.PARITY_ODD, m["俄语语法"]!!.parity)
        assertEquals(3, m["俄罗斯文化"]!!.weekFrom)
        assertEquals(12, m["俄罗斯文化"]!!.weekTo)
    }

    @Test
    fun 星期与时间识别正确() {
        val r = ScheduleImporter.import(context, assetFileUri("schedule_test.xlsx"), "x.xlsx")
        val m = r.rows.associateBy { it.courseName }
        assertEquals(1, m["俄语精读"]!!.weekday)
        assertEquals(3, m["俄语口语"]!!.weekday)
        assertEquals(5, m["俄罗斯文化"]!!.weekday)
        assertEquals("13:00", m["俄语语法"]!!.startTime)
    }

    @Test
    fun csv不带表头也能解析() {
        val out = File(context.cacheDir, "t.csv")
        out.writeText(
            "俄语听力,俄语2101班,张三,小班,2,,3,10:00,11:40,语音室,1,16,每周\n" +
                "俄国史,全校,李四,讲座,2,,4,14:00,15:40,大讲堂,1,18,双周\n"
        )
        val r = ScheduleImporter.import(context, Uri.fromFile(out), "t.csv")
        assertNull(r.error)
        assertEquals(2, r.rows.size)
        assertEquals("俄语听力", r.rows[0].courseName)
        assertEquals(ClassType.SMALL, r.rows[0].classType)
        assertEquals(CourseSchedule.PARITY_EVEN, r.rows[1].parity)
    }

    @Test
    fun 模板自身可解析() {
        val tpl = ScheduleImporter.templateCsv()
        assertTrue(tpl.contains("课程名称"))
        val out = File(context.cacheDir, "tpl.csv")
        out.writeText(tpl)
        val r = ScheduleImporter.import(context, Uri.fromFile(out), "tpl.csv")
        assertNull("模板应能解析：" + r.error, r.error)
        assertEquals(3, r.rows.size)
    }

    @Test
    fun 空文件给出明确错误() {
        val out = File(context.cacheDir, "empty.csv")
        out.writeText("")
        val r = ScheduleImporter.import(context, Uri.fromFile(out), "empty.csv")
        assertNotNull(r.error)
        println("空文件提示: " + r.error)
    }
}

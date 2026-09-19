package com.liuxue.assistant

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.liuxue.assistant.data.ocr.OcrEngine
import com.liuxue.assistant.data.ocr.OcrScript
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 俄文（西里尔）识别设备测试。
 *
 * 背景：第一批只接了中英 rec 模型，用户反馈"西里尔字母错误率极高"——那是必然的（模型词表里根本没有西里尔）。
 * 本用例固化"接俄文模型后"的效果：
 *  - 印刷体合成图：逐行与 ground truth 比对，要求**字符级完全一致**
 *  - 真机俄文截图（练习册照片）：俄文模型必须比中英模型好非常多（西里尔字符数 / 平均分）
 */
@RunWith(AndroidJUnit4::class)
class OcrRussianTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun modelFile(name: String) = File(File(ctx.getExternalFilesDir(null), "ocr"), name)

    private fun modelsReady(): Boolean =
        listOf("det.onnx", "rec_ch.onnx", "keys_ch.txt", "rec_ru.onnx", "dict_ru.txt")
            .all { modelFile(it).let { f -> f.isFile && f.length() > 100 } }

    private fun load() = OcrEngine.load(
        detPath = modelFile("det.onnx").absolutePath,
        recPath = modelFile("rec_ch.onnx").absolutePath,
        keysPath = modelFile("keys_ch.txt").absolutePath,
        recRuPath = modelFile("rec_ru.onnx").absolutePath,
        keysRuPath = modelFile("dict_ru.txt").absolutePath
    )

    private fun image(asset: String) =
        InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use {
            BitmapFactory.decodeStream(it)
        }

    private val GT = listOf(
        "Расписание занятий на вторник",
        "Лекция по математике в 10:30, аудитория 216",
        "Принесите студенческий билет и паспорт",
        "Спасибо за помощь! До встречи.",
        "Контрольная работа №3 (физика)"
    )

    @Test
    fun 俄文印刷体_逐行完全正确() {
        assumeTrue("设备上没有俄文 OCR 模型，跳过", modelsReady())
        assertTrue("模型加载失败", load())
        val r = OcrEngine.recognize(image("ru_printed.png"), OcrScript.AUTO)
        val dump = StringBuilder()
        dump.append("俄文模型识别（AUTO）：").append(System.lineSeparator())
        r.lines.forEach { dump.append("  ").append(it.text).append(System.lineSeparator()) }
        File(ctx.getExternalFilesDir(null), "ru_ocr_dump.txt").writeText(dump.toString())

        assertTrue("行数不对：${r.boxCount}", r.boxCount == GT.size)
        GT.forEachIndexed { i, gt ->
            assertTrue(
                "第 ${i + 1} 行不一致\n  期望: $gt\n  实际: ${r.lines[i].text}",
                r.lines[i].text == gt
            )
        }
    }

    @Test
    fun 俄文真实截图_俄文模型远好于中英模型() {
        assumeTrue("设备上没有俄文 OCR 模型，跳过", modelsReady())
        assertTrue("模型加载失败", load())
        val bmp = image("ru_real.jpg")
        val gt = InstrumentationRegistry.getInstrumentation().context.assets
            .open("ru_real_gt.txt").bufferedReader().use { it.readText() }

        val ch = OcrEngine.recognize(bmp, OcrScript.CH)
        val ru = OcrEngine.recognize(bmp, OcrScript.RU)
        val chCer = cer(gt, ch.fullText)
        val ruCer = cer(gt, ru.fullText)

        val dump = StringBuilder()
        dump.append("真实截图 CER（越小越好，GT ").append(gt.filter { !it.isWhitespace() }.length)
            .append(" 字符）：中英模型=").append("%.4f".format(chCer))
            .append("  俄文模型=").append("%.4f".format(ruCer)).append(System.lineSeparator())
        dump.append("=== 中英模型（现状/错误示范）").append(System.lineSeparator())
            .append(ch.fullText).append(System.lineSeparator())
        dump.append("=== 俄文模型（PP-OCRv5）").append(System.lineSeparator())
            .append(ru.fullText).append(System.lineSeparator())
        File(ctx.getExternalFilesDir(null), "ru_real_dump.txt").writeText(dump.toString())

        // 这是用户抱怨场景的量化回归线：现状模型是"语言级错误"，换 v5 后应降到个位数百分比
        assertTrue("俄文模型 CER 应 < 15%，实际 $ruCer", ruCer < 0.15)
        assertTrue("现状中英模型在俄文上应该明显更差（$chCer vs $ruCer）", chCer > ruCer * 2)
    }

    /** 字符错误率（忽略空白；Levenshtein / GT 长度） */
    private fun cer(gt: String, hyp: String): Double {
        val a = gt.filter { !it.isWhitespace() }
        val b = hyp.filter { !it.isWhitespace() }
        if (a.isEmpty()) return if (b.isEmpty()) 0.0 else 1.0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length].toDouble() / a.length
    }
}

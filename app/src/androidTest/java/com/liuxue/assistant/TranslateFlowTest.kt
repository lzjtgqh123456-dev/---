package com.liuxue.assistant

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.liuxue.assistant.feature.translate.TranslateViewModel
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 第一批验收的端到端设备测试：**相册图片 → PP-OCR 识别 → 复用现有 GGUF 翻译**。
 *
 * 走的是 App 自己的代码路径（TranslateViewModel.decodeForOcr → OcrEngine → LlamaBridge），
 * 只有"系统相册选图"这一步用 Uri.fromFile 代替（那一步是 AndroidX PickVisualMedia，
 * 已在真机上人工确认过：点「从相册选图」会打开系统相册并把 Uri 回传）。
 *
 * 前置（设备上已就绪即可，测试会自己 skip）：
 *   1. OCR 模型在 /sdcard/Android/data/com.liuxue.assistant.debug/files/ocr/
 *   2. 翻译模型 HY-MT1.5-1.8B-Q4_K_M.gguf 在同目录的 models/
 */
@RunWith(AndroidJUnit4::class)
class TranslateFlowTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun ocrReady(): Boolean = File(ctx.getExternalFilesDir(null), "ocr").let { d ->
        d.isDirectory && listOf("det.onnx", "rec_ch.onnx", "keys_ch.txt")
            .all { File(d, it).let { f -> f.isFile && f.length() > 1000 } }
    }

    private fun ggufReady(): Boolean =
        File(ctx.getExternalFilesDir(null), "models/HY-MT1.5-1.8B-Q4_K_M.gguf").let {
            it.isFile && it.length() > 1_000_000_000
        }

    private fun copyAsset(name: String): File {
        val f = File(ctx.cacheDir, "flow_" + name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            f.outputStream().use { out -> input.copyTo(out) }
        }
        return f
    }

    @Test
    fun 图片_识别并翻译_端到端() {
        assumeTrue("设备上没有 OCR 模型，跳过", ocrReady())
        assumeTrue("设备上没有 1.06GB 翻译模型，跳过", ggufReady())

        // 保持进程在前台：vivo 会冻结后台应用，冻结后测试线程与协程都会卡住（踩过）
        val intent = android.content.Intent(ctx, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        InstrumentationRegistry.getInstrumentation().targetContext.startActivity(intent)

        val img = copyAsset("rel_b.png")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = TranslateViewModel(app)
        vm.setTo("ru")                       // 验收：翻成俄语
        vm.setImage(Uri.fromFile(img))
        vm.recognizeAndTranslate()

        // 识别 + 分段翻译，最多等 6 分钟（首次加载 1GB 模型要几秒~几十秒）
        val deadline = System.currentTimeMillis() + 6 * 60 * 1000
        var tick = 0
        while (System.currentTimeMillis() < deadline) {
            val s = vm.ui.value
            // 心跳：把中间状态写盘，卡住时也能看出走到哪一步
            File(ctx.getExternalFilesDir(null), "flow_heartbeat.txt").writeText(
                "t=" + (tick++) + " ocrBusy=" + s.ocrBusy + " translating=" + s.translating +
                    " inputLen=" + s.input.length + " outputLen=" + s.output.length +
                    " info=" + s.ocrInfo + " msg=" + s.message
            )
            if (!s.ocrBusy && !s.translating && s.output.isNotBlank()) break
            Thread.sleep(2000)
        }
        val s = vm.ui.value
        val dump = StringBuilder()
        dump.append("OCR 原文(").append(s.input.length).append(" 字符):").append(s.input)
            .append(System.lineSeparator())
        dump.append("译文(").append(s.output.length).append(" 字符):").append(s.output)
            .append(System.lineSeparator())
        dump.append("ocrInfo=").append(s.ocrInfo).append(" message=").append(s.message)
            .append(System.lineSeparator())
        runCatching {
            File(ctx.getExternalFilesDir(null), "flow_test_dump.txt").writeText(dump.toString())
        }
        println("FLOW_DUMP_BEGIN")
        print(dump)
        println("FLOW_DUMP_END")

        assertTrue("图片没能识别出中英文（原文为空）", s.input.includes("Machine Learning"))
        assertTrue("OCR 信息缺失", s.ocrInfo != null)
        assertTrue("译文为空（GGUF 翻译没跑起来）：" + s.message, s.output.isNotBlank())
        assertTrue(
            "译文里没有西里尔字母，不像俄语：\n" + s.output,
            s.output.any { it in '\u0400'..'\u04FF' }
        )
    }

    /**
     * 用户抱怨的场景：**俄文图片 → 识别 → 翻译**。
     * 走的是 App 的 AUTO 路由（中英/俄文自动判定），断言识别出的原文确实是俄文而不是拉丁同形字。
     */
    @Test
    fun 俄文图片_识别并翻译成中文() {
        assumeTrue("设备上没有 OCR 模型，跳过", ocrReady())
        assumeTrue("设备上没有 1.06GB 翻译模型，跳过", ggufReady())

        val img = copyAsset("ru_printed.png")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = TranslateViewModel(app)
        vm.setTo("zh")
        vm.setImage(Uri.fromFile(img))
        vm.recognizeAndTranslate()

        val deadline = System.currentTimeMillis() + 6 * 60 * 1000
        while (System.currentTimeMillis() < deadline) {
            val s = vm.ui.value
            if (!s.ocrBusy && !s.translating && s.output.isNotBlank()) break
            Thread.sleep(1000)
        }
        val s = vm.ui.value
        val dump = StringBuilder()
        dump.append("OCR 原文(").append(s.input.length).append(" 字符):").append(s.input)
            .append(System.lineSeparator())
        dump.append("译文(").append(s.output.length).append(" 字符):").append(s.output)
            .append(System.lineSeparator())
        dump.append("ocrInfo=").append(s.ocrInfo).append(" message=").append(s.message)
        File(ctx.getExternalFilesDir(null), "flow_ru_dump.txt").writeText(dump.toString())

        val cyr = s.input.count { it in 'Ѐ'..'ӿ' }
        assertTrue("识别结果里几乎没有西里尔字母（$cyr 个）：" + s.input, cyr > 60)
        assertTrue("识别结果里混进了太多拉丁同形字：" + s.input, s.input.count { it in 'A'..'Z' || it in 'a'..'z' } < 20)
        assertTrue("俄文识别结果不对：" + s.input, s.input.contains("Расписание занятий"))
        assertTrue("译文为空（GGUF 翻译没跑起来）：" + s.message, s.output.isNotBlank())
    }
}

private fun String.includes(other: String) = this.contains(other)

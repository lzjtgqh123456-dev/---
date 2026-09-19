package com.liuxue.assistant

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.liuxue.assistant.feature.net.NetViewModel
import com.liuxue.assistant.feature.net.PendingAttachment
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * AI 聊天附件：解析 + 端到端（附件真的会影响回答）。
 *
 * 前置：设备上已配置好 AI（BaseURL/Key/模型），否则端到端用例自动 skip。
 */
@RunWith(AndroidJUnit4::class)
class AiAttachmentTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun pending(f: File, mime: String) =
        PendingAttachment(Uri.fromFile(f), f.name, mime, f.length())

    /** 文本附件应被读成正文；未知类型应给一句 note；图片应压成 base64 */
    @Test
    fun 附件解析_文本与图片() {
        val vm = NetViewModel(app)

        val txt = File(ctx.cacheDir, "att_readme.txt").apply {
            writeText("项目代号：北极星-7788\n第二行内容", Charsets.UTF_8)
        }
        val txtRes = vm.resolveAttachments(listOf(pending(txt, "text/plain")))
        assertTrue("文本附件没解析出正文", txtRes[0].text.contains("北极星-7788"))

        val zip = File(ctx.cacheDir, "att_bundle.zip").apply { writeBytes(ByteArray(64)) }
        val zipRes = vm.resolveAttachments(listOf(pending(zip, "application/zip")))
        assertTrue("未知类型应给 note", zipRes[0].note.isNotBlank())
        assertTrue("未知类型不该带正文", zipRes[0].text.isBlank())

        val png = File(ctx.cacheDir, "att_img.png")
        val bmp = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        FileOutputStream(png).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val imgRes = vm.resolveAttachments(listOf(pending(png, "image/png")))
        assertTrue("图片附件没转成 base64", imgRes[0].base64.length > 100)
        assertTrue("图片 mime 应为 jpeg", imgRes[0].mime == "image/jpeg")
    }

    /** PDF / PPT 解析（附件里塞了只有文件里才有的关键字） */
    @Test
    fun 附件解析_PDF与PPT() {
        val vm = NetViewModel(app)
        val pdf = File(ctx.cacheDir, "att_test.pdf")
        InstrumentationRegistry.getInstrumentation().context.assets.open("att_test.pdf").use { i ->
            pdf.outputStream().use { o -> i.copyTo(o) }
        }
        val pdfRes = vm.resolveAttachments(listOf(pending(pdf, "application/pdf")))
        assertTrue("PDF 没抽出文字：note=" + pdfRes[0].note + " text=" + pdfRes[0].text,
            pdfRes[0].text.contains("PDF-CODE-9911"))

        val ppt = File(ctx.cacheDir, "att_test.pptx")
        InstrumentationRegistry.getInstrumentation().context.assets.open("att_test.pptx").use { i ->
            ppt.outputStream().use { o -> i.copyTo(o) }
        }
        val pptRes = vm.resolveAttachments(listOf(pending(ppt, "application/vnd.openxmlformats-officedocument.presentationml.presentation")))
        assertTrue("PPT 没抽出文字：" + pptRes[0].text, pptRes[0].text.contains("PPT-CODE-2233"))
        assertTrue("PPT 只解析了第一页：" + pptRes[0].text, pptRes[0].text.contains("second slide text"))
    }

    /**
     * 端到端：把一段只有附件里才有的信息发给 AI，回答里必须出现该信息。
     * 这能证明"附件确实进了请求"，而不是只挂在界面上。
     */
    @Test
    fun 文本附件_真的影响AI回答() {
        val vm = NetViewModel(app)
        assumeTrue("设备上没配置 AI，跳过", vm.config().isAiConfigured() && vm.config().aiEnabled)

        val txt = File(ctx.cacheDir, "att_secret.txt").apply {
            writeText(
                "这是一份测试附件。\n项目代号：北极星-7788。\n" +
                    "请只根据附件内容回答：项目代号是什么？",
                Charsets.UTF_8
            )
        }
        vm.addAttachments(listOf(Uri.fromFile(txt)))
        // addAttachments 是协程，等它落到状态里
        var waited = 0
        while (vm.ui.value.attachments.isEmpty() && waited < 5000) {
            Thread.sleep(200); waited += 200
        }
        assertTrue("附件没加进状态", vm.ui.value.attachments.isNotEmpty())

        vm.setQuery("项目代号是什么？只回复代号。")
        vm.quickAsk()

        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            val s = vm.ui.value
            if (!s.aiBusy && s.aiAnswer.isNotBlank()) break
            if (!s.aiBusy && s.message != null) break
            Thread.sleep(500)
        }
        val s = vm.ui.value
        File(ctx.getExternalFilesDir(null), "ai_att_dump.txt").writeText(
            "附件=" + s.attachments.joinToString { it.name } + "\n" +
                "提问=" + s.query + "\n回答=" + s.aiAnswer + "\nmessage=" + s.message
        )
        assertTrue("AI 没回答：" + s.message, s.aiAnswer.isNotBlank())
        assertTrue(
            "回答里没有附件里的代号，说明附件没进请求：\n" + s.aiAnswer,
            s.aiAnswer.contains("7788") || s.aiAnswer.contains("北极星")
        )
    }
}

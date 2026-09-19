package com.liuxue.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.liuxue.assistant.data.db.AppDatabase
import com.liuxue.assistant.data.db.VaultFile
import com.liuxue.assistant.data.repo.VaultRepository
import com.liuxue.assistant.data.security.VaultStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * 图片放大功能的数据层验证 + 真机可视化种子。
 *
 * 图片是 AES 加密落盘的，查看器拿到的是解密后的字节。
 * 这里验证「加密保存 → 解密读回 → BitmapFactory 解码」链路，并写入一条带封面的测试记录，
 * 便于随后在真机上点开、双指缩放查看。
 */
@RunWith(AndroidJUnit4::class)
class ImageViewerSeedTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var store: VaultStore
    private lateinit var repo: VaultRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.get(context)
        store = VaultStore(context)
        repo = VaultRepository(context, db)
    }

    private fun makeJpeg(w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val a = 0xFF3366CC.toInt()
        val b = 0xFFEEEEEE.toInt()
        for (x in 0 until w) {
            for (y in 0 until h) {
                bmp.setPixel(x, y, if ((x / 40 + y / 40) % 2 == 0) a else b)
            }
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }

    @Test
    fun 加密图片解密后能解码回原尺寸() = runBlocking {
        val jpeg = makeJpeg(800, 600)
        val path = store.saveBytes(jpeg, "jpg")
        assertNotNull("加密保存应返回路径", path)
        val back = store.readBytes(path)
        assertNotNull("应能解密读回", back)
        val bmp = BitmapFactory.decodeByteArray(back, 0, back!!.size)
        assertNotNull("解密后的字节应能解码成 Bitmap", bmp)
        assertEquals(800, bmp!!.width)
        assertEquals(600, bmp.height)
        println("解密→解码 OK: " + bmp.width + "x" + bmp.height + "，密文 " + jpeg.size + " 字节")
        store.delete(path)
        Unit
    }

    @Test
    fun 种子一条带封面的记录供真机点击放大() = runBlocking {
        // 清掉上次的测试记录
        db.vaultDao().all().filter { it.title == "图片放大测试" }.forEach {
            it.coverPath?.let { p -> store.delete(p) }
            db.vaultDao().delete(it)
        }
        val jpeg = makeJpeg(900, 1200)
        val path = store.saveBytes(jpeg, "jpg")
        val id = db.vaultDao().insert(
            VaultFile(
                title = "图片放大测试",
                category = "visa",
                note = "点封面可全屏，双指缩放",
                coverPath = path
            )
        )
        val item = db.vaultDao().findById(id)
        assertNotNull("记录应写入", item)
        val bytes = repo.coverBytes(item!!)
        assertNotNull("coverBytes 应能解密", bytes)
        println("已写入测试记录 id=" + id + "，封面 " + path)
    }
}

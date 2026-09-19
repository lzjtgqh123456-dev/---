package com.liuxue.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.liuxue.assistant.data.db.AppDatabase
import com.liuxue.assistant.data.repo.VaultRepository
import com.liuxue.assistant.feature.transfer.BackupManager
import com.liuxue.assistant.feature.transfer.ExportOptions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 导入导出的真机测试：导出 -> 解析 -> 再导入，验证往返一致且不产生重复。
 */
@RunWith(AndroidJUnit4::class)
class TransferTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var manager: BackupManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.get(context)
        manager = BackupManager(
            context = context,
            db = db,
            vaultRepo = VaultRepository(context, db),
            appVersion = "0.2.0-test"
        )
    }

    @Test
    fun 导出内容包含必要结构() = runBlocking {
        // 先放一条短语，保证有内容
        manager.importFromJson(
            """{"meta":{"format":"liuxue-assistant-backup","version":1},
                "phrases":[{"textRu":"Здравствуйте","textZh":"您好","categoryName":"问候"}]}"""
        )
        val json = manager.exportToJson(ExportOptions())
        println("导出长度=" + json.length)
        assertTrue("应包含 meta", json.contains("liuxue-assistant-backup"))
        assertTrue("应包含 phrases", json.contains("phrases"))
        assertTrue("应包含 wordbookItems", json.contains("wordbookItems"))
        assertTrue("应导出刚加的短语", json.contains("Здравствуйте"))
    }

    @Test
    fun 导入后重复导入不会产生重复() = runBlocking {
        val payload = """{"meta":{"format":"liuxue-assistant-backup","version":1},
            "phrases":[{"textRu":"Спасибо","textZh":"谢谢","categoryName":"礼貌用语"}]}"""
        val first = manager.importFromJson(payload)
        val second = manager.importFromJson(payload)
        println("第一次: ${first.summary()} / 第二次: ${second.summary()}")
        assertTrue("第一次应新增", first.phrasesAdded >= 1)
        assertEquals("第二次应全部跳过", 0, second.phrasesAdded)
        assertTrue("第二次应有跳过计数", second.phrasesSkipped >= 1)
    }

    @Test
    fun 拒绝非本应用备份文件() = runBlocking {
        val r = manager.importFromJson("""{"hello":"world"}""")
        assertNotNull("应返回错误", r.error)
        println("错误提示: ${r.error}")
    }

    @Test
    fun 拒绝非法JSON() = runBlocking {
        val r = manager.importFromJson("{ this is not json")
        assertNotNull("应返回错误", r.error)
    }

    @Test
    fun 证件往返导入含到期信息() = runBlocking {
        val payload = """{"meta":{"format":"liuxue-assistant-backup","version":1},
            "vaultFiles":[{"title":"测试签证往返","category":"visa","note":"备注内容",
            "issueDate":1700000000000,"expireDate":1800000000000,"remindDaysBefore":60}]}"""
        val r = manager.importFromJson(payload)
        println("导入结果: ${r.summary()}")
        assertTrue("应导入 1 个证件", r.vaultAdded >= 1)
        val found = db.vaultDao().all().firstOrNull { it.title == "测试签证往返" }
        assertNotNull("证件应存在", found)
        assertEquals(1800000000000L, found!!.expireDate)
        assertEquals(60, found.remindDaysBefore)
    }
}

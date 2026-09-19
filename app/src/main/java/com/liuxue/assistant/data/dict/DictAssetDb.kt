package com.liuxue.assistant.data.dict

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * 词典库管理。
 *
 * 设计要点：
 * - 词典是**只读数据包**，不走 Room（Room 对预置库 schema 校验过严），直接原生 SQLite 打开；
 * - 一个库文件可以服务多个查询入口：`dict_ru.db` 同时提供「俄汉」与「俄英」；
 * - 词典**可以不随 APK 分发**（assetName = null），改由「导入文件 / 下载 / 导出」管理，
 *   装完可删除释放空间；内置机型（assetName != null）则在首次使用时释放到 databases/。
 */
object DictAssetDb {

    /** 一个查询入口（用户看到的「一本词典」） */
    data class Pack(
        val id: String,
        /** 随包分发的资源名；null = 不内置，需要用户导入/下载 */
        val assetName: String?,
        val dbName: String,
        val label: String,
        val isEnglish: Boolean = false,
        /** 展示与反查用哪一列释义：glossZh / glossEn */
        val glossColumn: String = "glossZh"
    ) {
        val forwardLabel: String get() = when {
            isEnglish -> "英 → 汉"
            glossColumn == "glossEn" -> "俄 → 英"
            else -> "俄 → 汉"
        }
        val reverseLabel: String get() = when {
            isEnglish -> "汉 → 英"
            glossColumn == "glossEn" -> "英 → 俄"
            else -> "汉 → 俄"
        }
        val forwardHint: String get() = when {
            isEnglish -> "输入英文（支持复数/过去式等变形）"
            glossColumn == "glossEn" -> "输入俄语（显示英文释义）"
            else -> "输入俄语（可输入任意变格形式）"
        }
        val reverseHint: String get() = when {
            isEnglish -> "输入中文查英文"
            glossColumn == "glossEn" -> "输入英文查俄语"
            else -> "输入中文查俄语"
        }
    }

    /** 需要「安装」的一个词典文件（可服务多个 Pack） */
    data class PackFile(
        val key: String,
        val label: String,
        val dbName: String,
        val assetName: String?,
        val packs: List<Pack>
    )

    val RU_ZH = Pack("ru_zh", null, "dict_ru_v1.db", "俄汉", glossColumn = "glossZh")
    val RU_EN = Pack("ru_en", null, "dict_ru_v1.db", "俄英", glossColumn = "glossEn")
    val EN_ZH = Pack("en_zh", "dict_en.db", "dict_en_v1.db", "英汉", isEnglish = true)

    /** 三个独立入口：俄汉 / 俄英 / 英汉（不再混在一个包里） */
    val ALL = listOf(RU_ZH, RU_EN, EN_ZH)

    val FILE_RU = PackFile("ru", "俄语词典（俄汉 + 俄英）", "dict_ru_v1.db", null, listOf(RU_ZH, RU_EN))
    val FILE_EN = PackFile("en", "英汉词典", "dict_en_v1.db", "dict_en.db", listOf(EN_ZH))

    val FILES = listOf(FILE_RU, FILE_EN)

    private val dbs = HashMap<String, SQLiteDatabase>()

    fun fileFor(context: Context, pack: Pack): File = context.getDatabasePath(pack.dbName)
    fun fileFor(context: Context, f: PackFile): File = context.getDatabasePath(f.dbName)

    fun isReady(context: Context, pack: Pack): Boolean = fileFor(context, pack).exists()
    fun isReady(context: Context, f: PackFile): Boolean = fileFor(context, f).exists()

    fun sizeOf(context: Context, f: PackFile): Long =
        fileFor(context, f).takeIf { it.exists() }?.length() ?: 0L

    private fun releaseFromAssets(context: Context, assetName: String, target: File) {
        target.parentFile?.mkdirs()
        context.assets.open(assetName).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /**
     * 打开词典库（首次会把内置资源释放到 databases/）。
     * 未安装且没有内置资源时抛 IllegalStateException——调用方先查 isReady()。
     */
    fun open(context: Context, pack: Pack = RU_ZH): SQLiteDatabase {
        synchronized(dbs) { dbs[pack.id]?.let { if (it.isOpen) return it } }
        val target = fileFor(context, pack)
        val asset = pack.assetName
        if (asset != null) {
            val assetSize = runCatching {
                context.assets.openFd(asset).use { it.length }
            }.getOrDefault(-1L)
            val needCopy = !target.exists() || (assetSize > 0 && target.length() != assetSize)
            if (needCopy) releaseFromAssets(context, asset, target)
        }
        check(target.exists()) { "词典未安装：" + pack.label }
        val opened = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY)
        synchronized(dbs) { dbs[pack.id] = opened }
        return opened
    }

    /** 删除/替换文件前关闭句柄 */
    fun close(pack: Pack) {
        synchronized(dbs) { dbs.remove(pack.id)?.let { runCatching { it.close() } } }
    }

    fun closeFile(f: PackFile) = f.packs.forEach { close(it) }

    /** 删除词典文件，返回释放的字节数 */
    fun deleteFile(context: Context, f: PackFile): Long {
        closeFile(f)
        val file = fileFor(context, f)
        val size = if (file.exists()) file.length() else 0L
        runCatching { file.delete() }
        return size
    }
}

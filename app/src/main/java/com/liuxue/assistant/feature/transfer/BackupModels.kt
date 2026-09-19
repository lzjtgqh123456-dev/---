package com.liuxue.assistant.feature.transfer

import org.json.JSONArray
import org.json.JSONObject

/**
 * 备份/分享文件格式（.lex，实为 JSON 文本）。
 *
 * 设计原则：
 * - 版本号显式声明，便于将来兼容
 * - 每类数据独立数组，未知字段忽略、缺失字段容错
 * - 导入时按"合并"语义：重复条目跳过，不覆盖用户已有数据
 */
object BackupFormat {
    const val EXTENSION = "lex"
    const val MIME = "application/json"
    const val CURRENT_VERSION = 1

    fun metaJson(appVersion: String): JSONObject = JSONObject().apply {
        put("format", "liuxue-assistant-backup")
        put("version", CURRENT_VERSION)
        put("appVersion", appVersion)
        put("exportedAt", System.currentTimeMillis())
    }

    /** 顶层结构 */
    fun bundle(appVersion: String): JSONObject = JSONObject().apply {
        put("meta", metaJson(appVersion))
        put("wordbookCategories", JSONArray())
        put("wordbookItems", JSONArray())
        put("phraseCategories", JSONArray())
        put("phrases", JSONArray())
        put("vaultFiles", JSONArray())
        // ---- 学业管理 ----
        put("studySemester", JSONObject.NULL)
        put("studyCourses", JSONArray())
        put("studySchedules", JSONArray())
        put("studyHomework", JSONArray())
        put("studyExams", JSONArray())
    }

    fun wordbookItem(
        entryId: Long,
        lemma: String,
        glossZh: String?,
        categoryName: String?,
        mastery: Int,
        note: String
    ): JSONObject = JSONObject().apply {
        put("entryId", entryId)
        put("lemma", lemma)
        put("glossZh", glossZh ?: "")
        put("categoryName", categoryName ?: "")
        put("mastery", mastery)
        put("note", note)
    }

    fun phrase(
        textRu: String,
        textZh: String,
        note: String,
        categoryName: String?,
        starred: Boolean,
        createdAt: Long
    ): JSONObject = JSONObject().apply {
        put("textRu", textRu)
        put("textZh", textZh)
        put("note", note)
        put("categoryName", categoryName ?: "")
        put("starred", starred)
        put("createdAt", createdAt)
    }

    fun vaultFile(
        title: String,
        category: String,
        note: String,
        issueDate: Long?,
        expireDate: Long?,
        remindDaysBefore: Int,
        coverBase64: String?
    ): JSONObject = JSONObject().apply {
        put("title", title)
        put("category", category)
        put("note", note)
        put("issueDate", issueDate ?: JSONObject.NULL)
        put("expireDate", expireDate ?: JSONObject.NULL)
        put("remindDaysBefore", remindDaysBefore)
        put("coverBase64", coverBase64 ?: JSONObject.NULL)
    }
}

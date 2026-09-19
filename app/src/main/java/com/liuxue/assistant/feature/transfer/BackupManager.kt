package com.liuxue.assistant.feature.transfer

import android.content.Context
import android.util.Base64
import com.liuxue.assistant.data.db.AppDatabase
import com.liuxue.assistant.data.db.VaultFile
import com.liuxue.assistant.data.dict.DictAssetDb
import com.liuxue.assistant.data.dict.Phrase
import com.liuxue.assistant.data.dict.PhraseCategory
import com.liuxue.assistant.data.dict.WordbookCategory
import com.liuxue.assistant.data.dict.WordbookItem
import com.liuxue.assistant.data.repo.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ExportOptions(
    val wordbook: Boolean = true,
    val phrases: Boolean = true,
    val vault: Boolean = true,
    /** 是否连证件图片一起导出（体积会大幅增加） */
    val vaultImages: Boolean = false
)

data class ImportResult(
    val wordbookAdded: Int = 0,
    val wordbookSkipped: Int = 0,
    val phrasesAdded: Int = 0,
    val phrasesSkipped: Int = 0,
    val vaultAdded: Int = 0,
    val vaultSkipped: Int = 0,
    val courseAdded: Int = 0,
    val homeworkAdded: Int = 0,
    val examAdded: Int = 0,
    val error: String? = null
) {
    fun summary(): String = buildString {
        append("生词 +").append(wordbookAdded)
        if (wordbookSkipped > 0) append("（跳过 ").append(wordbookSkipped).append("）")
        append(" · 短语 +").append(phrasesAdded)
        if (phrasesSkipped > 0) append("（跳过 ").append(phrasesSkipped).append("）")
        if (vaultAdded > 0 || vaultSkipped > 0) {
            append(" · 证件 +").append(vaultAdded)
            if (vaultSkipped > 0) append("（跳过 ").append(vaultSkipped).append("）")
        }
        if (courseAdded > 0) append(" · 课程 +").append(courseAdded)
        if (homeworkAdded > 0) append(" · 作业 +").append(homeworkAdded)
        if (examAdded > 0) append(" · 考试 +").append(examAdded)
    }
}

/**
 * 数据导入导出门面。
 *
 * 导出：生词本 / 短语集 / 证件元数据 -> 一个 `.lex`（JSON 文本）文件，可分享给他人。
 * 导入：读取 `.lex` 并**合并**进本地，重复项跳过，绝不覆盖已有数据。
 */
class BackupManager(
    private val context: Context,
    private val db: AppDatabase,
    private val vaultRepo: VaultRepository,
    private val appVersion: String
) {

    // ==================== 导出 ====================

    suspend fun exportToJson(options: ExportOptions = ExportOptions()): String =
        withContext(Dispatchers.IO) {
            val bundle = BackupFormat.bundle(appVersion)
            val dict = DictAssetDb.open(context)

            if (options.wordbook) {
                val catArr = bundle.getJSONArray("wordbookCategories")
                db.wordbookDao().allCategories().forEach { c ->
                    catArr.put(JSONObject().apply {
                        put("name", c.name)
                        put("colorHex", c.colorHex)
                        put("sortOrder", c.sortOrder)
                    })
                }

                val itemArr = bundle.getJSONArray("wordbookItems")
                db.wordbookDao().allItems().forEach { item ->
                    val (lemma, gloss) = dict.rawQuery(
                        "SELECT lemma, glossZh FROM dict_entry WHERE id = ?",
                        arrayOf(item.entryId.toString())
                    ).use { c ->
                        if (c.moveToFirst()) (c.getString(0) ?: "") to c.getString(1) else "" to null
                    }
                    val catName = db.wordbookDao().allCategories()
                        .firstOrNull { it.id == item.categoryId }?.name
                    itemArr.put(
                        BackupFormat.wordbookItem(
                            entryId = item.entryId,
                            lemma = lemma,
                            glossZh = gloss,
                            categoryName = catName,
                            mastery = item.mastery,
                            note = item.note
                        )
                    )
                }
            }

            if (options.phrases) {
                val catArr = bundle.getJSONArray("phraseCategories")
                val phraseCats = db.phraseDao().allCategories()
                phraseCats.forEach { c ->
                    catArr.put(JSONObject().apply {
                        put("name", c.name)
                        put("sortOrder", c.sortOrder)
                    })
                }
                val arr = bundle.getJSONArray("phrases")
                db.phraseDao().all().forEach { p ->
                    arr.put(
                        BackupFormat.phrase(
                            textRu = p.textRu,
                            textZh = p.textZh,
                            note = p.note,
                            categoryName = phraseCats.firstOrNull { it.id == p.categoryId }?.name,
                            starred = p.starred,
                            createdAt = p.createdAt
                        )
                    )
                }
            }

            if (options.vault) {
                val arr = bundle.getJSONArray("vaultFiles")
                db.vaultDao().all().forEach { f ->
                    val b64 = if (options.vaultImages) {
                        f.coverPath?.let { path ->
                            vaultRepo.coverBytesByPath(path)?.let {
                                Base64.encodeToString(it, Base64.NO_WRAP)
                            }
                        }
                    } else null
                    arr.put(
                        BackupFormat.vaultFile(
                            title = f.title,
                            category = f.category,
                            note = f.note,
                            issueDate = f.issueDate,
                            expireDate = f.expireDate,
                            remindDaysBefore = f.remindDaysBefore,
                            coverBase64 = b64
                        )
                    )
                }
            }
            fillStudy(bundle)
            bundle.toString(2)
        }

    /** 学业数据是否纳入导出 */
    private suspend fun fillStudy(bundle: JSONObject) {
        val dao = db.studyDao()
        val sem = dao.activeSemester()
        if (sem != null) {
            bundle.put("studySemester", JSONObject().apply {
                put("name", sem.name)
                put("startDate", sem.startDate)
                put("totalWeeks", sem.totalWeeks)
            })
        }
        val courses = dao.allCourses()
        val nameById = courses.associate { it.id to it.name }
        val courseArr = bundle.getJSONArray("studyCourses")
        courses.forEach { c ->
            courseArr.put(JSONObject().apply {
                put("name", c.name)
                put("className", c.className)
                put("teacher", c.teacher)
                put("classType", c.classType)
                put("credits", c.credits)
                put("content", c.content)
            })
        }
        val schArr = bundle.getJSONArray("studySchedules")
        dao.allSchedules().forEach { s ->
            schArr.put(JSONObject().apply {
                put("courseName", nameById[s.courseId] ?: "")
                put("weekday", s.weekday)
                put("startTime", s.startTime)
                put("endTime", s.endTime)
                put("location", s.location)
                put("weekFrom", s.weekFrom)
                put("weekTo", s.weekTo)
                put("parity", s.parity)
            })
        }
        val hwArr = bundle.getJSONArray("studyHomework")
        dao.allHomework().forEach { h ->
            hwArr.put(JSONObject().apply {
                put("courseName", nameById[h.courseId] ?: "")
                put("title", h.title)
                put("content", h.content)
                put("dueDate", h.dueDate ?: JSONObject.NULL)
                put("status", h.status)
                put("remindDaysBefore", h.remindDaysBefore)
            })
        }
        val exArr = bundle.getJSONArray("studyExams")
        dao.allExams().forEach { e ->
            exArr.put(JSONObject().apply {
                put("courseName", nameById[e.courseId] ?: "")
                put("name", e.name)
                put("examDate", e.examDate)
                put("durationMinutes", e.durationMinutes)
                put("location", e.location)
                put("content", e.content)
            })
        }
    }

    // ==================== 导入 ====================

    suspend fun importFromJson(json: String): ImportResult = withContext(Dispatchers.IO) {
        val root = runCatching { JSONObject(json) }.getOrElse {
            return@withContext ImportResult(error = "文件格式不正确，不是有效的备份文件")
        }
        val meta = root.optJSONObject("meta")
        if (meta?.optString("format") != "liuxue-assistant-backup") {
            return@withContext ImportResult(error = "这不是「留学助手-乐」的备份文件")
        }

        var wbAdd = 0; var wbSkip = 0
        var courseAdd = 0; var hwAdd = 0; var exAdd = 0
        var phAdd = 0; var phSkip = 0
        var vAdd = 0; var vSkip = 0

        try {
            // ---- 生词本 ----
            val wbCats = root.optJSONArray("wordbookCategories") ?: JSONArray()
            val existingWbCats = db.wordbookDao().allCategories().associateBy { it.name }
            val wbCatIdByName = existingWbCats.mapValues { it.value.id }.toMutableMap()
            for (i in 0 until wbCats.length()) {
                val name = wbCats.getJSONObject(i).optString("name").trim()
                if (name.isEmpty() || wbCatIdByName.containsKey(name)) continue
                val id = db.wordbookDao().addCategory(WordbookCategory(name = name))
                wbCatIdByName[name] = id
            }

            val wbItems = root.optJSONArray("wordbookItems") ?: JSONArray()
            for (i in 0 until wbItems.length()) {
                val o = wbItems.getJSONObject(i)
                val entryId = o.optLong("entryId", -1L)
                if (entryId <= 0L) { wbSkip++; continue }
                if (db.wordbookDao().find(entryId) != null) { wbSkip++; continue }
                db.wordbookDao().upsert(
                    WordbookItem(
                        entryId = entryId,
                        categoryId = wbCatIdByName[o.optString("categoryName").trim()] ?: 0L,
                        mastery = o.optInt("mastery", 0),
                        note = o.optString("note")
                    )
                )
                wbAdd++
            }

            // ---- 短语集 ----
            val phCats = root.optJSONArray("phraseCategories") ?: JSONArray()
            val phCatIdByName = db.phraseDao().allCategories()
                .associate { it.name to it.id }.toMutableMap()
            for (i in 0 until phCats.length()) {
                val name = phCats.getJSONObject(i).optString("name").trim()
                if (name.isEmpty() || phCatIdByName.containsKey(name)) continue
                phCatIdByName[name] = db.phraseDao().addCategory(PhraseCategory(name = name))
            }

            val phrases = root.optJSONArray("phrases") ?: JSONArray()
            for (i in 0 until phrases.length()) {
                val o = phrases.getJSONObject(i)
                val ru = o.optString("textRu").trim()
                val zh = o.optString("textZh").trim()
                if (ru.isEmpty() && zh.isEmpty()) { phSkip++; continue }
                val catId = phCatIdByName[o.optString("categoryName").trim()] ?: 0L
                if (db.phraseDao().findDuplicate(catId, ru) != null) { phSkip++; continue }
                db.phraseDao().insert(
                    Phrase(
                        textRu = ru,
                        textZh = zh,
                        note = o.optString("note"),
                        categoryId = catId,
                        starred = o.optBoolean("starred", false),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis())
                    )
                )
                phAdd++
            }

            // ---- 证件（元数据，可选图片）----
            val vaults = root.optJSONArray("vaultFiles") ?: JSONArray()
            for (i in 0 until vaults.length()) {
                val o = vaults.getJSONObject(i)
                val title = o.optString("title").trim()
                val category = o.optString("category", "other")
                if (title.isEmpty()) { vSkip++; continue }
                if (db.vaultDao().findDuplicate(title, category) != null) { vSkip++; continue }
                val b64 = o.optString("coverBase64").takeIf { it.isNotBlank() }
                vaultRepo.create(
                    title = title,
                    category = category,
                    note = o.optString("note"),
                    issueDate = if (o.isNull("issueDate")) null else o.optLong("issueDate"),
                    expireDate = if (o.isNull("expireDate")) null else o.optLong("expireDate"),
                    remindDaysBefore = o.optInt("remindDaysBefore", 30),
                    coverBytes = b64?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
                )
                vAdd++
            }
            // ---- 学业：学期 / 课程 / 课表 / 作业 / 考试 ----
            val sdao = db.studyDao()
            val semObj = root.optJSONObject("studySemester")
            if (semObj != null && sdao.activeSemester() == null) {
                sdao.upsertSemester(
                    com.liuxue.assistant.data.study.Semester(
                        name = semObj.optString("name", "导入的学期"),
                        startDate = semObj.optLong("startDate", System.currentTimeMillis()),
                        totalWeeks = semObj.optInt("totalWeeks", 18),
                        isActive = true
                    )
                )
            }

            // 课程：按名称去重
            val existingCourses = sdao.allCourses().associateBy { it.name }.toMutableMap()
            val cArr = root.optJSONArray("studyCourses") ?: JSONArray()
            for (i in 0 until cArr.length()) {
                val o = cArr.getJSONObject(i)
                val cname = o.optString("name").trim()
                if (cname.isEmpty() || existingCourses.containsKey(cname)) continue
                val id = sdao.insertCourse(
                    com.liuxue.assistant.data.study.Course(
                        name = cname,
                        className = o.optString("className"),
                        teacher = o.optString("teacher"),
                        classType = o.optString("classType", com.liuxue.assistant.data.study.ClassType.LARGE),
                        credits = o.optDouble("credits", 0.0),
                        content = o.optString("content")
                    )
                )
                existingCourses[cname] = com.liuxue.assistant.data.study.Course(id = id, name = cname)
                courseAdd++
            }

            // 课表：按课程名挂到课程上
            val sArr = root.optJSONArray("studySchedules") ?: JSONArray()
            for (i in 0 until sArr.length()) {
                val o = sArr.getJSONObject(i)
                val cid = existingCourses[o.optString("courseName").trim()]?.id ?: continue
                sdao.insertSchedule(
                    com.liuxue.assistant.data.study.CourseSchedule(
                        courseId = cid,
                        weekday = o.optInt("weekday", 1),
                        startTime = o.optString("startTime", "08:00"),
                        endTime = o.optString("endTime", "09:40"),
                        location = o.optString("location"),
                        weekFrom = o.optInt("weekFrom", 1),
                        weekTo = o.optInt("weekTo", 18),
                        parity = o.optString("parity", com.liuxue.assistant.data.study.CourseSchedule.PARITY_ALL)
                    )
                )
            }

            // 作业：标题+课程去重
            val existingHw = sdao.allHomework().map { it.courseId to it.title }.toSet()
            val hArr = root.optJSONArray("studyHomework") ?: JSONArray()
            for (i in 0 until hArr.length()) {
                val o = hArr.getJSONObject(i)
                val title = o.optString("title").trim()
                if (title.isEmpty()) continue
                val cid = existingCourses[o.optString("courseName").trim()]?.id ?: 0L
                if ((cid to title) in existingHw) continue
                sdao.insertHomework(
                    com.liuxue.assistant.data.study.Homework(
                        courseId = cid,
                        title = title,
                        content = o.optString("content"),
                        dueDate = if (o.isNull("dueDate")) null else o.optLong("dueDate"),
                        status = o.optString("status", com.liuxue.assistant.data.study.Homework.STATUS_TODO),
                        remindDaysBefore = o.optInt("remindDaysBefore", 1)
                    )
                )
                hwAdd++
            }

            // 考试
            val eArr = root.optJSONArray("studyExams") ?: JSONArray()
            for (i in 0 until eArr.length()) {
                val o = eArr.getJSONObject(i)
                val ename = o.optString("name").trim()
                if (ename.isEmpty()) continue
                val cid = existingCourses[o.optString("courseName").trim()]?.id ?: 0L
                sdao.insertExam(
                    com.liuxue.assistant.data.study.Exam(
                        courseId = cid, name = ename,
                        examDate = o.optLong("examDate", System.currentTimeMillis()),
                        durationMinutes = o.optInt("durationMinutes", 120),
                        location = o.optString("location"),
                        content = o.optString("content")
                    )
                )
                exAdd++
            }
        } catch (e: Exception) {
            return@withContext ImportResult(
                wbAdd, wbSkip, phAdd, phSkip, vAdd, vSkip, courseAdd, hwAdd, exAdd,
                error = "导入中断：" + (e.message ?: e::class.java.simpleName)
            )
        }

        ImportResult(wbAdd, wbSkip, phAdd, phSkip, vAdd, vSkip, courseAdd, hwAdd, exAdd)
    }
}

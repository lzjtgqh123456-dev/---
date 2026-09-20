package com.liuxue.assistant.data.study

import android.content.Context
import android.net.Uri
import com.liuxue.assistant.data.db.AppDatabase
import com.liuxue.assistant.data.security.VaultStore
import com.liuxue.assistant.domain.WeekCalc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/** 某天的课程 + 课程信息（UI 直接消费） */
data class LessonItem(
    val schedule: CourseSchedule,
    val course: Course?
)

/**
 * 学业管理门面：课程 / 课表 / 作业 / 考试 / 课件。
 *
 * 课件与笔记复用证件模块的加密存储（VaultStore），保证资料同样是加密落盘的。
 */
class StudyRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val store: VaultStore = VaultStore(context)
) {
    private val dao = db.studyDao()

    // ---------- 学期 ----------

    fun observeSemesters(): Flow<List<Semester>> = dao.observeSemesters()
    suspend fun activeSemester(): Semester? = withContext(Dispatchers.IO) { dao.activeSemester() }
    suspend fun semester(id: Long) = withContext(Dispatchers.IO) { dao.semester(id) }
    suspend fun saveSemester(s: Semester) = withContext(Dispatchers.IO) { dao.upsertSemester(s) }
    suspend fun deleteSemester(s: Semester) = withContext(Dispatchers.IO) { dao.deleteSemester(s) }

    /** 删除一个学期会连带删掉什么（给二次确认弹窗写清楚用） */
    data class SemesterImpact(val courses: Int, val lessons: Int, val materials: Int)

    suspend fun semesterImpact(semester: Semester): SemesterImpact = withContext(Dispatchers.IO) {
        val courses = dao.allCourses().filter { it.semesterId == semester.id }
        var lessons = 0
        var materials = 0
        for (c in courses) {
            lessons += dao.schedulesOf(c.id).size
            materials += dao.materialsOf(c.id).size
        }
        SemesterImpact(courses.size, lessons, materials)
    }

    /**
     * 彻底删除一个学期：课程、课时、作业（含附件）、考试、课件笔记（含加密文件）全部清掉。
     *
     * 为什么连加密文件一起删：课件/笔记的密文存在 files/vault 下，只删数据库行的话
     * 那些文件会永远留在磁盘上没人引用（既占空间，也不符合"删了就是删了"的预期）。
     *
     * 如果删的正好是当前学期，会自动把剩下的最新一个学期设为当前，避免课表突然"没有学期"。
     */
    suspend fun deleteSemesterDeep(semester: Semester) = withContext(Dispatchers.IO) {
        val courses = dao.allCourses().filter { it.semesterId == semester.id }
        for (c in courses) {
            dao.materialsOf(c.id).forEach { m ->
                if (m.path.isNotBlank()) store.delete(m.path)
            }
            dao.homeworkOf(c.id).forEach { h ->
                dao.homeworkAttachments(h.id).forEach { a ->
                    if (a.path.isNotBlank()) store.delete(a.path)
                }
            }
        }
        dao.deleteSchedulesOfSemester(semester.id)
        dao.deleteExamsOfSemester(semester.id)
        dao.deleteHomeworkAttachmentsOfSemester(semester.id)
        dao.deleteHomeworkOfSemester(semester.id)
        dao.deleteMaterialsOfSemester(semester.id)
        dao.deleteCoursesOfSemester(semester.id)
        dao.deleteSemester(semester)

        if (semester.isActive) {
            dao.allSemesters().firstOrNull()?.let { left ->
                if (!left.isActive) dao.upsertSemester(left.copy(isActive = true))
            }
        }
    }

    /** 当前周次（无学期时返回 null） */
    suspend fun currentWeek(): Int? = withContext(Dispatchers.IO) {
        val s = dao.activeSemester() ?: return@withContext null
        WeekCalc.weekOf(s).coerceAtLeast(1)
    }

    // ---------- 课程 ----------

    fun observeCourses(): Flow<List<Course>> = dao.observeCourses()
    suspend fun courses(): List<Course> = withContext(Dispatchers.IO) { dao.allCourses() }
    suspend fun course(id: Long) = withContext(Dispatchers.IO) { dao.course(id) }

    suspend fun saveCourse(c: Course): Long = withContext(Dispatchers.IO) {
        if (c.id == 0L) dao.insertCourse(c) else { dao.updateCourse(c); c.id }
    }

    suspend fun deleteCourse(c: Course) = withContext(Dispatchers.IO) {
        dao.deleteSchedulesOf(c.id)
        dao.deleteCourse(c)
    }

    // ---------- 上课时间 ----------

    fun observeAllSchedules(): Flow<List<CourseSchedule>> = dao.observeAllSchedules()
    fun observeSchedulesOf(courseId: Long): Flow<List<CourseSchedule>> = dao.observeSchedulesOf(courseId)
    suspend fun schedulesOf(courseId: Long): List<CourseSchedule> =
        withContext(Dispatchers.IO) { dao.schedulesOf(courseId) }

    suspend fun saveSchedule(s: CourseSchedule): Long = withContext(Dispatchers.IO) {
        if (s.id == 0L) dao.insertSchedule(s) else { dao.updateSchedule(s); s.id }
    }

    suspend fun deleteSchedule(s: CourseSchedule) = withContext(Dispatchers.IO) { dao.deleteSchedule(s) }

    /** 组装某周某天的课程（带课程信息） */
    suspend fun lessonsOn(week: Int, weekday: Int): List<LessonItem> = withContext(Dispatchers.IO) {
        val sem = dao.activeSemester() ?: return@withContext emptyList()
        val all = dao.allSchedules()
        val courses = dao.allCourses().associateBy { it.id }
        WeekCalc.lessonsOn(all, sem, week, weekday).map { LessonItem(it, courses[it.courseId]) }
    }

    /** 某一周 7 天的全部课程（周视图用），按星期 + 开始时间排序 */
    suspend fun lessonsOfWeek(week: Int): List<LessonItem> = withContext(Dispatchers.IO) {
        val sem = dao.activeSemester() ?: return@withContext emptyList()
        val all = dao.allSchedules()
        val courses = dao.allCourses().associateBy { it.id }
        (1..7).flatMap { wd -> WeekCalc.lessonsOn(all, sem, week, wd) }
            .sortedWith(compareBy({ it.weekday }, { WeekCalc.minutesOf(it.startTime) }))
            .map { LessonItem(it, courses[it.courseId]) }
    }

    /** 今天剩余/已过的课程 */
    suspend fun todayLessons(): List<LessonItem> = withContext(Dispatchers.IO) {
        val sem = dao.activeSemester() ?: return@withContext emptyList()
        val all = dao.allSchedules()
        val courses = dao.allCourses().associateBy { it.id }
        WeekCalc.todayLessons(all, sem).map { LessonItem(it, courses[it.courseId]) }
    }

    // ---------- 作业 ----------

    fun observeHomework(): Flow<List<Homework>> = dao.observeHomework()
    fun observeHomeworkOf(courseId: Long): Flow<List<Homework>> = dao.observeHomeworkOf(courseId)
    suspend fun homework(id: Long) = withContext(Dispatchers.IO) { dao.homework(id) }

    suspend fun saveHomework(h: Homework): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (h.id == 0L) dao.insertHomework(h.copy(createdAt = now, updatedAt = now))
        else { dao.updateHomework(h.copy(updatedAt = now)); h.id }
    }

    suspend fun setHomeworkStatus(id: Long, status: String) =
        withContext(Dispatchers.IO) { dao.setHomeworkStatus(id, status) }

    suspend fun deleteHomework(h: Homework) = withContext(Dispatchers.IO) {
        // 连带删除该作业的附件（含加密文件）
        dao.homeworkAttachments(h.id).forEach { a ->
            store.delete(a.path)
            dao.deleteHomeworkAttachment(a)
        }
        dao.deleteHomework(h)
    }

    suspend fun homeworkOfOnce(courseId: Long): List<Homework> =
        withContext(Dispatchers.IO) { dao.homeworkOf(courseId) }

    /** 未完成作业里，即将到期或已逾期的 */
    suspend fun urgentHomework(days: Int = 7): List<Homework> = withContext(Dispatchers.IO) {
        val after = System.currentTimeMillis() + days.toLong() * WeekCalc.MILLIS_PER_DAY
        dao.openHomework().filter { it.dueDate != null && it.dueDate <= after }
    }

    // ---------- 考试 ----------

    fun observeExams(): Flow<List<Exam>> = dao.observeExams()
    fun observeExamsOf(courseId: Long): Flow<List<Exam>> = dao.observeExamsOf(courseId)
    suspend fun saveExam(e: Exam): Long = withContext(Dispatchers.IO) {
        if (e.id == 0L) dao.insertExam(e) else { dao.updateExam(e); e.id }
    }
    suspend fun deleteExam(e: Exam) = withContext(Dispatchers.IO) { dao.deleteExam(e) }
    suspend fun upcomingExams(days: Int = 60): List<Exam> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dao.allExams().filter { it.examDate >= now }
    }

    // ---------- 课件与笔记 ----------

    fun observeMaterials(): Flow<List<CourseMaterial>> = dao.observeMaterials()
    fun observeMaterialsOf(courseId: Long): Flow<List<CourseMaterial>> = dao.observeMaterialsOf(courseId)

    /** 导入课件/笔记：加密保存到本地 */
    suspend fun addMaterial(
        courseId: Long,
        uri: Uri,
        displayName: String,
        mime: String,
        kind: String
    ): Long = withContext(Dispatchers.IO) {
        val ext = displayName.substringAfterLast('.', "bin").take(8)
        val path = context.contentResolver.openInputStream(uri)?.use { input ->
            store.saveStream(input, ext)
        } ?: error("无法读取所选文件")
        val size = File(context.filesDir, "vault/$path").length()
        dao.insertMaterial(
            CourseMaterial(
                courseId = courseId,
                name = displayName,
                path = path,
                kind = kind,
                mime = mime,
                sizeBytes = size
            )
        )
    }

    suspend fun deleteMaterial(m: CourseMaterial) = withContext(Dispatchers.IO) {
        store.delete(m.path)
        dao.deleteMaterial(m)
    }

    /** 导出为可分享的明文文件 */
    suspend fun materializeForShare(m: CourseMaterial): File? = withContext(Dispatchers.IO) {
        val ext = m.name.substringAfterLast('.', "bin").take(8)
        store.materialize(m.path, ext)?.let { plain ->
            val named = File(plain.parentFile, m.name)
            if (plain.renameTo(named)) named else plain
        }
    }

    suspend fun materialBytes(m: CourseMaterial): ByteArray? = withContext(Dispatchers.IO) {
        store.readBytes(m.path)
    }

    suspend fun materialsOfOnce(courseId: Long): List<CourseMaterial> =
        withContext(Dispatchers.IO) { dao.materialsOf(courseId) }

    // ---------- 作业附件（任意文件，加密保存） ----------

    fun observeHomeworkAttachments(homeworkId: Long) = dao.observeHomeworkAttachments(homeworkId)

    suspend fun attachmentsOf(homeworkId: Long): List<HomeworkAttachment> =
        withContext(Dispatchers.IO) { dao.homeworkAttachments(homeworkId) }

    suspend fun addHomeworkAttachment(
        homeworkId: Long,
        uri: Uri,
        displayName: String,
        mime: String
    ): Long = withContext(Dispatchers.IO) {
        val ext = displayName.substringAfterLast('.', "bin").take(8)
        val path = context.contentResolver.openInputStream(uri)?.use { input ->
            store.saveStream(input, ext)
        } ?: error("无法读取所选文件")
        val size = File(context.filesDir, "vault/$path").length()
        dao.insertHomeworkAttachment(
            HomeworkAttachment(
                homeworkId = homeworkId,
                name = displayName,
                path = path,
                mime = mime,
                sizeBytes = size
            )
        )
    }

    suspend fun deleteHomeworkAttachment(a: HomeworkAttachment) = withContext(Dispatchers.IO) {
        store.delete(a.path)
        dao.deleteHomeworkAttachment(a)
    }

    suspend fun homeworkAttachmentBytes(a: HomeworkAttachment): ByteArray? =
        withContext(Dispatchers.IO) { store.readBytes(a.path) }

    suspend fun materializeHomeworkAttachmentForShare(a: HomeworkAttachment): File? =
        withContext(Dispatchers.IO) {
            val ext = a.name.substringAfterLast('.', "bin").take(8)
            store.materialize(a.path, ext)?.let { plain ->
                val named = File(plain.parentFile, a.name)
                if (plain.renameTo(named)) named else plain
            }
        }
}

package com.liuxue.assistant.feature.study

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liuxue.assistant.AppContainer
import com.liuxue.assistant.data.study.Course
import com.liuxue.assistant.data.study.CourseMaterial
import com.liuxue.assistant.data.study.CourseSchedule
import com.liuxue.assistant.data.study.Exam
import com.liuxue.assistant.data.study.Homework
import com.liuxue.assistant.data.study.HomeworkAttachment
import com.liuxue.assistant.data.study.LessonItem
import com.liuxue.assistant.data.study.Semester
import com.liuxue.assistant.data.study.StudyRepository
import com.liuxue.assistant.data.study.StudyPrefs
import com.liuxue.assistant.domain.ClassFilter
import com.liuxue.assistant.notify.ClassReminderSettings
import com.liuxue.assistant.notify.ReminderScheduler
import com.liuxue.assistant.domain.WeekCalc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class StudyTab(val label: String) {
    SCHEDULE("课表"),
    HOMEWORK("作业"),
    COURSES("课程"),
    EXAMS("考试")
}

/// 待随作业一起保存的本地文件（还没加密入库）
data class PendingFile(val uri: Uri, val name: String, val mime: String)

data class StudyUiState(
    val tab: StudyTab = StudyTab.SCHEDULE,
    val semester: Semester? = null,
    val currentWeek: Int = 1,
    /** 正在查看的周次（默认=当前周） */
    val viewingWeek: Int? = null,
    val viewingWeekday: Int = WeekCalc.weekdayOf(System.currentTimeMillis()),
    val lessons: List<LessonItem> = emptyList(),
    /** 当前查看周 7 天的全部课程（周视图用） */
    val weekLessons: List<LessonItem> = emptyList(),
    val todayLessons: List<LessonItem> = emptyList(),
    val courses: List<Course> = emptyList(),
    val schedules: List<CourseSchedule> = emptyList(),
    val homework: List<Homework> = emptyList(),
    val exams: List<Exam> = emptyList(),
    val materials: List<CourseMaterial> = emptyList(),
    /** 全部资料（课表卡片角标用） */
    val materialsAll: List<CourseMaterial> = emptyList(),
    /** 班级筛选：null = 全部 */
    val classFilter: String? = null,
    // 上课提醒（按班级作用域）
    val reminderEnabled: Boolean = false,
    val reminderMinutes: Int = 15,
    /** 提醒作用班级：null = 全部班级 */
    val reminderScope: String? = null,
    /** 正在写入导入数据 */
    val importing: Boolean = false,
    val message: String? = null
) {
    /** 实际查看的周次 */
    val week: Int get() = viewingWeek ?: currentWeek

    fun courseName(id: Long): String = courses.firstOrNull { it.id == id }?.name ?: "未指定课程"

    /** 所有出现过的班级名（用于筛选与选择） */
    val allClasses: List<String>
        get() = courses.mapNotNull { it.className.trim().ifBlank { null } }.distinct().sorted()

    /** 按班级筛选后的课程（整班/子班/全员课都按 [ClassFilter] 规则命中） */
    val filteredCourses: List<Course>
        get() = courses.filter { ClassFilter.matches(it.className, classFilter) }

    /** 按班级筛选后的课表（当天） */
    val filteredLessons: List<LessonItem>
        get() = lessons.filter { ClassFilter.matches(it.course?.className ?: "", classFilter) }

    /** 按班级筛选后的整周课表（周视图用） */
    val filteredWeekLessons: List<LessonItem>
        get() = weekLessons.filter { ClassFilter.matches(it.course?.className ?: "", classFilter) }

    /** 每门课的教室/地点（去重，供课程卡片展示） */
    val locationsByCourse: Map<Long, List<String>>
        get() = schedules
            .filter { it.location.isNotBlank() }
            .groupBy { it.courseId }
            .mapValues { (_, list) -> list.map { it.location }.distinct() }

    /** 当前筛选班级涉及的所有教室/地点 */
    val locationsOfSelectedClass: List<String>
        get() = filteredLessons.mapNotNull { it.schedule.location.ifBlank { null } }.distinct()
}

class StudyViewModel(app: Application) : AndroidViewModel(app) {

    private val repo: StudyRepository = AppContainer.studyRepository(app)

    private val _ui = MutableStateFlow(StudyUiState())
    val ui: StateFlow<StudyUiState> = _ui.asStateFlow()

    val semesters: StateFlow<List<Semester>> = repo.observeSemesters()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 正在查看资料的课程（null = 未打开） */
    private val _materialsForCourse = MutableStateFlow<Long?>(null)
    val materialsForCourse: StateFlow<Long?> = _materialsForCourse.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val materials: StateFlow<List<CourseMaterial>> = _materialsForCourse
        .flatMapLatest { id ->
            if (id == null) kotlinx.coroutines.flow.flowOf(emptyList())
            else repo.observeMaterialsOf(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun openMaterials(courseId: Long) { _materialsForCourse.value = courseId }
    fun closeMaterials() { _materialsForCourse.value = null }

    /** 课件/笔记解密成字节，供图片点击放大查看 */
    fun loadMaterialBytes(m: CourseMaterial, onResult: (ByteArray?) -> Unit) = viewModelScope.launch {
        onResult(repo.materialBytes(m))
    }

    /** 导出单个课件/笔记为可分享的明文文件 */
    fun shareMaterial(m: CourseMaterial, onReady: (java.io.File?) -> Unit) =
        viewModelScope.launch {
            val f = repo.materializeForShare(m)
            if (f == null) {
                _ui.value = _ui.value.copy(message = "解密失败，无法分享")
            } else {
                onReady(f)
            }
        }

    // ---------- 某个课程的作业 / 资料（课表详情里展示） ----------

    suspend fun courseHomework(courseId: Long): List<Homework> = repo.homeworkOfOnce(courseId)
    suspend fun courseMaterials(courseId: Long): List<CourseMaterial> = repo.materialsOfOnce(courseId)
    suspend fun attachmentsOf(homeworkId: Long): List<HomeworkAttachment> = repo.attachmentsOf(homeworkId)

    /** 作业附件解密成字节（图片显示预览图 / 点击放大） */
    fun loadAttachmentBytes(a: HomeworkAttachment, onResult: (ByteArray?) -> Unit) =
        viewModelScope.launch { onResult(repo.homeworkAttachmentBytes(a)) }

    fun shareAttachment(a: HomeworkAttachment, onReady: (java.io.File?) -> Unit) =
        viewModelScope.launch {
            val f = repo.materializeHomeworkAttachmentForShare(a)
            if (f == null) _ui.value = _ui.value.copy(message = "解密失败，无法分享")
            else onReady(f)
        }

    fun deleteAttachment(a: HomeworkAttachment) = viewModelScope.launch {
        repo.deleteHomeworkAttachment(a)
        _ui.value = _ui.value.copy(message = "附件已删除")
    }

    init {
        // 全部资料（给课表卡片显示「📄N」角标）
        viewModelScope.launch {
            repo.observeMaterials().collect { list ->
                _ui.value = _ui.value.copy(materialsAll = list)
            }
        }
        val reminder = ClassReminderSettings(app)
        _ui.value = _ui.value.copy(
            reminderEnabled = reminder.enabled,
            reminderMinutes = reminder.minutesBefore,
            reminderScope = reminder.className,
            // 班级筛选落盘：重启 / 切页 / 重新导入都不会改动用户自己的选择
            classFilter = StudyPrefs(app).classFilter
        )
        // 数据流：课程 / 作业 / 考试 / 学期
        viewModelScope.launch {
            combine(
                repo.observeCourses(),
                repo.observeHomework(),
                repo.observeExams(),
                repo.observeSemesters(),
                repo.observeAllSchedules()
            ) { courses, hw, exams, sems, schedules ->
                val active = sems.firstOrNull { it.isActive } ?: sems.firstOrNull()
                _ui.value = _ui.value.copy(
                    courses = courses,
                    schedules = schedules,
                    homework = hw,
                    exams = exams,
                    semester = active,
                    currentWeek = active?.let { WeekCalc.weekOf(it).coerceAtLeast(1) } ?: 1
                )
                Unit
            }.collect { }
        }
        refreshLessons()
    }

    fun setTab(t: StudyTab) {
        _ui.value = _ui.value.copy(tab = t)
        // 课表永远默认今天：切到课表页就回到「当前周 + 今天的星期」
        if (t == StudyTab.SCHEDULE) resetToToday()
    }

    /** 回到今天：当前周 + 今天的星期，并刷新课表 */
    fun resetToToday() {
        _ui.value = _ui.value.copy(
            viewingWeek = null,
            viewingWeekday = WeekCalc.weekdayOf(System.currentTimeMillis())
        )
        refreshLessons()
    }

    fun setViewingWeek(week: Int) {
        _ui.value = _ui.value.copy(viewingWeek = week.coerceAtLeast(1))
        refreshLessons()
    }

    fun shiftWeek(delta: Int) {
        val target = (_ui.value.week + delta).coerceAtLeast(1)
        setViewingWeek(target)
    }

    fun setClassFilter(name: String?) {
        StudyPrefs(getApplication()).classFilter = name
        _ui.value = _ui.value.copy(classFilter = name)
    }

    fun setViewingWeekday(weekday: Int) {
        _ui.value = _ui.value.copy(viewingWeekday = weekday)
        refreshLessons()
    }

    fun backToToday() {
        val now = System.currentTimeMillis()
        _ui.value = _ui.value.copy(
            viewingWeek = null,
            viewingWeekday = WeekCalc.weekdayOf(now)
        )
        refreshLessons()
    }

    fun refreshLessons() = viewModelScope.launch {
        val s = _ui.value
        val lessons = repo.lessonsOn(s.week, s.viewingWeekday)
        val weekLessons = repo.lessonsOfWeek(s.week)
        val today = repo.todayLessons()
        _ui.value = _ui.value.copy(
            lessons = lessons,
            weekLessons = weekLessons,
            todayLessons = today
        )
    }

    // ---------- 上课提醒 ----------

    /** 保存上课提醒设置并重新排期（enabled=false 时取消任务） */
    fun saveClassReminder(enabled: Boolean, minutes: Int, scope: String?) {
        val ctx = getApplication<Application>()
        val st = ClassReminderSettings(ctx)
        st.enabled = enabled
        st.minutesBefore = minutes
        st.className = scope
        ReminderScheduler.scheduleClassReminders(ctx)
        _ui.value = _ui.value.copy(
            reminderEnabled = enabled,
            reminderMinutes = st.minutesBefore,
            reminderScope = st.className,
            message = if (enabled) "上课提醒已开启（提前 " + st.minutesBefore + " 分钟）" else "上课提醒已关闭"
        )
    }

    /** 立即跑一次检查（方便验证/临时手动触发） */
    fun runClassReminderNow() {
        val ctx = getApplication<Application>()
        ReminderScheduler.runClassReminderNow(ctx)
        _ui.value = _ui.value.copy(message = "已触发一次检查；命中会发通知")
    }

    // ---------- 学期 ----------

    fun saveSemester(name: String, startDate: Long, totalWeeks: Int, active: Boolean = true) =
        viewModelScope.launch {
            repo.saveSemester(
                Semester(
                    name = name.ifBlank { "我的学期" },
                    startDate = startDate,
                    totalWeeks = totalWeeks,
                    isActive = active
                )
            )
            _ui.value = _ui.value.copy(message = "学期已保存")
            refreshLessons()
        }

    // ---------- 课程 ----------

    fun saveCourse(c: Course, schedules: List<CourseSchedule>) = viewModelScope.launch {
        val id = repo.saveCourse(c)
        // 课表整体覆盖式保存：先删旧的再写新的
        repo.schedulesOf(id).forEach { repo.deleteSchedule(it) }
        schedules.forEach { repo.saveSchedule(it.copy(courseId = id, id = 0L)) }
        _ui.value = _ui.value.copy(message = "课程已保存")
        refreshLessons()
    }

    fun deleteCourse(c: Course) = viewModelScope.launch {
        repo.deleteCourse(c)
        _ui.value = _ui.value.copy(message = "已删除「" + c.name + "」")
        refreshLessons()
    }

    fun deleteScheduleItem(s: CourseSchedule) = viewModelScope.launch {
        repo.deleteSchedule(s)
        _ui.value = _ui.value.copy(message = "已删除该节课")
        refreshLessons()
    }

    /**
     * 批量导入课表（来自 Excel / CSV）。
     *
     * 合并规则：按「课程名 + 班级」把多行归为同一门课，
     * 每行变成该课程下的一个上课时段；已存在的同名同班课程跳过，不重复导入。
     */
    fun importSchedule(rows: List<ScheduleImporter.Row>) = viewModelScope.launch {
        if (rows.isEmpty()) {
            _ui.value = _ui.value.copy(message = "没有可导入的数据")
            return@launch
        }
        _ui.value = _ui.value.copy(importing = true)
        val stats = withContext(Dispatchers.IO) {
            val existing = repo.courses().map { it.name to it.className }.toSet()
            var coursesAdded = 0
            var lessonsAdded = 0
            var skipped = 0
            rows.filter { it.courseName.isNotBlank() }
                .groupBy { it.courseName.trim() to it.className.trim() }
                .forEach { (key, group) ->
                    val (name, cls) = key
                    if (name.isBlank()) return@forEach
                    if (key in existing) {
                        skipped += group.size
                        return@forEach
                    }
                    val head = group.first()
                    val course = Course(
                        name = name,
                        className = cls,
                        teacher = head.teacher,
                        classType = head.classType,
                        credits = head.credits,
                        content = buildCourseContent(head, group.maxOf { it.hours })
                    )
                    val courseId = repo.saveCourse(course)
                    group.forEach { r ->
                        repo.saveSchedule(
                            CourseSchedule(
                                courseId = courseId,
                                weekday = r.weekday.coerceIn(1, 7),
                                startTime = r.startTime,
                                endTime = r.endTime,
                                location = r.location,
                                weekFrom = r.weekFrom.coerceAtLeast(1),
                                weekTo = r.weekTo.coerceAtLeast(r.weekFrom.coerceAtLeast(1)),
                                parity = r.parity
                            )
                        )
                    }
                    coursesAdded++
                    lessonsAdded += group.size
                }
            listOf(coursesAdded, lessonsAdded, skipped)
        }
        val coursesAdded = stats[0]
        val lessonsAdded = stats[1]
        val skipped = stats[2]
        _ui.value = _ui.value.copy(
            importing = false,
            message = "导入完成：新增 $coursesAdded 门课程 / $lessonsAdded 个上课时段" +
                (if (skipped > 0) "（跳过重复 $skipped 行）" else "")
        )
        refreshLessons()
    }

    /** 从课程简介「共 24 学时」里取回学时，供导出时还原 (N ч.) 字段 */
    private fun hoursFromContent(content: String): Int {
        val idx = content.indexOf("学时")
        if (idx <= 0) return 0
        var i = idx - 1
        while (i >= 0 && !content[i].isDigit()) i--
        if (i < 0) return 0
        var j = i
        while (j >= 0 && content[j].isDigit()) j--
        return content.substring(j + 1, i + 1).toIntOrNull() ?: 0
    }

    /** 课程简介：把专业、学时等矩阵课表信息带进课程备注 */
    private fun buildCourseContent(row: ScheduleImporter.Row, hours: Int): String {
        val parts = ArrayList<String>()
        if (row.major.isNotBlank()) parts.add("专业：" + row.major)
        if (hours > 0) parts.add("共 " + hours + " 学时")
        if (row.content.isNotBlank()) parts.add(row.content)
        return parts.joinToString(" · ")
    }

    /** 解析导入文件（IO 线程），返回结果供确认对话框展示 */
    fun parseScheduleFile(
        uri: android.net.Uri,
        displayName: String,
        onResult: (ScheduleImporter.Result) -> Unit
    ) = viewModelScope.launch {
        val ctx = getApplication<Application>()
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { ScheduleImporter.import(ctx, uri, displayName) }
                .getOrElse { ScheduleImporter.Result(error = "解析失败：" + (it.message ?: "")) }
        }
        onResult(r)
    }

    /** 课表模板文本（供分享/另存） */
    fun scheduleTemplate(): String = ScheduleImporter.templateCsv()

    /**
     * 导出课表为「教室矩阵」xlsx，写到 cache 目录后交给 UI 分享。
     * [className] 为 null 时导出全部课程，否则只导出该班。
     */
    fun exportSchedule(className: String?, onReady: (File?) -> Unit) = viewModelScope.launch {
        val ctx = getApplication<Application>()
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val courses = repo.courses().filter { className.isNullOrBlank() || it.className == className }
                if (courses.isEmpty()) return@runCatching null
                val lessons = ArrayList<ScheduleXlsxWriter.ExportLesson>()
                for (c in courses) {
                    for (s in repo.schedulesOf(c.id)) {
                        lessons.add(
                            ScheduleXlsxWriter.ExportLesson(
                                className = c.className.ifBlank { "未分班" },
                                courseName = c.name,
                                teacher = c.teacher,
                                typeLabel = ScheduleXlsxWriter.typeLabelOf(c.classType),
                                weekday = s.weekday,
                                startTime = s.startTime,
                                endTime = s.endTime,
                                location = s.location,
                                weekFrom = s.weekFrom,
                                weekTo = s.weekTo,
                                hours = hoursFromContent(c.content)
                            )
                        )
                    }
                }
                val dir = File(ctx.cacheDir, "schedule").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                val tag = (className ?: "全部课程").replace('/', '_')
                val f = File(dir, "课表_" + tag + "_" + stamp + ".xlsx")
                ScheduleXlsxWriter.writeFile(f, lessons, "课表")
                f
            }.getOrNull()
        }
        if (file == null) {
            _ui.value = _ui.value.copy(message = "没有可导出的课程")
            onReady(null)
        } else {
            _ui.value = _ui.value.copy(message = "已生成课表：" + file.name)
            onReady(file)
        }
    }

    fun schedulesOf(courseId: Long, onResult: (List<CourseSchedule>) -> Unit) =
        viewModelScope.launch { onResult(repo.schedulesOf(courseId)) }

    // ---------- 作业 ----------

    fun saveHomework(h: Homework) = viewModelScope.launch {
        repo.saveHomework(h)
        _ui.value = _ui.value.copy(message = "作业已保存")
    }

    /** 保存作业并把所选文件加密存为附件（支持任意类型） */
    fun saveHomeworkWithFiles(h: Homework, files: List<PendingFile>) = viewModelScope.launch {
        val id = repo.saveHomework(h)
        var ok = 0
        files.forEach { f ->
            runCatching { repo.addHomeworkAttachment(id, f.uri, f.name, f.mime) }
                .onSuccess { ok++ }
        }
        _ui.value = _ui.value.copy(
            message = if (files.isEmpty()) "作业已保存"
            else "作业已保存（附件 $ok/${files.size}）"
        )
    }

    fun setHomeworkStatus(h: Homework, status: String) = viewModelScope.launch {
        repo.setHomeworkStatus(h.id, status)
    }

    fun deleteHomework(h: Homework) = viewModelScope.launch {
        repo.deleteHomework(h)
        _ui.value = _ui.value.copy(message = "已删除")
    }

    /** 把外部文本解析成作业（供"粘贴导入"与深链导入使用） */
    fun parseShared(text: String): HomeworkShare.Payload? = HomeworkShare.parse(text)

    /** 确认后真正写入 */
    fun importSharedHomework(payload: HomeworkShare.Payload) = viewModelScope.launch {
        // 按课程名匹配已有课程；匹配不到则归入"未指定"
        val course = _ui.value.courses.firstOrNull { it.name == payload.courseName }
        repo.saveHomework(
            HomeworkShare.toHomework(payload, course?.id ?: 0L)
        )
        _ui.value = _ui.value.copy(
            message = "已导入作业：" + payload.title +
                (if (course != null) "（归入 " + course.name + "）" else "（未指定课程）")
        )
    }

    /** 生成分享文本 */
    fun buildShareText(hw: Homework): String {
        val course = _ui.value.courses.firstOrNull { it.id == hw.courseId }
        return HomeworkShare.shareText(
            HomeworkShare.Payload(
                title = hw.title,
                content = hw.content,
                courseName = course?.name ?: "",
                dueDate = hw.dueDate,
                remindDaysBefore = hw.remindDaysBefore
            )
        )
    }

    // ---------- 考试 ----------

    fun saveExam(e: Exam) = viewModelScope.launch {
        repo.saveExam(e)
        _ui.value = _ui.value.copy(message = "考试已保存")
    }

    fun deleteExam(e: Exam) = viewModelScope.launch {
        repo.deleteExam(e)
        _ui.value = _ui.value.copy(message = "已删除")
    }

    // ---------- 课件 ----------

    fun addMaterial(courseId: Long, uri: android.net.Uri, name: String, mime: String, kind: String) =
        viewModelScope.launch {
            runCatching { repo.addMaterial(courseId, uri, name, mime, kind) }
                .onSuccess { _ui.value = _ui.value.copy(message = "已导入：" + name) }
                .onFailure { _ui.value = _ui.value.copy(message = "导入失败：" + (it.message ?: "")) }
        }

    fun deleteMaterial(m: CourseMaterial) = viewModelScope.launch {
        repo.deleteMaterial(m)
        _ui.value = _ui.value.copy(message = "已删除")
    }

    fun clearMessage() { _ui.value = _ui.value.copy(message = null) }

    companion object {
        /** 快捷生成：每周固定时间段的课 */
        fun weekdayNames() = (1..7).map { CourseSchedule.weekdayLabel(it) }
    }
}

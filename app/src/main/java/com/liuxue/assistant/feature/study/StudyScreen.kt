package com.liuxue.assistant.feature.study

import android.widget.Toast
import kotlinx.coroutines.launch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liuxue.assistant.data.study.ClassType
import com.liuxue.assistant.data.study.Course
import com.liuxue.assistant.data.study.CourseMaterial
import com.liuxue.assistant.data.study.CourseSchedule
import com.liuxue.assistant.data.study.Exam
import com.liuxue.assistant.data.study.Homework
import com.liuxue.assistant.data.study.HomeworkAttachment
import com.liuxue.assistant.data.study.LessonItem
import com.liuxue.assistant.feature.files.ZoomableImageBytesDialog
import com.liuxue.assistant.ui.SearchablePickerField
import com.liuxue.assistant.domain.WeekCalc
import com.liuxue.assistant.util.DateUtils
import com.liuxue.assistant.util.ShareUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.liuxue.assistant.ui.ConfirmDeleteDialog
import com.liuxue.assistant.ui.WarnDialog
import kotlin.math.absoluteValue
import java.util.Calendar

/**
 * 学业管理：课表 / 作业 / 课程 / 考试。
 * 课表按「周次 + 星期」实时查询，并显示当前第几周。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyScreen(vm: StudyViewModel = viewModel<StudyViewModel>()) {
    val state by vm.ui.collectAsState(initial = StudyUiState())
    val snackbar = remember { SnackbarHostState() }
    var editCourse by remember { mutableStateOf(false) }
    var editHomework by remember { mutableStateOf(false) }
    var editExam by remember { mutableStateOf(false) }
    var editSemester by remember { mutableStateOf(false) }
    var showReminder by remember { mutableStateOf(false) }
    // 课表导入：解析结果（非 null 时弹确认框）
    var scheduleImport by remember { mutableStateOf<ScheduleImporter.Result?>(null) }
    var scheduleImportBusy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // 选择课表文件（xlsx / csv）
    val pickSchedule = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scheduleImportBusy = true
            var name = "schedule.xlsx"
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) c.getString(i)?.let { name = it }
                }
            }
            vm.parseScheduleFile(uri, name) { r ->
                scheduleImportBusy = false
                scheduleImport = r
            }
        }
    }

    // 导出课表（按当前班级筛选导出；未筛选则导出全部）
    fun exportSchedule() {
        vm.exportSchedule(state.classFilter) { f ->
            if (f != null) {
                ShareUtils.shareFile(
                    context, f,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "分享课表"
                )
            }
        }
    }

    // null=不显示；非 null=对话框里预填的文本
    var importText by remember { mutableStateOf<String?>(null) }

    // 消费来自深链的分享内容（MainActivity 收到 liuxue:// 后放进来）
    LaunchedEffect(Unit) {
        // 进入学业页：课表默认回到今天
        vm.resetToToday()
        HomeworkShare.Pending.take()?.let { importText = it }
    }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("学业管理") },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { editSemester = true }) {
                        Icon(Icons.Filled.DateRange, contentDescription = "学期设置")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = state.tab.ordinal) {
                StudyTab.entries.forEach { t ->
                    Tab(
                        selected = state.tab == t,
                        onClick = { vm.setTab(t) },
                        text = { Text(t.label) }
                    )
                }
            }
            when (state.tab) {
                StudyTab.SCHEDULE -> ScheduleTab(state, vm, { editCourse = true }, { showReminder = true })
                StudyTab.HOMEWORK -> HomeworkTab(state, vm, { editHomework = true }, { importText = "" })
                StudyTab.COURSES -> CoursesTab(state, vm, { editCourse = true }, { pickSchedule.launch(arrayOf("*/*")) }, { exportSchedule() })
                StudyTab.EXAMS -> ExamsTab(state, vm) { editExam = true }
            }
        }
    }

    if (editCourse) CourseDialog(vm, state.courses) { editCourse = false }
    if (editHomework) HomeworkDialog(state.courses) { editHomework = false }
    if (editExam) ExamDialog(state.courses, vm) { editExam = false }
    if (editSemester) SemesterDialog(vm) { editSemester = false }
    val materialsCourseId by vm.materialsForCourse.collectAsState(initial = null)
    if (materialsCourseId != null) {
        MaterialsDialog(vm, materialsCourseId!!, state.courses) { vm.closeMaterials() }
    }
    if (scheduleImportBusy) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在解析课表") },
            text = { Text("请稍候…") },
            confirmButton = {}
        )
    }
    if (state.importing) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在导入课表") },
            text = { Text("正在写入课程与上课时段，请稍候…") },
            confirmButton = {}
        )
    }
    scheduleImport?.let { result ->
        ScheduleImportDialog(
            result = result,
            vm = vm,
            onShareTemplate = {
                ShareUtils.shareText(context, vm.scheduleTemplate(), "课表模板")
            },
            onDismiss = { scheduleImport = null }
        )
    }
    importText?.let { prefill ->
        ImportHomeworkDialog(
            vm = vm,
            prefill = prefill,
            onDismiss = { importText = null }
        )
    }
    if (showReminder) {
        ClassReminderDialog(
            state = state,
            onSave = { e, m, scope -> vm.saveClassReminder(e, m, scope) },
            onCheckNow = { vm.runClassReminderNow() },
            onDismiss = { showReminder = false }
        )
    }
}

@Composable
private fun ClassFilterRow(
    state: StudyUiState,
    vm: StudyViewModel,
    trailing: (@Composable () -> Unit)? = null
) {
    if (state.allClasses.isEmpty() && trailing == null) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SearchablePickerField(
            label = "班级筛选（${state.allClasses.size} 个班）",
            items = state.allClasses,
            selected = state.classFilter,
            labelOf = { it },
            clearLabel = "全部班级",
            emptyHint = "还没有识别到班级；导入课表后会出现",
            modifier = Modifier.weight(1f),
            onSelect = { vm.setClassFilter(it) }
        )
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            trailing()
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ==================== 课表 ====================

@Composable
private fun ScheduleTab(
    state: StudyUiState,
    vm: StudyViewModel,
    onAdd: () -> Unit,
    onReminder: () -> Unit
) {
    var view by remember { mutableStateOf(ScheduleView.DAY) }
    var detail by remember { mutableStateOf<List<LessonItem>?>(null) }
    var pendingDelete by remember { mutableStateOf<CourseSchedule?>(null) }

    val weekLessons = state.filteredWeekLessons
    val rows = weekLessons.map { it.schedule.startTime }.distinct()
        .sortedBy { WeekCalc.minutesOf(it) }
    val byCell = weekLessons.groupBy { it.schedule.startTime to it.schedule.weekday }
    val timeColWidth = 42.dp

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 104.dp)
    ) {
        item(key = "weeknav") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { vm.shiftWeek(-1) }) { Text("上一周") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("第 ${state.week} 周", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    Text(
                        state.semester?.let { WeekCalc.weekLabel(it) } ?: "未设置学期",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = { vm.shiftWeek(1) }) { Text("下一周") }
            }
        }
        item(key = "actions") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { vm.backToToday() }, modifier = Modifier.weight(1f)) {
                    Text("回到今天")
                }
                Button(onClick = onAdd, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("添加课程")
                }
            }
        }
        item(key = "reminder") {
            Column {
                Spacer(Modifier.height(6.dp))
                ReminderBar(state, onReminder)
                Spacer(Modifier.height(6.dp))
            }
        }
        item(key = "filter") {
            ClassFilterRow(state, vm) {
                FilterChip(
                    selected = view == ScheduleView.DAY,
                    onClick = { view = ScheduleView.DAY },
                    label = { Text("日") }
                )
                Spacer(Modifier.width(4.dp))
                FilterChip(
                    selected = view == ScheduleView.WEEK,
                    onClick = { view = ScheduleView.WEEK },
                    label = { Text("周") }
                )
            }
        }
        item(key = "count") {
            Text(
                if (view == ScheduleView.WEEK) "本周 ${weekLessons.size} 节"
                else "当天 ${state.filteredLessons.size} 节",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, top = 2.dp, bottom = 4.dp)
            )
        }
        if (state.semester == null) {
            item { Hint("还没设置学期\n点右上角日历图标设置开学日期和周数") }
        } else if (view == ScheduleView.WEEK) {
            if (weekLessons.isEmpty()) {
                item { Hint("第 ${state.week} 周 没有课") }
            } else {
                item(key = "gridhead") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 4.dp)
                    ) {
                        Spacer(Modifier.width(timeColWidth))
                        (1..7).forEach { d ->
                            Text(
                                CourseSchedule.weekdayLabel(d).removePrefix("周"),
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                items(rows, key = { "row_" + it }) { t ->
                    val maxCount = (1..7).maxOf { byCell[t to it]?.size ?: 0 }.coerceAtLeast(1)
                    val shownRows = maxCount.coerceAtMost(MAX_LESSONS_PER_CELL)
                    val cellHeight = (12 + shownRows * 34 +
                        (if (maxCount > shownRows) 16 else 0)).dp
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(vertical = 2.dp)
                    ) {
                        Column(Modifier.width(timeColWidth)) {
                            Text(t, style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium)
                        }
                        (1..7).forEach { d ->
                            val cell = byCell[t to d].orEmpty()
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(cellHeight)
                                    .padding(horizontal = 1.dp)
                                    .background(
                                        if (cell.isEmpty())
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                        else Color.Transparent,
                                        RoundedCornerShape(5.dp)
                                    )
                                    .clickable(enabled = cell.isNotEmpty()) {
                                        if (cell.isNotEmpty()) detail = cell
                                    }
                            ) {
                                Column(
                                    Modifier.fillMaxSize().padding(2.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    cell.take(MAX_LESSONS_PER_CELL).forEach { lesson ->
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .background(lessonColor(lesson), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 3.dp, vertical = 2.dp)
                                        ) {
                                            Column {
                                                Text(
                                                    lesson.course?.name ?: "未命名",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                    color = Color.White,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    lineHeight = 10.sp
                                                )
                                                Text(
                                                    "📍" + lesson.schedule.location.ifBlank { "—" },
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                    color = Color.White,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    lineHeight = 10.sp
                                                )
                                            }
                                        }
                                    }
                                    if (cell.size > MAX_LESSONS_PER_CELL) {
                                        Text(
                                            "+" + (cell.size - MAX_LESSONS_PER_CELL),
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        else {
            item(key = "daychips") {
                // 自动把星期 chip 滚到今天（否则今天在屏幕外，看不出选中的是哪天）
                val chipState = androidx.compose.foundation.lazy.rememberLazyListState()
                LaunchedEffect(state.viewingWeekday) {
                    chipState.animateScrollToItem((state.viewingWeekday - 1).coerceIn(0, 6))
                }
                LazyRow(
                    state = chipState,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items((1..7).toList()) { w ->
                        FilterChip(
                            selected = state.viewingWeekday == w,
                            onClick = { vm.setViewingWeekday(w) },
                            label = { Text(CourseSchedule.weekdayLabel(w)) }
                        )
                    }
                }
            }
            if (state.filteredLessons.isEmpty()) {
                item {
                    Hint("第 ${state.week} 周 " +
                        CourseSchedule.weekdayLabel(state.viewingWeekday) + " 没有课")
                }
            } else {
                items(state.filteredLessons, key = { it.schedule.id }) { lesson ->
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        val cid = lesson.course?.id ?: -1L
                        LessonCard(
                            lesson,
                            materialCount = state.materialsAll.count { it.courseId == cid },
                            homeworkCount = state.homework.count { it.courseId == cid },
                            onClick = { detail = listOf(lesson) },
                            onDelete = { pendingDelete = lesson.schedule }
                        )
                    }
                }
            }
        }
    }

    detail?.let { list -> LessonDetailDialog(list, vm) { detail = null } }
    pendingDelete?.let { s ->
        ConfirmDeleteDialog(
            text = "删除这个时段？" + CourseSchedule.weekdayLabel(s.weekday) + " " +
                s.startTime + "-" + s.endTime,
            onConfirm = { vm.deleteScheduleItem(s) },
            onDismiss = { pendingDelete = null }
        )
    }
}

private enum class ScheduleView { WEEK, DAY }

/** 一格最多显示几门课，多出的用 +N 表示（点格子看全部） */
private const val MAX_LESSONS_PER_CELL = 3

private val LESSON_COLORS = listOf(
    Color(0xFF1E6F5C), Color(0xFF2D6CDF), Color(0xFF8E44AD), Color(0xFFC0392B),
    Color(0xFFB7791F), Color(0xFF2E7D32), Color(0xFF00838F), Color(0xFF5D4037)
)

private fun lessonColor(lesson: LessonItem): Color {
    val key = lesson.course?.name.orEmpty()
    if (key.isBlank()) return Color(0xFF6B7280)
    return LESSON_COLORS[key.hashCode().absoluteValue % LESSON_COLORS.size]
}

@Composable
private fun LessonDetailDialog(
    lessons: List<LessonItem>,
    vm: StudyViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val courseList = lessons.mapNotNull { it.course }.distinctBy { it.id }
    val courseId = courseList.firstOrNull()?.id
    val linkCourse = courseList.firstOrNull { it.link.isNotBlank() }

    var homework by remember(courseId) { mutableStateOf<List<Homework>>(emptyList()) }
    var materials by remember(courseId) { mutableStateOf<List<CourseMaterial>>(emptyList()) }
    var attachments by remember(courseId) { mutableStateOf<Map<Long, List<HomeworkAttachment>>>(emptyMap()) }
    var viewerBytes by remember { mutableStateOf<ByteArray?>(null) }
    var viewerTitle by remember { mutableStateOf("") }
    var showMaterials by remember { mutableStateOf(false) }
    var showHomework by remember { mutableStateOf(false) }
    var delMaterial by remember { mutableStateOf<CourseMaterial?>(null) }
    var delAttachment by remember { mutableStateOf<HomeworkAttachment?>(null) }
    var reload by remember(courseId) { mutableStateOf(0) }

    LaunchedEffect(courseId, reload) {
        if (courseId != null) {
            val hw = vm.courseHomework(courseId)
            homework = hw
            materials = vm.courseMaterials(courseId)
            attachments = hw.associate { it.id to vm.attachmentsOf(it.id) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (lessons.size > 1) "本节课共 ${lessons.size} 门" else "课程详情") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                lessons.forEachIndexed { i, lesson ->
                    val c = lesson.course
                    val s = lesson.schedule
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(
                        c?.name ?: "未指定课程",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    val info = buildList {
                        if (c != null) {
                            add(ClassType.label(c.classType))
                            if (c.teacher.isNotBlank()) add(c.teacher)
                            if (c.className.isNotBlank()) add(c.className)
                        }
                    }.joinToString(" · ")
                    if (info.isNotBlank()) {
                        Text(
                            info,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Text(
                        "🕐 " + CourseSchedule.weekdayLabel(s.weekday) + "  " +
                            s.startTime + " - " + s.endTime +
                            (if (s.parity != CourseSchedule.PARITY_ALL || s.weekFrom != 1 || s.weekTo != 18)
                                "（第 ${s.weekFrom}-${s.weekTo} 周" + when (s.parity) {
                                    CourseSchedule.PARITY_ODD -> " 单周"
                                    CourseSchedule.PARITY_EVEN -> " 双周"
                                    else -> ""
                                } + "）"
                            else ""),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        "📍 " + s.location.ifBlank { "未填写地点" },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (c != null && c.content.isNotBlank()) {
                        Text(
                            c.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                // ---------- 资料 ----------
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "📄 资料（${materials.size}）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    if (courseId != null) {
                        TextButton(onClick = { showMaterials = true }) { Text("＋ 添加") }
                    }
                }
                if (materials.isEmpty()) {
                    Text(
                        "暂无资料",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    materials.forEach { m ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            ThumbFromBytes(
                                key = "mat_" + m.id,
                                mime = m.mime,
                                name = m.name,
                                loader = { cb -> vm.loadMaterialBytes(m) { cb(it) } },
                                size = 36.dp
                            )
                            Spacer(Modifier.size(6.dp))
                            Column(
                                Modifier.weight(1f).clickable {
                                    if (m.mime.startsWith("image/")) {
                                        vm.loadMaterialBytes(m) { b ->
                                            if (b != null) {
                                                viewerBytes = b
                                                viewerTitle = m.name
                                            }
                                        }
                                    } else {
                                        ShareUtils.openStream(context, m.path, m.name, m.mime)
                                    }
                                }
                            ) {
                                Text(
                                    m.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    CourseMaterial.kindLabel(m.kind) + " · " + formatBytes(m.sizeBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = {
                                ShareUtils.shareStream(context, m.path, m.name, m.mime)
                            }) { Text("分享", style = MaterialTheme.typography.labelSmall) }
                            TextButton(onClick = { delMaterial = m }) {
                                Text("删除", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                // ---------- 作业 ----------
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "📝 作业（${homework.size}）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    if (courseId != null) {
                        TextButton(onClick = { showHomework = true }) { Text("＋ 添加") }
                    }
                }
                if (homework.isEmpty()) {
                    Text(
                        "暂无作业",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    homework.forEach { hw ->
                        Text(
                            "• " + hw.title + " · " + Homework.statusLabel(hw.status) +
                                (hw.dueDate?.let { " · 截止 " + DateUtils.formatDay(it) } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        attachments[hw.id].orEmpty().forEach { a ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                            ) {
                                ThumbFromBytes(
                                    key = "att_" + a.id,
                                    mime = a.mime,
                                    name = a.name,
                                    loader = { cb -> vm.loadAttachmentBytes(a) { cb(it) } },
                                    size = 36.dp
                                )
                                Spacer(Modifier.size(6.dp))
                                Column(
                                    Modifier.weight(1f).clickable {
                                        if (a.mime.startsWith("image/")) {
                                            vm.loadAttachmentBytes(a) { b ->
                                                if (b != null) {
                                                    viewerBytes = b
                                                    viewerTitle = a.name
                                                }
                                            }
                                        } else {
                                            ShareUtils.openStream(context, a.path, a.name, a.mime)
                                        }
                                    }
                                ) {
                                    Text(
                                        a.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        formatBytes(a.sizeBytes),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = {
                                    ShareUtils.shareStream(context, a.path, a.name, a.mime)
                                }) { Text("分享", style = MaterialTheme.typography.labelSmall) }
                                TextButton(onClick = { delAttachment = a }) {
                                    Text("删除", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }

                if (linkCourse != null) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    TextButton(onClick = { openUrl(context, linkCourse.link) }) {
                        Text("🔗 打开网课链接")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )

    viewerBytes?.let { bytes ->
        ZoomableImageBytesDialog(bytes, viewerTitle) { viewerBytes = null }
    }

    if (showMaterials && courseId != null) {
        MaterialsDialog(
            vm = vm,
            courseId = courseId,
            courses = courseList,
            onDismiss = {
                showMaterials = false
                reload++
            }
        )
    }
    if (showHomework && courseId != null) {
        HomeworkDialog(
            courses = courseList,
            initialCourseId = courseId,
            onDismiss = {
                showHomework = false
                reload++
            }
        )
    }
    delMaterial?.let { m ->
        ConfirmDeleteDialog(
            text = "删除资料「" + m.name + "」？文件会一起删。",
            onConfirm = { vm.deleteMaterial(m); reload++ },
            onDismiss = { delMaterial = null }
        )
    }
    delAttachment?.let { a ->
        ConfirmDeleteDialog(
            text = "删除附件「" + a.name + "」？",
            onConfirm = { vm.deleteAttachment(a); reload++ },
            onDismiss = { delAttachment = null }
        )
    }
}
@Composable
private fun LessonCard(
    lesson: LessonItem,
    materialCount: Int,
    homeworkCount: Int,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val s = lesson.schedule
    val c = lesson.course
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // 左侧大号开始时间 + 小号结束时间（原来的样式）
            Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.startTime, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                Text(s.endTime, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(c?.name ?: "未指定课程", style = MaterialTheme.typography.titleMedium)
                val info = buildList {
                    if (c != null) {
                        add(ClassType.label(c.classType))
                        if (c.teacher.isNotBlank()) add(c.teacher)
                        if (c.className.isNotBlank()) add(c.className)
                    }
                }.joinToString(" · ")
                Text(info, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.location.isNotBlank()) {
                    Text(
                        "📍 " + s.location,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (materialCount > 0 || homeworkCount > 0) {
                    Text(
                        buildString {
                            if (materialCount > 0) append("📄 ").append(materialCount).append(" 份资料")
                            if (materialCount > 0 && homeworkCount > 0) append("  ·  ")
                            if (homeworkCount > 0) append("📝 ").append(homeworkCount).append(" 作业")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (s.parity != CourseSchedule.PARITY_ALL || s.weekFrom != 1 || s.weekTo != 18) {
                    Text(
                        "周次 ${s.weekFrom}-${s.weekTo}" + when (s.parity) {
                            CourseSchedule.PARITY_ODD -> " 单周"
                            CourseSchedule.PARITY_EVEN -> " 双周"
                            else -> ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除")
            }
        }
    }
}

// ==================== 作业 ====================

@Composable
private fun HomeworkTab(
    state: StudyUiState,
    vm: StudyViewModel,
    onAdd: () -> Unit,
    onImport: () -> Unit
) {
    val context = LocalContext.current
    var onlyOpen by remember { mutableStateOf(true) }
    var pendingDelete by remember { mutableStateOf<Homework?>(null) }
    val list = state.homework.filter {
        !onlyOpen || it.status != Homework.STATUS_DONE
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = onlyOpen, onClick = { onlyOpen = true },
                label = { Text("未完成") })
            FilterChip(selected = !onlyOpen, onClick = { onlyOpen = false },
                label = { Text("全部") })
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onImport) {
                Text("导入", style = MaterialTheme.typography.labelMedium)
            }
            Button(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("作业")
            }
        }
        if (list.isEmpty()) {
            Hint("没有作业\n可设截止时间和提醒")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(list, key = { it.id }) { hw ->
                    HomeworkCard(hw, state.courseName(hw.courseId),
                        onStatus = { vm.setHomeworkStatus(hw, it) },
                        onDelete = { pendingDelete = hw },
                        onShare = {
                            ShareUtils.shareText(context, vm.buildShareText(hw), "分享作业")
                        })
                }
            }
        }
    }

    pendingDelete?.let { hw ->
        ConfirmDeleteDialog(
            text = "删除作业「" + hw.title + "」？",
            onConfirm = { vm.deleteHomework(hw) },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun HomeworkCard(
    hw: Homework,
    courseName: String,
    onStatus: (String) -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    val overdue = hw.dueDate != null && hw.dueDate < System.currentTimeMillis() &&
        hw.status != Homework.STATUS_DONE
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(hw.title, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text(courseName, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = "分享")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                }
            }
            if (hw.dueDate != null) {
                Text(
                    "截止 ${DateUtils.formatDay(hw.dueDate)} · ${WeekCalc.humanUntil(hw.dueDate)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overdue) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (hw.content.isNotBlank()) {
                Text(hw.content, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Homework.STATUS_TODO, Homework.STATUS_DOING, Homework.STATUS_DONE)
                    .forEach { st ->
                        FilterChip(
                            selected = hw.status == st,
                            onClick = { onStatus(st) },
                            label = { Text(Homework.statusLabel(st),
                                style = MaterialTheme.typography.labelSmall) }
                        )
                    }
            }
        }
    }
}

// ==================== 课程 ====================

@Composable
private fun CoursesTab(
    state: StudyUiState,
    vm: StudyViewModel,
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit
) {
    var pendingDelete by remember { mutableStateOf<Course?>(null) }
    val context = LocalContext.current

    // 顶部「数量 + 导入/导出/添加 + 班级筛选」也做成列表项：往上滑会自动收起，把空间让给课程列表
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 104.dp)
    ) {
        item(key = "courses_header") {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "共 ${state.filteredCourses.size} 门课程",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = onImport) {
                    Text("导入", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.size(6.dp))
                OutlinedButton(onClick = onExport) {
                    Text("导出", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.size(6.dp))
                Button(onClick = onAdd) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("课程")
                }
            }
        }
        item(key = "courses_filter") { ClassFilterRow(state, vm) }
        if (state.courses.isEmpty()) {
            item {
                Hint("还没有课程\n添加时可设上课时间、周次、单双周")
            }
        } else {
            items(state.filteredCourses, key = { it.id }) { c ->
                Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold)
                                val info = buildList {
                                    add(ClassType.label(c.classType))
                                    if (c.teacher.isNotBlank()) add(c.teacher)
                                    if (c.className.isNotBlank()) add(c.className)
                                    if (c.credits > 0) add("${c.credits} 学分")
                                }.joinToString(" · ")
                                Text(info, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val locs = state.locationsByCourse[c.id].orEmpty()
                                if (locs.isNotEmpty()) {
                                    Text(
                                        "📍 " + locs.take(3).joinToString(" / ") +
                                            (if (locs.size > 3) " 等 " + locs.size + " 处" else ""),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                                if (c.content.isNotBlank()) {
                                    Text(c.content, style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 2.dp))
                                }
                                val mc = state.materialsAll.count { it.courseId == c.id }
                                val hc = state.homework.count { it.courseId == c.id }
                                if (mc > 0 || hc > 0) {
                                    Text(
                                        buildString {
                                            if (mc > 0) append("📄 ").append(mc).append(" 份资料")
                                            if (mc > 0 && hc > 0) append("  ·  ")
                                            if (hc > 0) append("📝 ").append(hc).append(" 作业")
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                                if (c.link.isNotBlank()) {
                                    Text(
                                        "🔗 " + c.link,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 2.dp)
                                            .clickable { openUrl(context, c.link) }
                                    )
                                }
                            }
                            IconButton(onClick = { vm.openMaterials(c.id) }) {
                                Text("资料", style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = { pendingDelete = c }) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除")
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { c ->
        ConfirmDeleteDialog(
            text = "删除课程「" + c.name + "」？上课时间会一起删。",
            onConfirm = { vm.deleteCourse(c) },
            onDismiss = { pendingDelete = null }
        )
    }
}

// ==================== 考试 ====================

@Composable
private fun ExamsTab(state: StudyUiState, vm: StudyViewModel, onAdd: () -> Unit) {
    var pendingDelete by remember { mutableStateOf<Exam?>(null) }
    val now = System.currentTimeMillis()
    val upcoming = state.exams.filter { it.examDate >= now }
    val passed = state.exams.filter { it.examDate < now }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("待考 ${upcoming.size} 场", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f))
            Button(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("考试")
            }
        }
        if (state.exams.isEmpty()) {
            Hint("还没有考试\n可记录时间、地点和范围")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(upcoming + passed, key = { it.id }) { e ->
                    val isPast = e.examDate < now
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.name, style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold)
                                Text(state.courseName(e.courseId),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    DateUtils.formatDateTime(e.examDate) +
                                        " · ${e.durationMinutes} 分钟" +
                                        (if (e.location.isNotBlank()) " · ${e.location}" else ""),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    if (isPast) "已结束" else WeekCalc.humanUntil(e.examDate),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isPast) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.primary
                                )
                                if (e.content.isNotBlank()) {
                                    Text(e.content, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            IconButton(onClick = { pendingDelete = e }) {
                                Icon(Icons.Filled.Delete, contentDescription = "删除")
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { e ->
        ConfirmDeleteDialog(
            text = "删除考试「" + e.name + "」？",
            onConfirm = { vm.deleteExam(e) },
            onDismiss = { pendingDelete = null }
        )
    }
}

// ==================== 编辑对话框 ====================

@Composable
private fun CourseDialog(
    vm: StudyViewModel,
    courses: List<Course>,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var className by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf("") }
    var classType by remember { mutableStateOf(ClassType.LARGE) }
    var content by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var weekday by remember { mutableStateOf(1) }
    var startTime by remember { mutableStateOf("08:00") }
    var endTime by remember { mutableStateOf("09:40") }
    var location by remember { mutableStateOf("") }
    var weekFrom by remember { mutableStateOf(1) }
    var weekTo by remember { mutableStateOf(18) }
    var parity by remember { mutableStateOf(CourseSchedule.PARITY_ALL) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加课程") },
        text = {
            Column(
                Modifier.height(400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("课程名称 *") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = className, onValueChange = { className = it },
                    label = { Text("具体班级") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = teacher, onValueChange = { teacher = it },
                    label = { Text("任课教师") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Text("课程类型", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ClassType.all) { t ->
                        FilterChip(selected = classType == t, onClick = { classType = t },
                            label = { Text(ClassType.label(t),
                                style = MaterialTheme.typography.labelSmall) })
                    }
                }
                OutlinedTextField(value = content, onValueChange = { content = it },
                    label = { Text("课程内容") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = link, onValueChange = { link = it },
                    label = { Text("课程链接（可留空）") },
                    placeholder = { Text("https://…") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("上课时间", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items((1..7).toList()) { w ->
                        FilterChip(selected = weekday == w, onClick = { weekday = w },
                            label = { Text(CourseSchedule.weekdayLabel(w),
                                style = MaterialTheme.typography.labelSmall) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = startTime, onValueChange = { startTime = it },
                        label = { Text("开始") }, singleLine = true,
                        modifier = Modifier.weight(1f))
                    OutlinedTextField(value = endTime, onValueChange = { endTime = it },
                        label = { Text("结束") }, singleLine = true,
                        modifier = Modifier.weight(1f))
                }
                OutlinedTextField(value = location, onValueChange = { location = it },
                    label = { Text("上课地点") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Text("周次与单双周", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = weekFrom.toString(),
                        onValueChange = { weekFrom = it.toIntOrNull() ?: 1 },
                        label = { Text("起始周") }, singleLine = true,
                        modifier = Modifier.weight(1f))
                    OutlinedTextField(value = weekTo.toString(),
                        onValueChange = { weekTo = it.toIntOrNull() ?: 18 },
                        label = { Text("结束周") }, singleLine = true,
                        modifier = Modifier.weight(1f))
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf(
                        CourseSchedule.PARITY_ALL to "每周",
                        CourseSchedule.PARITY_ODD to "单周",
                        CourseSchedule.PARITY_EVEN to "双周"
                    )) { pair ->
                        FilterChip(selected = parity == pair.first,
                            onClick = { parity = pair.first },
                            label = { Text(pair.second,
                                style = MaterialTheme.typography.labelSmall) })
                    }
                }
                error?.let { msg ->
                    Text(msg, color = MaterialTheme.colorScheme.error)
                    WarnDialog(msg) { error = null }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    name.isBlank() -> error = "请填写课程名称"
                    WeekCalc.minutesOf(startTime) < 0 || WeekCalc.minutesOf(endTime) < 0 ->
                        error = "时间格式应为 HH:mm"
                    else -> {
                        vm.saveCourse(
                            Course(name = name, className = className, teacher = teacher,
                                classType = classType, content = content, link = link.trim()),
                            listOf(CourseSchedule(courseId = 0, weekday = weekday,
                                startTime = startTime, endTime = endTime, location = location,
                                weekFrom = weekFrom, weekTo = weekTo, parity = parity))
                        )
                        onDismiss()
                    }
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun CoursePicker(
    courses: List<Course>,
    selected: Long,
    onSelect: (Long) -> Unit
) {
    SearchablePickerField(
        label = "课程（可搜索）",
        items = courses,
        selected = courses.firstOrNull { it.id == selected },
        labelOf = { c ->
            c.name + if (c.className.isNotBlank()) "（" + c.className + "）" else ""
        },
        searchOf = { c -> c.name + " " + c.className + " " + c.teacher },
        clearLabel = "未指定",
        emptyHint = "还没有课程，先在「课程」页添加或导入课表",
        onSelect = { onSelect(it?.id ?: 0L) }
    )
}

@Composable
private fun HomeworkDialog(
    courses: List<Course>,
    initialCourseId: Long? = null,
    onDismiss: () -> Unit
) {
    val vm: StudyViewModel = viewModel<StudyViewModel>()
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var courseId by remember {
        mutableStateOf(initialCourseId ?: courses.firstOrNull()?.id ?: 0L)
    }
    var dueDate by remember { mutableStateOf(System.currentTimeMillis() + 7 * WeekCalc.MILLIS_PER_DAY) }
    var showDuePicker by remember { mutableStateOf(false) }
    var remindDays by remember { mutableStateOf(1) }
    var showRemindPicker by remember { mutableStateOf(false) }

    if (showDuePicker) {
        com.liuxue.assistant.ui.WheelDateDialog(
            title = "截止日期",
            initialMillis = dueDate,
            onDismiss = { showDuePicker = false },
            onPick = { dueDate = it; showDuePicker = false }
        )
    }
    if (showRemindPicker) {
        com.liuxue.assistant.ui.WheelNumberDialog(
            title = "提前几天提醒", range = 0..30, initial = remindDays, unit = "天",
            onDismiss = { showRemindPicker = false },
            onPick = { remindDays = it; showRemindPicker = false }
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<List<PendingFile>>(emptyList()) }

    val pickFiles = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            var name = "file_" + System.currentTimeMillis()
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) c.getString(i)?.let { name = it }
                }
            }
            pending = pending + PendingFile(uri, name, mime)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加作业") },
        text = {
            Column(
                Modifier.height(340.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = title, onValueChange = { title = it },
                    label = { Text("作业标题 *") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Text("所属课程", style = MaterialTheme.typography.labelMedium)
                CoursePicker(courses, courseId) { courseId = it }
                OutlinedTextField(value = content, onValueChange = { content = it },
                    label = { Text("作业内容（可随时补充）") },
                    modifier = Modifier.fillMaxWidth())
                Text("截止日期", style = MaterialTheme.typography.labelMedium)
                OutlinedButton(
                    onClick = { showDuePicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(DateUtils.formatDay(dueDate)) }
                Text("提前提醒", style = MaterialTheme.typography.labelMedium)
                OutlinedButton(
                    onClick = { showRemindPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (remindDays <= 0) "不提醒" else "提前 " + remindDays + " 天") }
                Text("附件（图片可预览）", style = MaterialTheme.typography.labelMedium)
                OutlinedButton(onClick = { pickFiles.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("添加文件（可多选）")
                }
                pending.forEachIndexed { i, f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        com.liuxue.assistant.ui.UriThumb(f.uri, f.mime, f.name, 40.dp)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            f.name,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            pending = pending.filterIndexed { j, _ -> j != i }
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "移除")
                        }
                    }
                }
                error?.let { msg ->
                    Text(msg, color = MaterialTheme.colorScheme.error)
                    WarnDialog(msg) { error = null }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (title.isBlank()) error = "请填写标题"
                else {
                    vm.saveHomeworkWithFiles(
                        Homework(courseId = courseId, title = title, content = content,
                            dueDate = dueDate,
                            remindDaysBefore = remindDays),
                        pending
                    )
                    onDismiss()
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ExamDialog(
    courses: List<Course>,
    vm: StudyViewModel,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var courseId by remember { mutableStateOf(courses.firstOrNull()?.id ?: 0L) }
    var examDate by remember { mutableStateOf(System.currentTimeMillis() + 30 * WeekCalc.MILLIS_PER_DAY) }
    var showExamPicker by remember { mutableStateOf(false) }

    if (showExamPicker) {
        com.liuxue.assistant.ui.WheelDateDialog(
            title = "考试时间",
            initialMillis = examDate,
            onDismiss = { showExamPicker = false },
            onPick = { examDate = it; showExamPicker = false }
        )
    }
    var duration by remember { mutableStateOf(120) }
    var location by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加考试") },
        text = {
            Column(
                Modifier.height(340.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("考试名称 *") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Text("所属课程", style = MaterialTheme.typography.labelMedium)
                CoursePicker(courses, courseId) { courseId = it }
                Text("考试时间", style = MaterialTheme.typography.labelMedium)
                OutlinedButton(
                    onClick = { showExamPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(DateUtils.formatDay(examDate)) }
                OutlinedTextField(value = duration.toString(),
                    onValueChange = { duration = it.toIntOrNull() ?: 120 },
                    label = { Text("考试时长（分钟）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = location, onValueChange = { location = it },
                    label = { Text("考试地点") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = content, onValueChange = { content = it },
                    label = { Text("考试内容与范围") }, modifier = Modifier.fillMaxWidth())
                error?.let { msg ->
                    Text(msg, color = MaterialTheme.colorScheme.error)
                    WarnDialog(msg) { error = null }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) error = "请填写考试名称"
                else {
                    vm.saveExam(
                        Exam(courseId = courseId, name = name,
                            examDate = System.currentTimeMillis() +
                                examDate - System.currentTimeMillis(),
                            durationMinutes = duration, location = location, content = content)
                    )
                    onDismiss()
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SemesterDialog(vm: StudyViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var totalWeeks by remember { mutableStateOf(18) }
    var startDate by remember { mutableStateOf(WeekCalc.mondayOf(System.currentTimeMillis())) }
    // 用户实际点选的那一天。startDate 会被自动对齐到周一（课表按周算），
    // 单独留一份原始选择，免得用户以为"我选的开学日期被改掉了"。
    var pickedDate by remember { mutableStateOf<Long?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }

    // 删除学期（删旧的、重新导入课程用）
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val semesters by vm.semesters.collectAsState()
    var pendingDelete by remember { mutableStateOf<com.liuxue.assistant.data.study.Semester?>(null) }
    var deleteText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("学期设置") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("学期名称") },
                    placeholder = { Text("如：2026 春季学期") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = totalWeeks.toString(),
                    onValueChange = { totalWeeks = it.toIntOrNull() ?: 18 },
                    label = { Text("总周数") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Text("开学日期", style = MaterialTheme.typography.labelMedium)
                // 点开日历选择；选完自动对齐到该周周一
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.DateRange, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(DateUtils.formatDay(pickedDate ?: startDate), style = MaterialTheme.typography.titleSmall)
                }
                val picked = pickedDate
                Text(
                    if (picked != null && picked != startDate)
                        "你选的 " + DateUtils.formatDay(picked) +
                            "（" + CourseSchedule.weekdayLabel(WeekCalc.weekdayOf(picked)) + "）" +
                            " → 第 1 周起点 " + DateUtils.formatDay(startDate) + "（周一）"
                    else
                        "第 1 周起点：" + DateUtils.formatDay(startDate) + "（周一）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "课表按周计算，所以会把开学那一周对齐到周一；你选的日期本身不会被改动。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // ---------- 已有学期：可以删除（进入下学期后删旧的、重新导入课程） ----------
                if (semesters.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(top = 6.dp))
                    Text("已有学期", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "进入下学期后，可以在这里把上一个学期删掉再重新导入课程。删除不可恢复。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    semesters.forEach { sem ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    sem.name + if (sem.isActive) "（当前）" else "",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    DateUtils.formatDay(sem.startDate) + " 起 · 共 " + sem.totalWeeks + " 周",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = {
                                pendingDelete = sem
                                deleteText = null     // 先算影响面，算完再显示
                                scope.launch {
                                    val imp = vm.semesterImpact(sem)
                                    deleteText = "确认删除学期「" + sem.name + "」？\n\n" +
                                        (if (imp != null && (imp.courses > 0 || imp.lessons > 0 || imp.materials > 0))
                                            "会同时删除 " + imp.courses + " 门课程、" + imp.lessons +
                                                " 节课、" + imp.materials + " 份资料（含已加密的文件）。"
                                        else "这个学期还没有课程数据。") +
                                        "\n\n删除后无法恢复。"
                                }
                            }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.saveSemester(name, startDate, totalWeeks)
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    // 删除学期：二次确认（列出会连带删掉什么）
    pendingDelete?.let { target ->
        ConfirmDeleteDialog(
            text = deleteText ?: ("确认删除学期「" + target.name + "」？删除后无法恢复。"),
            onConfirm = {
                pendingDelete = null
                vm.deleteSemester(target) { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { pendingDelete = null }
        )
    }

    // 日历选择开学日期
    if (showDatePicker) {
        val pickerState = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = pickedDate ?: startDate
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { sel ->
                        pickedDate = sel                      // 记住原始选择
                        startDate = WeekCalc.mondayOf(sel)    // 第 1 周起点对齐到周一
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            }
        ) {
            androidx.compose.material3.DatePicker(state = pickerState)
        }
    }
}

// ==================== 导入分享的作业 ====================

/**
 * 粘贴导入对话框。
 * 需求里"在其他程序拥有者点击并确认后直接录入"——这里的「导入」按钮就是那次确认。
 */
@Composable
private fun ImportHomeworkDialog(
    vm: StudyViewModel,
    prefill: String,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(prefill) }
    var payload by remember { mutableStateOf(if (prefill.isNotBlank()) HomeworkShare.parse(prefill) else null) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入分享的作业") },
        text = {
            Column(
                Modifier.height(340.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "把别人分享给你的那行 LXHW1. 开头的短码粘贴进来；" +
                        "如果是在别的 App 里直接点开的链接，这里会自动填好。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        payload = HomeworkShare.parse(it)
                        error = null
                    },
                    label = { Text("粘贴分享内容") },
                    placeholder = { Text("LXHW1.xxxxx") },
                    modifier = Modifier.fillMaxWidth()
                )
                val p = payload
                if (p != null) {
                    Text("识别结果", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    Text("标题：" + p.title, style = MaterialTheme.typography.bodyMedium)
                    if (p.courseName.isNotBlank()) {
                        Text("课程：" + p.courseName, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (p.dueDate != null) {
                        Text("截止：" + DateUtils.formatDay(p.dueDate),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    if (p.content.isNotBlank()) {
                        Text("内容：" + p.content, style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (text.isNotBlank()) {
                    Text("还识别不出内容，请确认粘贴的是完整分享文本",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                error?.let { msg ->
                    Text(msg, color = MaterialTheme.colorScheme.error)
                    WarnDialog(msg) { error = null }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val p = payload
                    if (p == null) error = "没有识别到有效的作业内容"
                    else {
                        vm.importSharedHomework(p)
                        onDismiss()
                    }
                },
                enabled = payload != null
            ) { Text("确认导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ==================== 课件与笔记 ====================

@Composable
private fun MaterialsDialog(
    vm: StudyViewModel,
    courseId: Long,
    courses: List<Course>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    // 只订阅「这门课」的资料。
    // 以前用的是 vm.materials（跟着 openMaterials() 那个全局"当前查看课程"走）——
    // 从课程详情打开弹窗时没人设置它，于是列表恒空、导入完也看不到刚加的条目。
    val list by remember(courseId) { vm.materialsFlow(courseId) }
        .collectAsState(initial = emptyList())
    var kind by remember { mutableStateOf(com.liuxue.assistant.data.study.CourseMaterial.KIND_SLIDE) }
    val course = courses.firstOrNull { it.id == courseId }
    var viewerBytes by remember { mutableStateOf<ByteArray?>(null) }
    var viewerTitle by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<CourseMaterial?>(null) }

    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            var name = "material_" + System.currentTimeMillis()
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) c.getString(i)?.let { name = it }
                }
            }
            vm.addMaterial(courseId, uri, name, mime, kind) { _, msg ->
                // 就地 Toast：主界面的 Snackbar 会被这个弹窗挡住，用户看不见
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text((course?.name ?: "课程") + " · 资料") },
        text = {
            Column(
                Modifier.height(400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "课件、讲义、笔记、作业文件都加密保存在本机（AES-256），可单独分享出去。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("导入类型", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(com.liuxue.assistant.data.study.CourseMaterial.allKinds) { k ->
                        FilterChip(
                            selected = kind == k,
                            onClick = { kind = k },
                            label = {
                                Text(
                                    com.liuxue.assistant.data.study.CourseMaterial.kindLabel(k),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }
                Button(
                    onClick = { pick.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("选择文件（可多选）")
                }
                if (list.isEmpty()) {
                    Text("这门课还没有资料",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    list.forEach { m ->
                        val isImage = m.mime.startsWith("image/")
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    if (isImage) {
                                        vm.loadMaterialBytes(m) { b ->
                                            if (b != null) {
                                                viewerBytes = b
                                                viewerTitle = m.name
                                            }
                                        }
                                    } else {
                                        ShareUtils.openStream(context, m.path, m.name, m.mime)
                                    }
                                }
                        ) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                ThumbFromBytes(
                                    key = "mat_" + m.id,
                                    mime = m.mime,
                                    name = m.name,
                                    loader = { cb -> vm.loadMaterialBytes(m) { cb(it) } }
                                )
                                Spacer(Modifier.size(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(m.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        com.liuxue.assistant.data.study.CourseMaterial.kindLabel(m.kind) +
                                            " · " + formatBytes(m.sizeBytes) + " · 已加密",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = {
                                    ShareUtils.shareStream(context, m.path, m.name, m.mime)
                                }) {
                                    Icon(Icons.Filled.Share, contentDescription = "分享")
                                }
                                IconButton(onClick = { pendingDelete = m }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )

    pendingDelete?.let { m ->
        ConfirmDeleteDialog(
            text = "删除资料「" + m.name + "」？文件会一起删。",
            onConfirm = {
                vm.deleteMaterial(m) { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { pendingDelete = null }
        )
    }
    viewerBytes?.let { bytes ->
        ZoomableImageBytesDialog(bytes, viewerTitle) { viewerBytes = null }
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    val fixed = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://" + url
    runCatching {
        context.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(fixed)
            )
        )
    }
}

/**
 * 加密文件的图片缩略图：先解密再解码，非图片/解码失败显示 emoji 占位。
 * loader 里用 VM 的解密回调（io 线程），只在进入组合时加载一次。
 */
@Composable
private fun ThumbFromBytes(
    key: Any,
    mime: String,
    name: String = "",
    loader: ((ByteArray?) -> Unit) -> Unit,
    size: Dp = 44.dp
) {
    var bmp by remember(key) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(key) {
        if (mime.startsWith("image/")) {
            loader { bytes ->
                bmp = bytes?.let { com.liuxue.assistant.ui.decodeThumbBytes(it) }
            }
        }
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size)
            )
        } else {
            Text(com.liuxue.assistant.ui.fileEmoji(mime, name),
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}


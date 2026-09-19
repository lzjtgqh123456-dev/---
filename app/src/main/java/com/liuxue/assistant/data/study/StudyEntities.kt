package com.liuxue.assistant.data.study

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 课程类型 */
object ClassType {
    const val LECTURE = "lecture"      // 讲座 / 大课
    const val LARGE = "large"          // 大班
    const val SMALL = "small"          // 小班
    const val LAB = "lab"              // 实验/实践
    const val SEMINAR = "seminar"      // 研讨
    const val OTHER = "other"

    fun label(key: String): String = when (key) {
        LECTURE -> "讲座"
        LARGE -> "大班"
        SMALL -> "小班"
        LAB -> "实验"
        SEMINAR -> "研讨"
        else -> "其他"
    }

    val all = listOf(LECTURE, LARGE, SMALL, LAB, SEMINAR, OTHER)
}

/** 学期设置：用于把"第几周"算出来 */
@Entity(tableName = "semester")
data class Semester(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    /** 第 1 周周一 00:00 的时间戳 */
    val startDate: Long,
    /** 学期总周数 */
    val totalWeeks: Int = 18,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

/** 课程 */
@Entity(tableName = "course", indices = [Index("semesterId")])
data class Course(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val semesterId: Long = 0L,
    /** 课程名称 */
    val name: String,
    /** 具体班级，如"俄语 2101 班" */
    val className: String = "",
    /** 任课教师 */
    val teacher: String = "",
    /** 课程类型：讲座/大班/小班/实验… */
    val classType: String = ClassType.LARGE,
    /** 学分 */
    val credits: Double = 0.0,
    /** 课程内容简介 */
    val content: String = "",
    /** 网课/课程链接（可自定义，留空则不显示） */
    val link: String = "",
    /** 显示颜色（十六进制） */
    val colorHex: String = "#1E6F5C",
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/** 上课时间：一门课可以有多个时间段 */
@Entity(tableName = "course_schedule", indices = [Index("courseId")])
data class CourseSchedule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val courseId: Long,
    /** 星期几：1=周一 … 7=周日 */
    val weekday: Int,
    /** 开始/结束时间，格式 "HH:mm" */
    val startTime: String,
    val endTime: String,
    val location: String = "",
    /** 生效周范围（含） */
    val weekFrom: Int = 1,
    val weekTo: Int = 18,
    /** 周次过滤：all=每周 odd=单周 even=双周 */
    val parity: String = PARITY_ALL,
    /** 是否提醒 */
    val remind: Boolean = true,
    /** 提前多少分钟提醒 */
    val remindMinutesBefore: Int = 15
) {
    companion object {
        const val PARITY_ALL = "all"
        const val PARITY_ODD = "odd"
        const val PARITY_EVEN = "even"

        fun weekdayLabel(w: Int): String = when (w) {
            1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
            5 -> "周五"; 6 -> "周六"; 7 -> "周日"; else -> "?"
        }
    }
}

/** 作业 / 任务 */
@Entity(tableName = "homework", indices = [Index("courseId"), Index("dueDate")])
data class Homework(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val courseId: Long = 0L,
    val title: String,
    /** 具体内容（可随时补充修改） */
    val content: String = "",
    val assignedDate: Long? = null,
    val dueDate: Long? = null,
    /** todo=待完成 doing=进行中 done=已完成 */
    val status: String = STATUS_TODO,
    /** 提前多少天提醒 */
    val remindDaysBefore: Int = 1,
    /**
     * 外部来源标记：从别人分享的作业导入时记录，
     * 便于"由作业拥有者确认后直接录入"的流程追踪。
     */
    val sharedFrom: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_TODO = "todo"
        const val STATUS_DOING = "doing"
        const val STATUS_DONE = "done"

        fun statusLabel(s: String): String = when (s) {
            STATUS_DOING -> "进行中"
            STATUS_DONE -> "已完成"
            else -> "待完成"
        }
    }
}

/** 考试 */
@Entity(tableName = "exam", indices = [Index("courseId")])
data class Exam(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val courseId: Long = 0L,
    val name: String,
    /** 考试时间 */
    val examDate: Long,
    val durationMinutes: Int = 120,
    val location: String = "",
    /** 考试范围与内容 */
    val content: String = "",
    /** 提前多少天提醒 */
    val remindDaysBefore: Int = 7,
    val createdAt: Long = System.currentTimeMillis()
)

/** 课件与笔记，按课程归档 */
@Entity(tableName = "course_material", indices = [Index("courseId")])
data class CourseMaterial(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val courseId: Long,
    val name: String,
    /** 加密文件相对路径（与证件模块共用加密存储） */
    val path: String = "",
    /** slide=课件 handout=讲义 note=笔记 homework=作业文件 other=其他 */
    val kind: String = KIND_SLIDE,
    val mime: String = "application/octet-stream",
    val sizeBytes: Long = 0L,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val KIND_SLIDE = "slide"
        const val KIND_HANDOUT = "handout"
        const val KIND_NOTE = "note"
        const val KIND_HOMEWORK = "homework"
        const val KIND_OTHER = "other"

        fun kindLabel(k: String): String = when (k) {
            KIND_SLIDE -> "课件"
            KIND_HANDOUT -> "讲义"
            KIND_NOTE -> "笔记"
            KIND_HOMEWORK -> "作业文件"
            else -> "其他"
        }

        val allKinds = listOf(KIND_SLIDE, KIND_HANDOUT, KIND_NOTE, KIND_HOMEWORK, KIND_OTHER)
    }
}

/** 作业附件：加密保存，支持任意文件类型（图片可显示预览图） */
@Entity(tableName = "homework_attachment", indices = [Index("homeworkId")])
data class HomeworkAttachment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val homeworkId: Long,
    val name: String,
    /** 加密文件相对路径（与证件/资料共用 VaultStore） */
    val path: String,
    val mime: String = "application/octet-stream",
    val sizeBytes: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)

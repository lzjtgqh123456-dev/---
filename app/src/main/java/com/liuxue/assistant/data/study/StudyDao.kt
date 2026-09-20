package com.liuxue.assistant.data.study

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface StudyDao {

    // ---------- 学期 ----------

    @Query("SELECT * FROM semester ORDER BY isActive DESC, startDate DESC")
    fun observeSemesters(): Flow<List<Semester>>

    @Query("SELECT * FROM semester WHERE isActive = 1 ORDER BY startDate DESC LIMIT 1")
    suspend fun activeSemester(): Semester?

    @Query("SELECT * FROM semester WHERE id = :id")
    suspend fun semester(id: Long): Semester?

    @Query("UPDATE semester SET isActive = 0")
    suspend fun clearActiveFlags()

    @Transaction
    suspend fun upsertSemester(s: Semester): Long {
        if (s.isActive) clearActiveFlags()
        return if (s.id == 0L) insertSemester(s) else { updateSemester(s); s.id }
    }

    @Insert
    suspend fun insertSemester(s: Semester): Long

    @Update
    suspend fun updateSemester(s: Semester)

    @Delete
    suspend fun deleteSemester(s: Semester)

    @Query("SELECT * FROM semester ORDER BY startDate DESC")
    suspend fun allSemesters(): List<Semester>

    // ---------- 按学期批量删除（「删除学期」用：一次清干净，别留孤儿行） ----------

    @Query("DELETE FROM course_schedule WHERE courseId IN (SELECT id FROM course WHERE semesterId = :sid)")
    suspend fun deleteSchedulesOfSemester(sid: Long)

    @Query("DELETE FROM exam WHERE courseId IN (SELECT id FROM course WHERE semesterId = :sid)")
    suspend fun deleteExamsOfSemester(sid: Long)

    @Query(
        "DELETE FROM homework_attachment WHERE homeworkId IN (SELECT id FROM homework WHERE courseId IN (SELECT id FROM course WHERE semesterId = :sid))"
    )
    suspend fun deleteHomeworkAttachmentsOfSemester(sid: Long)

    @Query("DELETE FROM homework WHERE courseId IN (SELECT id FROM course WHERE semesterId = :sid)")
    suspend fun deleteHomeworkOfSemester(sid: Long)

    @Query("DELETE FROM course_material WHERE courseId IN (SELECT id FROM course WHERE semesterId = :sid)")
    suspend fun deleteMaterialsOfSemester(sid: Long)

    @Query("DELETE FROM course WHERE semesterId = :sid")
    suspend fun deleteCoursesOfSemester(sid: Long)

    // ---------- 课程 ----------

    @Query("SELECT * FROM course WHERE archived = 0 ORDER BY name")
    fun observeCourses(): Flow<List<Course>>

    @Query("SELECT * FROM course WHERE semesterId = :sid AND archived = 0 ORDER BY name")
    fun observeCoursesOfSemester(sid: Long): Flow<List<Course>>

    @Query("SELECT * FROM course WHERE id = :id")
    suspend fun course(id: Long): Course?

    @Query("SELECT * FROM course WHERE archived = 0 ORDER BY name")
    suspend fun allCourses(): List<Course>

    @Insert
    suspend fun insertCourse(c: Course): Long

    @Update
    suspend fun updateCourse(c: Course)

    @Delete
    suspend fun deleteCourse(c: Course)

    // ---------- 上课时间 ----------

    @Query("SELECT * FROM course_schedule ORDER BY weekday, startTime")
    fun observeAllSchedules(): Flow<List<CourseSchedule>>

    @Query("SELECT * FROM course_schedule WHERE courseId = :courseId ORDER BY weekday, startTime")
    fun observeSchedulesOf(courseId: Long): Flow<List<CourseSchedule>>

    @Query("SELECT * FROM course_schedule WHERE courseId = :courseId ORDER BY weekday, startTime")
    suspend fun schedulesOf(courseId: Long): List<CourseSchedule>

    @Query("SELECT * FROM course_schedule")
    suspend fun allSchedules(): List<CourseSchedule>

    /** 某天的课（按开始时间排序） */
    @Query("SELECT * FROM course_schedule WHERE weekday = :weekday ORDER BY startTime")
    suspend fun schedulesOnWeekday(weekday: Int): List<CourseSchedule>

    @Insert
    suspend fun insertSchedule(s: CourseSchedule): Long

    @Update
    suspend fun updateSchedule(s: CourseSchedule)

    @Delete
    suspend fun deleteSchedule(s: CourseSchedule)

    @Query("DELETE FROM course_schedule WHERE courseId = :courseId")
    suspend fun deleteSchedulesOf(courseId: Long)

    // ---------- 作业 ----------

    @Query("SELECT * FROM homework ORDER BY (status = 'done'), COALESCE(dueDate, 9223372036854775807)")
    fun observeHomework(): Flow<List<Homework>>

    @Query("SELECT * FROM homework WHERE courseId = :courseId ORDER BY COALESCE(dueDate, 9223372036854775807)")
    fun observeHomeworkOf(courseId: Long): Flow<List<Homework>>

    @Query("SELECT * FROM homework WHERE courseId = :courseId ORDER BY COALESCE(dueDate, 9223372036854775807)")
    suspend fun homeworkOf(courseId: Long): List<Homework>

    @Query("SELECT * FROM homework WHERE id = :id")
    suspend fun homework(id: Long): Homework?

    @Query("SELECT * FROM homework WHERE status != 'done'")
    suspend fun openHomework(): List<Homework>

    @Query("SELECT * FROM homework")
    suspend fun allHomework(): List<Homework>

    @Insert
    suspend fun insertHomework(h: Homework): Long

    @Update
    suspend fun updateHomework(h: Homework)

    @Delete
    suspend fun deleteHomework(h: Homework)

    @Query("UPDATE homework SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun setHomeworkStatus(id: Long, status: String, now: Long = System.currentTimeMillis())

    // ---------- 考试 ----------

    @Query("SELECT * FROM exam ORDER BY examDate")
    fun observeExams(): Flow<List<Exam>>

    @Query("SELECT * FROM exam WHERE courseId = :courseId ORDER BY examDate")
    fun observeExamsOf(courseId: Long): Flow<List<Exam>>

    @Query("SELECT * FROM exam ORDER BY examDate")
    suspend fun allExams(): List<Exam>

    @Insert
    suspend fun insertExam(e: Exam): Long

    @Update
    suspend fun updateExam(e: Exam)

    @Delete
    suspend fun deleteExam(e: Exam)

    // ---------- 课件笔记 ----------

    @Query("SELECT * FROM course_material ORDER BY createdAt DESC")
    fun observeMaterials(): Flow<List<CourseMaterial>>

    @Query("SELECT * FROM course_material WHERE courseId = :courseId ORDER BY createdAt DESC")
    fun observeMaterialsOf(courseId: Long): Flow<List<CourseMaterial>>

    @Query("SELECT * FROM course_material WHERE courseId = :courseId ORDER BY createdAt DESC")
    suspend fun materialsOf(courseId: Long): List<CourseMaterial>

    @Query("SELECT * FROM course_material WHERE id = :id")
    suspend fun material(id: Long): CourseMaterial?

    @Query("SELECT * FROM course_material")
    suspend fun allMaterials(): List<CourseMaterial>

    @Insert
    suspend fun insertMaterial(m: CourseMaterial): Long

    @Update
    suspend fun updateMaterial(m: CourseMaterial)

    @Delete
    suspend fun deleteMaterial(m: CourseMaterial)

    // ---------- 作业附件 ----------

    @Query("SELECT * FROM homework_attachment WHERE homeworkId = :homeworkId ORDER BY createdAt")
    fun observeHomeworkAttachments(homeworkId: Long): Flow<List<HomeworkAttachment>>

    @Query("SELECT * FROM homework_attachment ORDER BY createdAt")
    fun observeAllHomeworkAttachments(): Flow<List<HomeworkAttachment>>

    @Query("SELECT * FROM homework_attachment WHERE homeworkId = :homeworkId ORDER BY createdAt")
    suspend fun homeworkAttachments(homeworkId: Long): List<HomeworkAttachment>

    @Query("SELECT * FROM homework_attachment ORDER BY createdAt")
    suspend fun allHomeworkAttachments(): List<HomeworkAttachment>

    @Insert
    suspend fun insertHomeworkAttachment(a: HomeworkAttachment): Long

    @Delete
    suspend fun deleteHomeworkAttachment(a: HomeworkAttachment)
}

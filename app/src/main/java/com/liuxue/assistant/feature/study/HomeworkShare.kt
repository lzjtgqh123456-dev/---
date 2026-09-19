package com.liuxue.assistant.feature.study

import android.util.Base64
import com.liuxue.assistant.data.study.Homework
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 作业分享与回流录入。
 *
 * 分享出去的文本形如：
 *   【留学助手-乐】作业：阅读理解 P32
 *   课程：俄语精读 ｜ 截止：2026-03-15
 *   内容：完成第 3 章课后练习
 *   ——————
 *   点击导入：liuxue://homework?d=xxxx
 *   或在 App 内「导入分享的作业」粘贴下面这行：
 *   LXHW1.xxxx
 *
 * 设计考虑：
 * - **微信里自定义 scheme 常被拦截**，所以同时提供 `LXHW1.` 开头的短码，
 *   收件人在 App 内粘贴即可导入——这条路在所有 App 里都可靠。
 * - App 内导入必须由用户**点击确认**才写入（需求里的"拥有者点击并确认后直接录入"）。
 */
object HomeworkShare {

    const val SCHEME = "liuxue"
    const val HOST = "homework"
    const val CODE_PREFIX = "LXHW1."
    const val DEEP_LINK_PREFIX = "$SCHEME://$HOST?d="

    /** 结构化载荷 */
    data class Payload(
        val title: String,
        val content: String,
        val courseName: String,
        val dueDate: Long?,
        val remindDaysBefore: Int,
        val sender: String = ""
    )

    /** 把作业编码成短码（不含前缀逻辑，返回 payload 串） */
    private fun encodePayload(p: Payload): String {
        val o = JSONObject().apply {
            put("t", p.title)
            put("c", p.content)
            put("n", p.courseName)
            put("d", p.dueDate ?: 0L)
            put("r", p.remindDaysBefore)
            if (p.sender.isNotBlank()) put("s", p.sender)
        }
        val b64 = Base64.encodeToString(
            o.toString().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return b64
    }

    /** 短码（用户在 App 内粘贴用） */
    fun shareCode(p: Payload): String = CODE_PREFIX + encodePayload(p)

    /** 深链（其它 App 里点击可唤起） */
    fun deepLink(p: Payload): String = DEEP_LINK_PREFIX +
        URLEncoder.encode(encodePayload(p), "UTF-8")

    /** 完整的分享文本 */
    fun shareText(p: Payload): String = buildString {
        append("【留学助手-乐】作业\n")
        append("课程：").append(p.courseName.ifBlank { "未指定" }).append("\n")
        if (p.dueDate != null && p.dueDate > 0) {
            append("截止：")
                .append(com.liuxue.assistant.util.DateUtils.formatDay(p.dueDate))
                .append("\n")
        }
        append("标题：").append(p.title).append("\n")
        if (p.content.isNotBlank()) append("内容：").append(p.content).append("\n")
        if (p.sender.isNotBlank()) append("分享者：").append(p.sender).append("\n")
        append("\n—————\n")
        append("在「留学助手-乐」里粘贴下面这行即可导入：\n")
        append(shareCode(p))
    }

    /**
     * 从任意文本中解析作业：
     * 支持直接粘贴短码、粘贴深链、或粘贴整段分享文本。
     * 解析失败返回 null。
     */
    fun parse(text: String): Payload? {
        val raw = text.trim()
        if (raw.isEmpty()) return null

        val b64 = when {
            // 纯短码
            raw.startsWith(CODE_PREFIX) -> raw.removePrefix(CODE_PREFIX).trim()
            // 深链
            raw.contains(DEEP_LINK_PREFIX) -> {
                val seg = raw.substringAfter(DEEP_LINK_PREFIX)
                    .substringBefore('&').substringBefore(' ').substringBefore('\n').trim()
                runCatching { URLDecoder.decode(seg, "UTF-8") }.getOrDefault(seg)
            }
            // 整段文本里夹带短码
            raw.contains(CODE_PREFIX) -> {
                val start = raw.indexOf(CODE_PREFIX) + CODE_PREFIX.length
                raw.substring(start).trim()
                    .substringBefore(' ').substringBefore('\n').substringBefore('\r').trim()
            }
            else -> return null
        }
        if (b64.isBlank()) return null

        return runCatching {
            val pad = when (b64.length % 4) {
                2 -> "=="
                3 -> "="
                else -> ""
            }
            val json = String(
                Base64.decode(b64 + pad, Base64.URL_SAFE or Base64.NO_WRAP),
                Charsets.UTF_8
            )
            val o = JSONObject(json)
            Payload(
                title = o.optString("t"),
                content = o.optString("c"),
                courseName = o.optString("n"),
                dueDate = o.optLong("d", 0L).takeIf { it > 0 },
                remindDaysBefore = o.optInt("r", 1),
                sender = o.optString("s")
            )
        }.getOrNull()
    }

    /** 把解析结果转成待保存的作业（课程按名称匹配，匹配不到则归入未指定） */
    fun toHomework(p: Payload, courseId: Long): Homework = Homework(
        courseId = courseId,
        title = p.title,
        content = p.content,
        dueDate = p.dueDate,
        remindDaysBefore = p.remindDaysBefore,
        sharedFrom = p.sender.ifBlank { "外部分享" }
    )

    /**
     * 待处理的分享内容：MainActivity 收到深链后放进来，StudyScreen 取走并弹确认框。
     * 用单例而不是导航参数，避免为一个简单场景引入复杂的参数传递。
     */
    object Pending {
        @Volatile var text: String? = null
        fun take(): String? {
            val v = text
            text = null
            return v
        }
    }
}

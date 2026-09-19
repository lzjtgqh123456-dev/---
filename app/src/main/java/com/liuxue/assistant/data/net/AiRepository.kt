package com.liuxue.assistant.data.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 一条聊天消息 */
data class ChatMessage(val role: String, val content: String)

/**
 * 聊天附件。
 *
 * - 图片：[base64] 存图片字节的 base64（不含 data URI 前缀），以 OpenAI 视觉消息（image_url）发出去，
 *   需要模型支持视觉（如 deepseek-flash、gpt-4o、qwen-vl）；不支持视觉的模型会返回报错，界面会原样显示。
 * - 文本文件：解析出的正文放 [text]，拼进用户消息。
 * - 其它类型：[note] 里写一句说明（只把文件名/大小告诉模型）。
 */
data class AiAttachment(
    val name: String,
    val mime: String = "",
    val base64: String = "",
    val text: String = "",
    val note: String = ""
) {
    val isImage: Boolean get() = base64.isNotBlank()
}

/** AI 预设：可自定义的"预设要求"，用来固定常用场景的系统提示词 */
data class AiPreset(
    val id: Long,
    val name: String,
    val systemPrompt: String,
    val builtin: Boolean = false
)

/**
 * AI 辅助学习（OpenAI 兼容协议）。
 *
 * 为什么走 OpenAI 兼容格式：绝大多数中转站、以及 OpenAI / DeepSeek / 通义 /
 * 智谱 / Moonshot / 本地 Ollama 都提供这个接口，用户只填 BaseURL + Key + 模型名
 * 就能用，不需要我逐个适配厂商 SDK。
 *
 * 隐私提示：走自定义 API 时，对话内容会发送到用户自己配置的服务端；
 * 这一点在设置页里明确写出，不与"离线词典/离线翻译"混淆。
 */
class AiRepository(private val context: Context, private val config: NetConfig) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)   // 大模型出字慢，读超时给足
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /**
     * 发起一次对话。
     * @param history 历史消息（不含 system）
     * @param presetSystemPrompt 预设要求；为空则用通用助手提示
     */
    suspend fun chat(
        userText: String,
        history: List<ChatMessage> = emptyList(),
        presetSystemPrompt: String = "",
        /** 深度思考：更长的系统提示 + 更低温度 + 更大输出预算 */
        deepThink: Boolean = false,
        /** 测试连接时用：忽略"AI 总开关"，只验证 BaseURL/Key/模型能不能通 */
        ignoreEnabled: Boolean = false,
        /** 附件（图片走视觉消息，文本拼进提问，其它只带文件名） */
        attachments: List<AiAttachment> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        if (ignoreEnabled && !config.isAiConfigured()) {
            throw IllegalStateException("请先填 BaseURL 与模型名")
        }
        if (!ignoreEnabled && !config.isAiReady()) {
            throw IllegalStateException("AI 还没配置好，请到「设置」里填 BaseURL、API Key 与模型名")
        }

        val messages = JSONArray()
        val sys = buildString {
            append(presetSystemPrompt.ifBlank { DEFAULT_SYSTEM })
            if (deepThink) append(DEEP_THINK_SUFFIX)
        }
        messages.put(JSONObject().put("role", "system").put("content", sys))
        history.forEach { h ->
            messages.put(JSONObject().put("role", h.role).put("content", h.content))
        }
        // 文本附件拼进提问；其它类型只留一句说明
        val textParts = StringBuilder(userText)
        attachments.filter { !it.isImage }.forEach { a ->
            if (a.text.isNotBlank()) {
                textParts.append("\n\n【附件：").append(a.name).append("】\n").append(a.text)
            } else if (a.note.isNotBlank()) {
                textParts.append("\n\n（附件：").append(a.name).append("，").append(a.note).append("）")
            }
        }
        val images = attachments.filter { it.isImage }
        val userContent: Any = if (images.isEmpty()) {
            textParts.toString()
        } else {
            // OpenAI 视觉格式：content 为数组，text + 若干 image_url(data URI)
            JSONArray().apply {
                put(JSONObject().put("type", "text").put("text", textParts.toString()))
                images.forEach { img ->
                    val mime = img.mime.ifBlank { "image/jpeg" }
                    put(
                        JSONObject().put("type", "image_url").put(
                            "image_url",
                            JSONObject().put("url", "data:" + mime + ";base64," + img.base64)
                        )
                    )
                }
            }
        }
        messages.put(JSONObject().put("role", "user").put("content", userContent))

        val body = JSONObject()
            .put("model", config.aiModel)
            .put("messages", messages)
            .put("temperature", if (deepThink) 0.3 else 0.7)
            .put("stream", false)
            .apply { if (deepThink) put("max_tokens", 4096) }
        // 附加请求参数：服务商自带的联网检索等（原样合并，覆盖同名键）
        val extra = config.aiExtraJson
        if (extra.isNotBlank()) {
            val ex = try {
                JSONObject(extra)
            } catch (e: Exception) {
                throw IllegalStateException("「附加请求参数」不是合法 JSON：" + (e.message ?: ""))
            }
            ex.keys().forEach { k -> body.put(k, ex.get(k)) }
        }
        val bodyText = body.toString()

        val builder = Request.Builder()
            .url(config.aiEndpoint())
            .post(bodyText.toRequestBody(jsonType))
            .header("Content-Type", "application/json")
        if (config.aiSendAuth && config.aiApiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer " + config.aiApiKey)
        }

        http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP " + resp.code + "：" + extractError(text))
            }
            // 标准响应：choices[0].message.content
            val content = runCatching {
                JSONObject(text).getJSONArray("choices")
                    .getJSONObject(0).getJSONObject("message").getString("content")
            }.getOrNull()
            if (content.isNullOrBlank()) {
                throw IllegalStateException("返回内容为空，请检查模型名是否正确。原始响应：" + text.take(200))
            }
            content
        }
    }

    private fun extractError(text: String): String = runCatching {
        JSONObject(text).optJSONObject("error")?.optString("message")
            ?: JSONObject(text).optString("message")
    }.getOrNull()?.take(200) ?: text.take(200)

    /** 用当前配置做一次连通性测试 */
    suspend fun test(): String = runCatching {
        chat("请只回复两个字：正常", presetSystemPrompt = "你是一个测试助手。", ignoreEnabled = true)
    }.fold(
        { reply ->
            val head = "✅ 连接正常，" + config.aiModel + " 回复：" + reply.take(50).trim()
            if (config.aiEnabled) head
            else head + System.lineSeparator() + "⚠️ 但「AI 辅助学习」总开关是关着的，提问前请到设置里打开。"
        },
        { e -> "❌ 连接失败：" + (e.message ?: "未知错误") }
    )

    companion object {
        /** 深度思考时追加到系统提示 */
        const val DEEP_THINK_SUFFIX =
            " 请先分步推理（把关键中间步骤写出来），再给出结论；内容较多时用清晰的小标题分点，不要省略推理过程。"

        const val DEFAULT_SYSTEM =
            "你是留学生的学习助手，用中文回答。回答要准确、简洁、分点。涉及俄语时给出重音与变格说明。"

        /** 内置预设：用户可直接用，也可自己加 */
        fun builtinPresets(): List<AiPreset> = listOf(
            AiPreset(1, "通用学习助手",
                DEFAULT_SYSTEM, true),
            AiPreset(2, "俄语语法讲解",
                "你是俄语语法老师。用户会给出一句俄语或一个语法点，请：1) 逐词标注词性与格/数；" +
                    "2) 说明所用语法规则；3) 给出 2 个同类例句并附中文。用中文讲解。", true),
            AiPreset(3, "作文批改",
                "你是俄语写作老师。请检查用户给的俄语作文：1) 逐句指出语法/搭配错误并给正确写法；" +
                    "2) 评价用词与结构；3) 给出一段改写示范。用中文说明。", true),
            AiPreset(4, "翻译润色",
                "你是中俄翻译。用户给一段文字，请给出：1) 直译；2) 更自然的意译；" +
                    "3) 指出其中难译之处与理由。保持原意，不要添加内容。", true),
            AiPreset(5, "文献摘要",
                "你是学术阅读助手。用户给一段俄语/英语文献，请：1) 用中文概括要点；" +
                    "2) 列出关键术语及其中文对照；3) 指出结论与局限。", true),
            AiPreset(6, "出题练习",
                "你是出题老师。根据用户给的内容，出 5 道练习题（含答案与解析），" +
                    "难度由易到难，覆盖核心知识点。用中文出题。", true)
        )
    }
}

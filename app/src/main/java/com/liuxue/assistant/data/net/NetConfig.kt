package com.liuxue.assistant.data.net

import android.content.Context

/** 搜索引擎类型 */
enum class SearchEngine(
    val key: String,
    val label: String,
    /** 是否需要用户自己申请 API Key */
    val needsKey: Boolean,
    val note: String
) {
    DUCKDUCKGO(
        "duckduckgo", "DuckDuckGo", false,
        "免费、无需申请 Key。返回即时答案与相关条目，适合查概念、定义。"
    ),
    WIKIPEDIA(
        "wikipedia", "维基百科", false,
        "免费、无需 Key。适合查百科类、人物、地名、历史事件。"
    ),
    BING_CN(
        "bing_cn", "必应网页搜索（免 Key）", false,
        "抓取 www.bing.com 的搜索结果页并解析摘要，无需 Key、不用浏览器；实测可用。"
    ),
    GOOGLE_CSE(
        "google_cse", "Google 可编程搜索", true,
        "质量最好。需要在 Google Cloud 申请 CX（搜索引擎 ID）与 API Key，有免费额度（每天 100 次）。"
    ),
    BING(
        "bing", "Bing Web Search", true,
        "需要 Azure 订阅里的 Bing Search Key（有免费档）。"
    ),
    SERPER(
        "serper", "Serper.dev", true,
        "国内可直连、响应快。注册后免费 2500 次，Key 形如一串 40 位十六进制。"
    ),
    BRAVE(
        "brave", "Brave Search", true,
        "注重隐私，免费档每月 2000 次。"
    ),
    SEARXNG(
        "searxng", "自建 SearXNG", true,
        "填你自己的 SearXNG 实例地址（形如 https://your.host），隐私最好、无次数限制。"
    );

    companion object {
        fun fromKey(k: String): SearchEngine = entries.firstOrNull { it.key == k } ?: DUCKDUCKGO
    }
}

/**
 * 搜索 / AI / 汇率的配置。
 *
 * 设计原则（对应用户选择"尽可能全部支持"）：
 * - **默认零配置可用**：DuckDuckGo + 维基百科 + 汇率 三个都无需 Key，装上就能用
 * - **想更好就填 Key**：Google / Bing / Serper / Brave / 自建 SearXNG，设置页里有申请指引
 * - **AI 走 OpenAI 兼容协议**：绝大多数中转站都兼容，填 BaseURL + Key + 模型名即可
 */
class NetConfig(context: Context) {

    private val sp = context.getSharedPreferences("net_config", Context.MODE_PRIVATE)

    // ---------- 搜索引擎 ----------

    var enabledEngines: Set<String>
        get() = sp.getStringSet(KEY_ENGINES, setOf(
            SearchEngine.BING_CN.key,
            SearchEngine.WIKIPEDIA.key,
            SearchEngine.DUCKDUCKGO.key
        )) ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_ENGINES, v).apply()

    /** 用户在设置页里选的"主引擎"，空表示按启用的引擎自动聚合 */
    var primaryEngine: String
        get() = sp.getString(KEY_PRIMARY, "") ?: ""
        set(v) = sp.edit().putString(KEY_PRIMARY, v).apply()

    /** 只显示有 Key 的引擎时用；填了 Key 才会真正调用 */
    fun apiKey(engine: SearchEngine): String =
        sp.getString("key_" + engine.key, "") ?: ""

    fun setApiKey(engine: SearchEngine, v: String) {
        sp.edit().putString("key_" + engine.key, v.trim()).apply()
    }

    /** Google 还需要 CX（搜索引擎 ID） */
    var googleCx: String
        get() = sp.getString(KEY_GOOGLE_CX, "") ?: ""
        set(v) = sp.edit().putString(KEY_GOOGLE_CX, v.trim()).apply()

    /** SearXNG 实例地址 */
    var searxngUrl: String
        get() = sp.getString(KEY_SEARXNG_URL, "") ?: ""
        set(v) = sp.edit().putString(KEY_SEARXNG_URL, v.trim()).apply()

    /** 该引擎当前是否可用（免 Key 的恒可用；需 Key 的必须填了 Key） */
    fun isUsable(engine: SearchEngine): Boolean = when (engine) {
        SearchEngine.DUCKDUCKGO, SearchEngine.WIKIPEDIA, SearchEngine.BING_CN -> true
        SearchEngine.GOOGLE_CSE -> apiKey(engine).isNotBlank() && googleCx.isNotBlank()
        SearchEngine.SEARXNG -> searxngUrl.isNotBlank()
        else -> apiKey(engine).isNotBlank()
    }

    /** 实际参与查询的引擎列表 */
    fun activeEngines(): List<SearchEngine> =
        enabledEngines.map { SearchEngine.fromKey(it) }.filter { isUsable(it) }

    // ---------- 汇率 ----------

    /** 基准货币（默认人民币） */
    var baseCurrency: String
        get() = sp.getString(KEY_BASE_CCY, "CNY") ?: "CNY"
        set(v) = sp.edit().putString(KEY_BASE_CCY, v.uppercase()).apply()

    // ---------- AI ----------

    var aiEnabled: Boolean
        get() = sp.getBoolean(KEY_AI_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_AI_ENABLED, v).apply()

    /** OpenAI 兼容的 BaseURL，例如 https://api.openai.com/v1 或你的中转站地址 */
    var aiBaseUrl: String
        get() = sp.getString(KEY_AI_BASE, "") ?: ""
        set(v) = sp.edit().putString(KEY_AI_BASE, v.trim().trimEnd('/')).apply()

    var aiApiKey: String
        get() = sp.getString(KEY_AI_KEY, "") ?: ""
        set(v) = sp.edit().putString(KEY_AI_KEY, v.trim()).apply()

    var aiModel: String
        get() = sp.getString(KEY_AI_MODEL, "") ?: ""
        set(v) = sp.edit().putString(KEY_AI_MODEL, v.trim()).apply()

    /** 是否在请求里带 Authorization 头（个别本地服务如 Ollama 不需要） */
    var aiSendAuth: Boolean
        get() = sp.getBoolean(KEY_AI_AUTH, true)
        set(v) = sp.edit().putBoolean(KEY_AI_AUTH, v).apply()

    /** 三项是否填全（不看总开关）—— 用于"填好了但没开开关"这种提示 */
    /** 附加请求参数（原始 JSON，可选）：用于服务商自带的联网检索等，合并进请求体 */
    var aiExtraJson: String
        get() = sp.getString(KEY_AI_EXTRA, "") ?: ""
        set(v) = sp.edit().putString(KEY_AI_EXTRA, v.trim()).apply()

    fun isAiConfigured(): Boolean = aiBaseUrl.isNotBlank() && aiModel.isNotBlank()

    /** 真正可用于提问：既填好了，又打开了 AI 开关 */
    fun isAiReady(): Boolean = aiEnabled && isAiConfigured()

    /** 拼接完整请求地址，兼容用户填 /v1 或不填的情况 */
    fun aiEndpoint(): String {
        val b = aiBaseUrl
        return when {
            b.endsWith("/chat/completions") -> b
            b.endsWith("/v1") -> b + "/chat/completions"
            else -> b + "/v1/chat/completions"
        }
    }

    companion object {
        private const val KEY_ENGINES = "engines"
        private const val KEY_PRIMARY = "primary"
        private const val KEY_GOOGLE_CX = "google_cx"
        private const val KEY_SEARXNG_URL = "searxng_url"
        private const val KEY_BASE_CCY = "base_ccy"
        private const val KEY_AI_ENABLED = "ai_enabled"
        private const val KEY_AI_BASE = "ai_base"
        private const val KEY_AI_KEY = "ai_key"
        private const val KEY_AI_MODEL = "ai_model"
        private const val KEY_AI_AUTH = "ai_auth"
        private const val KEY_AI_EXTRA = "ai_extra_json"
    }
}

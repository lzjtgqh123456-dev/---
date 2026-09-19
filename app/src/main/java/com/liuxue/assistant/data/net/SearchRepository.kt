package com.liuxue.assistant.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** 一条搜索结果 */
data class SearchResult(
    val title: String,
    val snippet: String,
    val url: String,
    val source: String
)

/** 聚合结果：每个引擎的成功/失败都单独记录，方便用户知道哪个源没配好 */
data class SearchResponse(
    val results: List<SearchResult> = emptyList(),
    val errors: List<String> = emptyList(),
    val query: String = ""
)

/**
 * 多引擎搜索。
 *
 * 设计取舍：
 * - **免 Key 引擎优先**：DuckDuckGo 即时答案 + 维基百科，装上就能用，不需要任何申请
 * - **付费/申请型引擎作为增强**：填了 Key 才会调用，失败不影响其它引擎
 * - **并发查询 + 失败隔离**：各引擎并行跑，谁挂都不影响整体结果
 * - 每个引擎的响应格式差异很大，全部在这里归一化成 SearchResult
 */
class SearchRepository(private val config: NetConfig) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        // 国内环境里有些引擎（Google/Bing/Brave 等）根本连不上，
        // 超时给太长会让整个搜索卡住 —— 6/10 秒够用了，失败也很快就返回。
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> b.header(k, v) }
        http.newCall(b.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP " + resp.code + (if (body.isBlank()) "" else "：" + body.take(120)))
            }
            return body
        }
    }

    private fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): String {
        val b = Request.Builder().url(url)
            .post(json.toRequestBody(jsonType))
            .header("User-Agent", UA)
        headers.forEach { (k, v) -> b.header(k, v) }
        http.newCall(b.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP " + resp.code + (if (body.isBlank()) "" else "：" + body.take(120)))
            }
            return body
        }
    }

    // ==================== 聚合查询 ====================

    suspend fun search(query: String): SearchResponse = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext SearchResponse(query = q)

        val engines = config.activeEngines()
        if (engines.isEmpty()) {
            return@withContext SearchResponse(
                query = q,
                errors = listOf("没有可用的搜索引擎，请到设置里至少启用一个")
            )
        }

        val errors = mutableListOf<String>()
        val collected = coroutineScope {
            engines.map { engine ->
                async {
                    runCatching { engine.search(q) }
                        .fold(
                            onSuccess = { engine.label to it },
                            onFailure = { e ->
                                synchronized(errors) {
                                    errors.add(engine.label + "：" + (e.message ?: "请求失败"))
                                }
                                engine.label to emptyList()
                            }
                        )
                }
            }.awaitAll()
        }

        // 交错合并各引擎结果，避免某一家的结果霸占前面
        val merged = mutableListOf<SearchResult>()
        val lists = collected.map { it.second }
        var i = 0
        while (true) {
            var added = false
            lists.forEach { list ->
                list.getOrNull(i)?.let { merged.add(it); added = true }
            }
            if (!added) break
            i++
        }
        // 按 URL 去重
        val dedup = merged.distinctBy { it.url.ifBlank { it.title } }
        SearchResponse(results = dedup, errors = errors.toList(), query = q)
    }

    private suspend fun SearchEngine.search(q: String): List<SearchResult> = when (this) {
        SearchEngine.DUCKDUCKGO -> duckduckgo(q)
        SearchEngine.WIKIPEDIA -> wikipedia(q)
        SearchEngine.BING_CN -> bingHtml(q)
        SearchEngine.GOOGLE_CSE -> googleCse(q, config.googleCx, config.apiKey(this))
        SearchEngine.BING -> bing(q, config.apiKey(this))
        SearchEngine.SERPER -> serper(q, config.apiKey(this))
        SearchEngine.BRAVE -> brave(q, config.apiKey(this))
        SearchEngine.SEARXNG -> searxng(q, config.searxngUrl)
    }

    // ==================== 免 Key 引擎 ====================

    /** DuckDuckGo 即时答案：无需 Key，适合概念/定义 */
    private fun duckduckgo(q: String): List<SearchResult> {
        val url = "https://api.duckduckgo.com/?q=" + enc(q) +
            "&format=json&no_html=1&skip_disambig=1&no_redirect=1"
        val root = JSONObject(get(url))
        val out = mutableListOf<SearchResult>()

        val abstract = root.optString("AbstractText")
        if (abstract.isNotBlank()) {
            out.add(
                SearchResult(
                    title = root.optString("Heading").ifBlank { q },
                    snippet = abstract,
                    url = root.optString("AbstractURL"),
                    source = "DuckDuckGo 摘要"
                )
            )
        }
        // 相关主题只取一层，够用且避免递归展开
        val topics = root.optJSONArray("RelatedTopics") ?: JSONArray()
        for (i in 0 until topics.length()) {
            val t = topics.optJSONObject(i) ?: continue
            t.optJSONArray("Topics")?.let { sub ->
                for (j in 0 until sub.length()) {
                    sub.optJSONObject(j)?.let { addTopic(it, q, out) }
                }
            } ?: addTopic(t, q, out)
        }
        return out
    }

    private fun addTopic(t: JSONObject, q: String, out: MutableList<SearchResult>) {
        val text = t.optString("Text")
        if (text.isBlank()) return
        val icon = t.optJSONObject("Icon")?.optString("URL") ?: ""
        out.add(
            SearchResult(
                title = text.substringBefore(" - ").take(80),
                snippet = text,
                url = t.optString("FirstURL"),
                source = "DuckDuckGo 相关" + if (icon.isBlank()) "" else ""
            )
        )
    }

    /** 维基百科 opensearch：无需 Key，返回标题+摘要+链接 */
    private fun wikipedia(q: String): List<SearchResult> {
        val url = "https://zh.wikipedia.org/w/api.php?action=opensearch&format=json" +
            "&limit=8&namespace=0&search=" + enc(q)
        val arr = JSONArray(get(url))
        val titles = arr.optJSONArray(1) ?: JSONArray()
        val descs = arr.optJSONArray(2) ?: JSONArray()
        val urls = arr.optJSONArray(3) ?: JSONArray()
        val out = mutableListOf<SearchResult>()
        for (i in 0 until titles.length()) {
            out.add(
                SearchResult(
                    title = titles.optString(i),
                    snippet = descs.optString(i),
                    url = urls.optString(i),
                    source = "维基百科"
                )
            )
        }
        return out
    }

    /** 汇率也作为一种"结果"参与聚合，方便用户直接搜「美元 人民币」 */

    // ==================== 需要 Key 的引擎 ====================

    /**
     * 免 Key：抓必应搜索结果页（cn.bing.com）自己解析。
     * 不依赖任何浏览器：一次 HTTP GET + HTML 解析（比无头浏览器快得多，也省电）。
     */
    private fun bingHtml(q: String): List<SearchResult> {
        val html = get(
            "https://www.bing.com/search?q=" + enc(q) + "&count=10",
            mapOf(
                "User-Agent" to BROWSER_UA,
                "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
            )
        )
        val doc = org.jsoup.Jsoup.parse(html)
        val out = mutableListOf<SearchResult>()
        for (li in doc.select("li.b_algo")) {
            val a = li.selectFirst("h2 a") ?: continue
            val title = a.text().trim()
            if (title.isBlank()) continue
            val snippet = (li.selectFirst(".b_caption p") ?: li.selectFirst("p"))
                ?.text()?.trim().orEmpty()
            out.add(SearchResult(title, snippet, a.attr("href").trim(), "必应"))
            if (out.size >= 10) break
        }
        if (out.isEmpty()) throw IllegalStateException("结果页没有解析到条目（可能被拦或页面改版）")
        return out
    }

    private fun googleCse(q: String, cx: String, key: String): List<SearchResult> {
        val url = "https://www.googleapis.com/customsearch/v1?key=" + enc(key) +
            "&cx=" + enc(cx) + "&num=10&q=" + enc(q)
        val items = JSONObject(get(url)).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            SearchResult(
                title = o.optString("title"),
                snippet = o.optString("snippet"),
                url = o.optString("link"),
                source = "Google"
            )
        }
    }

    private fun bing(q: String, key: String): List<SearchResult> {
        val url = "https://api.bing.microsoft.com/v7.0/search?count=10&mkt=zh-CN&q=" + enc(q)
        val root = JSONObject(get(url, mapOf("Ocp-Apim-Subscription-Key" to key)))
        val values = root.optJSONObject("web")?.optJSONArray("value") ?: return emptyList()
        return (0 until values.length()).mapNotNull { i ->
            val o = values.optJSONObject(i) ?: return@mapNotNull null
            SearchResult(
                title = o.optString("name"),
                snippet = o.optString("snippet"),
                url = o.optString("url"),
                source = "Bing"
            )
        }
    }

    private fun serper(q: String, key: String): List<SearchResult> {
        val body = JSONObject().put("q", q).put("num", 10).toString()
        val root = JSONObject(
            postJson("https://google.serper.dev/search", body, mapOf("X-API-KEY" to key))
        )
        val arr = root.optJSONArray("organic") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            SearchResult(
                title = o.optString("title"),
                snippet = o.optString("snippet"),
                url = o.optString("link"),
                source = "Google(Serper)"
            )
        }
    }

    private fun brave(q: String, key: String): List<SearchResult> {
        val url = "https://api.search.brave.com/res/v1/web/search?count=10&q=" + enc(q)
        val root = JSONObject(
            get(url, mapOf("X-Subscription-Token" to key, "Accept" to "application/json"))
        )
        val arr = root.optJSONObject("web")?.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            SearchResult(
                title = o.optString("title"),
                snippet = o.optString("description"),
                url = o.optString("url"),
                source = "Brave"
            )
        }
    }

    private fun searxng(q: String, base: String): List<SearchResult> {
        val url = base.trimEnd('/') + "/search?format=json&q=" + enc(q)
        val arr = JSONObject(get(url)).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            SearchResult(
                title = o.optString("title"),
                snippet = o.optString("content"),
                url = o.optString("url"),
                source = "SearXNG"
            )
        }.take(10)
    }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

        /** 抓搜索结果页用桌面 UA，拿到的 HTML 更稳定、条目更多 */
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}

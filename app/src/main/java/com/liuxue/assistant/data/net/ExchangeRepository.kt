package com.liuxue.assistant.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class RateTable(
    val base: String,
    val rates: Map<String, Double>,
    val updatedAt: Long,
    val source: String
)

/**
 * 实时汇率。
 *
 * 用 open.er-api.com 的免费接口（无需 Key、无次数限制、支持 160+ 币种）。
 * 失败时自动回退到 frankfurter.app（欧洲央行数据，币种少一些但更稳）。
 *
 * 缓存策略：内存 + 1 分钟有效期；下拉/点刷新时强制绕过缓存。免费接口本身按天更新，
 * 页面上会显示「更新于 …」，避免用户误以为是一直不动的旧数据。
 */
class ExchangeRepository {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var cache: RateTable? = null

    private val symbols = mapOf(
        "CNY" to "人民币", "USD" to "美元", "EUR" to "欧元", "RUB" to "卢布",
        "GBP" to "英镑", "JPY" to "日元", "KRW" to "韩元", "HKD" to "港币",
        "AUD" to "澳元", "CAD" to "加元", "CHF" to "瑞士法郎", "SGD" to "新加坡元",
        "THB" to "泰铢", "INR" to "印度卢比", "BRL" to "雷亚尔", "TRY" to "里拉",
        "AED" to "迪拉姆", "KZT" to "坚戈", "UAH" to "格里夫纳", "BYN" to "白俄卢布"
    )

    fun currencyName(code: String): String = symbols[code] ?: code
    fun availableCurrencies(): List<Pair<String, String>> =
        symbols.entries.map { it.key to it.value }.sortedBy { it.first }

    /** 取以 base 为基准的汇率表（1 分钟内走缓存；force=true 强制刷新） */
    suspend fun rates(base: String, force: Boolean = false): RateTable = withContext(Dispatchers.IO) {
        val b = base.uppercase()
        if (!force) {
            cache?.takeIf { it.base == b && System.currentTimeMillis() - it.updatedAt < CACHE_TTL_MS }
                ?.let { return@withContext it }
        }

        var lastError: String? = null
        // 源 1：open.er-api.com
        runCatching { fetchErApi(b) }.onSuccess {
            cache = it
            return@withContext it
        }.onFailure { lastError = it.message }

        // 源 2：frankfurter.app（欧洲央行）
        runCatching { fetchFrankfurter(b) }.onSuccess {
            cache = it
            return@withContext it
        }.onFailure { lastError = (lastError ?: "") + " / " + it.message }

        throw IllegalStateException("汇率获取失败：" + (lastError ?: "网络不可用"))
    }

    private fun fetchErApi(base: String): RateTable {
        val body = httpGet("https://open.er-api.com/v6/latest/" + base)
        val o = JSONObject(body)
        if (o.optString("result") != "success") {
            throw IllegalStateException(o.optString("error-type").ifBlank { "返回异常" })
        }
        val r = o.getJSONObject("rates")
        val map = mutableMapOf<String, Double>()
        r.keys().forEach { k -> map[k] = r.optDouble(k) }
        return RateTable(base, map, System.currentTimeMillis(), "open.er-api.com")
    }

    private fun fetchFrankfurter(base: String): RateTable {
        val body = httpGet("https://api.frankfurter.app/latest?from=" + base)
        val o = JSONObject(body)
        val r = o.getJSONObject("rates")
        val map = mutableMapOf<String, Double>()
        r.keys().forEach { k -> map[k] = r.optDouble(k) }
        map[base] = 1.0
        return RateTable(base, map, System.currentTimeMillis(), "frankfurter.app")
    }

    private fun httpGet(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "StudyAssistant/1.0")
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP " + resp.code)
            return body
        }
    }

    companion object {
        /** 汇率内存缓存有效期：1 分钟（点「刷新」可强制绕过） */
        private const val CACHE_TTL_MS = 60_000L
    }

    /** 换算：amount 个 from -> to */
    fun convert(table: RateTable, from: String, to: String, amount: Double): Double? {
        val f = from.uppercase()
        val t = to.uppercase()
        val fromRate = if (f == table.base) 1.0 else table.rates[f] ?: return null
        val toRate = if (t == table.base) 1.0 else table.rates[t] ?: return null
        if (fromRate == 0.0) return null
        // table 基准是 base：amount(from) -> base -> to
        return amount / fromRate * toRate
    }

    /** 从自然语言里识别币种，如「美元 人民币」「100 USD to CNY」 */
    fun parseQuery(text: String): Pair<String, String>? {
        val codes = Regex("[A-Za-z]{3}").findAll(text)
            .map { it.value.uppercase() }
            .filter { symbols.containsKey(it) }
            .toList()
        if (codes.size >= 2) return codes[0] to codes[1]
        val names = symbols.entries.filter { (_, cn) -> text.contains(cn) }.map { it.key }
        if (names.size >= 2) return names[0] to names[1]
        return null
    }

    /** 从文本里取出金额，默认 1 */
    fun parseAmount(text: String): Double {
        val m = Regex("[0-9]+([.][0-9]+)?").find(text) ?: return 1.0
        return m.value.toDoubleOrNull() ?: 1.0
    }
}

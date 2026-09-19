package com.liuxue.assistant.data.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 识别语言：自动 / 中英 / 俄文 */
enum class OcrScript { AUTO, CH, RU }

/** 一行识别结果 */
data class OcrLine(val text: String, val score: Float, val box: FloatArray)

/** 整页识别结果 */
data class OcrResult(
    val lines: List<OcrLine>,
    val fullText: String,
    val srcW: Int,
    val srcH: Int,
    val detW: Int,
    val detH: Int,
    val detMs: Long,
    val recMs: Long
) {
    val boxCount: Int get() = lines.size
}

/**
 * PP-OCR（ONNX Runtime）离线文字识别。
 *
 * 流水线（与 RapidOCR / PaddleOCR 对齐，按 PC 端参考实现移植，详见 tools/ocr_ref/SPEC.md）：
 *   1. det：整图缩放到长边 ≤ 960（补到 32 的倍数）→ ImageNet mean/std 归一化 → 概率图
 *   2. DB 后处理：0.3 二值化 → 2x2 膨胀 → 连通域 → 最小外接矩形 → 0.6 框得分过滤 → unclip(1.5) 外扩
 *   3. 按框从**原图**做透视裁剪（高 48 输入 rec）
 *   4. rec：(x/255-0.5)/0.5 归一化 → CTC 解码（blank=0，字符表 = keys + 末尾空格）
 *
 * 纯本地推理，不联网；模型文件由 [OcrModelRepository] 首次使用时下载。
 * 单例缓存 ONNX session（加载一次约 1 秒，之后复用）。
 */
object OcrEngine {

    private const val TAG = "OcrEngine"

    // ---- PP-OCRv4 + RapidOCR 同款参数（改这里就能调灵敏度） ----
    private const val DET_LIMIT_SIDE = 960      // det 输入长边上限
    private const val DET_MIN_SIDE = 320        // 图太小时放大到的最小长边
    private const val DET_THRESH = 0.3f         // 概率图二值化阈值
    private const val DET_BOX_THRESH = 0.6f     // 框平均得分阈值
    private const val DET_UNCLIP_RATIO = 1.5f   // 文本框外扩比例
    private const val DET_MIN_SIZE = 3f         // 最小框边长
    private const val DET_MAX_CANDIDATES = 1000
    private const val REC_HEIGHT = 48
    private const val REC_MIN_WIDTH = 320       // rec 输入最小宽度（不足右侧补 0），与 PaddleOCR 一致
    private const val REC_MAX_WIDTH = 2048      // 超长文本行保护（正常不会触发）
    private const val TEXT_SCORE = 0.5f         // 整行置信度低于此值丢弃（同 RapidOCR text_score）
    private const val SOURCE_MAX_SIDE = 3200    // 原图先降采样到此长边，防 OOM（别设太小，会把小字压糊）
    private const val FALLBACK_SCORE = 0.75f    // 单行低于此分就试试另一个语言的模型（中俄混排兜底）
    private const val DET_USE_DILATION = false  // PaddleOCR 默认不膨胀（RapidOCR 1.2.3 默认膨胀）

    /**
     * 通道序：PaddleOCR 训练/推理用 BGR（cv2.imread），Android Bitmap 是 ARGB。
     * 与 PC 参考实现（tools/ocr_ref/SPEC.md）对齐，这里喂模型前按 BGR 排列。
     */
    private const val BGR_ORDER = true

    private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /**
     * 调试钩子（仅设备测试用）：非 null 时，每次 rec 前回调 (输入张量, 宽, 高)。
     * 用来和 PC 参考实现逐值比对，定位前后处理差异。
     */
    @Volatile var debugRecInputHook: ((FloatArray, Int, Int) -> Unit)? = null

    private val lock = Any()
    private var env: OrtEnvironment? = null
    private var detSession: OrtSession? = null

    /** 中英 rec（必需） */
    private var recSession: OrtSession? = null
    private var charList: List<String> = emptyList()

    /** 俄文（西里尔）rec（可选；没下载时自动退化为只有中英） */
    private var recRuSession: OrtSession? = null
    private var charListRu: List<String> = emptyList()

    private var loadedDetPath: String? = null
    private var loadedRecPath: String? = null
    private var loadedKeysPath: String? = null
    private var loadedRecRuPath: String? = null
    private var loadedKeysRuPath: String? = null

    /** 原生库 / onnxruntime 是否可用（未打包对应 ABI 时优雅降级） */
    val isAvailable: Boolean by lazy {
        runCatching { OrtEnvironment.getEnvironment(); true }.getOrElse {
            Log.e(TAG, "onnxruntime 不可用", it); false
        }
    }

    /**
     * 加载（或复用）模型。相同路径重复调用直接返回 true。
     * 耗时约 1 秒，务必在 IO 线程调用。
     */
    fun load(
        detPath: String,
        recPath: String,
        keysPath: String,
        recRuPath: String? = null,
        keysRuPath: String? = null
    ): Boolean = synchronized(lock) {
        if (!isAvailable) return false
        val sameRu = (loadedRecRuPath == recRuPath) && (loadedKeysRuPath == keysRuPath)
        if (detSession != null && recSession != null &&
            loadedDetPath == detPath && loadedRecPath == recPath && loadedKeysPath == keysPath && sameRu
        ) return true
        return runCatching {
            releaseLocked()
            val e = OrtEnvironment.getEnvironment()
            env = e
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
            }
            detSession = e.createSession(detPath, opts)
            recSession = e.createSession(recPath, opts)
            charList = readKeys(keysPath)
            // 词表与模型必须配套：classes == 词表行数 + blank + 末尾空格（= charList.size + 1）。
            // 不配套时 CTC 会拿模型索引去索引错词表，输出"看着像字但全错"的噪声且不报错（实测踩过），
            // 所以这里直接判失败，让界面提示"模型损坏，删除后重新下载"。
            val chClasses = outputClasses(recSession!!)
            if (chClasses > 0 && chClasses != charList.size + 1) {
                throw IllegalStateException(
                    "中英 rec 词表不配套：模型 $chClasses 类，词表 ${charList.size} 行"
                )
            }
            loadedDetPath = detPath
            loadedRecPath = recPath
            loadedKeysPath = keysPath

            // 俄文模型可选：文件在就加载，不在就只跑中英
            if (recRuPath != null && keysRuPath != null &&
                File(recRuPath).isFile && File(keysRuPath).isFile
            ) {
                runCatching {
                    val s2 = e.createSession(recRuPath, opts)
                    val keys2 = readKeys(keysRuPath)
                    val ruClasses = outputClasses(s2)
                    if (ruClasses > 0 && ruClasses != keys2.size + 1) {
                        s2.close()
                        throw IllegalStateException(
                            "俄文 rec 词表不配套：模型 $ruClasses 类，词表 ${keys2.size} 行"
                        )
                    }
                    recRuSession = s2
                    charListRu = keys2
                    loadedRecRuPath = recRuPath
                    loadedKeysRuPath = keysRuPath
                }.onFailure { Log.e(TAG, "俄文识别模型加载失败，退化为只用中英模型", it) }
            }
            Log.i(TAG, "OCR 模型已加载：det=" + File(detPath).name + " rec=" + File(recPath).name +
                " 字符数=" + charList.size +
                " 俄文rec=" + (if (recRuSession != null) File(recRuPath!!).name + " 字符数=" + charListRu.size else "未启用"))
            true
        }.getOrElse {
            Log.e(TAG, "OCR 模型加载失败", it)
            false
        }
    }

    fun isLoaded(): Boolean = detSession != null && recSession != null

    fun release() = synchronized(lock) { releaseLocked() }

    private fun releaseLocked() {
        runCatching { detSession?.close() }
        runCatching { recSession?.close() }
        runCatching { recRuSession?.close() }
        detSession = null
        recSession = null
        recRuSession = null
        loadedDetPath = null
        loadedRecPath = null
        loadedKeysPath = null
        loadedRecRuPath = null
        loadedKeysRuPath = null
    }

    /** rec 模型最后一维 = 类别数（词表行数 + blank + 末尾空格） */
    private fun outputClasses(session: OrtSession): Int = runCatching {
        val info = session.outputInfo.values.first().info
        (info as TensorInfo).shape.last().toInt()
    }.getOrDefault(-1)

    private fun readKeys(path: String): List<String> {
        val text = File(path).readText(Charsets.UTF_8)
        // 与 RapidOCR CTCLabelDecode 一致：按行切分、去换行，末尾补一个空格（作为第 6624 号字符）
        val chars = text.split('\n').map { it.removeSuffix("\r") }
        return chars.filter { it.isNotEmpty() || chars.size <= 1 } + " "
    }

    // ==================================================================
    //  对外入口
    // ==================================================================

    /**
     * 识别一张图（[bitmap] 任意尺寸，内部按需缩放）。
     *
     * [script] = AUTO 时：拿前 1~2 行**同时跑中英和俄文模型**，谁整体得分高就用谁
     * （俄文几乎不会被中英模型读对，得分会明显偏低，所以这个判据很稳）；
     * 判完之后其余行只用选中的模型，单行若得分过低再兜底试另一个模型 —— 这样中俄混排也能处理。
     */
    fun recognize(bitmap: Bitmap, script: OcrScript = OcrScript.AUTO): OcrResult = synchronized(lock) {
        val det = detSession ?: throw IllegalStateException("OCR 模型未加载")
        recSession ?: throw IllegalStateException("OCR 模型未加载")

        val src = downscale(bitmap, SOURCE_MAX_SIDE)
        val srcW = src.width
        val srcH = src.height

        // ---------- 1) det ----------
        var t0 = System.currentTimeMillis()
        val (dw, dh) = detInputSize(srcW, srcH)
        val detBmp = if (dw == srcW && dh == srcH) src else Bitmap.createScaledBitmap(src, dw, dh, true)
        val detInput = toDetInput(detBmp, dw, dh)
        val prob = runDet(det, detInput, dw, dh)
        val detMs = System.currentTimeMillis() - t0

        // ---------- 2) DB 后处理 ----------
        val mask = Array(dh) { y -> BooleanArray(dw) { x -> prob[y][x] > DET_THRESH } }
        val dilated = if (DET_USE_DILATION) dilate2x2(mask, dw, dh) else mask
        val boxes = boxesFromBitmap(
            prob, dilated, dw, dh,
            destW = srcW, destH = srcH
        )
        if (boxes.isEmpty()) {
            return OcrResult(emptyList(), "", srcW, srcH, dw, dh, detMs, 0L)
        }

        // ---------- 3) 裁剪 ----------
        t0 = System.currentTimeMillis()
        val raster = Raster(srcW, srcH, IntArray(srcW * srcH).also { src.getPixels(it, 0, srcW, 0, 0, srcW, srcH) })
        val crops = ArrayList<Crop?>(boxes.size)
        for (box in boxes) {
            val crop = raster.cropQuad(box)
            crops.add(if (crop != null && crop.w >= 2 && crop.h >= 2) crop else null)
        }

        // ---------- 4) 选中英还是俄文模型 ----------
        val ru = recRuSession
        val useRu: Boolean = when (script) {
            OcrScript.CH -> false
            OcrScript.RU -> ru != null
            OcrScript.AUTO -> if (ru == null) false else decideCyrillic(crops)
        }

        val lines = ArrayList<OcrLine>(boxes.size)
        for (i in boxes.indices) {
            val crop = crops[i] ?: continue
            var (text, score) = recWith(crop, useRu)

            // 中俄混排按行兜底：整页判定只决定"默认模型"，逐行还要救两种情况——
            //   1) 默认模型这一行读得不好（分数低 / 空）→ 换另一个模型试
            //   2) 另一个模型读出了汉字（俄文词表里没有汉字，结构上只能来自中英模型）→ 优先它
            val otherSession = if (useRu) recSession else recRuSession
            if (otherSession != null && (score < FALLBACK_SCORE || text.isBlank())) {
                val (t2, s2) = recWith(crop, !useRu)
                val otherHasCjk = t2.isNotBlank() && t2.any { isCjk(it) } && s2 > 0.5f
                if (otherHasCjk || s2 > score) { text = t2; score = s2 }
            }
            if (text.isBlank()) continue
            if (score < TEXT_SCORE) continue
            lines.add(OcrLine(text, score, boxes[i]))
        }
        sortBoxes(lines)
        val recMs = System.currentTimeMillis() - t0

        return OcrResult(
            lines = lines,
            fullText = lines.joinToString("\n") { it.text },
            srcW = srcW, srcH = srcH, detW = dw, detH = dh, detMs = detMs, recMs = recMs
        )
    }

    /** 通道偏移：BGR_ORDER 时 c=0 取 B(<<0)，否则取 R(<<16) */
    private fun channelShift(c: Int): Int = if (BGR_ORDER) c * 8 else 16 - c * 8

    /**
     * 四舍六入五成双（Python round / numpy round 的规则）。
     * PC 参考实现实测：`int(round(t/32))*32` 必须用这个规则，用 Math.round（五入）
     * 会让 rel_a 的 det 输入高度 384 变 416（框角点差 1px，设备端逐框比对会失败）。
     */
    private fun roundHalfEven(v: Double): Int {
        val k = floor(v).toInt()
        val r = v - k
        return when {
            r > 0.5 -> k + 1
            r == 0.5 && (k and 1) == 1 -> k + 1
            else -> k
        }
    }

    /** 用前 1~2 行判定是否俄文：俄文模型整体得分明显更高 → 俄文 */
    private fun decideCyrillic(crops: List<Crop?>): Boolean {
        val ru = recRuSession ?: return false
        val ch = recSession ?: return false
        var chSum = 0f
        var ruSum = 0f
        var n = 0
        for (k in 0 until min(2, crops.size)) {
            val c = crops[k] ?: continue
            val (t1, s1) = runRec(ch, charList, c)
            if (t1.isNotBlank() && t1.any { isCjk(it) } && s1 > 0.5f) return false  // 有汉字 → 中英模型
            val (t2, s2) = runRec(ru, charListRu, c)
            if (t1.isNotBlank()) chSum += s1
            if (t2.isNotBlank()) ruSum += s2
            n++
        }
        if (n == 0) return false
        return ruSum > chSum + 0.02f * n
    }

    private fun recWith(crop: Crop, ru: Boolean): Pair<String, Float> =
        if (ru) runRec(recRuSession ?: recSession!!, charListRu.ifEmpty { charList }, crop)
        else runRec(recSession!!, charList, crop)

    private fun isCjk(c: Char): Boolean =
        c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF || c.code in 0xF900..0xFAFF

    /** 目标尺寸：长边限制 + 32 的倍数（与 PC 参考 SPEC §3 一致） */
    private fun detInputSize(w: Int, h: Int): Pair<Int, Int> {
        val maxSide = max(w, h)
        val ratio = when {
            maxSide > DET_LIMIT_SIDE -> DET_LIMIT_SIDE.toDouble() / maxSide
            maxSide < DET_MIN_SIDE -> DET_MIN_SIDE.toDouble() / maxSide
            else -> 1.0
        }
        val tH = (h * ratio).toInt()   // ★ 先截断
        val tW = (w * ratio).toInt()
        val rh = max(32, roundHalfEven(tH / 32.0) * 32)
        val rw = max(32, roundHalfEven(tW / 32.0) * 32)
        return rw to rh
    }

    /** 原图长边超过 [maxSide] 时先等比降采样 */
    private fun downscale(bmp: Bitmap, maxSide: Int): Bitmap {
        val m = max(bmp.width, bmp.height)
        if (m <= maxSide) return bmp
        val r = maxSide.toFloat() / m
        val w = max(1, (bmp.width * r).roundToInt())
        val h = max(1, (bmp.height * r).roundToInt())
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }

    /** Bitmap -> NCHW float，ImageNet 归一化（det） */
    private fun toDetInput(bmp: Bitmap, w: Int, h: Int): FloatArray {
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = FloatArray(3 * w * h)
        val plane = w * h
        for (i in 0 until plane) {
            val p = px[i]
            for (c in 0..2) {
                val v = ((p shr channelShift(c)) and 0xFF) / 255f
                out[c * plane + i] = (v - DET_MEAN[c]) / DET_STD[c]
            }
        }
        return out
    }

    // ==================================================================
    //  det 推理
    // ==================================================================

    private fun runDet(session: OrtSession, data: FloatArray, w: Int, h: Int): Array<FloatArray> {
        val e = env ?: throw IllegalStateException("ONNX Runtime 未初始化")
        val inputName = session.inputNames.first()
        val shape = longArrayOf(1, 3, h.toLong(), w.toLong())
        OnnxTensor.createTensor(e, FloatBuffer.wrap(data), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { res ->
                val out = res.get(0) as OnnxTensor
                val fb = out.floatBuffer
                return Array(h) { y ->
                    FloatArray(w) { x -> fb.get(y * w + x) }
                }
            }
        }
    }

    /** 2x2 膨胀（cv2.dilate 默认锚点，等价于取左上 2x2 邻域最大值） */
    private fun dilate2x2(mask: Array<BooleanArray>, w: Int, h: Int): Array<BooleanArray> {
        val out = Array(h) { BooleanArray(w) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = mask[y][x]
                if (!v && x > 0) v = mask[y][x - 1]
                if (!v && y > 0) v = mask[y - 1][x]
                if (!v && x > 0 && y > 0) v = mask[y - 1][x - 1]
                out[y][x] = v
            }
        }
        return out
    }

    // ==================================================================
    //  DB 后处理（boxes_from_bitmap）
    // ==================================================================

    private fun boxesFromBitmap(
        prob: Array<FloatArray>,
        mask: Array<BooleanArray>,
        w: Int,
        h: Int,
        destW: Int,
        destH: Int
    ): List<FloatArray> {
        val labels = IntArray(w * h)
        var label = 0
        val stack = IntArray(w * h)
        val result = ArrayList<FloatArray>()
        val scaleX = destW.toFloat() / w
        val scaleY = destH.toFloat() / h

        for (sy in 0 until h) {
            for (sx in 0 until w) {
                if (!mask[sy][sx] || labels[sy * w + sx] != 0) continue
                label++
                // ---- 1) 8 邻域 flood fill，同时记录 bbox ----
                var sp = 0
                stack[sp++] = sy * w + sx
                labels[sy * w + sx] = label
                var minX = sx; var maxX = sx; var minY = sy; var maxY = sy
                while (sp > 0) {
                    val idx = stack[--sp]
                    val cy = idx / w
                    val cx = idx - cy * w
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy
                    for (dy in -1..1) {
                        val ny = cy + dy
                        if (ny < 0 || ny >= h) continue
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = cx + dx
                            if (nx < 0 || nx >= w) continue
                            val ni = ny * w + nx
                            if (mask[ny][nx] && labels[ni] == 0) {
                                labels[ni] = label
                                stack[sp++] = ni
                            }
                        }
                    }
                }
                if (maxX - minX < 1 || maxY - minY < 1) continue

                // ---- 2) 取该连通域的边界像素（凸包与全体像素一致，但点更少） ----
                val pts = ArrayList<Pt>()
                for (y in minY..maxY) {
                    for (x in minX..maxX) {
                        if (labels[y * w + x] != label) continue
                        var boundary = false
                        outer@ for (dy in -1..1) {
                            val ny = y + dy
                            for (dx in -1..1) {
                                if (ny < 0 || ny >= h || x + dx < 0 || x + dx >= w) { boundary = true; break@outer }
                                if (labels[ny * w + (x + dx)] != label) { boundary = true; break@outer }
                            }
                        }
                        if (boundary) pts.add(Pt(x.toFloat(), y.toFloat()))
                    }
                }
                if (pts.size < 3) continue

                // ---- 3) 最小外接矩形 + 得分过滤 ----
                val hull = convexHull(pts)
                if (hull.size < 3) continue
                var rect = minAreaRect(hull)
                if (min(rect.w, rect.h) < DET_MIN_SIZE) continue
                val score = boxScoreFast(prob, rect.corners, w, h)
                if (score < DET_BOX_THRESH) continue

                // ---- 4) unclip 外扩后再取一次最小外接矩形 ----
                val expanded = unclip(rect.corners, DET_UNCLIP_RATIO)
                rect = minAreaRect(convexHull(expanded.toList()))
                if (min(rect.w, rect.h) < DET_MIN_SIZE + 2) continue

                // ---- 5) 映射回原图坐标（prob 图与 det 输入 1:1）+ 裁剪 ----
                val box = FloatArray(8)
                for (i in 0..3) {
                    // round = numpy.round（五成双），与 PC 参考一致
                    box[i * 2] = roundHalfEven((rect.corners[i].x * scaleX).toDouble())
                        .toFloat().coerceIn(0f, destW.toFloat())
                    box[i * 2 + 1] = roundHalfEven((rect.corners[i].y * scaleY).toDouble())
                        .toFloat().coerceIn(0f, destH.toFloat())
                }
                val bw = dist(box[0], box[1], box[2], box[3])
                val bh = dist(box[0], box[1], box[6], box[7])
                if (bw <= 3f || bh <= 3f) continue
                result.add(box)
                if (result.size >= DET_MAX_CANDIDATES) return result
            }
        }
        return result
    }

    /** 框平均得分：概率图在四边形内的均值（对应 cv2.mean(pred, mask)） */
    private fun boxScoreFast(prob: Array<FloatArray>, corners: Array<Pt>, w: Int, h: Int): Float {
        var xmin = Int.MAX_VALUE; var xmax = Int.MIN_VALUE
        var ymin = Int.MAX_VALUE; var ymax = Int.MIN_VALUE
        for (p in corners) {
            xmin = min(xmin, floor(p.x).toInt())
            xmax = max(xmax, ceil(p.x).toInt())
            ymin = min(ymin, floor(p.y).toInt())
            ymax = max(ymax, ceil(p.y).toInt())
        }
        xmin = xmin.coerceIn(0, w - 1); xmax = xmax.coerceIn(0, w - 1)
        ymin = ymin.coerceIn(0, h - 1); ymax = ymax.coerceIn(0, h - 1)
        var sum = 0.0
        var cnt = 0
        for (y in ymin..ymax) {
            for (x in xmin..xmax) {
                if (pointInPoly(x + 0.5f, y + 0.5f, corners)) {
                    sum += prob[y][x]
                    cnt++
                }
            }
        }
        return if (cnt == 0) 0f else (sum / cnt).toFloat()
    }

    /** 多边形外扩：每条边沿外法线平移 distance = area*ratio/perimeter，再求相邻边交点 */
    private fun unclip(box: Array<Pt>, ratio: Float): Array<Pt> {
        var area = 0.0
        var per = 0.0
        val n = box.size
        for (i in 0 until n) {
            val a = box[i]
            val b = box[(i + 1) % n]
            area += a.x * b.y - b.x * a.y
            per += sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y)).toDouble()
        }
        area /= 2.0
        if (per <= 1e-6) return box
        val dist = (abs(area) * ratio / per).toFloat()
        // 统一成"正面积"方向，此时外法线 = (dy, -dx)/|d|
        val poly = if (area > 0) box else box.reversedArray()
        val offA = ArrayList<Pt>(n)
        val offB = ArrayList<Pt>(n)
        for (i in 0 until n) {
            val a = poly[i]
            val b = poly[(i + 1) % n]
            var dx = b.x - a.x
            var dy = b.y - a.y
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-6) { dx = 0f; dy = 0f } else { dx /= len; dy /= len }
            val nx = dy * dist
            val ny = -dx * dist
            offA.add(Pt(a.x + nx, a.y + ny))
            offB.add(Pt(b.x + nx, b.y + ny))
        }
        val out = Array(n) { i ->
            val prev = (i - 1 + n) % n
            intersect(offA[prev], offB[prev], offA[i], offB[i]) ?: offA[i]
        }
        return out
    }

    /** 两条直线交点；平行时返回 null */
    private fun intersect(p1: Pt, p2: Pt, p3: Pt, p4: Pt): Pt? {
        val d1x = p2.x - p1.x; val d1y = p2.y - p1.y
        val d2x = p4.x - p3.x; val d2y = p4.y - p3.y
        val den = d1x * d2y - d1y * d2x
        if (abs(den) < 1e-6f) return null
        val ex = p3.x - p1.x; val ey = p3.y - p1.y
        val t = (ex * d2y - ey * d2x) / den
        return Pt(p1.x + t * d1x, p1.y + t * d1y)
    }

    // ==================================================================
    //  几何工具
    // ==================================================================

    class Pt(val x: Float, val y: Float)

    // 三次卷积权重临时缓冲（避免每个像素分配数组）
    private val WX = FloatArray(4)
    private val WY = FloatArray(4)

    /** Keys 三次卷积核，a = -0.75（与 cv2.INTER_CUBIC 一致） */
    private fun cubicKernel(x: Float): Float {
        val a = -0.75f
        val ax = abs(x)
        return when {
            ax <= 1f -> ((a + 2f) * ax - (a + 3f)) * ax * ax + 1f
            ax < 2f -> (((ax - 5f) * ax + 8f) * ax - 4f) * a
            else -> 0f
        }
    }

    /** 采样点相对整数点偏移 t 时，4 个邻居（-1,0,1,2）的权重 */
    private fun cubicWeights(t: Float, out: FloatArray) {
        for (i in 0..3) out[i] = cubicKernel(i - 1 - t)
    }

    /** 4 个 ARGB 像素按 (ax, ay) 双线性混合 */
    private fun blend(c00: Int, c10: Int, c01: Int, c11: Int, ax: Float, ay: Float): Int {
        var outPx = 0xFF shl 24
        for (k in 0..2) {
            val sh = 16 - k * 8
            val v00 = (c00 shr sh) and 0xFF
            val v10 = (c10 shr sh) and 0xFF
            val v01 = (c01 shr sh) and 0xFF
            val v11 = (c11 shr sh) and 0xFF
            val top = v00 + (v10 - v00) * ax
            val bot = v01 + (v11 - v01) * ax
            val v = (top + (bot - top) * ay).roundToInt().coerceIn(0, 255)
            outPx = outPx or (v shl sh)
        }
        return outPx
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1))

    private fun pointInPoly(x: Float, y: Float, poly: Array<Pt>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val xi = poly[i].x; val yi = poly[i].y
            val xj = poly[j].x; val yj = poly[j].y
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    private fun cross(o: Pt, a: Pt, b: Pt): Float =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    /** Andrew monotone chain 凸包（严格凸，去掉共线点） */
    private fun convexHull(points: List<Pt>): Array<Pt> {
        if (points.size < 3) return points.toTypedArray()
        val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
        val lower = ArrayList<Pt>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0f) {
                lower.removeAt(lower.size - 1)
            }
            lower.add(p)
        }
        val upper = ArrayList<Pt>()
        for (i in sorted.indices.reversed()) {
            val p = sorted[i]
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0f) {
                upper.removeAt(upper.size - 1)
            }
            upper.add(p)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return (lower + upper).toTypedArray()
    }

    class MiniRect(val corners: Array<Pt>, val w: Float, val h: Float)

    /**
     * 最小外接矩形（旋转卡壳），返回顺序与 cv2.minAreaRect + RapidOCR get_mini_boxes 一致：
     * [左上, 右上, 右下, 左下]（按 x 分左右，再按 y 分上下）。
     */
    private fun minAreaRect(hull: Array<Pt>): MiniRect {
        if (hull.size < 3) {
            // 退化情况：用 bbox 兜底
            val minX = hull.minOf { it.x }; val maxX = hull.maxOf { it.x }
            val minY = hull.minOf { it.y }; val maxY = hull.maxOf { it.y }
            val cs = arrayOf(
                Pt(minX, minY), Pt(maxX, minY), Pt(maxX, maxY), Pt(minX, maxY)
            )
            return MiniRect(cs, maxX - minX, maxY - minY)
        }
        var bestArea = Float.MAX_VALUE
        var bestW = 0f
        var bestH = 0f
        var best: Array<Pt>? = null
        val n = hull.size
        for (i in 0 until n) {
            val p1 = hull[i]
            val p2 = hull[(i + 1) % n]
            var dx = p2.x - p1.x
            var dy = p2.y - p1.y
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-6f) continue
            dx /= len; dy /= len
            var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
            var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
            for (p in hull) {
                val u = p.x * dx + p.y * dy
                val v = -p.x * dy + p.y * dx
                if (u < minU) minU = u
                if (u > maxU) maxU = u
                if (v < minV) minV = v
                if (v > maxV) maxV = v
            }
            val area = (maxU - minU) * (maxV - minV)
            if (area < bestArea) {
                bestArea = area
                bestW = maxU - minU
                bestH = maxV - minV
                val cu = (minU + maxU) / 2f
                val cv = (minV + maxV) / 2f
                val hw = bestW / 2f
                val hh = bestH / 2f
                fun toXY(u: Float, v: Float) = Pt(u * dx - v * dy, u * dy + v * dx)
                best = arrayOf(
                    toXY(cu - hw, cv - hh), toXY(cu + hw, cv - hh),
                    toXY(cu + hw, cv + hh), toXY(cu - hw, cv + hh)
                )
            }
        }
        val cs = best ?: return MiniRect(arrayOf(hull[0], hull[0], hull[0], hull[0]), 0f, 0f)
        return MiniRect(orderBox(cs), bestW, bestH)
    }

    /** 复刻 get_mini_boxes 的点序：按 x 排序 → 左两个按 y 分上下、右两个按 y 分上下 */
    private fun orderBox(pts: Array<Pt>): Array<Pt> {
        val s = pts.sortedBy { it.x }
        val l0 = s[0]; val l1 = s[1]
        val r0 = s[2]; val r1 = s[3]
        val tl = if (l1.y > l0.y) l0 else l1
        val bl = if (l1.y > l0.y) l1 else l0
        val tr = if (r1.y > r0.y) r0 else r1
        val br = if (r1.y > r0.y) r1 else r0
        return arrayOf(tl, tr, br, bl)
    }

    // ==================================================================
    //  裁剪 + 识别
    // ==================================================================

    /** 原图像素访问（一次性 getPixels，避免逐像素 JNI） */
    private class Raster(val w: Int, val h: Int, val px: IntArray) {

        fun at(x: Int, y: Int): Int {
            val cx = x.coerceIn(0, w - 1)
            val cy = y.coerceIn(0, h - 1)
            return px[cy * w + cx]
        }

        /** 双线性采样（BORDER_REPLICATE） */
        fun sample(fx: Float, fy: Float): Int {
            val x0 = floor(fx).toInt()
            val y0 = floor(fy).toInt()
            val ax = fx - x0
            val ay = fy - y0
            val c00 = at(x0, y0); val c10 = at(x0 + 1, y0)
            val c01 = at(x0, y0 + 1); val c11 = at(x0 + 1, y0 + 1)
            return blend(c00, c10, c01, c11, ax, ay)
        }

        /**
         * 三次卷积采样（cv2.warpPerspective 的 INTER_CUBIC，Keys 核 a=-0.75）。
         * PC 参考实测：裁剪插值会决定"识别率 99.8%"这类行里空格是否被识别出来，
         * 用双线性会丢掉空格 → 必须和 PaddleOCR 一样用三次。
         */
        fun sampleCubic(fx: Float, fy: Float): Int {
            val x0 = floor(fx).toInt()
            val y0 = floor(fy).toInt()
            cubicWeights(fx - x0, WX)
            cubicWeights(fy - y0, WY)
            var out = 0xFF shl 24
            for (k in 0..2) {
                val sh = 16 - k * 8
                var sum = 0f
                for (j in 0..3) {
                    val wy = WY[j]
                    for (i in 0..3) {
                        val v = (at(x0 - 1 + i, y0 - 1 + j) shr sh) and 0xFF
                        sum += v * WX[i] * wy
                    }
                }
                val v = sum.roundToInt().coerceIn(0, 255)
                out = out or (v shl sh)
            }
            return out
        }

        /** 按 4 点（tl,tr,br,bl）透视裁剪；与 RapidOCR get_rotate_crop_image 一致 */
        fun cropQuad(box: FloatArray): Crop? {
            val p = Array(4) { Pt(box[it * 2], box[it * 2 + 1]) }
            val cw = max(
                dist(p[0].x, p[0].y, p[1].x, p[1].y),
                dist(p[2].x, p[2].y, p[3].x, p[3].y)
            ).toInt()
            val ch = max(
                dist(p[0].x, p[0].y, p[3].x, p[3].y),
                dist(p[1].x, p[1].y, p[2].x, p[2].y)
            ).toInt()
            if (cw < 2 || ch < 2) return null
            // dst -> src 的单应矩阵（对 dst 每个像素反查原图坐标）
            val dst = listOf(Pt(0f, 0f), Pt(cw.toFloat(), 0f), Pt(cw.toFloat(), ch.toFloat()), Pt(0f, ch.toFloat()))
            val hMat = homography(dst, p.toList()) ?: return null
            val out = IntArray(cw * ch)
            for (y in 0 until ch) {
                for (x in 0 until cw) {
                    val den = hMat[6] * x + hMat[7] * y + 1f
                    if (abs(den) < 1e-9f) { out[y * cw + x] = at(x, y); continue }
                    val sx = (hMat[0] * x + hMat[1] * y + hMat[2]) / den
                    val sy = (hMat[3] * x + hMat[4] * y + hMat[5]) / den
                    out[y * cw + x] = sampleCubic(sx, sy)
                }
            }
            // 竖排文本（高/宽 >= 1.5）旋转 90°（np.rot90，逆时针）
            return if (ch.toFloat() / cw >= 1.5f) rot90(out, cw, ch) else Crop(out, cw, ch)
        }
    }

    class Crop(val px: IntArray, val w: Int, val h: Int)

    private fun rot90(px: IntArray, w: Int, h: Int): Crop {
        // np.rot90（逆时针 90°）：out[y][x] = src[x][w-1-y]；结果尺寸 宽=h 高=w
        val nw = h
        val nh = w
        val out = IntArray(w * h)
        for (y in 0 until nh) {
            for (x in 0 until nw) {
                out[y * nw + x] = px[x * w + (w - 1 - y)]
            }
        }
        return Crop(out, nw, nh)
    }

    /** 4 点透视变换矩阵（8 未知数，高斯消元）；失败返回 null */
    private fun homography(src: List<Pt>, dst: List<Pt>): FloatArray? {
        val a = Array(8) { DoubleArray(9) }
        for (i in 0..3) {
            val x = src[i].x.toDouble(); val y = src[i].y.toDouble()
            val u = dst[i].x.toDouble(); val v = dst[i].y.toDouble()
            a[i * 2] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -x * u, -y * u, u)
            a[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -x * v, -y * v, v)
        }
        for (col in 0 until 8) {
            var pivot = col
            for (r in col + 1 until 8) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
            if (abs(a[pivot][col]) < 1e-12) return null
            val t = a[col]; a[col] = a[pivot]; a[pivot] = t
            val pv = a[col][col]
            for (c in col..8) a[col][c] /= pv
            for (r in 0 until 8) {
                if (r == col) continue
                val f = a[r][col]
                if (f == 0.0) continue
                for (c in col..8) a[r][c] -= f * a[col][c]
            }
        }
        return FloatArray(8) { a[it][8].toFloat() }
    }

    /**
     * 整行识别：高 48、宽 = max(320, ceil(48*w/h))，右侧补 0（PC 参考 SPEC §9），
     * 归一化 (x/255-0.5)/0.5，CTC 解码（blank=0，i -> 字符表[i-1]，表 = keys + 末尾空格）。
     */
    private fun runRec(session: OrtSession, chars: List<String>, crop: Crop): Pair<String, Float> {
        val e = env ?: throw IllegalStateException("ONNX Runtime 未初始化")
        val ratio = crop.w.toDouble() / crop.h
        var resizedW = ceil(REC_HEIGHT * ratio).toInt()
        if (resizedW < 1) resizedW = 1
        var width = max(REC_MIN_WIDTH, resizedW)
        if (width > REC_MAX_WIDTH) {
            // 超长行保护：整体等比缩小（正常文本行不会触发）
            val k = REC_MAX_WIDTH.toDouble() / width
            width = REC_MAX_WIDTH
            resizedW = max(1, (resizedW * k).toInt())
        }
        val resized = resizeBilinear(crop.px, crop.w, crop.h, resizedW, REC_HEIGHT)

        // NCHW，右侧 x >= resizedW 的部分保持 0（补 0），与 PaddleOCR padding_im 一致
        val plane = width * REC_HEIGHT
        val data = FloatArray(3 * plane)
        for (y in 0 until REC_HEIGHT) {
            for (x in 0 until resizedW) {
                val p = resized[y * resizedW + x]
                val i = y * width + x
                for (c in 0..2) {
                    val v = ((p shr channelShift(c)) and 0xFF) / 255f
                    data[c * plane + i] = (v - 0.5f) / 0.5f
                }
            }
        }
        debugRecInputHook?.invoke(data, width, REC_HEIGHT)
        val inputName = session.inputNames.first()
        val shape = longArrayOf(1, 3, REC_HEIGHT.toLong(), width.toLong())
        OnnxTensor.createTensor(e, FloatBuffer.wrap(data), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { res ->
                val out = res.get(0) as OnnxTensor
                val dims = out.info.shape
                val t = dims[1].toInt()
                val c = dims[2].toInt()
                val fb = out.floatBuffer
                val sb = StringBuilder()
                var prev = -1
                var confSum = 0.0
                var n = 0
                val row = FloatArray(c)
                for (step in 0 until t) {
                    fb.position(step * c)
                    fb.get(row)
                    var bi = 0
                    var bv = row[0]
                    for (k in 1 until c) if (row[k] > bv) { bv = row[k]; bi = k }
                    if (bi != 0 && bi != prev) {
                        val ch = chars.getOrNull(bi - 1)
                        if (!ch.isNullOrEmpty()) {
                            sb.append(ch)
                            confSum += bv
                            n++
                        }
                    }
                    prev = bi
                }
                val score = if (n == 0) 0f else (confSum / n).toFloat()
                return sb.toString() to score
            }
        }
    }

    private fun resizeBilinear(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        if (sw == dw && sh == dh) return src
        val out = IntArray(dw * dh)
        val rx = sw.toFloat() / dw
        val ry = sh.toFloat() / dh
        for (y in 0 until dh) {
            val fy = (y + 0.5f) * ry - 0.5f
            var y0 = floor(fy).toInt()
            val ay = fy - y0
            if (y0 < 0) y0 = 0
            val y1 = min(y0 + 1, sh - 1)
            for (x in 0 until dw) {
                val fx = (x + 0.5f) * rx - 0.5f
                var x0 = floor(fx).toInt()
                val ax = fx - x0
                if (x0 < 0) x0 = 0
                val x1 = min(x0 + 1, sw - 1)
                val i00 = y0 * sw + x0; val i10 = y0 * sw + x1
                val i01 = y1 * sw + x0; val i11 = y1 * sw + x1
                val c00 = src[i00]; val c10 = src[i10]; val c01 = src[i01]; val c11 = src[i11]
                out[y * dw + x] = blend(c00, c10, c01, c11, ax, ay)
            }
        }
        return out
    }

    /** 文本框排序：先上后下、同高（<10px）再从左到右（同 RapidOCR sorted_boxes） */
    private fun sortBoxes(lines: MutableList<OcrLine>) {
        lines.sortWith(compareBy({ it.box[1] }, { it.box[0] }))
        for (i in 0 until lines.size - 1) {
            if (abs(lines[i + 1].box[1] - lines[i].box[1]) < 10f && lines[i + 1].box[0] < lines[i].box[0]) {
                val tmp = lines[i]
                lines[i] = lines[i + 1]
                lines[i + 1] = tmp
            }
        }
    }
}

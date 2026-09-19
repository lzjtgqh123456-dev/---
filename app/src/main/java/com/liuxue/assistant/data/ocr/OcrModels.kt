package com.liuxue.assistant.data.ocr

/**
 * 离线 OCR 模型清单（设计同词典包 / 翻译模型：**不打进 APK**，首次使用时下载）。
 *
 * - 引擎：ONNX Runtime + PP-OCR 模型（RapidOCR 同款前后处理），纯离线。
 * - 落盘目录：`getExternalFilesDir("ocr")`，文件名固定（det.onnx / rec_ch.onnx / ...）。
 * - 每个文件配多个镜像，按顺序尝试；国内 hf-mirror 优先，失败自动切 HuggingFace 官方。
 * - 每个镜像带**自己的预期字节数**（同一模型不同转换仓库大小可能不同，用它做完整性校验）。
 *
 * 模型来源（公开、Apache-2.0）：
 * - PP-OCRv4 中英：HuggingFace `SWHL/RapidOCR`（也备用 `deepghs/paddleocr`）
 * - 西里尔（俄文）rec：HuggingFace `deepghs/paddleocr` 的 `cyrillic_PP-OCRv3_rec`
 * - 字符表：`deepghs/paddleocr` 的 `rec/ch_PP-OCRv4_rec/dict.txt`（与 PaddleOCR 官方 ppocr_keys_v1.txt 内容一致）
 *   + GitHub 官方 ppocr_keys_v1.txt 作备用
 *
 * 换源只改本文件；App 侧不需要知道具体仓库。
 */
object OcrModels {

    /** 一个下载镜像：url + 该文件在该镜像上的精确字节数 + SHA-256（下完双校验） */
    data class Mirror(val url: String, val size: Long, val sha256: String = "")

    /** 一个模型文件的描述 */
    data class Spec(
        val key: String,
        val fileName: String,
        val label: String,
        val mirrors: List<Mirror>,
        /** 第一批必须的能力（false = 可选/第二批） */
        val required: Boolean
    ) {
        val expectedSize: Long get() = mirrors.first().size

        /** 大小是否可接受：允许 ±1%（不同转换仓库可能差几个字节） */
        fun acceptsSize(bytes: Long): Boolean =
            mirrors.any { m -> m.size > 0 && kotlin.math.abs(bytes - m.size) <= m.size / 100 }

        /** SHA-256 是否与任意一个镜像登记值一致（未登记则跳过校验） */
        fun acceptsSha(sha: String): Boolean {
            val known = mirrors.map { it.sha256.lowercase() }.filter { it.isNotEmpty() }
            return known.isEmpty() || known.contains(sha.lowercase())
        }
    }

    // ---- 逻辑 key ----
    const val DET = "det"
    const val REC_CH = "rec_ch"
    const val REC_RU = "rec_ru"
    const val CLS = "cls"
    const val KEYS_CH = "keys_ch"
    const val DICT_RU = "dict_ru"

    private const val HF = "https://huggingface.co/"
    private const val HFM = "https://hf-mirror.com/"
    private const val GH = "https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/main/ppocr/utils/"

    private const val SWHL = "SWHL/RapidOCR/resolve/main/"
    private const val DEEP = "deepghs/paddleocr/resolve/main/"

    /**
     * 文本检测 —— PP-OCRv5 det mobile（4.8 MB）。
     * 实测：印刷体与 v4 打平（页面 8/8、单行 20/20），**手写体明显更好**（手写行 3/8 → 7/8，v4 还会整行漏检）。
     * 主源 HF 官方 `PaddlePaddle/PP-OCRv5_mobile_det_onnx`（hf-mirror 可走）；备源 sukode/RapidOCR（字节不同）。
     */
    val DET_SPEC = Spec(
        key = DET,
        fileName = "det.onnx",
        label = "文本检测",
        required = true,
        mirrors = listOf(
            Mirror(
                HFM + "PaddlePaddle/PP-OCRv5_mobile_det_onnx/resolve/main/inference.onnx",
                4_826_518,
                "a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d"
            ),
            Mirror(
                HF + "PaddlePaddle/PP-OCRv5_mobile_det_onnx/resolve/main/inference.onnx",
                4_826_518,
                "a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d"
            ),
            Mirror(
                HFM + "sukode/RapidOCR/resolve/main/onnx/PP-OCRv4/det/multi_PP-OCRv3_det_mobile.onnx",
                4_819_576,
                "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae"
            )
        )
    )

    /** 中英识别（PP-OCRv4 rec mobile，10.9 MB） */
    val REC_CH_SPEC = Spec(
        key = REC_CH,
        fileName = "rec_ch.onnx",
        label = "中英识别",
        required = true,
        mirrors = listOf(
            Mirror(
                HFM + SWHL + "PP-OCRv4/ch_PP-OCRv4_rec_infer.onnx", 10_857_958,
                "48fc40f24f6d2a207a2b1091d3437eb3cc3eb6b676dc3ef9c37384005483683b"
            ),
            Mirror(
                HF + SWHL + "PP-OCRv4/ch_PP-OCRv4_rec_infer.onnx", 10_857_958,
                "48fc40f24f6d2a207a2b1091d3437eb3cc3eb6b676dc3ef9c37384005483683b"
            ),
            Mirror(HFM + DEEP + "rec/ch_PP-OCRv4_rec/model.onnx", 10_826_336),
            Mirror(HF + DEEP + "rec/ch_PP-OCRv4_rec/model.onnx", 10_826_336)
        )
    )

    /** 中英字符表（6623 个字符；PP-OCRv4 rec 输出 6625 = 1 blank + 6623 keys + 1 空格） */
    val KEYS_CH_SPEC = Spec(
        key = KEYS_CH,
        fileName = "keys_ch.txt",
        label = "中英字符表",
        required = true,
        mirrors = listOf(
            Mirror(
                HFM + DEEP + "rec/ch_PP-OCRv4_rec/dict.txt", 26_249,
                "28b2362ad4ab2dc38769aa72feb535e3a9ddb3fd2a7585a05920e6393b1dc7f7"
            ),
            Mirror(
                HF + DEEP + "rec/ch_PP-OCRv4_rec/dict.txt", 26_249,
                "28b2362ad4ab2dc38769aa72feb535e3a9ddb3fd2a7585a05920e6393b1dc7f7"
            ),
            Mirror(
                GH + "ppocr_keys_v1.txt", 26_250,
                "a1c84d9bdb9ab29043c58896224d32941783eb821629618416dcb08f12886492"
            )
        )
    )

    /** 方向分类（可选，本批不启用；留给第二批） */
    val CLS_SPEC = Spec(
        key = CLS,
        fileName = "cls.onnx",
        label = "方向分类",
        required = false,
        mirrors = listOf(
            Mirror(
                HFM + SWHL + "PP-OCRv1/ch_ppocr_mobile_v2.0_cls_infer.onnx", 585_532,
                "e47acedf663230f8863ff1ab0e64dd2d82b838fceb5957146dab185a89d6215c"
            ),
            Mirror(
                HF + SWHL + "PP-OCRv1/ch_ppocr_mobile_v2.0_cls_infer.onnx", 585_532,
                "e47acedf663230f8863ff1ab0e64dd2d82b838fceb5957146dab185a89d6215c"
            )
        )
    )

    /**
     * 俄文（西里尔）识别 —— PP-OCRv5（实测远强于 v3：印刷体 CER 0.244 → 0.022，手写 0.963 → 0.347）。
     * 主源：HuggingFace 官方 `PaddlePaddle/cyrillic_PP-OCRv5_mobile_rec_onnx`（hf-mirror 可走）
     * 备源：ModelScope RapidAI/RapidOCR v3.9.2（同模型、不同转换，字节数不同 → 按镜像分别登记大小/SHA）
     */
    val REC_RU_SPEC = Spec(
        key = REC_RU,
        fileName = "rec_ru.onnx",
        label = "俄文识别",
        required = false,
        mirrors = listOf(
            Mirror(
                HFM + "PaddlePaddle/cyrillic_PP-OCRv5_mobile_rec_onnx/resolve/main/inference.onnx",
                8_048_799,
                "5371ee1ddaa7983cc62d0818d99e982b6804638c85e4f960d59a574094e172e5"
            ),
            Mirror(
                HF + "PaddlePaddle/cyrillic_PP-OCRv5_mobile_rec_onnx/resolve/main/inference.onnx",
                8_048_799,
                "5371ee1ddaa7983cc62d0818d99e982b6804638c85e4f960d59a574094e172e5"
            ),
            Mirror(
                "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.2/onnx/PP-OCRv5/rec/cyrillic_PP-OCRv5_rec_mobile.onnx",
                8_074_092,
                "90f761b4bfcce0c8c561c0cb5c887b0971d3ec01c32164bdf7374a35b0982711"
            )
        )
    )

    /** 西里尔字符表（PP-OCRv5，850 行；852 = 850 + blank + 末尾空格） */
    val DICT_RU_SPEC = Spec(
        key = DICT_RU,
        fileName = "dict_ru.txt",
        label = "西里尔字符表",
        required = false,
        mirrors = listOf(
            Mirror(
                "https://cdn.jsdelivr.net/gh/PaddlePaddle/PaddleOCR@main/ppocr/utils/dict/ppocrv5_cyrillic_dict.txt",
                2_781,
                "db40aa52ceb112055be80c694afdf655d5d2c4f7873704524cc16a447ca913ba"
            ),
            Mirror(
                GH + "dict/ppocrv5_cyrillic_dict.txt",
                2_781,
                "db40aa52ceb112055be80c694afdf655d5d2c4f7873704524cc16a447ca913ba"
            ),
            Mirror(
                "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.2/paddle/PP-OCRv5/rec/cyrillic_PP-OCRv5_rec_mobile/ppocrv5_cyrillic_dict.txt",
                2_781,
                "db40aa52ceb112055be80c694afdf655d5d2c4f7873704524cc16a447ca913ba"
            )
        )
    )

    /**
     * App 需要的全部文件：
     * 中英一套 + 俄文一套（识别时按语言自动选模型；详见 OcrEngine.recognize 的 AUTO 逻辑）。
     */
    val BATCH1: List<Spec> = listOf(DET_SPEC, REC_CH_SPEC, KEYS_CH_SPEC, REC_RU_SPEC, DICT_RU_SPEC)

    /** 含俄文在内的全部文件（第二批） */
    val ALL: List<Spec> = listOf(DET_SPEC, REC_CH_SPEC, KEYS_CH_SPEC, CLS_SPEC, REC_RU_SPEC, DICT_RU_SPEC)

    fun spec(key: String): Spec = when (key) {
        DET -> DET_SPEC
        REC_CH -> REC_CH_SPEC
        REC_RU -> REC_RU_SPEC
        CLS -> CLS_SPEC
        KEYS_CH -> KEYS_CH_SPEC
        DICT_RU -> DICT_RU_SPEC
        else -> throw IllegalArgumentException("未知 OCR 模型：$key")
    }

    fun humanSize(bytes: Long): String = when {
        bytes <= 0 -> "—"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }
}

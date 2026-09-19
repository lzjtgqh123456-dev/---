# OCR 模型源（公开直链实测）

> 目的：确定 App 首次下载 OCR 模型时该用哪些**公开直链**，给出实测过的 URL / 字节大小 / SHA-256 / Range 支持情况，
> 以及可直接抄进 Kotlin 的 `EXPECTED_SIZE` / `EXPECTED_SHA256` 常量。
> 实测时间：2026-09-19；实测环境：Windows + curl 8.21 + `tools/ocrenv`（onnxruntime 1.30.0）。
> 落盘位置：俄文模型已下载到 `D:\ai\models\ocr\ru\`；本文只写文档与模型文件，不改任何 Kotlin 代码。

---

## 一、结论速览

1. 六个文件**全部可用公开直链下载**，全部支持 HTTP Range（实测 206 + `Accept-Ranges: bytes`），断点续传可用。
2. 中英三件套来自 HF `SWHL/RapidOCR`；字符表来自 PaddleOCR 官方 GitHub raw；俄文来自 HF `deepghs/paddleocr`。
3. **hf-mirror.com 对 model / dataset 仓库现在都是 308 跳到 huggingface.co**（不再自己缓存文件），
   跳转后依然能 206 断点续传（实测全量下载 + 续传拼接后 SHA-256 与官方完全一致）。
   → 下载器**必须支持跟随 307/308/302 并透传 `Range` 头**，否则 hf-mirror 前缀会直接失败。
4. 俄文 rec 模型 + 字符表已实测落地；用共享 python + onnxruntime 均能 `InferenceSession` 加载并成功推理。
5. 备选俄文源（主源挂掉可回退）：HF `cycloneboy/cyrillic_PP-OCRv3_rec_infer` → `model.onnx`
   （8,971,985 B，与主源**数值等价**：同一输入下 max\|diff\| ≈ 7.7e-07，argmax 完全一致）。
6. 全套 6 个文件合计 **25,199,633 B ≈ 24.0 MiB**（中英四件套 16,215,257 B ≈ 15.5 MiB，俄文 8,984,376 B ≈ 8.6 MiB）。

---

## 二、六个文件直链实测表（hf-mirror 优先 + huggingface 官方）

直链格式：`https://hf-mirror.com/<repo>/resolve/main/<path>`（镜像）与 `https://huggingface.co/<repo>/resolve/main/<path>`（官方）。
`Range` 列 = `curl -r 0-1023` 实测结果（状态码 + `Content-Range`）。

| 用途 | 文件名 | 直链（hf-mirror 优先） | 直链（huggingface 官方） | 大小(B) | SHA-256 | Range 206 | 镜像实测链路 |
|---|---|---|---|---|---|---|---|
| 文本检测（PP-OCRv4） | `det.onnx` | `https://hf-mirror.com/SWHL/RapidOCR/resolve/main/PP-OCRv4/ch_PP-OCRv4_det_infer.onnx` | `https://huggingface.co/SWHL/RapidOCR/resolve/main/PP-OCRv4/ch_PP-OCRv4_det_infer.onnx` | 4,745,517 | `d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9` | ✅ 206 `bytes 0-1023/4745517` | 308 → 302 → 206 |
| 中英识别（PP-OCRv4） | `rec_ch.onnx` | `https://hf-mirror.com/SWHL/RapidOCR/resolve/main/PP-OCRv4/ch_PP-OCRv4_rec_infer.onnx` | `https://huggingface.co/SWHL/RapidOCR/resolve/main/PP-OCRv4/ch_PP-OCRv4_rec_infer.onnx` | 10,857,958 | `48fc40f24f6d2a207a2b1091d3437eb3cc3eb6b676dc3ef9c37384005483683b` | ✅ 206 `bytes 0-1023/10857958` | 308 → 302 → 206 |
| 方向分类（v2.0） | `cls.onnx` | `https://hf-mirror.com/SWHL/RapidOCR/resolve/main/PP-OCRv1/ch_ppocr_mobile_v2.0_cls_infer.onnx` | `https://huggingface.co/SWHL/RapidOCR/resolve/main/PP-OCRv1/ch_ppocr_mobile_v2.0_cls_infer.onnx` | 585,532 | `e47acedf663230f8863ff1ab0e64dd2d82b838fceb5957146dab185a89d6215c` | ✅ 206 `bytes 0-1023/585532` | 308 → 302 → 206 |
| 中英字符表 | `keys_ch.txt` | GitHub raw（hf-mirror 不代理 GitHub，见 §六） | `https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/main/ppocr/utils/ppocr_keys_v1.txt` | 26,250 | `a1c84d9bdb9ab29043c58896224d32941783eb821629618416dcb08f12886492` | ✅ 206 `bytes 0-1023/26250` | 直连 206（无跳转） |
| 俄文识别（cyrillic PP-OCRv3） | `rec_ru.onnx` | `https://hf-mirror.com/deepghs/paddleocr/resolve/main/rec/cyrillic_PP-OCRv3_rec/model.onnx` | `https://huggingface.co/deepghs/paddleocr/resolve/main/rec/cyrillic_PP-OCRv3_rec/model.onnx` | 8,983,966 | `befb0c18a5648d44c6e7891ea10d8ee8edd8ee98698e2b952c33474025273f31` | ✅ 206 `bytes 0-1023/8983966` | 308 → 302 → 206 |
| 俄文字符表 | `dict_ru.txt` | `https://hf-mirror.com/deepghs/paddleocr/resolve/main/rec/cyrillic_PP-OCRv3_rec/dict.txt` | `https://huggingface.co/deepghs/paddleocr/resolve/main/rec/cyrillic_PP-OCRv3_rec/dict.txt` | 410 | `369a82c6c8c479784a5d726448b83b1eafb5fef0a4129a5eaa3929625ddcd132` | ✅ 206 `bytes 0-409/410` | 308 → 307 → 206 |

> 中英四件套已由队友 pc-ref 落在 `D:\ai\models\ocr\ch\`（本地 `sha256sum` 实测与本表四项完全一致）。
> App 侧文件名 ↔ 源文件名的对应：`det.onnx` ← `ch_PP-OCRv4_det_infer.onnx`；`rec_ch.onnx` ← `ch_PP-OCRv4_rec_infer.onnx`；`cls.onnx` ← `ch_ppocr_mobile_v2.0_cls_infer.onnx`；`keys_ch.txt` ← `ppocr_keys_v1.txt`（改名下载即可，字节与 SHA 不变）。

要点：

- 大文件（.onnx）走 `huggingface.co` 时首跳是 **302**（跳到 `us.aws.cdn.hf.co` 的 xet-bridge 签名 URL）；小文本文件（dict/txt）首跳是 **307**（跳到 `/api/resolve-cache/...`）。
- `hf-mirror.com` 前缀首跳一律 **308 Permanent Redirect → huggingface.co**，之后链路与官方一致。**最终文件字节与官方完全一致**（见 §三）。
- `huggingface.co` 的 HEAD（`-I`）对 .onnx 返回 302，只有 GET + `-L` 才拿到 206；做存活探测时别用 HEAD 判大小。
- `Accept-Ranges: bytes` 在最终 CDN 响应上都存在；`Content-Range` 显示的 total 与上表大小逐字节一致。

### 校验方式（三选一，建议同时做前两项）

| 方式 | 命令 / 判定 | 说明 |
|---|---|---|
| 大小比对 | 本地字节数 == `EXPECTED_SIZE[文件名]` | 最便宜，能挡住截断/半截文件 |
| SHA-256 比对 | Windows：`certutil -hashfile rec_ru.onnx SHA256`；Linux/mac：`sha256sum` | 强校验，上表值与 HF LFS `oid` 一致 |
| 可加载性 | onnxruntime `InferenceSession(path)` 成功 + 输入输出 shape 符合预期 | 挡 "大小对但内容坏" 的极端情况；`.onnx` 首字节应为 protobuf 字段头（本例 `08 08`），不是 zip/HTML |

---

## 三、hf-mirror 跳转行为实测（与词典源经验一致）

词典源（`hf-mirror.com/datasets/SweetTouch/transpak/...`）此前的经验是"308 跳到 huggingface.co，实测仍能 206 断点续传"。
本次对 **model 仓库**（`SWHL/RapidOCR`、`deepghs/paddleocr`、`cycloneboy/...`、`sukode/RapidOCR`）逐个复核，行为**完全一致**：

| 请求 | 首跳 | 全链路 | 结果 |
|---|---|---|---|
| `HEAD/GET https://hf-mirror.com/<repo>/resolve/main/<file>.onnx` | **308**（Location: `https://huggingface.co/...`） | 308 → 302 → 206 | ✅ 拿得到文件 |
| `GET ... -r 0-1023`（Range） | **308** | 308 → 302 → 206 | ✅ `content-range: bytes 0-1023/8983966` |
| `GET` 小文本 .txt | **308** | 308 → 307 → 206 | ✅ `content-range: bytes 0-409/410` |
| `GET https://hf-mirror.com/api/models/<repo>` | **308** | — | ⚠️ API 端点也跳官方；不要在 App 里依赖 hf-mirror 的 API |
| `GET https://raw.githubusercontent.com/...`（GitHub） | 200/206 直连 | — | hf-mirror **不代理** GitHub raw，需另配 GitHub 加速前缀（见 §六） |

**端到端强证据（不只是看响应头）**：

1. 从 `hf-mirror.com` 全量下载 `rec_ru.onnx`：`200 OK`，`size_download=8983966`，`num_redirects=2` → SHA-256 `befb0c18…273f31`，与官方 `huggingface.co` 直链下载结果**逐字节相同**。
2. 断点续传：先写前 2,000,000 B，然后 `Range: bytes=2000000-` 走 hf-mirror → **206**，`content-range: bytes 2000000-8983965/8983966`，拼接后 SHA-256 仍为 `befb0c18…273f31`。

**对 App 下载器的硬性要求**

- 必须跟随 `301/302/303/307/308`；`307/308` 要**保留原请求方法与 `Range` 头**（OkHttp 默认保留，自研 `HttpURLConnection` 逻辑要注意 308 在同协议下会自动跟随、但 `Range` 需自己重发）。
- 跳转链最多 2 跳（hf-mirror）或 1 跳（官方），`maxRedirects ≥ 5` 足够。
- 跳转后 Host 会从 `hf-mirror.com` 变成 `huggingface.co` 再到 `*.cdn.hf.co`：**不要做 Host/域名白名单校验**，只校验最终文件大小 + SHA-256。
- hf-mirror 目前**不等于"国内加速"**（它自己跳回官方），保留它只是为了"前缀可切换 + 不阻断"；真正提升下载成功率要靠多源列表 + 断点续传 + 本地校验重试。

---

## 四、俄文模型来源与许可

| 项 | 内容 |
|---|---|
| 原始模型 | PaddleOCR 官方多语种识别模型 **`cyrillic_PP-OCRv3_mobile_rec`**（PP-OCRv3 西里尔文，`rec_image_shape: [3,48,320]`） |
| 官方仓库（Paddle 格式，作词表权威参考） | `https://huggingface.co/PaddlePaddle/cyrillic_PP-OCRv3_mobile_rec`（`inference.pdiparams` 8,934,498 B + `inference.yml`，其中 `PostProcess.CTCLabelDecode.character_dict` 共 **163** 个字符，与 `dict_ru.txt` 逐项一致） |
| 主源（ONNX，App 用） | `deepghs/paddleocr`（deepghs 把 PaddleOCR 官方模型批量转成 ONNX；`rec/cyrillic_PP-OCRv3_rec/model.onnx` + `dict.txt`） |
| 许可 | PaddleOCR 本体 **Apache-2.0**；`SWHL/RapidOCR` 仓库标注 `license: apache-2.0`；`deepghs/paddleocr` 为其对官方模型的格式转换产物，遵循同一上游许可。App 内"模型来源/许可"说明可直接写：**PaddleOCR (Apache-2.0)，模型由 deepghs/paddleocr 转为 ONNX**。 |
| 词表一致性验证 | `deepghs` 的 `dict.txt` 与 PaddleOCR 官方 GitHub `ppocr/utils/dict/cyrillic_dict.txt` **SHA-256 完全相同**（`369a82c6…cd132`，410 B）→ 转换过程未改词表。 |
| 数值验证 | 主源 `rec_ru.onnx` 与备选源 `cycloneboy/.../model.onnx` 在同一随机输入下 `max\|diff\| = 7.75e-07`、`mean\|diff\| = 2.48e-09`、argmax 完全一致 → 两者是同一套权重、不同转换管线。 |

### 已落盘文件（本机）

| 路径 | 大小(B) | SHA-256 | 说明 |
|---|---|---|---|
| `D:\ai\models\ocr\ru\rec_ru.onnx` | 8,983,966 | `befb0c18a5648d44c6e7891ea10d8ee8edd8ee98698e2b952c33474025273f31` | 主源 cyrillic rec onnx，已用 onnxruntime 加载 + 推理通过 |
| `D:\ai\models\ocr\ru\dict_ru.txt` | 410 | `369a82c6c8c479784a5d726448b83b1eafb5fef0a4129a5eaa3929625ddcd132` | 由源文件规范化为 **UTF-8 无 BOM、LF 换行、结尾带换行**；共 **163** 个非空字符行 |

`dict_ru.txt` 行信息（实测）：

- 字节 410；BOM：无；`\r`：0 个；`\n`：163 个（`split("\n")` 得 164 段，最后一段为空）。
- 非空字符行 = **163** 行。
- 首 5 行：`' '`（空格）、`!`、`#`、`$`、`%`
- 末 8 行：`ј`、`љ`、`њ`、`ћ`、`ў`、`џ`、`Ґ`、`ґ`

> 注意：与 `keys_ch.txt` 一样，**词表文件本身不含 blank**，调用方要自己补特殊符号（见 §八）。

---

## 五、onnxruntime 实测输出（证明模型可用、不是半截文件）

环境：`D:\ai\projects\study-assistant\tools\ocrenv\Scripts\python.exe`，onnxruntime **1.30.0**，providers = `['AzureExecutionProvider', 'CPUExecutionProvider']`。
方法：`InferenceSession(path, providers=["CPUExecutionProvider"])`，再用全 0 输入跑一次 `session.run`。

| 模型 | INPUT 名 / shape | OUTPUT 名 / shape | 全 0 输入推理输出 |
|---|---|---|---|
| `det.onnx` | `x` = `[N, 3, H, W]`（动态，`p2o.DynamicDimension.*`） | `sigmoid_0.tmp_0` = `[N, 1, H, W]` | `[1,1,64,64]` float32 ✅ |
| `rec_ch.onnx` | `x` = `[N, 3, ?, W]`（高固定、宽动态） | `softmax_11.tmp_0` = `[N, T, 6625]` | `[1,40,6625]` float32，max 0.9951 ✅ |
| `cls.onnx` | `x` = `[N, 3, ?, ?]` | `save_infer_model/scale_0.tmp_1` = `[N, 2]` | `[1,2]` float32，≈0.5/0.5 ✅ |
| `rec_ru.onnx`（主源） | `x` = `[N, 3, 48, ?]`（**高固定 48**，宽动态） | `softmax_2.tmp_0` = `[N, T, 165]` | `[1,40,165]` float32，max 0.9871 ✅ |
| `rec_ru` 备选（cycloneboy） | `x` = `[N, 3, 48, ?]` | `softmax_2.tmp_0` = `[N, T, 165]` | `[1,40,165]`，与主源 max\|diff\| 7.75e-07 ✅ |
| `rec_ru` 备选（sukode PP-OCRv4） | `x` = `[N, 3, ?, ?]` | `softmax_2.tmp_0` = `[N, T, 165]` | `[1,40,165]`，与主源 max\|diff\| 4.17e-07 ✅ |

对 App 的直接结论：

- **det**：一次前向输出单通道概率图（sigmoid），后处理走 DB（阈值 0.3 / box_thresh 0.6 / unclip 1.5）。
- **rec_ch**：输出 **6625** 类 = 6623（`keys_ch.txt` 非空行）+ 1（blank）+ 1（追加空格）→ `use_space_char=True`。
- **rec_ru**：输出 **165** 类 = 163（`dict_ru.txt` 非空行）+ 1（blank）+ 1（追加空格）→ 同样 `use_space_char=True`。
- 推理耗时未做基准（本机 CPU、全 0 输入），App 端应以真机实测为准。

---

## 六、`keys_ch.txt` 的取法与 GitHub 加速

`keys_ch.txt` 来自 GitHub（`SWHL/RapidOCR` 仓库里没有词表文件），hf-mirror **不代理** GitHub，所以它没有 hf-mirror 前缀。四个可选前缀（都已实测）：

| 方案 | URL | 大小(B) | SHA-256 | 实测 |
|---|---|---|---|---|
| 官方 main（上表采用） | `https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/main/ppocr/utils/ppocr_keys_v1.txt` | 26,250 | `a1c84d9bdb9ab29043c58896224d32941783eb821629618416dcb08f12886492` | ✅ 200 / 206 |
| GitHub 加速前缀（国内可用） | `https://cdn.jsdelivr.net/gh/PaddlePaddle/PaddleOCR@main/ppocr/utils/ppocr_keys_v1.txt` | 26,250 | 同上（实测字节一致） | ✅ 200 / 206 |
| **HF 托管等价源（可走 hf-mirror）** | `https://hf-mirror.com/deepghs/paddleocr/resolve/main/rec/ch_PP-OCRv4_rec/dict.txt` | 26,249 | `28b2362ad4ab2dc38769aa72feb535e3a9ddb3fd2a7585a05920e6393b1dc7f7` | ✅ 308 → 307 → 206 |
| 同上（官方前缀） | `https://huggingface.co/deepghs/paddleocr/resolve/main/rec/ch_PP-OCRv4_rec/dict.txt` | 26,249 | 同上 | ✅ 307 → 206 |

- `deepghs/.../rec/ch_PP-OCRv4_rec/dict.txt` 与 PaddleOCR `release/2.7` 分支的 `ppocr_keys_v1.txt` **逐字节相同**（26,249 B）；
  与 `main` 分支**字符内容完全相同，仅差结尾 1 个换行**（main 26,250 B，两者都是 6623 个非空行）。两者都可安全使用。
- 推荐：把 HF 托管版同时放进 hf-mirror 与官方前缀（这样"镜像前缀 + 官方前缀"的统一逻辑对 6 个文件都成立），GitHub raw / jsdelivr 作为额外兜底。
- 若只用 GitHub main：注意 main 分支内容**未来可能变**，一旦变化 SHA 校验会失败 → 见 §十一 的降级建议。

---

## 七、App 侧建议常量（可直接抄）

```kotlin
object OcrModelSpec {
    private const val MIRROR = "https://hf-mirror.com"
    private const val HF = "https://huggingface.co"

    /** 精确字节数：下载完成后必须严格相等，否则视为半截文件 */
    val EXPECTED_SIZE = mapOf(
        "det.onnx"     to 4_745_517L,
        "rec_ch.onnx"  to 10_857_958L,
        "cls.onnx"     to 585_532L,
        "keys_ch.txt"  to 26_250L,     // 若改用 release/2.7 或 deepghs 版则为 26_249L
        "rec_ru.onnx"  to 8_983_966L,
        "dict_ru.txt"  to 410L,
    )

    /** 强校验（推荐）：与 EXPECTED_SIZE 一起用 */
    val EXPECTED_SHA256 = mapOf(
        "det.onnx"    to "d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9",
        "rec_ch.onnx" to "48fc40f24f6d2a207a2b1091d3437eb3cc3eb6b676dc3ef9c37384005483683b",
        "cls.onnx"    to "e47acedf663230f8863ff1ab0e64dd2d82b838fceb5957146dab185a89d6215c",
        "keys_ch.txt" to "a1c84d9bdb9ab29043c58896224d32941783eb821629618416dcb08f12886492",
        "rec_ru.onnx" to "befb0c18a5648d44c6e7891ea10d8ee8edd8ee98698e2b952c33474025273f31",
        "dict_ru.txt" to "369a82c6c8c479784a5d726448b83b1eafb5fef0a4129a5eaa3929625ddcd132",
    )

    /** 每个文件的多源列表：镜像优先 → 官方兜底（顺序即重试顺序） */
    fun sourcesFor(fileName: String): List<String> = when (fileName) {
        "det.onnx" -> listOf(
            "$MIRROR/SWHL/RapidOCR/resolve/main/PP-OCRv4/ch_PP-OCRv4_det_infer.onnx",
            "$HF/SWHL/RapidOCR/resolve/main/PP-OCRv4/ch_PP-OCRv4_det_infer.onnx",
        )
        "rec_ch.onnx" -> listOf(
            "$MIRROR/SWHL/RapidOCR/resolve/main/PP-OCRv4/ch_PP-OCRv4_rec_infer.onnx",
            "$HF/SWHL/RapidOCR/resolve/main/PP-OCRv4/ch_PP-OCRv4_rec_infer.onnx",
        )
        "cls.onnx" -> listOf(
            "$MIRROR/SWHL/RapidOCR/resolve/main/PP-OCRv1/ch_ppocr_mobile_v2.0_cls_infer.onnx",
            "$HF/SWHL/RapidOCR/resolve/main/PP-OCRv1/ch_ppocr_mobile_v2.0_cls_infer.onnx",
        )
        "keys_ch.txt" -> listOf(
            "$MIRROR/deepghs/paddleocr/resolve/main/rec/ch_PP-OCRv4_rec/dict.txt",   // 26,249 B 等价源
            "$HF/deepghs/paddleocr/resolve/main/rec/ch_PP-OCRv4_rec/dict.txt",
            "https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/main/ppocr/utils/ppocr_keys_v1.txt",
            "https://cdn.jsdelivr.net/gh/PaddlePaddle/PaddleOCR@main/ppocr/utils/ppocr_keys_v1.txt",
        )
        "rec_ru.onnx" -> listOf(
            "$MIRROR/deepghs/paddleocr/resolve/main/rec/cyrillic_PP-OCRv3_rec/model.onnx",
            "$HF/deepghs/paddleocr/resolve/main/rec/cyrillic_PP-OCRv3_rec/model.onnx",
            "$MIRROR/cycloneboy/cyrillic_PP-OCRv3_rec_infer/resolve/main/model.onnx",  // 备选，见 §九
            "$HF/cycloneboy/cyrillic_PP-OCRv3_rec_infer/resolve/main/model.onnx",
        )
        "dict_ru.txt" -> listOf(
            "$MIRROR/deepghs/paddleocr/resolve/main/rec/cyrillic_PP-OCRv3_rec/dict.txt",
            "$HF/deepghs/paddleocr/resolve/main/rec/cyrillic_PP-OCRv3_rec/dict.txt",
        )
        else -> emptyList()
    }
}
```

下载器注意点（承 §三）：

- 6 个文件合计 **25,199,633 B ≈ 24.0 MiB**，UI 文案建议写"OCR 模型约 24 MB"（中英四件套 16,215,257 B ≈ 15.5 MiB，俄文两件 8,984,376 B ≈ 8.6 MiB）。
- `hf-mirror` 与 `huggingface.co` 的文件 SHA-256 完全相同，校验只需一套常量。
- 出现 `307/308` 时**必须重发 `Range`**；校验失败先重试同源，再换下一源。
- `.onnx` 落盘用 `.part` + 原子改名；完成后做 `size == EXPECTED_SIZE` + `sha256 == EXPECTED_SHA256` 双校验再标记可用。

---

## 八、字符表规则（rec 解码，别踩坑）

PaddleOCR `CTCLabelDecode` 的字符表构造是 `['blank'] + 词表行 + [' ']`（`use_space_char=True`）。实测两个 rec 模型的输出维度正好对上：

| 模型 | 词表文件 | 词表非空行 | 追加 | 输出类别数（实测） |
|---|---|---|---|---|
| `rec_ch.onnx` | `keys_ch.txt` | 6623 | +blank +空格 | **6625** ✅ |
| `rec_ru.onnx` | `dict_ru.txt` | 163 | +blank +空格 | **165** ✅ |

- 索引 0 = blank（CTC 的 `-`），**不要**把词表第 1 行当成 index 0。
- `dict_ru.txt` 的第 1 行**本身就是一个空格字符**（`' '`）；PaddleOCR 官方 `inference.yml` 的 `character_dict` 序列与之逐项一致（163 项），所以按上面规则构造即可与官方推理对齐。
- 中英模型输出含空格类别；解码后按行拼接时注意保留词间空格。

---

## 九、备选俄文 rec 源（主源挂掉时回退）

| 优先级 | 源 | 直链 | 大小(B) | SHA-256 | 类别数 | 备注 |
|---|---|---|---|---|---|---|
| **备选 1（推荐）** | HF `cycloneboy/cyrillic_PP-OCRv3_rec_infer` → `model.onnx` | `https://hf-mirror.com/cycloneboy/cyrillic_PP-OCRv3_rec_infer/resolve/main/model.onnx`（官方把前缀换成 `https://huggingface.co` 同路径） | 8,971,985 | `d35f33a21833d065edf5720731b3b87579fb5901f0e2bef36fe0fd22ad16de0b` | 165 | 已实测下载 + 加载 + 推理；与主源**数值等价**（max\|diff\| 7.75e-07、argmax 全同）；Range 206（hf 与 hf-mirror 都验过） |
| 备选 2 | HF `sukode/RapidOCR`（RapidOCR 风格转换） | `https://hf-mirror.com/sukode/RapidOCR/resolve/main/onnx/PP-OCRv4/rec/cyrillic_PP-OCRv3_rec_mobile.onnx` | 8,972,413 | `1efb65bdc460af1c0e8733d005b20952b17ca5aac10ddb56c968333791c5eaa3` | 165 | 已实测下载 + 加载 + 推理，与主源数值等价（4.17e-07）；同文件亦见 `pitapo/rapidocr`（`onnx/PP-OCRv4/rec/cyrillic_PP-OCRv3_rec_infer.onnx`，同 SHA-256） |
| 备选 3（升级选项，非直接替换） | HF `sukode/RapidOCR` → PP-OCRv5 西里尔文 | `https://huggingface.co/sukode/RapidOCR/resolve/main/onnx/PP-OCRv5/rec/cyrillic_PP-OCRv5_rec_mobile.onnx` | 8,074,092 | `90f761b4bfcce0c8c561c0cb5c887b0971d3ec01c32164bdf7374a35b0982711` | **852** | 输出 852 类 → 词表不同，**必须换配套 PP-OCRv5 西里尔词表**并重做前后处理对齐，不能直接顶替 v3 |
| 上游（Paddle 原始格式，仅供词表/配置核对） | HF `PaddlePaddle/cyrillic_PP-OCRv3_mobile_rec` | `https://huggingface.co/PaddlePaddle/cyrillic_PP-OCRv3_mobile_rec/resolve/main/inference.pdiparams` | 8,934,498 | `2ef815afbb9070610618561946ce86faf60745ada64cd316ed34bfe34bdbf46f` | — | 官方 `inference.yml` 给出 `rec_image_shape: [3,48,320]` 与 163 项字符表，是词表权威参考 |

> 备选 1 另有 `fp16_model.onnx`（4,533,482 B，SHA-256 `1becd836af63c71c57322fd1941855e9e2a666ee82cc2b46c2ab7e47dfd49271`）体积更小，日后若要压包体可评估，但需真机验证精度与 ORT 的 fp16 支持。

---

## 十、复现步骤（全部本地可跑）

```bash
# 0. 直链实测（状态码 / Range / 跳转链）
curl -sS -L -o /dev/null -D - -r 0-1023 \
  "https://hf-mirror.com/deepghs/paddleocr/resolve/main/rec/cyrillic_PP-OCRv3_rec/model.onnx"

# 1. 下载俄文模型 + 词表
mkdir -p /d/ai/models/ocr/ru
curl -sS -L -o /d/ai/models/ocr/ru/rec_ru.onnx \
  "https://huggingface.co/deepghs/paddleocr/resolve/main/rec/cyrillic_PP-OCRv3_rec/model.onnx"
curl -sS -L -o /d/ai/models/ocr/ru/dict_ru.txt \
  "https://huggingface.co/deepghs/paddleocr/resolve/main/rec/cyrillic_PP-OCRv3_rec/dict.txt"

# 2. 校验
sha256sum /d/ai/models/ocr/ru/rec_ru.onnx /d/ai/models/ocr/ru/dict_ru.txt

# 3. onnxruntime 加载 + 形状
/d/ai/projects/study-assistant/tools/ocrenv/Scripts/python.exe - <<'PY'
import onnxruntime as ort, numpy as np
s = ort.InferenceSession(r"D:\ai\models\ocr\ru\rec_ru.onnx", providers=["CPUExecutionProvider"])
for i in s.get_inputs():  print("IN ", i.name, i.shape, i.type)
for o in s.get_outputs(): print("OUT", o.name, o.shape, o.type)
out = s.run(None, {s.get_inputs()[0].name: np.zeros((1,3,48,320), np.float32)})
print("run ok:", out[0].shape)   # -> (1, 40, 165)
PY
```

---

## 十一、风险与待办建议

1. **社区镜像仓库可能被删/改名**：`deepghs/paddleocr`、`cycloneboy/...`、`sukode/RapidOCR` 都是公开个人/组织仓库，随时可能变动 → App 端务必多源 + SHA-256 校验；§九 的 3 个备选建议直接进源列表。
2. **GitHub main 分支漂移**：`ppocr_keys_v1.txt` 在 main 上未来可能改字（`release/2.7` 与 main 目前只差结尾换行）→ 建议"大小不符直接失败；SHA 不符警告并允许继续（或维护 SHA 白名单）"，避免模型能跑却因词表校验全部下不动。
3. **不要用 HF API 端点做可用性探测**（hf-mirror 的 `/api/...` 也 308，并消耗官方速率限制）；直接探 resolve 直链。
4. **未做**：App 端 Kotlin 代码（本次约束禁止改动）；真机下载速度/成功率基准；PP-OCRv5 西里尔文模型的精度对比。
5. 中英四件套（`det.onnx`/`rec_ch.onnx`/`cls.onnx`/`keys_ch.txt`）由队友 pc-ref 正式落在 `D:\ai\models\ocr\ch\`（文件名 `ch_PP-OCRv4_det_infer.onnx` / `ch_PP-OCRv4_rec_infer.onnx` / `ch_ppocr_mobile_v2.0_cls_infer.onnx` / `ppocr_keys_v1.txt`），本地实测 sha256 与 §二 表中四项**逐项一致**（已交叉验证）；俄文正式文件在 `D:\ai\models\ocr
u\`（`rec_ru.onnx` + `dict_ru.txt`）。
   本人核验中英模型时用的临时副本在 `%TEMP%\ocr-verify\`，纯验证用、可随时按 §二 重新下载。

---

# 十二、俄文 / 手写模型选型（t3，2026-09-19 实测）

> 背景：用户反馈"西里尔字母识别错误率极高"。本轮用同一套测试图把「App 现在的中英模型 / 俄文 v3 / 俄文 v5」跑了一遍，
> 并调研 + 落地了手写候选。**rec-only 评测**（测试图都是行级裁剪或 pc-ref 行框，det 不参与），因此数字只反映识别模型本身。

## 12.1 推荐顺序（结论先行）

| 优先级 | 动作 | 模型 | 体积 | 依据 |
|---|---|---|---|---|
| **P0 立即换** | 俄文 rec：v3 → **PP-OCRv5 西里尔** | `cyrillic_PP-OCRv5_mobile_rec`（官方 ONNX） | 8.0 MB | 印刷体 CER 0.244 → **0.022**（精确匹配 13% → 87%），手写 CER 0.963 → **0.347** |
| **P1 下一批** | 手写模式：新增**手写专用 CRNN**（**已导出 ONNX 并实测**） | `Hukyl/crnn-rukopys` → ONNX | 25 MB（INT8 约 7 MB） | 6.25M 参数、CTC、无 tokenizer、Apache-2.0；**实测手写 CER 0.237（v5 0.347）/ 0.221（v5 0.583）**，CPU 8.9 ms/行（v5 20.5 ms）；**但真实手写整页 CER 88.7%，只是 baseline 不是可用产品**（详见 §12.4.3） |
| P2 备选 | 手写更强但更重 | `Hukyl/parseq-s-cyrillic-handwritten`（96.9 MB）/ `cyrillic-trocr/trocr-handwritten-cyrillic`（1.34 GB） | 96.9 MB / 1.34 GB | PARSeq 需自己导出；TrOCR 需 BPE + 自回归解码，手机端不现实（PC 参考） |

**先做 P0 就能把用户看到的问题解决大半**：现在 App 只有中英模型，对西里尔输出的是拉丁近似转写（`Расписание` → `PaCnMCaHMe`），属于"语言错了"，不是"精度不够"。

## 12.2 PP-OCRv5 西里尔 rec（首选升级项）· 落地与实测

已下载到 `D:\ai\models\ocr\ru_v5\`：

| 文件 | 大小(B) | SHA-256 | 来源 | 实测 |
|---|---|---|---|---|
| `rec_ru_v5.onnx`（**推荐主源**） | 8,048,799 | `5371ee1ddaa7983cc62d0818d99e982b6804638c85e4f960d59a574094e172e5` | HF 官方 `PaddlePaddle/cyrillic_PP-OCRv5_mobile_rec_onnx/inference.onnx` | hf-mirror：308 → 302 → 206；`bytes 0-1023/8048799` |
| `rec_ru_v5_rapidocr.ms.onnx`（备源，**字节与主源不同**） | 8,074,092 | `90f761b4bfcce0c8c561c0cb5c887b0971d3ec01c32164bdf7374a35b0982711` | ModelScope `RapidAI/RapidOCR` v3.9.2 `onnx/PP-OCRv5/rec/cyrillic_PP-OCRv5_rec_mobile.onnx` | ModelScope：302 → 206；与 HF `sukode/RapidOCR` 同路径文件的 LFS oid **完全相同**（同字节镜像） |
| `dict_ru_v5.txt` | 2,781 | `db40aa52ceb112055be80c694afdf655d5d2c4f7873704524cc16a447ca913ba` | GitHub raw `ppocr/utils/dict/ppocrv5_cyrillic_dict.txt` | GitHub raw / jsdelivr / ModelScope **三处字节完全一致**；850 行、无 BOM、结尾带换行 |
| `_official_inference.yml` | 6,991 | `5c76cc91fa98410178a09f498db10050d0ec1634a660053d3005ab7be581f501` | 官方 repo `inference.yml` | 内含权威 `character_dict`（850 项），与词表 txt 逐项一致（仅 YAML 对 `'` 的转义写法不同） |

**ONNX 实测（onnxruntime 1.30）**

| 模型 | INPUT | OUTPUT | 推理 |
|---|---|---|---|
| 官方 `rec_ru_v5.onnx` | `x` = `[N, 3, 48, W]`（高固定 48、宽动态） | `fetch_name_0` = `[N, T, **852**]` | 全 0 输入 → `[1,40,852]` ✅ |
| RapidOCR/ModelScope 版 | `x` = `[N, 3, 48, W]` | `fetch_name_0` = `[N, T, 852]` | `[1,40,852]` ✅，与官方版 `max\|diff\| = 1.49e-07`、argmax 全同（同一套权重，不同转换管线）；**同一套权重、不同转换管线 → 数值等价，任选其一** |

**词表（850 行）与 v3（163 行）的差异**

- 850 + blank + 追加空格 = **852** 类（`use_space_char=True`）。
- v5 比 v3 **多 688 个字符**：全拉丁 `A-Za-z`（52 个）、希腊字母、数学/排版符号（`∑∫≈≤≥№…`）、上下标、圈号数字、以及大量**扩展西里尔**（`Ꙁ…ꚟ`、`ⷠ…ⷿ`、`ѠѡѢѣ…`）。
- v3 有而 v5 没有的只有 **1 个**：空格行（v3 词表首行是空格字符；v5 词表**没有空格行**，空格只由"追加空格"提供）。
- ⚠️ 因此 **v3 与 v5 的解码表不能复用**：v5 是 `['blank'] + 850 行 + [' ']`，v3 是 `['blank'] + 163 行 + [' ']`（v3 的 index 1 恰好是空格）。

**预处理（官方 `inference.yml` + 实测）**

- `DecodeImage: img_mode BGR`；`RecResizeImg: image_shape [3,48,320]`；宽度按比例缩放到 48 高后右侧补零到 `max(320, 48*w/h)`。
- 归一化实测：`(x/255 - 0.5)/0.5` 略优于 `x/255`（手写集 CER 0.583 vs 0.607），**采用 `(x/255-0.5)/0.5`**。
- `inference.yml` 里没有 `NormalizeImage` 算子，容易误以为要自己传原图 0-255——实测按 `(x/255-0.5)/0.5` 才是对的。

## 12.3 手写西里尔候选（按可行性排序）

| # | 仓库 / 直链 | 许可 | 体积 / 参数量 | 架构 & I/O | 需要 tokenizer? | 现成 ONNX? | 预期速度（CPU） | 结论 |
|---|---|---|---|---|---|---|---|---|
| **1** | `Hukyl/crnn-rukopys`<br>`https://hf-mirror.com/Hukyl/crnn-rukopys/resolve/main/best.pt`<br>（HF: `https://huggingface.co/Hukyl/crnn-rukopys/resolve/main/best.pt`） | **Apache-2.0** | **25,031,291 B**<br>sha256 `add8c3f1d5757574d6f9620fc2a951ca59d0b1f1f4f4c835658ca15468c79870`<br>6,254,679 参数 | CRNN：6×Conv → BiLSTM(256) → Linear → CTC<br>IN 灰度 `[N,1,32,W]`（W≤512）<br>OUT `[T,N,341]` log_softmax | 否（词表在 ckpt 的 `chars` / `config.json`，340 + blank） | ✅ **已导出**：`crnn_rukopys.onnx` 25,011,512 B / sha256 `daf33d37f03cea0c641830ddaaeff38babca7722b1b5ed9c60327750155c22c9`（IN `x`[N,1,32,W] → OUT `log_probs`[T,N,341]） | **8.9 ms/行**（CPU 实测） | **手写首选**：手机可承受、CTC 解码与现有管线同构；作者 Rukopys gold-val CER 0.1539 / WER 0.4188 |
| 2 | `Hukyl/parseq-s-cyrillic-handwritten`<br>`https://hf-mirror.com/Hukyl/parseq-s-cyrillic-handwritten/resolve/main/best.pt` | CC-BY-SA-4.0 | 96,899,202 B<br>sha256 `740c14f3eb61c97faa652a26a8c3f3521fcc9d8a8b202cd2be38aa838161799c`<br>（PARSeq-small，约 24M 参数） | PARSeq（ViT 编码 + 自回归字符解码，内部 6 次细化迭代） | 否，但需 PARSeq 代码里的 charset 与解码循环 | ❌ 需自己导出（需 PARSeq 源码） | 中等（比 CRNN 慢数倍） | 备选：手写场景通常比 CRNN 强，但 4 倍体积 + 导出更麻烦 |
| 3 | `cyrillic-trocr/trocr-handwritten-cyrillic`<br>`https://huggingface.co/cyrillic-trocr/trocr-handwritten-cyrillic/resolve/main/model.safetensors` | MIT | 1,335,747,032 B<br>sha256 `eb84f2d4f7c1d5b30526e131a5293f9365bd48dcab70bdffc0256926c5d78e13`<br>（TrOCR-base，约 334M 参数） | TrOCR：ViT 编码 + Transformer 解码（逐 token 自回归） | **是**（`vocab.json`+`merges.txt` BPE，`tokenizer.json` 3.5 MB） | ❌ 需自己导出（编码器+带 cache 的解码器两张图） | 慢（自回归 20+ 步；手机端几乎不可用） | PC 端参考 / 服务器兜底，不进入 App 默认包 |
| 4 | `Kansallisarkisto/cyrillic-large-handwritten-onnx`（= `stmz1337/...` 同文件） | Apache-2.0 | encoder 1,217,831,384 + decoder 1,224,961,526 + tokenizer.json 5,384,185（共 **2.45 GB**） | TrOCR-large，**已是 ONNX** | 是（tokenizer.json） | ✅ 已有 | 很慢 | "已 ONNX 但 2.4 GB"——只适合 PC/离线批处理，手机不现实 |
| 5 | `Hukyl/trocr-rukopys` / `Hukyl/trocr-large-rukopys-hw` | **未声明** / Apache-2.0 | 1,339.3 MB / 2,236.9 MB | TrOCR base/large | 是 | ❌ | 慢 | 精度上限高（rukopys CER 0.1146），但体积与许可都不适合直接进 App |
| 6 | EasyOCR `cyrillic.zip`（VGG-BiLSTM-CTC）<br>`https://github.com/JaidedAI/EasyOCR/releases/download/pre-v1.1.6/cyrillic.zip` | Apache-2.0 | 199,879,414 B（GitHub release asset，Range 206 实测通过） | CRNN（VGG 特征 + BiLSTM + CTC） | 否（内置字符集） | ❌ 需自己导出（PyTorch→ONNX） | 中等（约 100M 参数级） | 覆盖印刷体为主，手写是弱项；作为"多语言 CRNN 参考" |

> 说明：`kazars24/trocr-base-handwritten-ru`（1,335,747,032 B，`cyrillic-trocr` 的底座）**未声明许可**，用于商业 App 前需谨慎。

**已下载落地**（本轮"最有希望的 1 个手写候选"）：

| 路径 | 大小(B) | SHA-256 |
|---|---|---|
| `D:\ai\models\ocr\hw\crnn_rukopys_best.pt` | 25,031,291 | `add8c3f1d5757574d6f9620fc2a951ca59d0b1f1f4f4c835658ca15468c79870`（经 hf-mirror 下载，与 HF LFS oid 一致） |
| `D:\ai\models\ocr\hw\crnn_rukopys_config.json` | 5,268 | `7c6e4b870a2c8baace53b6c18ad8e5cad2aa99552c1fbfa36bfb5a37cc2ca3e4` |
| `D:\ai\models\ocr\hw\export_crnn_rukopys_onnx.py` | — | 一键导出脚本（含模型定义、导出、随词表落盘、ORT 自检） |

**导出难度与做法**（共享 venv 无 torch，未在本机执行导出）：模型是单次前向的 CNN→BiLSTM→CTC，**没有自回归、没有 tokenizer**，权重与结构在 HF README 里公开，因此：

```bash
pip install torch --index-url https://download.pytorch.org/whl/cpu    # CPU 版约 200MB
python D:\ai\models\ocr\hw\export_crnn_rukopys_onnx.py                # 产出 crnn_rukopys.onnx（约 25MB）
# 可选：onnxruntime.quantization 动态量化 INT8 → 约 7MB
```

导出后 App 侧接线方式与 PP-OCR 的 rec 完全同构（灰度、1×32×W、`x/127.5-1.0` 归一化、CTC blank=0），可以直接挂在现有 rec 位置上做一个"手写模式"开关。

## 12.4 CER 粗测（同一批测试图）

**测试集 A = pc-ref 的共享图 `tools/ocr_ref/img/ru_bench/`**（我只读，未改动他人文件）
- printed：8 张页面图，按 pc-ref 的 `gt/*.boxes.json` 行框透视裁剪出 **23 行**（裁剪脚本见 `bench_ru_bench.py`；注意 `cv2.minAreaRect` 的 180° 翻转坑，已改用 sum/diff 点序）
- handwritten：`handwriting_manifest.json` 的 **8 行**合成手写行图

| 模型（rec-only） | 印刷体 23 行 CER / CER_dict / 精确匹配 | 手写 8 行 CER / CER_dict / 精确匹配 |
|---|---|---|
| `ch_v4`（App 现在用的中英模型） | 0.692 / 1.949 / 0.00 | 0.942 / 6.077 / 0.00 |
| `ru_v3`（deepghs cyrillic_PP-OCRv3，统一 norm `(x/255-0.5)/0.5`） | 0.249 / 0.238 / 0.13 | 0.932 / 0.931 / 0.00 |
| **`ru_v5`（PaddlePaddle 官方 ONNX）** | **0.022 / 0.002 / 0.87** | **0.347 / 0.347 / 0.12** |
| `ru_v5`（RapidOCR/ModelScope 转换版） | 0.022 / 0.002 / 0.87 | 0.347 / 0.347 / 0.12 |

`CER_dict` = 先把 GT 里**该模型词表装不下的字符**删掉再算（区分"读错"与"词表根本装不下"）：
`ru_v5` 印刷体 0.022 → **0.002**（它的错几乎全是"GT 里有它词表外的符号"）；`ru_v3` 基本不变（0.249 → 0.238，是**真的读错**）；
`ch_v4` >100%（分母被删得很小）——即中英模型的问题不是词表，而是**语言完全不对**。

> **口径**：CER = 编辑距离总和 / GT 字符数总和（NFC + 空白折叠归一化后）；“完全正确率” = 归一化后逐行完全相等。本轮已与 pc-ref 的 `ru_bench_report.md` 统一口径并重跑全部数字（此前我用的是只 strip 首尾空白的宽松版）。注意完全正确率对**裁剪留白**较敏感：我的裁剪带 2px 白边、pc-ref 更贴行框，因此 CRNN 印刷体是 8/23 与 6/23 的差别（CER 只差 0.6pp）。pc-ref 已复核：他的行框裁剪比我的小 4px（同一行 362×28 vs 366×32，源于 `get_rotate_crop_image` 对边长做 `int()` 截断）。→ **跨实现引用“完全正确率”时请标注“对裁剪留白敏感，可差 ~1pp”**。

### 12.4.1 与 pc-ref 独立实现交叉验证（两套代码 / 同一批图）

pc-ref 用 `tools/ocr_ref/bench_rec.py`（复用 `pc_ocr_ref.py` 参考前处理 + GT 行框裁剪）独立跑的结果（报告：`tools/ocr_ref/out/ru_bench_report.md`）：

| 模型 | 我这套 printed CER / 精确 | pc-ref printed CER / 精确 | 我这套 hw CER / 精确 | pc-ref hw CER / 精确 |
|---|---|---|---|---|
| `ch_v4` | 69.2% / 4.3% | 70.2% / 4.3% | 94.2% / 0.0% | 93.2% / 0.0% |
| `ru_v3` | 24.9% / 13.0% | 25.9% / 8.7% | 93.2% / 0.0% | 94.2% / 0.0% |
| `ru_v5` | **2.2% / 87.0%** | **2.4% / 82.6%** | **34.7% / 12.5%** | **34.7% / 12.5%** |
| `ru_v5` CER_dict（印刷体） | 0.15% | 0.3% | — | — |
| `crnn_rukopys`（手写专用） | 14.6% / 34.8% | 15.2% / 26.1% | **23.7% / 0.0%** | **23.7% / 0.0%** |

两套独立实现差异 ≤1.4 个百分点（裁剪留白 / 空格归一化的小差别），**结论一致**。pc-ref 还多跑了一组 `handwritten_page`（3 张真实手写页，走完整 det+rec）：`ru_v5` CER **96.5%**、完全正确 0% ——**真实手写页面上 PP-OCR 基本不可用**，进一步支持 §12.3 的专用手写模型路线。

### 12.4.2 评测实现的两个坑（已修正，复现时注意）

1. **行框裁剪的朝向**：`cv2.minAreaRect` 的 `boxPoints` 起点/绕向不保证是"左上→顺时针"，直接映射到 `[[0,0],[w,0],[w,h],[0,h]]` 会得到**转置/倒置**的裁剪图（表现为 32×366 而不是 366×32）。转置后 `ratio = w/h = 0.087`，预处理把宽度压到 5px，模型只看到一条竖缝 → 输出空串。
   **修正**：用 sum/diff 法自己排 `tl/tr/br/bl`（`bench_ru_bench.py::order_quad`）；重跑后自检裁剪图为 367×33 / 681×46 / 321×28（宽×高）✅。
   ⚠️ 2026-09-19 16:05 那一版结果因该问题**作废**；本表是修正后重跑的（pc-ref 已独立确认）。
2. **归一化统一**：PP-OCR 系列 rec 一律 `(x/255-0.5)/0.5`（SPEC §7）；`ru_v3` 之前并行跑过 `x/255` 变体，现统一为 0.5 以与 App 端一致。

**测试集 B = 自建集**（`D:\ai\models\ocr\bench\`：20 行 PIL 渲染印刷体 + 25 行 `deepcopy/synthetic-handwritten-cyrillic-180k` 合成手写）

| 模型 | 印刷体 20 行 CER | 手写 25 行 CER |
|---|---|---|
| `ch_v4` | 0.838 | 0.905 |
| `ru_v3`（norm `(x/255-0.5)/0.5` / norm `x/255`） | 0.196 / **0.176** | 0.874 / 0.884 |
| **`ru_v5`** | **0.004** | **0.583**（norm `x/255` 时 0.607） |

典型样例（v5 vs 现状）：

| 真值 | `ch_v4`（App 现状） | `ru_v3` | `ru_v5` |
|---|---|---|---|
| `Москва — столица России.` | `MocKBa — CTonNua PocCUN.` | `MockBa -- cToлицa Poccии.` | ✅ `Москва — столица России.` |
| `Студенческий билет № 24517` | `CtyeH4eckni 6wnet No 24517` | `Студенческий билет о 2А5ут` | ✅ 原样 |
| `Попри поетичний темперамент,`（手写） | `Tonpu noemutmua meune raueum,` | `I Iou nde'murMuta me u ne rauerm.` | `П опри поетигний темпе рамент,` |

**结论**：①现状（中英模型读西里尔）是"语言级错误"，必须换模型；②v5 在印刷体上接近可用（CER 2%、87% 整行全对）；③**手写仍是短板（CER 35%~58%）**，需要专用手写模型，这就是 §12.3 候选存在的意义。

### 12.4.3 手写专用 CRNN（已导出 ONNX）vs PP-OCRv5 西里尔 · 实测对比

`crnn_rukopys.onnx` 用本机 ComfyUI 的 torch 2.10 导出（脚本 `D:\ai\models\ocr\hw\export_crnn_rukopys_onnx.py`），
25,011,512 B，sha256 `daf33d37f03cea0c641830ddaaeff38babca7722b1b5ed9c60327750155c22c9`，IN `x`[N,1,32,W] → OUT `log_probs`[T,N,341]。

| 测试集（rec-only） | `crnn_rukopys`（25 MB，手写专用） | `ru_v5`（8 MB，印刷专用） |
|---|---|---|
| pc-ref printed 23 行 CER / 完全正确 | 0.146 / 0.348 | **0.022 / 0.870** |
| pc-ref handwritten 8 行 CER / 完全正确 | **0.237 / 0.000** | 0.347 / 0.125 |
| 自建印刷 20 行 CER / 完全正确 | 0.120 / 0.050 | **0.004 / 0.950** |
| 自建手写 25 行 CER / 完全正确 | **0.221 / 0.160** | 0.583 / 0.160 |
| CPU 速度（本机实测，样本 6 图 × 3 轮） | **8.9 ms/行** | 20.5 ms/行 |

结论与建议：

1. **手写：CRNN 明显更好**（0.237 vs 0.347；0.221 vs 0.583），而且**速度还快 2.3 倍**（6.25M 参数、单次前向、序列极短），特别适合手机。
2. **印刷：CRNN 也能读**（CER 0.12~0.15）但明显不如 v5（0.004~0.022），典型错误是大小写与形近字（`язык`→`язьк`、`математике`→`матоматнкс`）——它是在乌克兰档案馆手写语料（Rukopys）上从零训练的。
3. **推荐分工**：默认走 `ru_v5`（印刷体）；"手写模式"（用户手动开）走 `crnn_rukopys`。
   自动路由（先 v5 → 置信度低再用 CRNN 复跑）可以作为下一步优化，但**建议先做手动开关**，避免路由误判带来"越修越差"。
4. 体积账：v5 rec 8.0 MB + det v5 4.8 MB + 手写 CRNN 25.0 MB ≈ **37.8 MB**；若把手写包做成可选下载，默认包仍是 12.8 MB。
5. 典型样例（手写 8 行集）：
   - GT `Попри поетичний темперамент,` → CRNN `П опри поетичний те мпе рамент,`（v5 `П опри поетигний темпе рамент,`）
   - GT `(притока Дудвагу) Турець` → CRNN `(притока 2удвагу) Турець`（v5 `(притока Дуаy) Пуpе`）

6. **真实手写整页**（pc-ref 独立跑，同 3 张真扫页）——这是“能不能上产品”的关键数据：

| 方案 | CER | 字符恢复率 |
|---|---|---|
| `crnn_rukopys` + det（SPEC 走 det 切行） | 0.930 | 7.7% |
| `crnn_rukopys` + 跳过 det（行投影切行） | **0.887** | 13.9% |
| `ru_v5` + det / + 投影（对照） | 0.965 / 0.936 | — |

   → **CRNN 在所有模型里最好，但整页 CER 88.7% 仍不可用**。pc-ref 还验证了“长行 512px 截断”**不是**瓶颈（按 16×h 分块再喂，CER 只从 0.870→0.867），所以整页失败不是宽度被压扁导致的。
7. **收口结论（t3）**：`crnn_rukopys` 是**可用的第一步 / 明确的 baseline，不是可用产品**——341 类、32px 高、512 宽上限、Apache-2.0、8.9 ms/行，适合当起点；若产品要求手写可用，正确路线是在这个 baseline 上针对目标语料（俄文手写、手机拍摄）微调，而不是直接上线。

### 12.4.4 真实截图（真机截图，无人工 GT → 用“多模型共识 GT”补出这一列）

背景：pc-ref 指出 `img/real_ru/` 的 4 张真机截图没有 GT，所以“真实印刷体”一直只有定性。本机又没有俄语 OCR 语言包（Windows OCR 只能输出拉丁转写、rapid/paddle 引擎未安装），因此我用**三个独立模型（ru_v5 / ru_v3 / crnn_rukopys）跑同一条 det+rec 管线**，逐行取共识再用俄语语法合理性校验，整理出两张标准练习题的 GT（放在 `D:\ai\models\ocrench
eal_ru_gt\`，并复制到 `tools/ocr_ref/img/ru_bench/scout_gt/`）。**这不是人工逐字转写**，建议抽查后再引用。

| 截图 | GT 字符数 | `ru_v5` | `ch_v4`（App 现状） | `ru_v3` | `crnn_rukopys` |
|---|---|---|---|---|---|
| Screenshot_20220331_113023 | 431 | **0.0162** | 0.7981 | 0.5592 | 0.0441 |
| Screenshot_20220331_113947 | 309 | **0.0227** | 0.6602 | 0.4984 | 0.1294 |

结论：

1. **在真实手机截图上，App 现状（中英模型）CER 66%~80%**——这与用户反馈的“西里尔字母识别错误率极高”完全吻合，是最贴近真实场景的一组证据（此前只有合成图/扫描页）。
2. `ru_v5` 在真实截图上 1.6%~2.3%，与合成印刷体的 2.2% 一致 ⇒ 印刷体这条线可以收口。
3. `crnn_rukopys` 在印刷截图上 4.4%~12.9%，仍然明显不如 v5（它也不是印刷专用模型）。
4. ⚠️ **循环性说明**：GT 部分来自 v5 输出，评 v5 有轻微循环性（残差 1.6%~2.3% 是我修正过 v5 错误之后的值，不是 0）；评 ch_v4 / ru_v3 / crnn 则是公平的。

### 12.4.4b PC-ref 复核与独立复现（这一列已闭环）

pc-ref 复核了这份 GT 并**通过**：拉丁混入西里尔单词数 = 0、纯拉丁词 = 0（排除了把 `ru_v3` 同形字输出误当 GT 的风险）；
我人工修正的三处，正好都是三个模型同时读错的位置（`магазин, , поликлиника` → `магазин, поликлиника`；`парк гостиница` → `парк, гостиница`；
`1.Вбиблиотеке` → `1. В библиотеке`），说明校验收敛在模型都错的地方、而非照抄某个模型。

他用**自己的** det / 投影两条管线独立跑出的真实截图 CER（不是引用我的数字）：

| 截图 | GT 字符 | `ru_v5` det / proj | `ch_v4` det / proj | `ru_v3` det / proj | `crnn` det / proj |
|---|---|---|---|---|---|
| 113023 | 431 | **0.70% / 0.00%** | 79.1% / 84.9% | 41.1% / 48.3% | 6.7% / 10.9% |
| 113947 | 309 | **6.15% / 5.83%** | 68.3% / 76.1% | 52.1% / 53.4% | 15.2% / 22.7% |

与我的数字（v5 1.62%/2.27%、ch_v4 79.8%/66.0%、ru_v3 55.9%/49.8%、crnn 4.4%/12.9%）同一个量级，两处差异已查清：

1. **113947 他比我高约 4pp**：残差集中在**空格与省略号个数**，以及一处 `6`→`б` 同形字——他的 det 把那行读成 `1.Вбиблиотеке студенты читают ...`，
   GT 写的是 `1. В библиотеке студенты читают ... .`。属于 det 分行/空格保留方式不同，不是谁算错。
2. **113023 的 proj 模式他算出 0.00%**：这正是**循环性**的直接证据——GT 部分源自 v5 输出，用 proj（无 det、整行直送 rec）复现它就会完美复现。
   ⚠️ 这个 0.00% **不能**解读为“v5 在真实截图上零错误”；只有 v5 这一列有轻微循环性，评 `ch_v4`/`ru_v3`/`crnn` 是公平的。

两个后续项经评估**不做**：① 082542/113926 两张 1080×2400 长图混中俄文与变格表，共识 GT 的误差会直接变成虚高 CER，保留定性即可；
② 把 det v4/v5 对比搬到这 4 张真机截图上没有必要——印刷体两版 det 都是满分，手写侧结论已一致（det 是硬伤、投影更好但仍不够），时间留给手写微调。

出处与复核记录另见 pc-ref 的 `tools/ocr_ref/img/ru_bench/SOURCES.md` §8。

复现：`python D:\ai\models\ocrench\make_real_ru_gt.py <图片>`（生成行框 + 四模型逐行对照）＋ `score_gt()`（整图 CER）。

## 12.5 App 侧常量补充（接 §七，v5 与手写用）

```kotlin
// 追加到 OcrModelSpec.EXPECTED_SIZE / EXPECTED_SHA256
"rec_ru_v5.onnx" to 8_048_799L,     // 官方 PaddlePaddle ONNX（推荐主源）
"dict_ru_v5.txt" to 2_781L,

"rec_ru_v5.onnx" to "5371ee1ddaa7983cc62d0818d99e982b6804638c85e4f960d59a574094e172e5",
"dict_ru_v5.txt" to "db40aa52ceb112055be80c694afdf655d5d2c4f7873704524cc16a447ca913ba",

// 只打算用 ModelScope / HF sukode 那个转换版时才用下面这组（**与官方版字节不同，大小与 SHA 都不能混用**）
// "rec_ru_v5.onnx" to 8_074_092L
// sha256 = 90f761b4bfcce0c8c561c0cb5c887b0971d3ec01c32164bdf7374a35b0982711

fun sourcesForV5(fileName: String): List<String> = when (fileName) {
    "rec_ru_v5.onnx" -> listOf(
        "https://hf-mirror.com/PaddlePaddle/cyrillic_PP-OCRv5_mobile_rec_onnx/resolve/main/inference.onnx",
        "https://huggingface.co/PaddlePaddle/cyrillic_PP-OCRv5_mobile_rec_onnx/resolve/main/inference.onnx",
        "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.2/onnx/PP-OCRv5/rec/cyrillic_PP-OCRv5_rec_mobile.onnx",  // 字节不同！
        "https://hf-mirror.com/sukode/RapidOCR/resolve/main/onnx/PP-OCRv5/rec/cyrillic_PP-OCRv5_rec_mobile.onnx",
    )
    "dict_ru_v5.txt" -> listOf(
        "https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/main/ppocr/utils/dict/ppocrv5_cyrillic_dict.txt",
        "https://cdn.jsdelivr.net/gh/PaddlePaddle/PaddleOCR@main/ppocr/utils/dict/ppocrv5_cyrillic_dict.txt",
        "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.2/paddle/PP-OCRv5/rec/cyrillic_PP-OCRv5_rec_mobile/ppocrv5_cyrillic_dict.txt",
    )
    else -> emptyList()
}
```

要点：

- **没有 HF 托管的 v5 西里尔词表**（我把 `sukode/RapidOCR`、`pitapo/rapidocr`、`PaddlePaddle/*`、`itextresearch/*` 都翻过了），所以词表只能走 GitHub raw / jsdelivr / ModelScope；若一定要走 hf-mirror，可退化为解析官方 `inference.yml` 的 `character_dict`（6,991 B，`5c76cc91…`）。
- **两个 v5 ONNX 源字节不同**（8,048,799 vs 8,074,092），sha 不同但**数值等价（max\|diff\| 1.49e-07、argmax 全同）→ 任选其一即可**（pc-ref 逐图逐行完全一致，我这边随机输入同样一致）。真正要防的是**模型与词表配错**（§13.4）与**混引大小/哈希**，不是选哪个版。
- 解码表规则随模型变：v5 = `['blank'] + 850 行 + [' ']`（852）；**不要沿用 v3 的 163 词表**。
- 预处理：`(x/255 - 0.5) / 0.5`、BGR、高 48、宽 `max(320, ceil(48*w/h))` 右侧补零。

## 12.6 手写落地路线（导出已完成 + 实测结果）

1. ✅ **导出已完成**：用本机 ComfyUI 的 torch 2.10 跑 `D:\ai\models\ocr\hw\export_crnn_rukopys_onnx.py`，产出 `crnn_rukopys.onnx`
   （25,011,512 B，sha256 `daf33d37f03cea0c641830ddaaeff38babca7722b1b5ed9c60327750155c22c9`，IN `x`[N,1,32,W] → OUT `log_probs`[T,N,341]，推理 OK `[80,1,341]`）。
   导出踩坑：① ckpt 里 LSTM 的键是 `rnn.0.*`（原实现把 LSTM 包在容器里），要 `nn.Sequential` 包一层才对得上；② torch 2.10 默认用 dynamo 导出器、需要 `onnxscript`，改 `dynamo=False` 的传统导出器即可。
2. ✅ **已实测**（§12.4.3）：手写 CER **0.237**（pc-ref 8 行）/ **0.221**（自建 25 行），**优于 v5 的 0.347 / 0.583**；但印刷体不如 v5（0.146 vs 0.022）→ **两个模型分工使用**（v5 兜印刷体，CRNN 兜手写）。
3. App 侧接线：灰度、`w' = max(4, min(int(w*32/h), 512))`、`cv2.INTER_AREA`、`x/127.5-1.0`、NCHW；CTC 贪心（blank=0），**不要追加空格**（空格已在字符表内 index 2）。
   ⚠️ 字符表**必须**用 `crnn_rukopys_charset.json` 里的 `chars` 数组（= `config.json` 的 `vocab.chars`，341 项）。按行读 `crnn_rukopys_chars.txt` 会因 index 1 是换行符本身而多出一个空项，导致**所有字符错位一个**（症状：о→н、р→п、ь→ы——我实际踩过这个坑）。
4. 可选优化：INT8 动态量化把 25 MB 压到约 7 MB（`onnxruntime.quantization.quantize_dynamic`），量化后要用 `ru_bench` 复测精度。
5. 若手写仍不够：再评估 PARSeq-s（96.9 MB）；TrOCR 系只做 PC 端兜底。

**风险提示**

- `crnn-rukopys` 训练数据是**乌克兰文档案**（UkrainianCatholicUniversity/rukopys），俄文手写/印刷混合场景需要实测；许可 Apache-2.0，可商用。
- 手写评测目前只用"行级裁剪图"，真实场景还要过 det：`real_pages/` 的整页扫描需要 full pipeline 才能评（这是 pc-ref t1 的活）。
- 本轮的 CER 是"粗测"：样本量小（23/8/20/25 行）、手写集是合成字体，绝对数值参考价值有限，**模型之间的相对排序才是结论**。

**复现命令**

```bash
PY=/d/ai/projects/study-assistant/tools/ocrenv/Scripts/python.exe
$PY D:/ai/models/ocr/bench/bench.py                # 自建集：印刷 20 + 手写 25
$PY D:/ai/models/ocr/bench/bench_ru_bench.py       # pc-ref 共享集（只读 ru_bench）
# 结果: D:/ai/models/ocr/bench/bench_result.json / bench_ru_bench_result.json
```

产物清单：`D:\ai\models\ocr\ru_v5\`（v5 模型+词表+官方 yml）、`D:\ai\models\ocr\hw\`（手写候选 + 导出脚本）、`D:\ai\models\ocr\bench\`（两个评测脚本 + 结果 json + 测试图）。

## 12.7 det（文本检测）也要换：v4 det 会"漏掉/切碎"手写行

> 补测（承接 §12.6 的"检测是不是问题"）：结论是**是问题**——App 现在这版 `ch_PP-OCRv4_det` 对手写行会把一行切成好几块甚至整行漏检，
> 而 PP-OCRv5 det mobile 明显更稳。

### 12.7.1 v5 det 的落地参数（已下载 `D:\ai\models\ocr\det_v5\`）

| 文件 | 大小(B) | SHA-256 | 来源 | 实测 |
|---|---|---|---|---|
| `det_v5_official.onnx`（推荐） | 4,826,518 | `a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d` | HF 官方 `PaddlePaddle/PP-OCRv5_mobile_det_onnx/inference.onnx` | hf-mirror 全量下载 200；IN `x`[N,3,H,W] → OUT `fetch_name_0`[N,1,H,W] |
| `det_v5_rapidocr.onnx`（备源） | 4,819,576 | `4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae` | HF `sukode/RapidOCR` → `onnx/PP-OCRv5/det/ch_PP-OCRv5_det_mobile.onnx` | 与官方版输出逐像素同一热力图（实测计数完全一致） |
| `det_v5_official_inference.yml` | 903 | — | 官方 repo | 权威预处理/后处理参数 |

**预处理（这里有两个必踩的坑）**

1. **归一化不是 rec 那一套**：det 用 ImageNet 归一化 `scale=1/255, mean=[0.485,0.456,0.406], std=[0.229,0.224,0.225]`（BGR、HWC→CHW）；rec 才是 `(x/255-0.5)/0.5`。两套参数混用会明显掉框。
2. **`DetResizeForTest(resize_long=960)` 只缩小、不放大**：长边 ≤960 时 `ratio = 1`，保持原尺寸（再向上取到 32 的倍数）。
   我第一版测试按"一律缩放到 960"跑，小图被放大后**行被切成碎块**：p03（4 行页面）v5 det 检出 **15** 个框；改成"只缩小"后立刻变成 **4** 个框，p01 从 7 → 3。→ App 侧务必按"只缩小"实现。
3. 后处理（DB）：`thresh 0.3 / box_thresh 0.6 / unclip_ratio 1.5 / max_candidates 1000 / min_size 3`，与 v4 det 相同。

### 12.7.2 检出效果实测（同一批图，框数是否与真值一致）

| 测试组（图片张数） | `det_v4`（App 现在这版） | **`det_v5`**（官方/RapidOCR 版结果一致） |
|---|---|---|
| `ru_bench/printed` 页面 8 张（真值 = pc-ref 行框数） | 8/8 一致 | **8/8 一致** |
| 自建 20 张印刷行图（期望 1 行） | 20/20 检出 1 框 | **20/20 检出 1 框** |
| `ru_bench/handwritten` 8 张合成手写行图（期望 1 行） | **3/8**（切成 4/8/3/3 框，其中 1 张**完全漏检 0 框**） | **7/8**（仅 1 张切成 2 框） |
| 自建 25 张合成手写行图（期望 1 行） | 13/25 | **22/25** |

- 结论：**印刷体两者都够用；手写体 v4 det 明显不行**（一行被切碎 → 会重复识别/只识别到半行；漏检 → 用户看到"什么都没识别出来"）。
- `real_pages` 3 张真扫页：v4 检出 14/36/3 框，v5 检出 30/18/14 框；pc-ref 的 GT 是整段文本（无行级真值），这组**只能作为"能检出多少行"的参考**，行级准确率要等 t1 的整页 pipeline 才能评。

### 12.7.3 给 App 的建议（直接对应"手写模式入口"）

1. **det 也一起升级到 v5**（只多 4.83 MB，却决定手写行会不会被切碎/漏检）；det 与 rec 的归一化参数分开配置，别共用。
2. **手写模式**：
   - 用户已框选/裁出**单行**图片 → 可以**跳过 det 直接整图 rec**（实测 v5 det 在单行图上 7/8 会正好给 1 框，等价于整图；但 v4 det 只有 3/8，所以"跳过 det"比"用 v4 det"更靠谱）。
   - 用户拍**整页/多行**照片 → 必须 det：用 **v5 det**，否则手写行会被切碎。
3. 若暂时不换 det：手写模式建议**强制走"整图 rec + 行切分兜底"**，不要依赖 v4 det 的框。
4. `EXPECTED_SIZE`/`SHA256` 追加：`"det_v5.onnx" to 4_826_518L` + `a4319856…b6e61d`；源列表
   `hf-mirror.com/PaddlePaddle/PP-OCRv5_mobile_det_onnx/resolve/main/inference.onnx` → 官方 → `hf-mirror.com/sukode/RapidOCR/resolve/main/onnx/PP-OCRv5/det/ch_PP-OCRv5_det_mobile.onnx`（**注意两条源的 sha/大小不同：4,826,518 vs 4,819,576**）。

**复现**：`D:\ai\models\ocr\bench\det_compare.py`（DB 后处理用 pc-ref `_reftools` 里的 pyclipper），结果 `det_compare_result.json`。

---

## 13. 收口：真实场景证据链与最终建议（t3 结论）

### 13.1 证据链（三个场景、两套独立实现互证）

| 场景 | 数据 | App 现状 `ch_v4` | `ru_v3` | **`ru_v5`** | `crnn_rukopys`（手写专用） |
|---|---|---|---|---|---|
| **真实手机截图**（113023 / 113947，无合成无扫描） | 整图 micro-CER | **66%~85%** | 41%~56% | **0.7%~6.2%** | 4.4%~22.7% |
| 合成印刷体（pc-ref 23 行） | 行级 CER / 行全对 | 70.2% / 4.3% | 25.9% / 8.7% | **2.4% / 82.6%** | 15.2% / 26.1% |
| 合成手写（pc-ref 8 行） | 行级 CER | 93.2% | 94.2% | 34.7% | **23.7%** |
| 真实手写整页（3 张扫描） | 整页 CER / 字符恢复 | — | — | 96.5% | **88.7% / 13.9%** |

### 13.2 最终建议（给 App 的接入决策）

1. **P0｜立刻换 rec**：`cyrillic_PP-OCRv5_mobile_rec`（8.0 MB，`rec_ru_v5.onnx` 8,048,799 B / sha `5371ee1d…`）+ 850 词表的 `dict_ru_v5.txt`（2,781 B）。
   这是**唯一能把用户看到的“西里尔识别错误率极高”从 66%~85% 压到个位数**的动作；纯印刷体场景到此已经可用。
2. **P0｜det 一起换**：`PP-OCRv5_mobile_det`（4.8 MB）。印刷体两版 det 都够用，但手写行上 v4 det 只有 3/8 给对单框（含整行漏检），v5 det 是 7/8。
   注意 det 用 ImageNet 归一化、`resize_long` 只缩小不放大（§12.7），这两条与 rec 不同。
3. **P1｜手写入口**：`crnn_rukopys`（已导出 ONNX 25,011,512 B / sha `daf33d37…`）——**baseline 而非产品**：
   行级 23.7% 已优于 v5 的 34.7%，但真实手写整页 88.7% 仍不可用。建议作为可选手写包 + 微调起点（341 类、32px 高、512 宽上限、Apache-2.0、8.9 ms/行）。
4. **不建议做**：再引入 PARSeq-s / EasyOCR cyrillic / TrOCR 系列——前两者是印刷体模型（体积 12~25 倍于 v5、收益为负），
   TrOCR 需 BPE + 自回归且 1.34~2.45 GB，手机端不现实。

### 13.3 交付物索引

| 路径 | 内容 |
|---|---|
| `docs/OCR模型源.md` | 本文（§1~§11 中英模型源；§12 v5/手写/det 选型与全部实测；§13 收口） |
| `D:\ai\models\ocr\ru_v5\` | v5 西里尔 rec 主/备 onnx + 850 词表 + 官方 inference.yml |
| `D:\ai\models\ocr\det_v5\` | v5 det 官方/RapidOCR onnx + inference.yml |
| `D:\ai\models\ocr\hw\` | 手写 CRNN：`crnn_rukopys.onnx` + 字符表/权重/导出脚本 |
| `D:\ai\models\ocr\bench\` | 4 个评测脚本 + 结果 json + 测试图 + `real_ru_gt\`（真机截图 GT） |
| `tools\ocr_ref\img\ru_bench\scout_gt\` | 真机截图 GT 副本（供 pc-ref 直接并入基准） |

复现全部数字：`bench.py`（自建集）、`bench_ru_bench.py`（共享集）、`bench_crnn.py`（手写 CRNN）、`det_compare.py`（det v4/v5）、`make_real_ru_gt.py`（真机截图）。
### 13.4 打包校验清单：模型与词表必须配套（防静默错误）

> 背景：pc-ref 在 `D:\ai\models\ocr\ru_combo\` 里发现**模型与词表不配套**——该目录的 `ch_PP-OCRv4_rec_infer.onnx` 实际是 **cyrillic v3 模型（165 类）**，
> 却配了**中英词表** `ppocr_keys_v1.txt`（6623 行 → 需 6625 类）。CTC 解码会拿模型索引去索引中文词表，输出拉丁/中文噪声且**不报错**——属于典型的静默错误。
> 我用同一批文件独立复核，确认该判断成立；截至本次核对，`ru_combo` / `ru_v5_combo` / `det_v5_combo` 三个目录**已不存在**（疑为打包过程的临时产物，事后被清理）。
>
> **证据已固化**：pc-ref 把三个包的逐文件 hash 表（包内名 → 实际内容 → 大小 → sha256 → 结论）写进了 `tools\ocr_ref\out\model_pack_audit.md`；即使目录被清理，后来人也能看到完整证据链。

**统一校验规则**（三个 PP-OCR 模型全部成立）：

```
PP-OCR 系列： classes（rec 输出最后一维） == 词表行数 + 2      # +2 = blank + 追加空格
CRNN 系列 ： classes == crnn_rukopys_charset.json 的 classes  # 该模型不追加空格

实测： ch 6625 == 6623 + 2 ✓      ru v3 165 == 163 + 2 ✓      ru v5 852 == 850 + 2 ✓
```

**⚠️ 两个计数陷阱（我写校验器时都踩过）**

1. **数词表行数不能 `strip()`**：cyrillic 词表**第 1 行就是一个空格字符**，`strip()` 会把它当空行丢掉——6623 行的 `ppocr_keys_v1.txt` 会被数成 6622、163 行的 `dict_ru.txt` 会被数成 162，于是“明明配套却报不配套”。正确做法：按换行切分后**只丢弃完全为空的行**。 实测补充：**该坑只在“首行是空格”的词表上发作**——`ppocr_keys_v1.txt`(6623→6622 ✗)、`dict_ru.txt`(163→162 ✗)；而 `dict_ru_v5.txt` 首行是 `!`，用不用 strip 都是 850 ✓。规范化表述：**词表行数 = 按换行切分后仅丢弃完全空行（末尾换行丢掉、首行空格保留）**（pc-ref 复现后采用了同一表述）。
2. **det 模型不能套这条规则**：det 输出是 `[N,1,H,W]`、最后一维是动态符号，取 `int()` 会直接抛异常；det/cls 不需要词表。
3. CRNN 的 `crnn_rukopys_chars.txt` 也不要用行数判断：它 index 1 是换行符本身（写成空行），以 `crnn_rukopys_charset.json` 为准。

**可直接使用的校验器**：`D:\ai\models\ocr\bench\check_pack.py`（**7,350 B，sha256 `9c6c51a3f7b0a5ceacd870b681fb73a77107693d011f7e0ed353ffb2dfc73234`**——引用/分发时可用这个 hash 确认拿到的是同一次交付；pc-ref 已把这个 hash 锁进其审计文件）

```bash
python D:\ai\models\ocr\bench\check_pack.py            # 扫 D:\ai\models\ocr 下所有子目录
python check_pack.py <某个 combo 目录>          # 只验一个包（App 打包后建议跑一遍）
# 输出：每个 onnx 的 classes / 大小 / sha256（能对上“标准答案”会标注是哪一份）+ 词表行数 + 是否配套；有❌则退出码 1
```

> 附：pc-ref 自查确认他们的评测脚本本来就没踩这两个坑（`pc_ocr_ref.load_char_table()` 用 `split("\n") + ln != ""`、`bench_rec.Rec.dict_ok` 有等价断言并在报告 §1 标记不匹配）——坑在**校验器/打包流程**这一侧，不在评测脚本。

**App 侧建议**：加载期断言 `sess.outputShape.last == dictLines + 2`（CRNN 用 charset 长度），不匹配就报“模型包不完整/不配套”并拒用，而不是让 CTC 输出噪声。打包脚本把“模型 + 词表”当成一个不可拆分的原子单元，别从不同目录各取一个。
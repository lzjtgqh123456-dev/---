# 模型包配套性核对记录（打包防静默错误）

> 记录人：pc-ref（team ocr-batch1，任务 t4 的旁路核对）
> 背景：`D:\ai\models\ocr\` 下曾出现 `ru_combo/`、`ru_v5_combo/`、`det_v5_combo/` 三个"App 组合包"目录。
> 我做了只读核对并发现一处**会静默出错**的配套问题；通报 captain 后，三个目录已被清理（本文件用于**保留证据**，因为现在已无法在磁盘上复现）。

## 1. 核对结果（按内容 SHA-256 判定真实来源）

| 组合包 | 文件（包内名字） | 实际内容 | 大小(B) | SHA-256 | 结论 |
|---|---|---|---|---|---|
| `ru_combo` | `ch_PP-OCRv4_det_infer.onnx` | `ch/ch_PP-OCRv4_det_infer.onnx` | 4,745,517 | `d2a7720d45a54257208b1e13e36a8479...` | 配套 OK |
| `ru_combo` | `ch_PP-OCRv4_rec_infer.onnx` | **`ru/rec_ru.onnx`（cyrillic v3）** | 8,983,966 | `befb0c18a5648d44c6e7891ea10d8ee8...` | 165 类 |
| `ru_combo` | `ppocr_keys_v1.txt` | **`ch/ppocr_keys_v1.txt`（中英）** | 26,250 | `a1c84d9bdb9ab29043c58896224d3294...` | 6,623 行 → 需 6,625 类 |
| `ru_v5_combo` | `ch_PP-OCRv4_rec_infer.onnx` | `ru_v5/rec_ru_v5.onnx`（cyrillic v5 官方） | 8,048,799 | `5371ee1ddaa7983cc62d0818d99e982b...` | 852 类 |
| `ru_v5_combo` | `ppocr_keys_v1.txt` | `ru_v5/dict_ru_v5.txt`（v5 俄文） | 2,781 | `db40aa52ceb112055be80c694afdf655...` | 850 行 → 需 852 类 ✓ |
| `det_v5_combo` | `ch_PP-OCRv4_det_infer.onnx` | `det_v5/det_v5_official.onnx`（PP-OCRv5 det） | 4,826,518 | `a431985659dc921974177a95adcfbb90...` | det 无需词表 ✓ |

**问题**：`ru_combo` 把 **165 类**的 v3 西里尔 rec 和 **6,623 行**的中英词表放在一起 —— 差 **6,460 类**。
后果：CTC 用模型索引 0..164 去索引中英词表 → 输出拉丁/中文噪声，**不抛异常、不报错**；若加载期断言 `classes == dictLines + 2` 则会直接崩。
另两个包（`ru_v5_combo` / `det_v5_combo`）核对为**配套正确**。

（两个 v5 转换版说明：RapidOCR `90f761b4…` 8,074,092 B 与 PaddlePaddle 官方 `5371ee1d…` 8,048,799 B 是同一模型的不同转换，实测**逐图逐行输出完全一致**，配同一份 850 行词表均可；风险只在"模型—词表配错"。）

## 2. 配套性判据（可直接用于打包自检）

**规则**：`rec 模型输出类别数 == 词表行数 + 2`
（`+2` = 1 个 CTC blank + 1 个追加的空格；PP-OCR 的 `use_space_char=True`）

实测三套均成立：

| 模型 | 类别数 | 词表行数 | 校验 |
|---|---|---|---|
| `ch_PP-OCRv4_rec` | 6,625 | `ppocr_keys_v1.txt` 6,623 | ✓ |
| `cyrillic_PP-OCRv3_rec` | 165 | `dict_ru.txt` 163 | ✓ |
| `cyrillic_PP-OCRv5_rec` | 852 | `dict_ru_v5.txt` 850 | ✓ |

### 2.1 三个必须避开的坑（实现校验器时）

1. **数词表行数不要用 `strip()`**：西里尔词表**第 1 行就是一个空格字符**（它本身就是词表的一个条目）。
   - 只丢弃"完全空行"(`line != ""`)：`ppocr_keys_v1.txt` = 6,623 ✓、`dict_ru.txt` = 163 ✓
   - 用 `line.strip() != ""`：会数成 6,622 / 162 → **把配套正确的包误判成不配套**（实测复现）
   - 词表末尾的换行要丢掉，但中间的空格行必须保留。
2. **det / cls 模型不能套这条规则**：det 输出 `[N,1,H,W]`，最后一维是动态符号，取 `int()` 会抛异常。det/cls 不需要词表，必须单独分支（它们只做"能否加载 + 输入通道数"检查）。
3. **CRNN 的 `chars.txt` 不能按行数判**：`crnn_rukopys_chars.txt` 的 index 1 就是换行符本身，按行切会多一个空项、导致整列错位一位；以 `crnn_rukopys_charset.json` 的 `chars` 数组为准（341 项，blank=0、空格在 2）。

### 2.2 给 App 的建议（3 行运行期断言，永久防住这类静默错误）

```kotlin
val outLast = recSession.outputInfo[0].shape.last()          // 6625 / 165 / 852
val n = dictText.split('\n').count { it.isNotEmpty() }        // 注意: 不要 strip()
require(outLast == n + 2L) { "模型包不配套: rec classes=$outLast, dict lines=$n" }
```

同时建议：**模型与词表当作原子单元打包**（成对来自同一目录、一起改名、一起拷贝），不要出现"文件名是 A、内容是 B"的情况——本次正是靠 hash 才发现 `ru_combo` 的问题，按文件名/大小判断会漏。

## 3. pc-ref 侧的实现现状（自查）

- `tools/ocr_ref/pc_ocr_ref.py::load_char_table()` 用 `split("\n")` + `if ln != ""`，**没有用 strip()** → 一开始就数对了（v3 = 163 + 追加空格 = 164 项）。
- `tools/ocr_ref/bench_rec.py::Rec.dict_ok` 对 PP-OCR 与 CRNN 均执行等价断言
  （ppocr：`classes == len(keys+[' ']) + 1`；crnn：`classes == len(chars[1:]) + 1`），
  不匹配时在模型清单里打印"**不匹配**"，并在 `ru_bench_report.md` §1 表格中标注。
- 结论：这次的坑在"校验器写法"与"打包流程"，不在评测脚本。

## 4. 相关文件

- 评测与数据集：`tools/ocr_ref/bench_rec.py`、`img/ru_bench/`（来源与许可见 `img/ru_bench/SOURCES.md`）
- 模型源与选型：`docs/OCR模型源.md`（model-scout，§13.4 收录了同一规则与陷阱）
- 现成校验器：`D:/ai/models/ocr/bench/check_pack.py`（model-scout 实现，扫 `D:/ai/models/ocr` 全部子目录或传单个目录；已按 §2.1 的三个坑修正，ch/ru/ru_v5/hw 四个包全部通过）
  - **pc-ref 实测锁定**：7,350 B，sha256 `9c6c51a3f7b0a5ceacd870b681fb73a77107693d011f7e0ed353ffb2dfc73234`；代码抽查确认已修：第 63 行 `l != ""`（不是 strip）、有独立 CRNN 分支且以 `charset.json` 为准、det 不套 rec 规则

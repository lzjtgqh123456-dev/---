# 离线 OCR 拍照/图片翻译 · 前置说明（新对话从这里开始）

> 目标：在「留学助手-乐」里做**纯离线**的「拍照识别翻译」和「本地图片翻译」。
> 本文是开工前的准备与决策记录，新对话读完本文即可直接动手。

> ## ✅ 状态（2026-09-19）：**第一批已完成并真机验证；俄文识别已修复到可用（真实截图 CER 2.0%）**
> 俄文 rec 已换成 **cyrillic PP-OCRv5**（真实手机截图 CER 从 66~85% → 0.7~2.0%），det 换 PP-OCRv5，并按行做中英/俄文语言路由；**手写体目前不可用**（最好 23.7% CER，需专门微调），详见 交接说明.md 第十轮。
> 「相册选图 → PP-OCR(ONNX Runtime) 识别 → 复用现有 GGUF 翻译 → 显示原文+译文」全部跑通，
> 含**断网可用**、App 内首次下载模型（多镜像/断点续传/大小+SHA-256 双校验，实测拉回的文件与 PC 端 md5 一致）。
> 实现细节、验收证据、已知差异与踩坑见 `交接说明.md` 的「第九轮」，PC 对齐规格见 `tools/ocr_ref/SPEC.md`，模型源见 `OCR模型源.md`。
> **第二批**（拍照 / 俄文模型 / 区域框选 / 结果存备忘录）尚未开始；俄文模型源与参数已备好，直接接即可。


## 一、已定方案（用户确认）

- **路线 A**：本地 OCR + 复用现有离线翻译模型（`HY-MT1.5-1.8B-Q4_K_M.gguf`，文本模型，不能吃图片）。
- **OCR 引擎**：**ONNX Runtime + PP-OCR 模型**（RapidOCR 同款）。
  - 不用 ML Kit：bundled 版必须把模型打进 APK（违背"首次下载"）；unbundled 版走 Google Play 服务下载，**国内机型多半没有 GMS，不可用**。
- **模型不内置**：**首次使用时下载**，复用现有下载器那套（多镜像 + 断点续传 + 进度 + 校验）。
- APK 只增加 ONNX Runtime（arm64 约 10~12 MB）。

## 二、模型清单（已核实可用）

源：HuggingFace `SWHL/RapidOCR`（公开，直链格式 `https://huggingface.co/<repo>/resolve/main/<path>`）

| 用途 | 仓库内路径 | 大小 |
|---|---|---|
| 文本检测 | `PP-OCRv4/ch_PP-OCRv4_det_infer.onnx` | 4.7 MB |
| 中英识别 | `PP-OCRv4/ch_PP-OCRv4_rec_infer.onnx` | 10.9 MB |
| 方向分类（可选） | `PP-OCRv1/ch_ppocr_mobile_v2.0_cls_infer.onnx` | 0.6 MB |
| 字符表（中英） | `PP-OCRv4/ppocr_keys_v1.txt`（若仓库没有，用 PaddleOCR 官方同名文件） | 几十 KB |

**俄文（西里尔）rec 模型**：上面这个仓库没列到，需要另找（PaddleOCR 官方 `cyrillic_PP-OCRv3_rec_infer` 一类）。
→ 建议：下载后**上传到你自己的 HuggingFace**（`datasets/SweetTouch/transpak/ocr/`），App 默认源同时配 `hf-mirror.com` + `huggingface.co`，和词典一个做法。
→ 西里尔字符表：`cyrillic_dict.txt`。

## 三、App 侧设计（照这个做）

1. **依赖**：`implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")`（或更新版），只留 arm64。
2. **模型存放**：`getExternalFilesDir("ocr")`（和翻译模型 `models/` 并列），文件名固定：`det.onnx` / `rec_ch.onnx` / `rec_ru.onnx` / `cls.onnx` / `keys_ch.txt` / `dict_ru.txt`。
3. **模型管理**：抄 `data/translate/ModelRepository.kt`（镜像回退、Range 断点续传、`.part` 原子改名、状态 Flow）
   + 抄 `data/dict/DictManager.kt`（导入/导出/删除、URL 可改）。
4. **推理层**：新增 `data/ocr/`：
   - `OcrEngine.kt`：det（输入 3×H×W，归一化 mean/std 同 RapidOCR）→ 文本框（DB 后处理：阈值 0.3 / box_thresh 0.6 / unclip 1.5）→ 按框透视裁剪 → rec（高 48，宽按比例 padding 到 320 的倍数）→ CTC 解码（keys 表 + blank）。
   - 预处理/后处理可**直接参考 RapidOCR 的 Python 实现**移植（PC 上已用过 RapidOCR）。
5. **UI**：
   - 翻译页加「相册选图 / 拍照」→ 预览 → 点「识别并翻译」→ 显示 OCR 原文 + 译文（可复制/分享/存备忘录）。
   - 识别结果按行拼接；语言对复用现有 `TranslateScreen` 的语言选择。
   - 首次进入时若模型未下载：显示「下载 OCR 模型（约 20 MB）」+ 进度 + 可换源（和词典管理一个交互）。
6. **第一批验收**：相册选一张中/英文图片 → 识别出文字 → 复用现有 GGUF 翻成俄语/中文 → 界面显示原文+译文；断网可用。
7. **第二批**：拍照、俄文模型、区域框选、结果存备忘录。

## 四、可复用的现有代码

| 现有 | 位置 | 复用点 |
|---|---|---|
| 翻译模型管理 | `data/translate/ModelRepository.kt` | 下载/校验/状态 Flow 整套 |
| 词典包管理 | `data/dict/DictManager.kt` + `DictPackMeta.kt` | 导入/导出/删除、默认镜像 URL 写法 |
| 翻译调用 | `data/translate/LlamaBridge.kt`、`feature/translate/*` | OCR 出文本后直接调它 |
| 加密文件/流式打开 | `data/security/VaultStore.kt`、`VaultStreamProvider.kt` | 选图/拍照落盘、打开查看 |
| 图片解码工具 | `ui/Thumbnails.kt`（含下采样） | OCR 输入图预处理 |

## 五、已知坑（务必注意）

1. **R8**：release 开了混淆，ONNX Runtime 的 JNI 类要加 keep 规则（`-keep class ai.onnxruntime.** { *; }`）。
2. **ABI**：`abiFilters` 目前只有 `arm64-v8a`，ONNX Runtime 也要只留 arm64，否则包体会涨。
3. **模型文件校验**：下完必须校验大小（可选 SHA-256）+ `onnx` 魔数/能加载，避免半截文件。
4. **PP-OCR 前后处理容易错**：归一化（`(x/255-mean)/std`）、rec 输入高度 48、CTC blank=0、keys 表最后要加空格；先用同一张图与 PC 端 RapidOCR 结果对齐再上手机。
5. **内存**：det 输入长边限制在 960~1280，别直接喂原图（否则 OOM）。
6. **中文/路径**：模型目录别放中文路径；`getExternalFilesDir` 上不用存储权限。
7. 装机/构建照 `交接说明.md` 的 `build.py` 流程；vivo 装机要过安全弹窗。

## 六、新会话开工清单

1. 读 `docs/交接说明.md`（现状/工具链/坑）+ 本文。
2. 确认：俄文 rec 模型从哪里拿（自己上传 or 公开源直链）。
3. 拉依赖、写 `data/ocr/`、接 UI、**先用 PC 端 RapidOCR 对齐一张图的中间结果**。
4. 分批打 debug + 装机自测，最后按 `交接说明.md` 重打 release。

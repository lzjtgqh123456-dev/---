# ru_bench 数据来源与许可说明

本目录是 t4(俄文识别准确率基准)的测试集。所有条目都在 `bench_rec.py` 里被自动发现并按下面的约定加载。

## 目录约定

| 路径 | 含义 | `bench_rec.py` 里的模式 |
|---|---|---|
| `printed/*.png` + `gt/<stem>.boxes.json` | 合成印刷体,每张图自带每行的 GT 文本框 | `lines`(= GT 框裁剪后只评 rec) |
| `handwritten/*.png` + `gt/<stem>.txt` | 手写体(整图即一行文本) | `whole` |
| `real_pages/*.png` + `gt/<stem>.txt` | 真实手写整页 + 整页转录 | `page`(det+rec 端到端) |
| `../real_ru/*.png` | captain 提供的真机俄文截图 | `page`;若 `gt/<stem>.txt` 不存在则只做定性对照(不计 CER) |

## 1. 合成印刷体(8 张,23 行) —— 自造,无版权问题

由 `make_ru_bench.py` 确定性生成(PIL + Windows 自带字体 Arial / Times New Roman / Segoe UI / Tahoma / Verdana;
中文行用 微软雅黑 msyh.ttc)。覆盖:字号 20/28/40、行数 1~4、纯俄文 / 俄英混排 / 俄中混排 / 层级标题 / 浅色块背景。

| 文件 | 内容要点 |
|---|---|
| p01_ru_3lines | 纯俄文 3 行,28px,Arial,含破折号 `—` |
| p02_ru_1line | 纯俄文 1 行,40px,Times,含数字 |
| p03_ru_4lines | 纯俄文 4 行,20px,Segoe UI |
| p04_ru_en_mix | 俄英混排(Booking.com / Check-in) |
| p05_ru_cn_mix | 俄中混排(中文标题 + 俄文条目) |
| p06_ru_hierarchy | 40/28/20px 三级标题正文混排 |
| p07_ru_graybg | 浅灰底 + 蓝色块背景 |
| p08_ru_all_mix | 俄+英+数字+中文+`№`,最杂的一张 |

GT 与该图同源生成(`gt/<stem>.txt` 每行一条;`gt/<stem>.boxes.json` 为逐行四点框),**不是**用模型反推的。

## 2. 合成手写体(8 张) —— 公开数据集,许可未声明

- 数据集: `deepcopy/synthetic-handwritten-cyrillic-180k`(HuggingFace,178,595 条 image+text)
- 取法: `fetch_ru_handwriting.py` 扫描 train 分片,只保留"含西里尔字母、不含乌克兰语专有字母(і ї є ґ)、长度 ≥ 12"的样本,按长度取前 8 条
- **许可**: 该数据集在 Hub 上**未声明 license**(`cardData.license = None`)。这里只取 8 张内部基准图并注明出处,**不得再分发**。
- 注意: 它是"手写风格合成图"(非真人手写),所以另配了下面的真实手写整页做交叉验证。

## 3. 真实手写整页(3 张) —— 公开数据集,许可未声明

- 数据集: `Limerencii/russian-handwriting-ocr`(HuggingFace,13,050 条俄文手写作文的 scan/dark/light 图 + 整页转录)
- 取法: `fetch_ru_handwriting.py` 取 train 前 3 条(`real_00_scan` / `real_01_scan` / `real_02_light`)
- **许可**: 同样**未声明 license**;样本含真实手写笔迹与作文内容,只作内部基准,**不得再分发**。
- 用途: 只做"整页 det+rec 端到端"的定性/定量对照(见报告 §3c 的 page 级诊断)。

## 4. 真机俄文截图(4 张,`img/real_ru/`) —— captain 提供

`Screenshot_20220331_{082542,113023,113926,113947}.jpg`,1080×476 ~ 1080×2400。
内容是**印刷体**俄语教材练习页(如 "Упражнение 19. Напишите данные существительные во множественном числе.")。
**没有提供 GT 转录**,因此报告里只出现在 §3b 定性对照中,不计 CER。若需要定量,请补 `img/real_ru/gt/<stem>.txt`。

## 5. 考察过但未采用

| 候选 | 未采用原因 |
|---|---|
| `UenoGK/HKR`(license: openrail) | 打开后发现内容是**对话数据集**,不是手写图像,与 OCR 无关 |
| `DonkeySmall/OCR-Cyrillic-Printed-*` | `license: unknown`,且是 500k 张打包 zip(体积过大);印刷体我们自己合成更可控 |
| Kaggle "Cyrillic Handwritten Characters" | 需要登录鉴权,无法在脚本里自动化复现 |
| `nastyboget/*_hkr_*`、`pumb-ai/synthetic-cyrillic-*` | 文本生成类数据集,不含手写图像 |

## 6. 模型来源

| 名字 | 文件 | 出处 |
|---|---|---|
| `rec_ch_now` | `D:/ai/models/ocr/ch/ch_PP-OCRv4_rec_infer.onnx` + `ppocr_keys_v1.txt` | App 现状(中英,6625 类) |
| `ru_v3` | `D:/ai/models/ocr/ru/rec_ru.onnx` + `dict_ru.txt` | t2 交付:`deepghs/paddleocr` → `cyrillic_PP-OCRv3_rec`(165 类,163 字符词表) |
| `ru_v5_rapidocr` | `tools/ocr_ref/models/rec_ru_v5.onnx` + `dict_ru_v5.txt` | `sukode/RapidOCR` → `onnx/PP-OCRv5/rec/cyrillic_PP-OCRv5_rec_mobile.onnx`(852 类) |
| `ru_v5_paddle` | `D:/ai/models/ocr/ru_v5/rec_ru_v5.onnx` + `dict_ru_v5.txt` | t3 交付:PaddlePaddle 官方 ONNX 转换(852 类)。实测与 RapidOCR 版**逐条输出完全一致** |
| (未纳入) | `D:/ai/models/ocr/hw/crnn_rukopys_best.pt` | t3 的手写候选是 **PyTorch 权重**,本环境没有 torch;要评测必须先导出 ONNX |

`dict_ru_v5.txt` = PaddleOCR 官方 `ppocr/utils/dict/ppocrv5_cyrillic_dict.txt`(850 行),
与模型自带 metadata 逐字符一致;852 = 1(blank) + 850 + 1(空格)。

## 7. 重新生成全部数据

```bash
cd D:/ai/projects/study-assistant/tools/ocr_ref
PY=D:/ai/projects/study-assistant/tools/ocrenv/Scripts/python.exe
$PY make_ru_bench.py            # 合成印刷体 + GT(确定性,可反复跑)
$PY fetch_ru_handwriting.py     # 从 HF 拉手写样本(需要联网)
$PY bench_rec.py                # 跑基准,输出 out/ru_bench_report.md
```

---

## 8. 真机截图的 GT(2026-09-19 追加,由 model-scout 提供、pc-ref 复核)

`img/real_ru/*.jpg` 原本没有 GT,所以只有定性对照。model-scout 用**三模型共识**(`ru_v5` / `ru_v3` / `crnn_rukopys` 跑同一条 det+rec 管线,逐行取共识 + 俄语语法合理性校验)整理出两张标准练习题截图的 GT:

| 文件 | 字符数 | 内容 |
|---|---|---|
| `Screenshot_20220331_113023.txt` | 431 | Упражнение 19. Напишите данные существительные во множественном числе. (а/б/в 三组) |
| `Screenshot_20220331_113947.txt` | 309 | Упражнение 32. Допишите предложения. (1~8 题) |

- 原始出处: `img/ru_bench/scout_gt/`(含 README、逐行三模型对照);pc-ref 复制一份到 `img/ru_bench/real_ru_gt/` 供 `bench_rec.py` 自动加载。
  (`img/real_ru/` 按任务约定对 pc-ref 只读,所以 GT 放在这里而不是 `img/real_ru/gt/`;`bench_rec.py` 两个位置都会找。)
- 另外两张 1080×2400 长截图(082542 / 113926)混有中俄文与变格表,共识置信度不足,**没有 GT**。

### 8.1 pc-ref 的复核结论(可引用)

1. **同形字污染检查:通过**。两张 GT 里"拉丁字母混进西里尔单词"的词数 = **0**,纯拉丁词 = 0;不像是误把 `ru_v3` 的同形字输出当成了 GT。
2. **语法/排版合理**:句子通顺、大小写与标点符合俄语教材排版;修正处正好是三模型都读错的位置(例:`магазин, , поликлиника` → `магазин, поликлиника`;`парк гостиница` → `парк, гостиница`;`1.Вбиблиотеке` → `1. В библиотеке`)。
3. **残差**(pc-ref 独立跑 `ru_v5` + 我的 det/proj 管线):
   - `113023`:CER **0.70%**(det) / **0.00%**(proj,完全一致)
   - `113947`:CER **6.15%**(det) / **5.83%**(proj)
   差异主要来自**空格与省略号个数**、以及一处 `6`→`б` 同形字(`1. В библиотеке` 被读成 `1.Вбиблиотеке`)。与 model-scout 自己的 1.6~2.3% 属同一量级,差别来自双方 det 分行方式不同。
4. **循环性(必须随数字一起引用)**:该 GT 部分源自 `ru_v5` 输出,所以用这份 GT 评 `ru_v5` 有轻微循环性(所以会出现 proj 模式 0.00% 这种"完全一致");评 **`ch_v4` / `ru_v3` / `crnn_rukopys` 是公平的**(它们的错误量级完全不同)。

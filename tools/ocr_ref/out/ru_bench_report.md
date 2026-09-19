# 俄文识别准确率基准(CER)——印刷 vs 手写,多模型对比


## 0. 结论(按实测 CER 自动生成)

- printed: 最佳 ru_v5_paddle CER=2.4% / 完全正确=82.6% ; 最差 rec_ch_now CER=70.2%
- handwritten: 最佳 crnn_rukopys_hw CER=23.7% / 完全正确=0.0% ; 最差 ru_v3 CER=94.2%
  - 手写体最佳模型的 CER 仍 > 15%,说明没有可直接使用的手写模型,需要专门训练或微调。


复现命令(工作目录 tools/ocr_ref/)

```
python bench_rec.py --model "rec_ch_now=D:/ai/models/ocr/ch/ch_PP-OCRv4_rec_infer.onnx;D:/ai/models/ocr/ch/ppocr_keys_v1.txt" --model "ru_v3=D:/ai/models/ocr/ru/rec_ru.onnx;D:/ai/models/ocr/ru/dict_ru.txt" --model "ru_v5_rapidocr=models/rec_ru_v5.onnx;models/dict_ru_v5.txt" --model "ru_v5_paddle=D:/ai/models/ocr/ru_v5/rec_ru_v5.onnx;D:/ai/models/ocr/ru_v5/dict_ru_v5.txt" --model "crnn_rukopys_hw=D:/ai/models/ocr/hw/crnn_rukopys.onnx;D:/ai/models/ocr/hw/crnn_rukopys_charset.json"
```
模型 = rec_ch_now: D:/ai/models/ocr/ch/ch_PP-OCRv4_rec_infer.onnx + D:/ai/models/ocr/ch/ppocr_keys_v1.txt / ru_v3: D:/ai/models/ocr/ru/rec_ru.onnx + D:/ai/models/ocr/ru/dict_ru.txt / ru_v5_rapidocr: models/rec_ru_v5.onnx + models/dict_ru_v5.txt / ru_v5_paddle: D:/ai/models/ocr/ru_v5/rec_ru_v5.onnx + D:/ai/models/ocr/ru_v5/dict_ru_v5.txt / crnn_rukopys_hw: D:/ai/models/ocr/hw/crnn_rukopys.onnx + D:/ai/models/ocr/hw/crnn_rukopys_charset.json
由 `tools/ocr_ref/bench_rec.py` 生成(与 `pc_ocr_ref.py` 参考流水线共用同一套前处理/CTC)。

CER = 编辑距离总和 / GT 字符数总和(微平均,已做 NFC+空白归一化);`CER_dict` = 先把 GT 里该模型词表无法表示的字删掉再算(用于区分"读错"与"词表根本装不下")。


## 1. 模型清单

| 模型 | 架构 | 文件 | 输入 | 输出 | 类别数 | 词表字符数 | 词表匹配 |
|---|---|---|---|---|---|---|---|
| `rec_ch_now` | ppocr | ch_PP-OCRv4_rec_infer.onnx | `x` | `softmax_11.tmp_0` | 6625 | 6624 | OK |
| `ru_v3` | ppocr | rec_ru.onnx | `x` | `softmax_2.tmp_0` | 165 | 164 | OK |
| `ru_v5_rapidocr` | ppocr | rec_ru_v5.onnx | `x` | `fetch_name_0` | 852 | 851 | OK |
| `ru_v5_paddle` | ppocr | rec_ru_v5.onnx | `x` | `fetch_name_0` | 852 | 851 | OK |
| `crnn_rukopys_hw` | crnn | crnn_rukopys.onnx | `x` | `log_probs` | 341 | 340 | OK |

## 2. 总表(micro-CER / 完全正确率)

| 模型 | printed | handwritten | handwritten_page | real_ru | 各集合平均(macro) |
|---|---|---|---|---|---|
| `rec_ch_now` | 70.2% / 4.3% | 93.2% / 0.0% | 99.4% / 0.0% | 77.9% / 0.0% | 85.2% |
| `ru_v3` | 25.9% / 8.7% | 94.2% / 0.0% | 96.9% / 0.0% | 48.0% / 0.0% | 66.3% |
| `ru_v5_rapidocr` | 2.4% / 82.6% | 34.7% / 12.5% | 95.1% / 0.0% | 2.7% / 25.0% | 33.7% |
| `ru_v5_paddle` | 2.4% / 82.6% | 34.7% / 12.5% | 95.1% / 0.0% | 2.7% / 25.0% | 33.7% |
| `crnn_rukopys_hw` | 15.2% / 26.1% | 23.7% / 0.0% | 90.8% / 0.0% | 13.0% / 0.0% | 35.7% |

(单元格 = CER / 完全正确率;越低越好 / 越高越好。最后一列 = 各集合 CER 的等权平均)


## 3. 分模型明细(含 CER_dict)

| 模型 | 集合 | 条目 | CER | CER_dict(剔除词表外字符) | 完全正确率 |
|---|---|---|---|---|---|
| `rec_ch_now` | handwritten | 8 | 93.2% | 603.8% | 0.0% |
| `rec_ch_now` | handwritten_page | 6 | 99.4% | 98.3% | 0.0% |
| `rec_ch_now` | printed | 23 | 70.2% | 193.2% | 4.3% |
| `rec_ch_now` | real_ru | 4 | 77.9% | 233.6% | 0.0% |
| `ru_v3` | handwritten | 8 | 94.2% | 94.7% | 0.0% |
| `ru_v3` | handwritten_page | 6 | 96.9% | 96.9% | 0.0% |
| `ru_v3` | printed | 23 | 25.9% | 26.1% | 8.7% |
| `ru_v3` | real_ru | 4 | 48.0% | 47.8% | 0.0% |
| `ru_v5_rapidocr` | handwritten | 8 | 34.7% | 34.7% | 12.5% |
| `ru_v5_rapidocr` | handwritten_page | 6 | 95.1% | 95.1% | 0.0% |
| `ru_v5_rapidocr` | printed | 23 | 2.4% | 0.3% | 82.6% |
| `ru_v5_rapidocr` | real_ru | 4 | 2.7% | 2.7% | 25.0% |
| `ru_v5_paddle` | handwritten | 8 | 34.7% | 34.7% | 12.5% |
| `ru_v5_paddle` | handwritten_page | 6 | 95.1% | 95.1% | 0.0% |
| `ru_v5_paddle` | printed | 23 | 2.4% | 0.3% | 82.6% |
| `ru_v5_paddle` | real_ru | 4 | 2.7% | 2.7% | 25.0% |
| `crnn_rukopys_hw` | handwritten | 8 | 23.7% | 23.7% | 0.0% |
| `crnn_rukopys_hw` | handwritten_page | 6 | 90.8% | 90.8% | 0.0% |
| `crnn_rukopys_hw` | printed | 23 | 15.2% | 16.0% | 26.1% |
| `crnn_rukopys_hw` | real_ru | 4 | 13.0% | 13.0% | 0.0% |

## 3b. 无 ground truth 的真实截图 (定性对照, 不计 CER)


### `Screenshot_20220331_082542 [det]`

- `rec_ch_now`: `4 08:25 只需动动嘴念念咒，就能让全纽约人获得天才般的智商IQ 202. 学俄语鸭：超全数词变格表！从1- 1000000000（十亿）全都有！！！ 2020.07.14更新限定代词的变格 限定代词Becb与cam的变格 单数 复数 复数 单数 格 阳性 阴性 阳性 阴性 中性 中性 caMa caMN BCA CaMo Becb Bce Bce caM CaMOn CaMAX Bcei Bcero caMoro Bcex 2 CaMOMy Bcei BceM caMON BCeMy CaMMM BCHO 同…`
- `ru_v3`: `Ki. g... ... a O8:25  +  le A m izoI o io J, sh neil ig  i ir it hi a nIO 202:. Seiei: tekixuhe! Mi- 1OOOOOOOOO GT1Z 4EBE! ! ! 2O2O.O7.14 #T$EE1EKH It t it ubecbicam ii it H E Es п н  DAt Bt BAI п п сама сами Bса самд Bеcь Bсё Bce Cам самой самйх всеeй Bcero с…`
- `ru_v5_rapidocr`: `1A  08:25 ,Q 202S. :!1 1000000000 (+1Z) #! ! ! 2020.07.14 весьсам     * BA РA BA ВA   camá сámи вся саmó весь всё все сам самой самих всей всего саmоró всех 2 самому всей всем самой всему самим всю     саmú всё camó ф всеми всем самим самой самими всей осам об…`
- `ru_v5_paddle`: `1A  08:25 ,Q 202S. :!1 1000000000 (+1Z) #! ! ! 2020.07.14 весьсам     * BA РA BA ВA   camá сámи вся саmó весь всё все сам самой самих всей всего саmоró всех 2 самому всей всем самой всему самим всю     саmú всё camó ф всеми всем самим самой самими всей осам об…`
- `crnn_rukopys_hw`: `1а3 д0і зі =е0 О8:25 а - т  ЕРЕВСОКІ ЗВЕТКАТЕНІНЕНО 2023. З 18і 7Е: [ВаНЯ [Н  ТНЕ! NТ- 1000000000(-11[ЕрА!?! 2020.01.14ТРRЕКІАЕТН Аl *1С іm весb → сам lК ЖН нз 23 [ 13 н [аl7 ВАl7 SАl ВРl  7і7 7l самі сами вся само весь все васе сам самой самнх всей васего сам…`

### `Screenshot_20220331_082542 [proj]`

- `rec_ch_now`: ` 学俄语鸭：超全数词变格表！从1- 1000000000（十亿）全都有！！！ 2020.07.14更新限定代词的变格 —限定代词Becb与caM的变格— 格性单教阴性复数性单教性复数 1BeCbBCeBCABCecaMcaMoCaMacaMИ 2BceroBceBceXcaMorocaMocaMx 3BCeMyBCeMBCeMCaMoMyCaMOCaMMM 4同一或二BceBCro同一或二同一或二caMocaMy同一或二 5BCeMBCeBCeMNCaMMcaMOcaMMW 6o6oBceMo6oBceo6oBCe…`
- `ru_v3`: `Hэ SlEi: tekiuht! i- 1OOOOOOOOO GT1Z 4E5E! !! 2O2O.O7.14 ET$EE1E:KUE --. pe iitubecь Ecam'iih- t.etet uatkobet tit at.s IBeCьtEcebCAEcetalEaiid.CailаCaMИ есегсEсeйEсехсалогdсамoйсамийх зEсемвсейEсeмсамомйсамойсамйм A e --.- bce bcoe.-e--- camo. camy.e---.- бBс…`
- `ru_v5_rapidocr`: ` :!1 1000000000 (+1Z) ! ! ! 2020.07.141 —Bе с M— pepne 1 весь  всё вся все сам самó  сама  сáми 2   всего всей   всех   самогó самой  самих з  всему  всей  всем  самому самой  самим 4 BcBcюcamócamú 5  всем   всей  всеми    самим   самой  самими 6обо всёмобо вс…`
- `ru_v5_paddle`: ` :!1 1000000000 (+1Z) ! ! ! 2020.07.141 —Bе с M— pepne 1 весь  всё вся все сам самó  сама  сáми 2   всего всей   всех   самогó самой  самих з  всему  всей  всем  самому самой  самим 4 BcBcюcamócamú 5  всем   всей  всеми    самим   самой  самими 6обо всёмобо вс…`
- `crnn_rukopys_hw`: ` І 176   : [ = А2 іb V тb I! М]- 1000000000(-12) [АВА?! 2020.О7.14ТРРФК ЕУТЕ - КАЕ lLmlRеСb → саm 13) 2/ 184  В 9 т 197  так т/ 1е сі вес все  в34 с6м сз2 саиа 2 сг сей всх саноо самон самім 3 воему всей вен самому самой сомии А В-1- се 6ю 5 -→/ Б -2і сьмо сан…`

### `Screenshot_20220331_113926 [det]`

- `rec_ch_now`: ` 4 11:39 () 个 :: Menex-BapaHOBa P... D yHpa*xHeHne riannunre AaHHbIe CyuECTBnTeJIbHbie BC MHO*KECTBeHHOM YHCJIe. WHxKeHep, Bpa4, reOnOr, npenOMaBaTeMb, CTpoHTenb, 9KOHOMMCT, MaTeMaTHK, ΦH3HK, XyIOXHHK, HO3T, apTHCTKa, apXHTeKTOp, *ypHaSIHCT, yYHTeJbHHua, MeHeX…`
- `ru_v3`: `A kB: aG... .... 11:39 0 9$ .- .. Meлex-5apaнoвa P... Б уражнение ганишите данне сушествительные B. MHOжеcTBеHHOM чиcлe. Uнжeнep, Bpaч, reолоr, npenодаaвateлb, ctpоTeлb, SkOhомисt, Mатeматик, фи3иK, xyдожниk, поэт, аpтистка, аpxитekтоp, жypналисT, учитeльница,…`
- `ru_v5_rapidocr`: `0/ 4 11:39() ← • • • Мелех-Баранова Р... 2 у пражнение папишите данные существительные вс множественном числе. Инженер, врач, геолог, преподаватель, строитель, экономист, математик, физик, художник, поэт, артистка, архитектор, журналист, учительница,менеджер, …`
- `ru_v5_paddle`: `0/ 4 11:39() ← • • • Мелех-Баранова Р... 2 у пражнение папишите данные существительные вс множественном числе. Инженер, врач, геолог, преподаватель, строитель, экономист, математик, физик, художник, поэт, артистка, архитектор, журналист, учительница,менеджер, …`
- `crnn_rukopys_hw`: `А 20 д6лі зві 11:390(1-) - ооо Мелех-Баранова Р... 5 У пражненне палишите цанньс сущесівительнье ві множественном числе. Мнженер, врач, геолог, преподаватель, стронтель, зкономист, магематик, физик, художник, позт, аргистка, архнтектор, журналист, уічительница…`

### `Screenshot_20220331_113926 [proj]`

- `rec_ch_now`: `11:39） ←Menex-BapaHoBa P..： : yIpaxHeHneZy.FiannHNTeAaHHbIeCyueCTBNTeJIbHbieBO MHOKECTBeHHOMYHCIe. HHKeHep, Bpa4, reOnOr, npnOaBaTeJIb, CTpOHTeJb, 3KOHOMHCT, MaTeMarHK, H3HK, xyOxHK, nOT, apTHCTKa, apXHTeKTOp, KypHaICT, yTeJbHHua, MeHeIKp, yHHK,yHHHa, careJb, …`
- `ru_v3`: `11:39 O d5 4 889 4g... ....u Meлex-bapahoba P...5 уражнениеZ.rанишигеданныеCyшесTBигелbHыtBо MнOжесTBеHHOмисле. Инжeнеp,Bpач,гeолоr,пpeлодаватeль,cтроитeль,экономист. MатeматиK, фи3иk, xyдожниk,поэT, аpтистка, аpxитeктоp,жyрналиcт. учителница, Mенеджеp, yчениK…`
- `ru_v5_rapidocr`: `11:39( 4  ←Мелех-Баранова Р...: у пражнение 29.гапишитеданные существительные во множественном числе. Инженер, врач, геолог, преподаватель, строитель, экономист, математик, физик, художник, поэт, артистка, архитектор, журналист, учительница,менеджер, ученик, у…`
- `ru_v5_paddle`: `11:39( 4  ←Мелех-Баранова Р...: у пражнение 29.гапишитеданные существительные во множественном числе. Инженер, врач, геолог, преподаватель, строитель, экономист, математик, физик, художник, поэт, артистка, архитектор, журналист, учительница,менеджер, ученик, у…`
- `crnn_rukopys_hw`: `1390(2) 1000 д6 I Мелех-БарановаР... Б): УІражнениє ї. палишитє данньіє существиіельньс во МНоЖеСтвенном числе. Мнженер, врач, геолог, преподаватель, стронтель, зономист, математик, физик, художник, позт, артистка, архнтектор, хурналист, кчительнища, менеджер,…`

## 3c. det 出框 vs 跳过 det(投影分行) —— 决定要不要给手写做单独入口

| 模型 | 图片 | 模式 | GT 字符 | 识别出的字符 | 比例 | boxes |
|---|---|---|---|---|---|---|
| `rec_ch_now` | real_00_scan | page | 4949 | 334 | 7% | 1 |
| `rec_ch_now` | real_01_scan | page | 3582 | 450 | 13% | 14 |
| `rec_ch_now` | real_02_light | page | 3290 | 34 | 1% | 32 |
| `rec_ch_now` | real_00_scan | proj | 4949 | 706 | 14% | 21 |
| `rec_ch_now` | real_01_scan | proj | 3582 | 748 | 21% | 22 |
| `rec_ch_now` | real_02_light | proj | 3290 | 1 | 0% | 1 |
| `rec_ch_now` | Screenshot_20220331_113023 | page | 431 | 429 | 100% | 1 |
| `rec_ch_now` | Screenshot_20220331_113947 | page | 309 | 302 | 98% | 9 |
| `rec_ch_now` | Screenshot_20220331_113023 | proj | 431 | 382 | 89% | 8 |
| `rec_ch_now` | Screenshot_20220331_113947 | proj | 309 | 270 | 87% | 9 |
| `ru_v3` | real_00_scan | page | 4949 | 328 | 7% | 1 |
| `ru_v3` | real_01_scan | page | 3582 | 490 | 14% | 14 |
| `ru_v3` | real_02_light | page | 3290 | 36 | 1% | 32 |
| `ru_v3` | real_00_scan | proj | 4949 | 1449 | 29% | 21 |
| `ru_v3` | real_01_scan | proj | 3582 | 1323 | 37% | 22 |
| `ru_v3` | real_02_light | proj | 3290 | 0 | 0% | 1 |
| `ru_v3` | Screenshot_20220331_113023 | page | 431 | 413 | 96% | 1 |
| `ru_v3` | Screenshot_20220331_113947 | page | 309 | 298 | 96% | 9 |
| `ru_v3` | Screenshot_20220331_113023 | proj | 431 | 413 | 96% | 8 |
| `ru_v3` | Screenshot_20220331_113947 | proj | 309 | 296 | 96% | 9 |
| `ru_v5_rapidocr` | real_00_scan | page | 4949 | 318 | 6% | 1 |
| `ru_v5_rapidocr` | real_01_scan | page | 3582 | 437 | 12% | 14 |
| `ru_v5_rapidocr` | real_02_light | page | 3290 | 9 | 0% | 32 |
| `ru_v5_rapidocr` | real_00_scan | proj | 4949 | 1288 | 26% | 21 |
| `ru_v5_rapidocr` | real_01_scan | proj | 3582 | 1045 | 29% | 22 |
| `ru_v5_rapidocr` | real_02_light | proj | 3290 | 0 | 0% | 1 |
| `ru_v5_rapidocr` | Screenshot_20220331_113023 | page | 431 | 432 | 100% | 1 |
| `ru_v5_rapidocr` | Screenshot_20220331_113947 | page | 309 | 291 | 94% | 9 |
| `ru_v5_rapidocr` | Screenshot_20220331_113023 | proj | 431 | 431 | 100% | 8 |
| `ru_v5_rapidocr` | Screenshot_20220331_113947 | proj | 309 | 292 | 94% | 9 |
| `ru_v5_paddle` | real_00_scan | page | 4949 | 318 | 6% | 1 |
| `ru_v5_paddle` | real_01_scan | page | 3582 | 437 | 12% | 14 |
| `ru_v5_paddle` | real_02_light | page | 3290 | 9 | 0% | 32 |
| `ru_v5_paddle` | real_00_scan | proj | 4949 | 1288 | 26% | 21 |
| `ru_v5_paddle` | real_01_scan | proj | 3582 | 1045 | 29% | 22 |
| `ru_v5_paddle` | real_02_light | proj | 3290 | 0 | 0% | 1 |
| `ru_v5_paddle` | Screenshot_20220331_113023 | page | 431 | 432 | 100% | 1 |
| `ru_v5_paddle` | Screenshot_20220331_113947 | page | 309 | 291 | 94% | 9 |
| `ru_v5_paddle` | Screenshot_20220331_113023 | proj | 431 | 431 | 100% | 8 |
| `ru_v5_paddle` | Screenshot_20220331_113947 | proj | 309 | 292 | 94% | 9 |
| `crnn_rukopys_hw` | real_00_scan | page | 4949 | 363 | 7% | 1 |
| `crnn_rukopys_hw` | real_01_scan | page | 3582 | 523 | 15% | 14 |
| `crnn_rukopys_hw` | real_02_light | page | 3290 | 34 | 1% | 32 |
| `crnn_rukopys_hw` | real_00_scan | proj | 4949 | 751 | 15% | 21 |
| `crnn_rukopys_hw` | real_01_scan | proj | 3582 | 933 | 26% | 22 |
| `crnn_rukopys_hw` | real_02_light | proj | 3290 | 0 | 0% | 1 |
| `crnn_rukopys_hw` | Screenshot_20220331_113023 | page | 431 | 432 | 100% | 1 |
| `crnn_rukopys_hw` | Screenshot_20220331_113947 | page | 309 | 283 | 92% | 9 |
| `crnn_rukopys_hw` | Screenshot_20220331_113023 | proj | 431 | 430 | 100% | 8 |
| `crnn_rukopys_hw` | Screenshot_20220331_113947 | proj | 309 | 287 | 93% | 9 |

注:
- Otsu + 行投影(本表 proj 模式)在**扫描件**上把出框数从 det 的 1~14 行提到 21~22 行,恢复字符从 6~14% 提到 22~29%;\n- 同一个 proj 在 real_02_light(4032x3024 手机翻拍)上只出 1 个框、恢复 0%,换 adaptiveThreshold 也一样 => 翻拍页要先裁边/去阴影/纠偏,单纯跳过 det 不够;\n- 但即使分行成功,rec 的整页 CER 仍在 0.89~0.92 => **跳过 det 不能解决手写问题,瓶颈是 rec 模型本身**。

## 3d. 词表覆盖率(GT 里模型根本没法表示的字)

| 模型 | 词表大小 | GT 总字数 | 词表外字数 | 占比 | 具体字符(前 12) |
|---|---|---|---|---|---|
| `rec_ch_now` | 6624 | 25983 | 20637 | 79.43% | о x2249 е x1894 а x1568 т x1538 и x1429 н x1330 с x1120 в x996 р x938 л x778 к x671 м x587 |
| `ru_v3` | 164 | 25983 | 178 | 0.69% | » x44 « x42 " x20 – x14 [ x12 ] x12 ) x10 — x5 … x4 谢 x2 俄 x1 罗 x1 |
| `ru_v5_rapidocr` | 851 | 25983 | 14 | 0.05% | 谢 x2 俄 x1 罗 x1 斯 x1 留 x1 学 x1 申 x1 请 x1 材 x1 料 x1 清 x1 单 x1 |
| `ru_v5_paddle` | 851 | 25983 | 14 | 0.05% | 谢 x2 俄 x1 罗 x1 斯 x1 留 x1 学 x1 申 x1 请 x1 材 x1 料 x1 清 x1 单 x1 |
| `crnn_rukopys_hw` | 340 | 25983 | 143 | 0.55% | » x44 « x42 – x14 — x5 o x5 i x4 c x4 e x4 a x2 谢 x2 B x1 C x1 |

## 4. 典型错例(每模型每集合取编辑距离最大的若干条)


### `rec_ch_now`

**printed**

- `p01_ru_3lines` (编辑距离 35 / GT 42 字)
  - GT : `Сегодня хорошая погода, мы гуляем в парке.`
  - 识别: `CeronH9 xopoWag norona, Mbl ryngem B napke.`
  - 差异: `XXXXXXX=XXXXXXX=XXXXXX==+XX=XXXXXX=X=XXX`  (`=`同 `X`替换 `-`漏 `+`多)
- `p03_ru_4lines` (编辑距离 32 / GT 37 字)
  - GT : `Библиотека работает до восьми вечера.`
  - 识别: `Bu6nWoTeKa pa6oTaeT AO BOcbMW Beuepa.`
  - 差异: `XXXXXXXXXX=XXXXXXXX=XX=XXXXXX=XXXXXX=`  (`=`同 `X`替换 `-`漏 `+`多)
- `p02_ru_1line` (编辑距离 31 / GT 36 字)
  - GT : `Санкт-Петербург, Невский проспект 28`
  - 识别: `CaHKT-HIeTep6ypr, HeBcKuY 1IpocIeKT 28`
  - 差异: `XXXXX=+XXXXXXXXX==XXXXXXX=+XXXXXXXX===`  (`=`同 `X`替换 `-`漏 `+`多)
**handwritten**

- `syn_01` (编辑距离 27 / GT 27 字)
  - GT : `попереднього вибору товари,`
  - 识别: `noegeguymag`
  - 差异: `----------------XXXXXXXXXXX`  (`=`同 `X`替换 `-`漏 `+`多)
- `syn_00` (编辑距离 25 / GT 28 字)
  - GT : `Попри поетичний темперамент,`
  - 识别: `Tonpu noemutmua meunerauenm,`
  - 差异: `XXXXX=XXXXXXXXX=XXXXXXXXXXX=`  (`=`同 `X`替换 `-`漏 `+`多)
- `syn_07` (编辑距离 22 / GT 22 字)
  - GT : `заступник гендиректора`
  - 识别: `bacmynaukreugupekmo ka`
  - 差异: `XXXXXXXXXXXXXXXXXXXXXX`  (`=`同 `X`替换 `-`漏 `+`多)
**handwritten_page**

- `real_00_scan` (编辑距离 4931 / GT 4949 字)
  - GT : `Что такое честь? Честь - это нравственный стержень человека. Под нравс…`
  - 识别: `Ho,markakeecober-3morclboe eyecmbo kumonaliy Heatthlmt.Hemna.cbemereto…`
  - 差异: `(too long to visualise)`  (`=`同 `X`替换 `-`漏 `+`多)
- `real_01_scan` (编辑距离 3552 / GT 3582 字)
  - GT : `Игорь Ботов неравнодушен к поставленной им проблеме, он считает, что т…`
  - 识别: `gabomyujanercamoceMuoununuugluou,gucna llrlegyoboulo mbueuemoaoroclene…`
  - 差异: `(too long to visualise)`  (`=`同 `X`替换 `-`漏 `+`多)
- `real_02_light` (编辑距离 3290 / GT 3290 字)
  - GT : `Убью!». С. Львов использует яркое сравнение, когда говорит, что лай со…`
  - 识别: `#`
  - 差异: `(too long to visualise)`  (`=`同 `X`替换 `-`漏 `+`多)
**real_ru**

- `Screenshot_20220331_113023` (编辑距离 366 / GT 431 字)
  - GT : `Упражнение 19. Напишите данные существительные во множественном числе.…`
  - 识别: `YupaxKHeHHe 19. HaIHwHTe aHHbIe cCyueCTBHTeJIbHbIe BO MHOKECTBEHHOM4HC…`
  - 差异: `(too long to visualise)`  (`=`同 `X`替换 `-`漏 `+`多)
- `Screenshot_20220331_113947` (编辑距离 235 / GT 309 字)
  - GT : `Упражнение 32. Допишите предложения. 1. В библиотеке студенты читают .…`
  - 识别: `YnpaxHeHHe32.JonnwnTenpeoKeHHg. 1.B 6n6nnOTeKe cryneHTbI yHTaIOT...: 2…`
  - 差异: `-XXXXXXXXXX===----XXXXXXXXXXXXXXXXX====-`  (`=`同 `X`替换 `-`漏 `+`多)

### `ru_v3`

**printed**

- `p08_ru_all_mix` (编辑距离 20 / GT 36 字)
  - GT : `Товар: словарь, 3 шт., цена 899 руб.`
  - 识别: `Tobap: cлobapb, 3 шT., Leha 399 py6.`
  - 差异: `XXXXX==X=XXXXX=====X===XXXX=X===XXX=`  (`=`同 `X`替换 `-`漏 `+`多)
- `p01_ru_3lines` (编辑距离 17 / GT 24 字)
  - GT : `Москва — столица России.`
  - 识别: `MockBa -- cTOлиLa Poccии.`
  - 差异: `XXXXXX=+X=XXX==XX=XXXX===`  (`=`同 `X`替换 `-`漏 `+`多)
- `p03_ru_4lines` (编辑距离 13 / GT 31 字)
  - GT : `Студенты сдают экзамены в июне.`
  - 识别: `CтyдеHтыI CдаOT SKзаMены B иоHе.`
  - 差异: `X=X==X==+=X==XX=XX==X====X==XX==`  (`=`同 `X`替换 `-`漏 `+`多)
**handwritten**

- `syn_00` (编辑距离 30 / GT 28 字)
  - GT : `Попри поетичний темперамент,`
  - 识别: `I Inu nle mutkuu me u ne rauerm.`
  - 差异: `++++XXXXX=XXXXXXXXX=XXXXXXXXXXXX`  (`=`同 `X`替换 `-`漏 `+`多)
- `syn_01` (编辑距离 25 / GT 27 字)
  - GT : `попереднього вибору товари,`
  - 识别: `Wou y'ywu to y uhay.`
  - 差异: `---XXXXXXXXX=----XX=XXXXXXX`  (`=`同 `X`替换 `-`漏 `+`多)
- `syn_06` (编辑距离 23 / GT 22 字)
  - GT : `Вапнярки Велика Русава`
  - 识别: `Sahls juku Senuka S ycaga`
  - 差异: `++XXXXXXXX=XXXXXX=+XXXXXX`  (`=`同 `X`替换 `-`漏 `+`多)
**handwritten_page**

- `real_00_scan` (编辑距离 4885 / GT 4949 字)
  - GT : `Что такое честь? Честь - это нравственный стержень человека. Под нравс…`
  - 识别: `Ho, mrt kk tenobk- no gcubol eyudecribo Kooho Hsost rsus.tlem k aebem'…`
  - 差异: `(too long to visualise)`  (`=`同 `X`替换 `-`漏 `+`多)
- `real_01_scan` (编辑距离 3462 / GT 3582 字)
  - GT : `Игорь Ботов неравнодушен к поставленной им проблеме, он считает, что т…`
  - 识别: `otd. и г lц Sinx mnlok. coslyltse.. .. t us. de illltit. дуu Iseilliit…`
  - 差异: `(too long to visualise)`  (`=`同 `X`替换 `-`漏 `+`多)
- `real_02_light` (编辑距离 3290 / GT 3290 字)
  - GT : `Убью!». С. Львов использует яркое сравнение, когда говорит, что лай со…`
  - 识别: ``
  - 差异: `(too long to visualise)`  (`=`同 `X`替换 `-`漏 `+`多)
**real_ru**

- `Screenshot_20220331_113023` (编辑距离 208 / GT 431 字)
  - GT : `Упражнение 19. Напишите данные существительные во множественном числе.…`
  - 识别: `VиpажнeниеO.HалишитeданныесyшестBигeльныеBо MHOжeCTBEHHOM чиCлe. аYлиц…`
  - 差异: `(too long to visualise)`  (`=`同 `X`替换 `-`漏 `+`多)
- `Screenshot_20220331_113947` (编辑距离 165 / GT 309 字)
  - GT : `Упражнение 32. Допишите предложения. 1. В библиотеке студенты читают .…`
  - 识别: `VпрaжheHие 32. ДоишиTe пpeдожeHия. I. B 6иблиOTeke cryдeHTь чиTaoT ...…`
  - 差异: `X==X=XXX=========-===XX==XX=-==XX====X==`  (`=`同 `X`替换 `-`漏 `+`多)

### `ru_v5_rapidocr`

**printed**

- `p05_ru_cn_mix` (编辑距离 11 / GT 11 字)
  - GT : `俄罗斯留学申请材料清单`
  - 识别: ``
  - 差异: `-----------`  (`=`同 `X`替换 `-`漏 `+`多)
- `p08_ru_all_mix` (编辑距离 3 / GT 22 字)
  - GT : `谢谢！Спасибо за покупку!`
  - 识别: `Спасибо за покупку!`
  - 差异: `---===================`  (`=`同 `X`替换 `-`漏 `+`多)
- `p04_ru_en_mix` (编辑距离 1 / GT 33 字)
  - GT : `Забронируйте отель на Booking.com`
  - 识别: `Забронируйте отель на Вooking.com`
  - 差异: `======================X==========`  (`=`同 `X`替换 `-`漏 `+`多)
**handwritten**

- `syn_01` (编辑距离 24 / GT 27 字)
  - GT : `попереднього вибору товари,`
  - 识别: `none pe у a`
  - 差异: `--------XXXX=--XXX==------X`  (`=`同 `X`替换 `-`漏 `+`多)
- `syn_06` (编辑距离 15 / GT 22 字)
  - GT : `Вапнярки Велика Русава`
  - 识别: `дanнркu Denuкa S уcaвa`
  - 差异: `XXX=-==X=XXXX=X=+X=XX=X`  (`=`同 `X`替换 `-`漏 `+`多)
- `syn_04` (编辑距离 8 / GT 22 字)
  - GT : `(зозулинець салеповий)`
  - 识别: `зозуинецо солетовн`
  - 差异: `-====-====X==X==X==--X`  (`=`同 `X`替换 `-`漏 `+`多)
**handwritten_page**

- `real_00_scan` (编辑距离 4791 / GT 4949 字)
  - GT : `Что такое честь? Честь - это нравственный стержень человека. Под нравс…`
  - 识别: `нодмак как rеловек-эмоивое суцето komonau ненае нум. Hемна свеме чесоб…`
  - 差异: `(too long to visualise)`  (`=`同 `X`替换 `-`漏 `+`多)
- `real_01_scan` (编辑距离 3325 / GT 3582 字)
  - GT : `Игорь Ботов неравнодушен к поставленной им проблеме, он считает, что т…`
  - 识别: `yp06016 dadomy - meu как сонное сообуешше о мон шио дека лроши cypa бо…`
  - 差异: `(too long to visualise)`  (`=`同 `X`替换 `-`漏 `+`多)
- `real_02_light` (编辑距离 3290 / GT 3290 字)
  - GT : `Убью!». С. Львов использует яркое сравнение, когда говорит, что лай со…`
  - 识别: ``
  - 差异: `(too long to visualise)`  (`=`同 `X`替换 `-`漏 `+`多)
**real_ru**

- `Screenshot_20220331_113947` (编辑距离 19 / GT 309 字)
  - GT : `Упражнение 32. Допишите предложения. 1. В библиотеке студенты читают .…`
  - 识别: `Упражнение 32. Допишите предложения. 1.Вбиблиотеке студенты читают ...…`
  - 差异: `=======================================-`  (`=`同 `X`替换 `-`漏 `+`多)
- `Screenshot_20220331_113023` (编辑距离 3 / GT 431 字)
  - GT : `Упражнение 19. Напишите данные существительные во множественном числе.…`
  - 识别: `Упражнение 19. Напишите данные существительные во множественном числе.…`
  - 差异: `(too long to visualise)`  (`=`同 `X`替换 `-`漏 `+`多)

### `ru_v5_paddle`

**printed**

- `p05_ru_cn_mix` (编辑距离 11 / GT 11 字)
  - GT : `俄罗斯留学申请材料清单`
  - 识别: ``
  - 差异: `-----------`  (`=`同 `X`替换 `-`漏 `+`多)
- `p08_ru_all_mix` (编辑距离 3 / GT 22 字)
  - GT : `谢谢！Спасибо за покупку!`
  - 识别: `Спасибо за покупку!`
  - 差异: `---===================`  (`=`同 `X`替换 `-`漏 `+`多)
- `p04_ru_en_mix` (编辑距离 1 / GT 33 字)
  - GT : `Забронируйте отель на Booking.com`
  - 识别: `Забронируйте отель на Вooking.com`
  - 差异: `======================X==========`  (`=`同 `X`替换 `-`漏 `+`多)
**handwritten**

- `syn_01` (编辑距离 24 / GT 27 字)
  - GT : `попереднього вибору товари,`
  - 识别: `none pe у a`
  - 差异: `--------XXXX=--XXX==------X`  (`=`同 `X`替换 `-`漏 `+`多)
- `syn_06` (编辑距离 15 / GT 22 字)
  - GT : `Вапнярки Велика Русава`
  - 识别: `дanнркu Denuкa S уcaвa`
  - 差异: `XXX=-==X=XXXX=X=+X=XX=X`  (`=`同 `X`替换 `-`漏 `+`多)
- `syn_04` (编辑距离 8 / GT 22 字)
  - GT : `(зозулинець салеповий)`
  - 识别: `зозуинецо солетовн`
  - 差异: `-====-====X==X==X==--X`  (`=`同 `X`替换 `-`漏 `+`多)
**handwritten_page**

- `real_00_scan` (编辑距离 4791 / GT 4949 字)
  - GT : `Что такое честь? Честь - это нравственный стержень человека. Под нравс…`
  - 识别: `нодмак как rеловек-эмоивое суцето komonau ненае нум. Hемна свеме чесоб…`
  - 差异: `(too long to visualise)`  (`=`同 `X`替换 `-`漏 `+`多)
- `real_01_scan` (编辑距离 3325 / GT 3582 字)
  - GT : `Игорь Ботов неравнодушен к поставленной им проблеме, он считает, что т…`
  - 识别: `yp06016 dadomy - meu как сонное сообуешше о мон шио дека лроши cypa бо…`
  - 差异: `(too long to visualise)`  (`=`同 `X`替换 `-`漏 `+`多)
- `real_02_light` (编辑距离 3290 / GT 3290 字)
  - GT : `Убью!». С. Львов использует яркое сравнение, когда говорит, что лай со…`
  - 识别: ``
  - 差异: `(too long to visualise)`  (`=`同 `X`替换 `-`漏 `+`多)
**real_ru**

- `Screenshot_20220331_113947` (编辑距离 19 / GT 309 字)
  - GT : `Упражнение 32. Допишите предложения. 1. В библиотеке студенты читают .…`
  - 识别: `Упражнение 32. Допишите предложения. 1.Вбиблиотеке студенты читают ...…`
  - 差异: `=======================================-`  (`=`同 `X`替换 `-`漏 `+`多)
- `Screenshot_20220331_113023` (编辑距离 3 / GT 431 字)
  - GT : `Упражнение 19. Напишите данные существительные во множественном числе.…`
  - 识别: `Упражнение 19. Напишите данные существительные во множественном числе.…`
  - 差异: `(too long to visualise)`  (`=`同 `X`替换 `-`漏 `+`多)

### `crnn_rukopys_hw`

**printed**

- `p05_ru_cn_mix` (编辑距离 22 / GT 11 字)
  - GT : `俄罗斯留学申请材料清单`
  - 识别: `18 НТЕЕ [І ] + S Y+ ЕА`
  - 差异: `+++++++++++XXXXXXXXXXX`  (`=`同 `X`替换 `-`漏 `+`多)
- `p08_ru_all_mix` (编辑距离 17 / GT 46 字)
  - GT : `E-mail: info@example.ru, тел. +7 495 123-45-67`
  - 识别: `Е-mаl: іnrоQехаmрlе. rи, тел. + 7495123-45-67`
  - 差异: `X==-X===X=XXXXXX=X=X=+=X========XX===-==`  (`=`同 `X`替换 `-`漏 `+`多)
- `p04_ru_en_mix` (编辑距离 14 / GT 40 字)
  - GT : `Check-in после 14:00, check-out до 12:00`
  - 识别: `Спеск-іn после 14:00, сnеск-ОН' до 12:00`
  - 差异: `XXXXX=X===============XXXXX=XXX=========`  (`=`同 `X`替换 `-`漏 `+`多)
**handwritten**

- `syn_01` (编辑距离 16 / GT 27 字)
  - GT : `попереднього вибору товари,`
  - 识别: `ттате дедного бібо ду жева ди`
  - 差异: `+XXX=+X===-====XX==+X==XX==XXX`  (`=`同 `X`替换 `-`漏 `+`多)
- `syn_04` (编辑距离 7 / GT 22 字)
  - GT : `(зозулинець салеповий)`
  - 识别: `зозумнець салетвим`
  - 差异: `-====-X=========-X==-X`  (`=`同 `X`替换 `-`漏 `+`多)
- `syn_05` (编辑距离 6 / GT 22 字)
  - GT : `Сасекорбо Саука Саятон`
  - 识别: `Сосесфбо Стуса Саятон`
  - 差异: `=X==-XX====X=X========`  (`=`同 `X`替换 `-`漏 `+`多)
**handwritten_page**

- `real_00_scan` (编辑距离 4609 / GT 4949 字)
  - GT : `Что такое честь? Честь - это нравственный стержень человека. Под нравс…`
  - 识别: `но, так как человек-зто живо существо которомс неном пути. Нетна свете…`
  - 差异: `(too long to visualise)`  (`=`同 `X`替换 `-`漏 `+`多)
- `real_02_light` (编辑距离 3290 / GT 3290 字)
  - GT : `Убью!». С. Львов использует яркое сравнение, когда говорит, что лай со…`
  - 识别: ``
  - 差异: `(too long to visualise)`  (`=`同 `X`替换 `-`漏 `+`多)
- `real_01_scan` (编辑距离 3129 / GT 3582 字)
  - GT : `Игорь Ботов неравнодушен к поставленной им проблеме, он считает, что т…`
  - 识别: `Удоволь работу - тому нак лонкої сообщенне в том, что дока герошии сер…`
  - 差异: `(too long to visualise)`  (`=`同 `X`替换 `-`漏 `+`多)
**real_ru**

- `Screenshot_20220331_113947` (编辑距离 70 / GT 309 字)
  - GT : `Упражнение 32. Допишите предложения. 1. В библиотеке студенты читают .…`
  - 识别: `Упражнснис 32. Допишите предложения. 1. ВБиблиотеке студенть читают...…`
  - 差异: `======X==X==============================`  (`=`同 `X`替换 `-`漏 `+`多)
- `Screenshot_20220331_113023` (编辑距离 47 / GT 431 字)
  - GT : `Упражнение 19. Напишите данные существительные во множественном числе.…`
  - 识别: `Упражненнє 19. Напишите даннье существительнье до МНОЖЕСТВЕННОМ ЧИСЛЕ.…`
  - 差异: `(too long to visualise)`  (`=`同 `X`替换 `-`漏 `+`多)

# PP-OCRv4 PC 参考规格书 (Android 端 1:1 照抄用)

> 作者: pc-ref (team ocr-batch1) · 环境: `tools/ocrenv` (python 3.13.2 / numpy 2.5.3 / opencv 5.0.0 /
> onnxruntime 1.30.0) · 参考实现: `tools/ocr_ref/pc_ocr_ref.py`
> 所有数字都有实测依据: 见 §13 证据索引 (原始日志 `tools/ocr_ref/out/experiments.txt`)。
> **凡本文写了"实测"的,都是本机跑出来的;写了"上游"的,是 RapidOCR 1.2.3 / PaddleOCR 源码原文。**

---

## 0. 摘要

流水线: **det (DB, PP-OCRv4) -> 四点框 -> 透视裁剪 -> (可选 cls 180°) -> rec (CRNN+CTC, PP-OCRv4) -> 贪心 CTC 解码**。

与 Android 端有关的硬结论(每条的实测证据在 §11):

| # | 结论 |
|---|------|
| 1 | det 输入归一化必须用 ImageNet `mean=[0.485,0.456,0.406] std=[0.229,0.224,0.225]`,scale=1/255 |
| 2 | det 预处理尺寸 = `t=int(dim*ratio)` 再 `int(round(t/32))*32`,其中 **round 是"四舍六入五成双"**(Python `round`),不是 Kotlin `Math.round` |
| 3 | 长边限制 960,`limit_type='max'`(RapidOCR 的 config 默认是 **736/'min'**,不要用) |
| 4 | DB 参数: `thresh=0.3, box_thresh=0.6, unclip_ratio=1.5, max_candidates=1000, min_size=3(+2)`,**不做 bitmap 膨胀** |
| 5 | unclip 用多边形外扩,miter 直线求交即可(pyclipper JT_ROUND 等价;实测最大偏差 0.000 px);Android 不需要移植 pyclipper |
| 6 | 框回原图 = `clip(round(x / ratio), 0, src_w)`(整数、四舍五入、上界含 src_w) |
| 7 | 框排序 = 按"第 0 号点的 y,再按 x",稳定排序 |
| 8 | 裁剪 = 透视变换 `INTER_CUBIC` + `BORDER_REPLICATE`,边长用 `int(截断)`,且 **h/w ≥ 1.5 时 `np.rot90`** |
| 9 | rec 输入高 **48**,宽 `W = max(320, ceil(48*w/h))`,缩放后**右侧补 0 到 W**;归一化 `(x/255 - 0.5)/0.5` |
| 10 | rec 输出 `T = ceil(W/8)` 帧,每帧 6625 维;`blank=0`;帧索引 `i>=1 -> 字符表[i-1]`;字符表 = `ppocr_keys_v1.txt` 6623 字 **+ 末尾一个空格** |
| 11 | 每行置信度 = 被保留字符的 max-prob 平均值(空串 = 0.0)。RapidOCR 用 `mean(conf+[1e-50])`,分母是 n+1,会让 13 字行读成 0.9249 而不是 0.9961 —— 必须二选一并写死 |

---

## 1. 模型文件 (`D:/ai/models/ocr/ch/`)

| 文件 | 字节 | sha256 |
|---|---|---|
| `ch_PP-OCRv4_det_infer.onnx` | 4 745 517 | `d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9` |
| `ch_PP-OCRv4_rec_infer.onnx` | 10 857 958 | `48fc40f24f6d2a207a2b1091d3437eb3cc3eb6b676dc3ef9c37384005483683b` |
| `ch_ppocr_mobile_v2.0_cls_infer.onnx` (可选) | 585 532 | `e47acedf663230f8863ff1ab0e64dd2d82b838fceb5957146dab185a89d6215c` |
| `ppocr_keys_v1.txt` | 26 250 | `a1c84d9bdb9ab29043c58896224d32941783eb821629618416dcb08f12886492` |

下载(实测可直连,`curl -L`):

```bash
curl -L -o ch_PP-OCRv4_det_infer.onnx \
  https://huggingface.co/SWHL/RapidOCR/resolve/main/PP-OCRv4/ch_PP-OCRv4_det_infer.onnx
curl -L -o ch_PP-OCRv4_rec_infer.onnx \
  https://huggingface.co/SWHL/RapidOCR/resolve/main/PP-OCRv4/ch_PP-OCRv4_rec_infer.onnx
curl -L -o ch_ppocr_mobile_v2.0_cls_infer.onnx \
  https://huggingface.co/SWHL/RapidOCR/resolve/main/PP-OCRv1/ch_ppocr_mobile_v2.0_cls_infer.onnx
curl -L -o ppocr_keys_v1.txt \
  https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/main/ppocr/utils/ppocr_keys_v1.txt
```

---

## 2. 模型 I/O (onnxruntime 1.30.0 实测,`SectionName` 区分大小写)

| 模型 | 输入名 | 输入 shape (声明) | 实测输入 | 输出名 | 输出 shape (声明) | 实测输出 |
|---|---|---|---|---|---|---|
| det | `x` | `[N,3,H,W]` 全动态 | `[1,3,384,896]` , `[1,3,448,960]` | `sigmoid_0.tmp_0` | `[N,1,H,W]` | `[1,1,384,896]` , `[1,1,448,960]` |
| rec | `x` | `[N,3,'?','?']` (H/W 动态) | `[1,3,48,702]` 等 | `softmax_11.tmp_0` | `[N,T,6625]` | `[1,88,6625]` |
| cls | `x` | `[N,3,'?','?']` 动态 | `[1,3,48,192]` | `save_infer_model/scale_0.tmp_1` | `[N,2]` | `[1,2]` |

- 全部 `tensor(float)`。det 输出**已经过 sigmoid**(名字即 `sigmoid_0.tmp_0`),值域 [0,1],**不要再自己 sigmoid**。
- rec 输出**已经过 softmax**(名字即 `softmax_11.tmp_0`),每帧 6625 维概率和=1。
- rec 输出帧数实测 `T = ceil(W/8)`:W=48→6, 96→12, 320→40, 375→47, 471→59, 702→88, 727→91, 960→120。
- rec 模型自带 `character` metadata(6623 行),**与 `ppocr_keys_v1.txt` 逐字符完全一致**(实测 True)。字符表可内置,不必读 metadata。
- **类别数对齐**:6625 = 1(blank) + 6623(字典) + 1(空格),见 §7。

---

## 3. det 预处理(逐行,可直接翻译 Kotlin)

输入: 原图 (BGR 或 RGB,见 §11-9) `H x W x 3`;常量 `LIMIT = 960`,`DIV = 32`。

```
ratio = 1.0
if (max(H, W) > LIMIT) ratio = LIMIT / max(H, W)        // limit_type = 'max'
tH = (int)(H * ratio)                                   // ★ 先截断(向零取整)
tW = (int)(W * ratio)
rH = max(roundHalfEven(tH / 32) * 32, 32)               // ★ 再就近取 32 的倍数
rW = max(roundHalfEven(tW / 32) * 32, 32)
img = cv2.resize(src, (rW, rH))                         // 双线性 (cv2.resize 默认 INTER_LINEAR)
ratioH = rH / (double) H  ;  ratioW = rW / (double) W    // ★ 用最终尺寸反算,后处理要用
x = img.astype(float32) * (1f/255f)
x = (x - mean[c]) / std[c]                              // mean/std 见下,HWC 逐通道
tensor = x.transpose(2,0,1)[None]                       // [1,3,rH,rW] 连续内存
```

- `mean = [0.485, 0.456, 0.406]`,`std = [0.229, 0.224, 0.225]`(scale `1./255.`,order `hwc`,上游 `NormalizeImage`)。
- `roundHalfEven(t/32)`:因为 t 是整数、除数是 2 的幂,`t/32` 可被 double 精确表示,所以可以这样算:
  ```
  double q = t / 32.0;  int k = (int) Math.floor(q);  double r = q - k;
  if (r > 0.5) k++;  else if (r == 0.5 && (k & 1) == 1) k++;   // 五成双 => 取偶数
  ```
- **陷阱 A(五成双 vs 五入)**: `rel_a.png` 400px 高,`tH=400, 400/32=12.5` → 五成双得 384,五入得 416。实测: 两种尺寸下 3 个框角点最大差 1.0 px、IoU 0.951;文本相同,但框值不同 → 设备端逐框比对会失败。
- **陷阱 B(先截断)**: `int(H*ratio)` 这一步不能省。实测 1024x495 样例: 先截断 → `tH=464 → 464/32=14.5 → 14 → 448`;不截断 → `round(464.0625/32)=15 → 480`。两种规则框角点最大差 1.0 px、IoU 0.956。
- **陷阱 C(不要 ceil)**: ceil 会把 rel_a 变成 928x416(角点差 3.0 px、IoU 0.900)。
- 不需要再把张量补到 32 的倍数 —— resize 的目标尺寸本身就是 32 的倍数;也不要额外 padding。

---

## 4. det 后处理 DBPostProcess(`thresh=0.3, box_thresh=0.6, unclip_ratio=1.5, max_candidates=1000, min_size=3`)

输入 `prob = det_out[0,0]`,shape `[rH, rW]`(概率图),以及 §3 的 `ratioH/ratioW` 和 `srcH/srcW`。

```
bitmap = (prob > 0.3)                      // 灰度 0/255;★ 不做 cv2.dilate(见 §10)
contours = findContours(bitmap*255, RETR_LIST, CHAIN_APPROX_SIMPLE)
for contour in contours[0 : min(len, 1000)]:
    quad, sside = getMiniBoxes(contour)                  // 见下
    if (sside < 3) continue                              // min_size
    score = boxScoreFast(prob, quad)                     // 见下,score_mode='fast'
    if (score < 0.6) continue                            // box_thresh
    dist  = abs(polyArea(quad)) * 1.5 / polyPerimeter(quad)
    quad2 = offsetPolygonMiter(quad, dist)               // 见 §5
    quad3, sside2 = getMiniBoxes(quad2)
    if (sside2 < 5) continue                             // ★ min_size + 2 = 5,不是 3
    box = round(quad3 / (ratioW, ratioH)) clip 到 [0,srcW]x[0,srcH]   // 整数
```

`getMiniBoxes(contour)`(上游原文,顺序 TL,TR,BR,BL):

```
rect  = cv2.minAreaRect(contour)
pts   = cv2.boxPoints(rect) 排序后按 x 升序 (只按 x,不要按 (x,y))
i1,i4 = (pts[1].y > pts[0].y) ? (0,1) : (1,0)      // 左侧两点: 上面的是 TL
i2,i3 = (pts[3].y > pts[2].y) ? (2,3) : (3,2)      // 右侧两点: 上面的是 TR
quad  = [pts[i1], pts[i2], pts[i3], pts[i4]]        // TL, TR, BR, BL
sside = min(rect.width, rect.height)                // min(rect[1])
```

`boxScoreFast(prob, quad)`(score_mode='fast',在**概率图**上算,不是 bitmap):

```
xmin = clip(floor(min x of quad), 0, rW-1);  xmax = clip(ceil(max x), 0, rW-1)
ymin = clip(floor(min y of quad), 0, rH-1);  ymax = clip(ceil(max y), 0, rH-1)
mask = zeros((ymax-ymin+1, xmax-xmin+1), u8)
fillPoly(mask, quad - (xmin,ymin), 1)               // 多边形内为 1
score = mean(prob[ymin:ymax+1, xmin:xmax+1][mask == 1])
```

输出框格式:`[x1,y1,x2,y2,x3,y3,x4,y4]`(TL,TR,BR,BL,**int32 整数**),再加上每框的 det score。

- 实测:`rel_a` 概率图轮廓数 3、出框 3;`rel_b` 3/3;2560x1600 截图 26 轮廓 / 20 框。
- `thresh`/`box_thresh` 实测影响(2560x1600 截图): `box_thresh=0.6` → 20 框,`0.5` → 21 框;`thresh` 0.3 → 26 轮廓。参数必须一致,否则框数不同。

---

## 5. unclip:多边形外扩(不依赖 pyclipper)

上游用 `pyclipper.PyclipperOffset().AddPath(box, JT_ROUND, ET_CLOSEDPOLYGON).Execute(distance)`。
Android 上直接照抄下面这个等价实现(**输入四边形就是"精确旋转矩形"**,因此外层再取 minAreaRect 后,圆角与尖角的差别为 0):

```
nrm[j]  = 边 j 的单位法线 = normalize(p[j+1]-p[j]) 旋转 -90°: (dy, -dx)
// 若外扩后面积反而变小则整体取反法线(保证向外)
for j in 0..n-1:
    k = (j+1) % n
    lineA = (p[j] + nrm[j]*dist, dir[j])          // 边 j 的外扩直线
    lineB = (p[k] + nrm[k]*dist, dir[k])          // 边 k 的外扩直线
    ip = 两条直线的交点(平行则退化为 bevel: 输出 p[k]+nrm[j]*dist 与 p[k]+nrm[k]*dist)
    if (|ip - p[k]| > 10*dist) 输出 p[k]+nrm[j]*dist 与 p[k]+nrm[k]*dist   // 过尖 -> bevel
    else 输出 ip
```

实测(E7):与 pyclipper 1.4.0 对比,**把多边形坐标 ×1000 再喂给 pyclipper(消除它本身的整数量化)后, 角点最大偏差 0.000 px、IoU = 1.000000、文本完全一致**;
直接喂整数坐标(上游做法)才出现 1.0 px 偏差。⇒ miter 实现与 JT_ROUND 在本场景等价,Android 无需 pyclipper。

`polyArea` 用鞋带公式(带符号),`polyPerimeter` 用闭合边长和(RapidOCR 用 shapely 的 `.area` / `.length`,等价)。

---

## 6. 裁剪 get_rotate_crop_image(上游原文 + 实测)

```
cropW = (int) max(||p0-p1||, ||p2-p3||)        // ★ 截断,不四舍五入
cropH = (int) max(||p0-p3||, ||p1-p2||)
M     = getPerspectiveTransform(quad, [(0,0),(cropW,0),(cropW,cropH),(0,cropH)])
crop  = warpPerspective(img, M, (cropW, cropH), borderMode=BORDER_REPLICATE, flags=INTER_CUBIC)
if (cropH / (double) cropW >= 1.5) crop = rot90(crop)     // ★ 竖长条旋转成横向
```

实测(E8): 40x320 的竖框 → warp 目标 40x320 → 返回 shape `(40,320)`(已 rot90);300x80 的横框 → 返回 `(80,300)`(未 rot90)。

---

## 7. rec 预处理 + CTC 解码

### 7.1 输入张量

```
ratio     = cropW / (double) cropH
resizedW  = max(1, (int) ceil(48 * ratio))          // imgH = 48
W         = max(320, resizedW)                      // rec_img_shape = (3,48,320)
img48     = cv2.resize(crop, (resizedW, 48))        // 双线性;高固定 48
x         = img48.astype(float32).transpose(2,0,1) / 255f
x         = (x - 0.5f) / 0.5f
tensor    = zeros((3, 48, W));  tensor[:, :, 0:resizedW] = x    // ★ 右侧补 0 到 W
输入 shape: [1, 3, 48, W]  ->  输出 [1, ceil(W/8), 6625]
```

- **高 48 是硬要求**:实测(`rel_a` 3 行 / 2560x1600 截图 20 块)平均置信度 h=48: 0.9961 / 0.9079;h=32: 0.9880 / 0.8712;h=64: 0.9839 / 0.8813。截图上的文本在 32/64 下还会变。
- **宽度规则必须一致**:`W = max(320, ceil(48*w/h))` + 右侧补零 = PaddleOCR 规则。RapidOCR 1.2.3 只有单张裁剪时 `W = int(48*w/h)`(没有 320 下限,不补零)。实测同一批 20 个真实裁剪,两种规则 **15/20 文本相同**,不同的 5 个是: `'1st'` vs `'1 st'`、`'攻：1500%'` vs `'攻：1500'`、`'2'`(0.382) vs `'02'`(0.975)、`'0 /85000'` vs `'0 / 85000'`、以及一块糊图。⇒ 宽规则不能"随便实现一个"。
- 逐框单独前向即可(batch=1);若做 batch,注意上游是**按 batch 内最大宽高比**决定 W,批大小会改变结果 —— 所以 Android 端**必须逐框**(或固定 W=320)。

### 7.2 字符表

```
keys = ppocr_keys_v1.txt 的 6623 行(空行丢弃)
table = keys + [" "]            // use_space_char=True: 空格追加在最后
// 模型输出 6625 类: 0 = CTC blank, 1..6623 = keys[0..6622], 6624 = " "
```

### 7.3 贪心 CTC

```
prev = -1; chars = []; confs = []
for t in 0..T-1:
    k = argmax(probs[t]);  p = probs[t][k]
    if (k != 0 && k != prev) { chars.add(table[k-1]); confs.add(p); }
    prev = k
text  = join(chars)
score = confs.isEmpty() ? 0.0 : mean(confs)      // ★ 见 §11-11 的 RapidOCR 差异
```

实测: `rel_a` 第 1 行 13 个字 → 平均置信度 0.9961(若照 RapidOCR 的 `mean(conf+[1e-50])` 会变成 0.9249,差 7.1%)。

---

## 8. 方向分类 cls(可选,只有需要 180° 校正时)

```
输入 [1,3,48,192]; imgH=48, imgW=192, cls_thresh=0.9, label_list = ['0','180']
resizedW = min((int) ceil(48 * w/h), 192)
x = resize(crop, (resizedW, 48)).transpose(2,0,1)/255f ; x = (x-0.5)/0.5
pad 右侧补 0 到 192
out = session(x)[0]                  // [1,2], 已 softmax
if (argmax(out) == 1 && out[1] > 0.9) crop = rot90(crop, 2)    // 180°
```

参考实现默认**关闭** cls(`--use-cls` 打开);两张参考图的文本都是正向,RapidOCR 默认也只在 score>0.9 时才翻转。

---

## 9. 全流程与输出 JSON

```
1. 读图            -> BGR(或 RGB,见 §11-9),不做任何自动旋转
2. det 预处理 §3   -> [1,3,rH,rW]
3. det 前向        -> sigmoid_0.tmp_0 [1,1,rH,rW]
4. DB 后处理 §4,5  -> N 个四点框(整数坐标)+ N 个 det score
5. 排序            -> 按 (第0点的 y, 第0点的 x) 稳定排序
6. 逐框裁剪 §6     -> crop(可能已 rot90)
7. (可选) cls §8
8. rec 前向 §7     -> [1,T,6625] -> 贪心 CTC -> (text, score)
9. 拼接            -> full = texts.join("\n")
```

参考实现写出的 JSON(`tools/ocr_ref/out/<name>.json`):
**前 5 个字段是设备测试要比对的最小集**,后面是诊断字段(设备端可忽略):

```json
{
  "image": "rel_a.png",
  "image_size": [900, 400],
  "boxes": [[x1,y1,x2,y2,x3,y3,x4,y4], ...],
  "scores": [0.996, ...],
  "texts": ["..."],
  "full": "行1\n行2",
  "det_scores": [...], "det_input_shape": [1,3,384,896], "det_out_shape": [1,1,384,896],
  "rec_widths": [702, 718, 661], "n_contours": 3, "timings_ms": {"det_ms": 66.3, ...}
}
```

---

## 10. 参数归属:本 SPEC vs RapidOCR 默认 vs PaddleOCR 默认

| 项 | 本 SPEC(参考实现) | RapidOCR 1.2.3 默认(源码实测) | PaddleOCR 推理默认 |
|---|---|---|---|
| det 长边限制 / 类型 | **960 / max** | 736 / **min** | 960 / max |
| det mean/std | ImageNet | ImageNet(相同) | ImageNet(相同) |
| det resize | `int(round(t/32))*32` | 相同 | 相同 |
| DB thresh | **0.3** | 0.3 | 0.3 |
| DB box_thresh | **0.6** | **0.5** | 0.6 |
| DB unclip_ratio | **1.5** | **1.6** | 1.5 |
| DB max_candidates | 1000 | 1000 | 1000 |
| bitmap 膨胀 `use_dilation` | **false** | **true**(2x2 kernel) | false |
| score_mode | fast | fast | fast |
| rec 输入 | 48, W=max(320, ceil(48r)) + 右补 0 | 48, rec_img_shape (3,48,320),单张时 W=int(48r) | 同左(本 SPEC 一致) |
| rec 归一化 | (x/255-0.5)/0.5 | 相同 | 相同 |
| 通道序 | **BGR**(PaddleOCR 训练/推理序) | **RGB**(PIL 解码) | BGR |
| 字符表 | keys+空格,blank=0 | 相同(读模型 metadata) | 相同 |
| 行置信度 | `mean(kept conf)` | `mean(kept conf + [1e-50])` | `mean(kept conf)` |
| 结果过滤 | **不过滤** | `text_score=0.5`:丢掉 rec score < 0.5 的框;`h<=30` 或 `w/h>8` 时**整图直接当一行**(跳过 det) | 无 |

> 结论:**"RapidOCR 默认值"≠"本 SPEC"**。差异集中在 det 的 `limit_type/limit_side_len`、`box_thresh`、`unclip_ratio`、`use_dilation` 以及单张 rec 宽度规则。设备端要复现 PC 参考结果,必须用**本 SPEC**的列,不要照抄 RapidOCR config.yaml。
> 端到端实测佐证(E10): 用 RapidOCR 1.2.3 + 同一份 v4 模型跑 `rel_b.png`,在它的默认参数下切成 **5 个框**(`机器学习` / `Machine Learning` / `2024` 被拆开),而本 SPEC 是 **3 个框**;`rel_a.png` 两者都是 3 行、文本相同,但框坐标差 1~3 px。

---

## 11. 必须一致清单(MUST-MATCH,附实测影响)

| # | 项目 | 不一致的后果(实测) |
|---|---|---|
| 1 | det 归一化 ImageNet mean/std | 用 `x/255` 直接把 2560x1600 截图的框从 20 个变成 14 个;两个参考图仍能出 3 框,但 det score 与框都会不同 |
| 2 | resize = 截断 + 五成双 | 五入 → rel_a 尺寸 384→416,角点最大差 1.0 px、IoU 0.951;1024x495 上 448→480 |
| 3 | 长边限制 960/'max' | 736/'min' 时 rel_a 变成 320x736(文本第 2 行实测变成 `识别测试第一行中文文学内容`),rel_b 仍 3 框但框不同 |
| 4 | thresh 0.3 / box_thresh 0.6 / unclip 1.5 / 不膨胀 | box_thresh 0.5 → 截图多 1 框;膨胀 → 轮廓数 26→25(框数相同);unclip 1.5→2.0 框会外扩 |
| 5 | min_size 判定 `3` 与 unclip 后 `5` | 小框会被多留/误删 |
| 6 | 框回原图 `round + clip(0, srcW/srcH)` | 不 round → 设备端整数框比对全错 |
| 7 | 排序 `(y0, x0)` 稳定 | 多行时"行序"与 `full` 文本顺序会变 |
| 8 | 裁剪 INTER_CUBIC + BORDER_REPLICATE + 边长 `int()` 截断 + h/w≥1.5 rot90 | 像素级不同 → rec 置信度漂移;rot90 漏掉则竖排文本整行识别失败 |
| 9 | 通道序(本 SPEC **BGR**) | 两个参考图上 rel_a 完全一致,rel_b 有 1 px 框差且第 2 行文本从 `OCR test:识别率 99.8%` 变 `OCR test: 识别率 99.8%`(多一个空格);截图 20 框中 7 框完全相同、其余差 1~3 px;同裁剪下 rec 平均置信度 BGR 0.9079 / RGB 0.9072(基本持平)。**Android Bitmap 天然是 RGB** —— 要么在喂模型前做一次 R/B 交换(与本 SPEC 完全对齐),要么全链路用 RGB 并在设备测试的期望值上也用 RGB(参考实现加 `--channel-order rgb` 可生成对照) |
| 10 | rec `W = max(320, ceil(48r))` + 右侧补 0 | 换成 RapidOCR 单张规则 → 20 块真实裁剪中 5 块文本不同(见 §7.1) |
| 11 | 行置信度公式 | 只影响 `scores` 字段,不影响文本。本 SPEC = `mean(kept conf)`;RapidOCR 的 `+[1e-50]` 会让 13 字行显示 0.9249 而非 0.9961(设备端若照抄 RapidOCR 公式,`scores` 会系统性偏低 ~n/(n+1)) |
| 12 | 字符表 keys+空格、blank=0、`table[i-1]` | 少一个映射就整体错位一个字符;空格必须在**末尾追加**(不是开头) |
| 13 | 张量名 `x` / 输出名 `sigmoid_0.tmp_0` / `softmax_11.tmp_0` | 名字写错直接跑不起来 |
| 14 | det 输出已 sigmoid、rec 输出已 softmax | 再套一层激活会得到完全错误的结果 |

**建议**: Android 端把 §3/§4/§6/§7 的常量写成一份 `PPOCRv4Spec.kt`,并在设备测试里对 `rel_a/rel_b` 逐框比对 `boxes`(允许 ±1 px,或严格相等取决于通道序选择)与 `texts`(严格相等)。

---

## 12. 参考结果(本机实测,设备端目标值)

### rel_a.png (900x400, 白底黑字, 3 行中文)

det 输入 `1x3x384x896`;轮廓 3;出框 3;det score 0.8454 / 0.9123 / 0.8944;rec 宽度 702 / 718 / 661。

```
今天天气很好我们去公园散步          score 0.9961  box [45,76, 659,75, 659,117, 45,118]
识别测试第一行中文文字内容          score 0.9938  box [46,175, 659,175, 659,216, 46,216]
第三行机器学习与深度学习            score 0.9984  box [44,275, 608,275, 608,316, 44,316]
```

### rel_b.png (1000x480, 中英混排 + 色块 + -4° 倾斜, 3 行)

det 输入 `1x3x448x960`;轮廓 3;出框 3;det score 0.7651 / 0.8038 / 0.8289;rec 宽度 727 / 548 / 471。

```
机器学习 Machine Learning 2024      score 0.9685  box [83,56, 763,103, 760,148, 80,101]
OCR test:识别率 99.8%               score 0.9685  box [83,191, 560,225, 558,266, 80,233]
ABC abc 123 Test                    score 0.9343  box [63,326, 416,352, 413,388, 60,362]
```

耗时(CPU,单线程默认): det 66~78 ms,DB 后处理 3 ms,裁剪 1 ms,rec 99~110 ms(3 行)。

**可复现性(实测)**: 重复运行上面的命令,`out/rel_a.json`、`out/rel_b.json` 的 `boxes/scores/texts/full/det_scores/det_input_shape/rec_widths` **逐字节相同**,唯一变化的是 `timings_ms`(真实墙钟毫秒)。E9 也验证了两次独立进程结果完全一致。

设备测试资源已就位: `app/src/androidTest/assets/rel_a.png`、`app/src/androidTest/assets/rel_b.png`
(与 `tools/ocr_ref/img/rel_a.png`、`img/rel_b.png` 逐字节相同);可视化 `tools/ocr_ref/out/rel_a.vis.png`、`out/rel_b.vis.png`。

**完整复现命令**

```bash
cd D:/ai/projects/study-assistant/tools/ocr_ref
PY=D:/ai/projects/study-assistant/tools/ocrenv/Scripts/python.exe
$PY make_test_images.py                                    # 重新生成两张测试图(确定性)
$PY pc_ocr_ref.py --image img/rel_a.png --image img/rel_b.png --out-dir out   # JSON + 可视化
$PY pc_ocr_ref.py --image img/rel_a.png --dump-shapes --verbose               # 打印模型 I/O 与张量尺寸
$PY pc_ocr_ref.py --image img/rel_a.png --channel-order rgb                   # 生成 RGB 变体作对照
$PY experiments.py                                         # 重跑 §13 全部证据
```

---

## 13. 证据索引(`tools/ocr_ref/out/experiments.txt`,由 `experiments.py` 生成)

| 段 | 内容 | 关键数字 |
|---|---|---|
| E0 | 模型 I/O 名/shape/metadata/sha256 + 两个参考图的实际张量尺寸 | §2 全部数字;rec 帧数 `T=ceil(W/8)` |
| E1 | 通道序 BGR vs RGB(整链 + 同裁剪 rec 对照) | rel_a 3/3 框完全一致;rel_b 1 框差 1px + 1 行文本差 1 空格;截图 7/20 框相同;rec 0.9079 vs 0.9072 |
| E2 | det 归一化 ImageNet vs `x/255` | 截图 20 框 vs 14 框 |
| E3 | 32 倍数取整规则(4 种)+ 1024x495 专项 | 五入 416 vs 五成双 384;截断 448 vs 不截断 480;ceil 928x416 |
| E4 | DB 参数组合 | 截图 20/21 框;轮廓 26/25 |
| E5 | rec 高 32/48/64 | 平均置信度 0.9079 / 0.8712 / 0.8813(截图 20 块) |
| E6 | rec 宽规则 PaddleOCR vs RapidOCR | 15/20 文本相同,5 个不同列表 |
| E7 | unclip miter vs pyclipper | ×1000 缩放后偏差 0.000 px、IoU 1.000000;整数坐标下 1.0 px |
| E8 | 竖长条 rot90 规则 | 40x320 → rot90;300x80 → 不转 |
| E9 | 确定性 | 两次独立运行 boxes/scores/texts 完全相同 |
| E10 | 与 rapidocr_onnxruntime 1.2.3 端到端对照 | rel_a 3 框(坐标差 1~3 px);rel_b 5 框 vs 本 SPEC 3 框 |
| E11 | 上游源码引用(逐段贴出) | NormalizeImage / resize_image_type0 / DBPostProcess / CTC / resize_norm_img / config.yaml |

验证用第三方包(仅 E7/E10 需要)装在 `tools/ocr_ref/_reftools/`,参考流水线**不依赖**它们:

```bash
pip install --target _reftools pyclipper shapely pyyaml six
pip install --target _reftools --no-deps rapidocr_onnxruntime
```

---

## 14. 未决 / 需要产品决策的点

1. **通道序**: 本 SPEC 取 BGR(PaddleOCR 训练/推理序,RapidOCR 取 RGB)。两者实测差异很小但不为零;Android 若不想做 R/B 交换,需要在设备测试期望值上同步用 RGB(`--channel-order rgb`)。
2. **rec 输出宽度**: 本 SPEC 用 `W=max(320,ceil(48r))`(PaddleOCR 规则)。若 Android 端最终固定 `W=320`(把所有裁剪都缩放到 ≤320 再补零),对更宽的裁剪会不同 —— 参考实现支持 `--rec-width-rule rapidocr` 做对照。
3. **是否加结果过滤**(rec score < 0.5 丢弃): 本 SPEC 不过滤,保留全部框。Android 若过滤,`texts` 数量会少于参考 JSON。
4. **cls**: 参考实现默认关闭;若相册照片可能有倒置文本,再按 §8 打开(会把 180° 的裁剪翻转)。

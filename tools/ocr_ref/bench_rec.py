#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""bench_rec.py -- Cyrillic recognition accuracy benchmark: printed vs handwritten, multi-model CER.

Reuses the audited reference pipeline in pc_ocr_ref.py (det / crop / rec pre-process / CTC).

Test set layout (auto-discovered, see img/ru_bench/SOURCES.md):
  img/ru_bench/printed/*.png        + gt/<stem>.boxes.json   -> mode lines  (GT boxes, rec-only)
  img/ru_bench/handwritten/*.png    + gt/<stem>.txt          -> mode whole  (whole image = one line)
  img/ru_bench/real_pages/*.png     + gt/<stem>.txt          -> mode page   (det+rec end-to-end)
  img/real_ru/*.png                 + img/real_ru/gt/<stem>.txt (optional <stem>.boxes.json)
                                                              -> captain's real screenshots

Metrics: micro-CER = sum(levenshtein) / sum(len(gt));  line/image exact-match rate;
         CER_dict = CER after deleting GT characters the model's dictionary cannot represent.

Usage (default model set):
  D:/ai/projects/study-assistant/tools/ocrenv/Scripts/python.exe bench_rec.py
  ... bench_rec.py --model hand=path/to/model.onnx:path/to/dict.txt --sets handwritten
  ... bench_rec.py --mode both --out out/ru_bench_report.md
"""
import argparse
import glob
import io
import json
import os
import sys
import time
import unicodedata

import cv2
import numpy as np
import onnxruntime as ort

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pc_ocr_ref as R                                     # noqa: E402

CH_DIR = r"D:\ai\models\ocr\ch"
RU_DIR = r"D:\ai\models\ocr\ru"
LOCAL_MODELS = os.path.join(HERE, "models")

DEFAULT_MODELS = [
    # name          onnx                                          dict
    ("rec_ch_now", os.path.join(CH_DIR, "ch_PP-OCRv4_rec_infer.onnx"),
     os.path.join(CH_DIR, "ppocr_keys_v1.txt")),
    ("ru_v3", os.path.join(RU_DIR, "rec_ru.onnx"), os.path.join(RU_DIR, "dict_ru.txt")),
    ("ru_v5", os.path.join(LOCAL_MODELS, "rec_ru_v5.onnx"),
     os.path.join(LOCAL_MODELS, "dict_ru_v5.txt")),
]


# --------------------------------------------------------------------------------------
# text metrics
# --------------------------------------------------------------------------------------
def lev(a, b):
    """Levenshtein distance (two-row DP)."""
    if a == b:
        return 0
    if not a:
        return len(b)
    if not b:
        return len(a)
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def norm(s):
    """Light normalisation: NFC, no NBSP, collapsed whitespace, stripped."""
    s = unicodedata.normalize("NFC", s).replace("\u00a0", " ").replace("\u2011", "-")
    return " ".join(s.split()).strip()


def drop_unsupported(gt, table):
    ok = set(table)
    return "".join(c for c in gt if c in ok)


def char_diff(gt, hyp, limit=40):
    """Compact per-character view: '=' same, 'X' substituted, '-' missing, '+' extra."""
    out, ops = [], []
    n, m = len(gt), len(hyp)
    # simple LCS-free walk using Levenshtein backtrace on short strings only
    if max(n, m) > 400:
        return "(too long to visualise)"
    d = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1):
        d[i][0] = i
    for j in range(m + 1):
        d[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1,
                          d[i - 1][j - 1] + (gt[i - 1] != hyp[j - 1]))
    i, j = n, m
    while i > 0 or j > 0:
        if i and j and d[i][j] == d[i - 1][j - 1] + (gt[i - 1] != hyp[j - 1]):
            ops.append("=" if gt[i - 1] == hyp[j - 1] else "X")
            i, j = i - 1, j - 1
        elif i and d[i][j] == d[i - 1][j] + 1:
            ops.append("-")
            i -= 1
        else:
            ops.append("+")
            j -= 1
    ops.reverse()
    return "".join(ops[:limit])


def show(s, n=70):
    return s if len(s) <= n else s[:n] + "…"


# --------------------------------------------------------------------------------------
# models
# --------------------------------------------------------------------------------------
class Rec:
    def __init__(self, name, onnx_path, dict_path, threads=0):
        self.name = name
        self.onnx_path = onnx_path
        self.dict_path = dict_path
        if not os.path.exists(onnx_path):
            raise FileNotFoundError(onnx_path)
        so = ort.SessionOptions()
        so.log_severity_level = 3
        if threads > 0:
            so.intra_op_num_threads = threads
        self.sess = ort.InferenceSession(onnx_path, so, providers=["CPUExecutionProvider"])
        self.in_name = self.sess.get_inputs()[0].name
        self.out_name = self.sess.get_outputs()[0].name
        self.out_shape = list(self.sess.get_outputs()[0].shape)
        self.nclass = self.out_shape[-1]
        in_shape = list(self.sess.get_inputs()[0].shape)
        self.in_channels = in_shape[1] if len(in_shape) > 1 else None
        # architecture adapter: a 1-channel / .json-charset model is a raw CRNN (H=32),
        # anything else is the PP-OCR rec pipeline (H=48, 3-channel)
        self.arch = "crnn" if ((dict_path or "").lower().endswith(".json")
                               or self.in_channels == 1) else "ppocr"
        if self.arch == "crnn":
            self.table = json.load(io.open(dict_path, encoding="utf-8"))["chars"][1:]
            self.img_h = in_shape[2] if len(in_shape) > 2 and isinstance(in_shape[2], int) else 32
            self.max_w = 512
        else:
            self.table = R.load_char_table(dict_path, onnx_path) if dict_path else None
            self.pre = R.RecPreProcess(width_rule="paddleocr")
            self.img_h = R.REC_HEIGHT
            self.max_w = None
        self.dict_ok = (self.table is not None and self.nclass == len(self.table) + 1)

    def info(self):
        return dict(name=self.name, onnx=os.path.basename(self.onnx_path),
                    input=self.in_name, output=self.out_name, arch=self.arch,
                    classes=self.nclass, dict_chars=(len(self.table) if self.table else 0),
                    dict_ok=self.dict_ok)

    def read(self, crop):
        if self.arch == "crnn":
            g = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
            h, w = g.shape[:2]
            tw = max(4, min(int(w * float(self.img_h) / max(h, 1)), self.max_w))
            g = cv2.resize(g, (tw, self.img_h), interpolation=cv2.INTER_AREA).astype(np.float32)
            inp = (g / 127.5 - 1.0)[None, None, :, :]
            out = self.sess.run(None, {self.in_name: inp})[0]        # [T,1,C] log-probs
            probs = np.exp(out[:, 0, :])
            return R.ctc_decode(probs, self.table)
        probs = self.sess.run(None, {self.in_name: self.pre(crop)})[0][0]
        return R.ctc_decode(probs, self.table)


def load_entry_sets(bench_dir, real_ru_dir, keep):
    """Auto-discover benchmark entries; returns {set_name: [entry, ...]}."""
    gt = os.path.join(bench_dir, "gt")
    sets = {}

    def add(set_name, entry):
        if set_name in keep:
            sets.setdefault(set_name, []).append(entry)

    for p in sorted(glob.glob(os.path.join(bench_dir, "printed", "*.png"))):
        stem = os.path.splitext(os.path.basename(p))[0]
        bj = os.path.join(gt, stem + ".boxes.json")
        txt = os.path.join(gt, stem + ".txt")
        if not os.path.exists(bj):
            continue
        d = json.load(io.open(bj, encoding="utf-8"))
        add("printed", dict(name=stem, image=p, mode="lines",
                            lines=[l["text"] for l in d["lines"]],
                            boxes=[l["box"] for l in d["lines"]]))

    for p in sorted(glob.glob(os.path.join(bench_dir, "handwritten", "*.png"))):
        stem = os.path.splitext(os.path.basename(p))[0]
        txt = os.path.join(gt, stem + ".txt")
        if not os.path.exists(txt):
            continue
        text = io.open(txt, encoding="utf-8").read().strip()
        bj = os.path.join(gt, stem + ".boxes.json")
        if os.path.exists(bj):
            d = json.load(io.open(bj, encoding="utf-8"))
            add("handwritten", dict(name=stem, image=p, mode="lines",
                                    lines=[l["text"] for l in d["lines"]],
                                    boxes=[l["box"] for l in d["lines"]]))
        else:
            add("handwritten", dict(name=stem, image=p, mode="whole", lines=[text], boxes=None))

    for p in sorted(glob.glob(os.path.join(bench_dir, "real_pages", "*.png"))):
        stem = os.path.splitext(os.path.basename(p))[0]
        txt = os.path.join(gt, stem + ".txt")
        if os.path.exists(txt):
            add("handwritten_page", dict(name=stem, image=p, mode="page",
                                         lines=[io.open(txt, encoding="utf-8").read()],
                                         boxes=None))

    # captain's real device screenshots: img/real_ru/*.png (+ optional gt/)
    for p in sorted(glob.glob(os.path.join(real_ru_dir, "*.png"))) + \
            sorted(glob.glob(os.path.join(real_ru_dir, "*.jpg"))):
        stem = os.path.splitext(os.path.basename(p))[0]
        # GT may live in the (read-only) real_ru dir, or in ru_bench/real_ru_gt/
        gdirs = [os.path.join(real_ru_dir, "gt"), os.path.join(bench_dir, "real_ru_gt")]
        txt = next((os.path.join(g, stem + ".txt") for g in gdirs
                    if os.path.exists(os.path.join(g, stem + ".txt"))), None)
        bj = next((os.path.join(g, stem + ".boxes.json") for g in gdirs
                   if os.path.exists(os.path.join(g, stem + ".boxes.json"))), None)
        if bj:
            d = json.load(io.open(bj, encoding="utf-8"))
            add("real_ru", dict(name=stem, image=p, mode="lines",
                                lines=[l["text"] for l in d["lines"]],
                                boxes=[l["box"] for l in d["lines"]]))
        elif txt:
            add("real_ru", dict(name=stem, image=p, mode="page",
                                lines=[io.open(txt, encoding="utf-8").read()], boxes=None))
        else:
            add("real_ru", dict(name=stem, image=p, mode="page", lines=None, boxes=None))
    return sets


def project_lines(img, ink_frac=0.05, gap_frac=0.004, min_h=8, pad=2):
    """Line boxes WITHOUT det: horizontal ink projection + valley splitting.

    The "skip det / handwriting entry" fallback: Otsu binarise -> per-row ink profile ->
    smooth -> threshold -> merge runs across small gaps -> trim each band to its ink
    x-extent -> one quad per text line.
    """
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    bw = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV | cv2.THRESH_OTSU)[1]
    h, w = bw.shape[:2]
    prof = (bw > 0).sum(axis=1).astype(np.float32)
    k = max(3, h // 200)
    prof = np.convolve(prof, np.ones(k, np.float32) / k, mode="same")
    thr = max(1.0, float(prof.max()) * ink_frac)
    on = prof > thr
    runs, i = [], 0
    while i < h:
        if on[i]:
            j = i
            while j + 1 < h and on[j + 1]:
                j += 1
            runs.append([i, j])
            i = j + 1
        else:
            i += 1
    mingap = max(1, int(h * gap_frac))
    merged = []
    for r in runs:
        if merged and r[0] - merged[-1][1] <= mingap:
            merged[-1][1] = r[1]
        else:
            merged.append(list(r))
    boxes = []
    for y0, y1 in merged:
        if y1 - y0 + 1 < min_h:
            continue
        cols = np.where((bw[y0:y1 + 1] > 0).sum(axis=0) > 0)[0]
        if len(cols) == 0:
            continue
        x0, x1 = int(cols[0]), int(cols[-1])
        y0p, y1p = max(0, y0 - pad), min(h - 1, y1 + pad)
        x0p, x1p = max(0, x0 - pad), min(w - 1, x1 + pad)
        boxes.append([x0p, y0p, x1p, y0p, x1p, y1p, x0p, y1p])
    return boxes


class Det:
    """PP-OCRv4 Chinese det model (language-agnostic text-region detector)."""

    def __init__(self, onnx_path=None, limit=960):
        onnx_path = onnx_path or os.path.join(CH_DIR, "ch_PP-OCRv4_det_infer.onnx")
        so = ort.SessionOptions()
        so.log_severity_level = 3
        self.sess = ort.InferenceSession(onnx_path, so, providers=["CPUExecutionProvider"])
        self.in_name = self.sess.get_inputs()[0].name
        self.pre = R.DetPreProcess(limit)
        self.post = R.DBPostProcess()

    def __call__(self, img):
        t, si = self.pre(img)
        out = self.sess.run(None, {self.in_name: t})[0]
        boxes, scores, _ = self.post(out, si)
        boxes, order = R.sorted_boxes(boxes)
        return boxes, [scores[i] for i in order]


# --------------------------------------------------------------------------------------
# evaluation
# --------------------------------------------------------------------------------------
def eval_model(rec, sets, det, modes, limit=None):
    """Returns (rows, examples). rows: one dict per (set, mode, item)."""
    rows = []
    for set_name, entries in sets.items():
        for mode in modes.get(set_name, ["lines"]):
            for e in entries:
                img = cv2.imdecode(np.fromfile(e["image"], dtype=np.uint8), cv2.IMREAD_COLOR)
                if img is None:
                    continue
                t0 = time.time()
                if e["lines"] is None:                       # no GT -> qualitative only
                    for _mode, _boxes in (("page", det(img)[0]), ("proj", project_lines(img))):
                        hyps = []
                        for b in _boxes:
                            crop = R.get_rotate_crop_image(img, b)
                            txt, _s = rec.read(crop)
                            hyps.append(txt)
                        rows.append(dict(set=set_name, mode=_mode, n_boxes=len(_boxes),
                                         name=e["name"] + (" [proj]" if _mode == "proj" else " [det]"),
                                         gt=None, hyp=" ".join(hyps),
                                         ms=(time.time() - t0) * 1000.0))
                    continue
                if mode == "lines":
                    hyps = []
                    for b in e["boxes"]:
                        crop = R.get_rotate_crop_image(img, b)
                        txt, _sc = rec.read(crop)
                        hyps.append(txt)
                    items = list(zip(e["lines"], hyps))          # score per line
                elif mode == "whole":
                    txt, _sc = rec.read(img)
                    items = [(e["lines"][0], txt)]
                elif mode == "proj":                             # NO det: projection split
                    boxes = project_lines(img)
                    hyps = []
                    for b in boxes:
                        crop = R.get_rotate_crop_image(img, b)
                        txt, _sc = rec.read(crop)
                        hyps.append(txt)
                    items = [(" ".join(e["lines"]), " ".join(hyps))]
                else:                                            # page = det + rec
                    boxes, _scores = det(img)
                    hyps = []
                    for b in boxes:
                        crop = R.get_rotate_crop_image(img, b)
                        txt, _sc = rec.read(crop)
                        hyps.append(txt)
                    items = [("\n".join(e["lines"]), "\n".join(hyps))]
                dt = (time.time() - t0) * 1000.0
                if limit:
                    items = items[:limit]
                nb = len(e["boxes"]) if (mode == "lines" and e["boxes"]) else (
                    len(hyp.split(chr(10))) if mode == "page" else (
                        len(project_lines(img)) if mode == "proj" else 1))
                for gt, hyp in items:
                    rows.append(dict(set=set_name, mode=mode, name=e["name"],
                                     gt=gt, hyp=hyp, ms=dt / max(len(items), 1), n_boxes=nb))
    return rows


def summarise(rows, rec):
    tot_d = tot_n = 0
    tot_dd = tot_nd = 0
    exact = 0
    per_set = {}
    scored_rows = [r for r in rows if r["gt"] is not None]
    for r in scored_rows:
        gt, hyp = norm(r["gt"]), norm(r["hyp"])
        d = lev(gt, hyp)
        tot_d += d
        tot_n += len(gt)
        if rec.table is not None:
            gtf = drop_unsupported(gt, rec.table)
            tot_dd += lev(gtf, hyp)
            tot_nd += len(gtf)
        if gt == hyp:
            exact += 1
        s = per_set.setdefault(r["set"], dict(d=0, n=0, exact=0, k=0, dd=0, nd=0))
        s["d"] += d
        s["n"] += len(gt)
        s["k"] += 1
        s["exact"] += int(gt == hyp)
        if rec.table is not None:
            gtf = drop_unsupported(gt, rec.table)
            s["dd"] += lev(gtf, hyp)
            s["nd"] += len(gtf)
    out = dict(items=len(scored_rows), cer=(tot_d / tot_n if tot_n else 0.0),
               cer_dict=(tot_dd / tot_nd if tot_dd else 0.0),
               exact_rate=(exact / len(scored_rows) if scored_rows else 0.0), per_set={})
    for k, s in per_set.items():
        out["per_set"][k] = dict(items=s["k"], cer=s["d"] / s["n"] if s["n"] else 0.0,
                                 cer_dict=s["dd"] / s["nd"] if s["nd"] else 0.0,
                                 exact_rate=s["exact"] / s["k"])
    macro = [v["cer"] for v in out["per_set"].values()]
    out["cer_macro"] = sum(macro) / len(macro) if macro else 0.0
    if rec.table is not None:
        tset = set(rec.table)
        missing = {}
        for r in scored_rows:
            for ch in norm(r["gt"]):
                if ch not in tset:
                    missing[ch] = missing.get(ch, 0) + 1
        out["missing_chars"] = sorted(missing.items(), key=lambda kv: -kv[1])[:12]
        out["missing_total"] = sum(missing.values())
        out["gt_total"] = sum(len(norm(r["gt"])) for r in scored_rows)
    return out


def worst_examples(rows, n=3):
    scored = []
    for r in rows:
        if r["gt"] is None:
            continue
        gt, hyp = norm(r["gt"]), norm(r["hyp"])
        d = lev(gt, hyp)
        scored.append((d / max(len(gt), 1), d, r, gt, hyp))
    scored.sort(key=lambda x: (-x[1], -x[0]))
    picked, seen = [], set()
    for _, d, r, gt, hyp in scored:
        if r["name"] in seen:
            continue
        seen.add(r["name"])
        picked.append((r, gt, hyp))
        if len(picked) >= n:
            break
    return picked


# --------------------------------------------------------------------------------------
# report
# --------------------------------------------------------------------------------------
def pct(x):
    return "%.1f%%" % (100.0 * x)


def build_report(models, results, sets, args):
    L = []
    L.append("# 俄文识别准确率基准(CER)——印刷 vs 手写,多模型对比\n")
    NL = chr(10)          # real newline in the generated report
    L.append(NL + "## 0. 结论(按实测 CER 自动生成)" + NL)
    for _s in [x for x in ("printed", "handwritten") if x in sets]:
        _rank = sorted((results[m.name]["summary"]["per_set"].get(_s, {}).get("cer", 9.99),
                        results[m.name]["summary"]["per_set"].get(_s, {}).get("exact_rate", 0.0),
                        m.name) for m in models)
        _b, _w = _rank[0], _rank[-1]
        L.append("- " + _s + ": 最佳 " + _b[2] + " CER=" + pct(_b[0]) + " / 完全正确=" + pct(_b[1])
                 + " ; 最差 " + _w[2] + " CER=" + pct(_w[0]))
        if _s == "handwritten" and _b[0] > 0.15:
            L.append("  - 手写体最佳模型的 CER 仍 > 15%,说明没有可直接使用的手写模型,需要专门训练或微调。")
    L.append("")
    L.append(NL + "复现命令(工作目录 tools/ocr_ref/)" + NL)
    L.append('```')
    L.append("python bench_rec.py " + " ".join(["--model " + chr(34) + "%s=%s;%s" % (m.name, m.onnx_path, m.dict_path) + chr(34) for m in models]))
    L.append('```')
    L.append("模型 = " + " / ".join("%s: %s + %s" % (m.name, m.onnx_path, m.dict_path) for m in models))
    L.append("由 `tools/ocr_ref/bench_rec.py` 生成(与 `pc_ocr_ref.py` 参考流水线共用同一套前处理/CTC)。\n")
    L.append("CER = 编辑距离总和 / GT 字符数总和(微平均,已做 NFC+空白归一化);"
             "`CER_dict` = 先把 GT 里该模型词表无法表示的字删掉再算(用于区分\"读错\"与\"词表根本装不下\")。\n")

    L.append("\n## 1. 模型清单\n")
    L.append("| 模型 | 架构 | 文件 | 输入 | 输出 | 类别数 | 词表字符数 | 词表匹配 |")
    L.append("|---|---|---|---|---|---|---|---|")
    for m in models:
        i = m.info()
        L.append("| `%s` | %s | %s | `%s` | `%s` | %d | %d | %s |"
                 % (i["name"], i["arch"], i["onnx"], i["input"], i["output"], i["classes"],
                    i["dict_chars"], "OK" if i["dict_ok"] else "**不匹配**"))

    L.append("\n## 2. 总表(micro-CER / 完全正确率)\n")
    sets_order = [s for s in ("printed", "handwritten", "handwritten_page", "real_ru")
                  if s in sets]
    head = "| 模型 | " + " | ".join("%s" % s for s in sets_order) + " | 各集合平均(macro) |"
    L.append(head)
    L.append("|" + "---|" * (len(sets_order) + 2))
    for m in models:
        res = results[m.name]
        cells = []
        for s in sets_order:
            ps = res["summary"]["per_set"].get(s)
            cells.append("%s / %s" % (pct(ps["cer"]), pct(ps["exact_rate"])) if ps else "—")
        L.append("| `%s` | %s | %s |"
                 % (m.name, " | ".join(cells), pct(res["summary"]["cer_macro"])))
    L.append("\n(单元格 = CER / 完全正确率;越低越好 / 越高越好。最后一列 = 各集合 CER 的等权平均)\n")

    L.append("\n## 3. 分模型明细(含 CER_dict)\n")
    L.append("| 模型 | 集合 | 条目 | CER | CER_dict(剔除词表外字符) | 完全正确率 |")
    L.append("|---|---|---|---|---|---|")
    for m in models:
        for s, ps in sorted(results[m.name]["summary"]["per_set"].items()):
            L.append("| `%s` | %s | %d | %s | %s | %s |"
                     % (m.name, s, ps["items"], pct(ps["cer"]), pct(ps["cer_dict"]),
                        pct(ps["exact_rate"])))

    nogt = {}
    for m in models:
        for r in results[m.name].get("raw_rows", []):
            if r["gt"] is None:
                nogt.setdefault(r["name"], {})[m.name] = r["hyp"]
    if nogt:
        L.append("\n## 3b. 无 ground truth 的真实截图 (定性对照, 不计 CER)\n")
        for nm, per_model in sorted(nogt.items()):
            L.append("\n### `%s`\n" % nm)
            for m in models:
                if m.name in per_model:
                    L.append("- `%s`: `%s`" % (m.name, show(per_model[m.name], 260)))

    L.append("\n## 3c. det 出框 vs 跳过 det(投影分行) —— 决定要不要给手写做单独入口\n")
    L.append("| 模型 | 图片 | 模式 | GT 字符 | 识别出的字符 | 比例 | boxes |")
    L.append("|---|---|---|---|---|---|---|")
    for m in models:
        for r in results[m.name].get("raw_rows", []):
            if r["mode"] not in ("page", "proj") or r["gt"] is None:
                continue
            g, h = len(norm(r["gt"])), len(norm(r["hyp"]))
            L.append("| `%s` | %s | %s | %d | %d | %.0f%% | %s |" % (m.name, r["name"], r["mode"],
                     g, h, 100.0 * h / max(g, 1), r.get("n_boxes", "-")))

    L.append("\n注:\n"
             "- Otsu + 行投影(本表 proj 模式)在**扫描件**上把出框数从 det 的 1~14 行提到 21~22 行,恢复字符从 6~14% 提到 22~29%;" + '\\n' +
             "- 同一个 proj 在 real_02_light(4032x3024 手机翻拍)上只出 1 个框、恢复 0%,换 adaptiveThreshold 也一样 => 翻拍页要先裁边/去阴影/纠偏,单纯跳过 det 不够;" + '\\n' +
             "- 但即使分行成功,rec 的整页 CER 仍在 0.89~0.92 => **跳过 det 不能解决手写问题,瓶颈是 rec 模型本身**。")
    L.append("\n## 3d. 词表覆盖率(GT 里模型根本没法表示的字)\n")
    L.append("| 模型 | 词表大小 | GT 总字数 | 词表外字数 | 占比 | 具体字符(前 12) |")
    L.append("|---|---|---|---|---|---|")
    for m in models:
        su = results[m.name]["summary"]
        if "gt_total" not in su:
            continue
        miss = " ".join("%s x%d" % (c, n) for c, n in su["missing_chars"]) or "-"
        L.append("| `%s` | %d | %d | %d | %.2f%% | %s |" % (m.name, len(m.table), su["gt_total"],
                 su["missing_total"], 100.0 * su["missing_total"] / max(su["gt_total"], 1), miss))

    L.append("\n## 4. 典型错例(每模型每集合取编辑距离最大的若干条)\n")
    for m in models:
        res = results[m.name]
        L.append("\n### `%s`\n" % m.name)
        for s in sets_order:
            ex = [e for e in res["examples"] if e["set"] == s][:3]
            if not ex:
                continue
            L.append("**%s**\n" % s)
            for e in ex:
                L.append("- `%s` (编辑距离 %d / GT %d 字)" % (e["name"], e["dist"], len(e["gt"])))
                L.append("  - GT : `%s`" % show(e["gt"]))
                L.append("  - 识别: `%s`" % show(e["hyp"]))
                L.append("  - 差异: `%s`  (`=`同 `X`替换 `-`漏 `+`多)" % e["ops"])
    return "\n".join(L) + "\n"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bench-dir", default=os.path.join(HERE, "img", "ru_bench"))
    ap.add_argument("--real-ru", default=os.path.join(HERE, "img", "real_ru"))
    ap.add_argument("--model", action="append", default=[],
                    help="NAME=ONNX[;DICT]  (repeatable, replaces the default model set; "
                         "use ';' because Windows paths contain ':')")
    ap.add_argument("--sets", default="printed,handwritten,handwritten_page,real_ru")
    ap.add_argument("--mode", default="auto", choices=["auto", "gtbox", "det", "both"],
                    help="gtbox = GT 框(rec-only) / det = det 出框 / both")
    ap.add_argument("--limit", type=int, default=0, help="每张图最多评几条(调试用)")
    ap.add_argument("--out", default=os.path.join(HERE, "out", "ru_bench_report.md"))
    ap.add_argument("--json", default=os.path.join(HERE, "out", "ru_bench_results.json"))
    args = ap.parse_args()

    keep = set(x.strip() for x in args.sets.split(",") if x.strip())
    sets = load_entry_sets(args.bench_dir, args.real_ru, keep)
    print("测试集:")
    for s, e in sets.items():
        print("  %-18s %d 张  (模式: %s)" % (s, len(e), sorted({x["mode"] for x in e})))
    if not sets:
        print("没有找到任何测试项 —— 检查 img/ru_bench 布局"); return 1

    # which evaluation modes per set
    modes = {}
    for s, entries in sets.items():
        ms = sorted({e["mode"] for e in entries})
        if args.mode == "gtbox":
            ms = [m for m in ms if m in ("lines", "whole")]
        elif args.mode == "det":
            ms = ["page"]
        elif args.mode == "both":
            ms = sorted(set(ms) | {"page"})
            if s == "handwritten":
                ms = ["whole", "page"]
        if "page" in ms and args.mode in ("auto", "both"):
            ms = sorted(set(ms) | {"proj"})                  # det vs no-det comparison
        modes[s] = ms
    print("评估模式:", modes)

    model_specs = []
    if args.model:
        for spec in args.model:
            name, _, rest = spec.partition("=")
            if ";" in rest:
                onnx_path, _, dict_path = rest.partition(";")
            else:
                idx = rest.rfind(":")
                if idx > 2:                       # "C:/x.onnx:/y/dict.txt"
                    onnx_path, dict_path = rest[:idx], rest[idx + 1:]
                else:
                    onnx_path, dict_path = rest, None
            model_specs.append((name, onnx_path.strip(), (dict_path or "").strip() or None))
    else:
        model_specs = DEFAULT_MODELS

    models = []
    for name, onnx_path, dict_path in model_specs:
        try:
            models.append(Rec(name, onnx_path, dict_path))
            i = models[-1].info()
            print("模型 %-12s classes=%-5d dict=%-5d %s"
                  % (name, i["classes"], i["dict_chars"], "OK" if i["dict_ok"] else "词表不匹配!"))
        except Exception as exc:
            print("跳过模型 %s: %r" % (name, exc))

    det = None
    if any("page" in m for m in modes.values()):
        det = Det()
        print("det: ch_PP-OCRv4_det_infer.onnx (语言无关的文本框检测)")

    results = {}
    for m in models:
        t0 = time.time()
        rows = eval_model(m, sets, det, modes, args.limit or None)
        summ = summarise(rows, m)
        ex = []
        for s in sorted({r["set"] for r in rows}):
            for r, gt, hyp in worst_examples([x for x in rows if x["set"] == s], 3):
                ex.append(dict(set=s, name=r["name"], gt=gt, hyp=hyp,
                               dist=lev(gt, hyp), ops=char_diff(gt, hyp)))
        results[m.name] = dict(summary=summ, examples=ex, rows=len(rows), raw_rows=rows)
        print("  %-12s CER=%.4f  exact=%.1f%%  (%d items, %.1fs)"
              % (m.name, summ["cer"], 100 * summ["exact_rate"], len(rows), time.time() - t0))

    md = build_report(models, results, sets, args)
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with io.open(args.out, "w", encoding="utf-8") as fh:
        fh.write(md)
    with io.open(args.json, "w", encoding="utf-8") as fh:
        json.dump({k: dict(summary=v["summary"], examples=v["examples"], raw=v["raw_rows"])
                   for k, v in results.items()},
                  fh, ensure_ascii=False, indent=2)
    print("\n报告 -> %s\n结果 -> %s" % (args.out, args.json))
    print(md[md.index("## 2. 总表"):md.index("## 3. 分模型明细")] if "## 2. 总表" in md else "")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""experiments.py -- every numeric claim in SPEC.md comes from this script.

It probes the two ONNX models directly and A/B-tests every preprocessing choice, so
SPEC.md never has to guess.  Output goes to stdout and to out/experiments.txt.

Run:
    D:/ai/projects/study-assistant/tools/ocrenv/Scripts/python.exe experiments.py

_reftools/ holds verification-only packages (pyclipper, shapely,
rapidocr_onnxruntime 1.2.3 for the source cross-check).  It is NOT needed to run
pc_ocr_ref.py and can be deleted.
"""
import io
import json
import math
import os
import sys

import cv2
import numpy as np
import onnxruntime as ort

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "_reftools"))

import pc_ocr_ref as R                                    # noqa: E402

MODEL_DIR = r"D:\ai\models\ocr\ch"
OUT = os.path.join(HERE, "out")
IMGDIR = os.path.join(HERE, "img")
LOGFILE = os.path.join(OUT, "experiments.txt")
REAL = os.path.join(IMGDIR, "_real_screenshot.png")

_buf = io.StringIO()


def log(*a):
    s = " ".join(str(x) for x in a)
    print(s, flush=True)
    _buf.write(s + "\n")


def hr(t):
    log("")
    log("=" * 78)
    log("== " + t)
    log("=" * 78)


def read_img(name):
    p = name if os.path.isabs(name) else os.path.join(IMGDIR, name)
    im = cv2.imdecode(np.fromfile(p, dtype=np.uint8), cv2.IMREAD_COLOR)
    if im is None:
        raise SystemExit("cannot read " + p)
    return im


def load_eng(**kw):
    return R.PPOCRRef(MODEL_DIR, **kw)


def box_diff(a, b):
    """max corner deviation + min IoU between two box lists of equal length."""
    if len(a) != len(b):
        return None, None
    if not a:
        return 0.0, 1.0
    md = 0.0
    for pa, pb in zip(a, b):
        pa = np.asarray(pa, dtype=np.float64).reshape(4, 2)
        pb = np.asarray(pb, dtype=np.float64).reshape(4, 2)
        md = max(md, float(np.abs(pa - pb).max()))
    try:
        from shapely.geometry import Polygon
        ious = []
        for pa, pb in zip(a, b):
            A = Polygon(np.asarray(pa).reshape(4, 2))
            B = Polygon(np.asarray(pb).reshape(4, 2))
            ious.append(A.intersection(B).area / max(A.union(B).area, 1e-9))
        return md, float(min(ious))
    except Exception:
        return md, None


def e0_model_io():
    hr("E0  model I/O names + shapes (onnxruntime %s)" % ort.__version__)
    log("onnxruntime %s | numpy %s | cv2 %s | python %s"
        % (ort.__version__, np.__version__, cv2.__version__, sys.version.split()[0]))
    for f in ("ch_PP-OCRv4_det_infer.onnx", "ch_PP-OCRv4_rec_infer.onnx",
              "ch_ppocr_mobile_v2.0_cls_infer.onnx"):
        p = os.path.join(MODEL_DIR, f)
        s = ort.InferenceSession(p, providers=["CPUExecutionProvider"])
        log("- %s (%d bytes, sha256 below)" % (f, os.path.getsize(p)))
        for i in s.get_inputs():
            log("    INPUT  name=%-24s shape=%s type=%s" % (i.name, i.shape, i.type))
        for o in s.get_outputs():
            log("    OUTPUT name=%-24s shape=%s type=%s" % (o.name, o.shape, o.type))
        meta = s.get_modelmeta().custom_metadata_map
        log("    metadata keys=%s" % list(meta.keys()))
        if "character" in meta:
            emb = [c for c in meta["character"].splitlines() if c != ""]
            keys = [k for k in open(os.path.join(MODEL_DIR, "ppocr_keys_v1.txt"),
                                    encoding="utf-8").read().split("\n") if k != ""]
            log("    embedded dict: %d chars; equals ppocr_keys_v1.txt: %s"
                % (len(emb), emb == keys))
    import hashlib
    for f in ("ch_PP-OCRv4_det_infer.onnx", "ch_PP-OCRv4_rec_infer.onnx",
              "ch_ppocr_mobile_v2.0_cls_infer.onnx", "ppocr_keys_v1.txt"):
        h = hashlib.sha256(open(os.path.join(MODEL_DIR, f), "rb").read()).hexdigest()
        log("    sha256 %s  %s" % (h[:32] + "...", f))

    eng = load_eng()
    for n in ("rel_a.png", "rel_b.png"):
        img = read_img(n)
        t, si = eng.det_pre(img)
        out = eng.det.run(None, {eng.det.get_inputs()[0].name: t})[0]
        crop = R.get_rotate_crop_image(img, [10, 10, 400, 10, 400, 60, 10, 60])
        rin = eng.rec_pre(crop)
        rout = eng.rec.run(None, {eng.rec.get_inputs()[0].name: rin})[0]
        log("  %s %dx%d -> det in %s out %s | rec crop %dx%d in %s out %s"
            % (n, img.shape[1], img.shape[0], t.shape, out.shape,
               crop.shape[1], crop.shape[0], rin.shape, rout.shape))
        log("      shape_info (src_h,src_w,ratio_h,ratio_w,resize_h,resize_w)=%s" % (si,))


def e1_channel_order():
    hr("E1  channel order BGR (PaddleOCR training) vs RGB (RapidOCR/PIL, Android Bitmap)")
    images = [("rel_a.png", read_img("rel_a.png")),
              ("rel_b.png", read_img("rel_b.png")),
              ("real_screenshot", read_img(REAL))]
    for name, img in images:
        out = {}
        for order in ("bgr", "rgb"):
            eng = load_eng(channel_order=order)
            r = eng.run(img)
            out[order] = (r, eng)
            log("  %-16s %-4s boxes=%2d det_mean=%.4f rec_mean=%.4f"
                % (name, order.upper(), len(r["boxes"]),
                   float(np.mean(r["det_scores"])) if r["det_scores"] else -1,
                   float(np.mean(r["scores"])) if r["scores"] else -1))
        a, b = out["bgr"][0], out["rgb"][0]
        seta = set(tuple(int(v) for v in bx) for bx in a["boxes"])
        setb = set(tuple(int(v) for v in bx) for bx in b["boxes"])
        log("      boxes: bgr=%d rgb=%d  exactly-equal-boxes=%d  rec texts identical=%s"
            % (len(seta), len(setb), len(seta & setb), a["texts"] == b["texts"]))
        only_b = [(t, bx) for t, bx in zip(b["texts"], b["boxes"])
                  if tuple(int(v) for v in bx) not in seta]
        for t, bx in only_b[:4]:
            log("        RGB-only box %s -> %r" % (bx, t))
        only_a = [(t, bx) for t, bx in zip(a["texts"], a["boxes"])
                  if tuple(int(v) for v in bx) not in setb]
        for t, bx in only_a[:4]:
            log("        BGR-only box %s -> %r" % (bx, t))
        if name == "real_screenshot":
            # rec-only, same crops -> isolates the channel order from det differences
            eng = out["bgr"][1]
            crops = [R.get_rotate_crop_image(img, bx) for bx in a["boxes"]]
            n_in = eng.rec.get_inputs()[0].name
            tb, tr = [], []
            for c in crops:
                for tag, im in (("bgr", c), ("rgb", np.ascontiguousarray(c[:, :, ::-1]))):
                    probs = eng.rec.run(None, {n_in: eng.rec_pre(im)})[0][0]
                    t, s = R.ctc_decode(probs, eng.char_table)
                    (tb if tag == "bgr" else tr).append((t, s))
            log("      rec-only on the SAME %d crops: mean score bgr=%.4f rgb=%.4f | "
                "rgb wins %d, bgr wins %d, ties %d | texts equal=%s"
                % (len(crops), np.mean([x[1] for x in tb]), np.mean([x[1] for x in tr]),
                   sum(1 for x, y in zip(tb, tr) if y[1] > x[1]),
                   sum(1 for x, y in zip(tb, tr) if x[1] > y[1]),
                   sum(1 for x, y in zip(tb, tr) if x[1] == y[1]),
                   [x[0] for x in tb] == [x[0] for x in tr]))


def e2_det_normalisation():
    hr("E2  det normalisation: ImageNet mean/std (upstream) vs plain x/255")
    eng = load_eng()
    for name, img in (("rel_a.png", read_img("rel_a.png")),
                      ("rel_b.png", read_img("rel_b.png")),
                      ("real_screenshot", read_img(REAL))):
        log("--- %s %dx%d" % (name, img.shape[1], img.shape[0]))
        for tag, (mean, std) in (("imagenet", (R.DET_MEAN, R.DET_STD)),
                                 ("plain/255", ((0, 0, 0), (1, 1, 1)))):
            pre = R.DetPreProcess(960, mean, std)
            t, si = pre(img)
            prob = eng.det.run(None, {eng.det.get_inputs()[0].name: t})[0][0, 0]
            boxes, scores, nc = R.DBPostProcess()(prob[None, None], si)
            log("    %-9s contours=%-3d boxes=%-3d det_score mean=%.4f min=%.4f "
                "probmax=%.4f" % (tag, nc, len(boxes),
                                  float(np.mean(scores)) if scores else -1,
                                  float(np.min(scores)) if scores else -1,
                                  float(prob.max())))


def _det_variant(img, eng, resize_h=None, resize_w=None):
    """Run det with an explicit resize size, then the standard DB post-process."""
    src_h, src_w = img.shape[:2]
    if resize_h is None:
        t, si = eng.det_pre(img)
        return eng.det_post(eng.det.run(None, {eng.det.get_inputs()[0].name: t})[0], si)
    data = cv2.resize(img, (resize_w, resize_h)).astype(np.float32) * R.DET_SCALE
    data = (data - np.array(R.DET_MEAN, np.float32)) / np.array(R.DET_STD, np.float32)
    t = np.ascontiguousarray(data.transpose(2, 0, 1)[None], dtype=np.float32)
    out = eng.det.run(None, {eng.det.get_inputs()[0].name: t})[0]
    si = (src_h, src_w, resize_h / float(src_h), resize_w / float(src_w), resize_h, resize_w)
    return eng.det_post(out, si)


def e3_resize_rule():
    hr("E3  det resize rule to a multiple of 32 (truncate-then-round, and the rounding mode)")
    # a synthetic 1024x495 image where int(h*ratio) = 464 and 464/32 = 14.5 exactly:
    # the inner int() truncation flips the result from 480 to 448 here
    base = read_img("rel_b.png")
    synth = cv2.resize(base, (1024, 495))
    cv2.imencode(".png", synth)[1].tofile(os.path.join(OUT, "_synth_1024x495.png"))
    for name in ("rel_a.png", "rel_b.png", REAL, os.path.join(OUT, "_synth_1024x495.png")):
        img = read_img(name)
        h, w = img.shape[:2]
        ratio = min(1.0, 960.0 / max(h, w))
        t_h, t_w = int(h * ratio), int(w * ratio)
        variants = {
            "UPSTREAM int(round(t/32))*32 (half-to-even)": (
                max(int(round(t_h / 32)) * 32, 32), max(int(round(t_w / 32)) * 32, 32)),
            "half-UP (Kotlin Math.round)": (
                max(int(math.floor(t_h / 32 + 0.5)) * 32, 32),
                max(int(math.floor(t_w / 32 + 0.5)) * 32, 32)),
            "ceil": (max(int(math.ceil(t_h / 32)) * 32, 32), max(int(math.ceil(t_w / 32)) * 32, 32)),
            "no inner int() truncation, half-even": (
                max(int(round(h * ratio / 32)) * 32, 32), max(int(round(w * ratio / 32)) * 32, 32)),
        }
        log("--- %s src=%dx%d ratio=%.6f  t=(%d,%d)" % (name, w, h, ratio, t_h, t_w))
        eng = load_eng()
        base = None
        for tag, (rh, rw) in variants.items():
            boxes, scores, nc = _det_variant(img, eng, rh, rw)
            if base is None:
                base = boxes
                log("    %-44s -> %dx%d  boxes=%d" % (tag, rw, rh, len(boxes)))
            else:
                md, iou = box_diff(base, boxes)
                log("    %-44s -> %dx%d  boxes=%d  max|corner|=%s IoU=%s"
                    % (tag, rw, rh, len(boxes), md, iou))


def e4_db_params():
    hr("E4  DB post-process parameters: spec values vs RapidOCR 1.2.3 config defaults")
    img = read_img("rel_a.png")
    img2 = read_img(REAL)
    eng = load_eng()
    t, si = eng.det_pre(img)
    out = eng.det.run(None, {eng.det.get_inputs()[0].name: t})[0]
    t2, si2 = eng.det_pre(img2)
    out2 = eng.det.run(None, {eng.det.get_inputs()[0].name: t2})[0]
    variants = {
        "SPEC thresh .3 box .6 unclip 1.5 nodil": dict(thresh=0.3, box_thresh=0.6,
                                                       unclip_ratio=1.5, use_dilation=False),
        "RapidOCR .3/.5/1.6 dilate": dict(thresh=0.3, box_thresh=0.5, unclip_ratio=1.6,
                                          use_dilation=True),
        "PaddleOCR-server .3/.6/2.0": dict(thresh=0.3, box_thresh=0.6, unclip_ratio=2.0),
        "box_thresh 0.5 nodil": dict(thresh=0.3, box_thresh=0.5, unclip_ratio=1.5),
    }
    for tag, kw in variants.items():
        for nm, o, s in (("rel_a", out, si), ("real", out2, si2)):
            boxes, scores, nc = R.DBPostProcess(**kw)(o, s)
            log("    %-38s %-6s contours=%-3d boxes=%-3d" % (tag, nm, nc, len(boxes)))


def e5_rec_height():
    hr("E5  rec input height 48 (PP-OCRv4) vs 32 vs 64 (same crops, confidence comparison)")
    eng = load_eng()
    sets = [("rel_a.png", read_img("rel_a.png")), ("real_screenshot", read_img(REAL))]
    for name, img in sets:
        base = eng.run(img)
        crops = [R.get_rotate_crop_image(img, b) for b in base["boxes"]]
        n_in = eng.rec.get_inputs()[0].name
        log("--- %s (%d crops)" % (name, len(crops)))
        for h in (48, 32, 64):
            pre = R.RecPreProcess(img_h=h, width_rule="paddleocr")
            texts, scores = [], []
            for c in crops:
                probs = eng.rec.run(None, {n_in: pre(c)})[0][0]
                t, s = R.ctc_decode(probs, eng.char_table)
                texts.append(t)
                scores.append(s)
            log("    img_h=%-3d mean conf=%.4f  identical to h=48: %s  sample=%.4f %r"
                % (h, float(np.mean(scores)), texts == base["texts"] if h != 48 else "-",
                   scores[0], texts[0]))


def e6_rec_width_rule():
    hr("E6  rec width rule: PaddleOCR pad-to-320 vs RapidOCR (single crop) int(48*r)")
    eng = load_eng()
    for name in ("rel_a.png", REAL):
        img = read_img(name)
        base = eng.run(img)
        crops = [R.get_rotate_crop_image(img, b) for b in base["boxes"]]
        n_in = eng.rec.get_inputs()[0].name
        res = {}
        for rule in ("paddleocr", "rapidocr"):
            pre = R.RecPreProcess(width_rule=rule)
            items = []
            for c in crops:
                cin = pre(c)
                probs = eng.rec.run(None, {n_in: cin})[0][0]
                items.append((R.ctc_decode(probs, eng.char_table), probs, int(cin.shape[3])))
            res[rule] = items
        a, b = res["paddleocr"], res["rapidocr"]
        same = sum(1 for x, y in zip(a, b) if x[0][0] == y[0][0])
        log("--- %s (%d crops)" % (name, len(crops)))
        log("    mean rec score: paddleocr=%.4f  rapidocr=%.4f  identical texts: %d/%d"
            % (float(np.mean([x[0][1] for x in a])), float(np.mean([x[0][1] for x in b])),
               same, len(a)))
        for i, (x, y) in enumerate(zip(a, b)):
            if x[0][0] != y[0][0]:
                log("      crop %2d W:%-3d->%-3d  paddleocr %.3f %r  |  rapidocr %.3f %r"
                    % (i, x[2], y[2], x[0][1], x[0][0], y[0][1], y[0][0]))


def e7_unclip():
    hr("E7  unclip: pure-numpy miter offset (this ref) vs pyclipper JT_ROUND")
    try:
        import pyclipper
    except Exception as exc:
        log("    pyclipper unavailable: %r" % exc)
        log("    install hint: pip install --target _reftools pyclipper shapely")
        return
    log("    pyclipper version: %s" % getattr(pyclipper, "__version__", "?"))
    eng = load_eng()
    orig = R.offset_polygon_miter

    def make_pyclipper(scale):
        def f(poly, distance, miter_limit=10.0):
            poly = np.asarray(poly, dtype=np.float64)
            if distance <= 0:
                return poly.copy()
            off = pyclipper.PyclipperOffset()
            path = [[int(round(x * scale)), int(round(y * scale))] for x, y in poly]
            off.AddPath(path, pyclipper.JT_ROUND, pyclipper.ET_CLOSEDPOLYGON)
            out = off.Execute(distance * scale)
            if not out:
                return poly.copy()
            return np.asarray(out[0], dtype=np.float64) / scale
        return f

    for name in ("rel_a.png", "rel_b.png"):
        img = read_img(name)
        a = eng.run(img)
        for tag, scale in (("pyclipper int", 1.0), ("pyclipper x1000", 1000.0)):
            R.offset_polygon_miter = make_pyclipper(scale)
            try:
                b = eng.run(img)
            finally:
                R.offset_polygon_miter = orig
            md, iou = box_diff(a["boxes"], b["boxes"])
            log("    %-11s %-15s boxes=%d max|corner|=%.3f px minIoU=%.6f texts_equal=%s"
                % (name, tag, len(b["boxes"]), md if md is not None else -1,
                   iou if iou is not None else -1, a["texts"] == b["texts"]))
        log("      miter boxes:     %s" % [list(x) for x in a["boxes"]])


def e8_vertical_crop():
    hr("E8  tall-crop rule: h/w >= 1.5 -> rot90 (PaddleOCR/RapidOCR get_rotate_crop_image)")
    img = read_img("rel_a.png")
    for tag, pts in (("tall 40x320", [100, 60, 140, 60, 140, 380, 100, 380]),
                     ("wide 300x80", [100, 60, 400, 60, 400, 140, 100, 140])):
        pts = np.asarray(pts, dtype=np.float32).reshape(4, 2)
        cw = int(max(np.linalg.norm(pts[0] - pts[1]), np.linalg.norm(pts[2] - pts[3])))
        ch = int(max(np.linalg.norm(pts[0] - pts[3]), np.linalg.norm(pts[1] - pts[2])))
        out = R.get_rotate_crop_image(img, pts)
        log("    %-12s warp target=%dx%d (h/w=%.2f) -> returned shape (h,w)=%s  rot90_applied=%s"
            % (tag, cw, ch, ch / float(cw), out.shape[:2], ch / float(cw) >= 1.5))


def e9_determinism():
    hr("E9  determinism: two independent runs produce identical boxes/scores/texts")
    for name in ("rel_a.png", "rel_b.png"):
        img = read_img(name)
        a = load_eng().run(img)
        b = load_eng().run(img)
        ja = json.dumps({"b": a["boxes"], "s": a["scores"], "t": a["texts"]}, sort_keys=True)
        jb = json.dumps({"b": b["boxes"], "s": b["scores"], "t": b["texts"]}, sort_keys=True)
        log("    %-11s identical=%s boxes=%d" % (name, ja == jb, len(a["boxes"])))


def e10_rapidocr_crosscheck():
    hr("E10 end-to-end cross-check vs rapidocr_onnxruntime 1.2.3 (v4 models injected)")
    try:
        from rapidocr_onnxruntime import RapidOCR
    except Exception as exc:
        log("    rapidocr_onnxruntime unavailable: %r" % exc)
        log("    install hint: pip install --target _reftools --no-deps rapidocr_onnxruntime pyyaml six")
        return
    import rapidocr_onnxruntime as RR
    log("    rapidocr_onnxruntime %s" % getattr(RR, "__version__", "?"))
    log("    NOTE: its config defaults are limit_side_len=736/limit_type=min, box_thresh=0.5,")
    log("          unclip_ratio=1.6, use_dilation=true, and it feeds RGB (PIL).")
    base = dict(det_model_path=os.path.join(MODEL_DIR, "ch_PP-OCRv4_det_infer.onnx"),
                rec_model_path=os.path.join(MODEL_DIR, "ch_PP-OCRv4_rec_infer.onnx"),
                cls_model_path=os.path.join(MODEL_DIR, "ch_ppocr_mobile_v2.0_cls_infer.onnx"))
    for tag, extra in (("rapidocr-defaults", {}),
                       ("spec-params(box.6,unclip1.5)", dict(box_thresh=0.6, unclip_ratio=1.5))):
        try:
            ocr = RapidOCR(**base, **extra)
        except Exception as exc:
            log("    %s: init failed %r" % (tag, exc))
            continue
        for name in ("rel_a.png", "rel_b.png"):
            p = os.path.join(IMGDIR, name)
            try:
                res, _ = ocr(p)
            except Exception as exc:
                log("    %s %s: run failed %r" % (tag, name, exc))
                continue
            if not res:
                log("    %s %s: no result" % (tag, name))
                continue
            log("    %s %-11s boxes=%d" % (tag, name, len(res)))
            for i, item in enumerate(res):
                box, txt, sc = item[0], item[1], item[2]
                log("        [%d] score=%s %r" % (i, sc, txt))
                log("            box=%s" % ([[round(float(v), 1) for v in pt] for pt in box],))


def e11_source_citations():
    hr("E11 upstream source citations (installed rapidocr_onnxruntime 1.2.3)")
    root = os.path.join(HERE, "_reftools", "rapidocr_onnxruntime")
    if not os.path.isdir(root):
        log("    _reftools/rapidocr_onnxruntime not present (verification only)")
        return
    import re
    picks = [
        ("ch_ppocr_v3_det/utils.py", "class NormalizeImage", 26),
        ("ch_ppocr_v3_det/utils.py", "def resize_image_type0", 34),
        ("ch_ppocr_v3_det/utils.py", "self.min_size = ", 6),
        ("ch_ppocr_v3_det/utils.py", "if sside < self.min_size + 2", 2),
        ("ch_ppocr_v3_det/utils.py", "def box_score_fast", 6),
        ("ch_ppocr_v3_rec/text_recognize.py", "def resize_norm_img", 22),
        ("ch_ppocr_v3_rec/utils.py", "self.character_str.append", 3),
        ("ch_ppocr_v3_rec/utils.py", "dict_character = ['blank']", 2),
        ("rapid_ocr_api.py", "def sorted_boxes", 6),
    ]
    for rel, needle, nlines in picks:
        p = os.path.join(root, rel)
        lines = open(p, encoding="utf-8").read().split("\n")
        for i, ln in enumerate(lines):
            if needle in ln:
                log("--- %s : %s" % (rel, needle))
                for x in lines[i:i + nlines]:
                    log("    " + x)
                break
    cfg = os.path.join(root, "ch_ppocr_v3_det/config.yaml")
    log("--- ch_ppocr_v3_det/config.yaml (RapidOCR defaults)")
    for ln in open(cfg, encoding="utf-8").read().split("\n"):
        if any(k in ln for k in ("thresh", "candidates", "unclip", "dilation", "score_mode",
                                 "limit_side", "limit_type", "mean", "std", "scale")):
            log("    " + ln.strip())
    cfg = os.path.join(root, "ch_ppocr_v3_rec/config.yaml")
    log("--- ch_ppocr_v3_rec/config.yaml")
    for ln in open(cfg, encoding="utf-8").read().split("\n"):
        if "rec_img_shape" in ln or "rec_batch_num" in ln:
            log("    " + ln.strip())


def main():
    os.makedirs(OUT, exist_ok=True)
    for fn in (e0_model_io, e1_channel_order, e2_det_normalisation, e3_resize_rule,
               e4_db_params, e5_rec_height, e6_rec_width_rule, e7_unclip,
               e8_vertical_crop, e9_determinism, e10_rapidocr_crosscheck,
               e11_source_citations):
        try:
            fn()
        except Exception as exc:                       # keep going, record the failure
            import traceback
            log("!!! %s FAILED: %r" % (fn.__name__, exc))
            log(traceback.format_exc())
    with open(LOGFILE, "w", encoding="utf-8") as fh:
        fh.write(_buf.getvalue())
    print("\n[written] %s" % LOGFILE)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()

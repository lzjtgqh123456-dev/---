#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""pc_ocr_ref.py -- authoritative PC reference pipeline for PP-OCRv4 (det -> (cls) -> rec).

Self-contained: onnxruntime + numpy + opencv only.
NO rapidocr / paddleocr / pyclipper dependency at runtime (pyclipper is replaced by a
pure-numpy polygon offset, see offset_polygon_miter).

Every constant below was measured or taken verbatim from the reference source
(RapidOCR 1.2.3 / PaddleOCR) -- see SPEC.md and out/experiments.txt.

Usage
-----
  set PY=D:\ai\projects\study-assistant\tools\ocrenv\Scripts\python.exe
  %PY% pc_ocr_ref.py --image img/rel_a.png --image img/rel_b.png --out-dir out
  %PY% pc_ocr_ref.py --image img/rel_a.png --dump-shapes --verbose

Measured model I/O (onnxruntime 1.30.0):
  det : input "x" [N,3,H,W] dynamic      -> output "sigmoid_0.tmp_0" [N,1,H,W]
  rec : input "x" [N,3,48,W]             -> output "softmax_11.tmp_0" [N,T,6625]
  cls : input "x" [N,3,48,192]           -> output "save_infer_model/scale_0.tmp_1" [N,2]
"""
from __future__ import annotations

import argparse
import json
import math
import os
import sys
import time

import cv2
import numpy as np
import onnxruntime as ort

# --------------------------------------------------------------------------------------
# normative constants (SPEC.md documents the measurement behind each one)
# --------------------------------------------------------------------------------------
DET_MEAN = (0.485, 0.456, 0.406)      # NormalizeImage mean (HWC order)
DET_STD = (0.229, 0.224, 0.225)       # NormalizeImage std
DET_SCALE = 1.0 / 255.0               # NormalizeImage scale
DET_LIMIT_SIDE_LEN = 960              # long-side limit, limit_type='max'
DET_SIZE_DIVISOR = 32                 # resize to a multiple of 32

DB_THRESH = 0.3                       # binarisation threshold of the prob map
DB_BOX_THRESH = 0.6                   # min mean prob inside a candidate box
DB_UNCLIP_RATIO = 1.5                 # polygon offset ratio
DB_MAX_CANDIDATES = 1000
DB_MIN_SIZE = 3                       # also: sside < min_size + 2 rejected after unclip

REC_HEIGHT = 48
REC_MAX_WIDTH = 320                   # rec_img_shape = (3,48,320); narrow crops padded to it
REC_MEAN, REC_STD = 0.5, 0.5          # (x/255 - 0.5) / 0.5
SPACE_CHAR = " "                      # use_space_char=True -> appended AFTER the keys
CTC_BLANK = 0

CLS_HEIGHT, CLS_WIDTH = 48, 192
CLS_THRESH = 0.9                      # flip only when label == '180' AND prob > 0.9


def load_char_table(keys_path, rec_model_path=None):
    """CTC index table: index 0 == blank, character[i-1] for i >= 1.

    character = dict_character + [' ']   (use_space_char=True)
    """
    with open(keys_path, "r", encoding="utf-8") as fh:
        raw = fh.read()
    keys = [ln for ln in raw.split("\n") if ln != ""]
    if rec_model_path and os.path.exists(rec_model_path):
        try:
            sess = ort.InferenceSession(rec_model_path, providers=["CPUExecutionProvider"])
            meta = sess.get_modelmeta().custom_metadata_map
            if "character" in meta:
                emb = [c for c in meta["character"].splitlines() if c != ""]
                if emb != keys:
                    print("WARN: embedded dict != %s (%d vs %d)"
                          % (keys_path, len(emb), len(keys)), file=sys.stderr)
        except Exception as exc:                               # pragma: no cover
            print("WARN: could not read rec metadata: %r" % exc, file=sys.stderr)
    return keys + [SPACE_CHAR]


def round_half_even_to_multiple(value, divisor=DET_SIZE_DIVISOR):
    """int(round(value / divisor)) * divisor with Python round() == round-half-to-EVEN.

    This is exactly the PaddleOCR/RapidOCR rule int(round(resize_h / 32) * 32).
    Kotlin Math.round() is half-UP and differs on an exact .5 (SPEC.md section 3).
    """
    return int(round(value / divisor)) * divisor


class DetPreProcess:
    """PaddleOCR/RapidOCR DetResizeForTest + NormalizeImage + ToCHW."""

    def __init__(self, limit_side_len=DET_LIMIT_SIDE_LEN, mean=DET_MEAN, std=DET_STD,
                 limit_type="max"):
        self.limit_side_len = int(limit_side_len)
        self.limit_type = limit_type
        self.mean = np.array(mean, dtype=np.float32)
        self.std = np.array(std, dtype=np.float32)

    def __call__(self, img):
        src_h, src_w = img.shape[:2]
        limit = self.limit_side_len
        if self.limit_type == "max":
            if max(src_h, src_w) > limit:
                ratio = float(limit) / (src_h if src_h > src_w else src_w)
            else:
                ratio = 1.0
        else:                               # 'min' == RapidOCR 1.2.3 config default
            if min(src_h, src_w) < limit:
                ratio = float(limit) / (src_h if src_h < src_w else src_w)
            else:
                ratio = 1.0
        # ---- upstream rule: truncate first, THEN round to nearest 32 (half-to-even) ----
        resize_h = int(src_h * ratio)
        resize_w = int(src_w * ratio)
        resize_h = max(round_half_even_to_multiple(resize_h), DET_SIZE_DIVISOR)
        resize_w = max(round_half_even_to_multiple(resize_w), DET_SIZE_DIVISOR)
        # --------------------------------------------------------------------------------
        resized = cv2.resize(img, (int(resize_w), int(resize_h)))
        data = resized.astype(np.float32) * DET_SCALE
        data = (data - self.mean) / self.std    # per-channel (HWC)
        tensor = np.ascontiguousarray(data.transpose(2, 0, 1)[None, ...], dtype=np.float32)
        ratio_h = resize_h / float(src_h)
        ratio_w = resize_w / float(src_w)
        return tensor, (src_h, src_w, ratio_h, ratio_w, resize_h, resize_w)


# ======================================================================================
# DB post-process (pure numpy replacement for pyclipper)
# ======================================================================================
def _poly_area(poly):
    x, y = poly[:, 0], poly[:, 1]
    return 0.5 * float(np.dot(x, np.roll(y, -1)) - np.dot(y, np.roll(x, -1)))


def _poly_perimeter(poly):
    d = poly - np.roll(poly, -1, axis=0)
    return float(np.sum(np.sqrt((d ** 2).sum(axis=1))))


def offset_polygon_miter(poly, distance, miter_limit=10.0):
    """Outward polygon offset ~= pyclipper.PyclipperOffset().Execute(distance), JT_ROUND.

    pyclipper is not installed and is unavailable on Android, so this is the normative
    implementation.  Joins are mitered (bevelled past miter_limit).
    Input quads are exact rotated rectangles; for those the min-area rectangle of the
    mitered polygon equals that of the round-joined one (same 4 offset edge lines, the
    rounded corners are never extreme points).  Measured deviation vs pyclipper:
    <= 1 px on the reference images (experiments E7).
    """
    poly = np.asarray(poly, dtype=np.float64)
    n = len(poly)
    if n < 3 or distance <= 0:
        return poly.copy()

    signed = _poly_area(poly)
    dirs = np.roll(poly, -1, axis=0) - poly         # edge vectors
    lens = np.sqrt((dirs ** 2).sum(axis=1))
    lens[lens == 0] = 1e-9
    unit = dirs / lens[:, None]
    nrm = np.stack([unit[:, 1], -unit[:, 0]], axis=1)   # rotate -90 deg
    if abs(_poly_area(poly + nrm * distance)) < abs(signed):
        nrm = -nrm                                  # make sure we grow outward

    out = []
    for j in range(n):
        k = (j + 1) % n
        p0, d0 = poly[j] + nrm[j] * distance, unit[j]     # line of offset edge j
        p1, d1 = poly[k] + nrm[k] * distance, unit[k]     # line of offset edge k
        denom = d0[0] * d1[1] - d0[1] * d1[0]
        if abs(denom) < 1e-9:                             # collinear -> bevel
            out.append(poly[k] + nrm[j] * distance)
            out.append(p1)
            continue
        t = ((p1[0] - p0[0]) * d1[1] - (p1[1] - p0[1]) * d1[0]) / denom
        ip = p0 + t * d0
        if np.linalg.norm(ip - poly[k]) > distance * miter_limit:
            out.append(poly[k] + nrm[j] * distance)       # miter too long -> bevel
            out.append(p1)
        else:
            out.append(ip)
    return np.asarray(out, dtype=np.float64)


def get_mini_boxes(contour):
    """PaddleOCR get_mini_boxes: min-area rect, points ordered TL,TR,BR,BL."""
    rect = cv2.minAreaRect(np.asarray(contour, dtype=np.float32))
    pts = sorted(list(cv2.boxPoints(rect)), key=lambda x: x[0])   # upstream sorts by x only
    if pts[1][1] > pts[0][1]:
        i1, i4 = 0, 1
    else:
        i1, i4 = 1, 0
    if pts[3][1] > pts[2][1]:
        i2, i3 = 2, 3
    else:
        i2, i3 = 3, 2
    return [pts[i1], pts[i2], pts[i3], pts[i4]], min(rect[1])


def box_score_fast(pred, box):
    """PaddleOCR box_score_fast on the probability map (score_mode='fast')."""
    h, w = pred.shape[:2]
    box = np.asarray(box, dtype=np.float64).copy()
    xmin = int(np.clip(np.floor(box[:, 0].min()), 0, w - 1))
    xmax = int(np.clip(np.ceil(box[:, 0].max()), 0, w - 1))
    ymin = int(np.clip(np.floor(box[:, 1].min()), 0, h - 1))
    ymax = int(np.clip(np.ceil(box[:, 1].max()), 0, h - 1))
    mask = np.zeros((ymax - ymin + 1, xmax - xmin + 1), dtype=np.uint8)
    box[:, 0] -= xmin
    box[:, 1] -= ymin
    cv2.fillPoly(mask, [box.astype(np.int32).reshape(1, -1, 2)], 1)
    return float(cv2.mean(pred[ymin:ymax + 1, xmin:xmax + 1], mask=mask)[0])


class DBPostProcess:
    """PaddleOCR/RapidOCR DBPostProcess, score_mode='fast', quad boxes."""

    def __init__(self, thresh=DB_THRESH, box_thresh=DB_BOX_THRESH,
                 max_candidates=DB_MAX_CANDIDATES, unclip_ratio=DB_UNCLIP_RATIO,
                 min_size=DB_MIN_SIZE, use_dilation=False):
        self.thresh = thresh
        self.box_thresh = box_thresh
        self.max_candidates = max_candidates
        self.unclip_ratio = unclip_ratio
        self.min_size = min_size
        self.dilation_kernel = (np.array([[1, 1], [1, 1]], dtype=np.uint8)
                                if use_dilation else None)

    def __call__(self, pred, shape_info):
        src_h, src_w, ratio_h, ratio_w, rh, rw = shape_info
        prob = pred[0, 0]
        bitmap = (prob > self.thresh).astype(np.uint8)
        if self.dilation_kernel is not None:
            bitmap = cv2.dilate(bitmap, self.dilation_kernel)
        outs = cv2.findContours(bitmap * 255, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
        contours = outs[0] if len(outs) == 2 else outs[1]
        boxes, scores = [], []
        for contour in contours[:min(len(contours), self.max_candidates)]:
            pts, sside = get_mini_boxes(contour)
            if sside < self.min_size:
                continue
            pts = np.asarray(pts, dtype=np.float64)
            score = box_score_fast(prob, pts)
            if self.box_thresh > score:
                continue
            dist = _poly_area(pts) * self.unclip_ratio / _poly_perimeter(pts)
            expanded = offset_polygon_miter(pts, dist)
            box, sside2 = get_mini_boxes(expanded)
            if sside2 < self.min_size + 2:
                continue
            box = np.asarray(box, dtype=np.float64)
            # back to source pixels: divide by the resize ratio, round, clip
            box[:, 0] = np.clip(np.round(box[:, 0] / ratio_w), 0, src_w)
            box[:, 1] = np.clip(np.round(box[:, 1] / ratio_h), 0, src_h)
            boxes.append(box.astype(np.int32).reshape(-1).tolist())   # [x1,y1,...,x4,y4]
            scores.append(score)
        return boxes, scores, len(contours)


def sorted_boxes(boxes):
    """PaddleOCR/RapidOCR sorted_boxes: by (y of point 0, x of point 0), stable."""
    idx = sorted(range(len(boxes)), key=lambda i: (boxes[i][1], boxes[i][0]))
    return [boxes[i] for i in idx], idx


def get_rotate_crop_image(img, points):
    """PaddleOCR/RapidOCR get_rotate_crop_image (INTER_CUBIC, BORDER_REPLICATE, rot90)."""
    pts = np.asarray(points, dtype=np.float32).reshape(4, 2)
    crop_w = int(max(np.linalg.norm(pts[0] - pts[1]), np.linalg.norm(pts[2] - pts[3])))
    crop_h = int(max(np.linalg.norm(pts[0] - pts[3]), np.linalg.norm(pts[1] - pts[2])))
    crop_w, crop_h = max(crop_w, 1), max(crop_h, 1)
    dst = np.float32([[0, 0], [crop_w, 0], [crop_w, crop_h], [0, crop_h]])
    M = cv2.getPerspectiveTransform(pts, dst)
    out = cv2.warpPerspective(img, M, (crop_w, crop_h),
                              borderMode=cv2.BORDER_REPLICATE, flags=cv2.INTER_CUBIC)
    h, w = out.shape[:2]
    if h * 1.0 / w >= 1.5:               # tall crop -> rotate into landscape
        out = np.rot90(out)
    return out


class RecPreProcess:
    """CRNN resize_norm_img.

    width_rule='paddleocr' (normative):
        r = w/h; resized_w = ceil(48*r); W = max(320, resized_w)
        -> resize to (resized_w, 48), then right-pad with zeros up to W
    width_rule='rapidocr' (RapidOCR 1.2.3 with a single crop):
        W = int(48*r); resized_w = min(ceil(48*r), W)   (no 320 floor -> no padding)
    """

    def __init__(self, img_h=REC_HEIGHT, max_width=REC_MAX_WIDTH, width_rule="paddleocr"):
        self.img_h = img_h
        self.max_width = max_width
        self.width_rule = width_rule

    def __call__(self, img):
        h, w = img.shape[:2]
        ratio = w / float(h)
        need = int(math.ceil(self.img_h * ratio))
        if self.width_rule == "paddleocr":
            width = max(self.max_width, need)
        else:
            width = max(int(self.img_h * ratio), 1)
        resized_w = max(min(need, width), 1)
        resized = cv2.resize(img, (resized_w, self.img_h)).astype(np.float32)
        data = resized.transpose(2, 0, 1) / 255.0
        data = (data - REC_MEAN) / REC_STD
        padded = np.zeros((3, self.img_h, width), dtype=np.float32)
        padded[:, :, 0:resized_w] = data
        return padded[None, ...]


def ctc_decode(probs, char_table):
    """Greedy CTC: drop blank(0) and frame-level repeats; index i -> char_table[i-1]."""
    idx = probs.argmax(axis=1)
    conf = probs.max(axis=1)
    chars, scores, prev = [], [], None
    for i, k in enumerate(idx):
        k = int(k)
        if k != CTC_BLANK and k != prev:
            chars.append(char_table[k - 1])
            scores.append(float(conf[i]))
        prev = k
    text = "".join(chars)
    # Score = mean confidence of the KEPT characters (PaddleOCR CTCLabelDecode:
    #   if len(conf_list) == 0: conf_list = [0];  score = mean(conf_list) ).
    # NOTE: RapidOCR 1.2.3 instead does mean(conf_list + [1e-50]), i.e. it always divides
    # by n+1, which reports ~n/(n+1) of the real value (13 chars -> 0.9249 vs 0.9961).
    score = float(np.mean(scores)) if scores else 0.0
    return text, score


class PPOCRRef:
    def __init__(self, model_dir, keys_path=None, use_cls=False,
                 limit_side_len=DET_LIMIT_SIDE_LEN, limit_type="max",
                 rec_width_rule="paddleocr", channel_order="bgr", threads=0, verbose=False):
        self.model_dir = model_dir
        so = ort.SessionOptions()
        if threads and threads > 0:
            so.intra_op_num_threads = threads
        so.log_severity_level = 3
        self.det_path = os.path.join(model_dir, "ch_PP-OCRv4_det_infer.onnx")
        self.rec_path = os.path.join(model_dir, "ch_PP-OCRv4_rec_infer.onnx")
        self.cls_path = os.path.join(model_dir, "ch_ppocr_mobile_v2.0_cls_infer.onnx")
        self.det = ort.InferenceSession(self.det_path, so, providers=["CPUExecutionProvider"])
        self.rec = ort.InferenceSession(self.rec_path, so, providers=["CPUExecutionProvider"])
        self.cls = None
        self.use_cls = use_cls
        if use_cls and os.path.exists(self.cls_path):
            self.cls = ort.InferenceSession(self.cls_path, so,
                                            providers=["CPUExecutionProvider"])
        self.char_table = load_char_table(
            keys_path or os.path.join(model_dir, "ppocr_keys_v1.txt"), self.rec_path)
        self.det_pre = DetPreProcess(limit_side_len, limit_type=limit_type)
        self.det_post = DBPostProcess()
        self.rec_pre = RecPreProcess(width_rule=rec_width_rule)
        self.channel_order = channel_order.lower()
        self.verbose = verbose

    def shapes(self):
        rep = {}
        for tag, sess in (("det", self.det), ("rec", self.rec), ("cls", self.cls)):
            if sess is None:
                continue
            rep[tag] = {
                "inputs": [[i.name, list(i.shape), i.type] for i in sess.get_inputs()],
                "outputs": [[o.name, list(o.shape), o.type] for o in sess.get_outputs()],
            }
        return rep

    def prepare(self, img):
        """bgr = PaddleOCR training order (default), rgb = RapidOCR/PIL order."""
        if self.channel_order == "rgb":
            return np.ascontiguousarray(img[:, :, ::-1])
        return img

    def run(self, img, use_cls=None, timings=None):
        img = self.prepare(img)
        use_cls = self.use_cls if use_cls is None else use_cls
        timings = timings if timings is not None else {}

        t0 = time.perf_counter()
        det_in, shape_info = self.det_pre(img)
        det_out = self.det.run(None, {self.det.get_inputs()[0].name: det_in})[0]
        timings["det_ms"] = (time.perf_counter() - t0) * 1000.0
        if self.verbose:
            print("  det in %s -> out %s" % (det_in.shape, det_out.shape))

        t0 = time.perf_counter()
        boxes, det_scores, n_contours = self.det_post(det_out, shape_info)
        boxes, order = sorted_boxes(boxes)
        det_scores = [det_scores[i] for i in order]
        timings["post_ms"] = (time.perf_counter() - t0) * 1000.0

        t0 = time.perf_counter()
        crops = [get_rotate_crop_image(img, b) for b in boxes]
        timings["crop_ms"] = (time.perf_counter() - t0) * 1000.0

        texts, rec_scores, widths = [], [], []
        rec_name = self.rec.get_inputs()[0].name
        t0 = time.perf_counter()
        for crop in crops:
            if use_cls and self.cls is not None:
                crop = self._cls_rotate(crop)
            rec_in = self.rec_pre(crop)
            widths.append(int(rec_in.shape[3]))
            if self.verbose:
                print("  rec in %s" % (rec_in.shape,))
            probs = self.rec.run(None, {rec_name: rec_in})[0][0]
            text, score = ctc_decode(probs, self.char_table)
            texts.append(text)
            rec_scores.append(score)
        timings["rec_ms"] = (time.perf_counter() - t0) * 1000.0
        return {
            "image_size_in": [img.shape[1], img.shape[0]],
            "det_input_shape": list(det_in.shape),
            "det_out_shape": list(det_out.shape),
            "boxes": boxes,
            "det_scores": det_scores,
            "scores": rec_scores,
            "texts": texts,
            "full": "\n".join(texts),
            "rec_widths": widths,
            "n_contours": n_contours,
        }

    def _cls_rotate(self, img):
        """TextClassifier: resize to h=48 keeping ratio, right-pad to 192, (x/255-0.5)/0.5."""
        h, w = img.shape[:2]
        if h == 0 or w == 0:
            return img
        need = int(math.ceil(CLS_HEIGHT * (w / float(h))))
        resized_w = max(min(need, CLS_WIDTH), 1)
        resized = cv2.resize(img, (resized_w, CLS_HEIGHT)).astype(np.float32)
        data = resized.transpose(2, 0, 1) / 255.0
        data = (data - 0.5) / 0.5
        padded = np.zeros((3, CLS_HEIGHT, CLS_WIDTH), dtype=np.float32)
        padded[:, :, 0:resized_w] = data
        out = self.cls.run(None, {self.cls.get_inputs()[0].name: padded[None, ...]})[0][0]
        if int(out.argmax()) == 1 and float(out[1]) > CLS_THRESH:   # label '180'
            return np.ascontiguousarray(np.rot90(img, 2))
        return img


def draw_vis(img_bgr, res, font_path="C:/Windows/Fonts/msyh.ttc"):
    """Draw the detected quads plus 'idx text score' labels; returns a BGR image."""
    from PIL import Image, ImageDraw, ImageFont
    vis = img_bgr.copy()
    for b in res["boxes"]:
        pts = np.asarray(b, dtype=np.int32).reshape(-1, 2)
        cv2.polylines(vis, [pts], True, (0, 0, 255), 2)
    img = Image.fromarray(cv2.cvtColor(vis, cv2.COLOR_BGR2RGB))
    d = ImageDraw.Draw(img)
    try:
        f = ImageFont.truetype(font_path, 20)
    except Exception:
        f = ImageFont.load_default()
    for i, b in enumerate(res["boxes"]):
        pts = np.asarray(b, dtype=np.int32).reshape(-1, 2)
        x, y = int(pts[:, 0].min()), int(pts[:, 1].min())
        label = "%d %s %.2f" % (i, res["texts"][i], res["scores"][i])
        d.rectangle([x, max(0, y - 22), x + 9 * len(label), y], fill=(255, 255, 0))
        d.text((x + 2, max(0, y - 20)), label, font=f, fill=(200, 0, 0))
    return cv2.cvtColor(np.array(img), cv2.COLOR_RGB2BGR)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--image", action="append", required=True)
    ap.add_argument("--model-dir", default=r"D:\ai\models\ocr\ch")
    ap.add_argument("--keys", default=None)
    ap.add_argument("--out", default=None, help="json path (single --image only)")
    ap.add_argument("--out-dir", default=None, help="write <stem>.json / <stem>.vis.png here")
    ap.add_argument("--vis", default=None)
    ap.add_argument("--use-cls", action="store_true")
    ap.add_argument("--limit-side-len", type=int, default=DET_LIMIT_SIDE_LEN)
    ap.add_argument("--limit-type", default="max", choices=["max", "min"])
    ap.add_argument("--rec-width-rule", default="paddleocr", choices=["paddleocr", "rapidocr"])
    ap.add_argument("--channel-order", default="bgr", choices=["bgr", "rgb"])
    ap.add_argument("--dump-shapes", action="store_true")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args(argv)

    eng = PPOCRRef(args.model_dir, args.keys, use_cls=args.use_cls,
                   limit_side_len=args.limit_side_len, limit_type=args.limit_type,
                   rec_width_rule=args.rec_width_rule, channel_order=args.channel_order,
                   verbose=args.verbose)
    if args.dump_shapes:
        print(json.dumps(eng.shapes(), indent=2, ensure_ascii=False))
    if args.out_dir:
        os.makedirs(args.out_dir, exist_ok=True)

    single = len(args.image) == 1
    for path in args.image:
        img = cv2.imdecode(np.fromfile(path, dtype=np.uint8), cv2.IMREAD_COLOR)
        if img is None:
            print("cannot read", path, file=sys.stderr)
            continue
        tm = {}
        res = eng.run(img, timings=tm)
        out = {
            "image": os.path.basename(path),
            "image_size": [img.shape[1], img.shape[0]],
            "boxes": res["boxes"],
            "scores": res["scores"],
            "texts": res["texts"],
            "full": res["full"],
            # --- diagnostics (extra, not required by the Android device test) ---
            "det_scores": res["det_scores"],
            "det_input_shape": res["det_input_shape"],
            "det_out_shape": res["det_out_shape"],
            "rec_widths": res["rec_widths"],
            "n_contours": res["n_contours"],
            "timings_ms": {k: round(v, 2) for k, v in tm.items()},
        }
        print("=== %s (%dx%d) %d boxes %.1fms" %
              (path, img.shape[1], img.shape[0], len(res["boxes"]), sum(tm.values())))
        print(res["full"])
        stem = os.path.splitext(os.path.basename(path))[0]
        js = args.out if (args.out and single) else (
            os.path.join(args.out_dir, stem + ".json") if args.out_dir else None)
        if js:
            with open(js, "w", encoding="utf-8") as fh:
                json.dump(out, fh, ensure_ascii=False, indent=2)
            print("json ->", js)
        vis = args.vis if (args.vis and single) else (
            os.path.join(args.out_dir, stem + ".vis.png") if args.out_dir else None)
        if vis:
            cv2.imencode(".png", draw_vis(img, res))[1].tofile(vis)
            print("vis  ->", vis)
    return 0


if __name__ == "__main__":
    sys.exit(main())

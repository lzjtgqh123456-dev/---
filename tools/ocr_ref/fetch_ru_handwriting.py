#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""fetch_ru_handwriting.py -- pull public Cyrillic HANDWRITING samples (+GT) for the bench.

Sources (both HuggingFace datasets, fetched through the public datasets-server API):
  1. deepcopy/synthetic-handwritten-cyrillic-180k   -> synthetic handwriting, word/short-phrase,
     exact GT per image            -> img/ru_bench/handwritten/syn_*.png, mode = whole image
  2. Limerencii/russian-handwriting-ocr             -> REAL handwriting (scan / dark / light
     photo) of Russian essays, page-level transcript -> img/ru_bench/real_pages/real_*.png

License note: neither dataset declares a license on the Hub (checked via /api/datasets).
They are used here only as a few internal benchmark samples with full attribution
(see img/ru_bench/SOURCES.md). Do not redistribute.

Run:
  D:/ai/projects/study-assistant/tools/ocrenv/Scripts/python.exe fetch_ru_handwriting.py
"""
import io
import json
import os
import urllib.parse
import urllib.request

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
BENCH = os.path.join(HERE, "img", "ru_bench")
HAND = os.path.join(BENCH, "handwritten")
PAGES = os.path.join(BENCH, "real_pages")
GT = os.path.join(BENCH, "gt")

API = "https://datasets-server.huggingface.co/rows?dataset=%s&config=default&split=train&offset=%d&length=%d"


def get_json(url, timeout=120):
    req = urllib.request.Request(url, headers={"User-Agent": "ocr-bench/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def save_png(raw, path):
    """The Hub's cached assets are often JPEG; re-encode so the .png name is truthful."""
    arr = np.frombuffer(raw, np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if img is None:
        with open(path, "wb") as fh:
            fh.write(raw)
    else:
        cv2.imencode(".png", img)[1].tofile(path)


def get_bytes(url, timeout=180):
    req = urllib.request.Request(url, headers={"User-Agent": "ocr-bench/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def norm_text(s):
    return " ".join(str(s).replace("\r", " ").split())


UK_EXCLUSIVE = set("іїєґІЇЄҐ")


def is_russian_like(text):
    """has Cyrillic, no Ukrainian-exclusive letters, long enough to be a real test."""
    if not any("А" <= c <= "я" or c in "Ёё" for c in text):
        return False
    if any(c in UK_EXCLUSIVE for c in text):
        return False
    return len(text) >= 12


def fetch_synthetic(n=8, offset=120, scan=100, pages=4):
    """Scan up to `pages`*`scan` rows (datasets-server caps length at 100), keep the
    `n` longest Russian-like samples."""
    ds = "deepcopy/synthetic-handwritten-cyrillic-180k"
    cands = []
    for pg in range(pages):
        off = offset + pg * scan
        rows = get_json(API % (urllib.parse.quote(ds, safe=""), off, scan))["rows"]
        if not rows:
            break
        for r in rows:
            text = norm_text(r["row"]["text"])
            if is_russian_like(text):
                cands.append((len(text), text, r["row"]))
        if len(cands) >= n * 3:
            break
    cands.sort(key=lambda x: -x[0])
    out = []
    for i, (_, text, row) in enumerate(cands[:n]):
        name = "syn_%02d" % i
        p = os.path.join(HAND, name + ".png")
        save_png(get_bytes(row["image"]["src"]), p)
        with open(os.path.join(GT, name + ".txt"), "w", encoding="utf-8") as fh:
            fh.write(text + "\n")
        out.append(dict(name=name, image=os.path.relpath(p, HERE), text=text,
                        source=ds, license="not declared on the Hub (internal benchmark only)"))
        print("  %s  %-28s <- %r" % (name, "(synthetic handwriting)", text[:60]))
    return out


def fetch_real_pages(n=3, offset=0):
    ds = "Limerencii/russian-handwriting-ocr"
    rows = get_json(API % (urllib.parse.quote(ds, safe=""), offset, n))["rows"]
    out = []
    for i, r in enumerate(rows):
        row = r["row"]
        text = norm_text(row["messages"][1]["content"][0]["text"])
        name = "real_%02d_%s" % (i, row.get("image_type", "scan"))
        p = os.path.join(PAGES, name + ".png")
        save_png(get_bytes(row["image"]["src"]), p)
        with open(os.path.join(GT, name + ".txt"), "w", encoding="utf-8") as fh:
            fh.write(text + "\n")
        out.append(dict(name=name, image=os.path.relpath(p, HERE), text=text,
                        source=ds, image_type=row.get("image_type"),
                        license="not declared on the Hub (internal benchmark only)"))
        print("  %s  %-28s chars=%d" % (name, "(REAL handwriting page)", len(text)))
    return out


def main():
    for d in (HAND, PAGES, GT):
        os.makedirs(d, exist_ok=True)
    man = {"synthetic_handwriting": [], "real_handwriting_pages": []}
    print("== synthetic handwriting (word level, exact GT) ==")
    man["synthetic_handwriting"] = fetch_synthetic(8, 120)
    print("== real handwriting pages (page-level transcript) ==")
    man["real_handwriting_pages"] = fetch_real_pages(3, 0)
    with open(os.path.join(BENCH, "handwriting_manifest.json"), "w", encoding="utf-8") as fh:
        json.dump(man, fh, ensure_ascii=False, indent=2)
    print("\nmanifest -> img/ru_bench/handwriting_manifest.json")
    print("samples: %d synthetic + %d real pages"
          % (len(man["synthetic_handwriting"]), len(man["real_handwriting_pages"])))


if __name__ == "__main__":
    main()

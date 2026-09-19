#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Generate the two deterministic reference test images for the OCR batch.

  img/rel_a.png : pure white background, black Chinese text, 3 lines (clean case)
  img/rel_b.png : Chinese + English mixed, background colour blocks, ~4 deg tilt, 2 lines

Run:
    D:\\ai\\projects\\study-assistant\\tools\\ocrenv\\Scripts\\python.exe make_test_images.py
"""
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
IMG_DIR = os.path.join(HERE, "img")

MSYH = "C:/Windows/Fonts/msyh.ttc"
ARIAL = "C:/Windows/Fonts/arial.ttf"


def font(path, size):
    try:
        return ImageFont.truetype(path, size, index=0)
    except Exception:
        return ImageFont.truetype(path, size)


def make_a():
    """Clean case: white background, black CJK text, 3 lines."""
    W, H = 900, 400
    img = Image.new("RGB", (W, H), (255, 255, 255))
    d = ImageDraw.Draw(img)
    f = font(MSYH, 48)
    lines = [
        "今天天气很好我们去公园散步",
        "识别测试第一行中文文字内容",
        "第三行机器学习与深度学习",
    ]
    for i, t in enumerate(lines):
        d.text((40, 60 + i * 100), t, font=f, fill=(0, 0, 0))
    return img


def make_b():
    """Harder case: CN+EN mixed, colour blocks behind text, ~4 degree tilt, 3 lines."""
    W, H = 1000, 480
    base = Image.new("RGB", (W, H), (238, 240, 245))
    d = ImageDraw.Draw(base)
    # background colour blocks (behind the text layer)
    d.rectangle([30, 40, 660, 160], fill=(205, 225, 245))
    d.rectangle([60, 175, 960, 300], fill=(250, 232, 200))
    d.rectangle([40, 330, 520, 440], fill=(215, 240, 215))

    # text layer: transparent, rotated, then composited
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    f_cjk = font(MSYH, 44)
    f_en = font(ARIAL, 44)
    ld.text((70, 75), "机器学习 Machine Learning 2024", font=f_cjk, fill=(20, 20, 90, 255))
    ld.text((80, 210), "OCR test: 识别率 99.8%", font=f_cjk, fill=(90, 20, 20, 255))
    # pure-English line with the Latin font for ASCII charset coverage
    ld.text((70, 350), "ABC abc 123 Test", font=f_en, fill=(30, 60, 30, 255))

    layer = layer.rotate(-4.0, resample=Image.BICUBIC, expand=False, center=(W // 2, H // 2))
    base = Image.alpha_composite(base.convert("RGBA"), layer).convert("RGB")
    return base


def main():
    os.makedirs(IMG_DIR, exist_ok=True)
    for name, fn in (("rel_a.png", make_a), ("rel_b.png", make_b)):
        p = os.path.join(IMG_DIR, name)
        im = fn()
        im.save(p)
        print("wrote", p, im.size)


if __name__ == "__main__":
    main()

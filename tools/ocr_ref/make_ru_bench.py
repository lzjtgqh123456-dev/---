#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""make_ru_bench.py -- build the synthetic PRINTED Cyrillic benchmark (deterministic).

Writes:
  img/ru_bench/printed/<name>.png          rendered image
  img/ru_bench/gt/<name>.txt               ground-truth text, one line per text line
  img/ru_bench/gt/<name>.boxes.json        per-line quads (for rec-only / "gt box" mode)

Run:
  D:/ai/projects/study-assistant/tools/ocrenv/Scripts/python.exe make_ru_bench.py
"""
import json
import os
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_IMG = os.path.join(HERE, "img", "ru_bench", "printed")
OUT_GT = os.path.join(HERE, "img", "ru_bench", "gt")

F = {
    "arial": "C:/Windows/Fonts/arial.ttf",
    "times": "C:/Windows/Fonts/times.ttf",
    "segoe": "C:/Windows/Fonts/segoeui.ttf",
    "tahoma": "C:/Windows/Fonts/tahoma.ttf",
    "verdana": "C:/Windows/Fonts/verdana.ttf",
    "georgia": "C:/Windows/Fonts/georgia.ttf",
}
CJK = "C:/Windows/Fonts/msyh.ttc"
_cache = {}


def font(name, size):
    key = (name, size)
    if key not in _cache:
        path = CJK if name == "cjk" else F[name]
        kw = {"index": 0} if path.lower().endswith(".ttc") else {}
        _cache[key] = ImageFont.truetype(path, size, **kw)
    return _cache[key]


# ---------------------------------------------------------------------------
# test cases:  (font size 20/28/40, line count 1..4, ru / ru+en / ru+cn mixes)
# ---------------------------------------------------------------------------
CASES = [
    dict(name="p01_ru_3lines", font="arial", size=28, bg=(255, 255, 255), pad=8,
         lines=["Москва — столица России.",
                "Я изучаю русский язык уже два года.",
                "Сегодня хорошая погода, мы гуляем в парке."]),
    dict(name="p02_ru_1line", font="times", size=40, bg=(255, 255, 255), pad=10,
         lines=["Санкт-Петербург, Невский проспект 28"]),
    dict(name="p03_ru_4lines", font="segoe", size=20, bg=(255, 255, 255), pad=6,
         lines=["Студенты сдают экзамены в июне.",
                "Библиотека работает до восьми вечера.",
                "Расписание занятий изменилось.",
                "Пожалуйста, приходите вовремя."]),
    dict(name="p04_ru_en_mix", font="arial", size=28, bg=(255, 255, 255), pad=8,
         lines=["Забронируйте отель на Booking.com",
                "Цена: 1250 рублей за ночь",
                "Check-in после 14:00, check-out до 12:00"]),
    dict(name="p05_ru_cn_mix", font="arial", size=28, bg=(255, 255, 255), pad=8,
         lines=["俄罗斯留学申请材料清单",
                "Заявление и паспорт",
                "Диплом и перевод документов"]),
    dict(name="p06_ru_hierarchy", font="arial", size=28, bg=(255, 255, 255), pad=10,
         lines=[dict(text="ВНИМАНИЕ", size=40, font="arial"),
                dict(text="Поезд отправляется в 19:45", size=28),
                dict(text="Не забудьте документы и билеты", size=20)]),
    dict(name="p07_ru_graybg", font="tahoma", size=28, bg=(238, 240, 245), pad=8,
         blocks=[(0, 30, 640, 90, (205, 225, 245))],
         lines=["Внимание! Рейс задержан.",
                "Приносим извинения за неудобства."]),
    dict(name="p08_ru_all_mix", font="verdana", size=24, bg=(255, 255, 255), pad=8,
         lines=["Заказ № 4587 от 12.05.2024",
                "Товар: словарь, 3 шт., цена 899 руб.",
                "E-mail: info@example.ru, тел. +7 495 123-45-67",
                "谢谢！Спасибо за покупку!"]),
]


def render_case(case):
    lines = []
    for ln in case["lines"]:
        if isinstance(ln, str):
            lines.append(dict(text=ln, size=case["size"], font=case["font"], color=(0, 0, 0)))
        else:
            d = dict(size=case["size"], font=case["font"], color=(0, 0, 0))
            d.update(ln)
            lines.append(d)

    painter = ImageDraw.Draw(Image.new("RGB", (10, 10)))
    gap = 6
    heights, widths = [], []
    for ln in lines:
        f = font("cjk" if any("\u4e00" <= c <= "\u9fff" for c in ln["text"]) else ln["font"],
                 ln["size"])
        box = painter.textbbox((0, 0), ln["text"], font=f)
        widths.append(box[2] - box[0])
        heights.append(box[3] - box[1])
    pad = case["pad"]
    W = max(widths) + 2 * pad
    H = sum(heights) + gap * (len(lines) - 1) + 2 * pad
    img = Image.new("RGB", (W, H), case["bg"])
    d = ImageDraw.Draw(img)
    for blk in case.get("blocks", []):
        d.rectangle(list(blk[:4]), fill=tuple(blk[4]))

    boxes, y = [], pad
    for ln, h in zip(lines, heights):
        f = font("cjk" if any("\u4e00" <= c <= "\u9fff" for c in ln["text"]) else ln["font"],
                 ln["size"])
        bbox = d.textbbox((pad, y), ln["text"], font=f)
        d.text((pad, y), ln["text"], font=f, fill=tuple(ln["color"]))
        x0, y0, x1, y1 = bbox
        m = 2
        boxes.append(dict(text=ln["text"],
                          box=[max(0, x0 - m), max(0, y0 - m), x1 + m, max(0, y0 - m),
                               x1 + m, y1 + m, max(0, x0 - m), y1 + m]))
        y += h + gap
    return img, boxes


def main():
    os.makedirs(OUT_IMG, exist_ok=True)
    os.makedirs(OUT_GT, exist_ok=True)
    for case in CASES:
        img, boxes = render_case(case)
        p = os.path.join(OUT_IMG, case["name"] + ".png")
        img.save(p)
        with open(os.path.join(OUT_GT, case["name"] + ".txt"), "w", encoding="utf-8") as fh:
            fh.write("\n".join(b["text"] for b in boxes) + "\n")
        with open(os.path.join(OUT_GT, case["name"] + ".boxes.json"), "w", encoding="utf-8") as fh:
            json.dump({"image": case["name"] + ".png", "size": [img.size[0], img.size[1]],
                       "lines": boxes}, fh, ensure_ascii=False, indent=2)
        print("wrote %-22s %dx%d  %d line(s)" % (case["name"], img.size[0], img.size[1], len(boxes)))


if __name__ == "__main__":
    main()

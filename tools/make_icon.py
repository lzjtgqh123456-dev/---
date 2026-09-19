#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""自定义 App 图标：把 icon_src/icon.png 生成 Android 全套图标。

用法：
    py tools/make_icon.py                 # 用 icon_src/icon.png
    py tools/make_icon.py 我的图.png       # 临时指定别的图

输入建议：
    · 正方形 PNG（1024×1024 最好，至少 512×512）
    · **带透明背景** → 当作"logo"：图本身居中放，背景自动取图上主色（可用 icon_src/bg.txt 覆盖）
    · **不带透明（整张铺满）** → 当作"整图背景"：整张图铺满图标

输出（全部自动写进 app/src/main/res/）：
    mipmap-mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi 的 ic_launcher.png + ic_launcher_round.png
    drawable/ic_launcher_foreground.png / ic_launcher_background.png
    mipmap-anydpi-v26/ic_launcher.xml + ic_launcher_round.xml（自适应图标，含 monochrome）
"""
import os
import sys
from collections import Counter

from PIL import Image, ImageDraw, ImageOps

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
SRC_DIR = os.path.join(ROOT, "icon_src")

# 各密度下 legacy 图标边长
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
# 自适应图标：108dp 画布，安全区中心 72dp；前景logo缩到画布的 62%
ADAPTIVE = 432
LOGO_SCALE = 0.62


def load_source(path: str) -> Image.Image:
    img = Image.open(path).convert("RGBA")
    w, h = img.size
    if w != h:  # 非正方形：居中裁成正方形
        side = min(w, h)
        img = img.crop(((w - side) // 2, (h - side) // 2, (w + side) // 2, (h + side) // 2))
    return img


def has_transparency(img: Image.Image) -> bool:
    alpha = img.getchannel("A")
    hist = alpha.histogram()
    transparent = sum(hist[:200])          # alpha < 200 算透明
    return transparent > (img.width * img.height * 0.05)


def dominant_color(img: Image.Image) -> tuple:
    """取不透明像素的主色（用于 logo 模式的背景）"""
    small = img.resize((64, 64))
    counts = Counter()
    for r, g, b, a in small.getdata():
        if a < 200:
            continue
        counts[(r // 24 * 24, g // 24 * 24, b // 24 * 24)] += 1
    if not counts:
        return (20, 98, 90, 255)
    r, g, b = counts.most_common(1)[0][0]
    return (r, g, b, 255)


def fit(img: Image.Image, size: int) -> Image.Image:
    return ImageOps.contain(img, (size, size), Image.LANCZOS)


def compose_icon(src: Image.Image, logo_mode: bool, bg: tuple, size: int, round_mask: bool) -> Image.Image:
    """出图：logo 模式 = 纯色底 + 居中小图；整图模式 = 图铺满"""
    canvas = Image.new("RGBA", (size, size), bg if logo_mode else (0, 0, 0, 0))
    if logo_mode:
        fg = fit(src, int(size * 0.78))
        canvas.alpha_composite(fg, ((size - fg.width) // 2, (size - fg.height) // 2))
    else:
        bgimg = ImageOps.fit(src.convert("RGBA"), (size, size), Image.LANCZOS)
        canvas.alpha_composite(bgimg)
    if round_mask:
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
        out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        out.paste(canvas, (0, 0), mask)
        return out
    return canvas


def main() -> int:
    name = sys.argv[1] if len(sys.argv) > 1 else "icon.png"
    path = name if os.path.isabs(name) else os.path.join(SRC_DIR, name)
    if not os.path.exists(path):
        print("找不到源图：%s\n请把图片放到 %s（文件名 icon.png）" % (path, SRC_DIR))
        return 1

    src = load_source(path)
    logo_mode = has_transparency(src)
    bg_path = os.path.join(SRC_DIR, "bg.txt")
    if os.path.exists(bg_path):
        hexv = open(bg_path, encoding="utf-8").read().strip().lstrip("#")
        bg = tuple(int(hexv[i:i + 2], 16) for i in (0, 2, 4)) + (255,)
    else:
        bg = (0x14, 0x62, 0x5A, 255)   # 默认用 App 主题青；想换就写 icon_src/bg.txt（如 #1E6F5C）
    print("源图 %s  %dx%d  模式=%s  背景色=#%02X%02X%02X" % (
        os.path.basename(path), src.width, src.height,
        "logo" if logo_mode else "整图铺满", bg[0], bg[1], bg[2]))

    # 1) legacy 各密度 PNG（方形 + 圆形）
    for dens, size in DENSITIES.items():
        d = os.path.join(RES, "mipmap-" + dens)
        os.makedirs(d, exist_ok=True)
        compose_icon(src, logo_mode, bg, size, False).save(os.path.join(d, "ic_launcher.png"))
        compose_icon(src, logo_mode, bg, size, True).save(os.path.join(d, "ic_launcher_round.png"))

    # 2) 自适应图标的两层
    drawable = os.path.join(RES, "drawable")
    os.makedirs(drawable, exist_ok=True)
    if logo_mode:
        fg = Image.new("RGBA", (ADAPTIVE, ADAPTIVE), (0, 0, 0, 0))
        logo = fit(src, int(ADAPTIVE * LOGO_SCALE))
        fg.alpha_composite(logo, ((ADAPTIVE - logo.width) // 2, (ADAPTIVE - logo.height) // 2))
        fg.save(os.path.join(drawable, "ic_launcher_foreground.png"))
        Image.new("RGBA", (ADAPTIVE, ADAPTIVE), bg).save(
            os.path.join(drawable, "ic_launcher_background.png"))
    else:
        # 整图模式：图放背景层铺满，前景留空
        ImageOps.fit(src, (ADAPTIVE, ADAPTIVE), Image.LANCZOS).save(
            os.path.join(drawable, "ic_launcher_background.png"))
        Image.new("RGBA", (ADAPTIVE, ADAPTIVE), (0, 0, 0, 0)).save(
            os.path.join(drawable, "ic_launcher_foreground.png"))

    # 3) anydpi-v26 自适应图标 xml
    anydpi = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
           '    <background android:drawable="@drawable/ic_launcher_background" />\n'
           '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
           '    <monochrome android:drawable="@drawable/ic_launcher_foreground" />\n'
           '</adaptive-icon>\n')
    for n in ("ic_launcher.xml", "ic_launcher_round.xml"):
        open(os.path.join(anydpi, n), "w", encoding="utf-8", newline="\n").write(xml)

    print("完成：mipmap-*/ + drawable/ic_launcher_{foreground,background}.png + anydpi-v26/*.xml")
    print("重新打包即可生效：py build.py <项目> :app:assembleDebug")
    return 0


if __name__ == "__main__":
    sys.exit(main())

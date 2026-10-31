#!/usr/bin/env python3
"""Folio Awakening: generates the Android bootanimation (frames, desc.txt, stored zip, preview).

Usage: python make_bootanimation.py OUT_DIR
Writes OUT_DIR/light and OUT_DIR/dark, each with part0/, part1/, desc.txt and bootanimation.zip,
plus preview_light.gif / preview_dark.gif. Pure Pillow, vector-drawn at 2x and downsampled.
"""
import os
import sys
import zipfile

from PIL import Image, ImageDraw, ImageFont

W, H, FPS = 800, 1280, 20
SS = 2  # supersampling factor
FONTS = r"C:\Windows\Fonts"
TOTAL = 60  # 3 s at 20 fps; the last frame is the resting composition

PALETTES = {
    "light": dict(bg=(0xF8, 0xF4, 0xE8), ink=(0x1F, 0x1C, 0x18), page=(0xED, 0xE2, 0xD1), edge=(0xB8, 0xA9, 0x95)),
    "dark": dict(bg=(0x1F, 0x1C, 0x18), ink=(0xF8, 0xF4, 0xE8), page=(0x2B, 0x27, 0x23), edge=(0x5A, 0x51, 0x46)),
}


def ease(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def seg(f, a, b):
    """Progress 0..1 of frame f inside [a, b]."""
    return ease((f - a) / float(b - a))


def mix(c1, c2, t):
    return tuple(int(round(c1[i] + (c2[i] - c1[i]) * t)) for i in range(3))


def book_points(t, half=190, grow=1.0):
    """Outline of an open book as a polyline; t=0 is a flat line, t=1 the full book."""
    pts = [
        (-half, 0), (-half, -112), (-95, -122), (0, -100), (95, -122), (half, -112), (half, 0),
        (half, 34), (95, 58), (0, 84), (-95, 58), (-half, 34), (-half, 0),
    ]
    return [(x * grow, y * t + 0) for x, y in pts]


def draw_frame(f, pal, fonts):
    big = Image.new("RGB", (W * SS, H * SS), pal["bg"])
    d = ImageDraw.Draw(big)
    cx, cy = W * SS // 2, 540 * SS

    def P(pts):
        return [(cx + x * SS, cy + y * SS) for x, y in pts]

    lw = 5 * SS

    # 1. the line appears in the centre and grows
    grow = seg(f, 6, 15)
    # 2. it becomes the outline of a book
    morph = seg(f, 16, 30)
    # 3. pages open: extra page edges fan out and the page surface fades in
    fan = seg(f, 31, 42)
    fill = seg(f, 24, 34)

    if grow > 0:
        if morph <= 0:
            half = 190 * grow
            d.line(P([(-half, 0), (half, 0)]), fill=mix(pal["bg"], pal["ink"], min(1.0, grow * 1.5)), width=lw)
        else:
            pts = book_points(morph)
            # the flat line sits at y=-10 in the outline, keep the join with the line phase seamless
            pts = [(x, y) for x, y in pts]
            if fill > 0:
                d.polygon(P(pts), fill=mix(pal["bg"], pal["page"], fill))
            for k in (2, 1):  # extra page layers behind the cover
                off = fan * 11 * k
                if off > 0.5:
                    layer = [(x + (off if x > 0 else -off) * 0.6, y + off * 0.9) for x, y in pts]
                    d.line(P(layer), fill=mix(pal["bg"], pal["edge"], fan), width=int(lw * 0.7), joint="curve")
            d.line(P(pts), fill=pal["ink"], width=lw, joint="curve")
            d.line(P([(0, -100 * morph), (0, 84 * morph)]),
                   fill=pal["edge"], width=int(lw * 0.6))

    # 4. monogram F
    fa = seg(f, 43, 50)
    if fa > 0:
        fr = fonts["F"]
        text = "F"
        bb = d.textbbox((0, 0), text, font=fr)
        tw, th = bb[2] - bb[0], bb[3] - bb[1]
        x = cx - tw // 2 - bb[0]
        y = cy - 22 * SS - th // 2 - bb[1] + int((1 - fa) * 14 * SS)
        d.text((x, y), text, font=fr, fill=mix(pal["page"] if f >= 31 else pal["bg"], pal["ink"], fa))

    # 5. title
    ta = seg(f, 50, 58)
    if ta > 0:
        ft = fonts["T"]
        text = "Folio"
        bb = d.textbbox((0, 0), text, font=ft)
        tw = bb[2] - bb[0]
        x = cx - tw // 2 - bb[0]
        y = cy + 150 * SS - bb[1] + int((1 - ta) * 10 * SS)
        d.text((x, y), text, font=ft, fill=mix(pal["bg"], pal["ink"], ta))

    return big.resize((W, H), Image.LANCZOS)


def build(name, pal, out):
    base = os.path.join(out, name)
    for part in ("part0", "part1"):
        os.makedirs(os.path.join(base, part), exist_ok=True)
    fonts = {
        "F": ImageFont.truetype(os.path.join(FONTS, "georgiab.ttf"), 150 * SS),
        "T": ImageFont.truetype(os.path.join(FONTS, "georgiab.ttf"), 112 * SS),
    }
    frames = []
    for f in range(TOTAL):
        im = draw_frame(f, pal, fonts)
        im.save(os.path.join(base, "part0", "%05d.png" % f), optimize=True)
        frames.append(im)
    frames[-1].save(os.path.join(base, "part1", "00000.png"), optimize=True)
    with open(os.path.join(base, "desc.txt"), "w", newline="\n") as fh:
        fh.write("%d %d %d\np 1 0 part0\np 0 0 part1\n" % (W, H, FPS))
    zp = os.path.join(base, "bootanimation.zip")
    with zipfile.ZipFile(zp, "w", zipfile.ZIP_STORED) as z:  # stored: Android requires no compression
        z.write(os.path.join(base, "desc.txt"), "desc.txt")
        for part in ("part0", "part1"):
            for fn in sorted(os.listdir(os.path.join(base, part))):
                z.write(os.path.join(base, part, fn), part + "/" + fn)
    small = [fr.resize((W // 3, H // 3), Image.LANCZOS) for fr in frames]
    small += [small[-1]] * 20  # hold the resting frame for one second in the preview
    small[0].save(os.path.join(out, "preview_%s.gif" % name), save_all=True, append_images=small[1:],
                  duration=1000 // FPS, loop=0, optimize=False)
    # contact sheet with key frames
    keys = [0, 12, 20, 30, 40, 48, 54, TOTAL - 1]
    sheet = Image.new("RGB", (4 * (W // 4), 2 * (H // 4)), pal["bg"])
    for i, k in enumerate(keys):
        t = frames[k].resize((W // 4, H // 4), Image.LANCZOS)
        sheet.paste(t, ((i % 4) * (W // 4), (i // 4) * (H // 4)))
    sheet.save(os.path.join(out, "frames_%s.png" % name))
    print(name, "ok:", os.path.getsize(zp) // 1024, "KB,", TOTAL, "quadros")


if __name__ == "__main__":
    out = sys.argv[1]
    for n, p in PALETTES.items():
        build(n, p, out)

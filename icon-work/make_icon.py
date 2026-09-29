#!/usr/bin/env python3
"""OMNI launcher icon: glowing neural node-ring 'O' on near-black. Master 1024 + mipmap densities."""
from PIL import Image, ImageDraw
import math, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.environ.get("ICON_WORK", os.path.join(ROOT, "icon-work"))
RES = os.environ.get("APK_RES", os.path.join(ROOT, "apk-build", "res"))
S = 1024
CX = CY = S // 2

violet = (124, 58, 237)
cyan = (34, 211, 238)

def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

img = Image.new("RGBA", (S, S), (6, 6, 9, 255))
px = img.load()

# radial background vignette: center #17171f -> edge #060609
for y in range(S):
    for x in range(S):
        d = math.hypot(x - CX, y - CY) / (S * 0.72)
        d = min(d, 1.0)
        r = int(23 - 17 * d)
        g = int(23 - 17 * d)
        b = int(31 - 22 * d)
        px[x, y] = (r, g, b, 255)

ov = Image.new("RGBA", (S, S), (0, 0, 0, 0))
dr = ImageDraw.Draw(ov)

N = 16
RING = 300
pts = []
for i in range(N):
    a = -math.pi / 2 + i * 2 * math.pi / N
    pts.append((CX + RING * math.cos(a), CY + RING * math.sin(a)))
cols = [lerp(violet, cyan, i / (N - 1)) for i in range(N)]

# faint outer orbit ring
dr.ellipse([CX - 400, CY - 400, CX + 400, CY + 400], outline=violet + (36,), width=10)

# links between consecutive nodes (under-glow pass then bright pass)
for w, alpha in ((26, 70), (10, 255)):
    for i in range(N):
        x1, y1 = pts[i]
        x2, y2 = pts[(i + 1) % N]
        c = lerp(cols[i], cols[(i + 1) % N], 0.5)
        dr.line([x1, y1, x2, y2], fill=c + (alpha,), width=w)

# node glows + cores
for (x, y), c in zip(pts, cols):
    for r, a in ((58, 36), (42, 70), (30, 120)):
        dr.ellipse([x - r, y - r, x + r, y + r], fill=c + (a,))
    dr.ellipse([x - 17, y - 17, x + 17, y + 17], fill=c + (255,))
    dr.ellipse([x - 7, y - 7, x + 7, y + 7], fill=(235, 245, 255, 255))

# subtle all-seeing core dot
for r, a in ((40, 30), (26, 60)):
    dr.ellipse([CX - r, CY - r, CX + r, CY + r], fill=cyan + (a,))
dr.ellipse([CX - 11, CY - 11, CX + 11, CY + 11], fill=(220, 250, 255, 255))

img = Image.alpha_composite(img, ov).convert("RGB")
os.makedirs(OUT, exist_ok=True)
img.save(f"{OUT}/omni-icon-1024.png")

dens = [("xxxhdpi", 192), ("xxhdpi", 144), ("xhdpi", 96), ("hdpi", 72), ("mdpi", 48)]
for name, size in dens:
    d = os.path.join(RES, f"mipmap-{name}")
    os.makedirs(d, exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(f"{d}/ic_launcher.png")
    print("wrote", d, "ic_launcher.png", size)
print("master:", f"{OUT}/omni-icon-1024.png")

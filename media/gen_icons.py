"""Generate PiDA icon assets from media/PiDA.png.

- Adaptive launcher foregrounds: cream background across the whole canvas
  (cropped naturally by the launcher mask), arch artwork cut out of the
  cream source and scaled so it fits the 66dp safe-zone circle.
- Widget icon: 192x192 arch on transparent, centered with padding.
"""
from PIL import Image, ImageDraw
import pathlib
import os
import numpy as np

SRC = pathlib.Path(__file__).resolve().parent / "pida.png"
RES = pathlib.Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"

im = Image.open(SRC).convert("RGBA")
w0, h0 = im.size
# Cut the cream background out (flood-fill from the border, incl. the arch
# opening at the bottom edge).
for xy in [(0, 0), (w0 - 1, 0), (0, h0 - 1), (w0 - 1, h0 - 1), (w0 // 2, h0 - 1)]:
    ImageDraw.floodfill(im, xy, (0, 0, 0, 0), thresh=40)
bbox = im.getbbox()
art = im.crop(bbox)
w, h = art.size
# Max opaque distance from the artwork's center: drives the safe-zone fit.
a = np.array(art)
ys, xs = np.nonzero(a[:, :, 3])
maxd = float(np.sqrt(((xs - w / 2) ** 2 + (ys - h / 2) ** 2).max()))
print("source:", im.size, "-> arch:", art.size, "maxd:", round(maxd, 1))

# --- Adaptive launcher foregrounds: cream background fills the canvas
# (the launcher mask crops it naturally), arch at first-iteration size ---
# (farthest opaque pixel on the 66dp safe-zone circle, centered).
densities = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
CREAM = (238, 236, 227, 255)
for name, canvas in densities.items():
    safe_r = canvas * 66 / 108 / 2  # safe-zone radius in px
    scale = safe_r / maxd
    nw, nh = max(1, round(w * scale)), max(1, round(h * scale))
    scaled = art.resize((nw, nh), Image.LANCZOS)
    fg = Image.new("RGBA", (canvas, canvas), CREAM)
    fg.paste(scaled, ((canvas - nw) // 2, (canvas - nh) // 2), scaled)
    out = os.path.join(RES, f"mipmap-{name}", "ic_launcher_foreground.png")
    fg.save(out)
    print("wrote", out, fg.size)

# --- Widget icon 192x192 ---
W = 192
pad = 0.85
scale = min(W * pad / w, W * pad / h)
nw, nh = max(1, round(w * scale)), max(1, round(h * scale))
scaled = art.resize((nw, nh), Image.LANCZOS)
wi = Image.new("RGBA", (W, W), (0, 0, 0, 0))
wi.paste(scaled, ((W - nw) // 2, (W - nh) // 2), scaled)
wi.save(os.path.join(RES, "drawable", "ic_widget_generic.png"))
print("wrote widget icon", wi.size)

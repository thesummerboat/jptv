import sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter
FONT = "/usr/share/fonts/opentype/inter/Inter-Black.otf"
S = 1024
img = Image.new("RGB", (S, S))
px = img.load()
top, bot = (24, 29, 39), (9, 11, 15)
for y in range(S):
    for x in range(S):
        t = x / S * 0.45 + y / S * 0.55
        px[x, y] = tuple(int(top[i] * (1 - t) + bot[i] * t) for i in range(3))
glow = Image.new("L", (S, S), 0)
ImageDraw.Draw(glow).ellipse((int(S * 0.35), int(S * 0.45), int(S * 1.4), int(S * 1.5)), fill=120)
glow = glow.filter(ImageFilter.GaussianBlur(150))
img = Image.composite(Image.new("RGB", (S, S), (242, 165, 65)), img, glow.point(lambda v: int(v * 0.55)))
d = ImageDraw.Draw(img)
font = ImageFont.truetype(FONT, 330)
for text, color, cy in [("JP", (244, 246, 249), 0.36), ("TV", (242, 165, 65), 0.66)]:
    b = d.textbbox((0, 0), text, font=font)
    w, h = b[2] - b[0], b[3] - b[1]
    d.text(((S - w) / 2 - b[0], S * cy - h / 2 - b[1]), text, font=font, fill=color)
# Rundade hörn som en launcher-ikon.
mask = Image.new("L", (S, S), 0)
ImageDraw.Draw(mask).rounded_rectangle((0, 0, S, S), radius=int(S * 0.22), fill=255)
out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
out.paste(img, (0, 0), mask)
dest = sys.argv[1]
for name, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    out.resize((size, size), Image.LANCZOS).save(f"{dest}/icon_{name}.png", optimize=True)
out.resize((256, 256), Image.LANCZOS).save(f"{dest}/icon_preview.png")
print("ok")

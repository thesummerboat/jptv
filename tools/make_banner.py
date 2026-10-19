import sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter

FONT = "/usr/share/fonts/opentype/inter/Inter-Black.otf"
W, H = 1280, 720  # arbetsstorlek, skalas ner

def render():
    img = Image.new("RGB", (W, H))
    px = img.load()
    # Diagonal grundton: något ljusare uppe till vänster.
    top = (24, 29, 39)
    bot = (9, 11, 15)
    for y in range(H):
        for x in range(W):
            t = (x / W * 0.45 + y / H * 0.55)
            px[x, y] = tuple(int(top[i] * (1 - t) + bot[i] * t) for i in range(3))
    # Varm glöd nere till höger.
    glow = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(glow)
    d.ellipse((int(W * 0.45), int(H * 0.35), int(W * 1.45), int(H * 1.6)), fill=120)
    glow = glow.filter(ImageFilter.GaussianBlur(170))
    orange = Image.new("RGB", (W, H), (242, 165, 65))
    img = Image.composite(orange, img, glow.point(lambda v: int(v * 0.55)))

    draw = ImageDraw.Draw(img)
    font = ImageFont.truetype(FONT, 330)
    parts = [("JP", (244, 246, 249)), ("TV", (242, 165, 65))]
    tracking = -14
    widths = [draw.textlength(t, font=font) for t, _ in parts]
    total = sum(widths) + tracking
    asc, desc = font.getmetrics()
    bbox = draw.textbbox((0, 0), "JPTV", font=font)
    text_h = bbox[3] - bbox[1]
    x = (W - total) / 2
    y = (H - text_h) / 2 - bbox[1] - 40
    for (t, color), w in zip(parts, widths):
        draw.text((x, y), t, font=font, fill=color)
        x += w + tracking
    # Orange linje under ordmärket.
    bar_w = total * 0.86
    bar_h = 26
    bx = (W - bar_w) / 2
    by = y + bbox[3] + 34
    draw.rounded_rectangle((bx, by, bx + bar_w, by + bar_h), radius=bar_h / 2, fill=(242, 165, 65))
    return img

base = render()
out = sys.argv[1]
for name, size in [("xhdpi", (320, 180)), ("xxhdpi", (480, 270)), ("xxxhdpi", (640, 360))]:
    base.resize(size, Image.LANCZOS).save(f"{out}/banner_{name}.png", optimize=True)
base.resize((960, 540), Image.LANCZOS).save(f"{out}/preview.png")
print("ok")

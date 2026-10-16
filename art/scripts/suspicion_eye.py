"""The suspicion eye shown on a heist (hud/suspicion_eye, suspicion_fill, suspicion_pupil, suspicion_glow), painted
at 4x the size they're drawn: an almond eye of dark iron lids in a tarnished-gold rim, the white that rises in it as
a watcher grows suspicious (drawn tinted, gold to amber to red), a slit pupil that turns toward them and a soft halo
for when they're sure. suspicion_eye.py <sprites/hud dir>"""
import sys, os
from PIL import Image, ImageDraw, ImageFilter

out = sys.argv[1]
os.makedirs(out, exist_ok=True)

S = 4          # pixels per GUI pixel
SS = 4         # supersampling while painting
W, H = 20, 12  # the eye's size on screen, in GUI pixels

OUT = (12, 11, 14, 235)
GOLD_L = (198, 162, 84, 255); GOLD = (152, 120, 58, 255); GOLD_D = (106, 82, 40, 255)
IRON_D = (38, 36, 42, 255)
WELL = (14, 12, 12, 225)


def almond(w, h, inset, pinch=0.85, lower=0.9):
    """The outline of an almond filling a w x h box, inset all round; pointed at the corners, the lower lid flatter."""
    cx, cy = w / 2, h / 2
    a, b = w / 2 - inset, h / 2 - inset
    top, bottom = [], []
    n = 96
    for i in range(n + 1):
        t = -1 + 2 * i / n
        y = b * max(0.0, 1 - t * t) ** pinch
        top.append((cx + t * a, cy - y))
        bottom.append((cx + t * a, cy + y * lower))
    return top + bottom[::-1]


def canvas(w, h):
    return Image.new('RGBA', (w * S * SS, h * S * SS), (0, 0, 0, 0))


def done(img, name):
    img = img.resize((img.width // SS, img.height // SS), Image.LANCZOS)
    img.save(f'{out}/{name}.png')


k = S * SS  # supersampled pixels per GUI pixel

# ---- suspicion_eye 20x12: outline, a gold rim, dark lids, three lashes over the top ----
eye = canvas(W, H)
d = ImageDraw.Draw(eye)
d.polygon(almond(W * k, H * k, 0), fill=OUT)
d.polygon(almond(W * k, H * k, 0.7 * k), fill=GOLD)
d.polygon(almond(W * k, H * k, 1.0 * k), fill=GOLD_D)
d.polygon(almond(W * k, H * k, 1.5 * k), fill=WELL)
# A light catch along the upper rim
rim = almond(W * k, H * k, 0.45 * k)
d.line(rim[20:77], fill=GOLD_L, width=int(0.35 * k))
# Lashes over the upper lid: short gold-dark strokes fanning up
for x0, lean in ((6.0, -0.9), (10.0, 0.0), (14.0, 0.9)):
    x = x0 * k
    d.line([(x, 1.1 * k), (x + lean * k, 0.0)], fill=GOLD_D, width=int(0.55 * k))
done(eye, 'suspicion_eye')

# ---- suspicion_fill 20x12: the eye's white, brightest in the middle, to be tinted as it rises ----
fill = canvas(W, H)
mask = Image.new('L', fill.size, 0)
ImageDraw.Draw(mask).polygon(almond(W * k, H * k, 1.5 * k), fill=255)
shade = Image.new('L', fill.size, 0)
sd = ImageDraw.Draw(shade)
cx, cy = fill.width / 2, fill.height / 2
for i in range(40, 0, -1):
    f = i / 40
    v = int(150 + 105 * (1 - f) ** 1.5)
    sd.ellipse([cx - f * fill.width * 0.55, cy - f * fill.height * 0.75, cx + f * fill.width * 0.55, cy + f * fill.height * 0.75], fill=v)
white = Image.new('RGBA', fill.size, (255, 255, 255, 255))
white.putalpha(Image.composite(shade, Image.new('L', fill.size, 0), mask))
done(white, 'suspicion_fill')

# ---- suspicion_pupil 4x8: a dark slit with a pale glint ----
PW, PH = 4, 8
pupil = canvas(PW, PH)
pd = ImageDraw.Draw(pupil)
pd.ellipse([0.2 * k, 0.1 * k, (PW - 0.2) * k, (PH - 0.1) * k], fill=(20, 12, 10, 255))
pd.ellipse([1.0 * k, 0.6 * k, (PW - 1.0) * k, (PH - 0.6) * k], fill=(6, 4, 4, 255))
pd.ellipse([1.2 * k, 1.4 * k, 2.0 * k, 2.4 * k], fill=(240, 228, 200, 220))
done(pupil, 'suspicion_pupil')

# ---- suspicion_glow 28x20: a soft halo round the eye ----
GW, GH = 28, 20
glow = canvas(GW, GH)
gd = ImageDraw.Draw(glow)
ox, oy = (GW - W) / 2 * k, (GH - H) / 2 * k
gd.polygon([(x + ox, y + oy) for x, y in almond(W * k, H * k, -0.5 * k)], fill=(255, 255, 255, 200))
glow = glow.filter(ImageFilter.GaussianBlur(2.2 * k))
done(glow, 'suspicion_glow')

import sys
from PIL import Image
out = sys.argv[1]
# A crown shard: a jagged gold band (bottom) with a violet crystal spike rising from it
rows = [
 "................",
 "..........o.....",
 ".........oLo....",
 "........oLLo....",
 "........oLMo....",
 ".......oLMMo....",
 "......oLMMDo....",
 "......oLMDDo....",
 ".....oLMMDo.....",
 ".....oLMDDo.....",
 "....oGgGGgGo....",
 "...oGYgGYgGGo...",
 "...oggGgGGgo....",
 "....ooooGgo.....",
 "........oo......",
 "................",
]
C = {'o': (38, 20, 46), 'L': (236, 214, 255), 'M': (176, 128, 230), 'D': (112, 68, 168),
     'G': (226, 182, 64), 'g': (168, 120, 34), 'Y': (255, 238, 150)}
img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
for y, r in enumerate(rows):
    for x, c in enumerate(r):
        if c in C: img.putpixel((x, y), C[c] + (255,))
img.save(out)

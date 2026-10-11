import sys, json
sys.path.insert(0, sys.argv[0].rsplit('/', 1)[0])
from modelkit import *
from PIL import Image
import numpy as np

def C(o, s, name, **kw): return Cube(o, s, None, name=name, **kw)

head = Part('head', cubes=[C((-4, -8, -4), (8, 8, 8), 'face')], children=[
    Part('hat', cubes=[C((-4, -8, -4), (8, 8, 8), 'hat', inflate=0.5)]),
    Part('wren_hat', cubes=[C((-6.5, -8.5, -6.5), (13, 1, 13), 'brim'), C((-4.5, -11.5, -4.5), (9, 3, 9), 'crown')],
         children=[Part('feather', (4.5, -10, 1), (0, 0, 20), cubes=[C((0, -4, 0), (1, 5, 1), 'feather')])]),
    Part('tobin_beard', cubes=[C((-4, -2, -4.75), (8, 6, 1), 'beard')]),
    Part('ilsa_hood', cubes=[C((-4.5, -8.5, -4.5), (9, 9, 9), 'ihood')]),
    Part('maren_hood', cubes=[C((-4.6, -8.6, -4.6), (9, 9, 9), 'mhood', inflate=0.1), C((-2, -8, 4.4), (4, 4, 2), 'mpoint')]),
    # Pip's floppy beret, slumped to one side, with a long plume stuck in the band
    Part('pip_cap', (0, -8, 0), (0, 0, -12), cubes=[C((-5, -2, -5), (10, 2, 10), 'beret'), C((-4.5, -0.5, -4.5), (9, 1, 9), 'capband'),
                                                    C((3.5, -7, 1), (1, 6, 1), 'plume')]),
])
body = Part('body', cubes=[C((-4, 0, -2), (8, 12, 4), 'torso')], children=[
    Part('wren_bag', cubes=[C((-3, 3, 2), (6, 6, 2), 'satchel'), C((-4.5, 1.5, 2.5), (9, 2, 2), 'maps')]),
    Part('ilsa_robe', cubes=[C((-4.5, 10, -2.5), (9, 12, 5), 'robe')]),
    Part('maren_cloak', (0, 0, 2), (5, 0, 0), cubes=[C((-5, 0, 0), (10, 21, 1), 'cloak')]),
    Part('maren_quiver', (2.5, 1, 3.2), (0, 0, 25), cubes=[C((-1, -1, 0), (2, 10, 2), 'quiver'), C((-1, -3, 0.25), (2, 2, 1.5), 'fletch')]),
    # A lute slung across the back, neck over the left shoulder
    Part('pip_lute', (0, 7, 2.6), (0, 0, -35), cubes=[C((-2.5, -2, 0), (5, 6, 2), 'lutebody'), C((-0.5, -10, 0.5), (1, 8, 1), 'luteneck'),
                                                      C((-1, -12, 0.25), (2, 2, 1.5), 'lutehead')]),
])
right_arm = Part('right_arm', (-5, 2, 0), cubes=[C((-3, -2, -2), (4, 12, 4), 'rarm')],
                 children=[Part('tobin_rpaul', cubes=[C((-3.5, -2.5, -2.5), (5, 3, 5), 'rpaul')])])
left_arm = Part('left_arm', (5, 2, 0), cubes=[C((-1, -2, -2), (4, 12, 4), 'larm')],
                children=[Part('tobin_lpaul', cubes=[C((-1.5, -2.5, -2.5), (5, 3, 5), 'lpaul')])])
right_leg = Part('right_leg', (-1.9, 12, 0), cubes=[C((-2, 0, -2), (4, 12, 4), 'rleg')])
left_leg = Part('left_leg', (1.9, 12, 0), cubes=[C((-2, 0, -2), (4, 12, 4), 'lleg')])

model = Model('story_npc', 128, 128, [head, body, right_arm, left_arm, right_leg, left_leg])
model.pack()

CL = (0, 0, 0, 0)
def limb(rows, P, default):  # 4-wide limb painted the same on all four sides
    return grid({'front': rows, 'back': rows, 'left': rows, 'right': rows}, P, default=default)

# ---------------- Wren Ashdown ----------------
SK = (234, 192, 160); HR = (164, 78, 40); HRD = (122, 54, 28); TEAL = (40, 112, 122); TEALD = (28, 82, 92)
BRASS = (210, 168, 62); CREAM = (234, 222, 192); BELT = (92, 58, 30); TROU = (54, 54, 72); BOOT = (86, 54, 32)
FELT = (112, 74, 42); FELTD = (84, 54, 30); BAND = (156, 40, 40); PARCH = (232, 216, 172)
PW = {'H': HR, 'h': HRD, 'S': SK, 'W': (250, 250, 250), 'e': (52, 140, 78), 'f': (206, 142, 108), 'n': (214, 168, 138),
      'm': (176, 92, 82), 'T': TEAL, 't': TEALD, 'B': BRASS, 'C': CREAM, 'L': BELT, 'D': TROU, 'O': BOOT, 'F': FELT,
      'g': FELTD, 'R': BAND, 'P': PARCH, 'K': (60, 40, 24)}
wren = {
    'face': grid({
        'front': ["HHHHHHHH", "HHHHHHhH", "HhSSSHHH", "SSSSSSSS", "SWeSSeWS", "SfSnnSfS", "SSSmmSSS", "SSSSSSSS"],
        'right': ["HHHHHHHH", "HHHHHHHH", "HHHHHhHH", "HHHHSSSS", "HHHSSSSS", "HHHSSSSS", "hHHSSSSS", "hHSSSSSS"],
        'left': ["HHHHHHHH", "HHHHHHHH", "HHhHHHHH", "SSSSHHHH", "SSSSSHHH", "SSSSSHHH", "SSSSSHHh", "SSSSSShh"],
        'back': ["HHHHHHHH", "HHhHHHHH", "HHHHHhHH", "HHHHHHHH", "HhHHHHHH", "HHHHHHhH", "hHHhHHHh", "hhHhhHhh"],
        'top': ["HHHHHHHH"] * 8,
    }, PW, default=fill(SK)),
    'brim': grid({'top': ["F"] * 13, 'bottom': ["g"] * 13}, PW, default=fill(FELTD, 4)),
    'crown': grid({'front': ["FFFFFFFFF", "FFFFFFFFF", "RRRRRRRRR"], 'back': ["FFFFFFFFF", "FFFFFFFFF", "RRRRRRRRR"],
                   'left': ["FFFFFFFFF", "FFFFFFFFF", "RRRRRRRRR"], 'right': ["FFFFFFFFF", "FFFFFFFFF", "RRRRRRRRR"]},
                  PW, default=fill(FELT, 5)),
    'feather': grid({'front': ["R", "R", "R", "R", "B"], 'back': ["R", "R", "R", "R", "B"]}, PW, default=fill((200, 70, 40), 10)),
    'torso': grid({
        'front': ["CCTTTTCC", "TCCTTCCT", "TTTBTTTT", "TTTTTTTT", "TTTBTTTT", "TTTTTTTT", "TTTBTTTT", "LLLLLLLL",
                  "TTTBTTTT", "TTTTTTTT", "tTTTTTTt", "tttttttt"],
        'back': ["TTTTTTTT"] * 7 + ["LLLLLLLL"] + ["TTTTTTTT"] * 3 + ["tttttttt"],
        'left': ["TTTT"] * 7 + ["LLLL"] + ["TTTT"] * 3 + ["tttt"], 'right': ["TTTT"] * 7 + ["LLLL"] + ["TTTT"] * 3 + ["tttt"],
    }, PW, default=fill(TEAL)),
    'rarm': limb(["TTTT"] * 8 + ["tttt", "CCCC", "SSSS", "SSSS"], PW, fill(SK)),
    'larm': limb(["TTTT"] * 8 + ["tttt", "CCCC", "SSSS", "SSSS"], PW, fill(SK)),
    'rleg': limb(["DDDD"] * 8 + ["OOOO", "OOOO", "OOOO", "KKKK"], PW, fill(BOOT)),
    'lleg': limb(["DDDD"] * 8 + ["OOOO", "OOOO", "OOOO", "KKKK"], PW, fill(BOOT)),
    'satchel': grid({'back': ["KKKKKK", "LLLLLL", "LLBBLL", "LLLLLL", "LLLLLL", "KKKKKK"]}, PW, default=fill(BELT, 5)),
    'maps': grid({'front': ["PPPRPPPPP", "PPPRPPPPP"], 'back': ["PPPPPRPPP", "PPPPPRPPP"], 'top': ["PPPRPPPPP", "PPPRPPPPP"],
                  'left': ["gP", "Pg"], 'right': ["gP", "Pg"]}, PW, default=fill(PARCH, 6)),
}

# ---------------- Tobin Greaves ----------------
TS = (206, 152, 118); GR = (190, 190, 186); GRD = (140, 140, 138); MAIL = (128, 128, 138); APRON = (104, 64, 36)
STL = (156, 156, 168); BRN = (70, 50, 36)
PT = {'S': TS, 'G': GR, 'g': (90, 90, 90), 'P': (24, 20, 20), 'W': (245, 245, 245), 'b': (70, 110, 170), 'n': (186, 132, 100),
      'B': GR, '.': CL, 'M': MAIL, 'A': APRON, 'a': (80, 48, 26), 'L': (60, 40, 24), 'I': STL, 'i': (196, 196, 206),
      'D': BRN, 'O': (40, 30, 26), 'r': (176, 110, 90), 'X': (238, 170, 140)}
tobin = {
    'face': grid({
        'front': ["SSSSSXSS", "SSrSSSSS", "GSSrSSSG", "GggSSggG", "GPPSSWbG", "GPSSnSSG", "GSSnnSSG", "GSSSSSSG"],
        'right': ["SSSSSSSS", "SSSSSSSS", "GGGGSSSS", "GGGGSSSS", "PPPPPPPP", "GGGGSSSS", "GGGGGSSS", "GGGGGGSS"],
        'left': ["SSSSSSSS", "SSSSSSSS", "SSSSGGGG", "SSSSGGGG", "SSPPPPPP", "SSSSGGGG", "SSSGGGGG", "SSGGGGGG"],
        'back': ["SSSSSSSS", "SSSSSSSS", "GGGGGGGG", "GGGGGGGG", "PPPPPPPP", "GGGGGGGG", "GGGGGGGG", "GGGGGGGG"],
        'top': ["SSSSSSSS", "SSSXSSSS", "SSSSSSSS", "SSSSSSXS", "SSSSSSSS", "SXSSSSSS", "SSSSSSSS", "SSSSSSSS"],
    }, PT, default=fill(TS)),
    'beard': grid({'front': ["BB....BB", "BgBBBBgB", "BBBBBBBB", "BBgBBgBB", ".BBBBBB.", "..BBBB.."],
                   'back': ["BBBBBBBB"] * 4 + [".BBBBBB.", "..BBBB.."]}, PT, default=fill(GR, 6)),
    'torso': grid({
        'front': ["MMMMMMMM", "MAMMMMAM", "MAAAAAAM", "MAAAAAAM", "MAAAAAAM", "MAAaaAAM", "MAAAAAAM", "LLLLLLLL",
                  "MAAAAAAM", "MAAAAAAM", "MAAAAAAM", "MaaaaaaM"],
        'back': ["MMMMMMMM", "MAMMMMAM", "MMAMMAMM", "MMMAAMMM", "MMMMMMMM", "MMMMMMMM", "MMMMMMMM", "LLLLLLLL",
                 "MMMMMMMM", "MMMMMMMM", "MMMMMMMM", "MMMMMMMM"],
    }, PT, default=fill(MAIL, 14)),
    'rarm': limb(["MMMM", "MMMM", "MMMM", "SSSS", "SSSS", "SSSS", "LLLL", "aaaa", "LLLL", "SSSS", "SSSS", "SSSS"], PT, fill(TS)),
    'larm': limb(["MMMM", "MMMM", "MMMM", "SSSS", "SSSS", "SSSS", "LLLL", "aaaa", "LLLL", "SSSS", "SSSS", "SSSS"], PT, fill(TS)),
    'rpaul': grid({'top': ["IIIII", "IiIiI", "IIIII", "IiIiI", "IIIII"]}, PT, default=fill(STL, 6)),
    'lpaul': grid({'top': ["IIIII", "IiIiI", "IIIII", "IiIiI", "IIIII"]}, PT, default=fill(STL, 6)),
    'rleg': limb(["DDDD"] * 7 + ["OOOO"] * 5, PT, fill(BRN)),
    'lleg': limb(["DDDD"] * 7 + ["OOOO"] * 5, PT, fill(BRN)),
}

# ---------------- Sister Ilsa Venn ----------------
IS = (242, 220, 204); WH = (236, 236, 242); WHD = (204, 204, 214); VI = (124, 70, 172); VID = (88, 48, 128); GD = (220, 178, 70)
PI = {'S': IS, 'D': (44, 34, 34), 'W': (250, 250, 250), 'v': VI, 'n': (226, 198, 182), 'm': (206, 140, 150),
      'H': WH, 'h': WHD, 'V': VI, 'u': VID, '.': CL, 'Y': GD, 'O': (90, 70, 60)}
ilsa = {
    'face': grid({'front': ["DDDDDDDD", "DDDDDDDD", "DSSSSSSD", "SSSSSSSS", "SWvSSvWS", "SSSnnSSS", "SSSmmSSS", "SSSSSSSS"]},
                 PI, default=fill((44, 34, 34), 4)),
    'ihood': grid({
        'front': ["HHHHHHHHH", "HVVVVVVVH", "HV.....VH", "HV.....VH", "HV.....VH", "HV.....VH", "HV.....VH", "HV.....VH", "HV.....VH"],
        'back': ["HHHHHHHHH", "HHHHHHHHH", "HHHHVHHHH", "HHHHVHHHH", "HHHHVHHHH", "HHHHVHHHH", "HHHHVHHHH", "HHHHHHHHH", "hhhhhhhhh"],
        'bottom': ["........."] * 9,
    }, PI, default=fill(WH, 4)),
    'torso': grid({
        'front': ["HHHuuHHH", "HHuHHuHH", "HuHHHHuH", "HHHYYHHH", "HHHYYHHH", "HHHHHHHH", "HHHHHHHH", "VVVVVVVV",
                  "HHHHHHHH", "HHHHHHHH", "HHHHHHHH", "HHHHHHHH"],
        'back': ["HHHHHHHH"] * 7 + ["VVVVVVVV"] + ["HHHHHHHH"] * 4,
        'left': ["HHHH"] * 7 + ["VVVV"] + ["HHHH"] * 4, 'right': ["HHHH"] * 7 + ["VVVV"] + ["HHHH"] * 4,
    }, PI, default=fill(WH)),
    'robe': grid({
        'front': ["HHHHVHHHH"] * 10 + ["VVVVVVVVV", "uuuuuuuuu"], 'back': ["HHHHHHHHH"] * 10 + ["VVVVVVVVV", "uuuuuuuuu"],
        'left': ["HHHHH"] * 10 + ["VVVVV", "uuuuu"], 'right': ["HHHHH"] * 10 + ["VVVVV", "uuuuu"],
    }, PI, default=fill(WHD, 4)),
    'rarm': limb(["HHHH"] * 8 + ["VVVV", "HHHH", "SSSS", "SSSS"], PI, fill(IS)),
    'larm': limb(["HHHH"] * 8 + ["VVVV", "HHHH", "SSSS", "SSSS"], PI, fill(IS)),
    'rleg': limb(["hhhh"] * 9 + ["OOOO"] * 3, PI, fill(WHD)),
    'lleg': limb(["hhhh"] * 9 + ["OOOO"] * 3, PI, fill(WHD)),
}

# ---------------- Maren, the Last Warden ----------------
GLOW = (130, 255, 226, 255)
MS = (150, 156, 154); CLOAK = (80, 98, 88); CLOAKD = (52, 66, 58); LEA = (74, 82, 52); LEAD = (54, 58, 36); STRAP = (70, 46, 30)
PM = {'D': (40, 44, 44), 's': (176, 182, 180), 'G': GLOW, 'n': (136, 142, 140), 'F': (70, 92, 80), 'f': (58, 76, 66), 'C': CLOAK,
      'c': CLOAKD, '.': CL, 'L': LEA, 'l': LEAD, 'T': STRAP, 'Y': (196, 204, 210), 'B': (44, 34, 28), 'W': (226, 226, 220)}
maren = {
    'face': grid({'front': ["DDDDDDDD", "DDDDDDDD", "DssssssD", "ssssssss", "sGGssGGs", "sssnnsss", "FFFFFFFF", "FfFFFFfF"]},
                 PM, default=fill((40, 44, 44), 4)),
    'mhood': grid({
        'front': ["CCCCCCCCC", "CcccccccC", "Cc.....cC", "Cc.....cC", "Cc.....cC", "Cc.....cC", "Cc.....cC", "Cc.....cC", "Cc.....cC"],
        'bottom': ["........."] * 9,
    }, PM, default=fill(CLOAK, 5)),
    'mpoint': fill(CLOAK, 5),
    'cloak': grid({'back': ["CCCCCCCCCC"] * 17 + ["cCCcCCcCCc", "C.CC.CC.CC", ".C..C..C.C", "..C....C.."],
                   'front': ["cccccccccc"] * 18 + ["c.cc.cc.cc", ".c..c..c.c", "..c....c.."],
                   'left': ["C"] * 18 + ["C", ".", "."], 'right': ["C"] * 18 + ["C", ".", "."]}, PM, default=fill(CLOAKD, 4)),
    'quiver': fill(STRAP, 6),
    'fletch': grid({'top': ["WW"]}, PM, default=fill((226, 226, 220), 8)),
    'torso': grid({
        'front': ["cLLYYLLc", "LTLLLLLL", "LLTLLLLL", "LLLTLLLL", "LLLLTLLL", "LLLLLTLL", "LLLLLLTL", "TTTTTTTT",
                  "LLLLLLLL", "LLLLLLLL", "llllllll", "llllllll"],
        'back': ["LLLLLLLL"] * 7 + ["TTTTTTTT"] + ["llllllll"] * 4,
    }, PM, default=fill(LEA, 5)),
    'rarm': limb(["CCCC"] * 5 + ["LLLL", "TTTT", "LLLL", "TTTT", "BBBB", "BBBB", "BBBB"], PM, fill(LEA)),
    'larm': limb(["CCCC"] * 5 + ["LLLL", "TTTT", "LLLL", "TTTT", "BBBB", "BBBB", "BBBB"], PM, fill(LEA)),
    'rleg': limb(["llll"] * 7 + ["BBBB"] * 5, PM, fill(LEAD)),
    'lleg': limb(["llll"] * 7 + ["BBBB"] * 5, PM, fill(LEAD)),
}

# ---------------- Pip Larkspur, bard ----------------
PS = (240, 200, 168); PHR = (232, 196, 96); PHRD = (196, 156, 64); CRIM = (176, 38, 52); CRIMD = (126, 26, 40)
SAF = (236, 186, 52); SAFD = (196, 146, 34); FOR = (58, 112, 64); FORD = (40, 82, 46); WOOD = (152, 96, 46); WOODD = (108, 66, 32)
PP = {'H': PHR, 'h': PHRD, 'S': PS, 'W': (250, 250, 250), 'e': (60, 100, 180), 'f': (234, 160, 140), 'n': (222, 178, 148),
      'm': (190, 84, 84), 'R': CRIM, 'r': CRIMD, 'Y': SAF, 'y': SAFD, 'G': FOR, 'g': FORD, 'L': (82, 52, 30), 'O': (66, 44, 28),
      'K': (40, 28, 20), 'w': WOOD, 'd': WOODD, 'c': (230, 220, 200), '.': CL, 'F': (250, 250, 250), 'B': (196, 160, 70)}
pip = {
    'face': grid({
        'front': ["HHHHHHHH", "HHHhHHHH", "HSSSSSHH", "SSSSSSSS", "SWeSSeWS", "SfSnnSfS", "SSSmmSSS", "SSSSSSSS"],
        'right': ["HHHHHHHH", "HHHHHHHH", "HHHHhHHH", "HHHHSSSS", "HHHSSSSS", "HHHSSSSS", "HhHSSSSS", "hHSSSSSS"],
        'left': ["HHHHHHHH", "HHHHHHHH", "HHHhHHHH", "SSSSHHHH", "SSSSSHHH", "SSSSSHHH", "SSSSSHhH", "SSSSSShh"],
        'back': ["HHHHHHHH", "HHhHHHHH", "HHHHHhHH", "HHHHHHHH", "HhHHHHhH", "HHHHHHHH", "hHHhHHHh", "hHhhHhhh"],
        'top': ["HHHHHHHH"] * 8,
    }, PP, default=fill(PS)),
    'beret': grid({'top': ["RRRRRRRRRR", "RRRRRRRRRR", "RRRrRRRRRR", "RRRRRRRRrR", "RRRRRRRRRR", "RrRRRRRRRR", "RRRRRRrRRR",
                           "RRRRRRRRRR", "RRRRrRRRRR", "RRRRRRRRRR"]}, PP, default=fill(CRIMD, 4)),
    'capband': grid({'front': ["YYYYYYYYY"], 'back': ["YYYYYYYYY"], 'left': ["YYYYYYYYY"], 'right': ["YYYYYYYYY"]}, PP, default=fill(SAFD, 4)),
    'plume': grid({'front': ["F", "F", "F", "c", "c", "Y"], 'back': ["F", "F", "F", "c", "c", "Y"]}, PP, default=fill((240, 240, 236), 6)),
    'torso': grid({
        'front': ["RRYYYYRR", "RRRYYRRR", "YYYRRYYY", "YYYRRYYY", "RRRYYRRR", "RRRYYRRR", "YYYRRYYY", "LLLBLLLL",
                  "RRRYYYYY", "RRRYYYYY", "rrrYYYYY", "rrryyyyy"],
        'back': ["RRRRYYYY"] * 7 + ["LLLLLLLL"] + ["RRRRYYYY"] * 3 + ["rrrryyyy"],
        'left': ["YYYY"] * 7 + ["LLLL"] + ["YYYY"] * 3 + ["yyyy"], 'right': ["RRRR"] * 7 + ["LLLL"] + ["RRRR"] * 3 + ["rrrr"],
    }, PP, default=fill(SAF)),
    'rarm': limb(["RRRR"] * 6 + ["YYYY", "RRRR", "rrrr", "cccc", "SSSS", "SSSS"], PP, fill(PS)),
    'larm': limb(["YYYY"] * 6 + ["RRRR", "YYYY", "yyyy", "cccc", "SSSS", "SSSS"], PP, fill(PS)),
    'rleg': limb(["GGGG"] * 7 + ["gggg", "OOOO", "OOOO", "OOOO", "KKKK"], PP, fill(FORD)),
    'lleg': limb(["GGGG"] * 7 + ["gggg", "OOOO", "OOOO", "OOOO", "KKKK"], PP, fill(FORD)),
    'lutebody': grid({'back': ["dwwwd", "wwwww", "wwKww", "wKKKw", "wwKww", "dwwwd"],
                      'front': ["ddddd", "dwwwd", "dwwwd", "dwwwd", "dwwwd", "ddddd"]}, PP, default=fill(WOODD, 4)),
    'luteneck': grid({'back': ["d", "w", "c", "w", "c", "w", "c", "w"]}, PP, default=fill(WOODD, 4)),
    'lutehead': fill(WOODD, 5),
}

out, prev, art = sys.argv[1], sys.argv[2], sys.argv[3]
shown = {
    'wren': {'wren_hat', 'wren_bag'}, 'tobin': {'tobin_beard', 'tobin_rpaul', 'tobin_lpaul'},
    'ilsa': {'ilsa_hood', 'ilsa_robe'}, 'maren': {'maren_hood', 'maren_cloak', 'maren_quiver'},
    'pip': {'pip_cap', 'pip_lute'},
}
optional = set().union(*shown.values())
sheet = Image.new('RGBA', (1280, 640))
for i, (name, painters) in enumerate([('wren', wren), ('tobin', tobin), ('ilsa', ilsa), ('maren', maren), ('pip', pip)]):
    tex = model.paint({c.name: painters.get(c.name) for c in model.all_cubes()})
    arr = np.array(tex)
    glow_mask = np.all(arr == np.array(GLOW), axis=-1)
    glow = np.zeros_like(arr); glow[glow_mask] = arr[glow_mask]
    if name == 'maren':
        # The Last Warden is barely there: everything but her eyes is faintly see-through
        a = arr[..., 3]; arr[..., 3] = np.where((a > 0) & ~glow_mask, 200, a)
        tex = Image.fromarray(arr, 'RGBA')
        Image.fromarray(glow, 'RGBA').save(f'{out}/textures/entity/character/{name}_glow.png')
    tex.save(f'{out}/textures/entity/character/{name}.png')
    # Preview with only this character's parts
    parts = {p.name: p for p in model.all_parts()}
    saved = {n: parts[n].cubes for n in optional}
    for n in optional:
        if n not in shown[name]: parts[n].cubes = []
    hidden_children = {}
    feather = parts['feather']
    if name != 'wren': hidden_children['feather'] = feather.cubes; feather.cubes = []
    g = Image.fromarray(glow, 'RGBA')
    sheet.paste(model.render(tex, yaw=25, glow=g), (i * 256, 0))
    sheet.paste(model.render(tex, yaw=205, glow=g).resize((192, 240)), (i * 256 + 32, 330))
    for n, cubes in saved.items(): parts[n].cubes = cubes
    for n, cubes in hidden_children.items(): parts[n].cubes = cubes
    if name == 'wren':
        json.dump(model.bbmodel(tex, 'story_npc'), open(f'{art}/story_npc.bbmodel', 'w'))
sheet.save(f'{prev}/npc_preview.png')
open(f'{prev}/story_npc.kt.txt', 'w').write(model.kotlin())

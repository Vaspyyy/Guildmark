"""An exact Python port of `sea/Tentacle.kt` (the Maelgrasp's arm: the curve through a point, the joints walked out along it, the coil past
the end of the curve and the ripple running up it), so the previews can drive the arm's model with the joints the game would give it. The
port keeps the Kotlin's order of operations; `check()` compiles the real Tentacle.kt and compares the two on a spread of arms:

    python3 -B art/scripts/sea_tentacle.py

Units are blocks, y up, z the way the arm faces, x to its right (the arm's own frame, see Tentacle.kt).
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

SEGMENTS = 12
LENGTHS = [1.4, 1.3, 1.2, 1.12, 1.04, 0.96, 0.9, 0.84, 0.78, 0.72, 0.66, 0.6]
RADII = [0.62, 0.56, 0.5, 0.45, 0.4, 0.36, 0.32, 0.28, 0.24, 0.2, 0.16, 0.12, 0.07]
TOTAL = sum(LENGTHS)
ROOT_DEPTH = 2.4
SAMPLES = 40
HANDLE_MAX = 9.0
FROM_HANDLE = 0.8
LEAN = 0.25
CURL_SHARE = 0.4
REACH = 0.97
COIL_MIN, COIL_MAX = 0.38, 0.8


def _dist(ax, ay, az, bx, by, bz):
    dx, dy, dz = bx - ax, by - ay, bz - az
    return math.sqrt(dx * dx + dy * dy + dz * dz)


def _smooth(a, b, x):
    t = min(max((x - a) / (b - a), 0.0), 1.0)
    return t * t * (3.0 - 2.0 * t)


def _sample(curve, px, py, pz, ax, ay, az, h):
    """The curve with handles [h] long, written into [curve] (SAMPLES + 1 points of three floats), and its length."""
    oy = -ROOT_DEPTH
    lx, lz = px, pz
    ll = math.sqrt(lx * lx + lz * lz)
    if ll < 1.0e-3:
        lx, lz = 0.0, 1.0
    else:
        lx /= ll
        lz /= ll
    p1x = lx * h * LEAN
    p1y = oy + h
    p1z = lz * h * LEAN
    p2x = px + ax * h * FROM_HANDLE
    p2y = py + ay * h * FROM_HANDLE
    p2z = pz + az * h * FROM_HANDLE
    length = 0.0
    for k in range(SAMPLES + 1):
        t = k / float(SAMPLES)
        u = 1.0 - t
        b0 = u * u * u
        b1 = 3.0 * u * u * t
        b2 = 3.0 * u * t * t
        b3 = t * t * t
        x = b1 * p1x + b2 * p2x + b3 * px
        y = b0 * oy + b1 * p1y + b2 * p2y + b3 * py
        z = b1 * p1z + b2 * p2z + b3 * pz
        curve[k * 3], curve[k * 3 + 1], curve[k * 3 + 2] = x, y, z
        if k > 0:
            length += _dist(curve[k * 3 - 3], curve[k * 3 - 2], curve[k * 3 - 1], x, y, z)
    return length


def solve(tx, ty, tz, fx, fy, fz, curl, ripple, time, phase):
    """The arm's joints (a list of (SEGMENTS + 1) * 3 floats) reaching for (tx, ty, tz) and arriving travelling away from (fx, fy, fz)."""
    c = min(max(curl, 0.0), 1.0)
    oy = -ROOT_DEPTH
    px, py, pz = tx, ty, tz
    dl = math.sqrt(px * px + (py - oy) * (py - oy) + pz * pz)
    if dl > TOTAL * REACH:
        k = TOTAL * REACH / dl
        px *= k
        py = oy + (py - oy) * k
        pz *= k
    ax, ay, az = fx, fy, fz
    al = math.sqrt(ax * ax + ay * ay + az * az)
    if al < 1.0e-4:
        ax, ay, az = 0.0, 1.0, 0.0
    else:
        ax /= al
        ay /= al
        az /= al
    want = TOTAL * (1.0 - CURL_SHARE * c)
    curve = [0.0] * ((SAMPLES + 1) * 3)
    raw = [0.0] * ((SEGMENTS + 1) * 3)
    if _sample(curve, px, py, pz, ax, ay, az, 0.0) >= want:
        h = 0.0
    elif _sample(curve, px, py, pz, ax, ay, az, HANDLE_MAX) <= want:
        h = HANDLE_MAX
    else:
        lo, hi = 0.0, HANDLE_MAX
        for _ in range(16):
            mid = (lo + hi) * 0.5
            if _sample(curve, px, py, pz, ax, ay, az, mid) < want:
                lo = mid
            else:
                hi = mid
        h = (lo + hi) * 0.5
    _sample(curve, px, py, pz, ax, ay, az, h)

    e = SAMPLES * 3
    ex = curve[e] - curve[e - 3]
    ey = curve[e + 1] - curve[e - 2]
    ez = curve[e + 2] - curve[e - 1]
    el = max(math.sqrt(ex * ex + ey * ey + ez * ez), 1.0e-5)
    ex /= el
    ey /= el
    ez /= el
    w = _smooth(0.6, 0.95, abs(ey))
    bx = 0.0
    by = -(1.0 - w)
    bz = w
    bd = bx * ex + by * ey + bz * ez
    bx -= bd * ex
    by -= bd * ey
    bz -= bd * ez
    bl = math.sqrt(bx * bx + by * by + bz * bz)
    if bl < 1.0e-3:
        bx, by, bz = 1.0, 0.0, 0.0
        bl = 1.0
    bx /= bl
    by /= bl
    bz /= bl

    raw[0], raw[1], raw[2] = curve[0], curve[1], curve[2]
    at = 0
    coil = -1.0
    radius = 0.5
    cx = cy = cz = 0.0
    for i in range(1, SEGMENTS + 1):
        step = LENGTHS[i - 1]
        qx, qy, qz = raw[i * 3 - 3], raw[i * 3 - 2], raw[i * 3 - 1]
        placed = False
        if coil < 0.0:
            while at < SAMPLES:
                nx, ny, nz = curve[at * 3 + 3], curve[at * 3 + 4], curve[at * 3 + 5]
                far = _dist(qx, qy, qz, nx, ny, nz)
                if far >= step:
                    sx, sy, sz = curve[at * 3], curve[at * 3 + 1], curve[at * 3 + 2]
                    lo, hi = 0.0, 1.0
                    for _ in range(12):
                        mid = (lo + hi) * 0.5
                        d = _dist(qx, qy, qz, sx + (nx - sx) * mid, sy + (ny - sy) * mid, sz + (nz - sz) * mid)
                        if d < step:
                            lo = mid
                        else:
                            hi = mid
                    f = (lo + hi) * 0.5
                    raw[i * 3] = sx + (nx - sx) * f
                    raw[i * 3 + 1] = sy + (ny - sy) * f
                    raw[i * 3 + 2] = sz + (nz - sz) * f
                    placed = True
                    break
                at += 1
            if not placed:
                rest = 0.0
                for k in range(i - 1, SEGMENTS):
                    rest += LENGTHS[k]
                radius = min(max(rest / (1.7 * math.pi), COIL_MIN), COIL_MAX)
                coil = 0.0
                cx, cy, cz = qx, qy, qz
        if not placed:
            coil += 2.0 * math.asin(min(1.0, step / (2.0 * radius)))
            raw[i * 3] = cx + ex * radius * math.sin(coil) + bx * radius * (1.0 - math.cos(coil))
            raw[i * 3 + 1] = cy + ey * radius * math.sin(coil) + by * radius * (1.0 - math.cos(coil))
            raw[i * 3 + 2] = cz + ez * radius * math.sin(coil) + bz * radius * (1.0 - math.cos(coil))

    out = [0.0] * ((SEGMENTS + 1) * 3)
    out[0], out[1], out[2] = raw[0], raw[1], raw[2]
    run = 0.0
    for i in range(1, SEGMENTS + 1):
        run += LENGTHS[i - 1]
        s = run / TOTAL
        amp = ripple * (s ** 1.4)
        dx = dy = dz = 0.0
        if amp > 1.0e-4:
            tx1 = raw[i * 3] - raw[i * 3 - 3]
            ty1 = raw[i * 3 + 1] - raw[i * 3 - 2]
            tz1 = raw[i * 3 + 2] - raw[i * 3 - 1]
            tl = max(math.sqrt(tx1 * tx1 + ty1 * ty1 + tz1 * tz1), 1.0e-5)
            tx1 /= tl
            ty1 /= tl
            tz1 /= tl
            nx = tz1
            nz = -tx1
            nl = math.sqrt(nx * nx + nz * nz)
            if nl < 1.0e-3:
                nx, nz, nl = 1.0, 0.0, 1.0
            nx /= nl
            nz /= nl
            mx = ty1 * nz
            my = tz1 * nx - tx1 * nz
            mz = -ty1 * nx
            w1 = math.sin(6.2 * s - 0.21 * time + phase)
            w2 = math.sin(4.1 * s - 0.16 * time + phase * 1.7 + 1.3)
            dx = nx * amp * w1 + mx * amp * 0.6 * w2
            dy = my * amp * 0.6 * w2
            dz = nz * amp * w1 + mz * amp * 0.6 * w2
        wx = raw[i * 3] + dx - out[i * 3 - 3]
        wy = raw[i * 3 + 1] + dy - out[i * 3 - 2]
        wz = raw[i * 3 + 2] + dz - out[i * 3 - 1]
        wl = max(math.sqrt(wx * wx + wy * wy + wz * wz), 1.0e-5)
        k = LENGTHS[i - 1] / wl
        out[i * 3] = out[i * 3 - 3] + wx * k
        out[i * 3 + 1] = out[i * 3 - 2] + wy * k
        out[i * 3 + 2] = out[i * 3 - 1] + wz * k
    return out


def grip(joints):
    """The middle of the coil at the tip: the mean of the last four joints (what `Shape.grip` gives), in blocks."""
    n = len(joints) // 3
    pts = [joints[i * 3:i * 3 + 3] for i in range(n - 4, n)]
    return tuple(sum(p[k] for p in pts) / 4.0 for k in range(3))


# ---- the check against the Kotlin

CASES = [
    # target (x, y, z), arriving from (x, y, z), curl, ripple, time, phase
    (0.0, 1.2, 4.6, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0),                       # a slam onto the deck from above
    (0.0, 1.2, 4.6, 0.0, 1.0, 0.0, 0.8, 0.3, 40.0, 1.3),
    (-3.0, 1.3, 3.0, -1.0, 0.1, 0.2, 0.3, 0.2, 12.0, 0.7),                    # a sweep across it from the side
    (0.0, 6.0, 2.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0),                       # reared up
    (1.0, 4.0, 4.0, 0.0, 1.0, 0.2, 1.0, 0.5, 100.0, 2.1),                     # coiled tight round something
    (0.0, 0.0, 30.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0),                      # out of reach
    (0.0, 9.0, 0.0, 0.0, 1.0, 0.0, 0.5, 0.4, 7.0, 0.2),                       # straight up
    (0.0, -1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),                      # nowhere much
    (2.5, 2.0, 2.5, 0.0, 0.0, 1.0, 0.0, 0.1, 3.0, 0.0),
    (-4.0, 1.5, 1.0, 0.7, 0.7, 0.0, 0.1, 0.2, 20.0, 3.0),
    (0.5, 3.0, 5.5, 0.0, -1.0, 0.0, 0.9, 0.35, 66.0, 1.0),
]

HARNESS = '''
import io.github.vaspyyy.guildmark.sea.Tentacle

fun main() {
    val shape = Tentacle.Shape()
    while (true) {
        val line = readLine() ?: break
        val v = line.trim().split(" ").map { it.toFloat() }
        if (v.size < 10) continue
        Tentacle.solve(shape, v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9])
        println(shape.joints.joinToString(" ") { it.toString() })
    }
}
'''


def check(work, tentacle_kt=None):
    """Compiles Tentacle.kt and a tiny harness, solves the CASES and, over a few hundred random arms, compares every joint with this port.
    Returns the largest difference seen (blocks)."""
    import random
    sys.path.insert(0, HERE)
    import sea_kit
    repo = os.path.normpath(os.path.join(HERE, '..', '..'))
    tentacle_kt = tentacle_kt or os.path.join(repo, 'src', 'main', 'kotlin', 'io', 'github', 'vaspyyy', 'guildmark', 'sea', 'Tentacle.kt')
    os.makedirs(work, exist_ok=True)
    h = os.path.join(work, 'TentacleHarness.kt')
    with open(h, 'w') as f:
        f.write(HARNESS)
    rng = random.Random(7)
    cases = list(CASES)
    for _ in range(300):
        cases.append((rng.uniform(-6, 6), rng.uniform(-1, 8), rng.uniform(-6, 8), rng.uniform(-1, 1), rng.uniform(-1, 1), rng.uniform(-1, 1),
                      rng.random(), rng.uniform(0, 0.6), rng.uniform(0, 200), rng.uniform(0, 6.28)))
    text = '\n'.join(' '.join(repr(float(v)) for v in c) for c in cases) + '\n'
    out, _ = sea_kit.run_kotlin([tentacle_kt, h], work, 'TentacleHarnessKt', text)
    rows = [[float(v) for v in ln.split()] for ln in out.strip().splitlines()]
    worst = 0.0
    for c, kt in zip(cases, rows):
        py = solve(*c)
        worst = max(worst, max(abs(a - b) for a, b in zip(py, kt)))
    return worst, len(rows)


if __name__ == '__main__':
    scratch = sys.argv[1] if len(sys.argv) > 1 else '/tmp/sea_tentacle_check'
    w, n = check(scratch)
    print(f'{n} arms solved by the Kotlin and by this port: the joints differ by at most {w:.2e} blocks')
    sys.exit(0 if w < 2e-3 else 1)

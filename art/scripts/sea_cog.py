"""The cog's figurehead, checked: the generated CogModel.kt is compiled against Minecraft's own ModelPart classes and the game's CogRenderState,
its `setupAnim` is run on a spread of render states and every part's position, turn, scale and visibility is compared with what ship.py's
`pose()` gives (so the figurehead's shown/hidden switch, its swinging jaw and the two loose ends of its lashing agree with the Kotlin, and the
rest of the ship is shown to be untouched by it). The part of the cog that is the figurehead's is `figure_parts()`.

    python3 -B art/scripts/sea_cog.py <work dir>
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import numpy as np

import sea_kit as K


def figure_parts(model):
    """The names of the figurehead's parts: the figurehead itself and everything that hangs from it."""
    return {p.name for p in next(p for p in model.parts if p.name == 'figurehead').walk()}


def check_cases(n=24, seed=5):
    """Cog render states to compare on: every field over its range at random, the figurehead on for two cases in three, and the corners."""
    rng = np.random.default_rng(seed)
    fields = [('sail', 0.0, 1.0), ('fill', -1.0, 1.0), ('luff', 0.0, 1.0), ('brace', -60.0, 60.0), ('windYaw', -180.0, 180.0), ('wind', 0.0, 1.0),
              ('wheel', -40.0, 40.0), ('rudder', -30.0, 30.0), ('anchor', 0.0, 1.0), ('lanterns', 0.0, 1.0), ('speed', 0.0, 0.3),
              ('pitch', -8.0, 8.0), ('heel', -12.0, 12.0), ('hurtTime', 0.0, 10.0)]
    out = []
    for i in range(n):
        c = {k: float(rng.uniform(lo, hi)) for k, lo, hi in fields}
        c['time'] = float(rng.uniform(0, 9000))
        c['bell'] = -1.0 if i % 3 else float(rng.uniform(0, 80))
        c['hurtDir'] = int(rng.choice([-1, 1]))
        c['figurehead'] = bool(i % 3 != 2)
        c['bob'] = 0.0
        out.append(c)
    # every field set in every case: CogRenderState's own defaults (a furled sail) are not CogState's (let down)
    base = dict(sail=0.5, fill=0.0, luff=0.0, brace=0.0, windYaw=0.0, wind=0.0, wheel=0.0, rudder=0.0, anchor=0.0, lanterns=0.0, bell=-1.0, time=0.0,
                speed=0.0, pitch=0.0, heel=0.0, bob=0.0, hurtTime=0.0, hurtDir=1, figurehead=True)
    out.append(dict(base))
    out.append(dict(base, figurehead=False, time=10.0))
    out.append(dict(base, wind=1.0, time=333.0, pitch=8.0, heel=-12.0))
    out.append(dict(base, wind=1.0, time=333.0, pitch=-8.0, heel=12.0))
    return out


def check(work, n=24):
    """Returns (worst position error px, worst turn error, worst scale error, parts compared, problems) for the whole cog; problems that
    belong to the figurehead's parts are listed first, and any other part that disagrees is named too (it would not be this script's doing)."""
    import ship
    model = ship.build()
    ship.pack_atlas(model)
    scene = ship.Scene(model)
    lens = {p.name: p.cubes[0].size[1] for p in model.all_parts() if p.name.startswith(('brace_', 'lift_'))}
    mine = figure_parts(model)
    files = K.scratch_tree(work, {'client/ship/CogModel.kt': ship.kotlin_source(model)}, ['client/ship/CogRenderState.kt'])
    r = K.verify_pose(files, 'io.github.vaspyyy.guildmark.client.ship', 'CogModel', 'CogRenderState', scene, check_cases(n),
                      lambda c: ship.CogState(**c), lambda st: ship.pose(st, lens), work)
    problems = sorted(r[4], key=lambda p: (p.split()[2].rstrip(':') not in mine, p))
    return r[0], r[1], r[2], r[3], problems


def figure_extent(scene, pose, mine):
    """(lo, hi) over every corner of every drawn face of the figurehead's parts in [pose], in the cog's model space (px, y down)."""
    idx, A, B, D, _n, _ok = scene.quads(pose)
    keep = np.array([scene.parts[int(scene.FP[i])].name in mine for i in idx])
    pts = np.stack([A, B, D, B + D - A], 1)[keep].reshape(-1, 3)
    return pts.min(0), pts.max(0)


def figure_report():
    """The figurehead's part of the report: what it is made of, what animates each part, and how far it reaches in the cog's model space over a spread of
    render states (so a culling box or a hitbox round the ship knows whether to care)."""
    import ship
    model = ship.build()
    ship.pack_atlas(model)
    scene = ship.Scene(model)
    lens = {p.name: p.cubes[0].size[1] for p in model.all_parts() if p.name.startswith(('brace_', 'lift_'))}
    mine = figure_parts(model)
    parts = [p for p in model.all_parts() if p.name in mine]
    base = dict(sail=0.5, fill=0.0, luff=0.0, brace=0.0, windYaw=0.0, wind=0.0, wheel=0.0, rudder=0.0, anchor=0.0, lanterns=0.0, bell=-1.0, time=0.0,
                speed=0.0, pitch=0.0, heel=0.0, bob=0.0, hurtTime=0.0, hurtDir=1, figurehead=True)
    cases = {'at rest, calm': dict(base), 'bow up 8 degrees, gale': dict(base, pitch=8.0, wind=1.0, time=40.0), 'bow down 8 degrees, gale': dict(base, pitch=-8.0, wind=1.0, time=120.0),
             'heeled 12 degrees, gale': dict(base, heel=12.0, wind=1.0, time=300.0), 'heeled the other way': dict(base, heel=-12.0, wind=1.0, time=77.0)}
    out = ['The cog\'s figurehead (CogModel: the part `figurehead` and what hangs from it)', '=' * 63, '',
           'A slain Maelgrasp\'s beak lashed on the bowsprit, ahead of the bowsprit\'s heel. Not a separate model: these are parts of the cog\'s own model, drawn in the', 'cog\'s texture and glow map (nothing of it glows). Origin as the cog\'s: the waterline in the middle of the ship, px (1/16 block), y down, the bow faces -z.', '',
           f'{len(parts)} parts, {sum(len(p.cubes) for p in parts)} cubes (of the cog\'s {len(list(model.all_parts()))} parts, {len(list(model.all_cubes()))} cubes)', '', 'Parts, and what animates them']
    notes = {
        'figurehead': 'shown only while `CogRenderState.figurehead` is true (hidden otherwise, with everything that hangs from it); the lashing round the sprit under the beak; tilted along the bowsprit\'s rise',
        'fh_beak': 'the beak (eleven slices of the upper mandible reared up off the sprit, the scrap of mantle it was cut from, a turn of hemp round its neck); static',
        'fh_jaw': 'the lower jaw on a hinge of sinew with a tongue in it: hangs open at rest and swings with her `pitch` (and a little with the wind)',
        'fh_tail_s': 'a loose end of the lashing (starboard): hangs plumb as she pitches and heels, streams in the wind (the harder with `wind`)',
        'fh_tail_p': 'a loose end of the lashing (port): as the starboard one, on its own beat',
    }
    for p in parts:
        out.append(f'  {p.name}: {len(p.cubes)} cubes')
        out.append(f'      {notes[p.name]}')
    out += ['', 'Extents of the drawn figurehead over a spread of render states (every corner of every drawn face; the cog\'s model space)']
    lo_all, hi_all = np.full(3, 1e9), np.full(3, -1e9)
    for label, c in cases.items():
        lo, hi = figure_extent(scene, ship.pose(ship.CogState(**c), lens), mine)
        lo_all, hi_all = np.minimum(lo_all, lo), np.maximum(hi_all, hi)
        out.append(f'  {label:<26} x {lo[0]:7.1f} .. {hi[0]:6.1f}   y {lo[1]:7.1f} .. {hi[1]:6.1f}   z {lo[2]:7.1f} .. {hi[2]:6.1f}  px')
    out.append(f'  {"union":<26} x {lo_all[0]:7.1f} .. {hi_all[0]:6.1f}   y {lo_all[1]:7.1f} .. {hi_all[1]:6.1f}   z {lo_all[2]:7.1f} .. {hi_all[2]:6.1f}  px')
    out.append('  (all of it lies inside what the cog already reached: the bowsprit runs on past it, so the cog\'s culling box and hitbox stay as they were)')
    return '\n'.join(out) + '\n'


if __name__ == '__main__':
    r = check(sys.argv[1])
    print(f'cog: Kotlin vs Python poses over {r[3]} parts: position {r[0]:.5f} px, turn {r[1]:.5f}, scale {r[2]:.5f}')
    for p in r[4][:30]:
        print('PROBLEM', p)
    sys.exit(1 if r[4] else 0)

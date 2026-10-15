"""
Compiles every program of Guildmark's shader pack the way iris_lite runs it (compatibility built-ins turned into
core inputs), for each dimension, with and without Distant Horizons, and with each switch flipped from its
default, so a typo in a rarely used branch shows up here instead of in the game.

    python3 -I art/shaderkit/check.py src/main/resources/shaderpack/Guildmark/shaders [--quick]
"""

import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import moderngl  # noqa: E402

import iris_lite  # noqa: E402

DH_PRELUDE_VSH = iris_lite.DH_PRELUDE_VSH
DH_DEFINES = iris_lite.DH_DEFINES


def switches(shaders):
    """Every on/off option in lib/settings.glsl and whether it's on by default."""
    out = {}
    text = open(os.path.join(shaders, "lib", "settings.glsl"), encoding="utf-8").read()
    for m in re.finditer(r"^\s*(//)?\s*#define\s+([A-Z_][A-Z0-9_]*)\s*(//.*)?$", text, flags=re.M):
        out[m.group(2)] = m.group(1) is None
    return out


def compile_all(ctx, shaders, world, defines, overrides):
    folder = os.path.join(shaders, world) if world else shaders
    failures = []
    names = sorted({f[:-4] for f in os.listdir(folder) if f.endswith(".fsh")})
    for name in names:
        src = {}
        for stage in ("vsh", "fsh"):
            text = iris_lite.apply_option_overrides(iris_lite.preprocess(os.path.join(folder, name + "." + stage), shaders), overrides)
            d = dict(defines)
            if name.startswith("dh_"):
                d.update(DH_DEFINES)
            core = iris_lite.to_core(text, stage, d)
            if name.startswith("dh_") and stage == "vsh":
                core = core.replace("#line 1", DH_PRELUDE_VSH + "#line 1", 1)
            src[stage] = core
        try:
            prog = ctx.program(vertex_shader=src["vsh"], fragment_shader=src["fsh"])
            prog.release()
        except Exception as e:
            failures.append("%s/%s: %s" % (world or "root", name, str(e).strip()[:1500]))
    return failures, len(names)


def main():
    shaders = sys.argv[1]
    quick = "--quick" in sys.argv
    ctx = moderngl.create_standalone_context(backend="egl", require=450)
    base = {"MC_VERSION": "260300", "IRIS_VERSION": "11107", "IS_IRIS": None}
    failures = []
    count = 0
    for world in ("", "world-1", "world1"):
        for dh in (False, True):
            d = dict(base)
            if dh:
                d["DISTANT_HORIZONS"] = None
            f, n = compile_all(ctx, shaders, world, d, {})
            failures += f
            count += n
    if not quick:
        for name, on in switches(shaders).items():
            f, n = compile_all(ctx, shaders, "", dict(base, DISTANT_HORIZONS=None), {name: not on})
            failures += ["[%s %s] %s" % (name, "off" if on else "on", x) for x in f]
            count += n
        for opt, val in (("SHADOW_FILTER", "1"), ("SHADOW_FILTER", "3")):
            f, n = compile_all(ctx, shaders, "", dict(base), {opt: val})
            failures += ["[%s=%s] %s" % (opt, val, x) for x in f]
            count += n
    for x in failures:
        print(x)
        print()
    print("%d program builds, %d failed" % (count, len(failures)))
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()

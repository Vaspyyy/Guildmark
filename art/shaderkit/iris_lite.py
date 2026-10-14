"""
A small stand-in for Iris, enough to run Guildmark's shader pack offline and take pictures of it.

It reads the pack the way Iris does (programs, fallbacks, #include, buffer directives, RENDERTARGETS,
shaders.properties blend and scale lines), turns the compatibility-profile built-ins the pack uses into core-profile
inputs, and runs the passes in Iris's order over a scene from scene.py: shadow, gbuffers (opaque), deferred,
gbuffers (translucent), composite, final. Buffers ping-pong like Iris's: a composite pass reads the current copy and
writes the other, and gbuffers draw into whatever copy is current.

It is not Iris: only the programs, uniforms and directives Guildmark's pack uses are covered, and the uniforms are
worked out the way Iris documents them, not taken from the game.
"""

import math
import os
import re
import subprocess

import moderngl
import numpy as np

# ---------------------------------------------------------------------------------------------------------------
# Reading the pack


def preprocess(path, root, seen=None):
    """The file with every #include spliced in; '/x' is from the shaders folder, 'x' from the file's own folder."""
    seen = seen or []
    if path in seen:
        raise RuntimeError("include loop: " + " -> ".join(seen + [path]))
    with open(path, encoding="utf-8") as f:
        lines = f.read().split("\n")
    out = []
    for line in lines:
        m = re.match(r'\s*#include\s+"([^"]+)"', line)
        if m:
            name = m.group(1)
            inc = os.path.join(root, name[1:]) if name.startswith("/") else os.path.join(os.path.dirname(path), name)
            out.append(preprocess(os.path.normpath(inc), root, seen + [path]))
        else:
            out.append(line)
    return "\n".join(out)


def parse_properties(path, defines):
    """shaders.properties after its own #if/#ifdef lines, as key -> value."""
    props = {}
    if not os.path.exists(path):
        return props
    stack = [True]
    text = open(path, encoding="utf-8").read().replace("\\\n", " ")
    for raw in text.split("\n"):
        line = raw.strip()
        if line.startswith("#ifdef"):
            stack.append(stack[-1] and line.split()[1] in defines)
            continue
        if line.startswith("#ifndef"):
            stack.append(stack[-1] and line.split()[1] not in defines)
            continue
        if line.startswith("#else"):
            prev = stack.pop()
            stack.append(stack[-1] and not prev)
            continue
        if line.startswith("#endif"):
            stack.pop()
            continue
        if not stack[-1] or not line or line.startswith("#"):
            continue
        if "=" in line:
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    return props


GBUFFER_FALLBACKS = {
    "gbuffers_basic": None,
    "gbuffers_line": "gbuffers_basic",
    "gbuffers_textured": "gbuffers_basic",
    "gbuffers_textured_lit": "gbuffers_textured",
    "gbuffers_skybasic": "gbuffers_basic",
    "gbuffers_skytextured": "gbuffers_textured",
    "gbuffers_clouds": "gbuffers_textured",
    "gbuffers_terrain": "gbuffers_textured_lit",
    "gbuffers_terrain_solid": "gbuffers_terrain",
    "gbuffers_terrain_cutout": "gbuffers_terrain",
    "gbuffers_damagedblock": "gbuffers_terrain",
    "gbuffers_block": "gbuffers_terrain",
    "gbuffers_beaconbeam": "gbuffers_textured",
    "gbuffers_entities": "gbuffers_textured_lit",
    "gbuffers_armor_glint": "gbuffers_textured",
    "gbuffers_spidereyes": "gbuffers_textured",
    "gbuffers_hand": "gbuffers_textured_lit",
    "gbuffers_weather": "gbuffers_textured_lit",
    "gbuffers_water": "gbuffers_terrain",
    "gbuffers_hand_water": "gbuffers_hand",
    "gbuffers_particles": "gbuffers_textured_lit",
    "gbuffers_lightning": "gbuffers_entities",
    "dh_terrain": None,
    "dh_water": None,
    "dh_shadow": None,
    "shadow": None,
}

VERTEX_PRELUDE = """
in vec3 iris_Position;
in vec4 iris_Color;
in vec2 iris_UV0;
in vec2 iris_UV2;
in vec3 iris_Normal;
uniform mat4 iris_ModelViewMatrix;
uniform mat4 iris_ProjectionMatrix;
uniform mat3 iris_NormalMatrix;
uniform mat4 iris_TextureMatrix0;
uniform mat4 iris_TextureMatrix1;
#define gl_Vertex vec4(iris_Position, 1.0)
#define gl_Color iris_Color
#define gl_MultiTexCoord0 vec4(iris_UV0, 0.0, 1.0)
#define gl_MultiTexCoord1 vec4(iris_UV2, 0.0, 1.0)
#define gl_MultiTexCoord2 vec4(iris_UV2, 0.0, 1.0)
#define gl_Normal iris_Normal
#define gl_ModelViewMatrix iris_ModelViewMatrix
#define gl_ProjectionMatrix iris_ProjectionMatrix
#define gl_ModelViewProjectionMatrix (iris_ProjectionMatrix * iris_ModelViewMatrix)
#define gl_NormalMatrix iris_NormalMatrix
#define ftransform() (iris_ProjectionMatrix * (iris_ModelViewMatrix * vec4(iris_Position, 1.0)))
"""

FRAGMENT_PRELUDE = """
uniform mat4 iris_ModelViewMatrix;
uniform mat4 iris_ProjectionMatrix;
#define gl_ModelViewMatrix iris_ModelViewMatrix
#define gl_ProjectionMatrix iris_ProjectionMatrix
"""


def to_core(source, stage, defines):
    """Compatibility built-ins -> core inputs, with Iris's standard defines added after #version."""
    lines = source.split("\n")
    vi = next((i for i, l in enumerate(lines) if l.strip().startswith("#version")), None)
    if vi is None:
        raise RuntimeError("no #version line")
    body = "\n".join(lines[vi + 1:])
    body = re.sub(r"gl_TextureMatrix\s*\[\s*0\s*\]", "iris_TextureMatrix0", body)
    body = re.sub(r"gl_TextureMatrix\s*\[\s*[12]\s*\]", "iris_TextureMatrix1", body)
    body = re.sub(r"\btexture2D\s*\(", "texture(", body)
    body = re.sub(r"\btexture2DLod\s*\(", "textureLod(", body)
    if stage == "vsh":
        body = re.sub(r"^\s*attribute\s", "in ", body, flags=re.M)
        body = re.sub(r"^\s*varying\s", "out ", body, flags=re.M)
    else:
        body = re.sub(r"^\s*varying\s", "in ", body, flags=re.M)
    head = ["#version 450 core"]
    for k, v in defines.items():
        head.append("#define %s %s" % (k, v) if v is not None else "#define %s" % k)
    head.append(VERTEX_PRELUDE if stage == "vsh" else FRAGMENT_PRELUDE)
    head.append("#line 1")
    return "\n".join(head) + "\n" + body


def apply_option_overrides(source, overrides):
    """Settings the way Iris changes them: the value of an option's #define line, or the line commented out."""
    for name, value in overrides.items():
        if value is False:
            source = re.sub(r"^(\s*)#define\s+%s\b" % name, r"\1//#define %s" % name, source, flags=re.M)
        elif value is True:
            source = re.sub(r"^(\s*)//\s*#define\s+%s\b" % name, r"\1#define %s" % name, source, flags=re.M)
        else:
            source = re.sub(r"^(\s*#define\s+%s\s+)\S+" % name, r"\g<1>%s" % value, source, flags=re.M)
    return source


def active_source(source, defines):
    """The source as the preprocessor leaves it (comments kept), which is what Iris reads its directives from."""
    lines = [l for l in source.split("\n") if not re.match(r"\s*#\s*(version|extension)\b", l)]
    args = ["cpp", "-P", "-C", "-undef", "-nostdinc", "-w"]
    for k, v in defines.items():
        args.append("-D%s=%s" % (k, v) if v is not None else "-D%s" % k)
    try:
        done = subprocess.run(args, input="\n".join(lines), capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired):
        return source
    return done.stdout if done.returncode == 0 else source


def render_targets(fsh):
    """The last RENDERTARGETS (or DRAWBUFFERS) comment, as OptiFine and Iris take it."""
    found = list(re.finditer(r"/\*\s*RENDERTARGETS\s*:\s*([0-9,\s]+)\*/", fsh))
    if found:
        return [int(x) for x in found[-1].group(1).replace(" ", "").split(",") if x]
    found = list(re.finditer(r"/\*\s*DRAWBUFFERS\s*:\s*([0-9]+)\s*\*/", fsh))
    if found:
        return [int(c) for c in found[-1].group(1)]
    return [0]


FORMATS = {
    "RGBA8": (4, "f1"), "RGB8": (4, "f1"), "RG8": (2, "f1"), "R8": (1, "f1"),
    "RGBA16": (4, "nu2"), "RGB16": (4, "nu2"), "RG16": (2, "nu2"), "R16": (1, "nu2"),
    "RGBA16F": (4, "f2"), "RGB16F": (4, "f2"), "RG16F": (2, "f2"), "R16F": (1, "f2"),
    "RGBA32F": (4, "f4"), "RGB32F": (4, "f4"), "RG32F": (2, "f4"), "R32F": (1, "f4"),
    "R11F_G11F_B10F": (4, "f2"), "RGB10_A2": (4, "f2"),
}


def directives(sources):
    """Buffer formats, clears and the shadow constants from every program's source, as Iris collects them."""
    d = {"formats": {}, "clear": {}, "clearColor": {}, "consts": {}}
    for src in sources:
        for m in re.finditer(r"const\s+int\s+(colortex\d+|shadowcolor\d)Format\s*=\s*(\w+)\s*;", src):
            d["formats"][m.group(1)] = m.group(2)
        for m in re.finditer(r"const\s+bool\s+(colortex\d+|shadowcolor\d)Clear\s*=\s*(true|false)\s*;", src):
            d["clear"][m.group(1)] = m.group(2) == "true"
        for m in re.finditer(r"const\s+vec4\s+(colortex\d+|shadowcolor\d)ClearColor\s*=\s*vec4\s*\(([^)]*)\)\s*;", src):
            vals = [float(x) for x in m.group(2).split(",")]
            d["clearColor"][m.group(1)] = vals * 4 if len(vals) == 1 else vals
        for name in ("shadowMapResolution", "noiseTextureResolution"):
            m = re.search(r"const\s+int\s+%s\s*=\s*(\d+)\s*;" % name, src)
            if m:
                d["consts"][name] = int(m.group(1))
        for name in ("shadowDistance", "sunPathRotation", "shadowDistanceRenderMul", "shadowIntervalSize", "ambientOcclusionLevel"):
            m = re.search(r"const\s+float\s+%s\s*=\s*(-?[\d.]+)\s*;" % name, src)
            if m:
                d["consts"][name] = float(m.group(1))
    return d


# ---------------------------------------------------------------------------------------------------------------
# Matrices, as Minecraft and Iris build them


def perspective(fov_y_deg, aspect, near, far):
    f = 1.0 / math.tan(math.radians(fov_y_deg) / 2)
    m = np.zeros((4, 4), dtype=np.float64)
    m[0, 0] = f / aspect
    m[1, 1] = f
    m[2, 2] = (far + near) / (near - far)
    m[2, 3] = 2 * far * near / (near - far)
    m[3, 2] = -1
    return m


def ortho(l, r, b, t, n, f):
    m = np.identity(4)
    m[0, 0] = 2 / (r - l)
    m[1, 1] = 2 / (t - b)
    m[2, 2] = -2 / (f - n)
    m[0, 3] = -(r + l) / (r - l)
    m[1, 3] = -(t + b) / (t - b)
    m[2, 3] = -(f + n) / (f - n)
    return m


def rot_x(deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    m = np.identity(4)
    m[1, 1], m[1, 2], m[2, 1], m[2, 2] = c, -s, s, c
    return m


def rot_y(deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    m = np.identity(4)
    m[0, 0], m[0, 2], m[2, 0], m[2, 2] = c, s, -s, c
    return m


def rot_z(deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    m = np.identity(4)
    m[0, 0], m[0, 1], m[1, 0], m[1, 1] = c, -s, s, c
    return m


def translate(x, y, z):
    m = np.identity(4)
    m[0, 3], m[1, 3], m[2, 3] = x, y, z
    return m


def sky_angle(world_time):
    """Minecraft's celestial angle: 0 at noon, 0.5 at midnight, eased around dawn and dusk."""
    d = ((world_time / 24000.0) - 0.25) % 1.0
    e = 0.5 - math.cos(d * math.pi) / 2.0
    return (d * 2.0 + e) / 3.0


def glmat(m):
    """numpy row-major -> GL column-major bytes."""
    return np.asarray(m, dtype="f4").T.copy().tobytes()


# ---------------------------------------------------------------------------------------------------------------
# The pipeline


class Buffer:
    """A colortex: two copies that composite passes swap between, and which one is current."""

    def __init__(self, ctx, size, fmt):
        comps, dtype = FORMATS.get(fmt, (4, "f1"))
        self.fmt = fmt
        self.tex = [ctx.texture(size, comps, dtype=dtype) for _ in range(2)]
        for t in self.tex:
            t.filter = (moderngl.LINEAR, moderngl.LINEAR)
            t.repeat_x = False
            t.repeat_y = False
        self.cur = 0

    @property
    def current(self):
        return self.tex[self.cur]

    @property
    def other(self):
        return self.tex[1 - self.cur]


class Pipeline:
    def __init__(self, ctx, shaders_dir, size, world="", option_overrides=None, extra_defines=None, verbose=False):
        self.ctx = ctx
        self.root = shaders_dir
        self.world = world
        self.size = size
        self.verbose = verbose
        self.overrides = option_overrides or {}
        self.defines = {"MC_VERSION": "260300", "IRIS_VERSION": "11107", "IS_IRIS": None,
                        "MC_GL_VERSION": "450", "MC_GLSL_VERSION": "450", "MC_OS_LINUX": None,
                        "MC_RENDER_QUALITY": "1.0", "MC_SHADOW_QUALITY": "1.0"}
        self.defines.update(extra_defines or {})
        self.props = parse_properties(os.path.join(shaders_dir, "shaders.properties"), self.defines)
        self.folder = os.path.join(shaders_dir, world) if world else shaders_dir
        self._programs = {}
        all_sources = []
        for f in sorted(os.listdir(self.folder)):
            if f.endswith(".vsh") or f.endswith(".fsh"):
                all_sources.append(self._source(os.path.join(self.folder, f)))
        self.dir = directives(all_sources)
        self.consts = self.dir["consts"]
        w, h = size
        self.buffers = {}
        for i in range(16):
            name = "colortex%d" % i
            self.buffers[i] = Buffer(ctx, size, self.dir["formats"].get(name, "RGBA8"))
        self.depth = ctx.depth_texture(size)
        self.depth1 = ctx.depth_texture(size)
        self.depth2 = ctx.depth_texture(size)
        for t in (self.depth, self.depth1, self.depth2):
            t.repeat_x = t.repeat_y = False
        res = self.consts.get("shadowMapResolution", 2048)
        self.shadow_res = res
        self.shadow0 = ctx.depth_texture((res, res))
        self.shadow1 = ctx.depth_texture((res, res))
        sfmt = FORMATS.get(self.dir["formats"].get("shadowcolor0", "RGBA8"), (4, "f1"))
        self.shadowcolor0 = ctx.texture((res, res), sfmt[0], dtype=sfmt[1])
        self.shadowcolor1 = ctx.texture((res, res), sfmt[0], dtype=sfmt[1])
        for t in (self.shadow0, self.shadow1, self.shadowcolor0, self.shadowcolor1):
            t.repeat_x = t.repeat_y = False
        # The pack asks for nearest filtering (shadowtex0Nearest and the rest) and compares depths itself
        sources = "\n".join(all_sources)
        for name, t in (("shadowtex0", self.shadow0), ("shadowtex1", self.shadow1),
                        ("shadowcolor0", self.shadowcolor0), ("shadowcolor1", self.shadowcolor1)):
            if re.search(r"const\s+bool\s+%sNearest\s*=\s*true" % name, sources):
                t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        nres = self.consts.get("noiseTextureResolution", 256)
        rng = np.random.default_rng(1)
        self.noise = ctx.texture((nres, nres), 4, (rng.random((nres, nres, 4)) * 255).astype("u1").tobytes())
        self.noise.filter = (moderngl.LINEAR, moderngl.LINEAR)
        self.out = ctx.texture(size, 4, dtype="f1")
        self.out_fbo = ctx.framebuffer([self.out])
        quad = np.array([0, 0, 1, 0, 0, 1, 1, 1], dtype="f4")
        self.quad_vbo = ctx.buffer(quad.tobytes())
        self.copy_prog = ctx.program(
            vertex_shader="#version 450 core\nin vec2 p; out vec2 uv; void main(){ uv=p; gl_Position=vec4(p*2.0-1.0,0.0,1.0);}",
            fragment_shader="#version 450 core\nin vec2 uv; uniform sampler2D src; void main(){ gl_FragDepth = texture(src, uv).r; }")
        self.copy_vao = ctx.vertex_array(self.copy_prog, [(self.quad_vbo, "2f", "p")])
        self.fbo_cache = {}
        self.scene_textures = {}
        self.on_pass = None  # called with each pass's name after it runs, to look at the buffers in between

    # -- programs ------------------------------------------------------------------------------------------------

    def _source(self, path):
        return apply_option_overrides(preprocess(path, self.root), self.overrides)

    def program(self, name):
        """The program for name, or the one it falls back to; None when the chain ends without one."""
        if name in self._programs:
            return self._programs[name]
        chosen = name
        while chosen is not None and not os.path.exists(os.path.join(self.folder, chosen + ".fsh")):
            chosen = GBUFFER_FALLBACKS.get(chosen)
        if chosen is None:
            self._programs[name] = None
            return None
        if chosen != name and chosen in self._programs:
            self._programs[name] = self._programs[chosen]
            return self._programs[chosen]
        vsh = self._source(os.path.join(self.folder, chosen + ".vsh"))
        fsh = self._source(os.path.join(self.folder, chosen + ".fsh"))
        defines = dict(self.defines)
        try:
            prog = self.ctx.program(vertex_shader=to_core(vsh, "vsh", defines), fragment_shader=to_core(fsh, "fsh", defines))
        except Exception as e:
            raise RuntimeError("%s/%s failed to compile:\n%s" % (self.world or "root", chosen, e))
        active = active_source(fsh, defines)
        entry = {"name": chosen, "prog": prog, "targets": render_targets(active), "fsh": fsh}
        entry["mipmaps"] = [int(m.group(1)) for m in re.finditer(r"const\s+bool\s+colortex(\d+)MipmapEnabled\s*=\s*true", active)]
        self._programs[name] = entry
        self._programs[chosen] = entry
        return entry

    def blend_for(self, name, default):
        v = self.props.get("blend." + name)
        if v is None:
            return default
        if v.strip().lower() == "off":
            return None
        parts = v.split()
        lut = {"ZERO": moderngl.ZERO, "ONE": moderngl.ONE, "SRC_ALPHA": moderngl.SRC_ALPHA,
               "ONE_MINUS_SRC_ALPHA": moderngl.ONE_MINUS_SRC_ALPHA, "DST_ALPHA": moderngl.DST_ALPHA,
               "ONE_MINUS_DST_ALPHA": moderngl.ONE_MINUS_DST_ALPHA, "SRC_COLOR": moderngl.SRC_COLOR,
               "ONE_MINUS_SRC_COLOR": moderngl.ONE_MINUS_SRC_COLOR, "DST_COLOR": moderngl.DST_COLOR,
               "ONE_MINUS_DST_COLOR": moderngl.ONE_MINUS_DST_COLOR}
        return tuple(lut[p] for p in parts)

    # -- uniforms and samplers -----------------------------------------------------------------------------------

    def bind(self, entry, uniforms, textures, mv, proj, tex_mats=True):
        prog = entry["prog"]
        unit = 0
        for name in list(prog):
            member = prog[name]
            if not isinstance(member, moderngl.Uniform):
                continue
            if name in textures:
                textures[name].use(location=unit)
                member.value = unit
                unit += 1
            elif name == "iris_ModelViewMatrix":
                member.write(glmat(mv))
            elif name == "iris_ProjectionMatrix":
                member.write(glmat(proj))
            elif name == "iris_NormalMatrix":
                nm = np.linalg.inv(mv[:3, :3]).T
                member.write(np.asarray(nm, dtype="f4").T.copy().tobytes())
            elif name == "iris_TextureMatrix0":
                member.write(glmat(np.identity(4)))
            elif name == "iris_TextureMatrix1":
                lm = np.identity(4)
                lm[0, 0] = lm[1, 1] = 1.0 / 256.0
                lm[0, 3] = lm[1, 3] = 8.0 / 256.0
                member.write(glmat(lm))
            elif name in uniforms:
                v = uniforms[name]
                if isinstance(v, np.ndarray) and v.shape == (4, 4):
                    member.write(glmat(v))
                elif isinstance(v, np.ndarray) and v.shape == (3, 3):
                    member.write(np.asarray(v, dtype="f4").T.copy().tobytes())
                else:
                    try:
                        member.value = tuple(v) if isinstance(v, (list, np.ndarray)) else v
                    except Exception as e:
                        raise RuntimeError("uniform %s in %s: %s" % (name, entry["name"], e))
            elif self.verbose and not name.startswith("iris_"):
                print("  unset uniform", name, "in", entry["name"])

    def textures(self, stage):
        t = {"noisetex": self.noise, "shadowtex0": self.shadow0, "shadowtex1": self.shadow1,
             "shadowcolor0": self.shadowcolor0, "shadowcolor1": self.shadowcolor1,
             "shadowcolor": self.shadowcolor0, "watershadow": self.shadow0, "shadow": self.shadow0}
        if stage != "shadow":
            t.update({"depthtex0": self.depth, "depthtex1": self.depth1, "depthtex2": self.depth2,
                      "gdepthtex": self.depth})
            for i, b in self.buffers.items():
                t["colortex%d" % i] = b.current
            t["gcolor"] = self.buffers[0].current
            t["gdepth"] = self.buffers[1].current
            t["gnormal"] = self.buffers[2].current
            t["composite"] = self.buffers[3].current
            t["gaux1"] = self.buffers[4].current
            t["gaux2"] = self.buffers[5].current
            t["gaux3"] = self.buffers[6].current
            t["gaux4"] = self.buffers[7].current
        return t

    def read(self, index):
        """The current copy of colortex<index> as float32 HxWxC, top row first."""
        t = self.buffers[index].current
        comps, dtype = FORMATS.get(self.buffers[index].fmt, (4, "f1"))
        raw = t.read()
        if dtype == "f1":
            a = np.frombuffer(raw, dtype="u1").astype("f4") / 255.0
        elif dtype == "nu2":
            a = np.frombuffer(raw, dtype="u2").astype("f4") / 65535.0
        elif dtype == "f2":
            a = np.frombuffer(raw, dtype="f2").astype("f4")
        else:
            a = np.frombuffer(raw, dtype="f4")
        return a.reshape(self.size[1], self.size[0], comps)[::-1]

    # -- framebuffers --------------------------------------------------------------------------------------------

    def gbuffer_fbo(self, targets):
        key = ("g",) + tuple(targets) + tuple(self.buffers[i].cur for i in targets)
        if key not in self.fbo_cache:
            self.fbo_cache[key] = self.ctx.framebuffer([self.buffers[i].current for i in targets], self.depth)
        return self.fbo_cache[key]

    def composite_fbo(self, targets):
        key = ("c",) + tuple(targets) + tuple(self.buffers[i].cur for i in targets)
        if key not in self.fbo_cache:
            self.fbo_cache[key] = self.ctx.framebuffer([self.buffers[i].other for i in targets])
        return self.fbo_cache[key]

    def copy_depth(self, src, dst):
        fbo = self.ctx.framebuffer(depth_attachment=dst)
        fbo.use()
        self.ctx.disable(moderngl.BLEND)
        self.ctx.enable(moderngl.DEPTH_TEST)
        self.ctx.depth_func = "1"
        src.compare_func = ""
        src.use(location=0)
        self.copy_prog["src"].value = 0
        self.copy_vao.render(moderngl.TRIANGLE_STRIP)
        self.ctx.depth_func = "<="
        fbo.release()

    # -- passes --------------------------------------------------------------------------------------------------

    def clear_buffers(self, fog_color):
        for i, b in self.buffers.items():
            name = "colortex%d" % i
            if not self.dir["clear"].get(name, True):
                continue
            default = list(fog_color) + [1.0] if i == 0 else ([1.0, 1.0, 1.0, 1.0] if i == 1 else [0.0, 0.0, 0.0, 0.0])
            color = self.dir["clearColor"].get(name, default)
            for t in b.tex:
                fbo = self.ctx.framebuffer([t])
                fbo.clear(*color)
                fbo.release()
            b.cur = 0
        self.fbo_cache.clear()
        fbo = self.ctx.framebuffer(depth_attachment=self.depth)
        fbo.clear(depth=1.0)
        fbo.release()

    def draw_geometry(self, name, mesh, uniforms, mv, proj, blend, cull=True, fbo=None, stage="gbuffers", own_textures=None):
        entry = self.program(name)
        if entry is None or mesh is None:
            return
        prog = entry["prog"]
        if fbo is None:
            fbo = self.gbuffer_fbo(entry["targets"])
        fbo.use()
        b = self.blend_for(entry["name"], blend)
        if b is None:
            self.ctx.disable(moderngl.BLEND)
        else:
            self.ctx.enable(moderngl.BLEND)
            self.ctx.blend_func = b if len(b) == 4 else (b[0], b[1])
        self.ctx.enable(moderngl.DEPTH_TEST)
        self.ctx.depth_func = "<="
        if cull:
            self.ctx.enable(moderngl.CULL_FACE)
        else:
            self.ctx.disable(moderngl.CULL_FACE)
        self.shadow0.compare_func = ""
        self.shadow1.compare_func = ""
        self.depth.compare_func = ""
        self.depth1.compare_func = ""
        textures = self.textures(stage)
        textures.update(self.scene_textures)
        textures.update(own_textures or {})
        self.bind(entry, uniforms, textures, mv, proj)
        vao = mesh.vao(self.ctx, prog)
        if vao is not None:
            vao.render(moderngl.TRIANGLES)
        self.ctx.disable(moderngl.CULL_FACE)
        if self.on_pass and stage != "shadow":
            self.on_pass(name)

    def composite(self, name, uniforms):
        """One full-screen pass: reads the current copies, writes the others, then those buffers flip."""
        entry = self.program(name)
        if entry is None:
            return False
        targets = entry["targets"]
        scale = 1.0
        sline = self.props.get("scale." + name)
        if sline:
            scale = float(sline.split()[0])
        for i in entry["mipmaps"]:
            t = self.buffers[i].current
            t.build_mipmaps()
            t.filter = (moderngl.LINEAR_MIPMAP_LINEAR, moderngl.LINEAR)
        final = name == "final"
        if final:
            fbo = self.out_fbo
        else:
            fbo = self.composite_fbo(targets)
            # Iris keeps what a scaled pass didn't draw over: the other copy starts as the current one
            if scale < 1.0:
                for i in targets:
                    self.ctx.copy_framebuffer(self.ctx.framebuffer([self.buffers[i].other]), self.ctx.framebuffer([self.buffers[i].current]))
        fbo.use()
        w, h = self.size
        fbo.viewport = (0, 0, max(1, int(w * scale)), max(1, int(h * scale)))
        self.ctx.disable(moderngl.BLEND)
        self.ctx.disable(moderngl.DEPTH_TEST)
        self.ctx.disable(moderngl.CULL_FACE)
        self.shadow0.compare_func = ""
        self.shadow1.compare_func = ""
        self.depth.compare_func = ""
        self.depth1.compare_func = ""
        ident = np.identity(4)
        self.bind(entry, uniforms, self.textures("composite"), ident, ortho(0, 1, 0, 1, -1, 1))
        prog = entry["prog"]
        content = []
        if "iris_Position" in prog:
            content.append((self.ctx.buffer(np.array([0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 0], dtype="f4").tobytes()), "3f", "iris_Position"))
        if "iris_UV0" in prog:
            content.append((self.quad_vbo, "2f", "iris_UV0"))
        vao = self.ctx.vertex_array(prog, content)
        vao.render(moderngl.TRIANGLE_STRIP)
        fbo.viewport = (0, 0, w, h)
        for i in entry["mipmaps"]:
            self.buffers[i].current.filter = (moderngl.LINEAR, moderngl.LINEAR)
        if not final:
            for i in targets:
                self.buffers[i].cur = 1 - self.buffers[i].cur
            self.fbo_cache.clear()
        if self.on_pass:
            self.on_pass(name)
        return True

    def run_composites(self, prefix, uniforms):
        for suffix in [""] + [str(i) for i in range(1, 100)]:
            if os.path.exists(os.path.join(self.folder, prefix + suffix + ".fsh")):
                self.composite(prefix + suffix, uniforms)

    def shadow_pass(self, scene, uniforms):
        entry = self.program("shadow")
        if entry is None:
            return
        res = self.shadow_res
        fbo0 = self.ctx.framebuffer([self.shadowcolor0, self.shadowcolor1][:len(entry["targets"])] if entry["targets"] else [self.shadowcolor0], self.shadow0)
        fbo0.clear(1.0, 1.0, 1.0, 1.0, depth=1.0)
        fbo0.viewport = (0, 0, res, res)
        mv, proj = uniforms["shadowModelView"], uniforms["shadowProjection"]
        u = dict(uniforms)
        for part in ("solid", "cutout"):
            u["renderStage"] = 1
            self.draw_geometry("shadow", scene.meshes.get(part), u, mv, proj, None, cull=False, fbo=fbo0, stage="shadow")
        self.copy_depth(self.shadow0, self.shadow1)
        self.draw_geometry("shadow", scene.meshes.get("translucent"), u, mv, proj, None, cull=False, fbo=fbo0, stage="shadow")
        fbo0.release()

    def render(self, scene, frame):
        """One frame of scene as frame (see uniforms_for) sees it; returns the final image as uint8 HxWx3."""
        u = frame
        self.scene_textures = scene.gbuffer_textures()
        self.clear_buffers(u["fogColor"])
        self.shadow_pass(scene, u)
        mv, proj = u["gbufferModelView"], u["gbufferProjection"]
        # Opaque: terrain (solid then cutout), then deferred
        self.draw_geometry("gbuffers_terrain_solid", scene.meshes.get("solid"), u, mv, proj, None)
        self.draw_geometry("gbuffers_terrain_cutout", scene.meshes.get("cutout"), u, mv, proj, None, cull=False)
        self.draw_geometry("gbuffers_entities", scene.meshes.get("entities"), u, mv, proj,
                           (moderngl.SRC_ALPHA, moderngl.ONE_MINUS_SRC_ALPHA, moderngl.ONE, moderngl.ONE_MINUS_SRC_ALPHA))
        self.copy_depth(self.depth, self.depth1)
        self.copy_depth(self.depth, self.depth2)
        self.run_composites("deferred", u)
        # Translucent
        alpha = (moderngl.SRC_ALPHA, moderngl.ONE_MINUS_SRC_ALPHA, moderngl.ONE, moderngl.ONE_MINUS_SRC_ALPHA)
        # Minecraft draws the water's surface from below too (a second, reversed face)
        self.draw_geometry("gbuffers_water", scene.meshes.get("translucent"), u, mv, proj, alpha, cull=False)
        self.draw_geometry("gbuffers_particles", scene.meshes.get("particles"), u, mv, proj, alpha, cull=False)
        self.draw_geometry("gbuffers_weather", scene.meshes.get("weather"), u, mv, proj, alpha, cull=False,
                           own_textures=getattr(scene, "mesh_textures", {}).get("weather"))
        self.run_composites("composite", u)
        self.composite("final", u)
        data = np.frombuffer(self.out.read(), dtype="u1").reshape(self.size[1], self.size[0], 4)
        return data[::-1, :, :3].copy()


# ---------------------------------------------------------------------------------------------------------------
# The uniforms of one frame


def frame_uniforms(camera, world_time, size, *, far=192.0, rain=0.0, thunder=0.0, frame_counter=0, time_s=0.0,
                   sun_path_rotation=0.0, shadow_distance=128.0, shadow_interval=2.0, eye_brightness=(0, 240),
                   eye_in_water=0, fog_color=None, sky_color=None, fog_start=None, fog_end=None, moon_phase=0,
                   held_light=0, dimension="overworld", lightning=None, temperature=0.8, rainfall=0.4):
    """Every uniform the pack reads for one frame, worked out as Iris documents them."""
    w, h = size
    cx, cy, cz = camera["pos"]
    yaw, pitch = camera["yaw"], camera["pitch"]
    mv = rot_x(pitch) @ rot_y(yaw + 180.0)
    proj = perspective(camera.get("fov", 70.0), w / h, 0.05, max(far * 4.0, 1024.0))
    angle = sky_angle(world_time)
    sun_angle = angle + 0.25 if angle < 0.75 else angle - 0.75
    celestial = mv @ rot_y(-90.0) @ rot_z(sun_path_rotation) @ rot_x(angle * 360.0)
    sun = celestial @ np.array([0, 100, 0, 0.0])
    moon = celestial @ np.array([0, -100, 0, 0.0])
    up = mv @ np.array([0, 100, 0, 0.0])
    is_day = sun_angle <= 0.5
    shadow_angle = sun_angle if is_day else sun_angle - 0.5
    shadow_light = sun if is_day else moon
    # Iris's ShadowMatrices: back off 100 blocks, turn to face the light, snap to the interval
    sky = shadow_angle + 0.75 if shadow_angle < 0.25 else shadow_angle - 0.25
    smv = translate(0, 0, -100) @ rot_x(90.0) @ rot_z(sky * -360.0) @ rot_x(sun_path_rotation)
    if shadow_interval > 0:
        half = shadow_interval / 2.0
        off = [math.fmod(c, shadow_interval) - half for c in (cx, cy, cz)]
        smv = smv @ translate(*off)
    sproj = ortho(-shadow_distance, shadow_distance, -shadow_distance, shadow_distance, 0.05, 256.0)
    # Vanilla-ish fog colour if none given: day blue-grey, dusk warm, night dark
    day = max(0.0, min(1.0, (sun[1] / 100.0) * 4 + 0.5)) if dimension == "overworld" else 1.0
    if fog_color is None:
        fog_color = tuple(np.array([0.06, 0.07, 0.11]) * (1 - day) + np.array([0.68, 0.78, 0.95]) * day)
    if sky_color is None:
        sky_color = tuple(np.array([0.0, 0.0, 0.02]) * (1 - day) + np.array([0.47, 0.65, 1.0]) * day)
    fog_color = tuple(np.array(fog_color) * (1 - 0.6 * rain))
    u = {
        "gbufferModelView": mv, "gbufferModelViewInverse": np.linalg.inv(mv),
        "gbufferProjection": proj, "gbufferProjectionInverse": np.linalg.inv(proj),
        "gbufferPreviousModelView": mv, "gbufferPreviousProjection": proj,
        "shadowModelView": smv, "shadowModelViewInverse": np.linalg.inv(smv),
        "shadowProjection": sproj, "shadowProjectionInverse": np.linalg.inv(sproj),
        "cameraPosition": (cx, cy, cz), "previousCameraPosition": (cx, cy, cz),
        "cameraPositionFract": (cx % 1.0, cy % 1.0, cz % 1.0), "cameraPositionInt": (int(math.floor(cx)), int(math.floor(cy)), int(math.floor(cz))),
        "eyeAltitude": cy,
        "sunPosition": tuple(sun[:3]), "moonPosition": tuple(moon[:3]), "shadowLightPosition": tuple(shadow_light[:3]),
        "upPosition": tuple(up[:3]), "sunAngle": sun_angle, "shadowAngle": shadow_angle,
        "worldTime": int(world_time) % 24000, "worldDay": 0, "moonPhase": moon_phase,
        "frameTimeCounter": float(time_s), "frameCounter": int(frame_counter), "frameTime": 1.0 / 60.0,
        "rainStrength": rain, "wetness": rain, "thunderStrength": thunder,
        "viewWidth": float(w), "viewHeight": float(h), "aspectRatio": w / h,
        "near": 0.05, "far": far, "fogColor": fog_color, "skyColor": sky_color,
        "fogStart": fog_start if fog_start is not None else far * 0.9, "fogEnd": fog_end if fog_end is not None else far,
        "fogDensity": 0.5, "fogMode": 9729, "fogShape": 1,
        "eyeBrightness": eye_brightness, "eyeBrightnessSmooth": eye_brightness,
        "isEyeInWater": eye_in_water, "nightVision": 0.0, "blindness": 0.0, "darknessFactor": 0.0, "darknessLightFactor": 0.0,
        "heldBlockLightValue": held_light, "heldBlockLightValue2": 0, "screenBrightness": 0.5,
        "lightningBoltPosition": lightning if lightning is not None else (0.0, 0.0, 0.0, 0.0),
        "entityColor": (0.0, 0.0, 0.0, 0.0), "blockEntityId": 0, "entityId": 0, "renderStage": 0,
        "alphaTestRef": 0.1, "temperature": temperature, "rainfall": rainfall, "playerMood": 0.0,
        "hideGUI": 1, "atlasSize": (1024, 1024),
    }
    return u

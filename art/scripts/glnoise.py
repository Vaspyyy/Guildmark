"""The value noise of ship.py (hash01, vnoise, fbm, fbm2) worked out by a compute shader, for the big arrays a preview's
sea and sky need: millions of pixels, each several octaves of noise.

There's no GPU in the cloud, but Mesa's EGL gives an OpenGL 4.5 context on llvmpipe, which compiles the shader to
vector code and runs it on every core: about fifty times faster than numpy for a 2000 x 2200 sea. It hashes the lattice
exactly as ship.hash01 does (32-bit unsigned arithmetic wraps the same way), but blends in float32 rather than float64,
so a value can differ from numpy's in its fifth or sixth decimal place: a pixel may come out one step different. That's
nothing in a preview, but textures are painted without it so they stay exactly repeatable (see ship.fast_noise).

A process makes its own context the first time it needs one. A process forked from one that had a context can't: the
driver's worker threads don't come along with the fork, and a new context would wait for them for ever. So such a
process starts a fresh one of its own (this file run with --serve) and sends it the points through a pipe.

Needs moderngl and Mesa's EGL, as art/shaderkit does. Without them [available] says so and ship.py keeps to numpy.
"""
import os
import struct
import subprocess
import sys
import threading

import numpy as np

_SRC = """
#version 430
layout(local_size_x = 256) in;
layout(std430, binding = 0) readonly buffer BX { float xs[]; };
layout(std430, binding = 1) readonly buffer BY { float ys[]; };
layout(std430, binding = 2) readonly buffer BZ { float zs[]; };
layout(std430, binding = 3) writeonly buffer BO { float outv[]; };
uniform uint n;
uniform int octaves;
uniform vec3 k0;        // the first octave's frequency along each axis (it doubles each octave)
uniform vec3 off;       // how far each octave is shifted along each axis, times its number
uniform int seed;

// ship.hash01: a pseudo-random number in [0, 1) for each lattice point
float hash01(ivec3 p, int s) {
    uint h = (uint(p.x) * 73856093u) ^ (uint(p.y) * 19349663u) ^ (uint(p.z) * 83492791u) ^ (uint(s) * 2654435761u);
    h ^= h >> 15; h *= 2246822519u; h ^= h >> 13; h *= 3266489917u; h ^= h >> 16;
    return float(h) / 4294967296.0;
}

// ship.vnoise: one value per lattice point, eased between them
float vnoise(vec3 q, int s) {
    vec3 c = floor(q);
    vec3 t = clamp(q - c, 0.0, 1.0);
    vec3 f = t * t * (3.0 - 2.0 * t);
    ivec3 i = ivec3(c);
    float o = 0.0;
    for (int dz = 0; dz < 2; dz++)
    for (int dy = 0; dy < 2; dy++)
    for (int dx = 0; dx < 2; dx++) {
        float w = (dx == 1 ? f.x : 1.0 - f.x) * (dy == 1 ? f.y : 1.0 - f.y) * (dz == 1 ? f.z : 1.0 - f.z);
        o += w * hash01(i + ivec3(dx, dy, dz), s);
    }
    return o;
}

void main() {
    uint stride = gl_NumWorkGroups.x * 256u;
    for (uint idx = gl_GlobalInvocationID.x; idx < n; idx += stride) {
        vec3 p = vec3(xs[idx], ys[idx], zs[idx]);
        float tot = 0.0, amp = 1.0, norm = 0.0;
        vec3 k = k0;
        for (int o = 0; o < octaves; o++) {
            tot += amp * vnoise(p * k + off * float(o), seed + 31 * o);
            norm += amp; amp *= 0.5; k *= 2.0;
        }
        outv[idx] = tot / norm;
    }
}
"""

# Points sent to the shader at once: more is split up, to keep its buffers a sensible size
_CHUNK = 1 << 23

# A request to a --serve process: how many points, octaves, seed, k (3), off (3); then the x, y and z of the points as
# float32, and back come their values as float32
_HEAD = struct.Struct('<Qii6d')

# This process's way to the shader: {pid, ok, and ctx + prog (its own context) or proc + lock (a --serve process)}
_state = {}
# Whether EGL was ever started in this process's memory (here, or in one it was forked from): if so it can't have a context of its own
_EGL = [False]


def _context():
    import moderngl
    ctx = moderngl.create_standalone_context(backend='egl', require=430)
    return ctx, ctx.compute_shader(_SRC)


def _int32(v):
    """[v] wrapped to a 32-bit int, the way the shader's arithmetic wraps (crc32 seeds are bigger than an int holds)."""
    return (int(v) + (1 << 31)) % (1 << 32) - (1 << 31)


def _run(ctx, prog, flat, k, off, seed, octaves):
    """The shader's values (float32) at the points [flat] (three contiguous float32 arrays)."""
    n = flat[0].size
    out = np.empty(n, np.float32)
    prog['octaves'] = int(octaves)
    prog['k0'] = tuple(float(v) for v in k)
    prog['off'] = tuple(float(v) for v in off)
    prog['seed'] = _int32(seed)
    for lo in range(0, n, _CHUNK):
        hi = min(n, lo + _CHUNK)
        m = hi - lo
        bufs = [ctx.buffer(a[lo:hi].tobytes()) for a in flat]
        res = ctx.buffer(reserve=m * 4)
        for i, b in enumerate(bufs):
            b.bind_to_storage_buffer(i)
        res.bind_to_storage_buffer(3)
        prog['n'] = m
        prog.run(min((m + 255) // 256, 65535))
        out[lo:hi] = np.frombuffer(res.read(), np.float32)
        for b in bufs:
            b.release()
        res.release()
    return out


def _fill(stream, a):
    """Reads exactly as many bytes as [a] holds from [stream] into it. False at the end of the stream."""
    view = memoryview(a).cast('B')
    got = 0
    while got < len(view):
        k = stream.readinto(view[got:])
        if not k:
            return False
        got += k
    return True


def _spawn():
    """A fresh process (not a fork: one with nothing of EGL in it yet) with a context of its own, once it says it's ready."""
    proc = subprocess.Popen([sys.executable, os.path.abspath(__file__), '--serve'], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    if proc.stdout.read(1) != b'\x01':
        proc.kill()
        proc.wait()
        raise RuntimeError('no EGL context in a fresh process either')
    return proc


def _setup():
    _state.clear()
    _state['pid'] = os.getpid()
    try:
        if _EGL[0]:
            _state.update(proc=_spawn(), lock=threading.Lock())
        else:
            _EGL[0] = True
            _state['ctx'], _state['prog'] = _context()
        _state['ok'] = True
    except Exception:
        _state['ok'] = False


def available():
    """Whether the shader can run here. Tried once a process; a failure is remembered and numpy is used instead."""
    if _state.get('pid') != os.getpid():
        _setup()
    return _state['ok']


def fbm(x, y, z, k=(1.0, 1.0, 1.0), off=(0.0, 0.0, 0.0), seed=0, octaves=1):
    """Fractal value noise in 0..1 at the points (x, y, z), broadcast together: octave o (of [octaves], each half the
    weight of the last) is ship.vnoise at p * k * 2^o + off * o with seed + 31 o, as ship.fbm and ship.fbm2 have it."""
    if not available():
        raise RuntimeError('glnoise is not available here')
    x, y, z = np.broadcast_arrays(np.asarray(x, np.float32), np.asarray(y, np.float32), np.asarray(z, np.float32))
    shape = x.shape
    flat = [np.ascontiguousarray(a).ravel() for a in (x, y, z)]
    if 'ctx' in _state:
        out = _run(_state['ctx'], _state['prog'], flat, k, off, seed, octaves)
    else:
        proc = _state['proc']
        out = np.empty(flat[0].size, np.float32)
        with _state['lock']:
            proc.stdin.write(_HEAD.pack(out.size, int(octaves), _int32(seed), *(float(v) for v in k), *(float(v) for v in off)))
            for a in flat:
                proc.stdin.write(memoryview(a).cast('B'))
            proc.stdin.flush()
            if not _fill(proc.stdout, out):
                raise RuntimeError('the glnoise process stopped')
    return out.astype(np.float64).reshape(shape)


def _serve():
    """Answers requests on stdin until it closes (when the process that started this one ends)."""
    replies = os.fdopen(os.dup(1), 'wb')
    os.dup2(2, 1)  # anything else that prints goes to stderr, not into the replies
    asks = sys.stdin.buffer
    try:
        ctx, prog = _context()
    except Exception:
        replies.write(b'\x00')
        replies.flush()
        return
    replies.write(b'\x01')
    replies.flush()
    while True:
        head = asks.read(_HEAD.size)
        if len(head) < _HEAD.size:
            return
        n, octaves, seed, *rest = _HEAD.unpack(head)
        flat = [np.empty(n, np.float32) for _ in range(3)]
        for a in flat:
            if not _fill(asks, a):
                return
        replies.write(memoryview(_run(ctx, prog, flat, rest[:3], rest[3:], seed, octaves)).cast('B'))
        replies.flush()


if __name__ == '__main__' and sys.argv[1:] == ['--serve']:
    _serve()

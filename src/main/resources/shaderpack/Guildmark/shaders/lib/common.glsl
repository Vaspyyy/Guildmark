// Small helpers every program may use.

#define PI 3.14159265
#define TAU 6.28318531

// Material ids, kept in colortex2.r as id / 255
#define MAT_DEFAULT 0
#define MAT_LEAVES 1
#define MAT_PLANT 2
#define MAT_TORCH 3
#define MAT_GLOW 4
#define MAT_HAND 6
#define MAT_ENTITY 7
#define MAT_UNLIT 8
#define MAT_WARDFIRE 11
#define MAT_CRYSTAL 12
#define MAT_LAVA 13
#define MAT_SNOW 14
#define MAT_METAL 15

// Block ids from block.properties, as mc_Entity.x hands them over
#define ID_WATER 10001
#define ID_LEAVES 10002
#define ID_PLANT 10003
#define ID_TALL_LOWER 10004
#define ID_TALL_UPPER 10005
#define ID_HANGING 10006
#define ID_GLOW 10010
#define ID_TORCH 10011
#define ID_LAVA 10012
#define ID_WARDFIRE 10013
#define ID_CRYSTAL 10014
#define ID_SNOW 10020
#define ID_ICE 10021
#define ID_GLASS 10022
#define ID_METAL 10030
#define ID_MIST_GATE 10040

float saturate(float x) { return clamp(x, 0.0, 1.0); }
vec3 saturate(vec3 x) { return clamp(x, 0.0, 1.0); }
float sq(float x) { return x * x; }
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
vec3 toLinear(vec3 c) { return pow(max(c, 0.0), vec3(2.2)); }
vec3 toGamma(vec3 c) { return pow(max(c, 0.0), vec3(1.0 / 2.2)); }

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
float hash13(vec3 p3) {
    p3 = fract(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return fract((p3.x + p3.y) * p3.z);
}
vec2 hash22(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}

/** Smooth value noise, 0..1. */
float noise2(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash12(i);
    float b = hash12(i + vec2(1.0, 0.0));
    float c = hash12(i + vec2(0.0, 1.0));
    float d = hash12(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}
float noise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    vec3 u = f * f * (3.0 - 2.0 * f);
    float n000 = hash13(i);
    float n100 = hash13(i + vec3(1, 0, 0));
    float n010 = hash13(i + vec3(0, 1, 0));
    float n110 = hash13(i + vec3(1, 1, 0));
    float n001 = hash13(i + vec3(0, 0, 1));
    float n101 = hash13(i + vec3(1, 0, 1));
    float n011 = hash13(i + vec3(0, 1, 1));
    float n111 = hash13(i + vec3(1, 1, 1));
    return mix(mix(mix(n000, n100, u.x), mix(n010, n110, u.x), u.y),
               mix(mix(n001, n101, u.x), mix(n011, n111, u.x), u.y), u.z);
}

/** Interleaved gradient noise: a dither that hides banding and sampling patterns, shifted every frame. */
float dither(vec2 fragCoord, int frame) {
    vec2 p = fragCoord + float(frame % 64) * vec2(5.588238, 5.588238);
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

// Unit vectors in two numbers (octahedral), stored 0..1
vec2 encodeNormal(vec3 n) {
    n /= (abs(n.x) + abs(n.y) + abs(n.z));
    vec2 e = n.z >= 0.0 ? n.xy : (1.0 - abs(n.yx)) * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    return e * 0.5 + 0.5;
}
vec3 decodeNormal(vec2 e) {
    e = e * 2.0 - 1.0;
    vec3 n = vec3(e.xy, 1.0 - abs(e.x) - abs(e.y));
    float t = max(-n.z, 0.0);
    n.x += n.x >= 0.0 ? -t : t;
    n.y += n.y >= 0.0 ? -t : t;
    return normalize(n);
}

vec3 desaturate(vec3 c, float amount) { return mix(c, vec3(luma(c)), amount); }

/** Light levels from the lightmap coordinate: [1/32, 31/32] in, 0..1 out. */
vec2 lightLevels(vec2 lmcoord) { return clamp((lmcoord - 1.0 / 32.0) * 16.0 / 15.0, 0.0, 1.0); }

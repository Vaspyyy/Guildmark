// Water: slow, heavy waves and dark water that swallows the light quickly.

float waveHeight(vec2 p, float t) {
    float h = 0.0;
    h += sin(dot(p, vec2(0.86, 0.50)) * 1.10 + t * 1.30) * 0.10;
    h += sin(dot(p, vec2(-0.60, 0.80)) * 1.90 + t * 1.80) * 0.06;
    h += sin(dot(p, vec2(0.20, -0.98)) * 3.40 + t * 2.60) * 0.030;
    h += (noise2(p * 0.9 + t * vec2(0.35, 0.22)) - 0.5) * 0.20;
    h += (noise2(p * 2.4 - t * vec2(0.55, 0.30)) - 0.5) * 0.09;
    h += (noise2(p * 6.0 + t * vec2(0.9, -0.6)) - 0.5) * 0.030;
    return h * WATER_WAVES;
}

/** The surface's world normal at world x, z. */
vec3 waveNormal(vec2 p) {
    float t = frameTimeCounter;
    const float e = 0.05;
    float h0 = waveHeight(p, t);
    float hx = waveHeight(p + vec2(e, 0.0), t);
    float hz = waveHeight(p + vec2(0.0, e), t);
    return normalize(vec3((h0 - hx) / e, 1.0, (h0 - hz) / e) * vec3(0.55, 1.0, 0.55));
}

// What a block of water takes out of the light, per channel: red goes first, then green
vec3 waterAbsorption() { return vec3(0.42, 0.15, 0.10) * WATER_MURK; }

// The colour of the silt hanging in it, lit by daylight
vec3 waterScatter(Atmos a, float skyLight) {
    vec3 lit = a.ambient * 0.9 + a.direct * 0.10;
    return vec3(0.045, 0.110, 0.100) * lit * (0.15 + 0.85 * skyLight) + vec3(0.0004, 0.0008, 0.0008);
}

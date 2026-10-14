// Plants and leaves moving in the wind, the same in the shadow pass as in the world so shadows follow them.

vec3 windOffset(vec3 worldPos, float strength) {
    float t = frameTimeCounter;
    float gust = 0.55 + 0.45 * sin(t * 0.35 + worldPos.x * 0.012 + worldPos.z * 0.009);
    float storm = 1.0 + 1.6 * saturate(rainStrength) + 1.2 * saturate(thunderStrength);
    float a = sin(t * 1.7 + worldPos.x * 0.55 + worldPos.z * 0.35) * 0.6 + sin(t * 2.9 + worldPos.z * 0.8 + worldPos.y * 0.5) * 0.4;
    float b = sin(t * 2.3 + worldPos.z * 0.62 - worldPos.x * 0.21) * 0.6 + sin(t * 3.7 + worldPos.x * 0.9) * 0.4;
    return vec3(a, 0.0, b * 0.8) * 0.055 * strength * gust * storm * WIND_STRENGTH;
}

/**
 * The offset for a vertex of block id with texture coordinate uv (top of the texture moves, its foot stays).
 * Returns zero for anything that doesn't sway.
 */
vec3 waving(int id, vec3 worldPos, vec2 uv, vec2 midUv) {
    bool top = uv.y < midUv.y;
#ifdef WAVING_PLANTS
    if (id == ID_PLANT || id == ID_TALL_LOWER) {
        return top ? windOffset(worldPos, 1.0) : vec3(0.0);
    }
    if (id == ID_TALL_UPPER) {
        return windOffset(worldPos, top ? 1.9 : 1.0);
    }
    if (id == ID_HANGING) {
        return windOffset(worldPos, 0.45);
    }
#endif
#ifdef WAVING_LEAVES
    if (id == ID_LEAVES) {
        vec3 o = windOffset(worldPos, 0.38);
        o.y = sin(frameTimeCounter * 2.1 + worldPos.x * 0.7 + worldPos.z * 0.4) * 0.012 * WIND_STRENGTH;
        return o;
    }
#endif
    return vec3(0.0);
}

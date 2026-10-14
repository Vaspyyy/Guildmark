// The mist: a haze through all the air, and a heavier ground mist that pools in the low land, worst at dawn
// and in rain. Its density falls off with height, so it can be added up along a view ray in closed form.

#define MIST_FLOOR 62.0

struct Mist {
    float valley; // ground mist density at the floor
    float haze;   // the haze's
};

Mist mistAmounts(Atmos a) {
    Mist m;
#if defined WORLD_NETHER
    m.valley = 0.0;
    m.haze = 0.016 * MIST_DENSITY;
#elif defined WORLD_END
    m.valley = 0.0;
    m.haze = 0.006 * MIST_DENSITY;
#else
    float morning = smoothstep(-0.25, 0.25, a.sunDir.x);
    float low = saturate(a.dusk + a.night * 0.7);
    float rain = saturate(rainStrength);
    m.valley = (0.0055 + 0.0120 * morning * low + 0.0035 * a.night) * VALLEY_MIST + 0.0080 * rain;
    m.haze = 0.0013 + 0.0010 * a.night + 0.0006 * a.dusk + 0.0045 * rain + 0.0030 * saturate(thunderStrength);
    // The land's own haze: Guildmark's regions draw the fog in, unless the fog was pushed out of sight on purpose
    if (fogEnd > 0.0 && fogEnd < far * 1.5) {
        m.haze += 0.0060 * saturate((far - fogEnd) / max(far, 1.0));
    }
    m.valley *= MIST_DENSITY;
    m.haze *= MIST_DENSITY;
    // Underground the air is still and clear
    float outside = float(eyeBrightnessSmooth.y) / 240.0;
    m.valley *= mix(0.15, 1.0, outside);
    m.haze *= mix(0.35, 1.0, outside);
#endif
    m.valley += blindness * 0.4 + darknessFactor * 0.08;
    return m;
}

const float VALLEY_HEIGHT = 9.0;
const float HAZE_HEIGHT = 65.0;

float mistDensityAt(float y, Mist m) {
    y = max(y, MIST_FLOOR - 6.0);
    return m.valley * exp(-(y - MIST_FLOOR) / VALLEY_HEIGHT) + m.haze * exp(-(y - MIST_FLOOR) / HAZE_HEIGHT);
}

float layerDepth(float density, float scale, float y0, float y1, float len) {
    y0 = max(y0, MIST_FLOOR - 6.0);
    y1 = max(y1, MIST_FLOOR - 6.0);
    float k0 = exp(-(y0 - MIST_FLOOR) / scale);
    float k1 = exp(-(y1 - MIST_FLOOR) / scale);
    float dy = y1 - y0;
    if (abs(dy) < 0.01) return density * len * k0;
    return density * len * (k0 - k1) * scale / dy;
}

/** Optical depth of the mist along a straight line of length len from height y0 to height y1. */
float mistDepth(float y0, float y1, float len, Mist m) {
    return layerDepth(m.valley, VALLEY_HEIGHT, y0, y1, len) + layerDepth(m.haze, HAZE_HEIGHT, y0, y1, len);
}

/**
 * How much sunlight is left at height y after crossing the ground mist above it toward a light of elevation ly:
 * thick mist glows on top and darkens within. (The thin haze is already in the sun's own colour.)
 */
float mistSunlight(float y, float ly, Mist m) {
    y = max(y, MIST_FLOOR - 6.0);
    float od = m.valley * VALLEY_HEIGHT * exp(-(y - MIST_FLOOR) / VALLEY_HEIGHT) / max(ly, 0.04);
    return exp(-od);
}

// The light of the hour: the sun and moon, the sky's fill light, the sky itself, its clouds and stars.
// Dark fantasy: pale sun at noon, deep embers at dusk, nights that are truly dark, and always some haze.

struct Atmos {
    vec3 sunDir;     // world space, toward the sun
    vec3 moonDir;
    vec3 lightDir;   // toward whichever of the two casts the shadows now
    vec3 axis;       // the axis the sky turns about
    vec3 direct;     // that body's light, after the air and the weather
    vec3 sunTint;    // the sun's colour (for glows, also when it is down)
    vec3 ambient;    // the sky's fill light from straight above
    vec3 zenith;
    vec3 horizon;
    vec3 mist;       // the colour lit mist takes from the sky
    float day;       // 0 at night, 1 by full day
    float dusk;      // 1 around sunrise and sunset
    float night;
    float rain;
    float flash;     // lightning
};

float hgPhase(float cosTheta, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * PI * pow(max(1.0 + g2 - 2.0 * g * cosTheta, 1e-4), 1.5));
}

/** How much air the light crosses at this elevation, 1 straight up (Kasten and Young). */
float airMass(float elevation) {
    float e = max(elevation, 0.0);
    float deg = degrees(asin(e));
    return 1.0 / (e + 0.50572 * pow(deg + 6.07995, -1.6364));
}

vec3 sunlightColor(float elevation) {
    vec3 extinction = vec3(0.045, 0.105, 0.24);
    return exp(-extinction * min(airMass(elevation), 40.0));
}

/** fogColor carries the biome's and Guildmark's region tint: its hue, at a brightness of our choosing. */
vec3 fogHue() {
    vec3 f = toLinear(fogColor);
    float l = luma(f);
    return l > 1e-4 ? clamp(f / l, 0.0, 3.0) : vec3(1.0);
}

Atmos atmosphere() {
    Atmos a;
    a.rain = saturate(rainStrength);
    a.flash = lightningBoltPosition.w > 0.5 ? 1.0 : 0.0;
#if defined WORLD_NETHER
    a.sunDir = vec3(0.0, 1.0, 0.0);
    a.moonDir = vec3(0.0, -1.0, 0.0);
    a.lightDir = a.sunDir;
    a.axis = vec3(0.0, 0.0, 1.0);
    vec3 f = toLinear(fogColor);
    a.direct = vec3(0.0);
    a.sunTint = vec3(1.0, 0.4, 0.15);
    // The biome's fog colour lights everything a little, as the vanilla Nether's ambient light does
    a.ambient = (f * 1.6 + vec3(0.030, 0.014, 0.008)) * AMBIENT_STRENGTH;
    a.zenith = f * 0.30;
    a.horizon = f * 0.45;
    a.mist = f * 1.2 + vec3(0.020, 0.008, 0.004);
    a.day = 0.0;
    a.dusk = 0.0;
    a.night = 1.0;
#elif defined WORLD_END
    a.sunDir = viewDirToWorld(sunPosition);
    a.moonDir = -a.sunDir;
    a.lightDir = viewDirToWorld(shadowLightPosition);
    a.axis = vec3(0.0, 0.0, 1.0);
    a.direct = vec3(0.30, 0.24, 0.42) * SUN_STRENGTH;
    a.sunTint = vec3(0.70, 0.55, 1.0);
    a.ambient = vec3(0.030, 0.022, 0.045) * AMBIENT_STRENGTH;
    a.zenith = vec3(0.012, 0.007, 0.022);
    a.horizon = vec3(0.045, 0.030, 0.065);
    a.mist = vec3(0.050, 0.034, 0.075);
    a.day = 0.0;
    a.dusk = 0.0;
    a.night = 1.0;
#else
    a.sunDir = viewDirToWorld(sunPosition);
    a.moonDir = viewDirToWorld(moonPosition);
    a.lightDir = viewDirToWorld(shadowLightPosition);
    float spr = radians(sunPathRotation);
    a.axis = vec3(0.0, sin(spr), cos(spr));
    float e = a.sunDir.y;
    a.day = smoothstep(-0.06, 0.30, e);
    a.dusk = exp(-sq((e - 0.02) / 0.16));
    a.night = 1.0 - smoothstep(-0.20, 0.02, e);

    // The sun through the air: pale and a little warm when high, ember red at the horizon
    a.sunTint = sunlightColor(e + 0.02);
    vec3 sun = a.sunTint * 3.2 * SUN_STRENGTH * smoothstep(-0.03, 0.06, e);
    // The moon: cold and faint, brighter when full
    float phase = 1.0 - abs(float(moonPhase) - 4.0) / 4.0;
    float moonLight = mix(0.18, 1.0, phase) * smoothstep(-0.04, 0.10, a.moonDir.y);
    vec3 moon = vec3(0.50, 0.62, 1.0) * 0.085 * moonLight * NIGHT_BRIGHTNESS;
    a.direct = e > 0.0 ? sun : moon;
    // Overcast swallows most of the direct light
    a.direct *= 1.0 - 0.88 * a.rain;

    // The sky by the sun's height. Low, cool fill light: the shade stays deep
    vec3 zDay = vec3(0.100, 0.145, 0.230);
    vec3 hDay = vec3(0.380, 0.410, 0.450);
    vec3 zGold = vec3(0.085, 0.105, 0.180);
    vec3 hGold = vec3(0.470, 0.360, 0.260);
    vec3 zSet = vec3(0.040, 0.040, 0.085);
    vec3 hSet = vec3(0.400, 0.180, 0.090);
    vec3 zTwi = vec3(0.010, 0.012, 0.030);
    vec3 hTwi = vec3(0.065, 0.036, 0.048);
    vec3 zNight = vec3(0.0016, 0.0024, 0.0060) * NIGHT_BRIGHTNESS;
    vec3 hNight = vec3(0.0050, 0.0068, 0.0130) * NIGHT_BRIGHTNESS;
    float t1 = smoothstep(0.10, 0.55, e);   // gold -> day
    float t2 = smoothstep(-0.02, 0.10, e);  // set -> gold
    float t3 = smoothstep(-0.12, 0.00, e);  // twilight -> set
    float t4 = smoothstep(-0.30, -0.10, e); // night -> twilight
    a.zenith = mix(mix(mix(mix(zNight, zTwi, t4), zSet, t3), zGold, t2), zDay, t1);
    a.horizon = mix(mix(mix(mix(hNight, hTwi, t4), hSet, t3), hGold, t2), hDay, t1);
    // A brighter moonlit night sky
    a.zenith += vec3(0.0010, 0.0016, 0.0040) * moonLight;
    a.horizon += vec3(0.0024, 0.0034, 0.0070) * moonLight;

    vec3 ambDay = vec3(0.125, 0.150, 0.190);
    vec3 ambGold = vec3(0.080, 0.075, 0.105);
    vec3 ambSet = vec3(0.040, 0.030, 0.050);
    vec3 ambTwi = vec3(0.010, 0.010, 0.020);
    vec3 ambNight = vec3(0.0022, 0.0032, 0.0062) * NIGHT_BRIGHTNESS + vec3(0.0010, 0.0016, 0.0035) * moonLight;
    a.ambient = mix(mix(mix(mix(ambNight, ambTwi, t4), ambSet, t3), ambGold, t2), ambDay, t1) * AMBIENT_STRENGTH;

    // Rain: one low grey lid over everything
    float overcast = a.rain;
    vec3 grey = vec3(0.30, 0.32, 0.34) * (a.day * 0.85 + 0.03) + vec3(0.003, 0.004, 0.006);
    a.zenith = mix(a.zenith, grey * 0.62, overcast);
    a.horizon = mix(a.horizon, grey * 0.80, overcast);
    a.ambient = mix(a.ambient, grey * 0.42 * AMBIENT_STRENGTH, overcast * 0.85);

    // The land's own air leans the haze toward its colour
    vec3 hue = fogHue();
    a.horizon = mix(a.horizon, a.horizon * hue, 0.30);
    a.mist = a.horizon * 0.72;

    a.zenith += vec3(0.20, 0.22, 0.30) * a.flash;
    a.horizon += vec3(0.30, 0.32, 0.40) * a.flash;
    a.ambient += vec3(0.25, 0.27, 0.34) * a.flash;
#endif
    return a;
}

// ---- Clouds: one wind-driven layer, lit from the sun's side, dark-bellied

#ifdef CLOUDS
float cloudFbm(vec2 p) {
    float v = 0.0;
    float amp = 0.55;
    const mat2 turn = mat2(1.6, 1.2, -1.2, 1.6);
    for (int i = 0; i < 5; i++) {
        v += amp * noise2(p);
        p = turn * p + vec2(17.3, 4.1);
        amp *= 0.48;
    }
    return v;
}

float cloudCover() {
    return mix(CLOUD_COVERAGE, 0.88, saturate(rainStrength));
}

/** Cloud thickness 0..1 at a world-space x, z on the cloud layer. */
float cloudDensity(vec2 xz) {
    vec2 wind = vec2(frameTimeCounter * 1.6, frameTimeCounter * 0.55);
    vec2 p = (xz + wind) / 190.0;
    float n = cloudFbm(p);
    float cover = cloudCover();
    return smoothstep(1.0 - cover - 0.06, 1.0 - cover + 0.20, n);
}

/** The cloud layer seen along d from the camera: colour (premultiplied) and coverage. */
vec4 cloudLayer(vec3 d, Atmos a) {
    if (d.y < 0.01) return vec4(0.0);
    float camY = cameraPosition.y;
    float h = max(CLOUD_HEIGHT - camY, 20.0);
    float t = h / d.y;
    vec2 xz = cameraPosition.xz + d.xz * t;
    float dens = cloudDensity(xz);
    if (dens <= 0.0) return vec4(0.0);
    // Self-shadowing: how much cloud lies between this point and the light
    vec2 toLight = normalize(a.lightDir.xz + vec2(1e-4));
    float shade = cloudDensity(xz + toLight * 22.0) + cloudDensity(xz + toLight * 60.0) * 0.7;
    float lit = exp(-shade * 1.6);
    float cosL = dot(d, a.lightDir);
    float forward = hgPhase(cosL, 0.65) * 4.0 + 0.45;
    // Heavy grey bellies, the sun catching the tops and the thinning edges
    vec3 belly = mix(a.zenith, a.horizon, 0.5) * 0.48 + a.ambient * 0.30;
    vec3 col = belly * (1.0 - 0.35 * dens) + a.direct * 0.22 * lit * forward;
    // Silver edges round the sun where the cloud thins
    col += a.direct * 0.6 * (1.0 - dens) * hgPhase(cosL, 0.85) * lit;
    col *= mix(1.0, 0.55, saturate(rainStrength));
    float fade = exp(-t / 9000.0) * smoothstep(0.01, 0.12, d.y);
    float alpha = dens * fade;
    return vec4(col * alpha, alpha);
}

/** The share of sunlight the clouds let through to a world position below them. */
float cloudShadow(vec3 worldPos, Atmos a) {
#ifdef CLOUD_SHADOWS
    vec3 l = a.lightDir;
    if (l.y < 0.05) return 1.0;
    float t = (CLOUD_HEIGHT - worldPos.y) / l.y;
    float d = cloudDensity(worldPos.xz + l.xz * t);
    return 1.0 - d * mix(0.70, 0.35, saturate(rainStrength));
#else
    return 1.0;
#endif
}
#else
vec4 cloudLayer(vec3 d, Atmos a) { return vec4(0.0); }
float cloudShadow(vec3 worldPos, Atmos a) { return 1.0; }
#endif

// ---- Stars and the moon's face

float starField(vec3 d, Atmos a) {
#ifdef STARS
    // Fixed to the sky as it turns
    vec3 e1 = a.sunDir;
    vec3 e3 = a.axis;
    vec3 e2 = normalize(cross(e3, e1));
    vec3 p = vec3(dot(d, e1), dot(d, e2), dot(d, e3)) * 300.0;
    vec3 cell = floor(p);
    float h = hash13(cell);
    if (h < 0.9965) return 0.0;
    vec3 f = fract(p) - 0.5;
    float r = length(f);
    float size = (h - 0.9965) / 0.0035;
    float s = smoothstep(0.32, 0.0, r) * (0.25 + size * size * 1.4);
    float twinkle = 0.75 + 0.25 * sin(frameTimeCounter * (2.0 + h * 5.0) + h * 400.0);
    return s * twinkle;
#else
    return 0.0;
#endif
}

vec3 moonFace(vec3 d, Atmos a) {
    float cosM = dot(d, a.moonDir);
    float radius = 0.034;
    float c = cos(radius);
    if (cosM < c - 0.002) return vec3(0.0);
    vec3 right = normalize(cross(a.moonDir, a.axis));
    vec3 up = cross(right, a.moonDir);
    vec2 q = vec2(dot(d, right), dot(d, up)) / sin(radius);
    float r2 = dot(q, q);
    if (r2 > 1.0) return vec3(0.0);
    vec3 n = vec3(q, sqrt(1.0 - r2));
    float phi = float(moonPhase) / 8.0 * TAU;
    vec3 l = vec3(sin(phi), 0.0, cos(phi));
    float lit = smoothstep(-0.05, 0.15, dot(n, l));
    float maria = noise2(q * 3.0 + 7.0) * 0.5 + noise2(q * 7.0) * 0.25;
    float albedo = 0.75 - smoothstep(0.45, 0.75, maria) * 0.3;
    float edge = smoothstep(1.0, 0.92, r2);
    return vec3(0.80, 0.86, 1.0) * (lit * albedo * 1.6 + 0.012) * edge;
}

/**
 * The sky along world direction d. full adds the sun's and moon's discs, the stars and the clouds; reflections
 * and the mist leave them out.
 */
vec3 skyRadiance(vec3 d, Atmos a, bool full) {
#if defined WORLD_NETHER
    return a.horizon;
#elif defined WORLD_END
    float n = noise2(d.xz / (abs(d.y) + 0.35) * 2.5 + frameTimeCounter * 0.01) * noise2(d.zx * 3.0 + 4.0);
    vec3 col = mix(a.horizon, a.zenith, saturate(abs(d.y) * 1.5)) + vec3(0.050, 0.020, 0.080) * n * n;
    if (full) col += vec3(0.9, 0.85, 1.0) * starField(d, a) * 0.6;
    return col;
#else
    float h = d.y;
    float up = pow(saturate(h), 0.42);
    vec3 col = mix(a.horizon, a.zenith, up);
    // The low sky toward the sun burns; the opposite side shades to violet
    float cosS = dot(d, a.sunDir);
    vec2 toSun = normalize(a.sunDir.xz + vec2(1e-4));
    vec2 across = normalize(d.xz + vec2(1e-4));
    float side = dot(across, toSun) * 0.5 + 0.5;
    float low = exp(-max(h, 0.0) * 5.0);
    col += a.sunTint * vec3(1.0, 0.70, 0.45) * a.dusk * side * side * low * 0.55 * (1.0 - a.rain * 0.8);
    col += vec3(0.10, 0.05, 0.12) * a.dusk * (1.0 - side) * low * 0.35 * (1.0 - a.rain);
    // Below the horizon the haze darkens toward the ground
    if (h < 0.0) col = mix(col, a.horizon * 0.40, saturate(-h * 2.5));
    // The glow round the sun, and the moon's
    float sunUp = smoothstep(-0.10, 0.05, a.sunDir.y);
    col += a.sunTint * 3.2 * SUN_STRENGTH * hgPhase(cosS, 0.80) * 0.035 * sunUp * (1.0 - 0.7 * a.rain);
    float cosM = dot(d, a.moonDir);
    float phase = 1.0 - abs(float(moonPhase) - 4.0) / 4.0;
    col += vec3(0.45, 0.55, 0.85) * hgPhase(cosM, 0.88) * 0.0035 * phase * smoothstep(-0.05, 0.1, a.moonDir.y) * (1.0 - 0.8 * a.rain);
    if (full) {
        float cover = 1.0;
        vec4 cl = cloudLayer(d, a);
        cover = 1.0 - cl.a;
        float sunDisc = smoothstep(0.99955, 0.99975, cosS);
        col += a.sunTint * 90.0 * sunDisc * sunUp * cover * (1.0 - 0.9 * a.rain);
        col += moonFace(d, a) * cover * (1.0 - 0.85 * a.rain);
        float starsShow = saturate(1.0 - luma(a.zenith) * 60.0) * (1.0 - a.rain);
        col += vec3(0.85, 0.90, 1.0) * starField(d, a) * 0.05 * starsShow * cover * smoothstep(-0.05, 0.15, h);
        col = col * (1.0 - cl.a) + cl.rgb;
    }
    return col;
#endif
}

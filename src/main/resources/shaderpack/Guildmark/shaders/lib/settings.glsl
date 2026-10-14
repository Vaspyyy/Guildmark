// Guildmark's shader settings: the values Iris lists under Shader Pack Settings. Every program includes this file.

// ---- Shadows
const int shadowMapResolution = 2048; // [1024 2048 3072 4096]
const float shadowDistance = 128.0; // [64.0 96.0 128.0 160.0 192.0 256.0]
const float shadowDistanceRenderMul = 1.0;
const float shadowIntervalSize = 2.0;
const float sunPathRotation = -25.0; // [-40.0 -35.0 -30.0 -25.0 -20.0 -15.0 -10.0 0.0 10.0 20.0]
const bool shadowtex0Nearest = true;
const bool shadowtex1Nearest = true;
const bool shadowcolor0Nearest = true;
#define SHADOW_FILTER 2 // [1 2 3] Taps per pixel: 1 few, 2 medium, 3 many
#define SHADOW_SOFTNESS 1.0 // [0.5 0.75 1.0 1.25 1.5 2.0]
#define COLORED_SHADOWS // Light through stained glass and water takes its colour
#define CLOUD_SHADOWS // The clouds' shadows drift over the land

// ---- Light
#define SUN_STRENGTH 1.0 // [0.6 0.8 1.0 1.2 1.4 1.6]
#define AMBIENT_STRENGTH 1.0 // [0.5 0.75 1.0 1.25 1.5 2.0] How much light fills the shade
#define NIGHT_BRIGHTNESS 1.0 // [0.5 0.75 1.0 1.5 2.0 3.0]
#define CAVE_DARKNESS 1.0 // [0.0 0.5 1.0 1.5 2.0] How dark unlit places get
#define TORCH_STRENGTH 1.0 // [0.5 0.75 1.0 1.25 1.5 2.0]
#define TORCH_FLICKER
#define HELD_LIGHT // A torch in hand lights the way
#define EMISSIVE_STRENGTH 1.0 // [0.5 0.75 1.0 1.5 2.0 3.0]
#define SSAO // Deeper shade in corners and under eaves
#define SSAO_STRENGTH 1.0 // [0.5 0.75 1.0 1.25 1.5 2.0]
#define FOLIAGE_SSS // Light glows through leaves and grass from behind

// ---- Atmosphere
#define MIST_DENSITY 1.0 // [0.0 0.25 0.5 0.75 1.0 1.25 1.5 2.0 3.0]
#define VALLEY_MIST 1.0 // [0.0 0.5 1.0 1.5 2.0] Ground mist pooling in low land, thickest at dawn
#define LIGHT_SHAFTS
#define SHAFT_STRENGTH 1.0 // [0.5 0.75 1.0 1.25 1.5 2.0 3.0]
#define SHAFT_STEPS 12 // [8 12 16 24]
#define SUN_RAYS
#define CLOUDS
#define CLOUD_COVERAGE 0.5 // [0.2 0.3 0.4 0.5 0.6 0.7 0.8]
#define CLOUD_HEIGHT 230.0 // [190.0 230.0 280.0 330.0]
#define STARS
#define RAIN_WETNESS // Rain darkens and wets the ground, and leaves puddles

// ---- Water
#define WATER_WAVES 1.0 // [0.0 0.5 1.0 1.5 2.0]
#define WATER_MURK 1.0 // [0.25 0.5 0.75 1.0 1.5 2.0] How quickly water swallows what lies below
#define WATER_REFLECTIONS

// ---- Camera
#define EXPOSURE 1.0 // [0.5 0.6 0.7 0.8 0.9 1.0 1.1 1.2 1.4 1.6 1.8 2.0]
#define AUTO_EXPOSURE // Eyes adjust between caves and open sky, never fully
#define BLOOM
#define BLOOM_STRENGTH 1.0 // [0.0 0.5 0.75 1.0 1.5 2.0]
#define SATURATION 0.8 // [0.5 0.6 0.7 0.8 0.9 1.0 1.1 1.2]
#define GREEN_MUTE 0.5 // [0.0 0.25 0.5 0.75 1.0] Takes the brightness out of Minecraft's greens
#define CONTRAST 1.1 // [0.9 1.0 1.05 1.1 1.15 1.2 1.3]
#define SPLIT_TONE 1.0 // [0.0 0.5 1.0 1.5 2.0] Cold shade, warm light
#define VIGNETTE 0.4 // [0.0 0.2 0.3 0.4 0.5 0.6 0.8]
#define FILM_GRAIN 0.3 // [0.0 0.15 0.3 0.5 0.75 1.0]
#define FXAA

// ---- Motion
#define WAVING_PLANTS
#define WAVING_LEAVES
#define WIND_STRENGTH 1.0 // [0.0 0.5 1.0 1.5 2.0]

// Checked once with #ifdef, as Iris needs for every switch
#ifdef COLORED_SHADOWS
#endif
#ifdef CLOUD_SHADOWS
#endif
#ifdef TORCH_FLICKER
#endif
#ifdef HELD_LIGHT
#endif
#ifdef SSAO
#endif
#ifdef FOLIAGE_SSS
#endif
#ifdef LIGHT_SHAFTS
#endif
#ifdef SUN_RAYS
#endif
#ifdef CLOUDS
#endif
#ifdef STARS
#endif
#ifdef RAIN_WETNESS
#endif
#ifdef WATER_REFLECTIONS
#endif
#ifdef AUTO_EXPOSURE
#endif
#ifdef BLOOM
#endif
#ifdef FXAA
#endif
#ifdef WAVING_PLANTS
#endif
#ifdef WAVING_LEAVES
#endif

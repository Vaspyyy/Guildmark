// The uniforms Iris hands over that Guildmark's programs read. Unused ones cost nothing.

uniform mat4 gbufferModelView;
uniform mat4 gbufferModelViewInverse;
uniform mat4 gbufferProjection;
uniform mat4 gbufferProjectionInverse;
uniform mat4 shadowModelView;
uniform mat4 shadowModelViewInverse;
uniform mat4 shadowProjection;
uniform mat4 shadowProjectionInverse;

uniform vec3 cameraPosition;
uniform vec3 sunPosition;
uniform vec3 moonPosition;
uniform vec3 shadowLightPosition;
uniform vec3 fogColor;
uniform vec3 skyColor;

uniform float frameTimeCounter;
uniform float rainStrength;
uniform float wetness;
uniform float thunderStrength;
uniform float sunAngle;
uniform float near;
uniform float far;
uniform float fogStart;
uniform float fogEnd;
uniform float viewWidth;
uniform float viewHeight;
uniform float aspectRatio;
uniform float nightVision;
uniform float blindness;
uniform float darknessFactor;
uniform float screenBrightness;
uniform float temperature;
uniform float rainfall;

uniform int worldTime;
uniform int moonPhase;
uniform int frameCounter;
uniform int isEyeInWater;
uniform int heldBlockLightValue;
uniform int heldBlockLightValue2;

uniform ivec2 eyeBrightnessSmooth;
uniform vec4 lightningBoltPosition;

#version 330

in vec2 texCoord;
out vec4 fragColor;

layout(std140) uniform SkyInfo {
    vec4 ResolutionTimeMode;
    vec4 AuroraColorQuality;
    vec4 StarsNightSaturation;
    vec4 PulseCameraFov;
};

#define uResolution ResolutionTimeMode.xy
#define uCameraDir PulseCameraFov.xy
#define uColor AuroraColorQuality.xyz
#define uTime PulseCameraFov.w
#define uAlpha 1.0
#define uSpeed 1.0
#define uScale 5.0
#define uIntensity 0.02
#define uFov PulseCameraFov.z

mat3 rotX(float a) {
    float c = cos(a), s = sin(a);
    return mat3(1.0, 0.0, 0.0, 0.0, c, s, 0.0, -s, c);
}

mat3 rotY(float a) {
    float c = cos(a), s = sin(a);
    return mat3(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c);
}

const mat3 m3 = mat3(
    0.00,  0.80,  0.60,
   -0.80,  0.36, -0.48,
   -0.60, -0.48,  0.64
);

float hash(vec3 p) {
    p = fract(p * vec3(443.897, 441.423, 437.195));
    p += dot(p, p.yzx + 19.19);
    return fract((p.x + p.y) * p.z);
}

float noise(vec3 x) {
    vec3 p = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(mix(hash(p + vec3(0,0,0)), hash(p + vec3(1,0,0)), f.x),
            mix(hash(p + vec3(0,1,0)), hash(p + vec3(1,1,0)), f.x), f.y),
        mix(mix(hash(p + vec3(0,0,1)), hash(p + vec3(1,0,1)), f.x),
            mix(hash(p + vec3(0,1,1)), hash(p + vec3(1,1,1)), f.x), f.y),
        f.z
    );
}

float fbm(vec3 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; ++i) {
        v += a * noise(p);
        p = m3 * p * 2.02;
        a *= 0.5;
    }
    return v;
}

void main() {
    vec2 uv = gl_FragCoord.xy / uResolution;
    vec2 sp = uv * 2.0 - 1.0;
    float aspect = uResolution.x / uResolution.y;

    float tanV = tan(radians(uFov) * 0.5);
    vec3 rayV = normalize(vec3(sp.x * tanV * aspect, sp.y * tanV, 1.0));
    vec3 rayW = rotY(uCameraDir.x) * rotX(uCameraDir.y) * rayV;

    vec3 p = rayW * (uScale * 0.3 + 0.15);
    float t = uTime * uSpeed * 0.04;

    vec3 q = vec3(
        fbm(p + vec3(0.0, t, 0.0)),
        fbm(p + vec3(1.7, 0.4, t * 0.8)),
        fbm(p + vec3(2.4, t * 1.2, 0.5))
    );

    float density = fbm(p + q * 1.3);
    float innerCore = fbm(p * 1.6 + q * 2.0 - vec3(0.0, t * 0.5, 0.0));
    vec3 primaryColor = uColor;
    vec3 mysteryGlowColor = mix(vec3(uColor.b, uColor.r, uColor.g), vec3(0.2, 0.8, 1.0), 0.35);
    vec3 voidDark = vec3(0.001, 0.001, 0.004);
    float cloudShape = pow(clamp(density, 0.0, 1.0), 2.2);
    vec3 finalColor = mix(voidDark, primaryColor * 0.45, cloudShape);
    float corePower = pow(clamp(innerCore * density, 0.0, 1.0), 2.0);
    vec3 coreGlow = mysteryGlowColor * corePower * (1.8 + uIntensity * 16.0);
    finalColor += coreGlow;
    finalColor = mix(voidDark, finalColor, uAlpha);

    fragColor = vec4(finalColor, uAlpha);
}

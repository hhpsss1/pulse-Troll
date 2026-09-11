#version 330

in vec3 vWorldDir;

layout(std140) uniform SkyInfo {
    vec4 ResolutionTimeMode;
    vec4 AuroraColorQuality;
    vec4 StarsNightSaturation;
};

#define Time ResolutionTimeMode.z
#define AuroraColor AuroraColorQuality.xyz
#define Quality AuroraColorQuality.w
#define Night StarsNightSaturation.y
#define Saturation StarsNightSaturation.z

out vec4 OutColor;

vec3 applySaturation(vec3 color, float saturation) {
    float gray = dot(color, vec3(0.299, 0.587, 0.114));
    return mix(vec3(gray), color, saturation);
}

float hash31(vec3 p) {
    return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453123);
}

float asteriaValueNoise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    vec3 w3 = f * f * (3.0 - 2.0 * f);
    float n000 = hash31(i);
    float n100 = hash31(i + vec3(1.0, 0.0, 0.0));
    float n010 = hash31(i + vec3(0.0, 1.0, 0.0));
    float n110 = hash31(i + vec3(1.0, 1.0, 0.0));
    float n001 = hash31(i + vec3(0.0, 0.0, 1.0));
    float n101 = hash31(i + vec3(1.0, 0.0, 1.0));
    float n011 = hash31(i + vec3(0.0, 1.0, 1.0));
    float n111 = hash31(i + vec3(1.0, 1.0, 1.0));
    float x00 = mix(n000, n100, w3.x);
    float x10 = mix(n010, n110, w3.x);
    float x01 = mix(n001, n101, w3.x);
    float x11 = mix(n011, n111, w3.x);
    float y0 = mix(x00, x10, w3.y);
    float y1 = mix(x01, x11, w3.y);
    return mix(y0, y1, w3.z);
}

float fbm3(vec3 p) {
    float s = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) {
        s += a * asteriaValueNoise3(p);
        p *= 2.07;
        a *= 0.5;
    }
    return s;
}

void main() {
    vec3 rd = normalize(vWorldDir);
    float y = rd.y;
    float t = Time;
    float n = clamp(Night, 0.0, 1.0);

    float breathe = 0.93 + 0.07 * sin(t * 0.26);
    float pulseSat = 0.96 + 0.04 * sin(t * 0.19 + 1.7);
    float pulseNight = 0.88 + 0.12 * sin(t * 0.12 + 0.4);

    float hazeMask = smoothstep(0.2, -0.38, y);
    hazeMask *= hazeMask;
    vec3 hazeOff = vec3(
        sin(t * 2.15 + dot(rd, vec3(1.0, 0.55, 0.35)) * 12.0),
        cos(t * 1.95 + dot(rd, vec3(-0.45, 1.0, 0.5)) * 11.5),
        sin(t * 2.35 + dot(rd, vec3(0.25, -0.4, 1.0)) * 13.0)
    ) * (0.0055 * hazeMask * Quality * mix(1.0, 0.65, n));

    vec3 rsum = rd + hazeOff;
    float rlen = length(rsum);
    vec3 rs = (rlen > 1.0e-4) ? (rsum / rlen) : rd;

    float sc = Quality;
    vec3 drift = vec3(t * 0.042, t * 0.024, t * 0.033);

    vec3 zenithD = vec3(0.04, 0.26, 0.78) * pulseSat;
    vec3 zenithN = vec3(0.015, 0.04, 0.14) * pulseNight;
    vec3 zenith = mix(zenithD, zenithN, n);

    vec3 horizonLightD = vec3(0.62, 0.86, 1.0);
    vec3 horizonLightN = vec3(0.05, 0.07, 0.16);
    vec3 horizonLight = mix(horizonLightD, horizonLightN, n);

    vec3 grassHintD = vec3(0.1, 0.42, 0.24);
    vec3 grassHintN = vec3(0.02, 0.05, 0.09);
    vec3 grassHint = mix(grassHintD, grassHintN, n);

    float gradMix = smoothstep(-0.38, 0.68, y);
    vec3 skyBase = mix(horizonLight, zenith, gradMix);
    float nearGround = (1.0 - gradMix) * smoothstep(-0.55, 0.18, y);
    skyBase = mix(skyBase, mix(skyBase, grassHint, 0.4), nearGround * mix(0.55, 0.35, n));

    vec3 pC = rs * sc * 1.85 + drift;
    float boil = fbm3(pC * 2.35 + vec3(t * 0.11));
    float cLow = fbm3(pC + vec3(boil * 0.5, -boil * 0.38, boil * 0.22));
    float cHi = fbm3(pC * 1.35 - drift * 0.55 + vec3(17.3, 84.1, 31.7));
    float clouds = cLow * 0.58 + cHi * 0.42;
    clouds = pow(clamp(clouds, 0.0, 1.0), mix(0.82, 0.92, n));
    float puffCore = smoothstep(0.5, 0.84, clouds);
    float puffEdge = smoothstep(0.36, 0.9, clouds) * (1.0 - puffCore) * mix(0.45, 0.32, n);
    vec3 cloudTintD = vec3(1.0, 0.99, 0.97);
    vec3 cloudTintN = vec3(0.22, 0.24, 0.32);
    vec3 cloudTint = mix(cloudTintD, cloudTintN, n);
    vec3 col = mix(skyBase, cloudTint, puffCore);
    col = mix(col, mix(skyBase, cloudTint, 0.88), puffEdge);
    col += vec3(1.0, 0.92, 0.75) * puffEdge * mix(0.12, 0.02, n) * breathe;

    vec3 gBase = rs * sc * 1.25 + drift * 0.85 + vec3(0.0, sin(t * 0.28) * 0.12, 0.0);
    float g1 = fbm3(gBase + fbm3(gBase * 1.65 + vec3(t * 0.095)) * 0.55);
    float g2 = fbm3(gBase * 2.2 - vec3(t * 0.05, t * 0.03, t * 0.04));
    float goldMask = smoothstep(0.52, 0.8, g1 * 0.55 + g2 * 0.45);
    goldMask *= smoothstep(-0.15, 0.58, y);
    goldMask *= 0.55 + 0.45 * sin(dot(rs, vec3(1.15, 2.05, 0.75)) * 3.0 + t * 0.7);
    goldMask *= mix(1.0, 0.08, n);
    vec3 goldA = vec3(1.0, 0.843, 0.0);
    vec3 goldB = vec3(1.0, 0.58, 0.15);
    vec3 goldCol = mix(goldA, goldB, sin(t * 0.45 + dot(rs, vec3(0.55, 1.95, 1.05)) * 4.0) * 0.5 + 0.5);
    vec3 tintRgb = vec3(AuroraColor.r, AuroraColor.g, AuroraColor.b);
    goldCol = mix(goldCol, clamp(tintRgb, 0.15, 1.0), 0.2);
    col += goldCol * goldMask * 0.5 * breathe;

    float airLight = pow(max(0.0, 1.0 - abs(y) - 0.04), 2.15);
    col = mix(col, col * 1.06 + vec3(0.06, 0.07, 0.03), airLight * mix(0.38, 0.12, n));

    vec3 sp = rs * 48.0 + vec3(t * 0.018, t * 0.011, t * 0.014);
    float st = fbm3(sp);
    float tw = sin(t * 2.4 + st * 40.0) * 0.5 + 0.5;
    float starField = smoothstep(0.72, 0.92, st) * smoothstep(0.15, 0.85, y) * n;
    starField *= (0.35 + 0.65 * tw);
    col += vec3(0.75, 0.82, 1.0) * starField * 0.55;

    col = clamp(col * mix(1.01 + 0.035 * sin(t * 0.21), 0.92 + 0.04 * sin(t * 0.17), n), 0.0, 1.0);

    OutColor = vec4(applySaturation(col, Saturation), 1.0);
}

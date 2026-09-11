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

#define PI 3.1415926

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
    for (int i = 0; i < 4; i++) {
        s += a * asteriaValueNoise3(p);
        p *= 2.03;
        a *= 0.5;
    }
    return s;
}

float hash11(float t) {
    return fract(sin(t * 437.513) * 951.135);
}

void main() {
    vec3 rd = normalize(vWorldDir);
    float t = Time;
    float n = clamp(Night, 0.0, 1.0);
    float sc = max(0.35, Quality);

    vec3 col = mix(vec3(0.005, 0.01, 0.03), vec3(0.01, 0.02, 0.06), smoothstep(-0.8, 0.9, rd.y));

    // Milky Way band axis and mask (slowly rotating for living sky feel).
    float gSpin = t * 0.045;
    vec3 galaxyAxis = normalize(vec3(sin(gSpin) * 0.24 - 0.28, 0.77, cos(gSpin) * 0.24 + 0.57));
    float axisDot = dot(rd, galaxyAxis);
    float band = exp(-axisDot * axisDot * 24.0);
    float bandWide = exp(-axisDot * axisDot * 7.2);
    float bandCore = exp(-axisDot * axisDot * 42.0);

    // Nebula texture along Milky Way.
    vec3 nebulaP = rd * (4.45 * sc) + vec3(t * 0.03, -t * 0.018, t * 0.022);
    float neb1 = fbm3(nebulaP);
    float neb2 = fbm3(nebulaP * 1.8 + vec3(9.3, 2.1, -4.7));
    float dust = fbm3(nebulaP * 2.6 + vec3(-2.7, 7.9, 5.4));
    float neb = smoothstep(0.32, 0.86, neb1 * 0.62 + neb2 * 0.38);
    float darkLanes = smoothstep(0.25, 0.72, dust);

    vec3 nebulaColA = vec3(0.23, 0.35, 0.9);
    vec3 nebulaColB = vec3(0.86, 0.44, 0.92);
    vec3 nebulaColC = vec3(0.45, 0.76, 1.0);
    vec3 nebula = mix(mix(nebulaColA, nebulaColB, neb2), nebulaColC, neb1 * 0.35);
    nebula *= bandWide * (0.45 + 0.55 * neb);
    nebula *= (1.0 - darkLanes * 0.58);

    float pulse = 0.88 + 0.12 * sin(t * 0.65 + neb2 * 4.0);
    col += nebula * (1.0 + 0.2 * pulse);

    vec3 core = vec3(1.0, 0.93, 0.79) * band * (0.46 + 0.54 * neb) * (1.0 - darkLanes * 0.4);
    col += core * 0.74;

    // Bloom-like glow around Milky Way.
    vec3 halo = vec3(0.5, 0.62, 1.0) * bandWide * 0.26;
    halo += vec3(1.0, 0.72, 0.92) * bandCore * 0.18;
    halo *= 0.9 + 0.1 * sin(t * 0.9 + dot(rd, vec3(2.3, 1.2, 1.7)) * 3.0);
    col += halo;

    // Dense star field.
    vec3 sp = rd * 320.0;
    vec3 sid = floor(sp);
    vec3 sfr = fract(sp) - 0.5;
    float sh = hash31(sid);
    float tw = 0.65 + 0.35 * sin(t * 2.4 + sh * 60.0);
    float stars = smoothstep(0.9925, 0.9997, sh) * smoothstep(0.34, 0.0, length(sfr));
    col += vec3(0.85, 0.92, 1.0) * stars * tw * (0.62 + 0.38 * bandWide);

    // Extra micro-stars layer for richer cosmic depth.
    vec3 sp2 = rd * 540.0 + vec3(17.0, 29.0, 11.0);
    vec3 sid2 = floor(sp2);
    vec3 sfr2 = fract(sp2) - 0.5;
    float sh2 = hash31(sid2 + vec3(13.0, 7.0, 19.0));
    float stars2 = smoothstep(0.997, 0.9998, sh2) * smoothstep(0.28, 0.0, length(sfr2));
    col += vec3(0.72, 0.82, 1.0) * stars2 * (0.6 + 0.4 * bandWide);

    // Light tint from theme color.
    col *= mix(vec3(1.0), clamp(AuroraColor, 0.0, 1.0), 0.1);

    // Tonemap + slight night adaptation.
    col *= mix(1.0, 1.08, n);
    col = col / (col + vec3(0.95));
    col = pow(clamp(col, 0.0, 1.0), vec3(0.93));
    OutColor = vec4(applySaturation(col, Saturation), 1.0);
}

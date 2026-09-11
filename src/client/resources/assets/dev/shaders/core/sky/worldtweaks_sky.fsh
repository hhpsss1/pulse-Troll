#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 vPos;

layout(std140) uniform SkyInfo {
    vec4 ResolutionTimeMode;
    vec4 AuroraColorQuality;
    vec4 StarsNightSaturation;
};

#define Resolution ResolutionTimeMode.xy
#define Time ResolutionTimeMode.z
#define Mode ResolutionTimeMode.w
#define AuroraColor AuroraColorQuality.xyz
#define Quality AuroraColorQuality.w
#define ShowStars StarsNightSaturation.x
#define Saturation StarsNightSaturation.z

out vec4 OutColor;

vec3 applySaturation(vec3 color, float saturation) {
    float gray = dot(color, vec3(0.299, 0.587, 0.114));
    return mix(vec3(gray), color, saturation);
}

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

float tri(float x) { return abs(fract(x) - 0.5); }

float triNoise2d(vec2 p, float spd, int noiseIter) {
    float z = 1.8;
    float z2 = 2.5;
    float rz = 0.0;
    float c = cos(p.x * 0.06);
    float s = sin(p.x * 0.06);
    p = vec2(p.x * c - p.y * s, p.x * s + p.y * c);
    vec2 bp = p;
    float t = Time * spd;
    for (int i = 0; i < 4; i++) {
        if (i >= noiseIter) break;
        vec2 dg = vec2(tri(bp.x * 1.85) + tri(bp.y * 1.85), tri(bp.y * 1.85 + tri(bp.x * 1.85))) * 0.75;
        float ct = cos(t);
        float st = sin(t);
        dg = vec2(dg.x * ct - dg.y * st, dg.x * st + dg.y * ct);
        p -= dg / z2;
        bp *= 1.3;
        z2 *= 0.45;
        z *= 0.42;
        p *= 1.21 + (rz - 1.0) * 0.02;
        rz += tri(p.x + tri(p.y)) * z;
        p = vec2(-p.y * 0.95534 + p.x * 0.29552, p.x * 0.95534 + p.y * 0.29552);
    }
    return clamp(1.0 / pow(max(rz * 29.0, 1e-3), 1.3), 0.0, 0.55);
}

vec3 bgAurora(vec3 rd) {
    float sd = dot(normalize(vec3(-0.5, -0.6, 0.9)), rd) * 0.5 + 0.5;
    sd = pow(sd, 5.0);
    vec3 col = mix(vec3(0.05, 0.1, 0.2), vec3(0.1, 0.05, 0.2), sd);
    return col * 0.63;
}

vec4 aurora(vec3 ro, vec3 rd, int aurIter, int noiseIter, float aurStep, float noiseStep) {
    vec4 col = vec4(0.0);
    vec4 avgCol = vec4(0.0);
    float rdY = rd.y * 2.0 + 0.4;
    if (rdY <= 0.01) return col;

    for (int i = 0; i < 16; i++) {
        if (i >= aurIter) break;
        float fi = float(i);
        float pt = (0.8 + pow(fi, 1.4) * 0.004 - ro.y) / rdY;
        pt -= 0.006 * hash12(gl_FragCoord.xy) * smoothstep(0.0, 8.0, fi);
        vec3 bpos = ro + pt * rd;
        float rzt = triNoise2d(bpos.zx, noiseStep, noiseIter);
        vec4 col2 = vec4(AuroraColor * rzt, rzt);
        avgCol = mix(avgCol, col2, 0.5);
        col += avgCol * exp2(-fi * aurStep - 1.5) * smoothstep(0.0, 3.0, fi);
    }
    col *= clamp(rd.y * 15.0 + 0.4, 0.0, 1.0);
    return col * 3.0;
}

vec3 stars(vec3 rd, int starIter) {
    if (ShowStars < 0.5) return vec3(0.0);
    vec3 c = vec3(0.0);
    float res = 420.0;
    for (int i = 0; i < 3; i++) {
        if (i >= starIter) break;
        vec3 q = fract(rd * res) - 0.5;
        vec3 id = floor(rd * res);
        float rn = hash13(id);
        float c2 = 1.0 - smoothstep(0.0, 0.6, length(q));
        c2 *= step(rn, 0.003 + float(i) * 0.001);
        c += c2 * (mix(vec3(1.0, 0.49, 0.1), vec3(0.75, 0.9, 1.0), hash13(id + 100.0)) * 0.1 + 0.9);
        res *= 1.5;
    }
    return c * c * 0.75;
}

// ---- Nebula ----
float noise3d(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float n = i.x + i.y * 57.0 + 113.0 * i.z;
    float a = hash13(vec3(n + 0.0, 0.0, 0.0));
    float b = hash13(vec3(n + 1.0, 0.0, 0.0));
    float c = hash13(vec3(n + 57.0, 0.0, 0.0));
    float d = hash13(vec3(n + 58.0, 0.0, 0.0));
    float e = hash13(vec3(n + 113.0, 0.0, 0.0));
    float f2 = hash13(vec3(n + 114.0, 0.0, 0.0));
    float g = hash13(vec3(n + 170.0, 0.0, 0.0));
    float h = hash13(vec3(n + 171.0, 0.0, 0.0));
    float x1 = mix(a, b, f.x);
    float x2 = mix(c, d, f.x);
    float x3 = mix(e, f2, f.x);
    float x4 = mix(g, h, f.x);
    float y1 = mix(x1, x2, f.y);
    float y2 = mix(x3, x4, f.y);
    return mix(y1, y2, f.z);
}

float fbm3(vec3 p) {
    float v = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 4; i++) {
        v += amp * noise3d(p);
        p *= 2.0;
        amp *= 0.5;
    }
    return v;
}

vec3 bgNebula(vec3 rd) {
    float t = Time * 0.02;
    vec3 p = rd * 2.0;
    float angle = atan(p.x, p.z) + t;
    float radius = length(p.xz);
    float spiral = sin(angle * 3.0 - radius * 5.0) * 0.5 + 0.5;
    spiral *= smoothstep(1.5, 0.3, radius);
    vec3 col1 = vec3(0.02, 0.03, 0.08);
    vec3 col2 = vec3(0.08, 0.05, 0.15);
    vec3 col = mix(col1, col2, spiral * 0.5);
    col += vec3(0.1, 0.05, 0.2) * pow(spiral, 2.0) * 0.3;
    return col;
}

vec4 nebulaClouds(vec3 ro, vec3 rd, int cloudIter, float cloudStep) {
    vec4 col = vec4(0.0);
    float t = Time * 0.05;
    for (int i = 0; i < 20; i++) {
        if (i >= cloudIter) break;
        float fi = float(i);
        vec3 pos = ro + rd * (fi * cloudStep + 2.0);
        pos += vec3(t * 0.3, t * 0.2, 0.0);
        float density = fbm3(pos * 0.8);
        density = smoothstep(0.3, 0.8, density);
        vec3 nebulaCol = mix(AuroraColor, vec3(0.8, 0.3, 0.9), sin(fi * 0.5 + t) * 0.5 + 0.5);
        nebulaCol = mix(nebulaCol, vec3(0.2, 0.5, 1.0), noise3d(pos * 1.5 + t));
        vec4 cloudCol = vec4(nebulaCol * density, density * 0.15);
        col = col + cloudCol * (1.0 - col.a);
        if (col.a > 0.95) break;
    }
    return col;
}

// ---- FogBlur-ish ----
float asteriaValueNoise2(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash12(i);
    float b = hash12(i + vec2(1.0, 0.0));
    float c = hash12(i + vec2(0.0, 1.0));
    float d = hash12(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm2(vec2 p) {
    float v = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 4; i++) {
        v += asteriaValueNoise2(p) * amp;
        p = p * 2.02 + vec2(19.1, 7.4);
        amp *= 0.5;
    }
    return v;
}

float blurredFbm(vec2 p, int samples, float radius) {
    float sum = fbm2(p) * 2.0;
    float weight = 2.0;
    for (int i = 0; i < 8; i++) {
        if (i >= samples) break;
        float fi = float(i);
        float ang = fi * 6.2831853 / float(max(samples, 1));
        vec2 dir = vec2(cos(ang), sin(ang));
        float w = 1.0 + sin(fi * 2.3) * 0.15;
        sum += fbm2(p + dir * radius) * w;
        sum += fbm2(p - dir * radius * 0.6) * w * 0.7;
        weight += w * 1.7;
    }
    return sum / max(weight, 1e-3);
}

vec3 baseSky(vec3 rd) {
    float horizon = clamp(rd.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 low = vec3(0.015, 0.02, 0.035);
    vec3 high = AuroraColor * 0.28 + vec3(0.08, 0.10, 0.16);
    return mix(low, high, pow(horizon, 0.75));
}

vec3 fogPalette(float t) {
    vec3 softTint = AuroraColor * 0.45 + vec3(0.10, 0.11, 0.14);
    vec3 brightTint = AuroraColor * 0.95 + vec3(0.12, 0.13, 0.16);
    return mix(softTint, brightTint, clamp(t, 0.0, 1.0));
}

void main() {
    // vPos.xy is clip-space in [-1..1]. Reconstruct a WORLD-space view direction,
    // so the sky is anchored to the world (not "stuck" to the crosshair overlay).
    vec2 ndc = vPos.xy;
    vec4 clip = vec4(ndc, 1.0, 1.0);
    vec4 view = inverse(ProjMat) * clip;
    vec3 dirView = normalize(view.xyz / max(abs(view.w), 1e-5));
    vec3 rd = normalize((inverse(ModelViewMat) * vec4(dirView, 0.0)).xyz);
    // Same sky under horizon / in chunk void: sample as if looking at the mirrored upper dome.
    vec3 rdSky = normalize(vec3(rd.x, max(abs(rd.y), 1e-4), rd.z));

    int q = int(clamp(Quality, 1.0, 3.0));
    int mode = int(clamp(Mode, 0.0, 2.0));

    // iterations by quality
    int aurIter = (q == 1) ? 6 : (q == 3 ? 16 : 10);
    int noiseIter = (q == 1) ? 2 : (q == 3 ? 4 : 3);
    int starIter = (q == 1) ? 1 : (q == 3 ? 3 : 2);

    int cloudIter = (q == 1) ? 8 : (q == 3 ? 20 : 14);
    float cloudStep = (q == 1) ? 0.20 : (q == 3 ? 0.08 : 0.12);

    int blurSamples = (q == 1) ? 4 : (q == 3 ? 8 : 6);
    float blurRadius = (q == 1) ? 0.24 : (q == 3 ? 0.10 : 0.16);
    float fogIntensity = (q == 1) ? 0.75 : (q == 3 ? 1.05 : 0.90);
    float fogScale = (q == 1) ? 1.55 : (q == 3 ? 2.35 : 1.95);

    vec3 col;

    if (mode == 2) {
        vec2 uv = rdSky.xz / max(rdSky.y + 1.35, 0.32);
        uv *= fogScale;
        uv += vec2(Time * 0.03, Time * 0.016);
        float fogLarge = blurredFbm(uv, blurSamples, blurRadius);
        float fogSmall = blurredFbm(uv * 1.85 - vec2(Time * 0.02, -Time * 0.01), blurSamples, blurRadius);
        float fogShape = mix(fogLarge, fogSmall, 0.35);
        fogShape = smoothstep(0.38, 0.82, fogShape);
        fogShape *= mix(0.55, 1.15, clamp(rdSky.y * 0.5 + 0.5, 0.0, 1.0));

        col = baseSky(rdSky);
        vec3 fogCol = fogPalette(fogLarge * 0.8 + fogSmall * 0.35);
        col += fogCol * fogShape * fogIntensity;
        col += stars(rdSky, starIter);
        col = pow(col, vec3(0.92));
    } else {
        col = bgAurora(rdSky);
        vec4 aur = aurora(vec3(0.0, 0.0, -6.7), rdSky, aurIter, noiseIter, (q == 1 ? 0.15 : (q == 3 ? 0.05 : 0.10)), (q == 1 ? 0.20 : 0.15));
        col += stars(rdSky, starIter);
        col = col * (1.0 - aur.a) + aur.rgb;
    }

    OutColor = vec4(applySaturation(col, Saturation), 1.0);
}

#version 330

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D maskTexture;
uniform sampler2D glowTexture;
uniform sampler2D sceneTexture;
uniform sampler2D gateTexture;  // mask, downscaled 8x and dilated by the effect's reach

layout(std140) uniform OutlineData {
    vec4 color;          // fill color (r, g, b, a)
    vec4 outlineColor;   // glow/outline color
    vec4 params1;        // texelX, texelY, outlineWidthF, friendCount
    vec4 params2;        // glowMode, 0, shimmerT, shimmerEnabled
    vec4 params3;        // shimmerWidth, useItemColor, 0, 0
    vec4 friendRects[8]; // u1, v1, u2, v2 in UV [0-1], up to 8 friends
};

float maxChan(vec4 s) {
    return max(s.r, max(s.g, s.b));
}

// тот же "оживитель" цвета, что и в fire glow — тянет к насыщенному тону предмета
vec3 itemColor(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    float mx = max(max(c.r, c.g), c.b);
    if (mx < 0.04) {
        return vec3(0.0);
    }
    float gray = dot(c, vec3(0.299, 0.587, 0.114));
    float sat = mx - min(min(c.r, c.g), c.b);
    vec3 vivid = mix(vec3(gray), c, 1.0 + sat * 0.45);
    return clamp(vivid / max(max(max(vivid.r, vivid.g), vivid.b), 0.20), 0.0, 1.0) * min(mx * 1.18, 1.0);
}

// маленькое усреднение цвета сцены вокруг точки — гасит текстурный шум предмета
vec3 sceneAvg(vec2 c, vec2 ts) {
    vec3 s = texture(sceneTexture, c).rgb;
    s += texture(sceneTexture, c + vec2(ts.x, 0.0)).rgb;
    s += texture(sceneTexture, c - vec2(ts.x, 0.0)).rgb;
    s += texture(sceneTexture, c + vec2(0.0, ts.y)).rgb;
    s += texture(sceneTexture, c - vec2(0.0, ts.y)).rgb;
    return s / 5.0;
}

// цвет предмета: марш к силуэту по градиенту свечения (свечение растёт внутрь силуэта),
// на первом же пикселе маски берём цвет предмета из сцены. Работает при любой ширине глоу.
vec3 sampleItemColor(vec3 fallback) {
    vec2 ts = params1.xy;
    int maxSteps = int(clamp(params3.z, 8.0, 48.0));

    // направление к силуэту = градиент яркости размытой маски
    float gx = maxChan(texture(glowTexture, texCoord + vec2(ts.x, 0.0)))
             - maxChan(texture(glowTexture, texCoord - vec2(ts.x, 0.0)));
    float gy = maxChan(texture(glowTexture, texCoord + vec2(0.0, ts.y)))
             - maxChan(texture(glowTexture, texCoord - vec2(0.0, ts.y)));
    vec2 dir = vec2(gx, gy);

    if (dot(dir, dir) > 1e-9) {
        dir = normalize(dir);
        for (int s = 1; s <= 48; s++) {
            if (s > maxSteps) break;
            vec2 c = texCoord + dir * ts * float(s);
            if (maxChan(texture(maskTexture, c)) > 0.5) {
                vec3 boosted = itemColor(sceneAvg(c + dir * ts * 1.5, ts));
                if (max(max(boosted.r, boosted.g), boosted.b) >= 0.04) return boosted;
                return fallback;
            }
        }
    }

    // запасной вариант — расширяющиеся кольца, если градиент не помог
    vec3 sum = vec3(0.0);
    float wsum = 0.0;
    for (int i = 0; i < 12; i++) {
        float a = float(i) * 0.5235987756;
        for (int r = 1; r <= 6; r++) {
            vec2 off = vec2(cos(a), sin(a)) * ts * (float(maxSteps) * float(r) / 6.0);
            vec2 c = texCoord + off;
            float m = maxChan(texture(maskTexture, c));
            if (m > 0.3) {
                sum += texture(sceneTexture, c).rgb * m;
                wsum += m;
            }
        }
    }
    if (wsum < 0.001) return fallback;
    vec3 boosted = itemColor(sum / wsum);
    if (max(max(boosted.r, boosted.g), boosted.b) < 0.04) return fallback;
    return boosted;
}

float getOutlineAlpha() {
    int w = int(params1.z);
    if (w == 0) return 0.0;
    if (maxChan(texture(maskTexture, texCoord)) > 0.5) return 0.0;
    if (maxChan(texture(glowTexture,  texCoord)) < 0.01) return 0.0;

    vec2 ts = params1.xy;
    for (int x = -w; x <= w; x++) {
        for (int y = -w; y <= w; y++) {
            if (x == 0 && y == 0) continue;
            vec2 off = vec2(float(x), float(y)) * ts;
            if (maxChan(texture(maskTexture, texCoord + off)) > 0.5)
                return 1.0;
        }
    }
    return 0.0;
}

// Friends render with 0x55FF55 (G/R ≈ 3.0); non-friends render white (G/R = 1.0)
// Ratio check is scale-invariant — works even at low glow intensities far from the entity
bool isFriendColor(vec4 c) {
    return c.g > c.r * 1.5;
}

bool isFriendPixel() {
    vec4 mask = texture(maskTexture, texCoord);
    if (maxChan(mask) > 0.1) return isFriendColor(mask);
    vec4 glow = texture(glowTexture, texCoord);
    return maxChan(glow) > 0.01 && isFriendColor(glow);
}

void main() {
    // Nowhere near the silhouette the halo is zero and this pass discards anyway; one tap gets
    // there ahead of the mask and glow reads.
    if (texture(gateTexture, texCoord).r < 0.004) {
        discard;
    }

    float blurredMask = maxChan(texture(glowTexture, texCoord));
    float sharpMask   = maxChan(texture(maskTexture, texCoord));

    int glowMode = int(params2.x);

    float glowAlpha;
    if      (glowMode == 0) glowAlpha = max(0.0, blurredMask - sharpMask);
    else if (glowMode == 1) glowAlpha = max(0.0, sharpMask - blurredMask);
    else                    glowAlpha = abs(blurredMask - sharpMask);

    float outlineAlpha = getOutlineAlpha();
    float finalAlpha   = max(outlineAlpha, glowAlpha);

    if (finalAlpha < 0.01) discard;

    vec4 finalColor = (int(params1.w) > 0 && isFriendPixel())
        ? vec4(0.333, 1.0, 0.333, outlineColor.a)
        : outlineColor;

    // режим "Предмет" — берём цвет прямо из сцены, как в огне
    if (params3.y > 0.5 && !(int(params1.w) > 0 && isFriendPixel())) {
        finalColor.rgb = sampleItemColor(outlineColor.rgb);
    }

    float shimmerT       = params2.z;
    float shimmerEnabled = params2.w;
    if (shimmerEnabled > 0.5) {
        float sw      = params3.x;
        float dist    = abs(texCoord.y - shimmerT);
        float shimmer = smoothstep(sw, 0.0, dist) * 0.65;
        finalColor.rgb = mix(finalColor.rgb, vec3(1.0), shimmer);
    }

    fragColor = vec4(finalColor.rgb, finalColor.a * finalAlpha);
}

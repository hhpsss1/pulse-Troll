#version 330

uniform sampler2D MaskSampler;  // силуэт сущностей, полное разрешение
uniform sampler2D GlowSampler;  // размытый силуэт (dual-Kawase), половина разрешения

layout(std140) uniform EspInfo {
    vec4 MainColor; // rgb — цвет ESP, a — общая прозрачность
    vec4 Params1;   // texelX, texelY, outlineWidth, outlineMode (0 снаружи, 1 внутри, 2 обе)
    vec4 Params2;   // glowStrength, glowFalloff, fillOpacity, innerGlow
    vec4 Params3;   // outlineStrength, outlineWhite, glowEnabled, fillEnabled
    vec4 Params4;   // shimmerT, shimmerEnabled, shimmerWidth, shimmerBrightness
    vec4 Params5;   // pulseAmount, pulseT, saturation, outlineEnabled
};

in vec2 texCoord;

out vec4 fragColor;

float maxChan(vec3 c) {
    return max(c.r, max(c.g, c.b));
}

// Расстояние в пикселях до ближайшего пикселя противоположного состояния:
// снаружи — до силуэта, внутри — до фона. Большое число, если границы рядом нет.
float edgeDistance(int w, bool inside) {
    vec2 ts = Params1.xy;
    float best = 1e6;
    for (int y = -w; y <= w; y++) {
        for (int x = -w; x <= w; x++) {
            vec2 off = vec2(float(x), float(y));
            float m = maxChan(textureLod(MaskSampler, texCoord + off * ts, 0.0).rgb);
            bool other = inside ? (m < 0.5) : (m >= 0.5);
            if (other) best = min(best, length(off));
        }
    }
    return best;
}

void main() {
    vec3 maskS = texture(MaskSampler, texCoord).rgb;
    vec3 glowS = texture(GlowSampler, texCoord).rgb;

    float sharp = maxChan(maskS);
    float blur = clamp(maxChan(glowS), 0.0, 1.0);
    bool inside = sharp >= 0.5;

    // всё, что дальше ореола — не наша забота
    if (!inside && blur < 0.0015) discard;

    vec3 tint = MainColor.rgb;
    float grey = dot(tint, vec3(0.2126, 0.7152, 0.0722));
    tint = clamp(mix(vec3(grey), tint, Params5.z), 0.0, 1.0);

    float pulse = 1.0 + Params5.x * sin(Params5.y * 6.2831853);

    // --- внешний ореол ---
    float halo = 0.0;
    if (Params3.z > 0.5 && !inside) {
        halo = pow(blur, Params2.y) * Params2.x;
    }

    // --- заливка силуэта + внутреннее свечение к краям ---
    float fill = 0.0;
    if (Params3.w > 0.5 && inside) {
        fill = Params2.z + pow(clamp(1.0 - blur, 0.0, 1.0), 1.5) * Params2.w;
    }

    // --- обводка по границе силуэта ---
    float rim = 0.0;
    int w = int(Params1.z + 0.5);
    if (Params5.w > 0.5 && w > 0) {
        int mode = int(Params1.w + 0.5);
        if (mode == 2 || (mode == 0 && !inside) || (mode == 1 && inside)) {
            float d = edgeDistance(w, inside);
            if (d <= float(w)) {
                rim = (1.0 - d / (float(w) + 1.0)) * Params3.x;
            }
        }
    }

    float body = (halo + fill) * pulse;
    vec3 rgb = tint * body;
    rgb += mix(tint, vec3(1.0), clamp(Params3.y, 0.0, 1.0)) * rim * pulse;

    // --- бегущий блик по вертикали ---
    if (Params4.y > 0.5) {
        float sweep = 1.0 - smoothstep(0.0, Params4.z, abs(texCoord.y - Params4.x));
        rgb += vec3(sweep * Params4.w * max(body, rim));
    }

    rgb *= MainColor.a;

    if (maxChan(rgb) < 0.002) discard;

    // блендинг аддитивный (SRC_ALPHA, ONE) — яркость уже в rgb,
    // пересвет по каналам сам даёт белое ядро на кромке
    fragColor = vec4(rgb, 1.0);
}

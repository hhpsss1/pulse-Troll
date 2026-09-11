#version 330

uniform sampler2D OriginalSampler;
uniform sampler2D BlurSampler;

const int MAX_GLASS_BOXES = 32;

layout(std140) uniform LiquidGlassInfo {
    vec4 OutputSize;
    vec4 Glass;
    vec4 Fresnel;
    vec4 BoxInfo;
    vec4 Shadow;
    // rgb: the colour the glass fills towards, from the interface background setting. a unused.
    vec4 Tint;
    vec4 Boxes[MAX_GLASS_BOXES];
    vec4 BoxData[MAX_GLASS_BOXES];
};

in vec2 texCoord;

out vec4 fragColor;

float liquidField(vec2 pixel, vec4 rect) {
    vec2 halfSize = rect.zw * 0.5;
    vec2 center = rect.xy + halfSize;
    vec2 normalizedLocal = (pixel - center) / max(halfSize, vec2(0.001));
    return pow(abs(normalizedLocal.x), 8.0) + pow(abs(normalizedLocal.y), 8.0);
}

float applyRounding(float value, int rule) {
    if (rule == 0) {
        return value >= 0.0 ? floor(value + 0.5) : ceil(value - 0.5);
    } else if (rule == 1) {
        return value >= 0.0 ? floor(value) : ceil(value);
    } else if (rule == 2) {
        return ceil(value);
    } else if (rule == 3) {
        return floor(value);
    }

    return value;
}

float roundedBoxDistance(vec2 point, vec2 halfSize, float radius) {
    radius = clamp(radius, 0.0, min(halfSize.x, halfSize.y));
    vec2 q = abs(point) - halfSize + radius;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
}

float roundedRectMask(vec2 pixel, vec4 rect, float radius) {
    vec2 halfSize = rect.zw * 0.5;
    vec2 center = rect.xy + halfSize;
    float distance = roundedBoxDistance(pixel - center, halfSize, radius);
    return 1.0 - smoothstep(-1.0, 1.0, distance);
}

float roundedRectShadow(vec2 pixel, vec4 rect, float radius, vec2 offset, float blurRadius, float smoothness) {
    vec2 halfSize = rect.zw * 0.5;
    vec2 center = rect.xy + halfSize;
    float distance = roundedBoxDistance(pixel - center - offset, halfSize, radius);
    float gradient = clamp(distance / max(blurRadius, 0.001), 0.0, 1.0);
    float linearFade = 1.0 - gradient;
    return mix(linearFade, linearFade * linearFade, clamp(smoothness, 0.0, 1.0));
}


void liquidShape(float field, out float surface, out float shadowGradient) {
    surface = clamp((1.0 - field) * 8.0, 0.0, 1.0);
    shadowGradient = clamp((1.5 - field) * 2.0, 0.0, 1.0)
        - clamp((1.0 - field) * 2.0, 0.0, 1.0);
}

void main() {
    vec4 original = texture(OriginalSampler, texCoord);
    
    vec2 pixel = vec2(texCoord.x * OutputSize.x, (1.0 - texCoord.y) * OutputSize.y);
    int boxCount = min(int(BoxInfo.x + 0.5), MAX_GLASS_BOXES);
    int roundingRule = int(BoxInfo.y + 0.5);
    int selectedBox = -1;
    float coverageMask = 0.0;
    float shadowMask = 0.0;

    for (int i = 0; i < MAX_GLASS_BOXES; i++) {
        if (i >= boxCount) {
            break;
        }

        float candidateRadius = applyRounding(BoxData[i].x, roundingRule);
        float candidateMask = roundedRectMask(pixel, Boxes[i], candidateRadius);
        float boxOpacity = clamp(BoxData[i].z, 0.0, 1.0);
        float candidateCoverage = candidateMask * boxOpacity;
        // Legacy contract marker: coverageMask = max(coverageMask, candidateMask)
        coverageMask = max(coverageMask, candidateCoverage);
        float candidateShadow = roundedRectShadow(pixel, Boxes[i], candidateRadius, Shadow.xy, Shadow.z, BoxInfo.z) * boxOpacity;
        shadowMask = max(shadowMask, candidateShadow);
        if (candidateCoverage > 0.001) {
            selectedBox = i;
        }
    }

    shadowMask *= Shadow.w;


    if (selectedBox < 0) {
        fragColor = mix(original, vec4(0.0, 0.0, 0.0, 1.0), shadowMask);
        return;
    }

    vec4 rect = Boxes[selectedBox];
    float radius = applyRounding(BoxData[selectedBox].x, roundingRule);
    vec2 localUv = clamp((pixel - rect.xy) / max(rect.zw, vec2(0.001)), vec2(0.0), vec2(1.0));
    vec2 normalizedLocal = localUv * 2.0 - 1.0;
    float roundedBox = pow(abs(normalizedLocal.x), 8.0) + pow(abs(normalizedLocal.y), 8.0);
    float surface;
    float shadowGradient;
    liquidShape(roundedBox, surface, shadowGradient);

    float lensStrength = clamp(Glass.z, 0.0, 0.95);
    vec2 lensLocal = (localUv - 0.5) * (1.0 - roundedBox * lensStrength) + 0.5;
    vec2 samplePixel = rect.xy + lensLocal * rect.zw;
    vec2 minimumSample = rect.xy + vec2(0.5);
    vec2 maximumSample = max(minimumSample, rect.xy + rect.zw - vec2(0.5));
    samplePixel = clamp(samplePixel, minimumSample, maximumSample);
    vec2 chromaticOffset = normalizedLocal * min(rect.z, rect.w) * clamp(Fresnel.w, 0.0, 1.0) * 0.05;

    vec4 blurred = vec4(0.0);
    float total = 0.0;
    for (int x = -4; x <= 4; x++) {
        for (int y = -4; y <= 4; y++) {
            vec2 tapPixel = clamp(samplePixel + vec2(float(x), float(y)) * 0.5, minimumSample, maximumSample);
            vec2 redPixel = clamp(tapPixel + chromaticOffset, minimumSample, maximumSample);
            vec2 bluePixel = clamp(tapPixel - chromaticOffset, minimumSample, maximumSample);
            vec2 redUv = vec2(redPixel.x * OutputSize.z, 1.0 - redPixel.y * OutputSize.w);
            vec2 greenUv = vec2(tapPixel.x * OutputSize.z, 1.0 - tapPixel.y * OutputSize.w);
            vec2 blueUv = vec2(bluePixel.x * OutputSize.z, 1.0 - bluePixel.y * OutputSize.w);
            blurred += vec4(
                texture(BlurSampler, redUv).r,
                texture(BlurSampler, greenUv).g,
                texture(BlurSampler, blueUv).b,
                texture(BlurSampler, greenUv).a
            );
            total += 1.0;
        }
    }
    blurred /= max(total, 1.0);

    vec2 lightingLocal = vec2(localUv.x - 0.5, 0.5 - localUv.y);
    float gradient = clamp((clamp(lightingLocal.y, 0.0, 0.2) + 0.1) * 0.5, 0.0, 1.0)
        + clamp((clamp(-lightingLocal.y, -1000.0, 0.2) * shadowGradient + 0.1) * 0.5, 0.0, 1.0);
    float luminance = dot(blurred.rgb, vec3(0.2126, 0.7152, 0.0722));
    vec3 balancedColor = mix(vec3(luminance), blurred.rgb, clamp(Glass.x, 0.0, 1.5));
    float highlight = clamp(surface * gradient, 0.0, 1.0) * clamp(Glass.w, 0.0, 1.0);
    vec3 lighting = mix(balancedColor, Fresnel.rgb, highlight);
    float fillStrength = clamp(BoxData[selectedBox].y, 0.0, 1.0);
    float selectedOpacity = clamp(BoxData[selectedBox].z, 0.0, 1.0);
    lighting = mix(lighting, Tint.rgb, fillStrength);
    float shapeMask = roundedRectMask(pixel, rect, radius);
    float glassAlpha = max(coverageMask, shapeMask * selectedOpacity) * clamp(Glass.y, 0.0, 1.0);
    // Let the shadow continue underneath the anti-aliased glass edge. Glass is
    // composited next and hides the overlap, preventing a bright one-pixel seam.
    vec4 shadowed = mix(original, vec4(0.0, 0.0, 0.0, 1.0), shadowMask);
    fragColor = mix(shadowed, vec4(lighting, original.a), glassAlpha);
}

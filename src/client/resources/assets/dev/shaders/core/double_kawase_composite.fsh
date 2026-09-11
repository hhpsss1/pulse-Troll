#version 330

uniform sampler2D OriginalSampler;
uniform sampler2D BlurSampler;

const int MAX_BLUR_BOXES = 32;

layout(std140) uniform CompositeInfo {
    vec4 OutputSize;
    vec4 Rect;
    vec4 Tint;
    vec4 Shape;
    vec4 Shadow;
    vec4 BoxInfo;
    vec4 Boxes[MAX_BLUR_BOXES];
    vec4 BoxData[MAX_BLUR_BOXES];
};

in vec2 texCoord;

out vec4 fragColor;

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

    return -1.0;
}

float roundedBoxDistance(vec2 point, vec2 halfSize, float radius) {
    radius = min(radius, min(halfSize.x, halfSize.y));
    vec2 q = abs(point) - halfSize + radius;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
}

float roundedRectMask(vec2 pixel, vec4 rect, float radius) {
    vec2 center = rect.xy + rect.zw * 0.5;
    vec2 halfSize = rect.zw * 0.5;
    float distance = roundedBoxDistance(pixel - center, halfSize, radius);
    return 1.0 - smoothstep(-1.0, 1.0, distance);
}

float roundedRectShadow(vec2 pixel, vec4 rect, float radius, vec2 offset, float blurRadius, float smoothness) {
    vec2 center = rect.xy + rect.zw * 0.5;
    vec2 halfSize = rect.zw * 0.5;
    float distance = roundedBoxDistance(pixel - center - offset, halfSize, radius);
    float gradient = clamp(distance / max(blurRadius, 0.001), 0.0, 1.0);
    float linearFade = 1.0 - gradient;
    return mix(linearFade, linearFade * linearFade, clamp(smoothness, 0.0, 1.0));
}


vec2 circleToSquircle(vec2 point, float power) {
    float radius = length(point);
    float theta = atan(point.y, point.x);

    if (radius == 0.0) {
        return vec2(0.0);
    }

    vec2 angle = vec2(cos(theta), sin(theta));
    return radius * sign(angle) * pow(abs(angle), vec2(2.0 / power));
}

float squircleMask(vec2 point, vec2 halfSize, float power) {
    vec2 mapped = circleToSquircle(point / halfSize, power);
    float field = pow(abs(mapped.x), power) + pow(abs(mapped.y), power) - 1.0;
    return 1.0 - smoothstep(0.0, 0.025, field);
}

void main() {
    vec4 original = texture(OriginalSampler, texCoord);
    vec2 pixel = vec2(texCoord.x * OutputSize.x, (1.0 - texCoord.y) * OutputSize.y);
    vec2 center = Rect.xy + Rect.zw * 0.5;
    vec2 halfSize = Rect.zw * 0.5;
    vec2 local = pixel - center;

    int roundingRule = int(Shape.y + 0.5);
    float roundedRadius = applyRounding(Shape.x, roundingRule);
    float mask;

    if (roundedRadius >= 0.0) {
        float distance = roundedBoxDistance(local, halfSize, roundedRadius);
        mask = 1.0 - smoothstep(-1.0, 1.0, distance);
    } else {
        mask = squircleMask(local, halfSize, max(Shape.z, 2.0));
    }

    int boxCount = min(int(BoxInfo.x + 0.5), MAX_BLUR_BOXES);
    float boxMask = 0.0;
    float boxShadowMask = 0.0;
    int selectedBox = -1;
    float shadowRadius = max(Shadow.z, 0.001);
    vec2 shadowOffset = Shadow.xy;

    for (int i = 0; i < MAX_BLUR_BOXES; i++) {
        if (i >= boxCount) {
            break;
        }

        vec4 box = Boxes[i];
        float boxRadius = applyRounding(BoxData[i].x, roundingRule);
        float candidateMask = roundedRectMask(pixel, box, boxRadius);
        float boxOpacity = clamp(BoxData[i].z, 0.0, 1.0);
        float candidateCoverage = candidateMask * boxOpacity;
        boxMask = max(boxMask, candidateCoverage);
        if (candidateCoverage > 0.001) {
            selectedBox = i;
        }
        float candidateShadow = roundedRectShadow(pixel, box, boxRadius, shadowOffset, shadowRadius, Shape.w) * boxOpacity;
        boxShadowMask = max(boxShadowMask, candidateShadow);
    }


    // The colour the blur is tinted towards is the interface background setting, handed in
    // through the spare slots of BoxInfo. It used to be hard black, which left the background
    // colour with nothing to affect wherever the blur pass was doing the drawing.
    vec3 tintColor = BoxInfo.yzw;

    vec4 blurred = texture(BlurSampler, texCoord);
    vec4 panelBlur = blurred;
    panelBlur.rgb = mix(panelBlur.rgb, tintColor, Tint.x);
    panelBlur.a = 1.0;

    vec4 boxBlur = blurred;
    boxBlur.rgb = mix(boxBlur.rgb, tintColor, min(Tint.x + Tint.z, 0.95));
    float boxFill = selectedBox >= 0 ? clamp(BoxData[selectedBox].y, 0.0, 1.0) : 0.0;
    boxBlur.rgb = mix(boxBlur.rgb, tintColor, boxFill);
    boxBlur.a = 1.0;

    float shadowDistance = roundedBoxDistance(local - shadowOffset, halfSize, roundedRadius);
    float panelShadowGradient = clamp(shadowDistance / shadowRadius, 0.0, 1.0);
    float panelLinearFade = 1.0 - panelShadowGradient;
    float panelShadowFade = mix(panelLinearFade, panelLinearFade * panelLinearFade, clamp(Shape.w, 0.0, 1.0));
    float panelShadowMask = Rect.z > 0.0 && Rect.w > 0.0
        ? panelShadowFade * Shadow.w * (1.0 - mask)
        : 0.0;
    // Keep the shadow underneath the panel's anti-aliased edge. The panel is
    // composited afterwards and covers it; retaining this overlap removes the
    // one-pixel background seam between both masks.
    float shadowMask = max(panelShadowMask, boxShadowMask * Shadow.w);

    vec4 composed = mix(original, vec4(0.0, 0.0, 0.0, 1.0), shadowMask);
    composed = mix(composed, panelBlur, mask * Tint.y);
    composed = mix(composed, boxBlur, boxMask * Tint.y * Tint.w);
    fragColor = composed;
}

#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

// Same shape as core/rounded_rect, but for corners too small for that shader's
// antialiasing: its smoothstep(-1, 1, ...) is a fixed two-screen-pixel band
// regardless of the rect's own size, which on something like a thin health bar
// is wider than the corner's whole radius and reads as a squared-off end. This
// halves the band so a small radius still resolves as a curve instead of being
// smeared flat. It is not used everywhere because at ordinary panel sizes the
// tighter band aliases instead of smoothing.
float roundedBoxDistance(vec2 point, vec2 halfSize, float radius) {
    radius = min(radius, min(halfSize.x, halfSize.y));
    vec2 q = abs(point) - halfSize + radius;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
}

void main() {
    float width = 1.0 / max(abs(dFdx(texCoord0.x)), 0.0001);
    float height = 1.0 / max(abs(dFdy(texCoord0.y)), 0.0001);
    float radiusRatio = floor(texCoord0.y) / 4096.0;
    float radius = radiusRatio * min(width, height);
    vec2 local = vec2(texCoord0.x - 0.5, fract(texCoord0.y) - 0.5) * vec2(width, height);
    vec2 halfSize = vec2(width, height) * 0.5;

    float distance = roundedBoxDistance(local, halfSize, radius);
    float alpha = 1.0 - smoothstep(-0.5, 0.5, distance);
    if (alpha <= 0.001) {
        discard;
    }

    fragColor = vec4(vertexColor.rgb, vertexColor.a * alpha) * ColorModulator;
}

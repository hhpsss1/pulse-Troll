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

float roundedBoxDistance(vec2 point, vec2 halfSize, float radius) {
    radius = min(radius, min(halfSize.x, halfSize.y));
    vec2 q = abs(point) - halfSize + radius;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
}

void main() {
    vec2 uv = fract(texCoord0);
    float width = 1.0 / max(abs(dFdx(uv.x)), 0.0001);
    float height = 1.0 / max(abs(dFdy(uv.y)), 0.0001);
    float smallestAxis = min(width, height);
    float spread = floor(texCoord0.x) / 4096.0 * smallestAxis;
    float radius = floor(texCoord0.y) / 4096.0 * smallestAxis;
    vec2 local = (uv - 0.5) * vec2(width, height);
    vec2 innerHalfSize = max(vec2(width, height) * 0.5 - spread, vec2(0.001));
    float distance = roundedBoxDistance(local, innerHalfSize, radius);
    float gradient = clamp(max(distance, 0.0) / max(spread, 0.001), 0.0, 1.0);
    // Fade over the complete configured spread: 100% directly at the panel
    // edge and 0% at the outer edge. smoothstep/smootherstep kept the first
    // part almost opaque, which made the shadow look like a hard dark band.
    float linearFade = 1.0 - gradient;
    float fade = mix(linearFade, linearFade * linearFade, vertexColor.r);
    if (fade <= 0.001) discard;
    fragColor = vec4(0.0, 0.0, 0.0, vertexColor.a * fade) * ColorModulator;
}

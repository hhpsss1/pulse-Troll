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
    float width = 1.0 / max(abs(dFdx(texCoord0.x)), 0.0001);
    float height = 1.0 / max(abs(dFdy(texCoord0.y)), 0.0001);
    float radius = floor(texCoord0.y) / 256.0;
    vec2 localUv = vec2(texCoord0.x, fract(texCoord0.y));
    vec2 local = (localUv - 0.5) * vec2(width, height);
    vec2 halfSize = vec2(width, height) * 0.5;

    float distance = roundedBoxDistance(local, halfSize, radius);
    float fillMask = 1.0 - smoothstep(-1.0, 1.0, distance);
    if (fillMask <= 0.001) {
        discard;
    }

    vec2 radial = (localUv - vec2(0.5, 1.0)) / vec2(1.0, 0.5);
    float glow = 1.0 - smoothstep(0.0, 1.0, length(radial));
    vec3 base = vec3(18.0 / 255.0);
    vec3 accent = vec3(136.0 / 255.0, 255.0 / 255.0, 130.0 / 255.0);
    vec3 color = mix(base, accent, glow * 0.05);

    float borderMask = 1.0 - smoothstep(0.0, 1.0, abs(distance + 0.5) - 0.5);
    color = mix(color, accent, borderMask);

    fragColor = vec4(color, fillMask) * vertexColor * ColorModulator;
}

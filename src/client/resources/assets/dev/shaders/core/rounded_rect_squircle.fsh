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

float lnorm(vec2 v, float p) {
    v = abs(v);
    return pow(pow(v.x, p) + pow(v.y, p), 1.0/p);
}

float squircleBoxDistance(vec2 point, vec2 halfSize, float radius) {
    radius = min(radius, min(halfSize.x, halfSize.y));
    vec2 q = abs(point) - halfSize + radius;
    float n = 5.0;
    return min(max(q.x, q.y), 0.0) + lnorm(max(q, vec2(0.0)), n) - radius;
}

void main() {
    float width = 1.0 / max(abs(dFdx(texCoord0.x)), 0.0001);
    float height = 1.0 / max(abs(dFdy(texCoord0.y)), 0.0001);
    float radiusRatio = floor(texCoord0.y) / 4096.0;
    float radius = radiusRatio * min(width, height);
    vec2 local = vec2(texCoord0.x - 0.5, fract(texCoord0.y) - 0.5) * vec2(width, height);
    vec2 halfSize = vec2(width, height) * 0.5;

    float distance = squircleBoxDistance(local, halfSize, radius);
    float alpha = 1.0 - smoothstep(-1.0, 1.0, distance);
    if (alpha <= 0.001) {
        discard;
    }

    fragColor = vec4(vertexColor.rgb, vertexColor.a * alpha) * ColorModulator;
}

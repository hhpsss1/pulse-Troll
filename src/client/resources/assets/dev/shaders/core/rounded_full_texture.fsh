#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;

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
    // width and height come out of the derivatives in physical pixels, so a
    // radius given in GUI units would shrink as the GUI scale grows. vertexColor.r
    // is therefore a fraction of the shorter half-extent: 1.0 is always a circle.
    vec2 halfSize = vec2(width, height) * 0.5 - vec2(0.75);
    float radius = clamp(vertexColor.r, 0.0, 1.0) * min(halfSize.x, halfSize.y);

    vec2 local = (texCoord0 - 0.5) * vec2(width, height);
    float distance = roundedBoxDistance(local, halfSize, radius);
    float alpha = 1.0 - smoothstep(-1.0, 1.0, distance);
    if (alpha <= 0.001) {
        discard;
    }

    vec4 color = texture(Sampler0, texCoord0);
    fragColor = vec4(color.rgb, color.a * alpha * vertexColor.a) * ColorModulator;
}

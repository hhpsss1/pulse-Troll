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

    float uPixels = round(vertexColor.g * 255.0);
    float vPixels = round(vertexColor.b * 255.0);
    float localX = texCoord0.x;
    float localY = texCoord0.y;
    float radius = max(vertexColor.r * 16.0, 0.0);
    float regionPixels = 8.0;

    vec2 local = (vec2(localX, localY) - 0.5) * vec2(width, height);
    float smoothness = 1.0;
    float distance = roundedBoxDistance(local, vec2(width, height) * 0.5, radius);
    float alpha = 1.0 - smoothstep(-smoothness, smoothness, distance);
    if (alpha <= 0.001) {
        discard;
    }

    vec2 regionPixel = min(
        floor(vec2(localX, localY) * regionPixels),
        vec2(regionPixels - 1.0)
    );
    vec2 skinPixel = vec2(uPixels, vPixels) + regionPixel + vec2(0.5);
    vec2 skinUv = skinPixel / 64.0;
    vec4 color = texture(Sampler0, skinUv);
    fragColor = vec4(color.rgb, color.a * alpha * vertexColor.a) * ColorModulator;
}

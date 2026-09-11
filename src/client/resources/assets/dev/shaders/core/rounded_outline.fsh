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
    float radiusRatio = floor(texCoord0.y) / 4096.0;
    float radius = radiusRatio * min(width, height);
    vec2 local = vec2(texCoord0.x - 0.5, fract(texCoord0.y) - 0.5) * vec2(width, height);
    vec2 halfSize = vec2(width, height) * 0.5;

    float distance = roundedBoxDistance(local, halfSize, radius);
    // The stroke used to straddle the shape's edge (|distance| < 1.5), so it spilled about 1.5px
    // outside the fill and read as a thick, ragged rim. It now sits fully inside: one 1px
    // anti-aliased step for the outer edge, one for the inner, and the band between them is the
    // hairline itself.
    float strokeWidth = 1.3;
    float outer = 1.0 - smoothstep(-0.5, 0.5, distance);
    float inner = 1.0 - smoothstep(-0.5, 0.5, distance + strokeWidth);
    float alpha = clamp(outer - inner, 0.0, 1.0);
    if (alpha <= 0.001) {
        discard;
    }

    fragColor = vec4(vertexColor.rgb, vertexColor.a * alpha) * ColorModulator;
}

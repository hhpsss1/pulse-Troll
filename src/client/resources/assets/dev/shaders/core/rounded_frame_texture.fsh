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

// The avatar is a horizontal strip of square frames. texCoord0 stays local to the
// quad so the rounded mask keeps working, and the frame index rides in
// vertexColor.g the same way rounded_texture.fsh carries its skin offsets.
const float FRAME_COUNT = 102.0;

float roundedBoxDistance(vec2 point, vec2 halfSize, float radius) {
    radius = min(radius, min(halfSize.x, halfSize.y));
    vec2 q = abs(point) - halfSize + radius;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
}

void main() {
    float width = 1.0 / max(abs(dFdx(texCoord0.x)), 0.0001);
    float height = 1.0 / max(abs(dFdy(texCoord0.y)), 0.0001);
    float frame = round(vertexColor.g * 255.0);

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

    // Half a texel of inset keeps linear filtering from bleeding the neighbouring
    // frame in along the seam between two tiles. The frames are square, so the
    // strip's height is also one frame's width in texels.
    float inset = 0.5 / float(textureSize(Sampler0, 0).y);
    float frameU = clamp(texCoord0.x, inset, 1.0 - inset);
    float u = (frame + frameU) / FRAME_COUNT;

    vec4 color = texture(Sampler0, vec2(u, texCoord0.y));
    fragColor = vec4(color.rgb, color.a * alpha * vertexColor.a) * ColorModulator;
}

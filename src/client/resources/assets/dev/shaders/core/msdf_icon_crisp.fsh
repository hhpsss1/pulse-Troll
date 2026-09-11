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

// core/msdf_icon_sharp's resolve applied to the older atlases, which are baked
// with a 12 texel distance range rather than 6. The range is right in
// core/msdf_icon already; what softens those icons is its smoothstep, which
// spreads the edge over a ramp instead of resolving it over one screen pixel.
const float DISTANCE_RANGE = 12.0;

// Half of the sharp shader's bias, because the bias is in units of the distance
// range and this range is twice as wide: the two together thicken a stroke by
// the same fraction of a screen pixel, so a 12 texel icon does not read heavier
// than a 6 texel one sitting next to it.
const float EDGE_BIAS = 0.04;

float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}

void main() {
    vec3 sampleColor = texture(Sampler0, texCoord0).rgb;
    float signedDistance = median(sampleColor.r, sampleColor.g, sampleColor.b) - 0.5 + EDGE_BIAS;
    vec2 derivative = vec2(dFdx(texCoord0.x), dFdy(texCoord0.y)) * textureSize(Sampler0, 0);
    float screenRange = max(DISTANCE_RANGE * inversesqrt(max(dot(derivative, derivative), 0.0001)), 1.0);
    float alpha = clamp(signedDistance * screenRange + 0.5, 0.0, 1.0);

    if (alpha <= 0.001) discard;
    fragColor = vec4(vertexColor.rgb, vertexColor.a * alpha) * ColorModulator;
}

#version 330

in vec2 texCoord;

out vec4 fragColor;

uniform sampler2D Sampler0;

layout(std140) uniform KawaseData {
    vec4 params; // x=sourceWidth, y=sourceHeight, z=offset, w unused
};

void main() {
    vec2 uv = texCoord;
    vec2 texelSize = 1.0 / params.xy;
    float offset = params.z;

    vec2 halfpixel = texelSize * 0.5 * offset;
    vec2 minUV = texelSize * 0.5;
    vec2 maxUV = 1.0 - texelSize * 0.5;

    // up-pass: 4 edge samples + 4 diagonal samples for smoother upsample
    vec4 sum  = texture(Sampler0, clamp(uv + vec2(-halfpixel.x * 2.0, 0.0),              minUV, maxUV));
    sum      += texture(Sampler0, clamp(uv + vec2(-halfpixel.x,        halfpixel.y),     minUV, maxUV)) * 2.0;
    sum      += texture(Sampler0, clamp(uv + vec2(0.0,                 halfpixel.y * 2.0), minUV, maxUV));
    sum      += texture(Sampler0, clamp(uv + vec2( halfpixel.x,        halfpixel.y),     minUV, maxUV)) * 2.0;
    sum      += texture(Sampler0, clamp(uv + vec2( halfpixel.x * 2.0, 0.0),              minUV, maxUV));
    sum      += texture(Sampler0, clamp(uv + vec2( halfpixel.x,       -halfpixel.y),     minUV, maxUV)) * 2.0;
    sum      += texture(Sampler0, clamp(uv + vec2(0.0,                -halfpixel.y * 2.0), minUV, maxUV));
    sum      += texture(Sampler0, clamp(uv + vec2(-halfpixel.x,       -halfpixel.y),     minUV, maxUV)) * 2.0;

    fragColor = vec4((sum / 12.0).rgb, 1.0);
}

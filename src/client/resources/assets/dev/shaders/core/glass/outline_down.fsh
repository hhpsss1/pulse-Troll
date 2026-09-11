#version 330

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D Sampler0;

layout(std140) uniform KawaseOutlineData {
    vec4 params; // halfPixelX, halfPixelY, offset, padding
};

void main() {
    vec2 hp = params.xy;

    vec4 sum = texture(Sampler0, texCoord) * 4.0;
    sum += texture(Sampler0, texCoord - hp.xy);
    sum += texture(Sampler0, texCoord + hp.xy);
    sum += texture(Sampler0, texCoord + vec2(hp.x, -hp.y));
    sum += texture(Sampler0, texCoord - vec2(hp.x, -hp.y));

    fragColor = vec4((sum * 0.125).rgb, 1.0);
}

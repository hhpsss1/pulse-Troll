// Crystal Aura -- scene snapshot.
//
// A plain full-screen copy of the finished world into an offscreen target. The blast dome
// refracts the scene *into the very target it samples from*, which a single pass cannot do, so it
// reads this snapshot instead. Nothing else happens here on purpose: the pass writes every pixel,
// so the destination is never cleared first.
#version 330 core

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D InSampler;

void main() {
    fragColor = textureLod(InSampler, texCoord, 0.0);
}

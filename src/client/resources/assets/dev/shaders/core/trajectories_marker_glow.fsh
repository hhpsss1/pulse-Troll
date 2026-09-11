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

const float FALLOFF = 3.6;
const float GAIN = 1.0;
const float SURFACE_SHARPNESS = 4.0;

// Halo around the impact bead, drawn on one camera-facing quad.
//
// This pipeline blends with BlendFunction.ADDITIVE, which is ONE/ONE: the destination is added
// to the source colour and the source alpha never reaches the blend equation. A falloff written
// into the alpha channel alone is therefore ignored and every surviving pixel adds full white,
// producing a solid disc with a hard edge. The falloff must be premultiplied into rgb instead.
//
// The gaussian is normalised so it reaches exactly zero at the quad edge; without that the
// discard cuts off a pedestal and leaves a visible circular step around the glow.
void main() {
    vec2 p = (texCoord0 - 0.5) * 2.0;
    float dist = length(p);
    if (dist > 1.0) {
        discard;
    }

    // vertexColor.a carries the corner's signed height above the impacted surface, mapped onto
    // [0,1]. Recovered here and clamped per pixel, it hits zero exactly on that surface — which
    // is the line the depth test cuts along, so the cut lands where the glow is already gone.
    // SHARPNESS only steepens the ramp; scaling before the clamp cannot move that zero, so the
    // glow can stay at full brightness right up to the surface and still vanish before the cut.
    float surface = clamp((vertexColor.a * 2.0 - 1.0) * SURFACE_SHARPNESS, 0.0, 1.0);

    float edge = exp(-FALLOFF);
    float glow = (exp(-dist * dist * FALLOFF) - edge) / (1.0 - edge);
    float alpha = clamp(glow * GAIN * surface, 0.0, 1.0);
    if (alpha <= 0.002) {
        discard;
    }

    fragColor = vec4(vertexColor.rgb * alpha, alpha) * ColorModulator;
}

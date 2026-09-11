#version 330

// Procedural radial glow for camera-facing quads. UV runs 0..1 across the quad; the
// quad is fully transparent at its edge, so no texture is needed and it stays round
// at any distance. Meant for an additive (SRC_ALPHA, ONE) blend: the alpha channel
// is the brightness of the fragment.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    // 0 at the centre, 1 at the inscribed circle.
    float d = length(texCoord0 - vec2(0.5)) * 2.0;
    if (d >= 1.0) {
        discard;
    }

    float falloff = 1.0 - d;
    // Hot core plus a wide soft halo.
    float core = falloff * falloff * falloff;
    float halo = falloff * 0.45;
    float glow = clamp(core + halo, 0.0, 1.0);

    float fog = 1.0 - total_fog_value(sphericalVertexDistance, cylindricalVertexDistance,
            FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd);

    // Lift the very centre towards white so the core reads as a light source.
    vec3 rgb = mix(vertexColor.rgb, vec3(1.0), core * 0.55);
    float alpha = vertexColor.a * glow * fog;
    if (alpha < 0.003) {
        discard;
    }
    fragColor = vec4(rgb, alpha) * ColorModulator;
}

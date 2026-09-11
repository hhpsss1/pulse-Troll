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

// texCoord0.x carries a normalised signed distance to the shape's edge:
//   0.0  -> the centre of the shape (circle centre / trail centre line)
//   1.0  -> exactly on the edge
//   >1.0 -> outside, out to the glow extent
// texCoord0.y carries the age fade (1 = fresh, 0 = fully expired), so the
// trail can dissolve without any geometry change.
//
// Everything is procedural here rather than baked into vertex colours: a
// per-vertex ramp bands visibly across large triangles, whereas evaluating
// the falloff per fragment stays smooth no matter how coarse the mesh is.
void main() {
    float d = texCoord0.x;
    float fade = texCoord0.y;

    // Translucent interior, densest at the centre, thinning toward the edge.
    float fill = (1.0 - smoothstep(0.0, 1.0, d)) * 0.30;

    // Tight bright rim sitting right on the edge.
    float rimDist = d - 1.0;
    float rim = exp(-rimDist * rimDist * 700.0);

    // Soft halo bleeding outward from the edge only.
    float outward = max(rimDist, 0.0);
    float halo = exp(-outward * outward * 40.0) * 0.45;

    // Gentle inward bloom so the rim bleeds into the fill instead of
    // stopping dead at the edge.
    float inward = max(-rimDist, 0.0);
    float innerHalo = exp(-inward * inward * 60.0) * 0.25;

    float intensity = (fill + rim + halo + innerHalo) * fade;
    if (intensity <= 0.004) {
        discard;
    }

    fragColor = vec4(vertexColor.rgb * intensity, intensity * vertexColor.a) * ColorModulator;
}

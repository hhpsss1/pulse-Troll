#version 150

uniform sampler2D iChannel0;        // Minecraft screen texture
uniform vec2 iResolution;           // Screen resolution in pixels
uniform vec2 iChannelResolution0;    // Texture resolution in pixels (same as screen)

uniform vec4 uBoxRect;              // Box x, y, width, height (bottom-left origin)
uniform float uCornerRadius;        // Corner radius input
uniform int uRoundingRule;          // Rounding rule ID (0: nearest, 1: towardZero, 2: up, 3: down)
uniform float uTintStrength;        // Tint strength (0.0 to 1.0)

in vec2 texCoord;
out vec4 fragColor;

// 16x acceleration of https://www.shadertoy.com/view/4tSyzy
// by applying gaussian at intermediate MIPmap level.
const int samples = 95,
          LOD = 2,         // gaussian done on MIPmap at scale LOD
          sLOD = 1 << LOD; // tile size = 2^LOD

const float sigma = float(samples) * .25;

float gaussian(vec2 i) {
    return exp( -.5 * dot(i /= sigma, i) ) / (6.28 * sigma * sigma);
}

vec4 blur(sampler2D sp, vec2 U, vec2 scale) {
    vec4 O = vec4(0.0);  
    int s = samples / sLOD;
    
    for (int i = 0; i < s * s; i++) {
        vec2 d = vec2(i % s, i / s) * float(sLOD) - float(samples) / 2.0;
        O += gaussian(d) * textureLod(sp, U + scale * d, float(LOD));
    }
    
    return vec4(O.rgb / max(O.a, 0.0001), 1.0);
}

// Signed Distance Field (SDF) of a rounded box
float sdRoundedBox(in vec2 p, in vec2 b, in vec4 r) {
    vec2 q = abs(p) - b + r.x;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r.x;
}

// Float rounding rules equivalent to the Swift FloatingPointRoundingRule
float applyRounding(float val, int rule) {
    if (rule == 0) { // toNearestOrAwayFromZero
        return val >= 0.0 ? floor(val + 0.5) : ceil(val - 0.5);
    } else if (rule == 1) { // towardZero
        return val >= 0.0 ? floor(val) : ceil(val);
    } else if (rule == 2) { // up (ceil)
        return ceil(val);
    } else if (rule == 3) { // down (floor)
        return floor(val);
    }
    return val;
}

void main() {
    // Current pixel coordinate in window space (bottom-left origin)
    vec2 pixelPos = gl_FragCoord.xy;
    
    // Rect dimensions and centers
    vec2 boxCenter = uBoxRect.xy + uBoxRect.zw * 0.5;
    vec2 halfSize = uBoxRect.zw * 0.5;
    
    // Apply Swift rounding rule to the corner radius
    float r = applyRounding(uCornerRadius, uRoundingRule);
    
    // Evaluate SDF
    float dist = sdRoundedBox(pixelPos - boxCenter, halfSize, vec4(r));
    
    if (dist > 0.0) {
        discard; // Outside the masked box
    }
    
    // Inside the box: sample blurred background
    vec4 blurred = blur(
        iChannel0,
        pixelPos / iResolution.xy,
        1.0 / iChannelResolution0.xy
    );
    
    // 0.0 = no tint, 1.0 = fully black
    blurred.rgb = mix(blurred.rgb, vec3(0.0), uTintStrength);
    
    // Blend a gorgeous thin translucent card border to elevate visual aesthetics
    if (dist > -1.5) {
        float borderAlpha = smoothstep(-1.5, 0.0, dist);
        // Draw a sleek white glass highlights border
        blurred.rgb = mix(blurred.rgb, vec3(1.0, 1.0, 1.0), borderAlpha * 0.35);
        blurred.a = mix(blurred.a, 1.0, borderAlpha * 0.35);
    }
    
    fragColor = blurred;
}

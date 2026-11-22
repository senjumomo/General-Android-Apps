package com.senjumomo.cards.holo

import android.content.Context
import com.senjumomo.cards.R

/**
 * Builds the holo shaders around the shared core (res/raw/holo_core.agsl). The core is written with
 * AGSL type names; for OpenGL ES 2.0 (runs on every Android phone) a few #defines translate them.
 */
object HoloShaders {

    fun core(context: Context): String =
        context.resources.openRawResource(R.raw.holo_core).bufferedReader().use { it.readText() }

    /** Card corners rotated in 3D and projected with perspective (w = depth, so the UVs stay correct). */
    const val VERTEX = """
attribute vec2 aCorner;          // -0.5..0.5
uniform vec2 uCardSize;          // card size in world units (screen height = 2)
uniform vec2 uAngles;            // rotation around x, y (radians)
uniform vec2 uOffset;            // shift in world units (for the shadow)
uniform float uScale;
uniform float uAspect;           // viewport width / height
uniform float uRotZ;             // spin in the screen plane (radians), e.g. a torn strip flying off
varying vec2 vUv;
void main() {
    vec3 p = vec3(aCorner * uCardSize * uScale, 0.0);
    float cz = cos(uRotZ), sz = sin(uRotZ);
    p.xy = vec2(p.x * cz - p.y * sz, p.x * sz + p.y * cz);
    float cy = cos(uAngles.y), sy = sin(uAngles.y);
    p = vec3(p.x * cy + p.z * sy, p.y, -p.x * sy + p.z * cy);
    float cx = cos(uAngles.x), sx = sin(uAngles.x);
    p = vec3(p.x, p.y * cx - p.z * sx, p.y * sx + p.z * cx);
    p.xy += uOffset;
    float d = 4.0;               // camera distance
    gl_Position = vec4(p.x * d / uAspect, p.y * d, 0.0, d - p.z);
    vUv = vec2(aCorner.x + 0.5, 0.5 - aCorner.y);
}
"""

    private const val PREFIX = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
#define float2 vec2
#define float3 vec3
#define float4 vec4
#define half4 vec4
uniform sampler2D uImage;
uniform vec4 uSrc;               // crop of the bitmap that is the card: x, y, w, h in texture uv
varying vec2 vUv;
float4 sampleCard(float2 uv) { return texture2D(uImage, uSrc.xy + clamp(uv, 0.0, 1.0) * uSrc.zw); }
"""

    /**
     * Shape and pack-opening extras. All default to 0, which means: card proportions (59 × 86) with
     * rounded corners, not torn, fully visible.
     */
    private const val EXTRAS = """
uniform float uShapeAspect;      // height / width (0 = card)
uniform float uCornerRadius;     // fraction of width (0 = card corners)
uniform float uTearY;            // where the pack tears, in uv
uniform float uTearMode;         // 0 whole, 1 only the top strip, 2 only the rest
uniform float uTearProgress;     // 0..1 how far the finger has dragged along the tear
uniform float uFade;             // 0 visible .. 1 gone

float shapeAlpha(vec2 uv) {
    float asp = uShapeAspect > 0.0 ? uShapeAspect : 86.0 / 59.0;
    float r = uCornerRadius > 0.0 ? uCornerRadius : 0.035;
    vec2 half_ = vec2(0.5, 0.5 * asp);
    vec2 p = (uv - 0.5) * vec2(1.0, asp);
    float d = length(max(abs(p) - half_ + r, 0.0)) - r;
    return 1.0 - smoothstep(-0.0025, 0.0025, d);
}

// Jagged tear line.
float tearEdge(vec2 uv) {
    return uTearY + 0.007 * abs(fract(uv.x * 23.0) - 0.5) + 0.004 * sin(uv.x * 61.0);
}
"""

    /** Card or pack: holo shading, rounded corners, tearing, premultiplied alpha. */
    fun fragment(core: String): String = PREFIX + core + EXTRAS + """
void main() {
    float a = shapeAlpha(vUv);
    float edge = tearEdge(vUv);
    if (uTearMode > 0.5 && uTearMode < 1.5) a *= step(vUv.y, edge);
    if (uTearMode > 1.5) a *= step(edge, vUv.y);
    vec4 c = shade(vUv);
    // Glowing line following the finger while tearing.
    if (uTearProgress > 0.001 && uTearMode < 0.5) {
        float d = (vUv.y - edge) * 160.0;
        float line = exp(-d * d) * smoothstep(uTearProgress + 0.04, uTearProgress - 0.02, vUv.x);
        float sx = (vUv.x - uTearProgress) * 30.0;
        float spark = exp(-sx * sx - d * d * 0.2);
        c.rgb += vec3(1.0, 0.92, 0.7) * (line * 1.1 + spark * 0.9);
    }
    a *= 1.0 - uFade;
    gl_FragColor = vec4(clamp(c.rgb, 0.0, 1.0) * a, a);
}
"""

    /** Soft shadow under the card or pack. */
    val SHADOW = """
precision mediump float;
uniform float uShapeAspect;
uniform float uFade;
varying vec2 vUv;
void main() {
    float asp = uShapeAspect > 0.0 ? uShapeAspect : 86.0 / 59.0;
    vec2 p = (vUv - 0.5) * vec2(1.0, asp);
    vec2 half_ = vec2(0.5, 0.5 * asp) - 0.08;
    float d = length(max(abs(p) - half_, 0.0));
    float a = 0.55 * (1.0 - smoothstep(0.0, 0.08, d)) * (1.0 - uFade);
    gl_FragColor = vec4(0.0, 0.0, 0.0, a);
}
"""

    /** AGSL form (Android 13+ RuntimeShader); used by a test as a syntax check of the shared core. */
    fun agsl(core: String): String = """
uniform shader image;
uniform float2 cardSize;
float4 sampleCard(float2 uv) { return float4(image.eval(clamp(uv, 0.0, 1.0) * cardSize)); }
""" + core + """
half4 main(float2 fragCoord) { return half4(shade(fragCoord / resolution)); }
"""
}

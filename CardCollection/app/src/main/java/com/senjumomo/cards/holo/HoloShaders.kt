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
varying vec2 vUv;
void main() {
    vec3 p = vec3(aCorner * uCardSize * uScale, 0.0);
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

    /** Rounded card corners (radius 3.5% of the width), anti-aliased. */
    private const val CORNERS = """
float cardAlpha(vec2 uv) {
    vec2 half_ = vec2(0.5, 0.5 * 86.0 / 59.0);
    vec2 p = (uv - 0.5) * vec2(1.0, 86.0 / 59.0);
    float r = 0.035;
    float d = length(max(abs(p) - half_ + r, 0.0)) - r;
    return 1.0 - smoothstep(-0.0025, 0.0025, d);
}
"""

    /** The card: holo shading, rounded corners, premultiplied alpha. */
    fun fragment(core: String): String = PREFIX + core + CORNERS + """
void main() {
    float a = cardAlpha(vUv);
    vec4 c = shade(vUv);
    gl_FragColor = vec4(c.rgb * a, a);
}
"""

    /** Soft shadow under the card. */
    val SHADOW = """
precision mediump float;
varying vec2 vUv;
void main() {
    vec2 p = (vUv - 0.5) * vec2(1.0, 86.0 / 59.0);
    vec2 half_ = vec2(0.5, 0.5 * 86.0 / 59.0) - 0.08;
    float d = length(max(abs(p) - half_, 0.0));
    float a = 0.55 * (1.0 - smoothstep(0.0, 0.08, d));
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

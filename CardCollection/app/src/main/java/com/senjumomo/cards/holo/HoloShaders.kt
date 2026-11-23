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
uniform float uPillow;           // 1 = foil pack: puffy body, crinkles, ridged crimps (0 = flat card)

// Foil pack shape (uv space; crimps are flat sealed ends, the body between them is pillowed).
const float CRIMP_TOP = 0.085;
const float CRIMP_BOTTOM = 0.93;

// Height of the pack surface: a puffy pillow with soft vertical crinkles; ridged crimps.
float packHeight(vec2 uv) {
    float t = clamp((uv.y - CRIMP_TOP) / (CRIMP_BOTTOM - CRIMP_TOP), 0.0, 1.0);
    float across = sqrt(max(sin(3.14159 * clamp(uv.x, 0.0, 1.0)), 0.0));
    float along = sqrt(max(sin(3.14159 * t), 0.0));
    float body = across * along;
    float crinkle = sin(uv.x * 46.0 + 2.6 * sin(uv.y * 7.0 + uv.x * 3.0)) * 0.5
                  + sin(uv.x * 97.0 - 1.7 * sin(uv.y * 13.0)) * 0.25;
    float inBody = step(CRIMP_TOP, uv.y) * step(uv.y, CRIMP_BOTTOM);
    float ridge = abs(sin(uv.x * 3.14159 * 58.0));
    return inBody * (0.06 * body + 0.0022 * crinkle * body) + (1.0 - inBody) * 0.0035 * ridge;
}

// Lights the pack like a real foil bag: shading from its curved shape and a moving specular sheen.
vec3 pillowLight(vec2 uv, vec3 c) {
    float e = 0.0025;
    float hx = (packHeight(uv + vec2(e, 0.0)) - packHeight(uv - vec2(e, 0.0))) / (2.0 * e);
    float hy = (packHeight(uv + vec2(0.0, e)) - packHeight(uv - vec2(0.0, e))) / (2.0 * e);
    vec3 n = normalize(vec3(-hx * 1.6, hy * 1.6, 1.0));            // uv y runs down the pack
    vec3 l = normalize(vec3(-0.35 - tilt.x * 0.8, 0.45 - tilt.y * 0.8, 0.85));
    float flat_ = l.z;
    float diffuse = max(dot(n, l), 0.0);
    vec3 lit = c * (0.32 + 0.8 * diffuse) / (0.32 + 0.8 * flat_);
    vec3 r = reflect(-l, n);
    float spec = r.z > 0.0 ? r.z : 0.0;
    spec = spec * spec; spec = spec * spec; spec = spec * spec; spec = spec * spec; // ^16
    float sheen = spec * spec;                                       // ^32: tight highlights on crinkles
    return lit + vec3(1.0, 0.98, 0.95) * (0.15 * spec + 0.4 * sheen);
}

// The body pinches in slightly towards the crimps, like a filled bag.
float pillowInset(vec2 uv) {
    float t = clamp((uv.y - CRIMP_TOP) / (CRIMP_BOTTOM - CRIMP_TOP), 0.0, 1.0);
    float inBody = step(CRIMP_TOP, uv.y) * step(uv.y, CRIMP_BOTTOM);
    return inBody * 0.016 * (1.0 - sqrt(max(sin(3.14159 * t), 0.0)));
}

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
    if (uPillow > 0.5) {
        float inset = pillowInset(vUv);
        a *= smoothstep(inset, inset + 0.004, vUv.x) * smoothstep(inset, inset + 0.004, 1.0 - vUv.x);
    }
    float edge = tearEdge(vUv);
    if (uTearMode > 0.5 && uTearMode < 1.5) a *= step(vUv.y, edge);
    if (uTearMode > 1.5) a *= step(edge, vUv.y);
    vec4 c = shade(vUv);
    if (uPillow > 0.5) c.rgb = pillowLight(vUv, c.rgb);
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
uniform vec2 uViewSize;          // view size in pixels; 0 = no edge fade
varying vec2 vUv;
void main() {
    float asp = uShapeAspect > 0.0 ? uShapeAspect : 86.0 / 59.0;
    vec2 p = (vUv - 0.5) * vec2(1.0, asp);
    vec2 half_ = vec2(0.5, 0.5 * asp) - 0.08;
    float d = length(max(abs(p) - half_, 0.0));
    float a = 0.55 * (1.0 - smoothstep(0.0, 0.08, d)) * (1.0 - uFade);
    // Fade out towards the view's edges, so a tilted card's shadow never ends in a hard clipped line.
    if (uViewSize.x > 0.0) {
        vec2 q = gl_FragCoord.xy / uViewSize;
        float m = min(min(q.x, 1.0 - q.x), min(q.y, 1.0 - q.y));
        a *= smoothstep(0.0, 0.09, m);
    }
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

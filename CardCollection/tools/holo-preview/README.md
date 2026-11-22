# Holo preview

Renders the app's foil shader (`app/src/main/res/raw/holo_core.agsl`) in headless Chromium/WebGL so the
look can be tuned without a phone. The same file runs in the app; this tool only adds a few `#define`s to
translate AGSL type names to GLSL.

```
node render.js ../../app/src/main/res/raw/holo_core.agsl out.png 300 spec.json '[[-0.7,-0.4],[0,0],[0.7,0.45]]'
```

`spec.json` lists cards to render:

```json
[{"image": "/path/card.jpg", "mode": 4, "label": "Secret Rare", "photo": 1, "art": [0.119, 0.18, 0.881, 0.705]}]
```

`mode` values are in `HoloStyle.kt`. `photo` is 1 for photos of real cards (TCGplayer), 0 for clean scans.
Needs Node with Playwright and Chromium.

## Checking the app's exact OpenGL shaders

The app renders with OpenGL ES 2.0 (`HoloGLView`). `HoloTest.writeGlShaders` writes the exact vertex and
fragment shaders it uses to `app/build/holo/`; `gl-check.js` compiles them (strict GLSL ES validation, like
phone drivers) and renders them through the same pipeline: shadow, perspective tilt, rounded corners.

```
./gradlew testDebugUnitTest --tests '*HoloTest*'
node gl-check.js ../../app/build/holo out.png spec.json
```

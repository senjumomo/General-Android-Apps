# Card Binder AR: pack opening on the Meta Quest

A web page you open in the **Meta Quest Browser**. Booster packs float in front of you through the
passthrough cameras; you pick one, tear it open by hand, and the cards come out one at a time. It does
**pack opening only** and is separate from the Android app (nothing here touches `CardCollection/`).

## Using it
1. Host the folder over HTTPS (WebXR needs it): turn on GitHub Pages for the repo (Settings → Pages →
   Deploy from a branch → pick the branch, folder `/ (root)`), then open
   `https://<user>.github.io/<repo>/PackVR/` in the Quest Browser.
2. Pick a set, tap **Enter AR**, and allow it. The packs appear about 60 cm in front of you at chest height.
3. **Pinch a pack** (or pull the controller trigger, aiming at it) to pick it. The set's top card appears
   on its wrapper.
4. **Pinch the top edge and drag across** to tear it. Rare and better cards flip by themselves with a
   build-up; Ultra Rares and better spin and burst.
5. **Grab a card** to turn it in your hand (the foil follows how you tilt it and where your head is).
   **Throw it away** (a flick, or carry it well off) for the next one. After the ninth you see everything
   you pulled and the pack's value, and can open another pack.

"Preview on screen" runs the same thing in a normal browser with the mouse as the hand.

## How it's built
- `index.html`, `js/main.js`: the scene (three.js, vendored in `vendor/`), hands/controllers, tearing,
  card flow, effects. `js/packs.js`: set/card data from YGOPRODeck and the pack generator, a port of the
  Android app's `PackData.kt` (same pull rates). `js/art.js`: the pack wrapper (port of `PackArt`) and labels.
- **The foil is the app's own shader.** `sync_from_app.py` copies the shader core and wrapper from
  `CardCollection/app/src/main/res/raw/holo_core.agsl` + `HoloShaders.kt` into `gen/holo_shaders.js`, and the
  sounds into `sounds/`. Run `python3 PackVR/sync_from_app.py` after changing either in the app; never edit
  `gen/` by hand. The foil's "tilt" comes from where a fixed room light reflects given the card's
  orientation and your head position (`updateTilt` in `js/shaders.js`).
- Card images come through **wsrv.nl** (a free image relay): YGOPRODeck's image server doesn't allow web
  pages to use its pictures in 3D (no CORS header) and the relay adds it. Card data, exchange rates and the
  card back come straight from their sources. No card art is stored in the repo.

## Testing without a headset
`node PackVR/tools/e2e.mjs` runs the flat preview in headless Chromium (mouse as the hand);
`node PackVR/tools/e2e-xr.mjs` (or `HANDS=1 node …` for bare-hand pinching, no controllers) runs an emulated Quest 3 (Meta's IWER, `tools/iwer.min.js`) through a real
`immersive-ar` session with a pointing, pinching controller. Both go through pick → tear → 9 cards → summary
and save screenshots. They need Playwright installed globally and use curl for the real network data.
What they can't check: how it feels on the headset (comfort, hand tracking, performance, haptics, sound).

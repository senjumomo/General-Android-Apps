# Card Binder AR: pack opening on the Meta Quest

A web page you open in the **Meta Quest Browser**. Booster packs float in front of you through the
passthrough cameras; you pick one, tear it open by hand, and the cards come out one at a time. It does
**pack opening only** and is separate from the Android app (nothing here touches `CardCollection/`).

## Using it
1. Host the folder over HTTPS (WebXR needs it): turn on GitHub Pages for the repo (Settings → Pages →
   Deploy from a branch → pick the branch, folder `/ (root)`), then open
   `https://<user>.github.io/<repo>/PackVR/` in the Quest Browser. (On a free GitHub plan, Pages only
   works while the repository is public.)
2. On the start page search for a set (name or code; its pack shows in the corner), choose **Graphics**
   (*Sharp* renders the headset view bigger; *Smooth* is lighter if it ever stutters), tap **Enter AR**
   and allow it. The packs appear about 55 cm in front of you, leaning back a little so they face you.
3. **The shelf**: five packs on an arc with their real artwork. **Newer / Older** page through every set;
   **Sets** (menu bar) jumps to a year. **Reach out and pinch a pack** with thumb and index finger to pick
   it (or point at it and pinch; with controllers, aim and pull the trigger).
4. **Pinch the top edge of the pack and drag across** to tear it. Let go early and it closes again.
   **Shelf** (menu bar) puts the pack back.
5. **The cards**: Rare and better flip by themselves after a glow and shake; Super and up get a rarity
   banner and sparkles, Ultra and up add spinning god rays. **Grab a card** to turn it in your hand (the
   foil follows how you tilt it and where your head is), **throw it away** for the next.
   **Previous** brings the last card back; **Skip** jumps to the summary.
6. **The summary**: all nine cards, the pack's value, your best pull and a count per rarity. Pinch any
   card to look closer (it grows in your hand and goes back when you let go). **Open another pack** gives a
   new pack of the same set straight away; **Back to shelf** goes back to the packs.
7. **The menu bar** is always at the bottom, tilted toward your eyes: Shelf (Back in the year picker),
   Previous, Skip, Sets, Recenter (moves everything in front of you again), Sound, Exit. Leaving a pack
   half-way and Exit ask "Sure?" first: press again within a few seconds.

"Preview on screen" runs the same thing in a normal browser with the mouse as the hand.

## How it's built
- `index.html` (start page), `js/main.js`: the scene (three.js, vendored in `vendor/`), hands/controllers,
  the screens (shelf, year picker, pack, cards, summary), tearing, effects. `js/packs.js`: set/card data
  from YGOPRODeck and the pack generator, a port of the Android app's `PackData.kt` (same pull rates).
  `js/art.js`: the pack wrapper (port of `PackArt`).
- **UI** (`js/ui.js`): every panel and button is a canvas drawn in millimetres at about 5400 pixels per
  metre with mipmaps and anisotropic filtering (the headset shows ~2600 at arm's length), so text stays
  sharp. Glass look, Material icons, hover glow and press feedback on every button. `tools/ui-sheet.html`
  is a contact sheet of all of it (open it through the dev server).
- **Atmosphere** (`js/fx.js`): a haze behind the stage, drifting motes, god rays, shockwave rings, glow
  behind cards, the tear guide and loading ring. On the headset it thins itself out if the frame rate drops.
- **The foil is the app's own shader.** `sync_from_app.py` copies the shader core and wrapper from
  `CardCollection/app/src/main/res/raw/holo_core.agsl` + `HoloShaders.kt` into `gen/holo_shaders.js`, and the
  sounds into `sounds/`. Run `python3 PackVR/sync_from_app.py` after changing either in the app; never edit
  `gen/` by hand. The foil's "tilt" comes from where a fixed room light reflects given the card's
  orientation and your head position (`updateTilt` in `js/shaders.js`). Interface blips are generated in
  `js/sound.js`.
- Card images come through **wsrv.nl** (a free image relay): YGOPRODeck's image server doesn't allow web
  pages to use its pictures in 3D (no CORS header) and the relay adds it. Card data, exchange rates and the
  card back come straight from their sources. No card art is stored in the repo.
- **After changing anything in `js/` or `gen/`, run `python3 PackVR/tools/stamp.py`.** GitHub Pages lets a
  browser keep a file for ten minutes, and this page is several modules; the script gives every module a new
  address (a hash of the code, in the import map in `index.html`) so a reload can't mix old and new.
- Icons are Material Design icons (Apache License 2.0).

## Testing without a headset
`node PackVR/tools/e2e.mjs` runs the flat preview in headless Chromium (mouse as the hand) through pick,
put back, tear, previous, skip, the summary, open another, the year picker and exit.
`node PackVR/tools/e2e-xr.mjs` (`HANDS=1` pointing and pinching with bare hands, `DIRECT=1` reaching out and
touching with bare hands, no controllers) runs an emulated Quest 3 (Meta's IWER, `tools/iwer.min.js`) through a
real `immersive-ar` session, including the menu bar buttons. `node PackVR/tools/shots-rare.mjs` forces a
line-up with big pulls and takes screenshots of the effects. All need Playwright installed globally, use
curl for the real network data (cached in the system temp folder), and pass `?fx=2` so the slow test machine doesn't
trigger the thinning-out (the emulated-hand runs also pass `&confirm=30`, because "Sure?" would time out).
What they can't check: how it feels on the headset (comfort, hand tracking, performance, haptics, sound).

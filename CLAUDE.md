# General Android Apps

Personal Android apps for one user (South Africa, prices in rand, phone on Android 17). Two independent
Gradle projects; each builds on its own.

| Folder | App | Package |
|---|---|---|
| `UtilitiesCalculator/` | **Utilities**: monthly water/electricity bill → message for the landlord, with history and trends | `com.senjumomo.utilities` |
| `CardCollection/` | **Card Binder**: Yu-Gi-Oh! collection tracker with holo card viewer and pack opening simulator | `com.senjumomo.cards` |

Each folder has a README with features and build/test commands. Read it before changing that app.

## Working with the user

- They install the **release APK** sideloaded on their phone. After every change: build `assembleRelease`,
  copy it to `<App>/apk/` (`UtilitiesCalculator.apk` / `CardBinder.apk`), commit, push, and send it.
  Release builds are signed with the debug key on purpose (installs straight over the previous version).
- They judge things visually and send phone screenshots. Before handing over a visual change, render it
  (Paparazzi snapshots, or the holo preview tools below) and look at it yourself.
- Plain-language summaries; be upfront about what couldn't be tested on a real device (camera, haptics,
  motion feel, performance).
- Development branch: `claude/utilities-calculator-android-orpmpn`. Push to `main` only when asked
  (it's been fast-forwarded to match the branch each time so far).

## Environment setup (cloud container)

No Android SDK is preinstalled. Install it (about a minute; dl.google.com is reachable):

```
mkdir -p /root/android-sdk/cmdline-tools && cd /root/android-sdk/cmdline-tools
curl -s -o clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q clt.zip && mv cmdline-tools latest && rm clt.zip
yes | latest/bin/sdkmanager --licenses >/dev/null
latest/bin/sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
echo "sdk.dir=/root/android-sdk" > <App>/local.properties   # local.properties is git-ignored
```

- Java 21 is installed; projects target JVM 17. Gradle wrapper 8.14.3, AGP 8.7.3, Kotlin 2.0.21.
- **Maven Central sometimes returns HTTP 429** (rate limit). Just retry the Gradle command after ~30 s.
- No KVM → no emulator. Visual checks use Paparazzi (JVM rendering) and headless Chromium/WebGL.
- Node + Playwright + Chromium are preinstalled (`NODE_PATH=$(npm root -g) node script.js`).

## Build and test

```
cd CardCollection        # or UtilitiesCalculator
./gradlew testDebugUnitTest verifyPaparazziDebug assembleRelease
./gradlew recordPaparazziDebug          # after intentional UI changes; then look at the PNGs
```

Snapshots live in `app/src/test/snapshots/images/` and are committed. `assembleRelease` runs lint;
lint errors fail the build (e.g. duplicate view ids inside one layout → use `<include>`).

Card Binder opt-in tests:
- `LIVE=1 ./gradlew testDebugUnitTest`: hits TCGplayer, YGOPRODeck, the exchange-rate API, Yugipedia.
- `CARD_EXPORT=/path/to/export.json`: checks the user's real collection export parses and round-trips
  (380 entries, 413 copies, R9,843.50 at the time). The export isn't in the repo; ask the user to
  re-upload it if needed.

---

## Utilities Calculator

Single-activity app (bottom nav: Calculate / History) plus SettingsActivity. Plain Views + Material 3,
dynamic colour, dark mode. Data in SharedPreferences (`utilities`), history stored as JSON.

- `Bill.kt`: the maths and the exact landlord message format (the user specified it; keep it exact):
  `Previous Month's Debt: R900` / `Water: 810kl - 800kl = 10kl x R70 = R700` /
  `Electricity: 10 tokens x R100 = R1000` / `Owing = R2600`.
- Prices (per kl, per token) live in Settings and show read-only on the Calculate page.
- Month selector on Calculate; "Start new month" advances it and moves the reading over.
- History tab: trend cards (bar chart of last 12 months + average/latest/lowest/highest) per measure.
  `Trends.kt` is pure and unit-tested; `BarChartView` follows the dataviz rules (≤24px bars, 4px rounded
  tops, hairline average line labelled in a gutter, value label only on the selected bar).

---

## Card Binder (CardCollection)

### Layout

- `data/`: Room DB (`Card` entity, migration 1→2 added `imageUrl`, `priceUpdatedAt`), JSON import/export
  (`CollectionJson`, the user's collection-app export format, all values strings), price refresh, image
  resolver, set-code OCR parsing, lookups.
- `net/`: `Http` (HttpURLConnection; headers must be set **before** writing a POST body), `Tcgplayer`
  (+ `TcgSearch`, `Exchange`), `Ygoprodeck`.
- `ui/`: main screen (tabs Collection / Wishlist / Packs / Stats; list/grid), read-only card detail,
  edit form, search-to-add, camera scan, charts.
- `holo/`: holographic card viewer (OpenGL ES 2.0).
- `packs/`: pack opening simulator.

### Data sources (no API keys)

- **YGOPRODeck API** (`db.ygoprodeck.com/api/v7`): card info, set lists, set card lists.
  `cardsetsinfo.php?setcode=X` returns the **artwork id of that printing** (alt arts resolve correctly);
  images at `images.ygoprodeck.com/images/cards/{id}.jpg` (813×1185), `cards_small` (268×391),
  `cards_cropped` (624×624 art). Limit is 20 req/s: `ImageResolver` paces to ~6/s and stores results.
  Cloudflare blocks default Python/urllib user agents; the app sends `CardBinder/2.0 (Android)`.
- **TCGplayer**: official API is closed to new developers, so the app uses the endpoints TCGplayer's own
  site uses (unofficial; may break): market price `mpapi.tcgplayer.com/v2/product/{id}/pricepoints`,
  search (POST) `mp-search-api.tcgplayer.com/v1/search/request` (by name or set code), images
  `tcgplayer-cdn.tcgplayer.com/product/{id}_200w.jpg` / `_in_1000x1000.jpg`.
  **Prices are per edition**: `pricepoints` blends editions (for old cards printed in 1st Edition and
  Unlimited it's the Unlimited price: Archlord Kristya SOVR-EN096 $99.99 vs $370 1st Ed). The app uses
  SKUs instead: `mp-search-api.tcgplayer.com/v2/product/{id}/details` lists skus (variant = edition,
  condition, language), `mpgateway.tcgplayer.com/v1/pricepoints/marketprice/skus/search` (POST
  `{"skuIds":[…]}`, batched) gives each SKU's market price. Edition from the rarity label, Near Mint,
  English (`Tcgplayer.pickSku`); single-edition products use their only edition; no SKU price →
  `pricepoints`. Matches Shiny and TCGplayer's "Near Mint Comparison Prices". Adds/scans re-price the
  saved card right after (`PriceRefresher.repriceSoon`), since search results carry the blended price.
  `infinite-api` (price history) and `latestsales` return 403 to the app: don't try to get around it.
  The user's export values = TCGplayer market price (USD) × USD→ZAR, verified to within a cent.
- **open.er-api.com**: USD exchange rates (cached 1 h).
- **Yugipedia**: official card back `img.yugipedia.com/e/e5/Back-EN.png` (927×1353), downloaded at
  runtime. The MediaWiki API (`yugipedia.com/api.php?...prop=imageinfo`) works; wiki pages return 403.

Images: YGOPRODeck scans everywhere (matched by set code, saved in `Card.imageUrl`; `""` = looked up,
not found), TCGplayer as fallback. **Never commit Konami card art or TCGplayer/YGOPRODeck images**
(tests use made-up cards; real art is only downloaded into `app/build/`).

### Scanning (`ScanActivity`)

- **Bundled** ML Kit text recognition (`com.google.mlkit:text-recognition`), not the Play-services one:
  the user's phone may lack Google Play services / the model download (scanner "detected nothing").
  APK is arm64-v8a only (`abiFilters`), else the OCR native libs make it ~50 MB (now ~20 MB).
- Analysis frames 1920×1080 (`ResolutionSelector`): at the default 640×480 a 1.5 mm set code is ~12 px
  tall, below OCR's ~16 px. Tap the preview to focus. Recognition errors are shown on screen; a live
  hint says whether text/codes are being seen. A code seen twice in the last 6 frames is confirmed.
- `SetCode` tolerates OCR slips: dash variants or a lost dash (only with a real region code), O/Q/D→0,
  I/L→1, Z→2, S→5, B→8 in the number (which must contain a real digit).

### Product decisions (from the user)

- Tapping a card opens a **read-only** detail screen; editing only via the Edit button.
- **Market value is never typed in**: it comes from TCGplayer (refresh all or per card). Cards without
  a TCGplayer link get "Find on TCGplayer" to link them. "Paid" stays editable.
- Add card = search-as-you-type against TCGplayer (ranked so real name matches come first), scan, or
  (fallback) manual entry.
- Wishlist cards don't count towards collection value.

### Holo viewer (`holo/`): the user loves this, especially Ultimate Rare emboss

- Rendered with **OpenGL ES 2.0** (`HoloGLView`), not AGSL RuntimeShader. The earlier RuntimeShader
  version silently fell back to one identical gradient on the user's Android 17 phone, so every
  rarity looked the same. Keep GL, and never add a silent fallback: errors are shown on screen.
- One shared shader core: `res/raw/holo_core.agsl` (AGSL type names; `HoloShaders` adds `#define`s for
  GLSL). `HoloShaders.fragment()` adds rounded corners, tear/fade uniforms (defaults = card).
- Rarity → foil mode in `HoloStyle` (keep in sync with the mode list at the top of the shader).
- Card layout constants (fractions of the card): artwork 0.119/0.180–0.881/0.705 (Pendulum
  0.067/0.178–0.933/0.629); name bar x 0.068–0.835, **lettering spans y 0.057–0.099**, plain margins
  0.045–0.055 and 0.099–0.105. Name foil detects letters as strokes on the text's side of their
  surroundings; text polarity (dark or light letters) = which way the lettering rows stray from the
  plain margins above/below them (y 0.052, 0.1015). Sampling only the centre line was a coin flip.
  Two contrast rings (far 0.012/0.008 for thick strokes, near 0.0055/0.0045 for squeezed long names,
  whose letters sit closer than the far ring) gated by brightness relative to bar vs. letter level. Don't sample a "background colour" (hits
  the bevel → whole bar turns metallic) and don't trim the mask inside 0.099 (cuts letter bottoms).
  Descenders (J tail) reach 0.108; the bar's bottom bevel starts at 0.1089 on every frame, so the mask
  ends at 0.1075. Card fills 1.72 of the GL view's height (not 1.84) and the shadow fades out near the
  view's edges (`uViewSize`), else a tilted card's shadow is clipped into a hard line.
- GLSL ES pitfalls: `pow(x, y)` with negative `x` is undefined (square instead); identifiers ending `_`
  are fine but `half` is reserved; loops need constant bounds.

Preview tools (see `CardCollection/tools/holo-preview/README.md`):
- `HoloTest.writeGlShaders` writes the exact GL shaders to `app/build/holo/`;
  `gl-check.js` compiles them in WebGL (strict GLSL ES, like phone drivers) and renders cards through
  the same pipeline (`VW=700` for bigger tiles). `render.js` renders the core quickly for tuning.
- Download real card images into the scratchpad to test (e.g. the user's cards by TCGplayer id or
  YGOPRODeck id), render several tilts, crop and inspect. Make GIFs with PIL for motion.

### Pack opening (`packs/`)

- Sets from YGOPRODeck (booster-like only: ≥30 cards, decks/promos excluded), cached on disk.
- Pack wrapper is generated (`PackArt`: featured top card art + crimped foil ends), since official
  pack images are low-res, sometimes boxes or Japanese. Rendered with the shared shader (`PackGLView`):
  swipe the top to tear (glowing tear line), the strip peels off sideways, the pack drops away.
- `PackGenerator`: 9 cards = 7 Commons + 1 Rare (or a Super in sets with no Rares) + 1 foil slot (Ultra
  1/6, Secret 1/12, QCSR/Prismatic/Platinum 1/24, Collector's 1/48, Starlight/Ghost 1/288); all-foil sets
  fill every slot from the foil distribution. Approximate community odds, covered by a 20k-pack test.
- Reveal: Commons face up; Rare+ land face down and flip by themselves after `AUTO_FLIP_MS` (user
  found tapping to reveal annoying; a tap still flips early), with a rarity-coloured glow and shake first;
  Ultra+ shown in `HoloGLView`. Revealed cards are swiped away either side (drag follows the
  finger, flick or drag past 28% throws it; on the holo view the same drag also tilts). Faces use 813×1185 scans; back is the official Yugipedia image
  (`CardBack.get()` draws a stand-in only while loading or offline).
- Simulator only: pulls are never added to the collection automatically (♡ adds to wishlist).
- Light effects (`PackFx`, plain Canvas, clock-driven so `HoloTest.packEffects` can freeze frames):
  `fxBack` behind everything (dim while cutting, rainbow rays + sparks from the tear line on rip,
  colour-flooded background for Ultra+), `fxFront` above the card (twinkles on landing, flare on
  Super+). Ultra+ spin 540° back→face (`spin`), rarity label pops in. "Hold" button fast-forwards
  (flips/throws every 110 ms) and stops at an Ultra+ so the big pull is never skipped.
  Inspired by the user's Pokémon TCG Pocket video: same kind of effects, none of its assets.
- **Vibration switch** next to mute (`Haptics.enabled`, prefs "packs"/"vibrate", default on).
- **Sounds** (`PackSounds`, SoundPool, mute remembered): synthesised WAVs in `res/raw/pack_*.wav` from
  `tools/sounds/make_sounds.py` (noise/filters/sine partials only; never add recorded/borrowed audio).
  Tear tick, rip + shimmer, whoosh (throw), flip, build-up (rate-matched to the shake), reveal chimes.
- **Pull history / binder** (simulator only): separate Room DB `pulls.db` (`PullDatabase`: `openings`,
  `pulls`) so the collection DB is never touched or migrated. Recorded when the pack rips
  (`PullRecorder`, outlives the screen). `PackExpectation` simulates 400 packs of the set for expected
  value and Ultra+/Secret+ counts per pack; `PullSummary.luck()` compares actual vs expected (Secret+ if
  ≥1 expected, else Ultra+, else value; needs ≥3 packs). Packs tab header (`view_pull_header`, first item
  of a ConcatAdapter, full span): stats, luck, best pulls (`ShineImageView`, tap → HoloViewer), set
  binders → `PullBinderActivity` (set list + pulls → `BinderEntry.build`; pulled in colour, missing grey).
  Set tiles show binder progress; the pack summary has a "Set binder" button.
- `ShineImageView`: foil gleam sweeping across pack/best-pull images; phase offset by screen position so
  it ripples and moves with scrolling; sleeps between sweeps; off when system animations are off.
- Paparazzi quirks: progress bars only draw progress set after attach; Coil placeholders are async (set
  the placeholder with `setImageResource` first or snapshots flake).

### Ideas not built yet (pack opening round 2)

Open a whole box, special reveals for Starlight/Ghost/QCSR, god packs, share a pull, "was it worth it"
(sealed pack price vs pulls), achievements.

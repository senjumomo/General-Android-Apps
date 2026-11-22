# Card Binder

Android app for keeping track of a Yu-Gi-Oh! card collection.

## Install
Download `apk/CardBinder.apk` to your phone and open it (allow "install unknown apps" when asked).

## Features
- **Import** a collection export (JSON list with `product_name`, `set_name`, `discriminator`, `rarity`,
  `quantity`, `value_per_unit`, `paid_per_unit`, `tcgplayer_id`, ...). Choose **Merge** (adds new cards,
  updates matching ones by id) or **Replace**.
- **Card images**: the exact printing from TCGplayer (by `tcgplayer_id`), falling back to YGOPRODeck by
  name. Cached on the phone. Tap the image on a card to view it full screen.
- **Holo view**: tap a card's image for a full-screen 3D card that tilts with the phone (gyroscope) or
  your finger, with a foil effect per rarity: silver/gold foil names, holo and Secret Rare line patterns,
  prismatic glitter, Ultimate Rare emboss, Ghost, Starlight and Collector's whole-card foil, and more.
  Runs per pixel on the GPU (`res/raw/holo_core.agsl`) on Android 13+, with a simpler sheen on older
  versions. Uses the TCGplayer photo of the exact printing (whose real foil texture lights up) or
  YGOPRODeck's clean high-res scan when the photo is low resolution. Preview tool: `tools/holo-preview`.
- **List or grid view** (toggle in the toolbar).
- **Price refresh** (menu): current TCGplayer market price × today's USD exchange rate (open.er-api.com).
- **Wishlist** tab: cards you want, with the cost to complete it. Wishlist cards don't count towards the
  collection value.
- **Stats** tab: value by set, value by rarity (tap a bar to filter the collection) and the most valuable cards.
- **Scan** (camera icon): reads the set code printed on the card (e.g. `MAMA-EN081`) with on-device text
  recognition, looks up the printing on TCGplayer (YGOPRODeck as fallback), then add it to the collection or
  wishlist. Adding a printing you already own bumps its quantity. You can also type the code.
- **Card screen** is read-only: image, value (with Refresh price), what you paid and gain, details.
  Editing only happens after tapping Edit; delete (with undo) lives there too. Wishlist cards get
  "Got it! Move to my collection".
- **Add card**: search TCGplayer's catalogue as you type (name or set code), pick the exact printing,
  then choose edition, quantity, condition and what you paid. Adding a printing you own bumps its quantity.
  Entering a card by hand is still possible as a fallback.
- **Value is never typed in**: it always comes from TCGplayer. Cards without a TCGplayer link get
  "Find on TCGplayer" to link them to a printing.
- **Search**, filter by set/rarity, sort by name/value/recent/set/quantity.
- **Export** back to the same JSON format as a backup.
- Material 3, Material You colours, dark mode. Data stored on the phone (Room database).

### Data sources
TCGplayer's official API is closed to new developers, so images, prices and the code lookup use the public
endpoints TCGplayer's own website uses. They work today but are unofficial and could change.

## Build
```
./gradlew assembleRelease
./gradlew testDebugUnitTest verifyPaparazziDebug   # unit + screenshot tests
CARD_EXPORT=/path/to/export.json ./gradlew testDebugUnitTest   # also check a real export parses
LIVE=1 ./gradlew testDebugUnitTest                               # also hit the real price/lookup services
```
Needs the Android SDK (set `sdk.dir` in `local.properties` or `ANDROID_HOME`).
Screenshots: `app/src/test/snapshots/images/`.

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
- **List or grid view** (toggle in the toolbar).
- **Price refresh** (menu): current TCGplayer market price × today's USD exchange rate (open.er-api.com).
- **Wishlist** tab: cards you want, with the cost to complete it. Wishlist cards don't count towards the
  collection value.
- **Stats** tab: value by set, value by rarity (tap a bar to filter the collection) and the most valuable cards.
- **Scan** (camera icon): reads the set code printed on the card (e.g. `MAMA-EN081`) with on-device text
  recognition, looks up the printing on TCGplayer (YGOPRODeck as fallback), then add it to the collection or
  wishlist. Adding a printing you already own bumps its quantity. You can also type the code.
- **Search**, filter by set/rarity, sort by name/value/recent/set/quantity.
- **Add / edit** cards by hand: quantity stepper, condition, value and paid price, TCGplayer link. Delete with undo.
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
